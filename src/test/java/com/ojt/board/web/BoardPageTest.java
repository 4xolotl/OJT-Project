package com.ojt.board.web;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:board-page;MODE=MariaDB;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class BoardPageTest {

    @Autowired
    private TestRestTemplate restTemplate;

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html"})
    void boardPageServesHtmlWithoutAuthentication(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<html"));
        assertTrue(response.getBody().contains("/assets/css/common.css"));
        assertTrue(response.getBody().contains("/assets/js/board.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/post.html?id=1", "/post.html?preview=1"})
    void postDetailPageServesHtmlWithoutAuthentication(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<html"));
        assertTrue(response.getBody().contains("/assets/css/common.css"));
        assertTrue(response.getBody().contains("/assets/css/post.css"));
        assertTrue(response.getBody().contains("/assets/js/post.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/login.html", "/login.html?returnTo=%2Fpost.html%3Fid%3D1"})
    void loginPageServesFormWithoutAuthentication(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<html"));
        assertTrue(response.getBody().contains("type=\"email\""));
        assertTrue(response.getBody().contains("type=\"password\""));
        assertTrue(response.getBody().contains("/assets/css/common.css"));
        assertTrue(response.getBody().contains("/assets/css/auth.css"));
        assertTrue(response.getBody().contains("/assets/js/login.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/signup.html", "/signup.html?returnTo=%2Fpost.html%3Fid%3D1"})
    void signupPageServesFormWithoutAuthentication(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<html"));
        assertTrue(response.getBody().contains("method=\"post\""));
        for (String field : List.of("email", "nickname", "password", "password-confirm")) {
            assertTrue(response.getBody().contains("id=\"" + field + "\""), field);
        }
        assertTrue(response.getBody().contains("type=\"email\""));
        assertTrue(response.getBody().contains("type=\"password\""));
        assertTrue(response.getBody().contains("/assets/css/common.css"));
        assertTrue(response.getBody().contains("/assets/css/auth.css"));
        assertTrue(response.getBody().contains("/assets/js/signup.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/write.html", "/write.html?preview=1"})
    void writePageServesFormWithoutAuthentication(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertNotNull(response.getBody());
        assertTrue(response.getBody().contains("<html"));
        for (String field : List.of("post-form", "post-title", "post-content", "file-input")) {
            assertTrue(response.getBody().contains("id=\"" + field + "\""), field);
        }
        assertTrue(response.getBody().contains("type=\"file\""));
        assertTrue(response.getBody().contains("/assets/css/common.css"));
        assertTrue(response.getBody().contains("/assets/css/editor.css"));
        assertTrue(response.getBody().contains("/assets/js/write.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/edit.html?id=1", "/edit.html?preview=1"})
    void editPageServesFormWithoutAuthentication(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getHeaders().getContentType());
        assertTrue(response.getHeaders().getContentType().isCompatibleWith(MediaType.TEXT_HTML));
        assertNotNull(response.getBody());
        for (String field : List.of("edit-form", "post-title", "post-content", "file-input")) {
            assertTrue(response.getBody().contains("id=\"" + field + "\""), field);
        }
        assertTrue(response.getBody().contains("type=\"file\""));
        assertTrue(response.getBody().contains("/assets/css/editor.css"));
        assertTrue(response.getBody().contains("/assets/js/edit.js"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/assets/css/common.css", "/assets/js/board.js",
            "/assets/css/post.css", "/assets/js/post.js", "/assets/css/auth.css", "/assets/js/login.js",
            "/assets/js/signup.js", "/assets/css/editor.css", "/assets/js/write.js", "/assets/js/edit.js"})
    void boardAssetsAreAccessibleWithoutAuthentication(String path) {
        ResponseEntity<String> response = restTemplate.getForEntity(path, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNotNull(response.getBody());
        assertFalse(response.getBody().isBlank());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/", "/index.html", "/post.html", "/login.html", "/signup.html", "/write.html", "/edit.html",
            "/assets/css/common.css", "/assets/js/board.js", "/assets/css/post.css", "/assets/js/post.js",
            "/assets/css/auth.css", "/assets/js/login.js", "/assets/js/signup.js", "/assets/css/editor.css",
            "/assets/js/write.js", "/assets/js/edit.js"})
    void boardPagesAndAssetsSupportAnonymousHead(String path) {
        ResponseEntity<String> response = restTemplate.exchange(
                path, HttpMethod.HEAD, HttpEntity.EMPTY, String.class);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertNull(response.getBody());
    }

    @Test
    void publishingBoardPageKeepsPrivateApiAndWritesProtected() {
        ResponseEntity<JsonNode> me = restTemplate.getForEntity("/api/auth/me", JsonNode.class);
        assertEquals(HttpStatus.UNAUTHORIZED, me.getStatusCode());
        assertNotNull(me.getBody());
        assertFalse(me.getBody().path("message").asText().isBlank());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        String postBody = "{\"title\":\"Unauthorized post\",\"content\":\"Must not be stored\"}";
        ResponseEntity<JsonNode> missingCsrf = restTemplate.postForEntity(
                "/api/posts", new HttpEntity<>(postBody, headers), JsonNode.class);
        assertEquals(HttpStatus.FORBIDDEN, missingCsrf.getStatusCode());

        ResponseEntity<JsonNode> csrfResponse = restTemplate.getForEntity("/api/auth/csrf", JsonNode.class);
        assertEquals(HttpStatus.OK, csrfResponse.getStatusCode());
        JsonNode csrf = csrfResponse.getBody();
        assertNotNull(csrf);
        List<String> cookies = csrfResponse.getHeaders().get(HttpHeaders.SET_COOKIE);
        assertNotNull(cookies);
        String csrfCookie = cookies.stream().filter(cookie -> cookie.startsWith("XSRF-TOKEN="))
                .findFirst().orElseThrow();
        headers.set(HttpHeaders.COOKIE, csrfCookie.split(";", 2)[0]);
        headers.set(csrf.path("headerName").asText(), csrf.path("token").asText());

        // A valid CSRF token must not grant authentication or make static paths writable.
        for (String path : List.of("/api/posts", "/", "/index.html", "/post.html", "/login.html", "/signup.html",
                "/write.html", "/edit.html", "/assets/js/board.js", "/assets/js/write.js", "/assets/js/edit.js")) {
            ResponseEntity<JsonNode> response = restTemplate.postForEntity(
                    path, new HttpEntity<>(postBody, headers), JsonNode.class);
            assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode(), path);
        }
    }
}
