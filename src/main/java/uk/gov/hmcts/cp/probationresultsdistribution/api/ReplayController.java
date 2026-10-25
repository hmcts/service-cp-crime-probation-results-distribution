package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.util.Map;
import java.util.UUID;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import uk.gov.hmcts.cp.probationresultsdistribution.api.dto.ReplayResponse;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;

/**
 * {@code POST /operations/requests/{requestId}/replay} (FR-005-006 to FR-005-009). A driving
 * adapter only: parses, has the call audited, calls {@link ReplayAction}, renders its result — no
 * eligibility rule, no lock, no store write of its own (FR-005-003). Available at any time,
 * including while intake is paused: this endpoint adds no refusal of its own (FR-005-008).
 *
 * <p>Excluded from {@code Application}'s component scan and registered instead by an explicit,
 * {@code @ConditionalOnBean(ReplayAction.class)}-gated {@code @Bean} method in {@code
 * config.OperationsConfiguration}: a {@link ReplayAction} bean exists only once both its own
 * dependencies do — {@code ProcessedRequestRepository} (today still wired only inside the
 * intake-gated {@code ServiceBusConsumerConfig}, a pre-existing coupling this endpoint's needs
 * expose but does not fix here) and a production {@code ReplayEnqueuer} (004's gap, not yet built).
 * Until both land, this controller is absent from the context rather than failing it.
 */
@RestController
public class ReplayController {

    private final ReplayAction replayAction;
    private final OperationsRequestMapper requestMapper;
    private final OperationsResponseMapper responseMapper;
    private final OperationsAuditPublisher auditPublisher;

    /**
     * Creates the controller.
     *
     * @param replayAction   slice 004's replay action
     * @param requestMapper  parses and bound-checks the request (FR-005-003)
     * @param responseMapper maps the action's outcome to this slice's answer shapes
     * @param auditPublisher publishes the before/after audit event pair (FR-005-025)
     */
    public ReplayController(final ReplayAction replayAction, final OperationsRequestMapper requestMapper,
                            final OperationsResponseMapper responseMapper,
                            final OperationsAuditPublisher auditPublisher) {
        this.replayAction = replayAction;
        this.requestMapper = requestMapper;
        this.responseMapper = responseMapper;
        this.auditPublisher = auditPublisher;
    }

    /**
     * Replays one request.
     *
     * @param requestId the raw path variable, parsed as a {@code UUID} (FR-005-006)
     * @param body      {@code null} or an empty body only; any field is refused (FR-005-009,
     *                  FR-005-033)
     * @return the {@code 200} body for {@code enqueued}/{@code no-op}
     */
    @PostMapping("/operations/requests/{requestId}/replay")
    public ReplayResponse replay(@PathVariable final String requestId,
                                 @RequestBody(required = false) final Map<String, Object> body) {
        requestMapper.validateReplayBody(body);
        final UUID parsedRequestId = requestMapper.parseRequestId(requestId);
        auditPublisher.publishRequestEvent(OperationsAction.REPLAY,
                Map.of("requestId", parsedRequestId.toString()));
        final ReplayOutcome outcome = replayAction.replay(parsedRequestId);
        return renderAudited(parsedRequestId, outcome);
    }

    private ReplayResponse renderAudited(final UUID requestId, final ReplayOutcome outcome) {
        final ReplayResponse response;
        try {
            response = responseMapper.toReplayResponse(requestId, outcome);
        } catch (final OperationsRefusalException refusal) {
            auditPublisher.publishResponseEvent(OperationsAction.REPLAY,
                    Map.of("outcome", refusal.reason().wireValue()));
            throw refusal;
        }
        auditPublisher.publishResponseEvent(OperationsAction.REPLAY, Map.of("outcome", response.outcome()));
        return response;
    }
}
