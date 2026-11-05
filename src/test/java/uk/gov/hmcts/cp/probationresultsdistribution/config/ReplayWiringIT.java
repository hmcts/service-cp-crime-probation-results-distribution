package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.messaging.servicebus.ServiceBusSenderClient;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.servicebus.ServiceBusReplayEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.api.ReplayController;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayEnqueuer;

/**
 * Pins replay's wiring in the serving context (T078; design §4.8 action 2, H9; FR-004-015): with
 * intake wired, the production {@link ServiceBusReplayEnqueuer} over one sender on
 * {@code probation.requests}, a {@link ReplayAction} and with it slice 005's {@link
 * ReplayController} are beans, and the sender is closed with the context; with intake off, none of
 * them is, and the context still starts.
 */
@DisplayName("replay in the serving context")
class ReplayWiringIT {

    @BeforeAll
    static void startContainers() {
        EndToEndTestSupport.startSharedContainers();
    }

    @Test
    @DisplayName("with intake enabled ReplayAction, its enqueuer and ReplayController are beans")
    void servingContext_intakeEnabled_should_registerReplay() {
        servingContext().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ServiceBusSenderClient.class);
            assertThat(context).getBean(ReplayEnqueuer.class)
                    .isInstanceOf(ServiceBusReplayEnqueuer.class);
            assertThat(context).hasSingleBean(ReplayAction.class);
            assertThat(context).hasSingleBean(ReplayController.class);
        });
    }

    @Test
    @DisplayName("the replay sender is closed when the serving context closes")
    void servingContext_closed_should_closeTheReplaySender() {
        final AtomicReference<ReplayEnqueuer> enqueuer = new AtomicReference<>();
        servingContext().run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(ReplayEnqueuer.class);
            enqueuer.set(context.getBean(ReplayEnqueuer.class));
        });

        assertThat(enqueuer.get()).isNotNull();
        assertThatThrownBy(() -> enqueuer.get().enqueue("REPLAY:" + UUID.randomUUID(),
                UUID.randomUUID().toString(), "{}"))
                .isInstanceOf(ReplayEnqueuer.ReplayEnqueueException.class);
    }

    @Test
    @DisplayName("with intake disabled there is no sender, enqueuer, ReplayAction or ReplayController")
    void servingContext_intakeDisabled_should_registerNoReplayBeans() {
        servingContext().withPropertyValues("distribution.consumer.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(ServiceBusSenderClient.class);
            assertThat(context).doesNotHaveBean(ReplayEnqueuer.class);
            assertThat(context).doesNotHaveBean(ReplayAction.class);
            assertThat(context).doesNotHaveBean(ReplayController.class);
        });
    }

    private static ApplicationContextRunner servingContext() {
        // OperationsConfiguration first: its @ConditionalOnBean must not depend on scan order.
        return new ApplicationContextRunner()
                .withUserConfiguration(OperationsConfiguration.class, ServiceBusConsumerConfig.class,
                        MetricRegistryConfiguration.class)
                .withBean(DataSource.class, EndToEndTestSupport::sharedDataSource)
                .withBean(JdbcClient.class,
                        () -> JdbcClient.create(EndToEndTestSupport.sharedDataSource()))
                .withBean(PlatformTransactionManager.class,
                        () -> new DataSourceTransactionManager(EndToEndTestSupport.sharedDataSource()))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DistributionProperties.class, DistributionPropertiesFixtures::defaults)
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
    }
}
