package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyMaintenanceStateRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.DatabaseClock;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionCatalogue;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionMaintenanceRepository;
import uk.gov.hmcts.testfixtures.ServiceBusReadinessHealthContributors;

/** Exercises the deployed readiness group with a live Actuator endpoint and a failed broker. */
// This fixture has no store; maintenance is exercised against PostgreSQL by its own ITs.
@MockitoBean(types = {BodyRetentionJob.class, DatabaseClock.class, MaintenanceMetrics.class,
    PartitionCatalogue.class, PartitionMaintenanceRepository.class,
    BodyMaintenanceStateRepository.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.group.readiness.include=db")
@ActiveProfiles("test")
@DisplayName("the deployed readiness endpoint")
@Import(ServiceBusReadinessHealthContributors.class)
class ServiceBusReadinessIT {

    private final int port;
    private final ServiceBusHealthIndicator broker;

    @Autowired
    ServiceBusReadinessIT(@Value("${local.server.port}") final int port,
                          final ServiceBusHealthIndicator broker) {
        this.port = port;
        this.broker = broker;
    }

    @Test
    @DisplayName("stays up with a healthy database when broker health is down")
    void excludes_the_broker_from_readiness() throws Exception {
        broker.recordError();

        final HttpRequest request = HttpRequest.newBuilder(
                URI.create("http://localhost:" + port + "/actuator/health/readiness")).GET().build();
        final HttpResponse<String> readiness = HttpClient.newHttpClient().send(
                request, HttpResponse.BodyHandlers.ofString());
        assertThat(readiness.statusCode()).isEqualTo(200);
        assertThat(readiness.body()).contains("\"status\":\"UP\"");
        assertThat(broker.health().getStatus().getCode()).isEqualTo("DOWN");
    }

}
