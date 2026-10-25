package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * Pins FR-005-006 to FR-005-009: every {@link ReplayOutcome} renders the status/body the contract
 * names, replay is available while intake is paused (no refusal of its own), and no caller value is
 * echoed. {@link ReplayAction} is mocked; {@link OperationsRequestMapper} and
 * {@link OperationsResponseMapper} come from the production configuration.
 *
 * <p>{@link ReplayAction} is provided as a plain Mockito mock registered through {@link
 * ReplayActionBean}, not {@code @MockitoBean}: {@link ReplayController} carries
 * {@code @ConditionalOnBean(ReplayAction.class)} (its dependency chain — {@code
 * ProcessedRequestRepository}, a production {@code ReplayEnqueuer} — does not exist unconditionally
 * yet), and that condition is evaluated before {@code @MockitoBean}'s override registers, so the
 * controller would never be found otherwise.
 */
@WebMvcTest(controllers = ReplayController.class, properties = "distribution.consumer.enabled=false")
@ActiveProfiles("test")
@Import(ReplayControllerTest.ReplayActionBean.class)
@DisplayName("the replay controller")
class ReplayControllerTest {

    private static final String REQUEST_ID = "3fa85f64-5717-4562-b3fc-2c963f66afa6";
    private static final String REPLAY_PATH = "/operations/requests/" + REQUEST_ID + "/replay";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReplayAction replayAction;
    @MockitoBean
    private OperationsAuditPublisher auditPublisher;
    @MockitoBean
    private MetricRegistry metrics;

    @org.junit.jupiter.api.BeforeEach
    void resetReplayActionMock() {
        Mockito.reset(replayAction);
    }

    @Test
    @DisplayName("Enqueued renders 200 with the outcome, requestId and message identity")
    void enqueued_renders_200() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID)))
                .thenReturn(new ReplayOutcome.Enqueued("RESULTS:abc-123"));

        mockMvc.perform(post(REPLAY_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID))
                .andExpect(jsonPath("$.outcome").value("enqueued"))
                .andExpect(jsonPath("$.messageId").value("RESULTS:abc-123"));
    }

    @Test
    @DisplayName("NoOp renders 200 with the outcome no-op")
    void no_op_renders_200() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID))).thenReturn(new ReplayOutcome.NoOp());

        mockMvc.perform(post(REPLAY_PATH))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.outcome").value("no-op"))
                .andExpect(jsonPath("$.messageId").doesNotExist());
    }

    @ParameterizedTest
    @EnumSource(value = ResendRefusal.class, names = "REQUEST_CLAIMED", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("every refusal replay can give renders its bounded status and reason")
    void refusals_render_their_status_and_reason(final ResendRefusal reason) throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID)))
                .thenReturn(new ReplayOutcome.Refused(reason));

        final int expectedStatus = switch (reason) {
            case UNKNOWN_REQUEST -> 404;
            case NOT_ELIGIBLE, RESEND_IN_PROGRESS -> 409;
            case REQUEST_CLAIMED -> throw new IllegalStateException("excluded above");
        };

        mockMvc.perform(post(REPLAY_PATH))
                .andExpect(status().is(expectedStatus))
                .andExpect(jsonPath("$.reason").value(reason.wireValue()));
    }

    @Test
    @DisplayName("Refused(REQUEST_CLAIMED), which replay never yields, renders 500 unexpected")
    void request_claimed_renders_500_unexpected() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID)))
                .thenReturn(new ReplayOutcome.Refused(ResendRefusal.REQUEST_CLAIMED));

        mockMvc.perform(post(REPLAY_PATH))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reason").value("unexpected"));
    }

    @Test
    @DisplayName("StoreUnavailable renders 503 store-unavailable")
    void store_unavailable_renders_503() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID)))
                .thenReturn(new ReplayOutcome.StoreUnavailable());

        mockMvc.perform(post(REPLAY_PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("store-unavailable"));
    }

    @Test
    @DisplayName("BrokerUnavailable renders 503 broker-unavailable")
    void broker_unavailable_renders_503() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID)))
                .thenReturn(new ReplayOutcome.BrokerUnavailable());

        mockMvc.perform(post(REPLAY_PATH))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reason").value("broker-unavailable"));
    }

    @Test
    @DisplayName("no body or {} is accepted; any field is refused unknown-field")
    void no_body_or_empty_object_accepted_any_field_refused() throws Exception {
        when(replayAction.replay(any())).thenReturn(new ReplayOutcome.NoOp());

        mockMvc.perform(post(REPLAY_PATH)).andExpect(status().isOk());
        mockMvc.perform(post(REPLAY_PATH).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        mockMvc.perform(post(REPLAY_PATH).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operator\": \"someone\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("unknown-field"))
                .andExpect(jsonPath("$.argumentName").value("operator"));
    }

    @Test
    @DisplayName("an unreadable requestId is refused, never echoed")
    void unreadable_requestId_refused_never_echoed() throws Exception {
        mockMvc.perform(post("/operations/requests/not-a-uuid/replay"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                .andExpect(jsonPath("$.argumentName").value("requestId"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("not-a-uuid"))));
    }

    /** Registers a mock {@link ReplayAction} bean so the conditional controller bean sees it. */
    static class ReplayActionBean {

        /* default */ @Bean
        ReplayAction replayAction() {
            return Mockito.mock(ReplayAction.class);
        }
    }
}
