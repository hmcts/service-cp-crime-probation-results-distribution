package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CloseExpiredResendAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CloseExpiredResendOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The {@code close-expired-resend} command-line runner (H40; slice 004, FR-004-059, FR-004-060;
 * {@code contracts/cli-runners.md} §9):
 * {@code close-expired-resend --request-id <uuid> --operator <name> --reason <text>}.
 *
 * <p>The arguments are checked whole before any resource is opened (FR-004-020), as
 * {@link ResendLegRunner}'s own rules are: a missing or blank {@code --request-id},
 * {@code --operator} or {@code --reason} is {@code missing-argument}; a {@code --request-id} that
 * is not a UUID is {@code unreadable-argument}; an operator or reason over 200 characters is
 * {@code argument-too-long}. No intake pause is required, so this runner opens only the store —
 * no cache, broker or gateway client.
 */
public final class CloseExpiredResendRunner {

    private static final Logger LOG = LoggerFactory.getLogger(CloseExpiredResendRunner.class);
    private static final String REQUEST_ID_FLAG = "--request-id";
    private static final String OPERATOR_FLAG = "--operator";
    private static final String REASON_FLAG = "--reason";
    private static final int MAX_TEXT_LENGTH = 200;

    private final CloseExpiredResendAction action;

    /**
     * Creates the runner.
     *
     * @param action the close-expired-resend action
     */
    public CloseExpiredResendRunner(final CloseExpiredResendAction action) {
        this.action = action;
    }

    /**
     * Checks the arguments' shape before any resource is opened (FR-004-020).
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the whole-run refusal, or empty when the arguments are readable
     */
    public static Optional<WholeRunRefusal> checkArguments(final String... args) {
        return parse(args).refusal();
    }

    /**
     * Runs {@code close-expired-resend} over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final Parsed parsed = parse(args);
        final RunnerOutcome outcome;
        if (parsed.refusal().isPresent()) {
            LOG.warn("close-expired-resend refused reason={}", parsed.refusal().get().value());
            outcome = RunnerOutcome.REFUSED;
        } else {
            outcome = render(action.close(parsed.requestId().orElseThrow(),
                    parsed.operator().orElseThrow(), parsed.reason().orElseThrow()));
        }
        return outcome;
    }

    private static RunnerOutcome render(final CloseExpiredResendOutcome outcome) {
        return switch (outcome) {
            case CloseExpiredResendOutcome.Done() -> {
                LOG.info("close-expired-resend done");
                yield RunnerOutcome.DONE;
            }
            case CloseExpiredResendOutcome.Refused refused -> {
                LOG.warn("close-expired-resend refused reason={}", refused.reason().wireValue());
                yield RunnerOutcome.REFUSED;
            }
        };
    }

    // Each check exits as soon as it decides, so the first unreadable argument names the refusal.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Parsed parse(final String... args) {
        final String requestId = valueOf(args, REQUEST_ID_FLAG);
        final String operator = valueOf(args, OPERATOR_FLAG);
        final String reason = valueOf(args, REASON_FLAG);
        if (blank(requestId) || blank(operator) || blank(reason)) {
            return Parsed.refused(WholeRunRefusal.MISSING_ARGUMENT);
        }
        final Optional<UUID> id = readUuid(requestId);
        if (id.isEmpty()) {
            return Parsed.refused(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }
        if (operator.length() > MAX_TEXT_LENGTH || reason.length() > MAX_TEXT_LENGTH) {
            return Parsed.refused(WholeRunRefusal.ARGUMENT_TOO_LONG);
        }
        return new Parsed(Optional.empty(), id, Optional.of(operator), Optional.of(reason));
    }

    private static Optional<UUID> readUuid(final String value) {
        Optional<UUID> id;
        try {
            id = Optional.of(UUID.fromString(value));
        } catch (final IllegalArgumentException notAUuid) {
            // Refused whole as unreadable-argument; the entry's text is never echoed.
            id = Optional.empty();
        }
        return id;
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
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

    /** The checked arguments: a refusal, or the request to run. */
    private record Parsed(Optional<WholeRunRefusal> refusal, Optional<UUID> requestId,
                          Optional<String> operator, Optional<String> reason) {

        /* default */ static Parsed refused(final WholeRunRefusal refusal) {
            return new Parsed(Optional.of(refusal), Optional.empty(), Optional.empty(),
                    Optional.empty());
        }
    }
}
