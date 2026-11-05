package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

/**
 * Pins that {@code distributionConfigValidator} — the bean the real server boots with — actually
 * invokes the gateway-key and Redis-TLS checks (FR-002-066, FR-002-016, DV-14), not just that the
 * checks exist in {@link StartupValidator} in isolation (that is {@link StartupValidationTest}'s
 * job). Before this was wired in, a blank gateway key or a disabled Redis TLS flag sailed through
 * unvalidated on the actual startup path.
 */
@DisplayName("the distribution config validator's wiring of the gateway-key and Redis-TLS checks")
class StartupValidationConfigTest {

    @Test
    @DisplayName("refuses a blank gateway subscription key outside local/test (FR-002-066)")
    void refuses_a_blank_gateway_key() {
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), new MockEnvironment());

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.gateway.subscription-key");
    }

    @Test
    @DisplayName("refuses Redis TLS switched off outside local/test (FR-002-016, DV-14)")
    void refuses_redis_tls_off() {
        final MockEnvironment environment = new MockEnvironment()
                .withProperty("distribution.gateway.subscription-key", "a-key")
                .withProperty("distribution.redis.ssl", "false")
                .withProperty("distribution.redis.verify-peer", "false");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.ssl");
    }

    @Test
    @DisplayName("starts under the local profile with a blank gateway key and Redis TLS off")
    void accepts_local_profile_defaults() {
        final MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("local");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatCode(() -> runner.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("starts outside local/test with a non-blank key and Redis TLS on")
    void accepts_compliant_deployed_configuration() {
        final MockEnvironment environment = new MockEnvironment()
                .withProperty("distribution.gateway.subscription-key", "a-key")
                .withProperty("distribution.redis.ssl", "true")
                .withProperty("distribution.redis.verify-peer", "true");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatCode(() -> runner.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
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
