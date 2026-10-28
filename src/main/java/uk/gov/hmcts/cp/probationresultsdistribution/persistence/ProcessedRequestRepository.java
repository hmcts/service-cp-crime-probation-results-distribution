package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.FailureReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
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
     * Admits a {@code FAILED} request as a replay, in one statement (design A2-62, FR-004-004):
     * moves it {@code FAILED} to {@code RECEIVED}, clears {@code failure_reason} and
     * {@code exhausted_message_id}, sets {@code replayed_at}, appends the
     * {@code REPLAYED_AFTER_FAILURE} line and the route line to {@code audit_note}, and takes a
     * fresh request claim. Matches only a {@code FAILED} row — the caller has already excluded a
     * live {@code PENDING} resend row on the leg (FR-004-003).
     *
     * @param source    the command source
     * @param requestId the request id
     * @param claim     the fresh claim to take
     * @param route     the replay route, appended to {@code audit_note} as an audit label only
     * @return {@code true} when the replay statement matched a {@code FAILED} row; {@code false}
     *     when the request was no longer {@code FAILED} (a race with another admission)
     */
    boolean admitReplay(String source, UUID requestId, RunClaim claim, ReplayRoute route);

    /**
     * Checks the latest HMPPS attempt before a claimed queue or replay run performs external work.
     * A terminal-success leg is preserved; only the claim-guarded request completion may change.
     *
     * @param source the request source
     * @param requestId the claimed request
     * @return whether its latest HMPPS attempt is SENT or NOT_APPLICABLE
     */
    boolean hasTerminalSuccess(String source, UUID requestId);


    /**
     * Reads a request's state for the replay action (FR-004-014), without the attempt-1 columns the
     * guard's hot-path {@link #find} carries — a dedicated, lighter read for an operator-triggered
     * action rather than every delivery.
     *
     * @param source    the command source
     * @param requestId the request id
     * @return the replay candidate, or empty when no row exists
     */
    Optional<ReplayCandidate> findForReplay(String source, UUID requestId);

    /**
     * Checks one resend-leg ID and, when it is eligible, claims it — in one transaction that locks
     * the request row before the leg's rows (slice 004, FR-004-022–FR-004-025). The refusals run in
     * the fixed order {@code unknown-request} → {@code request-claimed} →
     * {@code resend-in-progress} → {@code not-eligible}. An eligible ID gets a new attempt row —
     * {@code max(attempt) + 1}, {@code attempt_origin = resend}, {@code PENDING}, its own leg claim,
     * the run's {@code resend_run_id} and the {@code operator} — committed before this method
     * returns, so before any POST. No earlier attempt row is changed, and a refused ID changes
     * nothing.
     *
     * @param source      the command source
     * @param requestId   the request id
     * @param resendRunId the resend run claiming it
     * @param operator    the operator, already sanitised to one line of at most 200 characters
     * @param legClaim    the leg claim to take on the new row
     * @return the claimed attempt with the command rebuilt from the request's columns, or the refusal
     */
    ResendClaim claimResend(String source, UUID requestId, UUID resendRunId, String operator,
                            RunClaim legClaim);

    /**
     * The result of {@link #claimResend}: the ID was claimed, or refused with a bounded code that is
     * never written to a row.
     */
    sealed interface ResendClaim permits ResendClaim.Claimed, ResendClaim.Refused {

        /**
         * The ID was claimed: its new attempt row is committed {@code PENDING}.
         *
         * @param attempt the claimed attempt row
         * @param command the command rebuilt from the request's columns, with no {@code userId}, so
         *                the results query fallback always reads as the system user (DV-17, H30)
         */
        record Claimed(ResendAttempt attempt, DistributionCommand command) implements ResendClaim {

            /** Rejects null components. */
            public Claimed {
                Objects.requireNonNull(attempt, "attempt");
                Objects.requireNonNull(command, "command");
            }
        }

        /**
         * The ID was refused; nothing changed.
         *
         * @param reason the refusal code
         */
        record Refused(ResendRefusal reason) implements ResendClaim {

            /** Rejects a null reason. */
            public Refused {
                Objects.requireNonNull(reason, "reason");
            }
        }
    }

    /**
     * A request's state as the replay action needs it (FR-004-014–FR-004-017).
     *
     * @param status                 the request status
     * @param hearingId              the resulted hearing, for the replay message's correlation id
     * @param command                the admitted body exactly as received, re-sent byte for byte
     * @param pendingResendRowLatest whether the leg's latest row is a {@code PENDING} resend row,
     *                               live or expired (FR-004-003)
     */
    record ReplayCandidate(String status, UUID hearingId, String command,
                           boolean pendingResendRowLatest) {

        /** Rejects null components. */
        public ReplayCandidate {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(hearingId, "hearingId");
            Objects.requireNonNull(command, "command");
        }
    }

    /**
     * A request's current state as the guard needs it.
     *
     * @param status      the request status
     * @param fingerprint the stored fingerprint
     * @param claimLive   whether a live (unexpired) claim is held
     * @param attemptOneStatus the recorded HMPPS attempt-1 status, if present
     * @param attemptOneReason the bounded HMPPS attempt-1 reason, if present
     * @param attemptOneHttpStatus the HMPPS attempt-1 HTTP status, if present
     * @param exhaustedMessageId the stored {@code exhausted_message_id}, if any (FR-004-001)
     * @param pendingResendRowLatest whether the leg's latest row is a {@code PENDING} resend row,
     *                               live or expired (FR-004-003)
     */
    record RequestRecord(String status, String fingerprint, boolean claimLive,
                         Optional<String> attemptOneStatus, Optional<String> attemptOneReason,
                         OptionalInt attemptOneHttpStatus, Optional<String> exhaustedMessageId,
                         boolean pendingResendRowLatest) {

        public RequestRecord(final String status, final String fingerprint,
                             final boolean claimLive) {
            this(status, fingerprint, claimLive, Optional.empty(), Optional.empty(),
                    OptionalInt.empty(), Optional.empty(), false);
        }

        public RequestRecord(final String status, final String fingerprint, final boolean claimLive,
                             final Optional<String> attemptOneStatus,
                             final Optional<String> attemptOneReason,
                             final OptionalInt attemptOneHttpStatus) {
            this(status, fingerprint, claimLive, attemptOneStatus, attemptOneReason,
                    attemptOneHttpStatus, Optional.empty(), false);
        }

        /** Rejects null components. */
        public RequestRecord {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(fingerprint, "fingerprint");
            Objects.requireNonNull(attemptOneStatus, "attemptOneStatus");
            Objects.requireNonNull(attemptOneReason, "attemptOneReason");
            Objects.requireNonNull(exhaustedMessageId, "exhaustedMessageId");
        }
    }
}
