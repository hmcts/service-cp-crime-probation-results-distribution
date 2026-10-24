package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * Why a youth-switch read ended {@code UNREADABLE} (design §10, FR-002-024). It is the {@code cause}
 * label on {@code probation_youth_switch_unreadable_total} and a field of the outcome log line; it
 * is never stored in a column.
 */
public enum YouthSwitchUnreadableCause implements WireValued {

    /** A non-2xx answer, or a no-response error other than a timeout, once the retry rule stops. */
    HTTP_FAILURE,
    /** The last attempt timed out, or the 25 s switch stage ran out. */
    TIMEOUT,
    /** A 2xx with an empty or missing permissions list, or a null first entry. */
    EMPTY_PERMISSIONS,
    /** A 2xx body that does not parse. */
    UNPARSEABLE;

    @Override
    public String wireValue() {
        return name();
    }

    /**
     * Resolves a wire string to its {@link YouthSwitchUnreadableCause} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static YouthSwitchUnreadableCause fromValue(final String value) {
        return Vocabularies.fromValue(YouthSwitchUnreadableCause.class, value);
    }
}
