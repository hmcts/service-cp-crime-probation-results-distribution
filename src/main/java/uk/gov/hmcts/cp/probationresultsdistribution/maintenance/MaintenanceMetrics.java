package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicReference;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep;

/**
 * This slice's own five metrics, registered directly against the raw Micrometer {@link MeterRegistry}
 * — never through {@code application.OutcomeMetrics} (research.md "Metrics ownership", 2026-10-22).
 * The names below are duplicated, by value, from {@code config.MetricRegistry.METRIC_NAMES}'s four
 * pre-reserved {@code probation_body_*} entries plus the fifth, new one; a unit test asserts the two
 * catalogues agree, so production code never needs to import {@code config}.
 */
public final class MaintenanceMetrics {

    private static final String PARTITIONS_CREATED_M = "probation_body_partitions_created_total";
    private static final String PARTITIONS_DROPPED_M = "probation_body_partitions_dropped_total";
    private static final String MAINTENANCE_FAILURES_M = "probation_body_maintenance_failures_total";
    private static final String DEFAULT_ROWS_M = "probation_body_default_partition_rows";
    private static final String LAST_SUCCESS_AGE_M = "probation_body_job_last_success_age_seconds";
    private static final String TAG_STEP = "step";

    /**
     * Every metric name this class owns, for the sync-check test against
     * {@code config.MetricRegistry.METRIC_NAMES}.
     */
    public static final java.util.Set<String> METRIC_NAMES = java.util.Set.of(PARTITIONS_CREATED_M,
            PARTITIONS_DROPPED_M, MAINTENANCE_FAILURES_M, DEFAULT_ROWS_M, LAST_SUCCESS_AGE_M);

    private final MeterRegistry registry;
    private final AtomicReference<Instant> lastSuccess =
            new AtomicReference<>(Instant.EPOCH);

    /**
     * Creates and registers this slice's metrics.
     *
     * @param registry   the Micrometer registry to register against
     * @param repository the repository the DEFAULT-rows gauge reads live, at every scrape
     */
    public MaintenanceMetrics(final MeterRegistry registry,
                              final PartitionMaintenanceRepository repository) {
        this.registry = registry;
        Gauge.builder(DEFAULT_ROWS_M, repository, MaintenanceMetrics::safeDefaultRowCount)
                .register(registry);
        Gauge.builder(LAST_SUCCESS_AGE_M, lastSuccess,
                        ref -> Duration.between(ref.get(), Instant.now()).toSeconds())
                .register(registry);
    }

    /**
     * Counts one partition created (FR-003-020).
     */
    public void partitionCreated() {
        registry.counter(PARTITIONS_CREATED_M).increment();
    }

    /**
     * Counts one partition dropped (FR-003-020).
     */
    public void partitionDropped() {
        registry.counter(PARTITIONS_DROPPED_M).increment();
    }

    /**
     * Counts one maintenance failure under its step (FR-003-021).
     *
     * @param step the bounded step the failure is counted under
     */
    public void maintenanceFailure(final BodyMaintenanceStep step) {
        registry.counter(MAINTENANCE_FAILURES_M, Tags.of(Tag.of(TAG_STEP, step.wireValue())))
                .increment();
    }

    /**
     * Records a run reaching its final commit, resetting the last-success-age gauge to (effectively)
     * zero from this instant on.
     *
     * @param when the instant the run committed
     */
    public void recordSuccess(final Instant when) {
        lastSuccess.set(when);
    }

    // A Micrometer value function must never throw: a lock-timeout or a lost connection on this
    // live read would otherwise fail the whole scrape, not just this one series (FR-003-031).
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    private static double safeDefaultRowCount(final PartitionMaintenanceRepository repository) {
        try {
            return repository.countDefaultRows();
        } catch (final RuntimeException e) {
            return Double.NaN;
        }
    }
}
