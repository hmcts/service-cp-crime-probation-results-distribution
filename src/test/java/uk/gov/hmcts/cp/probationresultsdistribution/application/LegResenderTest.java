package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.time.LocalDate;
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
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.ResendClaim;

/**
 * Plain-mock cases of {@link LegResender}'s store-failure and fencing paths (slice 004,
 * FR-004-022, FR-004-028, FR-004-039; spec edge case "The store fails during a resend run"): an ID
 * whose claim fails is {@code not-run}, an ID claimed but not recorded is
 * {@code claimed-incomplete}, a store failure fails the run while its report stands, and a store
 * that cannot answer the first pause read refuses the run whole {@code store-unavailable}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("LegResender's store-failure paths")
class LegResenderTest {

    private static final UUID ID_1 = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");
    private static final UUID ID_2 = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");

    @Mock
    private IntakePauseGate pauseGate;
    @Mock
    private ProcessedRequestRepository store;
    @Mock
    private DistributionRunner runner;
    @Mock
    private OutcomeMetrics metrics;

    private final Logger logger = (Logger) LoggerFactory.getLogger(LegResender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        logger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("a store failure for one id")
    class StoreFailure {

        @Test
        @DisplayName("a failed claim is not-run, the next id still runs, and the run is failed")
        void claim_failure_is_not_run_and_the_run_carries_on() {
            when(pauseGate.pausedByOperator()).thenReturn(false);
            when(store.claimResend(eq("RESULTS"), eq(ID_1), any(), any(), any()))
                    .thenThrow(new DataAccessResourceFailureException("connection lost"));
            when(store.claimResend(eq("RESULTS"), eq(ID_2), any(), any(), any()))
                    .thenAnswer(invocation -> claimed(ID_2, invocation.getArgument(2)));
            when(runner.runResend(any(), any(), anyBoolean())).thenReturn(sent(true));

            final ResendRunResult result = resender().resend(request(ID_1, ID_2));

            assertThat(result.wholeRunRefusal()).isEmpty();
            assertThat(result.entries()).containsExactly(
                    new ResendRunResult.Entry(ID_1, new ResendEntryOutcome.NotRun()),
                    new ResendRunResult.Entry(ID_2,
                            new ResendEntryOutcome.Ran(LegStatus.SENT, Optional.empty())));
            assertThat(result.storeFailed()).isTrue();
            assertThat(errorLines()).anySatisfy(line -> assertThat(line)
                    .contains("store-unavailable", "requestId=" + ID_1,
                            "cause=DataAccessResourceFailureException")
                    .doesNotContain("connection lost"));
        }

        @Test
        @DisplayName("a store failure after the claim is claimed-incomplete, and the run is failed")
        void failure_after_claim_is_claimed_incomplete() {
            when(pauseGate.pausedByOperator()).thenReturn(false);
            when(store.claimResend(eq("RESULTS"), eq(ID_1), any(), any(), any()))
                    .thenAnswer(invocation -> claimed(ID_1, invocation.getArgument(2)));
            when(runner.runResend(any(), any(), anyBoolean()))
                    .thenThrow(new DataAccessResourceFailureException("completion write failed"));

            final ResendRunResult result = resender().resend(request(ID_1));

            assertThat(result.entries()).containsExactly(
                    new ResendRunResult.Entry(ID_1, new ResendEntryOutcome.ClaimedIncomplete()));
            assertThat(result.notRun()).isEmpty();
            assertThat(result.storeFailed()).isTrue();
            assertThat(errorLines()).anySatisfy(line -> assertThat(line)
                    .contains("store-unavailable", "requestId=" + ID_1, "attempt=2",
                            "cause=DataAccessResourceFailureException"));
        }
    }

    @Nested
    @DisplayName("a fenced completion (FR-004-028)")
    class Fenced {

        @Test
        @DisplayName("is claimed-incomplete, counts a stale-runner rejection, and does not fail the run")
        void fenced_completion_is_claimed_incomplete() {
            when(pauseGate.pausedByOperator()).thenReturn(false);
            when(store.claimResend(eq("RESULTS"), eq(ID_1), any(), any(), any()))
                    .thenAnswer(invocation -> claimed(ID_1, invocation.getArgument(2)));
            when(runner.runResend(any(), any(), anyBoolean())).thenReturn(sent(false));

            final ResendRunResult result = resender().resend(request(ID_1));

            assertThat(result.entries()).containsExactly(
                    new ResendRunResult.Entry(ID_1, new ResendEntryOutcome.ClaimedIncomplete()));
            assertThat(result.storeFailed()).isFalse();
            verify(metrics).staleRunnerRejection();
        }
    }

    @Nested
    @DisplayName("a pause read the store cannot answer")
    class PauseReadFailure {

        @Test
        @DisplayName("before the run refuses it whole store-unavailable, claiming nothing")
        void first_pause_read_failure_refuses_the_run_whole() {
            when(pauseGate.pausedByOperator())
                    .thenThrow(new DataAccessResourceFailureException("store down"));

            final ResendRunResult result = resender().resend(request(ID_1, ID_2));

            assertThat(result.wholeRunRefusal()).contains(WholeRunRefusal.STORE_UNAVAILABLE);
            assertThat(result.entries()).isEmpty();
            verify(store, never()).claimResend(any(), any(), any(), any(), any());
        }

        @Test
        @DisplayName("mid-run claims no further id, reports the rest not-run and fails the run")
        void mid_run_pause_read_failure_stops_claiming() {
            when(pauseGate.pausedByOperator()).thenReturn(false, false)
                    .thenThrow(new DataAccessResourceFailureException("store down"));
            when(store.claimResend(eq("RESULTS"), eq(ID_1), any(), any(), any()))
                    .thenAnswer(invocation -> claimed(ID_1, invocation.getArgument(2)));
            when(runner.runResend(any(), any(), anyBoolean())).thenReturn(sent(true));

            final ResendRunResult result = resender().resend(request(ID_1, ID_2));

            assertThat(result.entries()).containsExactly(
                    new ResendRunResult.Entry(ID_1,
                            new ResendEntryOutcome.Ran(LegStatus.SENT, Optional.empty())),
                    new ResendRunResult.Entry(ID_2, new ResendEntryOutcome.NotRun()));
            assertThat(result.storeFailed()).isTrue();
            verify(store, never()).claimResend(eq("RESULTS"), eq(ID_2), any(), any(), any());
        }
    }

    private LegResender resender() {
        return new LegResender(pauseGate, store, runner, metrics, 100);
    }

    private static ResendRequest request(final UUID... ids) {
        return new ResendRequest(List.of(ids), "an-operator", "a reason", false);
    }

    private static ResendClaim claimed(final UUID requestId, final UUID resendRunId) {
        final DistributionCommand command = new DistributionCommand("RESULTS", requestId,
                UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
        return new ResendClaim.Claimed(new ResendAttempt(UUID.randomUUID(), 2, RunClaim.fresh(),
                resendRunId), command);
    }

    private static RunResult sent(final boolean recorded) {
        return new RunResult(AttemptOrigin.RESEND, LegStatus.SENT, Optional.empty(), recorded);
    }

    private List<String> errorLines() {
        return appender.list.stream().filter(event -> event.getLevel() == Level.ERROR)
                .map(ILoggingEvent::getFormattedMessage).toList();
    }
}
