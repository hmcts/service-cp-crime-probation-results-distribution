package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;

import com.azure.messaging.servicebus.ServiceBusSenderClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer;

/** The Service Bus {@link CommandEnqueuer}. */
public final class ServiceBusCommandEnqueuer implements CommandEnqueuer {

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
        if (sender == null) {
            throw new CommandEnqueueException(new IllegalStateException("no sender"));
        }
    }
}
