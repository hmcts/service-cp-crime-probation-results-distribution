package uk.gov.hmcts.cp.probationresultsdistribution;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.azure.ServiceBusEmulatorContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.mssqlserver.MSSQLServerContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway.HmppsPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestFingerprint;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * Shared Testcontainers base for the US1 end-to-end suites (T030-T033). It stands the service's whole
 * dependency stack up once for the JVM — PostgreSQL 15, Redis, the Azure Service Bus emulator (with
 * its SQL Server companion) seeded with the committed {@code probation.requests} queue, and a WireMock
 * standing in for the users-groups read and the APIM gateway — and wires the real pipeline against
 * them. Each test sends one real command onto the queue, lets the running processor consume it, and
 * polls the store for the recorded outcome. The containers are shared and the suites run in one JVM,
 * sequentially, so the per-test processor is started and stopped around each case.
 */
class EndToEndTestSupport {

    protected static final String QUEUE = "probation.requests";
    protected static final String GATEWAY_PATH = "/probation/api/v1/hearing/results";
    protected static final String SUBSCRIPTION_KEY = "test-subscription-key";
    protected static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000000";
    protected static final String CACHE_PREFIX = "EXT_";
    protected static final Duration POLL_TIMEOUT = Duration.ofSeconds(90);

    private static final Logger LOG = LoggerFactory.getLogger(EndToEndTestSupport.class);
    private static final int REDIS_PORT = 6379;
    private static final String SB_IMAGE = "mcr.microsoft.com/azure-messaging/servicebus-emulator:1.1.2";
    private static final String MSSQL_IMAGE = "mcr.microsoft.com/mssql/server:2022-latest";

    private static final Network NETWORK = Network.newNetwork();
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:15-alpine"));
    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(REDIS_PORT);
    private static final MSSQLServerContainer MSSQL =
            new MSSQLServerContainer(DockerImageName.parse(MSSQL_IMAGE)
                    .asCompatibleSubstituteFor("mcr.microsoft.com/mssql/server"))
                    .acceptLicense()
                    .withNetwork(NETWORK);
    private static final ServiceBusEmulatorContainer SERVICE_BUS =
            new ServiceBusEmulatorContainer(DockerImageName.parse(SB_IMAGE))
                    .acceptLicense()
                    .withNetwork(NETWORK)
                    .withMsSqlServerContainer(MSSQL)
                    .withConfig(Transferable.of(readQueueConfig()));

    private static DataSource dataSource;
    private static RedisClient redisClient;
    private static StatefulRedisConnection<String, String> redisConnection;
    private static WireMockServer wireMock;
    private static ServiceBusSenderClient sender;
    private static volatile boolean started;

    private ServiceBusProcessorClient processor;
    private MeterRegistry meters = new SimpleMeterRegistry();

    protected EndToEndTestSupport() {
        startOnce();
    }

    private static synchronized void startOnce() {
        if (started) {
            return;
        }
        quietAzureSdkLogging();
        POSTGRES.start();
        REDIS.start();
        SERVICE_BUS.start();
        dataSource = buildDataSource();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        redisClient = RedisClient.create(
                "redis://" + REDIS.getHost() + ":" + REDIS.getMappedPort(REDIS_PORT));
        redisConnection = redisClient.connect();
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        sender = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS.getConnectionString())
                .sender()
                .queueName(QUEUE)
                .buildClient();
        started = true;
    }

    /**
     * Quietens the Azure Service Bus SDK, Reactor and Netty loggers to WARN for the shared test JVM.
     * The SDK logs connection open/close chatter at INFO on its own background threads, which keep
     * running after a processor closes; left at INFO they bleed into the root appender that unrelated
     * suites capture (for example {@code HmppsRedactionPurityTest}'s no-log-line assertion). This is
     * test noise, not service behaviour.
     */
    private static void quietAzureSdkLogging() {
        final String[] noisy = {"com.azure", "reactor", "io.netty", "com.microsoft"};
        for (final String name : noisy) {
            ((ch.qos.logback.classic.Logger) LoggerFactory.getLogger(name))
                    .setLevel(ch.qos.logback.classic.Level.WARN);
        }
    }

    private static DataSource buildDataSource() {
        final DriverManagerDataSource source = new DriverManagerDataSource();
        source.setUrl(POSTGRES.getJdbcUrl());
        source.setUsername(POSTGRES.getUsername());
        source.setPassword(POSTGRES.getPassword());
        source.setDriverClassName("org.postgresql.Driver");
        return source;
    }

    private static String readQueueConfig() {
        try {
            return Files.readString(Path.of("docker/servicebus-emulator/config.json"));
        } catch (final IOException e) {
            throw new UncheckedIOException("cannot read the committed emulator queue config", e);
        }
    }

    @AfterEach
    void stopProcessor() {
        if (processor != null) {
            processor.close();
        }
        wireMock.resetAll();
    }

    /**
     * Starts the running processor for one test, consuming {@code probation.requests} with peek-lock
     * and auto-complete disabled, dispatching to the production listener. Subclasses call this after
     * configuring their WireMock and cache state.
     */
    protected final void startProcessor() {
        meters = new SimpleMeterRegistry();
        processor = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS.getConnectionString())
                .processor()
                .queueName(QUEUE)
                .disableAutoComplete()
                .maxConcurrentCalls(2)
                .processMessage(listener()::processMessage)
                .processError(context -> LOG.error("processor error", context.getException()))
                .buildProcessorClient();
        processor.start();
    }

    /**
     * Builds the production listener wired against the live dependencies: the JDBC store on the shared
     * Postgres, the Redis cache adapter, the WireMock-backed users-groups and gateway clients, and a
     * {@code MetricRegistry} over the per-test meter registry. The youth-switch default is
     * {@code ACTIVE}.
     *
     * @return the listener under test
     */
    protected final ProbationMessageListener listener() {
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        final ProcessedRequestRepository processedRequests =
                new JdbcProcessedRequestRepository(jdbc(), tx, Duration.ofMinutes(4).plusSeconds(30));
        final OutboundMessageRepository outboundMessages =
                new OutboundMessageRepository(jdbc(), tx, "test-version");
        final OutboundLogAdapter outboundLog = new OutboundLogAdapter(outboundMessages);
        final CachedHearingPayloadAdapter payload =
                new CachedHearingPayloadAdapter(redisConnection, CACHE_PREFIX);
        final FeaturePermissionClient youthFilter = new FeaturePermissionClient(
                usersGroupsBaseUrl(), SYSTEM_USER_ID, "EMYouthFilter",
                Duration.ofSeconds(2), Duration.ofSeconds(5));
        final HmppsPublisher publisher = new HmppsPublisher(
                gatewayUrl(), SUBSCRIPTION_KEY, Duration.ofSeconds(5), Duration.ofSeconds(60));
        final MetricRegistry registry = new MetricRegistry(meters);
        final DistributionRunner runner = new DistributionRunner(payload, youthFilter, publisher,
                outboundLog, YouthSwitchRecordedValue.ACTIVE, registry);
        return new ProbationMessageListener(new DistributionCommandParser(),
                new IdempotencyGuard(processedRequests, new RequestFingerprint()), runner,
                processedRequests, registry);
    }

    protected final JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /**
     * The Micrometer registry the per-test pipeline records into. Recreated when the processor is
     * started, so each test asserts its own counts. T042 wires the pipeline's {@code MetricRegistry}
     * over this registry.
     *
     * @return the per-test meter registry
     */
    protected final MeterRegistry meterRegistry() {
        return meters;
    }

    protected final String usersGroupsBaseUrl() {
        return wireMock.baseUrl();
    }

    protected final String gatewayUrl() {
        return wireMock.baseUrl() + GATEWAY_PATH;
    }

    /**
     * Stubs the users-groups {@code EMYouthFilter} read to answer {@code ACTIVE}.
     */
    protected final void stubYouthSwitchActive() {
        wireMock.stubFor(get(urlPathEqualTo(
                "/usersgroups-query-api/query/api/rest/usersgroups/permissions"))
                .willReturn(okJson("{\"permissions\":[{\"active\":true}]}")));
    }

    /**
     * Stubs the users-groups {@code EMYouthFilter} read to answer {@code INACTIVE}.
     */
    protected final void stubYouthSwitchInactive() {
        wireMock.stubFor(get(urlPathEqualTo(
                "/usersgroups-query-api/query/api/rest/usersgroups/permissions"))
                .willReturn(okJson("{\"permissions\":[{\"active\":false}]}")));
    }

    /**
     * Stubs the HMPPS gateway route to answer a fixed status.
     *
     * @param status the HTTP status to answer
     */
    protected final void stubGateway(final int status) {
        wireMock.stubFor(post(urlPathEqualTo(GATEWAY_PATH))
                .willReturn(aResponse().withStatus(status)));
    }

    /**
     * The number of POSTs the gateway route has received.
     *
     * @return the POST count
     */
    protected final int gatewayPostCount() {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(GATEWAY_PATH))).size();
    }

    /**
     * The body of the single POST the gateway route received.
     *
     * @return the POST body as UTF-8 text
     */
    protected final String gatewayPostBody() {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(GATEWAY_PATH)))
                .get(0).getBodyAsString();
    }

    /**
     * Writes a payload under the dated cache key for a hearing.
     *
     * @param hearingId  the hearing id
     * @param hearingDay the hearing day
     * @param payload    the payload JSON text
     */
    protected final void putDatedCachePayload(final UUID hearingId, final LocalDate hearingDay,
                                              final String payload) {
        redisConnection.sync().set(
                CACHE_PREFIX + hearingId + "_" + hearingDay + "_result_", payload);
    }

    /**
     * Sends one command onto the queue with {@code messageId = RESULTS:{requestId}}.
     *
     * @param requestId the request id
     * @param body      the command body
     */
    protected final void sendCommand(final UUID requestId, final String body) {
        final ServiceBusMessage message = new ServiceBusMessage(body);
        message.setMessageId("RESULTS:" + requestId);
        sender.sendMessage(message);
    }

    /**
     * Builds a valid command body.
     *
     * @param requestId  the request id
     * @param hearingId  the hearing id
     * @param hearingDay the hearing day
     * @param sharedTime the shared time, an ISO-8601 instant
     * @return the command JSON
     */
    protected final String commandJson(final UUID requestId, final UUID hearingId,
                                       final LocalDate hearingDay, final String sharedTime) {
        return "{"
                + "\"source\":\"RESULTS\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"hearingId\":\"" + hearingId + "\","
                + "\"hearingDay\":\"" + hearingDay + "\","
                + "\"sharedTime\":\"" + sharedTime + "\","
                + "\"eventType\":\"Hearing_Resulted\""
                + "}";
    }

    /**
     * Polls for the latest HMPPS leg of a hearing until it reaches a non-{@code PENDING} status or the
     * timeout elapses.
     *
     * @param hearingId  the hearing id
     * @param hearingDay the hearing day
     * @return the latest-leg row, or empty when the timeout elapses with no settled row
     */
    protected final Optional<Map<String, Object>> awaitSettledLeg(final UUID hearingId,
                                                                  final LocalDate hearingDay) {
        final long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        Optional<Map<String, Object>> row = Optional.empty();
        while (System.nanoTime() < deadline) {
            row = findLatestLeg(hearingId, hearingDay);
            if (row.isPresent() && !"PENDING".equals(row.get().get("leg_status"))) {
                return row;
            }
            sleep();
        }
        return row;
    }

    private Optional<Map<String, Object>> findLatestLeg(final UUID hearingId,
                                                        final LocalDate hearingDay) {
        final List<Map<String, Object>> rows = jdbc().sql(
                        "SELECT pr.status AS request_status, pr.completion_reason, "
                        + "pr.failure_reason, oml.status AS leg_status, oml.reason, "
                        + "oml.http_status, oml.youth_switch, oml.payload_source, "
                        + "oml.body_digest, oml.service_version, oml.sent_at, oml.attempts, "
                        + "oml.note, ob.body "
                        + "FROM processed_request pr "
                        + "JOIN outbound_message_latest oml ON oml.source = pr.source "
                        + "AND oml.request_id = pr.request_id "
                        + "LEFT JOIN outbound_body ob ON ob.outbound_message_id = oml.id "
                        + "WHERE pr.hearing_id = :hearingId AND pr.hearing_day = :hearingDay")
                .param("hearingId", hearingId)
                .param("hearingDay", hearingDay)
                .query()
                .listOfRows();
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    private static void sleep() {
        try {
            Thread.sleep(250L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling the store", e);
        }
    }

    protected static String sha256Hex(final String value) {
        try {
            return java.util.HexFormat.of().formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                            .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
