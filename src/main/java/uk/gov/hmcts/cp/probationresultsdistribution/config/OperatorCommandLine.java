package uk.gov.hmcts.cp.probationresultsdistribution.config;

import io.lettuce.core.RedisClient;
import io.lettuce.core.RedisURI;
import io.lettuce.core.api.StatefulRedisConnection;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.info.BuildProperties;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway.HmppsPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ChainedHearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ResultsQueryExternalHearingClient;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DistributionRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseGate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.PurgeQueueAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.QueuePurger;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RollbackCloseInFlightAction;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.PauseIntakeRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.PurgeQueueRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.ResendLegRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.ResumeIntakeRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.RollbackCloseInFlightRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.RunnerDispatcher;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcIntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcRollbackFencingRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;

/**
 * Composes one operator command without scanning the server, consumer or scheduled jobs. The
 * no-argument intake commands and {@code resend-leg} share this root; {@code resend-leg}'s pipeline
 * (the payload cache, the results-query and users-groups clients, the gateway publisher) is built
 * lazily, only when that command is dispatched.
 */
public final class OperatorCommandLine {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorCommandLine.class);
    private static final Set<String> ACTIONS = Set.of("pause-intake", "resume-intake", "purge-queue",
            "rollback-close-in-flight");
    private static final String RESEND_LEG = "resend-leg";

    private OperatorCommandLine() {
    }

    /**
     * Runs one operator command, returning the contract's process exit code.
     *
     * @param args the action and any supplied arguments, validated before opening resources
     * @return 0 for done, 1 for refused, 2 for failed
     */
    public static int run(final String... args) {
        final int exitCode;
        if (args.length == 1 && ACTIONS.contains(args[0])) {
            exitCode = runInContext(args);
        } else if (args.length > 0 && RESEND_LEG.equals(args[0])) {
            // resend-leg's arguments are checked whole before any context, store or cache is opened
            // (FR-004-020); the refusal code is printed, never the text supplied.
            final Optional<WholeRunRefusal> refusal = ResendLegRunner.checkArguments(args);
            if (refusal.isPresent()) {
                LOG.warn("operator command refused reason={}", refusal.get().value());
                exitCode = RunnerOutcome.REFUSED.exitCode();
            } else {
                exitCode = runInContext(args);
            }
        } else {
            LOG.warn("operator command refused reason=unreadable-argument");
            exitCode = RunnerOutcome.REFUSED.exitCode();
        }
        return exitCode;
    }

    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Context/startup/store failures must produce FAILED and an exit, never start the normal service.
    private static int runInContext(final String... args) {
        int exitCode;
        final SpringApplication application = new SpringApplication(CommandConfiguration.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        // The arguments reach the runners as a bean, not as Spring command-line properties: an
        // operator's free text is never turned into configuration.
        application.addInitializers(context -> context.getBeanFactory().registerSingleton(
                "operatorArguments", new OperatorArguments(List.of(args))));
        try (ConfigurableApplicationContext context = application.run()) {
            exitCode = context.getBean(RunnerDispatcher.class).dispatch(args).orElseThrow().exitCode();
        } catch (final RuntimeException failure) {
            LOG.error("operator command failed reason=store-or-broker-unavailable");
            exitCode = RunnerOutcome.FAILED.exitCode();
        }
        return exitCode;
    }

    /**
     * The operator command's own arguments, as the runners that take arguments read them.
     *
     * @param values the arguments, the action name first
     */
    /* default */ record OperatorArguments(List<String> values) {

        /* default */ String[] asArray() {
            return values.toArray(String[]::new);
        }
    }

    /**
     * Explicit Boot source, deliberately without {@code @Configuration} or component scanning:
     * the normal server must not discover this separate composition root.
     */
    @EnableAutoConfiguration
    public static class CommandConfiguration {

        /* default */ @Bean
        IntakeControlRepository commandIntakeControl(final JdbcClient jdbc) {
            return new JdbcIntakeControlRepository(jdbc);
        }

        /* default */ @Bean
        IntakePauseGate commandPauseGate(final IntakeControlRepository repository) {
            // A fresh standalone process reconciles from the persisted state before its guard.
            return repository::pausedByOperator;
        }

        /* default */ @Bean
        QueuePurger unavailableQueuePurger() {
            // The installed Java SDK has no native cutoff-delete operation. Receiving and abandoning
            // later arrivals can exhaust their delivery budget, so that fallback is forbidden.
            return cutoff -> {
                LOG.error("purge-queue failed reason=unsupported-broker-operation");
                throw new QueuePurger.QueuePurgeException(new UnsupportedOperationException(
                        "native cutoff deletion is unavailable in the installed Service Bus SDK"));
            };
        }

        /* default */
        @Bean(destroyMethod = "shutdown")
        @Lazy
        RedisClient commandRedisClient(final Environment environment) {
            final DistributionProperties properties = distributionProperties(environment);
            final RedisURI.Builder uri = RedisURI.builder()
                    .withHost(environment.getRequiredProperty("distribution.redis.host"))
                    .withPort(environment.getRequiredProperty("distribution.redis.port",
                            Integer.class))
                    .withSsl(environment.getRequiredProperty("distribution.redis.ssl", Boolean.class))
                    .withVerifyPeer(environment.getRequiredProperty("distribution.redis.verify-peer",
                            Boolean.class))
                    .withTimeout(properties.redis().commandTimeout());
            final String password = environment.getProperty("distribution.redis.key", "");
            if (!password.isBlank()) {
                uri.withPassword(password.toCharArray());
            }
            return RedisClient.create(uri.build());
        }

        /* default */
        @Bean(destroyMethod = "close")
        @Lazy
        StatefulRedisConnection<String, String> commandRedisConnection(
                final RedisClient commandRedisClient) {
            return commandRedisClient.connect();
        }

        /**
         * The resend-leg runner over the same pipeline the service runs: built only when
         * {@code resend-leg} is dispatched, so no other command opens the cache or a client.
         */
        /* default */
        @Bean
        @Lazy
        ResendLegRunner commandResendLegRunner(final Environment environment, final JdbcClient jdbc,
                final PlatformTransactionManager transactions,
                final StatefulRedisConnection<String, String> commandRedisConnection,
                final IntakePauseGate gate, final ObjectProvider<BuildProperties> buildProperties,
                final ObjectProvider<MeterRegistry> meterRegistry) {
            final DistributionProperties properties = distributionProperties(environment);
            final TransactionTemplate tx = new TransactionTemplate(transactions);
            final MetricRegistry metrics = new MetricRegistry(
                    meterRegistry.getIfAvailable(SimpleMeterRegistry::new));
            final ChainedHearingPayloadSource payload = new ChainedHearingPayloadSource(
                    new CachedHearingPayloadAdapter(commandRedisConnection,
                            environment.getRequiredProperty("distribution.redis.key-prefix")),
                    new ResultsQueryExternalHearingClient(properties.results().baseUrl(),
                            properties.results().systemUserId(), properties.results().connectTimeout(),
                            properties.results().readTimeout(), properties.results().maxAttempts(),
                            properties.results().initialBackoff(), properties.results().maxBackoff()),
                    properties.budget().payload());
            final FeaturePermissionClient youthFilter = new FeaturePermissionClient(
                    properties.usersgroups().baseUrl(), properties.usersgroups().systemUserId(),
                    environment.getRequiredProperty("distribution.usersgroups.youth-filter-key"),
                    properties.usersgroups().connectTimeout(), properties.usersgroups().readTimeout(),
                    properties.usersgroups().maxAttempts(), properties.usersgroups().retryWait(),
                    metrics);
            final HmppsPublisher publisher = new HmppsPublisher(properties.hmpps().url(),
                    environment.getProperty("distribution.gateway.subscription-key", ""),
                    properties.gateway().connectTimeout(), properties.gateway().readTimeout(),
                    properties.gateway().maxAttempts(), properties.gateway().initialBackoff(),
                    properties.gateway().maxBackoff(), properties.budget().leg(), metrics);
            final BuildProperties build = buildProperties.getIfAvailable();
            final String serviceVersion = build == null ? "unknown" : build.getVersion();
            final OutboundLogAdapter outboundLog = new OutboundLogAdapter(
                    new OutboundMessageRepository(jdbc, tx, serviceVersion));
            final DistributionRunner runner = new DistributionRunner(payload, youthFilter, publisher,
                    outboundLog, YouthSwitchRecordedValue.fromValue(
                            properties.youthSwitch().defaultWhenUnreadable()), metrics);
            final LegResender resender = new LegResender(gate, new JdbcProcessedRequestRepository(jdbc,
                    tx, properties.claim().lease()), runner, metrics, properties.resend().maxIdsPerRun());
            return new ResendLegRunner(resender);
        }

        /* default */ @Bean
        RunnerDispatcher commandDispatcher(final IntakeControlRepository repository,
                                           final IntakePauseGate gate, final QueuePurger purger,
                                           final JdbcClient jdbc,
                                           final PlatformTransactionManager transactions,
                                           final ObjectProvider<ResendLegRunner> resendLeg,
                                           final OperatorArguments operatorArguments) {
            final IntakePauseAction intake = new IntakePauseAction(repository);
            final PauseIntakeRunner pause = new PauseIntakeRunner(intake);
            final ResumeIntakeRunner resume = new ResumeIntakeRunner(intake);
            final PurgeQueueRunner purge = new PurgeQueueRunner(new PurgeQueueAction(gate, purger,
                    Clock.systemUTC()));
            final RollbackCloseInFlightRunner rollback = new RollbackCloseInFlightRunner(
                    new RollbackCloseInFlightAction(gate, new JdbcRollbackFencingRepository(jdbc,
                            new TransactionTemplate(transactions))));
            return new RunnerDispatcher(Map.of("pause-intake", pause::run, "resume-intake", resume::run,
                    "purge-queue", purge::run, "rollback-close-in-flight", rollback::run,
                    RESEND_LEG, () -> resendLeg.getObject().run(operatorArguments.asArray())));
        }

        private static DistributionProperties distributionProperties(final Environment environment) {
            return Binder.get(environment).bind("distribution", DistributionProperties.class)
                    .orElseThrow(() -> new IllegalStateException("distribution.* is not configured"));
        }

    }
}
