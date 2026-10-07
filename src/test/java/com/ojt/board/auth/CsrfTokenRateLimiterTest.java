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

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class CsrfTokenRateLimiterTest {

    private static final String DECISION_METRIC = "security.csrf.token.rate.limit.decisions";
    private static final String TRACKED_SOURCES_METRIC = "security.csrf.token.rate.limit.tracked.sources";

    @Test
    void suppressesRepeatedLogsUntilTheBucketFullyRefillsAndIsRecreated() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        LimiterFixture fixture = limiter(2, 1, Duration.ofSeconds(30), 10, clock);
        CsrfTokenRateLimiter limiter = fixture.limiter();

        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertTrue(limiter.acquire("198.51.100.10").permitted());
        CsrfTokenRateLimiter.Decision blocked = limiter.acquire("198.51.100.10");
        assertFalse(blocked.permitted());
        assertEquals(CsrfTokenRateLimiter.DecisionReason.TOKEN_DEPLETED, blocked.reason());
        assertEquals(30, blocked.retryAfterSeconds());
        assertTrue(blocked.shouldLog());

        clock.advance(Duration.ofSeconds(15));
        CsrfTokenRateLimiter.Decision halfway = limiter.acquire("198.51.100.10");
        assertFalse(halfway.permitted());
        assertEquals(15, halfway.retryAfterSeconds());
        assertFalse(halfway.shouldLog());

        clock.advance(Duration.ofSeconds(15));
        assertTrue(limiter.acquire("198.51.100.10").permitted());
        CsrfTokenRateLimiter.Decision blockedAfterRefill = limiter.acquire("198.51.100.10");
        assertFalse(blockedAfterRefill.permitted());
        assertFalse(blockedAfterRefill.shouldLog());
        assertFalse(limiter.acquire("198.51.100.10").shouldLog());

        clock.advance(Duration.ofSeconds(60));
        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertTrue(limiter.acquire("198.51.100.10").permitted());
        CsrfTokenRateLimiter.Decision blockedAfterFullRefill =
                limiter.acquire("198.51.100.10");
        assertFalse(blockedAfterFullRefill.permitted());
        assertTrue(blockedAfterFullRefill.shouldLog());
    }

    @Test
    void keepsIndependentBudgetsForDifferentSources() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(
                1, 1, Duration.ofMinutes(1), 10, clock).limiter();

        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertFalse(limiter.acquire("198.51.100.10").permitted());
        assertTrue(limiter.acquire("198.51.100.11").permitted());
    }

    @Test
    void boundsTrackedSourcesAndReleasesFullyRefilledBuckets() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(
                2, 1, Duration.ofSeconds(30), 2, clock).limiter();

        limiter.acquire("198.51.100.10");
        limiter.acquire("198.51.100.11");
        CsrfTokenRateLimiter.Decision capacityBlocked = limiter.acquire("198.51.100.12");
        assertFalse(capacityBlocked.permitted());
        assertEquals(CsrfTokenRateLimiter.DecisionReason.SOURCE_CAPACITY, capacityBlocked.reason());
        assertEquals(30, capacityBlocked.retryAfterSeconds());
        assertTrue(capacityBlocked.shouldLog());
        assertFalse(limiter.acquire("198.51.100.13").shouldLog());
        assertEquals(2, limiter.trackedSources());

        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertFalse(limiter.acquire("198.51.100.10").permitted());

        clock.advance(Duration.ofSeconds(30));
        assertTrue(limiter.acquire("198.51.100.14").permitted());
        assertEquals(2, limiter.trackedSources());
    }

    @Test
    void releasesAnyFullyRefilledBucketRegardlessOfAccessOrder() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(
                3, 1, Duration.ofSeconds(10), 2, clock).limiter();

        assertTrue(limiter.acquire("198.51.100.10").permitted());
        assertTrue(limiter.acquire("198.51.100.11").permitted());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(limiter.acquire("198.51.100.10").permitted());

        clock.advance(Duration.ofSeconds(5));
        assertTrue(limiter.acquire("198.51.100.12").permitted());
        assertEquals(2, limiter.trackedSources());
        CsrfTokenRateLimiter.Decision formerSource = limiter.acquire("198.51.100.11");
        assertFalse(formerSource.permitted());
        assertEquals(CsrfTokenRateLimiter.DecisionReason.SOURCE_CAPACITY,
                formerSource.reason());
    }

    @Test
    void groupsMissingSourceAddressesIntoOneBoundedBudget() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        CsrfTokenRateLimiter limiter = limiter(
                1, 1, Duration.ofMinutes(1), 10, clock).limiter();

        assertTrue(limiter.acquire(null).permitted());
        assertFalse(limiter.acquire("   ").permitted());
    }

    @Test
    void recordsBoundedDecisionMetricsWithoutClientAddressTags() {
        MutableClock clock = new MutableClock(Instant.parse("2026-10-06T00:00:00Z"));
        LimiterFixture fixture = limiter(1, 1, Duration.ofMinutes(1), 1, clock);
        CsrfTokenRateLimiter limiter = fixture.limiter();
        SimpleMeterRegistry registry = fixture.registry();

        limiter.acquire("198.51.100.10");
        limiter.acquire("198.51.100.10");
        limiter.acquire("198.51.100.11");

        assertEquals(1.0, decisionCount(registry, "allowed", "token_available"));
        assertEquals(1.0, decisionCount(registry, "rejected", "token_depleted"));
        assertEquals(1.0, decisionCount(registry, "rejected", "source_capacity"));
        assertEquals(1.0, registry.get(TRACKED_SOURCES_METRIC).gauge().value());
        assertTrue(registry.find(DECISION_METRIC).meters().stream()
                .flatMap(meter -> meter.getId().getTags().stream())
                .noneMatch(tag -> tag.getValue().contains("198.51.100")));
    }

    private static double decisionCount(
            SimpleMeterRegistry registry,
            String outcome,
            String reason
    ) {
        return registry.get(DECISION_METRIC)
                .tags("outcome", outcome, "reason", reason)
                .counter()
                .count();
    }

    private static LimiterFixture limiter(
            int capacity,
            int refillTokens,
            Duration refillPeriod,
            int maxSources,
            Clock clock
    ) {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CsrfTokenRateLimiter limiter = new CsrfTokenRateLimiter(
                new CsrfTokenRateLimitProperties(
                        capacity, refillTokens, refillPeriod, maxSources),
                clock,
                registry);
        return new LimiterFixture(limiter, registry);
    }

    private record LimiterFixture(
            CsrfTokenRateLimiter limiter,
            SimpleMeterRegistry registry
    ) {
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
