package uk.gov.hmcts.cp.probationresultsdistribution.application;

/**
 * The read-before-claim / read-before-remove pause check resend-leg and purge-queue share (slice
 * 004, FR-004-021, FR-004-053). Implemented by {@code inbound.ConsumerLifecycleController}, which
 * reconciles the in-memory value this port exposes against the persisted {@code intake_control} row
 * on startup and on {@code distribution.store.probe-interval} (research.md Decision 3) — this port
 * is deliberately not another database read of its own.
 */
public interface IntakePauseGate {

    /**
     * The reconciled, in-memory operator-pause flag.
     *
     * @return {@code true} while intake is paused by an operator
     */
    boolean pausedByOperator();
}
