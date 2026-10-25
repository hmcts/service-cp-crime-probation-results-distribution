package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jms.JmsException;
import org.springframework.jms.core.JmsTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsFailure;

/**
 * Pins FR-005-025 to FR-005-027 and FR-005-043: a request-event publish failure refuses the call
 * before anything downstream runs; a response-event publish failure never changes the already-
 * decided outcome, but is logged and counted; the audit transport deliberately off is not a
 * failure (FR-005-028).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the operations audit publisher")
class OperationsAuditPublisherTest {

    @Mock
    private JmsTemplate jmsTemplate;
    @Mock
    private MetricRegistry metrics;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private OperationsAuditPublisher publisherWithTransport() {
        return new OperationsAuditPublisher(Optional.of(jmsTemplate), objectMapper, metrics);
    }

    private OperationsAuditPublisher publisherWithoutTransport() {
        return new OperationsAuditPublisher(Optional.empty(), objectMapper, metrics);
    }

    @Nested
    @DisplayName("a request event")
    class RequestEvent {

        @Test
        @DisplayName("is published when the transport is up")
        void is_published_when_the_transport_is_up() {
            publisherWithTransport().publishRequestEvent(OperationsAction.REPLAY, Map.of("x", "y"));

            verify(jmsTemplate).convertAndSend(eq("jms.topic.auditing.event"), any(String.class));
        }

        @Test
        @DisplayName("a publish failure refuses the call before anything downstream runs")
        void a_publish_failure_refuses_the_call() {
            doThrow(mock(JmsException.class)).when(jmsTemplate)
                    .convertAndSend(eq("jms.topic.auditing.event"), any(String.class));

            assertThatThrownBy(() -> publisherWithTransport()
                    .publishRequestEvent(OperationsAction.REPLAY, Map.of("x", "y")))
                    .isInstanceOf(OperationsRefusalException.class)
                    .satisfies(ex -> assertThat(((OperationsRefusalException) ex).reason())
                            .isEqualTo(OperationsRefusalReason.AUDIT_UNAVAILABLE));
        }

        @Test
        @DisplayName("the transport deliberately off is a no-op, not a failure")
        void the_transport_deliberately_off_is_a_no_op() {
            publisherWithoutTransport().publishRequestEvent(OperationsAction.REPLAY, Map.of("x", "y"));

            verifyNoInteractions(jmsTemplate, metrics);
        }
    }

    @Nested
    @DisplayName("a response event")
    class ResponseEvent {

        @Test
        @DisplayName("is published when the transport is up")
        void is_published_when_the_transport_is_up() {
            publisherWithTransport().publishResponseEvent(OperationsAction.RESEND_LEG, Map.of("x", "y"));

            verify(jmsTemplate).convertAndSend(eq("jms.topic.auditing.event"), any(String.class));
        }

        @Test
        @DisplayName("a publish failure is logged and counted, never thrown")
        void a_publish_failure_is_logged_and_counted_never_thrown() {
            doThrow(mock(JmsException.class)).when(jmsTemplate)
                    .convertAndSend(eq("jms.topic.auditing.event"), any(String.class));

            publisherWithTransport().publishResponseEvent(OperationsAction.RESEND_LEG, Map.of("x", "y"));

            verify(metrics).operationsFailure(OperationsAction.RESEND_LEG,
                    OperationsFailure.AUDIT_RESPONSE_UNPUBLISHED);
        }

        @Test
        @DisplayName("the transport deliberately off is a no-op, not a failure")
        void the_transport_deliberately_off_is_a_no_op() {
            publisherWithoutTransport().publishResponseEvent(OperationsAction.RESEND_LEG, Map.of("x", "y"));

            verifyNoInteractions(jmsTemplate, metrics);
        }
    }
}
