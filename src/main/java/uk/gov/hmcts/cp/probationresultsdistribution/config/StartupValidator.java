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
    private static final int MAX_TCP_PORT = 65_535;
    private static final String IS_REQUIRED = "is required";
    private static final String IS_REQUIRED_OUTSIDE_LOCAL_AND_TEST =
            "is required outside the local and test profiles";
    private static final String MUST_BE_AT_LEAST_ONE = "must be at least 1";
    private static final String WORKLOAD_IDENTITY_REQUIRED =
            "is required for workload identity outside the local and test profiles";

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
        validateAttemptCounts(props);
        validateResend(props);
        if (requireSecrets) {
            validateSecrets(props);
        }
    }

    /**
     * Refuses neither or both Service Bus credential sources (design §8.1, application.yaml's own
     * comment on {@code distribution.servicebus.*}): exactly one of
     * {@code distribution.servicebus.connection-string} (local and CI) or
     * {@code distribution.servicebus.namespace} (deployed, workload identity) may be set.
     * {@link uk.gov.hmcts.cp.probationresultsdistribution.config.PipelineClients#serviceBus} silently
     * prefers the connection string when both are set, so this is the only refusal either mistake
     * gets. Neither set is accepted only when secrets are not required (the {@code test} profile,
     * where messaging is never used at all).
     *
     * @param connectionString {@code distribution.servicebus.connection-string}, blank for none
     * @param namespace        {@code distribution.servicebus.namespace}, blank for none
     * @param requireSecrets   whether required secrets must be present
     */
    public void validateServiceBusCredential(final String connectionString, final String namespace,
                                             final boolean requireSecrets) {
        final boolean hasConnectionString = isNotBlank(connectionString);
        final boolean hasNamespace = isNotBlank(namespace);
        if (!requireSecrets && !hasConnectionString && !hasNamespace) {
            return;
        }
        require(hasConnectionString ^ hasNamespace, "distribution.servicebus.connection-string",
                "exactly one of distribution.servicebus.connection-string or "
                        + "distribution.servicebus.namespace must be set, never neither or both");
    }

    /**
     * Refuses a deployed profile's namespace credential without the workload identity environment
     * {@code azure-identity} itself reads (review of PR #8): without
     * {@code AZURE_CLIENT_ID}/{@code AZURE_TENANT_ID}/{@code AZURE_FEDERATED_TOKEN_FILE}, a
     * {@code DefaultAzureCredential} built on the namespace path fails only on first use — the first
     * queue connection, well after the pod is already reporting ready — rather than at startup.
     *
     * @param namespace      {@code distribution.servicebus.namespace}, blank for none
     * @param environment    the application environment
     * @param requireSecrets whether required secrets must be present
     */
    public void validateWorkloadIdentity(final String namespace, final Environment environment,
                                         final boolean requireSecrets) {
        if (requireSecrets && isNotBlank(namespace)) {
            require(isNotBlank(environment.getProperty("AZURE_CLIENT_ID")), "AZURE_CLIENT_ID",
                    WORKLOAD_IDENTITY_REQUIRED);
            require(isNotBlank(environment.getProperty("AZURE_TENANT_ID")), "AZURE_TENANT_ID",
                    WORKLOAD_IDENTITY_REQUIRED);
            require(isNotBlank(environment.getProperty("AZURE_FEDERATED_TOKEN_FILE")),
                    "AZURE_FEDERATED_TOKEN_FILE", WORKLOAD_IDENTITY_REQUIRED);
        }
    }

    /**
     * Refuses a blank Redis host always, and a blank Redis key where secrets are required (outside
     * the {@code local} and {@code test} profiles): the payload cache is a required dependency in
     * every profile, and its credential — absent only because the local compose Redis runs with no
     * password — arrives from Key Vault everywhere else.
     *
     * @param host           {@code distribution.redis.host}
     * @param key            {@code distribution.redis.key}, blank for none
     * @param requireSecrets whether required secrets must be present
     */
    public void validateRedisRequired(final String host, final String key,
                                      final boolean requireSecrets) {
        require(isNotBlank(host), "distribution.redis.host", IS_REQUIRED);
        if (requireSecrets) {
            require(isNotBlank(key), "distribution.redis.key", IS_REQUIRED_OUTSIDE_LOCAL_AND_TEST);
        }
    }

    /**
     * Refuses a Redis port outside the valid TCP range.
     *
     * @param port {@code distribution.redis.port}
     */
    public void validateRedisPort(final int port) {
        require(port >= 1 && port <= MAX_TCP_PORT, "distribution.redis.port",
                "must be a valid TCP port (1-" + MAX_TCP_PORT + ")");
    }

    /**
     * Refuses a Service Bus concurrency below 1 — the processor's own
     * {@code maxConcurrentCalls(0)} would never dispatch a delivery at all.
     *
     * @param maxConcurrentCalls {@code distribution.servicebus.max-concurrent-calls}
     */
    public void validateConcurrency(final int maxConcurrentCalls) {
        require(maxConcurrentCalls >= 1, "distribution.servicebus.max-concurrent-calls",
                MUST_BE_AT_LEAST_ONE);
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
                    IS_REQUIRED_OUTSIDE_LOCAL_AND_TEST);
        }
    }

    /**
     * Refuses Redis TLS or peer verification switched off where secrets are required (outside the
     * {@code local} and {@code test} profiles): an unverified or plaintext connection to the payload
     * cache is an interception risk (FR-002-016, DV-14).
     *
     * @param ssl            {@code distribution.redis.ssl}
     * @param verifyPeer     {@code distribution.redis.verify-peer}
     * @param requireSecrets whether required secrets must be present
     */
    public void validateRedisTls(final boolean ssl, final boolean verifyPeer,
                                 final boolean requireSecrets) {
        if (requireSecrets) {
            require(ssl, "distribution.redis.ssl", "must be true outside the local and test profiles");
            require(verifyPeer, "distribution.redis.verify-peer",
                    "must be true outside the local and test profiles");
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
     * Refuses a client attempt count below 1: zero attempts means the client never actually tries
     * its call.
     */
    private void validateAttemptCounts(final DistributionProperties props) {
        require(props.gateway().maxAttempts() >= 1, "distribution.gateway.max-attempts",
                MUST_BE_AT_LEAST_ONE);
        require(props.results().maxAttempts() >= 1, "distribution.results.max-attempts",
                MUST_BE_AT_LEAST_ONE);
        require(props.usersgroups().maxAttempts() >= 1, "distribution.usersgroups.max-attempts",
                MUST_BE_AT_LEAST_ONE);
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
                "distribution.resend.max-ids-per-run", MUST_BE_AT_LEAST_ONE);
    }

    private void validateSecrets(final DistributionProperties props) {
        require(isNotBlank(props.results().baseUrl()), "distribution.results.base-url", IS_REQUIRED);
        require(isNotBlank(props.usersgroups().baseUrl()), "distribution.usersgroups.base-url",
                IS_REQUIRED);
        require(isNotBlank(props.results().systemUserId()), "distribution.results.system-user-id",
                IS_REQUIRED);
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
