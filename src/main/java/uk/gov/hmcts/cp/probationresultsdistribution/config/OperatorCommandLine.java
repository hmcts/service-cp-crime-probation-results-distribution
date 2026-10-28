package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.time.Clock;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseGate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.PurgeQueueAction;
import uk.gov.hmcts.cp.probationresultsdistribution.application.QueuePurger;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RollbackCloseInFlightAction;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.PauseIntakeRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.PurgeQueueRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.ResumeIntakeRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.RollbackCloseInFlightRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.RunnerDispatcher;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcIntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcRollbackFencingRepository;

/** Composes one operator command without scanning the server, consumer or scheduled jobs. */
public final class OperatorCommandLine {

    private static final Logger LOG = LoggerFactory.getLogger(OperatorCommandLine.class);
    private static final Set<String> ACTIONS = Set.of("pause-intake", "resume-intake", "purge-queue",
            "rollback-close-in-flight");

    private OperatorCommandLine() {
    }

    /**
     * Runs a no-argument intake command, returning the contract's process exit code.
     *
     * @param args the action and any supplied arguments, validated before opening resources
     * @return 0 for done, 1 for refused, 2 for failed
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    // Context/startup/store failures must produce FAILED and an exit, never start the normal service.
    public static int run(final String... args) {
        int exitCode;
        if (args.length == 1 && ACTIONS.contains(args[0])) {
            final SpringApplication application = new SpringApplication(CommandConfiguration.class);
            application.setWebApplicationType(WebApplicationType.NONE);
            try (ConfigurableApplicationContext context = application.run()) {
                exitCode = context.getBean(RunnerDispatcher.class).dispatch(args).orElseThrow().exitCode();
            } catch (final RuntimeException failure) {
                LOG.error("operator command failed reason=store-or-broker-unavailable");
                exitCode = RunnerOutcome.FAILED.exitCode();
            }
        } else {
            LOG.warn("operator command refused reason=unreadable-argument");
            exitCode = RunnerOutcome.REFUSED.exitCode();
        }
        return exitCode;
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

        /* default */ @Bean
        RunnerDispatcher commandDispatcher(final IntakeControlRepository repository,
                                           final IntakePauseGate gate, final QueuePurger purger,
                                           final JdbcClient jdbc,
                                           final PlatformTransactionManager transactions) {
            final IntakePauseAction intake = new IntakePauseAction(repository);
            final PauseIntakeRunner pause = new PauseIntakeRunner(intake);
            final ResumeIntakeRunner resume = new ResumeIntakeRunner(intake);
            final PurgeQueueRunner purge = new PurgeQueueRunner(new PurgeQueueAction(gate, purger,
                    Clock.systemUTC()));
            final RollbackCloseInFlightRunner rollback = new RollbackCloseInFlightRunner(
                    new RollbackCloseInFlightAction(gate, new JdbcRollbackFencingRepository(jdbc,
                            new TransactionTemplate(transactions))));
            return new RunnerDispatcher(Map.of("pause-intake", pause::run, "resume-intake", resume::run,
                    "purge-queue", purge::run, "rollback-close-in-flight", rollback::run));
        }

    }
}
