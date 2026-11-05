package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

@DisplayName("the partition catalogue")
class PartitionCatalogueIT extends SchemaTestSupport {

    private final PartitionCatalogue catalogue = new PartitionCatalogue(jdbc(),
            new TransactionTemplate(new DataSourceTransactionManager(datasource())));

    @Test
    @DisplayName("reads V1's migration-month partitions with their bounds from the catalogue")
    void reads_v1s_migration_month_partitions() {
        final List<PartitionBound> partitions = catalogue.partitions();

        final OffsetDateTime thisMonth = OffsetDateTime.now(ZoneOffset.UTC)
                .truncatedTo(ChronoUnit.DAYS)
                .withDayOfMonth(1)
                .withHour(0).withMinute(0).withSecond(0).withNano(0);

        assertThat(partitions).filteredOn(p -> !p.isDefault()).hasSizeGreaterThanOrEqualTo(3);
        final PartitionBound current = partitions.stream()
                .filter(p -> !p.isDefault() && !p.from().isBefore(thisMonth)
                        && p.from().isBefore(thisMonth.plusMonths(1)))
                .findFirst()
                .orElseThrow();
        assertThat(current.from()).isEqualTo(thisMonth);
        assertThat(current.to()).isEqualTo(thisMonth.plusMonths(1));
        assertThat(current.partitionName()).isEqualTo(
                "outbound_body_" + thisMonth.getYear() + "_"
                        + String.format("%02d", thisMonth.getMonthValue()));
    }

    @Test
    @DisplayName("represents the DEFAULT partition with no usable bounds")
    void represents_the_default_partition() {
        final List<PartitionBound> partitions = catalogue.partitions();

        final PartitionBound defaultPartition = partitions.stream()
                .filter(PartitionBound::isDefault)
                .findFirst()
                .orElseThrow();
        assertThat(defaultPartition.partitionName()).isEqualTo("outbound_body_default");
        assertThat(defaultPartition.from()).isNull();
        assertThat(defaultPartition.to()).isNull();
    }

    @Test
    void other_schema_parent_should_not_supply_partitions() {
        execute("CREATE SCHEMA catalogue_other");
        try {
            execute("CREATE TABLE catalogue_other.outbound_body (created_at timestamptz) "
                    + "PARTITION BY RANGE (created_at)");
            execute("CREATE TABLE catalogue_other.unrelated PARTITION OF catalogue_other.outbound_body "
                    + "FOR VALUES FROM ('2000-01-01') TO ('2000-02-01')");
            assertThat(catalogue.partitions()).noneMatch(p -> "unrelated".equals(p.partitionName()));
        } finally {
            execute("DROP SCHEMA catalogue_other CASCADE");
        }
    }

    @Test
    void unsupported_bound_should_fail_explicitly_instead_of_becoming_default() {
        execute("CREATE TABLE outbound_body_unbounded PARTITION OF outbound_body "
                + "FOR VALUES FROM (MINVALUE) TO ('1900-01-01')");
        try {
            org.assertj.core.api.Assertions.assertThatThrownBy(catalogue::partitions)
                    .isInstanceOf(IllegalStateException.class);
        } finally {
            execute("DROP TABLE outbound_body_unbounded");
        }
    }

    @Test
    void non_hour_session_offset_should_preserve_catalogue_instants() {
        final org.springframework.jdbc.datasource.DriverManagerDataSource shared =
                (org.springframework.jdbc.datasource.DriverManagerDataSource) datasource();
        final org.springframework.jdbc.datasource.DriverManagerDataSource kolkata =
                new org.springframework.jdbc.datasource.DriverManagerDataSource(shared.getUrl(),
                        shared.getUsername(), shared.getPassword());
        final java.util.Properties properties = new java.util.Properties();
        properties.setProperty("options", "-c TimeZone=Asia/Kolkata");
        kolkata.setConnectionProperties(properties);
        final PartitionCatalogue other = new PartitionCatalogue(
                org.springframework.jdbc.core.simple.JdbcClient.create(kolkata),
                new TransactionTemplate(new DataSourceTransactionManager(kolkata)));

        org.assertj.core.api.Assertions.assertThatCode(other::partitions).doesNotThrowAnyException();
        assertThat(other.partitions()).usingRecursiveComparison()
                .withComparatorForType(java.util.Comparator.comparing(OffsetDateTime::toInstant),
                        OffsetDateTime.class)
                .isEqualTo(catalogue.partitions());
    }

    @Test
    @Timeout(20)
    @DisplayName("gives up after 5s rather than hang when another session holds a conflicting lock")
    void gives_up_rather_than_hang_on_a_conflicting_lock() throws Exception {
        try (Connection blocker = datasource().getConnection()) {
            blocker.setAutoCommit(false);
            try (Statement statement = blocker.createStatement()) {
                statement.execute("LOCK TABLE outbound_body IN ACCESS EXCLUSIVE MODE");

                final long start = System.nanoTime();
                org.assertj.core.api.Assertions.assertThatThrownBy(catalogue::partitions)
                        .isInstanceOf(RuntimeException.class);
                final Duration elapsed = Duration.ofNanos(System.nanoTime() - start);

                assertThat(elapsed).isLessThan(Duration.ofSeconds(10));
            } finally {
                blocker.rollback();
            }
        }
    }
}
