package uk.gov.hmcts.cp.probationresultsdistribution.application;

/**
 * The read-before-claim / read-before-remove pause check resend-leg, rollback-close-in-flight and
 * purge-queue share (slice 004, FR-004-021, FR-004-053). Rollback-close-in-flight and purge-queue
 * read {@link #pausedByOperator()}, confirmed only once a live send this pod (or another replica)
 * could have been making is guaranteed to have finished — a running pod's own
 * {@code inbound.ConsumerLifecycleController} reconciles that in-memory flag against
 * {@code intake_control} on startup and on {@code distribution.store.probe-interval}, and a
 * standalone operator command's {@link ConfirmedIntakePauseGate} derives the same confirmation from
 * {@code paused_at} instead, since it has no running pod's in-memory state to reconcile against
 * (review of PR #8/#23, M3 follow-up). Resend-leg reads {@link #pauseRequested()} instead: it has no
 * in-flight send of its own to race, so it refuses the instant a pause is requested rather than
 * waiting for that confirmation (review of PR #23 round 4; design H43, issue #27).
 */
public interface IntakePauseGate {

    /**
     * The reconciled, in-memory operator-pause flag.
     *
     * @return {@code true} while intake is paused by an operator
     */
    boolean pausedByOperator();

    /**
     * Whether resend-leg must refuse right now (review of PR #23 round 4; design H43, issue #27):
     * {@code true} as soon as an operator pause is requested, never waiting for a live pod's drain
     * confirmation the way {@link #pausedByOperator()} does for rollback-close-in-flight and
     * purge-queue. Those two must wait for the drain, since proceeding against a send still in
     * flight corrupts a terminal row; resend-leg has no such race to avoid, so it is deliberately
     * stricter in the safe direction instead. Defaults to {@link #pausedByOperator()}, which is
     * already the raw, unconfirmed read for any gate — such as the standalone command's resend-leg
     * wiring — that carries no separate, earlier signal of its own.
     *
     * @return {@code true} once a pause has been requested, confirmed or not
     */
    default boolean pauseRequested() {
        return pausedByOperator();
    }
}
