package uk.gov.hmcts.cp.probationresultsdistribution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.jms.core.JmsTemplate;
import uk.gov.hmcts.cp.filter.audit.AuditFilter;
import uk.gov.hmcts.cp.filter.audit.service.AuditPayloadGenerationService;
import uk.gov.hmcts.cp.filter.audit.service.AuditService;
import uk.gov.hmcts.cp.filter.audit.service.PathParameterService;
import uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsAuditPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsRequestMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.api.OperationsResponseMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.api.ReplayController;
import uk.gov.hmcts.cp.probationresultsdistribution.api.ResendLegController;
import uk.gov.hmcts.cp.probationresultsdistribution.api.ResendLegExecutor;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseGate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayAction;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * Composes the operations API: its controllers, the resend-leg capability and the one thread its
 * accepted runs execute on (FR-005-015), and its audit publishing (FR-005-025 to FR-005-027). {@code JmsTemplate} is
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

    /**
     * Slice 004's resend-leg capability as a bean of the serving context, built from the
     * consumer's own {@link DistributionRunner} and processed-log store ({@code
     * ServiceBusConsumerConfig}) and its reconciled intake-pause gate ({@code
     * inbound.ConsumerLifecycleController}), so it exists exactly when intake is wired. The packaged
     * command line ({@code OperatorCommandLine}) still builds its own, unchanged.
     *
     * <p>Declared here, ahead of the two {@code @ConditionalOnBean(LegResender.class)} methods
     * below, rather than in {@code ServiceBusConsumerConfig}: a {@code @Bean}-method condition sees
     * only the definitions registered before it, and within one class that is declaration order,
     * whereas the order in which the component scan parses two configuration classes is not
     * something to rely on.
     *
     * @param pauseGate  the reconciled operator-pause read
     * @param store      the processed-log store
     * @param runner     the consumer's pipeline
     * @param metrics    the telemetry port
     * @param properties the service's typed configuration, for
     *                   {@code distribution.resend.max-ids-per-run}
     * @return the capability
     */
    /* default */
    @Bean
    @ConditionalOnProperty(name = "distribution.consumer.enabled", havingValue = "true")
    LegResender legResender(final IntakePauseGate pauseGate, final ProcessedRequestRepository store,
                            final DistributionRunner runner, final MetricRegistry metrics,
                            final DistributionProperties properties) {
        return new LegResender(pauseGate, store, runner, metrics, properties.resend().maxIdsPerRun());
    }

    /**
     * The one thread accepted resend-leg runs execute on, one at a time (FR-005-015); closed with
     * the context, when the running run claims nothing further and queued runs never start.
     *
     * @param legResender slice 004's resend-leg capability
     * @return the executor
     */
    /* default */
    @Bean(destroyMethod = "close")
    @ConditionalOnBean(LegResender.class)
    ResendLegExecutor resendLegExecutor(final LegResender legResender) {
        return new ResendLegExecutor(legResender);
    }

    /**
     * Registers {@link ResendLegController} once a {@link LegResender} bean exists, for the same
     * reason and in the same way as {@link #replayController}: the serving context has one exactly
     * when intake is wired ({@link #legResender}); without one the controller is absent from the
     * context rather than failing it.
     *
     * @param legResender    slice 004's resend-leg capability
     * @param executor       runs each accepted run
     * @param requestMapper  the request mapper
     * @param responseMapper the response mapper
     * @param auditPublisher the audit publisher
     * @param properties     the service's typed configuration, for
     *                       {@code distribution.resend.max-ids-per-run}
     * @return the controller
     */
    /* default */
    @Bean
    @ConditionalOnBean(LegResender.class)
    ResendLegController resendLegController(final LegResender legResender,
                                            final ResendLegExecutor executor,
                                            final OperationsRequestMapper requestMapper,
                                            final OperationsResponseMapper responseMapper,
                                            final OperationsAuditPublisher auditPublisher,
                                            final DistributionProperties properties) {
        return new ResendLegController(legResender, executor, requestMapper, responseMapper,
                auditPublisher, properties.resend().maxIdsPerRun());
    }

    /**
     * Supplies the one bean {@code cp-audit-filter-springboot}'s own {@code auditFilter} {@code @Bean}
     * method is {@code @ConditionalOnMissingBean(AuditFilter.class)} against (confirmed by reading
     * the library's bytecode), so the library never builds its own copy. Needed because that filter
     * audits every request unconditionally — its own {@code shouldNotFilter} excludes only
     * {@code /health} and {@code /actuator}, and it never consults the OpenAPI document before
     * publishing, only to look up path-parameter names — by calling
     * {@code AuditService.postMessageToArtemis} on the SAME topic {@link OperationsAuditPublisher}
     * publishes to by name (see that class's own Javadoc). Left running alongside
     * {@link OperationsAuditPublisher}, every operations-API call would produce four audit events,
     * not the two FR-005-025 requires. This bean only satisfies that condition; it is never
     * registered as an active servlet filter (see
     * {@link #estateAuditFilterRegistration(AuditFilter)}).
     *
     * @param auditService                  the library's own publish mechanism (unused directly;
     *                                       present only to construct the instance this bean must be)
     * @param auditPayloadGenerationService the library's own payload builder
     * @param pathParameterService          the library's own path-parameter lookup
     * @return an {@link AuditFilter} instance that this configuration never wires into the chain
     */
    /* default */
    @Bean
    @ConditionalOnBean({AuditService.class, AuditPayloadGenerationService.class, PathParameterService.class})
    AuditFilter estateAuditFilter(final AuditService auditService,
                                  final AuditPayloadGenerationService auditPayloadGenerationService,
                                  final PathParameterService pathParameterService) {
        return new AuditFilter(auditService, auditPayloadGenerationService, pathParameterService);
    }

    /**
     * Wraps {@link #estateAuditFilter(AuditService, AuditPayloadGenerationService,
     * PathParameterService)} in a disabled registration, so Spring Boot's own auto-registration of
     * every raw {@code Filter} bean leaves it out of the chain entirely — the bean must still exist
     * (above) to pre-empt the library's own competing one, but neither copy ever runs.
     *
     * @param estateAuditFilter the bean that must exist but never run
     * @return its disabled registration
     */
    /* default */
    @Bean
    @ConditionalOnBean(AuditFilter.class)
    FilterRegistrationBean<AuditFilter> estateAuditFilterRegistration(final AuditFilter estateAuditFilter) {
        final FilterRegistrationBean<AuditFilter> registration = new FilterRegistrationBean<>(estateAuditFilter);
        registration.setEnabled(false);
        return registration;
    }
}
