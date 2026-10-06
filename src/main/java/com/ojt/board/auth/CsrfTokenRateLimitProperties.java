package com.ojt.board.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.csrf.rate-limit")
public record CsrfTokenRateLimitProperties(
        int maxRequests,
        Duration window,
        int maxEntries
) {
    public CsrfTokenRateLimitProperties {
        if (maxRequests < 1) {
            throw new IllegalArgumentException("CSRF max-requests must be positive");
        }
        if (window == null || window.toMillis() < 1) {
            throw new IllegalArgumentException("CSRF rate-limit window must be at least 1ms");
        }
        if (maxEntries < 1) {
            throw new IllegalArgumentException("CSRF max-entries must be positive");
        }
    }
}
