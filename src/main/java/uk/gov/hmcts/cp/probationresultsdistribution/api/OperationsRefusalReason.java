package uk.gov.hmcts.cp.probationresultsdistribution.api;

import org.springframework.http.HttpStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Vocabularies;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WireValued;

/**
 * The operations API's closed answer {@code reason} set (FR-005-029) and the status family each
 * one belongs to (FR-005-030). Embeds {@link HttpStatus}, a Spring MVC type, so this lives in
 * {@code api/}, never in {@code domain/} (constitution Principle V).
 */
public enum OperationsRefusalReason implements WireValued {

    /** No, or a blank, identity on the request. */
    UNAUTHENTICATED("unauthenticated", HttpStatus.UNAUTHORIZED),
    /** The caller is outside the admitted group, or users-groups could not be asked. */
    FORBIDDEN("forbidden", HttpStatus.FORBIDDEN),
    /** A required argument is absent or blank. */
    MISSING_ARGUMENT("missing-argument", HttpStatus.BAD_REQUEST),
    /** An argument cannot be read as its type. */
    UNREADABLE_ARGUMENT("unreadable-argument", HttpStatus.BAD_REQUEST),
    /** An argument exceeds its bound. */
    ARGUMENT_TOO_LONG("argument-too-long", HttpStatus.BAD_REQUEST),
    /** The body carries a field the contract does not name. */
    UNKNOWN_FIELD("unknown-field", HttpStatus.BAD_REQUEST),
    /** The body will not parse as JSON. */
    UNREADABLE_BODY("unreadable-body", HttpStatus.BAD_REQUEST),
    /** The distinct request id count exceeds the configured cap. */
    TOO_MANY_REQUEST_IDS("too-many-request-ids", HttpStatus.BAD_REQUEST),
    /** The request declares a content type other than JSON. */
    UNSUPPORTED_CONTENT_TYPE("unsupported-content-type", HttpStatus.UNSUPPORTED_MEDIA_TYPE),
    /** A well-formed {@code requestId} names no request. */
    UNKNOWN_REQUEST("unknown-request", HttpStatus.NOT_FOUND),
    /** The request's current state does not permit the action. */
    NOT_ELIGIBLE("not-eligible", HttpStatus.CONFLICT),
    /** A resend of the same leg is already in flight. */
    RESEND_IN_PROGRESS("resend-in-progress", HttpStatus.CONFLICT),
    /** Intake is paused by an operator. */
    INTAKE_PAUSED("intake-paused", HttpStatus.CONFLICT),
    /** The audit context could not be reached. */
    AUDIT_UNAVAILABLE("audit-unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    /** The processed log could not be reached. */
    STORE_UNAVAILABLE("store-unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    /** The broker could not take the replay's command. */
    BROKER_UNAVAILABLE("broker-unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    /** An unclassified defect. */
    UNEXPECTED("unexpected", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String wire;
    private final HttpStatus status;

    OperationsRefusalReason(final String wireValue, final HttpStatus status) {
        this.wire = wireValue;
        this.status = status;
    }

    @Override
    public String wireValue() {
        return wire;
    }

    /**
     * The status family this reason answers with (FR-005-030).
     *
     * @return the HTTP status
     */
    public HttpStatus httpStatus() {
        return status;
    }

    /**
     * Resolves a wire string to its {@link OperationsRefusalReason} constant.
     *
     * @param value the wire string
     * @return the matching constant
     */
    public static OperationsRefusalReason fromValue(final String value) {
        return Vocabularies.fromValue(OperationsRefusalReason.class, value);
    }
}
