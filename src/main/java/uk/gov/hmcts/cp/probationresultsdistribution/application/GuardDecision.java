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
     * @param replayMessageId the admitting delivery's {@code messageId} when this is a replay
     *                        admission (slice 004, FR-004-007); empty for a queue admission
     */
    record Admitted(RunClaim claim, boolean crashRerun, boolean gatewayParked,
                    Optional<String> replayMessageId) implements GuardDecision {

        public Admitted(final RunClaim claim) {
            this(claim, false, false, Optional.empty());
        }

        public Admitted(final RunClaim claim, final boolean crashRerun, final boolean gatewayParked) {
            this(claim, crashRerun, gatewayParked, Optional.empty());
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
