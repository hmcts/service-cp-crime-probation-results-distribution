package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Tags;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Set;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep;

/**
 * This slice's own six metrics, registered directly against the raw Micrometer {@link MeterRegistry}
 * — never through {@code application.OutcomeMetrics} (research.md "Metrics ownership", 2026-10-22).
 * The three counter names below are duplicated, by value, from {@code config.MetricRegistry}'s
 * pre-reserved {@code probation_body_*} entries; the three gauges — the DEFAULT-row count, the
 * consecutive months ahead and the last-success instant in epoch seconds — are this slice's own
 * (A3-118; H36; H38), read from the database whenever the metrics are collected, on every pod, lock
 * or not, at most five minutes old (a cached read); a failed read reports {@code -1} and is never
 * cached (FR-003-022, FR-003-031, FR-003-032). A unit test asserts the counter names agree with
 * {@code config.MetricRegistry.METRIC_NAMES}, so production code never needs to import {@code config}.
 */
public final class MaintenanceMetrics {

    private static final String PARTITIONS_CREATED_M = "probation_body_partitions_created_total";
    private static final String PARTITIONS_DROPPED_M = "probation_body_partitions_dropped_total";
    private static final String MAINTENANCE_FAILURES_M = "probation_body_maintenance_failures_total";
    private static final String DEFAULT_ROWS_M = "probation_body_default_partition_rows";
    private static final String MONTHS_AHEAD_M = "probation_body_partitions_ahead_months";
    private static final String LAST_SUCCESS_SECONDS_M =
            "probation_body_maintenance_last_success_seconds";
    private static final String TAG_STEP = "step";
    private static final Duration DEFAULT_CACHE_TTL = Duration.ofMinutes(5);

    /**
     * Every metric name this class owns, for the sync-check test against
     * {@code config.MetricRegistry.METRIC_NAMES}.
     */
    public static final Set<String> METRIC_NAMES = Set.of(PARTITIONS_CREATED_M, PARTITIONS_DROPPED_M,
            MAINTENANCE_FAILURES_M, DEFAULT_ROWS_M, MONTHS_AHEAD_M, LAST_SUCCESS_SECONDS_M);

    private final MeterRegistry registry;

    /**
     * Creates and registers this slice's metrics, with the production five-minute cache TTL.
     *
     * @param registry         the Micrometer registry to register against
     * @param repository       the repository the DEFAULT-rows gauge reads
     * @param catalogue        the catalogue the months-ahead gauge reads
     * @param clock            the database clock the months-ahead gauge's "current month" is taken from
     * @param stateRepository  the repository the last-success gauge reads
     */
    public MaintenanceMetrics(final MeterRegistry registry,
                              final PartitionMaintenanceRepository repository,
                              final PartitionCatalogue catalogue, final DatabaseClock clock,
                              final BodyMaintenanceStateRepository stateRepository) {
        this(registry, repository, catalogue, clock, stateRepository, DEFAULT_CACHE_TTL);
    }

    // Package-private: lets this slice's own tests prove the five-minute caching behaviour (both
    // "stays cached within the window" and "refreshes once it elapses") without a real five-minute
    // wait, by substituting a short TTL. Production always uses the public constructor above.
    /* default */ MaintenanceMetrics(final MeterRegistry registry,
                                     final PartitionMaintenanceRepository repository,
                                     final PartitionCatalogue catalogue, final DatabaseClock clock,
                                     final BodyMaintenanceStateRepository stateRepository,
                                     final Duration cacheTtl) {
        this.registry = registry;
        final CachedGaugeRead defaultRows =
                new CachedGaugeRead(repository::countDefaultRows, cacheTtl);
        final CachedGaugeRead monthsAhead = new CachedGaugeRead(
                () -> catalogue.monthsAhead(currentMonthStart(clock.nowUtc())), cacheTtl);
        final CachedGaugeRead lastSuccessSeconds = new CachedGaugeRead(
                () -> stateRepository.lastSuccessAt().toEpochSecond(), cacheTtl);
        Gauge.builder(DEFAULT_ROWS_M, defaultRows, CachedGaugeRead::value).register(registry);
        Gauge.builder(MONTHS_AHEAD_M, monthsAhead, CachedGaugeRead::value).register(registry);
        Gauge.builder(LAST_SUCCESS_SECONDS_M, lastSuccessSeconds, CachedGaugeRead::value)
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
     * Counts one maintenance failure under its step (FR-003-021). The step set stays closed to
     * {@code lock}, {@code create} and {@code drop}: a {@code state} or {@code read} failure
     * (FR-003-032) is logged and summarised but never reaches this counter.
     *
     * @param step the bounded step the failure is counted under
     */
    public void maintenanceFailure(final BodyMaintenanceStep step) {
        registry.counter(MAINTENANCE_FAILURES_M, Tags.of(Tag.of(TAG_STEP, step.wireValue())))
                .increment();
    }

    private static OffsetDateTime currentMonthStart(final OffsetDateTime now) {
        return now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }
}
