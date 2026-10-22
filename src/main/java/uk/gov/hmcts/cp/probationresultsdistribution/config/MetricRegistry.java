package uk.gov.hmcts.cp.probationresultsdistribution.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import uk.gov.hmcts.cp.probationresultsdistribution.application.OutcomeMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyShape;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.HttpStatusBucket;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Non200SuccessStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsFailure;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

/**
 * The one definition of the {@code probation_} metric family (design §10.1, §13; FR-002-057). Every
 * series name and every label value is closed: the increment methods take domain enums, so an open
 * string cannot reach a label. Later slices increment their own series ({@code probation_replays_*},
 * {@code probation_body_*}, {@code probation_resend_refusals_*}); their names are reserved here so the
 * registry keeps one owner. {@code probation_operations_failures_total} is registered-but-unread: a
 * proposed slice 005 series with no reader yet.
 */
public final class MetricRegistry implements OutcomeMetrics {

    /** The {@code reason}-label placeholder for a leg outcome that has no reason (R7). */
    public static final String NO_REASON = "none";

    private static final String LEG_OUTCOMES_M = "probation_leg_outcomes_total";
    private static final String PROCESSED_M = "probation_processed_total";
    private static final String PARKED_M = "probation_parked_total";
    private static final String DEADLETTERED_M = "probation_deadlettered_total";
    private static final String PAYLOAD_SOURCE_M = "probation_payload_source_total";
    private static final String YOUTH_UNREADABLE_M = "probation_youth_switch_unreadable_total";
    private static final String YOUTH_AMBIGUOUS_M = "probation_youth_switch_ambiguous_total";
    private static final String GATEWAY_NON200_M = "probation_gateway_non200_success_total";
    private static final String GATEWAY_RECOVERED_M = "probation_gateway_recovered_total";
    private static final String CRASH_RERUNS_M = "probation_crash_reruns_total";
    private static final String GATEWAY_DURATION_M = "probation_gateway_request_duration";
    private static final String PROCESSING_FAILURES_M = "probation_processing_failures_total";
    private static final String SETTLEMENT_FAILURES_M = "probation_settlement_failures_total";
    private static final String LOCK_LOSS_M = "probation_lock_loss_total";
    private static final String STALE_RUNNER_M = "probation_stale_runner_rejections_total";
    private static final String INTAKE_SUSPENSIONS_M = "probation_intake_suspensions_total";
    private static final String OPERATIONS_FAILURES_M = "probation_operations_failures_total";
    private static final String REPLAYS_M = "probation_replays_total";
    private static final String REPLAY_DEFERRED_M = "probation_replay_deferred_total";
    private static final String RESEND_REFUSALS_M = "probation_resend_refusals_total";
    private static final String BODY_PARTITIONS_CREATED_M = "probation_body_partitions_created_total";
    private static final String BODY_PARTITIONS_DROPPED_M = "probation_body_partitions_dropped_total";
    private static final String BODY_MAINTENANCE_FAILURES_M =
            "probation_body_maintenance_failures_total";
    private static final String OLDEST_NON_TERMINAL_AGE_M = "probation_oldest_non_terminal_age";
    private static final String OLDEST_PARKED_AGE_M = "probation_oldest_parked_age";
    private static final String SERVICEBUS_UP_M = "probation_servicebus_up";
    private static final String YOUTH_SWITCH_STATE_M = "probation_youth_switch_state";
    private static final String INTAKE_SUSPENDED_M = "probation_intake_suspended";
    private static final String BODY_DEFAULT_ROWS_M = "probation_body_default_partition_rows";

    private static final String TAG_LEG = "leg";
    private static final String TAG_STATUS = "status";
    private static final String TAG_REASON = "reason";
    private static final String TAG_ORIGIN = "origin";
    private static final String TAG_FORCED = "forced";
    private static final String TAG_SHAPE = "shape";
    private static final String TAG_HTTP = "http";
    private static final String TAG_SOURCE = "source";
    private static final String TAG_CAUSE = "cause";
    private static final String TAG_DEFAULTED = "defaulted";
    private static final String TAG_AFTER = "after";
    private static final String TAG_ACTION = "action";

    private static final Set<String> FINAL_REQUEST_STATUSES = Set.of("COMPLETED", "FAILED");

    /** Every series this registry owns (this slice's and the names reserved for later slices). */
    public static final Set<String> METRIC_NAMES = Set.of(
            LEG_OUTCOMES_M, PROCESSED_M, PARKED_M, DEADLETTERED_M, PAYLOAD_SOURCE_M, YOUTH_UNREADABLE_M,
            YOUTH_AMBIGUOUS_M, GATEWAY_NON200_M, GATEWAY_RECOVERED_M, CRASH_RERUNS_M, GATEWAY_DURATION_M,
            PROCESSING_FAILURES_M, SETTLEMENT_FAILURES_M, LOCK_LOSS_M, STALE_RUNNER_M, INTAKE_SUSPENSIONS_M,
            OPERATIONS_FAILURES_M, REPLAYS_M, REPLAY_DEFERRED_M, RESEND_REFUSALS_M, BODY_PARTITIONS_CREATED_M,
            BODY_PARTITIONS_DROPPED_M, BODY_MAINTENANCE_FAILURES_M, OLDEST_NON_TERMINAL_AGE_M,
            OLDEST_PARKED_AGE_M, SERVICEBUS_UP_M, YOUTH_SWITCH_STATE_M, INTAKE_SUSPENDED_M, BODY_DEFAULT_ROWS_M);

    private final MeterRegistry registry;
    private final AtomicInteger intakeSuspended = new AtomicInteger(0);

    /**
     * Creates the registry over a Micrometer {@link MeterRegistry}.
     *
     * @param registry the Micrometer registry
     */
    public MetricRegistry(final MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder(INTAKE_SUSPENDED_M, intakeSuspended, AtomicInteger::doubleValue)
                .tag(TAG_CAUSE, "database")
                .register(registry);
    }

    /**
     * Sets the {@code probation_intake_suspended{cause="database"}} gauge to 1 while intake is
     * suspended by a store outage, 0 otherwise (FR-002-009). Reporting the resume is half the signal:
     * a gauge that only ever rose would never say an outage had ended.
     *
     * @param suspended whether intake is currently suspended by the store
     */
    public void recordIntakeSuspended(final boolean suspended) {
        intakeSuspended.set(suspended ? 1 : 0);
    }

    /**
     * Counts one leg outcome with its closed label set (FR-002-059).
     *
     * @param leg      the leg
     * @param status   the leg status
     * @param reason   the bounded reason, or empty for a status with none
     * @param origin   the run origin
     * @param forced   whether a forced resend produced it
     * @param shape    the body shape
     * @param http     the HTTP bucket
     */
    @Override
    public void legOutcome(final Leg leg, final LegStatus status, final Optional<LegReason> reason,
                           final AttemptOrigin origin, final boolean forced, final BodyShape shape,
                           final HttpStatusBucket http) {
        registry.counter(LEG_OUTCOMES_M, Tags.of(
                Tag.of(TAG_LEG, leg.wireValue()),
                Tag.of(TAG_STATUS, status.wireValue()),
                Tag.of(TAG_REASON, reason.map(LegReason::wireValue).orElse(NO_REASON)),
                Tag.of(TAG_ORIGIN, origin.wireValue()),
                Tag.of(TAG_FORCED, Boolean.toString(forced)),
                Tag.of(TAG_SHAPE, shape.wireValue()),
                Tag.of(TAG_HTTP, http.wireValue()))).increment();
    }

    /**
     * Counts one request reaching a final status.
     *
     * @param finalStatus {@code COMPLETED} or {@code FAILED}
     */
    public void processed(final String finalStatus) {
        if (!FINAL_REQUEST_STATUSES.contains(finalStatus)) {
            throw new IllegalArgumentException("not a final request status: " + finalStatus);
        }
        registry.counter(PROCESSED_M, Tags.of(Tag.of(TAG_STATUS, finalStatus))).increment();
    }

    /**
     * Counts one parked leg.
     *
     * @param leg    the leg
     * @param reason the park reason
     */
    @Override
    public void parked(final Leg leg, final LegReason reason) {
        registry.counter(PARKED_M, Tags.of(Tag.of(TAG_LEG, leg.wireValue()),
                Tag.of(TAG_REASON, reason.wireValue()))).increment();
    }

    /**
     * Counts one dead-letter.
     *
     * @param reason the dead-letter reason
     */
    public void deadLettered(final DeadLetterReason reason) {
        registry.counter(DEADLETTERED_M, Tags.of(Tag.of(TAG_REASON, reason.wireValue()))).increment();
    }

    /**
     * Counts one payload found from a source.
     *
     * @param source the payload source
     */
    public void payloadSource(final PayloadSource source) {
        registry.counter(PAYLOAD_SOURCE_M, Tags.of(Tag.of(TAG_SOURCE, source.wireValue())))
                .increment();
    }

    /**
     * Counts one unreadable switch read and the value it defaulted to.
     *
     * @param cause     the unreadable cause
     * @param defaulted the value it defaulted to ({@code ACTIVE} or {@code INACTIVE})
     */
    @Override
    public void youthSwitchUnreadable(final YouthSwitchUnreadableCause cause,
                                      final YouthSwitchRecordedValue defaulted) {
        registry.counter(YOUTH_UNREADABLE_M, Tags.of(Tag.of(TAG_CAUSE, cause.wireValue()),
                Tag.of(TAG_DEFAULTED, defaulted.wireValue()))).increment();
    }

    /** Counts one ambiguous switch answer (more than one permission). */
    public void youthSwitchAmbiguous() {
        registry.counter(YOUTH_AMBIGUOUS_M).increment();
    }

    /**
     * Counts one 2xx answer other than 200.
     *
     * @param status the non-200 success bucket
     */
    @Override
    public void gatewayNon200Success(final Non200SuccessStatus status) {
        registry.counter(GATEWAY_NON200_M, Tags.of(Tag.of(TAG_STATUS, status.wireValue()))).increment();
    }

    /**
     * Counts one recovered send.
     *
     * @param after how it recovered
     */
    public void gatewayRecovered(final GatewayRecovery after) {
        registry.counter(GATEWAY_RECOVERED_M, Tags.of(Tag.of(TAG_AFTER, after.wireValue()))).increment();
    }

    /**
     * Counts one expired-claim re-run of a {@code PENDING} attempt-1 row.
     *
     * @param leg the leg
     */
    public void crashRerun(final Leg leg) {
        registry.counter(CRASH_RERUNS_M, Tags.of(Tag.of(TAG_LEG, leg.wireValue()))).increment();
    }

    /**
     * Records one POST attempt's duration.
     *
     * @param leg      the leg
     * @param duration the attempt duration
     */
    public void gatewayRequestDuration(final Leg leg, final Duration duration) {
        Timer.builder(GATEWAY_DURATION_M).tag(TAG_LEG, leg.wireValue()).register(registry)
                .record(duration);
    }

    /** Counts one unexpected run failure. */
    public void processingFailure() {
        registry.counter(PROCESSING_FAILURES_M).increment();
    }

    /** Counts one failed settlement. */
    public void settlementFailure() {
        registry.counter(SETTLEMENT_FAILURES_M).increment();
    }

    /** Counts one lost broker lock. */
    public void lockLoss() {
        registry.counter(LOCK_LOSS_M).increment();
    }

    /** Counts one stale-runner rejection. */
    public void staleRunnerRejection() {
        registry.counter(STALE_RUNNER_M).increment();
    }

    /** Counts one intake suspension. */
    public void intakeSuspension() {
        registry.counter(INTAKE_SUSPENSIONS_M).increment();
    }

    /**
     * Counts one operations-action failure (proposed slice 005 series, registered-but-unread).
     *
     * @param action the operator action
     * @param reason the failure reason
     */
    public void operationsFailure(final OperationsAction action, final OperationsFailure reason) {
        registry.counter(OPERATIONS_FAILURES_M, Tags.of(Tag.of(TAG_ACTION, action.wireValue()),
                Tag.of(TAG_REASON, reason.wireValue()))).increment();
    }

    /**
     * The tag keys of {@code probation_leg_outcomes_total}, for the registry's own tests.
     *
     * @return the ordered leg-outcome tag keys
     */
    public static List<String> legOutcomeTagKeys() {
        return List.of(TAG_LEG, TAG_STATUS, TAG_REASON, TAG_ORIGIN, TAG_FORCED, TAG_SHAPE, TAG_HTTP);
    }
}
