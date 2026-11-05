package uk.gov.hmcts.cp.probationresultsdistribution.config;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyMaintenanceStateRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.DatabaseClock;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionCatalogue;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionMaintenanceRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.SingleRunnerLock;

/**
 * Composes daily body maintenance independently of intake. No startup run or additional collector
 * is installed: the two gauges are evaluated on Micrometer's normal collection cycle.
 *
 * <p>{@code @ConditionalOnBean(JdbcClient.class)} lets this configuration back off cleanly in a
 * context with no datastore (the {@code test} profile excludes {@code DataSourceAutoConfiguration}
 * entirely). That condition is only reliable here because this is a genuine Boot auto-configuration
 * (registered in {@code META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration
 * .imports}, excluded from {@link uk.gov.hmcts.cp.probationresultsdistribution.Application}'s
 * component scan) ordered with {@link JdbcClientAutoConfiguration}: a component-scanned {@code
 * @Configuration}'s conditions are evaluated before any deferred auto-configuration import runs, so
 * a plain {@code @Configuration} class here would never see the {@code JdbcClient} bean
 * {@code JdbcClientAutoConfiguration} registers, in the one place it actually exists — the running
 * application. A full-application probe confirmed exactly that: {@code JdbcClient} present, this
 * configuration's beans absent.
 */
@AutoConfiguration(after = JdbcClientAutoConfiguration.class)
@EnableScheduling
@EnableConfigurationProperties(BodyRetentionProperties.class)
@ConditionalOnBean(JdbcClient.class)
public class BodyRetentionConfiguration {

    /* default */ @Bean
    DatabaseClock bodyDatabaseClock(final JdbcClient jdbc) {
        return new DatabaseClock(jdbc);
    }

    /* default */ @Bean
    PartitionCatalogue bodyPartitionCatalogue(final JdbcClient jdbc, final DataSource source) {
        return new PartitionCatalogue(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    /* default */ @Bean
    PartitionMaintenanceRepository bodyPartitionRepository(final JdbcClient jdbc,
                                                          final DataSource source) {
        return new PartitionMaintenanceRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    /* default */ @Bean
    BodyMaintenanceStateRepository bodyMaintenanceStateRepository(final JdbcClient jdbc,
                                                                  final DataSource source) {
        return new BodyMaintenanceStateRepository(jdbc,
                new TransactionTemplate(new DataSourceTransactionManager(source)));
    }

    /* default */ @Bean
    MaintenanceMetrics bodyMaintenanceMetrics(final MeterRegistry registry,
                                              final PartitionMaintenanceRepository repository,
                                              final PartitionCatalogue catalogue,
                                              final DatabaseClock clock,
                                              final BodyMaintenanceStateRepository stateRepository) {
        return new MaintenanceMetrics(registry, repository, catalogue, clock, stateRepository);
    }

    /* default */ @Bean
    BodyRetentionJob bodyRetentionJob(final BodyRetentionProperties properties,
                                      final DatabaseClock clock, final PartitionCatalogue catalogue,
                                      final PartitionMaintenanceRepository repository,
                                      final DataSource source, final MaintenanceMetrics metrics,
                                      final BodyMaintenanceStateRepository stateRepository) {
        new StartupValidator().validateBodyRetention(properties);
        return new BodyRetentionJob(clock, catalogue, repository, properties.bodyRetention(),
                () -> new SingleRunnerLock(source), metrics, stateRepository);
    }
}
