package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

class CsrfTokenRateLimiterTest {

    @Test
    void blocksRequestsBeyondTheSourceBudgetAndReportsTheRemainingWindow() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(2, Duration.ofSeconds(60), 10, clock);

        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertTrue(limiter.acquire("198.51.100.10").permitted());
        CsrfTokenRateLimiter.Decision blocked = limiter.acquire("198.51.100.10");
        assertFalse(blocked.permitted());
        assertEquals(60, blocked.retryAfterSeconds());
        assertTrue(blocked.shouldLog());
        assertFalse(limiter.acquire("198.51.100.10").shouldLog());

        clock.advance(Duration.ofMillis(59_001));
        assertEquals(1, limiter.acquire("198.51.100.10").retryAfterSeconds());
        clock.advance(Duration.ofMillis(999));
        assertTrue(limiter.acquire("198.51.100.10").permitted());
    }

    @Test
    void keepsIndependentBudgetsForDifferentSources() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(1, Duration.ofMinutes(1), 10, clock);

        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertFalse(limiter.acquire("198.51.100.10").permitted());
        assertTrue(limiter.acquire("198.51.100.11").permitted());
    }

    @Test
    void boundsTrackedSourcesAndPurgesExpiredWindows() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(2, Duration.ofSeconds(30), 2, clock);

        limiter.acquire("198.51.100.10");
        limiter.acquire("198.51.100.11");
        CsrfTokenRateLimiter.Decision capacityBlocked = limiter.acquire("198.51.100.12");
        assertFalse(capacityBlocked.permitted());
        assertTrue(capacityBlocked.shouldLog());
        assertFalse(limiter.acquire("198.51.100.13").shouldLog());
        assertEquals(2, limiter.trackedSources());
        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertFalse(limiter.acquire("198.51.100.10").permitted());

        clock.advance(Duration.ofSeconds(30));
        assertTrue(limiter.acquire("198.51.100.14").permitted());
        assertEquals(1, limiter.trackedSources());
    }

    @Test
    void groupsMissingSourceAddressesIntoOneBoundedBudget() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(1, Duration.ofMinutes(1), 10, clock);

        assertTrue(limiter.acquire(null).permitted());
        assertFalse(limiter.acquire("   ").permitted());
    }

    private static CsrfTokenRateLimiter limiter(
            int maxRequests, Duration window, int maxEntries, Clock clock) {
        return new CsrfTokenRateLimiter(
                new CsrfTokenRateLimitProperties(maxRequests, window, maxEntries), clock);
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }
}
