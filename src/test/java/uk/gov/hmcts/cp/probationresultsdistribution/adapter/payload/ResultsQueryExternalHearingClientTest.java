package uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.application.HearingPayloadSource.FetchedPayload;
import uk.gov.hmcts.cp.probationresultsdistribution.application.StageBudget;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;

@DisplayName("the results-query payload fallback")
class ResultsQueryExternalHearingClientTest {

    private static final String SYSTEM_USER = "00000000-0000-0000-0000-000000000000";
    private static final String ACCEPT = "application/vnd.results.hearing-information-details+json";
    private static final UUID HEARING_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final String PATH =
            "/results-query-api/query/api/rest/results/hearingDetails/" + HEARING_ID;

    private WireMockServer wireMock;
    private ResultsQueryExternalHearingClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        client = new ResultsQueryExternalHearingClient(wireMock.baseUrl(), SYSTEM_USER,
                Duration.ofSeconds(5), Duration.ofSeconds(30), 4,
                Duration.ofMillis(20), Duration.ofMillis(200));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private DistributionCommand command(final Optional<UUID> userId) {
        return new DistributionCommand("RESULTS", UUID.randomUUID(), HEARING_ID,
                java.time.LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", userId);
    }

    private StageBudget deadline() {
        return StageBudget.startingNow(Duration.ofSeconds(60));
    }

    @Test
    @DisplayName("returns the fallback body unexpanded and records payload_source=query")
    void returns_the_fallback_body() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson(
                "{\"hearing\":{\"id\":\"h\"},\"sharedTime\":\"2026-08-19T16:42:07.512Z\"}")));

        final Optional<FetchedPayload> fetched = client.fetch(command(Optional.empty()), deadline());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().source()).isEqualTo(PayloadSource.QUERY);
        assertThat(fetched.get().payload().get("hearing").get("id").asString()).isEqualTo("h");
        assertThat(fetched.get().payload().has("cpsProsecutorIds")).isFalse();
        assertThat(fetched.get().sharedTime()).contains(Instant.parse("2026-08-19T16:42:07.512Z"));
    }

    @Test
    @DisplayName("sends the system user as CJSCPPUID and the results Accept when there is no userId")
    void sends_the_system_user_when_no_user_id() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{\"hearing\":{}}")));

        client.fetch(command(Optional.empty()), deadline());

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("hearingDate", equalTo("2026-08-19"))
                .withHeader("CJSCPPUID", equalTo(SYSTEM_USER))
                .withHeader("Accept", equalTo(ACCEPT)));
    }

    @Test
    @DisplayName("sends the command's userId as CJSCPPUID when it carries one")
    void sends_the_command_user_when_present() {
        final UUID userId = UUID.randomUUID();
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{\"hearing\":{}}")));

        client.fetch(command(Optional.of(userId)), deadline());

        wireMock.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withHeader("CJSCPPUID", equalTo(userId.toString())));
    }

    @Test
    @DisplayName("treats an empty body, {} or non-JSON 2xx as a miss")
    void misses_on_an_empty_or_braces_or_nonjson_body() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(200)));
        assertThat(client.fetch(command(Optional.empty()), deadline())).isEmpty();

        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{}")));
        assertThat(client.fetch(command(Optional.empty()), deadline())).isEmpty();

        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(okJson("{not json")));
        assertThat(client.fetch(command(Optional.empty()), deadline())).isEmpty();
    }

    @Test
    @DisplayName("retries a 503 then is sent on the 200, honouring a delta-seconds Retry-After")
    void retries_a_503_then_is_sent() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover")
                .whenScenarioStateIs(com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "0"))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"hearing\":{\"id\":\"recovered\"}}")));

        final Optional<FetchedPayload> fetched = client.fetch(command(Optional.empty()), deadline());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().payload().get("hearing").get("id").asString())
                .isEqualTo("recovered");
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("does not retry a 404 and treats it as a miss")
    void does_not_retry_a_404() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(404)));

        assertThat(client.fetch(command(Optional.empty()), deadline())).isEmpty();

        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("gives up after the maximum attempts when a 5xx never recovers")
    void gives_up_after_max_attempts() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(503)));

        assertThat(client.fetch(command(Optional.empty()), deadline())).isEmpty();

        wireMock.verify(4, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("retries a 408 then is sent on the 200 (DV-23)")
    void retries_a_408_then_is_sent() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-408")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(408))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-408")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"hearing\":{\"id\":\"after-408\"}}")));

        final Optional<FetchedPayload> fetched = client.fetch(command(Optional.empty()), deadline());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().payload().get("hearing").get("id").asString())
                .isEqualTo("after-408");
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("retries a 429 carrying a delta-seconds Retry-After then is sent (DV-23)")
    void retries_a_429_with_retry_after_then_is_sent() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-429")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429).withHeader("Retry-After", "0"))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-429")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"hearing\":{\"id\":\"after-429\"}}")));

        final Optional<FetchedPayload> fetched = client.fetch(command(Optional.empty()), deadline());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().payload().get("hearing").get("id").asString())
                .isEqualTo("after-429");
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("retries a 429 with no Retry-After, backing off, then is sent (DV-23)")
    void retries_a_429_without_retry_after_then_is_sent() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-429-plain")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-429-plain")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"hearing\":{\"id\":\"backed-off\"}}")));

        final Optional<FetchedPayload> fetched = client.fetch(command(Optional.empty()), deadline());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().payload().get("hearing").get("id").asString())
                .isEqualTo("backed-off");
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("ignores a non-numeric Retry-After and falls back to the computed back-off (DV-23)")
    void ignores_a_non_numeric_retry_after() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("http-date")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)
                        .withHeader("Retry-After", "Wed, 21 Oct 2026 07:28:00 GMT"))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("http-date")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"hearing\":{\"id\":\"computed\"}}")));

        final Optional<FetchedPayload> fetched = client.fetch(command(Optional.empty()), deadline());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().payload().get("hearing").get("id").asString())
                .isEqualTo("computed");
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("retries a transport failure then is sent on the 200 (DV-23)")
    void retries_a_transport_failure_then_is_sent() {
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-fault")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
                .willSetStateTo("second"));
        wireMock.stubFor(get(urlPathEqualTo(PATH)).inScenario("recover-fault")
                .whenScenarioStateIs("second")
                .willReturn(okJson("{\"hearing\":{\"id\":\"after-reset\"}}")));

        final Optional<FetchedPayload> fetched = client.fetch(command(Optional.empty()), deadline());

        assertThat(fetched).isPresent();
        assertThat(fetched.get().payload().get("hearing").get("id").asString())
                .isEqualTo("after-reset");
        wireMock.verify(2, getRequestedFor(urlPathEqualTo(PATH)));
    }

    @Test
    @DisplayName("stops before a retry whose wait would overrun the payload budget (DV-23)")
    void stops_when_the_budget_runs_out_mid_retry() {
        final ResultsQueryExternalHearingClient budgeted = new ResultsQueryExternalHearingClient(
                wireMock.baseUrl(), SYSTEM_USER, Duration.ofSeconds(1), Duration.ofSeconds(1), 4,
                Duration.ofMillis(20), Duration.ofSeconds(5));
        wireMock.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(503).withHeader("Retry-After", "10")));

        final Optional<FetchedPayload> fetched =
                budgeted.fetch(command(Optional.empty()), StageBudget.startingNow(Duration.ofSeconds(5)));

        assertThat(fetched).isEmpty();
        wireMock.verify(1, getRequestedFor(urlPathEqualTo(PATH)));
    }
}
