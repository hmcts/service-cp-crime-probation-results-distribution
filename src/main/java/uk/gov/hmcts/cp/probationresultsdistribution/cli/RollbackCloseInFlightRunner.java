package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RollbackCloseInFlightAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RollbackCloseInFlightOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * The {@code rollback-close-in-flight} command-line runner (design §9.4 step 4; slice 004,
 * confirmed FR-004-049; {@code contracts/cli-runners.md} §6): no arguments. Refused
 * {@code intake-not-paused} unless intake is paused; otherwise reports the number of rows closed
 * by prior status. This service owns the guarded CLI execution path (research.md Decision 2).
 */
public final class RollbackCloseInFlightRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RollbackCloseInFlightRunner.class);

    private final RollbackCloseInFlightAction action;

    /**
     * Creates the runner.
     *
     * @param action the rollback close-in-flight action
     */
    public RollbackCloseInFlightRunner(final RollbackCloseInFlightAction action) {
        this.action = action;
    }

    /**
     * Runs the {@code rollback-close-in-flight} action.
     *
     * @return the bounded runner outcome
     */
    public RunnerOutcome run() {
        return render(action.run());
    }

    private static RunnerOutcome render(final RollbackCloseInFlightOutcome outcome) {
        return switch (outcome) {
            case RollbackCloseInFlightOutcome.Done done -> {
                LOG.info("rollback-close-in-flight done pendingClosed={} parkedClosed={}",
                        done.result().pendingClosed(), done.result().parkedClosed());
                yield RunnerOutcome.DONE;
            }
            case RollbackCloseInFlightOutcome.Refused refused -> {
                LOG.warn("rollback-close-in-flight refused reason={}", refused.reason().value());
                yield RunnerOutcome.REFUSED;
            }
        };
    }
}
