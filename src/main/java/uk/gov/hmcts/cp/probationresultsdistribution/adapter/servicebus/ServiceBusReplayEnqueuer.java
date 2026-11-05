package uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus;

import java.util.Map;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.OutgoingCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayEnqueuer;

/**
 * The production {@link ReplayEnqueuer} (design §4.8 action 2, A2-48; H9; slice 004, FR-004-015,
 * FR-004-017; T078). Built over submit-hearing's {@link CommandEnqueuer} — the one sender on
 * {@code probation.requests}, never a second one — it sends the stored command byte for byte under
 * the fresh {@code messageId} {@code ReplayAction} gives, with {@code correlationId} the hearing id,
 * {@code contentType = application/json} (the command enqueuer's own) and exactly one broker
 * application property, {@code origin = REPLAY}: an audit label only, never a body field. A broker
 * that cannot take the message is {@link ReplayEnqueueException} ({@code broker-unavailable}).
 */
public final class ServiceBusReplayEnqueuer implements ReplayEnqueuer {

    /** The one operator marker a replay carries, a broker application property only (H9). */
    private static final Map<String, String> REPLAY_MARKER = Map.of("origin", "REPLAY");

    private final CommandEnqueuer commands;

    /**
     * Creates the adapter.
     *
     * @param commands the command enqueuer on {@code probation.requests}
     */
    public ServiceBusReplayEnqueuer(final CommandEnqueuer commands) {
        this.commands = commands;
    }

    @Override
    public void enqueue(final String messageId, final String correlationId, final String body) {
        try {
            commands.enqueue(new OutgoingCommand(messageId, correlationId, body, REPLAY_MARKER));
        } catch (final CommandEnqueuer.CommandEnqueueException brokerFailure) {
            throw new ReplayEnqueueException(brokerFailure);
        }
    }
}
