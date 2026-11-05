package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.MessageDigest;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

@DisplayName("the partition maintenance repository")
class PartitionMaintenanceRepositoryIT extends SchemaTestSupport {

    private final PartitionMaintenanceRepository repository = new PartitionMaintenanceRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())));
    private final PartitionCatalogue catalogue = new PartitionCatalogue(jdbc(),
            new TransactionTemplate(new DataSourceTransactionManager(datasource())));

    @Nested
    @DisplayName("createPartition")
    class CreatePartition {

        @Test
        @DisplayName("creates a missing month with V1's name and bounds")
        void creates_a_missing_month() {
            final OffsetDateTime from = OffsetDateTime.of(2030, 3, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime to = OffsetDateTime.of(2030, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);

            repository.createPartition("outbound_body_2030_03", from, to);

            final List<PartitionBound> partitions = catalogue.partitions();
            assertThat(partitions).anySatisfy(p -> {
                assertThat(p.partitionName()).isEqualTo("outbound_body_2030_03");
                assertThat(p.from()).isEqualTo(from);
                assertThat(p.to()).isEqualTo(to);
            });
        }

        @Test
        @DisplayName("is a no-op when the name already exists")
        void is_a_no_op_when_already_present() {
            final OffsetDateTime from = OffsetDateTime.of(2030, 4, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime to = OffsetDateTime.of(2030, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            repository.createPartition("outbound_body_2030_04", from, to);

            repository.createPartition("outbound_body_2030_04", from, to);

            assertThat(catalogue.partitions())
                    .filteredOn(p -> "outbound_body_2030_04".equals(p.partitionName()))
                    .hasSize(1);
        }

        @Test
        @DisplayName("fails with PARTITION_OVERLAP when another table already covers the range")
        void fails_on_overlap() {
            final OffsetDateTime from = OffsetDateTime.of(2030, 5, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime to = OffsetDateTime.of(2030, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            repository.createPartition("outbound_body_2030_05_under_another_name", from, to);

            assertThatThrownBy(() -> repository.createPartition("outbound_body_2030_05", from, to))
                    .isInstanceOf(PartitionMaintenanceException.class)
                    .extracting(e -> ((PartitionMaintenanceException) e).cause())
                    .isEqualTo(MaintenanceFailureCause.PARTITION_OVERLAP);
        }

        @Test
        @DisplayName("fails with DEFAULT_HAS_ROWS when the DEFAULT partition holds rows of that month")
        void fails_when_default_has_rows() {
            final OffsetDateTime from = OffsetDateTime.of(2030, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime to = OffsetDateTime.of(2030, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            insertBody(from.plusDays(1));

            assertThatThrownBy(() -> repository.createPartition("outbound_body_2030_06", from, to))
                    .isInstanceOf(PartitionMaintenanceException.class)
                    .extracting(e -> ((PartitionMaintenanceException) e).cause())
                    .isEqualTo(MaintenanceFailureCause.DEFAULT_HAS_ROWS);
        }
    }

    @Nested
    @DisplayName("dropPartition")
    class DropPartition {

        @Test
        @DisplayName("drops the named partition and every row in it, independent of the caller's transaction")
        void drops_the_partition() {
            final OffsetDateTime from = OffsetDateTime.of(2030, 7, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final OffsetDateTime to = OffsetDateTime.of(2030, 8, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            repository.createPartition("outbound_body_2030_07", from, to);

            repository.dropPartition("outbound_body_2030_07");

            assertThat(catalogue.partitions())
                    .noneMatch(p -> "outbound_body_2030_07".equals(p.partitionName()));
        }
    }

    @Nested
    @DisplayName("deleteDefaultRowsBefore and countDefaultRows")
    class DefaultPartitionOperations {

        @Test
        @DisplayName("deletes only DEFAULT rows older than the cut-off, read-only count never takes the lock")
        void deletes_only_rows_before_the_cutoff() {
            // The container and schema are shared across this whole test class (SchemaTestSupport),
            // so other tests may have already left rows in DEFAULT — assert relative deltas, not an
            // absolute count.
            final OffsetDateTime cutoff = OffsetDateTime.of(2029, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
            final long before = repository.countDefaultRows();
            insertBody(cutoff.minusDays(10));
            insertBody(cutoff.plusDays(10));

            final long deleted = repository.deleteDefaultRowsBefore(cutoff);

            assertThat(deleted).isEqualTo(1L);
            assertThat(repository.countDefaultRows()).isEqualTo(before + 1L);
        }
    }

    @Test
    void drop_should_commit_even_when_callers_transaction_rolls_back() {
        final OffsetDateTime from = OffsetDateTime.parse("2042-01-01T00:00:00Z");
        repository.createPartition("outbound_body_2042_01", from, from.plusMonths(1));
        final TransactionTemplate outer = new TransactionTemplate(
                new DataSourceTransactionManager(datasource()));

        outer.executeWithoutResult(status -> {
            repository.dropPartition("outbound_body_2042_01");
            status.setRollbackOnly();
        });

        assertThat(catalogue.partitions())
                .noneMatch(p -> "outbound_body_2042_01".equals(p.partitionName()));
    }

    private void insertBody(final OffsetDateTime createdAt) {
        final String source = "RESULTS";
        final String requestId = UUID.randomUUID().toString();
        insertReceivedRequest(source, requestId);
        final UUID messageId = UUID.randomUUID();
        final String body = "{\"hearing\":{\"id\":\"" + messageId + "\"}}";
        final String digest = sha256Hex(body);
        execute("INSERT INTO outbound_message (id, source, request_id, leg, status, http_status, "
                + "body_digest, payload_source, service_version, youth_switch, sent_at) VALUES ("
                + "'" + messageId + "', '" + source + "', '" + requestId + "', 'HMPPS', 'SENT', 200, "
                + "'" + digest + "', 'cache-day', 'test-version', 'ACTIVE', now())");
        execute("INSERT INTO outbound_body (outbound_message_id, body, created_at) VALUES ("
                + "'" + messageId + "', '" + body.replace("'", "''") + "', "
                + "TIMESTAMPTZ '" + createdAt + "')");
    }

    private static String sha256Hex(final String value) {
        try {
            final byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (final byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
