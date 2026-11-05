package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.PurgeQueueAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.PurgeQueueOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * The {@code purge-queue} command-line runner (design §4.8 action 6; slice 004, FR-004-053;
 * {@code contracts/cli-runners.md} §4): no arguments. Refused {@code intake-not-paused} unless
 * intake is paused; otherwise reports the number of messages removed and the cut-off instant.
 */
public final class PurgeQueueRunner {

    private static final Logger LOG = LoggerFactory.getLogger(PurgeQueueRunner.class);

    private final PurgeQueueAction action;

    /**
     * Creates the runner.
     *
     * @param action the purge-queue action
     */
    public PurgeQueueRunner(final PurgeQueueAction action) {
        this.action = action;
    }

    /**
     * Runs the {@code purge-queue} action.
     *
     * @return the bounded runner outcome
     */
    public RunnerOutcome run() {
        return render(action.purge());
    }

    private static RunnerOutcome render(final PurgeQueueOutcome outcome) {
        return switch (outcome) {
            case PurgeQueueOutcome.Done done -> {
                LOG.info("purge-queue done removed={} cutoff={}", done.removed(), done.cutoff());
                yield RunnerOutcome.DONE;
            }
            case PurgeQueueOutcome.Refused refused -> {
                LOG.warn("purge-queue refused reason={}", refused.reason().value());
                yield RunnerOutcome.REFUSED;
            }
            case PurgeQueueOutcome.BrokerUnavailable ignored -> {
                LOG.error("purge-queue failed reason=broker-unavailable");
                yield RunnerOutcome.FAILED;
            }
        };
    }
}
