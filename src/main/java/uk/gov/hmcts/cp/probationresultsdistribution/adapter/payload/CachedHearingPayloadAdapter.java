package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import io.lettuce.core.RedisException;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;

/**
 * The Redis {@code EXT_} cache adapter (design H2, D5, FR-002-015, FR-002-016). It implements the
 * payload-fetch port over the two cache keys, in order: the dated key
 * {@code EXT_{hearingId}_{hearingDay}_result_} ({@code payload_source = cache-day}), then the undated
 * key {@code EXT_{hearingId}_result_} ({@code payload_source = cache-nullday}). The first key that
 * yields a usable value wins; the parsed {@code sharedTime} is recorded when it parses.
 *
 * <p>Every read failure is a miss for that key, never a failure of the run (FR-002-016, DV-07):
 * an unreachable, timed-out or refused read, and a value that is absent, blank, JSON {@code null}
 * or unparseable, all fall through to the next key, and then — in the fetch chain — to the
 * results-query fallback. The results-query leg of the chain is a separate adapter (T060).
 */
public final class CachedHearingPayloadAdapter implements HearingPayloadSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
        final String datedKey =
                keyPrefix + command.hearingId() + "_" + command.hearingDay() + "_result_";
        Optional<FetchedPayload> result = readKey(datedKey, PayloadSource.CACHE_DAY);
        if (result.isEmpty()) {
            final String undatedKey = keyPrefix + command.hearingId() + "_result_";
            result = readKey(undatedKey, PayloadSource.CACHE_NULLDAY);
        }
        return result;
    }

    private Optional<FetchedPayload> readKey(final String key, final PayloadSource source) {
        return read(key).filter(raw -> !raw.isBlank()).flatMap(raw -> parse(raw, source));
    }

    private Optional<String> read(final String key) {
        Optional<String> raw;
        try {
            raw = Optional.ofNullable(connection.sync().get(key));
        } catch (final RedisException unreachable) {
            // An unreachable, timed-out or refused read is a miss for this key, not a run failure
            // (FR-002-016, DV-07). Not swallowed: mapped to the explicit miss (null) the caller treats
            // as the next source's turn.
            raw = Optional.empty();
        }
        return raw;
    }

    private static Optional<FetchedPayload> parse(final String raw, final PayloadSource source) {
        Optional<FetchedPayload> result = Optional.empty();
        try {
            final JsonNode tree = MAPPER.readTree(raw);
            if (tree != null && !tree.isMissingNode() && !tree.isNull()) {
                result = Optional.of(new FetchedPayload(tree, source, SharedTimes.parse(tree)));
            }
        } catch (final JacksonException unparseable) {
            // An unparseable cached value is a miss for this key, not a run failure (FR-002-016,
            // DV-07); the fetch moves on. Not swallowed: mapped to the explicit miss below.
            result = Optional.empty();
        }
        return result;
    }
}
