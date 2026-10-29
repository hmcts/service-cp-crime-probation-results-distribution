package uk.gov.hmcts.cp.probationresultsdistribution.config;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the one {@code probation_} metric family (design §10.1, §13; FR-002-057), unconditional
 * on intake: slice 005's operations API increments it over its own HTTP surface
 * ({@code probation_operations_failures_total}, FR-005-043), independently of whether
 * {@code distribution.consumer.enabled} is on. {@link MetricRegistry} was previously defined inside
 * {@link ServiceBusConsumerConfig}, which is intake-gated; moved here so a pod can serve replay and
 * resend-leg with intake off (paused, or not yet enabled) without the context refresh failing.
 */
@Configuration
public class MetricRegistryConfiguration {

    /* default */ @Bean
    MetricRegistry metricRegistry(final MeterRegistry meterRegistry) {
        return new MetricRegistry(meterRegistry);
    }
}
