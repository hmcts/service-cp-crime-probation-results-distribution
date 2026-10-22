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
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender.SentBody;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

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

    private final HearingPayloadSource payloadSource;
    private final YouthFilterSource youthFilterSource;
    private final HmppsSender hmppsSender;
    private final OutboundLog outboundLog;
    private final YouthSwitchRecordedValue defaultWhenUnreadable;

    /**
     * Creates the runner over its four ports and the configured youth-switch default.
     *
     * @param payloadSource         the payload-fetch port
     * @param youthFilterSource     the youth-switch port
     * @param hmppsSender           the HMPPS send port
     * @param outboundLog           the outbound-log port
     * @param defaultWhenUnreadable the value an unreadable switch defaults to ({@code ACTIVE} or
     *                              {@code INACTIVE})
     */
    public DistributionRunner(final HearingPayloadSource payloadSource,
                              final YouthFilterSource youthFilterSource,
                              final HmppsSender hmppsSender,
                              final OutboundLog outboundLog,
                              final YouthSwitchRecordedValue defaultWhenUnreadable) {
        this.payloadSource = payloadSource;
        this.youthFilterSource = youthFilterSource;
        this.hmppsSender = hmppsSender;
        this.outboundLog = outboundLog;
        this.defaultWhenUnreadable = defaultWhenUnreadable;
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
        final boolean recorded = outboundLog.record(outcome, claim);
        LOG.error("HMPPS leg PARKED(payload-unavailable) requestId={} origin={}",
                command.requestId(), runOrigin);
        return new RunResult(runOrigin, LegStatus.PARKED,
                Optional.of(LegReason.PAYLOAD_UNAVAILABLE), recorded);
    }

    private RunResult runLeg(final DistributionCommand command, final AttemptOrigin runOrigin,
                             final RunClaim claim, final FetchedPayload fetched) {
        final YouthSwitchDecision decision = youthFilterSource.read(command);
        final YouthSwitchRecordedValue recordedSwitch = applyDefault(decision);
        // Slice 001's HmppsRedaction is wired in before the send by US1 (T040); the orchestration
        // seam here passes the fetched tree so the pipeline order is exercised end to end.
        final SentBody sent = hmppsSender.send(fetched.payload());
        final String digest = sha256Hex(sent.body());
        final MappedOutcome mapped = mapOutcome(sent.outcome());
        final LegOutcome outcome = new LegOutcome(command, runOrigin, mapped.status(),
                mapped.reason(), Optional.of(recordedSwitch), Optional.of(fetched.source()),
                fetched.sharedTime(), Optional.of(digest), mapped.httpStatus(),
                Optional.of(sent.body()));
        final boolean recorded = outboundLog.record(outcome, claim);
        LOG.info("HMPPS leg {} requestId={} origin={} youthSwitch={} bodyDigest={}",
                mapped.status(), command.requestId(), runOrigin, recordedSwitch, digest);
        return new RunResult(runOrigin, mapped.status(), mapped.reason(), recorded);
    }

    private YouthSwitchRecordedValue applyDefault(final YouthSwitchDecision decision) {
        final YouthSwitchRecordedValue recorded;
        if (decision instanceof YouthSwitchDecision.Active) {
            recorded = YouthSwitchRecordedValue.ACTIVE;
        } else if (decision instanceof YouthSwitchDecision.Inactive) {
            recorded = YouthSwitchRecordedValue.INACTIVE;
        } else if (defaultWhenUnreadable == YouthSwitchRecordedValue.ACTIVE) {
            recorded = YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_ACTIVE;
        } else {
            recorded = YouthSwitchRecordedValue.UNREADABLE_DEFAULTED_INACTIVE;
        }
        return recorded;
    }

    private static MappedOutcome mapOutcome(final PublishOutcome outcome) {
        final MappedOutcome mapped;
        if (outcome instanceof PublishOutcome.Sent sent) {
            mapped = new MappedOutcome(LegStatus.SENT, Optional.empty(),
                    OptionalInt.of(sent.httpStatus()));
        } else if (outcome instanceof PublishOutcome.FailedNonTransient failed) {
            mapped = new MappedOutcome(LegStatus.FAILED, Optional.of(LegReason.RECEIVER_REJECTED),
                    OptionalInt.of(failed.httpStatus()));
        } else {
            final PublishOutcome.TransientExhausted transientOutcome =
                    (PublishOutcome.TransientExhausted) outcome;
            mapped = new MappedOutcome(LegStatus.PARKED, Optional.of(LegReason.GATEWAY_TRANSIENT),
                    transientOutcome.httpStatus());
        }
        return mapped;
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
