package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * A distribution leg. There is one value, {@code HMPPS} (design H5): the per-leg structure is the
 * extension point, so a re-added leg is a new constant rather than a new code shape (A3-1). The
 * runner never branches on whether a leg is HMPPS.
 */
public enum Leg implements WireValued {

    /** The HMPPS hearing-results route, the only leg in this release. */
    HMPPS("HMPPS");

    private final String wire;

    Leg(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link Leg} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static Leg fromValue(final String value) {
        return Vocabularies.fromValue(Leg.class, value);
    }
}
