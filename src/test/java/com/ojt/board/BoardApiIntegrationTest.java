package com.ojt.board;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ojt.board.auth.AuthRequest;
import com.ojt.board.comment.CommentRepository;
import com.ojt.board.file.AttachmentRepository;
import com.ojt.board.post.PostRepository;
import com.ojt.board.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:board-api;MODE=MariaDB;DB_CLOSE_DELAY=-1"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BoardApiIntegrationTest {

    private static final String PASSWORD = "password123";
    private static final Path STORAGE = createStorageDirectory();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private AttachmentRepository attachmentRepository;
    @Autowired
    private CommentRepository commentRepository;
    @Autowired
    private PostRepository postRepository;
    @Autowired
    private UserRepository userRepository;

    @DynamicPropertySource
    static void storageProperties(DynamicPropertyRegistry registry) {
        registry.add("app.storage.location", STORAGE::toString);
    }

    @BeforeEach
    void cleanUpPreviousData() throws IOException {
        attachmentRepository.deleteAll();
        commentRepository.deleteAll();
        postRepository.deleteAll();
        userRepository.deleteAll();
        cleanStorageFiles();
    }

    @AfterAll
    static void cleanUpStorageDirectory() throws IOException {
        cleanStorageFiles();
        Files.deleteIfExists(STORAGE);
    }

    @Test
    void swaggerDocumentsAllBoardOperationsAndMultipartUpload() throws Exception {
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paths['/api/posts'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/posts/{postId}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/posts/{postId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/posts/{postId}/comments'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/posts/{postId}/comments/{commentId}'].put").exists())
                .andExpect(jsonPath("$.paths['/api/posts/{postId}/comments/{commentId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.paths['/api/posts/{postId}/files'].post.requestBody.content['multipart/form-data']").exists())
                .andExpect(jsonPath("$.paths['/api/files/{fileId}/download'].get").exists())
                .andExpect(jsonPath("$.paths['/api/files/{fileId}'].delete.responses['204']").exists())
                .andExpect(jsonPath("$.components.schemas.User").doesNotExist());
    }

    @Test
    void postLifecycleIsPubliclyReadableAndKeepsAuthorDetailsSafe() throws Exception {
        Actor author = registerAndLogin("author");
        JsonNode created = createPost(author, "First post", "First content");
        long postId = created.path("id").asLong();
        assertEquals(author.id(), created.path("author").path("id").asLong());
        assertEquals("author", created.path("author").path("nickname").asText());

        mockMvc.perform(get("/api/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("First post"))
                .andExpect(jsonPath("$.content").value("First content"))
                .andExpect(jsonPath("$.createdAt").isNotEmpty())
                .andExpect(jsonPath("$.updatedAt").isNotEmpty())
                .andExpect(jsonPath("$.author.email").doesNotExist())
                .andExpect(jsonPath("$.author.password").doesNotExist());
        mockMvc.perform(json(put("/api/posts/{id}", postId), author,
                        Map.of("title", "Edited title", "content", "Edited content")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Edited title"))
                .andExpect(jsonPath("$.content").value("Edited content"))
                .andExpect(jsonPath("$.author.id").value(author.id()));
        mockMvc.perform(authenticate(delete("/api/posts/{id}", postId), author))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/posts/{id}", postId))
                .andExpect(status().isNotFound());
    }

    @Test
    void anotherUserCannotEditOrDeletePost() throws Exception {
        Actor author = registerAndLogin("author");
        Actor other = registerAndLogin("other");
        long postId = createPost(author, "Original", "Original content").path("id").asLong();

        mockMvc.perform(json(put("/api/posts/{id}", postId), other,
                        Map.of("title", "Stolen", "content", "Changed")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticate(delete("/api/posts/{id}", postId), other))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/posts/{id}", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Original"));
    }

    @Test
    void anonymousWritesWithValidCsrfReturnUnauthorized() throws Exception {
        Fixture fixture = createFixture();
        CsrfData csrf = fetchCsrf(null);
        for (MockHttpServletRequestBuilder request : writeRequests(fixture)) {
            mockMvc.perform(withCsrf(request, csrf))
                    .andExpect(status().isUnauthorized());
        }
        assertEquals(1, postRepository.count());
        assertEquals(1, commentRepository.count());
        assertEquals(1, attachmentRepository.count());
    }

    @Test
    void authenticatedWritesWithoutCsrfAreRejectedBeforeChangingData() throws Exception {
        Fixture fixture = createFixture();
        for (MockHttpServletRequestBuilder request : writeRequests(fixture)) {
            mockMvc.perform(request.session(fixture.owner().session()))
                    .andExpect(status().isForbidden());
        }
        assertEquals(1, postRepository.count());
        assertEquals(1, commentRepository.count());
        assertEquals(1, attachmentRepository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"emptyTitle", "longTitle", "emptyContent", "longContent"})
    void invalidPostFieldsReturnBadRequest(String invalidField) throws Exception {
        Actor author = registerAndLogin("author");
        String title = switch (invalidField) {
            case "emptyTitle" -> " ";
            case "longTitle" -> "t".repeat(201);
            default -> "Valid title";
        };
        String body = switch (invalidField) {
            case "emptyContent" -> " ";
            case "longContent" -> "c".repeat(10001);
            default -> "Valid content";
        };
        mockMvc.perform(json(post("/api/posts"), author, Map.of("title", title, "content", body)))
                .andExpect(status().isBadRequest());
        assertEquals(0, postRepository.count());
    }

    @Test
    void postPaginationUsesNewestFirstAndReportsPageMetadata() throws Exception {
        Actor author = registerAndLogin("author");
        long first = createPost(author, "First", "Content").path("id").asLong();
        long second = createPost(author, "Second", "Content").path("id").asLong();
        long third = createPost(author, "Third", "Content").path("id").asLong();

        mockMvc.perform(get("/api/posts").param("page", "0").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2))
                .andExpect(jsonPath("$.first").value(true))
                .andExpect(jsonPath("$.last").value(false));
        mockMvc.perform(get("/api/posts").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.first").value(false))
                .andExpect(jsonPath("$.last").value(true));
        mockMvc.perform(get("/api/posts"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20));
    }

    @ParameterizedTest
    @ValueSource(strings = {"negativePage", "zeroSize", "excessSize"})
    void invalidPostPaginationReturnsBadRequest(String invalidInput) throws Exception {
        String page = invalidInput.equals("negativePage") ? "-1" : "0";
        String size = invalidInput.equals("zeroSize") ? "0" : invalidInput.equals("excessSize") ? "101" : "20";
        mockMvc.perform(get("/api/posts").param("page", page).param("size", size))
                .andExpect(status().isBadRequest());
    }

    @Test
    void keywordSearchIncludesContentAndTreatsSqlWildcardsLiterally() throws Exception {
        Actor author = registerAndLogin("author");
        long percent = createPost(author, "100% shipped", "Ordinary content").path("id").asLong();
        long underscore = createPost(author, "under_score", "needle in content").path("id").asLong();
        createPost(author, "needle in title", "Ordinary content");

        mockMvc.perform(get("/api/posts").param("keyword", "%"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(percent));
        mockMvc.perform(get("/api/posts").param("keyword", "_"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(underscore));
        mockMvc.perform(get("/api/posts").param("keyword", "needle"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(2));
    }

    @Test
    void missingResourcesReturnNotFound() throws Exception {
        Actor author = registerAndLogin("author");
        long missing = Long.MAX_VALUE;
        long postId = createPost(author, "Existing", "Content").path("id").asLong();
        mockMvc.perform(get("/api/posts/{id}", missing)).andExpect(status().isNotFound());
        mockMvc.perform(json(put("/api/posts/{id}", missing), author,
                        Map.of("title", "Title", "content", "Content")))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticate(delete("/api/posts/{id}", missing), author))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/posts/{id}/comments", missing)).andExpect(status().isNotFound());
        mockMvc.perform(json(post("/api/posts/{id}/comments", missing), author, Map.of("content", "Comment")))
                .andExpect(status().isNotFound());
        mockMvc.perform(json(put("/api/posts/{postId}/comments/{id}", postId, missing), author,
                        Map.of("content", "Changed")))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticate(delete("/api/posts/{postId}/comments/{id}", postId, missing), author))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/posts/{id}/files", missing)).andExpect(status().isNotFound());
        mockMvc.perform(uploadRequest(author, missing, textFile("hello.txt", "Hello")))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/files/{id}/download", missing)).andExpect(status().isNotFound());
        mockMvc.perform(authenticate(delete("/api/files/{id}", missing), author))
                .andExpect(status().isNotFound());
    }

    @Test
    void commentLifecycleReturnsAuthorAndParentAndUpdatesPublicList() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        JsonNode comment = createComment(author, postId, "Original comment");
        long commentId = comment.path("id").asLong();
        assertEquals(postId, comment.path("postId").asLong());
        assertEquals(author.id(), comment.path("author").path("id").asLong());
        assertTrue(!comment.path("createdAt").asText().isBlank());

        mockMvc.perform(json(put("/api/posts/{postId}/comments/{id}", postId, commentId), author,
                        Map.of("content", "Edited comment")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content").value("Edited comment"));
        mockMvc.perform(get("/api/posts/{postId}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("Edited comment"))
                .andExpect(jsonPath("$.content[0].author.nickname").value("author"));
        mockMvc.perform(authenticate(delete("/api/posts/{postId}/comments/{id}", postId, commentId), author))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/posts/{postId}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void onlyCommentAuthorCanEditOrDeleteEvenWhenOtherUserOwnsPost() throws Exception {
        Actor postAuthor = registerAndLogin("postAuthor");
        Actor commentAuthor = registerAndLogin("commentAuthor");
        long postId = createPost(postAuthor, "Post", "Content").path("id").asLong();
        long commentId = createComment(commentAuthor, postId, "Readers comment").path("id").asLong();

        mockMvc.perform(json(put("/api/posts/{postId}/comments/{id}", postId, commentId), postAuthor,
                        Map.of("content", "Changed")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticate(delete("/api/posts/{postId}/comments/{id}", postId, commentId), postAuthor))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticate(delete("/api/posts/{postId}/comments/{id}", postId, commentId), commentAuthor))
                .andExpect(status().isNoContent());
    }

    @Test
    void commentsArePagedOldestFirst() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        long first = createComment(author, postId, "First").path("id").asLong();
        long second = createComment(author, postId, "Second").path("id").asLong();
        long third = createComment(author, postId, "Third").path("id").asLong();

        mockMvc.perform(get("/api/posts/{postId}/comments", postId).param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(first))
                .andExpect(jsonPath("$.content[1].id").value(second))
                .andExpect(jsonPath("$.totalElements").value(3))
                .andExpect(jsonPath("$.totalPages").value(2));
        mockMvc.perform(get("/api/posts/{postId}/comments", postId).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id").value(third))
                .andExpect(jsonPath("$.last").value(true));
    }

    @ParameterizedTest
    @ValueSource(strings = {"blank", "tooLong"})
    void invalidCommentContentReturnsBadRequest(String invalidInput) throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        String body = invalidInput.equals("blank") ? " " : "c".repeat(2001);
        mockMvc.perform(json(post("/api/posts/{postId}/comments", postId), author, Map.of("content", body)))
                .andExpect(status().isBadRequest());
        assertEquals(0, commentRepository.count());
    }

    @Test
    void commentCannotBeModifiedThroughAnotherPostPath() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Original post", "Content").path("id").asLong();
        long otherPostId = createPost(author, "Other post", "Content").path("id").asLong();
        long commentId = createComment(author, postId, "Original comment").path("id").asLong();
        mockMvc.perform(json(put("/api/posts/{postId}/comments/{id}", otherPostId, commentId), author,
                        Map.of("content", "Changed")))
                .andExpect(status().isNotFound());
        mockMvc.perform(authenticate(delete("/api/posts/{postId}/comments/{id}", otherPostId, commentId), author))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/api/posts/{postId}/comments", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].content").value("Original comment"));
    }

    @Test
    void uploadedFilesCanBeListedDownloadedAndDeletedWithOriginalBytes() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        String filename = "한글 보고서.txt";
        byte[] bytes = "첨부파일 원문\nsecond line".getBytes(StandardCharsets.UTF_8);
        JsonNode files = upload(author, postId,
                new MockMultipartFile("files", filename, "text/plain", bytes), textFile("other.txt", "Other"));
        assertEquals(2, files.size());
        long fileId = files.get(0).path("id").asLong();
        assertEquals(filename, files.get(0).path("originalFilename").asText());
        assertEquals(bytes.length, files.get(0).path("size").asLong());
        assertEquals("/api/files/" + fileId + "/download", files.get(0).path("downloadUrl").asText());

        mockMvc.perform(get("/api/posts/{id}/files", postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2));
        MvcResult download = mockMvc.perform(get("/api/files/{id}/download", fileId))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andReturn();
        assertArrayEquals(bytes, download.getResponse().getContentAsByteArray());
        ContentDisposition disposition = ContentDisposition.parse(
                download.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION));
        assertEquals("attachment", disposition.getType());
        assertEquals(filename, disposition.getFilename());

        mockMvc.perform(authenticate(delete("/api/files/{id}", fileId), author))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/files/{id}/download", fileId)).andExpect(status().isNotFound());
        assertEquals(1, attachmentRepository.count());
        assertEquals(1, storedFileCount());
    }

    @Test
    void otherUsersCannotUploadToPostOrDeleteItsFiles() throws Exception {
        Fixture fixture = createFixture();
        Actor other = registerAndLogin("other");
        mockMvc.perform(uploadRequest(other, fixture.postId(), textFile("extra.txt", "Extra")))
                .andExpect(status().isForbidden());
        mockMvc.perform(authenticate(delete("/api/files/{id}", fixture.fileId()), other))
                .andExpect(status().isForbidden());
        assertEquals(1, attachmentRepository.count());
        assertEquals(1, storedFileCount());
    }

    @Test
    void emptyUploadReturnsBadRequestAndDoesNotStoreFiles() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        mockMvc.perform(uploadRequest(author, postId, textFile("empty.txt", "")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(uploadRequest(author, postId)).andExpect(status().isBadRequest());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void filesOverTenMegabytesReturnPayloadTooLarge() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        MockMultipartFile oversized = new MockMultipartFile(
                "files", "large.bin", "application/octet-stream", new byte[10 * 1024 * 1024 + 1]);
        mockMvc.perform(uploadRequest(author, postId, oversized))
                .andExpect(status().isPayloadTooLarge());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void uploadAcceptsFiveFilesAndRejectsSixFilesAsOneBatch() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        MockMultipartFile[] files = new MockMultipartFile[6];
        for (int index = 0; index < files.length; index++) {
            files[index] = textFile("file-" + index + ".txt", "Content");
        }
        mockMvc.perform(uploadRequest(author, postId, files)).andExpect(status().isBadRequest());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());

        MockMultipartFile[] fiveFiles = java.util.Arrays.copyOf(files, 5);
        assertEquals(5, upload(author, postId, fiveFiles).size());
        assertEquals(5, storedFileCount());
    }

    @ParameterizedTest
    @ValueSource(strings = {"../escape.txt", "..\\escape.txt"})
    void pathTraversalFilenamesAreRejected(String filename) throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        mockMvc.perform(uploadRequest(author, postId, textFile(filename, "Unsafe")))
                .andExpect(status().isBadRequest());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void invalidBatchDoesNotLeaveEarlierValidFileOnDiskOrInDatabase() throws Exception {
        Actor author = registerAndLogin("author");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        mockMvc.perform(uploadRequest(author, postId,
                        textFile("valid.txt", "Valid content"), textFile("empty.txt", "")))
                .andExpect(status().isBadRequest());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
    }

    @Test
    void deletingPostCascadesCommentsAttachmentMetadataAndPhysicalFiles() throws Exception {
        Actor author = registerAndLogin("author");
        Actor reader = registerAndLogin("reader");
        long postId = createPost(author, "Post", "Content").path("id").asLong();
        createComment(reader, postId, "A readers comment");
        JsonNode files = upload(author, postId, textFile("one.txt", "One"), textFile("two.txt", "Two"));
        assertEquals(2, storedFileCount());

        mockMvc.perform(authenticate(delete("/api/posts/{id}", postId), author))
                .andExpect(status().isNoContent());
        assertEquals(0, postRepository.count());
        assertEquals(0, commentRepository.count());
        assertEquals(0, attachmentRepository.count());
        assertEquals(0, storedFileCount());
        mockMvc.perform(get("/api/posts/{id}/comments", postId)).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/posts/{id}/files", postId)).andExpect(status().isNotFound());
        for (JsonNode file : files) {
            mockMvc.perform(get("/api/files/{id}/download", file.path("id").asLong()))
                    .andExpect(status().isNotFound());
        }
    }

    private Actor registerAndLogin(String nickname) throws Exception {
        String email = nickname + "@example.com";
        CsrfData initialCsrf = fetchCsrf(null);
        JsonNode user = responseJson(mockMvc.perform(withCsrf(post("/api/auth/signup"), initialCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Signup(email, nickname, PASSWORD))))
                .andExpect(status().isCreated()).andReturn());
        MvcResult login = mockMvc.perform(withCsrf(post("/api/auth/login"), initialCsrf)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(email, PASSWORD))))
                .andExpect(status().isOk()).andReturn();
        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        return new Actor(session, fetchCsrf(session), user.path("id").asLong());
    }

    private CsrfData fetchCsrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request).andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(cookie);
        JsonNode body = responseJson(result);
        return new CsrfData(cookie, body.path("headerName").asText(), body.path("token").asText());
    }

    private JsonNode createPost(Actor author, String title, String body) throws Exception {
        return responseJson(mockMvc.perform(json(post("/api/posts"), author, Map.of("title", title, "content", body)))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode createComment(Actor author, long postId, String body) throws Exception {
        return responseJson(mockMvc.perform(json(post("/api/posts/{id}/comments", postId), author, Map.of("content", body)))
                .andExpect(status().isCreated()).andReturn());
    }

    private JsonNode upload(Actor author, long postId, MockMultipartFile... files) throws Exception {
        return responseJson(mockMvc.perform(uploadRequest(author, postId, files))
                .andExpect(status().isCreated()).andReturn());
    }

    private MockHttpServletRequestBuilder uploadRequest(Actor author, long postId, MockMultipartFile... files) {
        MockMultipartHttpServletRequestBuilder request = multipart("/api/posts/{id}/files", postId);
        for (MockMultipartFile file : files) {
            request.file(file);
        }
        return authenticate(request, author);
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder request, Actor author, Object body)
            throws Exception {
        return authenticate(request, author).contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body));
    }

    private MockHttpServletRequestBuilder authenticate(MockHttpServletRequestBuilder request, Actor author) {
        return withCsrf(request.session(author.session()), author.csrf());
    }

    private MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request, CsrfData csrf) {
        return request.cookie(csrf.cookie()).header(csrf.headerName(), csrf.token());
    }

    private JsonNode responseJson(MvcResult result) throws IOException {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private Fixture createFixture() throws Exception {
        Actor owner = registerAndLogin("owner");
        long postId = createPost(owner, "Original post", "Content").path("id").asLong();
        long commentId = createComment(owner, postId, "Original comment").path("id").asLong();
        long fileId = upload(owner, postId, textFile("original.txt", "Original")).get(0).path("id").asLong();
        return new Fixture(owner, postId, commentId, fileId);
    }

    private List<MockHttpServletRequestBuilder> writeRequests(Fixture fixture) throws IOException {
        byte[] postBody = objectMapper.writeValueAsBytes(Map.of("title", "Changed", "content", "Changed"));
        byte[] commentBody = objectMapper.writeValueAsBytes(Map.of("content", "Changed"));
        return List.of(
                post("/api/posts").contentType(MediaType.APPLICATION_JSON).content(postBody),
                put("/api/posts/{id}", fixture.postId()).contentType(MediaType.APPLICATION_JSON).content(postBody),
                delete("/api/posts/{id}", fixture.postId()),
                post("/api/posts/{id}/comments", fixture.postId()).contentType(MediaType.APPLICATION_JSON).content(commentBody),
                put("/api/posts/{postId}/comments/{id}", fixture.postId(), fixture.commentId())
                        .contentType(MediaType.APPLICATION_JSON).content(commentBody),
                delete("/api/posts/{postId}/comments/{id}", fixture.postId(), fixture.commentId()),
                multipart("/api/posts/{id}/files", fixture.postId()).file(textFile("new.txt", "New")),
                delete("/api/files/{id}", fixture.fileId()));
    }

    private MockMultipartFile textFile(String filename, String body) {
        return new MockMultipartFile("files", filename, "text/plain", body.getBytes(StandardCharsets.UTF_8));
    }

    private long storedFileCount() throws IOException {
        try (Stream<Path> paths = Files.walk(STORAGE)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private static Path createStorageDirectory() {
        try {
            return Files.createTempDirectory("board-api-test-");
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot create isolated test storage", exception);
        }
    }

    private static void cleanStorageFiles() throws IOException {
        if (!Files.exists(STORAGE)) {
            return;
        }
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path target = STORAGE.toRealPath();
        if (!target.startsWith(tempRoot) || !target.getFileName().toString().startsWith("board-api-test-")) {
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

    private record Actor(MockHttpSession session, CsrfData csrf, long id) {
    }

    private record CsrfData(Cookie cookie, String headerName, String token) {
    }

    private record Fixture(Actor owner, long postId, long commentId, long fileId) {
    }
}
