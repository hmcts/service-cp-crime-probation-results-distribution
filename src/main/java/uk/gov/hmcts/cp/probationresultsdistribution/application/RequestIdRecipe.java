package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.UUID;

/**
 * The producer's {@code requestId} recipe (slice 004, FR-004-055; DIGEST §14).
 */
public final class RequestIdRecipe {

    private RequestIdRecipe() {
    }

    /**
     * The {@code requestId} for one share.
     *
     * @param hearingId  the hearing id, as a string
     * @param hearingDay the hearing day, {@code YYYY-MM-DD}
     * @param sharedTime the payload's raw {@code sharedTime} string
     * @return the request id
     */
    public static UUID requestId(final String hearingId, final String hearingDay,
                                 final String sharedTime) {
        return new UUID(0L, 0L);
    }
}
