package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;

@DisplayName("the Redis EXT_ cache adapter")
class CachedHearingPayloadAdapterTest {

    private static final int REDIS_PORT = 6379;
    private static final String PREFIX = "EXT_";

    @SuppressWarnings("resource")
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(REDIS_PORT);

    private static RedisClient client;
    private static StatefulRedisConnection<String, String> connection;

    private CachedHearingPayloadAdapter adapter;
    private RedisCommands<String, String> commands;

    @BeforeAll
    static void startRedis() {
        REDIS.start();
        client = RedisClient.create(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
        connection = client.connect();
    }

    @AfterAll
    static void stopRedis() {
        if (connection != null) {
            connection.close();
        }
        if (client != null) {
            client.shutdown();
        }
        REDIS.stop();
    }

    @BeforeEach
    void setUp() {
        commands = connection.sync();
        commands.flushall();
        adapter = new CachedHearingPayloadAdapter(connection, PREFIX);
    }

    private DistributionCommand command(final UUID hearingId, final LocalDate day) {
        return new DistributionCommand("RESULTS", UUID.randomUUID(), hearingId, day,
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
    }

    private String datedKey(final UUID hearingId, final LocalDate day) {
        return PREFIX + hearingId + "_" + day + "_result_";
    }

    @Test
    @DisplayName("reads the dated key and records cache-day with the parsed sharedTime")
    void reads_the_dated_key() {
        final UUID hearingId = UUID.randomUUID();
        final LocalDate day = LocalDate.parse("2026-08-19");
        commands.set(datedKey(hearingId, day),
                "{\"hearing\":{\"id\":\"x\"},\"sharedTime\":\"2026-08-19T16:42:07.512Z\"}");

        final Optional<FetchedPayload> fetched = adapter.fetch(command(hearingId, day));

        assertThat(fetched).isPresent();
        assertThat(fetched.get().source()).isEqualTo(PayloadSource.CACHE_DAY);
        assertThat(fetched.get().sharedTime()).contains(Instant.parse("2026-08-19T16:42:07.512Z"));
        assertThat(fetched.get().payload().get("hearing").get("id").asString()).isEqualTo("x");
    }

    @Test
    @DisplayName("records no sharedTime when the payload's sharedTime does not parse")
    void records_no_shared_time_when_unparseable() {
        final UUID hearingId = UUID.randomUUID();
        final LocalDate day = LocalDate.parse("2026-08-19");
        commands.set(datedKey(hearingId, day),
                "{\"hearing\":{\"id\":\"x\"},\"sharedTime\":\"not-a-time\"}");

        final Optional<FetchedPayload> fetched = adapter.fetch(command(hearingId, day));

        assertThat(fetched).isPresent();
        assertThat(fetched.get().sharedTime()).isEmpty();
    }

    @Test
    @DisplayName("misses when the dated key is absent")
    void misses_when_the_dated_key_is_absent() {
        assertThat(adapter.fetch(command(UUID.randomUUID(), LocalDate.parse("2026-08-19"))))
                .isEmpty();
    }

    @Test
    @DisplayName("misses when the dated value is blank")
    void misses_when_the_dated_value_is_blank() {
        final UUID hearingId = UUID.randomUUID();
        final LocalDate day = LocalDate.parse("2026-08-19");
        commands.set(datedKey(hearingId, day), "   ");

        assertThat(adapter.fetch(command(hearingId, day))).isEmpty();
    }

    @Test
    @DisplayName("misses when the dated value is JSON null")
    void misses_when_the_dated_value_is_json_null() {
        final UUID hearingId = UUID.randomUUID();
        final LocalDate day = LocalDate.parse("2026-08-19");
        commands.set(datedKey(hearingId, day), "null");

        assertThat(adapter.fetch(command(hearingId, day))).isEmpty();
    }

    @Test
    @DisplayName("misses when the dated value does not parse")
    void misses_when_the_dated_value_is_unparseable() {
        final UUID hearingId = UUID.randomUUID();
        final LocalDate day = LocalDate.parse("2026-08-19");
        commands.set(datedKey(hearingId, day), "{not json");

        assertThat(adapter.fetch(command(hearingId, day))).isEmpty();
    }
}
