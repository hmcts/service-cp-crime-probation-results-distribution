package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The typed {@code distribution.*} configuration this slice validates at startup (design §8.1,
 * digest §12; FR-002-066, FR-002-067). Only the groups the slice's own path uses are bound here;
 * later slices bind the resend cap and the body-retention properties. Unknown keys are ignored, so
 * the scaffold additions and later-slice keys bind elsewhere without failing this record.
 *
 * @param budget      the per-stage time budgets
 * @param gateway     the HMPPS gateway client
 * @param results     the results-query client and its system user
 * @param usersgroups the users-groups client and its system user
 * @param redis       the cache client timeouts
 * @param claim       the claim lease and processing deadline
 * @param servicebus  the broker delivery and lock settings
 * @param youthSwitch the youth-switch default
 * @param hmpps       the HMPPS route
 * @param resend      the resend-leg run cap (slice 004, FR-004-019)
 */
@ConfigurationProperties(prefix = "distribution")
public record DistributionProperties(
        Budget budget,
        Gateway gateway,
        Results results,
        UsersGroups usersgroups,
        Redis redis,
        Claim claim,
        ServiceBus servicebus,
        YouthSwitch youthSwitch,
        Hmpps hmpps,
        Resend resend) {

    /* default */ record Budget(Duration payload, Duration youthSwitch, Duration leg) {
    }

    /* default */ record Gateway(Duration connectTimeout, Duration readTimeout, int maxAttempts,
                   Duration initialBackoff, Duration maxBackoff) {
    }

    /* default */ record Results(String baseUrl, String systemUserId, Duration connectTimeout,
                   Duration readTimeout, int maxAttempts, Duration initialBackoff,
                   Duration maxBackoff) {
    }

    /* default */ record UsersGroups(String baseUrl, String systemUserId, Duration connectTimeout,
                       Duration readTimeout, int maxAttempts, Duration retryWait) {
    }

    /* default */ record Redis(Duration connectTimeout, Duration commandTimeout) {
    }

    /* default */ record Claim(Duration lease, Duration processingDeadline) {
    }

    /* default */ record ServiceBus(int maxDeliveryCount, Duration lockDuration,
                      Duration maxAutoLockRenewDuration) {
    }

    /* default */ record YouthSwitch(String defaultWhenUnreadable) {
    }

    /* default */ record Hmpps(String url) {
    }

    /* default */ record Resend(int maxIdsPerRun) {
    }
}
