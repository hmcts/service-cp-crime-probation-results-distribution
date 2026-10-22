package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.FailureReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;

/**
 * The processed-log store the idempotency guard claims through (design §4.4, §4.6). The interface is
 * free of JDBC types so the application core can depend on it; the JDBC adapter implements it. The
 * insert is the claim: a unique violation means a concurrent or duplicate delivery, handled, not an
 * error.
 */
public interface ProcessedRequestRepository {

    /**
     * Finds the current state of a request by its identity.
     *
     * @param source    the command source
     * @param requestId the request id
     * @return the request's state, or empty when no row exists
     */
    Optional<RequestRecord> find(String source, UUID requestId);

    /**
     * Inserts the {@code processed_request} ({@code RECEIVED}) and the attempt-1 {@code PENDING}
     * {@code outbound_message} row in one transaction, claiming the request.
     *
     * @param command the admitted command
     * @param commandBody the admitted body exactly as received
     * @param fingerprint the request fingerprint
     * @param claim       the claim to take
     * @return {@code true} when the claim was inserted; {@code false} on a unique violation (a
     *     concurrent or duplicate first delivery)
     */
    boolean insertClaim(DistributionCommand command, String commandBody, String fingerprint,
                        RunClaim claim);

    /**
     * Atomically takes over an absent or expired claim, bumping {@code attempts}.
     *
     * @param source    the command source
     * @param requestId the request id
     * @param claim     the fresh claim to take
     * @return {@code true} when the claim was taken; {@code false} when a live claim still held it
     */
    boolean reclaim(String source, UUID requestId, RunClaim claim);

    /**
     * Moves a {@code RECEIVED}/{@code RETRYING} request to {@code COMPLETED} with its completion
     * reason, guarded by the claim, and releases the claim. A {@code COMPLETED} row is never moved.
     *
     * @param source    the command source
     * @param requestId the request id
     * @param claim     the claim fencing the transition
     * @param reason    the completion reason
     * @return {@code true} when the transition took effect; {@code false} when the claim no longer
     *     matches or the request was no longer {@code RECEIVED}/{@code RETRYING}
     */
    boolean markCompleted(String source, UUID requestId, RunClaim claim, CompletionReason reason);

    /**
     * Moves a {@code RECEIVED}/{@code RETRYING} request to {@code RETRYING} (failure reason NULL),
     * guarded by the claim, and releases the claim so the redelivery can reclaim it.
     *
     * @param source    the command source
     * @param requestId the request id
     * @param claim     the claim fencing the transition
     * @return {@code true} when the transition took effect; {@code false} when the claim no longer
     *     matches
     */
    boolean markRetrying(String source, UUID requestId, RunClaim claim);

    /**
     * Moves a {@code RECEIVED}/{@code RETRYING} request to {@code FAILED} with its failure reason and,
     * for {@code leg-exhausted}, the final delivery's {@code messageId}, guarded by the claim, and
     * releases the claim (FR-002-047).
     *
     * @param source             the command source
     * @param requestId          the request id
     * @param claim              the claim fencing the transition
     * @param reason             the failure reason
     * @param exhaustedMessageId the final delivery's {@code messageId}, recorded with
     *                           {@code leg-exhausted}
     * @return {@code true} when the transition took effect; {@code false} when the claim no longer
     *     matches
     */
    boolean markFailed(String source, UUID requestId, RunClaim claim, FailureReason reason,
                       String exhaustedMessageId);

    /**
     * A request's current state as the guard needs it.
     *
     * @param status      the request status
     * @param fingerprint the stored fingerprint
     * @param claimLive   whether a live (unexpired) claim is held
     * @param attemptOneStatus the recorded HMPPS attempt-1 status, if present
     * @param attemptOneReason the bounded HMPPS attempt-1 reason, if present
     * @param attemptOneHttpStatus the HMPPS attempt-1 HTTP status, if present
     */
    record RequestRecord(String status, String fingerprint, boolean claimLive,
                         Optional<String> attemptOneStatus, Optional<String> attemptOneReason,
                         OptionalInt attemptOneHttpStatus) {

        public RequestRecord(final String status, final String fingerprint,
                             final boolean claimLive) {
            this(status, fingerprint, claimLive, Optional.empty(), Optional.empty(), OptionalInt.empty());
        }

        /** Rejects null components. */
        public RequestRecord {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(attemptOneStatus, "attemptOneStatus");
            Objects.requireNonNull(attemptOneReason, "attemptOneReason");
        }
    }
}
