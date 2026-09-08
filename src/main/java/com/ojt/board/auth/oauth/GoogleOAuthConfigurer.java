package com.ojt.board.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "app.oauth.google", name = "enabled", havingValue = "true")
public class GoogleOAuthConfigurer {
    private final ClientRegistrationRepository registrations;
    private final GoogleAccountService accounts;

    public void configure(HttpSecurity http) throws Exception {
        OidcUserService delegate = new OidcUserService();
        http.oauth2Login(oauth -> oauth
                .loginPage("/login.html")
                .clientRegistrationRepository(registrations)
                .authorizedClientRepository(new DiscardingAuthorizedClientRepository())
                .authorizationEndpoint(endpoint -> endpoint
                        .authorizationRequestResolver(new BoardAuthorizationRequestResolver(registrations))
                        .authorizationRequestRepository(new BoardAuthorizationRequestRepository()))
                .redirectionEndpoint(endpoint -> endpoint.baseUri("/login/oauth2/code/google"))
                .userInfoEndpoint(endpoint -> endpoint.oidcUserService(request -> {
                    if (!"google".equals(request.getClientRegistration().getRegistrationId())) {
                        throw new OAuth2AuthenticationException(new OAuth2Error("invalid_identity"));
                    }
                    try {
                        return accounts.loadOrCreate(delegate.loadUser(request));
                    } catch (DataIntegrityViolationException exception) {
                        // A simultaneous signup can win a unique constraint. Do not retry or auto-link by email.
                        throw new OAuth2AuthenticationException(new OAuth2Error("failed"));
                    }
                }))
                .successHandler((request, response, authentication) -> {
                    var session = request.getSession(false);
                    if (session != null) session.removeAttribute("SPRING_SECURITY_LAST_EXCEPTION");
                    response.sendRedirect(returnTo(request));
                })
                .failureHandler((request, response, exception) -> {
                    String errorCode = "failed";
                    if (exception instanceof OAuth2AuthenticationException oauthException) {
                        String providerCode = oauthException.getError().getErrorCode();
                        if ("access_denied".equals(providerCode)) errorCode = "cancelled";
                        else if ("unverified_email".equals(providerCode)) errorCode = "invalid_identity";
                        else if (Set.of("email_conflict", "invalid_identity").contains(providerCode)) errorCode = providerCode;
                    }
                    response.sendRedirect(BoardReturnUrl.loginFailure(errorCode, returnTo(request)));
                }));
    }

    private static String returnTo(HttpServletRequest request) {
        Object value = request.getAttribute(BoardAuthorizationRequestRepository.RETURN_TO);
        return BoardReturnUrl.sanitize(value instanceof String string ? string : null);
    }
}
