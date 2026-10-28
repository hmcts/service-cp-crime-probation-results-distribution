package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;

import com.azure.core.exception.AzureException;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer;

/**
 * The Service Bus {@link CommandEnqueuer} (slice 004, FR-004-056, FR-004-058): sends one message to
 * {@code probation.requests} through a sender client — the broker's sender role, which replay and
 * submit-hearing share — with the {@code messageId}, {@code correlationId}, body and application
 * properties given and {@code contentType = application/json}. A broker that cannot take the
 * message, or a sender that can no longer send, is {@link CommandEnqueueException}.
 */
public final class ServiceBusCommandEnqueuer implements CommandEnqueuer {

    private static final String JSON = "application/json";

    private final ServiceBusSenderClient sender;

    /**
     * Creates the adapter.
     *
     * @param sender a sender on {@code probation.requests}
     */
    public ServiceBusCommandEnqueuer(final ServiceBusSenderClient sender) {
        this.sender = sender;
    }

    @Override
    public void enqueue(final OutgoingCommand command) {
        final ServiceBusMessage message = new ServiceBusMessage(command.body())
                .setMessageId(command.messageId())
                .setCorrelationId(command.correlationId())
                .setContentType(JSON);
        message.getApplicationProperties().putAll(command.applicationProperties());
        try {
            sender.sendMessage(message);
        } catch (final AzureException | IllegalStateException brokerFailure) {
            // A disposed sender raises IllegalStateException. A broker failure is an AzureException:
            // the sync sender reports it as ServiceBusException, a link failure as AmqpException.
            throw new CommandEnqueueException(brokerFailure);
        }
    }
}
