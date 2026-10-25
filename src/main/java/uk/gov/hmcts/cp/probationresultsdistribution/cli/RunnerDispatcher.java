package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * Routes the first command-line argument to the matching runner action (research.md Decision 6;
 * FR-004-045).
 *
 * <p>Supplying an action argument runs exactly that action and nothing else; omitting it leaves
 * normal Boot startup untouched (the ASB listener and the body-maintenance scheduler start as they
 * do today). An action argument that matches no registered action is a configuration error, not a
 * silent fall-through to normal startup — failing loudly here is cheaper than a mistyped runner
 * invocation quietly starting the full service instead of the one action an operator asked for.
 */
public final class RunnerDispatcher {

    private final Map<String, Supplier<RunnerOutcome>> actions;

    /**
     * Creates a dispatcher over the given action registry.
     *
     * @param actions every registered action, keyed by the name an operator passes as the first
     *     command-line argument (e.g. {@code "replay"}, {@code "resend-leg"})
     */
    public RunnerDispatcher(final Map<String, Supplier<RunnerOutcome>> actions) {
        this.actions = Map.copyOf(actions);
    }

    /**
     * Dispatches on the first element of {@code args}, if present.
     *
     * @param args the process's command-line arguments
     * @return the invoked action's outcome, or {@link Optional#empty()} when no action argument was
     *     supplied — the caller's signal to proceed with normal Boot startup instead
     * @throws IllegalArgumentException if {@code args[0]} names no registered action
     */
    public Optional<RunnerOutcome> dispatch(final String... args) {
        final Optional<RunnerOutcome> outcome;
        if (args.length == 0) {
            outcome = Optional.empty();
        } else {
            final String actionName = args[0];
            final Supplier<RunnerOutcome> action = actions.get(actionName);
            if (action == null) {
                throw new IllegalArgumentException("Unknown action: " + actionName);
            }
            outcome = Optional.of(action.get());
        }
        return outcome;
    }
}
