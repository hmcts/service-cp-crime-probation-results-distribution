package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
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
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendRunResult;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.OperationsAction;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Pins FR-005-010 to FR-005-016 and FR-005-025/FR-005-026: the list reaches {@link LegResender}
 * exactly as sent with every other input unchanged, an accepted run answers {@code 202} carrying
 * only its {@code resendRunId} (even when every ID is refused), every whole-call refusal renders
 * 004's own code, both audit events bracket the call carrying bounded fields only, and nothing the
 * caller typed is echoed. {@link LegResender} is mocked; {@link OperationsRequestMapper} and
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

    private static ResendRunResult acceptedRun(final List<ResendRunResult.Entry> entries) {
        return new ResendRunResult(Optional.empty(), Optional.of(RUN_ID), false, false, entries, false);
    }

    private ResultActions postJson(final String body)
            throws Exception {
        return mockMvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    @Test
    @DisplayName("passes the list to LegResender exactly as sent: no collapse, no cap, no reorder")
    void passes_the_list_as_sent() throws Exception {
        when(legResender.resend(any())).thenReturn(acceptedRun(List.of()));

        postJson("""
                {"requestIds": ["%s", "%s", "%s"], "leg": "HMPPS", "operator": "%s", "reason": "%s",
                 "allowTerminalSuccess": true, "allowChangedPayload": true}
                """.formatted(SECOND, FIRST, SECOND, OPERATOR, REASON))
                .andExpect(status().isAccepted());

        final ArgumentCaptor<ResendRequest> sent = ArgumentCaptor.forClass(ResendRequest.class);
        verify(legResender).resend(sent.capture());
        assertThat(sent.getValue()).isEqualTo(new ResendRequest(
                List.of(UUID.fromString(SECOND), UUID.fromString(FIRST), UUID.fromString(SECOND)),
                OPERATOR, REASON, true, true));
    }

    @Test
    @DisplayName("both overrides reach LegResender as false when absent from the body")
    void absent_overrides_reach_004_as_false() throws Exception {
        when(legResender.resend(any())).thenReturn(acceptedRun(List.of()));

        postJson(VALID_BODY).andExpect(status().isAccepted());

        final ArgumentCaptor<ResendRequest> sent = ArgumentCaptor.forClass(ResendRequest.class);
        verify(legResender).resend(sent.capture());
        assertThat(sent.getValue().allowTerminalSuccess()).isFalse();
        assertThat(sent.getValue().allowChangedPayload()).isFalse();
    }

    @Test
    @DisplayName("an accepted run answers 202 carrying only its resendRunId, echoing nothing")
    void accepted_run_answers_202_with_only_the_run_id() throws Exception {
        when(legResender.resend(any())).thenReturn(acceptedRun(List.of(
                new ResendRunResult.Entry(UUID.fromString(FIRST),
                        new ResendEntryOutcome.Ran(LegStatus.SENT, Optional.empty())))));

        postJson(VALID_BODY)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendRunId").value(RUN_ID.toString()))
                .andExpect(jsonPath("$.*", hasSize(1)))
                .andExpect(content().string(not(containsString(OPERATOR))))
                .andExpect(content().string(not(containsString(REASON))));
    }

    @Test
    @DisplayName("a run whose every ID was refused is still 202: per-ID refusals live in the report")
    void every_id_refused_is_still_202() throws Exception {
        when(legResender.resend(any())).thenReturn(acceptedRun(List.of(
                new ResendRunResult.Entry(UUID.fromString(FIRST),
                        new ResendEntryOutcome.Refused(ResendRefusal.NOT_ELIGIBLE)))));

        postJson(VALID_BODY)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.resendRunId").value(RUN_ID.toString()));
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
        when(legResender.resend(any())).thenReturn(ResendRunResult.refusedWhole(refusal));

        postJson(VALID_BODY)
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.reason").value(refusal.value()))
                .andExpect(content().string(not(containsString(OPERATOR))))
                .andExpect(content().string(not(containsString(REASON))));
    }

    @Test
    @DisplayName("too-many-request-ids carries the configured cap applied")
    void too_many_request_ids_carries_the_cap() throws Exception {
        when(legResender.resend(any()))
                .thenReturn(ResendRunResult.refusedWhole(WholeRunRefusal.TOO_MANY_REQUEST_IDS));

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

        verify(legResender, never()).resend(any());
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

        verify(legResender, never()).resend(any());
    }

    @Test
    @DisplayName("the request event precedes the call and the response event follows it, bounded fields only")
    void audit_events_bracket_the_call() throws Exception {
        when(legResender.resend(any())).thenReturn(acceptedRun(List.of()));

        postJson(VALID_BODY).andExpect(status().isAccepted());

        final InOrder order = inOrder(auditPublisher, legResender);
        order.verify(auditPublisher).publishRequestEvent(eq(OperationsAction.RESEND_LEG), anyMap());
        order.verify(legResender).resend(any());
        @SuppressWarnings("unchecked")
        final ArgumentCaptor<Map<String, Object>> response = ArgumentCaptor.forClass(Map.class);
        order.verify(auditPublisher).publishResponseEvent(eq(OperationsAction.RESEND_LEG),
                response.capture());
        assertThat(response.getValue())
                .containsEntry("outcome", "accepted")
                .containsEntry("resendRunId", RUN_ID.toString())
                .containsEntry("allowTerminalSuccess", false)
                .containsEntry("allowChangedPayload", false);
        assertThat(response.getValue().values()).doesNotContain(OPERATOR, REASON);
    }

    @Test
    @DisplayName("a whole-call refusal still publishes its response event naming the refusal")
    void refusal_publishes_its_response_event() throws Exception {
        when(legResender.resend(any()))
                .thenReturn(ResendRunResult.refusedWhole(WholeRunRefusal.INTAKE_PAUSED));

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
