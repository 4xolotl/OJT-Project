package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.ojt.board.user.UserRepository;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Comparator;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/** Real authorization-code callbacks: only the remote provider endpoints are replaced. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "app.oauth.google.enabled=true",
        "app.oauth.google.client-id=local-oidc-test-client",
        "app.oauth.google.client-secret=local-oidc-test-secret",
        "spring.flyway.enabled=false"
})
@ActiveProfiles("test")
@Import(GoogleOidcIntegrationTest.ProviderConfiguration.class)
class GoogleOidcIntegrationTest {

    private static final String CLIENT_ID = "local-oidc-test-client";
    private static final String GOOGLE_ISSUER = "https://accounts.google.com";
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalOidcProvider PROVIDER = new LocalOidcProvider();
    private static final Path STORAGE = createStorage();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> "jdbc:h2:mem:board-google-oidc;MODE=MariaDB;DB_CLOSE_DELAY=-1");
        registry.add("app.storage.location", STORAGE::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ProviderConfiguration {
        @Bean
        @Primary
        ClientRegistrationRepository localGoogleClientRegistrationRepository() {
            return new InMemoryClientRegistrationRepository(CommonOAuth2Provider.GOOGLE.getBuilder("google")
                    .clientId(CLIENT_ID).clientSecret("local-oidc-test-secret")
                    .scope("openid", "profile", "email")
                    .redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
                    .authorizationUri(PROVIDER.url("/authorize"))
                    .tokenUri(PROVIDER.url("/token"))
                    .jwkSetUri(PROVIDER.url("/jwks"))
                    .userInfoUri(PROVIDER.url("/userinfo"))
                    .issuerUri(GOOGLE_ISSUER).build());
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private UserRepository users;

    @AfterEach
    void providerRequestsWereValid() {
        assertNull(PROVIDER.failure.getAndSet(null), "The mock provider rejected the application's token request");
    }

    @Test
    void providerDiscoveryAndAuthorizationUseOidcPkceStateAndNonce() throws Exception {
        try (Browser first = browser(); Browser second = browser()) {
            HttpResponse<byte[]> response = first.get("/api/auth/providers");
            assertEquals(200, response.statusCode());
            assertTrue(body(response).path("google").path("enabled").asBoolean());
            assertEquals("/oauth2/authorization/google", body(response).path("google").path("authorizationUrl").asText());
            Authorization one = authorize(first, "/write.html?keyword=Spring&page=2&size=50");
            Authorization two = authorize(second, "/");
            assertEquals("code", one.parameters().get("response_type"));
            assertEquals(CLIENT_ID, one.parameters().get("client_id"));
            assertEquals(Set.of("openid", "profile", "email"), Set.of(one.parameters().get("scope").split(" ")));
            assertEquals("S256", one.parameters().get("code_challenge_method"));
            assertTrue(one.parameters().get("code_challenge").matches("[A-Za-z0-9_-]{43}"));
            assertFalse(one.parameters().get("state").isBlank());
            assertFalse(one.parameters().get("nonce").isBlank());
            assertNotEquals(one.parameters().get("state"), two.parameters().get("state"));
            assertNotEquals(one.parameters().get("nonce"), two.parameters().get("nonce"));
            assertFalse(one.parameters().containsKey("client_secret"));
        }
    }

    @Test
    void validGoogleCallbackRotatesSessionAndCsrfAndSupportsBoardChangesAcrossAccounts() throws Exception {
        Identity identity = identity();
        try (Browser owner = browser(); Browser other = browser()) {
            String oldToken = csrf(owner);
            Authorization authorization = authorize(owner, "/write.html?keyword=Spring&page=2&size=50&preview=1");
            String previousSession = owner.sessionId();
            assertNotNull(previousSession);
            HttpResponse<byte[]> callback = complete(owner, authorization, identity, Defect.NONE);
            assertEquals("/write.html?keyword=Spring&page=2&size=50", redirectPath(callback));
            assertNotEquals(previousSession, owner.sessionId());
            assertTrue(callback.headers().allValues("Set-Cookie").stream()
                    .anyMatch(value -> value.contains("JSESSIONID=") && value.contains("HttpOnly")));
            HttpResponse<byte[]> me = owner.get("/api/auth/me");
            assertEquals(200, me.statusCode());
            long userId = body(me).path("id").asLong();
            assertTrue(userId > 0);
            assertEquals(identity.email(), body(me).path("email").asText());
            assertEquals(identity.nickname(), body(me).path("nickname").asText());
            assertFalse(new String(me.body(), StandardCharsets.UTF_8).contains("access_token"));
            assertFalse(new String(me.body(), StandardCharsets.UTF_8).contains("id_token"));
            assertNull(users.findByEmail(identity.email()).orElseThrow().getPassword());

            String token = csrf(owner);
            assertNotEquals(oldToken, token);
            HttpResponse<byte[]> staleTokenPost = owner.json("POST", "/api/posts", oldToken, post("stale token"));
            assertEquals(201, staleTokenPost.statusCode());
            assertEquals(204, owner.json("DELETE", "/api/posts/" + body(staleTokenPost).path("id").asLong(), null, null).statusCode());
            HttpResponse<byte[]> tokenlessPost = owner.json("POST", "/api/posts", null, post("missing token"));
            assertEquals(201, tokenlessPost.statusCode());
            assertEquals(204, owner.json("DELETE", "/api/posts/" + body(tokenlessPost).path("id").asLong(), null, null).statusCode());
            HttpResponse<byte[]> created = owner.json("POST", "/api/posts", token, post("Google 게시글"));
            assertEquals(201, created.statusCode());
            long postId = body(created).path("id").asLong();
            assertEquals(userId, body(created).path("author").path("id").asLong());
            HttpResponse<byte[]> comment = owner.json("POST", "/api/posts/" + postId + "/comments", token, Map.of("content", "Google 댓글"));
            assertEquals(201, comment.statusCode());
            long commentId = body(comment).path("id").asLong();
            assertEquals(userId, body(comment).path("author").path("id").asLong());
            byte[] bytes = "Google 첨부파일 🧪".getBytes(StandardCharsets.UTF_8);
            HttpResponse<byte[]> upload = owner.upload(postId, token, bytes);
            assertEquals(201, upload.statusCode());
            long fileId = body(upload).get(0).path("id").asLong();
            assertArrayEquals(bytes, other.get("/api/files/" + fileId + "/download").body());

            assertEquals(302, complete(other, authorize(other, "/"), identity(), Defect.NONE).statusCode());
            String otherToken = csrf(other);
            HttpResponse<byte[]> otherPostUpdate = other.json("PUT", "/api/posts/" + postId, otherToken, post("다른 계정의 수정"));
            assertEquals(200, otherPostUpdate.statusCode());
            assertEquals("다른 계정의 수정", body(owner.get("/api/posts/" + postId)).path("title").asText());
            assertEquals(userId, body(otherPostUpdate).path("author").path("id").asLong());
            HttpResponse<byte[]> otherCommentUpdate = other.json("PUT", "/api/posts/" + postId + "/comments/" + commentId,
                    otherToken, Map.of("content", "다른 계정의 댓글 수정"));
            assertEquals(200, otherCommentUpdate.statusCode());
            assertEquals("다른 계정의 댓글 수정", body(otherCommentUpdate).path("content").asText());
            assertEquals(userId, body(otherCommentUpdate).path("author").path("id").asLong());
            HttpResponse<byte[]> otherUpload = other.upload(postId, otherToken, bytes);
            assertEquals(201, otherUpload.statusCode());
            long otherFileId = body(otherUpload).get(0).path("id").asLong();
            assertArrayEquals(bytes, owner.get("/api/files/" + otherFileId + "/download").body());
            assertEquals(204, other.json("DELETE", "/api/files/" + fileId, otherToken, null).statusCode());
            assertEquals(404, owner.get("/api/files/" + fileId + "/download").statusCode());

            assertEquals(200, owner.json("PUT", "/api/posts/" + postId, token, post("수정")).statusCode());
            assertEquals(200, owner.json("PUT", "/api/posts/" + postId + "/comments/" + commentId, token, Map.of("content", "수정 댓글")).statusCode());
            assertEquals(204, other.json("DELETE", "/api/posts/" + postId + "/comments/" + commentId, otherToken, null).statusCode());
            assertEquals(404, owner.json("PUT", "/api/posts/" + postId + "/comments/" + commentId, token,
                    Map.of("content", "삭제된 댓글 수정")).statusCode());
            assertEquals(204, other.json("DELETE", "/api/posts/" + postId, otherToken, null).statusCode());
            assertEquals(404, other.get("/api/files/" + otherFileId + "/download").statusCode());
            assertEquals(404, other.get("/api/posts/" + postId + "/comments").statusCode());
            try (var files = Files.list(STORAGE)) { assertEquals(0, files.count()); }
            assertEquals(204, owner.json("POST", "/api/auth/logout", token, null).statusCode());
            assertEquals(401, owner.get("/api/auth/me").statusCode());
            String loggedOutToken = csrf(owner);
            assertEquals(401, owner.json("POST", "/api/auth/login", loggedOutToken,
                    Map.of("email", identity.email(), "password", "password123")).statusCode());
        }
    }

    @ParameterizedTest
    @EnumSource(value = Defect.class, names = {"NONCE", "ISSUER", "AUDIENCE", "SIGNATURE", "EXPIRED", "UNVERIFIED_EMAIL"})
    void invalidSignedIdentityNeverCreatesAnAuthenticatedBoardSession(Defect defect) throws Exception {
        Identity identity = identity();
        try (Browser browser = browser()) {
            assertFailure(complete(browser, authorize(browser, "/write.html"), identity, defect));
            assertEquals(401, browser.get("/api/auth/me").statusCode());
            assertTrue(users.findByEmail(identity.email()).isEmpty());
        }
    }

    @Test
    void mismatchedStateIsRejectedBeforeTheTokenEndpoint() throws Exception {
        try (Browser browser = browser()) {
            Authorization authorization = authorize(browser, "/write.html");
            int calls = PROVIDER.tokenCalls.get();
            assertFailure(browser.get("/login/oauth2/code/google?code=unused&state=" + encode(authorization.parameters().get("state") + "tampered")));
            assertEquals(calls, PROVIDER.tokenCalls.get());
            assertEquals(401, browser.get("/api/auth/me").statusCode());
        }
    }

    @Test
    void callbackCannotBeTransferredToAnotherBrowserSession() throws Exception {
        try (Browser initiator = browser(); Browser outsider = browser()) {
            Authorization authorization = authorize(initiator, "/");
            String code = PROVIDER.issue(authorization, identity(), Defect.NONE);
            int calls = PROVIDER.tokenCalls.get();
            assertFailure(outsider.get(callback(authorization, code)));
            assertEquals(calls, PROVIDER.tokenCalls.get());
            assertEquals(401, outsider.get("/api/auth/me").statusCode());
        }
    }

    @Test
    void completedCallbackCannotBeReplayedAndSubjectReturnsToTheSameBoardAccount() throws Exception {
        Identity identity = identity();
        try (Browser browser = browser()) {
            Authorization authorization = authorize(browser, "/");
            String code = PROVIDER.issue(authorization, identity, Defect.NONE);
            assertEquals("/", redirectPath(browser.get(callback(authorization, code))));
            long userId = body(browser.get("/api/auth/me")).path("id").asLong();
            int calls = PROVIDER.tokenCalls.get();
            assertFailure(browser.get(callback(authorization, code)));
            assertEquals(calls, PROVIDER.tokenCalls.get());
            assertEquals("/", redirectPath(complete(browser, authorize(browser, "/"), identity, Defect.NONE)));
            assertEquals(userId, body(browser.get("/api/auth/me")).path("id").asLong());
        }
    }

    @Test
    void existingPasswordAccountIsNotAutomaticallyLinkedByMatchingEmail() throws Exception {
        Identity identity = identity();
        try (Browser browser = browser()) {
            String token = csrf(browser);
            assertEquals(201, browser.json("POST", "/api/auth/signup", token,
                    Map.of("email", identity.email(), "nickname", "기존 사용자", "password", "password123")).statusCode());
            long existingId = users.findByEmail(identity.email()).orElseThrow().getId();
            String existingHash = users.findByEmail(identity.email()).orElseThrow().getPassword();
            Map<String, String> failure = assertFailure(complete(browser, authorize(browser, "/write.html"), identity, Defect.NONE));
            assertEquals("email_conflict", failure.get("oauthError"));
            assertEquals(401, browser.get("/api/auth/me").statusCode());
            assertEquals(existingId, users.findByEmail(identity.email()).orElseThrow().getId());
            assertEquals(existingHash, users.findByEmail(identity.email()).orElseThrow().getPassword());
            assertEquals(200, browser.json("POST", "/api/auth/login", csrf(browser),
                    Map.of("email", identity.email(), "password", "password123")).statusCode());
        }
    }

    @Test
    void cancellationUsesAFixedErrorCodeAndSanitizedReturnWithoutProviderDescriptions() throws Exception {
        try (Browser browser = browser()) {
            Authorization authorization = authorize(browser, "/post.html?id=9&keyword=Spring&page=2&size=50&preview=1#comments");
            String rawDescription = "untrusted-provider-description";
            HttpResponse<byte[]> response = browser.get("/login/oauth2/code/google?error=access_denied&error_description="
                    + rawDescription + "&state=" + encode(authorization.parameters().get("state")));
            Map<String, String> failure = assertFailure(response);
            assertEquals("cancelled", failure.get("oauthError"));
            assertEquals("/post.html?id=9&keyword=Spring&page=2&size=50#comments", failure.get("returnTo"));
            assertFalse(response.headers().firstValue("Location").orElseThrow().contains(rawDescription));
            assertEquals(401, browser.get("/api/auth/me").statusCode());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://evil.example/", "//evil.example/", "/\\evil.example", "/api/files/1/download", "/login.html", "/post.html?id=9223372036854775808", "/write.html%0d%0aLocation:evil"})
    void successfulLoginCannotRedirectToAnUnapprovedTarget(String returnTo) throws Exception {
        try (Browser browser = browser()) {
            assertEquals("/", redirectPath(complete(browser, authorize(browser, returnTo), identity(), Defect.NONE)));
            assertEquals(200, browser.get("/api/auth/me").statusCode());
        }
    }

    private Browser browser() { return new Browser(); }

    private Authorization authorize(Browser browser, String returnTo) throws Exception {
        HttpResponse<byte[]> response = browser.get("/oauth2/authorization/google?returnTo=" + encode(returnTo));
        assertEquals(302, response.statusCode());
        URI location = URI.create(response.headers().firstValue("Location").orElseThrow());
        assertEquals(PROVIDER.url("/authorize"), location.getScheme() + "://" + location.getAuthority() + location.getPath());
        return new Authorization(query(location.getRawQuery()));
    }

    private HttpResponse<byte[]> complete(Browser browser, Authorization authorization, Identity identity, Defect defect) throws Exception {
        return browser.get(callback(authorization, PROVIDER.issue(authorization, identity, defect)));
    }

    private String callback(Authorization authorization, String code) {
        return "/login/oauth2/code/google?code=" + encode(code) + "&state=" + encode(authorization.parameters().get("state"));
    }

    private String redirectPath(HttpResponse<byte[]> response) {
        assertEquals(302, response.statusCode());
        URI destination = URI.create(response.headers().firstValue("Location").orElseThrow());
        if (destination.isAbsolute()) {
            assertEquals("http", destination.getScheme());
            assertEquals("localhost", destination.getHost());
            assertEquals(port, destination.getPort());
        }
        return destination.getRawPath() + (destination.getRawQuery() == null ? "" : "?" + destination.getRawQuery())
                + (destination.getRawFragment() == null ? "" : "#" + destination.getRawFragment());
    }

    private Map<String, String> assertFailure(HttpResponse<byte[]> response) {
        String path = redirectPath(response);
        URI destination = URI.create(path);
        assertEquals("/login.html", destination.getPath());
        Map<String, String> params = query(destination.getRawQuery());
        assertTrue(Set.of("cancelled", "email_conflict", "invalid_identity", "failed").contains(params.get("oauthError")));
        assertEquals(Set.of("oauthError", "returnTo"), params.keySet());
        return params;
    }

    private String csrf(Browser browser) throws Exception {
        HttpResponse<byte[]> response = browser.get("/api/auth/csrf");
        assertEquals(200, response.statusCode());
        assertEquals("X-XSRF-TOKEN", body(response).path("headerName").asText());
        return body(response).path("token").asText();
    }

    private static JsonNode body(HttpResponse<byte[]> response) throws IOException { return JSON.readTree(response.body()); }
    private static Map<String, String> post(String title) { return Map.of("title", title, "content", "Google OIDC 통합 테스트 본문"); }
    private static Identity identity() {
        String unique = UUID.randomUUID().toString();
        return new Identity("google-test-" + unique, "oidc-" + unique + "@example.com", "구글 사용자");
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private static Map<String, String> query(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (raw == null || raw.isBlank()) return result;
        for (String part : raw.split("&")) {
            String[] pair = part.split("=", 2);
            result.put(URLDecoder.decode(pair[0], StandardCharsets.UTF_8),
                    pair.length == 1 ? "" : URLDecoder.decode(pair[1], StandardCharsets.UTF_8));
        }
        return result;
    }

    private final class Browser implements AutoCloseable {
        private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        private final HttpClient client = HttpClient.newBuilder().cookieHandler(cookies)
                .followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        HttpResponse<byte[]> get(String path) throws Exception {
            return client.send(builder(path).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        HttpResponse<byte[]> json(String method, String path, String token, Object content) throws Exception {
            HttpRequest.Builder request = builder(path).header("Content-Type", "application/json");
            if (token != null) request.header("X-XSRF-TOKEN", token);
            return client.send(request.method(method, content == null ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofByteArray(JSON.writeValueAsBytes(content))).build(), HttpResponse.BodyHandlers.ofByteArray());
        }
        HttpResponse<byte[]> upload(long postId, String token, byte[] content) throws Exception {
            String boundary = "oidc-test-multipart";
            byte[] prefix = ("--" + boundary + "\r\nContent-Disposition: form-data; name=\"files\"; filename=\"google.txt\"\r\n"
                    + "Content-Type: application/octet-stream\r\n\r\n").getBytes(StandardCharsets.UTF_8);
            byte[] suffix = ("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);
            return client.send(builder("/api/posts/" + postId + "/files").header("X-XSRF-TOKEN", token)
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.concat(HttpRequest.BodyPublishers.ofByteArray(prefix),
                            HttpRequest.BodyPublishers.ofByteArray(content), HttpRequest.BodyPublishers.ofByteArray(suffix))).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
        }
        private HttpRequest.Builder builder(String path) {
            return HttpRequest.newBuilder(URI.create("http://localhost:" + port + path)).timeout(Duration.ofSeconds(20));
        }
        String sessionId() {
            return cookies.getCookieStore().getCookies().stream().filter(cookie -> cookie.getName().equals("JSESSIONID") && !cookie.hasExpired())
                    .map(cookie -> cookie.getValue()).findFirst().orElse(null);
        }
        @Override public void close() { client.close(); }
    }

    private enum Defect { NONE, NONCE, ISSUER, AUDIENCE, SIGNATURE, EXPIRED, UNVERIFIED_EMAIL }
    private record Authorization(Map<String, String> parameters) {}
    private record Identity(String subject, String email, String nickname) {}
    private record Grant(Authorization authorization, Identity identity, Defect defect) {}

    private static final class LocalOidcProvider {
        private final HttpServer server;
        private final RSAKey signingKey;
        private final RSAKey wrongKey;
        private final Map<String, Grant> codes = new ConcurrentHashMap<>();
        private final Map<String, Grant> accessTokens = new ConcurrentHashMap<>();
        private final AtomicInteger tokenCalls = new AtomicInteger();
        private final AtomicReference<Throwable> failure = new AtomicReference<>();

        LocalOidcProvider() {
            try {
                signingKey = new RSAKeyGenerator(2048).keyID("oidc-test-key").generate();
                wrongKey = new RSAKeyGenerator(2048).keyID("oidc-test-key").generate();
                server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                server.createContext("/jwks", exchange -> send(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toJSONObject()));
                server.createContext("/token", this::token);
                server.createContext("/userinfo", this::userinfo);
                server.start();
            } catch (IOException | JOSEException exception) { throw new IllegalStateException("Cannot start local OIDC provider", exception); }
        }
        String url(String path) { return "http://127.0.0.1:" + server.getAddress().getPort() + path; }
        String issue(Authorization authorization, Identity identity, Defect defect) {
            String code = UUID.randomUUID().toString(); codes.put(code, new Grant(authorization, identity, defect)); return code;
        }
        void token(HttpExchange exchange) throws IOException {
            tokenCalls.incrementAndGet();
            try {
                assertEquals("POST", exchange.getRequestMethod());
                Map<String, String> form = query(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
                Grant grant = codes.remove(form.get("code"));
                assertNotNull(grant, "Authorization codes must be valid and single use");
                assertEquals("authorization_code", form.get("grant_type"));
                assertEquals(grant.authorization().parameters().get("redirect_uri"), form.get("redirect_uri"));
                String verifier = form.get("code_verifier");
                assertNotNull(verifier, "Token exchange must include the PKCE verifier");
                assertTrue(verifier.matches("[A-Za-z0-9._~-]{43,128}"));
                String challenge = Base64.getUrlEncoder().withoutPadding()
                        .encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
                assertEquals(grant.authorization().parameters().get("code_challenge"), challenge);
                String accessToken = "local-access-" + UUID.randomUUID();
                accessTokens.put(accessToken, grant);
                send(exchange, 200, Map.of("access_token", accessToken, "token_type", "Bearer", "expires_in", 3600,
                        "scope", "openid profile email", "id_token", signedToken(grant)));
            } catch (Exception | AssertionError exception) {
                failure.compareAndSet(null, exception);
                send(exchange, 400, Map.of("error", "invalid_grant"));
            }
        }
        void userinfo(HttpExchange exchange) throws IOException {
            String authorization = exchange.getRequestHeaders().getFirst("Authorization");
            Grant grant = accessTokens.get(authorization == null ? "" : authorization.replaceFirst("^Bearer ", ""));
            if (grant == null) { send(exchange, 401, Map.of("error", "invalid_token")); return; }
            Identity identity = grant.identity();
            send(exchange, 200, Map.of("sub", identity.subject(), "email", identity.email(),
                    "email_verified", grant.defect() != Defect.UNVERIFIED_EMAIL, "name", identity.nickname()));
        }
        String signedToken(Grant grant) throws JOSEException {
            Instant now = Instant.now();
            JWTClaimsSet claims = new JWTClaimsSet.Builder()
                    .issuer(grant.defect() == Defect.ISSUER ? "https://untrusted.example" : GOOGLE_ISSUER)
                    .audience(grant.defect() == Defect.AUDIENCE ? "another-client" : CLIENT_ID)
                    .subject(grant.identity().subject()).issueTime(Date.from(now.minusSeconds(5)))
                    .expirationTime(Date.from(now.plusSeconds(grant.defect() == Defect.EXPIRED ? -600 : 300)))
                    .claim("nonce", grant.defect() == Defect.NONCE ? "wrong-nonce" : grant.authorization().parameters().get("nonce"))
                    .claim("email", grant.identity().email()).claim("email_verified", grant.defect() != Defect.UNVERIFIED_EMAIL)
                    .claim("name", grant.identity().nickname()).build();
            SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT)
                    .keyID(signingKey.getKeyID()).build(), claims);
            token.sign(new RSASSASigner(grant.defect() == Defect.SIGNATURE ? wrongKey : signingKey));
            return token.serialize();
        }
        void send(HttpExchange exchange, int status, Object content) throws IOException {
            byte[] bytes = JSON.writeValueAsBytes(content);
            exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (exchange) { exchange.getResponseBody().write(bytes); }
        }
    }

    private static Path createStorage() {
        try { return Files.createTempDirectory("board-google-oidc-test-").toAbsolutePath().normalize(); }
        catch (IOException exception) { throw new UncheckedIOException(exception); }
    }

    @AfterAll
    static void cleanup() throws IOException {
        PROVIDER.server.stop(0);
        Path target = STORAGE.toRealPath();
        Path tempRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
        if (!target.startsWith(tempRoot) || !target.getFileName().toString().startsWith("board-google-oidc-test-")) {
            throw new IOException("Unexpected OIDC test storage path");
        }
        try (var paths = Files.walk(target)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        }
    }
}
