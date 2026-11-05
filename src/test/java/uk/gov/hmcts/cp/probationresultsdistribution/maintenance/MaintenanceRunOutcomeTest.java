package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

@DisplayName("the maintenance run outcome")
class MaintenanceRunOutcomeTest {

    @Test
    @DisplayName("carries whether the lock was acquired, what was created and dropped, and every failure")
    void carries_the_runs_results() {
        final MaintenanceRunOutcome.MaintenanceFailure failure =
                new MaintenanceRunOutcome.MaintenanceFailure(BodyMaintenanceStep.CREATE,
                        MaintenanceFailureCause.PARTITION_OVERLAP, "outbound_body_2030_05");

        final MaintenanceRunOutcome outcome = new MaintenanceRunOutcome(true,
                List.of("outbound_body_2030_12"), List.of("outbound_body_2029_03"), 2L,
                List.of(failure));

        assertThat(outcome.lockAcquired()).isTrue();
        assertThat(outcome.partitionsCreated()).containsExactly("outbound_body_2030_12");
        assertThat(outcome.partitionsDropped()).containsExactly("outbound_body_2029_03");
        assertThat(outcome.defaultRowsDeleted()).isEqualTo(2L);
        assertThat(outcome.failures()).containsExactly(failure);
        assertThat(failure.step()).isEqualTo(BodyMaintenanceStep.CREATE);
        assertThat(failure.cause()).isEqualTo(MaintenanceFailureCause.PARTITION_OVERLAP);
        assertThat(failure.detail()).isEqualTo("outbound_body_2030_05");
    }

    @Test
    @DisplayName("a skipped run (no lock) carries no partition work and no failure")
    void a_skipped_run_carries_nothing() {
        final MaintenanceRunOutcome skipped =
                new MaintenanceRunOutcome(false, List.of(), List.of(), 0L, List.of());

        assertThat(skipped.lockAcquired()).isFalse();
        assertThat(skipped.partitionsCreated()).isEmpty();
        assertThat(skipped.partitionsDropped()).isEmpty();
        assertThat(skipped.failures()).isEmpty();
    }
}
