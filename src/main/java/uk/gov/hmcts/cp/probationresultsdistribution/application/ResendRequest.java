package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * One resend-leg run's input (design §4.8 action 3; slice 004, FR-004-018): an explicit list of
 * {@code requestId}s for the HMPPS leg, its only value, with an operator and a reason. The list is
 * taken as given — {@link LegResender} collapses duplicates, applies the cap and checks the
 * operator and reason (FR-004-019, FR-004-020) before anything is read.
 *
 * @param requestIds          the listed ids, duplicates allowed
 * @param operator            the operator, recorded on each attempt row the run creates
 * @param reason              the reason; required and bounded, never logged or written by this
 *                            capability (FR-004-041)
 * @param allowChangedPayload the {@code allow-changed-payload} override (FR-004-031)
 */
public record ResendRequest(List<UUID> requestIds, String operator, String reason,
                            boolean allowChangedPayload) {

    /** Rejects a null list; a missing operator or reason is refused by the run, not here. */
    public ResendRequest {
        Objects.requireNonNull(requestIds, "requestIds");
        requestIds = List.copyOf(requestIds);
    }
}
