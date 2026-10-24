package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class SingleRunnerLockTest {

    @Test
    @SuppressWarnings({"PMD.CloseResource", "PMD.CheckResultSet"})
    void failed_acquisition_cleanup_should_surface_sanitised_failure() throws SQLException {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(false);
        doThrow(new SQLException("synthetic-body-marker")).when(connection).rollback();

        assertThatThrownBy(() -> new SingleRunnerLock(source).tryAcquire())
                .hasNoCause().hasMessageNotContaining("synthetic-body-marker");
        verify(connection).close();
    }

    @Test
    @SuppressWarnings({"PMD.CloseResource", "PMD.CheckResultSet"})
    void failed_connection_close_should_surface_sanitised_failure() throws SQLException {
        final DataSource source = mock(DataSource.class);
        final Connection connection = mock(Connection.class);
        final PreparedStatement statement = mock(PreparedStatement.class);
        final ResultSet result = mock(ResultSet.class);
        when(source.getConnection()).thenReturn(connection);
        when(connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")).thenReturn(statement);
        when(statement.executeQuery()).thenReturn(result);
        when(result.next()).thenReturn(true);
        when(result.getBoolean(1)).thenReturn(true);
        final SingleRunnerLock lock = new SingleRunnerLock(source);
        org.assertj.core.api.Assertions.assertThat(lock.tryAcquire()).isTrue();
        doThrow(new SQLException("synthetic-body-marker")).when(connection).close();

        assertThatThrownBy(() -> lock.release(true))
                .hasNoCause().hasMessageNotContaining("synthetic-body-marker");
    }
}
