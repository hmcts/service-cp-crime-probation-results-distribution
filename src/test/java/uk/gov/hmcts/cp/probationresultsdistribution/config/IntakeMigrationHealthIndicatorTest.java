package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Status;

@DisplayName("the deferred-migration readiness signal (review of PR #23)")
class IntakeMigrationHealthIndicatorTest {

    @Test
    @DisplayName("reports DOWN before the migration has run")
    void reports_down_before_migration() {
        final IntakeMigrationHealthIndicator health =
                new IntakeMigrationHealthIndicator(() -> false);

        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
    }

    @Test
    @DisplayName("reports UP once the migration has run")
    void reports_up_after_migration() {
        final IntakeMigrationHealthIndicator health =
                new IntakeMigrationHealthIndicator(() -> true);

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("re-reads the supplier on every call, so a late migration flips it to UP without "
            + "a new bean")
    void reflects_a_later_migration_without_a_new_instance() {
        final AtomicBoolean migrated = new AtomicBoolean(false);
        final IntakeMigrationHealthIndicator health =
                new IntakeMigrationHealthIndicator(migrated::get);

        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);

        migrated.set(true);

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }
}
