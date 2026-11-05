package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.SubmitHearingAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.SubmitHearingOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.SubmitHearingRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The {@code submit-hearing} command-line runner (design §4.8 action 5; slice 004, FR-004-042,
 * FR-004-054–FR-004-058; {@code contracts/cli-runners.md} §5):
 * {@code submit-hearing --hearing-id <uuid> --hearing-day <YYYY-MM-DD> --operator <name>
 * --reason <text>}.
 *
 * <p>The arguments are checked whole before anything is opened (FR-004-020) by the action's own
 * check ({@link SubmitHearingAction#check}), and the run calls that same {@link SubmitHearingAction}.
 * It prints {@code enqueued} with the {@code requestId}, the run id and the {@code messageId}, or a
 * bounded refusal or failure code — never the operator's name, the reason's text or any other
 * supplied value (FR-004-041). It is never refused for the intake state (FR-004-058).
 */
public final class SubmitHearingRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SubmitHearingRunner.class);
    private static final String REFUSED = "submit-hearing refused reason={}";

    private final SubmitHearingAction action;

    /**
     * Creates the runner.
     *
     * @param action the submit-hearing action
     */
    public SubmitHearingRunner(final SubmitHearingAction action) {
        this.action = action;
    }

    /**
     * Checks the arguments before anything is opened (FR-004-020).
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the whole-run refusal, or empty when the arguments are readable
     */
    public static Optional<WholeRunRefusal> checkArguments(final String... args) {
        return SubmitHearingAction.check(request(args));
    }

    /**
     * Runs {@code submit-hearing} over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final SubmitHearingRequest request = request(args);
        final Optional<WholeRunRefusal> refusal = SubmitHearingAction.check(request);
        final RunnerOutcome outcome;
        if (refusal.isPresent()) {
            LOG.warn(REFUSED, refusal.get().value());
            outcome = RunnerOutcome.REFUSED;
        } else {
            outcome = render(action.submit(request));
        }
        return outcome;
    }

    private static RunnerOutcome render(final SubmitHearingOutcome outcome) {
        return switch (outcome) {
            case SubmitHearingOutcome.Enqueued enqueued -> {
                LOG.info("submit-hearing done outcome=enqueued requestId={} runId={} "
                        + "messageId=RESULTS:{}", enqueued.requestId(), enqueued.runId(),
                        enqueued.requestId());
                yield RunnerOutcome.DONE;
            }
            case SubmitHearingOutcome.PayloadUnavailable ignored -> {
                LOG.warn(REFUSED, "payload-unavailable");
                yield RunnerOutcome.REFUSED;
            }
            case SubmitHearingOutcome.Refused refused -> {
                LOG.warn(REFUSED, refused.reason().value());
                yield RunnerOutcome.REFUSED;
            }
            case SubmitHearingOutcome.BrokerUnavailable ignored -> {
                LOG.error("submit-hearing failed reason=broker-unavailable");
                yield RunnerOutcome.FAILED;
            }
        };
    }

    private static SubmitHearingRequest request(final String... args) {
        return new SubmitHearingRequest(valueOf(args, "--hearing-id"),
                valueOf(args, "--hearing-day"), valueOf(args, "--operator"),
                valueOf(args, "--reason"));
    }

    /**
     * A flag's value; a flag given last, with no value after it, is present but blank, so it is
     * refused {@code missing-argument}.
     */
    private static Optional<String> valueOf(final String[] args, final String flag) {
        Optional<String> value = Optional.empty();
        for (int i = 0; i < args.length; i++) {
            if (flag.equals(args[i])) {
                value = Optional.of(i + 1 < args.length ? args[i + 1] : "");
                break;
            }
        }
        return value;
    }
}
