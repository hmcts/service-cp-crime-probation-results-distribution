package uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchDecision;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchUnreadableCause;

@DisplayName("the users-groups youth-switch client")
class FeaturePermissionClientTest {

    private static final String PATH =
            "/usersgroups-query-api/query/api/rest/usersgroups/permissions";
    private static final String KEY = "EMYouthFilter";
    private static final String SYSTEM_USER = "99999999-9999-9999-9999-999999999999";
    private static final String ACCEPT =
            "application/vnd.usersgroups.get-permissions-by-feature-key+json";

    private WireMockServer usersGroups;
    private FeaturePermissionClient client;

    @BeforeEach
    void setUp() {
        usersGroups = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        usersGroups.start();
        client = new FeaturePermissionClient(usersGroups.baseUrl(), SYSTEM_USER, KEY,
                Duration.ofSeconds(2), Duration.ofSeconds(5));
    }

    @AfterEach
    void tearDown() {
        usersGroups.stop();
    }

    private DistributionCommand command() {
        return new DistributionCommand("RESULTS", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-08-19"), Instant.parse("2026-08-19T16:42:07.512Z"),
                "Hearing_Resulted", Optional.empty());
    }

    private void stub(final String body) {
        usersGroups.stubFor(get(urlPathEqualTo(PATH))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test
    @DisplayName("reads ACTIVE when permissions[0].active is truthy, sending the key and system user")
    void reads_active() {
        stub("{\"permissions\":[{\"active\":true}]}");

        final YouthSwitchDecision decision = client.read(command());

        assertThat(decision).isInstanceOf(YouthSwitchDecision.Active.class);
        usersGroups.verify(getRequestedFor(urlPathEqualTo(PATH))
                .withQueryParam("key", equalTo(KEY))
                .withHeader("Accept", equalTo(ACCEPT))
                .withHeader("CJSCPPUID", equalTo(SYSTEM_USER)));
    }

    @Test
    @DisplayName("reads INACTIVE when permissions[0].active is false")
    void reads_inactive_when_false() {
        stub("{\"permissions\":[{\"active\":false}]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Inactive.class);
    }

    @Test
    @DisplayName("reads INACTIVE when permissions[0].active is absent")
    void reads_inactive_when_absent() {
        stub("{\"permissions\":[{\"feature\":\"EMYouthFilter\"}]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Inactive.class);
    }

    @Test
    @DisplayName("reads INACTIVE when permissions[0] is not an object but is non-null")
    void reads_inactive_when_first_is_not_an_object() {
        stub("{\"permissions\":[\"EMYouthFilter\"]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Inactive.class);
    }

    @Test
    @DisplayName("reads ACTIVE for a truthy non-boolean active (JavaScript truthiness)")
    void reads_active_for_a_truthy_string() {
        stub("{\"permissions\":[{\"active\":\"yes\"}]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Active.class);
    }

    @Test
    @DisplayName("reads INACTIVE for a zero active (falsy)")
    void reads_inactive_for_zero() {
        stub("{\"permissions\":[{\"active\":0}]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Inactive.class);
    }

    @Test
    @DisplayName("reads ACTIVE for a non-zero numeric active (truthy)")
    void reads_active_for_a_non_zero_number() {
        stub("{\"permissions\":[{\"active\":1}]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Active.class);
    }

    @Test
    @DisplayName("reads INACTIVE for an empty-string active (falsy)")
    void reads_inactive_for_empty_string() {
        stub("{\"permissions\":[{\"active\":\"\"}]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Inactive.class);
    }

    @Test
    @DisplayName("reads ACTIVE for a structured (object) active (truthy in JavaScript)")
    void reads_active_for_an_object() {
        stub("{\"permissions\":[{\"active\":{\"nested\":true}}]}");

        assertThat(client.read(command())).isInstanceOf(YouthSwitchDecision.Active.class);
    }

    private YouthSwitchUnreadableCause unreadableCause(final YouthSwitchDecision decision) {
        assertThat(decision).isInstanceOf(YouthSwitchDecision.Unreadable.class);
        return ((YouthSwitchDecision.Unreadable) decision).cause();
    }

    @Test
    @DisplayName("is UNREADABLE(HTTP_FAILURE) on a non-2xx answer")
    void unreadable_http_failure_on_non_2xx() {
        usersGroups.stubFor(get(urlPathEqualTo(PATH)).willReturn(aResponse().withStatus(500)));

        assertThat(unreadableCause(client.read(command())))
                .isEqualTo(YouthSwitchUnreadableCause.HTTP_FAILURE);
    }

    @Test
    @DisplayName("is UNREADABLE(UNPARSEABLE) when the 2xx body does not parse")
    void unreadable_unparseable_body() {
        stub("<html>not json</html>");

        assertThat(unreadableCause(client.read(command())))
                .isEqualTo(YouthSwitchUnreadableCause.UNPARSEABLE);
    }

    @Test
    @DisplayName("is UNREADABLE(EMPTY_PERMISSIONS) when the list is empty")
    void unreadable_empty_permissions() {
        stub("{\"permissions\":[]}");

        assertThat(unreadableCause(client.read(command())))
                .isEqualTo(YouthSwitchUnreadableCause.EMPTY_PERMISSIONS);
    }

    @Test
    @DisplayName("is UNREADABLE(EMPTY_PERMISSIONS) when the list is missing")
    void unreadable_missing_permissions() {
        stub("{\"other\":true}");

        assertThat(unreadableCause(client.read(command())))
                .isEqualTo(YouthSwitchUnreadableCause.EMPTY_PERMISSIONS);
    }

    @Test
    @DisplayName("is UNREADABLE(EMPTY_PERMISSIONS) when the first entry is null")
    void unreadable_null_first_entry() {
        stub("{\"permissions\":[null]}");

        assertThat(unreadableCause(client.read(command())))
                .isEqualTo(YouthSwitchUnreadableCause.EMPTY_PERMISSIONS);
    }

    @Test
    @DisplayName("is UNREADABLE(HTTP_FAILURE) on a transport failure")
    void unreadable_on_transport_failure() {
        final WireMockServer closed =
                new WireMockServer(WireMockConfiguration.options().dynamicPort());
        closed.start();
        final String deadBaseUrl = closed.baseUrl();
        closed.stop();
        final FeaturePermissionClient toClosed = new FeaturePermissionClient(deadBaseUrl,
                SYSTEM_USER, KEY, Duration.ofSeconds(1), Duration.ofSeconds(1));

        assertThat(unreadableCause(toClosed.read(command())))
                .isEqualTo(YouthSwitchUnreadableCause.HTTP_FAILURE);
    }
}
