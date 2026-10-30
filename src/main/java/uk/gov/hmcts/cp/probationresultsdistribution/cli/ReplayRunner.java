package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

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
     * Checks the {@code replay} arguments whole, before any context, store or broker is opened
     * ({@code contracts/cli-runners.md} §1): a missing or blank {@code --request-id} is
     * {@code missing-argument}, one that is not a UUID {@code unreadable-argument}.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the refusal, or empty when the arguments can be run
     */
    public static Optional<WholeRunRefusal> checkArguments(final String... args) {
        final String raw = valueOf(args, REQUEST_ID_FLAG);
        final Optional<WholeRunRefusal> refusal;
        if (raw == null || raw.isBlank()) {
            refusal = Optional.of(WholeRunRefusal.MISSING_ARGUMENT);
        } else if (parse(raw).isEmpty()) {
            refusal = Optional.of(WholeRunRefusal.UNREADABLE_ARGUMENT);
        } else {
            refusal = Optional.empty();
        }
        return refusal;
    }

    /**
     * Runs the {@code replay} action over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final Optional<WholeRunRefusal> refusal = checkArguments(args);
        final RunnerOutcome outcome;
        if (refusal.isPresent()) {
            LOG.warn("replay refused {}: --request-id", refusal.get().value());
            outcome = RunnerOutcome.REFUSED;
        } else {
            outcome = render(action.replay(parse(valueOf(args, REQUEST_ID_FLAG)).orElseThrow()));
        }
        return outcome;
    }

    private static Optional<UUID> parse(final String raw) {
        Optional<UUID> parsed;
        try {
            parsed = Optional.of(UUID.fromString(raw));
        } catch (final IllegalArgumentException notAUuid) {
            // Not a UUID: the caller refuses unreadable-argument; the text is never echoed.
            parsed = Optional.empty();
        }
        return parsed;
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
