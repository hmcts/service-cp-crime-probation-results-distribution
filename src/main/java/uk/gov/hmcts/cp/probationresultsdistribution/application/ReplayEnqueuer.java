package uk.gov.hmcts.cp.probationresultsdistribution.application;

/**
 * The replay action's enqueue port (design §4.8 action 2, A2-48). The adapter sends the stored
 * command byte for byte under a fresh {@code messageId}, marked {@code origin = REPLAY}, with
 * {@code correlationId} the hearing id and {@code contentType = application/json} (slice 004,
 * FR-004-015). The core never imports a broker client type.
 */
public interface ReplayEnqueuer {

    /**
     * Enqueues the stored command under a fresh identity.
     *
     * @param messageId     a {@code messageId} never used before for this request (FR-004-015)
     * @param correlationId the hearing id
     * @param body          the stored command, byte for byte
     * @throws ReplayEnqueueException when the broker could not take the message
     */
    void enqueue(String messageId, String correlationId, String body);

    /** Thrown when the broker cannot take the replay's command (FR-004-017, {@code broker-unavailable}). */
    class ReplayEnqueueException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        /**
         * Creates the exception.
         *
         * @param cause the underlying broker failure
         */
        public ReplayEnqueueException(final Throwable cause) {
            super("the broker could not take the replay's command", cause);
        }
    }
}
