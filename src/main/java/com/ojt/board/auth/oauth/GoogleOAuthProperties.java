package com.ojt.board.auth.oauth;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.oauth.google")
public record GoogleOAuthProperties(boolean enabled, String clientId, String clientSecret, String redirectUri) {
    // Do not include client credentials in generated record toString() output.
    @Override
    public String toString() {
        return "GoogleOAuthProperties[enabled=" + enabled + "]";
    }
}
