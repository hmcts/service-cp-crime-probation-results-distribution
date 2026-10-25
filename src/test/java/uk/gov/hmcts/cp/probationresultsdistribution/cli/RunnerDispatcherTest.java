package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * Pins the dispatch table of research.md Decision 6 (FR-004-045, SC-004-010): no action argument
 * leaves startup untouched; a known action argument runs exactly that action and nothing else; an
 * unknown action argument fails loudly rather than silently falling through to normal startup.
 */
class RunnerDispatcherTest {

    @Nested
    @DisplayName("no action argument")
    class NoActionArgument {

        @Test
        void dispatch_emptyArgs_returnsEmptyWithoutInvokingAnyAction() {
            final AtomicBoolean invoked = new AtomicBoolean(false);
            final RunnerDispatcher dispatcher =
                    new RunnerDispatcher(Map.of("replay", markInvoked(invoked)));

            final Optional<RunnerOutcome> result = dispatcher.dispatch();

            assertThat(result).isEmpty();
            assertThat(invoked).isFalse();
        }
    }

    @Nested
    @DisplayName("a known action argument")
    class KnownActionArgument {

        @Test
        void dispatch_knownAction_invokesOnlyThatActionAndReturnsItsOutcome() {
            final AtomicBoolean replayInvoked = new AtomicBoolean(false);
            final AtomicBoolean resendInvoked = new AtomicBoolean(false);
            final RunnerDispatcher dispatcher =
                    new RunnerDispatcher(
                            Map.of(
                                    "replay", markInvokedReturning(replayInvoked, RunnerOutcome.DONE),
                                    "resend-leg", markInvokedReturning(resendInvoked, RunnerOutcome.REFUSED)));

            final Optional<RunnerOutcome> result = dispatcher.dispatch("replay", "--request-id", "x");

            assertThat(result).contains(RunnerOutcome.DONE);
            assertThat(replayInvoked).isTrue();
            assertThat(resendInvoked).isFalse();
        }
    }

    @Nested
    @DisplayName("an unknown action argument")
    class UnknownActionArgument {

        @Test
        void dispatch_unknownAction_throwsRatherThanStartingNormalBoot() {
            final RunnerDispatcher dispatcher = new RunnerDispatcher(Map.of());

            assertThatIllegalArgumentException()
                    .isThrownBy(() -> dispatcher.dispatch("not-a-real-action"))
                    .withMessageContaining("not-a-real-action");
        }
    }

    private static Supplier<RunnerOutcome> markInvoked(final AtomicBoolean invoked) {
        return markInvokedReturning(invoked, RunnerOutcome.DONE);
    }

    private static Supplier<RunnerOutcome> markInvokedReturning(
            final AtomicBoolean invoked, final RunnerOutcome outcome) {
        return () -> {
            invoked.set(true);
            return outcome;
        };
    }
}
