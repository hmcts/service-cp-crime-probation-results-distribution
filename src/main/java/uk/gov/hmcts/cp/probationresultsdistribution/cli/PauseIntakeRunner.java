package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakeControlOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * The {@code pause-intake} command-line runner (design §4.8 action 4; slice 004, FR-004-051, 052;
 * {@code contracts/cli-runners.md} §3): no arguments. Calls the same {@link IntakePauseAction} the
 * operations API would call, printing its bounded outcome and nothing else.
 */
public final class PauseIntakeRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PauseIntakeRunner.class);

    private final IntakePauseAction action;

    /**
     * Creates the runner.
     *
     * @param action the intake pause/resume action
     */
    public PauseIntakeRunner(final IntakePauseAction action) {
        this.action = action;
    }

    /**
     * Runs the {@code pause-intake} action.
     *
     * @return the bounded runner outcome
     */
    public RunnerOutcome run() {
        return render(action.pause());
    }

    private static RunnerOutcome render(final IntakeControlOutcome outcome) {
        return switch (outcome) {
            case IntakeControlOutcome.Done ignored -> {
                LOG.info("pause-intake done");
                yield RunnerOutcome.DONE;
            }
            case IntakeControlOutcome.NoOp ignored -> {
                LOG.info("pause-intake done outcome=no-op");
                yield RunnerOutcome.DONE;
            }
            case IntakeControlOutcome.StoreUnavailable ignored -> {
                LOG.error("pause-intake failed reason=store-unavailable");
                yield RunnerOutcome.FAILED;
            }
        };
    }
}
