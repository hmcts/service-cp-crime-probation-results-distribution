package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.config.OperatorCommandLine;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins {@code contracts/cli-runners.md} §2: {@code resend-leg --leg HMPPS --request-ids <id,...>
 * --operator <name> --reason <text> [--allow-changed-payload]}. Arguments are checked whole before
 * anything is opened (FR-004-020); the runner calls the same {@link LegResender} the operations API
 * calls and maps its result to the bounded exit code (FR-004-045).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the resend-leg runner")
class ResendLegRunnerTest {

    private static final UUID ID_1 = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");
    private static final UUID ID_2 = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");
    private static final String IDS = ID_1 + "," + ID_2;

    @Mock
    private LegResender resender;

    @Nested
    @DisplayName("the arguments are checked whole before anything is opened (FR-004-020)")
    class Arguments {

        @Test
        @DisplayName("a readable invocation passes")
        void readable_invocation_passes() {
            assertThat(ResendLegRunner.checkArguments(args("HMPPS", IDS, "op", "why"))).isEmpty();
        }

        @Test
        @DisplayName("a missing --leg, --request-ids, --operator or --reason is missing-argument")
        void missing_arguments() {
            assertThat(ResendLegRunner.checkArguments("resend-leg", "--request-ids", IDS,
                    "--operator", "op", "--reason", "why"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(ResendLegRunner.checkArguments("resend-leg", "--leg", "HMPPS",
                    "--operator", "op", "--reason", "why"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(ResendLegRunner.checkArguments(args("HMPPS", IDS, " ", "why")))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(ResendLegRunner.checkArguments("resend-leg", "--leg", "HMPPS",
                    "--request-ids", IDS, "--operator", "op"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
        }

        @Test
        @DisplayName("a leg other than HMPPS, or an id that is not a UUID, is unreadable-argument")
        void unreadable_arguments() {
            assertThat(ResendLegRunner.checkArguments(args("VEP", IDS, "op", "why")))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
            assertThat(ResendLegRunner.checkArguments(args("HMPPS", ID_1 + ",not-a-uuid", "op",
                    "why"))).contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
            assertThat(ResendLegRunner.checkArguments(args("HMPPS", ID_1 + ",,", "op", "why")))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }

        @Test
        @DisplayName("an operator or reason over 200 characters is argument-too-long")
        void too_long_arguments() {
            assertThat(ResendLegRunner.checkArguments(args("HMPPS", IDS, "o".repeat(201), "why")))
                    .contains(WholeRunRefusal.ARGUMENT_TOO_LONG);
            assertThat(ResendLegRunner.checkArguments(args("HMPPS", IDS, "op", "r".repeat(201))))
                    .contains(WholeRunRefusal.ARGUMENT_TOO_LONG);
        }

        @Test
        @DisplayName("the forced-resend override is refused until it is built")
        void forced_resend_is_refused() {
            final String[] forced = {"resend-leg", "--leg", "HMPPS", "--request-ids", IDS,
                "--operator", "op", "--reason", "why", "--force-resend-sent"};

            assertThat(ResendLegRunner.checkArguments(forced))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }

        @Test
        @DisplayName("the packaged command refuses unreadable arguments with exit 1, opening nothing")
        void packaged_command_refuses_before_startup() {
            assertThat(OperatorCommandLine.run("resend-leg", "--leg", "HMPPS")).isEqualTo(1);
            assertThat(OperatorCommandLine.run(args("HMPPS", "not-a-uuid", "op", "why")))
                    .isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the run (FR-004-039, FR-004-045)")
    class TheRun {

        @Test
        @DisplayName("hands the parsed ids, operator, reason and override to LegResender")
        void hands_the_request_to_the_capability() {
            when(resender.resend(any())).thenReturn(ran());
            final ArgumentCaptor<ResendRequest> request = ArgumentCaptor.forClass(ResendRequest.class);

            new ResendLegRunner(resender).run("resend-leg", "--leg", "HMPPS", "--request-ids",
                    ID_1 + ", " + ID_2 + "," + ID_1, "--operator", "op", "--reason", "why",
                    "--allow-changed-payload");

            verify(resender).resend(request.capture());
            assertThat(request.getValue().requestIds()).containsExactly(ID_1, ID_2, ID_1);
            assertThat(request.getValue().operator()).isEqualTo("op");
            assertThat(request.getValue().reason()).isEqualTo("why");
            assertThat(request.getValue().allowChangedPayload()).isTrue();
        }

        @Test
        @DisplayName("answers done when the run produced a report, per-ID refusals included")
        void answers_done_for_a_report() {
            when(resender.resend(any())).thenReturn(ran());

            assertThat(new ResendLegRunner(resender).run(args("HMPPS", IDS, "op", "why")))
                    .isEqualTo(RunnerOutcome.DONE);
        }

        @Test
        @DisplayName("answers refused for a whole-run refusal")
        void answers_refused_for_a_whole_run_refusal() {
            when(resender.resend(any()))
                    .thenReturn(ResendRunResult.refusedWhole(WholeRunRefusal.INTAKE_PAUSED));

            assertThat(new ResendLegRunner(resender).run(args("HMPPS", IDS, "op", "why")))
                    .isEqualTo(RunnerOutcome.REFUSED);
        }

        @Test
        @DisplayName("answers failed for store-unavailable")
        void answers_failed_for_store_unavailable() {
            when(resender.resend(any()))
                    .thenReturn(ResendRunResult.refusedWhole(WholeRunRefusal.STORE_UNAVAILABLE));

            assertThat(new ResendLegRunner(resender).run(args("HMPPS", IDS, "op", "why")))
                    .isEqualTo(RunnerOutcome.FAILED);
        }

        @Test
        @DisplayName("answers failed for a report whose run met a store failure")
        void answers_failed_for_a_store_failure_during_the_run() {
            final ResendRunResult ran = ran();
            when(resender.resend(any())).thenReturn(new ResendRunResult(ran.wholeRunRefusal(),
                    ran.resendRunId(), false, ran.entries(), true));

            assertThat(new ResendLegRunner(resender).run(args("HMPPS", IDS, "op", "why")))
                    .isEqualTo(RunnerOutcome.FAILED);
        }

        @Test
        @DisplayName("refuses unreadable arguments without calling LegResender")
        void refuses_without_calling_the_capability() {
            assertThat(new ResendLegRunner(resender).run(args("HMPPS", "nope", "op", "why")))
                    .isEqualTo(RunnerOutcome.REFUSED);
            verify(resender, never()).resend(any());
        }
    }

    private static String[] args(final String leg, final String ids, final String operator,
                                 final String reason) {
        return new String[] {"resend-leg", "--leg", leg, "--request-ids", ids, "--operator",
            operator, "--reason", reason};
    }

    private static ResendRunResult ran() {
        return new ResendRunResult(Optional.empty(), Optional.of(UUID.randomUUID()), false,
                List.of(new ResendRunResult.Entry(ID_1, new ResendEntryOutcome.Ran(LegStatus.SENT,
                                Optional.empty())),
                        new ResendRunResult.Entry(ID_2,
                                new ResendEntryOutcome.Refused(ResendRefusal.NOT_ELIGIBLE))),
                false);
    }
}
