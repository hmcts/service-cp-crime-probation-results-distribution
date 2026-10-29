package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reads {@code outbound_body}'s partitions and their bounds from the PostgreSQL catalogue
 * (FR-003-010): {@code pg_get_expr(relpartbound, oid)} over {@code pg_inherits}, never a partition's
 * name. See research.md "Reading partition bounds from the catalogue".
 *
 * <p>Run under the same 5 s {@code lock_timeout} as every other partition statement: a plain catalog
 * read against {@code pg_inherits}/{@code pg_class} can still contend with a session holding an
 * {@code ACCESS EXCLUSIVE} lock on {@code outbound_body} (a removal's own brief hold, or User Story
 * 6's recovery procedure), so this read must not be the one statement capable of hanging indefinitely
 * (constitution Principle VI).
 */
public final class PartitionCatalogue {

    private static final String PARTITIONS_SQL = """
            SELECT child.relname AS partition_name,
                   pg_get_expr(child.relpartbound, child.oid) AS bound_expr
            FROM pg_inherits i
            JOIN pg_class child ON child.oid = i.inhrelid
            JOIN pg_class parent ON parent.oid = i.inhparent
            WHERE parent.oid = 'outbound_body'::regclass AND child.relispartition
            """;

    private static final Pattern BOUND_PATTERN =
            Pattern.compile("FROM \\('([^']+)'\\) TO \\('([^']+)'\\)");
    private static final DateTimeFormatter BOUND_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ssX");
    private static final String DEFAULT_BOUND_EXPR = "DEFAULT";

    private final JdbcClient jdbc;
    private final TransactionTemplate transactionTemplate;

    /**
     * Creates the catalogue reader over a {@link JdbcClient}.
     *
     * @param jdbc                the JDBC client to read {@code pg_catalog} through
     * @param transactionTemplate the transaction boundary the bounded read runs in
     */
    public PartitionCatalogue(final JdbcClient jdbc, final TransactionTemplate transactionTemplate) {
        this.jdbc = jdbc;
        this.transactionTemplate = transactionTemplate;
    }

    /**
     * Every partition of {@code outbound_body}, including DEFAULT.
     *
     * @return the partitions, in no particular order
     */
    public List<PartitionBound> partitions() {
        try {
            return transactionTemplate.execute(status -> {
                jdbc.sql("SET LOCAL lock_timeout = '5s'").update();
                return jdbc.sql(PARTITIONS_SQL).query(PartitionCatalogue::toBound).list();
            });
        } catch (final DataAccessException | TransactionException e) {
            throw DatabaseFailureClassifier.classify(e, "reading the partition catalogue");
        }
    }

    /**
     * Whether the service schema already has the month's name, even as a standalone table.
     *
     * @param name the internally generated monthly table name
     * @return whether that relation exists
     */
    public boolean tableExists(final String name) {
        return jdbc.sql("SELECT to_regclass(:name) IS NOT NULL")
                .param("name", name).query(Boolean.class).single();
    }

    /**
     * The number of consecutive whole months after {@code currentMonth} that have a partition whose
     * bounds exactly cover that month (FR-003-031; H37) — bounds read from the catalogue
     * (FR-003-010), never a partition's name. Reads the catalogue fresh; never cached.
     *
     * @param currentMonth the current UTC month's first instant
     * @return how many consecutive following months are covered
     */
    public int monthsAhead(final OffsetDateTime currentMonth) {
        return monthsAhead(currentMonth, partitions());
    }

    /**
     * The pure form of {@link #monthsAhead(OffsetDateTime)}, over an already-read partition list, so
     * a caller that has just read the catalogue for another reason (FR-003-030's start check) need
     * not read it again.
     *
     * @param currentMonth the current UTC month's first instant
     * @param partitions   the partitions to check coverage against
     * @return how many consecutive following months are covered
     */
    public static int monthsAhead(final OffsetDateTime currentMonth,
                                  final List<PartitionBound> partitions) {
        int count = 0;
        OffsetDateTime cursor = currentMonth.plusMonths(1);
        while (true) {
            final OffsetDateTime next = cursor.plusMonths(1);
            final OffsetDateTime monthStart = cursor;
            final boolean covered = partitions.stream().anyMatch(p -> !p.isDefault()
                    && monthStart.isEqual(p.from()) && next.isEqual(p.to()));
            if (!covered) {
                return count;
            }
            count++;
            cursor = next;
        }
    }

    // JDBC's row-mapper contract supplies an index this mapper does not need.
    @SuppressWarnings("PMD.UnusedFormalParameter")
    private static PartitionBound toBound(final ResultSet rs, final int rowNum) throws SQLException {
        final String name = rs.getString("partition_name");
        final String boundExpr = rs.getString("bound_expr");
        final Matcher matcher = BOUND_PATTERN.matcher(boundExpr);
        final PartitionBound bound;
        if (matcher.find()) {
            final OffsetDateTime from = OffsetDateTime.parse(matcher.group(1), BOUND_FORMAT);
            final OffsetDateTime to = OffsetDateTime.parse(matcher.group(2), BOUND_FORMAT);
            bound = new PartitionBound(name, from, to);
        } else if (DEFAULT_BOUND_EXPR.equals(boundExpr)) {
            bound = new PartitionBound(name, null, null);
        } else {
            throw new IllegalStateException("Unsupported outbound body partition bounds");
        }
        return bound;
    }
}
