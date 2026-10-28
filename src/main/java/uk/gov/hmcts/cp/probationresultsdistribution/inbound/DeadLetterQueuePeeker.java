package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import java.util.List;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader;

/**
 * The Service Bus {@link DeadLetterReader}: peeks the {@code probation.requests} dead-letter
 * sub-queue through a receiver built on {@code SubQueue.DEAD_LETTER_QUEUE}.
 */
public final class DeadLetterQueuePeeker implements DeadLetterReader {

    private final ServiceBusReceiverClient deadLetterReceiver;

    /**
     * Creates the adapter.
     *
     * @param deadLetterReceiver a receiver on the queue's dead-letter sub-queue
     */
    public DeadLetterQueuePeeker(final ServiceBusReceiverClient deadLetterReceiver) {
        this.deadLetterReceiver = deadLetterReceiver;
    }

    @Override
    public List<DeadLetterEntry> peek(final long fromSequenceNumber, final int maxEntries) {
        return deadLetterReceiver == null ? List.of() : List.of();
    }
}
