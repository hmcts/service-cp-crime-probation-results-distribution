package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * Where the hearing payload was read from (design §10). The fetch tries the dated cache key, then
 * the undated key, then the results query API; the first source that yields a payload wins.
 */
public enum PayloadSource implements WireValued {

    /** The dated cache key {@code EXT_{hearingId}_{hearingDay}_result_}. */
    CACHE_DAY("cache-day"),
    /** The undated cache key {@code EXT_{hearingId}_result_}. */
    CACHE_NULLDAY("cache-nullday"),
    /** The results query API fallback. */
    QUERY("query");

    private final String wire;

    PayloadSource(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link PayloadSource} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static PayloadSource fromValue(final String value) {
        return Vocabularies.fromValue(PayloadSource.class, value);
    }
}
