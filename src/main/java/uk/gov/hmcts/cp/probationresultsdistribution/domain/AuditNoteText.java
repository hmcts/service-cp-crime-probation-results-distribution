package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.regex.Pattern;

/**
 * The one escape rule shared by every writer of operator-or-reason text into
 * {@code processed_request.audit_note} (FR-004-012, FR-004-037, FR-004-057, FR-004-059; PR #29
 * review): a run of control characters collapses to one space and the result is stripped to one
 * line, then the literal {@code REPLAYED_AFTER_FAILURE} marker — the one
 * {@code JdbcProcessedRequestRepository#markCompleted} scans {@code audit_note} for, unanchored
 * and case-insensitive, to decide a completion is {@code recovered} — is escaped out of the text.
 * Without this, an operator's own reason or name could plant that marker and make an unrelated
 * completion of the same request read as a replay recovery it never was.
 *
 * <p>In {@code domain}, not {@code application}, so {@link SupportSubmission} can share it too:
 * {@code application.SubmitHearingAction}'s own sanitising only covers the application properties
 * it puts on the broker message it sends, never the submit-hearing admission's own
 * {@code audit_note} line, which is written from {@code SupportSubmission.fromProperties} reading
 * whatever the delivered message actually carries — a message marked {@code origin = SUPPORT}
 * admitted from anywhere else on the queue, not only this service's own runner, reaches
 * {@code audit_note} through this class, never {@code SubmitHearingAction}'s. Every other
 * operator-supplied-text writer — {@code LegResender}'s forced-resend line,
 * {@code CloseExpiredResendAction}'s close line, and {@code SubmitHearingAction}'s own broker
 * properties — sanitises through this one method too, so the rule cannot drift between any of
 * them.
 */
public final class AuditNoteText {

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}+");
    private static final Pattern REPLAY_MARKER =
            Pattern.compile(Pattern.quote("REPLAYED_AFTER_FAILURE"), Pattern.CASE_INSENSITIVE);
    private static final String REPLAY_MARKER_ESCAPED = "REPLAYED-AFTER-FAILURE";

    private AuditNoteText() {
    }

    /**
     * Collapses {@code text} to one line and escapes the replay marker out of it.
     *
     * @param text the raw operator or reason text, never {@code null}
     * @return the text, safe to append to {@code audit_note}
     */
    public static String oneLine(final String text) {
        final String collapsed = CONTROL_CHARACTERS.matcher(text).replaceAll(" ").strip();
        return REPLAY_MARKER.matcher(collapsed).replaceAll(REPLAY_MARKER_ESCAPED);
    }
}
