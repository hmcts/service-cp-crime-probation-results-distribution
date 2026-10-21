package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The reason a request reached {@code FAILED} (design §4.5). It is NULL on every other status. Only
 * {@link #LEG_EXHAUSTED} carries an {@code exhausted_message_id}; {@link #ROLLED_BACK} is written
 * only by slice 004's rollback.
 */
public enum FailureReason implements WireValued {

    /** A leg reached its final permitted delivery still parked. */
    LEG_EXHAUSTED("leg-exhausted"),
    /** A rollback failed the request (slice 004). */
    ROLLED_BACK("rolled-back");

    private final String wire;

    FailureReason(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link FailureReason} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static FailureReason fromValue(final String value) {
        return Vocabularies.fromValue(FailureReason.class, value);
    }
}
