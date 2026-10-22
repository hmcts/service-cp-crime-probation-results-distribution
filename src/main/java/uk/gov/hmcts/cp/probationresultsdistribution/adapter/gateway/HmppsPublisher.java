package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;

import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpConnectTimeoutException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.StageBudget;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;

/**
 * The HMPPS gateway adapter (design §9, FR-002-032 to FR-002-039). It serialises the redacted tree
 * once to UTF-8, POSTs those exact bytes to the APIM HMPPS route with {@code Content-Type:
 * application/json} and {@code Ocp-Apim-Subscription-Key}, follows no redirect, and maps the gateway
 * answer to a {@link PublishOutcome} without ever throwing past its outcome. It carries the exact
 * text it sent back to the runner, which digests and stores the same bytes.
 *
 * <p>The publisher retries only never-sent connection failures and 429 responses, subject to the
 * configured stage budget. It never retries an outcome-unknown response. Any 2xx is
 * {@code Sent}; a 4xx other than 408 and 429 is {@code FailedNonTransient}; a 5xx, a 408, a 429, a
 * 3xx, any other status and a transport failure are {@code TransientExhausted}.
 */
@SuppressWarnings("PMD.OnlyOneReturn")
public final class HmppsPublisher implements HmppsSender {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String CONTENT_TYPE_HEADER = "Content-Type";
    private static final String JSON = "application/json";
    private static final String SUBSCRIPTION_KEY_HEADER = "Ocp-Apim-Subscription-Key";

    private static final int HTTP_OK = 200;
    private static final int HTTP_MULTIPLE_CHOICES = 300;
    private static final int HTTP_BAD_REQUEST = 400;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;

    private final RestClient restClient;
    private final String url;
    private final String subscriptionKey;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration stageDuration;
    private final Duration connectTimeout;
    private final Duration readTimeout;

    /**
     * Creates the publisher over the HMPPS route and its subscription key.
     *
     * @param url             the full HMPPS route URL
     * @param subscriptionKey the {@code Ocp-Apim-Subscription-Key} value
     * @param connectTimeout  the connect timeout (H27: 5 s)
     * @param readTimeout     the read timeout (H27: 60 s)
     */
    public HmppsPublisher(final String url, final String subscriptionKey,
                          final Duration connectTimeout, final Duration readTimeout) {
        this(url, subscriptionKey, connectTimeout, readTimeout, 3,
                Duration.ofMillis(500), Duration.ofSeconds(10), Duration.ofSeconds(130));
    }

    /** Creates the publisher with its retry and send-stage limits. */
    @SuppressWarnings("PMD.CloseResource")
    public HmppsPublisher(final String url, final String subscriptionKey,
                          final Duration connectTimeout, final Duration readTimeout,
                          final int maxAttempts, final Duration initialBackoff,
                          final Duration maxBackoff, final Duration stageDuration) {
        this.url = url;
        this.subscriptionKey = subscriptionKey;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        this.stageDuration = stageDuration;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        final HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    @Override
    public SentBody send(final JsonNode redactedBody) {
        final String body = MAPPER.writeValueAsString(redactedBody);
        return new SentBody(post(body), body);
    }

    @SuppressWarnings({"PMD.AvoidInstantiatingObjectsInLoops",
        "PMD.AvoidBranchingStatementAsLastInLoop"})
    private PublishOutcome post(final String body) {
        final StageBudget budget = StageBudget.startingNow(stageDuration);
        Duration computedBackoff = initialBackoff;
        boolean recovered429 = false;
        OptionalInt lastStatus = OptionalInt.empty();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            Response response;
            try {
                response = restClient.post().uri(url)
                    .header(CONTENT_TYPE_HEADER, JSON)
                    .header(SUBSCRIPTION_KEY_HEADER, subscriptionKey)
                    .body(body)
                    .exchange((request, received) -> new Response(
                            received.getStatusCode().value(),
                            received.getHeaders().getFirst("Retry-After")));
            } catch (final ResourceAccessException transportFailure) {
                if (!isNeverSentConnectionFailure(transportFailure) || attempt == maxAttempts) {
                    return new PublishOutcome.TransientExhausted(lastStatus);
                }
                if (!waitAndFit(budget, computedBackoff)) {
                    return new PublishOutcome.TransientExhausted(lastStatus);
                }
                computedBackoff = nextWait(computedBackoff);
                continue;
            }
            final PublishOutcome outcome = mapStatus(response.status());
            if (outcome instanceof PublishOutcome.Sent sent) {
                final Optional<GatewayRecovery> recovery = recovered429
                        ? Optional.of(GatewayRecovery.IN_RUN_429) : Optional.empty();
                return new PublishOutcome.Sent(sent.httpStatus(), recovery);
            }
            if (outcome instanceof PublishOutcome.TransientExhausted transientOutcome) {
                lastStatus = transientOutcome.httpStatus();
                if (lastStatus.orElse(-1) != HTTP_TOO_MANY_REQUESTS || attempt == maxAttempts) {
                    return outcome;
                }
                recovered429 = true;
                final Duration retryWait = retryAfterMillis(response.retryAfter(), computedBackoff);
                if (!waitAndFit(budget, retryWait)) {
                    return outcome;
                }
                computedBackoff = nextWait(computedBackoff);
                continue;
            }
            return outcome;
        }
        return new PublishOutcome.TransientExhausted(lastStatus);
    }

    private record Response(int status, String retryAfter) {}

    private boolean waitAndFit(final StageBudget budget, final Duration wait) {
        final Duration worstCase = wait.plus(connectTimeout).plus(readTimeout);
        if (!budget.attemptFits(Instant.now(), worstCase)) {
            return false;
        }
        try {
            TimeUnit.NANOSECONDS.sleep(wait.toNanos());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return budget.attemptFits(Instant.now(), connectTimeout.plus(readTimeout));
    }

    private Duration nextWait(final Duration current) {
        final Duration doubled = current.multipliedBy(2);
        return doubled.compareTo(maxBackoff) > 0 ? maxBackoff : doubled;
    }

    private Duration retryAfterMillis(final String retryAfter, final Duration fallback) {
        if (retryAfter == null || !retryAfter.matches("[0-9]+")) {
            return fallback;
        }
        try {
            final long seconds = Long.parseLong(retryAfter);
            final Duration requested = Duration.ofSeconds(Math.max(0, seconds));
            return requested.compareTo(maxBackoff) > 0 ? maxBackoff : requested;
        } catch (final NumberFormatException invalid) {
            return fallback;
        }
    }

    private static boolean isNeverSentConnectionFailure(final Throwable error) {
        Throwable cause = error;
        while (cause != null) {
            if (cause instanceof HttpConnectTimeoutException || cause instanceof ConnectException
                    || cause instanceof UnknownHostException || cause instanceof NoRouteToHostException) {
                return true;
            }
            cause = cause.getCause();
        }
        return false;
    }

    private static PublishOutcome mapStatus(final int status) {
        final PublishOutcome outcome;
        if (status >= HTTP_OK && status < HTTP_MULTIPLE_CHOICES) {
            outcome = new PublishOutcome.Sent(status);
        } else if (status >= HTTP_BAD_REQUEST && status < HTTP_INTERNAL_SERVER_ERROR
                && status != HTTP_REQUEST_TIMEOUT && status != HTTP_TOO_MANY_REQUESTS) {
            outcome = new PublishOutcome.FailedNonTransient(status);
        } else {
            outcome = new PublishOutcome.TransientExhausted(OptionalInt.of(status));
        }
        return outcome;
    }
}
