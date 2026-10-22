package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;

/**
 * The Redis {@code EXT_} cache adapter (design H2, D5, FR-002-015, FR-002-016). It implements the
 * payload-fetch port over the dated cache key {@code EXT_{hearingId}_{hearingDay}_result_}, recording
 * {@code payload_source = cache-day}, and the parsed {@code sharedTime} when it parses.
 *
 * <p>This slice (US1) reads the dated key only. The undated-key fallback and the results-query leg of
 * the fetch chain are US3's (T059-T061), as is the full miss classification (unreachable, timeout,
 * blank, JSON null, unparseable). This adapter treats an absent, blank, JSON-null or unparseable
 * dated value as a miss and returns empty.
 */
public final class CachedHearingPayloadAdapter implements HearingPayloadSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String SHARED_TIME = "sharedTime";

    private final StatefulRedisConnection<String, String> connection;
    private final String keyPrefix;

    /**
     * Creates the adapter over a Redis connection and the key prefix.
     *
     * @param connection the Lettuce connection
     * @param keyPrefix  the {@code EXT_} key prefix
     */
    public CachedHearingPayloadAdapter(final StatefulRedisConnection<String, String> connection,
                                       final String keyPrefix) {
        this.connection = connection;
        this.keyPrefix = keyPrefix;
    }

    @Override
    public Optional<FetchedPayload> fetch(final DistributionCommand command) {
        final String key = keyPrefix + command.hearingId() + "_" + command.hearingDay() + "_result_";
        final String raw = connection.sync().get(key);
        Optional<FetchedPayload> result = Optional.empty();
        if (raw != null && !raw.isBlank()) {
            result = parse(raw);
        }
        return result;
    }

    private static Optional<FetchedPayload> parse(final String raw) {
        Optional<FetchedPayload> result = Optional.empty();
        try {
            final JsonNode tree = MAPPER.readTree(raw);
            if (tree != null && !tree.isMissingNode() && !tree.isNull()) {
                result = Optional.of(
                        new FetchedPayload(tree, PayloadSource.CACHE_DAY, sharedTime(tree)));
            }
        } catch (final JacksonException unparseable) {
            // An unparseable cached value is a miss for this key, not a run failure (FR-002-016,
            // DV-07); the fetch moves on. Not swallowed: mapped to the explicit miss below.
            result = Optional.empty();
        }
        return result;
    }

    private static Optional<Instant> sharedTime(final JsonNode tree) {
        final JsonNode node = tree.get(SHARED_TIME);
        Optional<Instant> result = Optional.empty();
        if (node != null && node.isString()) {
            result = parseInstant(node.stringValue());
        }
        return result;
    }

    private static Optional<Instant> parseInstant(final String value) {
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
