package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The {@code resend-leg} command-line runner (design §4.8 action 3; slice 004, FR-004-018–FR-004-042;
 * {@code contracts/cli-runners.md} §2):
 * {@code resend-leg --leg HMPPS --request-ids <id,id,...> --operator <name> --reason <text>
 * [--force-resend-sent] [--allow-changed-payload]}.
 *
 * <p>The arguments are checked whole before anything is opened (FR-004-020) — {@link
 * #checkArguments} is what the packaged command calls before it builds a context — and the run
 * calls the same {@link LegResender} the operations API calls, so the cap, the pause, the per-ID
 * checks and the report are that capability's, never re-implemented here. Its refusal codes and
 * per-ID outcomes are printed as bounded codes; no supplied text is echoed, and neither the
 * operator's name nor the reason's text is logged (FR-004-041).
 *
 * <p>{@code --force-resend-sent} is the forced-resend override, the design's
 * {@code allowTerminalSuccess} (FR-004-036–FR-004-038), and {@code --allow-changed-payload} the
 * {@code allow-changed-payload} override (FR-004-031); each is a flag taking no value, and
 * {@link LegResender} applies it per ID.
 */
public final class ResendLegRunner {

    private static final Logger LOG = LoggerFactory.getLogger(ResendLegRunner.class);
    private static final String LEG_FLAG = "--leg";
    private static final String REQUEST_IDS_FLAG = "--request-ids";
    private static final String OPERATOR_FLAG = "--operator";
    private static final String REASON_FLAG = "--reason";
    private static final String ALLOW_CHANGED_PAYLOAD_FLAG = "--allow-changed-payload";
    private static final String FORCE_RESEND_FLAG = "--force-resend-sent";
    private static final String HMPPS = "HMPPS";
    private static final int MAX_TEXT_LENGTH = 200;

    private final LegResender resender;

    /**
     * Creates the runner.
     *
     * @param resender the resend-leg capability
     */
    public ResendLegRunner(final LegResender resender) {
        this.resender = resender;
    }

    /**
     * Checks the arguments' shape before any resource is opened (FR-004-020): a missing or blank
     * {@code --leg}, {@code --request-ids}, {@code --operator} or {@code --reason} is
     * {@code missing-argument}; a leg other than {@code HMPPS}, or an entry of the id list that is
     * not a UUID, is {@code unreadable-argument}; an operator or reason over 200 characters is
     * {@code argument-too-long}. The cap and the pause are the capability's own checks.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the whole-run refusal, or empty when the arguments are readable
     */
    public static Optional<WholeRunRefusal> checkArguments(final String... args) {
        return parse(args).refusal();
    }

    /**
     * Runs {@code resend-leg} over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final Parsed parsed = parse(args);
        final RunnerOutcome outcome;
        if (parsed.refusal().isPresent()) {
            LOG.warn("resend-leg refused reason={}", parsed.refusal().get().value());
            outcome = RunnerOutcome.REFUSED;
        } else {
            outcome = render(resender.resend(parsed.request().orElseThrow()));
        }
        return outcome;
    }

    private static RunnerOutcome render(final ResendRunResult result) {
        final RunnerOutcome outcome;
        if (result.wholeRunRefusal().isPresent()) {
            final WholeRunRefusal refusal = result.wholeRunRefusal().get();
            final boolean failed = refusal == WholeRunRefusal.STORE_UNAVAILABLE;
            if (failed) {
                LOG.error("resend-leg failed reason={}", refusal.value());
            } else {
                LOG.warn("resend-leg refused reason={}", refusal.value());
            }
            outcome = failed ? RunnerOutcome.FAILED : RunnerOutcome.REFUSED;
        } else {
            final UUID resendRunId = result.resendRunId().orElseThrow();
            for (final ResendRunResult.Entry entry : result.entries()) {
                LOG.info("resend-leg id resendRunId={} requestId={} outcome={}", resendRunId,
                        entry.requestId(), entry.outcome().reportValue());
            }
            if (result.storeFailed()) {
                // The report stands, but the run's result is failed (spec edge case "The store
                // fails during a resend run").
                LOG.error("resend-leg failed resendRunId={} reason=store-unavailable ids={} "
                        + "notRun={}", resendRunId, result.entries().size(), result.notRun().size());
                outcome = RunnerOutcome.FAILED;
            } else {
                LOG.info("resend-leg done resendRunId={} ids={} notRun={}", resendRunId,
                        result.entries().size(), result.notRun().size());
                outcome = RunnerOutcome.DONE;
            }
        }
        return outcome;
    }

    // Each check exits as soon as it decides, so the first unreadable argument names the refusal.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Parsed parse(final String... args) {
        final String leg = valueOf(args, LEG_FLAG);
        final String ids = valueOf(args, REQUEST_IDS_FLAG);
        final String operator = valueOf(args, OPERATOR_FLAG);
        final String reason = valueOf(args, REASON_FLAG);
        if (blank(leg) || blank(ids) || blank(operator) || blank(reason)) {
            return Parsed.refused(WholeRunRefusal.MISSING_ARGUMENT);
        }
        if (!HMPPS.equals(leg)) {
            return Parsed.refused(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }
        if (operator.length() > MAX_TEXT_LENGTH || reason.length() > MAX_TEXT_LENGTH) {
            return Parsed.refused(WholeRunRefusal.ARGUMENT_TOO_LONG);
        }
        final Optional<List<UUID>> requestIds = requestIds(ids);
        if (requestIds.isEmpty()) {
            return Parsed.refused(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }
        final List<String> flags = Arrays.asList(args);
        return new Parsed(Optional.empty(), Optional.of(new ResendRequest(requestIds.get(), operator,
                reason, flags.contains(ALLOW_CHANGED_PAYLOAD_FLAG), flags.contains(FORCE_RESEND_FLAG))));
    }

    private static Optional<List<UUID>> requestIds(final String raw) {
        final List<UUID> ids = new ArrayList<>();
        boolean readable = true;
        for (final String entry : raw.split(",", -1)) {
            try {
                ids.add(UUID.fromString(entry.strip()));
            } catch (final IllegalArgumentException notAUuid) {
                // Refused whole as unreadable-argument; the entry's text is never echoed.
                readable = false;
            }
        }
        return readable ? Optional.of(ids) : Optional.empty();
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
    private record Parsed(Optional<WholeRunRefusal> refusal, Optional<ResendRequest> request) {

        /* default */ static Parsed refused(final WholeRunRefusal refusal) {
            return new Parsed(Optional.of(refusal), Optional.empty());
        }
    }
}
