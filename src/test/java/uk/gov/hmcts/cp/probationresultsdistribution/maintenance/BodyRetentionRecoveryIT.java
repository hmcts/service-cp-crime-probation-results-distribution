package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

class BodyRetentionRecoveryIT extends MaintenanceTestStore {

    @Test
    void documented_procedure_should_recover_spill_preserving_bytes_and_digest() throws IOException {
        final OffsetDateTime month = OffsetDateTime.parse("2044-10-01T00:00:00Z");
        final UUID message = insertBody(month.plusDays(2));
        final String before = sql.sql("SELECT body FROM outbound_body WHERE outbound_message_id=:id")
                .param("id", message).query(String.class).single();
        final List<String> attemptsBefore = attempts();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MaintenanceMetrics metrics = new MaintenanceMetrics(registry, repository);
        final MaintenanceRunOutcome refused = job(month.plusDays(4), Period.ofMonths(6), metrics).run();
        assertThat(refused.failures()).anySatisfy(failure ->
                assertThat(failure.cause()).isEqualTo(MaintenanceFailureCause.DEFAULT_HAS_ROWS));
        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isEqualTo(1);
        assertThat(registry.get("probation_body_maintenance_failures_total").tag("step", "create")
                .counter().count()).isEqualTo(1);

        final Path runbook = Path.of("doc/operations/body-partition-maintenance.md");
        assertThat(runbook).exists();
        final String documentation = Files.readString(runbook);
        assertThat(documentation).contains("distribution.consumer.enabled=false", "in-flight",
                "older than the cut-off");
        final String script = documentation.split("<!-- recovery-sql:start -->")[1]
                .split("<!-- recovery-sql:end -->")[0].replace("```sql", "").replace("```", "")
                .replace(":\"partition\"", name(month))
                .replace(":'from'", "'" + month + "'")
                .replace(":'to'", "'" + month.plusMonths(1) + "'");
        try (java.sql.Connection connection = source.getConnection();
                java.sql.Statement statement = connection.createStatement()) {
            for (final String command : script.split(";")) {
                if (!command.isBlank()) {
                    statement.execute(command);
                }
            }
        } catch (final java.sql.SQLException e) {
            throw new IllegalStateException("Recovery SQL failed", e);
        }

        final MaintenanceRunOutcome recovered = job(month.plusDays(5), Period.ofMonths(6), metrics).run();
        assertThat(recovered.failures()).isEmpty();
        assertThat(registry.get("probation_body_default_partition_rows").gauge().value()).isZero();
        assertThat(sql.sql("SELECT body FROM outbound_body WHERE outbound_message_id=:id")
                .param("id", message).query(String.class).single()).isEqualTo(before);
        assertThat(sql.sql("SELECT tableoid::regclass::text FROM outbound_body WHERE outbound_message_id=:id")
                .param("id", message).query(String.class).single()).isEqualTo(name(month));
        assertThat(sql.sql("SELECT count(*) FROM outbound_body b JOIN outbound_message m "
                + "ON m.id=b.outbound_message_id WHERE encode(sha256(convert_to(b.body, 'UTF8')), "
                + "'hex')=m.body_digest").query(Long.class).single()).isEqualTo(1);
        assertThat(attempts()).isEqualTo(attemptsBefore);
    }
}
