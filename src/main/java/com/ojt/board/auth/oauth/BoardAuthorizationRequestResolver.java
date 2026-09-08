package com.ojt.board.auth.oauth;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;

public final class BoardAuthorizationRequestResolver implements OAuth2AuthorizationRequestResolver {
    private final DefaultOAuth2AuthorizationRequestResolver delegate;

    public BoardAuthorizationRequestResolver(ClientRegistrationRepository registrations) {
        delegate = new DefaultOAuth2AuthorizationRequestResolver(registrations, "/oauth2/authorization");
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce()
                .andThen(builder -> builder.additionalParameters(parameters -> parameters.put("prompt", "select_account"))));
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request) {
        return withReturnTo(delegate.resolve(request), request);
    }

    @Override
    public OAuth2AuthorizationRequest resolve(HttpServletRequest request, String clientRegistrationId) {
        return withReturnTo(delegate.resolve(request, clientRegistrationId), request);
    }

    private OAuth2AuthorizationRequest withReturnTo(OAuth2AuthorizationRequest authorization, HttpServletRequest request) {
        if (authorization == null) return null;
        return OAuth2AuthorizationRequest.from(authorization).attributes(attributes -> attributes.put(
                BoardAuthorizationRequestRepository.RETURN_TO, BoardReturnUrl.sanitize(request.getParameter("returnTo"))))
                .build();
    }
}
