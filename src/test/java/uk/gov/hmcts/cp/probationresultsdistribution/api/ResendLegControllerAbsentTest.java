package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;

/**
 * Pins the {@code @ConditionalOnBean(LegResender.class)} registration in {@code
 * config.OperationsConfiguration}: with no {@link LegResender} bean, as in a context with intake
 * off ({@code distribution.consumer.enabled=false}), the context still starts, holds no {@link
 * ResendLegController} and has no resend-leg route. Its presence once a {@link LegResender} bean
 * exists is pinned by {@code ResendLegControllerTest}, and the serving context's own {@link
 * LegResender} bean with intake on by {@code ResendLegExecutionIT}.
 */
@WebMvcTest(controllers = ResendLegController.class, properties = "distribution.consumer.enabled=false")
@ActiveProfiles("test")
@DisplayName("the resend-leg controller without a LegResender")
class ResendLegControllerAbsentTest {

    @Autowired
    private ApplicationContext context;
    @Autowired
    private MockMvc mockMvc;
    @MockitoBean
    private OperationsAuditPublisher auditPublisher;
    @MockitoBean
    private MetricRegistry metrics;

    @Test
    @DisplayName("the context starts with no resend-leg controller and the route is not served")
    void context_starts_without_the_controller() throws Exception {
        assertThat(context.getBeansOfType(LegResender.class)).isEmpty();
        assertThat(context.getBeansOfType(ResendLegController.class)).isEmpty();

        mockMvc.perform(post("/operations/resend-leg").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isNotFound());
    }
}
