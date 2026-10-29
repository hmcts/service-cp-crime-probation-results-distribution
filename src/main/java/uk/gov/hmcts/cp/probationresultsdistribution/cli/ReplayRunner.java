package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * The {@code replay} command-line runner (design §4.8 action 2; slice 004, FR-004-042;
 * {@code contracts/cli-runners.md} §1): {@code replay --request-id <uuid>}. Calls the same
 * {@link ReplayAction} the operations API will call (FR-004-005's "same application service"),
 * printing its bounded outcome and nothing else — no caller value is echoed (FR-004-020).
 */
public final class ReplayRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ReplayRunner.class);
    private static final String REQUEST_ID_FLAG = "--request-id";

    private final ReplayAction action;

    /**
     * Creates the runner.
     *
     * @param action the replay action
     */
    public ReplayRunner(final ReplayAction action) {
        this.action = action;
    }

    /**
     * Runs the {@code replay} action over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final String raw = valueOf(args, REQUEST_ID_FLAG);
        final RunnerOutcome outcome;
        if (raw == null || raw.isBlank()) {
            LOG.warn("replay refused missing-argument: --request-id");
            outcome = RunnerOutcome.REFUSED;
        } else {
            outcome = runWithRequestId(raw);
        }
        return outcome;
    }

    private RunnerOutcome runWithRequestId(final String raw) {
        final RunnerOutcome outcome;
        UUID requestId = null;
        try {
            requestId = UUID.fromString(raw);
        } catch (final IllegalArgumentException notAUuid) {
            LOG.warn("replay refused unreadable-argument: --request-id");
        }
        outcome = requestId == null ? RunnerOutcome.REFUSED : render(action.replay(requestId));
        return outcome;
    }

    private static RunnerOutcome render(final ReplayOutcome outcome) {
        return switch (outcome) {
            case ReplayOutcome.Enqueued enqueued -> {
                LOG.info("replay done outcome=enqueued messageId={}", enqueued.messageId());
                yield RunnerOutcome.DONE;
            }
            case ReplayOutcome.NoOp ignored -> {
                LOG.info("replay done outcome=no-op");
                yield RunnerOutcome.DONE;
            }
            case ReplayOutcome.Refused refused -> {
                LOG.warn("replay refused reason={}", refused.reason().wireValue());
                yield RunnerOutcome.REFUSED;
            }
            case ReplayOutcome.StoreUnavailable ignored -> {
                LOG.error("replay failed reason=store-unavailable");
                yield RunnerOutcome.FAILED;
            }
            case ReplayOutcome.BrokerUnavailable ignored -> {
                LOG.error("replay failed reason=broker-unavailable");
                yield RunnerOutcome.FAILED;
            }
        };
    }

    private static String valueOf(final String[] args, final String flag) {
        String value = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (flag.equals(args[i])) {
                value = args[i + 1];
                break;
            }
        }
        return value;
    }
}
