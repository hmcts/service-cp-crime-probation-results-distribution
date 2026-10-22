package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

@DisplayName("the youth-switch default startup setting")
class YouthSwitchDefaultConfigTest {

    private static final String CONFIGURED_DEFAULT = "INACTIVE";

    private final Logger configLogger =
            (Logger) LoggerFactory.getLogger(StartupValidationConfig.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @AfterEach
    void detachAppender() {
        configLogger.detachAppender(appender);
        appender.stop();
    }

    @Test
    @DisplayName("logs the validated configured default at INFO")
    void logs_the_configured_default() throws Exception {
        appender.start();
        configLogger.addAppender(appender);
        final ApplicationRunner runner = new StartupValidationConfig().distributionConfigValidator(
                validProperties(), new MockEnvironment());

        runner.run(new DefaultApplicationArguments());

        assertThat(appender.list).anySatisfy(event -> {
            assertThat(event.getLevel().toString()).isEqualTo("INFO");
            assertThat(event.getFormattedMessage())
                    .contains("distribution.youth-switch.default-when-unreadable")
                    .contains(CONFIGURED_DEFAULT);
        });
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
                new DistributionProperties.YouthSwitch(CONFIGURED_DEFAULT),
                new DistributionProperties.Hmpps("https://hmpps"));
    }
}
