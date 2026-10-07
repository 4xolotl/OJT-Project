package com.ojt.board.auth;

import java.time.Clock;
import java.util.HashMap;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public final class CsrfTokenRateLimiter {

    private static final String DECISION_METRIC = "security.csrf.token.rate.limit.decisions";
    private static final String TRACKED_SOURCES_METRIC = "security.csrf.token.rate.limit.tracked.sources";

    private final int capacity;
    private final int refillTokens;
    private final long refillPeriodMillis;
    private final int maxSources;
    private final Clock clock;
    private final Counter allowedCounter;
    private final Counter rateLimitRejectedCounter;
    private final Counter sourceCapacityRejectedCounter;
    private final Map<String, TokenBucket> buckets = new HashMap<>();
    private final NavigableSet<BucketExpiry> expirations = new TreeSet<>();
    private boolean capacityRejectionReported;

    @Autowired
    public CsrfTokenRateLimiter(CsrfTokenRateLimitProperties properties, MeterRegistry meterRegistry) {
        this(properties, Clock.systemUTC(), meterRegistry);
    }

    CsrfTokenRateLimiter(
            CsrfTokenRateLimitProperties properties,
            Clock clock,
            MeterRegistry meterRegistry
    ) {
        this.capacity = properties.capacity();
        this.refillTokens = properties.refillTokens();
        this.refillPeriodMillis = properties.refillPeriod().toMillis();
        this.maxSources = properties.maxSources();
        this.clock = clock;
        this.allowedCounter = decisionCounter(meterRegistry, "allowed", "token_available");
        this.rateLimitRejectedCounter = decisionCounter(meterRegistry, "rejected", "token_depleted");
        this.sourceCapacityRejectedCounter = decisionCounter(meterRegistry, "rejected", "source_capacity");
        Gauge.builder(TRACKED_SOURCES_METRIC, this, CsrfTokenRateLimiter::trackedSources)
                .description("Number of client sources currently tracked by the CSRF token rate limiter")
                .register(meterRegistry);
    }

    public synchronized Decision acquire(String remoteAddress) {
        long now = clock.millis();
        purgeFullyRefilledBuckets(now);

        String source = normalizeSource(remoteAddress);
        TokenBucket bucket = buckets.get(source);
        if (bucket == null) {
            if (buckets.size() >= maxSources) {
                boolean shouldLog = !capacityRejectionReported;
                capacityRejectionReported = true;
                sourceCapacityRejectedCounter.increment();
                return Decision.blocked(
                        retryAfterForSourceCapacity(now), shouldLog, DecisionReason.SOURCE_CAPACITY);
            }

            putBucket(source, null, capacity - 1.0, now, false);
            allowedCounter.increment();
            return Decision.allowed();
        }

        long effectiveNow = Math.max(now, bucket.updatedAtMillis());
        double availableTokens = refilledTokens(bucket, effectiveNow);
        if (availableTokens >= 1.0) {
            // Keep the rejection latch until full refill so trickle traffic cannot amplify logs.
            putBucket(source, bucket, availableTokens - 1.0, effectiveNow,
                    bucket.rejectionReported());
            allowedCounter.increment();
            return Decision.allowed();
        }

        boolean shouldLog = !bucket.rejectionReported();
        putBucket(source, bucket, availableTokens, effectiveNow, true);
        rateLimitRejectedCounter.increment();
        return Decision.blocked(
                retryAfterForToken(availableTokens), shouldLog, DecisionReason.TOKEN_DEPLETED);
    }

    synchronized void clear() {
        buckets.clear();
        expirations.clear();
        capacityRejectionReported = false;
    }

    synchronized int trackedSources() {
        return buckets.size();
    }

    private void putBucket(
            String source,
            TokenBucket previous,
            double availableTokens,
            long updatedAtMillis,
            boolean rejectionReported
    ) {
        if (previous != null) {
            expirations.remove(new BucketExpiry(previous.fullAtMillis(), source));
        }
        long fullAtMillis = fullAtMillis(availableTokens, updatedAtMillis);
        buckets.put(source, new TokenBucket(
                availableTokens, updatedAtMillis, fullAtMillis, rejectionReported));
        expirations.add(new BucketExpiry(fullAtMillis, source));
    }

    private void purgeFullyRefilledBuckets(long now) {
        boolean removed = false;
        while (!expirations.isEmpty() && expirations.first().fullAtMillis() <= now) {
            BucketExpiry expiry = expirations.pollFirst();
            TokenBucket bucket = buckets.get(expiry.source());
            if (bucket != null && bucket.fullAtMillis() == expiry.fullAtMillis()) {
                buckets.remove(expiry.source());
                removed = true;
            }
        }
        if (removed && buckets.size() < maxSources) {
            capacityRejectionReported = false;
        }
    }

    private double refilledTokens(TokenBucket bucket, long now) {
        double replenished = (double) elapsed(now, bucket.updatedAtMillis())
                * refillTokens / refillPeriodMillis;
        return Math.min(capacity, bucket.availableTokens() + replenished);
    }

    private long fullAtMillis(double availableTokens, long updatedAtMillis) {
        double refillMillis = Math.max(0.0,
                (capacity - availableTokens) * refillPeriodMillis / refillTokens);
        return saturatedAdd(updatedAtMillis, saturatedCeiling(refillMillis));
    }

    private long retryAfterForToken(double availableTokens) {
        double millisUntilToken = (1.0 - availableTokens) * refillPeriodMillis / refillTokens;
        return retryAfterSeconds(millisUntilToken);
    }

    private long retryAfterForSourceCapacity(long now) {
        long nextReleaseAt = expirations.first().fullAtMillis();
        return retryAfterSeconds(Math.max(0.0, (double) nextReleaseAt - now));
    }

    private static long elapsed(long now, long then) {
        return Math.max(0, now - then);
    }

    private static long retryAfterSeconds(double delayMillis) {
        return Math.max(1, saturatedCeiling(delayMillis / 1000.0));
    }

    private static long saturatedCeiling(double value) {
        if (!Double.isFinite(value) || value >= Long.MAX_VALUE) {
            return Long.MAX_VALUE;
        }
        return (long) Math.ceil(Math.max(0.0, value));
    }

    private static long saturatedAdd(long value, long addition) {
        try {
            return Math.addExact(value, addition);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static String normalizeSource(String remoteAddress) {
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private static Counter decisionCounter(
            MeterRegistry meterRegistry,
            String outcome,
            String reason
    ) {
        return Counter.builder(DECISION_METRIC)
                .description("CSRF token rate limiter decisions")
                .tags("outcome", outcome, "reason", reason)
                .register(meterRegistry);
    }

    public record Decision(
            boolean permitted,
            long retryAfterSeconds,
            boolean shouldLog,
            DecisionReason reason
    ) {
        private static Decision allowed() {
            return new Decision(true, 0, false, DecisionReason.TOKEN_AVAILABLE);
        }

        private static Decision blocked(
                long retryAfterSeconds,
                boolean shouldLog,
                DecisionReason reason
        ) {
            return new Decision(false, retryAfterSeconds, shouldLog, reason);
        }
    }

    public enum DecisionReason {
        TOKEN_AVAILABLE,
        TOKEN_DEPLETED,
        SOURCE_CAPACITY
    }

    private record TokenBucket(
            double availableTokens,
            long updatedAtMillis,
            long fullAtMillis,
            boolean rejectionReported
    ) {
    }

    private record BucketExpiry(long fullAtMillis, String source) implements Comparable<BucketExpiry> {
        @Override
        public int compareTo(BucketExpiry other) {
            int timeComparison = Long.compare(fullAtMillis, other.fullAtMillis);
            return timeComparison != 0 ? timeComparison : source.compareTo(other.source);
        }
    }
}
