package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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
import uk.gov.hmcts.cp.probationresultsdistribution.domain.HttpStatusBucket;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Non200SuccessStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
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
     * @param command   the admitted command
     * @param runOrigin the run origin captured at admission
     * @param claim     the request claim the run's writes are fenced by
     * @return the run result for the listener to settle on
     */
    public RunResult run(final DistributionCommand command, final AttemptOrigin runOrigin,
                         final RunClaim claim) {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(runOrigin, "runOrigin");
        Objects.requireNonNull(claim, "claim");
        final Optional<FetchedPayload> fetched = payloadSource.fetch(command);
        final RunResult result;
        if (fetched.isEmpty()) {
            result = park(command, runOrigin, claim);
        } else {
            result = runLeg(command, runOrigin, claim, fetched.get());
        }
        return result;
    }

    private RunResult park(final DistributionCommand command, final AttemptOrigin runOrigin,
                           final RunClaim claim) {
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.PARKED,
                Optional.of(LegReason.PAYLOAD_UNAVAILABLE), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), OptionalInt.empty(), Optional.empty());
        metrics.legOutcome(Leg.HMPPS, LegStatus.PARKED, Optional.of(LegReason.PAYLOAD_UNAVAILABLE),
                runOrigin, false, BodyShape.CASE, HttpStatusBucket.NONE);
        metrics.parked(Leg.HMPPS, LegReason.PAYLOAD_UNAVAILABLE);
        return record(command, runOrigin, claim, outcome);
    }

    private RunResult runLeg(final DistributionCommand command, final AttemptOrigin runOrigin,
                             final RunClaim claim, final FetchedPayload fetched) {
        final YouthSwitchDecision decision = youthFilterSource.read(command);
        final YouthSwitchRecordedValue recordedSwitch = applyDefault(decision);
        if (decision instanceof YouthSwitchDecision.Unreadable unreadable) {
            metrics.youthSwitchUnreadable(unreadable.cause(), defaultWhenUnreadable);
            LOG.warn("HMPPS leg youth switch UNREADABLE requestId={} origin={} cause={} defaulted={}",
                    command.requestId(), runOrigin, unreadable.cause(), defaultWhenUnreadable);
        }
        final EffectiveSwitchState effective = recordedSwitch.removesYouthDefendants()
                ? EffectiveSwitchState.ACTIVE : EffectiveSwitchState.INACTIVE;
        final RedactionResult redacted = redaction.redact(fetched.payload(), effective);
        return switch (redacted) {
            case RedactionResult.Body body -> send(
                    command, runOrigin, claim, recordedSwitch, fetched, body.payload());
            case RedactionResult.EmptiedByR1 ignored -> notApplicable(
                    command, runOrigin, claim, recordedSwitch, fetched,
                    LegReason.SJP_OR_BOX_HEARING);
            case RedactionResult.EmptiedByR11 ignored -> notApplicable(
                    command, runOrigin, claim, recordedSwitch, fetched,
                    LegReason.NOTHING_LEFT_AFTER_REDACTION);
            case RedactionResult.RulesFailure failure -> rulesFailure(
                    command, runOrigin, claim, recordedSwitch, fetched, failure.ruleId());
        };
    }

    private RunResult send(final DistributionCommand command, final AttemptOrigin runOrigin,
                           final RunClaim claim, final YouthSwitchRecordedValue recordedSwitch,
                           final FetchedPayload fetched, final JsonNode redactedBody) {
        final SentBody sent = hmppsSender.send(redactedBody);
        final String digest = sha256Hex(sent.body());
        final MappedOutcome mapped = mapOutcome(sent.outcome());
        final BodyShape shape = shapeOf(redactedBody);
        final LegOutcome outcome = new LegOutcome(command, runOrigin, mapped.status(),
                mapped.reason(), Optional.of(recordedSwitch), Optional.of(fetched.source()),
                fetched.sharedTime(), Optional.of(digest), mapped.httpStatus(),
                Optional.of(sent.body()));
        metrics.legOutcome(Leg.HMPPS, mapped.status(), mapped.reason(), runOrigin, false, shape,
                httpBucket(mapped.httpStatus()));
        if (mapped.status() == LegStatus.SENT && mapped.httpStatus().orElse(0) != HTTP_OK) {
            metrics.gatewayNon200Success(non200Status(mapped.httpStatus().orElse(0)));
            LOG.warn("HMPPS leg SENT non-200 requestId={} origin={} httpStatus={}",
                    command.requestId(), runOrigin, mapped.httpStatus().orElse(0));
        }
        if (mapped.status() == LegStatus.PARKED) {
            metrics.parked(Leg.HMPPS, LegReason.GATEWAY_TRANSIENT);
        }
        return record(command, runOrigin, claim, outcome);
    }

    private RunResult notApplicable(final DistributionCommand command, final AttemptOrigin runOrigin,
                                    final RunClaim claim,
                                    final YouthSwitchRecordedValue recordedSwitch,
                                    final FetchedPayload fetched, final LegReason reason) {
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.NOT_APPLICABLE,
                Optional.of(reason), Optional.of(recordedSwitch), Optional.of(fetched.source()),
                fetched.sharedTime(), Optional.empty(), OptionalInt.empty(), Optional.empty());
        metrics.legOutcome(Leg.HMPPS, LegStatus.NOT_APPLICABLE, Optional.of(reason), runOrigin,
                false, BodyShape.CASE, HttpStatusBucket.NONE);
        return record(command, runOrigin, claim, outcome);
    }

    private RunResult rulesFailure(final DistributionCommand command, final AttemptOrigin runOrigin,
                                   final RunClaim claim,
                                   final YouthSwitchRecordedValue recordedSwitch,
                                   final FetchedPayload fetched, final String ruleId) {
        final LegOutcome outcome = new LegOutcome(command, runOrigin, LegStatus.FAILED,
                Optional.of(LegReason.RULES_EXCEPTION), Optional.of(recordedSwitch),
                Optional.of(fetched.source()), fetched.sharedTime(), Optional.empty(),
                OptionalInt.empty(), Optional.empty());
        metrics.legOutcome(Leg.HMPPS, LegStatus.FAILED, Optional.of(LegReason.RULES_EXCEPTION),
                runOrigin, false, BodyShape.CASE, HttpStatusBucket.NONE);
        LOG.error("HMPPS leg FAILED(rules-exception) requestId={} origin={} rule={}",
                command.requestId(), runOrigin, ruleId);
        return record(command, runOrigin, claim, outcome);
    }

    private RunResult record(final DistributionCommand command, final AttemptOrigin runOrigin,
                             final RunClaim claim, final LegOutcome outcome) {
        final boolean recorded = outboundLog.record(outcome, claim);
        LOG.info("HMPPS leg {} requestId={} origin={} youthSwitch={} reason={}",
                outcome.status(), command.requestId(), runOrigin,
                outcome.youthSwitch().orElse(null),
                outcome.reason().map(LegReason::wireValue).orElse(null));
        return new RunResult(runOrigin, outcome.status(), outcome.reason(), recorded);
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

    private static MappedOutcome mapOutcome(final PublishOutcome outcome) {
        return switch (outcome) {
            case PublishOutcome.Sent sent -> new MappedOutcome(LegStatus.SENT, Optional.empty(),
                    OptionalInt.of(sent.httpStatus()));
            case PublishOutcome.FailedNonTransient failed -> new MappedOutcome(LegStatus.FAILED,
                    Optional.of(LegReason.RECEIVER_REJECTED), OptionalInt.of(failed.httpStatus()));
            case PublishOutcome.TransientExhausted transientOutcome -> new MappedOutcome(
                    LegStatus.PARKED, Optional.of(LegReason.GATEWAY_TRANSIENT),
                    transientOutcome.httpStatus());
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
}
