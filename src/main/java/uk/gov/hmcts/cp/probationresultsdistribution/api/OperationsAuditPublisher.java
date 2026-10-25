package uk.gov.hmcts.cp.probationresultsdistribution.api;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
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
            jmsTemplate.get().convertAndSend(AUDIT_TOPIC, toJson(fields));
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
            jmsTemplate.get().convertAndSend(AUDIT_TOPIC, toJson(fields));
        } catch (final JmsException publishFailed) {
            LOG.error("operations response audit event not published action={}", action.wireValue(),
                    publishFailed);
            metrics.operationsFailure(action, OperationsFailure.AUDIT_RESPONSE_UNPUBLISHED);
        }
    }

    private String toJson(final Map<String, Object> fields) {
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (final JsonProcessingException notSerialisable) {
            throw new IllegalStateException("operations audit event fields not serialisable",
                    notSerialisable);
        }
    }
}
