package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.node.JsonNodeFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DlqCompletedListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseGate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.OutcomeMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.application.SubmitHearingAction;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.DlqCompletedListingRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.ReplayRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.RequestListingRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.ResendLegRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.SubmitHearingRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.ResendClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;

@DisplayName("telemetry privacy")
class TelemetryPrivacyTest {

    private static final String CANARY = "CANARY-" + "never-printed";
    private static final String OPERATOR_CANARY = "CANARY-" + "operator-name";
    private static final String REASON_CANARY = "CANARY-" + "reason text";
    private static final String SERVICE_PACKAGE = "uk.gov.hmcts.cp.probationresultsdistribution";
    private static final UUID HEARING_ID = UUID.fromString("0aa5bf35-1e51-45e5-9e42-64bd57e15c11");
    private static final Pattern KEY = Pattern.compile("(\\w+)=");
    private static final Set<String> LISTING_KEYS = Set.of("requestId", "hearingId", "hearingDay",
            "requestStatus", "failureReason", "completionReason", "attempt", "attemptOrigin",
            "legStatus", "legReason", "httpStatus", "updatedAt", "sentAt", "rows", "more", "reason",
            "sequenceNumber", "messageId", "deadLetterReason", "enqueuedAt", "entries");

    @Test
    @DisplayName("defines exactly the seven MDC fields and never userId")
    void mdc_fields_exclude_user_id() {
        assertThat(MdcFields.KEYS).containsExactlyInAnyOrder(
                "requestId", "hearingId", "hearingDay", "source", "sequenceNumber", "deliveryCount",
                "leg");
        assertThat(MdcFields.KEYS).doesNotContain("userId");
    }

    @Test
    @DisplayName("the listings print only identifiers, statuses, reasons, attempts, origins, HTTP "
            + "statuses and times, and no text chosen elsewhere (slice 004, FR-004-041, FR-004-043)")
    void listings_print_only_their_bounded_fields() {
        final UUID completed = UUID.randomUUID();
        final DeadLetterReader reader = new CanaryDeadLetters(completed);
        final StoreWithOneRow store = new StoreWithOneRow(completed);
        final List<String> lines = capture(() -> {
            new RequestListingRunner(new RequestListingQuery(store)).run("list-requests",
                    "--status", "FAILED");
            new RequestListingRunner(new RequestListingQuery(store)).run("list-requests",
                    "--status", CANARY);
            new RequestListingRunner(new RequestListingQuery(store)).run("list-requests",
                    "--hearing-id", CANARY, "--hearing-day", CANARY);
            new DlqCompletedListingRunner(new DlqCompletedListingQuery(reader, store)).run();
        });

        assertThat(lines).hasSizeGreaterThanOrEqualTo(5);
        assertThat(lines).noneMatch(line -> line.contains(CANARY));
        for (final String line : lines) {
            final Matcher key = KEY.matcher(line);
            while (key.find()) {
                assertThat(LISTING_KEYS).as("a listing line's key in: %s", line)
                        .contains(key.group(1));
            }
        }
    }

    @Test
    @DisplayName("no line any slice 004 runner or action prints carries the operator's name, a "
            + "reason's text or any other supplied value, on every outcome (FR-004-041, SC-004-009)")
    void slice_004_runners_never_print_supplied_text() {
        final List<String> lines = captureService(() -> {
            resendLegRuns();
            submitHearingRuns();
            new ReplayRunner(mock(ReplayAction.class)).run("replay", "--request-id", CANARY);
        });

        assertThat(lines).as("lines the slice 004 runs printed").hasSizeGreaterThanOrEqualTo(12);
        for (final String reached : List.of("unknown-request", "not-run", "sent",
                "claimed-incomplete", "intake-paused", "argument-too-long", "outcome=enqueued",
                "payload-unavailable", "broker-unavailable", "unreadable-argument")) {
            assertThat(lines).as("an outcome the runs reached").anyMatch(line -> line.contains(reached));
        }
        assertThat(lines).noneMatch(line -> line.contains(CANARY) || line.contains("CANARY-"));
    }

    /** resend-leg over the real capability: refused whole, and a run reaching every per-ID outcome. */
    private static void resendLegRuns() {
        final IntakePauseGate pauseGate = mock(IntakePauseGate.class);
        final ProcessedRequestRepository store = mock(ProcessedRequestRepository.class);
        final DistributionRunner runner = mock(DistributionRunner.class);
        final UUID refused = UUID.randomUUID();
        final UUID claimFails = UUID.randomUUID();
        final UUID sent = UUID.randomUUID();
        final UUID incomplete = UUID.randomUUID();
        when(pauseGate.pausedByOperator()).thenReturn(false, false, false, false, false, true);
        when(store.claimResend(eq("RESULTS"), eq(refused), any(), any(), any(), any()))
                .thenReturn(new ResendClaim.Refused(ResendRefusal.UNKNOWN_REQUEST));
        when(store.claimResend(eq("RESULTS"), eq(claimFails), any(), any(), any(), any()))
                .thenThrow(new DataAccessResourceFailureException(CANARY));
        when(store.claimResend(eq("RESULTS"), eq(sent), any(), any(), any(), any()))
                .thenAnswer(invocation -> claimed(sent, invocation.getArgument(2)));
        when(store.claimResend(eq("RESULTS"), eq(incomplete), any(), any(), any(), any()))
                .thenAnswer(invocation -> claimed(incomplete, invocation.getArgument(2)));
        when(runner.runResend(any(), any(), anyBoolean()))
                .thenReturn(new RunResult(AttemptOrigin.RESEND, LegStatus.SENT, Optional.empty(), true))
                .thenThrow(new DataAccessResourceFailureException(CANARY));
        final ResendLegRunner resend = new ResendLegRunner(
                new LegResender(pauseGate, store, runner, mock(OutcomeMetrics.class), 100));
        final String ids = String.join(",", refused.toString(), claimFails.toString(),
                sent.toString(), incomplete.toString());
        resend.run("resend-leg", "--leg", "HMPPS", "--request-ids", ids, "--operator",
                OPERATOR_CANARY, "--reason", REASON_CANARY, "--force-resend-sent",
                "--allow-changed-payload");
        // The pause read now answers paused: the run is refused whole.
        resend.run("resend-leg", "--leg", "HMPPS", "--request-ids", sent.toString(), "--operator",
                OPERATOR_CANARY, "--reason", REASON_CANARY);
        resend.run("resend-leg", "--leg", "HMPPS", "--request-ids", sent.toString(), "--operator",
                OPERATOR_CANARY.repeat(20), "--reason", REASON_CANARY);
        resend.run("resend-leg", "--leg", CANARY, "--request-ids", CANARY, "--operator",
                OPERATOR_CANARY, "--reason", REASON_CANARY);
    }

    /** submit-hearing over the real action: enqueued, payload unavailable, broker unavailable, refused. */
    private static void submitHearingRuns() {
        final AtomicReference<Optional<HearingPayloadSource.FetchedPayload>> cached =
                new AtomicReference<>(Optional.of(new HearingPayloadSource.FetchedPayload(
                        JsonNodeFactory.instance.objectNode().put("sharedTime",
                                "2026-08-19T16:42:07.512Z"), PayloadSource.CACHE_DAY,
                        Optional.of(Instant.parse("2026-08-19T16:42:07.512Z")))));
        final AtomicReference<Boolean> brokerUp = new AtomicReference<>(true);
        final CommandEnqueuer enqueuer = command -> {
            if (!brokerUp.get()) {
                throw new CommandEnqueuer.CommandEnqueueException(new IllegalStateException(CANARY));
            }
        };
        final SubmitHearingRunner submit = new SubmitHearingRunner(
                new SubmitHearingAction(command -> cached.get(), enqueuer, UUID::randomUUID));
        final String[] args = {"submit-hearing", "--hearing-id", HEARING_ID.toString(),
            "--hearing-day", "2026-08-19", "--operator", OPERATOR_CANARY, "--reason", REASON_CANARY};
        submit.run(args);
        brokerUp.set(false);
        submit.run(args);
        cached.set(Optional.empty());
        submit.run(args);
        submit.run("submit-hearing", "--hearing-id", CANARY, "--hearing-day", CANARY, "--operator",
                OPERATOR_CANARY, "--reason", REASON_CANARY);
    }

    private static ResendClaim claimed(final UUID requestId, final UUID resendRunId) {
        final DistributionCommand command = new DistributionCommand("RESULTS", requestId, HEARING_ID,
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.of(UUID.randomUUID()));
        return new ResendClaim.Claimed(new ResendAttempt(UUID.randomUUID(), 2, RunClaim.fresh(),
                resendRunId), command);
    }

    /**
     * Captures every line any of the service's own loggers prints while {@code runs} runs, with the
     * message of any exception a line attaches.
     */
    private static List<String> captureService(final Runnable runs) {
        final Logger service = (Logger) LoggerFactory.getLogger(SERVICE_PACKAGE);
        final ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        service.addAppender(logs);
        try {
            runs.run();
        } finally {
            service.detachAppender(logs);
            logs.stop();
        }
        return logs.list.stream().map(event -> event.getThrowableProxy() == null
                ? event.getFormattedMessage()
                : event.getFormattedMessage() + " " + event.getThrowableProxy().getMessage()).toList();
    }

    private static List<String> capture(final Runnable listings) {
        final List<Logger> loggers = List.of(
                (Logger) LoggerFactory.getLogger(RequestListingRunner.class),
                (Logger) LoggerFactory.getLogger(DlqCompletedListingRunner.class),
                (Logger) LoggerFactory.getLogger(RequestListingQuery.class),
                (Logger) LoggerFactory.getLogger(DlqCompletedListingQuery.class));
        final ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        loggers.forEach(logger -> logger.addAppender(logs));
        try {
            listings.run();
        } finally {
            loggers.forEach(logger -> logger.detachAppender(logs));
            logs.stop();
        }
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** A dead-letter queue whose entries carry text chosen elsewhere in every free field. */
    private record CanaryDeadLetters(UUID completed) implements DeadLetterReader {

        @Override
        public List<DeadLetterEntry> peek(final long fromSequenceNumber, final int maxEntries) {
            return fromSequenceNumber > 0 ? List.of() : List.of(
                    new DeadLetterEntry(0L, Optional.of(CANARY), Optional.of(completed),
                            Optional.of(CANARY), Optional.empty()),
                    new DeadLetterEntry(1L, Optional.of("RESULTS:" + completed),
                            Optional.of(completed), Optional.of("leg-exhausted"),
                            Optional.of(Instant.parse("2026-08-19T16:42:07.512Z"))));
        }
    }

    /** A store answering one listed row, and every request as {@code COMPLETED}. */
    private record StoreWithOneRow(UUID requestId) implements RequestListingRepository {

        @Override
        public List<ListedAttempt> byHearing(final UUID hearingId, final LocalDate hearingDay,
                                             final int limit) {
            return List.of(row());
        }

        @Override
        public List<ListedAttempt> byLatestAttempt(final String status,
                                                   final Optional<String> reason,
                                                   final Optional<Instant> updatedAfter,
                                                   final Optional<Instant> updatedBefore,
                                                   final int limit) {
            return List.of(row());
        }

        @Override
        public Map<UUID, String> requestStatuses(final String source,
                                                 final Collection<UUID> requestIds) {
            final Map<UUID, String> statuses = new HashMap<>();
            requestIds.forEach(id -> statuses.put(id, "COMPLETED"));
            return statuses;
        }

        private ListedAttempt row() {
            return new ListedAttempt(requestId, UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                    "FAILED", Optional.of("leg-exhausted"), Optional.empty(), 1, "queue", "FAILED",
                    Optional.of("gateway-transient"), OptionalInt.of(503),
                    Instant.parse("2026-08-19T16:42:07.512Z"), Optional.empty());
        }
    }
}
