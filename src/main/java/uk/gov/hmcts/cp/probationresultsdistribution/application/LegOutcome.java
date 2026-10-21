package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

/**
 * A leg attempt's outcome as the core derives it, for the {@link OutboundLog} to persist. It is the
 * final, mapped shape: the business mapping from a {@code PublishOutcome} or a redaction result to a
 * {@link LegStatus} and {@link LegReason} lives in the core, while the adapter only translates this
 * record into the {@code outbound_message} row and the {@code outbound_body} text.
 *
 * @param command           the admitted command, giving the row's identity
 * @param runOrigin         the run origin captured once at admission
 * @param status            the leg status
 * @param reason            the bounded reason, empty on {@code PENDING} and {@code SENT}
 * @param youthSwitch       the recorded switch value, empty before the switch is read
 * @param payloadSource     where the payload was read from, empty when none was read
 * @param payloadSharedTime the payload's {@code sharedTime}, when it parsed
 * @param bodyDigest        the lower-case hex SHA-256 of the sent bytes, empty when none was sent
 * @param httpStatus        the gateway's last status, when there was one
 * @param body              the exact UTF-8 body text POSTed, empty when none was sent
 */
public record LegOutcome(
        DistributionCommand command,
        AttemptOrigin runOrigin,
        LegStatus status,
        Optional<LegReason> reason,
        Optional<YouthSwitchRecordedValue> youthSwitch,
        Optional<PayloadSource> payloadSource,
        Optional<Instant> payloadSharedTime,
        Optional<String> bodyDigest,
        OptionalInt httpStatus,
        Optional<String> body) {

    /** Rejects null components; absence is an empty optional, never null. */
    public LegOutcome {
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(runOrigin, "runOrigin");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(youthSwitch, "youthSwitch");
        Objects.requireNonNull(payloadSource, "payloadSource");
        Objects.requireNonNull(payloadSharedTime, "payloadSharedTime");
        Objects.requireNonNull(bodyDigest, "bodyDigest");
        Objects.requireNonNull(httpStatus, "httpStatus");
        Objects.requireNonNull(body, "body");
    }
}
