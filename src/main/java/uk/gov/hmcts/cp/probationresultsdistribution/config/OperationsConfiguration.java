package uk.gov.hmcts.cp.probationresultsdistribution.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
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
import uk.gov.hmcts.cp.probationresultsdistribution.application.ReplayEnqueuer;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * Composes the operations API: its controllers, the resend-leg capability and the one thread its
 * accepted runs execute on (FR-005-015), and its audit publishing (FR-005-025 to FR-005-027).
 * Whether a call is audited follows the audit transport's switch, {@code cp.audit.enabled}: with it
 * on, {@link OperationsAuditPublisher} publishes through {@code cp-audit-filter-springboot}'s own
 * {@code auditJmsTemplate}; with it off (the scaffold's default), every call is served unaudited
 * (FR-005-028), whatever other {@link JmsTemplate} Spring Boot's JMS auto-configuration registers.
 * Left unset, it is read as the library reads it, on (see {@link #operationsAuditPublisher}).
 *
 * <p>The {@link ObjectMapper} is this bean's own, not an autowired one: Spring Boot 4's
 * {@code JacksonAutoConfiguration} registers a Jackson 3 {@code tools.jackson.databind.json.JsonMapper}
 * by default, not a Jackson 2 {@code com.fasterxml.jackson.databind.ObjectMapper} bean (confirmed by
 * the context's own condition evaluation report), and nothing else in this service exposes one —
 * {@code cp-audit-filter-springboot} constructs its own the same way.
 */
@Configuration
public class OperationsConfiguration {

    /** The bean name {@code cp-audit-filter-springboot} gives its audit transport's template. */
    private static final String AUDIT_JMS_TEMPLATE = "auditJmsTemplate";
    /** The value every switch this configuration is conditional on takes when it is on. */
    private static final String SWITCH_ON = "true";

    /**
     * The operations API's audit publisher. Whether a call is audited is decided by the audit
     * transport's own switch, {@code cp.audit.enabled}, never by whether some {@link JmsTemplate}
     * exists: with the transport off, Spring Boot's JMS auto-configuration registers a default
     * template of its own, which is not the audit transport and whose publish fails against no
     * broker (FR-005-028; 005 T051). The template is always the library's own, taken by its bean
     * name, {@code auditJmsTemplate}.
     *
     * <ul>
     *   <li>{@code false}: every call is served unaudited.</li>
     *   <li>{@code true}: audited; a missing {@code auditJmsTemplate} fails the context.</li>
     *   <li>unset: read as the library reads it, on ({@code matchIfMissing = true} on its
     *       auto-configuration and on {@link #estateAuditFilter}), so audited through the library's
     *       template whenever its auto-configuration built one; a context with no audit
     *       auto-configuration at all (the {@code ApplicationContextRunner} wiring suites) has none
     *       and is served unaudited. Reading unset as off would leave the library's transport built
     *       and its filter pre-empted while every call went unaudited. {@code application.yaml}
     *       always sets the switch, so a deployed pod never meets this case.</li>
     * </ul>
     *
     * @param auditTransportEnabled {@code cp.audit.enabled}, {@code null} when unset
     * @param auditJmsTemplate      the library's {@code auditJmsTemplate}, when its auto-configuration built one
     * @param metrics               the metric registry
     * @return the publisher, a no-op when the transport is off
     */
    /* default */ @Bean
    OperationsAuditPublisher operationsAuditPublisher(
            @Value("${cp.audit.enabled:#{null}}") final Boolean auditTransportEnabled,
            @Qualifier(AUDIT_JMS_TEMPLATE) final ObjectProvider<JmsTemplate> auditJmsTemplate,
            final MetricRegistry metrics) {
        final Optional<JmsTemplate> transport;
        if (auditTransportEnabled == null) {
            transport = Optional.ofNullable(auditJmsTemplate.getIfAvailable());
        } else if (auditTransportEnabled) {
            transport = Optional.of(auditJmsTemplate.getObject());
        } else {
            transport = Optional.empty();
        }
        return new OperationsAuditPublisher(transport, new ObjectMapper(), metrics);
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
     * Slice 004's replay action as a bean of the serving context (T078; design §4.8 action 2; H9),
     * built from the consumer's processed-log store and the production replay enqueuer over the
     * serving context's {@code probation.requests} sender ({@code ServiceBusConsumerConfig}), so it
     * exists exactly when intake is wired. Declared ahead of {@link #replayController} for the
     * reason {@link #legResender} gives.
     *
     * @param store    the processed-log store
     * @param enqueuer the production replay enqueuer
     * @return the action
     */
    /* default */
    @Bean
    @ConditionalOnProperty(name = "distribution.consumer.enabled", havingValue = SWITCH_ON)
    ReplayAction replayAction(final ProcessedRequestRepository store, final ReplayEnqueuer enqueuer) {
        return new ReplayAction(store, enqueuer);
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
    @ConditionalOnProperty(name = "distribution.consumer.enabled", havingValue = SWITCH_ON)
    LegResender legResender(final IntakePauseGate pauseGate, final ProcessedRequestRepository store,
                            final DistributionRunner runner, final MetricRegistry metrics,
                            final DistributionProperties properties) {
        return new LegResender(pauseGate, store, runner, metrics, properties.resend().maxIdsPerRun());
    }

    /**
     * The one thread accepted resend-leg runs execute on, one at a time (FR-005-015); stopped with
     * the context, when the running run claims nothing further and queued runs never start.
     *
     * <p>Shares {@code inFlightRunCounter} with {@code ServiceBusConsumerConfig}'s consumer wiring
     * (H40; FR-004-060), the same counter {@code ConsumerLifecycleController#stop()} waits on for
     * an in-flight queue delivery, whenever that bean exists. A deployed pod always has one, since
     * {@code distribution.consumer.enabled=true} wherever a {@link LegResender} bean is the serving
     * context's own ({@link #legResender}) — but a slice test can supply a {@link LegResender} of
     * its own with the consumer switched off (no {@code ServiceBusConsumerConfig} in that context
     * at all), so the counter is read through an {@link ObjectProvider} with its own fallback
     * rather than required, and such a test's resend-leg run is simply never drained by a
     * {@code ConsumerLifecycleController} that does not exist there either.
     *
     * <p><strong>No {@code destroyMethod}</strong>: {@link ResendLegExecutor} is a
     * {@code SmartLifecycle} at a phase above {@code ConsumerLifecycleController}'s default, so
     * Spring stops it during the lifecycle-stop phase of context close, before that controller's
     * own {@code stop()} blocks on the shared counter — not during the later bean-destruction
     * phase a {@code destroyMethod} would run in, which is too late for the stop to actually drain
     * anything (PR #29 review: the ordering bug a {@code destroyMethod}-only shutdown had).
     *
     * @param legResender        slice 004's resend-leg capability
     * @param inFlightRunCounter the consumer's shared in-flight-run counter, when one exists
     * @return the executor
     */
    /* default */
    @Bean
    @ConditionalOnBean(LegResender.class)
    ResendLegExecutor resendLegExecutor(final LegResender legResender,
                                       final ObjectProvider<AtomicInteger> inFlightRunCounter) {
        return new ResendLegExecutor(legResender,
                inFlightRunCounter.getIfAvailable(AtomicInteger::new));
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
     * <p>Gated on the same two switches the library's own {@code auditFilter} method is: its
     * auto-configuration class on {@code cp.audit.enabled} (on when unset) and the method on {@code
     * audit.http.enabled=true}. Never on {@code @ConditionalOnBean} of the library's services: a
     * user configuration's bean conditions are evaluated before any auto-configuration has
     * registered its beans, so such a condition never matched, this bean was never defined, and the
     * library built and registered its own filter, which published two more events per call, the
     * resend-leg one carrying the request body with the caller's operator and reason (005 T052).
     *
     * @param auditService                  the library's own publish mechanism (unused directly;
     *                                       present only to construct the instance this bean must be)
     * @param auditPayloadGenerationService the library's own payload builder
     * @param pathParameterService          the library's own path-parameter lookup
     * @return an {@link AuditFilter} instance that this configuration never wires into the chain
     */
    /* default */
    @Bean
    @ConditionalOnProperty(name = "cp.audit.enabled", havingValue = SWITCH_ON, matchIfMissing = true)
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = SWITCH_ON)
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
    @ConditionalOnProperty(name = "cp.audit.enabled", havingValue = SWITCH_ON, matchIfMissing = true)
    @ConditionalOnProperty(name = "audit.http.enabled", havingValue = SWITCH_ON)
    FilterRegistrationBean<AuditFilter> estateAuditFilterRegistration(final AuditFilter estateAuditFilter) {
        final FilterRegistrationBean<AuditFilter> registration = new FilterRegistrationBean<>(estateAuditFilter);
        registration.setEnabled(false);
        return registration;
    }
}
