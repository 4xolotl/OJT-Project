package com.ojt.board.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.csrf.rate-limit")
public record CsrfTokenRateLimitProperties(
        int capacity,
        int refillTokens,
        Duration refillPeriod,
        int maxSources
) {
    public CsrfTokenRateLimitProperties {
        if (capacity < 1) {
            throw new IllegalArgumentException("CSRF rate-limit capacity must be positive");
        }
        if (refillTokens < 1) {
            throw new IllegalArgumentException("CSRF rate-limit refill-tokens must be positive");
        }
        if (refillPeriod == null || refillPeriod.toMillis() < 1) {
            throw new IllegalArgumentException("CSRF rate-limit refill-period must be at least 1ms");
        }
        if (maxSources < 1) {
            throw new IllegalArgumentException("CSRF rate-limit max-sources must be positive");
        }
    }
}
