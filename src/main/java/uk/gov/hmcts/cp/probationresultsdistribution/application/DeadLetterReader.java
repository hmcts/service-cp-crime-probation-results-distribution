package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The {@code dlq-completed} listing's broker port (design A3-33; slice 004, FR-004-043). The adapter
 * <em>peeks</em> the dead-letter queue of {@code probation.requests} — a receiver operation on the
 * {@code DEAD_LETTER_QUEUE} sub-queue that locks and removes nothing — a page at a time by sequence
 * number. It has no remove operation: the listing removes nothing. The core never imports a broker
 * client type, and an entry carries no body, no application property and no description.
 */
public interface DeadLetterReader {

    /**
     * Peeks up to {@code maxEntries} dead-letter entries from {@code fromSequenceNumber} on.
     *
     * @param fromSequenceNumber the first sequence number to peek
     * @param maxEntries         the most entries to answer
     * @return the entries in sequence order; empty once the queue is exhausted
     * @throws DeadLetterReadException when the broker could not be reached
     */
    List<DeadLetterEntry> peek(long fromSequenceNumber, int maxEntries);

    /**
     * One dead-letter entry as the listing needs it.
     *
     * @param sequenceNumber   the broker sequence number
     * @param messageId        the entry's {@code messageId}, when it has one
     * @param requestId        the {@code requestId} its body names, when the body is a valid command
     * @param deadLetterReason the dead-letter reason, when one was recorded
     * @param enqueuedAt       when the entry was enqueued, when known
     */
    record DeadLetterEntry(long sequenceNumber, Optional<String> messageId,
                           Optional<UUID> requestId, Optional<String> deadLetterReason,
                           Optional<Instant> enqueuedAt) {

        /** Rejects null components; absence is an empty optional. */
        public DeadLetterEntry {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadLetterReason, "deadLetterReason");
            Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        }
    }

    /** Thrown when the broker cannot be reached to peek the dead-letter queue. */
    class DeadLetterReadException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param cause the underlying broker failure
         */
        public DeadLetterReadException(final Throwable cause) {
            super("the broker could not peek the dead-letter queue", cause);
        }
    }
}
