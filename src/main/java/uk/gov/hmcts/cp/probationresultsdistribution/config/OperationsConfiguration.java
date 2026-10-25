package uk.gov.hmcts.cp.probationresultsdistribution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jms.core.JmsTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsAuditPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsRequestMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsResponseMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.api.ReplayController;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;

/**
 * Composes the operations API's audit publishing (FR-005-025 to FR-005-027). {@code JmsTemplate} is
 * {@link Optional} because {@code cp-audit-filter-springboot}'s own auto-configuration only
 * registers it when the audit transport is switched on ({@code cp.audit.enabled=true}); when it is
 * off (the scaffold's default), {@link OperationsAuditPublisher} treats every call as unaudited
 * rather than failing the context refresh (FR-005-028).
 *
 * <p>The {@link ObjectMapper} is this bean's own, not an autowired one: Spring Boot 4's
 * {@code JacksonAutoConfiguration} registers a Jackson 3 {@code tools.jackson.databind.json.JsonMapper}
 * by default, not a Jackson 2 {@code com.fasterxml.jackson.databind.ObjectMapper} bean (confirmed by
 * the context's own condition evaluation report), and nothing else in this service exposes one —
 * {@code cp-audit-filter-springboot} constructs its own the same way.
 */
@Configuration
public class OperationsConfiguration {

    /* default */ @Bean
    OperationsAuditPublisher operationsAuditPublisher(final Optional<JmsTemplate> auditJmsTemplate,
                                                      final MetricRegistry metrics) {
        return new OperationsAuditPublisher(auditJmsTemplate, new ObjectMapper(), metrics);
    }

    /**
     * Registers {@link OperationsActionResolutionFilter} ahead of {@code cp-auth-rules-filter}'s own
     * filter (default order {@code Integer.MIN_VALUE + 30} when {@code authz.http.filter-order} is
     * unset, read from that library's bytecode), so the action it fixes is what the Drools rules see
     * (FR-005-022; research.md Decision 2). Scoped to {@code /operations/*}: the only routes this
     * slice owns.
     *
     * @param filter the filter instance
     * @return its registration, ordered ahead of the authorisation filter
     */
    /* default */ @Bean
    FilterRegistrationBean<OperationsActionResolutionFilter> operationsActionResolutionFilterRegistration(
            final OperationsActionResolutionFilter filter) {
        final FilterRegistrationBean<OperationsActionResolutionFilter> registration =
                new FilterRegistrationBean<>(filter);
        registration.addUrlPatterns("/operations/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }

    /* default */ @Bean
    OperationsActionResolutionFilter operationsActionResolutionFilter() {
        return new OperationsActionResolutionFilter();
    }

    /* default */ @Bean
    OperationsRequestMapper operationsRequestMapper() {
        return new OperationsRequestMapper();
    }

    /* default */ @Bean
    OperationsResponseMapper operationsResponseMapper() {
        return new OperationsResponseMapper();
    }

    /**
     * Registers {@link ReplayController} once a {@link ReplayAction} bean exists. See {@link
     * ReplayController}'s own Javadoc for why this is a {@code @Bean}-method condition rather than
     * a class-level one on the (scan-excluded) controller itself.
     *
     * @param replayAction   slice 004's replay action, once it is wired
     * @param requestMapper  the request mapper
     * @param responseMapper the response mapper
     * @param auditPublisher the audit publisher
     * @return the controller
     */
    /* default */
    @Bean
    @ConditionalOnBean(ReplayAction.class)
    ReplayController replayController(final ReplayAction replayAction,
                                      final OperationsRequestMapper requestMapper,
                                      final OperationsResponseMapper responseMapper,
                                      final OperationsAuditPublisher auditPublisher) {
        return new ReplayController(replayAction, requestMapper, responseMapper, auditPublisher);
    }
}
