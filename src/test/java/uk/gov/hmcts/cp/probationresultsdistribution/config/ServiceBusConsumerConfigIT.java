package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ProbationMessageListener;

/**
 * Boots the production composition root ({@link ServiceBusConsumerConfig}) against the shared
 * Testcontainers stack with {@code distribution.consumer.enabled=true}, the one path the hand-wired
 * end-to-end suites never exercise. Refreshing the real context resolves every {@code @Bean} and every
 * {@code @Value} key the class declares and starts the peek-lock processor against the emulator, so a
 * bad property key, a bean cycle or a wrong constructor wiring fails this build rather than surfacing
 * only at real startup.
 */
@DisplayName("the Service Bus consumer composition root")
class ServiceBusConsumerConfigIT {

    private static final DistributionProperties TEST_PROPERTIES = testProperties();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ServiceBusConsumerConfig.class)
            .withBean(DataSource.class, EndToEndTestSupport::sharedDataSource)
            .withBean(JdbcClient.class,
                    () -> JdbcClient.create(EndToEndTestSupport.sharedDataSource()))
            .withBean(PlatformTransactionManager.class,
                    () -> new DataSourceTransactionManager(EndToEndTestSupport.sharedDataSource()))
            .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
            .withBean(DistributionProperties.class, () -> TEST_PROPERTIES)
            .withPropertyValues(
                    "distribution.consumer.enabled=true",
                    "distribution.servicebus.connection-string="
                            + EndToEndTestSupport.serviceBusConnectionString(),
                    "distribution.servicebus.namespace=",
                    "distribution.servicebus.queue-name=probation.requests",
                    "distribution.servicebus.max-concurrent-calls=2",
                    "distribution.redis.host=" + EndToEndTestSupport.redisHost(),
                    "distribution.redis.port=" + EndToEndTestSupport.redisPort(),
                    "distribution.redis.ssl=false",
                    "distribution.redis.verify-peer=false",
                    "distribution.redis.key-prefix=EXT_",
                    "distribution.usersgroups.youth-filter-key=EMYouthFilter",
                    "distribution.gateway.subscription-key=test-subscription-key");

    @BeforeAll
    static void startContainers() {
        EndToEndTestSupport.startSharedContainers();
    }

    @Test
    @DisplayName("refreshes the whole bean graph when intake is enabled")
    void refreshes_the_bean_graph_when_enabled() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ProbationMessageListener.class);
            assertThat(context).hasSingleBean(DistributionRunner.class);
            assertThat(context).hasSingleBean(MetricRegistry.class);
            assertThat(context)
                    .hasSingleBean(ServiceBusConsumerConfig.ServiceBusProcessorLifecycle.class);
        });
    }

    @Test
    @DisplayName("stays off under the test-profile default (intake disabled)")
    void stays_off_when_disabled() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServiceBusConsumerConfig.class)
                .withPropertyValues("distribution.consumer.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(ProbationMessageListener.class);
                    assertThat(context).doesNotHaveBean(DistributionRunner.class);
                });
    }

    private static DistributionProperties testProperties() {
        return new DistributionProperties(
                new DistributionProperties.Budget(
                        Duration.ofSeconds(60), Duration.ofSeconds(25), Duration.ofSeconds(130)),
                new DistributionProperties.Gateway(Duration.ofSeconds(5), Duration.ofSeconds(60),
                        3, Duration.ofMillis(500), Duration.ofSeconds(10)),
                new DistributionProperties.Results("http://localhost:8080",
                        "00000000-0000-0000-0000-000000000000", Duration.ofSeconds(5),
                        Duration.ofSeconds(30), 4, Duration.ofMillis(500), Duration.ofSeconds(20)),
                new DistributionProperties.UsersGroups("http://localhost:8080",
                        "00000000-0000-0000-0000-000000000000", Duration.ofSeconds(2),
                        Duration.ofSeconds(5), 3, Duration.ofSeconds(1)),
                new DistributionProperties.Redis(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new DistributionProperties.Claim(
                        Duration.ofMinutes(4).plusSeconds(30), Duration.ofMinutes(4)),
                new DistributionProperties.ServiceBus(5, Duration.ofMinutes(5), Duration.ofMinutes(5)),
                new DistributionProperties.YouthSwitch("ACTIVE"),
                new DistributionProperties.Hmpps("https://localhost/hmpps"));
    }
}
