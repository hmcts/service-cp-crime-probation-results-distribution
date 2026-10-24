package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
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

    private final PartitionMaintenanceRepository repository = new PartitionMaintenanceRepository(
            jdbc(), new TransactionTemplate(new DataSourceTransactionManager(datasource())));

    @Test
    @DisplayName("reflects the database's current count immediately, with no run in progress, never taking the lock")
    void reflects_the_current_count_with_no_run_in_progress() {
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new MaintenanceMetrics(registry, repository);

        final double before = registry.get("probation_body_default_partition_rows").gauge().value();

        // A row inserted directly into DEFAULT, with no job run anywhere near this call.
        final SingleRunnerLock lock = new SingleRunnerLock(datasource());
        assertThat(lock.tryAcquire()).isTrue();
        try {
            insertDefaultRow(OffsetDateTime.now(ZoneOffset.UTC).plusYears(5));
            final double after = registry.get("probation_body_default_partition_rows").gauge().value();
            assertThat(after).isEqualTo(before + 1.0);
        } finally {
            lock.release(false);
        }
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
