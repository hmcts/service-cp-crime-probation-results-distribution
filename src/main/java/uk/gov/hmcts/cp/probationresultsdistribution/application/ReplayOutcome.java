package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * The bounded outcome of one call to {@link ReplayAction} (design §4.8 action 2; slice 004,
 * FR-004-017). An answer and report code only — never a row value.
 */
public sealed interface ReplayOutcome
        permits ReplayOutcome.Enqueued, ReplayOutcome.NoOp, ReplayOutcome.Refused,
                ReplayOutcome.StoreUnavailable, ReplayOutcome.BrokerUnavailable {

    /**
     * The stored command was enqueued under a fresh message identity.
     *
     * @param messageId the fresh {@code messageId} the command went under
     */
    record Enqueued(String messageId) implements ReplayOutcome {

        /** Rejects a null {@code messageId}. */
        public Enqueued {
            Objects.requireNonNull(messageId, "messageId");
        }
    }

    /** The request is already {@code COMPLETED}: nothing enqueued, nothing changed. */
    record NoOp() implements ReplayOutcome {
    }

    /**
     * The request cannot be replayed, with resend-leg's own refusal code (spec Assumption 2):
     * {@code unknown-request}, {@code not-eligible} or {@code resend-in-progress}.
     *
     * @param reason the refusal code
     */
    record Refused(ResendRefusal reason) implements ReplayOutcome {

        /** Rejects a null reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The processed log could not be reached; nothing changed. */
    record StoreUnavailable() implements ReplayOutcome {
    }

    /** The broker could not take the replay's command; nothing changed in the store. */
    record BrokerUnavailable() implements ReplayOutcome {
    }
}
