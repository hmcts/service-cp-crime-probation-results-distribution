package uk.gov.hmcts.cp.probationresultsdistribution.config;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway.HmppsPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ChainedHearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ResultsQueryExternalHearingClient;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus.ServiceBusCommandEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus.ServiceBusReplayEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestFingerprint;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.StoreGate;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcIntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedLogProbe;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * The intake composition root (design §4.2, §4.4). Gated on {@code distribution.consumer.enabled}, so
 * the {@code test} profile — which turns intake off and excludes the datasource — refreshes a context
 * that reaches for no broker, store or cache. When enabled it wires the pipeline over the owned ports
 * and registers a peek-lock {@link ServiceBusProcessorClient} with auto-complete disabled, dispatching
 * every delivery to {@link ProbationMessageListener}, which performs exactly one explicit settlement.
 *
 * <p>Broker health is deliberately never a readiness input: the processor is a {@link SmartLifecycle}
 * the container starts and stops, and a broker blip never rolls the pod.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "distribution.consumer.enabled", havingValue = "true")
public class ServiceBusConsumerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusConsumerConfig.class);

    /* default */ @Bean
    ServiceBusHealthIndicator probationServiceBusHealthIndicator(final MeterRegistry meterRegistry,
            @Value("${distribution.servicebus.health-staleness:60s}") final String staleness) {
        return new ServiceBusHealthIndicator(DurationStyle.detectAndParse(staleness), meterRegistry);
    }

    /* default */ @Bean
    AgeGaugeRefresher ageGaugeRefresher(final JdbcClient jdbc, final MetricRegistry metrics) {
        return new AgeGaugeRefresher(jdbc, metrics);
    }

    /**
     * Overrides Boot's default of migrating {@link Flyway} eagerly, inside {@code
     * FlywayMigrationInitializer}, during context refresh. Left at that default, a pod cannot start
     * while the database is down (review of PR #8): {@code FlywayAutoConfiguration} forces a
     * connection before refresh ever completes, even though the datasource pool itself initialises
     * lazily ({@code spring.datasource.hikari.initialization-fail-timeout: -1}) for exactly the
     * opposite reason. With this no-op in place the {@code Flyway} bean is still built from the
     * classpath migrations, but nothing connects, validates or applies them until
     * {@link uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController
     * #migrateOnce()} runs it, on the first successful store probe, before intake starts (review of
     * PR #8). Until that first probe succeeds — most visibly against a brand-new, unmigrated
     * database — anything else that queries the schema (the 30-second {@link AgeGaugeRefresher},
     * an operations API call) fails and logs its own error; that is expected noise during the
     * window before the first successful probe, not a regression, and clears once migration runs.
     */
    /* default */ @Bean
    FlywayMigrationStrategy deferredFlywayMigrationStrategy() {
        return flyway -> LOG.info("Flyway migration deferred to the first successful store probe; "
                + "not running it during context refresh");
    }

    /* default */ @Bean
    TransactionTemplate distributionTransactionTemplate(
            final PlatformTransactionManager transactionManager) {
        return new TransactionTemplate(transactionManager);
    }

    /* default */ @Bean
    ProcessedRequestRepository processedRequestRepository(final JdbcClient jdbc,
            final TransactionTemplate transactionTemplate, final DistributionProperties properties) {
        return new JdbcProcessedRequestRepository(jdbc, transactionTemplate,
                properties.claim().lease());
    }

    /* default */ @Bean
    OutboundMessageRepository outboundMessageRepository(final JdbcClient jdbc,
            final TransactionTemplate transactionTemplate,
            final ObjectProvider<BuildProperties> buildProperties,
            final ProcessedRequestRepository processedRequests) {
        return new OutboundMessageRepository(jdbc, transactionTemplate,
                serviceVersion(buildProperties), processedRequests);
    }

    /* default */ @Bean
    OutboundLogAdapter outboundLogAdapter(final OutboundMessageRepository outboundMessages) {
        return new OutboundLogAdapter(outboundMessages);
    }

    /* default */ @Bean(destroyMethod = "shutdown")
    RedisClient redisClient(
            @Value("${distribution.redis.host}") final String host,
            @Value("${distribution.redis.port}") final int port,
            @Value("${distribution.redis.ssl}") final boolean ssl,
            @Value("${distribution.redis.verify-peer}") final boolean verifyPeer,
            @Value("${distribution.redis.key:}") final String password,
            final DistributionProperties properties) {
        return PipelineClients.redisClient(host, port, ssl, verifyPeer, password, properties);
    }

    /* default */ @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> redisConnection(final RedisClient client) {
        // Connects lazily, on the first real call, so a cache outage never fails context refresh
        // (FR-002-016): client.connect() here would block bean creation on an unreachable Redis.
        return LazyRedisConnection.over(client);
    }

    /* default */ @Bean
    CachedHearingPayloadAdapter cachedHearingPayloadAdapter(
            final StatefulRedisConnection<String, String> connection,
            @Value("${distribution.redis.key-prefix}") final String keyPrefix) {
        return new CachedHearingPayloadAdapter(connection, keyPrefix);
    }

    /* default */ @Bean
    ResultsQueryExternalHearingClient resultsQueryExternalHearingClient(
            final DistributionProperties properties) {
        return PipelineClients.resultsQuery(properties);
    }

    /* default */ @Bean
    ChainedHearingPayloadSource chainedHearingPayloadSource(
            final CachedHearingPayloadAdapter cache,
            final ResultsQueryExternalHearingClient query,
            final DistributionProperties properties) {
        return new ChainedHearingPayloadSource(cache, query, properties.budget().payload());
    }

    /* default */ @Bean
    FeaturePermissionClient featurePermissionClient(final DistributionProperties properties,
            @Value("${distribution.usersgroups.youth-filter-key}") final String youthFilterKey,
            final MetricRegistry metrics) {
        return PipelineClients.youthFilter(properties, youthFilterKey, metrics);
    }

    /* default */ @Bean
    HmppsPublisher hmppsPublisher(final DistributionProperties properties,
            @Value("${distribution.gateway.subscription-key}") final String subscriptionKey,
            final MetricRegistry metrics) {
        return PipelineClients.hmppsPublisher(properties, subscriptionKey, metrics);
    }

    /* default */ @Bean
    DistributionRunner distributionRunner(final ChainedHearingPayloadSource payload,
            final FeaturePermissionClient youthFilter, final HmppsPublisher publisher,
            final OutboundLogAdapter outboundLog, final DistributionProperties properties,
            final MetricRegistry metrics) {
        final YouthSwitchRecordedValue defaultWhenUnreadable = YouthSwitchRecordedValue.fromValue(
                properties.youthSwitch().defaultWhenUnreadable());
        return new DistributionRunner(payload, youthFilter, publisher, outboundLog,
                defaultWhenUnreadable, metrics);
    }

    /* default */ @Bean
    ProcessedLogProbe processedLogProbe(final JdbcClient jdbc) {
        return new ProcessedLogProbe(jdbc);
    }

    /* default */ @Bean
    IntakeControlRepository intakeControlRepository(final JdbcClient jdbc) {
        return new JdbcIntakeControlRepository(jdbc);
    }

    /* default */ @Bean
    ProbationMessageListener probationMessageListener(final DistributionRunner runner,
            final ProcessedRequestRepository processedRequests, final MetricRegistry metrics,
            final DistributionProperties properties,
            final ObjectProvider<ConsumerLifecycleController> lifecycle) {
        return new ProbationMessageListener(new DistributionCommandParser(),
                new IdempotencyGuard(processedRequests, new RequestFingerprint()), runner,
                processedRequests, metrics, new DeferredStoreGate(lifecycle::getObject),
                properties.servicebus().maxDeliveryCount());
    }

    /**
     * The number of deliveries {@link #probationProcessorClient} is currently running. Shared with
     * {@link ConsumerLifecycleController}, whose {@code stop()} waits for this to reach zero before
     * returning: the Service Bus SDK's own {@code ServiceBusProcessorClient.stop()} does not wait for
     * an in-flight delivery to finish, so without this a rolling deploy could kill a run mid-POST
     * (review of PR #8).
     */
    /* default */ @Bean
    AtomicInteger inFlightRunCounter() {
        return new AtomicInteger();
    }

    /* default */ @Bean(destroyMethod = "close")
    ServiceBusProcessorClient probationProcessorClient(final ProbationMessageListener listener,
            final ServiceBusHealthIndicator brokerHealth,
            final DistributionProperties properties,
            final AtomicInteger inFlightRunCounter,
            @Value("${distribution.servicebus.connection-string:}") final String connectionString,
            @Value("${distribution.servicebus.namespace:}") final String namespace,
            @Value("${distribution.servicebus.queue-name}") final String queueName,
            @Value("${distribution.servicebus.max-concurrent-calls}") final int maxConcurrentCalls) {
        final ServiceBusClientBuilder client = PipelineClients.serviceBus(connectionString,
                namespace);
        LOG.info("Building the Service Bus consumer. queue={} maxConcurrentCalls={}", queueName,
                maxConcurrentCalls);
        return client.processor()
                .queueName(queueName)
                .disableAutoComplete()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxAutoLockRenewDuration(properties.servicebus().maxAutoLockRenewDuration())
                .processMessage(context -> {
                    brokerHealth.recordTraffic();
                    inFlightRunCounter.incrementAndGet();
                    try {
                        listener.processMessage(context);
                    } finally {
                        inFlightRunCounter.decrementAndGet();
                    }
                })
                .processError(context -> {
                    if (!ServiceBusErrorSource.USER_CALLBACK.equals(context.getErrorSource())) {
                        brokerHealth.recordError();
                    }
                    LOG.error("Service Bus processor error", context.getException());
                })
                .buildProcessorClient();
    }

    /**
     * The one sender on {@code probation.requests} the serving context holds, for the replay
     * action's enqueue (design §4.8 action 2; H9), with the consumer's credential rule; closed with
     * the context. Building it opens no link: the first send does.
     *
     * @param connectionString the emulator's connection string, local and CI only
     * @param namespace        the namespace, for workload identity
     * @param queueName        the queue
     * @return the sender
     */
    /* default */ @Bean(destroyMethod = "close")
    ServiceBusSenderClient probationRequestsSender(
            @Value("${distribution.servicebus.connection-string:}") final String connectionString,
            @Value("${distribution.servicebus.namespace:}") final String namespace,
            @Value("${distribution.servicebus.queue-name}") final String queueName) {
        return PipelineClients.serviceBus(connectionString, namespace).sender()
                .queueName(queueName)
                .buildClient();
    }

    /* default */ @Bean
    ReplayEnqueuer replayEnqueuer(final ServiceBusSenderClient probationRequestsSender) {
        return new ServiceBusReplayEnqueuer(new ServiceBusCommandEnqueuer(probationRequestsSender));
    }

    /* default */ @Bean
    ConsumerLifecycleController probationConsumerLifecycle(final ServiceBusProcessorClient processor,
            final ProcessedLogProbe storeProbe, final IntakeControlRepository intakeControl,
            final ObjectProvider<Flyway> flyway, final MetricRegistry metrics,
            final AtomicInteger inFlightRunCounter, final DistributionProperties properties,
            @Value("${distribution.store.probe-interval:10s}") final String probeInterval) {
        // At least the enforced bound on a run (design §4.7; processingDeadline), so a run already
        // under way when shutdown starts is given the time it is allowed to take.
        return new ConsumerLifecycleController(processor, storeProbe, intakeControl,
                flyway::getIfAvailable, metrics, DurationStyle.detectAndParse(probeInterval),
                inFlightRunCounter, properties.claim().processingDeadline());
    }

    /* default */ @Bean
    IntakeMigrationHealthIndicator intakeMigrationHealthIndicator(
            final ConsumerLifecycleController consumer) {
        return new IntakeMigrationHealthIndicator(consumer::migrationCompleted);
    }

    private static String serviceVersion(final ObjectProvider<BuildProperties> buildProperties) {
        final BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "unknown" : build.getVersion();
    }

    /**
     * The store gate, resolved when a delivery uses it rather than when the listener is built. There is
     * a genuine cycle: the controller owns the processor, the processor is built around the listener,
     * and the listener must be able to ask the controller to suspend intake. The gate is the honest
     * place to be late — it is only ever needed while a delivery is being handled, by which time every
     * bean in the cycle exists.
     *
     * @param lifecycle the lazily resolved lifecycle controller
     */
    private record DeferredStoreGate(Supplier<ConsumerLifecycleController> lifecycle)
            implements StoreGate {

        @Override
        public boolean storeAvailable() {
            return lifecycle.get().storeAvailable();
        }

        @Override
        public void suspendIntake() {
            lifecycle.get().suspendIntake();
        }
    }
}
