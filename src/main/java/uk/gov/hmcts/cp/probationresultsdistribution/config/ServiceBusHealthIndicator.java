package uk.gov.hmcts.cp.probationresultsdistribution.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.function.LongSupplier;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;

/** Reports recent broker connection failures without making them a readiness dependency. */
public final class ServiceBusHealthIndicator implements HealthIndicator {

    private final Duration staleness;
    private final LongSupplier nanoTime;
    private long lastErrorNanos;
    private boolean errorRecorded;
    private boolean recovered;

    /** Creates the indicator and registers its bounded up/down gauge. */
    public ServiceBusHealthIndicator(final Duration staleness, final MeterRegistry registry) {
        this(staleness, registry, System::nanoTime);
    }

    /* default */ ServiceBusHealthIndicator(final Duration staleness, final MeterRegistry registry,
                                            final LongSupplier nanoTime) {
        if (staleness.isZero() || staleness.isNegative()) {
            throw new IllegalArgumentException("Service Bus health staleness must be positive");
        }
        this.staleness = staleness;
        this.nanoTime = nanoTime;
        Gauge.builder("probation_servicebus_up", this, value -> value.isUp() ? 1.0d : 0.0d)
                .register(registry);
    }

    /** Records a processor error; it is considered current until traffic recovers or it goes stale. */
    public synchronized void recordError() {
        errorRecorded = true;
        recovered = false;
        lastErrorNanos = nanoTime.getAsLong();
    }

    /** Any delivered message proves the broker connection is working again. */
    public synchronized void recordTraffic() {
        if (errorRecorded) {
            recovered = true;
        }
    }

    @Override
    public Health health() {
        return isUp() ? Health.up().build() : Health.down().build();
    }

    private synchronized boolean isUp() {
        return !errorRecorded || recovered
                || nanoTime.getAsLong() - lastErrorNanos >= staleness.toNanos();
    }
}
