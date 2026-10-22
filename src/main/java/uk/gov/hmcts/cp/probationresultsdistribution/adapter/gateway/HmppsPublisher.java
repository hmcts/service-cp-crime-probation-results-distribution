package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.OptionalInt;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;

/**
 * The HMPPS gateway adapter (design §9, FR-002-032 to FR-002-039). It serialises the redacted tree
 * once to UTF-8, POSTs those exact bytes to the APIM HMPPS route with {@code Content-Type:
 * application/json} and {@code Ocp-Apim-Subscription-Key}, follows no redirect, and maps the gateway
 * answer to a {@link PublishOutcome} without ever throwing past its outcome. It carries the exact
 * text it sent back to the runner, which digests and stores the same bytes.
 *
 * <p>This slice (US1) performs one POST with no in-run retry; the connection-failure and 429 retry
 * loop is US5's. The outcome mapping is complete so the publisher never throws: any 2xx is
 * {@code Sent}; a 4xx other than 408 and 429 is {@code FailedNonTransient}; a 5xx, a 408, a 429, a
 * 3xx, any other status and a transport failure are {@code TransientExhausted}.
 */
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

    /**
     * Creates the publisher over the HMPPS route and its subscription key.
     *
     * @param url             the full HMPPS route URL
     * @param subscriptionKey the {@code Ocp-Apim-Subscription-Key} value
     * @param connectTimeout  the connect timeout (H27: 5 s)
     * @param readTimeout     the read timeout (H27: 60 s)
     */
    // The HttpClient is a long-lived resource, held by the RestClient for the publisher's whole
    // lifetime and used on every POST; it is deliberately not closed in this constructor.
    @SuppressWarnings("PMD.CloseResource")
    public HmppsPublisher(final String url, final String subscriptionKey,
                          final Duration connectTimeout, final Duration readTimeout) {
        this.url = url;
        this.subscriptionKey = subscriptionKey;
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

    private PublishOutcome post(final String body) {
        PublishOutcome outcome;
        try {
            outcome = restClient.post().uri(url)
                    .header(CONTENT_TYPE_HEADER, JSON)
                    .header(SUBSCRIPTION_KEY_HEADER, subscriptionKey)
                    .body(body)
                    .exchange((request, response) -> mapStatus(response.getStatusCode().value()));
        } catch (final ResourceAccessException transportFailure) {
            // A connection failure, connect timeout, read timeout or I/O failure: the outcome at
            // HMPPS is unknown. Not swallowed — mapped to the park-at-once outcome the runner settles.
            outcome = new PublishOutcome.TransientExhausted(OptionalInt.empty());
        }
        return outcome;
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
