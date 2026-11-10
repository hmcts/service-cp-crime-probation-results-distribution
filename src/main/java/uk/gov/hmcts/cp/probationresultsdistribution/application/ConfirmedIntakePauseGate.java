package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Clock;
import java.time.Duration;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;

/**
 * The standalone operator command's {@link IntakePauseGate} (review of PR #8/#23, M3 follow-up): a
 * CLI invocation has no running pod's confirmed, drain-aware pause flag to reconcile against — that
 * lives only in {@code inbound.ConsumerLifecycleController}'s memory — so it reads
 * {@code intake_control} directly and applies the same confirmation window a live pod would have
 * needed to reach {@code SUSPENDED}: one more probe tick after {@code paused_at} to notice the
 * request, then the full drain wait for an in-flight delivery to finish. Before that window has
 * elapsed, {@code pausedByOperator()} answers {@code false} — exactly as if intake were still
 * running — so rollback-close-in-flight and purge-queue refuse rather than racing a send a pod may
 * still be making.
 */
public final class ConfirmedIntakePauseGate implements IntakePauseGate {

    private final IntakeControlRepository repository;
    private final Duration confirmationWindow;
    private final Clock clock;

    /**
     * Creates the gate.
     *
     * @param repository         the persisted pause record
     * @param confirmationWindow how long after {@code paused_at} a live pod is guaranteed to have
     *                           noticed the pause, stopped and drained — the store probe interval
     *                           plus the processing deadline
     * @param clock              the time source, explicit for testability
     */
    public ConfirmedIntakePauseGate(final IntakeControlRepository repository,
                                    final Duration confirmationWindow, final Clock clock) {
        this.repository = repository;
        this.confirmationWindow = confirmationWindow;
        this.clock = clock;
    }

    @Override
    public boolean pausedByOperator() {
        return repository.pausedByOperator()
                && repository.pausedSince()
                        .map(since -> !clock.instant().isBefore(since.plus(confirmationWindow)))
                        .orElse(false);
    }
}
