package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.health.contributor.Status;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

@DisplayName("the Service Bus health signal")
class ServiceBusHealthTest {

    @Test
    @DisplayName("reports a recent broker error, clears on traffic, and forgets stale errors")
    void reports_recent_errors_and_recovers() {
        final AtomicLong now = new AtomicLong();
        final SimpleMeterRegistry meters = new SimpleMeterRegistry();
        final ServiceBusHealthIndicator health = new ServiceBusHealthIndicator(
                Duration.ofSeconds(60), meters, now::get);

        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
        assertThat(meters.get("probation_servicebus_up").gauge().value()).isEqualTo(1.0d);

        health.recordError();
        assertThat(health.health().getStatus()).isEqualTo(Status.DOWN);
        assertThat(meters.get("probation_servicebus_up").gauge().value()).isZero();

        health.recordTraffic();
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);

        health.recordError();
        now.addAndGet(Duration.ofSeconds(61).toNanos());
        assertThat(health.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("keeps Service Bus outside the deployed readiness group")
    void readiness_only_includes_the_database_probe() throws Exception {
        final java.util.List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application",
                new ClassPathResource("application.yaml"));
        assertThat(sources).hasSize(1);
        assertThat(sources.getFirst().getProperty(
                "management.endpoint.health.group.readiness.include")).isEqualTo("db");
    }
}
