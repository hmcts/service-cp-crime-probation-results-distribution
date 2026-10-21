package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The status of one {@code outbound_message} leg attempt (design §4.5). {@code reason} is NULL iff
 * the status is {@link #PENDING} or {@link #SENT}; every other status carries a bounded
 * {@link LegReason}.
 */
public enum LegStatus implements WireValued {

    /** The attempt row is inserted but has no outcome yet. */
    PENDING,
    /** The gateway answered 2xx; the body was sent. */
    SENT,
    /** The redaction left nothing to send, or the hearing is out of scope. */
    NOT_APPLICABLE,
    /** A transient miss or outcome-unknown answer; the broker redelivers. */
    PARKED,
    /** A terminal failure, or the final-delivery form of a parked leg. */
    FAILED;

    @Override
    public String wireValue() {
        return name();
    }

    /**
     * Resolves a wire string to its {@link LegStatus} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static LegStatus fromValue(final String value) {
        return Vocabularies.fromValue(LegStatus.class, value);
    }
}
