/**
 * The service-owned PostgreSQL store (design §4.5, §4.6, Appendix B.2).
 *
 * <p>Three tables and one view: {@code processed_request} (one row per request, the per-request
 * state machine and its claim, and the admitted command a replay re-sends),
 * {@code outbound_message} (one row per leg per attempt, append-preserving), the
 * {@code outbound_message_latest} view, and {@code outbound_body} (the exact bytes POSTed for an
 * attempt, design H18-H20); all written through {@code JdbcClient} with the statements stated in
 * the design. The package also holds the store probe that gates intake and readiness. The schema
 * is owned by Flyway; every leg outcome is written in the same transaction as the state change it
 * belongs to, and every write a stale runner could make is token-guarded.
 *
 * <p>{@code outbound_body} is partitioned by calendar month in UTC, with a DEFAULT partition. A
 * body row is inserted after the attempt's outcome UPDATE, in the same transaction; an in-place
 * re-run replaces it; and a trigger refuses a body whose SHA-256 differs from the attempt's
 * {@code body_digest}. Whole months are dropped by the daily body-retention job once past the
 * retention (design H20), so a body is kept 6-7 months while the digest stays on the attempt row.
 * Stored bodies are never logged.
 *
 * <p><strong>Dependency direction.</strong> Implements the store ports declared in
 * {@code application} ({@code OutboundLog} and the request store behind the idempotency guard),
 * and depends on those ports and on {@code domain} only. It never calls an adapter, the
 * {@code pipeline} or a driving adapter; the transaction boundary is chosen by the caller in
 * {@code application}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.persistence;
