package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code action} label of the proposed {@code probation_operations_failures_total} (FR-002-057,
 * FR-002-072). The series is registered once here so the registry keeps one owner; slice 005 is the
 * only writer.
 */
public enum OperationsAction implements WireValued {

    /** The replay operator action. */
    REPLAY("replay"),
    /** The resend-leg operator action. */
    RESEND_LEG("resend-leg");

    private final String wire;

    OperationsAction(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link OperationsAction} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static OperationsAction fromValue(final String value) {
        return Vocabularies.fromValue(OperationsAction.class, value);
    }
}
