package uk.gov.hmcts.cp.probationresultsdistribution.config;

import io.micrometer.core.instrument.MeterRegistry;
import javax.sql.DataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.DatabaseClock;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionCatalogue;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionMaintenanceRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.SingleRunnerLock;

/**
 * Composes daily body maintenance independently of intake. No startup run or additional collector
 * is installed: the two gauges are evaluated on Micrometer's normal collection cycle.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(BodyRetentionProperties.class)
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
    MaintenanceMetrics bodyMaintenanceMetrics(final MeterRegistry registry,
                                              final PartitionMaintenanceRepository repository) {
        return new MaintenanceMetrics(registry, repository);
    }

    /* default */ @Bean
    BodyRetentionJob bodyRetentionJob(final BodyRetentionProperties properties,
                                      final DatabaseClock clock, final PartitionCatalogue catalogue,
                                      final PartitionMaintenanceRepository repository,
                                      final DataSource source, final MaintenanceMetrics metrics) {
        new StartupValidator().validateBodyRetention(properties);
        return new BodyRetentionJob(clock, catalogue, repository, properties.bodyRetention(),
                () -> new SingleRunnerLock(source), metrics);
    }
}
