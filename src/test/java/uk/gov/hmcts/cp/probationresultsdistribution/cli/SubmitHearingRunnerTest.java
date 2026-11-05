package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.SubmitHearingAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.SubmitHearingOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.SubmitHearingRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins {@code contracts/cli-runners.md} §5: {@code submit-hearing --hearing-id <uuid> --hearing-day
 * <YYYY-MM-DD> --operator <name> --reason <text>}. Arguments are checked whole before anything is
 * opened (FR-004-020); the runner calls {@link SubmitHearingAction} and prints its bounded outcome —
 * {@code enqueued} with the {@code requestId} and run id, or a refusal code — never the operator's
 * name or the reason's text (FR-004-041); the bounded exit code (FR-004-045).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the submit-hearing runner")
class SubmitHearingRunnerTest {

    private static final String HEARING_ID = "0aa5bf35-1e51-45e5-9e42-64bd57e15c11";
    private static final String DAY = "2026-08-19";
    private static final String OPERATOR = "SECRET-operator";
    private static final String REASON = "SECRET reason text";
    private static final UUID REQUEST_ID = UUID.fromString("e74ed270-0b62-36d9-bb10-12bd6baa5bea");
    private static final UUID RUN_ID = UUID.fromString("7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77");

    @Mock
    private SubmitHearingAction action;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(SubmitHearingRunner.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Nested
    @DisplayName("the arguments are checked whole before anything is opened (FR-004-020)")
    class Arguments {

        @Test
        @DisplayName("a readable invocation passes")
        void readable_invocation_passes() {
            assertThat(SubmitHearingRunner.checkArguments(args(HEARING_ID, DAY))).isEmpty();
        }

        @Test
        @DisplayName("a missing argument, or a flag with no value, is missing-argument")
        void missing_arguments() {
            assertThat(SubmitHearingRunner.checkArguments("submit-hearing", "--hearing-id",
                    HEARING_ID, "--operator", "op", "--reason", "why"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(SubmitHearingRunner.checkArguments("submit-hearing", "--hearing-id",
                    HEARING_ID, "--hearing-day", DAY, "--operator", "op", "--reason"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
        }

        @Test
        @DisplayName("a hearing id that is not a UUID, or a day that is not a date, is "
                + "unreadable-argument")
        void unreadable_arguments() {
            assertThat(SubmitHearingRunner.checkArguments(args("not-a-uuid", DAY)))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
            assertThat(SubmitHearingRunner.checkArguments(args(HEARING_ID, "19/08/2026")))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }

        @Test
        @DisplayName("a refused run calls nothing, answers refused and echoes nothing")
        void refused_run_echoes_nothing() {
            assertThat(new SubmitHearingRunner(action).run(args("SECRET-id", DAY)))
                    .isEqualTo(RunnerOutcome.REFUSED);
            verifyNoInteractions(action);
            assertThat(lines()).containsExactly("submit-hearing refused reason=unreadable-argument");
        }
    }

    @Test
    @DisplayName("hands the arguments to the action as supplied, and prints enqueued with the "
            + "requestId and run id, never the operator or reason")
    void enqueued_is_done() {
        when(action.submit(any())).thenReturn(new SubmitHearingOutcome.Enqueued(REQUEST_ID, RUN_ID));

        assertThat(new SubmitHearingRunner(action).run(args(HEARING_ID, DAY)))
                .isEqualTo(RunnerOutcome.DONE);
        verify(action).submit(new SubmitHearingRequest(Optional.of(HEARING_ID), Optional.of(DAY),
                Optional.of(OPERATOR), Optional.of(REASON)));
        assertThat(lines()).containsExactly("submit-hearing done outcome=enqueued requestId="
                + REQUEST_ID + " runId=" + RUN_ID + " messageId=RESULTS:" + REQUEST_ID);
        assertThat(lines()).noneMatch(line -> line.contains("SECRET"));
    }

    @Test
    @DisplayName("prints payload-unavailable and answers refused")
    void payload_unavailable_is_refused() {
        when(action.submit(any())).thenReturn(new SubmitHearingOutcome.PayloadUnavailable());

        assertThat(new SubmitHearingRunner(action).run(args(HEARING_ID, DAY)))
                .isEqualTo(RunnerOutcome.REFUSED);
        assertThat(lines()).containsExactly("submit-hearing refused reason=payload-unavailable");
    }

    @Test
    @DisplayName("answers refused when the action refuses its input")
    void action_refusal_is_refused() {
        when(action.submit(any()))
                .thenReturn(new SubmitHearingOutcome.Refused(WholeRunRefusal.ARGUMENT_TOO_LONG));

        assertThat(new SubmitHearingRunner(action).run(args(HEARING_ID, DAY)))
                .isEqualTo(RunnerOutcome.REFUSED);
        assertThat(lines()).containsExactly("submit-hearing refused reason=argument-too-long");
    }

    @Test
    @DisplayName("answers failed when the broker cannot take the command")
    void broker_failure_is_failed() {
        when(action.submit(any())).thenReturn(new SubmitHearingOutcome.BrokerUnavailable());

        assertThat(new SubmitHearingRunner(action).run(args(HEARING_ID, DAY)))
                .isEqualTo(RunnerOutcome.FAILED);
        assertThat(lines()).containsExactly("submit-hearing failed reason=broker-unavailable");
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static String[] args(final String hearingId, final String day) {
        return new String[] {"submit-hearing", "--hearing-id", hearingId, "--hearing-day", day,
            "--operator", OPERATOR, "--reason", REASON};
    }
}
