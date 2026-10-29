package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.OutgoingCommand;

/**
 * Pins submit-hearing's enqueue adapter against the Service Bus emulator (slice 004, US8;
 * FR-004-056; {@code contracts/broker-properties.md}): one message with the given
 * {@code messageId}, {@code correlationId}, {@code contentType = application/json}, body, and the
 * operator markers as broker application properties, never body fields.
 */
@DisplayName("the Service Bus command enqueuer")
class ServiceBusCommandEnqueuerIT extends EndToEndTestSupport {

    @Test
    @DisplayName("sends one message with the identity, content type, body and properties given")
    void enqueue_should_send_one_message() {
        final UUID requestId = UUID.randomUUID();
        final String body = "{\"requestId\":\"" + requestId + "\"}";
        final OutgoingCommand command = new OutgoingCommand("RESULTS:" + requestId,
                "0aa5bf35-1e51-45e5-9e42-64bd57e15c11", body, Map.of("origin", "SUPPORT",
                "operator", "local-dev", "runId", "7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77",
                "reason", "share never admitted"));

        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).sender().queueName(QUEUE)
                .buildClient()) {
            new ServiceBusCommandEnqueuer(sender).enqueue(command);
        }

        final ServiceBusReceivedMessage received = receive("RESULTS:" + requestId).orElseThrow(
                () -> new AssertionError("the enqueued command was not on the queue"));
        assertThat(received.getCorrelationId()).isEqualTo("0aa5bf35-1e51-45e5-9e42-64bd57e15c11");
        assertThat(received.getContentType()).isEqualTo("application/json");
        assertThat(received.getBody().toString()).isEqualTo(body);
        assertThat(received.getApplicationProperties()).containsAllEntriesOf(Map.of(
                "origin", "SUPPORT", "operator", "local-dev",
                "runId", "7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77", "reason", "share never admitted"));
    }

    @Test
    @DisplayName("throws CommandEnqueueException when the broker cannot take the message")
    void enqueue_closedSender_should_throw() {
        final ServiceBusCommandEnqueuer enqueuer;
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).sender().queueName(QUEUE)
                .buildClient()) {
            enqueuer = new ServiceBusCommandEnqueuer(sender);
        }

        assertThatThrownBy(() -> enqueuer.enqueue(
                new OutgoingCommand("RESULTS:" + UUID.randomUUID(), "c", "{}", Map.of())))
                .isInstanceOf(CommandEnqueuer.CommandEnqueueException.class);
    }

    private static Optional<ServiceBusReceivedMessage> receive(final String messageId) {
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).receiver().queueName(QUEUE)
                .prefetchCount(0).buildClient()) {
            final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                final Iterator<ServiceBusReceivedMessage> messages =
                        receiver.receiveMessages(10, Duration.ofSeconds(2)).iterator();
                while (messages.hasNext()) {
                    final ServiceBusReceivedMessage message = messages.next();
                    if (messageId.equals(message.getMessageId())) {
                        receiver.complete(message);
                        return Optional.of(message);
                    }
                    receiver.abandon(message);
                }
            }
        }
        return Optional.empty();
    }
}
