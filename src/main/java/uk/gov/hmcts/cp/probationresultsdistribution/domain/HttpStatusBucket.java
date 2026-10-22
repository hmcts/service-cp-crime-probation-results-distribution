package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The closed {@code http} label of {@code probation_leg_outcomes_total} (design §13). An answer
 * outside 2xx, 4xx and 5xx (in practice a 3xx) buckets as {@link #OTHER}; no answer is {@link #NONE}
 * (the 2026-10-21 slice 002 clarification added {@link #OTHER}).
 */
public enum HttpStatusBucket implements WireValued {

    /** HTTP 200. */
    OK_200("200"),
    /** A 2xx other than 200. */
    TWO_XX_OTHER("2xx-other"),
    /** HTTP 400. */
    BAD_REQUEST_400("400"),
    /** HTTP 401. */
    UNAUTHORIZED_401("401"),
    /** HTTP 408. */
    REQUEST_TIMEOUT_408("408"),
    /** HTTP 429. */
    TOO_MANY_REQUESTS_429("429"),
    /** A 4xx other than 400, 401, 408 and 429. */
    FOUR_XX_OTHER("4xx-other"),
    /** Any 5xx. */
    FIVE_XX("5xx"),
    /** An answer outside 2xx, 4xx and 5xx (for example a 3xx). */
    OTHER("other"),
    /** No answer: a connection or read failure. */
    NONE("none");

    private final String wire;

    HttpStatusBucket(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link HttpStatusBucket} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static HttpStatusBucket fromValue(final String value) {
        return Vocabularies.fromValue(HttpStatusBucket.class, value);
    }
}
