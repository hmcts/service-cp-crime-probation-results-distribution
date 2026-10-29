package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.List;
import java.util.Objects;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository.ListedAttempt;

/**
 * The bounded outcome of one request listing (slice 004, FR-004-043): the rows, at most one page,
 * and whether more exist than were shown; or a whole refusal with nothing read; or a store failure.
 */
public sealed interface RequestListing
        permits RequestListing.Listed, RequestListing.Refused, RequestListing.StoreUnavailable {

    /**
     * The listed rows.
     *
     * @param rows the rows, at most one page
     * @param more whether more rows exist than were shown
     */
    record Listed(List<ListedAttempt> rows, boolean more) implements RequestListing {

        /** Copies the rows; rejects null. */
        public Listed {
            rows = List.copyOf(rows);
        }
    }

    /**
     * The listing was refused whole, before any store read, and the supplied text is not echoed.
     *
     * @param reason the refusal code
     */
    record Refused(WholeRunRefusal reason) implements RequestListing {

        /** Rejects a null reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }

    /** The store could not be read. */
    record StoreUnavailable() implements RequestListing {
    }
}
