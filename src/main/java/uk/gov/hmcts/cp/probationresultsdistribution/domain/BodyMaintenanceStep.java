package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code step} label of {@code probation_body_maintenance_failures_total} (design §13). It
 * records which part of the body-retention job failed; the job itself is slice 003.
 */
public enum BodyMaintenanceStep implements WireValued {

    /** Acquiring the single-runner advisory lock. */
    LOCK("lock"),
    /** Creating a monthly partition. */
    CREATE("create"),
    /** Dropping an expired monthly partition. */
    DROP("drop");

    private final String wire;

    BodyMaintenanceStep(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link BodyMaintenanceStep} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static BodyMaintenanceStep fromValue(final String value) {
        return Vocabularies.fromValue(BodyMaintenanceStep.class, value);
    }
}
