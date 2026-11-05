package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyShape;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.HttpStatusBucket;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Non200SuccessStatus;

@DisplayName("selectable gateway outcome metrics")
class SelectableOutcomesTest {

    @Test
    @DisplayName("distinguishes receiver outcomes, non-200 success and recovered sends")
    void exposes_each_alertable_outcome_by_closed_labels() {
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final MetricRegistry metrics = new MetricRegistry(meters);
        metrics.legOutcome(Leg.HMPPS, LegStatus.FAILED, Optional.of(LegReason.RECEIVER_REJECTED),
                AttemptOrigin.QUEUE, false, BodyShape.APPLICATION_ONLY, HttpStatusBucket.BAD_REQUEST_400);
        metrics.legOutcome(Leg.HMPPS, LegStatus.FAILED, Optional.of(LegReason.RECEIVER_REJECTED),
                AttemptOrigin.QUEUE, false, BodyShape.CASE, HttpStatusBucket.BAD_REQUEST_400);
        metrics.legOutcome(Leg.HMPPS, LegStatus.FAILED, Optional.of(LegReason.RECEIVER_REJECTED),
                AttemptOrigin.QUEUE, false, BodyShape.CASE, HttpStatusBucket.UNAUTHORIZED_401);
        metrics.legOutcome(Leg.HMPPS, LegStatus.SENT, Optional.empty(), AttemptOrigin.QUEUE,
                false, BodyShape.CASE, HttpStatusBucket.TWO_XX_OTHER);
        metrics.gatewayNon200Success(Non200SuccessStatus.ACCEPTED_202);
        metrics.gatewayRecovered(GatewayRecovery.FIVE_XX_REDELIVERY);

        assertThat(meters.get("probation_leg_outcomes_total").tag("shape", "application-only")
                .tag("http", "400").counter().count()).isEqualTo(1.0d);
        assertThat(meters.get("probation_leg_outcomes_total").tag("shape", "case")
                .tag("http", "400").counter().count()).isEqualTo(1.0d);
        assertThat(meters.get("probation_leg_outcomes_total").tag("http", "401")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meters.get("probation_gateway_non200_success_total").tag("status", "202")
                .counter().count()).isEqualTo(1.0d);
        assertThat(meters.get("probation_gateway_recovered_total")
                .tag("after", "5xx-redelivery").counter().count()).isEqualTo(1.0d);
    }
}
