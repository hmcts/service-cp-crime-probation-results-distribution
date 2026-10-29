package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The closed set of causes a body-retention maintenance failure's ERROR line carries (FR-003-017;
 * AS-14; design §10.2). A log field only — never a metric label; {@link BodyMaintenanceStep} stays
 * the failure counter's only label, so an unbounded cause can never reach it.
 */
public enum MaintenanceFailureCause implements WireValued {

    /** A statement gave up waiting for its locks within the 5 s lock timeout. */
    LOCK_TIMEOUT("lock-timeout"),
    /** A creation was refused because the DEFAULT partition holds rows of that month. */
    DEFAULT_HAS_ROWS("default-has-rows"),
    /** A creation was refused because another partition already covers all or part of the month. */
    PARTITION_OVERLAP("partition-overlap"),
    /** The store could not be reached, or the connection was lost. */
    STORE_UNAVAILABLE("store-unavailable"),
    /** Any other failure. */
    OTHER("other");

    private final String wire;

    MaintenanceFailureCause(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link MaintenanceFailureCause} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static MaintenanceFailureCause fromValue(final String value) {
        return Vocabularies.fromValue(MaintenanceFailureCause.class, value);
    }
}
