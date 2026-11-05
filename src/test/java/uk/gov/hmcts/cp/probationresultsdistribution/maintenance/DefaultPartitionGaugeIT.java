package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SchemaTestSupport;

@DisplayName("the DEFAULT-partition row gauge")
class DefaultPartitionGaugeIT extends SchemaTestSupport {

    private static final Duration TEST_CACHE_TTL = Duration.ofMillis(200);

    private final PartitionMaintenanceRepository repository = new PartitionMaintenanceRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())));
    private final PartitionCatalogue catalogue = new PartitionCatalogue(jdbc(),
            new TransactionTemplate(new DataSourceTransactionManager(datasource())));
    private final BodyMaintenanceStateRepository stateRepository = new BodyMaintenanceStateRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())));
    private final DatabaseClock clock = new DatabaseClock(jdbc());

    @Test
    @DisplayName("reflects the database's current count with no run in progress, never taking the lock, and is not read every scrape")
    void reflects_the_current_count_within_its_cache_window() throws InterruptedException {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MaintenanceMetrics(registry, repository, catalogue,
                clock, stateRepository, TEST_CACHE_TTL);

        final double before = registry.get("probation_body_default_partition_rows").gauge().value();

        // A row inserted directly into DEFAULT, with no job run anywhere near this call.
        final SingleRunnerLock lock = new SingleRunnerLock(datasource());
        assertThat(lock.tryAcquire()).isTrue();
        try {
            insertDefaultRow(OffsetDateTime.now(ZoneOffset.UTC).plusYears(5));
            final double stillCached =
                    registry.get("probation_body_default_partition_rows").gauge().value();
            assertThat(stillCached).isEqualTo(before);

            Thread.sleep(TEST_CACHE_TTL.plusMillis(100).toMillis());
            final double afterTtl = registry.get("probation_body_default_partition_rows").gauge().value();
            assertThat(afterTtl).isEqualTo(before + 1.0);
        } finally {
            lock.release(false);
        }
    }

    @Test
    @DisplayName("reports -1 on a failed read, without caching the failure")
    void reports_minus_one_on_a_failed_read_without_caching_it() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final PartitionMaintenanceRepository failing =
                org.mockito.Mockito.mock(PartitionMaintenanceRepository.class);
        org.mockito.Mockito.when(failing.countDefaultRows()).thenThrow(
                new PartitionMaintenanceException(
                        uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause
                                .LOCK_TIMEOUT, "count", null))
                .thenReturn(3L);
        new MaintenanceMetrics(registry, failing, catalogue, clock,
                stateRepository, TEST_CACHE_TTL);

        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isEqualTo(-1.0);
        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isEqualTo(3.0);
    }

    private void insertDefaultRow(final OffsetDateTime createdAt) {
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
                    .digest(value.getBytes(StandardCharsets.UTF_8));
            final StringBuilder hex = new StringBuilder();
            for (final byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
