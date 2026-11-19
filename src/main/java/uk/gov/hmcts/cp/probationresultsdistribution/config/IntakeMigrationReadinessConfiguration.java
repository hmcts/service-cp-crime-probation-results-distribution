package uk.gov.hmcts.cp.probationresultsdistribution.config;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController;

/**
 * Registers {@link IntakeMigrationHealthIndicator} unconditional on intake (review of PR #23, P2
 * follow-up): a server run with {@code distribution.consumer.enabled=false} — the scaffold's own
 * intake switch (doc/design/DIGEST.md), used for an extended operational pause or during a
 * rollback — never registers {@link ConsumerLifecycleController}, the same reason {@link
 * MetricRegistryConfiguration} exists. Left gated inside {@link ServiceBusConsumerConfig}, {@code
 * application.yaml}'s readiness group would fail Spring's own group-membership validation at
 * startup whenever intake is off — exactly as it did for the CLI's own composition root before
 * that was fixed separately ({@code OperatorCommandLine#CLI_READINESS_OVERRIDE}): the CLI never
 * component-scans this package, so that fix does not reach a server with intake off, and this one
 * does not reach the CLI. With no consumer to report on, the indicator answers UP vacuously rather
 * than DOWN: intake being off is a deliberate operational state, not a startup failure.
 */
@Configuration
public class IntakeMigrationReadinessConfiguration {

    /* default */ @Bean
    IntakeMigrationHealthIndicator intakeMigrationHealthIndicator(
            final ObjectProvider<ConsumerLifecycleController> consumer) {
        return new IntakeMigrationHealthIndicator(() -> {
            final ConsumerLifecycleController controller = consumer.getIfAvailable();
            return controller == null || controller.migrationCompleted();
        });
    }
}
