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
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
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
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ChainedHearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ResultsQueryExternalHearingClient;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.probationresultsdistribution.application.OutboundLog;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestFingerprint;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.StoreGate;
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
public class EndToEndTestSupport {

    protected static final String QUEUE = "probation.requests";
    protected static final String GATEWAY_PATH = "/probation/api/v1/hearing/results";
    protected static final String SUBSCRIPTION_KEY = "test-subscription-key";
    protected static final String SYSTEM_USER_ID = "00000000-0000-0000-0000-000000000000";
    protected static final String CACHE_PREFIX = "EXT_";
    protected static final Duration POLL_TIMEOUT = Duration.ofSeconds(90);
    /** Mirrors the committed emulator queue's {@code maxDeliveryCount}. */
    protected static final int MAX_DELIVERY_COUNT = 5;

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
    private boolean processorClosed;
    private MeterRegistry meters = new SimpleMeterRegistry();
    private MetricRegistry sharedMetricRegistry;

    /** Starts the shared container stack once for the JVM on first subclass construction. */
    protected EndToEndTestSupport() {
        startOnce();
    }

    /**
     * Starts the shared container stack once for the JVM, for a context test that does not extend this
     * base but reuses its Postgres, Redis and Service Bus emulator.
     */
    public static void startSharedContainers() {
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
        stopProcessorNow();
        wireMock.resetAll();
    }

    /** Stops the active broker processor, allowing a test to inspect one settled delivery. */
    protected final void stopProcessorNow() {
        if (processor != null && !processorClosed) {
            processor.close();
            processorClosed = true;
        }
    }

    /**
     * Starts the running processor for one test, consuming {@code probation.requests} with peek-lock
     * and auto-complete disabled, dispatching to the production listener. Subclasses call this after
     * configuring their WireMock and cache state.
     */
    protected final void startProcessor() {
        meters = new SimpleMeterRegistry();
        processorClosed = false;
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

    /** Starts the production pipeline with a selected youth-switch default and short test timeouts. */
    protected final void startProcessorWithYouthDefault(
            final YouthSwitchRecordedValue defaultWhenUnreadable) {
        meters = new SimpleMeterRegistry();
        processorClosed = false;
        processor = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS.getConnectionString())
                .processor()
                .queueName(QUEUE)
                .disableAutoComplete()
                .maxConcurrentCalls(1)
                .processMessage(listener(defaultWhenUnreadable, Duration.ofMillis(200),
                        Duration.ofMillis(100), Duration.ofMillis(10))::processMessage)
                .processError(context -> LOG.error("processor error", context.getException()))
                .buildProcessorClient();
        processor.start();
    }

    /**
     * Starts a peek-lock processor dispatching to a caller-supplied listener, for suites that need a
     * listener wired with test doubles (a throwing or stale runner, a custom delivery budget). The
     * processor is tracked so {@code stopProcessor} closes it after the test.
     *
     * @param custom             the listener each delivery is handed to
     * @param maxConcurrentCalls the processor concurrency
     */
    protected final void startProcessorWith(final ProbationMessageListener custom,
                                            final int maxConcurrentCalls) {
        processorClosed = false;
        processor = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS.getConnectionString())
                .processor()
                .queueName(QUEUE)
                .disableAutoComplete()
                .maxConcurrentCalls(maxConcurrentCalls)
                .processMessage(custom::processMessage)
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
        return listener(YouthSwitchRecordedValue.ACTIVE, Duration.ofSeconds(2), Duration.ofSeconds(5),
                Duration.ofSeconds(1));
    }

    private ProbationMessageListener listener(final YouthSwitchRecordedValue defaultWhenUnreadable,
                                              final Duration connectTimeout,
                                              final Duration readTimeout,
                                              final Duration retryWait) {
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        final ProcessedRequestRepository processedRequests =
                new JdbcProcessedRequestRepository(jdbc(), tx, Duration.ofMinutes(4).plusSeconds(30));
        final OutboundMessageRepository outboundMessages =
                new OutboundMessageRepository(jdbc(), tx, "test-version");
        final OutboundLogAdapter outboundLog = new OutboundLogAdapter(outboundMessages);
        final ChainedHearingPayloadSource payload = chainedPayloadSource();
        final MetricRegistry registry = new MetricRegistry(meters);
        final FeaturePermissionClient youthFilter = new FeaturePermissionClient(
                usersGroupsBaseUrl(), SYSTEM_USER_ID, "EMYouthFilter",
                connectTimeout, readTimeout, 3, retryWait, registry);
        final HmppsPublisher publisher = new HmppsPublisher(
                gatewayUrl(), SUBSCRIPTION_KEY, Duration.ofSeconds(5), Duration.ofSeconds(60),
                registry);
        final DistributionRunner runner = new DistributionRunner(payload, youthFilter, publisher,
                outboundLog, defaultWhenUnreadable, registry);
        return new ProbationMessageListener(new DistributionCommandParser(),
                new IdempotencyGuard(processedRequests, new RequestFingerprint()), runner,
                processedRequests, registry, alwaysAvailableStore(), MAX_DELIVERY_COUNT);
    }

    /**
     * The payload-fetch chain over the shared Redis cache and a results-query client pointed at the
     * shared WireMock. For the US1/US2 suites the cache always hits, so the query is never called; the
     * US3 suites drive the cache-miss, undated-fallback and query branches.
     *
     * @return the chained payload source
     */
    private ChainedHearingPayloadSource chainedPayloadSource() {
        final CachedHearingPayloadAdapter cache =
                new CachedHearingPayloadAdapter(redisConnection, CACHE_PREFIX);
        final ResultsQueryExternalHearingClient query = new ResultsQueryExternalHearingClient(
                wireMock.baseUrl(), SYSTEM_USER_ID, Duration.ofSeconds(5), Duration.ofSeconds(30),
                4, Duration.ofMillis(500), Duration.ofSeconds(20));
        return new ChainedHearingPayloadSource(cache, query, Duration.ofSeconds(60));
    }

    /**
     * A store gate that is always available and never suspends, for the happy-path suites whose store
     * is up for the whole test. The database-outage suite wires a real {@code ConsumerLifecycleController}
     * instead.
     *
     * @return an always-available store gate
     */
    protected static StoreGate alwaysAvailableStore() {
        return new StoreGate() {
            @Override
            public boolean storeAvailable() {
                return true;
            }

            @Override
            public void suspendIntake() {
                // nothing to suspend in the happy-path suites
            }
        };
    }

    /**
     * Builds the production listener over a caller-supplied store and store gate, reusing the shared
     * Redis, users-groups and gateway stubs. For the database-outage suite, which points the processed
     * log at a container it can pause while the broker and downstreams stay up.
     *
     * @param store            the processed-log data source
     * @param gate             the store gate the listener consults before reading a body
     * @param maxDeliveryCount the delivery budget the listener recognises the final delivery from
     * @return the listener under test
     */
    protected final ProbationMessageListener listenerOver(final DataSource store, final StoreGate gate,
                                                          final int maxDeliveryCount) {
        return listenerOver(store, gate, maxDeliveryCount, Duration.ofSeconds(5),
                Duration.ofSeconds(60));
    }

    /** Builds the production listener with explicit HMPPS timeouts for HTTP fault tests. */
    protected final ProbationMessageListener listenerOver(final DataSource store, final StoreGate gate,
            final int maxDeliveryCount, final Duration gatewayConnectTimeout,
            final Duration gatewayReadTimeout) {
        meters = new SimpleMeterRegistry();
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(store));
        final ProcessedRequestRepository processedRequests = new JdbcProcessedRequestRepository(
                JdbcClient.create(store), tx, Duration.ofMinutes(4).plusSeconds(30));
        final OutboundMessageRepository outboundMessages =
                new OutboundMessageRepository(JdbcClient.create(store), tx, "test-version");
        final OutboundLogAdapter outboundLog = new OutboundLogAdapter(outboundMessages);
        final ChainedHearingPayloadSource payload = chainedPayloadSource();
        final MetricRegistry registry = new MetricRegistry(meters);
        final FeaturePermissionClient youthFilter = new FeaturePermissionClient(
                usersGroupsBaseUrl(), SYSTEM_USER_ID, "EMYouthFilter",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 3, Duration.ofSeconds(1), registry);
        final HmppsPublisher publisher = new HmppsPublisher(
                gatewayUrl(), SUBSCRIPTION_KEY, gatewayConnectTimeout, gatewayReadTimeout, registry);
        sharedMetricRegistry = registry;
        final DistributionRunner runner = new DistributionRunner(payload, youthFilter, publisher,
                outboundLog, YouthSwitchRecordedValue.ACTIVE, registry);
        return new ProbationMessageListener(new DistributionCommandParser(),
                new IdempotencyGuard(processedRequests, new RequestFingerprint()), runner,
                processedRequests, registry, gate, maxDeliveryCount);
    }

    /** Builds the production listener with a caller-supplied outbound log for write-failure tests. */
    protected final ProbationMessageListener listenerOverWithOutboundLog(final DataSource store,
            final StoreGate gate, final int maxDeliveryCount, final OutboundLog outboundLog) {
        meters = new SimpleMeterRegistry();
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(store));
        final ProcessedRequestRepository processedRequests = new JdbcProcessedRequestRepository(
                JdbcClient.create(store), tx, Duration.ofMinutes(4).plusSeconds(30));
        final ChainedHearingPayloadSource payload = chainedPayloadSource();
        final MetricRegistry registry = new MetricRegistry(meters);
        final FeaturePermissionClient youthFilter = new FeaturePermissionClient(
                usersGroupsBaseUrl(), SYSTEM_USER_ID, "EMYouthFilter",
                Duration.ofSeconds(2), Duration.ofSeconds(5), 3, Duration.ofSeconds(1), registry);
        final HmppsPublisher publisher = new HmppsPublisher(
                gatewayUrl(), SUBSCRIPTION_KEY, Duration.ofSeconds(5), Duration.ofSeconds(60),
                registry);
        sharedMetricRegistry = registry;
        final DistributionRunner runner = new DistributionRunner(payload, youthFilter, publisher,
                outboundLog, YouthSwitchRecordedValue.ACTIVE, registry);
        return new ProbationMessageListener(new DistributionCommandParser(),
                new IdempotencyGuard(processedRequests, new RequestFingerprint()), runner,
                processedRequests, registry, gate, maxDeliveryCount);
    }

    /**
     * The {@link MetricRegistry} the most recent {@link #listenerOver} built, so a caller can wire its
     * own lifecycle controller onto the same instrument surface the listener records into.
     *
     * @return the shared metric registry
     */
    protected final MetricRegistry sharedMetrics() {
        return sharedMetricRegistry;
    }

    /**
     * Builds a peek-lock processor over the shared emulator, not started, for a caller that owns its
     * lifecycle (the consumer lifecycle controller in the outage suite).
     *
     * @param custom             the listener each delivery is handed to
     * @param maxConcurrentCalls the processor concurrency
     * @return the built, unstarted processor client
     */
    protected final ServiceBusProcessorClient buildProcessor(final ProbationMessageListener custom,
                                                             final int maxConcurrentCalls) {
        return new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS.getConnectionString())
                .processor()
                .queueName(QUEUE)
                .disableAutoComplete()
                .maxConcurrentCalls(maxConcurrentCalls)
                .processMessage(custom::processMessage)
                .processError(context -> LOG.error("processor error", context.getException()))
                .buildProcessorClient();
    }

    /**
     * A JDBC client over the shared Postgres container.
     *
     * @return the JDBC client
     */
    protected final JdbcClient jdbc() {
        return JdbcClient.create(dataSource);
    }

    /**
     * The shared Postgres data source, for a context that wires its own beans over it.
     *
     * @return the shared data source
     */
    public static DataSource sharedDataSource() {
        return dataSource;
    }

    /**
     * The shared Redis container host.
     *
     * @return the Redis host
     */
    public static String redisHost() {
        return REDIS.getHost();
    }

    /**
     * The shared Redis container's mapped port.
     *
     * @return the mapped Redis port
     */
    public static int redisPort() {
        return REDIS.getMappedPort(REDIS_PORT);
    }

    /**
     * The shared Service Bus emulator's development connection string.
     *
     * @return the emulator connection string
     */
    public static String serviceBusConnectionString() {
        return SERVICE_BUS.getConnectionString();
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

    /**
     * The shared Redis connection the payload cache adapter reads through.
     *
     * @return the shared Redis connection
     */
    protected final StatefulRedisConnection<String, String> sharedRedisConnection() {
        return redisConnection;
    }

    /**
     * The number of users-groups permission reads (youth-switch reads) WireMock has received.
     *
     * @return the read count
     */
    protected final int usersGroupsReadCount() {
        return wireMock.findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                urlPathEqualTo("/usersgroups-query-api/query/api/rest/usersgroups/permissions")))
                .size();
    }

    /**
     * The {@code CJSCPPUID} header of every results query-API read made for a hearing.
     *
     * @param hearingId the hearing id in the path
     * @return each read's header value, in request order
     */
    protected final List<String> resultsQueryUserHeaders(final UUID hearingId) {
        return wireMock.findAll(com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor(
                        urlPathEqualTo("/results-query-api/query/api/rest/results/hearingDetails/"
                                + hearingId)))
                .stream().map(request -> request.getHeader("CJSCPPUID")).toList();
    }

    /**
     * The WireMock base URL the users-groups client reads from.
     *
     * @return the users-groups base URL
     */
    protected final String usersGroupsBaseUrl() {
        return wireMock.baseUrl();
    }

    /**
     * The WireMock URL the HMPPS gateway POSTs to.
     *
     * @return the gateway route URL
     */
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

    /** Stubs the youth-switch route with a controlled response for unreadable-switch scenarios. */
    protected final void stubYouthSwitch(final int status, final String body, final int delayMillis) {
        wireMock.stubFor(get(urlPathEqualTo(
                "/usersgroups-query-api/query/api/rest/usersgroups/permissions"))
                .willReturn(aResponse().withStatus(status).withFixedDelay(delayMillis)
                        .withHeader("Content-Type", "application/json").withBody(body)));
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

    /** Stubs a gateway response after a fixed delay to exercise the production read timeout. */
    protected final void stubGatewayWithDelay(final int status, final int delayMillis) {
        wireMock.stubFor(post(urlPathEqualTo(GATEWAY_PATH))
                .willReturn(aResponse().withStatus(status).withFixedDelay(delayMillis)));
    }

    /** Stubs one gateway response followed by a recovered response on the next POST. */
    protected final void stubGatewaySequence(final int firstStatus, final String retryAfter,
                                             final int recoveredStatus) {
        final String scenario = "gateway-recovery-" + UUID.randomUUID();
        wireMock.stubFor(post(urlPathEqualTo(GATEWAY_PATH)).inScenario(scenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(firstStatus)
                        .withHeader("Retry-After", retryAfter))
                .willSetStateTo("recovered"));
        wireMock.stubFor(post(urlPathEqualTo(GATEWAY_PATH)).inScenario(scenario)
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(recoveredStatus)));
    }

    /**
     * The number of POSTs the gateway route has received.
     *
     * @return the POST count
     */
    protected final int gatewayPostCount() {
        return wireMock.findAll(postRequestedFor(urlPathEqualTo(GATEWAY_PATH))).size();
    }

    /** Number of exact bodies retained for a request's latest HMPPS attempt. */
    protected final int outboundBodyCount(final UUID requestId) {
        return jdbc().sql("SELECT count(*) FROM outbound_body ob "
                        + "JOIN outbound_message om ON om.id = ob.outbound_message_id "
                        + "WHERE om.request_id = :requestId")
                .param("requestId", requestId)
                .query(Integer.class)
                .single();
    }

    /** Seeds a parked gateway-transient attempt with its previously sent body and digest. */
    protected final void seedParkedGatewayTransientLeg(final DistributionCommand command,
                                                       final String body) {
        seedRequest(command, "RETRYING", false);
        final UUID messageId = UUID.randomUUID();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, http_status, body_digest, "
                        + "payload_source, service_version, youth_switch) VALUES (:id, :source, "
                        + ":requestId, 'HMPPS', 1, 'queue', 'PARKED', 'gateway-transient', 1, "
                        + "503, :digest, 'cache-day', 'test-version', 'ACTIVE')")
                .param("id", messageId)
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param("digest", sha256Hex(body))
                .update();
        jdbc().sql("INSERT INTO outbound_body (outbound_message_id, body) VALUES (:id, :body)")
                .param("id", messageId)
                .param("body", body)
                .update();
    }

    /** Seeds a RETRYING request and PENDING attempt-1 row with an expired/absent claim. */
    protected final void seedPendingAttemptOne(final DistributionCommand command) {
        seedRequest(command, "RETRYING", false);
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts) VALUES (:id, :source, :requestId, "
                        + "'HMPPS', 1, 'queue', 'PENDING', 0)")
                .param("id", UUID.randomUUID())
                .param("source", command.source())
                .param("requestId", command.requestId())
                .update();
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
     * Writes a payload under the undated cache key for a hearing.
     *
     * @param hearingId the hearing id
     * @param payload   the payload JSON text
     */
    protected final void putUndatedCachePayload(final UUID hearingId, final String payload) {
        redisConnection.sync().set(CACHE_PREFIX + hearingId + "_result_", payload);
    }

    /**
     * Stubs the results query-API hearing-details route to answer a fixed status and body.
     *
     * @param hearingId the hearing id in the path
     * @param status    the HTTP status to answer
     * @param body      the response body
     */
    protected final void stubResultsQuery(final UUID hearingId, final int status, final String body) {
        wireMock.stubFor(get(urlPathEqualTo(
                "/results-query-api/query/api/rest/results/hearingDetails/" + hearingId))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("Content-Type", "application/json").withBody(body)));
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

    /** Waits until the latest leg reaches the requested persisted status. */
    protected final Optional<Map<String, Object>> awaitLegStatus(final UUID hearingId,
            final LocalDate hearingDay, final String status) {
        final long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        Optional<Map<String, Object>> row = Optional.empty();
        while (System.nanoTime() < deadline) {
            row = findLatestLeg(hearingId, hearingDay);
            if (row.map(value -> status.equals(value.get("leg_status"))).orElse(false)) {
                return row;
            }
            sleep();
        }
        return row.filter(value -> status.equals(value.get("leg_status")));
    }

    /** Waits until the processed request reaches the requested persisted status. */
    protected final void awaitRequestStatus(final UUID requestId, final String status) {
        final long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        while (System.nanoTime() < deadline) {
            if (requestStatus(requestId).filter(status::equals).isPresent()) {
                return;
            }
            sleep();
        }
        throw new IllegalStateException("request did not reach status " + status);
    }

    private static void sleep() {
        try {
            Thread.sleep(250L);
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while polling the store", e);
        }
    }

    /**
     * Polls the dead-letter sub-queue for a message with the given {@code messageId} until it appears
     * or the timeout elapses. Peeks only, so the message is not removed and parallel assertions see a
     * stable view.
     *
     * @param messageId the message id to find
     * @return the dead-lettered message, or empty when the timeout elapses with none
     */
    protected final Optional<ServiceBusReceivedMessage> awaitDeadLetter(final String messageId) {
        final long deadline = System.nanoTime() + POLL_TIMEOUT.toNanos();
        try (ServiceBusReceiverClient dlq = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS.getConnectionString())
                .receiver()
                .queueName(QUEUE)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .buildClient()) {
            while (System.nanoTime() < deadline) {
                final Optional<ServiceBusReceivedMessage> found = scanDeadLetter(dlq, messageId);
                if (found.isPresent()) {
                    return found;
                }
                sleep();
            }
        }
        return Optional.empty();
    }

    /**
     * Peeks the dead-letter sub-queue once for a message id, without waiting. For asserting that a
     * message is <em>not</em> on the dead-letter queue.
     *
     * @param messageId the message id to look for
     * @return the dead-lettered message if already present, otherwise empty
     */
    protected final Optional<ServiceBusReceivedMessage> peekDeadLetterNow(final String messageId) {
        try (ServiceBusReceiverClient dlq = new ServiceBusClientBuilder()
                .connectionString(SERVICE_BUS.getConnectionString())
                .receiver()
                .queueName(QUEUE)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE)
                .buildClient()) {
            return scanDeadLetter(dlq, messageId);
        }
    }

    /**
     * Pages through the whole dead-letter sub-queue. The queue is shared by every suite in the JVM and
     * never drained, so a single peek from sequence 0 misses anything past its first page.
     */
    private static Optional<ServiceBusReceivedMessage> scanDeadLetter(
            final ServiceBusReceiverClient dlq, final String messageId) {
        long sequence = 0L;
        List<ServiceBusReceivedMessage> page = dlq.peekMessages(100, sequence).stream().toList();
        while (!page.isEmpty()) {
            for (final ServiceBusReceivedMessage message : page) {
                if (messageId.equals(message.getMessageId())) {
                    return Optional.of(message);
                }
                sequence = message.getSequenceNumber() + 1;
            }
            page = dlq.peekMessages(100, sequence).stream().toList();
        }
        return Optional.empty();
    }

    /**
     * The number of {@code processed_request} rows for a request id.
     *
     * @param requestId the request id
     * @return the row count
     */
    protected final int processedRequestCount(final UUID requestId) {
        return jdbc().sql("SELECT count(*) FROM processed_request WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(Integer.class)
                .single();
    }

    /**
     * The status of the one {@code processed_request} row for a request id, or empty when none exists.
     *
     * @param requestId the request id
     * @return the request status
     */
    protected final Optional<String> requestStatus(final UUID requestId) {
        return jdbc().sql("SELECT status FROM processed_request WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(String.class)
                .optional();
    }

    /**
     * Seeds a {@code processed_request} row and its attempt-1 {@code PENDING} leg for a command,
     * using the real request fingerprint so the guard treats a later copy as the same key.
     *
     * @param command   the command whose identity and fingerprint are seeded
     * @param status    the request status to seed
     * @param claimLive whether to seed a live (far-future) claim
     */
    protected final void seedRequest(final DistributionCommand command, final String status,
                                     final boolean claimLive) {
        final String fingerprint = new RequestFingerprint().compute(command);
        final boolean failed = "FAILED".equals(status);
        final String failureReason = failed ? "leg-exhausted" : null;
        final String exhaustedMessageId = failed ? "RESULTS:" + command.requestId() : null;
        final String claimClause = claimLive
                ? "gen_random_uuid(), gen_random_uuid(), now() + interval '1 hour'"
                : "NULL, NULL, NULL";
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "failure_reason, exhausted_message_id, "
                        + "claim_owner, claim_token, claim_expires_at) VALUES (:source, :requestId, "
                        + ":hearingId, :hearingDay, :sharedTime, :eventType, :fingerprint, :status, 1, "
                        + "'{}', :failureReason, :exhaustedMessageId, " + claimClause + ")")
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param("hearingId", command.hearingId())
                .param("hearingDay", command.hearingDay())
                .param("sharedTime",
                        java.time.OffsetDateTime.ofInstant(command.sharedTime(),
                                java.time.ZoneOffset.UTC))
                .param("eventType", command.eventType())
                .param("fingerprint", fingerprint)
                .param("status", status)
                .param("failureReason", failureReason)
                .param("exhaustedMessageId", exhaustedMessageId)
                .update();
    }

    /**
     * Seeds a {@code RETRYING} request and its attempt-1 {@code PARKED(payload-unavailable)} leg, with
     * no claim held, as a delivery leaves them after a payload-unavailable park. A later delivery
     * reclaims the request and re-runs the same attempt-1 row in place.
     *
     * @param command the command whose identity and fingerprint are seeded
     */
    protected final void seedParkedPayloadUnavailableLeg(final DistributionCommand command) {
        final String fingerprint = new RequestFingerprint().compute(command);
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command) "
                        + "VALUES (:source, :requestId, :hearingId, :hearingDay, :sharedTime, "
                        + ":eventType, :fingerprint, 'RETRYING', 1, '{}')")
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param("hearingId", command.hearingId())
                .param("hearingDay", command.hearingDay())
                .param("sharedTime",
                        java.time.OffsetDateTime.ofInstant(command.sharedTime(),
                                java.time.ZoneOffset.UTC))
                .param("eventType", command.eventType())
                .param("fingerprint", fingerprint)
                .update();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts) VALUES (:id, :source, "
                        + ":requestId, 'HMPPS', 1, 'queue', 'PARKED', 'payload-unavailable', 1)")
                .param("id", UUID.randomUUID())
                .param("source", command.source())
                .param("requestId", command.requestId())
                .update();
    }

    /**
     * Seeds a {@code processed_request} row whose fingerprint deliberately differs from any command's,
     * so a later copy of the same key is a collision.
     *
     * @param source    the command source
     * @param requestId the request id
     */
    protected final void seedCollisionRow(final String source, final UUID requestId) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command) "
                        + "VALUES (:source, :requestId, gen_random_uuid(), DATE '2026-08-19', "
                        + "TIMESTAMPTZ '2026-08-19T16:42:07.512Z', 'Hearing_Resulted', "
                        + "'a-different-fingerprint', 'RECEIVED', 1, '{}')")
                .param("source", source)
                .param("requestId", requestId)
                .update();
    }

    /**
     * The lowercase hex SHA-256 of a UTF-8 string, matching the stored body digest.
     *
     * @param value the value to digest
     * @return the hex digest
     */
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
