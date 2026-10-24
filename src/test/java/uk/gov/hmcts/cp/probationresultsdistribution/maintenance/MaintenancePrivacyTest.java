package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.CannotGetJdbcConnectionException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

class MaintenancePrivacyTest {

    @Test
    void partition_exception_should_exclude_untrusted_messages_and_causes() {
        final PartitionMaintenanceException failure = new PartitionMaintenanceException(
                MaintenanceFailureCause.OTHER, "synthetic-body-marker", new IllegalStateException(
                        "database detail: synthetic-body-marker"));

        assertThat(failure).hasNoCause().hasMessageNotContaining("synthetic-body-marker");
        assertThat(failure.cause()).isEqualTo(MaintenanceFailureCause.OTHER);
    }

    @Test
    void unexpected_database_failure_should_escape_only_as_bounded_exception() {
        final SingleRunnerLock lock = mock(SingleRunnerLock.class);
        final PartitionCatalogue catalogue = mock(PartitionCatalogue.class);
        when(lock.tryAcquire()).thenReturn(true);
        when(catalogue.partitions()).thenThrow(new IllegalStateException("synthetic-body-marker"));
        final DatabaseClock clock = mock(DatabaseClock.class);
        when(clock.nowUtc()).thenReturn(java.time.OffsetDateTime.now(java.time.ZoneOffset.UTC));
        final BodyRetentionJob job = new BodyRetentionJob(clock, catalogue,
                mock(PartitionMaintenanceRepository.class), java.time.Period.ofMonths(6),
                () -> lock, mock(MaintenanceMetrics.class));

        assertThatThrownBy(job::run).hasNoCause().hasMessageNotContaining("synthetic-body-marker");
        org.mockito.Mockito.verify(lock).release(false);
    }

    @Test
    void gauge_read_failure_should_not_leak_database_detail() {
        final JdbcClient jdbc = mock(JdbcClient.class);
        when(jdbc.sql(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new CannotGetJdbcConnectionException("synthetic-body-marker"));
        final PartitionMaintenanceRepository repository = new PartitionMaintenanceRepository(jdbc,
                new TransactionTemplate(mock(org.springframework.transaction.PlatformTransactionManager.class)));

        assertThatThrownBy(repository::countDefaultRows)
                .isInstanceOf(PartitionMaintenanceException.class)
                .hasNoCause().hasMessageNotContaining("synthetic-body-marker");
    }

    @Test
    void lost_statement_connection_should_be_classified_without_database_detail() {
        final org.springframework.transaction.PlatformTransactionManager manager =
                mock(org.springframework.transaction.PlatformTransactionManager.class);
        when(manager.getTransaction(any()))
                .thenThrow(new CannotGetJdbcConnectionException("synthetic-body-marker"));
        final TransactionTemplate transactions = new TransactionTemplate(manager);
        final PartitionMaintenanceRepository repository = new PartitionMaintenanceRepository(
                mock(JdbcClient.class), transactions);

        assertThatThrownBy(() -> repository.dropPartition("outbound_body_2020_01"))
                .isInstanceOfSatisfying(PartitionMaintenanceException.class, failure -> {
                    assertThat(failure.cause()).isEqualTo(MaintenanceFailureCause.STORE_UNAVAILABLE);
                    assertThat(failure).hasNoCause().hasMessageNotContaining("synthetic-body-marker");
                });
    }
}
