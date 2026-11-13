package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Duration;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;

/**
 * The standalone operator command's {@link IntakePauseGate} for rollback-close-in-flight and
 * purge-queue (review of PR #8/#23, M3 follow-up): a CLI invocation has no running pod's confirmed,
 * drain-aware pause flag to reconcile against — that lives only in
 * {@code inbound.ConsumerLifecycleController}'s memory — so it reads {@code intake_control}
 * directly and applies the same confirmation window a live pod would have needed to reach
 * {@code SUSPENDED}: one more probe tick after {@code paused_at} to notice the request, then the
 * full drain wait for an in-flight delivery to finish. Before that window has elapsed,
 * {@code pausedByOperator()} answers {@code false} — exactly as if intake were still running — so
 * rollback-close-in-flight and purge-queue refuse rather than racing a send a pod may still be
 * making. The comparison itself runs in SQL ({@link IntakeControlRepository#pausedForAtLeast}), so
 * it never depends on this process's clock agreeing with the database's (review of PR #23 round 4).
 *
 * <p>Resend-leg does not use this gate (review of PR #23 round 4; design H43, issue #27): it has no
 * in-flight send of its own to race, so it reads {@code intake_control.paused_by_operator} directly
 * instead and refuses the instant a pause is requested, never waiting for this confirmation window.
 */
public final class ConfirmedIntakePauseGate implements IntakePauseGate {

    private final IntakeControlRepository repository;
    private final Duration confirmationWindow;

    /**
     * Creates the gate.
     *
     * @param repository         the persisted pause record
     * @param confirmationWindow how long after {@code paused_at} a live pod is guaranteed to have
     *                           noticed the pause, stopped and drained — the store probe interval
     *                           plus the processing deadline
     */
    public ConfirmedIntakePauseGate(final IntakeControlRepository repository,
                                    final Duration confirmationWindow) {
        this.repository = repository;
        this.confirmationWindow = confirmationWindow;
    }

    @Override
    public boolean pausedByOperator() {
        return repository.pausedForAtLeast(confirmationWindow);
    }
}
