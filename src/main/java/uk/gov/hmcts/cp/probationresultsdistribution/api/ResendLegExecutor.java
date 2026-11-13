package uk.gov.hmcts.cp.probationresultsdistribution.api;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
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
 * logged here: never the operator, the reason or a body. A run that throws is logged only; no
 * metric counts it, as none is specified for it (design §10.1): the ERROR line is the alert source.
 *
 * <h2>Shutdown (H40; FR-004-060)</h2>
 *
 * <p>Each run increments {@code inFlightRuns} before it starts and decrements it once it finishes,
 * the same shared counter {@code inbound.ConsumerLifecycleController#stop()} already waits on for
 * an in-flight queue delivery: no new drain mechanism, the existing one now also covers a
 * resend-leg run reached through this executor. The standalone {@code cli.ResendLegRunner} process
 * is unaffected — it has no Spring shutdown lifecycle to drain.
 *
 * <p>Implements {@link SmartLifecycle}, at {@link SmartLifecycle#DEFAULT_PHASE} — one phase above
 * {@code ConsumerLifecycleController}'s own, deliberately lowered phase — so Spring stops this bean
 * <strong>first</strong> on context close (a {@code SmartLifecycle} group stops highest phase
 * first). {@link #stop()} <strong>quiesces only</strong>: it raises {@link #stopping} (so the
 * running run finishes the id it is on, then claims nothing further and reports the rest
 * {@code not-run}, exactly as {@link LegResender} does for a pause that begins mid-run) and shuts
 * the executor down to new submissions, every queued-but-not-started run dropped and logged at
 * WARN with its {@code resendRunId} — but it does not itself wait for the id in flight to finish.
 * That wait is left entirely to {@code ConsumerLifecycleController#stop()}, which runs next (lower
 * phase, stopped after this one) and already waits on the very same counter for its own in-flight
 * queue deliveries, bounded by its one {@code drainTimeout}. By the time that wait starts, this
 * bean has already raised the stop, so the counter is already falling — one shared deadline, not
 * two stacked ones, and the broker processor that controller's own {@code stop()} turns off is
 * quiesced before its wait begins too, not only after this bean's.
 *
 * <p>An earlier version of this method waited here as well (calling {@link #close()}, which
 * additionally awaits termination up to {@link #shutdownWait}, 4 minutes). Stacked ahead of {@code
 * ConsumerLifecycleController#stop()}'s own wait on the same counter, that made the two waits
 * additive (worst case approaching 8 minutes — past a typical pod termination grace period) and
 * left the queue consumer still admitting new deliveries for the whole of this bean's wait, since
 * the controller that actually stops the processor had not even started yet. A review before that
 * version shipped (PR #29) had already corrected an earlier mistake in the phase values themselves
 * — {@code ConsumerLifecycleController} does not override {@link SmartLifecycle#getPhase()}, so it
 * already sat at {@code SmartLifecycle.DEFAULT_PHASE} itself, the same as an unmodified phase here
 * would, a tie a first fix assumed away without checking; a unit test driving the real phase values
 * caught that one immediately. {@link #close()} keeps the full quiesce-and-wait behaviour, for the
 * one case nothing else drains the counter: a standalone or test instance with no {@code
 * ConsumerLifecycleController} in the same context (see {@code OperationsConfiguration}'s own
 * Javadoc on that case). Its own wait is bounded by the processing deadline (4 minutes), past which
 * it is logged at ERROR and left to the claim lease (PT4M30S) — the same backstop a pod killed
 * first leaves the id in flight to either way, since that wait can be longer than the pod's
 * termination grace period, a deployment setting outside this repository.
 */
public final class ResendLegExecutor implements SmartLifecycle, AutoCloseable {

    /** The executor thread's name. */
    /* default */ static final String THREAD_NAME = "resend-leg-run";

    /**
     * {@link SmartLifecycle#DEFAULT_PHASE}, named explicitly here (rather than left as every
     * {@code SmartLifecycle} bean's own unoverridden default) so the relationship to {@code
     * ConsumerLifecycleController#getPhase()} — one phase lower, by that class's own deliberate
     * override — reads at this use site without a reader needing to know {@code SmartLifecycle}'s
     * own default-method value from memory.
     */
    private static final int PHASE = DEFAULT_PHASE;

    private static final Logger LOG = LoggerFactory.getLogger(ResendLegExecutor.class);
    private static final Duration SHUTDOWN_WAIT = Duration.ofMinutes(4);

    private final LegResender legResender;
    private final AtomicInteger inFlightRuns;
    private final Duration shutdownWait;
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicBoolean running = new AtomicBoolean();
    private final BlockingQueue<Runnable> queue = new LinkedBlockingQueue<>();
    private final ThreadPoolExecutor executor;

    /**
     * Creates the executor and its one thread, counting nothing on any shared drain counter.
     *
     * @param legResender the capability whose run step it executes
     */
    public ResendLegExecutor(final LegResender legResender) {
        this(legResender, new AtomicInteger(), SHUTDOWN_WAIT);
    }

    /**
     * Creates the executor and its one thread, sharing the consumer's own in-flight-run counter so
     * a resend-leg run reached through it is waited for on shutdown (H40; FR-004-060).
     *
     * @param legResender  the capability whose run step it executes
     * @param inFlightRuns the shared counter {@code ConsumerLifecycleController#stop()} waits on
     */
    public ResendLegExecutor(final LegResender legResender, final AtomicInteger inFlightRuns) {
        this(legResender, inFlightRuns, SHUTDOWN_WAIT);
    }

    /* default */ ResendLegExecutor(final LegResender legResender, final Duration shutdownWait) {
        this(legResender, new AtomicInteger(), shutdownWait);
    }

    /* default */ ResendLegExecutor(final LegResender legResender, final AtomicInteger inFlightRuns,
                                    final Duration shutdownWait) {
        this.legResender = legResender;
        this.inFlightRuns = inFlightRuns;
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
    public synchronized void submit(final ResendAcceptance.Accepted accepted) {
        if (stopping.get()) {
            throw new IllegalStateException("the resend-leg executor is shutting down");
        }
        executor.execute(new AcceptedRun(accepted));
    }

    /**
     * Stops the executor: the running run claims nothing further, queued runs never start.
     *
     * <p>Safe to call more than once (idempotent): {@link SmartLifecycle#stop()} calls this during
     * context close, and a direct/test caller may also call it explicitly; the second call finds
     * {@link #stopping} already set and the executor already shut down.
     */
    @Override
    public void close() {
        stopAndDrain();
        try {
            if (!executor.awaitTermination(shutdownWait.toMillis(), TimeUnit.MILLISECONDS)) {
                LOG.error("resend-leg run still in flight at shutdown cause=shutdown-wait-elapsed");
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.error("resend-leg shutdown wait interrupted cause={}",
                    interrupted.getClass().getSimpleName());
        } finally {
            running.set(false);
        }
    }

    /**
     * Starts participating in the context's lifecycle. The executor's thread is already live once
     * constructed; nothing else needs starting.
     */
    @Override
    public void start() {
        running.set(true);
    }

    /**
     * Stops this bean before {@code ConsumerLifecycleController} (see class Javadoc on
     * {@link #PHASE}) — <strong>quiesces only</strong>: raises {@link #stopping} and shuts the
     * executor down to new submissions, but does not itself wait for a run already in flight to
     * finish, unlike {@link #close()}.
     *
     * <p>An earlier version of this method called {@link #close()}, which also waits (up to {@link
     * #shutdownWait}, 4 minutes). Stacked after this bean's own phase-ordered stop and before
     * {@code ConsumerLifecycleController#stop()}'s own wait on the very same {@link #inFlightRuns}
     * counter, that made the two waits additive — worst case approaching 8 minutes, well past a
     * pod's termination grace period, with the queue consumer still admitting new deliveries for
     * the whole first wait, since {@code ConsumerLifecycleController#stop()} (which is what
     * actually stops the broker processor) had not even started yet (PR #29 review). Quiescing
     * here, without waiting, lets {@code ConsumerLifecycleController#stop()} begin at once — which
     * stops the processor from admitting further deliveries near the start of its own {@code
     * stop()}, before its one remaining wait, {@code awaitInFlightRunsToDrain()} — so both intake
     * paths are quiesced before either wait begins, and the shared counter (now already falling,
     * since the in-flight run noticed {@link #stopping} as soon as this method raised it) drains
     * against that one controller-owned deadline, not two stacked ones.
     *
     * <p>{@link #close()} keeps its own wait for the one case nothing else drains the counter: a
     * standalone or test {@link ResendLegExecutor} with no {@code ConsumerLifecycleController} in
     * the same context to do it (see {@code OperationsConfiguration}'s own Javadoc on that case).
     */
    @Override
    public void stop() {
        stopAndDrain();
        running.set(false);
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public int getPhase() {
        return PHASE;
    }

    /** Raises the stop, drops every queued run and shuts the thread down, exclusive of submit. */
    private synchronized void stopAndDrain() {
        stopping.set(true);
        final List<Runnable> dropped = new ArrayList<>();
        queue.drainTo(dropped);
        for (final Runnable run : dropped) {
            logNotStarted(((AcceptedRun) run).accepted);
        }
        executor.shutdown();
    }

    private static void logNotStarted(final ResendAcceptance.Accepted accepted) {
        LOG.warn("resend-leg run not started cause=shutdown resendRunId={}", accepted.resendRunId());
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
            if (stopping.get()) {
                // Taken off the queue after the stop was raised but before the drain reached it.
                logNotStarted(accepted);
                return;
            }
            inFlightRuns.incrementAndGet();
            try {
                legResender.run(accepted, stopping::get);
            } catch (final RuntimeException failure) {
                LOG.error("resend-leg run failed resendRunId={} cause={}", accepted.resendRunId(),
                        failure.getClass().getSimpleName());
            } finally {
                inFlightRuns.decrementAndGet();
            }
        }
    }
}
