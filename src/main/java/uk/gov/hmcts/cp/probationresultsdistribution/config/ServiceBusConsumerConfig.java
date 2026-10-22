package uk.gov.hmcts.cp.probationresultsdistribution.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway.HmppsPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ChainedHearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ResultsQueryExternalHearingClient;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestFingerprint;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.StoreGate;
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
@ConditionalOnProperty(name = "distribution.consumer.enabled", havingValue = "true")
public class ServiceBusConsumerConfig {

    private static final Logger LOG = LoggerFactory.getLogger(ServiceBusConsumerConfig.class);

    /* default */ @Bean
    MetricRegistry metricRegistry(final MeterRegistry meterRegistry) {
        return new MetricRegistry(meterRegistry);
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
            final ObjectProvider<BuildProperties> buildProperties) {
        return new OutboundMessageRepository(jdbc, transactionTemplate,
                serviceVersion(buildProperties));
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
        final RedisURI.Builder uri = RedisURI.builder()
                .withHost(host)
                .withPort(port)
                .withSsl(ssl)
                .withVerifyPeer(verifyPeer)
                .withTimeout(properties.redis().commandTimeout());
        if (!password.isBlank()) {
            uri.withPassword(password.toCharArray());
        }
        return RedisClient.create(uri.build());
    }

    /* default */ @Bean(destroyMethod = "close")
    StatefulRedisConnection<String, String> redisConnection(final RedisClient client) {
        return client.connect();
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
        return new ResultsQueryExternalHearingClient(properties.results().baseUrl(),
                properties.results().systemUserId(), properties.results().connectTimeout(),
                properties.results().readTimeout(), properties.results().maxAttempts(),
                properties.results().initialBackoff(), properties.results().maxBackoff());
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
            @Value("${distribution.usersgroups.youth-filter-key}") final String youthFilterKey) {
        return new FeaturePermissionClient(properties.usersgroups().baseUrl(),
                properties.usersgroups().systemUserId(), youthFilterKey,
                properties.usersgroups().connectTimeout(), properties.usersgroups().readTimeout());
    }

    /* default */ @Bean
    HmppsPublisher hmppsPublisher(final DistributionProperties properties,
            @Value("${distribution.gateway.subscription-key}") final String subscriptionKey) {
        return new HmppsPublisher(properties.hmpps().url(), subscriptionKey,
                properties.gateway().connectTimeout(), properties.gateway().readTimeout());
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
    ProbationMessageListener probationMessageListener(final DistributionRunner runner,
            final ProcessedRequestRepository processedRequests, final MetricRegistry metrics,
            final DistributionProperties properties,
            final ObjectProvider<ConsumerLifecycleController> lifecycle) {
        return new ProbationMessageListener(new DistributionCommandParser(),
                new IdempotencyGuard(processedRequests, new RequestFingerprint()), runner,
                processedRequests, metrics, new DeferredStoreGate(lifecycle::getObject),
                properties.servicebus().maxDeliveryCount());
    }

    /* default */ @Bean(destroyMethod = "close")
    ServiceBusProcessorClient probationProcessorClient(final ProbationMessageListener listener,
            final DistributionProperties properties,
            @Value("${distribution.servicebus.connection-string:}") final String connectionString,
            @Value("${distribution.servicebus.namespace:}") final String namespace,
            @Value("${distribution.servicebus.queue-name}") final String queueName,
            @Value("${distribution.servicebus.max-concurrent-calls}") final int maxConcurrentCalls) {
        final ServiceBusClientBuilder client = new ServiceBusClientBuilder();
        if (connectionString.isBlank()) {
            client.fullyQualifiedNamespace(namespace)
                    .credential(new DefaultAzureCredentialBuilder().build());
        } else {
            client.connectionString(connectionString);
        }
        LOG.info("Building the Service Bus consumer. queue={} maxConcurrentCalls={}", queueName,
                maxConcurrentCalls);
        return client.processor()
                .queueName(queueName)
                .disableAutoComplete()
                .maxConcurrentCalls(maxConcurrentCalls)
                .maxAutoLockRenewDuration(properties.servicebus().maxAutoLockRenewDuration())
                .processMessage(listener::processMessage)
                .processError(context ->
                        LOG.error("Service Bus processor error", context.getException()))
                .buildProcessorClient();
    }

    /* default */ @Bean
    ConsumerLifecycleController probationConsumerLifecycle(final ServiceBusProcessorClient processor,
            final ProcessedLogProbe storeProbe, final ObjectProvider<Flyway> flyway,
            final MetricRegistry metrics,
            @Value("${distribution.store.probe-interval:10s}") final String probeInterval) {
        return new ConsumerLifecycleController(processor, storeProbe, flyway::getIfAvailable, metrics,
                DurationStyle.detectAndParse(probeInterval));
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
