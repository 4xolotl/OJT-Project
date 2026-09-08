package com.ojt.board.post;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ojt.board.file.Attachment;
import com.ojt.board.file.AttachmentRepository;
import com.ojt.board.file.LocalFileStorage;
import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
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
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
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

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:post-multipart;MODE=MariaDB;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc(print = MockMvcPrint.NONE)
@ActiveProfiles("test")
class PostMultipartIntegrationTest {

    private static final Path STORAGE = createStorageDirectory();
    private static final String PASSWORD_HASH = new BCryptPasswordEncoder().encode("password123");

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private PostRepository postRepository;
    @Autowired private PostService postService;
    @Autowired private AttachmentRepository attachmentRepository;
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
        author = userRepository.save(new User("multipart@example.com", "작성자", PASSWORD_HASH));
    }

    @AfterAll
    static void cleanUpStorageDirectory() throws IOException {
        cleanStorageFiles();
        Files.deleteIfExists(STORAGE);
    }

    @Test
    void createsPostAndDownloadsKoreanFilenameWithOriginalBytes() throws Exception {
        byte[] bytes = "한글 첨부파일\n원본 내용\u0000".getBytes(StandardCharsets.UTF_8);
        MvcResult result = mockMvc.perform(authenticated(createRequest(validPost(),
                        new MockMultipartFile("files", "개발 계획.txt", "text/plain", bytes))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("작성 제목"))
                .andExpect(jsonPath("$.content").value("작성 본문"))
                .andExpect(jsonPath("$.author.id").value(author.getId()))
                .andExpect(jsonPath("$.author.email").doesNotExist())
                .andExpect(jsonPath("$.author.password").doesNotExist())
                .andReturn();

        long postId = responseJson(result).path("id").asLong();
        assertEquals(1, postRepository.count());
        List<Attachment> attachments = attachmentRepository.findByPostIdOrderByIdAsc(postId);
        assertEquals(1, attachments.size());
        Attachment attachment = attachments.get(0);
        assertEquals("개발 계획.txt", attachment.getOriginalFilename());
        assertEquals(bytes.length, attachment.getSize());
        assertEquals(1, storedFileCount());
        MvcResult download = mockMvc.perform(get("/api/files/{id}/download", attachment.getId()))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_OCTET_STREAM))
                .andExpect(content().bytes(bytes)).andReturn();
        ContentDisposition disposition = ContentDisposition.parse(
                download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("attachment", disposition.getType());
        assertEquals("개발 계획.txt", disposition.getFilename());
    }

    @Test
    void createsPostWhenFilesPartIsAbsent() throws Exception {
        mockMvc.perform(authenticated(createRequest(validPost())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isNumber());
        assertEquals(1, postRepository.count());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void emptyFileListCreatesOnlyPost() throws Exception {
        PostResponse response = postService.createWithFiles(author.getId(), validPost(), List.of());
        assertTrue(postRepository.existsById(response.id()));
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void existingJsonCreateContractStillWorks() throws Exception {
        mockMvc.perform(authenticated(post("/api/posts").contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(validPost()))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.title").value("작성 제목"));
        assertEquals(1, postRepository.count());
        assertEquals(0, attachmentRepository.count());
    }

    @Test
    void anonymousMultipartWithValidCsrfCannotCreatePostOrFiles() throws Exception {
        mockMvc.perform(withCsrf(createRequest(validPost(), textFile("valid.txt"))))
                .andExpect(status().isUnauthorized());
        assertNothingStored();
    }

    @Test
    void authenticatedMultipartWithoutCsrfCannotCreatePostOrFiles() throws Exception {
        mockMvc.perform(createRequest(validPost(), textFile("valid.txt")).with(user(author)))
                .andExpect(status().isForbidden());
        assertNothingStored();
    }

    @ParameterizedTest
    @ValueSource(strings = {"emptyTitle", "longTitle", "emptyContent", "longContent"})
    void invalidPostPartCannotLeaveFilesOrPostBehind(String invalidField) throws Exception {
        String title = switch (invalidField) {
            case "emptyTitle" -> " ";
            case "longTitle" -> "제".repeat(201);
            default -> "작성 제목";
        };
        String body = switch (invalidField) {
            case "emptyContent" -> "\n\t";
            case "longContent" -> "본".repeat(10001);
            default -> "작성 본문";
        };
        mockMvc.perform(authenticated(createRequest(new PostRequest(title, body), textFile("valid.txt"))))
                .andExpect(status().isBadRequest());
        assertNothingStored();
    }

    @Test
    void malformedPostJsonCannotLeaveFilesOrPostBehind() throws Exception {
        MockMultipartFile invalidJson = new MockMultipartFile("post", "post.json",
                MediaType.APPLICATION_JSON_VALUE, "{broken".getBytes(StandardCharsets.UTF_8));
        mockMvc.perform(authenticated(multipart("/api/posts").file(invalidJson).file(textFile("valid.txt"))))
                .andExpect(status().isBadRequest());
        assertNothingStored();
    }

    @Test
    void missingPostPartCannotLeaveFilesOrPostBehind() throws Exception {
        mockMvc.perform(authenticated(multipart("/api/posts").file(textFile("valid.txt"))))
                .andExpect(status().isBadRequest());
        assertNothingStored();
    }

    @Test
    void postPartMustDeclareJsonContentType() throws Exception {
        MockMultipartFile textPost = new MockMultipartFile("post", "post.txt", MediaType.TEXT_PLAIN_VALUE,
                objectMapper.writeValueAsBytes(validPost()));
        mockMvc.perform(authenticated(multipart("/api/posts").file(textPost).file(textFile("valid.txt"))))
                .andExpect(status().isUnsupportedMediaType());
        assertNothingStored();
    }

    @Test
    void emptyFileRollsBackNewPost() throws Exception {
        mockMvc.perform(authenticated(createRequest(validPost(), textFile("valid.txt"),
                        new MockMultipartFile("files", "empty.txt", "text/plain", new byte[0]))))
                .andExpect(status().isBadRequest());
        assertNothingStored();
    }

    @Test
    void sixFilesRollBackNewPost() throws Exception {
        MockMultipartFile[] files = new MockMultipartFile[6];
        for (int i = 0; i < files.length; i++) {
            files[i] = textFile("file-" + i + ".txt");
        }
        mockMvc.perform(authenticated(createRequest(validPost(), files)))
                .andExpect(status().isBadRequest());
        assertNothingStored();
    }

    @Test
    void oversizedFileRollsBackNewPost() throws Exception {
        MockMultipartFile oversized = new MockMultipartFile("files", "large.bin", "application/octet-stream",
                new byte[Math.toIntExact(LocalFileStorage.MAX_FILE_SIZE + 1)]);
        mockMvc.perform(authenticated(createRequest(validPost(), textFile("valid.txt"), oversized)))
                .andExpect(status().isPayloadTooLarge());
        assertNothingStored();
    }

    @Test
    void acceptsFiveFilesAndExactIndividualSizeLimit() throws Exception {
        MockMultipartFile[] files = new MockMultipartFile[5];
        files[0] = new MockMultipartFile("files", "limit.bin", "application/octet-stream",
                new byte[Math.toIntExact(LocalFileStorage.MAX_FILE_SIZE)]);
        for (int i = 1; i < files.length; i++) {
            files[i] = textFile("file-" + i + ".txt");
        }
        mockMvc.perform(authenticated(createRequest(validPost(), files))).andExpect(status().isCreated());
        assertEquals(1, postRepository.count());
        assertEquals(5, attachmentRepository.count());
        assertEquals(5, storedFileCount());
    }

    @Test
    void secondFileIoFailureRollsBackPostAndRemovesBothStoredFiles() throws Exception {
        AtomicBoolean secondStreamAttempted = new AtomicBoolean();
        MockMultipartFile failingFile = new MockMultipartFile("files", "second.txt", "text/plain", new byte[]{9}) {
            @Override
            public InputStream getInputStream() throws IOException {
                // The first upload exists, and storage has created the second output file before opening its input.
                assertEquals(2, storedFileCount());
                secondStreamAttempted.set(true);
                throw new IOException("Simulated second upload stream failure");
            }
        };
        mockMvc.perform(authenticated(createRequest(validPost(), textFile("first.txt"), failingFile)))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.message").value("파일 처리 중 오류가 발생했습니다."));
        assertTrue(secondStreamAttempted.get());
        assertNothingStored();
    }

    @Test
    void swaggerDocumentsBothCreateContentTypes() throws Exception {
        MvcResult result = mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/posts'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/posts'].post.requestBody.content['application/json']").exists())
                .andExpect(jsonPath("$.paths['/api/posts'].post.requestBody.content['multipart/form-data']").exists())
                .andReturn();

        JsonNode document = responseJson(result);
        JsonNode requestBody = document.path("paths").path("/api/posts").path("post").path("requestBody");
        assertTrue(requestBody.path("required").asBoolean());
        JsonNode contentTypes = requestBody.path("content");
        assertPostRequestSchema(document, contentTypes.path("application/json").path("schema"));
        assertEquals(MediaType.APPLICATION_JSON_VALUE,
                contentTypes.path("multipart/form-data").path("encoding").path("post").path("contentType").asText());

        JsonNode multipartSchema = resolveSchema(document,
                contentTypes.path("multipart/form-data").path("schema"));
        assertEquals("object", multipartSchema.path("type").asText(), multipartSchema.toString());
        assertEquals(Set.of("post", "files"), propertyNames(multipartSchema), multipartSchema.toString());
        assertEquals(objectMapper.valueToTree(List.of("post")), multipartSchema.path("required"));
        assertPostRequestSchema(document, multipartSchema.path("properties").path("post"));
        JsonNode files = resolveSchema(document, multipartSchema.path("properties").path("files"));
        assertEquals("array", files.path("type").asText());
        JsonNode file = resolveSchema(document, files.path("items"));
        assertEquals("string", file.path("type").asText());
        assertEquals("binary", file.path("format").asText());
    }

    private void assertPostRequestSchema(JsonNode document, JsonNode schema) {
        JsonNode resolved = resolveSchema(document, schema);
        assertEquals("object", resolved.path("type").asText(), resolved.toString());
        assertEquals(Set.of("title", "content"), propertyNames(resolved), resolved.toString());
        Set<String> required = new HashSet<>();
        resolved.path("required").forEach(field -> required.add(field.asText()));
        assertEquals(Set.of("title", "content"), required);
        assertEquals("string", resolved.path("properties").path("title").path("type").asText());
        assertEquals(200, resolved.path("properties").path("title").path("maxLength").asInt());
        assertEquals("string", resolved.path("properties").path("content").path("type").asText());
        assertEquals(10000, resolved.path("properties").path("content").path("maxLength").asInt());
    }

    private JsonNode resolveSchema(JsonNode document, JsonNode schema) {
        while (schema.has("$ref")) {
            String reference = schema.path("$ref").asText();
            assertTrue(reference.startsWith("#/components/schemas/"), reference);
            schema = document.at(reference.substring(1));
        }
        return schema;
    }

    private Set<String> propertyNames(JsonNode schema) {
        Set<String> properties = new HashSet<>();
        schema.path("properties").fieldNames().forEachRemaining(properties::add);
        return properties;
    }

    private PostRequest validPost() {
        return new PostRequest("작성 제목", "작성 본문");
    }

    private MockMultipartFile textFile(String filename) {
        return new MockMultipartFile("files", filename, "text/plain", new byte[]{1, 2, 3});
    }

    private MockMultipartHttpServletRequestBuilder createRequest(PostRequest request, MockMultipartFile... files)
            throws IOException {
        MockMultipartHttpServletRequestBuilder builder = multipart("/api/posts").file(new MockMultipartFile(
                "post", "post.json", MediaType.APPLICATION_JSON_VALUE, objectMapper.writeValueAsBytes(request)));
        for (MockMultipartFile file : files) {
            builder.file(file);
        }
        return builder;
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

    private void assertNothingStored() throws IOException {
        assertEquals(0, postRepository.count());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    private long storedFileCount() throws IOException {
        try (Stream<Path> paths = Files.list(STORAGE)) {
            return paths.count();
        }
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("post-multipart-test-");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create isolated test storage", exception);
        }
    }

    private static void cleanStorageFiles() throws IOException {
        if (!Files.exists(STORAGE)) {
            return;
        }
        Path target = STORAGE.toRealPath();
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!target.startsWith(tempRoot) || !target.getFileName().toString().startsWith("post-multipart-test-")) {
            throw new IOException("Unexpected test storage directory");
        }
        try (Stream<Path> paths = Files.walk(STORAGE)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                if (!path.equals(STORAGE)) {
                    Files.deleteIfExists(path);
                }
            }
        }
    }
}
