package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("body_maintenance_state (H38)")
class BodyMaintenanceStateRepositoryIT extends MaintenanceTestStore {

    @Test
    @DisplayName("V2 seeds exactly one row, with a recent migration-time value")
    void migration_seeds_exactly_one_row() {
        final long rows = sql.sql("SELECT count(*) FROM body_maintenance_state")
                .query(Long.class).single();
        assertThat(rows).isEqualTo(1L);

        final OffsetDateTime seeded = stateRepository.lastSuccessAt();
        assertThat(seeded).isCloseTo(OffsetDateTime.now(java.time.ZoneOffset.UTC),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
    }

    @Test
    @DisplayName("records success on the given connection, under the database's own wall clock, affecting exactly the one row")
    void records_success_on_the_given_connection() throws Exception {
        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            stateRepository.recordSuccessOnConnection(connection);
            connection.commit();
        }

        final OffsetDateTime recorded = stateRepository.lastSuccessAt();
        assertThat(recorded).isCloseTo(OffsetDateTime.now(java.time.ZoneOffset.UTC),
                org.assertj.core.api.Assertions.within(1, java.time.temporal.ChronoUnit.MINUTES));
        assertThat(sql.sql("SELECT count(*) FROM body_maintenance_state").query(Long.class).single())
                .isEqualTo(1L);
    }

    @Test
    @DisplayName("a failed update rolls back to its savepoint, leaving the row unchanged, and is reported as a bounded failure")
    void a_failed_update_leaves_the_row_unchanged() throws Exception {
        final OffsetDateTime before = stateRepository.lastSuccessAt();

        try (Connection connection = source.getConnection()) {
            connection.setAutoCommit(false);
            // A transaction whose own statement already failed cannot accept a further statement
            // without a savepoint to roll back to first — forcing recordSuccessOnConnection's own
            // UPDATE to fail, exactly as a lock-timeout or a lost connection would in production.
            try (java.sql.Statement broken = connection.createStatement()) {
                org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                        broken.execute("SELECT 1/0")).isInstanceOf(java.sql.SQLException.class);
            }

            org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                    stateRepository.recordSuccessOnConnection(connection))
                    .isInstanceOf(PartitionMaintenanceException.class);
            connection.rollback();
        }

        assertThat(stateRepository.lastSuccessAt()).isEqualTo(before);
    }
}
