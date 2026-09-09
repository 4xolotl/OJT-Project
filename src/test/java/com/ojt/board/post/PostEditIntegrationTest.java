package com.ojt.board.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ojt.board.file.Attachment;
import com.ojt.board.file.AttachmentRepository;
import com.ojt.board.file.AttachmentService;
import com.ojt.board.file.LocalFileStorage;
import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.MockMvcPrint;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-edit;MODE=MariaDB;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class PostEditIntegrationTest {

    private static final Path STORAGE = createStorageDirectory();
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode("password123");
    private static final String CONFLICT = "게시글 또는 첨부파일이 변경되었습니다. 새로고침 후 다시 수정해 주세요.";

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PostRepository postRepository;
    @Autowired private PostService postService;
    @Autowired private AttachmentRepository attachmentRepository;
    @Autowired private AttachmentService attachmentService;
    @Autowired private UserRepository userRepository;
    private User author;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.location", STORAGE::toString);
    }

    @BeforeEach
    void cleanUpPreviousData() throws IOException {
        attachmentRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();
        cleanStorageFiles();
        author = userRepository.save(new User("editor@example.com", "작성자", PASSWORD_HASH));
    }

    @AfterAll
    static void cleanUpStorageDirectory() throws IOException {
        cleanStorageFiles();
        Files.deleteIfExists(STORAGE);
    }

    @Test
    void editsTextDeletesChosenFileAndUploadsNewBytesTogether() throws Exception {
        Fixture fixture = fixture(textFile("delete.txt"), textFile("keep.txt"));
        long deletedId = fixture.attachments().get(0).getId();
        long keptId = fixture.attachments().get(1).getId();
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", List.of(deletedId));
        byte[] bytes = "수정 첨부\n원문\u0000".getBytes(StandardCharsets.UTF_8);

        MvcResult result = mockMvc.perform(authenticated(editRequest(fixture.post().id(), body,
                        new MockMultipartFile("files", "수정 자료.txt", "text/plain", bytes))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("수정 제목"))
                .andExpect(jsonPath("$.content").value("수정 본문"))
                .andExpect(jsonPath("$.author.id").value(author.getId())).andReturn();

        assertNotEquals(fixture.post().updatedAt(), Instant.parse(responseJson(result).path("updatedAt").asText()));
        assertFalse(attachmentRepository.existsById(deletedId));
        assertTrue(attachmentRepository.existsById(keptId));
        List<Attachment> remaining = attachmentRepository.findByPostIdOrderByIdAsc(fixture.post().id());
        assertEquals(2, remaining.size());
        Attachment added = remaining.stream().filter(attachment -> !attachment.getId().equals(keptId)).findFirst().orElseThrow();
        assertEquals("수정 자료.txt", added.getOriginalFilename());
        mockMvc.perform(get("/api/files/{id}/download", deletedId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/files/{id}/download", keptId)).andExpect(status().isOk()).andExpect(content().bytes(new byte[]{1, 2, 3}));
        mockMvc.perform(get("/api/files/{id}/download", added.getId())).andExpect(status().isOk()).andExpect(content().bytes(bytes));
        assertEquals(2, storedFileCount());
    }

    @Test
    void canDeleteExistingFileWithoutUploadingReplacement() throws Exception {
        Fixture fixture = fixture(textFile("delete-only.txt"));
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", fixture.ids());

        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("수정 제목"));

        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void returnedTimestampCanBeUsedForImmediateNextEdit() throws Exception {
        Fixture fixture = fixture();
        JsonNode first = responseJson(mockMvc.perform(authenticated(editRequest(fixture.post().id(), editBody(fixture))))
                .andExpect(status().isOk()).andReturn());
        Map<String, Object> second = editBody(fixture);
        second.put("updatedAt", first.path("updatedAt").asText());
        second.put("title", "한 번 더 수정");

        mockMvc.perform(authenticated(editRequest(fixture.post().id(), second)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("한 번 더 수정"));
    }

    @Test
    void noOpAcceptsReorderedSnapshotWithoutChangingTimestamp() throws Exception {
        Fixture fixture = fixture(textFile("one.txt"), textFile("two.txt"));
        Map<String, Object> body = editBody(fixture);
        body.put("title", fixture.post().title());
        body.put("content", fixture.post().content());
        body.put("attachmentIds", fixture.ids().reversed());

        JsonNode response = responseJson(mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isOk()).andReturn());

        assertEquals(fixture.post().updatedAt(), Instant.parse(response.path("updatedAt").asText()));
        assertOriginalPreserved(fixture);
    }

    @Test
    void oldJsonUpdateRemainsAvailableWithoutSnapshotFields() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        mockMvc.perform(authenticated(put("/api/posts/{id}", fixture.post().id()).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new PostRequest("JSON 수정", "JSON 본문")))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.title").value("JSON 수정"));
        assertEquals(fixture.ids(), attachmentRepository.findByPostIdOrderByIdAsc(fixture.post().id()).stream().map(Attachment::getId).toList());
    }

    @Test
    void authenticatedUserCanEditTextAndFilesWithCurrentSnapshot() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        User other = userRepository.save(new User("other@example.com", "다른 회원", PASSWORD_HASH));
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", fixture.ids());
        mockMvc.perform(withCsrf(editRequest(fixture.post().id(), body, textFile("new.txt")).with(user(other))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("수정 제목"))
                .andExpect(jsonPath("$.content").value("수정 본문"))
                .andExpect(jsonPath("$.author.id").value(author.getId()));
        List<Attachment> attachments = attachmentRepository.findByPostIdOrderByIdAsc(fixture.post().id());
        assertEquals(1, attachments.size());
        assertEquals("new.txt", attachments.getFirst().getOriginalFilename());
        assertEquals(1, storedFileCount());
        assertFalse(attachmentRepository.existsById(fixture.ids().getFirst()));
    }

    @Test
    void anonymousWithCsrfCannotEdit() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        mockMvc.perform(withCsrf(editRequest(fixture.post().id(), editBody(fixture))))
                .andExpect(status().isUnauthorized());
        assertOriginalPreserved(fixture);
    }

    @Test
    void authenticatedSessionCanEditWithoutAdditionalHeader() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        mockMvc.perform(editRequest(fixture.post().id(), editBody(fixture)).with(user(author)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("수정 제목"));
        assertEquals(1, attachmentRepository.findByPostIdOrderByIdAsc(fixture.post().id()).size());
    }

    @Test
    void anotherTabTextEditReturnsConflictAndKeepsItsNewText() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        postService.update(fixture.post().id(), author.getId(), new PostRequest("다른 탭 제목", "다른 탭 본문"));
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), editBody(fixture), textFile("new.txt"))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(CONFLICT));
        assertEquals("다른 탭 제목", postService.get(fixture.post().id()).title());
        assertEquals(fixture.ids(), attachmentRepository.findByPostIdOrderByIdAsc(fixture.post().id()).stream().map(Attachment::getId).toList());
        assertEquals(1, storedFileCount());
    }

    @Test
    void evenOneNanosecondTimestampDifferenceIsAConflict() throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> body = editBody(fixture);
        body.put("updatedAt", fixture.post().updatedAt().plusNanos(1));
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(CONFLICT));
        assertOriginalPreserved(fixture);
    }

    @Test
    void anotherTabFileAdditionReturnsConflictEvenWhenPostTimeIsUnchanged() throws Exception {
        Fixture fixture = fixture(textFile("original.txt"));
        attachmentService.upload(fixture.post().id(), author.getId(), List.of(textFile("other-tab.txt")));
        assertEquals(fixture.post().updatedAt(), postService.get(fixture.post().id()).updatedAt());

        mockMvc.perform(authenticated(editRequest(fixture.post().id(), editBody(fixture))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(CONFLICT));
        assertEquals(fixture.post().title(), postService.get(fixture.post().id()).title());
        assertEquals(2, attachmentRepository.count());
        assertEquals(2, storedFileCount());
    }

    @Test
    void anotherTabFileDeletionReturnsConflictInsteadOfDeletingAgain() throws Exception {
        Fixture fixture = fixture(textFile("other-tab-deleted.txt"));
        attachmentService.delete(fixture.ids().getFirst(), author.getId());
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", fixture.ids());

        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(CONFLICT));
        assertEquals(fixture.post().title(), postService.get(fixture.post().id()).title());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void incompleteAttachmentSnapshotReturnsConflict() throws Exception {
        Fixture fixture = fixture(textFile("not-listed.txt"));
        Map<String, Object> body = editBody(fixture);
        body.put("attachmentIds", List.of());
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.message").value(CONFLICT));
        assertOriginalPreserved(fixture);
    }

    @Test
    void cannotDeleteAnAttachmentBelongingToAnotherPost() throws Exception {
        Fixture fixture = fixture(textFile("mine.txt"));
        Fixture other = fixture(textFile("other-post.txt"));
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", other.ids());
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isBadRequest());
        assertEquals(fixture.post().title(), postService.get(fixture.post().id()).title());
        assertTrue(attachmentRepository.existsById(other.ids().getFirst()));
        assertEquals(2, attachmentRepository.count());
        assertEquals(2, storedFileCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"attachmentIds:null", "attachmentIds:duplicate", "attachmentIds:zero", "attachmentIds:negative",
            "attachmentIds:nullElement", "attachmentIds:tooMany", "deletedFileIds:null", "deletedFileIds:duplicate",
            "deletedFileIds:zero", "deletedFileIds:negative", "deletedFileIds:nullElement", "deletedFileIds:tooMany"})
    void malformedIdListsReturnBadRequestWithoutMutations(String scenario) throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        Map<String, Object> body = editBody(fixture);
        String[] parts = scenario.split(":");
        Object invalid = switch (parts[1]) {
            case "null" -> null;
            case "duplicate" -> List.of(fixture.ids().getFirst(), fixture.ids().getFirst());
            case "zero" -> List.of(0L);
            case "negative" -> List.of(-1L);
            case "nullElement" -> Arrays.asList((Long) null);
            default -> LongStream.rangeClosed(1, 1001).boxed().toList();
        };
        body.put(parts[0], invalid);
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isBadRequest());
        assertOriginalPreserved(fixture);
    }

    @ParameterizedTest
    @ValueSource(strings = {"missingTime", "nullTime", "badTime", "emptyTitle", "longTitle", "emptyContent", "longContent"})
    void invalidTextOrSnapshotTimeReturnsBadRequest(String scenario) throws Exception {
        Fixture fixture = fixture();
        Map<String, Object> body = editBody(fixture);
        switch (scenario) {
            case "missingTime" -> body.remove("updatedAt");
            case "nullTime" -> body.put("updatedAt", null);
            case "badTime" -> body.put("updatedAt", "not-a-timestamp");
            case "emptyTitle" -> body.put("title", " ");
            case "longTitle" -> body.put("title", "t".repeat(201));
            case "emptyContent" -> body.put("content", "\n\t");
            default -> body.put("content", "c".repeat(10001));
        }
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body)))
                .andExpect(status().isBadRequest());
        assertOriginalPreserved(fixture);
    }

    @Test
    void missingPostReturnsNotFound() throws Exception {
        Fixture fixture = fixture();
        mockMvc.perform(authenticated(editRequest(Long.MAX_VALUE, editBody(fixture))))
                .andExpect(status().isNotFound());
        assertOriginalPreserved(fixture);
    }

    @Test
    void sixNewFilesDoNotDeleteExistingFiles() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", fixture.ids());
        MockMultipartFile[] files = new MockMultipartFile[6];
        Arrays.setAll(files, index -> textFile("new-" + index + ".txt"));
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body, files))).andExpect(status().isBadRequest());
        assertOriginalPreserved(fixture);
    }

    @Test
    void emptyNewFileDoesNotDeleteExistingFiles() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", fixture.ids());
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body,
                        new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0]))))
                .andExpect(status().isBadRequest());
        assertOriginalPreserved(fixture);
    }

    @Test
    void oversizedNewFileDoesNotDeleteExistingFiles() throws Exception {
        Fixture fixture = fixture(textFile("keep.txt"));
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", fixture.ids());
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body,
                        new MockMultipartFile("files", "large.bin", "application/octet-stream",
                                new byte[Math.toIntExact(LocalFileStorage.MAX_FILE_SIZE + 1)]))))
                .andExpect(status().isPayloadTooLarge());
        assertOriginalPreserved(fixture);
    }

    @Test
    void existingFilesDoNotReduceTheFiveNewFilesPerRequestAllowance() throws Exception {
        MockMultipartFile[] files = new MockMultipartFile[5];
        Arrays.setAll(files, index -> textFile("file-" + index + ".txt"));
        Fixture fixture = fixture(files);
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), editBody(fixture), files)))
                .andExpect(status().isOk());
        assertEquals(10, attachmentRepository.count());
        assertEquals(10, storedFileCount());
    }

    @Test
    void secondUploadIoFailureRestoresDeletedMetadataAndRemovesNewPhysicalFiles() throws Exception {
        Fixture fixture = fixture(textFile("original.txt"));
        Attachment original = fixture.attachments().getFirst();
        Map<String, Object> body = editBody(fixture);
        body.put("deletedFileIds", fixture.ids());
        AtomicBoolean failureReached = new AtomicBoolean();
        MockMultipartFile failing = new MockMultipartFile("files", "second.txt", "text/plain", new byte[]{9}) {
            @Override
            public InputStream getInputStream() throws IOException {
                // A query flushes pending metadata deletion, while the original physical file must still exist.
                assertFalse(attachmentRepository.existsById(original.getId()));
                assertTrue(Files.exists(STORAGE.resolve(original.getStoredFilename())));
                assertEquals(3, storedFileCount());
                failureReached.set(true);
                throw new IOException("Simulated second edit upload failure");
            }
        };
        mockMvc.perform(authenticated(editRequest(fixture.post().id(), body, textFile("first.txt"), failing)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("파일 처리 중 오류가 발생했습니다."));
        assertTrue(failureReached.get());
        assertOriginalPreserved(fixture);
    }

    @Test
    void swaggerKeepsJsonAndMultipartEditSchemasSeparateAndDeclaresJsonPartEncoding() throws Exception {
        JsonNode doc = responseJson(mockMvc.perform(get("/v3/api-docs")).andExpect(status().isOk()).andReturn());
        JsonNode operation = doc.path("paths").path("/api/posts/{postId}").path("put");
        assertTrue(operation.path("responses").has("200"));
        JsonNode contentTypes = operation.path("requestBody").path("content");
        JsonNode jsonSchema = resolveSchema(doc, contentTypes.path("application/json").path("schema"));
        assertEquals(Set.of("title", "content"), propertyNames(jsonSchema), jsonSchema.toString());
        JsonNode multipartContent = contentTypes.path("multipart/form-data");
        assertEquals("application/json", multipartContent.path("encoding").path("post").path("contentType").asText());
        JsonNode multipartSchema = resolveSchema(doc, multipartContent.path("schema"));
        assertEquals(Set.of("post", "files"), propertyNames(multipartSchema));
        assertEquals(objectMapper.valueToTree(List.of("post")), multipartSchema.path("required"));
        JsonNode editSchema = resolveSchema(doc, multipartSchema.path("properties").path("post"));
        Set<String> fields = Set.of("title", "content", "updatedAt", "attachmentIds", "deletedFileIds");
        assertEquals(fields, propertyNames(editSchema));
        Set<String> required = new HashSet<>();
        editSchema.path("required").forEach(field -> required.add(field.asText()));
        assertEquals(fields, required);
        assertEquals("date-time", editSchema.path("properties").path("updatedAt").path("format").asText());
        for (String name : List.of("attachmentIds", "deletedFileIds")) {
            JsonNode ids = editSchema.path("properties").path(name);
            assertEquals("array", ids.path("type").asText());
            assertEquals(1000, ids.path("maxItems").asInt());
            assertEquals("int64", ids.path("items").path("format").asText());
        }
        JsonNode files = multipartSchema.path("properties").path("files");
        assertEquals("array", files.path("type").asText());
        assertEquals("binary", files.path("items").path("format").asText());
    }

    private Fixture fixture(MockMultipartFile... files) {
        PostResponse created = postService.createWithFiles(author.getId(), new PostRequest("원래 제목", "원래 본문"), List.of(files));
        return new Fixture(postService.get(created.id()), attachmentRepository.findByPostIdOrderByIdAsc(created.id()));
    }

    private Map<String, Object> editBody(Fixture fixture) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "수정 제목");
        body.put("content", "수정 본문");
        body.put("updatedAt", fixture.post().updatedAt());
        body.put("attachmentIds", fixture.ids());
        body.put("deletedFileIds", List.of());
        return body;
    }

    private MockMultipartHttpServletRequestBuilder editRequest(long postId, Object body, MockMultipartFile... files) throws IOException {
        MockMultipartHttpServletRequestBuilder request = multipart(HttpMethod.PUT, "/api/posts/{id}", postId)
                .file(new MockMultipartFile("post", "post.json", MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(body)));
        for (MockMultipartFile file : files) request.file(file);
        return request;
    }

    private MockMultipartFile textFile(String filename) {
        return new MockMultipartFile("files", filename, "text/plain", new byte[]{1, 2, 3});
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) throws Exception {
        return withCsrf(request.with(user(author)));
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult csrf = mockMvc.perform(get("/api/auth/csrf")).andExpect(status().isOk()).andReturn();
        Cookie cookie = csrf.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        JsonNode body = responseJson(csrf);
        return request.cookie(cookie).header(body.path("headerName").asText(), body.path("token").asText());
    }

    private JsonNode responseJson(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private JsonNode resolveSchema(JsonNode document, JsonNode schema) {
        while (schema.has("$ref")) schema = document.at(schema.path("$ref").asText().substring(1));
        return schema;
    }

    private Set<String> propertyNames(JsonNode schema) {
        Set<String> fields = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(fields::add);
        return fields;
    }

    private void assertOriginalPreserved(Fixture fixture) throws Exception {
        PostResponse current = postService.get(fixture.post().id());
        assertEquals(fixture.post().title(), current.title());
        assertEquals(fixture.post().content(), current.content());
        assertEquals(fixture.post().updatedAt(), current.updatedAt());
        assertEquals(fixture.ids(), attachmentRepository.findByPostIdOrderByIdAsc(fixture.post().id()).stream().map(Attachment::getId).toList());
        assertEquals(fixture.attachments().size(), attachmentRepository.count());
        assertEquals(fixture.attachments().size(), storedFileCount());
        for (Attachment attachment : fixture.attachments()) {
            mockMvc.perform(get("/api/files/{id}/download", attachment.getId())).andExpect(status().isOk())
                    .andExpect(content().bytes(new byte[]{1, 2, 3}));
        }
    }

    private long storedFileCount() throws IOException {
        try (Stream<Path> paths = Files.list(STORAGE)) { return paths.count(); }
    }

    private static Path createStorageDirectory() {
        try { return Files.createTempDirectory("post-edit-test-"); }
        catch (IOException exception) { throw new IllegalStateException("Cannot create isolated test storage", exception); }
    }

    private static void cleanStorageFiles() throws IOException {
        if (!Files.exists(STORAGE)) return;
        Path target = STORAGE.toRealPath();
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!target.startsWith(tempRoot) || !target.getFileName().toString().startsWith("post-edit-test-")) {
            throw new IOException("Unexpected test storage directory");
        }
        try (Stream<Path> paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(STORAGE)) Files.deleteIfExists(path);
            }
        }
    }

    private record Fixture(PostResponse post, List<Attachment> attachments) {
        List<Long> ids() { return attachments.stream().map(Attachment::getId).toList(); }
    }
}
