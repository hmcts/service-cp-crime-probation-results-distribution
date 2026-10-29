package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code route} label of {@code probation_replays_total} (design §13). It records how a replay
 * reached the service; it is written by slice 004.
 */
public enum ReplayRoute implements WireValued {

    /** The service's own replay action, under a fresh {@code messageId}. */
    SERVICE("service"),
    /** A submit-hearing resubmission ({@code origin = SUPPORT}). */
    SUPPORT("support"),
    /** A dead-letter copy resubmitted with no {@code origin}. */
    COPY("copy");

    private final String wire;

    ReplayRoute(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link ReplayRoute} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static ReplayRoute fromValue(final String value) {
        return Vocabularies.fromValue(ReplayRoute.class, value);
    }
}
