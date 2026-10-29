package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.LegResender;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendAcceptance;

/**
 * Executes the runs {@code POST /operations/resend-leg} has accepted (FR-005-015), so the call
 * answers {@code 202} before any id has necessarily finished. Accepted runs queue and execute one
 * at a time, in acceptance order, on one thread: the single thread is fixed here, in code, and is
 * not a {@code distribution.*} property (decided 2026-10-29).
 *
 * <p>A run that throws is logged at ERROR with its {@code resendRunId} and a bounded cause (the
 * exception's class name, never its message) and the next queued run still executes; the ids the
 * run had already taken keep their outcomes on their rows. Only identifiers and bounded codes are
 * logged here: never the operator, the reason or a body.
 *
 * <p>On {@link #close()} (the serving context shutting down) the running run finishes the id in
 * flight, then claims nothing further and reports the rest {@code not-run}, exactly as
 * {@link LegResender} does for a pause that begins mid-run; every queued run is dropped without
 * starting, each logged at WARN with its {@code resendRunId}; and nothing more is accepted. The wait
 * for the id in flight is bounded by the processing deadline (4 minutes), past which it is logged
 * at ERROR and left to the claim lease.
 */
public final class ResendLegExecutor implements AutoCloseable {

    /** The executor thread's name. */
    /* default */ static final String THREAD_NAME = "resend-leg-run";

    private static final Logger LOG = LoggerFactory.getLogger(ResendLegExecutor.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofMinutes(4);

    private final LegResender legResender;
    private final Duration shutdownWait;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ThreadPoolExecutor executor;

    /**
     * Creates the executor and its one thread.
     *
     * @param legResender the capability whose run step it executes
     */
    public ResendLegExecutor(final LegResender legResender) {
        this(legResender, SHUTDOWN_WAIT);
    }

    /* default */ ResendLegExecutor(final LegResender legResender, final Duration shutdownWait) {
        this.legResender = legResender;
        this.shutdownWait = shutdownWait;
        this.executor = new ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, queue,
                Thread.ofPlatform().name(THREAD_NAME).factory());
    }

    /**
     * Queues an accepted run behind any run already accepted.
     *
     * @param accepted the run the accept step accepted
     * @throws IllegalStateException once the executor is shutting down
     */
    public void submit(final ResendAcceptance.Accepted accepted) {
        if (stopping.get()) {
            throw new IllegalStateException("the resend-leg executor is shutting down");
        }
        executor.execute(new AcceptedRun(accepted));
    }

    /**
     * Stops the executor: the running run claims nothing further, queued runs never start.
     */
    @Override
    public void close() {
        stopping.set(true);
        final List<Runnable> dropped = new ArrayList<>();
        queue.drainTo(dropped);
        for (final Runnable run : dropped) {
            LOG.warn("resend-leg run not started cause=shutdown resendRunId={}",
                    ((AcceptedRun) run).accepted.resendRunId());
        }
        executor.shutdown();
        try {
            if (!executor.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.error("resend-leg run still in flight at shutdown cause=shutdown-wait-elapsed");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.error("resend-leg shutdown wait interrupted cause={}",
                    interrupted.getClass().getSimpleName());
        }
    }

    /** One accepted run, queued with its run id so a dropped one can still be named. */
    private final class AcceptedRun implements Runnable {

        private final ResendAcceptance.Accepted accepted;

        private AcceptedRun(final ResendAcceptance.Accepted accepted) {
            this.accepted = accepted;
        }

        @Override
        @SuppressWarnings("PMD.AvoidCatchingGenericException")
        // A run has no caller left to rethrow to: its failure is logged with a bounded cause, and
        // the next queued run still executes.
        public void run() {
            try {
                legResender.run(accepted, stopping::get);
            } catch (final RuntimeException failure) {
                LOG.error("resend-leg run failed resendRunId={} cause={}", accepted.resendRunId(),
                        failure.getClass().getSimpleName());
            }
        }
    }
}
