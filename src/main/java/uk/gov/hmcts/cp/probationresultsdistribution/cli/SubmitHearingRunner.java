package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Optional;
import java.util.Set;
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
 * <p>The whole token stream is read by the shared {@link ArgumentParser} (FR-004-020; PR #29
 * review) before anything else: an unrecognised flag, a flag given twice or a stray trailing token
 * is {@code unreadable-argument}, and a flag given no real value because the next token is itself
 * a flag is {@code missing-argument}, both refused before {@link SubmitHearingAction#check} ever
 * runs. A readable token stream then goes to that same check, and the run calls that same
 * {@link SubmitHearingAction}. It prints {@code enqueued} with the {@code requestId}, the run id
 * and the {@code messageId}, or a bounded refusal or failure code — never the operator's name, the
 * reason's text or any other supplied value (FR-004-041). It is never refused for the intake state
 * (FR-004-058).
 */
public final class SubmitHearingRunner {

    private static final Logger LOG = LoggerFactory.getLogger(SubmitHearingRunner.class);
    private static final String REFUSED = "submit-hearing refused reason={}";
    private static final String HEARING_ID_FLAG = "--hearing-id";
    private static final String HEARING_DAY_FLAG = "--hearing-day";
    private static final String OPERATOR_FLAG = "--operator";
    private static final String REASON_FLAG = "--reason";
    private static final Set<String> VALUE_FLAGS = Set.of(HEARING_ID_FLAG, HEARING_DAY_FLAG,
            OPERATOR_FLAG, REASON_FLAG);

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
        final Parsed parsed = parse(args);
        return parsed.refusal().isPresent() ? parsed.refusal()
                : SubmitHearingAction.check(parsed.request().orElseThrow());
    }

    /**
     * Runs {@code submit-hearing} over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final Parsed parsed = parse(args);
        final RunnerOutcome outcome;
        if (parsed.refusal().isPresent()) {
            LOG.warn(REFUSED, parsed.refusal().get().value());
            outcome = RunnerOutcome.REFUSED;
        } else {
            final SubmitHearingRequest request = parsed.request().orElseThrow();
            final Optional<WholeRunRefusal> refusal = SubmitHearingAction.check(request);
            if (refusal.isPresent()) {
                LOG.warn(REFUSED, refusal.get().value());
                outcome = RunnerOutcome.REFUSED;
            } else {
                outcome = render(action.submit(request));
            }
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

    private static Parsed parse(final String... args) {
        return switch (ArgumentParser.parse(args, VALUE_FLAGS, Set.of())) {
            case ArgumentParser.Result.Refused refused -> Parsed.refused(refused.refusal());
            case ArgumentParser.Result.Readable readable -> Parsed.readable(request(
                    readable.arguments()));
        };
    }

    private static SubmitHearingRequest request(final ArgumentParser arguments) {
        return new SubmitHearingRequest(arguments.value(HEARING_ID_FLAG),
                arguments.value(HEARING_DAY_FLAG), arguments.value(OPERATOR_FLAG),
                arguments.value(REASON_FLAG));
    }

    /** The checked token stream: a refusal, or the request to check and run. */
    private record Parsed(Optional<WholeRunRefusal> refusal, Optional<SubmitHearingRequest> request) {

        /* default */ static Parsed refused(final WholeRunRefusal refusal) {
            return new Parsed(Optional.of(refusal), Optional.empty());
        }

        /* default */ static Parsed readable(final SubmitHearingRequest request) {
            return new Parsed(Optional.empty(), Optional.of(request));
        }
    }
}
