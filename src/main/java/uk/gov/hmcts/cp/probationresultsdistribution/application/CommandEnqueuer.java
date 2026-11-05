package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Map;
import java.util.Objects;

/**
 * Submit-hearing's enqueue port (design §4.8 action 5; slice 004, FR-004-056, FR-004-058). The
 * adapter sends one message to {@code probation.requests} with the {@code messageId},
 * {@code correlationId}, body and broker application properties given, and
 * {@code contentType = application/json}, through the broker's sender role. The core never imports
 * a broker client type.
 */
public interface CommandEnqueuer {

    /**
     * Enqueues one command.
     *
     * @param command the message to send
     * @throws CommandEnqueueException when the broker could not take the message
     */
    void enqueue(OutgoingCommand command);

    /**
     * One message to enqueue.
     *
     * @param messageId             the broker {@code messageId}
     * @param correlationId         the broker {@code correlationId}
     * @param body                  the command body, sent as UTF-8 JSON
     * @param applicationProperties the broker application properties, never body fields
     */
    record OutgoingCommand(String messageId, String correlationId, String body,
                           Map<String, String> applicationProperties) {

        /** Copies the properties; rejects null components. */
        public OutgoingCommand {
            Objects.requireNonNull(messageId, "messageId");
            Objects.requireNonNull(correlationId, "correlationId");
            Objects.requireNonNull(body, "body");
            applicationProperties = Map.copyOf(applicationProperties);
        }
    }

    /** Thrown when the broker cannot take the command ({@code broker-unavailable}). */
    class CommandEnqueueException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param cause the underlying broker failure
         */
        public CommandEnqueueException(final Throwable cause) {
            super("the broker could not take the command", cause);
        }
    }
}
