package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code reason} label of the proposed {@code probation_operations_failures_total} (FR-002-057,
 * FR-002-072). Registered once here with one owner; slice 005 is the only writer.
 */
public enum OperationsFailure implements WireValued {

    /** The audit context could not be reached. */
    AUDIT_UNAVAILABLE("audit-unavailable"),
    /** The audit response could not be published. */
    AUDIT_RESPONSE_UNPUBLISHED("audit-response-unpublished"),
    /** The store could not be reached. */
    STORE_UNAVAILABLE("store-unavailable"),
    /** The broker could not be reached. */
    BROKER_UNAVAILABLE("broker-unavailable"),
    /** An unexpected failure. */
    UNEXPECTED("unexpected");

    private final String wire;

    OperationsFailure(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link OperationsFailure} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static OperationsFailure fromValue(final String value) {
        return Vocabularies.fromValue(OperationsFailure.class, value);
    }
}
