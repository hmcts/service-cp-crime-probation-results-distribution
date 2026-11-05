package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Duration;
import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyShape;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.HttpStatusBucket;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Non200SuccessStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

/**
 * The telemetry port the application core labels its leg outcomes on (design §10.1). It is declared
 * here, in {@code application}, by the side that uses it, so the core never imports the {@code config}
 * metric registry that implements it — the dependency points inward, not out. Every parameter is a
 * bounded {@code domain} enum, so no open string can reach a label.
 */
public interface OutcomeMetrics {

    /**
     * Counts one leg outcome with its closed label set (FR-002-059).
     *
     * @param leg    the leg
     * @param status the leg status
     * @param reason the bounded reason, or empty for a status with none
     * @param origin the run origin
     * @param forced whether a forced resend produced it
     * @param shape  the body shape
     * @param http   the HTTP bucket
     */
    void legOutcome(Leg leg, LegStatus status, Optional<LegReason> reason, AttemptOrigin origin,
                    boolean forced, BodyShape shape, HttpStatusBucket http);

    /**
     * Counts one 2xx answer other than 200 (H12).
     *
     * @param status the non-200 success bucket
     */
    void gatewayNon200Success(Non200SuccessStatus status);

    /**
     * Counts one parked leg.
     *
     * @param leg    the leg
     * @param reason the park reason
     */
    void parked(Leg leg, LegReason reason);

    /**
     * Counts one unreadable switch read and the value it defaulted to (DV-25).
     *
     * @param cause     the unreadable cause
     * @param defaulted the value it defaulted to ({@code ACTIVE} or {@code INACTIVE})
     */
    void youthSwitchUnreadable(YouthSwitchUnreadableCause cause, YouthSwitchRecordedValue defaulted);

    /** Reports the last switch value selected for an HMPPS leg. */
    void youthSwitchState(YouthSwitchRecordedValue state);

    /** Counts a response whose users-groups permission list contains more than one entry. */
    void youthSwitchAmbiguous();

    /** Counts a gateway send that recovered to SENT after a 429 or redelivery. */
    void gatewayRecovered(GatewayRecovery after);

    /** Records the duration of one gateway POST attempt, excluding retry waits. */
    void gatewayRequestDuration(Leg leg, Duration duration);

    /** Counts one stale-runner rejection: a guarded outcome write that matched no row. */
    void staleRunnerRejection();

    /**
     * Counts one resend-leg ID refused with its bounded code (slice 004, FR-004-039).
     *
     * @param reason the refusal code
     */
    void resendRefusal(ResendRefusal reason);
}
