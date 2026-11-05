package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayEnqueuer.ReplayEnqueueException;

/**
 * Pins the production replay enqueuer against the Service Bus emulator (T078; design §4.8 action
 * 2, H9; FR-004-015; {@code contracts/broker-properties.md}): the replayed message arrives on
 * {@code probation.requests} under the fresh {@code messageId}, with the hearing id as
 * {@code correlationId}, {@code contentType = application/json}, the stored command byte for byte
 * as its body and exactly one application property, {@code origin = REPLAY}.
 */
@DisplayName("the Service Bus replay enqueuer, against the emulator")
class ServiceBusReplayEnqueuerIT extends EndToEndTestSupport {

    @Test
    @DisplayName("the replayed message carries exactly the identity, body and one property given")
    void enqueue_should_put_the_replay_on_the_queue() {
        final String messageId = "REPLAY:" + UUID.randomUUID();
        final String hearingId = UUID.randomUUID().toString();
        final String storedCommand = "{ \"source\":\"RESULTS\",\n  \"requestId\" : \""
                + UUID.randomUUID() + "\", \"note\":\"café\" }";

        try (ServiceBusSenderClient sender = sender()) {
            new ServiceBusReplayEnqueuer(new ServiceBusCommandEnqueuer(sender))
                    .enqueue(messageId, hearingId, storedCommand);
        }

        final ServiceBusReceivedMessage received = receive(messageId).orElseThrow(
                () -> new AssertionError("the replayed command was not on the queue"));
        assertThat(received.getCorrelationId()).isEqualTo(hearingId);
        assertThat(received.getContentType()).isEqualTo("application/json");
        assertThat(received.getBody().toBytes())
                .isEqualTo(storedCommand.getBytes(StandardCharsets.UTF_8));
        assertThat(received.getApplicationProperties()).containsExactlyEntriesOf(
                Map.of("origin", "REPLAY"));
    }

    @Test
    @DisplayName("a sender that can no longer send is ReplayEnqueueException")
    void enqueue_closedSender_should_throw_replayEnqueueException() {
        final ServiceBusReplayEnqueuer enqueuer;
        try (ServiceBusSenderClient sender = sender()) {
            enqueuer = new ServiceBusReplayEnqueuer(new ServiceBusCommandEnqueuer(sender));
        }

        assertThatThrownBy(() -> enqueuer.enqueue("REPLAY:" + UUID.randomUUID(),
                UUID.randomUUID().toString(), "{}"))
                .isInstanceOf(ReplayEnqueueException.class);
    }

    private static ServiceBusSenderClient sender() {
        return new ServiceBusClientBuilder().connectionString(serviceBusConnectionString())
                .sender().queueName(QUEUE).buildClient();
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
