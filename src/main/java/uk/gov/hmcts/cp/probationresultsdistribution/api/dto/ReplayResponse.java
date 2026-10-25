package uk.gov.hmcts.cp.probationresultsdistribution.api.dto;

import java.util.UUID;

/**
 * The replay endpoint's success body (design §4.8 action 2; slice 005, FR-005-007). {@code
 * messageId} is present only when {@code outcome} is {@code "enqueued"}.
 *
 * @param requestId the service's own canonical parse of the replayed request's id (FR-005-031)
 * @param outcome   {@code "enqueued"} or {@code "no-op"} (FR-005-007)
 * @param messageId the fresh message identity the command went under, or {@code null} for a no-op
 */
public record ReplayResponse(UUID requestId, String outcome, String messageId) {
}
