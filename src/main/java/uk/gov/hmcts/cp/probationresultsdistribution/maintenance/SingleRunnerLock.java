package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import javax.sql.DataSource;

/**
 * The transaction-scoped single-runner advisory lock (FR-003-002), held on a connection obtained
 * directly from the {@link DataSource} — never through {@code JdbcClient} or Spring's declarative
 * transaction management, which would bind the connection to the calling thread and conflict with
 * every other statement in a run needing its own, separate connection and transaction. See
 * research.md "Lock and transaction strategy".
 *
 * <p>Not thread-safe and not reusable: one instance acquires at most once, then is released exactly
 * once. {@code BodyRetentionJob} creates a fresh instance per run.
 */
public final class SingleRunnerLock {

    /**
     * The fixed advisory lock key (FR-003-027: not a property). Chosen arbitrarily, distinct from
     * any key another part of the service's database might use.
     */
    private static final long LOCK_KEY = 0x8003_0000_0003L;

    private final DataSource dataSource;
    private Connection connection;

    /**
     * Creates the lock over a {@link DataSource}, not yet attempting to acquire it.
     *
     * @param dataSource the data source a fresh connection is obtained from on {@link #tryAcquire()}
     */
    public SingleRunnerLock(final DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /**
     * Attempts to take the lock on a freshly obtained connection, kept open (never committed) until
     * {@link #release(boolean)}.
     *
     * @return {@code true} if this instance now holds the lock
     */
    public boolean tryAcquire() {
        final boolean acquired;
        try {
            connection = dataSource.getConnection();
            connection.setAutoCommit(false);
            acquired = acquireOnConnection();
        } catch (final SQLException e) {
            release(false);
            throw DatabaseFailureClassifier.classify(e, "lock");
        }
        if (!acquired) {
            release(false);
        }
        return acquired;
    }

    private boolean acquireOnConnection() throws SQLException {
        try (PreparedStatement statement =
                connection.prepareStatement("SELECT pg_try_advisory_xact_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    throw new SQLException("Advisory lock returned no result");
                }
                return result.getBoolean(1);
            }
        }
    }

    /**
     * The connection presently holding the advisory lock, for the one statement the design requires
     * to run on it rather than its own, separate transaction: the {@code body_maintenance_state}
     * update (FR-003-032), so that a run's success is recorded in the same transaction as the lock
     * it was computed under and commits together with it.
     *
     * @return the held connection
     * @throws IllegalStateException if the lock is not currently held
     */
    public Connection heldConnection() {
        if (connection == null) {
            throw new IllegalStateException("Lock not held");
        }
        return connection;
    }

    /**
     * Commits or rolls back the lock transaction, always closing its connection. Cleanup errors
     * propagate as a bounded failure; database messages never cross this boundary.
     *
     * @param commit whether to commit rather than roll back
     */
    // Clearing the owned reference prevents double-release after a failed close.
    @SuppressWarnings("PMD.NullAssignment")
    public void release(final boolean commit) {
        if (connection == null) {
            return;
        }
        final Connection owned = connection;
        connection = null;
        try (owned) {
            if (commit) {
                owned.commit();
            } else {
                owned.rollback();
            }
        } catch (final SQLException e) {
            throw DatabaseFailureClassifier.classify(e, "lock release");
        }
    }
}
