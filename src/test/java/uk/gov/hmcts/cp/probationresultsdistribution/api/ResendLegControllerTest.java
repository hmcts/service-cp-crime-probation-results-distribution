package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.oneOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendAcceptance;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins FR-005-010 to FR-005-016 and FR-005-025/FR-005-026: the list reaches {@link LegResender}'s
 * accept step exactly as sent with every other input unchanged, an accepted run answers {@code 202}
 * carrying only its {@code resendRunId} while the run itself is still going (FR-005-015; T046), the
 * accepted run is handed to the run step, every whole-call refusal renders 004's own code and runs
 * nothing, both audit events bracket the call carrying bounded fields only, and nothing the caller
 * typed is echoed. {@link LegResender} is mocked; {@link OperationsRequestMapper} and
 * {@link OperationsResponseMapper} come from the production configuration.
 *
 * <p>{@link LegResender} is provided as a plain Mockito mock registered through {@link
 * LegResenderBean}, not {@code @MockitoBean}, for the reason {@code ReplayControllerTest} gives:
 * {@link ResendLegController} is registered by a {@code @ConditionalOnBean(LegResender.class)}
 * {@code @Bean} method, evaluated before a {@code @MockitoBean} override registers.
 */
@WebMvcTest(controllers = ResendLegController.class, properties = "distribution.consumer.enabled=false")
@ActiveProfiles("test")
@Import(ResendLegControllerTest.LegResenderBean.class)
@DisplayName("the resend-leg controller")
class ResendLegControllerTest {

    private static final String PATH = "/operations/resend-leg";
    private static final String FIRST = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    private static final String SECOND = "4fa85f64-5717-4562-b3fc-2c963f66afa6";
    private static final UUID RUN_ID = UUID.fromString("9c4f2a10-1b2c-4d3e-8f90-0a1b2c3d4e5f");
    private static final String OPERATOR = "an-operator-name";
    private static final String REASON = "a-reason-text";
    private static final String VALID_BODY = """
            {"requestIds": ["%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s"}
            """.formatted(FIRST, OPERATOR, REASON);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private LegResender legResender;
    @MockitoBean
    private OperationsAuditPublisher auditPublisher;
    @MockitoBean
    private MetricRegistry metrics;

    @BeforeEach
    void resetLegResenderMock() {
        Mockito.reset(legResender);
    }

    private static ResendAcceptance.Accepted accepted(final String... ids) {
        return new ResendAcceptance.Accepted(RUN_ID,
                Arrays.stream(ids).map(UUID::fromString).toList(), OPERATOR, false,
                Optional.empty());
    }

    private ResultActions postJson(final String body)
            throws Exception {
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("passes the list to LegResender exactly as sent: no collapse, no cap, no reorder")
    void passes_the_list_as_sent() throws Exception {
        when(legResender.accept(any())).thenReturn(accepted(FIRST));

        postJson("""
                {"requestIds": ["%s", "%s", "%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s",
                 "allowTerminalSuccess": true, "allowChangedPayload": true}
                """.formatted(SECOND, FIRST, SECOND, OPERATOR, REASON))
                .andExpect(status().isAccepted());

        final ArgumentCaptor<ResendRequest> sent = ArgumentCaptor.forClass(ResendRequest.class);
        verify(legResender).accept(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new ResendRequest(
                List.of(UUID.fromString(SECOND), UUID.fromString(FIRST), UUID.fromString(SECOND)),
                OPERATOR, REASON, true, true));
    }

    @Test
    @DisplayName("both overrides reach LegResender as false when absent from the body")
    void absent_overrides_reach_004_as_false() throws Exception {
        when(legResender.accept(any())).thenReturn(accepted(FIRST));

        postJson(VALID_BODY).andExpect(status().isAccepted());

        final ArgumentCaptor<ResendRequest> sent = ArgumentCaptor.forClass(ResendRequest.class);
        verify(legResender).accept(sent.capture());
        assertThat(sent.getValue().allowTerminalSuccess()).isFalse();
        assertThat(sent.getValue().allowChangedPayload()).isFalse();
    }

    @Test
    @DisplayName("an accepted run answers 202 carrying only its resendRunId, echoing nothing")
    void accepted_run_answers_202_with_only_the_run_id() throws Exception {
        when(legResender.accept(any())).thenReturn(accepted(FIRST));

        postJson(VALID_BODY)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendRunId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(content().string(not(containsString(OPERATOR))))
                .andExpect(content().string(not(containsString(REASON))));
    }

    @Test
    @DisplayName("answers 202 with the run id while the run is still going (FR-005-015)")
    void answers_202_while_the_run_is_still_going() throws Exception {
        final ResendAcceptance.Accepted accepted = accepted(FIRST, SECOND);
        final CountDownLatch runStarted = new CountDownLatch(1);
        final CountDownLatch releaseRun = new CountDownLatch(1);
        when(legResender.accept(any())).thenReturn(accepted);
        when(legResender.run(eq(accepted), any())).thenAnswer(invocation -> {
            runStarted.countDown();
            assertThat(releaseRun.await(10, TimeUnit.SECONDS)).isTrue();
            return null;
        });

        try {
            postJson(VALID_BODY)
                    .andExpect(status().isAccepted())
                    .andExpect(jsonPath("$.resendRunId").value(RUN_ID.toString()));
            assertThat(runStarted.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(releaseRun.getCount()).isEqualTo(1);
        } finally {
            releaseRun.countDown();
        }
        verify(legResender, never()).resend(any());
    }

    @Test
    @DisplayName("hands the accepted run, and only it, to the run step")
    void hands_the_accepted_run_to_the_run_step() throws Exception {
        final ResendAcceptance.Accepted accepted = accepted(FIRST);
        when(legResender.accept(any())).thenReturn(accepted);

        postJson(VALID_BODY).andExpect(status().isAccepted());

        verify(legResender, timeout(10_000)).run(eq(accepted), any());
    }

    @ParameterizedTest(name = "{0} renders {1} {0}")
    @CsvSource({
        "MISSING_ARGUMENT, 400",
        "ARGUMENT_TOO_LONG, 400",
        "UNREADABLE_ARGUMENT, 400",
        "TOO_MANY_REQUEST_IDS, 400",
        "INTAKE_PAUSED, 409",
        "STORE_UNAVAILABLE, 503"
    })
    @DisplayName("every whole-call refusal 004 gives renders its own code and status")
    void whole_call_refusals_render_004s_code(final WholeRunRefusal refusal, final int expectedStatus)
            throws Exception {
        when(legResender.accept(any())).thenReturn(new ResendAcceptance.Refused(refusal));

        postJson(VALID_BODY)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.reason").value(refusal.value()))
                .andExpect(content().string(not(containsString(OPERATOR))))
                .andExpect(content().string(not(containsString(REASON))));

        verify(legResender, never()).run(any(), any());
    }

    @Test
    @DisplayName("too-many-request-ids carries the configured cap applied")
    void too_many_request_ids_carries_the_cap() throws Exception {
        when(legResender.accept(any()))
                .thenReturn(new ResendAcceptance.Refused(WholeRunRefusal.TOO_MANY_REQUEST_IDS));

        postJson(VALID_BODY)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.argumentName").value("requestIds"))
                .andExpect(jsonPath("$.cap").value(100));
    }

    @Test
    @DisplayName("an absent requestIds list is refused missing-argument naming requestIds, nothing run")
    void absent_request_ids_refused_before_004() throws Exception {
        postJson("""
                {"leg": "HMPPS", "operator": "%s", "reason": "%s"}
                """.formatted(OPERATOR, REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("missing-argument"))
                .andExpect(jsonPath("$.argumentName").value("requestIds"));

        verify(legResender, never()).accept(any());
    }

    @Test
    @DisplayName("a field the contract does not name is refused unknown-field, nothing run")
    void unknown_field_refused() throws Exception {
        postJson("""
                {"requestIds": ["%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s", "extra": 1}
                """.formatted(FIRST, OPERATOR, REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("unknown-field"))
                .andExpect(jsonPath("$.argumentName").value("extra"));

        verify(legResender, never()).accept(any());
    }

    @Test
    @DisplayName("the request event precedes the call and the response event follows it, bounded fields only")
    void audit_events_bracket_the_call() throws Exception {
        when(legResender.accept(any())).thenReturn(accepted(FIRST, SECOND));

        postJson(VALID_BODY).andExpect(status().isAccepted());

        final InOrder order = inOrder(auditPublisher, legResender);
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> request = ArgumentCaptor.forClass(Map.class);
        order.verify(auditPublisher).publishRequestEvent(eq(OperationsAction.RESEND_LEG), request.capture());
        assertThat(request.getValue()).containsOnlyKeys("allowTerminalSuccess", "allowChangedPayload")
                .containsEntry("allowTerminalSuccess", false)
                .containsEntry("allowChangedPayload", false);
        assertThat(request.getValue().values()).doesNotContain(OPERATOR, REASON);
        order.verify(legResender).accept(any());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> response = ArgumentCaptor.forClass(Map.class);
        order.verify(auditPublisher).publishResponseEvent(eq(OperationsAction.RESEND_LEG),
                response.capture());
        assertThat(response.getValue())
                .containsEntry("outcome", "accepted")
                .containsEntry("resendRunId", RUN_ID.toString())
                .containsEntry("allowTerminalSuccess", false)
                .containsEntry("allowChangedPayload", false)
                .containsEntry("ids", 2)
                .doesNotContainKey("notRun");
        assertThat(response.getValue().values()).doesNotContain(OPERATOR, REASON);
    }

    @Test
    @DisplayName("LegResender throwing answers 500 unexpected and still publishes the response event")
    void a_throwing_run_still_publishes_its_response_event() throws Exception {
        when(legResender.accept(any())).thenThrow(new IllegalStateException("a defect"));

        postJson(VALID_BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reason").value("unexpected"))
                .andExpect(content().string(not(containsString("a defect"))));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> response = ArgumentCaptor.forClass(Map.class);
        verify(auditPublisher).publishResponseEvent(eq(OperationsAction.RESEND_LEG), response.capture());
        assertThat(response.getValue()).containsEntry("outcome", "unexpected");
    }

    @ParameterizedTest(name = "{0} renders 500 unexpected")
    @CsvSource({"INTAKE_NOT_PAUSED", "BROKER_UNAVAILABLE"})
    @DisplayName("a whole-run code resend-leg never yields answers 500 unexpected with its response event")
    void a_code_resend_never_yields_publishes_unexpected(final WholeRunRefusal refusal) throws Exception {
        when(legResender.accept(any())).thenReturn(new ResendAcceptance.Refused(refusal));

        postJson(VALID_BODY)
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reason").value("unexpected"));

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> response = ArgumentCaptor.forClass(Map.class);
        verify(auditPublisher).publishResponseEvent(eq(OperationsAction.RESEND_LEG), response.capture());
        assertThat(response.getValue()).containsEntry("outcome", "unexpected");
    }

    @Test
    @DisplayName("explicit JSON null overrides reach LegResender as false")
    void null_overrides_reach_004_as_false() throws Exception {
        when(legResender.accept(any())).thenReturn(accepted(FIRST));

        postJson("""
                {"requestIds": ["%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s",
                 "allowTerminalSuccess": null, "allowChangedPayload": null}
                """.formatted(FIRST, OPERATOR, REASON))
                .andExpect(status().isAccepted());

        final ArgumentCaptor<ResendRequest> sent = ArgumentCaptor.forClass(ResendRequest.class);
        verify(legResender).accept(sent.capture());
        assertThat(sent.getValue().allowTerminalSuccess()).isFalse();
        assertThat(sent.getValue().allowChangedPayload()).isFalse();
    }

    @Test
    @DisplayName("of several unknown fields, one of them is named (Jackson keeps no body order for them)")
    void several_unknown_fields_name_one_of_them() throws Exception {
        postJson("""
                {"requestIds": ["%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s",
                 "zeta": 1, "alpha": 2}
                """.formatted(FIRST, OPERATOR, REASON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("unknown-field"))
                .andExpect(jsonPath("$.argumentName", oneOf("zeta", "alpha")));

        verify(legResender, never()).accept(any());
    }

    @Test
    @DisplayName("an unknown field whose name is not a plain bounded identifier is refused without naming it")
    void unsafe_unknown_field_name_is_never_echoed() throws Exception {
        final String unsafeName = "<script>" + "x".repeat(100);
        postJson("""
                {"requestIds": ["%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s", "%s": 1}
                """.formatted(FIRST, OPERATOR, REASON, unsafeName))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("unknown-field"))
                .andExpect(jsonPath("$.argumentName").doesNotExist())
                .andExpect(content().string(not(containsString("script"))));

        verify(legResender, never()).accept(any());
    }

    @Test
    @DisplayName("a whole-call refusal still publishes its response event naming the refusal")
    void refusal_publishes_its_response_event() throws Exception {
        when(legResender.accept(any()))
                .thenReturn(new ResendAcceptance.Refused(WholeRunRefusal.INTAKE_PAUSED));

        postJson(VALID_BODY).andExpect(status().isConflict());

        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> response = ArgumentCaptor.forClass(Map.class);
        verify(auditPublisher).publishResponseEvent(eq(OperationsAction.RESEND_LEG), response.capture());
        assertThat(response.getValue()).containsEntry("outcome", "intake-paused");
    }

    /** Registers a mock {@link LegResender} bean so the conditional controller bean sees it. */
    static class LegResenderBean {

        /* default */ @Bean
        LegResender legResender() {
            return Mockito.mock(LegResender.class);
        }
    }
}
