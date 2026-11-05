package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.time.OffsetDateTime;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * The short, separately transacted DDL/DML statements this slice's job issues against
 * {@code outbound_body}, each under its own 5 s {@code lock_timeout} (FR-003-016), never the
 * single-runner lock's own transaction (research.md "Lock and transaction strategy").
 */
public final class PartitionMaintenanceRepository {

    private static final String SET_LOCK_TIMEOUT_5S = "SET LOCAL lock_timeout = '5s'";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the repository over a {@link JdbcClient} and a {@link TransactionTemplate} — never
     * the single-runner lock's own connection.
     *
     * @param jdbc                 the JDBC client each statement runs through
     * @param transactionTemplate  the transaction boundary each statement runs its own, short
     *                             transaction in
     */
    public PartitionMaintenanceRepository(final JdbcClient jdbc,
                                          final TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = new TransactionTemplate(
                transactionTemplate.getTransactionManager(), transactionTemplate);
        this.transactionTemplate.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Creates a month's partition if its name is absent, under the service's own role, refusing on
     * an overlap or a DEFAULT-partition spill (FR-003-006 to FR-003-008).
     *
     * @param partitionName the partition's table name
     * @param from          the inclusive lower bound
     * @param to            the exclusive upper bound
     */
    public void createPartition(final String partitionName, final OffsetDateTime from,
                                final OffsetDateTime to) {
        // PostgreSQL DDL does not accept bind parameters for a partition bound's literal values
        // ("there is no parameter $1"); the name and bounds are always computed internally by this
        // slice's own code (never external input), the same trust boundary V1's own migration
        // relies on for its format()/EXECUTE partition creation.
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.sql(SET_LOCK_TIMEOUT_5S).update();
                jdbc.sql("CREATE TABLE IF NOT EXISTS \"" + quotedIdentifier(partitionName)
                        + "\" PARTITION OF outbound_body FOR VALUES FROM ('" + from + "') TO ('"
                        + to + "')")
                        .update();
            });
        } catch (final DataAccessException | org.springframework.transaction.TransactionException e) {
            throw classify(e, "creating " + partitionName);
        }
    }

    /**
     * Drops an expired partition by a plain {@code DROP}, in its own transaction (FR-003-011).
     *
     * @param partitionName the partition's table name
     */
    public void dropPartition(final String partitionName) {
        try {
            transactionTemplate.executeWithoutResult(status -> {
                jdbc.sql(SET_LOCK_TIMEOUT_5S).update();
                jdbc.sql("DROP TABLE \"" + quotedIdentifier(partitionName) + "\"").update();
            });
        } catch (final DataAccessException | org.springframework.transaction.TransactionException e) {
            throw classify(e, "dropping " + partitionName);
        }
    }

    /**
     * Deletes DEFAULT-partition rows older than the cut-off (FR-003-014).
     *
     * @param cutoff the cut-off instant
     * @return the number of rows deleted
     */
    public long deleteDefaultRowsBefore(final OffsetDateTime cutoff) {
        try {
            return transactionTemplate.execute(status -> {
                jdbc.sql(SET_LOCK_TIMEOUT_5S).update();
                return (long) jdbc.sql("DELETE FROM outbound_body_default WHERE created_at < :cutoff")
                        .param("cutoff", cutoff)
                        .update();
            });
        } catch (final DataAccessException | org.springframework.transaction.TransactionException e) {
            throw classify(e, "deleting stranded DEFAULT rows before " + cutoff);
        }
    }

    /**
     * Reads the DEFAULT partition's current row count. Read-only: takes no lock (FR-003-031).
     *
     * @return the row count
     */
    public long countDefaultRows() {
        try {
            return transactionTemplate.execute(status -> {
                jdbc.sql(SET_LOCK_TIMEOUT_5S).update();
                return jdbc.sql("SELECT count(*) FROM outbound_body_default")
                        .query(Long.class).single();
            });
        } catch (final DataAccessException | org.springframework.transaction.TransactionException e) {
            throw classify(e, "counting DEFAULT rows");
        }
    }

    private static PartitionMaintenanceException classify(final RuntimeException e,
                                                           final String action) {
        return DatabaseFailureClassifier.classify(e, action);
    }

    private static String quotedIdentifier(final String name) {
        return name.replace("\"", "\"\"");
    }
}
