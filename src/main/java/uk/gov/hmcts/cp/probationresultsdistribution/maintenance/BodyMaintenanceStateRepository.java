package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Savepoint;
import java.time.OffsetDateTime;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The one-row {@code body_maintenance_state} table (H38), created and seeded by this slice's Flyway
 * {@code V2}. {@link #lastSuccessAt()} is an independent read, for the
 * {@code probation_body_maintenance_last_success_seconds} gauge (FR-003-031, A3-118).
 * {@link #recordSuccessOnConnection(Connection)} is different from every other statement this slice
 * issues: it MUST run on the single-runner lock's own connection, as the last statement of its
 * transaction, under its own savepoint, so a run's success commits together with the lock that
 * computed it rather than in a separate transaction (FR-003-032).
 */
public final class BodyMaintenanceStateRepository {

    private static final String SET_LOCK_TIMEOUT_5S = "SET LOCAL lock_timeout = '5s'";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the repository over a {@link JdbcClient}.
     *
     * @param jdbc                the JDBC client the independent read runs through
     * @param transactionTemplate the transaction boundary the independent read runs in
     */
    public BodyMaintenanceStateRepository(final JdbcClient jdbc,
                                          final TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Reads when the job last completed a run with no failure. Read-only: takes no lock
     * (FR-003-031).
     *
     * @return the last-success instant
     */
    public OffsetDateTime lastSuccessAt() {
        try {
            return transactionTemplate.execute(status -> {
                jdbc.sql(SET_LOCK_TIMEOUT_5S).update();
                return jdbc.sql("SELECT last_success_at FROM body_maintenance_state")
                        .query(OffsetDateTime.class).single();
            });
        } catch (final DataAccessException | TransactionException e) {
            throw DatabaseFailureClassifier.classify(e, "reading body_maintenance_state");
        }
    }

    /**
     * Sets {@code last_success_at} to the database's own wall clock ({@code clock_timestamp()}),
     * affecting exactly the one row, on the given connection — the single-runner lock's own — under
     * a savepoint, so a failure here is classified and reported without undoing the rest of the
     * run's already-committed statements (FR-003-032).
     *
     * @param lockConnection the connection presently holding the single-runner advisory lock
     */
    public void recordSuccessOnConnection(final Connection lockConnection) {
        try {
            final Savepoint savepoint = lockConnection.setSavepoint();
            try (PreparedStatement statement = lockConnection.prepareStatement(
                    "UPDATE body_maintenance_state SET last_success_at = clock_timestamp()")) {
                statement.executeUpdate();
            } catch (final SQLException e) {
                lockConnection.rollback(savepoint);
                throw e;
            }
        } catch (final SQLException e) {
            throw DatabaseFailureClassifier.classify(e, "updating body_maintenance_state");
        }
    }
}
