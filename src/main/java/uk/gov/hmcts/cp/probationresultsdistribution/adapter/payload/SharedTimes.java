package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import tools.jackson.databind.JsonNode;

/**
 * Defensive {@code sharedTime} parsing shared by the cache and results-query payload sources
 * (FR-002-019). A payload's {@code sharedTime} is read as an ISO-8601 instant with any offset or
 * zone honoured; a value that is absent, not a string, or does not parse leaves
 * {@code payload_shared_time} empty, and the leg carries on. The payload is never failed for a
 * {@code sharedTime} that will not parse.
 */
public final class SharedTimes {

    private static final String SHARED_TIME = "sharedTime";

    private SharedTimes() {
    }

    /**
     * Reads and parses the {@code sharedTime} member of a payload tree.
     *
     * @param tree the payload tree
     * @return the parsed instant, or empty when it is absent, not a string, or unparseable
     */
    public static Optional<Instant> parse(final JsonNode tree) {
        final JsonNode node = tree.get(SHARED_TIME);
        Optional<Instant> result = Optional.empty();
        if (node != null && node.isString()) {
            result = parse(node.stringValue());
        }
        return result;
    }

    /**
     * Parses an ISO-8601 instant string with any offset or zone.
     *
     * @param value the candidate instant string
     * @return the parsed instant, or empty when it does not parse
     */
    public static Optional<Instant> parse(final String value) {
        Optional<Instant> result;
        try {
            result = Optional.of(OffsetDateTime.parse(value).toInstant());
        } catch (final DateTimeParseException unparseable) {
            // A sharedTime that does not parse leaves payload_shared_time empty; the leg carries on
            // (FR-002-019). Not swallowed: mapped to the explicit empty below.
            result = Optional.empty();
        }
        return result;
    }
}
