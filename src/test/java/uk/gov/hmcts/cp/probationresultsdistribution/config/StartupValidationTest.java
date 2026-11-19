package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.Budget;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.Claim;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.Gateway;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.Hmpps;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.Redis;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.Resend;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.Results;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.ServiceBus;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.UsersGroups;
import uk.gov.hmcts.cp.probationresultsdistribution.config.DistributionProperties.YouthSwitch;

@DisplayName("the startup configuration validator")
class StartupValidationTest {

    private final StartupValidator validator = new StartupValidator();

    @Test
    @DisplayName("accepts the committed defaults, with and without required secrets")
    void accepts_the_committed_defaults() {
        assertThatCode(() -> validator.validate(valid().build(), false)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validate(valid().build(), true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a payload budget the composed stage overruns, naming the property")
    void refuses_an_overrun_payload_budget() {
        assertThatThrownBy(() -> validator.validate(
                valid().withBudget(new Budget(Duration.ofSeconds(50), Duration.ofSeconds(25),
                        Duration.ofSeconds(130))).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.budget.payload");
    }

    @Test
    @DisplayName("refuses a send stage that overruns the leg budget")
    void refuses_an_overrun_send_stage() {
        assertThatThrownBy(() -> validator.validate(
                valid().withGateway(new Gateway(Duration.ofSeconds(5), Duration.ofSeconds(60), 3,
                        Duration.ofMillis(500), Duration.ofSeconds(90))).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.budget.leg");
    }

    @Test
    @DisplayName("refuses a lease that is not shorter than the lock")
    void refuses_a_lease_not_shorter_than_the_lock() {
        assertThatThrownBy(() -> validator.validate(
                valid().withClaim(new Claim(Duration.ofMinutes(6), Duration.ofMinutes(4))).build(),
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.lock-duration");
    }

    @Test
    @DisplayName("refuses a processing deadline that is not shorter than the lease")
    void refuses_a_deadline_not_shorter_than_the_lease() {
        assertThatThrownBy(() -> validator.validate(
                valid().withClaim(new Claim(Duration.parse("PT4M30S"), Duration.ofMinutes(5))).build(),
                false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.claim.lease");
    }

    @Test
    @DisplayName("refuses differing system-user ids")
    void refuses_differing_system_user_ids() {
        assertThatThrownBy(() -> validator.validate(
                valid().withUsersgroups(usersGroupsWith("other-user")).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.results.system-user-id");
    }

    @Test
    @DisplayName("refuses a youth default other than ACTIVE or INACTIVE")
    void refuses_an_invalid_youth_default() {
        assertThatThrownBy(() -> validator.validate(
                valid().withYouthSwitch(new YouthSwitch("MAYBE")).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.youth-switch.default-when-unreadable");
    }

    @Test
    @DisplayName("refuses a missing HMPPS url when secrets are required")
    void refuses_a_missing_hmpps_url_in_a_deployed_profile() {
        assertThatThrownBy(() -> validator.validate(
                valid().withHmpps(new Hmpps("http://insecure")).build(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.hmpps.url");
    }

    @Test
    @DisplayName("refuses a blank system user when secrets are required")
    void refuses_a_blank_system_user_in_a_deployed_profile() {
        assertThatThrownBy(() -> validator.validate(
                valid().withResults(resultsWith("")).withUsersgroups(usersGroupsWith("")).build(), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.results.system-user-id");
    }

    @Test
    @DisplayName("refuses a blank gateway subscription key only when secrets are required")
    void refuses_a_blank_gateway_key_when_secrets_are_required() {
        assertThatThrownBy(() -> validator.validateGatewayKey(" ", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.gateway.subscription-key");
        assertThatCode(() -> validator.validateGatewayKey("", false)).doesNotThrowAnyException();
        assertThatCode(() -> validator.validateGatewayKey("a-key", true)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses Redis TLS or peer verification switched off, only outside the local and "
            + "test profiles (FR-002-016, DV-14)")
    void refuses_redis_tls_or_peer_verification_off_when_secrets_are_required() {
        assertThatThrownBy(() -> validator.validateRedisTls(false, true, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.ssl");
        assertThatThrownBy(() -> validator.validateRedisTls(true, false, true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.verify-peer");
        assertThatCode(() -> validator.validateRedisTls(false, false, false))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateRedisTls(true, true, true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a forbidden property and accepts an environment without one")
    void refuses_a_forbidden_property() {
        assertThatThrownBy(() -> validator.validateNoForbiddenProperties(
                new MockEnvironment().withProperty("distribution.vep.enabled", "true")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.vep.enabled");
        assertThatCode(() -> validator.validateNoForbiddenProperties(
                new MockEnvironment().withProperty("distribution.hmpps.url", "https://x")))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses an Azure App Configuration connection string or label (H22, H26)")
    void refuses_an_app_configuration_property() {
        assertThatThrownBy(() -> validator.validateNoForbiddenProperties(
                new MockEnvironment().withProperty(
                        "spring.cloud.azure.appconfiguration.stores[0].connection-string", "x")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("appconfiguration");
        assertThatThrownBy(() -> validator.validateNoForbiddenProperties(
                new MockEnvironment().withProperty("some.endpoint", "https://x.azconfig.io")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("some.endpoint");
    }

    @Test
    @DisplayName("refuses a max-delivery-count other than the queue's configured value of 5")
    void refuses_a_mismatched_max_delivery_count() {
        assertThatThrownBy(() -> validator.validate(
                valid().withServicebus(new ServiceBus(3, Duration.ofMinutes(5),
                        Duration.ofMinutes(5))).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.max-delivery-count");
    }

    @Test
    @DisplayName("refuses a resend cap below 1, naming the property (slice 004, FR-004-019)")
    void refuses_a_resend_cap_below_one() {
        assertThatThrownBy(() -> validator.validate(
                valid().withResend(new Resend(0)).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.resend.max-ids-per-run");
    }

    @Test
    @DisplayName("refuses a client attempt count below 1 for each of the gateway, results and "
            + "users-groups clients")
    void refuses_an_attempt_count_below_one() {
        assertThatThrownBy(() -> validator.validate(
                valid().withGateway(new Gateway(Duration.ofSeconds(5), Duration.ofSeconds(60), 0,
                        Duration.ofMillis(500), Duration.ofSeconds(10))).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.gateway.max-attempts");
        assertThatThrownBy(() -> validator.validate(
                valid().withResults(new Results("http://results", "system-user",
                        Duration.ofSeconds(5), Duration.ofSeconds(30), 0, Duration.ofMillis(500),
                        Duration.ofSeconds(20))).build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.results.max-attempts");
        assertThatThrownBy(() -> validator.validate(
                valid().withUsersgroups(new UsersGroups("http://usersgroups", "system-user",
                        Duration.ofSeconds(2), Duration.ofSeconds(5), 0, Duration.ofSeconds(1)))
                        .build(), false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.usersgroups.max-attempts");
    }

    @Test
    @DisplayName("refuses neither or both Service Bus credentials, accepts exactly one")
    void refuses_neither_or_both_service_bus_credentials() {
        assertThatThrownBy(() -> validator.validateServiceBusCredential("", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.connection-string");
        assertThatThrownBy(() -> validator.validateServiceBusCredential("Endpoint=sb://x",
                "a-namespace", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.connection-string");
        assertThatCode(() -> validator.validateServiceBusCredential("Endpoint=sb://x", "", true))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateServiceBusCredential("", "a-namespace", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("accepts neither Service Bus credential only when secrets are not required (test "
            + "profile)")
    void accepts_no_service_bus_credential_when_secrets_not_required() {
        assertThatCode(() -> validator.validateServiceBusCredential("", "", false))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> validator.validateServiceBusCredential("Endpoint=sb://x",
                "a-namespace", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.connection-string");
    }

    @Test
    @DisplayName("refuses the namespace credential outside local/test with any workload identity "
            + "variable missing")
    void refuses_namespace_credential_without_workload_identity() {
        final MockEnvironment complete =
                new MockEnvironment()
                        .withProperty("AZURE_CLIENT_ID", "a-client-id")
                        .withProperty("AZURE_TENANT_ID", "a-tenant-id")
                        .withProperty("AZURE_FEDERATED_TOKEN_FILE", "/var/run/secrets/token");
        assertThatCode(() -> validator.validateWorkloadIdentity("a-namespace", complete, true))
                .doesNotThrowAnyException();

        assertThatThrownBy(() -> validator.validateWorkloadIdentity("a-namespace",
                new MockEnvironment()
                        .withProperty("AZURE_TENANT_ID", "a-tenant-id")
                        .withProperty("AZURE_FEDERATED_TOKEN_FILE", "/var/run/secrets/token"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AZURE_CLIENT_ID");
        assertThatThrownBy(() -> validator.validateWorkloadIdentity("a-namespace",
                new MockEnvironment()
                        .withProperty("AZURE_CLIENT_ID", "a-client-id")
                        .withProperty("AZURE_FEDERATED_TOKEN_FILE", "/var/run/secrets/token"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AZURE_TENANT_ID");
        assertThatThrownBy(() -> validator.validateWorkloadIdentity("a-namespace",
                new MockEnvironment()
                        .withProperty("AZURE_CLIENT_ID", "a-client-id")
                        .withProperty("AZURE_TENANT_ID", "a-tenant-id"), true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("AZURE_FEDERATED_TOKEN_FILE");
    }

    @Test
    @DisplayName("never checks workload identity for the connection-string path or when secrets "
            + "are not required")
    void skips_workload_identity_check_when_not_applicable() {
        final MockEnvironment empty =
                new MockEnvironment();
        assertThatCode(() -> validator.validateWorkloadIdentity("", empty, true))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateWorkloadIdentity("a-namespace", empty, false))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a blank Redis host always, and a blank key only when secrets are required")
    void refuses_a_blank_redis_host_or_key() {
        assertThatThrownBy(() -> validator.validateRedisRequired("", "a-key", false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.host");
        assertThatThrownBy(() -> validator.validateRedisRequired("a-host", "", true))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.key");
        assertThatCode(() -> validator.validateRedisRequired("a-host", "", false))
                .doesNotThrowAnyException();
        assertThatCode(() -> validator.validateRedisRequired("a-host", "a-key", true))
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a Redis port outside the valid TCP range")
    void refuses_an_invalid_redis_port() {
        assertThatThrownBy(() -> validator.validateRedisPort(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.port");
        assertThatThrownBy(() -> validator.validateRedisPort(70_000))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.redis.port");
        assertThatCode(() -> validator.validateRedisPort(10_000)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("refuses a Service Bus concurrency below 1")
    void refuses_a_concurrency_below_one() {
        assertThatThrownBy(() -> validator.validateConcurrency(0))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("distribution.servicebus.max-concurrent-calls");
        assertThatCode(() -> validator.validateConcurrency(2)).doesNotThrowAnyException();
    }

    private static Builder valid() {
        return new Builder();
    }

    private static Results resultsWith(final String systemUser) {
        return new Results("http://results", systemUser, Duration.ofSeconds(5),
                Duration.ofSeconds(30), 4, Duration.ofMillis(500), Duration.ofSeconds(20));
    }

    private static UsersGroups usersGroupsWith(final String systemUser) {
        return new UsersGroups("http://usersgroups", systemUser, Duration.ofSeconds(2),
                Duration.ofSeconds(5), 3, Duration.ofSeconds(1));
    }

    private static final class Builder {
        private Budget budget = new Budget(Duration.ofSeconds(60), Duration.ofSeconds(25),
                Duration.ofSeconds(130));
        private Gateway gateway = new Gateway(Duration.ofSeconds(5), Duration.ofSeconds(60), 3,
                Duration.ofMillis(500), Duration.ofSeconds(10));
        private Results results = resultsWith("system-user");
        private UsersGroups usersgroups = usersGroupsWith("system-user");
        private Redis redis = new Redis(Duration.ofSeconds(5), Duration.ofSeconds(5));
        private Claim claim = new Claim(Duration.parse("PT4M30S"), Duration.ofMinutes(4));
        private ServiceBus servicebus = new ServiceBus(5, Duration.ofMinutes(5),
                Duration.ofMinutes(5));
        private YouthSwitch youthSwitch = new YouthSwitch("ACTIVE");
        private Hmpps hmpps = new Hmpps("https://hmpps");
        private Resend resend = new Resend(100);

        private Builder withBudget(final Budget value) {
            this.budget = value;
            return this;
        }

        private Builder withGateway(final Gateway value) {
            this.gateway = value;
            return this;
        }

        private Builder withResults(final Results value) {
            this.results = value;
            return this;
        }

        private Builder withUsersgroups(final UsersGroups value) {
            this.usersgroups = value;
            return this;
        }

        private Builder withClaim(final Claim value) {
            this.claim = value;
            return this;
        }

        private Builder withServicebus(final ServiceBus value) {
            this.servicebus = value;
            return this;
        }

        private Builder withYouthSwitch(final YouthSwitch value) {
            this.youthSwitch = value;
            return this;
        }

        private Builder withHmpps(final Hmpps value) {
            this.hmpps = value;
            return this;
        }

        private Builder withResend(final Resend value) {
            this.resend = value;
            return this;
        }

        private DistributionProperties build() {
            return new DistributionProperties(budget, gateway, results, usersgroups, redis, claim,
                    servicebus, youthSwitch, hmpps, resend);
        }
    }
}
