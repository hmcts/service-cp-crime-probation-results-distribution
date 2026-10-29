package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;

/**
 * Pins User Story 5 (FR-005-029 to FR-005-032) across what is servable today: the replay endpoint,
 * plus the shared {@link OperationsProblemDetailHandler}/{@link OperationsRequestMapper}/
 * {@link OperationsAuditPublisher} every endpoint sits behind. Every refusal case the problem-detail
 * shape itself can give is already pinned by {@code OperationsProblemDetailHandlerTest} (T005); this
 * sweep adds the checks that need a live request: the response's content type is RFC 9457
 * ({@code application/problem+json}), an unreadable identifier is never echoed regardless of what it
 * contains, and a successful call's {@code requestId} is always the service's own canonical parse,
 * never the raw characters sent.
 *
 * <p>The resend-leg half of this story is {@code [BLOCKED-ON-004]}: there is no
 * {@code ResendLegController} to sweep yet. This test covers only the endpoint that exists.
 */
@WebMvcTest(controllers = ReplayController.class, properties = "distribution.consumer.enabled=false")
@ActiveProfiles("test")
@Import(ReplayControllerTest.ReplayActionBean.class)
@DisplayName("the replay endpoint's refusals (User Story 5)")
class OperationsRefusalSweepTest {

    private static final String REQUEST_ID_UPPER = "3FA85F64-5717-4562-B3FC-2C963F66AFA6";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ReplayAction replayAction;
    @MockitoBean
    private OperationsAuditPublisher auditPublisher;
    @MockitoBean
    private MetricRegistry metrics;

    @BeforeEach
    void resetReplayActionMock() {
        Mockito.reset(replayAction);
    }

    @Test
    @DisplayName("a successful call's requestId is always the canonical parse, never the characters sent")
    void successful_calls_requestId_is_the_canonical_parse() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID_UPPER))).thenReturn(new ReplayOutcome.NoOp());

        mockMvc.perform(post("/operations/requests/" + REQUEST_ID_UPPER + "/replay"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requestId").value(REQUEST_ID_UPPER.toLowerCase(java.util.Locale.ROOT)))
                .andExpect(content().string(not(containsString(REQUEST_ID_UPPER))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"not-a-uuid", "'; DROP TABLE processed_request; --",
        "<script>alert(1)</script>", "../../etc/passwd"})
    @DisplayName("an unreadable requestId is refused RFC 9457 unreadable-argument, never echoed")
    void unreadable_request_id_never_echoed(final String badId) throws Exception {
        final String encoded = URLEncoder.encode(badId, StandardCharsets.UTF_8);

        mockMvc.perform(post("/operations/requests/" + encoded + "/replay"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("unreadable-argument"))
                .andExpect(jsonPath("$.argumentName").value("requestId"))
                .andExpect(content().string(not(containsString(badId))));
    }

    @Test
    @DisplayName("every refusal answers with the RFC 9457 problem+json content type")
    void refusals_answer_as_problem_json() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID_UPPER)))
                .thenReturn(new ReplayOutcome.StoreUnavailable());

        mockMvc.perform(post("/operations/requests/" + REQUEST_ID_UPPER + "/replay"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(content().contentType(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.reason").value("store-unavailable"));
    }

    @Test
    @DisplayName("an unexpected failure never leaks its own message or a stack trace")
    void unexpected_failure_never_leaks_its_message() throws Exception {
        when(replayAction.replay(UUID.fromString(REQUEST_ID_UPPER)))
                .thenThrow(new IllegalStateException("do not leak this message either"));

        mockMvc.perform(post("/operations/requests/" + REQUEST_ID_UPPER + "/replay"))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.reason").value("unexpected"))
                .andExpect(content().string(not(containsString("do not leak this message either"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("at uk.gov.hmcts"))));
    }
}
