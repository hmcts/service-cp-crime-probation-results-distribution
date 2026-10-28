package uk.gov.hmcts.cp.probationresultsdistribution.config;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway.HmppsPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ResultsQueryExternalHearingClient;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;

/**
 * The one construction of the pipeline's outbound clients from the bound {@code distribution.*}
 * properties, shared by the intake composition root ({@link ServiceBusConsumerConfig}) and the
 * operator command line's resend pipeline ({@link OperatorCommandLine}), so the two can never build
 * a client with different timeouts, retries or budgets.
 */
final class PipelineClients {

    private PipelineClients() {
    }

    /**
     * A Service Bus client builder on exactly one credential source: the connection string for
     * local and CI, or else the namespace with the pod's workload identity (the consumer's rule).
     *
     * @param connectionString {@code distribution.servicebus.connection-string}, blank for none
     * @param namespace        {@code distribution.servicebus.namespace}
     * @return the builder
     */
    /* default */ static ServiceBusClientBuilder serviceBus(final String connectionString,
                                                          final String namespace) {
        final ServiceBusClientBuilder client = new ServiceBusClientBuilder();
        if (connectionString.isBlank()) {
            client.fullyQualifiedNamespace(namespace)
                    .credential(new DefaultAzureCredentialBuilder().build());
        } else {
            client.connectionString(connectionString);
        }
        return client;
    }

    /**
     * The payload-cache Redis client.
     *
     * @param host       {@code distribution.redis.host}
     * @param port       {@code distribution.redis.port}
     * @param ssl        {@code distribution.redis.ssl}
     * @param verifyPeer {@code distribution.redis.verify-peer}
     * @param password   {@code distribution.redis.key}, blank for none
     * @param properties the bound configuration
     * @return the client
     */
    /* default */ static RedisClient redisClient(final String host, final int port, final boolean ssl,
                                                 final boolean verifyPeer, final String password,
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

    /**
     * The results query API fallback client.
     *
     * @param properties the bound configuration
     * @return the client
     */
    /* default */ static ResultsQueryExternalHearingClient resultsQuery(
            final DistributionProperties properties) {
        return new ResultsQueryExternalHearingClient(properties.results().baseUrl(),
                properties.results().systemUserId(), properties.results().connectTimeout(),
                properties.results().readTimeout(), properties.results().maxAttempts(),
                properties.results().initialBackoff(), properties.results().maxBackoff());
    }

    /**
     * The users-groups {@code EMYouthFilter} permission client.
     *
     * @param properties     the bound configuration
     * @param youthFilterKey {@code distribution.usersgroups.youth-filter-key}
     * @param metrics        the service metrics
     * @return the client
     */
    /* default */ static FeaturePermissionClient youthFilter(final DistributionProperties properties,
                                                           final String youthFilterKey,
                                                           final MetricRegistry metrics) {
        return new FeaturePermissionClient(properties.usersgroups().baseUrl(),
                properties.usersgroups().systemUserId(), youthFilterKey,
                properties.usersgroups().connectTimeout(), properties.usersgroups().readTimeout(),
                properties.usersgroups().maxAttempts(), properties.usersgroups().retryWait(), metrics);
    }

    /**
     * The HMPPS gateway publisher.
     *
     * @param properties      the bound configuration
     * @param subscriptionKey {@code distribution.gateway.subscription-key}
     * @param metrics         the service metrics
     * @return the publisher
     */
    /* default */ static HmppsPublisher hmppsPublisher(final DistributionProperties properties,
                                                     final String subscriptionKey,
                                                     final MetricRegistry metrics) {
        return new HmppsPublisher(properties.hmpps().url(), subscriptionKey,
                properties.gateway().connectTimeout(), properties.gateway().readTimeout(),
                properties.gateway().maxAttempts(), properties.gateway().initialBackoff(),
                properties.gateway().maxBackoff(), properties.budget().leg(), metrics);
    }
}
