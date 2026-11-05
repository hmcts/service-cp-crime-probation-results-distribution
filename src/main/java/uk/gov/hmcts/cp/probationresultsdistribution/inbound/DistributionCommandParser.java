package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * Parses and validates an inbound message against the closed command contract (design Appendix B.1,
 * FR-002-002, FR-002-003). Validation is explicit in code — the runtime carries no JSON-schema
 * library — mirroring the sibling services' parsers: the vendored
 * {@code distribution-command.schema.json} is the build's documented authority, exercised in the
 * contract test.
 *
 * <p>The parser rejects a body that is not a JSON object, a missing required field, an unknown field
 * (the schema is {@code additionalProperties: false}), a field of the wrong type or unparseable
 * format, a {@code source} other than {@code RESULTS}, an {@code eventType} other than
 * {@code Hearing_Resulted}, and an explicit {@code null} {@code userId}. An absent {@code userId} is
 * valid. Every rejection is a {@link CommandValidationException} carrying a bounded kind only, never
 * the offending field's name or any value from the message.
 */
public final class DistributionCommandParser {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";
    private static final String HEARING_ID = "hearingId";
    private static final String HEARING_DAY = "hearingDay";
    private static final String SHARED_TIME = "sharedTime";
    private static final String EVENT_TYPE = "eventType";
    private static final String USER_ID = "userId";

    private static final Set<String> ALLOWED =
            Set.of(SOURCE, REQUEST_ID, HEARING_ID, HEARING_DAY, SHARED_TIME, EVENT_TYPE, USER_ID);
    private static final Set<String> REQUIRED =
            Set.of(SOURCE, REQUEST_ID, HEARING_ID, HEARING_DAY, SHARED_TIME, EVENT_TYPE);

    /**
     * Parses and validates the UTF-8 message body into a {@link DistributionCommand}.
     *
     * @param body the raw message body
     * @return the parsed, validated command
     * @throws CommandValidationException when the body breaks the closed contract
     */
    public DistributionCommand parse(final String body) {
        final JsonNode root = readTree(body);
        rejectUnknownFields(root);
        requireFields(root);
        rejectNullUserId(root);

        requireValue(root, SOURCE, "RESULTS");
        requireValue(root, EVENT_TYPE, "Hearing_Resulted");

        final UUID requestId = requireUuid(root, REQUEST_ID);
        final UUID hearingId = requireUuid(root, HEARING_ID);
        final LocalDate hearingDay = requireLocalDate(root);
        final OffsetDateTime sharedTime = requireInstant(root);
        final Optional<UUID> userId = optionalUuid(root);

        return new DistributionCommand(requireString(root, SOURCE), requestId, hearingId,
                hearingDay, sharedTime.toInstant(), requireString(root, EVENT_TYPE), userId);
    }

    private static JsonNode readTree(final String body) {
        final JsonNode root;
        try {
            root = MAPPER.readTree(body);
        } catch (final JacksonException e) {
            throw new CommandValidationException(CommandValidationException.Kind.NOT_JSON);
        }
        if (root == null || root.isMissingNode() || root.isNull()) {
            throw new CommandValidationException(CommandValidationException.Kind.NOT_JSON);
        }
        if (!root.isObject()) {
            throw new CommandValidationException(CommandValidationException.Kind.NOT_OBJECT);
        }
        return root;
    }

    private static void rejectUnknownFields(final JsonNode root) {
        for (final String name : root.propertyNames()) {
            if (!ALLOWED.contains(name)) {
                throw new CommandValidationException(CommandValidationException.Kind.UNKNOWN_FIELD);
            }
        }
    }

    private static void requireFields(final JsonNode root) {
        for (final String name : REQUIRED) {
            final JsonNode value = root.get(name);
            if (value == null || value.isNull()) {
                throw new CommandValidationException(
                        CommandValidationException.Kind.MISSING_REQUIRED_FIELD);
            }
        }
    }

    private static void rejectNullUserId(final JsonNode root) {
        final JsonNode value = root.get(USER_ID);
        if (value != null && value.isNull()) {
            throw new CommandValidationException(CommandValidationException.Kind.NULL_USER_ID);
        }
    }

    private static void requireValue(final JsonNode root, final String field, final String only) {
        if (!only.equals(requireString(root, field))) {
            throw new CommandValidationException(CommandValidationException.Kind.DISALLOWED_VALUE);
        }
    }

    private static String requireString(final JsonNode root, final String field) {
        final JsonNode value = root.get(field);
        if (value == null || !value.isString()) {
            throw new CommandValidationException(CommandValidationException.Kind.WRONG_TYPE);
        }
        return value.stringValue();
    }

    private static UUID requireUuid(final JsonNode root, final String field) {
        return parseUuid(requireString(root, field));
    }

    private static Optional<UUID> optionalUuid(final JsonNode root) {
        final JsonNode value = root.get(USER_ID);
        final Optional<UUID> result;
        if (value == null) {
            result = Optional.empty();
        } else {
            result = Optional.of(parseUuid(requireString(root, USER_ID)));
        }
        return result;
    }

    private static UUID parseUuid(final String value) {
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException e) {
            throw new CommandValidationException(CommandValidationException.Kind.WRONG_TYPE);
        }
    }

    private static LocalDate requireLocalDate(final JsonNode root) {
        try {
            return LocalDate.parse(requireString(root, HEARING_DAY));
        } catch (final DateTimeParseException e) {
            throw new CommandValidationException(CommandValidationException.Kind.WRONG_TYPE);
        }
    }

    private static OffsetDateTime requireInstant(final JsonNode root) {
        try {
            return OffsetDateTime.parse(requireString(root, SHARED_TIME));
        } catch (final DateTimeParseException e) {
            throw new CommandValidationException(CommandValidationException.Kind.WRONG_TYPE);
        }
    }
}
