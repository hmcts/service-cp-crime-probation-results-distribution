package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ReplayResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegAcceptedResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * Maps slice 004's application-service outcomes to this slice's answer shapes (FR-005-007,
 * FR-005-014), throwing {@link OperationsRefusalException} for every refusal so {@link
 * OperationsProblemDetailHandler} renders it.
 */
public final class OperationsResponseMapper {

    private static final String ENQUEUED = "enqueued";
    private static final String NO_OP = "no-op";

    /**
     * Maps one {@link ReplayOutcome} to the replay endpoint's answer.
     *
     * @param requestId the service's own canonical parse of the replayed request's id
     * @param outcome   the replay action's result
     * @return the {@code 200} body, for {@code Enqueued} and {@code NoOp} only
     * @throws OperationsRefusalException every other case, as the bounded refusal it names
     */
    public ReplayResponse toReplayResponse(final UUID requestId, final ReplayOutcome outcome) {
        return switch (outcome) {
            case ReplayOutcome.Enqueued enqueued ->
                new ReplayResponse(requestId, ENQUEUED, enqueued.messageId());
            case ReplayOutcome.NoOp ignored -> new ReplayResponse(requestId, NO_OP, null);
            case ReplayOutcome.Refused refused -> throw refusalFor(refused.reason());
            case ReplayOutcome.StoreUnavailable ignored ->
                throw OperationsRefusalException.forReason(OperationsRefusalReason.STORE_UNAVAILABLE);
            case ReplayOutcome.BrokerUnavailable ignored ->
                throw OperationsRefusalException.forReason(OperationsRefusalReason.BROKER_UNAVAILABLE);
        };
    }

    private static OperationsRefusalException refusalFor(final ResendRefusal reason) {
        final OperationsRefusalReason mapped = switch (reason) {
            case UNKNOWN_REQUEST -> OperationsRefusalReason.UNKNOWN_REQUEST;
            case NOT_ELIGIBLE -> OperationsRefusalReason.NOT_ELIGIBLE;
            case RESEND_IN_PROGRESS -> OperationsRefusalReason.RESEND_IN_PROGRESS;
            case REQUEST_CLAIMED -> throw new IllegalStateException(
                    "replay never yields request-claimed (slice 004, Assumption 2)");
        };
        return OperationsRefusalException.forReason(mapped);
    }

    /**
     * Maps one resend-leg run's result to the endpoint's accepted-run answer (FR-005-015;
     * research.md Decision 4).
     *
     * @param result     the resend-leg capability's result
     * @param capApplied {@code distribution.resend.max-ids-per-run}, carried on a
     *                   {@code too-many-request-ids} refusal
     * @return the {@code 202} body
     */
    public ResendLegAcceptedResponse toResendLegAcceptedResponse(final ResendRunResult result,
                                                                 final int capApplied) {
        return new ResendLegAcceptedResponse(null);
    }
}
