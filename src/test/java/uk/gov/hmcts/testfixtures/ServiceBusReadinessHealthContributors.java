package uk.gov.hmcts.testfixtures;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import uk.gov.hmcts.cp.probationresultsdistribution.config.ServiceBusHealthIndicator;

/** Health contributors for the readiness HTTP test; kept outside the application's scan package. */
@TestConfiguration(proxyBeanMethods = false)
public class ServiceBusReadinessHealthContributors {

    @Bean("dbHealthIndicator")
    HealthIndicator database() {
        return () -> Health.up().build();
    }

    @Bean("probationServiceBusHealthIndicator")
    ServiceBusHealthIndicator broker() {
        return new ServiceBusHealthIndicator(Duration.ofSeconds(60), new SimpleMeterRegistry());
    }
}
