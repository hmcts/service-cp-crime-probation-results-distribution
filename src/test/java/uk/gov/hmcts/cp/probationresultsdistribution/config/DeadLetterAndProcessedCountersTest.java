package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;

@DisplayName("dead-letter and terminal request counters")
class DeadLetterAndProcessedCountersTest {

    @Test
    @DisplayName("counts each closed dead-letter reason and each terminal request status")
    void counts_dead_letters_and_final_statuses() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final MetricRegistry metrics = new MetricRegistry(meters);

        for (final DeadLetterReason reason : DeadLetterReason.values()) {
            metrics.deadLettered(reason);
            assertThat(meters.get("probation_deadlettered_total").tag("reason", reason.wireValue())
                    .counter().count()).isEqualTo(1.0d);
        }
        metrics.processed("COMPLETED");
        metrics.processed("FAILED");
        assertThat(meters.get("probation_processed_total").tag("status", "COMPLETED")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meters.get("probation_processed_total").tag("status", "FAILED")
                .counter().count()).isEqualTo(1.0d);
    }
}
