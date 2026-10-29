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
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;

/**
 * {@code POST /operations/resend-leg} (FR-005-010 to FR-005-017). A driving adapter only: parses,
 * has the call audited, calls slice 004's {@link LegResender} with the list exactly as sent, and
 * renders its result — no collapse, cap, pause check, eligibility rule or store write of its own
 * (FR-005-003, FR-005-011, FR-005-013). Every whole-call refusal past this layer's own parsing is
 * 004's code, rendered by {@link OperationsResponseMapper}; per-ID outcomes are 004's report and
 * never the call's status (FR-005-014).
 *
 * <p>{@link LegResender#resend} runs every listed id before it returns, and the run's
 * {@code resendRunId} is known only on that return, so this endpoint answers {@code 202} once the
 * run has finished rather than before any id has (FR-005-015's accepted-run shape, with 004's
 * synchronous capability underneath it; recorded as an open point in {@code tasks.md}).
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
    private final OperationsRequestMapper requestMapper;
    private final OperationsResponseMapper responseMapper;
    private final OperationsAuditPublisher auditPublisher;
    private final int maxIdsPerRun;

    /**
     * Creates the controller.
     *
     * @param legResender    slice 004's resend-leg capability
     * @param requestMapper  parses and bound-checks the request (FR-005-003)
     * @param responseMapper maps the capability's result to this slice's answer shapes
     * @param auditPublisher publishes the before/after audit event pair (FR-005-025)
     * @param maxIdsPerRun   {@code distribution.resend.max-ids-per-run}, carried on a
     *                       {@code too-many-request-ids} refusal
     */
    public ResendLegController(final LegResender legResender, final OperationsRequestMapper requestMapper,
                               final OperationsResponseMapper responseMapper,
                               final OperationsAuditPublisher auditPublisher, final int maxIdsPerRun) {
        this.legResender = legResender;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.auditPublisher = auditPublisher;
        this.maxIdsPerRun = maxIdsPerRun;
    }

    /**
     * Runs resend-leg over an explicit list of ids.
     *
     * @param body the request as sent
     * @return the {@code 202} accepted-run answer
     */
    @PostMapping("/operations/resend-leg")
    public ResponseEntity<ResendLegAcceptedResponse> resendLeg(@RequestBody final ResendLegRequest body) {
        final ParsedResendLegCommand parsed = requestMapper.parseResendLeg(body);
        auditPublisher.publishRequestEvent(OperationsAction.RESEND_LEG, overrides(parsed));
        final ResendRunResult result = legResender.resend(requestMapper.toResendRequest(parsed));
        return ResponseEntity.accepted().body(renderAudited(parsed, result));
    }

    private ResendLegAcceptedResponse renderAudited(final ParsedResendLegCommand parsed,
                                                    final ResendRunResult result) {
        final Map<String, Object> fields = overrides(parsed);
        final ResendLegAcceptedResponse response;
        try {
            response = responseMapper.toResendLegAcceptedResponse(result, maxIdsPerRun);
        } catch (final OperationsRefusalException refusal) {
            fields.put(OUTCOME, refusal.reason().wireValue());
            auditPublisher.publishResponseEvent(OperationsAction.RESEND_LEG, fields);
            throw refusal;
        }
        fields.put(OUTCOME, ACCEPTED);
        fields.put("resendRunId", response.resendRunId().toString());
        fields.put("ids", result.entries().size());
        fields.put("notRun", result.notRun().size());
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
