package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CloseExpiredResendAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CloseExpiredResendOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.config.OperatorCommandLine;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins {@code contracts/cli-runners.md} §9: {@code close-expired-resend --request-id <uuid>
 * --operator <name> --reason <text>} (H40; FR-004-059, FR-004-060). Arguments are checked whole
 * before anything is opened (FR-004-020), as {@link ResendLegRunnerTest} pins for its own runner.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the close-expired-resend runner")
class CloseExpiredResendRunnerTest {

    private static final UUID REQUEST_ID = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");

    @Mock
    private CloseExpiredResendAction action;

    @Nested
    @DisplayName("the arguments are checked whole before anything is opened (FR-004-020)")
    class Arguments {

        @Test
        @DisplayName("a readable invocation passes")
        void readable_invocation_passes() {
            assertThat(CloseExpiredResendRunner.checkArguments(args(REQUEST_ID.toString(), "op",
                    "why"))).isEmpty();
        }

        @Test
        @DisplayName("a missing --request-id, --operator or --reason is missing-argument")
        void missing_arguments() {
            assertThat(CloseExpiredResendRunner.checkArguments("close-expired-resend",
                    "--operator", "op", "--reason", "why"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(CloseExpiredResendRunner.checkArguments("close-expired-resend",
                    "--request-id", REQUEST_ID.toString(), "--reason", "why"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(CloseExpiredResendRunner.checkArguments("close-expired-resend",
                    "--request-id", REQUEST_ID.toString(), "--operator", "op"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(CloseExpiredResendRunner.checkArguments(args(REQUEST_ID.toString(), " ",
                    "why"))).contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(CloseExpiredResendRunner.checkArguments(args(REQUEST_ID.toString(), "op",
                    " "))).contains(WholeRunRefusal.MISSING_ARGUMENT);
        }

        @Test
        @DisplayName("a flag given no value before the next flag is missing-argument, not given "
                + "the next flag's own name as its value (P2, PR #29 review)")
        void a_flag_with_no_value_does_not_swallow_the_next_flags_name() {
            assertThat(CloseExpiredResendRunner.checkArguments("close-expired-resend",
                    "--request-id", REQUEST_ID.toString(), "--operator", "--reason", "maintenance"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(CloseExpiredResendRunner.checkArguments("close-expired-resend",
                    "--operator", "op", "--reason", "--request-id", REQUEST_ID.toString()))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
        }

        @Test
        @DisplayName("a --request-id that is not a UUID is unreadable-argument")
        void unreadable_argument() {
            assertThat(CloseExpiredResendRunner.checkArguments(args("not-a-uuid", "op", "why")))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }

        @Test
        @DisplayName("an operator or reason over 200 characters is argument-too-long")
        void too_long_arguments() {
            assertThat(CloseExpiredResendRunner.checkArguments(args(REQUEST_ID.toString(),
                    "o".repeat(201), "why"))).contains(WholeRunRefusal.ARGUMENT_TOO_LONG);
            assertThat(CloseExpiredResendRunner.checkArguments(args(REQUEST_ID.toString(), "op",
                    "r".repeat(201)))).contains(WholeRunRefusal.ARGUMENT_TOO_LONG);
        }

        @Test
        @DisplayName("the packaged command refuses unreadable arguments with exit 1, opening nothing")
        void packaged_command_refuses_before_startup() {
            assertThat(OperatorCommandLine.run("close-expired-resend", "--operator", "op",
                    "--reason", "why")).isEqualTo(1);
            assertThat(OperatorCommandLine.run(args("not-a-uuid", "op", "why"))).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the run (FR-004-059, FR-004-045)")
    class TheRun {

        @Test
        @DisplayName("hands the parsed request id, operator and reason to the action")
        void hands_the_request_to_the_capability() {
            when(action.close(any(), any(), any())).thenReturn(new CloseExpiredResendOutcome.Done());
            final ArgumentCaptor<UUID> requestId = ArgumentCaptor.forClass(UUID.class);
            final ArgumentCaptor<String> operator = ArgumentCaptor.forClass(String.class);
            final ArgumentCaptor<String> reason = ArgumentCaptor.forClass(String.class);

            new CloseExpiredResendRunner(action).run(args(REQUEST_ID.toString(), "op", "why"));

            verify(action).close(requestId.capture(), operator.capture(), reason.capture());
            assertThat(requestId.getValue()).isEqualTo(REQUEST_ID);
            assertThat(operator.getValue()).isEqualTo("op");
            assertThat(reason.getValue()).isEqualTo("why");
        }

        @Test
        @DisplayName("answers done when the action closed the row")
        void answers_done() {
            when(action.close(any(), any(), any())).thenReturn(new CloseExpiredResendOutcome.Done());

            assertThat(new CloseExpiredResendRunner(action).run(args(REQUEST_ID.toString(), "op",
                    "why"))).isEqualTo(RunnerOutcome.DONE);
        }

        @Test
        @DisplayName("answers refused for unknown-request")
        void answers_refused_for_unknown_request() {
            assertRefusedFor(ResendRefusal.UNKNOWN_REQUEST);
        }

        @Test
        @DisplayName("answers refused for not-eligible")
        void answers_refused_for_not_eligible() {
            assertRefusedFor(ResendRefusal.NOT_ELIGIBLE);
        }

        @Test
        @DisplayName("answers refused for resend-in-progress")
        void answers_refused_for_resend_in_progress() {
            assertRefusedFor(ResendRefusal.RESEND_IN_PROGRESS);
        }

        private void assertRefusedFor(final ResendRefusal reason) {
            when(action.close(any(), any(), any()))
                    .thenReturn(new CloseExpiredResendOutcome.Refused(reason));

            assertThat(new CloseExpiredResendRunner(action).run(args(REQUEST_ID.toString(), "op",
                    "why"))).isEqualTo(RunnerOutcome.REFUSED);
        }

        @Test
        @DisplayName("refuses unreadable arguments without calling the action")
        void refuses_without_calling_the_capability() {
            assertThat(new CloseExpiredResendRunner(action).run(args("not-a-uuid", "op", "why")))
                    .isEqualTo(RunnerOutcome.REFUSED);
            verify(action, never()).close(any(), any(), any());
        }
    }

    private static String[] args(final String requestId, final String operator,
                                 final String reason) {
        return new String[] {"close-expired-resend", "--request-id", requestId, "--operator",
            operator, "--reason", reason};
    }
}
