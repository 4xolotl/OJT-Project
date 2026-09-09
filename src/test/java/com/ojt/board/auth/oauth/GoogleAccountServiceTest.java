package com.ojt.board.auth.oauth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.doReturn;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ojt.board.auth.AuthResponse;
import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import com.ojt.board.user.UserRole;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:google-accounts;MODE=MariaDB;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class GoogleAccountServiceTest {

    private static final String LOCAL_PASSWORD_HASH = new BCryptPasswordEncoder().encode("password123");

    @Autowired private GoogleAccountService accountService;
    @Autowired private UserRepository userRepository;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbcTemplate;
    @MockitoSpyBean private OAuthAccountRepository oauthAccountRepository;

    @BeforeEach
    void cleanUpPreviousData() {
        oauthAccountRepository.deleteAll();
        userRepository.deleteAll();
    }

    @Test
    void createsPasswordlessLocalAccountAndStableProviderLink() {
        OidcUser providerUser = identity("subject-one", "google@example.com", true, "Google 회원");
        BoardOidcUser principal = accountService.loadOrCreate(providerUser);

        User stored = userRepository.findById(principal.getId()).orElseThrow();
        assertEquals("google@example.com", stored.getEmail());
        assertEquals("Google 회원", stored.getNickname());
        assertNull(stored.getPassword());
        assertEquals(UserRole.USER, stored.getRole());
        assertEquals(1, userRepository.count());
        assertEquals(1, oauthAccountRepository.count());
        OAuthAccount account = oauthAccountRepository.findByProviderAndSubject("google", "subject-one").orElseThrow();
        assertEquals(stored.getId(), account.getUser().getId());
        assertTrue(userRepository.findByEmailAndPasswordIsNotNull(stored.getEmail()).isEmpty());
        assertEquals(principal.getId().toString(), principal.getName());
        assertSame(providerUser.getIdToken(), principal.getIdToken());
        assertEquals(providerUser.getClaims(), principal.getClaims());
    }

    @Test
    void repeatedSubjectKeepsOriginalAccountEvenWhenEmailChangesToAnotherUsersEmail() {
        BoardOidcUser first = accountService.loadOrCreate(identity("stable-subject", "original@example.com", true, "처음 이름"));
        User local = userRepository.saveAndFlush(new User("local@example.com", "일반 회원", LOCAL_PASSWORD_HASH));

        BoardOidcUser repeated = accountService.loadOrCreate(identity("stable-subject", local.getEmail(), true, "새 이름"));

        assertEquals(first.getId(), repeated.getId());
        assertNotEquals(local.getId(), repeated.getId());
        assertEquals("original@example.com", repeated.getEmail());
        assertEquals("처음 이름", repeated.getNickname());
        assertEquals(2, userRepository.count());
        assertEquals(1, oauthAccountRepository.count());
        assertEquals(LOCAL_PASSWORD_HASH, userRepository.findById(local.getId()).orElseThrow().getPassword());
    }

    @Test
    void emailMatchDoesNotLinkOrModifyExistingPasswordAccount() {
        User local = userRepository.saveAndFlush(new User("existing@example.com", "기존 회원", LOCAL_PASSWORD_HASH));

        OAuth2AuthenticationException failure = assertThrows(OAuth2AuthenticationException.class,
                () -> accountService.loadOrCreate(identity("new-subject", local.getEmail(), true, "외부 이름")));

        assertEquals("email_conflict", failure.getError().getErrorCode());
        assertFalse(failure.getMessage().contains(local.getEmail()));
        assertEquals(1, userRepository.count());
        assertEquals(0, oauthAccountRepository.count());
        User unchanged = userRepository.findById(local.getId()).orElseThrow();
        assertEquals("기존 회원", unchanged.getNickname());
        assertEquals(LOCAL_PASSWORD_HASH, unchanged.getPassword());
        assertEquals(local.getId(), userRepository.findByEmailAndPasswordIsNotNull(local.getEmail()).orElseThrow().getId());
    }

    @Test
    void differentProviderSubjectCannotTakeOverExistingGoogleEmail() {
        BoardOidcUser first = accountService.loadOrCreate(identity("original-subject", "shared@example.com", true, "원래 회원"));

        OAuth2AuthenticationException failure = assertThrows(OAuth2AuthenticationException.class,
                () -> accountService.loadOrCreate(identity("different-subject", first.getEmail(), true, "다른 회원")));

        assertEquals("email_conflict", failure.getError().getErrorCode());
        assertEquals(1, userRepository.count());
        assertEquals(1, oauthAccountRepository.count());
    }

    @Test
    void caseSensitiveSubjectsRemainDifferentIdentities() {
        BoardOidcUser first = accountService.loadOrCreate(identity("CaseSensitive", "first@example.com", true, "첫 회원"));
        BoardOidcUser second = accountService.loadOrCreate(identity("casesensitive", "second@example.com", true, "둘째 회원"));

        assertNotEquals(first.getId(), second.getId());
        assertEquals(2, oauthAccountRepository.count());
    }

    @Test
    void uniqueSubjectConflictRollsBackTheNewLocalUser() {
        BoardOidcUser original = accountService.loadOrCreate(identity("racing-subject", "first@example.com", true, "기존 회원"));
        // Simulate the pre-check missing a row that a competing login has already committed.
        doReturn(Optional.empty()).when(oauthAccountRepository).findByProviderAndSubject("google", "racing-subject");

        assertThrows(DataIntegrityViolationException.class,
                () -> accountService.loadOrCreate(identity("racing-subject", "second@example.com", true, "경합 회원")));

        assertEquals(1, userRepository.count());
        assertEquals(1, oauthAccountRepository.count());
        assertTrue(userRepository.findByEmail("second@example.com").isEmpty());
        assertTrue(userRepository.existsById(original.getId()));
    }

    @Test
    void providerAuthoritiesCannotGrantApplicationAdminRole() {
        BoardOidcUser principal = accountService.loadOrCreate(identity("roles", "roles@example.com", true, "권한 확인"));

        assertEquals(Set.of("ROLE_USER"), principal.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(Collectors.toSet()));
    }

    @Test
    void subsequentGoogleLoginUsesRoleExplicitlyAssignedToLocalAccount() {
        OidcUser providerUser = identity("local-admin", "admin@example.com", true, "담당자");
        BoardOidcUser original = accountService.loadOrCreate(providerUser);
        assertEquals(1, jdbcTemplate.update("UPDATE users SET role = ? WHERE id = ?", "ADMIN", original.getId()));

        BoardOidcUser signedInAgain = accountService.loadOrCreate(providerUser);

        assertEquals(original.getId(), signedInAgain.getId());
        assertEquals(Set.of("ROLE_USER", "ROLE_ADMIN"), signedInAgain.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(Collectors.toSet()));
        assertEquals(Set.of("ROLE_USER"), original.getAuthorities().stream()
                .map(authority -> authority.getAuthority()).collect(Collectors.toSet()));
        assertEquals(1, userRepository.count());
    }

    @Test
    void apiResponseContainsOnlyLocalPublicIdentityFields() {
        BoardOidcUser principal = accountService.loadOrCreate(identity("private-provider-subject", "safe@example.com", true, "안전 회원"));

        JsonNode response = objectMapper.valueToTree(AuthResponse.from(principal));

        assertEquals(3, response.size());
        assertEquals(principal.getId().longValue(), response.path("id").asLong());
        assertEquals(principal.getEmail(), response.path("email").asText());
        assertEquals(principal.getNickname(), response.path("nickname").asText());
        assertFalse(response.toString().contains("test-id-token"));
        assertFalse(response.toString().contains("private-provider-subject"));
    }

    @Test
    void acceptsEmailAt254CharacterLimit() {
        String email = "a".repeat(64) + "@" + "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61);
        assertEquals(254, email.length());

        BoardOidcUser principal = accountService.loadOrCreate(identity("long-email", email, true, "긴 이메일"));

        assertEquals(email, userRepository.findById(principal.getId()).orElseThrow().getEmail());
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "not-an-email", "person@", "oversized"})
    void invalidEmailDoesNotCreateAccount(String value) {
        String email = value.equals("oversized") ? "a".repeat(64) + "@" + "b".repeat(63) + "."
                + "c".repeat(63) + "." + "d".repeat(62) : value;

        assertIdentityRejected(identity("bad-email", email, true, "회원"), "invalid_identity");
    }

    @Test
    void missingEmailDoesNotCreateAccount() {
        assertIdentityRejected(identity("missing-email", null, true, "회원"), "invalid_identity");
    }

    @Test
    void unverifiedEmailDoesNotCreateAccount() {
        assertIdentityRejected(identity("unverified", "unverified@example.com", false, "회원"), "unverified_email");
    }

    @Test
    void missingEmailVerificationDoesNotCreateAccount() {
        Map<String, Object> claims = claims("no-verification", "missing@example.com", true, "회원");
        claims.remove("email_verified");
        assertIdentityRejected(identity(claims), "unverified_email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "https://other.example.com", "https://accounts.google.com.attacker.example"})
    void unexpectedIssuerDoesNotCreateAccount(String issuer) {
        Map<String, Object> claims = claims("bad-issuer", "issuer@example.com", true, "회원");
        claims.put("iss", issuer);
        assertIdentityRejected(identity(claims), "invalid_identity");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "contains\nnewline", "한글", "oversized"})
    void invalidSubjectDoesNotCreateAccount(String value) {
        String subject = value.equals("oversized") ? "s".repeat(256) : value;
        assertIdentityRejected(identity(subject, "subject@example.com", true, "회원"), "invalid_identity");
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" \t\r\n "})
    void absentOrBlankNameUsesFallback(String name) {
        BoardOidcUser principal = accountService.loadOrCreate(identity("fallback", "fallback@example.com", true, name));
        assertEquals("Google 사용자", principal.getNickname());
    }

    @Test
    void nicknameDropsControlsAndDoesNotSplitSurrogatePairsAtLimit() {
        String name = "\n\t" + "가".repeat(99) + "😀추가";
        BoardOidcUser principal = accountService.loadOrCreate(identity("long-name", "name@example.com", true, name));

        assertEquals("가".repeat(99), principal.getNickname());
        assertFalse(principal.getNickname().chars().anyMatch(Character::isISOControl));
        assertFalse(Character.isHighSurrogate(principal.getNickname().charAt(principal.getNickname().length() - 1)));
    }

    private void assertIdentityRejected(OidcUser identity, String errorCode) {
        OAuth2AuthenticationException failure = assertThrows(OAuth2AuthenticationException.class,
                () -> accountService.loadOrCreate(identity));
        assertEquals(errorCode, failure.getError().getErrorCode());
        assertEquals(0, userRepository.count());
        assertEquals(0, oauthAccountRepository.count());
    }

    private OidcUser identity(String subject, String email, boolean verified, String name) {
        return identity(claims(subject, email, verified, name));
    }

    private Map<String, Object> claims(String subject, String email, boolean verified, String name) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("iss", "https://accounts.google.com");
        claims.put("sub", subject);
        if (email != null) {
            claims.put("email", email);
        }
        claims.put("email_verified", verified);
        if (name != null) {
            claims.put("name", name);
        }
        return claims;
    }

    private OidcUser identity(Map<String, Object> claims) {
        Instant now = Instant.now();
        OidcIdToken token = new OidcIdToken("test-id-token", now.minusSeconds(10), now.plusSeconds(300), claims);
        return new DefaultOidcUser(List.of(new SimpleGrantedAuthority("ROLE_ADMIN"),
                new SimpleGrantedAuthority("SCOPE_profile")), token);
    }
}
