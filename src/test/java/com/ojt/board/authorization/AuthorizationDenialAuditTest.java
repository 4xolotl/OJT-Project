package com.ojt.board.authorization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

@ExtendWith(OutputCaptureExtension.class)
class AuthorizationDenialAuditTest {

    @Test
    void countsEveryDenialWhileBoundingDetailedLogs(CapturedOutput output) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AtomicLong nanoTime = new AtomicLong();
        AuthorizationDenialAudit audit = new AuthorizationDenialAudit(
                meterRegistry, 2, Duration.ofSeconds(10), nanoTime::get);
        ObjectAuthorizationDeniedException denial = new ObjectAuthorizationDeniedException(
                8L, 1L, ObjectAuthorizationOperation.POST_UPDATE);

        for (int index = 0; index < 5; index++) {
            audit.record(denial);
        }

        assertEquals(5.0, deniedCount(meterRegistry, "update", "post"));
        assertEquals(2, occurrences(output.toString(), "security_event=authorization_denied"));

        nanoTime.addAndGet(Duration.ofSeconds(10).toNanos());
        audit.record(denial);

        assertEquals(6.0, deniedCount(meterRegistry, "update", "post"));
        assertEquals(3, occurrences(output.toString(), "security_event=authorization_denied"));
        assertThat(output).contains("actor_id=8 action=update resource_type=post resource_id=1 "
                + "suppressed_count=3");
    }

    @Test
    void usesOnlyBoundedActionAndResourceTags() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        new AuthorizationDenialAudit(meterRegistry, 1, Duration.ofMinutes(1), () -> 0L);

        assertEquals(ObjectAuthorizationOperation.values().length, meterRegistry.getMeters().size());
        assertThat(meterRegistry.getMeters()).allSatisfy(meter -> {
            assertThat(meter.getId().getName()).isEqualTo("security.authorization.denied");
            assertThat(meter.getId().getTags()).hasSize(2);
            assertThat(meter.getId().getTag("action")).isNotBlank();
            assertThat(meter.getId().getTag("resource_type")).isNotBlank();
        });
    }

    @Test
    void oneOperationCannotSuppressAnotherOperation(CapturedOutput output) {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();
        AtomicLong nanoTime = new AtomicLong();
        AuthorizationDenialAudit audit = new AuthorizationDenialAudit(
                meterRegistry, 1, Duration.ofMinutes(1), nanoTime::get);
        ObjectAuthorizationDeniedException postDenial = new ObjectAuthorizationDeniedException(
                8L, 1L, ObjectAuthorizationOperation.POST_UPDATE);
        ObjectAuthorizationDeniedException commentDenial = new ObjectAuthorizationDeniedException(
                8L, 2L, ObjectAuthorizationOperation.COMMENT_DELETE);

        audit.record(postDenial);
        audit.record(postDenial);
        audit.record(commentDenial);

        assertEquals(2.0, deniedCount(meterRegistry, "update", "post"));
        assertEquals(1.0, deniedCount(meterRegistry, "delete", "comment"));
        assertEquals(2, occurrences(output.toString(), "security_event=authorization_denied"));
        assertThat(output).contains("action=delete resource_type=comment resource_id=2");
    }

    @Test
    void rejectsConfigurationThatCouldDisableLogFloodProtection() {
        SimpleMeterRegistry meterRegistry = new SimpleMeterRegistry();

        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationDenialAudit(meterRegistry, 11, Duration.ofMinutes(1), () -> 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new AuthorizationDenialAudit(meterRegistry, 1, Duration.ofMillis(999), () -> 0L));
    }

    private double deniedCount(SimpleMeterRegistry meterRegistry, String action, String resourceType) {
        return meterRegistry.get("security.authorization.denied")
                .tag("action", action)
                .tag("resource_type", resourceType)
                .counter().count();
    }

    private int occurrences(String value, String fragment) {
        return (value.length() - value.replace(fragment, "").length()) / fragment.length();
    }
}
