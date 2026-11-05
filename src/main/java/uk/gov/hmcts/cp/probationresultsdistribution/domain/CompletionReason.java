package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The reason a request reached {@code COMPLETED} (design §4.5). The name {@link #BOTH_LEGS_TERMINAL}
 * is kept although this release has one leg (A3-7); {@link #RECOVERED} is written only when
 * {@code audit_note} holds a replay line, which slice 004 adds.
 */
public enum CompletionReason implements WireValued {

    /** Every leg reached a terminal outcome. */
    BOTH_LEGS_TERMINAL("both-legs-terminal"),
    /** A replay or resend recovered a previously failed request (slice 004). */
    RECOVERED("recovered");

    private final String wire;

    CompletionReason(final String wireValue) {
        this.wire = wireValue;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * Resolves a wire string to its {@link CompletionReason} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static CompletionReason fromValue(final String value) {
        return Vocabularies.fromValue(CompletionReason.class, value);
    }
}
