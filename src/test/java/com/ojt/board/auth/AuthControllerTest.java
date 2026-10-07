package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest(properties = {
        "app.security.csrf.rate-limit.capacity=3",
        "app.security.csrf.rate-limit.refill-tokens=3",
        "app.security.csrf.rate-limit.refill-period=1m",
        "app.security.csrf.rate-limit.max-sources=1",
        "app.security.login.rate-limit.source-account-threshold=3",
        "app.security.login.rate-limit.account-threshold=4",
        "app.security.login.rate-limit.source-threshold=5",
        "app.security.login.rate-limit.initial-backoff=30s",
        "app.security.login.rate-limit.max-backoff=2m",
        "app.security.login.rate-limit.record-ttl=10m",
        "app.security.login.rate-limit.source-record-ttl=1m",
        "app.security.login.rate-limit.max-source-accounts=100",
        "app.security.login.rate-limit.max-accounts=100",
        "app.security.login.rate-limit.max-sources=100"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "violet river 829";
    private static final String MAX_LENGTH_PASSWORD = "R7!violet-river_2026/candle?meadow#orbit="
            + "Lake9%forest&cloud2*harborTrail";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CsrfTokenRateLimiter csrfTokenRateLimiter;

    @Autowired
    private LoginAttemptLimiter loginAttemptLimiter;

    @Autowired
    private LoginRateLimitProperties loginRateLimitProperties;

    @BeforeEach
    void setUp() {
        csrfTokenRateLimiter.clear();
        loginAttemptLimiter.clear();
        userRepository.deleteAll();
    }

    @Test
    void loginRateLimitConfigurationBindsAllPolicyValues() {
        assertEquals(3, loginRateLimitProperties.sourceAccountThreshold());
        assertEquals(4, loginRateLimitProperties.accountThreshold());
        assertEquals(5, loginRateLimitProperties.sourceThreshold());
        assertEquals(Duration.ofSeconds(30), loginRateLimitProperties.initialBackoff());
        assertEquals(Duration.ofMinutes(2), loginRateLimitProperties.maxBackoff());
        assertEquals(Duration.ofMinutes(10), loginRateLimitProperties.recordTtl());
        assertEquals(Duration.ofMinutes(1), loginRateLimitProperties.sourceRecordTtl());
        assertEquals(100, loginRateLimitProperties.maxSourceAccounts());
        assertEquals(100, loginRateLimitProperties.maxAccounts());
        assertEquals(100, loginRateLimitProperties.maxSources());
    }

    @Test
    void signupLoginMeAndLogoutUseSessionAuthenticationAndRealCsrfTokens() throws Exception {
        CsrfData initialCsrf = fetchCsrf(null);
        signup(initialCsrf, PASSWORD);

        User savedUser = userRepository.findByEmail(EMAIL).orElseThrow();
        assertNotEquals(PASSWORD, savedUser.getPassword());
        assertTrue(passwordEncoder.matches(PASSWORD, savedUser.getPassword()));

        // Registering an account must not silently authenticate the browser.
        mockMvc.perform(get("/api/auth/me").cookie(initialCsrf.cookie()))
                .andExpect(status().isUnauthorized());

        MockHttpSession anonymousSession = new MockHttpSession();
        String oldSessionId = anonymousSession.getId();
        MvcResult loginResult = mockMvc.perform(csrfPost("/api/auth/login", initialCsrf)
                        .session(anonymousSession)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nickname").value("tester"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(cookie().maxAge("XSRF-TOKEN", 0))
                .andReturn();

        MockHttpSession authenticatedSession =
                (MockHttpSession) loginResult.getRequest().getSession(false);
        assertNotNull(authenticatedSession);
        assertNotEquals(oldSessionId, authenticatedSession.getId());

        mockMvc.perform(get("/api/auth/me").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.password").doesNotExist());

        // A browser discards the cleared token cookie and obtains a new token after login.
        CsrfData authenticatedCsrf = fetchCsrf(authenticatedSession);
        assertNotEquals(initialCsrf.token(), authenticatedCsrf.token());

        mockMvc.perform(csrfPost("/api/auth/logout", authenticatedCsrf).session(authenticatedSession))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0));
        assertTrue(authenticatedSession.isInvalid());

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void loginUsesTheSameCanonicalAsciiEmailIdentityAsStorageAndRateLimiting() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        userRepository.save(new User(" Test@EXAMPLE.COM ", "canonical-user",
                passwordEncoder.encode(PASSWORD)));

        mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .with(mock -> { mock.setRemoteAddr("198.51.100.44"); return mock; })
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "email", "\tTEST@EXAMPLE.COM ",
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("test@example.com"));

        assertEquals(LoginAttemptLimiter.fingerprint(" test@example.com "),
                LoginAttemptLimiter.fingerprint("TEST@EXAMPLE.COM"));
    }

    @Test
    void anonymousMeReturnsJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void csrfEndpointDoesNotCreateAServerSessionAndLoginCreatesOne() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, PASSWORD);

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
        MvcResult login = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();

        MockHttpSession session = (MockHttpSession) login.getRequest().getSession(false);
        assertNotNull(session);
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    void csrfEndpointRateLimitsRepeatedRequestsWithoutAllocatingSessions() throws Exception {
        String remoteAddress = "198.51.100.21";
        for (int request = 0; request < 3; request++) {
            MvcResult result = mockMvc.perform(get("/api/auth/csrf")
                            .with(mock -> { mock.setRemoteAddr(remoteAddress); return mock; }))
                    .andExpect(status().isOk())
                    .andReturn();
            assertNull(result.getRequest().getSession(false));
        }

        MvcResult blocked = mockMvc.perform(get("/api/auth/csrf")
                .with(mock -> { mock.setRemoteAddr(remoteAddress); return mock; }))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string("Retry-After", matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(cookie().doesNotExist("XSRF-TOKEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();
        assertNull(blocked.getRequest().getSession(false));
        assertTrue(Long.parseLong(blocked.getResponse().getHeader("Retry-After")) <= 60);
    }

    @Test
    void csrfEndpointReturnsServiceUnavailableWhenSourceTrackingIsAtCapacity() throws Exception {
        MvcResult allowed = mockMvc.perform(get("/api/auth/csrf")
                        .with(mock -> { mock.setRemoteAddr("198.51.100.31"); return mock; }))
                .andExpect(status().isOk())
                .andReturn();
        assertNull(allowed.getRequest().getSession(false));

        MvcResult blocked = mockMvc.perform(get("/api/auth/csrf")
                        .with(mock -> { mock.setRemoteAddr("198.51.100.32"); return mock; }))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(cookie().doesNotExist("XSRF-TOKEN"))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();
        assertNull(blocked.getRequest().getSession(false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"signup", "login", "logout"})
    void authPostRejectsMissingCsrfToken(String operation) throws Exception {
        assertCsrfRejected(operation, prepareAuthPost(operation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"signup", "login", "logout"})
    void authPostRejectsMismatchedCsrfToken(String operation) throws Exception {
        CsrfData csrf = fetchCsrf(null);
        assertCsrfRejected(operation, prepareAuthPost(operation)
                .cookie(csrf.cookie())
                .header(csrf.headerName(), "mismatched-" + csrf.token()));
    }

    @Test
    void repeatedFailuresReturnRetryableLimitWithoutCreatingASession() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, PASSWORD);
        String limitedSource = "198.51.100.70";

        for (int attempt = 0; attempt < 2; attempt++) {
            MvcResult failed = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                            .with(mock -> { mock.setRemoteAddr(limitedSource); return mock; })
                            .content(objectMapper.writeValueAsBytes(
                                    new AuthRequest.Login(EMAIL, "incorrect-password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").isNotEmpty())
                    .andReturn();
            assertNull(failed.getRequest().getSession(false));
        }

        MvcResult thresholdFailure = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .with(mock -> { mock.setRemoteAddr(limitedSource); return mock; })
                        .content(objectMapper.writeValueAsBytes(
                                new AuthRequest.Login(EMAIL, "incorrect-password"))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();
        assertNull(thresholdFailure.getRequest().getSession(false));

        MvcResult blockedCorrectPassword = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .with(mock -> { mock.setRemoteAddr(limitedSource); return mock; })
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")))
                .andReturn();
        assertNull(blockedCorrectPassword.getRequest().getSession(false));

        mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .with(mock -> { mock.setRemoteAddr("198.51.100.71"); return mock; })
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, PASSWORD))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @Test
    void sourceWideLimitCountsPasswordSprayAndIgnoresForwardedFor() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        String remoteAddress = "198.51.100.80";

        for (int attempt = 0; attempt < 4; attempt++) {
            mockMvc.perform(csrfPost("/api/auth/login", csrf)
                            .with(mock -> { mock.setRemoteAddr(remoteAddress); return mock; })
                            .header("X-Forwarded-For", "203.0.113." + attempt)
                            .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(
                                    "missing-" + attempt + "@example.com", PASSWORD))))
                    .andExpect(status().isUnauthorized());
        }

        mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .with(mock -> { mock.setRemoteAddr(remoteAddress); return mock; })
                        .header("X-Forwarded-For", "203.0.113.250")
                        .content(objectMapper.writeValueAsBytes(
                                new AuthRequest.Login("missing-final@example.com", PASSWORD))))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")));
    }

    @Test
    void loginReturnsServiceUnavailableWhenProtectedSourceTrackingIsAtCapacity() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        for (int index = 0; index < 100; index++) {
            LoginAttemptLimiter.BeginDecision begin = loginAttemptLimiter.beginAttempt(
                    "198.51.100." + index, "capacity-" + index + "@example.com");
            assertTrue(begin.permitted());
        }

        MvcResult unavailable = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .with(mock -> { mock.setRemoteAddr("203.0.113.200"); return mock; })
                        .content(objectMapper.writeValueAsBytes(
                                new AuthRequest.Login("new-account@example.com", PASSWORD))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string(HttpHeaders.RETRY_AFTER, matchesPattern("[1-9][0-9]*")))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.message").value(
                        "로그인을 일시적으로 처리할 수 없습니다. 잠시 후 다시 시도해 주세요."))
                .andReturn();
        assertNull(unavailable.getRequest().getSession(false));
    }

    @Test
    void unknownEmailAndIncorrectPasswordReturnSameUnauthorizedMessage() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, PASSWORD);

        String incorrectPasswordMessage = failedLoginMessage(csrf, EMAIL, "incorrect-password");
        String unknownEmailMessage = failedLoginMessage(csrf, "missing@example.com", PASSWORD);
        assertFalse(incorrectPasswordMessage.isBlank());
        assertEquals(incorrectPasswordMessage, unknownEmailMessage);
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateSignupReturnsBadRequestAndPreservesOriginalUser() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, PASSWORD);

        mockMvc.perform(csrfPost("/api/auth/signup", csrf)
                        .content(objectMapper.writeValueAsBytes(
                                new AuthRequest.Signup(EMAIL, "replacement", "amber meadow 731"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertEquals(1, userRepository.count());
        User original = userRepository.findByEmail(EMAIL).orElseThrow();
        assertEquals("tester", original.getNickname());
        assertTrue(passwordEncoder.matches(PASSWORD, original.getPassword()));
    }

    @Test
    void signupAndLoginRejectUnsupportedEmailIdentities() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        List<String> unsupportedEmails = List.of(
                "t\u00e9st@example.com",
                "user@fa\u00df.de",
                "\u039f\u03a3@example.com",
                "\uff34\uff25\uff33\uff34@example.com",
                "\u3000user@example.com\u3000",
                "\ufdfa@a.co",
                "a".repeat(101) + "@a.co"
        );

        for (String email : unsupportedEmails) {
            mockMvc.perform(csrfPost("/api/auth/signup", csrf)
                            .content(objectMapper.writeValueAsBytes(Map.of(
                                    "email", email,
                                    "nickname", "tester",
                                    "password", PASSWORD))))
                    .andExpect(status().isBadRequest());
            mockMvc.perform(csrfPost("/api/auth/login", csrf)
                            .content(objectMapper.writeValueAsBytes(Map.of(
                                    "email", email,
                                    "password", PASSWORD))))
                    .andExpect(status().isBadRequest());
        }
        assertEquals(0, userRepository.count());
    }

    @Test
    void oversizedEmailIsLeftUntouchedForBoundedBeanValidation() {
        String oversized = "\ufdfa".repeat(101) + "@a.co";

        assertEquals(oversized, new AuthRequest.Login(oversized, PASSWORD).email());
        assertEquals(oversized, new AuthRequest.Signup(oversized, "tester", PASSWORD).email());
    }

    @Test
    void concurrentSignupWithSameEmailCreatesOneUserAndRejectsTheDuplicate() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        byte[] signupBody = objectMapper.writeValueAsBytes(new AuthRequest.Signup(EMAIL, "tester", PASSWORD));
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Callable<Integer> signupRequest = () -> {
                ready.countDown();
                assertTrue(start.await(10, TimeUnit.SECONDS), "Concurrent signup start timed out");
                return mockMvc.perform(csrfPost("/api/auth/signup", csrf).content(signupBody))
                        .andReturn().getResponse().getStatus();
            };
            Future<Integer> first = executor.submit(signupRequest);
            Future<Integer> second = executor.submit(signupRequest);
            assertTrue(ready.await(10, TimeUnit.SECONDS), "Signup workers did not become ready");
            start.countDown();

            List<Integer> statuses = List.of(
                    first.get(15, TimeUnit.SECONDS), second.get(15, TimeUnit.SECONDS));
            assertEquals(List.of(201, 400), statuses.stream().sorted().toList());
            assertEquals(1, userRepository.count());
        } finally {
            start.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS), "Signup workers did not stop");
        }
    }

    @ParameterizedTest
    @MethodSource("allowedPasswords")
    void signupAndLoginAcceptLongPrintableAsciiWithoutRequiredCharacterMix(String password) throws Exception {
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, password);

        assertTrue(passwordEncoder.matches(password,
                userRepository.findByEmail(EMAIL).orElseThrow().getPassword()));
        mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, password))))
                .andExpect(status().isOk());
    }

    @Test
    void signupRejectsPasswordsExceedingUtf8ByteLimit() throws Exception {
        String password = "a".repeat(73);
        CsrfData csrf = fetchCsrf(null);

        assertPasswordRejected("signup", csrf, password);
        assertEquals(0, userRepository.count());
    }

    @Test
    void loginRejectsPasswordWithCorrectFirst72BytesAndExtraCharacters() throws Exception {
        String password = MAX_LENGTH_PASSWORD;
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, password);

        assertPasswordRejected("login", csrf, password + "x");
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupRequiresAtLeastFifteenCharacters() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        mockMvc.perform(csrfPost("/api/auth/signup", csrf)
                        .content(objectMapper.writeValueAsBytes(
                                new AuthRequest.Signup(EMAIL, "tester", "violet-river-8"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("password: 비밀번호는 15~72자로 입력해 주세요."));
        assertEquals(0, userRepository.count());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "password1234567",
            "P@ssw0rd2026!!!!",
            "P@ssw0rdP@ssw0rd",
            "abcabcabcabcabc",
            "123456789012345",
            "qwertyuiopasdfgh",
            "1qaz2wsx3edc4rfv",
            "test202620262026"
    })
    void signupRejectsCommonPatternsAndEmailDerivedPasswordsWithoutPersistingThem(String password) throws Exception {
        CsrfData csrf = fetchCsrf(null);

        assertPasswordRejected("signup", csrf, password);
        assertEquals(0, userRepository.count());
    }

    @Test
    void signupRejectsDominantSpacePaddingWithoutPersistingIt() throws Exception {
        CsrfData csrf = fetchCsrf(null);

        assertPasswordRejected("signup", csrf, "a" + " ".repeat(14));
        assertEquals(0, userRepository.count());
    }

    @Test
    void loginRetainsOneCharacterMinimumForAnExistingAccount() throws Exception {
        userRepository.saveAndFlush(new User(EMAIL, "tester", passwordEncoder.encode("!")));
        CsrfData csrf = fetchCsrf(null);

        mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, "!"))))
                .andExpect(status().isOk());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("unsupportedPasswords")
    void signupAndLoginRejectUnsupportedCharactersWithoutReflectingPassword(
            String description, String password) throws Exception {
        CsrfData csrf = fetchCsrf(null);

        assertPasswordRejected("signup", csrf, password);
        assertPasswordRejected("login", csrf, password);
        assertEquals(0, userRepository.count());
    }

    @Test
    void passwordWhitespaceIsPreservedInsteadOfTrimmed() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        String spacedPassword = "  violet river 829  ";
        signup(csrf, spacedPassword);

        mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, spacedPassword))))
                .andExpect(status().isOk());
        mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, spacedPassword.strip()))))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void swaggerUiAndOpenApiArePublic() throws Exception {
        mockMvc.perform(get("/swagger-ui/index.html"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_HTML));
        mockMvc.perform(get("/swagger-ui.html"))
                .andExpect(status().is3xxRedirection());
        mockMvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.openapi").isNotEmpty())
                .andExpect(jsonPath("$.paths['/api/auth/login']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/signup'].post.responses['201']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.parameters").doesNotExist())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.responses['200'].content['application/json'].schema").exists())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.responses['429']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/csrf'].get.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['200'].content['application/json'].schema['$ref']")
                        .value("#/components/schemas/AuthResponse"))
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['401']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['429']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['429'].headers['Retry-After']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['503']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/login'].post.responses['503'].headers['Retry-After']").exists())
                .andExpect(jsonPath("$.paths['/api/auth/logout'].post.responses['204']").exists());
        mockMvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.url").value("/v3/api-docs"));
        mockMvc.perform(get("/swagger-ui/swagger-initializer.js"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("requestInterceptor: (request) => {")))
                .andExpect(content().string(containsString("const parts = value.split(`; XSRF-TOKEN=`);")))
                .andExpect(content().string(containsString("request.headers['X-XSRF-TOKEN'] = parts.pop().split(';').shift();")));
    }

    private MockHttpServletRequestBuilder prepareAuthPost(String operation) throws Exception {
        if (!operation.equals("signup")) {
            userRepository.saveAndFlush(new User(EMAIL, "tester", passwordEncoder.encode(PASSWORD)));
        }
        Object body = operation.equals("signup")
                ? new AuthRequest.Signup(EMAIL, "tester", PASSWORD)
                : new AuthRequest.Login(EMAIL, PASSWORD);
        MockHttpServletRequestBuilder request = post("/api/auth/" + operation)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(body));
        if (operation.equals("logout")) {
            CsrfData csrf = fetchCsrf(null);
            MvcResult login = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                            .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, PASSWORD))))
                    .andExpect(status().isOk())
                    .andReturn();
            request.session((MockHttpSession) login.getRequest().getSession(false));
        }
        return request;
    }

    private void assertCsrfRejected(String operation, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isForbidden())
                .andReturn();
        assertEquals(operation.equals("signup") ? 0 : 1, userRepository.count());
        if (operation.equals("logout")) {
            MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
            assertNotNull(session);
            mockMvc.perform(get("/api/auth/me").session(session))
                    .andExpect(status().isOk());
        }
    }

    private static Stream<String> allowedPasswords() {
        return Stream.of(
                "violet-river-82",
                "onlylowercasephrase",
                "ONLYUPPERCASEPHRASE",
                "204938576102938",
                "violet river 829",
                "!^>_&{?~#)<[$]+",
                MAX_LENGTH_PASSWORD);
    }

    private static Stream<Arguments> unsupportedPasswords() {
        Stream<Arguments> asciiControls = IntStream.rangeClosed(0, 0x1f)
                .mapToObj(value -> Arguments.of("ASCII U+%04X".formatted(value), PASSWORD + (char) value));
        Stream<Arguments> unicodeAndOtherInvalidValues = Stream.of(
                Arguments.of("DEL", PASSWORD + (char) 0x7f),
                Arguments.of("Korean", PASSWORD + "가"),
                Arguments.of("Korean at former 72-byte limit", "가".repeat(24)),
                Arguments.of("emoji", PASSWORD + "😀"),
                Arguments.of("accented letter", PASSWORD + "é"),
                Arguments.of("combining mark", PASSWORD + "e\u0301"),
                Arguments.of("fullwidth ASCII lookalike", PASSWORD + "Ａ"),
                Arguments.of("non-breaking space", PASSWORD + "\u00a0"),
                Arguments.of("thin space", PASSWORD + "\u2009"),
                Arguments.of("zero-width space", PASSWORD + "\u200b"),
                Arguments.of("next-line control", PASSWORD + "\u0085"),
                Arguments.of("Unicode line separator", PASSWORD + "\u2028"),
                Arguments.of("Unicode paragraph separator", PASSWORD + "\u2029"),
                Arguments.of("blank spaces", " ".repeat(15)),
                Arguments.of("empty password", ""),
                Arguments.of("missing password", null));
        return Stream.concat(asciiControls, unicodeAndOtherInvalidValues);
    }

    private void assertPasswordRejected(String operation, CsrfData csrf, String password) throws Exception {
        Object request = operation.equals("signup")
                ? new AuthRequest.Signup(EMAIL, "tester", password)
                : new AuthRequest.Login(EMAIL, password);
        MvcResult result = mockMvc.perform(csrfPost("/api/auth/" + operation, csrf)
                        .content(objectMapper.writeValueAsBytes(request)))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        assertEquals(1, body.size(), "Validation errors must only expose a message");
        if (password != null && !password.isEmpty()) {
            assertFalse(body.path("message").asText().contains(password), "Password must not be reflected");
        }
        assertNull(result.getRequest().getSession(false), "Invalid credentials must not create an authenticated session");
    }

    private void signup(CsrfData csrf, String password) throws Exception {
        mockMvc.perform(csrfPost("/api/auth/signup", csrf)
                        .content(objectMapper.writeValueAsBytes(
                                new AuthRequest.Signup(EMAIL, "tester", password))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.password").doesNotExist());
    }

    private String failedLoginMessage(CsrfData csrf, String email, String password) throws Exception {
        MvcResult result = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(email, password))))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty())
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsByteArray()).path("message").asText();
    }

    private CsrfData fetchCsrf(MockHttpSession session) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/auth/csrf");
        if (session != null) {
            request.session(session);
        }
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, containsString("no-store")))
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        Cookie tokenCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(tokenCookie);
        assertEquals(body.path("token").asText(), tokenCookie.getValue());
        if (session == null) {
            assertNull(result.getRequest().getSession(false), "CSRF token issuance must not allocate a server session");
        }
        return new CsrfData(tokenCookie, body.path("headerName").asText(), body.path("token").asText());
    }

    private MockHttpServletRequestBuilder csrfPost(String path, CsrfData csrf) {
        return post(path)
                .cookie(csrf.cookie())
                .header(csrf.headerName(), csrf.token())
                .contentType(MediaType.APPLICATION_JSON);
    }

    private record CsrfData(Cookie cookie, String headerName, String token) {
    }
}
