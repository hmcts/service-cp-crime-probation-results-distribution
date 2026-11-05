package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.time.Duration;

/**
 * The typed configuration the composition-root suites boot with: the service's defaults, with
 * placeholder local endpoints. Shared so a suite outside this package can build one, the nested
 * property records being package-private.
 */
public final class DistributionPropertiesFixtures {

    private DistributionPropertiesFixtures() {
    }

    /**
     * The defaults, with placeholder local endpoints.
     *
     * @return the properties
     */
    public static DistributionProperties defaults() {
        return new DistributionProperties(
                new DistributionProperties.Budget(
                        Duration.ofSeconds(60), Duration.ofSeconds(25), Duration.ofSeconds(130)),
                new DistributionProperties.Gateway(Duration.ofSeconds(5), Duration.ofSeconds(60),
                        3, Duration.ofMillis(500), Duration.ofSeconds(10)),
                new DistributionProperties.Results("http://localhost:8080",
                        "00000000-0000-0000-0000-000000000000", Duration.ofSeconds(5),
                        Duration.ofSeconds(30), 4, Duration.ofMillis(500), Duration.ofSeconds(20)),
                new DistributionProperties.UsersGroups("http://localhost:8080",
                        "00000000-0000-0000-0000-000000000000", Duration.ofSeconds(2),
                        Duration.ofSeconds(5), 3, Duration.ofSeconds(1)),
                new DistributionProperties.Redis(Duration.ofSeconds(5), Duration.ofSeconds(5)),
                new DistributionProperties.Claim(
                        Duration.ofMinutes(4).plusSeconds(30), Duration.ofMinutes(4)),
                new DistributionProperties.ServiceBus(5, Duration.ofMinutes(5), Duration.ofMinutes(5)),
                new DistributionProperties.YouthSwitch("ACTIVE"),
                new DistributionProperties.Hmpps("https://localhost/hmpps"),
                new DistributionProperties.Resend(100));
    }
}
