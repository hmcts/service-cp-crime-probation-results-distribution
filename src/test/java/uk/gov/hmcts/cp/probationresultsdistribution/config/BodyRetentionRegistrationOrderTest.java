package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;

/**
 * Pins the real boot ordering a {@link org.springframework.boot.test.context.runner
 * .ApplicationContextRunner#withBean} shortcut cannot see (FR, P1): {@code JdbcClient} comes from
 * {@link JdbcClientAutoConfiguration}, a deferred auto-configuration import processed after every
 * component-scanned {@code @Configuration} class, {@link BodyRetentionConfiguration} included.
 * Supplying the bean directly via {@code withBean} (as {@link BodyRetentionPropertiesTest} does)
 * registers it immediately, before {@code BodyRetentionConfiguration}'s own condition is
 * evaluated — masking this exact ordering bug. Routing {@code JdbcClient} through {@link
 * AutoConfigurations#of} instead reproduces the same deferred-import ordering the real
 * application boots under.
 */
class BodyRetentionRegistrationOrderTest {

    @Test
    void registers_the_retention_job_when_jdbc_client_arrives_by_deferred_auto_configuration() {
        new ApplicationContextRunner()
                .withBean(DataSource.class, () -> mock(DataSource.class))
                .withBean(NamedParameterJdbcTemplate.class, () -> mock(NamedParameterJdbcTemplate.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(AutoConfigurations.of(JdbcClientAutoConfiguration.class,
                        BodyRetentionConfiguration.class))
                .withPropertyValues("distribution.body-retention=P6M",
                        "distribution.body-maintenance.cron=0 15 2 * * *")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(BodyRetentionJob.class);
                    assertThat(context).hasSingleBean(MaintenanceMetrics.class);
                });
    }

    @Test
    void backs_off_cleanly_through_the_same_deferred_mechanism_when_no_jdbc_client_exists() {
        new ApplicationContextRunner()
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withConfiguration(AutoConfigurations.of(JdbcClientAutoConfiguration.class,
                        BodyRetentionConfiguration.class))
                .withPropertyValues("distribution.body-retention=P6M",
                        "distribution.body-maintenance.cron=0 15 2 * * *")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(BodyRetentionJob.class);
                    assertThat(context).doesNotHaveBean(MaintenanceMetrics.class);
                });
    }
}
