package uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
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
 * <p>This slice (US1) implements the happy-path 2xx decision. The legacy retry rule (3 attempts,
 * fixed 1 s wait), the finite-timeout {@code TIMEOUT} cause, and the ambiguity counter are US4's
 * (T069); this adapter reads once and maps a non-2xx answer or a transport failure to
 * {@code UNREADABLE(HTTP_FAILURE)}.
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

    private final RestClient restClient;
    private final String url;
    private final String systemUserId;

    /**
     * Creates the client over the users-groups base URL and the system user.
     *
     * @param baseUrl        the users-groups base URL
     * @param systemUserId   the service's own system user, sent as {@code CJSCPPUID}
     * @param youthFilterKey the feature key (default {@code EMYouthFilter})
     * @param connectTimeout the connect timeout (DV-24: 2 s)
     * @param readTimeout    the read timeout (DV-24: 5 s)
     */
    // The HttpClient is a long-lived resource, held by the RestClient for the client's whole
    // lifetime and used on every read; it is deliberately not closed in this constructor.
    @SuppressWarnings("PMD.CloseResource")
    public FeaturePermissionClient(final String baseUrl, final String systemUserId,
                                   final String youthFilterKey, final Duration connectTimeout,
                                   final Duration readTimeout) {
        this.systemUserId = systemUserId;
        this.url = baseUrl + PERMISSIONS_PATH + "?key=" + youthFilterKey;
        final HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public YouthSwitchDecision read(final DistributionCommand command) {
        YouthSwitchDecision decision;
        try {
            decision = restClient.get().uri(url)
                    .header(ACCEPT, ACCEPT_VALUE)
                    .header(USER_HEADER, systemUserId)
                    .exchange((request, response) ->
                            decide(response.getStatusCode().value(), response.getBody()));
        } catch (final ResourceAccessException transportFailure) {
            // A connection failure or timeout: not swallowed — mapped to the unreadable outcome the
            // leg defaults and carries on from (H8). The finite-timeout TIMEOUT cause is US4's.
            decision = new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.HTTP_FAILURE);
        }
        return decision;
    }

    private static YouthSwitchDecision decide(final int status, final InputStream body)
            throws IOException {
        final YouthSwitchDecision decision;
        if (status < HTTP_OK || status >= HTTP_MULTIPLE_CHOICES) {
            decision = new YouthSwitchDecision.Unreadable(YouthSwitchUnreadableCause.HTTP_FAILURE);
        } else {
            decision = decideFromBody(body);
        }
        return decision;
    }

    private static YouthSwitchDecision decideFromBody(final InputStream body) throws IOException {
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

    private static YouthSwitchDecision decideFromTree(final JsonNode root) {
        final JsonNode permissions = root.get(PERMISSIONS);
        final YouthSwitchDecision decision;
        if (permissions == null || !permissions.isArray() || permissions.isEmpty()) {
            decision = new YouthSwitchDecision.Unreadable(
                    YouthSwitchUnreadableCause.EMPTY_PERMISSIONS);
        } else {
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
}
