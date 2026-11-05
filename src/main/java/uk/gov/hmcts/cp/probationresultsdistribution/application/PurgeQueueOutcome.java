package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.util.Objects;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The bounded outcome of one call to {@link PurgeQueueAction} (design §4.8 action 6; slice 004,
 * FR-004-053). An answer and report code only — never a row value.
 */
public sealed interface PurgeQueueOutcome
        permits PurgeQueueOutcome.Done, PurgeQueueOutcome.Refused,
                PurgeQueueOutcome.BrokerUnavailable {

    /**
     * The purge ran, removing every message enqueued before {@code cutoff}.
     *
     * @param removed the number of messages removed
     * @param cutoff  the run's start instant, reported alongside the count
     */
    record Done(long removed, Instant cutoff) implements PurgeQueueOutcome {

        /** Rejects a null {@code cutoff}. */
        public Done {
            Objects.requireNonNull(cutoff, "cutoff");
        }
    }

    /**
     * The run was refused whole, with nothing removed.
     *
     * @param reason the refusal code ({@code intake-not-paused})
     */
    record Refused(WholeRunRefusal reason) implements PurgeQueueOutcome {

        /** Rejects a null reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The broker could not be reached; nothing was removed. */
    record BrokerUnavailable() implements PurgeQueueOutcome {
    }
}
