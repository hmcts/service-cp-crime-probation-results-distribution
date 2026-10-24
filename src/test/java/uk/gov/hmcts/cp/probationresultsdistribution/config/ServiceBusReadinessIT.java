package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.BodyRetentionJob;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.DatabaseClock;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.MaintenanceMetrics;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionCatalogue;
import uk.gov.hmcts.cp.probationresultsdistribution.maintenance.PartitionMaintenanceRepository;

/** Exercises the deployed readiness group with a live Actuator endpoint and a failed broker. */
// This fixture has no store; maintenance is exercised against PostgreSQL by its own ITs.
@MockitoBean(types = {BodyRetentionJob.class, DatabaseClock.class, MaintenanceMetrics.class,
    PartitionCatalogue.class, PartitionMaintenanceRepository.class})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "management.endpoint.health.group.readiness.include=db")
@ActiveProfiles("test")
@DisplayName("the deployed readiness endpoint")
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

    @TestConfiguration(proxyBeanMethods = false)
    static class HealthContributors {

        @Bean("dbHealthIndicator")
        HealthIndicator database() {
            return () -> Health.up().build();
        }

        @Bean("probationServiceBusHealthIndicator")
        ServiceBusHealthIndicator broker() {
            return new ServiceBusHealthIndicator(Duration.ofSeconds(60), new SimpleMeterRegistry());
        }
    }
}
