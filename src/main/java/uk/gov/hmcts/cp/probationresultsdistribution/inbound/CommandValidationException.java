package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import java.util.Objects;

/**
 * Thrown when an inbound message breaks the closed command contract (design Appendix B.1,
 * FR-002-002, FR-002-003). It carries a bounded {@link Kind} only: the listener dead-letters the
 * delivery {@code validation} and records the bounded kind, never the offending field's name — which
 * is producer-chosen text — and never any value from the message (DV-16, workflow gate 1).
 */
public final class CommandValidationException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /**
     * The bounded violation kinds. None names a field or carries a value from the message; each is a
     * closed code safe to log and to label a metric with.
     */
    public enum Kind {
        /** The body is not JSON. */
        NOT_JSON,
        /** The body is JSON but not a JSON object. */
        NOT_OBJECT,
        /** A required field is missing. */
        MISSING_REQUIRED_FIELD,
        /** A field the closed schema does not allow is present. */
        UNKNOWN_FIELD,
        /** A field is present with the wrong type or an unparseable format. */
        WRONG_TYPE,
        /** A closed-enum field ({@code source}, {@code eventType}) carries a disallowed value. */
        DISALLOWED_VALUE,
        /** {@code userId} is present but explicitly {@code null}. */
        NULL_USER_ID
    }

    private final transient Kind violationKind;

    /**
     * Creates the exception with its bounded kind. The message is the kind's name only, so no field
     * name or message value ever reaches a log line.
     *
     * @param kind the bounded violation kind
     */
    public CommandValidationException(final Kind kind) {
        super(Objects.requireNonNull(kind, "kind").name());
        this.violationKind = kind;
    }

    /**
     * The bounded violation kind.
     *
     * @return the kind
     */
    public Kind kind() {
        return violationKind;
    }
}
