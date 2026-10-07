package com.ojt.board.auth;

import java.security.SecureRandom;
import java.time.Clock;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.NavigableSet;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.ojt.board.user.EmailCanonicalizer;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public final class LoginAttemptLimiter {

    static final String DECISION_METRIC = "security.login.rate.limit.decisions";
    static final String TRACKED_RECORDS_METRIC = "security.login.rate.limit.tracked.records";
    static final String IN_FLIGHT_METRIC = "security.login.rate.limit.inflight.attempts";
    static final String ACCOUNT_RECORDS_METRIC = "security.login.rate.limit.account.records";
    static final String ACCOUNT_OVERFLOW_ACTIVE_METRIC = "security.login.rate.limit.account.overflow.active";

    private static final String UNKNOWN_SOURCE = "unknown";
    private static final String NO_SCOPE = "none";
    private static final String EXACT_ACCOUNT_PREFIX = "exact:";
    private static final String OVERFLOW_ACCOUNT_PREFIX = "overflow:";
    private static final int ACCOUNT_OVERFLOW_BUCKETS = 1024;
    private static final int ACCOUNT_BUCKET_SECRET_BYTES = 32;
    private static final Scope[] SCOPES = Scope.values();
    private static final Comparator<StateExpiry> EXPIRY_ORDER = Comparator
            .comparingLong(StateExpiry::expiresAtMillis)
            .thenComparing(expiry -> expiry.key().scope())
            .thenComparing(expiry -> expiry.key().primary())
            .thenComparing(expiry -> expiry.key().secondary());
    private static final Comparator<EvictionCandidate> EVICTION_ORDER = Comparator
            .comparingInt(EvictionCandidate::failures)
            .thenComparingLong(EvictionCandidate::lastTouchedMillis)
            .thenComparing(candidate -> candidate.key().scope())
            .thenComparing(candidate -> candidate.key().primary())
            .thenComparing(candidate -> candidate.key().secondary());

    private final LoginRateLimitProperties properties;
    private final LongSupplier timeSource;
    private final byte[] accountBucketSecret;
    private final MeterRegistry meterRegistry;
    private final long initialBackoffMillis;
    private final long maxBackoffMillis;
    private final long recordTtlMillis;
    private final long sourceRecordTtlMillis;
    private final Map<StateKey, AttemptState> states = new HashMap<>();
    private final Map<Attempt, Reservation> reservations = new IdentityHashMap<>();
    private final EnumMap<Scope, NavigableSet<StateExpiry>> expirations = new EnumMap<>(Scope.class);
    private final EnumMap<Scope, NavigableSet<EvictionCandidate>> evictionCandidates =
            new EnumMap<>(Scope.class);
    private final EnumMap<Scope, Integer> trackedRecords = new EnumMap<>(Scope.class);
    private final EnumMap<Scope, Boolean> capacityRejectionReported = new EnumMap<>(Scope.class);
    private final Map<MetricKey, Counter> decisionCounters = new HashMap<>();
    private int trackedExactAccounts;
    private int trackedOverflowAccounts;
    private boolean exactAccountAdmissionClosed;

    @Autowired
    public LoginAttemptLimiter(LoginRateLimitProperties properties, MeterRegistry meterRegistry) {
        this(properties, () -> TimeUnit.NANOSECONDS.toMillis(System.nanoTime()),
                newAccountBucketSecret(), meterRegistry);
    }

    LoginAttemptLimiter(LoginRateLimitProperties properties, Clock clock, MeterRegistry meterRegistry) {
        this(properties, clock::millis, newAccountBucketSecret(), meterRegistry);
    }

    LoginAttemptLimiter(
            LoginRateLimitProperties properties,
            Clock clock,
            byte[] accountBucketSecret,
            MeterRegistry meterRegistry
    ) {
        this(properties, clock::millis, accountBucketSecret, meterRegistry);
    }

    private LoginAttemptLimiter(
            LoginRateLimitProperties properties,
            LongSupplier timeSource,
            byte[] accountBucketSecret,
            MeterRegistry meterRegistry
    ) {
        this.properties = properties;
        this.timeSource = timeSource;
        this.accountBucketSecret = accountBucketSecret.clone();
        this.meterRegistry = meterRegistry;
        this.initialBackoffMillis = properties.initialBackoff().toMillis();
        this.maxBackoffMillis = properties.maxBackoff().toMillis();
        this.recordTtlMillis = properties.recordTtl().toMillis();
        this.sourceRecordTtlMillis = properties.sourceRecordTtl().toMillis();
        for (Scope scope : SCOPES) {
            expirations.put(scope, new TreeSet<>(EXPIRY_ORDER));
            evictionCandidates.put(scope, new TreeSet<>(EVICTION_ORDER));
            trackedRecords.put(scope, 0);
            capacityRejectionReported.put(scope, false);
            Gauge.builder(TRACKED_RECORDS_METRIC, this, limiter -> limiter.trackedRecords(scope))
                    .description("Number of login rate-limit records currently tracked")
                    .tag("scope", metricValue(scope))
                    .register(meterRegistry);
        }
        Gauge.builder(IN_FLIGHT_METRIC, this, LoginAttemptLimiter::inFlightAttempts)
                .description("Number of login attempts currently reserved by the rate limiter")
                .register(meterRegistry);
        Gauge.builder(ACCOUNT_RECORDS_METRIC, this, LoginAttemptLimiter::trackedExactAccounts)
                .description("Number of account-scope login rate-limit records by storage kind")
                .tag("kind", "exact")
                .register(meterRegistry);
        Gauge.builder(ACCOUNT_RECORDS_METRIC, this, LoginAttemptLimiter::trackedOverflowAccounts)
                .description("Number of account-scope login rate-limit records by storage kind")
                .tag("kind", "overflow")
                .register(meterRegistry);
        Gauge.builder(ACCOUNT_OVERFLOW_ACTIVE_METRIC, this,
                        LoginAttemptLimiter::accountOverflowActive)
                .description("Whether new account identifiers use the bounded overflow pool")
                .register(meterRegistry);
    }

    public BeginDecision beginAttempt(String sourceAddress, String email) {
        return beginAttemptInternal(sourceAddress, email);
    }

    private synchronized BeginDecision beginAttemptInternal(String sourceAddress, String email) {
        long now = currentTimeMillis();
        purgeExpired(now);
        String source = normalizeSource(sourceAddress);
        String account = fingerprint(email);
        StateKey[] keys = keys(source, account);

        Rejection rejection = strongestCurrentRejection(keys, now);
        if (rejection != null) {
            AttemptState state = states.get(rejection.key());
            boolean shouldLog = !state.rejectionReported;
            state.rejectionReported = true;
            incrementDecision("rejected", rejection.reason(), rejection.key().scope());
            return BeginDecision.rejected(retryAfterSeconds(rejection.retryAtMillis(), now),
                    shouldLog, rejection.reason(), rejection.key().scope());
        }

        EnumMap<Scope, AttemptState> capacityEvictions = new EnumMap<>(Scope.class);
        for (StateKey key : keys) {
            if (key.scope() == Scope.ACCOUNT) {
                continue;
            }
            if (!states.containsKey(key) && trackedRecords.get(key.scope()) >= maxRecords(key.scope())) {
                AttemptState eviction = evictionCandidate(key.scope());
                if (eviction != null) {
                    capacityEvictions.put(key.scope(), eviction);
                    continue;
                }
                boolean shouldLog = !capacityRejectionReported.get(key.scope());
                capacityRejectionReported.put(key.scope(), true);
                incrementDecision("rejected", DecisionReason.STORE_CAPACITY, key.scope());
                return BeginDecision.rejected(retryAfterCapacitySeconds(now),
                        shouldLog, DecisionReason.STORE_CAPACITY, key.scope());
            }
        }
        for (Map.Entry<Scope, AttemptState> eviction : capacityEvictions.entrySet()) {
            removeState(eviction.getValue());
            incrementDecision("evicted", DecisionReason.STORE_CAPACITY_EVICTION, eviction.getKey());
        }

        if (isOverflowAccount(keys[0])) {
            exactAccountAdmissionClosed = true;
        }

        for (StateKey key : keys) {
            AttemptState state = states.get(key);
            if (state == null) {
                state = createState(key, now);
            }
            unschedule(state);
            state.inFlight += 1;
            if (state.lockUntilMillis <= now) {
                state.rejectionReported = false;
            }
        }
        Attempt attempt = new Attempt();
        reservations.put(attempt, new Reservation(keys));
        incrementDecision("allowed", DecisionReason.ATTEMPT_ALLOWED, null);
        return BeginDecision.allowed(attempt);
    }

    public synchronized Decision completeFailure(Attempt attempt) {
        Reservation reservation = consume(attempt);
        long now = currentTimeMillis();
        Rejection strongest = null;
        for (StateKey key : reservation.keys()) {
            AttemptState state = requiredState(key);
            releaseReservation(state);
            state.failures = saturatedIncrement(state.failures);
            state.lastTouchedMillis = now;
            int threshold = threshold(key.scope());
            if (state.failures >= threshold) {
                long delayMillis = backoffMillis(state.failures - threshold);
                state.lockUntilMillis = Math.max(state.lockUntilMillis, saturatedAdd(now, delayMillis));
                DecisionReason reason = backoffReason(key.scope());
                Rejection candidate = new Rejection(key, reason, state.lockUntilMillis);
                strongest = stronger(strongest, candidate);
            }
            state.expiresAtMillis = expiryAt(key.scope(), state, now);
            scheduleIfIdle(state);
        }

        if (strongest == null) {
            incrementDecision("recorded", DecisionReason.FAILURE_RECORDED, null);
            return Decision.allowed();
        }
        AttemptState state = states.get(strongest.key());
        boolean shouldLog = !state.rejectionReported;
        state.rejectionReported = true;
        incrementDecision("rejected", strongest.reason(), strongest.key().scope());
        return Decision.rejected(retryAfterSeconds(strongest.retryAtMillis(), now),
                shouldLog, strongest.reason(), strongest.key().scope());
    }

    public synchronized void completeSuccess(Attempt attempt) {
        Reservation reservation = consume(attempt);
        long now = currentTimeMillis();
        for (StateKey key : reservation.keys()) {
            AttemptState state = requiredState(key);
            releaseReservation(state);
            if (key.scope() == Scope.SOURCE_ACCOUNT || isExactAccount(key)) {
                state.failures = 0;
                state.lockUntilMillis = 0;
                state.rejectionReported = false;
            }
            if (state.inFlight == 0 && (state.failures == 0 || state.expiresAtMillis <= now)) {
                removeState(state);
            } else {
                scheduleIfIdle(state);
            }
        }
        incrementDecision("completed", DecisionReason.AUTHENTICATION_SUCCEEDED, null);
    }

    public synchronized void cancel(Attempt attempt) {
        Reservation reservation = consume(attempt);
        long now = currentTimeMillis();
        for (StateKey key : reservation.keys()) {
            AttemptState state = requiredState(key);
            releaseReservation(state);
            if (state.inFlight == 0 && (state.failures == 0 || state.expiresAtMillis <= now)) {
                removeState(state);
            } else {
                scheduleIfIdle(state);
            }
        }
        incrementDecision("completed", DecisionReason.ATTEMPT_CANCELLED, null);
    }

    synchronized void clear() {
        states.clear();
        reservations.clear();
        for (Scope scope : SCOPES) {
            expirations.get(scope).clear();
            evictionCandidates.get(scope).clear();
            trackedRecords.put(scope, 0);
            capacityRejectionReported.put(scope, false);
        }
        trackedExactAccounts = 0;
        trackedOverflowAccounts = 0;
        exactAccountAdmissionClosed = false;
    }

    synchronized int trackedRecords(Scope scope) {
        return trackedRecords.get(scope);
    }

    synchronized int inFlightAttempts() {
        return reservations.size();
    }

    synchronized int trackedExactAccounts() {
        return trackedExactAccounts;
    }

    synchronized int trackedOverflowAccounts() {
        return trackedOverflowAccounts;
    }

    synchronized int accountOverflowActive() {
        return exactAccountAdmissionClosed ? 1 : 0;
    }

    private Rejection strongestCurrentRejection(StateKey[] keys, long now) {
        Rejection strongest = null;
        for (StateKey key : keys) {
            AttemptState state = states.get(key);
            if (state == null) {
                continue;
            }
            if (state.lockUntilMillis > now) {
                strongest = stronger(strongest,
                        new Rejection(key, backoffReason(key.scope()), state.lockUntilMillis));
                continue;
            }
            int availableReservations = state.failures >= threshold(key.scope())
                    ? 1
                    : threshold(key.scope()) - state.failures;
            if (state.inFlight >= availableReservations) {
                strongest = stronger(strongest, new Rejection(
                        key, inFlightReason(key.scope()), saturatedAdd(now, initialBackoffMillis)));
            }
        }
        return strongest;
    }

    private AttemptState createState(StateKey key, long now) {
        AttemptState state = new AttemptState(key);
        state.lastTouchedMillis = now;
        state.expiresAtMillis = saturatedAdd(now, ttlMillis(key.scope()));
        states.put(key, state);
        trackedRecords.put(key.scope(), trackedRecords.get(key.scope()) + 1);
        if (isExactAccount(key)) {
            trackedExactAccounts += 1;
        } else if (isOverflowAccount(key)) {
            trackedOverflowAccounts += 1;
        }
        schedule(state);
        return state;
    }

    private Reservation consume(Attempt attempt) {
        if (attempt == null) {
            throw new IllegalArgumentException("Login attempt ticket is required");
        }
        Reservation reservation = reservations.remove(attempt);
        if (reservation == null) {
            throw new IllegalStateException("Login attempt ticket has already been completed or is not owned by this limiter");
        }
        return reservation;
    }

    private AttemptState requiredState(StateKey key) {
        AttemptState state = states.get(key);
        if (state == null || state.inFlight < 1) {
            throw new IllegalStateException("Login attempt reservation state is missing");
        }
        return state;
    }

    private static void releaseReservation(AttemptState state) {
        state.inFlight -= 1;
    }

    private void purgeExpired(long now) {
        for (Scope scope : SCOPES) {
            NavigableSet<StateExpiry> scopeExpirations = expirations.get(scope);
            while (!scopeExpirations.isEmpty() && scopeExpirations.first().expiresAtMillis() <= now) {
                StateExpiry expiry = scopeExpirations.pollFirst();
                AttemptState state = states.get(expiry.key());
                if (state == null || !expiry.equals(state.scheduledExpiry)) {
                    continue;
                }
                state.scheduledExpiry = null;
                if (state.inFlight == 0 && state.expiresAtMillis <= now) {
                    removeState(state);
                }
            }
        }
    }

    private AttemptState evictionCandidate(Scope scope) {
        NavigableSet<EvictionCandidate> candidates = evictionCandidates.get(scope);
        while (!candidates.isEmpty()) {
            EvictionCandidate candidate = candidates.first();
            AttemptState state = states.get(candidate.key());
            if (state != null && candidate.equals(state.scheduledEviction)) {
                return state;
            }
            candidates.pollFirst();
        }
        return null;
    }

    private void removeState(AttemptState state) {
        unschedule(state);
        if (states.remove(state.key, state)) {
            Scope scope = state.key.scope();
            trackedRecords.put(scope, trackedRecords.get(scope) - 1);
            if (isExactAccount(state.key)) {
                trackedExactAccounts -= 1;
            } else if (isOverflowAccount(state.key)) {
                trackedOverflowAccounts -= 1;
            }
            if (trackedOverflowAccounts == 0 && trackedExactAccounts < properties.maxAccounts()) {
                exactAccountAdmissionClosed = false;
            }
            if (scope != Scope.ACCOUNT && trackedRecords.get(scope) < maxRecords(scope)) {
                capacityRejectionReported.put(scope, false);
            }
        }
    }

    private void scheduleIfIdle(AttemptState state) {
        if (state.inFlight == 0) {
            schedule(state);
        }
    }

    private void schedule(AttemptState state) {
        unschedule(state);
        StateExpiry expiry = new StateExpiry(state.expiresAtMillis, state.key);
        state.scheduledExpiry = expiry;
        expirations.get(state.key.scope()).add(expiry);
        if (state.key.scope() != Scope.ACCOUNT && state.failures < threshold(state.key.scope())) {
            EvictionCandidate candidate = new EvictionCandidate(
                    state.failures, state.lastTouchedMillis, state.key);
            state.scheduledEviction = candidate;
            evictionCandidates.get(state.key.scope()).add(candidate);
        }
    }

    private void unschedule(AttemptState state) {
        if (state.scheduledExpiry != null) {
            expirations.get(state.key.scope()).remove(state.scheduledExpiry);
            state.scheduledExpiry = null;
        }
        if (state.scheduledEviction != null) {
            evictionCandidates.get(state.key.scope()).remove(state.scheduledEviction);
            state.scheduledEviction = null;
        }
    }

    private long expiryAt(Scope scope, AttemptState state, long now) {
        return Math.max(state.lockUntilMillis, saturatedAdd(now, ttlMillis(scope)));
    }

    private long retryAfterCapacitySeconds(long now) {
        return retryAfterSeconds(saturatedAdd(now, initialBackoffMillis), now);
    }

    private long retryAfterSeconds(long retryAtMillis, long now) {
        long actualDelay = positiveDifference(retryAtMillis, now);
        long roundedUnits = ceilingDivide(Math.max(1, actualDelay), initialBackoffMillis);
        long roundedMillis = saturatedMultiply(roundedUnits, initialBackoffMillis);
        return Math.max(1, ceilingDivide(roundedMillis, 1000));
    }

    private long backoffMillis(int exponent) {
        long delay = initialBackoffMillis;
        int remaining = Math.max(0, exponent);
        while (remaining > 0 && delay < maxBackoffMillis) {
            delay = delay > maxBackoffMillis / 2 ? maxBackoffMillis : delay * 2;
            remaining -= 1;
        }
        return Math.min(delay, maxBackoffMillis);
    }

    private long currentTimeMillis() {
        return timeSource.getAsLong();
    }

    private void incrementDecision(String outcome, DecisionReason reason, Scope scope) {
        MetricKey key = new MetricKey(outcome, reason, scope);
        decisionCounters.computeIfAbsent(key, ignored -> Counter.builder(DECISION_METRIC)
                        .description("Login rate limiter decisions")
                        .tags("outcome", outcome, "reason", metricValue(reason),
                                "scope", scope == null ? NO_SCOPE : metricValue(scope))
                        .register(meterRegistry))
                .increment();
    }

    private int threshold(Scope scope) {
        return switch (scope) {
            case ACCOUNT -> properties.accountThreshold();
            case SOURCE_ACCOUNT -> properties.sourceAccountThreshold();
            case SOURCE -> properties.sourceThreshold();
        };
    }

    private int maxRecords(Scope scope) {
        return switch (scope) {
            case ACCOUNT -> properties.maxAccounts();
            case SOURCE_ACCOUNT -> properties.maxSourceAccounts();
            case SOURCE -> properties.maxSources();
        };
    }

    private long ttlMillis(Scope scope) {
        return scope == Scope.SOURCE ? sourceRecordTtlMillis : recordTtlMillis;
    }

    private static DecisionReason backoffReason(Scope scope) {
        return switch (scope) {
            case ACCOUNT -> DecisionReason.ACCOUNT_BACKOFF;
            case SOURCE_ACCOUNT -> DecisionReason.SOURCE_ACCOUNT_BACKOFF;
            case SOURCE -> DecisionReason.SOURCE_BACKOFF;
        };
    }

    private static DecisionReason inFlightReason(Scope scope) {
        return switch (scope) {
            case ACCOUNT -> DecisionReason.ACCOUNT_IN_FLIGHT;
            case SOURCE_ACCOUNT -> DecisionReason.SOURCE_ACCOUNT_IN_FLIGHT;
            case SOURCE -> DecisionReason.SOURCE_IN_FLIGHT;
        };
    }

    private static Rejection stronger(Rejection current, Rejection candidate) {
        if (current == null || candidate.retryAtMillis() > current.retryAtMillis()) {
            return candidate;
        }
        if (candidate.retryAtMillis() == current.retryAtMillis()
                && candidate.key().scope().ordinal() < current.key().scope().ordinal()) {
            return candidate;
        }
        return current;
    }

    private StateKey[] keys(String source, String account) {
        StateKey exactAccount = new StateKey(Scope.ACCOUNT, EXACT_ACCOUNT_PREFIX + account, "");
        StateKey accountKey;
        if (states.containsKey(exactAccount)
                || (!exactAccountAdmissionClosed && trackedExactAccounts < properties.maxAccounts())) {
            accountKey = exactAccount;
        } else {
            int bucket = EmailFingerprint.bucket(account, accountBucketSecret, ACCOUNT_OVERFLOW_BUCKETS);
            accountKey = new StateKey(Scope.ACCOUNT, OVERFLOW_ACCOUNT_PREFIX + bucket, "");
        }
        return new StateKey[] {
                accountKey,
                new StateKey(Scope.SOURCE_ACCOUNT, source, account),
                new StateKey(Scope.SOURCE, source, "")
        };
    }

    private static boolean isExactAccount(StateKey key) {
        return key.scope() == Scope.ACCOUNT && key.primary().startsWith(EXACT_ACCOUNT_PREFIX);
    }

    private static boolean isOverflowAccount(StateKey key) {
        return key.scope() == Scope.ACCOUNT && key.primary().startsWith(OVERFLOW_ACCOUNT_PREFIX);
    }

    private static byte[] newAccountBucketSecret() {
        byte[] secret = new byte[ACCOUNT_BUCKET_SECRET_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }

    private static String normalizeSource(String sourceAddress) {
        return sourceAddress == null || sourceAddress.isBlank() ? UNKNOWN_SOURCE : sourceAddress.strip();
    }

    static String fingerprint(String email) {
        return EmailFingerprint.sha256(EmailCanonicalizer.canonicalize(email));
    }

    private static int saturatedIncrement(int value) {
        return value == Integer.MAX_VALUE ? Integer.MAX_VALUE : value + 1;
    }

    private static long positiveDifference(long later, long earlier) {
        if (later <= earlier) {
            return 0;
        }
        try {
            return Math.subtractExact(later, earlier);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedAdd(long value, long addition) {
        try {
            return Math.addExact(value, addition);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long saturatedMultiply(long left, long right) {
        try {
            return Math.multiplyExact(left, right);
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static long ceilingDivide(long dividend, long divisor) {
        if (dividend <= 0) {
            return 0;
        }
        return 1 + (dividend - 1) / divisor;
    }

    private static String metricValue(Enum<?> value) {
        return value.name().toLowerCase(Locale.ROOT);
    }

    public enum Scope {
        ACCOUNT,
        SOURCE_ACCOUNT,
        SOURCE
    }

    public enum DecisionReason {
        ATTEMPT_ALLOWED,
        FAILURE_RECORDED,
        AUTHENTICATION_SUCCEEDED,
        ATTEMPT_CANCELLED,
        ACCOUNT_BACKOFF,
        SOURCE_ACCOUNT_BACKOFF,
        SOURCE_BACKOFF,
        ACCOUNT_IN_FLIGHT,
        SOURCE_ACCOUNT_IN_FLIGHT,
        SOURCE_IN_FLIGHT,
        STORE_CAPACITY,
        STORE_CAPACITY_EVICTION
    }

    public record BeginDecision(
            boolean permitted,
            long retryAfterSeconds,
            boolean shouldLog,
            DecisionReason reason,
            Scope scope,
            Attempt attempt
    ) {
        private static BeginDecision allowed(Attempt attempt) {
            return new BeginDecision(true, 0, false, DecisionReason.ATTEMPT_ALLOWED, null, attempt);
        }

        private static BeginDecision rejected(
                long retryAfterSeconds,
                boolean shouldLog,
                DecisionReason reason,
                Scope scope
        ) {
            return new BeginDecision(false, retryAfterSeconds, shouldLog, reason, scope, null);
        }
    }

    public record Decision(
            boolean permitted,
            long retryAfterSeconds,
            boolean shouldLog,
            DecisionReason reason,
            Scope scope
    ) {
        private static Decision allowed() {
            return new Decision(true, 0, false, DecisionReason.FAILURE_RECORDED, null);
        }

        private static Decision rejected(
                long retryAfterSeconds,
                boolean shouldLog,
                DecisionReason reason,
                Scope scope
        ) {
            return new Decision(false, retryAfterSeconds, shouldLog, reason, scope);
        }
    }

    public static final class Attempt {
        private Attempt() {
        }
    }

    private static final class AttemptState {
        private final StateKey key;
        private int failures;
        private int inFlight;
        private long lockUntilMillis;
        private long lastTouchedMillis;
        private long expiresAtMillis;
        private boolean rejectionReported;
        private StateExpiry scheduledExpiry;
        private EvictionCandidate scheduledEviction;

        private AttemptState(StateKey key) {
            this.key = key;
        }
    }

    private record StateKey(Scope scope, String primary, String secondary) {
    }

    private record StateExpiry(long expiresAtMillis, StateKey key) {
    }

    private record EvictionCandidate(int failures, long lastTouchedMillis, StateKey key) {
    }

    private record Reservation(StateKey[] keys) {
    }

    private record Rejection(StateKey key, DecisionReason reason, long retryAtMillis) {
    }

    private record MetricKey(String outcome, DecisionReason reason, Scope scope) {
    }
}
