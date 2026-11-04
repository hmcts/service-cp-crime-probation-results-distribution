package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;

/**
 * What the idempotency guard decided for one delivery (design §4.5, FR-002-007). The message listener
 * turns this into exactly one settlement: an {@link Admitted} run then settles by its leg outcome; a
 * {@link DuplicateComplete} completes with no run; an {@link Abandon} hands the delivery back; a
 * {@link DeadLetter} dead-letters with its bounded reason.
 */
public sealed interface GuardDecision
        permits GuardDecision.Admitted,
                GuardDecision.DuplicateComplete,
                GuardDecision.Abandon,
                GuardDecision.ReplayDeferred,
                GuardDecision.DeadLetter {

    /**
     * The delivery was admitted to run under a fresh claim (a first insert, an expired-lease
     * reclaim, or a replay admission).
     *
     * @param claim the claim the run's writes are fenced by
     * @param crashRerun whether this reclaimed an expired claim with a still-PENDING attempt-1 row
     * @param gatewayParked whether attempt 1 was PARKED for a gateway 5xx before this reclaim
     * @param replayMessageId the delivery's {@code messageId} whenever the run this admits must apply
     *                        the replay column of the eligibility table (slice 004, FR-004-007): the
     *                        admitting delivery of a fresh replay, and every redelivery while the
     *                        request is still mid-replay; empty for a queue admission
     * @param terminalSuccess whether the latest leg is already successful and only the request
     *                        needs claim-guarded completion, without another leg run
     * @param freshReplayAdmission whether this decision is the replay's own admission — the
     *                             {@code FAILED}-to-{@code RECEIVED} transition itself, not a later
     *                             redelivery reclaimed while still mid-replay. The "replay admitted"
     *                             metric and log line fire once, on this transition only; a
     *                             redelivery reclaimed mid-replay still carries {@code replayMessageId}
     *                             so the run applies the replay column, but is not counted again
     * @param alreadyExhausted whether a reclaimed row's latest leg is already {@code FAILED} with an
     *                         exhaustion-eligible reason ({@code payload-unavailable},
     *                         {@code gateway-transient}): a crash left the request unsettled after
     *                         that outcome was recorded, so the leg needs no further attempt — only
     *                         the request's {@code FAILED(leg-exhausted)} transition and the
     *                         dead-letter are still owed (FR-002-012, FR-002-047). Never set on a
     *                         replay admission: FR-004-007 requires a replay to re-run a leg whose
     *                         latest row is {@code FAILED} for any reason
     */
    record Admitted(RunClaim claim, boolean crashRerun, boolean gatewayParked,
                    Optional<String> replayMessageId, boolean terminalSuccess,
                    boolean freshReplayAdmission, boolean alreadyExhausted) implements GuardDecision {

        public Admitted(final RunClaim claim) {
            this(claim, false, false, Optional.empty(), false, false, false);
        }

        public Admitted(final RunClaim claim, final boolean crashRerun, final boolean gatewayParked) {
            this(claim, crashRerun, gatewayParked, Optional.empty(), false, false, false);
        }

        public Admitted(final RunClaim claim, final boolean crashRerun, final boolean gatewayParked,
                        final Optional<String> replayMessageId, final boolean terminalSuccess,
                        final boolean freshReplayAdmission) {
            this(claim, crashRerun, gatewayParked, replayMessageId, terminalSuccess,
                    freshReplayAdmission, false);
        }

        public Admitted(final RunClaim claim, final boolean crashRerun, final boolean gatewayParked,
                        final Optional<String> replayMessageId, final boolean terminalSuccess) {
            this(claim, crashRerun, gatewayParked, replayMessageId, terminalSuccess, false, false);
        }

        /** Rejects a null claim or replay-message-id optional. */
        public Admitted {
            Objects.requireNonNull(claim, "claim");
            Objects.requireNonNull(replayMessageId, "replayMessageId");
        }
    }

    /** The request is already {@code COMPLETED}: complete the message with no run and no send. */
    record DuplicateComplete() implements GuardDecision {
    }

    /** A live claim or a lost insert race: hand the delivery back with no delay and no error line. */
    record Abandon() implements GuardDecision {
    }

    /**
     * A {@code FAILED} request whose leg's latest row is a {@code PENDING} resend row, live or
     * expired: the delivery is abandoned, never admitted as a replay, while the resend is in
     * progress (slice 004, FR-004-003). The request stays {@code FAILED} and {@code replayed_at}
     * stays NULL.
     */
    record ReplayDeferred() implements GuardDecision {
    }

    /**
     * Dead-letter the delivery with a bounded reason ({@code collision} or {@code exhausted}).
     *
     * @param reason the dead-letter reason
     */
    record DeadLetter(DeadLetterReason reason) implements GuardDecision {

        /** Rejects a null reason. */
        public DeadLetter {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
