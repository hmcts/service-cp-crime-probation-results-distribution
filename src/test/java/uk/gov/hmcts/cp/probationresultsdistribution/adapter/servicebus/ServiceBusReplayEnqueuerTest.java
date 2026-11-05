package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.CommandEnqueueException;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.OutgoingCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayEnqueuer.ReplayEnqueueException;

/**
 * Pins the production replay enqueuer (T078; design §4.8 action 2, H9; FR-004-015, FR-004-017):
 * one command through the shared command enqueuer, carrying the stored command byte for byte, the
 * fresh {@code messageId} given, the hearing id as {@code correlationId}, and exactly one broker
 * application property, {@code origin = REPLAY}; a broker failure is {@link ReplayEnqueueException}.
 */
@DisplayName("the Service Bus replay enqueuer")
class ServiceBusReplayEnqueuerTest {

    private static final String MESSAGE_ID = "REPLAY:5f0e2c1a-7b3d-4e8f-9a6b-1c2d3e4f5a6b";
    private static final String HEARING_ID = "0aa5bf35-1e51-45e5-9e42-64bd57e15c11";
    // Spacing, key order and a non-ASCII character a re-serialisation would not keep.
    private static final String STORED_COMMAND = "{ \"source\":\"RESULTS\",\n  \"requestId\" : "
            + "\"9c1f2e3d-4b5a-6c7d-8e9f-0a1b2c3d4e5f\", \"note\":\"café\" }";

    @Test
    @DisplayName("sends the stored command byte for byte under the given identity, marked only "
            + "origin=REPLAY")
    void enqueue_should_send_the_stored_command_marked_replay() {
        final List<OutgoingCommand> sent = new ArrayList<>();

        new ServiceBusReplayEnqueuer(sent::add).enqueue(MESSAGE_ID, HEARING_ID, STORED_COMMAND);

        assertThat(sent).hasSize(1);
        final OutgoingCommand command = sent.getFirst();
        assertThat(command.messageId()).isEqualTo(MESSAGE_ID);
        assertThat(command.correlationId()).isEqualTo(HEARING_ID);
        assertThat(command.body().getBytes(StandardCharsets.UTF_8))
                .isEqualTo(STORED_COMMAND.getBytes(StandardCharsets.UTF_8));
        assertThat(command.applicationProperties()).containsExactlyEntriesOf(
                Map.of("origin", "REPLAY"));
    }

    @Test
    @DisplayName("a CommandEnqueueException is ReplayEnqueueException, its cause kept")
    void enqueue_brokerUnavailable_should_throw_replayEnqueueException() {
        final CommandEnqueueException brokerFailure = new CommandEnqueueException(
                new IllegalStateException("the sender is closed"));
        final CommandEnqueuer failing = command -> {
            throw brokerFailure;
        };

        assertThatThrownBy(() -> new ServiceBusReplayEnqueuer(failing)
                .enqueue(MESSAGE_ID, HEARING_ID, STORED_COMMAND))
                .isInstanceOf(ReplayEnqueueException.class)
                .hasCause(brokerFailure);
    }
}
