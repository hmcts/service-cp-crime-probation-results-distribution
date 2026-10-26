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
