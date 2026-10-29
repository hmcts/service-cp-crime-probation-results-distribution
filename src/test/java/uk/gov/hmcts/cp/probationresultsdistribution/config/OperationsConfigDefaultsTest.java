package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.bind.PropertySourcesPlaceholdersResolver;
import org.springframework.boot.context.properties.source.ConfigurationPropertySources;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pins FR-005-028: the authorisation and audit switches default on in {@code application.yaml}
 * (never the {@code test} profile overlay, which deliberately switches both off since that profile
 * has neither estate behind it), {@code deny-when-no-rules} holds, and a pod with the audit filter on
 * and its transport off comes up and says so once at WARN, naming both settings, without refusing to
 * start.
 */
@DisplayName("the operations API's authorisation and audit defaults")
class OperationsConfigDefaultsTest {

    private final Logger configLogger =
            (Logger) LoggerFactory.getLogger(StartupValidationConfig.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detachAppender() {
        configLogger.detachAppender(appender);
        appender.stop();
    }

    @Nested
    @DisplayName("application.yaml's own defaults")
    class Defaults {

        @Test
        @DisplayName("authz.http.enabled and audit.http.enabled default true")
        void authz_and_audit_default_true() throws IOException {
            final Binder binder = applicationYamlBinder();

            assertThat(binder.bind("authz.http.enabled", Bindable.of(Boolean.class)).get()).isTrue();
            assertThat(binder.bind("audit.http.enabled", Bindable.of(Boolean.class)).get()).isTrue();
        }

        @Test
        @DisplayName("authz.http.deny-when-no-rules holds")
        void deny_when_no_rules_holds() throws IOException {
            final Binder binder = applicationYamlBinder();

            assertThat(binder.bind("authz.http.deny-when-no-rules", Bindable.of(Boolean.class)).get())
                    .isTrue();
        }

        private Binder applicationYamlBinder() throws IOException {
            final List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application",
                    new FileSystemResource("src/main/resources/application.yaml"));
            final MutablePropertySources propertySources = new MutablePropertySources();
            sources.forEach(propertySources::addLast);
            return new Binder(ConfigurationPropertySources.from(sources),
                    new PropertySourcesPlaceholdersResolver(propertySources));
        }
    }

    @Nested
    @DisplayName("the audit filter on with its transport off")
    class AuditFilterOnTransportOff {

        @Test
        @DisplayName("logs exactly one WARN naming both settings, and does not refuse to start")
        void warns_once_and_starts() throws Exception {
            appender.start();
            configLogger.addAppender(appender);
            final MockEnvironment environment = new MockEnvironment()
                    .withProperty("audit.http.enabled", "true")
                    .withProperty("cp.audit.enabled", "false");
            final ApplicationRunner runner = new StartupValidationConfig()
                    .distributionConfigValidator(validProperties(), environment);

            assertThatCode(() -> runner.run(new DefaultApplicationArguments()))
                    .doesNotThrowAnyException();

            final List<ILoggingEvent> warnings = appender.list.stream()
                    .filter(event -> "WARN".equals(event.getLevel().toString()))
                    .filter(event -> event.getFormattedMessage().contains("audit.http.enabled"))
                    .toList();
            assertThat(warnings).hasSize(1);
            assertThat(warnings.get(0).getFormattedMessage())
                    .contains("audit.http.enabled").contains("true")
                    .contains("cp.audit.enabled").contains("false");
        }

        @Test
        @DisplayName("no WARN when the transport is on too")
        void no_warning_when_transport_is_also_on() throws Exception {
            appender.start();
            configLogger.addAppender(appender);
            final MockEnvironment environment = new MockEnvironment()
                    .withProperty("audit.http.enabled", "true")
                    .withProperty("cp.audit.enabled", "true");
            final ApplicationRunner runner = new StartupValidationConfig()
                    .distributionConfigValidator(validProperties(), environment);

            runner.run(new DefaultApplicationArguments());

            assertThat(appender.list).noneMatch(
                    event -> event.getFormattedMessage().contains("audit.http.enabled=true"));
        }
    }

    private static DistributionProperties validProperties() {
        return new DistributionProperties(
                new DistributionProperties.Budget(Duration.ofSeconds(60), Duration.ofSeconds(25),
                        Duration.ofSeconds(130)),
                new DistributionProperties.Gateway(Duration.ofSeconds(5), Duration.ofSeconds(60),
                        3, Duration.ofMillis(500), Duration.ofSeconds(10)),
                new DistributionProperties.Results("https://results", "system-user",
                        Duration.ofSeconds(5), Duration.ofSeconds(30), 4,
                        Duration.ofMillis(500), Duration.ofSeconds(20)),
                new DistributionProperties.UsersGroups("https://usersgroups", "system-user",
                        Duration.ofSeconds(2), Duration.ofSeconds(5), 3, Duration.ofSeconds(1)),
                new DistributionProperties.Redis(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new DistributionProperties.Claim(Duration.ofMinutes(4).plusSeconds(30),
                        Duration.ofMinutes(4)),
                new DistributionProperties.ServiceBus(5, Duration.ofMinutes(5),
                        Duration.ofMinutes(5)),
                new DistributionProperties.YouthSwitch("ACTIVE"),
                new DistributionProperties.Hmpps("https://hmpps"),
                new DistributionProperties.Resend(100));
    }
}
