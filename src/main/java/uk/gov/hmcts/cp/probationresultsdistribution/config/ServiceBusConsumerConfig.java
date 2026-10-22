package uk.gov.hmcts.cp.probationresultsdistribution.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.SmartLifecycle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway.HmppsPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IdempotencyGuard;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestFingerprint;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.DistributionCommandParser;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;
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
    DistributionRunner distributionRunner(final CachedHearingPayloadAdapter payload,
            final FeaturePermissionClient youthFilter, final HmppsPublisher publisher,
            final OutboundLogAdapter outboundLog, final DistributionProperties properties,
            final MetricRegistry metrics) {
        final YouthSwitchRecordedValue defaultWhenUnreadable = YouthSwitchRecordedValue.fromValue(
                properties.youthSwitch().defaultWhenUnreadable());
        return new DistributionRunner(payload, youthFilter, publisher, outboundLog,
                defaultWhenUnreadable, metrics);
    }

    /* default */ @Bean
    ProbationMessageListener probationMessageListener(final DistributionRunner runner,
            final ProcessedRequestRepository processedRequests, final MetricRegistry metrics) {
        return new ProbationMessageListener(new DistributionCommandParser(),
                new IdempotencyGuard(processedRequests, new RequestFingerprint()), runner,
                processedRequests, metrics);
    }

    /* default */ @Bean
    ServiceBusProcessorLifecycle serviceBusProcessorLifecycle(final ProbationMessageListener listener,
            @Value("${distribution.servicebus.connection-string:}") final String connectionString,
            @Value("${distribution.servicebus.namespace:}") final String namespace,
            @Value("${distribution.servicebus.queue-name}") final String queueName,
            @Value("${distribution.servicebus.max-concurrent-calls}") final int maxConcurrentCalls) {
        return new ServiceBusProcessorLifecycle(listener, connectionString, namespace, queueName,
                maxConcurrentCalls);
    }

    private static String serviceVersion(final ObjectProvider<BuildProperties> buildProperties) {
        final BuildProperties build = buildProperties.getIfAvailable();
        return build == null ? "unknown" : build.getVersion();
    }

    /**
     * Owns the processor client's lifecycle: built, started and stopped with the container, never a
     * readiness input (a broker blip must not roll the pod).
     */
    public static final class ServiceBusProcessorLifecycle implements SmartLifecycle {

        private final ProbationMessageListener listener;
        private final String connectionString;
        private final String namespace;
        private final String queueName;
        private final int maxConcurrentCalls;
        private volatile ServiceBusProcessorClient processor;

        /* default */ ServiceBusProcessorLifecycle(final ProbationMessageListener listener,
                final String connectionString, final String namespace, final String queueName,
                final int maxConcurrentCalls) {
            this.listener = listener;
            this.connectionString = connectionString;
            this.namespace = namespace;
            this.queueName = queueName;
            this.maxConcurrentCalls = maxConcurrentCalls;
        }

        @Override
        public void start() {
            final ServiceBusClientBuilder client = new ServiceBusClientBuilder();
            if (connectionString.isBlank()) {
                client.fullyQualifiedNamespace(namespace)
                        .credential(new DefaultAzureCredentialBuilder().build());
            } else {
                client.connectionString(connectionString);
            }
            processor = client.processor()
                    .queueName(queueName)
                    .disableAutoComplete()
                    .maxConcurrentCalls(maxConcurrentCalls)
                    .processMessage(listener::processMessage)
                    .processError(context ->
                            LOG.error("Service Bus processor error", context.getException()))
                    .buildProcessorClient();
            processor.start();
            LOG.info("Service Bus intake started on queue {}", queueName);
        }

        @Override
        public void stop() {
            if (processor != null) {
                processor.close();
                processor = null;
            }
        }

        @Override
        public boolean isRunning() {
            return processor != null && processor.isRunning();
        }
    }
}
