package uk.gov.hmcts.cp.probationresultsdistribution;

import com.azure.messaging.servicebus.ServiceBusException;
import java.time.Duration;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Retries a harness call to the Service Bus emulator that the emulator refused with namespace
 * throttling (error 50002), which it raises under the full suite's load. Test harness only: the
 * service's own broker handling is not changed by it.
 */
final class EmulatorThrottleRetry {

    /** Attempts in all, the first included. */
    static final int ATTEMPTS = 3;

    /** The wait the emulator's throttling answer asks for. */
    private static final Duration WAIT = Duration.ofSeconds(10);

    private static final String THROTTLE_CODE = "Error code : 50002";

    private EmulatorThrottleRetry() {
    }

    /**
     * Runs the call, retrying it while the emulator answers throttled.
     *
     * @param call the call to the emulator
     * @param pause how to wait between attempts
     * @param <T> the call's answer
     * @return the call's answer
     */
    static <T> T call(final Supplier<T> call, final Consumer<Duration> pause) {
        int attempt = 1;
        while (true) {
            try {
                return call.get();
            } catch (final ServiceBusException e) {
                if (attempt >= ATTEMPTS || !isThrottled(e)) {
                    throw e;
                }
                attempt++;
                pause.accept(WAIT);
            }
        }
    }

    /**
     * Sleeps for the given wait; the pause the harness uses outside tests of this class.
     *
     * @param wait how long to sleep
     */
    static void sleep(final Duration wait) {
        try {
            Thread.sleep(wait.toMillis());
        } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting out emulator throttling", e);
        }
    }

    private static boolean isThrottled(final ServiceBusException e) {
        final String message = e.getMessage();
        return message != null && message.contains(THROTTLE_CODE);
    }
}
