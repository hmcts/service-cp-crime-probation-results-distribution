package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegAcceptedResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendAcceptance;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;

/**
 * {@code POST /operations/resend-leg} (FR-005-010 to FR-005-017). A driving adapter only: parses,
 * has the call audited, calls slice 004's {@link LegResender} with the list exactly as sent, and
 * renders its result — no collapse, cap, pause check, eligibility rule or store write of its own
 * (FR-005-003, FR-005-011, FR-005-013). Every whole-call refusal past this layer's own parsing is
 * 004's code, rendered by {@link OperationsResponseMapper}; per-ID outcomes are 004's report and
 * never the call's status (FR-005-014).
 *
 * <p>The call answers as an accepted run (FR-005-015): {@link LegResender#accept} applies every
 * whole-run check and assigns the {@code resendRunId}, claiming nothing, and a refusal there is
 * answered at once; an accepted run is handed to {@link ResendLegExecutor}, which runs it behind
 * any run already accepted, and the call answers {@code 202} with the run id without waiting for
 * any id. The response audit event is published at acceptance and carries the run id and the
 * number of distinct ids accepted; how each id ended is in 004's run report line only.
 *
 * <p>Excluded from {@code Application}'s component scan and registered by a {@code
 * @ConditionalOnBean(LegResender.class)}-gated {@code @Bean} method in {@code
 * config.OperationsConfiguration}, for the reason {@link ReplayController} gives.
 */
@RestController
public class ResendLegController {

    private static final String OUTCOME = "outcome";
    private static final String ACCEPTED = "accepted";

    private final LegResender legResender;
    private final ResendLegExecutor executor;
    private final OperationsRequestMapper requestMapper;
    private final OperationsResponseMapper responseMapper;
    private final OperationsAuditPublisher auditPublisher;
    private final int maxIdsPerRun;

    /**
     * Creates the controller.
     *
     * @param legResender    slice 004's resend-leg capability
     * @param executor       runs each accepted run, one at a time
     * @param requestMapper  parses and bound-checks the request (FR-005-003)
     * @param responseMapper maps the capability's result to this slice's answer shapes
     * @param auditPublisher publishes the before/after audit event pair (FR-005-025)
     * @param maxIdsPerRun   {@code distribution.resend.max-ids-per-run}, carried on a
     *                       {@code too-many-request-ids} refusal
     */
    public ResendLegController(final LegResender legResender, final ResendLegExecutor executor,
                               final OperationsRequestMapper requestMapper,
                               final OperationsResponseMapper responseMapper,
                               final OperationsAuditPublisher auditPublisher, final int maxIdsPerRun) {
        this.legResender = legResender;
        this.executor = executor;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.auditPublisher = auditPublisher;
        this.maxIdsPerRun = maxIdsPerRun;
    }

    /**
     * Accepts resend-leg over an explicit list of ids and queues the run.
     *
     * @param body the request as sent
     * @return the {@code 202} accepted-run answer
     */
    @PostMapping("/operations/resend-leg")
    public ResponseEntity<ResendLegAcceptedResponse> resendLeg(@RequestBody final ResendLegRequest body) {
        final ParsedResendLegCommand parsed = requestMapper.parseResendLeg(body);
        auditPublisher.publishRequestEvent(OperationsAction.RESEND_LEG, overrides(parsed));
        return ResponseEntity.accepted().body(runAudited(parsed));
    }

    /**
     * Accepts the run, queues it, and publishes the response event whatever the outcome
     * (FR-005-025): {@code accepted} with the run id and the number of distinct ids, the refusal
     * code, or {@code unexpected} for any other failure, which is then rethrown for the
     * problem-detail handler to render {@code 500}.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Caught only to publish the response event FR-005-025 owes every outcome, then rethrown unchanged.
    private ResendLegAcceptedResponse runAudited(final ParsedResendLegCommand parsed) {
        final Map<String, Object> fields = overrides(parsed);
        final ResendAcceptance.Accepted accepted;
        final ResendLegAcceptedResponse response;
        try {
            final ResendAcceptance acceptance = legResender.accept(requestMapper.toResendRequest(parsed));
            // Throws the refusal's own code for a run refused whole, so past it the run was accepted.
            response = responseMapper.toResendLegAcceptedResponse(acceptance, maxIdsPerRun);
            accepted = (ResendAcceptance.Accepted) acceptance;
            executor.submit(accepted);
        } catch (final OperationsRefusalException refusal) {
            fields.put(OUTCOME, refusal.reason().wireValue());
            auditPublisher.publishResponseEvent(OperationsAction.RESEND_LEG, fields);
            throw refusal;
        } catch (final RuntimeException unexpected) {
            fields.put(OUTCOME, OperationsRefusalReason.UNEXPECTED.wireValue());
            auditPublisher.publishResponseEvent(OperationsAction.RESEND_LEG, fields);
            throw unexpected;
        }
        fields.put(OUTCOME, ACCEPTED);
        fields.put("resendRunId", response.resendRunId().toString());
        fields.put("ids", accepted.requestIds().size());
        auditPublisher.publishResponseEvent(OperationsAction.RESEND_LEG, fields);
        return response;
    }

    /** The bounded fields both audit events carry (FR-005-017, FR-005-026): never operator or reason. */
    private static Map<String, Object> overrides(final ParsedResendLegCommand parsed) {
        final Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("allowTerminalSuccess", parsed.allowTerminalSuccess());
        fields.put("allowChangedPayload", parsed.allowChangedPayload());
        return fields;
    }
}
