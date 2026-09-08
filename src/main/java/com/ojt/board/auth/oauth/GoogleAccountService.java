package com.ojt.board.auth.oauth;

import com.ojt.board.user.User;
import com.ojt.board.user.UserRepository;
import jakarta.validation.Validator;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class GoogleAccountService {

    private static final String PROVIDER = "google";
    private final OAuthAccountRepository oauthAccountRepository;
    private final UserRepository userRepository;
    private final Validator validator;

    /** The caller must first authenticate the ID token through Spring Security's OIDC provider. */
    @Transactional
    public BoardOidcUser loadOrCreate(OidcUser oidcUser) {
        String issuer = oidcUser.getClaimAsString("iss");
        if (!"https://accounts.google.com".equals(issuer) && !"accounts.google.com".equals(issuer)) {
            throw invalidIdentity();
        }
        Object rawSubject = oidcUser.getClaims().get("sub");
        if (!(rawSubject instanceof String subject) || subject.isEmpty() || subject.length() > 255
                || subject.chars().anyMatch(character -> character < 0x21 || character > 0x7E)) {
            throw invalidIdentity();
        }
        if (!Boolean.TRUE.equals(oidcUser.getClaims().get("email_verified"))) {
            throw new OAuth2AuthenticationException(new OAuth2Error("unverified_email",
                    "Google에서 확인된 이메일이 필요합니다.", null));
        }
        String email = oidcUser.getEmail();
        if (!validator.validate(new EmailCandidate(email)).isEmpty()) {
            throw invalidIdentity();
        }

        // The stable provider subject owns the link. An email/profile change never creates a new link.
        OAuthAccount existing = oauthAccountRepository.findByProviderAndSubject(PROVIDER, subject).orElse(null);
        if (existing != null) {
            return new BoardOidcUser(existing.getUser(), oidcUser);
        }
        if (userRepository.existsByEmail(email)) {
            throw new OAuth2AuthenticationException(new OAuth2Error("email_conflict",
                    "이미 가입된 이메일입니다. 기존 로그인 방법으로 로그인해 주세요.", null));
        }

        User user = userRepository.save(new User(email, safeNickname(oidcUser.getFullName()), null));
        // Flush so a competing first login cannot leave a second local account behind after a unique-key failure.
        oauthAccountRepository.saveAndFlush(new OAuthAccount(PROVIDER, subject, user));
        return new BoardOidcUser(user, oidcUser);
    }

    private String safeNickname(String name) {
        if (name == null) {
            return "Google 사용자";
        }
        StringBuilder cleaned = new StringBuilder();
        name.codePoints().filter(character -> !Character.isISOControl(character)
                        && !(character >= Character.MIN_SURROGATE && character <= Character.MAX_SURROGATE))
                .forEach(cleaned::appendCodePoint);
        String nickname = cleaned.toString().strip();
        if (nickname.isBlank()) {
            return "Google 사용자";
        }
        if (nickname.length() > 100) {
            int end = Character.isHighSurrogate(nickname.charAt(99)) ? 99 : 100;
            nickname = nickname.substring(0, end);
        }
        return nickname;
    }

    private OAuth2AuthenticationException invalidIdentity() {
        return new OAuth2AuthenticationException(new OAuth2Error("invalid_identity",
                "Google 사용자 정보를 확인할 수 없습니다.", null));
    }

    private record EmailCandidate(@NotBlank @Email @Size(max = 254) String value) {}
}
