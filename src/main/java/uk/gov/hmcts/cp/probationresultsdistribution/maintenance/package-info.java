/**
 * The service's one scheduled task (design §4.1, §4.7; H20, H35): the daily {@code outbound_body}
 * maintenance job. Every run ensures the current UTC month and the next two each have a partition,
 * removes each whole partition past the retention cut-off, deletes stranded {@code DEFAULT}-partition
 * rows before the same cut-off, and publishes the signals that let the "Body partition maintenance"
 * alert page — a {@code DEFAULT} partition that is not empty, a creation or removal failure
 * (FR-003-023), and, independently of any of that, a job that has stopped running at all
 * (FR-003-031, FR-003-032; slice 003 clarification, 2026-10-22). It is not a batch job, not intake
 * and not part of a delivery: it never reads the queue, never sends, never settles a message, and
 * never changes a {@code processed_request} or {@code outbound_message} row.
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
 * port is declared, by its own javadoc, for the per-leg pipeline's use, and {@code config.MetricRegistry}
 * does not yet build an active counter or gauge for any of the four {@code probation_body_*} names it
 * reserves — this package is their sole registrar (research.md "Metrics ownership", 2026-10-22).
 * {@code config} wires this package's {@code @Scheduled} trigger and its two validated properties
 * (none of this package's own classes are {@code @Configuration} or own a bean definition beyond
 * what {@code config} composes); nothing here calls an adapter or a driving adapter.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;
