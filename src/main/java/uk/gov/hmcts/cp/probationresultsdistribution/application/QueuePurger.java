package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;

/**
 * Purge-queue's broker port (design §4.8 action 6; slice 004, FR-004-053). The adapter removes
 * every message on {@code probation.requests} enqueued before the given cut-off instant via the
 * Service Bus admin/management client, leaving the dead-letter queue untouched. The core never
 * imports a broker client type.
 */
public interface QueuePurger {

    /**
     * Removes every message enqueued strictly before {@code cutoff}.
     *
     * @param cutoff the run's start instant; a message enqueued at or after it is left for the
     *               reconciliation that follows (FR-004-053)
     * @return the result, naming how many messages were removed
     * @throws QueuePurgeException when the broker could not be reached
     */
    PurgeResult purgeBefore(Instant cutoff);

    /**
     * The result of one purge run.
     *
     * @param removed the number of messages removed
     */
    record PurgeResult(long removed) {
    }

    /** Thrown when the broker cannot be reached to purge the queue. */
    class QueuePurgeException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param cause the underlying broker failure
         */
        public QueuePurgeException(final Throwable cause) {
            super("the broker could not purge the queue", cause);
        }
    }
}
