package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The closed set of {@code outbound_message.note} forms (design §8). A note is one or more of these
 * joined by {@code ;}; each outcome UPDATE rewrites the whole note, so a note never grows across
 * re-runs. Five forms are in use; {@link #BASELINE_NONE} and {@link #CONTRACT_INVALID} are reserved
 * and unused.
 */
public enum NoteForm implements WireValued {

    /** The previous outcome of a re-run row. */
    PREV_STATUS("prev=<STATUS>/<reason>", false),
    /** The previous outcome of a re-run {@code PENDING} row. */
    PREV_PENDING("prev=PENDING/-", false),
    /** The delivery that re-ran the row (slice 004 replay). */
    RERUN_BY("rerun-by=<messageId>", false),
    /** A resend that allowed a changed payload (slice 004). */
    OVERRIDE_ALLOW_CHANGED_PAYLOAD("override=allow-changed-payload", false),
    /** A forced resend of a terminal-success leg (slice 004). */
    OVERRIDE_FORCE_RESEND("override=force-resend", false),
    /** Reserved and unused. */
    BASELINE_NONE("baseline=none", true),
    /** Reserved and unused while outbound validation is parked (H13). */
    CONTRACT_INVALID("contract=invalid", true);

    private final String template;
    private final boolean reservedForm;

    NoteForm(final String template, final boolean reservedForm) {
        this.template = template;
        this.reservedForm = reservedForm;
    }

    @Override
    public String wireValue() {
        return template;
    }

    /**
     * Whether this form is reserved and never written by any slice.
     *
     * @return {@code true} for a reserved form
     */
    public boolean reserved() {
        return reservedForm;
    }

    /**
     * Resolves a note-form template to its {@link NoteForm} constant.
     *
     * @param value the template string
     * @return the matching constant
     */
    public static NoteForm fromValue(final String value) {
        return Vocabularies.fromValue(NoteForm.class, value);
    }
}
