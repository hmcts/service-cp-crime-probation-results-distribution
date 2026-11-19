package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.health.contributor.Status;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.Application;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController;

/**
 * Pins the server's own readiness group against a real Spring Boot boot — full actuator
 * auto-configuration, unlike an {@code ApplicationContextRunner} slice over {@link
 * ServiceBusConsumerConfig} alone, which never enables it — with intake switched off (review of PR
 * #23, P2 follow-up). {@code application.yaml}'s deployed readiness group names {@code
 * intakeMigration} unconditionally, but {@link IntakeMigrationHealthIndicator} used to live only
 * inside {@link ServiceBusConsumerConfig}, which {@code distribution.consumer.enabled=false} never
 * activates. Left that way, a server deliberately run with intake off — the scaffold's own intake
 * switch (doc/design/DIGEST.md), used for an extended operational pause or during a rollback —
 * failed Spring's own group-membership validation at startup exactly as the CLI did before that was
 * fixed separately (6172292): "Health contributor 'intakeMigration' ... does not exist". The
 * datasource is the shared, reachable Postgres container, not an unreachable stand-in: with intake
 * off, Flyway still migrates eagerly at context refresh (it is only deferred inside {@link
 * ServiceBusConsumerConfig}), so an unreachable datasource would fail this test for an unrelated
 * reason before ever reaching the readiness assertion.
 */
@DisplayName("the server with intake switched off")
class DisabledConsumerReadinessIT {

    @BeforeAll
    static void startContainers() {
        EndToEndTestSupport.startSharedContainers();
    }

    @Test
    @DisplayName("still refreshes, with intakeMigration vacuously up: there is no consumer to gate on")
    void refreshes_with_intake_migration_vacuously_up() {
        final DriverManagerDataSource source =
                (DriverManagerDataSource) EndToEndTestSupport.sharedDataSource();
        final SpringApplicationBuilder builder = new SpringApplicationBuilder(Application.class)
                .web(WebApplicationType.NONE);
        try (ConfigurableApplicationContext context = builder.run(
                // local relaxes StartupValidator's secrets-required checks (base URLs, HMPPS TLS
                // URL, workload identity) the same way PackagedIntakeCommandsIT's packaged-jar
                // runs do - none of those are this test's concern.
                "--spring.profiles.active=local",
                "--distribution.consumer.enabled=false",
                "--spring.datasource.url=" + source.getUrl(),
                "--spring.datasource.username=" + source.getUsername(),
                "--spring.datasource.password=" + source.getPassword(),
                "--distribution.servicebus.connection-string="
                        + EndToEndTestSupport.serviceBusConnectionString(),
                "--distribution.servicebus.namespace=",
                "--distribution.servicebus.queue-name=probation.requests",
                "--distribution.redis.host=" + EndToEndTestSupport.redisHost(),
                "--distribution.redis.port=" + EndToEndTestSupport.redisPort(),
                "--distribution.redis.ssl=false",
                "--distribution.redis.verify-peer=false",
                "--distribution.redis.key-prefix=EXT_",
                "--distribution.usersgroups.youth-filter-key=EMYouthFilter",
                "--distribution.gateway.subscription-key=test-subscription-key",
                "--distribution.results.system-user-id=00000000-0000-0000-0000-000000000000",
                "--distribution.usersgroups.system-user-id=00000000-0000-0000-0000-000000000000",
                "--authz.http.enabled=false",
                "--audit.http.enabled=false")) {
            assertThat(context.getBeanNamesForType(ConsumerLifecycleController.class)).isEmpty();
            assertThat(context.getBeanNamesForType(IntakeMigrationHealthIndicator.class)).hasSize(1);
            assertThat(context.getBean(IntakeMigrationHealthIndicator.class).health().getStatus())
                    .isEqualTo(Status.UP);
        }
    }
}
