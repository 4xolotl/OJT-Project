package com.ojt.board;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BoardHttpSmokeTest {

    private static final Path STORAGE = createStorage();
    private static final String BOUNDARY = "board-http-smoke-boundary";

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:board-http;MODE=MariaDB;DB_CLOSE_DELAY=-1");
        registry.add("app.storage.location", STORAGE::toString);
    }

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void realServerPreservesCookiesEnforcesMultipartLimitAndDeletesAttachments() throws Exception {
        CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        try (HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .connectTimeout(Duration.ofSeconds(10)).build()) {
            String token = csrf(client);
            HttpResponse<byte[]> signup = json(client, "POST", "/api/auth/signup", token,
                    Map.of("email", "http@example.com", "nickname", "HTTP 사용자", "password", "password123"));
            assertEquals(201, signup.statusCode());
            HttpResponse<byte[]> login = json(client, "POST", "/api/auth/login", token,
                    Map.of("email", "http@example.com", "password", "password123"));
            assertEquals(200, login.statusCode());
            assertTrue(login.headers().allValues("Set-Cookie").stream()
                    .anyMatch(header -> header.contains("JSESSIONID=") && header.contains("HttpOnly")));
            token = csrf(client);

            HttpResponse<byte[]> post = json(client, "POST", "/api/posts", token,
                    Map.of("title", "실제 HTTP 게시글", "content", "한글 본문과 첨부파일"));
            assertEquals(201, post.statusCode());
            long postId = body(post).path("id").asLong();

            byte[] fileBytes = "다운로드 본문\n한글 😀".getBytes(StandardCharsets.UTF_8);
            HttpResponse<byte[]> upload = multipart(client, postId, token, "notes.txt", fileBytes);
            assertEquals(201, upload.statusCode());
            long fileId = body(upload).get(0).path("id").asLong();

            try (HttpClient anonymous = HttpClient.newHttpClient()) {
                HttpResponse<byte[]> download = anonymous.send(HttpRequest.newBuilder(uri("/api/files/" + fileId + "/download"))
                        .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
                assertEquals(200, download.statusCode());
                assertEquals(new String(fileBytes, StandardCharsets.UTF_8), new String(download.body(), StandardCharsets.UTF_8));
                assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
            }

            // MockMvc bypasses servlet multipart parsing; this request exercises Tomcat's real limit.
            HttpResponse<byte[]> tooLarge = multipart(client, postId, token, "large.bin", new byte[10 * 1024 * 1024 + 1]);
            assertEquals(413, tooLarge.statusCode());
            assertFalse(body(tooLarge).path("message").asText().isBlank());
            try (var files = Files.list(STORAGE)) {
                assertEquals(1L, files.count());
            }

            assertEquals(204, json(client, "DELETE", "/api/posts/" + postId, token, null).statusCode());
            try (var files = Files.list(STORAGE)) {
                assertEquals(0L, files.count());
            }
            assertEquals(204, json(client, "POST", "/api/auth/logout", token, null).statusCode());
            HttpResponse<byte[]> me = client.send(HttpRequest.newBuilder(uri("/api/auth/me"))
                    .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(401, me.statusCode());
        }
    }

    private String csrf(HttpClient client) throws Exception {
        HttpResponse<byte[]> response = client.send(HttpRequest.newBuilder(uri("/api/auth/csrf"))
                .timeout(Duration.ofSeconds(20)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertEquals(200, response.statusCode());
        return body(response).path("token").asText();
    }

    private HttpResponse<byte[]> json(HttpClient client, String method, String path, String token, Object content) throws Exception {
        return client.send(HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json").header("X-XSRF-TOKEN", token)
                .method(method, content == null ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(content)))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpResponse<byte[]> multipart(HttpClient client, long postId, String token, String name, byte[] bytes) throws Exception {
        byte[] prefix = ("--" + BOUNDARY + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"" + name
                + "\"\r\nContent-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] suffix = ("\r\n--" + BOUNDARY + "--\r\n").getBytes(StandardCharsets.UTF_8);
        return client.send(HttpRequest.newBuilder(uri("/api/posts/" + postId + "/files"))
                .timeout(Duration.ofSeconds(30)).header("X-XSRF-TOKEN", token)
                .header("Content-Type", "multipart/form-data; boundary=" + BOUNDARY)
                .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                        HttpRequest.BodyPublishers.ofByteArray(bytes), HttpRequest.BodyPublishers.ofByteArray(suffix)))
                .build(), HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private JsonNode body(HttpResponse<byte[]> response) throws IOException {
        return objectMapper.readTree(response.body());
    }

    private static Path createStorage() {
        try {
            return Files.createTempDirectory("board-http-test-").toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    @AfterAll
    static void cleanupStorage() throws IOException {
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        Path target = STORAGE.toRealPath();
        if (!target.startsWith(tempRoot) || !target.getFileName().toString().startsWith("board-http-test-")) {
            throw new IOException("Unexpected test storage directory");
        }
        try (var files = Files.walk(target)) {
            for (Path file : files.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(file);
            }
        }
    }
}
