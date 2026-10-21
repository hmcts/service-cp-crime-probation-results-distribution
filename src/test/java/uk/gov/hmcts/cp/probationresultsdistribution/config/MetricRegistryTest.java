package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyShape;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.HttpStatusBucket;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsFailure;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

@DisplayName("the metric registry")
class MetricRegistryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final MetricRegistry metrics = new MetricRegistry(meters);

    @Test
    @DisplayName("defines every probation_ series once, this slice's and the reserved ones")
    void defines_every_series() {
        assertThat(MetricRegistry.METRIC_NAMES).contains(
                "probation_leg_outcomes_total", "probation_processed_total",
                "probation_parked_total", "probation_deadlettered_total",
                "probation_payload_source_total", "probation_youth_switch_unreadable_total",
                "probation_youth_switch_ambiguous_total", "probation_gateway_non200_success_total",
                "probation_gateway_recovered_total", "probation_crash_reruns_total",
                "probation_gateway_request_duration", "probation_stale_runner_rejections_total",
                "probation_intake_suspensions_total", "probation_operations_failures_total",
                "probation_replays_total", "probation_replay_deferred_total",
                "probation_resend_refusals_total", "probation_body_partitions_created_total",
                "probation_body_partitions_dropped_total", "probation_body_maintenance_failures_total",
                "probation_oldest_non_terminal_age", "probation_oldest_parked_age",
                "probation_servicebus_up", "probation_youth_switch_state",
                "probation_intake_suspended", "probation_body_default_partition_rows");
    }

    @Test
    @DisplayName("gives probation_leg_outcomes_total exactly its seven closed labels")
    void leg_outcome_has_the_closed_label_set() {
        metrics.legOutcome(Leg.HMPPS, LegStatus.SENT, Optional.empty(), AttemptOrigin.QUEUE, false,
                BodyShape.CASE, HttpStatusBucket.OK_200);

        final Meter meter = meters.find("probation_leg_outcomes_total").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTags().stream().map(Tag::getKey).toList())
                .containsExactlyInAnyOrderElementsOf(MetricRegistry.legOutcomeTagKeys());
        assertThat(meter.getId().getTag("reason")).isEqualTo(MetricRegistry.NO_REASON);
        assertThat(meter.getId().getTag("status")).isEqualTo("SENT");
    }

    @Test
    @DisplayName("labels an unreadable switch by its cause and defaulted value")
    void youth_switch_unreadable_labels() {
        metrics.youthSwitchUnreadable(YouthSwitchUnreadableCause.TIMEOUT,
                YouthSwitchRecordedValue.ACTIVE);

        final Meter meter = meters.find("probation_youth_switch_unreadable_total").meter();
        assertThat(meter).isNotNull();
        assertThat(meter.getId().getTag("cause")).isEqualTo("TIMEOUT");
        assertThat(meter.getId().getTag("defaulted")).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("registers probation_operations_failures_total even though nothing reads it")
    void operations_failures_registered_but_unread() {
        metrics.operationsFailure(OperationsAction.REPLAY, OperationsFailure.UNEXPECTED);

        assertThat(meters.find("probation_operations_failures_total").meter()).isNotNull();
    }

    @Test
    @DisplayName("refuses an open request status on probation_processed_total")
    void processed_refuses_an_open_status() {
        assertThatThrownBy(() -> metrics.processed("GONE"))
                .isInstanceOf(IllegalArgumentException.class);

        metrics.processed("COMPLETED");
        assertThat(meters.find("probation_processed_total").meter()).isNotNull();
    }
}
