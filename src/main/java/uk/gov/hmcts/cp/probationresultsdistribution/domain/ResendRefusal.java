package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The {@code reason} label of {@code probation_resend_refusals_total} (design §13). It is an API and
 * run-report code, never a row value; it is used by slice 004. Three of the four values also serve
 * the replay action's refusals.
 */
public enum ResendRefusal implements WireValued {

    /** No request row exists for the id. */
    UNKNOWN_REQUEST("unknown-request"),
    /** The latest row's state does not permit a resend. */
    NOT_ELIGIBLE("not-eligible"),
    /** A resend of the same leg is already in flight. */
    RESEND_IN_PROGRESS("resend-in-progress"),
    /** The request claim is held by a live run. */
    REQUEST_CLAIMED("request-claimed");

    private final String wire;

    ResendRefusal(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link ResendRefusal} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static ResendRefusal fromValue(final String value) {
        return Vocabularies.fromValue(ResendRefusal.class, value);
    }
}
