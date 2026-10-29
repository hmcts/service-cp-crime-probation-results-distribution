package uk.gov.hmcts.cp.probationresultsdistribution.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegAcceptedResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ResendLegRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;

/**
 * {@code POST /operations/resend-leg} (FR-005-010 to FR-005-017).
 */
@RestController
public class ResendLegController {

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
        return ResponseEntity.accepted().body(new ResendLegAcceptedResponse(null));
    }
}
