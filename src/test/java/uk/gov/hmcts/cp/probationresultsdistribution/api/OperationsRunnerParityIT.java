package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.ReplayRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.ResendLegRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * Pins User Story 7 (FR-005-005; SC-005-005): the operations API and slice 004's command-line
 * runners are two front doors onto one capability, so the same input against identically seeded
 * state gives the same answer through either. The design's mixed list — two eligible {@code FAILED},
 * one {@code SENT} without the override, one under a live request claim and one unknown — is seeded
 * twice, one copy run through {@link ResendLegRunner} and the other through {@code POST
 * /operations/resend-leg}, and the two reports are compared position for position; a run refused
 * whole is refused with the same code through both; and a replay refused (or done) through {@code
 * POST /operations/requests/{requestId}/replay} is refused (or done) with the same code through
 * {@link ReplayRunner} in the same state.
 *
 * <p>Both doors are built over the same production classes: a real {@link LegResender} from {@link
 * ResendTestSupport} on the shared PostgreSQL, Redis and WireMock, its pause read switchable here,
 * and a real {@link ReplayAction} over the JDBC processed log. Replay has no production {@link
 * ReplayEnqueuer} wired in Spring yet (004's gap), so a recording double stands in for the broker;
 * every replay refusal is decided before the enqueue, so the double never decides an outcome. The
 * runner's per-id outcomes are its own {@code resend-leg id} lines; the API's are 004's {@code
 * resend-leg report} line for the run its {@code 202} names.
 */
@WebMvcTest(controllers = {ResendLegController.class, ReplayController.class},
        properties = "distribution.consumer.enabled=false")
@ActiveProfiles("test")
@Import(OperationsRunnerParityIT.RealCapabilityBeans.class)
@DisplayName("the operations API and its command-line fallback")
class OperationsRunnerParityIT extends ResendTestSupport {

    private static final AtomicBoolean PAUSED = new AtomicBoolean(false);
    private static final Stack STACK = new Stack();
    private static final RecordingEnqueuer ENQUEUER = new RecordingEnqueuer();

    private static final String RESEND_PATH = "/operations/resend-leg";
    private static final String RESEND_LEG = "resend-leg";
    private static final String REASON_PATH = "$.reason";
    private static final long WAIT_SECONDS = 30;

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final CapturingAppender appender = new CapturingAppender();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReplayAction replayAction;
    @Autowired
    private LegResender legResender;
    @MockitoBean
    private OperationsAuditPublisher auditPublisher;
    @MockitoBean
    private MetricRegistry metrics;

    @BeforeEach
    void attachAppender() {
        PAUSED.set(false);
        ENQUEUER.messageIds.clear();
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        PAUSED.set(false);
        root.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("resend-leg")
    class ResendLeg {

        @Test
        @DisplayName("the design's mixed list gives the same five outcomes, id for id")
        void resendLeg_mixedListThroughBothDoors_should_reportTheSameOutcomePerId() throws Exception {
            stubYouthSwitchActive();
            stubGateway(200);
            final List<UUID> viaRunner = seedMixedList();
            final List<UUID> viaApi = seedMixedList();

            final RunnerOutcome runnerOutcome = new ResendLegRunner(legResender).run(
                    resendArgs(joined(viaRunner), OPERATOR));
            final List<String> runnerReport = runnerOutcomes(viaRunner);

            final MvcResult answer = postResendLeg(viaApi, OPERATOR);
            assertThat(answer.getResponse().getStatus()).isEqualTo(202);
            final UUID runId = UUID.fromString(JsonPath.read(
                    answer.getResponse().getContentAsString(), "$.resendRunId"));
            final List<String> apiReport = apiOutcomes(awaitReport(runId), viaApi);

            assertThat(runnerOutcome).isEqualTo(RunnerOutcome.DONE);
            assertThat(apiReport).as("the API's report, position for position with the runner's")
                    .isEqualTo(runnerReport);
            assertThat(runnerReport).containsExactly("SENT", "SENT", "not-eligible",
                    "request-claimed", "unknown-request");
            for (int i = 0; i < viaRunner.size() - 1; i++) {
                assertThat(attemptCount(viaApi.get(i))).as("attempt rows of position %d", i)
                        .isEqualTo(attemptCount(viaRunner.get(i)));
            }
            assertThat(gatewayPostCount()).as("two sends through each door").isEqualTo(4);
        }

        @Test
        @DisplayName("a run while intake is paused is refused whole intake-paused through both")
        void resendLeg_intakePaused_should_refuseWholeWithTheSameCode() throws Exception {
            final List<UUID> viaRunner = seedMixedList();
            final List<UUID> viaApi = seedMixedList();
            PAUSED.set(true);

            final RunnerOutcome runnerOutcome = new ResendLegRunner(legResender).run(
                    resendArgs(joined(viaRunner), OPERATOR));
            final String apiCode = refusalCode(postResendLeg(viaApi, OPERATOR));

            assertThat(runnerOutcome).isEqualTo(RunnerOutcome.REFUSED);
            assertThat(apiCode).isEqualTo("intake-paused").isEqualTo(runnerRefusalCode());
            assertThat(attemptCount(viaRunner.getFirst())).isEqualTo(1);
            assertThat(attemptCount(viaApi.getFirst())).isEqualTo(1);
            assertThat(gatewayPostCount()).isZero();
        }

        @Test
        @DisplayName("more ids than the cap is refused whole too-many-request-ids through both")
        void resendLeg_overTheCap_should_refuseWholeWithTheSameCode() throws Exception {
            final List<UUID> ids = IntStream.range(0, 101).mapToObj(i -> UUID.randomUUID()).toList();

            final RunnerOutcome runnerOutcome = new ResendLegRunner(legResender).run(
                    resendArgs(joined(ids), OPERATOR));
            final String apiCode = refusalCode(postResendLeg(ids, OPERATOR));

            assertThat(runnerOutcome).isEqualTo(RunnerOutcome.REFUSED);
            assertThat(apiCode).isEqualTo("too-many-request-ids").isEqualTo(runnerRefusalCode());
            assertThat(gatewayPostCount()).isZero();
        }

        @Test
        @DisplayName("an operator over 200 characters is refused whole argument-too-long through both")
        void resendLeg_operatorTooLong_should_refuseWholeWithTheSameCode() throws Exception {
            final List<UUID> viaRunner = seedMixedList();
            final List<UUID> viaApi = seedMixedList();
            final String longOperator = "L".repeat(201);

            final RunnerOutcome runnerOutcome = new ResendLegRunner(legResender).run(
                    resendArgs(joined(viaRunner), longOperator));
            final String apiCode = refusalCode(postResendLeg(viaApi, longOperator));

            assertThat(runnerOutcome).isEqualTo(RunnerOutcome.REFUSED);
            assertThat(apiCode).isEqualTo("argument-too-long").isEqualTo(runnerRefusalCode());
            assertThat(attemptCount(viaRunner.getFirst())).isEqualTo(1);
            assertThat(attemptCount(viaApi.getFirst())).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("replay")
    class Replay {

        @Test
        @DisplayName("an unknown request is refused unknown-request through both")
        void replay_unknownRequest_should_refuseWithTheSameCode() throws Exception {
            assertSameReplayAnswer(UUID.randomUUID(), "unknown-request");
        }

        @Test
        @DisplayName("a request still in flight is refused not-eligible through both")
        void replay_requestInFlight_should_refuseWithTheSameCode() throws Exception {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "RECEIVED", null);
            seedBareAttemptOne(requestId, "PENDING", null);

            assertSameReplayAnswer(requestId, "not-eligible");
        }

        @Test
        @DisplayName("a FAILED request with a resend in flight is refused resend-in-progress through both")
        void replay_resendInFlight_should_refuseWithTheSameCode() throws Exception {
            final UUID requestId = seedFailedPayloadUnavailable();
            seedResendRow(requestId, 2, "PENDING", null, false);

            assertSameReplayAnswer(requestId, "resend-in-progress");
        }

        @Test
        @DisplayName("a COMPLETED request is a no-op through both")
        void replay_completedRequest_should_answerNoOpThroughBoth() throws Exception {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "COMPLETED", null);
            seedSentAttemptOne(requestId, "SENT", null, 200);

            final RunnerOutcome runnerOutcome = new ReplayRunner(replayAction).run(
                    "replay", "--request-id", requestId.toString());
            final MvcResult answer = postReplay(requestId);

            assertThat(runnerOutcome).isEqualTo(RunnerOutcome.DONE);
            assertThat(answer.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(answer.getResponse().getContentAsString(), "$.outcome"))
                    .isEqualTo("no-op").isEqualTo(runnerReplayDone());
            assertThat(ENQUEUER.messageIds).isEmpty();
        }

        @Test
        @DisplayName("a FAILED request is enqueued through both, each under a fresh identity")
        void replay_failedRequest_should_enqueueThroughBoth() throws Exception {
            final UUID viaRunner = seedFailedPayloadUnavailable();
            final UUID viaApi = seedFailedPayloadUnavailable();

            final RunnerOutcome runnerOutcome = new ReplayRunner(replayAction).run(
                    "replay", "--request-id", viaRunner.toString());
            final MvcResult answer = postReplay(viaApi);

            assertThat(runnerOutcome).isEqualTo(RunnerOutcome.DONE);
            assertThat(answer.getResponse().getStatus()).isEqualTo(200);
            assertThat((String) JsonPath.read(answer.getResponse().getContentAsString(), "$.outcome"))
                    .isEqualTo("enqueued").isEqualTo(runnerReplayDone());
            assertThat(ENQUEUER.messageIds).hasSize(2).doesNotHaveDuplicates();
        }

        private void assertSameReplayAnswer(final UUID requestId, final String expectedCode)
                throws Exception {
            final RunnerOutcome runnerOutcome = new ReplayRunner(replayAction).run(
                    "replay", "--request-id", requestId.toString());
            final String runnerCode = lastValue("replay refused reason=", ReplayRunner.class);
            final String apiCode = refusalCode(postReplay(requestId));

            assertThat(runnerOutcome).isEqualTo(RunnerOutcome.REFUSED);
            assertThat(apiCode).isEqualTo(expectedCode).isEqualTo(runnerCode);
            assertThat(ENQUEUER.messageIds).isEmpty();
        }
    }

    private List<UUID> seedMixedList() {
        final UUID failedA = seedFailedPayloadUnavailable();
        final UUID failedB = seedFailedPayloadUnavailable();
        final UUID sent = UUID.randomUUID();
        seedRequestRow(sent, cachedHearing(), "COMPLETED", null);
        seedSentAttemptOne(sent, "SENT", null, 200);
        final UUID claimed = UUID.randomUUID();
        seedRequestRow(claimed, cachedHearing(), "RECEIVED", null, true);
        seedBareAttemptOne(claimed, "PENDING", null);
        return List.of(failedA, failedB, sent, claimed, UUID.randomUUID());
    }

    private UUID seedFailedPayloadUnavailable() {
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
        seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
        return requestId;
    }

    private UUID cachedHearing() {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(SHARED_TIME));
        return hearingId;
    }

    private static String joined(final List<UUID> ids) {
        return ids.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    private static String[] resendArgs(final String ids, final String operator) {
        return new String[] {RESEND_LEG, "--leg", "HMPPS", "--request-ids", ids,
            "--operator", operator, "--reason", REASON};
    }

    private MvcResult postResendLeg(final List<UUID> ids, final String operator) throws Exception {
        final String requestIds = ids.stream().map(id -> "\"" + id + "\"")
                .collect(Collectors.joining(","));
        return mockMvc.perform(MockMvcRequestBuilders.post(RESEND_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestIds": [%s], "leg": "HMPPS", "operator": "%s",
                                 "reason": "%s"}
                                """.formatted(requestIds, operator, REASON)))
                .andReturn();
    }

    private MvcResult postReplay(final UUID requestId) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(
                "/operations/requests/" + requestId + "/replay")).andReturn();
    }

    private static String refusalCode(final MvcResult answer) throws Exception {
        assertThat(answer.getResponse().getStatus()).as("a refusal is a 4xx").isBetween(400, 499);
        return JsonPath.read(answer.getResponse().getContentAsString(), REASON_PATH);
    }

    private String runnerRefusalCode() {
        return lastValue("resend-leg refused reason=", ResendLegRunner.class);
    }

    private String runnerReplayDone() {
        return lastValue("replay done outcome=", ReplayRunner.class);
    }

    /** The value following {@code prefix} on the last line the given runner logged with it. */
    private String lastValue(final String prefix, final Class<?> logger) {
        final List<String> values = appender.events.stream()
                .filter(event -> event.getLoggerName().equals(logger.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith(prefix))
                .map(line -> line.substring(prefix.length()).split(" ", -1)[0])
                .toList();
        assertThat(values).as("a %s line from %s", prefix, logger.getSimpleName()).isNotEmpty();
        return values.getLast();
    }

    /** The runner's own per-id outcomes, in the order of the list it was given. */
    private List<String> runnerOutcomes(final List<UUID> ids) {
        final List<String> lines = appender.events.stream()
                .filter(event -> event.getLoggerName().equals(ResendLegRunner.class.getName()))
                .map(ILoggingEvent::getFormattedMessage)
                .filter(line -> line.startsWith("resend-leg id "))
                .toList();
        final List<String> outcomes = new ArrayList<>();
        for (final UUID id : ids) {
            outcomes.add(lines.stream().map(line -> valueAfter(line, "requestId=" + id + " outcome="))
                    .filter(value -> value != null).findFirst().orElse("missing"));
        }
        return Collections.unmodifiableList(outcomes);
    }

    /** 004's report entries for the run the API accepted, in the order of the list sent. */
    private static List<String> apiOutcomes(final String report, final List<UUID> ids) {
        final List<String> outcomes = new ArrayList<>();
        for (final UUID id : ids) {
            final String value = valueAfter(report, id + "=");
            outcomes.add(value == null ? "missing" : value);
        }
        return Collections.unmodifiableList(outcomes);
    }

    private static String valueAfter(final String line, final String key) {
        final Matcher matcher = Pattern.compile(Pattern.quote(key) + "([^,\\] ]+)").matcher(line);
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Waits for the run the answer named to log 004's report line, and returns that line. */
    private String awaitReport(final UUID resendRunId) throws InterruptedException {
        final String fragment = "resend-leg report resendRunId=" + resendRunId;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            final List<String> found = appender.events.stream()
                    .map(ILoggingEvent::getFormattedMessage)
                    .filter(line -> line.contains(fragment)).toList();
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("no resend-leg report for run " + resendRunId);
    }

    /** A thread-safe capture of every event the root logger passes, from any thread. */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(final ILoggingEvent event) {
            events.add(event);
        }
    }

    /** Stands in for the broker: records each fresh message identity and takes every message. */
    private static final class RecordingEnqueuer implements ReplayEnqueuer {

        private final List<String> messageIds = new CopyOnWriteArrayList<>();

        @Override
        public void enqueue(final String messageId, final String correlationId, final String body) {
            messageIds.add(messageId);
        }
    }

    /** The shared stack's real capabilities: resend-leg with a switchable pause, and replay. */
    private static final class Stack extends ResendTestSupport {

        private LegResender resender;
        private ReplayAction replay;

        private synchronized LegResender legResender() {
            if (resender == null) {
                resender = resender(sharedRedisConnection(), 100, PAUSED::get, null);
            }
            return resender;
        }

        private synchronized ReplayAction replayAction() {
            if (replay == null) {
                replay = new ReplayAction(requestStore(), ENQUEUER);
            }
            return replay;
        }
    }

    /** Registers both real capabilities so the conditional controllers and executor see them. */
    static class RealCapabilityBeans {

        /* default */ @Bean
        LegResender legResender() {
            return STACK.legResender();
        }

        /* default */ @Bean
        ReplayAction replayAction() {
            return STACK.replayAction();
        }
    }
}
