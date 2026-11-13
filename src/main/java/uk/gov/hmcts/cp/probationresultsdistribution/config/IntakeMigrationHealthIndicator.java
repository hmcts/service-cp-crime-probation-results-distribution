package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.util.function.BooleanSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/**
 * Reports DOWN until the deferred Flyway migration has succeeded (review of PR #23). With
 * migration no longer running eagerly at context refresh, a database that answers {@code db}'s own
 * probe while the schema migration itself fails against it — a checksum mismatch, a missing DDL
 * grant — would otherwise leave readiness depending only on connectivity, never on whether this
 * service's own tables exist: a rollout could replace a working pod with one that can never gate
 * its own start and never consumes a single message, all while reporting ready.
 */
public final class IntakeMigrationHealthIndicator implements HealthIndicator {

    private final BooleanSupplier migrationCompleted;

    /**
     * Creates the indicator.
     *
     * @param migrationCompleted whether the deferred migration has run
     */
    public IntakeMigrationHealthIndicator(final BooleanSupplier migrationCompleted) {
        this.migrationCompleted = migrationCompleted;
    }

    @Override
    public Health health() {
        return migrationCompleted.getAsBoolean() ? Health.up().build() : Health.down().build();
    }
}
