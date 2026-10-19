/**
 * The service-owned PostgreSQL store (design §4.5, §4.6, Appendix B.2).
 *
 * <p>{@code processed_request} (one row per request, the per-request state machine and its
 * claim), {@code outbound_message} (one row per leg per attempt, append-preserving) and the
 * {@code outbound_message_latest} view, written through {@code JdbcClient} with the statements
 * stated in the design, and the store probe that gates intake and readiness. The schema is owned
 * by Flyway; every leg outcome is written in the same transaction as the state change it belongs
 * to, and every write a stale runner could make is token-guarded.
 *
 * <p><strong>Dependency direction.</strong> Implements the store ports declared in
 * {@code application} ({@code OutboundLog} and the request store behind the idempotency guard),
 * and depends on those ports and on {@code domain} only. It never calls an adapter, the
 * {@code pipeline} or a driving adapter; the transaction boundary is chosen by the caller in
 * {@code application}.
 */
package uk.gov.hmcts.cp.probationresultsdistribution.persistence;
