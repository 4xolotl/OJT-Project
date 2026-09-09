package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import jakarta.servlet.http.Cookie;
import java.util.List;
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
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthControllerTest {

    private static final String EMAIL = "test@example.com";
    private static final String PASSWORD = "password123";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        userRepository.deleteAll();
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
        assertEquals(oldSessionId, authenticatedSession.getId());

        mockMvc.perform(get("/api/auth/me").session(authenticatedSession))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL))
                .andExpect(jsonPath("$.password").doesNotExist());

        // A browser discards the cleared token cookie and obtains a new token after login.
        CsrfData authenticatedCsrf = fetchCsrf(authenticatedSession);
        assertNotEquals(initialCsrf.token(), authenticatedCsrf.token());

        mockMvc.perform(post("/api/auth/logout").session(authenticatedSession))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("JSESSIONID", 0));
        assertTrue(authenticatedSession.isInvalid());

        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void anonymousMeReturnsJsonUnauthorized() throws Exception {
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.message").isNotEmpty());
    }

    @Test
    void csrfEndpointInitializesAnAnonymousSessionAndRetainsItOnLogin() throws Exception {
        MvcResult initial = mockMvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andReturn();
        MockHttpSession session = (MockHttpSession) initial.getRequest().getSession(false);
        assertNotNull(session);
        String sessionId = session.getId();
        CsrfData csrf = fetchCsrf(session);
        signup(csrf, "1234");

        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());
        MvcResult login = mockMvc.perform(csrfPost("/api/auth/login", csrf)
                        .session(session)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, "1234"))))
                .andExpect(status().isOk())
                .andReturn();

        assertEquals(sessionId, login.getRequest().getSession(false).getId());
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
    }

    @ParameterizedTest
    @ValueSource(strings = {"signup", "login", "logout"})
    void authPostAcceptsMissingCsrfToken(String operation) throws Exception {
        assertSuccessfulAuthPost(operation, prepareAuthPost(operation));
    }

    @ParameterizedTest
    @ValueSource(strings = {"signup", "login", "logout"})
    void authPostAcceptsMismatchedCsrfToken(String operation) throws Exception {
        CsrfData csrf = fetchCsrf(null);
        assertSuccessfulAuthPost(operation, prepareAuthPost(operation)
                .cookie(csrf.cookie())
                .header(csrf.headerName(), "mismatched-" + csrf.token()));
    }

    @Test
    void correctPasswordAuthenticatesAfterEightIncorrectAttempts() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, PASSWORD);
        MockHttpSession session = new MockHttpSession();

        for (int attempt = 0; attempt < 8; attempt++) {
            mockMvc.perform(post("/api/auth/login").session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(
                                    new AuthRequest.Login(EMAIL, "incorrect-password"))))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.message").isNotEmpty());
        }
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/auth/login").session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, PASSWORD))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(EMAIL));
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
                                new AuthRequest.Signup(EMAIL, "replacement", "different-password"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").isNotEmpty());

        assertEquals(1, userRepository.count());
        User original = userRepository.findByEmail(EMAIL).orElseThrow();
        assertEquals("tester", original.getNickname());
        assertTrue(passwordEncoder.matches(PASSWORD, original.getPassword()));
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
    void signupAndLoginAcceptPrintableAsciiWithoutRequiredCharacterMix(String password) throws Exception {
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
        String password = "a".repeat(72);
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, password);

        assertPasswordRejected("login", csrf, password + "x");
        mockMvc.perform(get("/api/auth/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signupRequiresAtLeastFourCharacters() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        assertPasswordRejected("signup", csrf, "a".repeat(3));
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
    void loginRejectsWhitespaceAroundCorrectPasswordInsteadOfTrimmingIt() throws Exception {
        CsrfData csrf = fetchCsrf(null);
        signup(csrf, PASSWORD);

        assertPasswordRejected("login", csrf, " " + PASSWORD);
        assertPasswordRejected("login", csrf, PASSWORD + " ");
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
            MvcResult login = mockMvc.perform(post("/api/auth/login")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsBytes(new AuthRequest.Login(EMAIL, PASSWORD))))
                    .andExpect(status().isOk())
                    .andReturn();
            request.session((MockHttpSession) login.getRequest().getSession(false));
        }
        return request;
    }

    private void assertSuccessfulAuthPost(String operation, MockHttpServletRequestBuilder request) throws Exception {
        int expectedStatus = switch (operation) {
            case "signup" -> 201;
            case "login" -> 200;
            case "logout" -> 204;
            default -> throw new IllegalArgumentException(operation);
        };
        MvcResult result = mockMvc.perform(request)
                .andExpect(status().is(expectedStatus))
                .andReturn();
        assertEquals(1, userRepository.count());
        if (operation.equals("login")) {
            MockHttpSession session = (MockHttpSession) result.getRequest().getSession(false);
            assertNotNull(session);
            mockMvc.perform(get("/api/auth/me").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.email").value(EMAIL));
        } else if (operation.equals("logout")) {
            assertNull(result.getRequest().getSession(false));
            assertNotNull(result.getResponse().getCookie("JSESSIONID"));
            assertEquals(0, result.getResponse().getCookie("JSESSIONID").getMaxAge());
        }
    }

    private static Stream<String> allowedPasswords() {
        String asciiSymbols = IntStream.rangeClosed(0x21, 0x7e)
                .filter(value -> !Character.isLetterOrDigit(value))
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append)
                .toString();
        return Stream.of("abcd", "ABCD", "1234", "!!!!", "abcdefgh", "ABCDEFGH", "12345678", "!!!!!!!!", asciiSymbols,
                "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789", "a".repeat(72));
    }

    private static Stream<Arguments> unsupportedPasswords() {
        Stream<Arguments> asciiControlsAndSpace = IntStream.rangeClosed(0, 0x20)
                .mapToObj(value -> Arguments.of("ASCII U+%04X".formatted(value), PASSWORD + (char) value));
        Stream<Arguments> unicodeAndOtherInvalidValues = Stream.of(
                Arguments.of("DEL", PASSWORD + (char) 0x7f),
                Arguments.of("leading space", " " + PASSWORD),
                Arguments.of("internal space", "pass word123"),
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
                Arguments.of("empty password", ""),
                Arguments.of("missing password", null));
        return Stream.concat(asciiControlsAndSpace, unicodeAndOtherInvalidValues);
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
                .andExpect(jsonPath("$.token").isNotEmpty())
                .andExpect(jsonPath("$.headerName").value("X-XSRF-TOKEN"))
                .andExpect(jsonPath("$.parameterName").value("_csrf"))
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        Cookie tokenCookie = result.getResponse().getCookie("XSRF-TOKEN");
        assertNotNull(tokenCookie);
        assertEquals(body.path("token").asText(), tokenCookie.getValue());
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
