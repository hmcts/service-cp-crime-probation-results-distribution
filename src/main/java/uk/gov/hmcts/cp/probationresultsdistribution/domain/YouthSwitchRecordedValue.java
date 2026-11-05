package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The four-valued persisted form written to {@code outbound_message.youth_switch} after the
 * configured default has resolved any unreadable reading (FR-002-025). The youth rules R3/R4 run
 * when the recorded value is {@link #ACTIVE} or {@link #UNREADABLE_DEFAULTED_ACTIVE}. It is tied to
 * no reason.
 */
public enum YouthSwitchRecordedValue implements WireValued {

    /** Read {@code ACTIVE}; youth defendants removed. */
    ACTIVE,
    /** Read {@code INACTIVE}; youth defendants kept. */
    INACTIVE,
    /** Unreadable, defaulted to active; youth defendants removed, assumption recorded. */
    UNREADABLE_DEFAULTED_ACTIVE,
    /** Unreadable, defaulted to inactive; youth defendants kept, assumption recorded. */
    UNREADABLE_DEFAULTED_INACTIVE;

    @Override
    public String wireValue() {
        return name();
    }

    /**
     * Whether the youth rules R3/R4 run for this recorded value.
     *
     * @return {@code true} when the effective state removes youth defendants
     */
    public boolean removesYouthDefendants() {
        return this == ACTIVE || this == UNREADABLE_DEFAULTED_ACTIVE;
    }

    /**
     * Resolves a wire string to its {@link YouthSwitchRecordedValue} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static YouthSwitchRecordedValue fromValue(final String value) {
        return Vocabularies.fromValue(YouthSwitchRecordedValue.class, value);
    }
}
