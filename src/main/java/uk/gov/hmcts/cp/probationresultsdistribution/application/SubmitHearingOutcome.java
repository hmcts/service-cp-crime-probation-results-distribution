package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The bounded outcome of one call to {@link SubmitHearingAction} (design §4.8 action 5; slice 004,
 * FR-004-054, FR-004-056). An answer and report code only — never a supplied value.
 */
public sealed interface SubmitHearingOutcome permits SubmitHearingOutcome.Enqueued,
        SubmitHearingOutcome.PayloadUnavailable, SubmitHearingOutcome.Refused,
        SubmitHearingOutcome.BrokerUnavailable {

    /**
     * One command was enqueued.
     *
     * @param requestId the recipe's {@code requestId}
     * @param runId     the run id new to this invocation
     */
    record Enqueued(UUID requestId, UUID runId) implements SubmitHearingOutcome {

        /** Rejects null components. */
        public Enqueued {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(runId, "runId");
        }
    }

    /**
     * Neither {@code EXT_} key holds a payload readable with a {@code sharedTime}; nothing was
     * enqueued.
     */
    record PayloadUnavailable() implements SubmitHearingOutcome {
    }

    /**
     * Refused whole for its input, before the cache was read; nothing was enqueued.
     *
     * @param reason the refusal code
     */
    record Refused(WholeRunRefusal reason) implements SubmitHearingOutcome {

        /** Rejects a null reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The broker could not take the command. */
    record BrokerUnavailable() implements SubmitHearingOutcome {
    }
}
