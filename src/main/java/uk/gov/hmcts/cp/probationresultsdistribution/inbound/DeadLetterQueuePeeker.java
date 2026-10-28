package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import com.azure.core.amqp.exception.AmqpException;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader;

/**
 * The Service Bus {@link DeadLetterReader} (slice 004, FR-004-043; design A3-33): peeks the
 * {@code probation.requests} dead-letter sub-queue through a receiver built on
 * {@code SubQueue.DEAD_LETTER_QUEUE}. A peek is a receiver operation that takes no lock and removes
 * nothing; the admin/management client cannot peek. Each entry's {@code requestId} is read from its
 * body by the closed-contract parser — a body that is not a valid command names none — and the body
 * itself, its application properties and its dead-letter description never leave this class, which
 * is the only kind of class that sees a {@link ServiceBusReceivedMessage}.
 */
public final class DeadLetterQueuePeeker implements DeadLetterReader {

    private final ServiceBusReceiverClient deadLetterReceiver;
    private final DistributionCommandParser parser = new DistributionCommandParser();

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
        final List<ServiceBusReceivedMessage> page;
        try {
            page = deadLetterReceiver.peekMessages(maxEntries, fromSequenceNumber).stream().toList();
        } catch (final AmqpException brokerFailure) {
            throw new DeadLetterReadException(brokerFailure);
        }
        return page.stream().map(this::entry).toList();
    }

    private DeadLetterEntry entry(final ServiceBusReceivedMessage message) {
        final OffsetDateTime enqueued = message.getEnqueuedTime();
        return new DeadLetterEntry(message.getSequenceNumber(),
                Optional.ofNullable(message.getMessageId()), requestId(message),
                Optional.ofNullable(message.getDeadLetterReason()),
                Optional.ofNullable(enqueued).map(OffsetDateTime::toInstant));
    }

    private Optional<UUID> requestId(final ServiceBusReceivedMessage message) {
        Optional<UUID> requestId;
        try {
            requestId = Optional.of(parser.parse(message.getBody().toString()).requestId());
        } catch (final CommandValidationException notACommand) {
            // A body that breaks the closed contract names no request, so its entry is never listed.
            requestId = Optional.empty();
        }
        return requestId;
    }
}
