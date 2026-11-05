package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The bounded outcome of one {@code dlq-completed} listing (design A3-33; slice 004, FR-004-043):
 * the dead-letter entries whose request is {@code COMPLETED}, at most one page, and whether more
 * exist; or a store or broker failure. Nothing is removed either way.
 */
public sealed interface DlqCompletedListing permits DlqCompletedListing.Listed,
        DlqCompletedListing.StoreUnavailable, DlqCompletedListing.BrokerUnavailable {

    /**
     * The listed entries.
     *
     * @param entries the entries, at most one page
     * @param more    whether more completed entries exist than were shown
     */
    record Listed(List<Entry> entries, boolean more) implements DlqCompletedListing {

        /** Copies the entries; rejects null. */
        public Listed {
            entries = List.copyOf(entries);
        }
    }

    /** The store could not be read; nothing was removed. */
    record StoreUnavailable() implements DlqCompletedListing {
    }

    /** The broker could not be reached; nothing was removed. */
    record BrokerUnavailable() implements DlqCompletedListing {
    }

    /**
     * One completed dead-letter entry, in bounded codes and identifiers only.
     *
     * @param sequenceNumber   the broker sequence number
     * @param messageId        the {@code messageId} when it is one of this service's forms
     *                         ({@code RESULTS:<uuid>}, {@code REPLAY:<uuid>}), otherwise
     *                         {@code other} — a {@code messageId} chosen elsewhere is never echoed
     * @param requestId        the request, which is {@code COMPLETED}
     * @param deadLetterReason the dead-letter reason: one of this service's codes, {@code none}
     *                         when none was recorded, or {@code other} — never another's text
     * @param enqueuedAt       when the entry was enqueued, when known
     */
    record Entry(long sequenceNumber, String messageId, UUID requestId, String deadLetterReason,
                 Optional<Instant> enqueuedAt) {

        /** Rejects null components. */
        public Entry {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(deadLetterReason, "deadLetterReason");
            Objects.requireNonNull(enqueuedAt, "enqueuedAt");
        }
    }
}
