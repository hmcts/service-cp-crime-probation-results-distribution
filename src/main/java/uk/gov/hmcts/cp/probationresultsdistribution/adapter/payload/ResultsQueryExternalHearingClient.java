package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.StageBudget;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;

/**
 * The results-query payload fallback (design §10, FR-002-017, DV-10, DV-17, DV-23). It is the third
 * source of the payload-fetch chain, read only when both cache keys miss. It GETs the hearing
 * details for the command, as the command's {@code userId} when present and the service's own system
 * user otherwise ({@code CJSCPPUID}, H30), and applies the results retry block: 408, 429, every
 * status from 500, and connect and read failures are retried with a doubling back-off honouring a
 * delta-seconds {@code Retry-After} up to the cap, bounded by the payload stage budget. A 2xx with an
 * empty body, {@code {}} or non-JSON is a miss; a non-retryable non-2xx (404 and the like) is a miss
 * at once. The fallback payload form {@code {hearing, sharedTime}} is passed through unexpanded.
 */
public final class ResultsQueryExternalHearingClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();
    private static final String ACCEPT = "Accept";
    private static final String ACCEPT_VALUE =
            "application/vnd.results.hearing-information-details+json";
    private static final String USER_HEADER = "CJSCPPUID";
    private static final String RETRY_AFTER = "Retry-After";
    private static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/";
    private static final int HTTP_OK = 200;
    private static final int HTTP_MULTIPLE_CHOICES = 300;
    private static final int HTTP_REQUEST_TIMEOUT = 408;
    private static final int HTTP_TOO_MANY_REQUESTS = 429;
    private static final int HTTP_INTERNAL_SERVER_ERROR = 500;

    private final RestClient restClient;
    private final String baseUrl;
    private final String systemUserId;
    private final Duration connectTimeout;
    private final Duration readTimeout;
    private final int maxAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;

    /**
     * Creates the client over the results base URL, the system user and the retry budget.
     *
     * @param baseUrl        the results base URL
     * @param systemUserId   the service's own system user, sent as {@code CJSCPPUID} when the command
     *                       carries no {@code userId}
     * @param connectTimeout the connect timeout (5 s)
     * @param readTimeout    the read timeout (30 s)
     * @param maxAttempts    the maximum number of attempts (default 4)
     * @param initialBackoff the first back-off (500 ms)
     * @param maxBackoff     the back-off cap (20 s)
     */
    // The HttpClient is a long-lived resource, held by the RestClient for the client's whole lifetime
    // and used on every read; it is deliberately not closed in this constructor.
    @SuppressWarnings("PMD.CloseResource")
    public ResultsQueryExternalHearingClient(final String baseUrl, final String systemUserId,
                                             final Duration connectTimeout, final Duration readTimeout,
                                             final int maxAttempts, final Duration initialBackoff,
                                             final Duration maxBackoff) {
        this.baseUrl = baseUrl;
        this.systemUserId = systemUserId;
        this.connectTimeout = connectTimeout;
        this.readTimeout = readTimeout;
        this.maxAttempts = maxAttempts;
        this.initialBackoff = initialBackoff;
        this.maxBackoff = maxBackoff;
        final HttpClient httpClient = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(connectTimeout)
                .build();
        final JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(readTimeout);
        this.restClient = RestClient.builder().requestFactory(factory).build();
    }

    /**
     * Reads the hearing payload from the results query API, retrying the retryable answers within the
     * payload stage budget.
     *
     * @param command the admitted command
     * @param budget  the payload stage budget the retries run within
     * @return the fetched payload with {@code payload_source = query}, or empty when every answer
     *     misses or the stage budget runs out
     */
    public Optional<FetchedPayload> fetch(final DistributionCommand command,
                                          final StageBudget budget) {
        final String url = baseUrl + PATH + command.hearingId()
                + "?hearingDate=" + command.hearingDay();
        final String user = command.userId().map(java.util.UUID::toString).orElse(systemUserId);
        Duration backoff = initialBackoff;
        Optional<FetchedPayload> result = Optional.empty();
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            if (!attemptFits(budget, Duration.ZERO)) {
                break;
            }
            final Attempt outcome = tryOnce(url, user);
            if (outcome instanceof Attempt.Hit hit) {
                result = Optional.of(hit.payload());
                break;
            }
            if (outcome instanceof Attempt.Miss) {
                break;
            }
            final Duration wait = waitFor(((Attempt.Retryable) outcome).retryAfterSeconds(), backoff);
            if (attempt == maxAttempts || !attemptFits(budget, wait)) {
                break;
            }
            sleep(wait);
            backoff = doubled(backoff);
        }
        return result;
    }

    private boolean attemptFits(final StageBudget budget, final Duration wait) {
        return budget.attemptFits(Instant.now(), wait.plus(connectTimeout).plus(readTimeout));
    }

    private Duration waitFor(final OptionalLong retryAfterSeconds, final Duration backoff) {
        final Duration computed = backoff.compareTo(maxBackoff) > 0 ? maxBackoff : backoff;
        return retryAfterSeconds.isPresent()
                ? cap(Duration.ofSeconds(retryAfterSeconds.getAsLong()))
                : computed;
    }

    private Duration cap(final Duration wait) {
        return wait.compareTo(maxBackoff) > 0 ? maxBackoff : wait;
    }

    private Duration doubled(final Duration backoff) {
        final Duration next = backoff.multipliedBy(2);
        return next.compareTo(maxBackoff) > 0 ? maxBackoff : next;
    }

    private Attempt tryOnce(final String url, final String user) {
        Attempt attempt;
        try {
            attempt = restClient.get().uri(url)
                    .header(ACCEPT, ACCEPT_VALUE)
                    .header(USER_HEADER, user)
                    .exchange((request, response) -> classify(
                            response.getStatusCode().value(),
                            response.getHeaders().getFirst(RETRY_AFTER),
                            response.getBody()));
        } catch (final ResourceAccessException transportFailure) {
            // A connect or read failure never reached results with an answer: it is retried (DV-23).
            // Not swallowed — mapped to a retryable attempt with no Retry-After.
            attempt = new Attempt.Retryable(OptionalLong.empty());
        }
        return attempt;
    }

    private static Attempt classify(final int status, final String retryAfter, final InputStream body)
            throws IOException {
        final Attempt attempt;
        if (status >= HTTP_OK && status < HTTP_MULTIPLE_CHOICES) {
            attempt = classifyBody(body);
        } else if (status == HTTP_REQUEST_TIMEOUT || status == HTTP_TOO_MANY_REQUESTS
                || status >= HTTP_INTERNAL_SERVER_ERROR) {
            attempt = new Attempt.Retryable(retryAfterSeconds(retryAfter));
        } else {
            // Any other non-2xx (404 and the like) is not retried: it is a miss at once (DV-23).
            attempt = new Attempt.Miss();
        }
        return attempt;
    }

    private static Attempt classifyBody(final InputStream body) throws IOException {
        Attempt attempt;
        try {
            final JsonNode tree = MAPPER.readTree(body);
            if (tree == null || !tree.isObject() || tree.isEmpty()) {
                // An empty body, {} or a non-object 2xx is a miss (DV-10).
                attempt = new Attempt.Miss();
            } else {
                attempt = new Attempt.Hit(
                        new FetchedPayload(tree, PayloadSource.QUERY, SharedTimes.parse(tree)));
            }
        } catch (final JacksonException unparseable) {
            // A 2xx that is not JSON is a miss (DV-10). Not swallowed: mapped to the explicit miss.
            attempt = new Attempt.Miss();
        }
        return attempt;
    }

    private static OptionalLong retryAfterSeconds(final String header) {
        OptionalLong seconds = OptionalLong.empty();
        if (header != null) {
            try {
                seconds = OptionalLong.of(Long.parseLong(header.trim()));
            } catch (final NumberFormatException notDeltaSeconds) {
                // A non-numeric Retry-After (an HTTP-date form) is ignored in favour of the computed
                // back-off (FR-002-017). Not swallowed: mapped to the empty default.
                seconds = OptionalLong.empty();
            }
        }
        return seconds;
    }

    private static void sleep(final Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off the results query",
                    interrupted);
        }
    }

    /** The classified result of one attempt. */
    private sealed interface Attempt permits Attempt.Hit, Attempt.Miss, Attempt.Retryable {

        /** A usable payload was read. */
        record Hit(FetchedPayload payload) implements Attempt {
        }

        /** A non-retryable answer: the fetch gives up on the query source. */
        record Miss() implements Attempt {
        }

        /** A retryable answer, with an optional delta-seconds {@code Retry-After}. */
        record Retryable(OptionalLong retryAfterSeconds) implements Attempt {
        }
    }
}
