package uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("the gateway retry policy")
class RetryPolicyTest {

    private final RetryPolicy policy = new RetryPolicy(3, Duration.ofMillis(500),
            Duration.ofSeconds(10), Duration.ofSeconds(65), status -> status == 429);

    @Test
    @DisplayName("uses the gateway's fixed status predicate and bounded attempt count")
    void retries_only_the_gateway_predicate() {
        assertThat(policy.maxAttempts()).isEqualTo(3);
        assertThat(policy.retryable(429)).isTrue();
        assertThat(policy.retryable(408)).isFalse();
        assertThat(policy.retryable(503)).isFalse();
    }

    @Test
    @DisplayName("doubles the wait and honours numeric Retry-After within the cap")
    void bounds_retry_waits() {
        assertThat(policy.waitAfter(1, null)).isEqualTo(Duration.ofMillis(500));
        assertThat(policy.waitAfter(2, null)).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.waitAfter(1, "2")).isEqualTo(Duration.ofSeconds(2));
        assertThat(policy.waitAfter(1, "20")).isEqualTo(Duration.ofSeconds(10));
        assertThat(policy.waitAfter(2, "later")).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    @DisplayName("reserves one complete connect-plus-read attempt before the deadline")
    void reserves_the_whole_attempt() {
        final Instant now = Instant.parse("2026-10-22T14:00:00Z");
        assertThat(policy.attemptFitsBefore(now, now.plusSeconds(66))).isTrue();
        assertThat(policy.attemptFitsBefore(now, now.plusSeconds(64))).isFalse();
    }
}
