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
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.gateway.subscription-key", "");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.gateway.subscription-key");
    }

    @Test
    @DisplayName("refuses Redis TLS switched off outside local/test (FR-002-016, DV-14)")
    void refuses_redis_tls_off() {
        final MockEnvironment environment = compliantDeployedEnvironment()
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
        final MockEnvironment environment = compliantDeployedEnvironment();
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatCode(() -> runner.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses neither Service Bus credential set outside local/test")
    void refuses_no_service_bus_credential() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.servicebus.connection-string", "");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.connection-string");
    }

    @Test
    @DisplayName("refuses both Service Bus credentials set at once")
    void refuses_both_service_bus_credentials() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.servicebus.namespace", "my-namespace.servicebus.windows.net")
                .withProperty("AZURE_CLIENT_ID", "a-client-id")
                .withProperty("AZURE_TENANT_ID", "a-tenant-id")
                .withProperty("AZURE_FEDERATED_TOKEN_FILE", "/var/run/secrets/token");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.connection-string");
    }

    @Test
    @DisplayName("refuses the namespace credential outside local/test with no workload identity "
            + "environment")
    void refuses_namespace_credential_without_workload_identity() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.servicebus.connection-string", "")
                .withProperty("distribution.servicebus.namespace",
                        "my-namespace.servicebus.windows.net");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AZURE_CLIENT_ID");
    }

    @Test
    @DisplayName("accepts the namespace credential outside local/test with workload identity present")
    void accepts_namespace_credential_with_workload_identity() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.servicebus.connection-string", "")
                .withProperty("distribution.servicebus.namespace",
                        "my-namespace.servicebus.windows.net")
                .withProperty("AZURE_CLIENT_ID", "a-client-id")
                .withProperty("AZURE_TENANT_ID", "a-tenant-id")
                .withProperty("AZURE_FEDERATED_TOKEN_FILE", "/var/run/secrets/token");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatCode(() -> runner.run(new DefaultApplicationArguments()))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a blank Redis host in every profile")
    void refuses_a_blank_redis_host() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.redis.host", "");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.host");
    }

    @Test
    @DisplayName("refuses a blank Redis key outside local/test")
    void refuses_a_blank_redis_key() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.redis.key", "");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.key");
    }

    @Test
    @DisplayName("refuses a Redis port outside the valid TCP range")
    void refuses_an_invalid_redis_port() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.redis.port", "70000");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.port");
    }

    @Test
    @DisplayName("refuses a Service Bus concurrency below 1")
    void refuses_a_concurrency_below_one() {
        final MockEnvironment environment = compliantDeployedEnvironment()
                .withProperty("distribution.servicebus.max-concurrent-calls", "0");
        final ApplicationRunner runner = new StartupValidationConfig()
                .distributionConfigValidator(validProperties(), environment);

        assertThatThrownBy(() -> runner.run(new DefaultApplicationArguments()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.max-concurrent-calls");
    }

    /**
     * A deployed profile's baseline, compliant on every check this config wires except the one each
     * test below mutates: a real gateway key, Redis TLS on, a single Service Bus credential (the
     * connection-string path, so no workload identity environment is needed), and a non-blank Redis
     * host and key.
     */
    private static MockEnvironment compliantDeployedEnvironment() {
        return new MockEnvironment()
                .withProperty("distribution.gateway.subscription-key", "a-key")
                .withProperty("distribution.redis.ssl", "true")
                .withProperty("distribution.redis.verify-peer", "true")
                .withProperty("distribution.servicebus.connection-string", "Endpoint=sb://test")
                .withProperty("distribution.redis.host", "redis-host")
                .withProperty("distribution.redis.key", "a-redis-key");
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
