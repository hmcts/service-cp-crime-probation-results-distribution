package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import com.jayway.jsonpath.JsonPath;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tag;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;

/**
 * Pins User Story 3 over HTTP (FR-005-014, FR-005-016, FR-005-017, FR-005-023, FR-005-031): {@code
 * allowTerminalSuccess: true} reaches slice 004's real {@link LegResender} unchanged with the
 * operator and the reason, and both audit events say the override was requested; without it the
 * same id is {@code not-eligible} and the call is still {@code 202}; an operator or a reason over
 * 200 characters is {@code 400 argument-too-long} and nothing runs; a forced resend whose send
 * fails is still a {@code 202}-accepted call; and neither the operator nor the reason appears in
 * the answer, a log line or a metric label.
 *
 * <p>The call answers before the run (FR-005-015), so every per-id outcome is read from 004's run
 * report line and the leg's attempt rows once the run the answer names has logged its report. The
 * controller runs in a {@code @WebMvcTest} slice over a real {@link LegResender} built by {@link
 * ResendTestSupport} on the shared PostgreSQL, Redis and WireMock — the production payload chain,
 * users-groups client, gateway publisher and processed log — and the production {@link
 * ResendLegExecutor}; only the audit publisher is mocked, to read its two events.
 */
@WebMvcTest(controllers = ResendLegController.class, properties = "distribution.consumer.enabled=false")
@ActiveProfiles("test")
@Import(ForcedResendControllerIT.RealLegResenderBean.class)
@DisplayName("the forced resend over HTTP")
class ForcedResendControllerIT extends ResendTestSupport {

    /** Builds the real resend-leg capability once for the slice context, and keeps its registry. */
    private static final Stack STACK = new Stack();

    private static final String PATH = "/operations/resend-leg";
    private static final String CALLER_OPERATOR = "op-7Qx-forced-caller";
    private static final String CALLER_REASON = "rsn-9Zk forced duplicate requested";
    private static final String TERMINAL_SUCCESS = "allowTerminalSuccess";
    private static final String STATUS = "status";
    private static final long WAIT_SECONDS = 30;

    private final Logger root = (Logger) LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME);
    private final CapturingAppender appender = new CapturingAppender();

    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private OperationsAuditPublisher auditPublisher;
    @MockitoBean
    private MetricRegistry metrics;

    @BeforeEach
    void attachAppender() {
        appender.start();
        root.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        root.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("with allowTerminalSuccess")
    class WithOverride {

        @Test
        @DisplayName("reaches LegResender unchanged with operator and reason: a new forced attempt on a SENT leg")
        void resendLeg_forcedOnSentLeg_should_reachLegResenderWithOverrideOperatorAndReason()
                throws Exception {
            final UUID requestId = seedCompletedSent();
            stubYouthSwitchActive();
            stubGateway(200);

            final UUID runId = acceptedRunId(postResendLeg(requestId, true));

            assertThat(awaitReport(runId)).contains("allowTerminalSuccess=true",
                    requestId + "=SENT");
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("attempt")).isEqualTo(2);
            assertThat(row.get("attempt_origin")).isEqualTo("resend");
            assertThat(row.get("operator")).isEqualTo(CALLER_OPERATOR);
            assertThat(row.get("override")).isEqualTo(true);
            assertThat(row.get("note")).isEqualTo("override=force-resend");
            assertThat((String) requestRow(requestId).get("audit_note"))
                    .contains("FORCED_RESEND attempt=2 operator=" + CALLER_OPERATOR
                            + " reason=" + CALLER_REASON);
            assertThat(requestRow(requestId).get(STATUS)).isEqualTo("COMPLETED");
            assertThat(gatewayPostCount()).as("a genuine duplicate is sent").isEqualTo(1);
        }

        @Test
        @DisplayName("both audit events say the override was requested")
        void resendLeg_forced_should_carryTheOverrideOnBothAuditEvents() throws Exception {
            final UUID requestId = seedCompletedSent();
            stubYouthSwitchActive();
            stubGateway(200);

            final UUID runId = acceptedRunId(postResendLeg(requestId, true));
            awaitReport(runId);

            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
            verify(auditPublisher).publishRequestEvent(eq(OperationsAction.RESEND_LEG),
                    request.capture());
            assertThat(request.getValue()).containsEntry(TERMINAL_SUCCESS, true);
            @SuppressWarnings("unchecked")
            final ArgumentCaptor<Map<String, Object>> response = ArgumentCaptor.forClass(Map.class);
            verify(auditPublisher).publishResponseEvent(eq(OperationsAction.RESEND_LEG),
                    response.capture());
            assertThat(response.getValue()).containsEntry(TERMINAL_SUCCESS, true)
                    .containsEntry("outcome", "accepted")
                    .containsEntry("resendRunId", runId.toString());
            assertThat(request.getValue().values()).doesNotContain(CALLER_OPERATOR, CALLER_REASON);
            assertThat(response.getValue().values()).doesNotContain(CALLER_OPERATOR, CALLER_REASON);
        }

        @Test
        @DisplayName("a forced resend whose send fails is still 202; the failure is in 004's report only")
        void resendLeg_forcedSendFails_should_stillAnswer202AndReportTheFailure() throws Exception {
            final UUID requestId = seedCompletedSent();
            stubYouthSwitchActive();
            stubGateway(400);

            final UUID runId = acceptedRunId(postResendLeg(requestId, true));

            assertThat(awaitReport(runId)).contains(requestId + "=FAILED(receiver-rejected)");
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("attempt")).isEqualTo(2);
            assertThat(row.get(STATUS)).isEqualTo("FAILED");
            assertThat(row.get("reason")).isEqualTo("receiver-rejected");
            assertThat(requestRow(requestId).get(STATUS)).isEqualTo("COMPLETED");
        }
    }

    @Nested
    @DisplayName("without allowTerminalSuccess")
    class WithoutOverride {

        @Test
        @DisplayName("the same SENT id is not-eligible in 004's report and the call is still 202")
        void resendLeg_unforcedOnSentLeg_should_reportNotEligibleAndAnswer202() throws Exception {
            final UUID requestId = seedCompletedSent();
            stubYouthSwitchActive();
            stubGateway(200);

            final UUID runId = acceptedRunId(postResendLeg(requestId, false));

            assertThat(awaitReport(runId)).contains("allowTerminalSuccess=false",
                    requestId + "=not-eligible");
            assertThat(attemptCount(requestId)).isEqualTo(1);
            assertThat(gatewayPostCount()).isZero();
        }
    }

    @Nested
    @DisplayName("the operator and the reason")
    class CallerText {

        @ParameterizedTest(name = "an over-long {0} is 400 argument-too-long and nothing runs")
        @ValueSource(strings = {"operator", "reason"})
        @DisplayName("over 200 characters is 400 argument-too-long naming the argument, nothing run")
        void resendLeg_textOver200Characters_should_answer400ArgumentTooLong(final String argument)
                throws Exception {
            final UUID requestId = seedCompletedSent();
            stubYouthSwitchActive();
            stubGateway(200);
            final String longText = "L".repeat(201);
            final String operator = "operator".equals(argument) ? longText : CALLER_OPERATOR;
            final String reason = "reason".equals(argument) ? longText : CALLER_REASON;

            post(requestId, operator, reason, true)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.reason").value("argument-too-long"))
                    .andExpect(jsonPath("$.argumentName").value(argument))
                    .andExpect(content().string(not(containsString(longText))));

            assertThat(attemptCount(requestId)).isEqualTo(1);
            assertThat(gatewayPostCount()).isZero();
            assertThat(capturedLines()).noneSatisfy(line -> assertThat(line)
                    .contains("resend-leg report"));
        }

        @Test
        @DisplayName("appear in no answer, no log line and no metric label")
        void resendLeg_forcedRunCompleted_should_echoOperatorAndReasonNowhere() throws Exception {
            final UUID requestId = seedCompletedSent();
            stubYouthSwitchActive();
            stubGateway(200);

            final ResultActions answer = postResendLeg(requestId, true)
                    .andExpect(content().string(not(containsString(CALLER_OPERATOR))))
                    .andExpect(content().string(not(containsString(CALLER_REASON))));
            awaitReport(acceptedRunId(answer));

            assertThat(capturedLines()).isNotEmpty().noneSatisfy(line -> assertThat(line)
                    .containsAnyOf(CALLER_OPERATOR, CALLER_REASON));
            assertThat(STACK.meterTagValues()).isNotEmpty()
                    .noneSatisfy(value -> assertThat(value)
                            .containsAnyOf(CALLER_OPERATOR, CALLER_REASON));
        }
    }

    private ResultActions postResendLeg(final UUID requestId, final boolean allowTerminalSuccess)
            throws Exception {
        return post(requestId, CALLER_OPERATOR, CALLER_REASON, allowTerminalSuccess)
                .andExpect(status().isAccepted());
    }

    private ResultActions post(final UUID requestId, final String operator, final String reason,
                               final boolean allowTerminalSuccess) throws Exception {
        return mockMvc.perform(
                MockMvcRequestBuilders.post(PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"requestIds": ["%s"], "leg": "HMPPS", "operator": "%s",
                                 "reason": "%s", "allowTerminalSuccess": %s}
                                """.formatted(requestId, operator, reason, allowTerminalSuccess)));
    }

    private static UUID acceptedRunId(final ResultActions answer) throws Exception {
        final String body = answer.andReturn().getResponse().getContentAsString();
        return UUID.fromString(JsonPath.read(body, "$.resendRunId"));
    }

    /** Waits for the run the answer named to log 004's report line, and returns that line. */
    private String awaitReport(final UUID resendRunId) throws InterruptedException {
        final String fragment = "resend-leg report resendRunId=" + resendRunId;
        final long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(WAIT_SECONDS);
        while (System.nanoTime() < deadline) {
            final List<String> found = capturedLines().stream()
                    .filter(line -> line.contains(fragment)).toList();
            if (!found.isEmpty()) {
                return found.getFirst();
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
        throw new AssertionError("no resend-leg report for run " + resendRunId);
    }

    private UUID seedCompletedSent() {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(SHARED_TIME));
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, hearingId, "COMPLETED", null);
        seedSentAttemptOne(requestId, "SENT", null, 200);
        return requestId;
    }

    /** Every captured line: its formatted message, its MDC values and its thrown class, if any. */
    private List<String> capturedLines() {
        return appender.events.stream().map(event -> event.getFormattedMessage() + " "
                + event.getMDCPropertyMap().values() + " "
                + (event.getThrowableProxy() == null ? ""
                : event.getThrowableProxy().getMessage())).toList();
    }

    /** A thread-safe capture of every event the root logger passes, from any thread. */
    private static final class CapturingAppender extends AppenderBase<ILoggingEvent> {

        private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

        @Override
        protected void append(final ILoggingEvent event) {
            events.add(event);
        }
    }

    /** The shared stack's real resend-leg capability, with the meter registry it records into. */
    private static final class Stack extends ResendTestSupport {

        private LegResender resender;

        private synchronized LegResender legResender() {
            if (resender == null) {
                resender = resender();
            }
            return resender;
        }

        private List<String> meterTagValues() {
            return meterRegistry().getMeters().stream().map(Meter::getId)
                    .flatMap(id -> id.getTags().stream()).map(Tag::getValue).toList();
        }
    }

    /** Registers the real {@link LegResender} so the conditional controller and executor see it. */
    static class RealLegResenderBean {

        /* default */ @Bean
        LegResender legResender() {
            return STACK.legResender();
        }
    }
}
