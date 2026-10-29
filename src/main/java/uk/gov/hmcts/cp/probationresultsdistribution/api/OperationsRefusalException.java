package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.io.Serial;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * One bounded operations-API refusal (FR-005-029 to FR-005-032): a closed {@link
 * OperationsRefusalReason}, with the argument name for a {@code 400} about one argument
 * (FR-005-031) or the cap applied for {@code too-many-request-ids}. Never carries the caller's own
 * value or the far end's own words.
 */
public final class OperationsRefusalException extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;
    private static final Pattern ECHOABLE_FIELD_NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]{0,63}");

    private final transient OperationsRefusalReason refusalReason;
    private final transient String argument;
    private final transient Integer cap;

    private OperationsRefusalException(final OperationsRefusalReason refusalReason, final String argument,
                                       final Integer cap) {
        super(refusalReason.wireValue());
        this.refusalReason = refusalReason;
        this.argument = argument;
        this.cap = cap;
    }

    /**
     * A refusal naming no particular argument.
     *
     * @param reason the refusal reason
     * @return the exception
     */
    public static OperationsRefusalException forReason(final OperationsRefusalReason reason) {
        Objects.requireNonNull(reason, "reason");
        return new OperationsRefusalException(reason, null, null);
    }

    /**
     * A {@code 400} about one named argument.
     *
     * @param reason       the refusal reason
     * @param argumentName the argument's name, never its value
     * @return the exception
     */
    public static OperationsRefusalException ofArgument(final OperationsRefusalReason reason,
                                                         final String argumentName) {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(argumentName, "argumentName");
        return new OperationsRefusalException(reason, argumentName, null);
    }

    /**
     * A {@code 400 unknown-field} (FR-005-033). The field's name is chosen by the caller, so it is
     * named only when it is a plain, bounded identifier — a letter, then up to 63 letters, digits
     * or underscores — and left out otherwise, so that no caller-chosen text of any length or
     * character set is echoed back (FR-005-029, FR-005-031).
     *
     * @param fieldName the name of the field the contract does not name, as sent
     * @return the exception
     */
    public static OperationsRefusalException unknownField(final String fieldName) {
        final String named = fieldName != null && ECHOABLE_FIELD_NAME.matcher(fieldName).matches()
                ? fieldName : null;
        return new OperationsRefusalException(OperationsRefusalReason.UNKNOWN_FIELD, named, null);
    }

    /**
     * The {@code requestIds} list carries more distinct ids than the configured cap.
     *
     * @param capApplied the cap that was applied
     * @return the exception
     */
    public static OperationsRefusalException tooManyRequestIds(final int capApplied) {
        return new OperationsRefusalException(OperationsRefusalReason.TOO_MANY_REQUEST_IDS,
                "requestIds", capApplied);
    }

    /**
     * The closed reason.
     *
     * @return the refusal reason
     */
    public OperationsRefusalReason reason() {
        return refusalReason;
    }

    /**
     * The argument this refusal names, if any.
     *
     * @return the argument name
     */
    public Optional<String> argumentName() {
        return Optional.ofNullable(argument);
    }

    /**
     * The cap applied, for {@code too-many-request-ids} only.
     *
     * @return the cap
     */
    public Optional<Integer> capApplied() {
        return Optional.ofNullable(cap);
    }
}
