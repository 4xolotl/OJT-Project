package com.ojt.board.auth.oauth;

import java.net.URI;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GoogleOAuthProperties.class)
public class GoogleOAuthConfiguration {
    @Bean
    @ConditionalOnProperty(prefix = "app.oauth.google", name = "enabled", havingValue = "true")
    public ClientRegistrationRepository googleClientRegistrations(GoogleOAuthProperties properties) {
        if (properties.clientId() == null || properties.clientId().isBlank()
                || properties.clientSecret() == null || properties.clientSecret().isBlank()) {
            throw new IllegalStateException("Google 로그인을 켜려면 GOOGLE_CLIENT_ID와 GOOGLE_CLIENT_SECRET을 설정해야 합니다.");
        }
        validateRedirectUri(properties.redirectUri());
        ClientRegistration registration = CommonOAuth2Provider.GOOGLE.getBuilder("google")
                .clientId(properties.clientId()).clientSecret(properties.clientSecret())
                .redirectUri(properties.redirectUri()).scope("openid", "profile", "email").build();
        return new InMemoryClientRegistrationRepository(registration);
    }

    private static void validateRedirectUri(String value) {
        try {
            URI uri = URI.create(value);
            boolean localHttp = "http".equals(uri.getScheme())
                    && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if ((!"https".equals(uri.getScheme()) && !localHttp) || uri.getHost() == null
                    || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                    || !"/login/oauth2/code/google".equals(uri.getRawPath())) {
                throw new IllegalArgumentException();
            }
        } catch (RuntimeException exception) {
            throw new IllegalStateException("GOOGLE_REDIRECT_URI는 올바른 HTTPS 콜백 주소 또는 localhost/127.0.0.1 HTTP 콜백 주소여야 합니다.");
        }
    }
}
