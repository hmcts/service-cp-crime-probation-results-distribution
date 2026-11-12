package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;

/**
 * Pins FR-004-045: the CLI's composition root must never register the body-retention cron
 * (review of PR #8). {@link OperatorCommandLine.CommandConfiguration}'s {@code
 * @EnableAutoConfiguration} picks up every entry in {@code AutoConfiguration.imports}, including
 * {@link uk.gov.hmcts.cp.probationresultsdistribution.config.BodyRetentionConfiguration} — nothing
 * pinned that it stays excluded.
 */
@DisplayName("the CLI composition root")
class CommandConfigurationExcludesBodyRetentionIT extends EndToEndTestSupport {

    @Test
    @DisplayName("never registers the body-retention job bean")
    void cli_context_has_no_body_retention_job() {
        final DriverManagerDataSource source = (DriverManagerDataSource) sharedDataSource();
        final SpringApplicationBuilder builder =
                new SpringApplicationBuilder(OperatorCommandLine.CommandConfiguration.class)
                        .web(WebApplicationType.NONE)
                        .initializers(context -> context.getBeanFactory().registerSingleton(
                                "operatorArguments", new OperatorCommandLine.OperatorArguments(
                                        List.of("list-requests"))));
        // Command-line arguments outrank application.yaml's committed local-development datasource
        // defaults, unlike setDefaultProperties(...), which application.yaml would win over. The
        // readiness-group override mirrors the one runInContext() itself passes (review of PR #23):
        // without it this context refresh fails before reaching the try block at all, with Spring's
        // own "Health contributor 'intakeMigration' ... does not exist" - intakeMigration is a bean
        // only ServiceBusConsumerConfig registers, never this standalone composition root.
        try (ConfigurableApplicationContext context = builder.run(
                "--spring.datasource.url=" + source.getUrl(),
                "--spring.datasource.username=" + source.getUsername(),
                "--spring.datasource.password=" + source.getPassword(),
                "--management.endpoint.health.group.readiness.include=db")) {
            assertThat(context.getBeanNamesForType(BodyRetentionJob.class)).isEmpty();
            // Sanity: the exclusion must not have dropped JDBC auto-configuration along with it.
            assertThat(context.getBeanNamesForType(JdbcClient.class)).isNotEmpty();
        }
    }
}
