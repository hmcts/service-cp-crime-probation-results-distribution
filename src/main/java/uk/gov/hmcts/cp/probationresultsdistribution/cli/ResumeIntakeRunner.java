package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakeControlOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * The {@code resume-intake} command-line runner (design §4.8 action 4; slice 004, FR-004-051, 052;
 * {@code contracts/cli-runners.md} §3): no arguments. Calls the same {@link IntakePauseAction} the
 * operations API would call, printing its bounded outcome and nothing else.
 */
public final class ResumeIntakeRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ResumeIntakeRunner.class);

    private final IntakePauseAction action;

    /**
     * Creates the runner.
     *
     * @param action the intake pause/resume action
     */
    public ResumeIntakeRunner(final IntakePauseAction action) {
        this.action = action;
    }

    /**
     * Runs the {@code resume-intake} action.
     *
     * @return the bounded runner outcome
     */
    public RunnerOutcome run() {
        return render(action.resume());
    }

    private static RunnerOutcome render(final IntakeControlOutcome outcome) {
        return switch (outcome) {
            case IntakeControlOutcome.Done ignored -> {
                LOG.info("resume-intake done");
                yield RunnerOutcome.DONE;
            }
            case IntakeControlOutcome.NoOp ignored -> {
                LOG.info("resume-intake done outcome=no-op");
                yield RunnerOutcome.DONE;
            }
            case IntakeControlOutcome.StoreUnavailable ignored -> {
                LOG.error("resume-intake failed reason=store-unavailable");
                yield RunnerOutcome.FAILED;
            }
        };
    }
}
