package uk.gov.hmcts.cp.probationresultsdistribution.application;

/**
 * The read-before-claim / read-before-remove pause check resend-leg, rollback-close-in-flight and
 * purge-queue share (slice 004, FR-004-021, FR-004-053). Two implementations, for the two processes
 * that need it: a running pod's own {@code inbound.ConsumerLifecycleController}, which reconciles
 * its in-memory, drain-confirmed flag against the persisted {@code intake_control} row on startup
 * and on {@code distribution.store.probe-interval}; and a standalone operator command's
 * {@link ConfirmedIntakePauseGate}, which has no running pod's in-memory state to reconcile against
 * and so reads the store directly, deriving the same confirmation from {@code paused_at} instead
 * (review of PR #8/#23, M3 follow-up).
 */
public interface IntakePauseGate {

    /**
     * The reconciled, in-memory operator-pause flag.
     *
     * @return {@code true} while intake is paused by an operator
     */
    boolean pausedByOperator();
}
