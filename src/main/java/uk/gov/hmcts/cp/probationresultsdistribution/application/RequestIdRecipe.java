package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.nio.charset.StandardCharsets;
import java.util.UUID;

/**
 * The producer's {@code requestId} recipe (slice 004, FR-004-055; design A2-4, A2-25; DIGEST §14):
 * the name-based (version 3) UUID of {@code hearingId|hearingDay|sharedTime} over the UTF-8 bytes
 * of the raw strings — {@code sharedTime} exactly as the payload carries it, never parsed and
 * re-rendered. Pinned by the vector of the results producer's own helper test, with no build
 * dependency on a results artefact; re-taken when Q20 lands.
 */
public final class RequestIdRecipe {

    private static final String SEPARATOR = "|";

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
        return UUID.nameUUIDFromBytes((hearingId + SEPARATOR + hearingDay + SEPARATOR + sharedTime)
                .getBytes(StandardCharsets.UTF_8));
    }
}
