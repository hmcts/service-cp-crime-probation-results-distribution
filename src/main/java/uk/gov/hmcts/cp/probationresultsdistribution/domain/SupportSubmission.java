package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.Objects;
import java.util.regex.Pattern;

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
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");

    /** Rejects null components. */
    public SupportSubmission {
        Objects.requireNonNull(operator, "operator");
        Objects.requireNonNull(runId, "runId");
        Objects.requireNonNull(reason, "reason");
    }

    /**
     * Reads the markers from the delivery's application property values, each sanitised to one
     * line of at most 200 characters before it can reach {@code audit_note}: control characters
     * become a space and the value is stripped and cut at 200 characters. A value that is absent,
     * not text, or blank once sanitised is {@code -}. The values are written to
     * {@code audit_note} only, never logged or echoed (FR-004-041).
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
            final String sanitised = CONTROL.matcher(text).replaceAll(" ").strip();
            final String bounded = sanitised.length() > MAX_LENGTH
                    ? sanitised.substring(0, MAX_LENGTH).strip() : sanitised;
            line = bounded.isEmpty() ? ABSENT : bounded;
        }
        return line;
    }
}
