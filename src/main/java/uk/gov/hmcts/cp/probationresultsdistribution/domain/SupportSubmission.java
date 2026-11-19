package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.Objects;

/**
 * The operator markers a submit-hearing command carries as broker application properties
 * ({@code operator}, {@code runId}, {@code reason}; slice 004, FR-004-056, FR-004-057), read at
 * admission only for a delivery marked {@code origin = SUPPORT}.
 *
 * @param operator the operator
 * @param runId    the submit-hearing run id
 * @param reason   the reason
 */
public record SupportSubmission(String operator, String runId, String reason) {

    private static final int MAX_LENGTH = 200;
    private static final String ABSENT = "-";

    /** Rejects null components. */
    public SupportSubmission {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Reads the markers from the delivery's application property values, each sanitised to one
     * line of at most 200 characters before it can reach {@code audit_note}: control characters
     * become a space, the value is stripped and cut at 200 characters (never inside a surrogate
     * pair), and the {@code REPLAYED_AFTER_FAILURE} marker is escaped out through
     * {@link AuditNoteText} — this is the actual {@code audit_note} write path for a submit-hearing
     * admission (via {@code JdbcProcessedRequestRepository#appendSupportLine}), reading whatever
     * application properties the delivered message carries rather than trusting
     * {@code application.SubmitHearingAction}'s own sanitising of its broker properties, so a
     * delivery marked {@code origin = SUPPORT} from anywhere else on the queue is covered too (PR
     * #29 review). A value that is absent, not text, or blank once sanitised is {@code -}. The
     * values are written to {@code audit_note} only, never logged or echoed (FR-004-041).
     *
     * @param operator the {@code operator} property value, or null
     * @param runId    the {@code runId} property value, or null
     * @param reason   the {@code reason} property value, or null
     * @return the markers
     */
    public static SupportSubmission fromProperties(final Object operator, final Object runId,
                                                   final Object reason) {
        return new SupportSubmission(oneLine(operator), oneLine(runId), oneLine(reason));
    }

    private static String oneLine(final Object value) {
        String line = ABSENT;
        if (value instanceof String text) {
            final String sanitised = AuditNoteText.oneLine(text);
            final String bounded = sanitised.length() > MAX_LENGTH
                    ? sanitised.substring(0, cut(sanitised)).strip() : sanitised;
            line = bounded.isEmpty() ? ABSENT : bounded;
        }
        return line;
    }

    /** The cut at 200 characters, one earlier when it would split a surrogate pair. */
    private static int cut(final String text) {
        return Character.isHighSurrogate(text.charAt(MAX_LENGTH - 1)) ? MAX_LENGTH - 1 : MAX_LENGTH;
    }
}
