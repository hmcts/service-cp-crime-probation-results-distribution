package uk.gov.hmcts.cp.probationresultsdistribution;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.azure.messaging.servicebus.ServiceBusErrorSource;
import com.azure.messaging.servicebus.ServiceBusException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins the harness's tolerance of the Service Bus emulator's namespace throttling (error 50002):
 * a throttled call is retried after the wait the emulator asks for, a bounded number of times,
 * and any other broker failure is rethrown at once.
 */
@DisplayName("the harness's retry of a throttled emulator call")
class EmulatorThrottleRetryTest {

    private static final String THROTTLED = "The request was terminated because the namespace"
            + " sbemulatorns is being throttled. Error code : 50002. Please wait 10 seconds and try"
            + " again.";

    private final List<Duration> waits = new ArrayList<>();

    private ServiceBusException brokerFailure(final String message) {
        return new ServiceBusException(new IllegalStateException(message), ServiceBusErrorSource.RECEIVE);
    }

    @Test
    @DisplayName("a call throttled once is retried after the emulator's wait and its answer returned")
    void call_throttledOnce_should_waitAndReturnTheRetriedAnswer() {
        final AtomicInteger calls = new AtomicInteger();

        final String answer = EmulatorThrottleRetry.call(() -> {
            if (calls.getAndIncrement() == 0) {
                throw brokerFailure(THROTTLED);
            }
            return "page";
        }, waits::add);

        assertThat(answer).isEqualTo("page");
        assertThat(calls).hasValue(2);
        assertThat(waits).containsExactly(Duration.ofSeconds(10));
    }

    @Test
    @DisplayName("a call that stays throttled is rethrown after the bounded attempts")
    void call_alwaysThrottled_should_rethrowAfterTheBoundedAttempts() {
        final AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> EmulatorThrottleRetry.call(() -> {
            calls.incrementAndGet();
            throw brokerFailure(THROTTLED);
        }, waits::add)).isInstanceOf(ServiceBusException.class);

        assertThat(calls).hasValue(EmulatorThrottleRetry.ATTEMPTS);
        assertThat(waits).hasSize(EmulatorThrottleRetry.ATTEMPTS - 1);
    }

    @Test
    @DisplayName("a broker failure that is not throttling is rethrown at once")
    void call_otherBrokerFailure_should_rethrowWithoutWaiting() {
        final AtomicInteger calls = new AtomicInteger();

        assertThatThrownBy(() -> EmulatorThrottleRetry.call(() -> {
            calls.incrementAndGet();
            throw brokerFailure("unreachable");
        }, waits::add)).isInstanceOf(ServiceBusException.class);

        assertThat(calls).hasValue(1);
        assertThat(waits).isEmpty();
    }
}
