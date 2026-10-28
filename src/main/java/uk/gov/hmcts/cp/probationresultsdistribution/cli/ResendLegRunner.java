package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The {@code resend-leg} command-line runner ({@code contracts/cli-runners.md} §2).
 *
 * <p>Seam (T047): argument checking and the report land with the implementation.
 */
public final class ResendLegRunner {

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
     * Checks the arguments' shape before any resource is opened.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the whole-run refusal, or empty when the arguments are readable
     */
    public static Optional<WholeRunRefusal> checkArguments(final String... args) {
        return Optional.empty();
    }

    /**
     * Runs {@code resend-leg} over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        return RunnerOutcome.DONE;
    }
}
