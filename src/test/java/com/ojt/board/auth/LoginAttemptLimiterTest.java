package com.ojt.board.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class LoginAttemptLimiterTest {

    private static final Instant START = Instant.parse("2026-10-07T00:00:00Z");

    @Test
    void validatesEveryThresholdDurationAndCapacityProperty() {
        List<Supplier<LoginRateLimitProperties>> invalid = List.of(
                () -> properties(0, 2, 3, seconds(1), seconds(4), seconds(30), seconds(10), 10, 10, 10),
                () -> properties(2, 0, 3, seconds(1), seconds(4), seconds(30), seconds(10), 10, 10, 10),
                () -> properties(2, 2, 0, seconds(1), seconds(4), seconds(30), seconds(10), 10, 10, 10),
                () -> properties(2, 2, 3, null, seconds(4), seconds(30), seconds(10), 10, 10, 10),
                () -> properties(2, 2, 3, Duration.ZERO, seconds(4), seconds(30), seconds(10), 10, 10, 10),
                () -> properties(2, 2, 3, seconds(5), seconds(4), seconds(30), seconds(10), 10, 10, 10),
                () -> properties(2, 2, 3, seconds(1), seconds(4), Duration.ZERO, seconds(10), 10, 10, 10),
                () -> properties(2, 2, 3, seconds(1), seconds(4), seconds(30), Duration.ofNanos(1), 10, 10, 10),
                () -> properties(2, 2, 3, seconds(1), seconds(4), seconds(30), seconds(10), 0, 10, 10),
                () -> properties(2, 2, 3, seconds(1), seconds(4), seconds(30), seconds(10), 10, 0, 10),
                () -> properties(2, 2, 3, seconds(1), seconds(4), seconds(30), seconds(10), 10, 10, 0),
                () -> properties(2, 2, 3, seconds(1), Duration.ofSeconds(Long.MAX_VALUE),
                        seconds(30), seconds(10), 10, 10, 10));

        for (Supplier<LoginRateLimitProperties> candidate : invalid) {
            assertThrows(IllegalArgumentException.class, candidate::get);
        }
        assertNotNull(properties(2, 2, 3, seconds(1), seconds(4), seconds(30), seconds(2), 1, 1, 1));
    }

    @Test
    void sourceAccountScopeSeparatesOtherAccountsAndSources() {
        Fixture fixture = fixture(properties(2, 10, 10,
                seconds(2), seconds(8), seconds(30), seconds(30), 20, 20, 20));

        assertTrue(fail(fixture.limiter(), "198.51.100.1", "one@example.com").permitted());
        LoginAttemptLimiter.Decision limited = fail(
                fixture.limiter(), "198.51.100.1", "one@example.com");
        assertFalse(limited.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.SOURCE_ACCOUNT_BACKOFF, limited.reason());
        assertEquals(LoginAttemptLimiter.Scope.SOURCE_ACCOUNT, limited.scope());

        LoginAttemptLimiter.BeginDecision samePair = fixture.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");
        assertFalse(samePair.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.SOURCE_ACCOUNT_BACKOFF, samePair.reason());

        cancelAllowed(fixture.limiter(), fixture.limiter().beginAttempt("198.51.100.2", "one@example.com"));
        cancelAllowed(fixture.limiter(), fixture.limiter().beginAttempt("198.51.100.1", "two@example.com"));
    }

    @Test
    void accountScopeStopsDistributedAttemptsAcrossSources() {
        Fixture fixture = fixture(properties(10, 2, 10,
                seconds(2), seconds(8), seconds(30), seconds(30), 20, 20, 20));

        assertTrue(fail(fixture.limiter(), "198.51.100.1", "victim@example.com").permitted());
        LoginAttemptLimiter.Decision limited = fail(
                fixture.limiter(), "198.51.100.2", "victim@example.com");
        assertFalse(limited.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.ACCOUNT_BACKOFF, limited.reason());
        assertEquals(LoginAttemptLimiter.Scope.ACCOUNT, limited.scope());

        cancelAllowed(fixture.limiter(), fixture.limiter()
                .beginAttempt("198.51.100.3", "different@example.com"));
    }

    @Test
    void sourceScopeStopsPasswordSprayAcrossAccounts() {
        Fixture fixture = fixture(properties(10, 10, 2,
                seconds(2), seconds(8), seconds(30), seconds(30), 20, 20, 20));

        assertTrue(fail(fixture.limiter(), "198.51.100.1", "one@example.com").permitted());
        LoginAttemptLimiter.Decision limited = fail(
                fixture.limiter(), "198.51.100.1", "two@example.com");
        assertFalse(limited.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.SOURCE_BACKOFF, limited.reason());
        assertEquals(LoginAttemptLimiter.Scope.SOURCE, limited.scope());

        cancelAllowed(fixture.limiter(), fixture.limiter()
                .beginAttempt("198.51.100.2", "three@example.com"));
    }

    @Test
    void normalizesEmailAndGroupsMissingSourcesWithoutRetainingPlainEmail() {
        Fixture fixture = fixture(properties(2, 20, 20,
                seconds(1), seconds(4), seconds(30), seconds(30), 20, 20, 20));

        assertTrue(fail(fixture.limiter(), null, "  User@Example.COM ").permitted());
        LoginAttemptLimiter.Decision limited = fail(
                fixture.limiter(), "   ", "user@example.com");
        assertFalse(limited.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.SOURCE_ACCOUNT_BACKOFF, limited.reason());

        String fingerprint = LoginAttemptLimiter.fingerprint("  User@Example.COM ");
        assertEquals(fingerprint, LoginAttemptLimiter.fingerprint("user@example.com"));
        assertEquals(64, fingerprint.length());
        assertFalse(fingerprint.contains("user@example.com"));
    }

    @Test
    void appliesExponentialBackoffWithACapAndRoundsRetryAfterUpToInitialUnits() {
        Fixture fixture = fixture(properties(1, 100, 100,
                seconds(2), seconds(5), seconds(60), seconds(60), 20, 20, 20));

        assertEquals(2, fail(fixture.limiter(), "198.51.100.1", "one@example.com")
                .retryAfterSeconds());
        fixture.clock().advance(seconds(2));
        assertEquals(4, fail(fixture.limiter(), "198.51.100.1", "one@example.com")
                .retryAfterSeconds());
        fixture.clock().advance(seconds(4));
        assertEquals(6, fail(fixture.limiter(), "198.51.100.1", "one@example.com")
                .retryAfterSeconds());
        fixture.clock().advance(seconds(5));
        assertEquals(6, fail(fixture.limiter(), "198.51.100.1", "one@example.com")
                .retryAfterSeconds());
    }

    @Test
    void successResetsAccountScopesButPreservesSourceFailures() {
        Fixture accountFixture = fixture(properties(2, 2, 100,
                seconds(1), seconds(4), seconds(30), seconds(30), 20, 20, 20));
        assertTrue(fail(accountFixture.limiter(), "198.51.100.1", "one@example.com").permitted());
        succeed(accountFixture.limiter(), "198.51.100.1", "one@example.com");
        assertTrue(fail(accountFixture.limiter(), "198.51.100.1", "one@example.com").permitted(),
                "success must reset account and source-account failure counts");

        Fixture sourceFixture = fixture(properties(100, 100, 2,
                seconds(1), seconds(4), seconds(30), seconds(30), 20, 20, 20));
        assertTrue(fail(sourceFixture.limiter(), "198.51.100.1", "one@example.com").permitted());
        succeed(sourceFixture.limiter(), "198.51.100.1", "one@example.com");
        LoginAttemptLimiter.Decision sourceLimited = fail(
                sourceFixture.limiter(), "198.51.100.1", "two@example.com");
        assertFalse(sourceLimited.permitted(), "a successful account must not reset source-wide spray history");
        assertEquals(LoginAttemptLimiter.DecisionReason.SOURCE_BACKOFF, sourceLimited.reason());
    }

    @Test
    void evictsIdleSourceRecordsAtCapacityButPreservesProtectedRecords() {
        Fixture sourceFixture = fixture(properties(100, 100, 100,
                seconds(1), seconds(4), seconds(10), seconds(2), 10, 10, 1));
        assertTrue(fail(sourceFixture.limiter(), "198.51.100.1", "one@example.com").permitted());
        LoginAttemptLimiter.BeginDecision sourceReplacement = sourceFixture.limiter()
                .beginAttempt("198.51.100.2", "two@example.com");
        assertTrue(sourceReplacement.permitted());
        assertEquals(1, sourceFixture.limiter().trackedRecords(LoginAttemptLimiter.Scope.SOURCE));
        sourceFixture.limiter().cancel(sourceReplacement.attempt());

        Fixture allInFlight = fixture(properties(100, 100, 100,
                seconds(1), seconds(4), seconds(5), seconds(5), 1, 1, 1));
        LoginAttemptLimiter.BeginDecision reserved = allInFlight.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");
        assertTrue(reserved.permitted());
        LoginAttemptLimiter.BeginDecision full = allInFlight.limiter()
                .beginAttempt("198.51.100.2", "two@example.com");
        assertFalse(full.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.STORE_CAPACITY, full.reason());
        assertEquals(LoginAttemptLimiter.Scope.SOURCE_ACCOUNT, full.scope());
        for (LoginAttemptLimiter.Scope scope : LoginAttemptLimiter.Scope.values()) {
            assertEquals(1, allInFlight.limiter().trackedRecords(scope));
        }
        allInFlight.limiter().cancel(reserved.attempt());
        cancelAllowed(allInFlight.limiter(), allInFlight.limiter()
                .beginAttempt("198.51.100.2", "two@example.com"));

        Fixture noPartialEviction = fixture(properties(100, 100, 1,
                seconds(1), seconds(4), seconds(30), seconds(30), 1, 10, 1));
        assertFalse(fail(noPartialEviction.limiter(), "198.51.100.1", "kept@example.com").permitted());
        LoginAttemptLimiter.BeginDecision rejected = noPartialEviction.limiter()
                .beginAttempt("198.51.100.2", "new@example.com");
        assertFalse(rejected.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.STORE_CAPACITY, rejected.reason());
        assertEquals(LoginAttemptLimiter.Scope.SOURCE, rejected.scope());
        assertEquals(1, noPartialEviction.limiter().trackedRecords(LoginAttemptLimiter.Scope.ACCOUNT));
        assertEquals(1, noPartialEviction.limiter().trackedRecords(LoginAttemptLimiter.Scope.SOURCE_ACCOUNT));
        assertEquals(1, noPartialEviction.limiter().trackedRecords(LoginAttemptLimiter.Scope.SOURCE));
    }

    @Test
    void accountCapacityUsesOverflowWithoutEvictingExactFailureHistory() {
        Fixture fixture = fixture(properties(100, 2, 100,
                seconds(1), seconds(4), seconds(30), seconds(30), 20, 1, 20));

        assertTrue(fail(fixture.limiter(), "198.51.100.1", "victim@example.com").permitted());
        assertTrue(fail(fixture.limiter(), "198.51.100.2", "other@example.com").permitted());
        assertEquals(1, fixture.limiter().trackedExactAccounts());
        assertEquals(1, fixture.limiter().trackedOverflowAccounts());

        LoginAttemptLimiter.Decision retained = fail(
                fixture.limiter(), "198.51.100.3", "victim@example.com");
        assertFalse(retained.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.ACCOUNT_BACKOFF, retained.reason());
    }

    @Test
    void overflowSuccessCannotResetAnotherIdentityAndAdmissionReopensAfterExpiry() {
        byte[] secret = new byte[32];
        MutableClock clock = new MutableClock(START);
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(
                properties(100, 2, 100,
                        seconds(1), seconds(4), seconds(10), seconds(10), 20, 1, 20),
                clock, secret, new SimpleMeterRegistry());
        EmailCollision collision = findCollision(secret);

        assertTrue(fail(limiter, "198.51.100.1", "exact@example.com").permitted());
        assertTrue(fail(limiter, "198.51.100.2", collision.first()).permitted());
        succeed(limiter, "198.51.100.3", collision.second());
        succeed(limiter, "198.51.100.4", "exact@example.com");

        LoginAttemptLimiter.BeginDecision remainsOverflow = limiter.beginAttempt(
                "198.51.100.5", "new-while-overflow-active@example.com");
        assertTrue(remainsOverflow.permitted());
        assertEquals(0, limiter.trackedExactAccounts());
        assertTrue(limiter.trackedOverflowAccounts() >= 1);
        limiter.cancel(remainsOverflow.attempt());

        LoginAttemptLimiter.Decision retained = fail(
                limiter, "198.51.100.6", collision.first());
        assertFalse(retained.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.ACCOUNT_BACKOFF, retained.reason());
        assertEquals(0, limiter.trackedExactAccounts());
        assertEquals(1, limiter.trackedOverflowAccounts());

        clock.advance(seconds(11));
        LoginAttemptLimiter.BeginDecision reopened = limiter.beginAttempt(
                "198.51.100.7", "after-expiry@example.com");
        assertTrue(reopened.permitted());
        assertEquals(1, limiter.trackedExactAccounts());
        assertEquals(0, limiter.trackedOverflowAccounts());
        limiter.cancel(reopened.attempt());
    }

    @Test
    void accountRecordsStayWithinExactAndFixedOverflowBounds() {
        byte[] secret = new byte[32];
        MutableClock clock = new MutableClock(START);
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(
                properties(2000, 2000, 2000,
                        seconds(1), seconds(4), seconds(30), seconds(30), 2000, 1, 2000),
                clock, secret, new SimpleMeterRegistry());

        for (int index = 0; index < 1500; index++) {
            assertTrue(fail(limiter, "198.51." + index / 256 + "." + index % 256,
                    "account-" + index + "@example.com").permitted());
        }

        assertEquals(1, limiter.trackedExactAccounts());
        assertTrue(limiter.trackedOverflowAccounts() <= 1024);
        assertEquals(limiter.trackedExactAccounts() + limiter.trackedOverflowAccounts(),
                limiter.trackedRecords(LoginAttemptLimiter.Scope.ACCOUNT));
    }

    @Test
    void reservesAllScopesAtomicallyToBoundConcurrentBursts() {
        Fixture pairFixture = fixture(properties(2, 3, 4,
                seconds(1), seconds(4), seconds(30), seconds(30), 20, 20, 20));
        LoginAttemptLimiter.BeginDecision first = pairFixture.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");
        LoginAttemptLimiter.BeginDecision second = pairFixture.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");
        assertTrue(first.permitted());
        assertTrue(second.permitted());
        LoginAttemptLimiter.BeginDecision third = pairFixture.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");
        assertFalse(third.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.SOURCE_ACCOUNT_IN_FLIGHT, third.reason());
        assertNull(third.attempt());

        pairFixture.limiter().cancel(first.attempt());
        LoginAttemptLimiter.BeginDecision replacement = pairFixture.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");
        assertTrue(replacement.permitted());
        pairFixture.limiter().cancel(second.attempt());
        pairFixture.limiter().cancel(replacement.attempt());

        Fixture accountFixture = fixture(properties(10, 2, 10,
                seconds(1), seconds(4), seconds(30), seconds(30), 20, 20, 20));
        LoginAttemptLimiter.BeginDecision accountOne = accountFixture.limiter()
                .beginAttempt("198.51.100.1", "victim@example.com");
        LoginAttemptLimiter.BeginDecision accountTwo = accountFixture.limiter()
                .beginAttempt("198.51.100.2", "victim@example.com");
        LoginAttemptLimiter.BeginDecision accountThree = accountFixture.limiter()
                .beginAttempt("198.51.100.3", "victim@example.com");
        assertFalse(accountThree.permitted());
        assertEquals(LoginAttemptLimiter.DecisionReason.ACCOUNT_IN_FLIGHT, accountThree.reason());
        accountFixture.limiter().cancel(accountOne.attempt());
        accountFixture.limiter().cancel(accountTwo.attempt());
    }

    @Test
    void ticketsAreOpaqueOwnedAndSingleUse() {
        Fixture owner = fixture(defaultProperties());
        Fixture other = fixture(defaultProperties());
        LoginAttemptLimiter.BeginDecision begin = owner.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");

        assertThrows(IllegalStateException.class, () -> other.limiter().cancel(begin.attempt()));
        owner.limiter().cancel(begin.attempt());
        assertThrows(IllegalStateException.class, () -> owner.limiter().cancel(begin.attempt()));
        assertThrows(IllegalStateException.class, () -> owner.limiter().completeFailure(begin.attempt()));
        assertThrows(IllegalStateException.class, () -> owner.limiter().completeSuccess(begin.attempt()));
        assertThrows(IllegalArgumentException.class, () -> owner.limiter().cancel(null));
        assertEquals(0, owner.limiter().inFlightAttempts());
    }

    @Test
    void injectedWallClockRollbackCannotExpireABackoffEarly() {
        MutableClock clock = new MutableClock(START);
        Fixture fixture = fixture(properties(1, 100, 100,
                seconds(10), seconds(40), seconds(60), seconds(60), 20, 20, 20), clock);
        assertEquals(10, fail(fixture.limiter(), "198.51.100.1", "one@example.com")
                .retryAfterSeconds());

        clock.set(START.minusSeconds(30));
        LoginAttemptLimiter.BeginDecision rolledBack = fixture.limiter()
                .beginAttempt("198.51.100.1", "one@example.com");
        assertFalse(rolledBack.permitted());
        assertEquals(40, rolledBack.retryAfterSeconds());

        clock.set(START.plusSeconds(9));
        assertFalse(fixture.limiter().beginAttempt("198.51.100.1", "one@example.com").permitted());
        clock.set(START.plusSeconds(10));
        cancelAllowed(fixture.limiter(), fixture.limiter()
                .beginAttempt("198.51.100.1", "one@example.com"));
    }

    @Test
    void metricsUseOnlyBoundedOutcomeReasonAndScopeTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        MutableClock clock = new MutableClock(START);
        LoginAttemptLimiter limiter = new LoginAttemptLimiter(
                properties(1, 10, 10, seconds(1), seconds(4), seconds(30), seconds(30), 20, 20, 20),
                clock, registry);

        fail(limiter, "198.51.100.77", "private.person@example.com");
        limiter.beginAttempt("198.51.100.77", "private.person@example.com");

        assertFalse(registry.find(LoginAttemptLimiter.DECISION_METRIC).meters().isEmpty());
        assertEquals(3, registry.find(LoginAttemptLimiter.TRACKED_RECORDS_METRIC).gauges().size());
        assertEquals(2, registry.find(LoginAttemptLimiter.ACCOUNT_RECORDS_METRIC).gauges().size());
        assertNotNull(registry.find(LoginAttemptLimiter.ACCOUNT_OVERFLOW_ACTIVE_METRIC).gauge());
        for (Meter meter : registry.getMeters()) {
            for (var tag : meter.getId().getTags()) {
                assertFalse(tag.getValue().contains("198.51.100.77"));
                assertFalse(tag.getValue().contains("private.person"));
                assertTrue(tag.getKey().equals("outcome") || tag.getKey().equals("reason")
                                || tag.getKey().equals("scope") || tag.getKey().equals("kind"),
                        "unexpected high-cardinality metric tag: " + tag.getKey());
            }
        }
    }

    private static LoginAttemptLimiter.Decision fail(
            LoginAttemptLimiter limiter,
            String source,
            String email
    ) {
        LoginAttemptLimiter.BeginDecision begin = limiter.beginAttempt(source, email);
        assertTrue(begin.permitted(), "test setup expected the attempt to be admitted");
        assertNotNull(begin.attempt());
        return limiter.completeFailure(begin.attempt());
    }

    private static void succeed(LoginAttemptLimiter limiter, String source, String email) {
        LoginAttemptLimiter.BeginDecision begin = limiter.beginAttempt(source, email);
        assertTrue(begin.permitted());
        limiter.completeSuccess(begin.attempt());
    }

    private static void cancelAllowed(
            LoginAttemptLimiter limiter,
            LoginAttemptLimiter.BeginDecision begin
    ) {
        assertTrue(begin.permitted());
        limiter.cancel(begin.attempt());
    }

    private static Fixture fixture(LoginRateLimitProperties properties) {
        return fixture(properties, new MutableClock(START));
    }

    private static Fixture fixture(LoginRateLimitProperties properties, MutableClock clock) {
        return new Fixture(new LoginAttemptLimiter(properties, clock, new SimpleMeterRegistry()), clock);
    }

    private static EmailCollision findCollision(byte[] secret) {
        Map<Integer, String> firstByBucket = new HashMap<>();
        for (int index = 0; index < 10_000; index++) {
            String email = "collision-" + index + "@example.com";
            int bucket = EmailFingerprint.bucket(
                    LoginAttemptLimiter.fingerprint(email), secret, 1024);
            String first = firstByBucket.putIfAbsent(bucket, email);
            if (first != null) {
                return new EmailCollision(first, email);
            }
        }
        throw new AssertionError("Expected to find a deterministic overflow-bucket collision");
    }

    private static LoginRateLimitProperties defaultProperties() {
        return properties(3, 4, 5, seconds(1), seconds(8), seconds(30), seconds(10), 20, 20, 20);
    }

    private static LoginRateLimitProperties properties(
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
        return new LoginRateLimitProperties(sourceAccountThreshold, accountThreshold, sourceThreshold,
                initialBackoff, maxBackoff, recordTtl, sourceRecordTtl,
                maxSourceAccounts, maxAccounts, maxSources);
    }

    private static Duration seconds(long seconds) {
        return Duration.ofSeconds(seconds);
    }

    private record Fixture(LoginAttemptLimiter limiter, MutableClock clock) {
    }

    private record EmailCollision(String first, String second) {
    }

    private static final class MutableClock extends Clock {
        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }

        private void set(Instant value) {
            instant = value;
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
