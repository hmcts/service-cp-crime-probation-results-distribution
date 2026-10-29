package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

@DisplayName("recording success on the lock's own connection (FR-003-032)")
class BodyMaintenanceStateRepositoryTest {

    @Test
    @DisplayName("issues the UPDATE on the given connection, under a savepoint, using the database's own wall clock")
    @SuppressWarnings("PMD.CloseResource") // mocked Connection/PreparedStatement: nothing to close
    void issues_the_update_under_a_savepoint() throws SQLException {
        final BodyMaintenanceStateRepository repository =
                new BodyMaintenanceStateRepository(mock(JdbcClient.class), mock(TransactionTemplate.class));
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.setSavepoint()).thenReturn(mock(Savepoint.class));
        when(connection.prepareStatement(
                "UPDATE body_maintenance_state SET last_success_at = clock_timestamp()"))
                .thenReturn(statement);

        repository.recordSuccessOnConnection(connection);

        verify(statement).executeUpdate();
        verify(statement).close();
        verify(connection, never()).rollback(any(Savepoint.class));
    }

    @Test
    @DisplayName("rolls back to its own savepoint and reports a bounded failure when the UPDATE fails")
    @SuppressWarnings("PMD.CloseResource") // mocked Connection/PreparedStatement: nothing to close
    void rolls_back_to_its_savepoint_on_failure() throws SQLException {
        final BodyMaintenanceStateRepository repository =
                new BodyMaintenanceStateRepository(mock(JdbcClient.class), mock(TransactionTemplate.class));
        final Connection connection = mock(Connection.class);
        final Savepoint savepoint = mock(Savepoint.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        when(connection.setSavepoint()).thenReturn(savepoint);
        when(connection.prepareStatement(
                "UPDATE body_maintenance_state SET last_success_at = clock_timestamp()"))
                .thenReturn(statement);
        when(statement.executeUpdate()).thenThrow(
                new SQLException("canceling statement due to lock timeout", "55P03"));

        assertThatThrownBy(() -> repository.recordSuccessOnConnection(connection))
                .isInstanceOfSatisfying(PartitionMaintenanceException.class, failure -> {
                    assertThat(failure.sqlState()).isEqualTo("55P03");
                    assertThat(failure).hasNoCause().hasMessageNotContaining("lock timeout");
                });

        verify(connection).rollback(savepoint);
    }
}
