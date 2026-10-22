package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
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
                GuardDecision.DeadLetter {

    /**
     * The delivery was admitted to run under a fresh claim (a first insert or an expired-lease
     * reclaim).
     *
     * @param claim the claim the run's writes are fenced by
     */
    record Admitted(RunClaim claim) implements GuardDecision {

        /** Rejects a null claim. */
        public Admitted {
            Objects.requireNonNull(claim, "claim");
        }
    }

    /** The request is already {@code COMPLETED}: complete the message with no run and no send. */
    record DuplicateComplete() implements GuardDecision {
    }

    /** A live claim or a lost insert race: hand the delivery back with no delay and no error line. */
    record Abandon() implements GuardDecision {
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
