package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.Arrays;

/**
 * The closed set of whole-run and whole-action refusal codes this slice adds (spec Key Entities,
 * "Outcome and refusal codes"; Assumption 3). None of these is the design's own vocabulary — they
 * are named here, aligned with the operations API slice's draft, so that a runner and its eventual
 * HTTP endpoint answer alike.
 *
 * <p>Refusing an action whole means nothing was read, claimed or changed (FR-004-019,
 * FR-004-020, FR-004-021, FR-004-053).
 */
public enum WholeRunRefusal {

    /** Resend-leg or purge-queue was submitted while intake is paused by an operator. */
    INTAKE_PAUSED("intake-paused"),

    /** Purge-queue was run while intake is not paused. */
    INTAKE_NOT_PAUSED("intake-not-paused"),

    /** The resend-leg ID list, after collapsing duplicates, exceeds the configured cap. */
    TOO_MANY_REQUEST_IDS("too-many-request-ids"),

    /** A required argument (operator, reason, an ID, a hearing identifier) is missing or blank. */
    MISSING_ARGUMENT("missing-argument"),

    /** An argument that must be a UUID, a date, or a closed value is none of those. */
    UNREADABLE_ARGUMENT("unreadable-argument"),

    /** An operator or reason argument exceeds 200 characters. */
    ARGUMENT_TOO_LONG("argument-too-long"),

    /** The processed-log store could not be reached; nothing changed. */
    STORE_UNAVAILABLE("store-unavailable"),

    /** The broker could not be reached to enqueue a message; nothing changed in the store. */
    BROKER_UNAVAILABLE("broker-unavailable");

    private final String wireValue;

    WholeRunRefusal(final String wireValue) {
        this.wireValue = wireValue;
    }

    /**
     * The closed wire/label value this refusal is reported as.
     *
     * @return the lower-case, hyphen-separated code used in reports and log lines
     */
    public String value() {
        return wireValue;
    }

    /**
     * Resolves a whole-run refusal from its wire value.
     *
     * @param value one of the closed set's {@link #value()} strings
     * @return the matching refusal
     * @throws IllegalArgumentException if {@code value} is not one of the closed set
     */
    public static WholeRunRefusal fromValue(final String value) {
        return Arrays.stream(values())
                .filter(refusal -> refusal.wireValue.equals(value))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown whole-run refusal: " + value));
    }
}
