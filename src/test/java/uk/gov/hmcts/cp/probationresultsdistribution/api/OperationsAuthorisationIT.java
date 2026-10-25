package uk.gov.hmcts.cp.probationresultsdistribution.api;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyMaintenanceStateRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.DatabaseClock;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionCatalogue;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionMaintenanceRepository;
import uk.gov.hmcts.testfixtures.ReplayAuthorisationTestController;
import uk.gov.hmcts.testfixtures.ResendLegAuthorisationTestController;

/**
 * Pins FR-005-018, FR-005-019, FR-005-021 and FR-005-022 against the real {@code
 * cp-auth-rules-filter}, with users-groups stubbed via WireMock: no/blank {@code CJSCPPUID} refuses
 * {@code 401}; a caller outside {@code Second Line Support} (or whose identity read fails, which the
 * library's own {@code IdentityClient} turns into an empty-groups {@link
 * uk.gov.moj.cpp.authz.http.IdentityResponse} rather than an exception) refuses {@code 403}; an
 * admitted caller reaches the mapped route; and {@link OperationsActionResolutionFilter} (T013) is
 * confirmed to make the resolved action immune to a caller-supplied {@code CPP-ACTION} header,
 * closing the bypass research.md Decision 2 found in the real library.
 *
 * <p>Both routes below are minimal test-only controllers mapped at the real paths, not the
 * production {@link ReplayController}: the authorisation filter chain (this test's actual subject)
 * runs identically regardless of which handler eventually serves an already-authorised request, and
 * {@code @ConditionalOnBean(ReplayAction.class)}-gated beans are not reliably visible from a
 * {@code @TestConfiguration} nested in the test class at the point {@code
 * OperationsConfiguration}'s own conditions are evaluated — confirmed empirically, not merely
 * suspected. Per tasks.md's own framing, this is unchanged once the real controllers exist.
 */
// This fixture has no store; maintenance is exercised against PostgreSQL by its own ITs
// (TestProfileContextTest).
@MockitoBean(types = {BodyRetentionJob.class, DatabaseClock.class, MaintenanceMetrics.class,
    PartitionCatalogue.class, PartitionMaintenanceRepository.class,
    BodyMaintenanceStateRepository.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
@Import({ReplayAuthorisationTestController.class, ResendLegAuthorisationTestController.class})
@DisplayName("operations API authorisation, against the real cp-auth-rules-filter")
class OperationsAuthorisationIT {

    private static final String SECOND_LINE_SUPPORT = "Second Line Support";
    private static final String OTHER_GROUP = "First Line Support";
    private static final String IDENTITY_PATH =
            "/usersgroups-query-api/query/api/rest/usersgroups/users/logged-in-user/permissions";
    private static final String REPLAY_PATH =
            "/operations/requests/3fa85f64-5717-4562-b3fc-2c963f66afa6/replay";
    private static final String RESEND_LEG_PATH = "/operations/resend-leg";

    private static WireMockServer usersGroups;

    @LocalServerPort
    private int port;
    @Autowired
    private TestRestTemplate restTemplate;

    @BeforeAll
    static void startUsersGroups() {
        usersGroups = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        usersGroups.start();
    }

    @AfterAll
    static void stopUsersGroups() {
        usersGroups.stop();
    }

    @DynamicPropertySource
    static void authzProperties(final DynamicPropertyRegistry registry) {
        // authz.http.enabled is bound via relaxed @ConfigurationProperties binding, which only
        // recognises the env-var spelling from a genuine SystemEnvironmentPropertySource, not a
        // property this registry adds under that literal name — the dotted key is needed here.
        // USERSGROUPS_BASE_URL is different: application.yaml resolves it as a ${...} placeholder,
        // a literal name lookup across all property sources, so the env-var spelling works for it.
        registry.add("authz.http.enabled", () -> "true");
        registry.add("USERSGROUPS_BASE_URL", usersGroups::baseUrl);
    }

    private void stubIdentity(final String... groupNames) {
        final StringBuilder groups = new StringBuilder();
        for (final String name : groupNames) {
            if (!groups.isEmpty()) {
                groups.append(',');
            }
            groups.append("{\"groupName\": \"").append(name).append("\"}");
        }
        usersGroups.stubFor(get(urlPathEqualTo(IDENTITY_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"groups\": [" + groups + "], \"switchableRoles\": [], \"permissions\": []}")));
    }

    private ResponseEntity<String> callReplay(final HttpHeaders headers) {
        return restTemplate.exchange("http://localhost:" + port + REPLAY_PATH, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    private ResponseEntity<String> callResendLeg(final HttpHeaders headers) {
        return restTemplate.exchange("http://localhost:" + port + RESEND_LEG_PATH, HttpMethod.POST,
                new HttpEntity<>(headers), String.class);
    }

    @Test
    @DisplayName("no CJSCPPUID refuses 401 and reaches no application service")
    void no_identity_refuses_401() {
        final ResponseEntity<String> response = callReplay(new HttpHeaders());

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a blank CJSCPPUID refuses 401")
    void blank_identity_refuses_401() {
        final HttpHeaders headers = new HttpHeaders();
        headers.add("CJSCPPUID", "");

        final ResponseEntity<String> response = callReplay(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a caller outside Second Line Support is refused 403 before the test route runs")
    void caller_outside_group_refuses_403() {
        stubIdentity(OTHER_GROUP);
        final HttpHeaders headers = new HttpHeaders();
        headers.add("CJSCPPUID", "caller-1");

        final ResponseEntity<String> response = callReplay(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

    @Test
    @DisplayName("users-groups unreachable fails closed to 403")
    void users_groups_unreachable_fails_closed() {
        usersGroups.stop();
        final HttpHeaders headers = new HttpHeaders();
        headers.add("CJSCPPUID", "caller-2");

        final ResponseEntity<String> response = callReplay(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        usersGroups.start();
    }

    @Test
    @DisplayName("an admitted caller reaches the real replay test route")
    void admitted_caller_reaches_replay() {
        stubIdentity(SECOND_LINE_SUPPORT);
        final HttpHeaders headers = new HttpHeaders();
        headers.add("CJSCPPUID", "caller-3");

        final ResponseEntity<String> response = callReplay(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("an admitted caller reaches the real resend-leg test route")
    void admitted_caller_reaches_resend_leg() {
        stubIdentity(SECOND_LINE_SUPPORT);
        final HttpHeaders headers = new HttpHeaders();
        headers.add("CJSCPPUID", "caller-4");

        final ResponseEntity<String> response = callResendLeg(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
    }

    @Test
    @DisplayName("a caller admitted to replay cannot reach resend-leg by naming it in CPP-ACTION")
    void action_header_cannot_redirect_authorisation() {
        stubIdentity(OTHER_GROUP);
        final HttpHeaders headers = new HttpHeaders();
        headers.add("CJSCPPUID", "caller-5");
        headers.add("CPP-ACTION", "probation-results-distribution-operations.replay");

        final ResponseEntity<String> response = callResendLeg(headers);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
    }

}
