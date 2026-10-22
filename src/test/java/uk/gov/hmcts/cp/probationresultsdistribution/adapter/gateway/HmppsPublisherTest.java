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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HmppsSender.SentBody;
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
    }

    @Test
    @DisplayName("maps a 408 to TransientExhausted")
    void maps_408_to_transient_exhausted() {
        gateway.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(408)));

        assertThat(publisher.send(body()).outcome())
                .isInstanceOf(PublishOutcome.TransientExhausted.class);
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

        final SentBody result = toClosedPort.send(body());

        assertThat(result.outcome()).isInstanceOf(PublishOutcome.TransientExhausted.class);
        assertThat(((PublishOutcome.TransientExhausted) result.outcome()).httpStatus()).isEmpty();
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
