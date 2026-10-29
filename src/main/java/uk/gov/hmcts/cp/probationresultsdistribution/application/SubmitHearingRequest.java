package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import java.util.Optional;

/**
 * Submit-hearing's arguments exactly as supplied, unchecked (design §4.8 action 5; slice 004,
 * FR-004-054; {@code contracts/cli-runners.md} §5). {@link SubmitHearingAction} checks them whole
 * before the cache is read (FR-004-020).
 *
 * @param hearingId  the hearing id, a UUID
 * @param hearingDay the hearing day, {@code YYYY-MM-DD}
 * @param operator   the operator, at most 200 characters
 * @param reason     the reason, at most 200 characters
 */
public record SubmitHearingRequest(Optional<String> hearingId, Optional<String> hearingDay,
                                   Optional<String> operator, Optional<String> reason) {

    /** Rejects null components; an argument not supplied is an empty optional. */
    public SubmitHearingRequest {
        Objects.requireNonNull(hearingId, "hearingId");
        Objects.requireNonNull(hearingDay, "hearingDay");
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(reason, "reason");
    }
}
