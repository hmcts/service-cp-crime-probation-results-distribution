package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

class MaintenanceTransactionFailureTest {

    private static final String BEGIN_BOUNDARY = "begin";

    @ParameterizedTest
    @CsvSource({"create,begin", "drop,begin", "delete,begin", "count,begin",
        "create,commit", "drop,commit", "delete,commit", "count,commit"})
    void transaction_connection_failure_should_be_classified_per_statement(final String operation,
                                                                          final String boundary) {
        final PlatformTransactionManager manager = mock(PlatformTransactionManager.class);
        final SQLException disconnected = new SQLException("synthetic-body-marker", "08006");
        if (BEGIN_BOUNDARY.equals(boundary)) {
            when(manager.getTransaction(any())).thenThrow(
                    new CannotCreateTransactionException("synthetic-body-marker", disconnected));
        } else {
            when(manager.getTransaction(any())).thenReturn(mock(TransactionStatus.class));
            doThrow(new TransactionSystemException("synthetic-body-marker", disconnected))
                    .when(manager).commit(any());
        }
        final JdbcClient jdbc = mock(JdbcClient.class, org.mockito.Answers.RETURNS_DEEP_STUBS);
        when(jdbc.sql("SELECT count(*) FROM outbound_body_default").query(Long.class).single())
                .thenReturn(0L);
        final PartitionMaintenanceRepository repository = new PartitionMaintenanceRepository(jdbc,
                new TransactionTemplate(manager));
        final OffsetDateTime month = OffsetDateTime.parse("2026-01-01T00:00:00Z");

        assertThatThrownBy(() -> {
            switch (operation) {
                case "create" -> repository.createPartition("outbound_body_2026_01", month, month.plusMonths(1));
                case "drop" -> repository.dropPartition("outbound_body_2026_01");
                case "delete" -> repository.deleteDefaultRowsBefore(month);
                case "count" -> repository.countDefaultRows();
                default -> throw new IllegalArgumentException("Unknown test operation");
            }
        }).isInstanceOfSatisfying(PartitionMaintenanceException.class, failure -> {
            assertThat(failure.cause()).isEqualTo(MaintenanceFailureCause.STORE_UNAVAILABLE);
            assertThat(failure).hasNoCause().hasMessageNotContaining("synthetic-body-marker");
        });
    }
}
