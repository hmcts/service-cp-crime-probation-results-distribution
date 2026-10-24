package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.BodyMaintenanceStep;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.MaintenanceFailureCause;

/**
 * The service's one scheduled task (FR-003-001): every run ensures the current UTC month and the
 * next three each have a partition (H37), removes each whole partition past the retention cut-off,
 * deletes stranded DEFAULT-partition rows before the same cut-off, and — on a run that held the lock
 * and found no failure — records its success in {@code body_maintenance_state} (H38), under a
 * single-runner advisory lock.
 *
 * <p>Logs one INFO start line on every trigger, one INFO skip line from a pod that does not get the
 * lock, and one INFO summary line from a run that held it (A3-119). Carries no request fields in its
 * log lines (AS-10: it concerns no request) and never logs a stored body or the database's own error
 * text at any level (FR-003-017, FR-003-024).
 */
public final class BodyRetentionJob {

    private static final Logger LOG = LoggerFactory.getLogger(BodyRetentionJob.class);

    private static final int MONTHS_AHEAD_CREATED = 4;
    private static final int START_WARN_BELOW_MONTHS = 2;
    private static final int END_WARN_BELOW_MONTHS = 3;
    private static final String STEP_STATE = "state";
    private static final String STEP_READ = "read";

    private final DatabaseClock clock;
    private final PartitionCatalogue catalogue;
    private final PartitionMaintenanceRepository repository;
    private final Period retention;
    private final Supplier<SingleRunnerLock> lockSupplier;
    private final MaintenanceMetrics metrics;
    private final BodyMaintenanceStateRepository stateRepository;

    /**
     * Creates the job over its collaborators.
     *
     * @param clock           the database-clock reader "now" is taken from
     * @param catalogue       the catalogue reader this run's absent/expired months are decided from
     * @param repository      the repository every partition statement runs through
     * @param retention       the whole-months retention the cut-off is computed from (FR-003-009)
     * @param lockSupplier    supplies a fresh {@link SingleRunnerLock} for each run (FR-003-002)
     * @param metrics         this slice's own counters and gauges (FR-003-020 to FR-003-022)
     * @param stateRepository the {@code body_maintenance_state} repository a successful run records
     *                        into (FR-003-032)
     */
    public BodyRetentionJob(final DatabaseClock clock, final PartitionCatalogue catalogue,
                            final PartitionMaintenanceRepository repository,
                            final Period retention, final Supplier<SingleRunnerLock> lockSupplier,
                            final MaintenanceMetrics metrics,
                            final BodyMaintenanceStateRepository stateRepository) {
        this.clock = clock;
        this.catalogue = catalogue;
        this.repository = repository;
        this.retention = retention;
        this.lockSupplier = lockSupplier;
        this.metrics = metrics;
        this.stateRepository = stateRepository;
    }

    /**
     * Runs the job once: skips entirely if the single-runner lock is not acquired, otherwise
     * performs the full run and releases the lock by committing on success or rolling back (and
     * rethrowing) on an unexpected failure.
     *
     * @return this run's outcome
     */
    @org.springframework.scheduling.annotation.Scheduled(
            cron = "${distribution.body-maintenance.cron}", zone = "UTC")
    @SuppressWarnings({"PMD.AvoidCatchingGenericException", "PMD.OnlyOneReturn"})
    public MaintenanceRunOutcome run() {
        LOG.info("body-retention maintenance triggered");
        final SingleRunnerLock lock = lockSupplier.get();
        final boolean lockAcquired;
        try {
            lockAcquired = lock.tryAcquire();
        } catch (final RuntimeException e) {
            // Classified by the same bounded lock-step cause used on every other failure path
            // (FR-003-003's own example: "the store being unreachable" is one possible cause, not
            // the only one); never rethrown past this point.
            final MaintenanceFailureCause cause = boundedCause(e);
            LOG.error("body-retention maintenance failed: step=lock cause={} sqlState={}",
                    cause.wireValue(), boundedSqlState(e));
            metrics.maintenanceFailure(BodyMaintenanceStep.LOCK);
            return new MaintenanceRunOutcome(false, List.of(), List.of(), 0L, List.of(
                    new MaintenanceRunOutcome.MaintenanceFailure(BodyMaintenanceStep.LOCK,
                            cause, "lock")));
        }
        if (!lockAcquired) {
            LOG.info("body-retention maintenance skipped: lock not acquired");
            return new MaintenanceRunOutcome(false, List.of(), List.of(), 0L, List.of());
        }
        return runAndRelease(lock);
    }

    // Every unexpected failure becomes a bounded, counted failure after cleanup, never a success.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private MaintenanceRunOutcome runAndRelease(final SingleRunnerLock lock) {
        final MaintenanceRunOutcome result;
        try {
            result = runUnderLock(lock);
            lock.release(true);
        } catch (final RuntimeException e) {
            throw failedRun(lock, e);
        }
        return result;
    }

    // A failed cleanup must still be surfaced; neither exception's raw database text may escape.
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private PartitionMaintenanceException failedRun(final SingleRunnerLock lock,
                                                     final RuntimeException failure) {
        MaintenanceFailureCause cause = boundedCause(failure);
        String sqlState = boundedSqlState(failure);
        try {
            lock.release(false);
        } catch (final RuntimeException cleanupFailure) {
            cause = boundedCause(cleanupFailure);
            sqlState = boundedSqlState(cleanupFailure);
        }
        metrics.maintenanceFailure(BodyMaintenanceStep.LOCK);
        LOG.error("body-retention maintenance failed: step=lock cause={} sqlState={}",
                cause.wireValue(), sqlState);
        return new PartitionMaintenanceException(cause, "run", failure);
    }

    private static MaintenanceFailureCause boundedCause(final RuntimeException failure) {
        return failure instanceof PartitionMaintenanceException classified
                ? classified.cause() : MaintenanceFailureCause.OTHER;
    }

    private static String boundedSqlState(final RuntimeException failure) {
        return failure instanceof PartitionMaintenanceException classified
                ? classified.sqlState() : PartitionMaintenanceException.UNAVAILABLE_SQL_STATE;
    }

    private MaintenanceRunOutcome runUnderLock(final SingleRunnerLock lock) {
        final long startNanos = System.nanoTime();
        final OffsetDateTime currentMonth = currentMonthStart(clock.nowUtc());
        final List<PartitionBound> partitions = catalogue.partitions();
        final int startMonthsAhead = PartitionCatalogue.monthsAhead(currentMonth, partitions);
        if (startMonthsAhead < START_WARN_BELOW_MONTHS) {
            LOG.warn("body-retention maintenance: {} whole months ahead at start (fewer than {})",
                    startMonthsAhead, START_WARN_BELOW_MONTHS);
        }
        final List<MaintenanceRunOutcome.MaintenanceFailure> failures = new ArrayList<>();
        final List<String> created = createAhead(currentMonth, partitions, failures);
        final OffsetDateTime cutoff = currentMonth.minusMonths(retention.toTotalMonths());
        final List<String> dropped = dropExpired(partitions, cutoff, failures);
        final long deleted = deleteDefault(cutoff, failures);

        recordSuccessAndSummarise(lock, currentMonth, failures, created, dropped, startNanos);
        return new MaintenanceRunOutcome(true, created, dropped, deleted, failures);
    }

    // Reads the fresh end-of-run signals (never the metrics cache), logs the end-of-run WARN when
    // due, records this run's success in body_maintenance_state when nothing failed, and logs the
    // run's one summary line. A "state"/"read" failure is summarised but never counted on the
    // maintenanceFailure metric (FR-003-021's step set stays closed).
    private void recordSuccessAndSummarise(final SingleRunnerLock lock,
                                           final OffsetDateTime currentMonth,
                                           final List<MaintenanceRunOutcome.MaintenanceFailure> failures,
                                           final List<String> created, final List<String> dropped,
                                           final long startNanos) {
        int extraFailures = 0;
        long monthsAheadEnd = -1;
        try {
            monthsAheadEnd = catalogue.monthsAhead(currentMonth);
        } catch (final PartitionMaintenanceException e) {
            logUnstepped(STEP_READ, e);
            extraFailures++;
        }
        long defaultRowsEnd = -1;
        try {
            defaultRowsEnd = repository.countDefaultRows();
        } catch (final PartitionMaintenanceException e) {
            logUnstepped(STEP_READ, e);
            extraFailures++;
        }
        final boolean readsOk = monthsAheadEnd >= 0 && defaultRowsEnd >= 0;
        if (failures.isEmpty() && readsOk && monthsAheadEnd < END_WARN_BELOW_MONTHS) {
            LOG.warn("body-retention maintenance: {} whole months ahead at end (fewer than {})",
                    monthsAheadEnd, END_WARN_BELOW_MONTHS);
        }
        if (failures.isEmpty() && readsOk) {
            try {
                stateRepository.recordSuccessOnConnection(lock.heldConnection());
            } catch (final PartitionMaintenanceException e) {
                logUnstepped(STEP_STATE, e);
                extraFailures++;
            }
        }
        final long durationMs = Duration.ofNanos(System.nanoTime() - startNanos).toMillis();
        LOG.info("body-retention maintenance finished: created={} dropped={} failed={} "
                        + "monthsAhead={} defaultRows={} durationMs={}",
                created.size(), dropped.size(), failures.size() + extraFailures, monthsAheadEnd,
                defaultRowsEnd, durationMs);
    }

    private static void logUnstepped(final String step, final PartitionMaintenanceException failure) {
        LOG.error("body-retention maintenance failed: step={} cause={} sqlState={}",
                step, failure.cause().wireValue(), failure.sqlState());
    }

    private List<String> createAhead(final OffsetDateTime currentMonth,
                                     final List<PartitionBound> partitions,
                                     final List<MaintenanceRunOutcome.MaintenanceFailure> failures) {
        final Set<String> existing = partitions.stream().map(PartitionBound::partitionName)
                .collect(Collectors.toSet());
        final List<String> created = new ArrayList<>();
        for (int i = 0; i < MONTHS_AHEAD_CREATED; i++) {
            final OffsetDateTime from = currentMonth.plusMonths(i);
            final String name = partitionName(from);
            if (existing.contains(name) || catalogue.tableExists(name)) {
                continue;
            }
            try {
                repository.createPartition(name, from, from.plusMonths(1));
                created.add(name);
                metrics.partitionCreated();
                LOG.info("body-retention maintenance: created partition {}", name);
            } catch (final PartitionMaintenanceException e) {
                recordFailure(failures, BodyMaintenanceStep.CREATE, name, e);
            }
        }
        return created;
    }

    private List<String> dropExpired(final List<PartitionBound> partitions,
                                     final OffsetDateTime cutoff,
                                     final List<MaintenanceRunOutcome.MaintenanceFailure> failures) {
        final List<String> dropped = new ArrayList<>();
        for (final PartitionBound partition : partitions) {
            if (partition.isDefault() || partition.to().isAfter(cutoff)) {
                continue;
            }
            try {
                repository.dropPartition(partition.partitionName());
                dropped.add(partition.partitionName());
                metrics.partitionDropped();
                LOG.info("body-retention maintenance: dropped partition {}", partition.partitionName());
            } catch (final PartitionMaintenanceException e) {
                recordFailure(failures, BodyMaintenanceStep.DROP, partition.partitionName(), e);
            }
        }
        return dropped;
    }

    private long deleteDefault(final OffsetDateTime cutoff,
                                final List<MaintenanceRunOutcome.MaintenanceFailure> failures) {
        long deleted = 0;
        try {
            deleted = repository.deleteDefaultRowsBefore(cutoff);
        } catch (final PartitionMaintenanceException e) {
            // AS-7: a DEFAULT-delete failure is part of the purge, counted under drop.
            recordFailure(failures, BodyMaintenanceStep.DROP, "outbound_body_default", e);
        }
        return deleted;
    }

    private void recordFailure(final List<MaintenanceRunOutcome.MaintenanceFailure> failures,
                                final BodyMaintenanceStep step, final String month,
                                final PartitionMaintenanceException failure) {
        failures.add(new MaintenanceRunOutcome.MaintenanceFailure(step, failure.cause(), month));
        metrics.maintenanceFailure(step);
        LOG.error("body-retention maintenance failed: step={} month={} cause={} sqlState={}",
                step.wireValue(), month, failure.cause().wireValue(), failure.sqlState());
    }

    private static OffsetDateTime currentMonthStart(final OffsetDateTime now) {
        return now.withDayOfMonth(1).withHour(0).withMinute(0).withSecond(0).withNano(0);
    }

    private static String partitionName(final OffsetDateTime month) {
        return String.format(Locale.ROOT, "outbound_body_%04d_%02d", month.getYear(),
                month.getMonthValue());
    }
}
