package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The closed set of dead-letter reasons the service writes (design §4.2). The guard writes
 * {@link #VALIDATION}, {@link #COLLISION} and {@link #EXHAUSTED}; only settlement writes
 * {@link #LEG_EXHAUSTED}.
 */
public enum DeadLetterReason implements WireValued {

    /** The body failed the closed inbound schema. */
    VALIDATION("validation"),
    /** A known key arrived with a different fingerprint. */
    COLLISION("collision"),
    /** An inherited guard path, or a listener final-delivery hand-back. */
    EXHAUSTED("exhausted"),
    /** A parked leg reached its final permitted delivery. */
    LEG_EXHAUSTED("leg-exhausted");

    private final String wire;

    DeadLetterReason(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link DeadLetterReason} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static DeadLetterReason fromValue(final String value) {
        return Vocabularies.fromValue(DeadLetterReason.class, value);
    }
}
