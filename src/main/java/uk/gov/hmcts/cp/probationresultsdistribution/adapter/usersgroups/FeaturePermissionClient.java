package uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups;

import java.io.InputStream;
import java.net.SocketTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.OutcomeMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.application.YouthFilterSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

/**
 * The users-groups youth-switch adapter (design H7, FR-002-021, FR-002-022). It reads the
 * {@code EMYouthFilter} permission once per message, as the service's own system user, and decides by
 * {@code permissions[0]} with the legacy's JavaScript truthiness: a truthy {@code active} is
 * {@code ACTIVE}; a non-null first entry whose {@code active} is falsy or absent — a non-object
 * included — is {@code INACTIVE}; an empty/missing/null first entry is
 * {@code UNREADABLE(EMPTY_PERMISSIONS)}, and an unparseable body is {@code UNREADABLE(UNPARSEABLE)}.
 *
 * <p>The read keeps the legacy retry predicate: retry no-response failures and statuses above 429,
 * up to the configured maximum, with a fixed delay and no {@code Retry-After}. A timeout remains
 * distinguishable from other no-response failures after the last attempt.
 */
public final class FeaturePermissionClient implements YouthFilterSource {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ACCEPT = "Accept";
    private static final String ACCEPT_VALUE =
            "application/vnd.usersgroups.get-permissions-by-feature-key+json";
    private static final String USER_HEADER = "CJSCPPUID";
    private static final String PERMISSIONS_PATH =
            "/usersgroups-query-api/query/api/rest/usersgroups/permissions";
    private static final String ACTIVE = "active";
    private static final String PERMISSIONS = "permissions";
    private static final int HTTP_OK = 200;
    private static final int HTTP_MULTIPLE_CHOICES = 300;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int MAX_RETRY_STATUS = HTTP_TOO_MANY_REQUESTS;
    private static final int ONE_PERMISSION = 1;

    private final RestClient restClient;
    private final String url;
    private final String systemUserId;
    private final int maxAttempts;
    private final Duration retryWait;
    private final OutcomeMetrics metrics;

    /**
     * Creates the client over the users-groups base URL and the system user.
     *
     * @param baseUrl        the users-groups base URL
     * @param systemUserId   the service's own system user, sent as {@code CJSCPPUID}
     * @param youthFilterKey the feature key (default {@code EMYouthFilter})
     * @param connectTimeout the connect timeout (DV-24: 2 s)
     * @param readTimeout    the read timeout (DV-24: 5 s)
     * @param maxAttempts    maximum reads (3)
     * @param retryWait      fixed delay between retryable answers (1 s)
     * @param metrics        bounded outcome metrics, including ambiguous responses
     */
    // The HttpClient is a long-lived resource, held by the RestClient for the client's whole
    // lifetime and used on every read; it is deliberately not closed in this constructor.
    @SuppressWarnings("PMD.CloseResource")
    public FeaturePermissionClient(final String baseUrl, final String systemUserId,
                                   final String youthFilterKey, final Duration connectTimeout,
                                   final Duration readTimeout, final int maxAttempts,
                                   final Duration retryWait, final OutcomeMetrics metrics) {
        this.systemUserId = systemUserId;
        this.url = baseUrl + PERMISSIONS_PATH + "?key=" + youthFilterKey;
        this.maxAttempts = maxAttempts;
        this.retryWait = retryWait;
        this.metrics = metrics;
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public YouthSwitchDecision read(final DistributionCommand command) {
        YouthSwitchDecision decision = unreadable(YouthSwitchUnreadableCause.HTTP_FAILURE);
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                final ResponseDecision response = restClient.get().uri(url)
                        .header(ACCEPT, ACCEPT_VALUE)
                        .header(USER_HEADER, systemUserId)
                        .exchange((request, answer) -> decide(answer.getStatusCode().value(),
                                answer.getBody()));
                decision = response.decision();
                if (response.status() > MAX_RETRY_STATUS && attempt < maxAttempts) {
                    if (pauseBeforeRetry()) {
                        continue;
                    }
                }
                break;
            } catch (final ResourceAccessException transportFailure) {
                final boolean timedOut = isTimeout(transportFailure);
                if (attempt < maxAttempts && pauseBeforeRetry()) {
                    continue;
                }
                decision = unreadable(timedOut
                        ? YouthSwitchUnreadableCause.TIMEOUT
                        : YouthSwitchUnreadableCause.HTTP_FAILURE);
            }
        }
        return decision;
    }

    private ResponseDecision decide(final int status, final InputStream body) {
        final YouthSwitchDecision decision;
        if (status < HTTP_OK || status >= HTTP_MULTIPLE_CHOICES) {
            decision = unreadable(YouthSwitchUnreadableCause.HTTP_FAILURE);
        } else {
            decision = decideFromBody(body);
        }
        return new ResponseDecision(status, decision);
    }

    private YouthSwitchDecision decideFromBody(final InputStream body) {
        YouthSwitchDecision decision;
        try {
            final JsonNode root = MAPPER.readTree(body);
            if (root == null || root.isMissingNode()) {
                decision = new YouthSwitchDecision.Unreadable(
                        YouthSwitchUnreadableCause.UNPARSEABLE);
            } else {
                decision = decideFromTree(root);
            }
        } catch (final JacksonException unparseable) {
            decision = new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.UNPARSEABLE);
        }
        return decision;
    }

    private YouthSwitchDecision decideFromTree(final JsonNode root) {
        final JsonNode permissions = root.get(PERMISSIONS);
        final YouthSwitchDecision decision;
        if (permissions == null || !permissions.isArray() || permissions.isEmpty()) {
            decision = new YouthSwitchDecision.Unreadable(
                    YouthSwitchUnreadableCause.EMPTY_PERMISSIONS);
        } else {
            if (permissions.size() > ONE_PERMISSION) {
                metrics.youthSwitchAmbiguous();
            }
            decision = decideFromFirst(permissions.get(0));
        }
        return decision;
    }

    private static YouthSwitchDecision decideFromFirst(final JsonNode first) {
        final YouthSwitchDecision decision;
        if (first == null || first.isNull()) {
            decision = new YouthSwitchDecision.Unreadable(
                    YouthSwitchUnreadableCause.EMPTY_PERMISSIONS);
        } else if (first.isObject() && truthy(first.get(ACTIVE))) {
            decision = new YouthSwitchDecision.Active();
        } else {
            // A non-object first entry, or an object whose active is falsy or absent, is INACTIVE.
            decision = new YouthSwitchDecision.Inactive();
        }
        return decision;
    }

    private static boolean truthy(final JsonNode value) {
        final boolean result;
        if (value == null || value.isNull() || value.isMissingNode()) {
            result = false;
        } else if (value.isBoolean()) {
            result = value.booleanValue();
        } else if (value.isNumber()) {
            result = value.doubleValue() != 0.0d;
        } else if (value.isString()) {
            result = !value.stringValue().isEmpty();
        } else {
            result = true;
        }
        return result;
    }

    private boolean pauseBeforeRetry() {
        boolean paused = true;
        try {
            Thread.sleep(retryWait.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            paused = false;
        }
        return paused;
    }

    private static boolean isTimeout(final Throwable failure) {
        Throwable current = failure;
        boolean timeout = false;
        while (current != null && !timeout) {
            timeout = current instanceof HttpTimeoutException
                    || current instanceof SocketTimeoutException;
            current = current.getCause();
        }
        return timeout;
    }

    private static YouthSwitchDecision unreadable(final YouthSwitchUnreadableCause cause) {
        return new YouthSwitchDecision.Unreadable(cause);
    }

    private record ResponseDecision(int status, YouthSwitchDecision decision) {
    }
}
