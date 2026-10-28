package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.List;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;

/**
 * The read-only request listing (design §4.8 action 1; slice 004, FR-004-043, FR-004-020,
 * FR-004-034; research.md Decision 5).
 */
public final class RequestListingQuery {

    /** The fixed page size (research.md Decision 5): no property sets it. */
    public static final int PAGE_SIZE = 500;

    private final RequestListingRepository repository;

    /**
     * Creates the query.
     *
     * @param repository the listing store reads
     */
    public RequestListingQuery(final RequestListingRepository repository) {
        this.repository = repository;
    }

    /**
     * Lists requests by one of the two forms.
     *
     * @param request the arguments as supplied
     * @return the bounded outcome
     */
    public RequestListing list(final RequestListingRequest request) {
        return new RequestListing.Listed(List.of(), repository == null);
    }
}
