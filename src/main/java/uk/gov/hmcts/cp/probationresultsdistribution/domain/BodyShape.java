package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code shape} label of {@code probation_leg_outcomes_total} (design §13, FR-002-059).
 * {@link #APPLICATION_ONLY} when the body sent carries court applications and no prosecution case;
 * {@link #CASE} otherwise.
 */
public enum BodyShape implements WireValued {

    /** Court applications, no prosecution case. */
    APPLICATION_ONLY("application-only"),
    /** Any body carrying a prosecution case, and every outcome that sent no body. */
    CASE("case");

    private final String wire;

    BodyShape(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link BodyShape} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static BodyShape fromValue(final String value) {
        return Vocabularies.fromValue(BodyShape.class, value);
    }
}
