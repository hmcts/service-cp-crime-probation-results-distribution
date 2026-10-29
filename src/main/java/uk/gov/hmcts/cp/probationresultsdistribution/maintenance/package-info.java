/**
 * The service's one scheduled task (design §4.1, §4.7; H20, H35): the daily {@code outbound_body}
 * maintenance job. Every run ensures the current UTC month and the next three each have a partition
 * (H37), removes each whole partition past the retention cut-off, deletes stranded
 * {@code DEFAULT}-partition rows before the same cut-off, and — on a run with no failure — records
 * its success in {@code body_maintenance_state} (H38). It publishes the signals that let the
 * "Body partition maintenance" alert page — a {@code DEFAULT} partition that is not empty, a
 * creation or removal failure (FR-003-023), fewer than two whole months ahead with a partition, and
 * no successful run for more than 48 hours — read from the database independently of the
 * single-runner lock and of whether the job has ever run on this pod, so a stopped job is seen
 * without waiting for the create-ahead buffer to exhaust (FR-003-031, FR-003-032; A3-118, A3-119;
 * H36–H38). It is not a batch job, not intake and not part of a delivery: it never reads the queue,
 * never sends, never settles a message, and never changes a {@code processed_request} or
 * {@code outbound_message} row.
 *
 * <p><strong>Dependency direction.</strong> This package depends on {@code domain} only
 * ({@code BodyMaintenanceStep}, reused from slice 002, and this slice's own
 * {@code MaintenanceFailureCause}) and on infrastructure types that are not internal ports of
 * another package: a raw {@code javax.sql.DataSource} connection for the single-runner advisory
 * lock, {@code JdbcClient}/{@code TransactionTemplate} for every other statement, and Micrometer's
 * {@code MeterRegistry} directly for this slice's own metrics. It never imports {@code application},
 * {@code pipeline}, an {@code adapter} package or {@code inbound} — this job is not part of the
 * per-leg pipeline and has no port of that pipeline's to implement or call (plan.md Constitution
 * Check, Principle V). In particular it does not import {@code application.OutcomeMetrics}: that
 * port is declared, by its own javadoc, for the per-leg pipeline's use. {@code config.MetricRegistry}
 * pre-reserves four {@code probation_body_*} counter/gauge names for this package to increment; the
 * two cached database-backed gauges A3-118 and H38 add — {@code probation_body_partitions_ahead_months}
 * and {@code probation_body_maintenance_last_success_seconds} — are this package's own, registered
 * here and nowhere else (research.md "Metrics ownership", 2026-10-22; {@code MaintenanceMetrics}'s own
 * sync test against {@code config.MetricRegistry.METRIC_NAMES}).
 * {@code config} wires this package's {@code @Scheduled} trigger and its two validated properties
 * (none of this package's own classes are {@code @Configuration} or own a bean definition beyond
 * what {@code config} composes); nothing here calls an adapter or a driving adapter.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;
