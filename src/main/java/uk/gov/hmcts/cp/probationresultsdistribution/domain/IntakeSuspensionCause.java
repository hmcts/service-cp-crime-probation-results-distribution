package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code cause} label of the {@code probation_intake_suspended} gauge (design §13). Slice 002
 * sets {@link #DATABASE}; {@link #OPERATOR} is set by slice 004's pause-intake.
 */
public enum IntakeSuspensionCause implements WireValued {

    /** Intake is suspended because the store is unavailable. */
    DATABASE("database"),
    /** Intake is suspended by an operator (slice 004). */
    OPERATOR("operator");

    private final String wire;

    IntakeSuspensionCause(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link IntakeSuspensionCause} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static IntakeSuspensionCause fromValue(final String value) {
        return Vocabularies.fromValue(IntakeSuspensionCause.class, value);
    }
}
