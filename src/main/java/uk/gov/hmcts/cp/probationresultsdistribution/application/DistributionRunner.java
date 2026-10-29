package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender.SentBody;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyShape;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.HttpStatusBucket;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Non200SuccessStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.HmppsRedaction;

/**
 * The application core: the one use case, orchestrating the four owned ports in the fixed pipeline
 * order (design §4.4, FR-002-011). It imports no Azure, Redis, HTTP-client or JDBC type; the hearing
 * payload crosses as a {@code JsonNode} only. The run origin is captured once at admission and
 * threads unchanged into every row the run writes, every metric and every log line (FR-002-010).
 *
 * <p>The order is: fetch the payload (the only pre-leg stage), then the HMPPS leg — read the switch,
 * redact, digest the body, send — then record the outcome, then the listener settles. Slice 001's
 * redaction and the retry, final-delivery and switch-default refinements are wired in by the user
 * stories; this core fixes the order and the port boundary.
 */
public final class DistributionRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DistributionRunner.class);
    private static final int HTTP_OK = 200;
    private static final String HEARING = "hearing";
    private static final String PROSECUTION_CASES = "prosecutionCases";

    private final HearingPayloadSource payloadSource;
    private final YouthFilterSource youthFilterSource;
    private final HmppsSender hmppsSender;
    private final OutboundLog outboundLog;
    private final YouthSwitchRecordedValue defaultWhenUnreadable;
    private final OutcomeMetrics metrics;
    private final HmppsRedaction redaction = new HmppsRedaction();

    /**
     * Creates the runner over its four ports, the configured youth-switch default and the metric
     * registry it labels every outcome on.
     *
     * @param payloadSource         the payload-fetch port
     * @param youthFilterSource     the youth-switch port
     * @param hmppsSender           the HMPPS send port
     * @param outboundLog           the outbound-log port
     * @param defaultWhenUnreadable the value an unreadable switch defaults to ({@code ACTIVE} or
     *                              {@code INACTIVE})
     * @param metrics               the metric registry
     */
    public DistributionRunner(final HearingPayloadSource payloadSource,
                              final YouthFilterSource youthFilterSource,
                              final HmppsSender hmppsSender,
                              final OutboundLog outboundLog,
                              final YouthSwitchRecordedValue defaultWhenUnreadable,
                              final OutcomeMetrics metrics) {
        this.payloadSource = payloadSource;
        this.youthFilterSource = youthFilterSource;
        this.hmppsSender = hmppsSender;
        this.outboundLog = outboundLog;
        this.defaultWhenUnreadable = defaultWhenUnreadable;
        this.metrics = metrics;
    }

    /**
     * Runs the HMPPS pipeline for one admitted delivery, in the fixed order of FR-002-011.
     *
     * @param command                the admitted command
     * @param runOrigin              the run origin captured at admission
     * @param claim                  the request claim the run's writes are fenced by
     * @param finalPermittedDelivery whether this is the final delivery the broker will permit, so a
     *                               transient miss fails the leg instead of parking it (FR-002-018)
     * @return the run result for the listener to settle on
     */
    public RunResult run(final DistributionCommand command, final AttemptOrigin runOrigin,
                         final RunClaim claim, final boolean finalPermittedDelivery) {
        Objects.requireNonNull(claim, "claim");
        return runPipeline(command, runOrigin, finalPermittedDelivery,
                outcome -> outboundLog.record(outcome, claim));
    }

    /**
     * Runs the HMPPS pipeline for one replayed delivery, re-running the attempt-1 row in place
     * (slice 004, FR-004-007, FR-004-008) instead of writing it only when it is {@code PENDING} or
     * {@code PARKED}.
     *
     * @param command                the admitted command
     * @param runOrigin              the run origin captured at admission ({@link AttemptOrigin#REPLAY})
     * @param claim                  the request claim the run's writes are fenced by
     * @param finalPermittedDelivery whether this is the final delivery the broker will permit
     * @param replayMessageId        the replay message's {@code messageId}, recorded in {@code note}
     * @return the run result for the listener to settle on
     */
    public RunResult run(final DistributionCommand command, final AttemptOrigin runOrigin,
                         final RunClaim claim, final boolean finalPermittedDelivery,
                         final String replayMessageId) {
        Objects.requireNonNull(claim, "claim");
        Objects.requireNonNull(replayMessageId, "replayMessageId");
        return runPipeline(command, runOrigin, finalPermittedDelivery,
                outcome -> outboundLog.recordReplay(outcome, claim, replayMessageId));
    }

    /**
     * Runs one claimed resend attempt (slice 004, FR-004-026, FR-004-027, FR-004-029): the payload
     * fetch, the {@code sharedTime} check, the switch, redaction, body digest and send, recording the
     * outcome on the attempt's own row under its leg claim. A resend never parks. An attempt claimed
     * under the forced-resend override is labelled {@code forced=true} on its outcome metric and log
     * line (FR-004-038, FR-004-040); every other outcome is {@code forced=false}.
     *
     * @param command             the command rebuilt from the request's columns, with no
     *                            {@code userId}, carrying the stored {@code shared_time}
     * @param attempt             the claimed resend attempt row
     * @param allowChangedPayload whether {@code allow-changed-payload} was requested
     * @return the run result; {@code recorded} is {@code false} on a stale-runner rejection
     */
    public RunResult runResend(final DistributionCommand command, final ResendAttempt attempt,
                               final boolean allowChangedPayload) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(attempt, "attempt");
        final AttemptOrigin origin = AttemptOrigin.RESEND;
        final Optional<FetchedPayload> fetched = payloadSource.fetch(command);
        final RunResult result;
        if (fetched.isEmpty()) {
            // A resend never parks: a payload missed everywhere fails at once (FR-004-027).
            result = failPayloadUnavailable(command, origin,
                    new ResendWriter(outboundLog, attempt, false));
        } else {
            // The sharedTime check runs directly after the fetch, before any leg stage
            // (FR-004-029); the attempt records the instant it parsed (FR-004-030).
            final Optional<Instant> payloadSharedTime = SharedTimeCheck.parse(fetched.get().payload());
            final boolean unchanged = SharedTimeCheck.matches(payloadSharedTime, command.sharedTime());
            final FetchedPayload checked = new FetchedPayload(fetched.get().payload(),
                    fetched.get().source(), payloadSharedTime);
            if (unchanged || allowChangedPayload) {
                final boolean overrideUsed = !unchanged;
                result = runLeg(command, origin, checked, true,
                        new ResendWriter(outboundLog, attempt, overrideUsed));
            } else {
                result = payloadChanged(command, origin, checked,
                        new ResendWriter(outboundLog, attempt, false));
            }
        }
        return result;
    }

    private RunResult runPipeline(final DistributionCommand command, final AttemptOrigin runOrigin,
                                  final boolean finalPermittedDelivery, final LegWriter writer) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(runOrigin, "runOrigin");
        final Optional<FetchedPayload> fetched = payloadSource.fetch(command);
        final RunResult result;
        if (fetched.isEmpty()) {
            result = finalPermittedDelivery
                    ? failPayloadUnavailable(command, runOrigin, writer)
                    : park(command, runOrigin, writer);
        } else {
            result = runLeg(command, runOrigin, fetched.get(), finalPermittedDelivery, writer);
        }
        return result;
    }

    private RunResult payloadChanged(final DistributionCommand command,
                                     final AttemptOrigin runOrigin, final FetchedPayload fetched,
                                     final LegWriter writer) {
        // No switch read, no redaction, no send: the row carries the payload's source and parsed
        // sharedTime only (FR-004-029, FR-004-030).
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.FAILED,
                Optional.of(LegReason.PAYLOAD_CHANGED), Optional.empty(),
                Optional.of(fetched.source()), fetched.sharedTime(), Optional.empty(),
                OptionalInt.empty(), Optional.empty());
        return record(command, runOrigin, outcome, Optional.empty(), BodyShape.CASE,
                Optional.empty(), writer);
    }

    private RunResult park(final DistributionCommand command, final AttemptOrigin runOrigin,
                           final LegWriter writer) {
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.PARKED,
                Optional.of(LegReason.PAYLOAD_UNAVAILABLE), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty());
        return record(command, runOrigin, outcome, Optional.empty(), BodyShape.CASE,
                Optional.empty(), writer);
    }

    private RunResult failPayloadUnavailable(final DistributionCommand command,
                                             final AttemptOrigin runOrigin,
                                             final LegWriter writer) {
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.FAILED,
                Optional.of(LegReason.PAYLOAD_UNAVAILABLE), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty());
        return record(command, runOrigin, outcome, Optional.empty(), BodyShape.CASE,
                Optional.empty(), writer);
    }

    private RunResult runLeg(final DistributionCommand command, final AttemptOrigin runOrigin,
                             final FetchedPayload fetched, final boolean finalPermittedDelivery,
                             final LegWriter writer) {
        final YouthSwitchDecision decision = youthFilterSource.read(command);
        final YouthSwitchRecordedValue recordedSwitch = applyDefault(decision);
        final Optional<YouthSwitchUnreadableCause> switchCause = decision
                instanceof YouthSwitchDecision.Unreadable unreadable
                ? Optional.of(unreadable.cause()) : Optional.empty();
        final EffectiveSwitchState effective = recordedSwitch.removesYouthDefendants()
                ? EffectiveSwitchState.ACTIVE : EffectiveSwitchState.INACTIVE;
        final RedactionResult redacted = redaction.redact(fetched.payload(), effective);
        return switch (redacted) {
            case RedactionResult.Body body -> send(
                    command, runOrigin, recordedSwitch, fetched, body.payload(),
                    finalPermittedDelivery, switchCause, writer);
            case RedactionResult.EmptiedByR1 ignored -> notApplicable(
                    command, runOrigin, recordedSwitch, fetched,
                    LegReason.SJP_OR_BOX_HEARING, switchCause, writer);
            case RedactionResult.EmptiedByR11 ignored -> notApplicable(
                    command, runOrigin, recordedSwitch, fetched,
                    LegReason.NOTHING_LEFT_AFTER_REDACTION, switchCause, writer);
            case RedactionResult.RulesFailure failure -> rulesFailure(
                    command, runOrigin, recordedSwitch, fetched, failure.ruleId(), switchCause,
                    writer);
        };
    }

    private RunResult send(final DistributionCommand command, final AttemptOrigin runOrigin,
                           final YouthSwitchRecordedValue recordedSwitch,
                           final FetchedPayload fetched, final JsonNode redactedBody,
                           final boolean finalPermittedDelivery,
                           final Optional<YouthSwitchUnreadableCause> switchCause,
                           final LegWriter writer) {
        final SentBody sent = hmppsSender.send(redactedBody);
        final String digest = sha256Hex(sent.body());
        final MappedOutcome mapped = mapOutcome(sent.outcome(), finalPermittedDelivery);
        final BodyShape shape = shapeOf(redactedBody);
        final LegOutcome outcome = new LegOutcome(command, runOrigin, mapped.status(),
                mapped.reason(), Optional.of(recordedSwitch), Optional.of(fetched.source()),
                fetched.sharedTime(), Optional.of(digest), mapped.httpStatus(),
                Optional.of(sent.body()));
        final Optional<GatewayRecovery> recovery = sent.outcome() instanceof PublishOutcome.Sent succeeded
                ? succeeded.recovery() : Optional.empty();
        return record(command, runOrigin, outcome, switchCause, shape, recovery, writer);
    }

    private RunResult notApplicable(final DistributionCommand command, final AttemptOrigin runOrigin,
                                    final YouthSwitchRecordedValue recordedSwitch,
                                    final FetchedPayload fetched, final LegReason reason,
                                    final Optional<YouthSwitchUnreadableCause> switchCause,
                                    final LegWriter writer) {
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.NOT_APPLICABLE,
                Optional.of(reason), Optional.of(recordedSwitch), Optional.of(fetched.source()),
                fetched.sharedTime(), Optional.empty(), OptionalInt.empty(), Optional.empty());
        return record(command, runOrigin, outcome, switchCause, BodyShape.CASE,
                Optional.empty(), writer);
    }

    private RunResult rulesFailure(final DistributionCommand command, final AttemptOrigin runOrigin,
                                   final YouthSwitchRecordedValue recordedSwitch,
                                   final FetchedPayload fetched, final String ruleId,
                                   final Optional<YouthSwitchUnreadableCause> switchCause,
                                   final LegWriter writer) {
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.FAILED,
                Optional.of(LegReason.RULES_EXCEPTION), Optional.of(recordedSwitch),
                Optional.of(fetched.source()), fetched.sharedTime(), Optional.empty(),
                OptionalInt.empty(), Optional.empty());
        LOG.error("HMPPS redaction rules failed requestId={} origin={} rule={}",
                command.requestId(), runOrigin, ruleId);
        return record(command, runOrigin, outcome, switchCause, BodyShape.CASE,
                Optional.empty(), writer);
    }

    // A rejected fenced write exits before any outcome telemetry can claim that it was recorded.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private RunResult record(final DistributionCommand command, final AttemptOrigin runOrigin,
                             final LegOutcome outcome,
                             final Optional<YouthSwitchUnreadableCause> switchCause,
                             final BodyShape shape, final Optional<GatewayRecovery> recovery,
                             final LegWriter writer) {
        final boolean recorded = writer.write(outcome);
        if (!recorded) {
            return new RunResult(runOrigin, outcome.status(), outcome.reason(), false);
        }
        switchCause.ifPresent(cause -> {
            metrics.youthSwitchUnreadable(cause, defaultWhenUnreadable);
            LOG.warn("HMPPS leg youth switch UNREADABLE requestId={} origin={} cause={} defaulted={}",
                    command.requestId(), runOrigin, cause, defaultWhenUnreadable);
        });
        outcome.youthSwitch().ifPresent(metrics::youthSwitchState);
        final boolean forced = writer.forced();
        metrics.legOutcome(Leg.HMPPS, outcome.status(), outcome.reason(), runOrigin, forced,
                shape, httpBucket(outcome.httpStatus()));
        if (outcome.status() == LegStatus.PARKED) {
            metrics.parked(Leg.HMPPS, outcome.reason().orElseThrow());
        }
        if (outcome.status() == LegStatus.SENT && outcome.httpStatus().orElse(0) != HTTP_OK) {
            metrics.gatewayNon200Success(non200Status(outcome.httpStatus().orElseThrow()));
        }
        recovery.ifPresent(metrics::gatewayRecovered);
        final String status = outcome.status().wireValue();
        final String reason = outcome.reason().map(LegReason::wireValue).orElse(null);
        final String youthSwitch = outcome.youthSwitch()
                .map(YouthSwitchRecordedValue::wireValue).orElse(null);
        final String youthSwitchCause = switchCause.map(YouthSwitchUnreadableCause::wireValue)
                .orElse(null);
        final int httpStatusValue = outcome.httpStatus().orElse(0);
        final String httpStatus = outcome.httpStatus().isPresent()
                ? Integer.toString(httpStatusValue) : "none";
        final String digest = outcome.bodyDigest().orElse(null);
        if (outcome.status() == LegStatus.FAILED || outcome.status() == LegStatus.PARKED) {
            LOG.error("HMPPS leg outcome status={} reason={} httpStatus={} bodyDigest={} "
                            + "runOrigin={} forced={} youthSwitch={} youthSwitchCause={} requestId={}",
                    status, reason, httpStatus, digest, runOrigin, forced, youthSwitch,
                    youthSwitchCause, command.requestId());
        } else if (isDefaulted(youthSwitch)
                || outcome.status() == LegStatus.SENT && httpStatusValue != HTTP_OK) {
            LOG.warn("HMPPS leg outcome status={} reason={} httpStatus={} bodyDigest={} "
                            + "runOrigin={} forced={} youthSwitch={} youthSwitchCause={} requestId={}",
                    status, reason, httpStatus, digest, runOrigin, forced, youthSwitch,
                    youthSwitchCause, command.requestId());
        } else {
            LOG.info("HMPPS leg outcome status={} reason={} httpStatus={} bodyDigest={} "
                            + "runOrigin={} forced={} youthSwitch={} youthSwitchCause={} requestId={}",
                    status, reason, httpStatus, digest, runOrigin, forced, youthSwitch,
                    youthSwitchCause, command.requestId());
        }
        return new RunResult(runOrigin, outcome.status(), outcome.reason(), recorded);
    }

    private static boolean isDefaulted(final String youthSwitch) {
        return YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_ACTIVE.wireValue().equals(youthSwitch)
                || YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_INACTIVE.wireValue().equals(youthSwitch);
    }

    /**
     * The body shape label (FR-002-059, research R7): {@code application-only} when the redacted body
     * carries court applications and no prosecution case; {@code case} otherwise. Redaction's R11
     * guarantees a {@code Body} carries at least one of the two, so an absent or empty
     * {@code prosecutionCases} means a court-application-only body.
     *
     * @param redactedBody the redacted hearing tree being sent
     * @return the body shape
     */
    private static BodyShape shapeOf(final JsonNode redactedBody) {
        return hasNonEmptyArray(redactedBody.get(HEARING), PROSECUTION_CASES)
                ? BodyShape.CASE : BodyShape.APPLICATION_ONLY;
    }

    private static boolean hasNonEmptyArray(final JsonNode hearing, final String member) {
        final JsonNode node = hearing == null ? null : hearing.get(member);
        return node != null && node.isArray() && !node.isEmpty();
    }

    private static HttpStatusBucket httpBucket(final OptionalInt status) {
        return status.isEmpty() ? HttpStatusBucket.NONE : switch (status.getAsInt()) {
            case HTTP_OK -> HttpStatusBucket.OK_200;
            case 400 -> HttpStatusBucket.BAD_REQUEST_400;
            case 401 -> HttpStatusBucket.UNAUTHORIZED_401;
            case 408 -> HttpStatusBucket.REQUEST_TIMEOUT_408;
            case 429 -> HttpStatusBucket.TOO_MANY_REQUESTS_429;
            default -> bucketByRange(status.getAsInt());
        };
    }

    private static HttpStatusBucket bucketByRange(final int status) {
        final HttpStatusBucket bucket;
        if (status >= 200 && status < 300) {
            bucket = HttpStatusBucket.TWO_XX_OTHER;
        } else if (status >= 400 && status < 500) {
            bucket = HttpStatusBucket.FOUR_XX_OTHER;
        } else if (status >= 500 && status < 600) {
            bucket = HttpStatusBucket.FIVE_XX;
        } else {
            bucket = HttpStatusBucket.OTHER;
        }
        return bucket;
    }

    private static Non200SuccessStatus non200Status(final int status) {
        return switch (status) {
            case 201 -> Non200SuccessStatus.CREATED_201;
            case 202 -> Non200SuccessStatus.ACCEPTED_202;
            case 204 -> Non200SuccessStatus.NO_CONTENT_204;
            default -> Non200SuccessStatus.OTHER;
        };
    }

    private YouthSwitchRecordedValue applyDefault(final YouthSwitchDecision decision) {
        return switch (decision) {
            case YouthSwitchDecision.Active ignored -> YouthSwitchRecordedValue.ACTIVE;
            case YouthSwitchDecision.Inactive ignored -> YouthSwitchRecordedValue.INACTIVE;
            case YouthSwitchDecision.Unreadable ignored -> defaultedUnreadable();
        };
    }

    private YouthSwitchRecordedValue defaultedUnreadable() {
        return defaultWhenUnreadable == YouthSwitchRecordedValue.ACTIVE
                ? YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_ACTIVE
                : YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_INACTIVE;
    }

    private static MappedOutcome mapOutcome(final PublishOutcome outcome,
                                            final boolean finalPermittedDelivery) {
        return switch (outcome) {
            case PublishOutcome.Sent sent -> new MappedOutcome(LegStatus.SENT, Optional.empty(),
                    OptionalInt.of(sent.httpStatus()));
            case PublishOutcome.FailedNonTransient failed -> new MappedOutcome(LegStatus.FAILED,
                    Optional.of(LegReason.RECEIVER_REJECTED), OptionalInt.of(failed.httpStatus()));
            case PublishOutcome.TransientExhausted transientOutcome -> new MappedOutcome(
                    finalPermittedDelivery ? LegStatus.FAILED : LegStatus.PARKED,
                    Optional.of(LegReason.GATEWAY_TRANSIENT), transientOutcome.httpStatus());
        };
    }

    private static String sha256Hex(final String body) {
        try {
            final byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required but unavailable", e);
        }
    }

    private record MappedOutcome(LegStatus status, Optional<LegReason> reason, OptionalInt httpStatus) {
    }

    /**
     * Where a run writes its leg outcome: the attempt-1 row under the request claim (a queue run),
     * the replay write path under the request claim (a replayed run), or the claimed resend row
     * under its own leg claim (a resend). Each returns {@code false} on a stale-runner rejection.
     */
    @FunctionalInterface
    private interface LegWriter {

        boolean write(LegOutcome outcome);

        /**
         * Whether the outcome is a forced resend's, labelled {@code forced=true} (FR-004-038).
         *
         * @return {@code false} for every write path but a forced resend attempt
         */
        default boolean forced() {
            return false;
        }
    }

    /**
     * The resend write path: the claimed attempt row, completed under its own leg claim, labelled
     * forced when the attempt was claimed under the forced-resend override.
     *
     * @param outboundLog  the outbound-log port
     * @param attempt      the claimed resend attempt row
     * @param overrideUsed whether {@code allow-changed-payload} let a changed {@code sharedTime} through
     */
    private record ResendWriter(OutboundLog outboundLog, ResendAttempt attempt, boolean overrideUsed)
            implements LegWriter {

        @Override
        public boolean write(final LegOutcome outcome) {
            return outboundLog.recordResend(outcome, attempt, overrideUsed);
        }

        @Override
        public boolean forced() {
            return attempt.forced();
        }
    }
}
