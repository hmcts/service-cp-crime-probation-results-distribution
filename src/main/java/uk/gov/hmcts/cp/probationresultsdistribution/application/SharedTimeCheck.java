package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * The resend {@code sharedTime} check (design H17, A3-57; slice 004, FR-004-029). A resend compares
 * the fetched payload's {@code sharedTime} with the request's stored {@code shared_time} as
 * instants, directly after the fetch and before any leg stage. The payload's ISO-8601 string is
 * parsed honouring its offset or its zone — a rendering that names a zone included — and the two
 * instants are compared at the stored precision, the microsecond of a PostgreSQL
 * {@code timestamptz}. A rendering with neither offset nor zone does not name an instant and is
 * unparseable.
 *
 * <p>This check is the resend's own: the queue run records {@code payload_shared_time} from its
 * payload adapter and compares nothing, so this class changes no queue behaviour.
 */
final class SharedTimeCheck {

    private static final String SHARED_TIME = "sharedTime";

    private SharedTimeCheck() {
    }

    /**
     * Reads and parses the payload's top-level {@code sharedTime}.
     *
     * @param payload the fetched hearing payload
     * @return the instant it names, or empty when it is absent, not a string, or unparseable
     */
    static Optional<Instant> parse(final JsonNode payload) {
        final JsonNode node = payload.get(SHARED_TIME);
        Optional<Instant> instant = Optional.empty();
        if (node != null && node.isString()) {
            instant = parse(node.stringValue());
        }
        return instant;
    }

    /**
     * Whether the payload's {@code sharedTime} names the stored instant, at the stored precision.
     *
     * @param payloadSharedTime the payload's parsed {@code sharedTime}, empty when absent or
     *                          unparseable
     * @param stored            the request's stored {@code shared_time}
     * @return {@code true} only when both name the same microsecond
     */
    static boolean matches(final Optional<Instant> payloadSharedTime, final Instant stored) {
        final Instant storedPrecision = stored.truncatedTo(ChronoUnit.MICROS);
        return payloadSharedTime
                .map(instant -> instant.truncatedTo(ChronoUnit.MICROS).equals(storedPrecision))
                .orElse(false);
    }

    private static Optional<Instant> parse(final String value) {
        Optional<Instant> instant;
        try {
            instant = Optional.of(ZonedDateTime.parse(value, DateTimeFormatter.ISO_DATE_TIME)
                    .toInstant());
        } catch (final DateTimeParseException unparseable) {
            // An unparseable sharedTime is a changed payload for a resend (FR-004-029), not a run
            // failure: mapped to the explicit empty the check refuses. Not swallowed.
            instant = Optional.empty();
        }
        return instant;
    }
}
