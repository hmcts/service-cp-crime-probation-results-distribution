package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import java.util.Optional;

/**
 * The request listing's arguments exactly as supplied, unchecked (design §4.8 action 1; slice 004,
 * FR-004-043; {@code contracts/cli-runners.md} §7). {@link RequestListingQuery} checks them whole
 * before any store read (FR-004-020). Exactly one form is used per listing: the hearing form
 * ({@code hearingId} and {@code hearingDay}) or the latest-attempt form ({@code status}, with an
 * optional {@code reason} and {@code updated_at} range).
 *
 * @param hearingId     the hearing form's hearing id, a UUID
 * @param hearingDay    the hearing form's hearing day, {@code YYYY-MM-DD}
 * @param status        the latest-attempt form's leg status
 * @param reason        the latest-attempt form's leg reason
 * @param updatedAfter  the latest-attempt form's inclusive lower bound, an ISO-8601 instant
 * @param updatedBefore the latest-attempt form's exclusive upper bound, an ISO-8601 instant
 */
public record RequestListingRequest(Optional<String> hearingId, Optional<String> hearingDay,
                                    Optional<String> status, Optional<String> reason,
                                    Optional<String> updatedAfter,
                                    Optional<String> updatedBefore) {

    /** Rejects null components; an argument not supplied is an empty optional. */
    public RequestListingRequest {
        Objects.requireNonNull(hearingId, "hearingId");
        Objects.requireNonNull(hearingDay, "hearingDay");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(updatedAfter, "updatedAfter");
        Objects.requireNonNull(updatedBefore, "updatedBefore");
    }
}
