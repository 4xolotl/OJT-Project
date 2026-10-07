package com.ojt.board.authorization;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.LongSupplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@Slf4j
public class AuthorizationDenialAudit {

    private static final String COUNTER_NAME = "security.authorization.denied";
    private static final int MAX_DETAIL_LOG_BURST_PER_OPERATION = 10;
    private static final Duration MIN_DETAIL_LOG_REFILL_INTERVAL = Duration.ofSeconds(1);

    private final Map<ObjectAuthorizationOperation, OperationAuditState> states;

    @Autowired
    public AuthorizationDenialAudit(
            MeterRegistry meterRegistry,
            @Value("${security.authorization-audit.detail-log-burst-per-operation:1}")
            int detailLogBurstPerOperation,
            @Value("${security.authorization-audit.detail-log-refill-interval:PT1M}") Duration refillInterval) {
        this(meterRegistry, detailLogBurstPerOperation, refillInterval, System::nanoTime);
    }

    AuthorizationDenialAudit(MeterRegistry meterRegistry, int detailLogBurstPerOperation,
                             Duration refillInterval, LongSupplier nanoTime) {
        if (detailLogBurstPerOperation < 1
                || detailLogBurstPerOperation > MAX_DETAIL_LOG_BURST_PER_OPERATION) {
            throw new IllegalArgumentException("detailLogBurstPerOperation must be between 1 and 10");
        }
        if (refillInterval.compareTo(MIN_DETAIL_LOG_REFILL_INTERVAL) < 0) {
            throw new IllegalArgumentException("refillInterval must be at least one second");
        }
        EnumMap<ObjectAuthorizationOperation, OperationAuditState> registeredStates =
                new EnumMap<>(ObjectAuthorizationOperation.class);
        for (ObjectAuthorizationOperation operation : ObjectAuthorizationOperation.values()) {
            Counter counter = Counter.builder(COUNTER_NAME)
                    .description("Rejected object ownership checks")
                    .tag("action", operation.action())
                    .tag("resource_type", operation.resourceType())
                    .register(meterRegistry);
            registeredStates.put(operation, new OperationAuditState(counter,
                    new DetailLogSampler(detailLogBurstPerOperation, refillInterval, nanoTime)));
        }
        this.states = Map.copyOf(registeredStates);
    }

    public void record(ObjectAuthorizationDeniedException exception) {
        ObjectAuthorizationOperation operation = exception.operation();
        OperationAuditState state = states.get(operation);
        state.counter().increment();
        long suppressedCount = state.detailLogSampler().acquire();
        if (suppressedCount < 0) {
            return;
        }
        log.warn("security_event=authorization_denied outcome=deny reason=not_owner "
                        + "actor_id={} action={} resource_type={} resource_id={} suppressed_count={}",
                exception.actorId(), operation.action(), operation.resourceType(), exception.resourceId(),
                suppressedCount);
    }

    private record OperationAuditState(Counter counter, DetailLogSampler detailLogSampler) {}

    private static final class DetailLogSampler {

        private final int capacity;
        private final long refillIntervalNanos;
        private final LongSupplier nanoTime;
        private int availableTokens;
        private long lastRefillNanos;
        private long suppressedCount;

        private DetailLogSampler(int capacity, Duration refillInterval, LongSupplier nanoTime) {
            this.capacity = capacity;
            this.refillIntervalNanos = refillInterval.toNanos();
            this.nanoTime = nanoTime;
            this.availableTokens = capacity;
            this.lastRefillNanos = nanoTime.getAsLong();
        }

        private synchronized long acquire() {
            refill(nanoTime.getAsLong());
            if (availableTokens == 0) {
                suppressedCount++;
                return -1;
            }
            availableTokens--;
            long previousSuppressedCount = suppressedCount;
            suppressedCount = 0;
            return previousSuppressedCount;
        }

        private void refill(long now) {
            long elapsed = now - lastRefillNanos;
            if (elapsed < refillIntervalNanos) {
                return;
            }
            long intervals = elapsed / refillIntervalNanos;
            if (intervals >= capacity) {
                availableTokens = capacity;
                lastRefillNanos = now;
                return;
            }
            availableTokens = Math.min(capacity, availableTokens + (int) intervals);
            lastRefillNanos += intervals * refillIntervalNanos;
        }
    }
}
