package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.models.SubQueue;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader.DeadLetterEntry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * Pins the {@code dlq-completed} listing's broker adapter against the Service Bus emulator (slice
 * 004, US6; FR-004-043): a peek of the dead-letter sub-queue, paged by sequence number, that names
 * each entry's {@code requestId} from its body and its dead-letter reason, and removes nothing.
 */
@DisplayName("the dead-letter queue peek")
class DeadLetterQueuePeekerIT extends DeadLetterTestSupport {

    @Test
    @DisplayName("names each entry's requestId and reason, and a second peek still sees them")
    void peek_should_name_entries_and_remove_nothing() {
        final DistributionCommand exhausted = newCommand();
        final DistributionCommand legExhausted = newCommand();
        final String notACommand = "not-a-command-" + exhausted.requestId();
        deadLetterDirectly(commandBody(exhausted), originalId(exhausted), NO_ORIGIN,
                Optional.of("exhausted"));
        deadLetterDirectly(commandBody(legExhausted), originalId(legExhausted), "SUPPORT",
                Optional.of("leg-exhausted"));
        deadLetterDirectly("{\"source\":\"RESULTS\"}", notACommand, NO_ORIGIN, Optional.empty());

        try (ServiceBusReceiverClient dlq = deadLetterReceiver()) {
            final DeadLetterQueuePeeker peeker = new DeadLetterQueuePeeker(dlq);

            final List<DeadLetterEntry> first = peekAll(peeker);
            final List<DeadLetterEntry> second = peekAll(peeker);

            final DeadLetterEntry exhaustedEntry = find(first, originalId(exhausted));
            assertThat(exhaustedEntry.requestId()).contains(exhausted.requestId());
            assertThat(exhaustedEntry.deadLetterReason()).contains("exhausted");
            assertThat(exhaustedEntry.enqueuedAt()).isPresent();
            final DeadLetterEntry legEntry = find(first, originalId(legExhausted));
            assertThat(legEntry.requestId()).contains(legExhausted.requestId());
            assertThat(legEntry.deadLetterReason()).contains("leg-exhausted");
            assertThat(legEntry.sequenceNumber()).isGreaterThan(exhaustedEntry.sequenceNumber());
            final DeadLetterEntry unreadable = find(first, notACommand);
            assertThat(unreadable.requestId()).isEmpty();
            assertThat(unreadable.deadLetterReason()).isEmpty();
            assertThat(second).extracting(DeadLetterEntry::messageId)
                    .contains(Optional.of(originalId(exhausted)),
                            Optional.of(originalId(legExhausted)), Optional.of(notACommand));
        }
        assertThat(peekDeadLetterNow(originalId(exhausted))).isPresent();
    }

    @Test
    @DisplayName("answers a bounded page from a sequence number, in sequence order")
    void peek_should_answer_a_bounded_page() {
        final DistributionCommand command = newCommand();
        deadLetterDirectly(commandBody(command), originalId(command), NO_ORIGIN,
                Optional.of("exhausted"));

        try (ServiceBusReceiverClient dlq = deadLetterReceiver()) {
            final DeadLetterQueuePeeker peeker = new DeadLetterQueuePeeker(dlq);
            final List<DeadLetterEntry> page = peeker.peek(0L, 2);

            assertThat(page).hasSizeBetween(1, 2);
            assertThat(page).extracting(DeadLetterEntry::sequenceNumber).isSorted();
            final long last = page.get(page.size() - 1).sequenceNumber();
            assertThat(peeker.peek(last + 1, 2)).allSatisfy(
                    entry -> assertThat(entry.sequenceNumber()).isGreaterThan(last));
        }
    }

    private static List<DeadLetterEntry> peekAll(final DeadLetterQueuePeeker peeker) {
        final List<DeadLetterEntry> all = new ArrayList<>();
        long from = 0L;
        List<DeadLetterEntry> page = peeker.peek(from, 100);
        while (!page.isEmpty()) {
            all.addAll(page);
            from = page.get(page.size() - 1).sequenceNumber() + 1;
            page = peeker.peek(from, 100);
        }
        return all;
    }

    private static DeadLetterEntry find(final List<DeadLetterEntry> entries, final String messageId) {
        return entries.stream().filter(entry -> entry.messageId().filter(messageId::equals).isPresent())
                .findFirst().orElseThrow(() -> new AssertionError("no dead-letter entry " + messageId));
    }

    private static ServiceBusReceiverClient deadLetterReceiver() {
        return new ServiceBusClientBuilder().connectionString(serviceBusConnectionString())
                .receiver().queueName(QUEUE).subQueue(SubQueue.DEAD_LETTER_QUEUE).buildClient();
    }
}
