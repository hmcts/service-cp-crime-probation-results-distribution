package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code status} label of {@code probation_gateway_non200_success_total} (design §13). A 2xx
 * other than 200 is a success, logged at WARN and counted here.
 */
public enum Non200SuccessStatus implements WireValued {

    /** HTTP 201. */
    CREATED_201("201"),
    /** HTTP 202. */
    ACCEPTED_202("202"),
    /** HTTP 204. */
    NO_CONTENT_204("204"),
    /** Any other 2xx. */
    OTHER("other");

    private final String wire;

    Non200SuccessStatus(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link Non200SuccessStatus} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static Non200SuccessStatus fromValue(final String value) {
        return Vocabularies.fromValue(Non200SuccessStatus.class, value);
    }
}
