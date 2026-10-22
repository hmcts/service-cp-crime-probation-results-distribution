package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender.SentBody;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.GatewayRecovery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PublishOutcome;

@DisplayName("the HMPPS gateway publisher")
class HmppsPublisherTest {

    private static final String KEY = "test-subscription-key";
    private static final String PATH = "/hmpps-route";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private WireMockServer gateway;
    private HmppsPublisher publisher;

    @BeforeEach
    void setUp() {
        gateway = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        gateway.start();
        publisher = new HmppsPublisher(gateway.baseUrl() + PATH, KEY,
                Duration.ofSeconds(5), Duration.ofSeconds(60));
    }

    @AfterEach
    void tearDown() {
        gateway.stop();
    }

    private JsonNode body() {
        return MAPPER.readTree("{\"hearing\":{\"id\":\"abc\"},\"cpsProsecutorIds\":[]}");
    }

    @Test
    @DisplayName("POSTs to the route with both required headers and no client certificate")
    void posts_with_required_headers() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        final SentBody result = publisher.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.Sent.class);
        gateway.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Ocp-Apim-Subscription-Key", equalTo(KEY))
                .withRequestBody(equalToJson(result.body())));
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    @DisplayName("maps a 200 to Sent with the status")
    void maps_200_to_sent() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        final SentBody result = publisher.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.Sent.class);
        assertThat(((PublishOutcome.Sent) result.outcome()).httpStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("maps a non-200 2xx to Sent with its actual status")
    void maps_202_to_sent() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(202)));

        final SentBody result = publisher.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.Sent.class);
        assertThat(((PublishOutcome.Sent) result.outcome()).httpStatus()).isEqualTo(202);
    }

    @Test
    @DisplayName("serialises once: the sent bytes hash to the digest of the stored text")
    void serialises_the_exact_bytes_once() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)));

        final SentBody result = publisher.send(body());

        final String sentBody = gateway.findAll(postRequestedFor(urlEqualTo(PATH)))
                .get(0).getBodyAsString();
        assertThat(sentBody).isEqualTo(result.body());
        assertThat(sha256(sentBody)).isEqualTo(sha256(result.body()));
    }

    @Test
    @DisplayName("maps a 400 to FailedNonTransient with the status, with no retry")
    void maps_400_to_failed_non_transient() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        final SentBody result = publisher.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.FailedNonTransient.class);
        assertThat(((PublishOutcome.FailedNonTransient) result.outcome()).httpStatus())
                .isEqualTo(400);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    @DisplayName("maps a 401 to FailedNonTransient (terminal, not parked)")
    void maps_401_to_failed_non_transient() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(401)));

        assertThat(publisher.send(body()).outcome())
                .isInstanceOf(PublishOutcome.FailedNonTransient.class);
    }

    @Test
    @DisplayName("maps a 5xx to TransientExhausted with the status")
    void maps_500_to_transient_exhausted() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        final SentBody result = publisher.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.TransientExhausted.class);
        assertThat(((PublishOutcome.TransientExhausted) result.outcome()).httpStatus()).hasValue(503);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    @DisplayName("maps a 408 to TransientExhausted")
    void maps_408_to_transient_exhausted() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(408)));

        assertThat(publisher.send(body()).outcome())
                .isInstanceOf(PublishOutcome.TransientExhausted.class);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    @DisplayName("does not retry a read timeout after the request reached the gateway")
    void does_not_retry_a_read_timeout_after_write() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withFixedDelay(500)));
        final HmppsPublisher shortReadTimeout = new HmppsPublisher(gateway.baseUrl() + PATH, KEY,
                Duration.ofSeconds(1), Duration.ofMillis(100), 3,
                Duration.ofMillis(5), Duration.ofMillis(50), Duration.ofSeconds(5));

        final SentBody result = shortReadTimeout.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.TransientExhausted.class);
        assertThat(((PublishOutcome.TransientExhausted) result.outcome()).httpStatus()).isEmpty();
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    @DisplayName("retries a 429 using Retry-After and reports the recovered send")
    void retries_429_and_marks_recovery() {
        final String retryScenario = "retry-after-rate-limit";
        gateway.stubFor(post(urlEqualTo(PATH)).inScenario(retryScenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("recovered"));
        gateway.stubFor(post(urlEqualTo(PATH)).inScenario(retryScenario)
                .whenScenarioStateIs("recovered").willReturn(aResponse().withStatus(200)));
        final HmppsPublisher retrying = new HmppsPublisher(gateway.baseUrl() + PATH, KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 3,
                Duration.ofMillis(5), Duration.ofMillis(50), Duration.ofSeconds(5));

        final PublishOutcome.Sent result = (PublishOutcome.Sent) retrying.send(body()).outcome();

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.recovery()).contains(GatewayRecovery.IN_RUN_429);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
    }

    @Test
    @DisplayName("honours a two-second Retry-After and marks a recovered 429")
    void honours_retry_after_two_seconds() {
        final String retryScenario = "retry-after-two-seconds";
        gateway.stubFor(post(urlEqualTo(PATH)).inScenario(retryScenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "2"))
                .willSetStateTo("recovered"));
        gateway.stubFor(post(urlEqualTo(PATH)).inScenario(retryScenario)
                .whenScenarioStateIs("recovered").willReturn(aResponse().withStatus(200)));
        final HmppsPublisher retrying = new HmppsPublisher(gateway.baseUrl() + PATH, KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 3,
                Duration.ofMillis(5), Duration.ofSeconds(10), Duration.ofSeconds(10));
        final long started = System.nanoTime();

        final PublishOutcome.Sent result = (PublishOutcome.Sent) retrying.send(body()).outcome();

        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isBetween(1_900L, 5_000L);
        assertThat(result.recovery()).contains(GatewayRecovery.IN_RUN_429);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
    }

    @Test
    @DisplayName("falls back to computed backoff for a non-numeric Retry-After")
    void falls_back_for_non_numeric_retry_after() {
        final String retryScenario = "invalid-retry-after";
        gateway.stubFor(post(urlEqualTo(PATH)).inScenario(retryScenario)
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "later"))
                .willSetStateTo("recovered"));
        gateway.stubFor(post(urlEqualTo(PATH)).inScenario(retryScenario)
                .whenScenarioStateIs("recovered").willReturn(aResponse().withStatus(200)));
        final HmppsPublisher retrying = new HmppsPublisher(gateway.baseUrl() + PATH, KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(2), 3,
                Duration.ofMillis(30), Duration.ofMillis(60), Duration.ofSeconds(5));
        final long started = System.nanoTime();

        final PublishOutcome.Sent result = (PublishOutcome.Sent) retrying.send(body()).outcome();

        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
        assertThat(elapsedMillis).isBetween(25L, 1_000L);
        assertThat(result.recovery()).contains(GatewayRecovery.IN_RUN_429);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(2);
    }

    @Test
    @DisplayName("does not follow a 302 and maps it to TransientExhausted with the status")
    void does_not_follow_a_302() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(302)
                .withHeader("Location", gateway.baseUrl() + "/elsewhere")));

        final SentBody result = publisher.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.TransientExhausted.class);
        assertThat(((PublishOutcome.TransientExhausted) result.outcome()).httpStatus()).hasValue(302);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    @Test
    @DisplayName("maps a transport failure to TransientExhausted with no status")
    void maps_a_transport_failure_to_transient_exhausted() {
        final WireMockServer closed =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        closed.start();
        final String deadUrl = closed.baseUrl() + PATH;
        closed.stop();
        final HmppsPublisher toClosedPort = new HmppsPublisher(deadUrl, KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(2));
        final long started = System.nanoTime();

        final SentBody result = toClosedPort.send(body());
        final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.TransientExhausted.class);
        assertThat(((PublishOutcome.TransientExhausted) result.outcome()).httpStatus()).isEmpty();
        assertThat(elapsedMillis).isBetween(1_400L, 15_000L);
    }

    @Test
    @DisplayName("retries a refused connection when the gateway becomes reachable")
    void retries_connection_refusal_until_gateway_is_reachable() throws Exception {
        final int port;
        try (ServerSocket reservation = new ServerSocket(0)) {
            port = reservation.getLocalPort();
        }
        final WireMockServer recoveringGateway = new WireMockServer(
                WireMockConfiguration.options().port(port));
        try (ScheduledExecutorService starter = Executors.newSingleThreadScheduledExecutor()) {
            starter.schedule(() -> {
                recoveringGateway.start();
                recoveringGateway.stubFor(post(urlEqualTo(PATH))
                        .willReturn(aResponse().withStatus(200)));
            }, 150, TimeUnit.MILLISECONDS);
            final HmppsPublisher retrying = new HmppsPublisher(
                    "http://127.0.0.1:" + port + PATH, KEY,
                    Duration.ofSeconds(1), Duration.ofSeconds(2), 3,
                    Duration.ofMillis(500), Duration.ofSeconds(10), Duration.ofSeconds(10));
            final long started = System.nanoTime();

            try {
                final SentBody result = retrying.send(body());
                final long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

                assertThat(result.outcome()).isInstanceOf(PublishOutcome.Sent.class);
                assertThat(elapsedMillis).isBetween(450L, 5_000L);
                assertThat(recoveringGateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
            } finally {
                recoveringGateway.stop();
            }
        }
    }

    @Test
    @DisplayName("does not start another 429 attempt when its wait and timeouts exceed the stage budget")
    void refuses_429_retry_that_does_not_fit_the_send_budget() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(429)
                .withHeader("Retry-After", "2").withFixedDelay(350)));
        final HmppsPublisher budgetBound = new HmppsPublisher(gateway.baseUrl() + PATH, KEY,
                Duration.ofSeconds(1), Duration.ofSeconds(1), 3,
                Duration.ofMillis(5), Duration.ofSeconds(10), Duration.ofMillis(800));

        final SentBody result = budgetBound.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.TransientExhausted.class);
        assertThat(((PublishOutcome.TransientExhausted) result.outcome()).httpStatus()).hasValue(429);
        assertThat(gateway.findAll(postRequestedFor(urlEqualTo(PATH)))).hasSize(1);
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
