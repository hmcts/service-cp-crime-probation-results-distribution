package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins S6 (review of PR #8/#23): {@code distribution.redis.connect-timeout} is validated at startup
 * but, without a client-options wiring, Lettuce never actually applied it — the command timeout on
 * {@code RedisURI} is the only timeout that reached the client, leaving connect at Lettuce's own
 * 10s default regardless of the property.
 */
@DisplayName("PipelineClients.redisClient")
class PipelineClientsTest {

    @Test
    @DisplayName("applies distribution.redis.connect-timeout as the socket connect timeout")
    @SuppressWarnings("PMD.CloseResource")
    void applies_the_configured_connect_timeout() {
        final Duration connectTimeout = Duration.ofSeconds(3);
        final DistributionProperties properties = validPropertiesWithRedis(
                new DistributionProperties.Redis(connectTimeout, Duration.ofSeconds(5)));

        final RedisClient client = PipelineClients.redisClient("localhost", 6379, false, false, "",
                properties);

        try {
            assertThat(client.getOptions().getSocketOptions().getConnectTimeout())
                    .isEqualTo(connectTimeout);
        } finally {
            client.shutdown();
        }
    }

    private static DistributionProperties validPropertiesWithRedis(
            final DistributionProperties.Redis redis) {
        return new DistributionProperties(
                new DistributionProperties.Budget(Duration.ofSeconds(60), Duration.ofSeconds(25),
                        Duration.ofSeconds(130)),
                new DistributionProperties.Gateway(Duration.ofSeconds(5), Duration.ofSeconds(60), 3,
                        Duration.ofMillis(500), Duration.ofSeconds(10)),
                new DistributionProperties.Results("https://results", "system-user",
                        Duration.ofSeconds(5), Duration.ofSeconds(30), 4, Duration.ofMillis(500),
                        Duration.ofSeconds(20)),
                new DistributionProperties.UsersGroups("https://usersgroups", "system-user",
                        Duration.ofSeconds(2), Duration.ofSeconds(5), 3, Duration.ofSeconds(1)),
                redis,
                new DistributionProperties.Claim(Duration.ofMinutes(4).plusSeconds(30),
                        Duration.ofMinutes(4)),
                new DistributionProperties.ServiceBus(5, Duration.ofMinutes(5),
                        Duration.ofMinutes(5)),
                new DistributionProperties.YouthSwitch("ACTIVE"),
                new DistributionProperties.Hmpps("https://hmpps"),
                new DistributionProperties.Resend(100));
    }
}
