package com.ojt.board.auth;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.security.login.rate-limit")
public record LoginRateLimitProperties(
        int sourceAccountThreshold,
        int accountThreshold,
        int sourceThreshold,
        Duration initialBackoff,
        Duration maxBackoff,
        Duration recordTtl,
        Duration sourceRecordTtl,
        int maxSourceAccounts,
        int maxAccounts,
        int maxSources
) {
    public LoginRateLimitProperties {
        requirePositive(sourceAccountThreshold, "source-account-threshold");
        requirePositive(accountThreshold, "account-threshold");
        requirePositive(sourceThreshold, "source-threshold");
        requirePositive(maxSourceAccounts, "max-source-accounts");
        requirePositive(maxAccounts, "max-accounts");
        requirePositive(maxSources, "max-sources");

        long initialBackoffMillis = requirePositiveDuration(initialBackoff, "initial-backoff");
        long maxBackoffMillis = requirePositiveDuration(maxBackoff, "max-backoff");
        requirePositiveDuration(recordTtl, "record-ttl");
        requirePositiveDuration(sourceRecordTtl, "source-record-ttl");
        if (maxBackoffMillis < initialBackoffMillis) {
            throw new IllegalArgumentException("Login rate-limit max-backoff must be at least initial-backoff");
        }
    }

    private static void requirePositive(int value, String name) {
        if (value < 1) {
            throw new IllegalArgumentException("Login rate-limit " + name + " must be positive");
        }
    }

    private static long requirePositiveDuration(Duration value, String name) {
        if (value == null) {
            throw new IllegalArgumentException("Login rate-limit " + name + " is required");
        }
        final long millis;
        try {
            millis = value.toMillis();
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Login rate-limit " + name + " is too large", exception);
        }
        if (millis < 1) {
            throw new IllegalArgumentException("Login rate-limit " + name + " must be at least 1ms");
        }
        return millis;
    }
}
