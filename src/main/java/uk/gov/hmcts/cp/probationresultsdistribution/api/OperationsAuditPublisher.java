package uk.gov.hmcts.cp.probationresultsdistribution.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsFailure;

/**
 * Publishes the operations API's two bounded audit events (FR-005-025, FR-005-026) directly over
 * the same JMS infrastructure {@code cp-audit-filter-springboot} configures — the same topic its
 * own {@code AuditService} sends to, {@code jms.topic.auditing.event} (read from that class's
 * bytecode; it is fixed in its constructor, not a configurable property) — rather than through that
 * library's own publisher, which swallows every publish failure internally
 * ({@code AuditService.postMessageToArtemis} catches {@code Exception} and only logs, confirmed by
 * reading its bytecode) and so cannot give FR-005-027's "refuse before anything changes" guarantee.
 *
 * <p>The topic is reached by name, not by constructing an Artemis-specific {@code Destination}
 * directly: the library's own {@code auditJmsTemplate} bean has {@code pubSubDomain=true} (also
 * read from its bytecode), so {@link JmsTemplate}'s default {@code DynamicDestinationResolver}
 * resolves the name to the same topic the library's own filter publishes to.
 *
 * <p>The {@link JmsTemplate} is absent whenever the audit transport is deliberately off
 * ({@code cp.audit.enabled=false}, the scaffold's default): that is the edge case of FR-005-028, not
 * a failure, so both publish methods are then a silent no-op.
 */
public final class OperationsAuditPublisher {

    private static final Logger LOG = LoggerFactory.getLogger(OperationsAuditPublisher.class);
    private static final String AUDIT_TOPIC = "jms.topic.auditing.event";
    private static final String ACTION_FIELD = "action";
    private static final String CALLER_ID_FIELD = "callerId";
    private static final String CALLER_ID_HEADER = "CJSCPPUID";

    private final Optional<JmsTemplate> jmsTemplate;
    private final ObjectMapper objectMapper;
    private final MetricRegistry metrics;

    /**
     * Creates the publisher.
     *
     * @param jmsTemplate  the audit transport's {@link JmsTemplate}, empty when the transport is off
     * @param objectMapper the mapper used to serialise each event's bounded fields
     * @param metrics      the metric registry, for counting an unpublished response event
     */
    public OperationsAuditPublisher(final Optional<JmsTemplate> jmsTemplate, final ObjectMapper objectMapper,
                                    final MetricRegistry metrics) {
        this.jmsTemplate = jmsTemplate;
        this.objectMapper = objectMapper;
        this.metrics = metrics;
    }

    /**
     * Publishes the request event. A failure refuses the call before anything downstream runs
     * (FR-005-027).
     *
     * @param action the operator action
     * @param fields the event's bounded fields (FR-005-026): no request/response body, no caller
     *               text, no payload
     * @throws OperationsRefusalException {@code audit-unavailable} when the transport is up but the
     *                                     publish failed
     */
    public void publishRequestEvent(final OperationsAction action, final Map<String, Object> fields) {
        if (jmsTemplate.isEmpty()) {
            return;
        }
        try {
            jmsTemplate.get().convertAndSend(AUDIT_TOPIC, toJson(action, fields));
        } catch (final JmsException publishFailed) {
            throw OperationsRefusalException.forReason(OperationsRefusalReason.AUDIT_UNAVAILABLE);
        }
    }

    /**
     * Publishes the response event. A failure never changes the call's already-decided outcome: it
     * is logged at ERROR and counted (FR-005-043).
     *
     * @param action the operator action
     * @param fields the event's bounded fields (FR-005-026)
     */
    public void publishResponseEvent(final OperationsAction action, final Map<String, Object> fields) {
        if (jmsTemplate.isEmpty()) {
            return;
        }
        try {
            jmsTemplate.get().convertAndSend(AUDIT_TOPIC, toJson(action, fields));
        } catch (final JmsException publishFailed) {
            LOG.error("operations response audit event not published action={}", action.wireValue(),
                    publishFailed);
            metrics.operationsFailure(action, OperationsFailure.AUDIT_RESPONSE_UNPUBLISHED);
        }
    }

    /**
     * Serialises one event's bounded fields with the action and the caller named alongside them
     * (FR-005-026): the action; the authenticated caller id, from the request's own {@code
     * CJSCPPUID} header, never the body (review of PR #8) — so a forced resend can be traced to a
     * person, not just the operator free text the caller supplies; the outcome or refusal reason;
     * for replay the requestId; whether either override was requested — never a request/response
     * body, the operator or reason text, a hearing payload or a stored body.
     */
    private String toJson(final OperationsAction action, final Map<String, Object> fields) {
        final Map<String, Object> event = new LinkedHashMap<>();
        event.put(ACTION_FIELD, action.wireValue());
        currentCallerId().ifPresent(callerId -> event.put(CALLER_ID_FIELD, callerId));
        event.putAll(fields);
        try {
            return objectMapper.writeValueAsString(event);
        } catch (final JsonProcessingException notSerialisable) {
            throw new IllegalStateException("operations audit event fields not serialisable",
                    notSerialisable);
        }
    }

    /**
     * The calling request's {@code CJSCPPUID} header: the identity the {@code cp-auth-rules-filter}
     * authorisation chain already validated before this call was ever admitted. Empty off the
     * request-handling thread (a unit test with no servlet request bound) or where the authorisation
     * filter is off ({@code authz.http.enabled=false}, local and test profiles), in which case no
     * caller id is recorded rather than a blank or placeholder one.
     */
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Optional<String> currentCallerId() {
        if (!(RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs)) {
            return Optional.empty();
        }
        final HttpServletRequest request = attrs.getRequest();
        final String header = request.getHeader(CALLER_ID_HEADER);
        return header == null || header.isBlank() ? Optional.empty() : Optional.of(header);
    }
}
