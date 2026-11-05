package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ReplayResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegAcceptedResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendAcceptance;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Maps slice 004's application-service outcomes to this slice's answer shapes (FR-005-007,
 * FR-005-014, FR-005-015), throwing {@link OperationsRefusalException} for every refusal so {@link
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
     * research.md Decision 4): a run that was not refused whole is accepted with its
     * {@code resendRunId} only, whatever its per-ID outcomes — every ID refused, some {@code
     * not-run}, or the store failing mid-run, whose report still stands (FR-005-014). A whole-run
     * refusal is rendered from slice 004's own code, never recomputed here (FR-005-011,
     * FR-005-012, FR-005-016).
     *
     * @param result     the resend-leg capability's result
     * @param capApplied {@code distribution.resend.max-ids-per-run}, carried on a
     *                   {@code too-many-request-ids} refusal
     * @return the {@code 202} body
     * @throws OperationsRefusalException a whole-run refusal, as the bounded refusal it names
     * @throws IllegalStateException      a whole-run code resend-leg never yields (a defect)
     */
    public ResendLegAcceptedResponse toResendLegAcceptedResponse(final ResendRunResult result,
                                                                 final int capApplied) {
        if (result.wholeRunRefusal().isPresent()) {
            throw wholeRunRefusalFor(result.wholeRunRefusal().get(), capApplied);
        }
        return new ResendLegAcceptedResponse(result.resendRunId().orElseThrow(
                () -> new IllegalStateException("an accepted resend-leg run carries its run id")));
    }

    /**
     * Maps resend-leg's accept step to the accepted-run answer (FR-005-015): an accepted run
     * answers with its {@code resendRunId} only (research.md Decision 4), before any id has run; a
     * whole-run refusal is thrown as its own code, exactly as {@link
     * #toResendLegAcceptedResponse(ResendRunResult, int)} renders it.
     *
     * @param acceptance the accept step's answer
     * @param capApplied {@code distribution.resend.max-ids-per-run}, carried on
     *                   {@code too-many-request-ids}
     * @return the accepted-run answer
     */
    public ResendLegAcceptedResponse toResendLegAcceptedResponse(final ResendAcceptance acceptance,
                                                                 final int capApplied) {
        return switch (acceptance) {
            case ResendAcceptance.Refused refused -> throw wholeRunRefusalFor(refused.refusal(),
                    capApplied);
            case ResendAcceptance.Accepted accepted ->
                new ResendLegAcceptedResponse(accepted.resendRunId());
        };
    }

    private static OperationsRefusalException wholeRunRefusalFor(final WholeRunRefusal refusal,
                                                                  final int capApplied) {
        return switch (refusal) {
            case MISSING_ARGUMENT ->
                OperationsRefusalException.forReason(OperationsRefusalReason.MISSING_ARGUMENT);
            case ARGUMENT_TOO_LONG ->
                OperationsRefusalException.forReason(OperationsRefusalReason.ARGUMENT_TOO_LONG);
            case UNREADABLE_ARGUMENT ->
                OperationsRefusalException.forReason(OperationsRefusalReason.UNREADABLE_ARGUMENT);
            case TOO_MANY_REQUEST_IDS -> OperationsRefusalException.tooManyRequestIds(capApplied);
            case INTAKE_PAUSED -> OperationsRefusalException.forReason(OperationsRefusalReason.INTAKE_PAUSED);
            case STORE_UNAVAILABLE ->
                OperationsRefusalException.forReason(OperationsRefusalReason.STORE_UNAVAILABLE);
            case INTAKE_NOT_PAUSED, BROKER_UNAVAILABLE -> throw new IllegalStateException(
                    "resend-leg never yields " + refusal.value() + " (slice 004, FR-004-019-FR-004-021)");
        };
    }
}
