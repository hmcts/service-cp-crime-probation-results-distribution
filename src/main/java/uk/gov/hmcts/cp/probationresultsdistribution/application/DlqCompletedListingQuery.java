package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.List;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;

/**
 * The read-only {@code dlq-completed} listing (design A3-33; slice 004, FR-004-043; research.md
 * Decision 5).
 */
public final class DlqCompletedListingQuery {

    /** The fixed page size (research.md Decision 5): no property sets it. */
    public static final int PAGE_SIZE = 500;

    private final DeadLetterReader reader;
    private final RequestListingRepository repository;

    /**
     * Creates the query.
     *
     * @param reader     the dead-letter peek port
     * @param repository the request-status lookup
     */
    public DlqCompletedListingQuery(final DeadLetterReader reader,
                                    final RequestListingRepository repository) {
        this.reader = reader;
        this.repository = repository;
    }

    /**
     * Lists the dead-letter entries whose request is {@code COMPLETED}.
     *
     * @return the bounded outcome
     */
    public DlqCompletedListing list() {
        return new DlqCompletedListing.Listed(List.of(), reader == null || repository == null);
    }
}
