package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.CommandEnqueueException;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.OutgoingCommand;

/**
 * Pins how the Service Bus enqueuer classifies a broker failure (slice 004, FR-004-058): the sync
 * sender reports a broker failure as {@link ServiceBusException}, which is not an
 * {@link AmqpException}, and each such failure is {@link CommandEnqueueException} — the bounded
 * broker-unavailable outcome — never an unclassified exception.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the Service Bus enqueuer's broker failures")
class ServiceBusCommandEnqueuerTest {

    private static final OutgoingCommand COMMAND = new OutgoingCommand(
            "RESULTS:0aa5bf35-1e51-45e5-9e42-64bd57e15c11", "c", "{}", Map.of());

    @Mock
    private ServiceBusSenderClient sender;

    @Test
    @DisplayName("a ServiceBusException from the send is CommandEnqueueException")
    void enqueue_serviceBusException_should_throw_commandEnqueueException() {
        doThrow(new ServiceBusException(new IllegalStateException("unauthorised"),
                ServiceBusErrorSource.SEND)).when(sender).sendMessage(any(ServiceBusMessage.class));

        assertThatThrownBy(() -> new ServiceBusCommandEnqueuer(sender).enqueue(COMMAND))
                .isInstanceOf(CommandEnqueueException.class)
                .hasCauseInstanceOf(ServiceBusException.class);
    }

    @Test
    @DisplayName("an AmqpException from the send is CommandEnqueueException")
    void enqueue_amqpException_should_throw_commandEnqueueException() {
        doThrow(new AmqpException(true, AmqpErrorCondition.SERVER_BUSY_ERROR, "busy", null))
                .when(sender).sendMessage(any(ServiceBusMessage.class));

        assertThatThrownBy(() -> new ServiceBusCommandEnqueuer(sender).enqueue(COMMAND))
                .isInstanceOf(CommandEnqueueException.class)
                .hasCauseInstanceOf(AmqpException.class);
    }
}
