package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import com.azure.core.amqp.exception.AmqpErrorCondition;
import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader.DeadLetterReadException;

/**
 * Pins how the dead-letter peek classifies a broker failure (slice 004, FR-004-043): the sync
 * receiver reports a broker failure as {@link ServiceBusException}, which is not an
 * {@link AmqpException}, and a disposed receiver raises {@link IllegalStateException}; each is
 * {@link DeadLetterReadException} — the bounded broker-unavailable outcome.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the dead-letter peek's broker failures")
class DeadLetterQueuePeekerTest {

    @Mock
    private ServiceBusReceiverClient receiver;

    @Test
    @DisplayName("a ServiceBusException from the peek is DeadLetterReadException")
    void peek_serviceBusException_should_throw_deadLetterReadException() {
        when(receiver.peekMessages(anyInt(), anyLong())).thenThrow(new ServiceBusException(
                new IllegalStateException("unreachable"), ServiceBusErrorSource.RECEIVE));

        assertThatThrownBy(() -> new DeadLetterQueuePeeker(receiver).peek(0L, 10))
                .isInstanceOf(DeadLetterReadException.class)
                .hasCauseInstanceOf(ServiceBusException.class);
    }

    @Test
    @DisplayName("an IllegalStateException from a disposed receiver is DeadLetterReadException")
    void peek_disposedReceiver_should_throw_deadLetterReadException() {
        when(receiver.peekMessages(anyInt(), anyLong()))
                .thenThrow(new IllegalStateException("disposed"));

        assertThatThrownBy(() -> new DeadLetterQueuePeeker(receiver).peek(0L, 10))
                .isInstanceOf(DeadLetterReadException.class)
                .hasCauseInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("an AmqpException from the peek is DeadLetterReadException")
    void peek_amqpException_should_throw_deadLetterReadException() {
        when(receiver.peekMessages(anyInt(), anyLong())).thenThrow(
                new AmqpException(true, AmqpErrorCondition.SERVER_BUSY_ERROR, "busy", null));

        assertThatThrownBy(() -> new DeadLetterQueuePeeker(receiver).peek(0L, 10))
                .isInstanceOf(DeadLetterReadException.class)
                .hasCauseInstanceOf(AmqpException.class);
    }
}
