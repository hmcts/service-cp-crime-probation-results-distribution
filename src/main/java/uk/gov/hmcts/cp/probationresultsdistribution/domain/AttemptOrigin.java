package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * How a run was triggered, persisted as {@code outbound_message.attempt_origin} and used as the
 * run origin that labels every metric, log line and row a run writes (FR-002-010, FR-002-072). It
 * is captured once at admission and never re-derived mid-run. {@code attempt = 1} iff
 * {@link #QUEUE}. Slice 002 always runs as {@link #QUEUE}; {@link #REPLAY} and {@link #RESEND}
 * arrive with slice 004.
 */
public enum AttemptOrigin implements WireValued {

    /** A queue delivery or redelivery; attempt 1. */
    QUEUE("queue"),
    /** A replay of a failed request (slice 004). */
    REPLAY("replay"),
    /** A resend-leg run (slice 004). */
    RESEND("resend");

    private final String wire;

    AttemptOrigin(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link AttemptOrigin} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static AttemptOrigin fromValue(final String value) {
        return Vocabularies.fromValue(AttemptOrigin.class, value);
    }
}
