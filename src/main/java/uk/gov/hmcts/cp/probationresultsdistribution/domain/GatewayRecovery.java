package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code after} label of {@code probation_gateway_recovered_total} (design §13, FR-002-060). It
 * records how a send recovered to {@code SENT}.
 */
public enum GatewayRecovery implements WireValued {

    /** A send that ended {@code SENT} after at least one 429 retried in the same run. */
    IN_RUN_429("429-in-run"),
    /** A re-run of a 5xx-parked row that ended {@code SENT} on redelivery. */
    FIVE_XX_REDELIVERY("5xx-redelivery");

    private final String wire;

    GatewayRecovery(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link GatewayRecovery} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static GatewayRecovery fromValue(final String value) {
        return Vocabularies.fromValue(GatewayRecovery.class, value);
    }
}
