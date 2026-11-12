package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.flyway.autoconfigure.FlywayAutoConfiguration;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceTransactionManagerAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcTemplateAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController;
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

    private static final DistributionProperties TEST_PROPERTIES =
            DistributionPropertiesFixtures.defaults();

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(ServiceBusConsumerConfig.class, MetricRegistryConfiguration.class)
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
            assertThat(context).hasSingleBean(ConsumerLifecycleController.class);
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

    @Test
    @DisplayName("refreshes the whole bean graph even when Redis is unreachable (FR-002-016): a "
            + "cache outage must not stop the service from starting")
    void refreshes_the_bean_graph_when_redis_is_unreachable() {
        new ApplicationContextRunner()
                .withUserConfiguration(ServiceBusConsumerConfig.class, MetricRegistryConfiguration.class)
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
                        // Port 1 is never a listening Redis: nothing answers, so a connect attempt
                        // here would fail fast rather than time out.
                        "distribution.redis.host=127.0.0.1",
                        "distribution.redis.port=1",
                        "distribution.redis.ssl=false",
                        "distribution.redis.verify-peer=false",
                        "distribution.redis.key-prefix=EXT_",
                        "distribution.usersgroups.youth-filter-key=EMYouthFilter",
                        "distribution.gateway.subscription-key=test-subscription-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ProbationMessageListener.class);
                });
    }

    @Test
    @DisplayName("refreshes the whole bean graph even when the store is unreachable (review of PR "
            + "#8): the pod must be able to start, and tell readiness why, rather than block on "
            + "Flyway's own eager migration - and readiness itself answers DOWN, not merely db "
            + "(review of PR #23): the migration this pod needs has not run either")
    void refreshes_the_bean_graph_when_the_store_is_unreachable() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(DataSourceAutoConfiguration.class,
                        DataSourceTransactionManagerAutoConfiguration.class,
                        JdbcTemplateAutoConfiguration.class, JdbcClientAutoConfiguration.class,
                        FlywayAutoConfiguration.class))
                .withUserConfiguration(ServiceBusConsumerConfig.class, MetricRegistryConfiguration.class)
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DistributionProperties.class, () -> TEST_PROPERTIES)
                .withPropertyValues(
                        // Never a listening Postgres: nothing answers, so a connection attempt fails
                        // fast rather than waiting out a real network timeout.
                        "spring.datasource.url=jdbc:postgresql://127.0.0.1:1/unreachable",
                        "spring.datasource.hikari.initialization-fail-timeout=-1",
                        "spring.flyway.locations=classpath:db/migration",
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
                        "distribution.gateway.subscription-key=test-subscription-key")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(ConsumerLifecycleController.class);
                    assertThat(context).hasSingleBean(ProbationMessageListener.class);
                    assertThat(context).hasSingleBean(IntakeMigrationHealthIndicator.class);
                    assertThat(context.getBean(IntakeMigrationHealthIndicator.class).health()
                            .getStatus()).isEqualTo(Status.DOWN);
                });
    }
}
