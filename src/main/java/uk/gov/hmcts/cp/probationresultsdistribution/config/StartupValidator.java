package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.time.Duration;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.Environment;
import org.springframework.core.env.PropertySource;

/**
 * The startup configuration check (design §6 Startup refusals, digest §12; FR-002-066, FR-002-067).
 * It refuses to start, naming the offending property, when a composed stage does not fit its budget,
 * the claim lease and lock are mis-ordered, the two system-user ids differ, the youth default is not
 * {@code ACTIVE}/{@code INACTIVE}, the delivery limit differs from the queue's configured value, a
 * required secret is missing in a deployed profile, or a forbidden property is present.
 */
public final class StartupValidator {

    private static final Set<String> VALID_DEFAULTS = Set.of("ACTIVE", "INACTIVE");
    private static final Duration RENEW_MARGIN = Duration.ofSeconds(30);
    private static final int EXPECTED_MAX_DELIVERY_COUNT = 5;

    /**
     * Validates the typed configuration, throwing when a rule fails.
     *
     * @param props          the bound configuration
     * @param requireSecrets whether required secrets must be present (true outside local and test)
     */
    public void validate(final DistributionProperties props, final boolean requireSecrets) {
        validateClientTimeouts(props);
        validateComposedStages(props);
        validateTotalBudget(props);
        validateLeaseOrdering(props);
        validateIdentity(props);
        validateYouthDefault(props);
        validateDeliveryLimit(props);
        validateResend(props);
        if (requireSecrets) {
            validateSecrets(props);
        }
    }

    /**
     * Refuses a blank gateway subscription key where secrets are required (outside the {@code local}
     * and {@code test} profiles): without it every POST is answered 401, which records
     * {@code FAILED(receiver-rejected)}, a terminal outcome, although nothing was delivered.
     *
     * @param subscriptionKey {@code distribution.gateway.subscription-key}
     * @param requireSecrets  whether required secrets must be present
     */
    public void validateGatewayKey(final String subscriptionKey, final boolean requireSecrets) {
        if (requireSecrets) {
            require(isNotBlank(subscriptionKey), "distribution.gateway.subscription-key",
                    "is required outside the local and test profiles");
        }
    }

    /**
     * Validates the retention settings before the scheduled bean is constructed.
     *
     * @param properties the bound retention settings
     */
    public void validateBodyRetention(final BodyRetentionProperties properties) {
        final java.time.Period retention = properties.bodyRetention();
        require(retention != null && !retention.isNegative() && retention.getDays() == 0
                        && retention.toTotalMonths() >= 1,
                "distribution.body-retention", "must be a positive whole number of months");
        final String cron = properties.bodyMaintenance().cron();
        require(cron != null && cron.strip().split("\\s+").length == 6
                        && org.springframework.scheduling.support.CronExpression.isValidExpression(cron),
                "distribution.body-maintenance.cron", "must be a valid six-field cron expression");
    }

    /**
     * Refuses a forbidden property: a contract-validation mode (H13), an App Configuration source
     * (H22, H26) — a property name carrying {@code appconfiguration}/{@code app-configuration} or a
     * value holding an {@code azconfig.io} connection string/endpoint — or any
     * {@code distribution.vep.*} (H5).
     *
     * @param environment the application environment
     */
    public void validateNoForbiddenProperties(final Environment environment) {
        if (environment instanceof ConfigurableEnvironment configurable) {
            for (final PropertySource<?> source : configurable.getPropertySources()) {
                if (source instanceof EnumerablePropertySource<?> enumerable) {
                    for (final String name : enumerable.getPropertyNames()) {
                        requireNotForbidden(name, enumerable.getProperty(name));
                    }
                }
            }
        }
    }

    private void validateClientTimeouts(final DistributionProperties props) {
        require(fits(sum(props.gateway().connectTimeout(), props.gateway().readTimeout()),
                        props.budget().leg()),
                "distribution.gateway.read-timeout", "connect + read must fit the leg budget");
        require(fits(sum(props.usersgroups().connectTimeout(), props.usersgroups().readTimeout()),
                        props.budget().youthSwitch()),
                "distribution.usersgroups.read-timeout",
                "connect + read must fit the youth-switch budget");
        require(fits(sum(props.results().connectTimeout(), props.results().readTimeout()),
                        props.budget().payload()),
                "distribution.results.read-timeout", "connect + read must fit the payload budget");
    }

    private void validateComposedStages(final DistributionProperties props) {
        final Duration payload = sum(
                props.redis().connectTimeout().multipliedBy(2),
                props.redis().commandTimeout().multipliedBy(2),
                props.results().connectTimeout(), props.results().readTimeout());
        require(fits(payload, props.budget().payload()),
                "distribution.budget.payload", "the composed payload stage must fit its budget");

        final int switchAttempts = props.usersgroups().maxAttempts();
        final Duration switchStage = sum(
                sum(props.usersgroups().connectTimeout(), props.usersgroups().readTimeout())
                        .multipliedBy(switchAttempts),
                props.usersgroups().retryWait().multipliedBy(Math.max(0, switchAttempts - 1)));
        require(fits(switchStage, props.budget().youthSwitch()),
                "distribution.budget.youth-switch", "the composed switch stage must fit its budget");

        final int sendAttempts = props.gateway().maxAttempts();
        final Duration sendStage = sum(
                sum(props.gateway().connectTimeout(), props.gateway().maxBackoff())
                        .multipliedBy(Math.max(0, sendAttempts - 1)),
                props.gateway().connectTimeout(), props.gateway().readTimeout());
        require(fits(sendStage, props.budget().leg()),
                "distribution.budget.leg", "the composed send stage must fit its budget");

        require(fits(props.gateway().maxBackoff(), props.budget().leg()),
                "distribution.gateway.max-backoff", "the gateway back-off must fit the leg budget");
        require(fits(props.results().maxBackoff(), props.budget().payload()),
                "distribution.results.max-backoff",
                "the results back-off must fit the payload budget");
    }

    private void validateTotalBudget(final DistributionProperties props) {
        final Duration total = sum(props.budget().payload(), props.budget().youthSwitch(),
                props.budget().leg());
        require(total.compareTo(props.claim().processingDeadline()) < 0,
                "distribution.claim.processing-deadline",
                "payload + switch + leg must be below the processing deadline");
    }

    private void validateLeaseOrdering(final DistributionProperties props) {
        require(props.claim().processingDeadline().compareTo(props.claim().lease()) < 0,
                "distribution.claim.lease", "the lease must be longer than the processing deadline");
        require(props.claim().lease().compareTo(props.servicebus().lockDuration()) < 0,
                "distribution.servicebus.lock-duration", "the lock must be longer than the lease");
        require(props.servicebus().maxAutoLockRenewDuration()
                        .compareTo(sum(props.claim().processingDeadline(), RENEW_MARGIN)) >= 0,
                "distribution.servicebus.max-auto-lock-renew-duration",
                "auto-renew must cover the deadline plus 30 seconds");
    }

    private void validateIdentity(final DistributionProperties props) {
        require(Objects.equals(props.results().systemUserId(), props.usersgroups().systemUserId()),
                "distribution.results.system-user-id",
                "must equal distribution.usersgroups.system-user-id (one system user, H30)");
    }

    private void validateYouthDefault(final DistributionProperties props) {
        require(VALID_DEFAULTS.contains(props.youthSwitch().defaultWhenUnreadable()),
                "distribution.youth-switch.default-when-unreadable",
                "must be ACTIVE or INACTIVE");
    }

    private void validateDeliveryLimit(final DistributionProperties props) {
        require(props.servicebus().maxDeliveryCount() == EXPECTED_MAX_DELIVERY_COUNT,
                "distribution.servicebus.max-delivery-count",
                "must equal the queue's configured maxDeliveryCount ("
                        + EXPECTED_MAX_DELIVERY_COUNT + ", design §4.2)");
    }

    /**
     * Refuses a resend-leg cap below 1 (slice 004, FR-004-019): {@code distribution.resend
     * .max-ids-per-run} bounds the explicit {@code requestId} list resend-leg takes per run, and a
     * run of zero or fewer ids is meaningless.
     *
     * @param props the bound configuration
     */
    private void validateResend(final DistributionProperties props) {
        require(props.resend().maxIdsPerRun() >= 1,
                "distribution.resend.max-ids-per-run", "must be at least 1");
    }

    private void validateSecrets(final DistributionProperties props) {
        require(isNotBlank(props.results().baseUrl()),
                "distribution.results.base-url", "is required");
        require(isNotBlank(props.usersgroups().baseUrl()),
                "distribution.usersgroups.base-url", "is required");
        require(isNotBlank(props.results().systemUserId()),
                "distribution.results.system-user-id", "is required");
        require(props.hmpps().url() != null && props.hmpps().url().startsWith("https://"),
                "distribution.hmpps.url", "is required and must be https");
    }

    private static void requireNotForbidden(final String name, final Object value) {
        final String lowerName = name.toLowerCase(Locale.ROOT);
        final boolean forbiddenName = lowerName.startsWith("distribution.vep.")
                || lowerName.contains("contract-validation")
                || lowerName.startsWith("distribution.feature.")
                || lowerName.contains("appconfiguration")
                || lowerName.contains("app-configuration");
        final boolean forbiddenValue = value instanceof String text
                && text.toLowerCase(Locale.ROOT).contains("azconfig.io");
        if (forbiddenName || forbiddenValue) {
            throw new IllegalStateException("Forbidden configuration property present: " + name);
        }
    }

    private static void require(final boolean condition, final String property,
                                final String detail) {
        if (!condition) {
            throw new IllegalStateException(
                    "Invalid configuration for " + property + ": " + detail);
        }
    }

    private static boolean fits(final Duration composed, final Duration budget) {
        return composed.compareTo(budget) <= 0;
    }

    private static boolean isNotBlank(final String value) {
        return value != null && !value.isBlank();
    }

    private static Duration sum(final Duration... durations) {
        Duration total = Duration.ZERO;
        for (final Duration duration : durations) {
            total = total.plus(duration);
        }
        return total;
    }
}
