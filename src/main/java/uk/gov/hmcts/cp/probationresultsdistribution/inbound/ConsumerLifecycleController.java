package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.flywaydb.core.Flyway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.SmartLifecycle;
import uk.gov.hmcts.cp.probationresultsdistribution.application.IntakePauseGate;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.IntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedLogProbe;

/**
 * The only thing permitted to start or stop the consumer (design §4.2 Intake; FR-002-009).
 *
 * <p>Intake is a decision, not a side effect. Left to itself the processor would consume whatever the
 * queue held, whether or not this pod could record what it did — and a store outage would then be
 * spent one delivery at a time, {@code maxDeliveryCount} deliveries per message, parking work that was
 * never faulty. Putting every start and stop behind one component is what makes "do not consume
 * without a store" expressible. The gauge and counter describe the outage cycle (RUNNING → SUSPENDED →
 * RUNNING) and say nothing about the gated start, which readiness already describes.
 *
 * <p>Nothing transitions on a callback thread: every start, stop and migration runs on one
 * single-threaded scheduled executor, which also runs the probe. Transitions are serialised, each is
 * idempotent, and {@code stop()} is never called from inside a message callback, which would deadlock.
 *
 * <p>Single replica: suspension is a per-pod decision, so cluster-safe suspension belongs to the
 * scale-out story; the constraint is recorded in the plan.
 */
public class ConsumerLifecycleController implements SmartLifecycle, StoreGate, IntakePauseGate {

    private static final Logger LOG = LoggerFactory.getLogger(ConsumerLifecycleController.class);

    /** How long shutdown waits for the transition thread to finish what it was doing. */
    private static final Duration SHUTDOWN_GRACE = Duration.ofSeconds(30);

    /** How often {@link #awaitInFlightRunsToDrain()} rechecks the in-flight count. */
    private static final Duration DRAIN_POLL_INTERVAL = Duration.ofMillis(200);

    /** The PMD suppression this class's several deliberately-total catches share. */
    private static final String SUPPRESS_GENERIC_CATCH = "PMD.AvoidCatchingGenericException";

    private final ServiceBusProcessorClient processor;
    private final ProcessedLogProbe storeProbe;
    private final IntakeControlRepository intakeControl;
    private final Supplier<Flyway> flyway;
    private final MetricRegistry metrics;
    private final Duration probeInterval;
    private final AtomicInteger inFlightRuns;
    private final Duration drainTimeout;

    private final ScheduledExecutorService transitions = Executors.newSingleThreadScheduledExecutor(
            runnable -> {
                final Thread thread = new Thread(runnable, "probation-results-distribution-intake");
                thread.setDaemon(true);
                return thread;
            });

    private final AtomicReference<State> state = new AtomicReference<>(State.AWAITING_STORE);
    private volatile boolean migrated;
    private volatile boolean active;
    private volatile boolean processorRunning;
    private volatile ScheduledFuture<?> probeSchedule;
    private volatile boolean gatedStartCompleted;
    private volatile boolean operatorPaused;
    private volatile boolean requestedPause;

    /**
     * Creates the controller over everything a gated start needs.
     *
     * @param processor     the client it owns and is the only thing to start
     * @param storeProbe    the availability question the gated start waits on
     * @param intakeControl the persisted operator-pause record (slice 004, FR-004-052), reconciled
     *                      on every probe
     * @param flyway        the deferred migration, run once before intake starts
     * @param metrics       the instrument surface suspensions are counted and gauged on
     * @param probeInterval how often the store is asked, driving the start and the resume
     * @param inFlightRuns  the number of deliveries the processor is currently running; shared
     *                      with the processor's {@code processMessage} callback, which increments
     *                      it before a run and decrements it after
     * @param drainTimeout  how long {@link #stop()} waits for {@code inFlightRuns} to reach zero
     *                      before giving up, so a rolling deploy does not kill a run mid-POST
     */
    public ConsumerLifecycleController(final ServiceBusProcessorClient processor,
                                       final ProcessedLogProbe storeProbe,
                                       final IntakeControlRepository intakeControl,
                                       final Supplier<Flyway> flyway,
                                       final MetricRegistry metrics,
                                       final Duration probeInterval,
                                       final AtomicInteger inFlightRuns,
                                       final Duration drainTimeout) {
        this.processor = processor;
        this.storeProbe = storeProbe;
        this.intakeControl = intakeControl;
        this.flyway = flyway;
        this.metrics = metrics;
        this.probeInterval = probeInterval;
        this.inFlightRuns = inFlightRuns;
        this.drainTimeout = drainTimeout;
    }

    /** What intake is doing. */
    private enum State {
        /** Started, not yet consuming: the store has not answered once. */
        AWAITING_STORE,
        /** Consuming. */
        RUNNING,
        /** The store went away and the processor has been asked to stop, but has not yet stopped. */
        SUSPENDING,
        /** Stopped consuming because the store went away. */
        SUSPENDED,
        /** The context is closing. Terminal: nothing may start intake from here. */
        STOPPING,
        /** Closed. */
        STOPPED
    }

    @Override
    public void start() {
        active = true;
        LOG.info("intake is gated on the processed log; probing every {} before consuming anything",
                probeInterval);
        probeSchedule = transitions.scheduleWithFixedDelay(
                this::probe, 0, probeInterval.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public void stop() {
        state.getAndUpdate(current -> terminal(current) ? current : State.STOPPING);
        active = false;
        cancelProbeSchedule();
        submitShutdown();
        transitions.shutdown();
        awaitTransitionsToFinish();
        shutdownIntake();
        // Run on this (the container's) shutdown thread, not the transition executor: SmartLifecycle
        // already blocks the caller until stop() returns, and the processor's own SDK stop() call
        // above does not wait for an in-flight delivery to finish (review of PR #8) - a rolling
        // deploy could otherwise kill a run mid-POST, and the redelivery could send it again.
        awaitInFlightRunsToDrain();
    }

    /**
     * Blocks until no delivery is still running, or until {@link #drainTimeout} elapses, whichever
     * comes first. Every caller — {@link #stop()}, {@link #stopForOperatorPause()} and
     * {@link #stopForSuspension()} — has already stopped the processor by the time this runs, so no
     * further delivery starts while this waits.
     */
    private void awaitInFlightRunsToDrain() {
        final long deadline = System.nanoTime() + drainTimeout.toNanos();
        while (inFlightRuns.get() > 0 && System.nanoTime() < deadline) {
            try {
                Thread.sleep(DRAIN_POLL_INTERVAL.toMillis());
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        final int stillRunning = inFlightRuns.get();
        if (stillRunning > 0) {
            LOG.warn("{} delivery(ies) still in flight after waiting {}; their lock will expire and "
                    + "the broker will redeliver", stillRunning, drainTimeout);
        }
    }

    private void cancelProbeSchedule() {
        final ScheduledFuture<?> schedule = probeSchedule;
        if (schedule != null) {
            schedule.cancel(false);
        }
    }

    private void submitShutdown() {
        try {
            transitions.execute(this::shutdownIntake);
        } catch (final RejectedExecutionException alreadyClosing) {
            LOG.debug("the transition thread was already closing; the stop runs inline instead");
        }
    }

    private synchronized void shutdownIntake() {
        if (processorRunning) {
            processor.stop();
            processorRunning = false;
            LOG.info("intake stopped for shutdown; queue={}", processor.getQueueName());
        }
        state.set(State.STOPPED);
    }

    private boolean closing() {
        return terminal(state.get());
    }

    private static boolean terminal(final State current) {
        return current == State.STOPPING || current == State.STOPPED;
    }

    @Override
    public boolean isRunning() {
        return active;
    }

    /**
     * Whether the gated start has completed: the migration ran and the processor started.
     *
     * @return whether this pod has got as far as consuming
     */
    public boolean intakeStarted() {
        return gatedStartCompleted;
    }

    private void awaitTransitionsToFinish() {
        try {
            if (!transitions.awaitTermination(SHUTDOWN_GRACE.toSeconds(), TimeUnit.SECONDS)) {
                LOG.warn("the intake transition thread did not finish within {}; shutting down anyway",
                        SHUTDOWN_GRACE);
            }
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            LOG.warn("interrupted while waiting for the intake transition thread to finish");
        }
    }

    @Override
    public boolean storeAvailable() {
        return storeProbe.available();
    }

    /**
     * The confirmed operator-pause flag (slice 004, FR-004-052): {@code true} only once the
     * processor has actually stopped and any run it had in flight has drained — or immediately, when
     * nothing was consuming to begin with — never merely because the operator's pause request has
     * been read off {@code intake_control} (review of PR #8/#23, M3). Read by resend-leg,
     * purge-queue and rollback-close-in-flight (research.md Decisions 2, 3) rather than asking the
     * store afresh, so none of them can proceed while a send this pod is still running could race
     * their work.
     *
     * @return {@code true} while intake is confirmed paused by an operator
     */
    @Override
    public boolean pausedByOperator() {
        return operatorPaused;
    }

    /**
     * {@code true} the instant a pause is requested, confirmed or not (review of PR #23 round 4;
     * design H43, issue #27): resend-leg reads this instead of {@link #pausedByOperator()}, since,
     * unlike rollback-close-in-flight and purge-queue, it has no in-flight send to race and must
     * refuse at once rather than let a pause drain for up to {@link #drainTimeout} first. The
     * {@code ||} is belt and braces: {@link #setOperatorPaused} never sets {@link #operatorPaused}
     * true without {@link #requestedPause} already being true, but the two are set together, not
     * atomically, so this does not depend on that ordering holding.
     *
     * @return {@code true} once a pause has been requested, confirmed or not
     */
    @Override
    public boolean pauseRequested() {
        return requestedPause || operatorPaused;
    }

    /**
     * Reads {@code intake_control.paused_by_operator} into {@link #requestedPause}, the signal
     * {@link #probe()} dispatches on. {@link #operatorPaused} (and its gauge) is confirmed here,
     * immediately, only when nothing could possibly be mid-delivery: unpausing (always safe to
     * report at once), or a pause requested while intake was not {@code RUNNING}/{@code SUSPENDING}
     * — including a pod that starts (or restarts) with the row already paused, which never reaches
     * {@link #resume()} on its first tick and so never runs a delivery to race. A pause requested
     * while {@code RUNNING} or {@code SUSPENDING} is left to {@link #stopForOperatorPause()}, which
     * confirms it only once the processor has stopped and {@link #inFlightRuns} has drained (M3).
     */
    private void reconcileOperatorPause() {
        final boolean paused = intakeControl.pausedByOperator();
        final boolean changed = paused != requestedPause;
        requestedPause = paused;
        if (!paused || (state.get() != State.RUNNING && state.get() != State.SUSPENDING)) {
            setOperatorPaused(paused);
        }
        if (changed) {
            LOG.info("intake operator-pause requested to {}", paused);
        }
    }

    private void setOperatorPaused(final boolean paused) {
        if (paused != operatorPaused) {
            operatorPaused = paused;
            metrics.recordOperatorPause(paused);
        }
    }

    @Override
    public void suspendIntake() {
        try {
            transitions.execute(this::suspend);
        } catch (final RejectedExecutionException afterShutdown) {
            LOG.debug("intake suspension requested after shutdown; there is nothing to suspend");
        }
    }

    @SuppressWarnings(SUPPRESS_GENERIC_CATCH)
    // Deliberately total: whatever ends a probe — an unreachable store, a failed migration, a
    // processor that refused to start — the schedule must outlive it, or a service that has stopped
    // asking whether its store is back never comes back. The next probe is the retry.
    private void probe() {
        try {
            // Migrated first, before anything queries the schema: reconcileOperatorPause() reads
            // intake_control, which does not exist on a brand-new database until Flyway has run
            // (deferred from context refresh; review of PR #23). Left in its old order, every tick
            // would fail the pause read before ever reaching resume() (where migration used to run),
            // and a fresh database would never start intake. Gated on storeProbe.available(), since
            // migrating needs a connection; reconcileOperatorPause() below is not gated on it, so a
            // store that only looks unavailable to this cheap probe does not stop the pause itself
            // from being read and, if it genuinely is down, that read fails there and is retried like
            // any other probe failure, exactly as before this fix.
            if (!migrated && storeProbe.available()) {
                migrateOnce();
            }
            // Reconciled next, before the store-outage decision: a pod that starts (or restarts)
            // with the row already paused must never reach resume() on its first tick, whatever the
            // store answers (research.md Decision 3; provisional FR-004-052).
            reconcileOperatorPause();
            if (requestedPause) {
                suspendForOperatorPause();
            } else if (state.get() != State.RUNNING && !closing() && storeProbe.available()) {
                resume();
            }
        } catch (final RuntimeException failed) {
            LOG.error("intake could not be started; the next probe will try again; type={}",
                    failed.getClass().getName());
        }
    }

    private synchronized void resume() {
        final State from = state.get();
        if (from != State.RUNNING && !terminal(from) && !requestedPause) {
            migrateOnce();
            if (state.compareAndSet(from, State.RUNNING)) {
                startConsuming(from);
            } else {
                LOG.info("intake is closing; the gated start was abandoned rather than completed");
            }
        }
    }

    /**
     * An operator pause was read as in force: stop consuming, exactly as a store outage would, but
     * under the {@code operator} cause rather than {@code database} (research.md Decision 3). A pod
     * already stopped for any other reason is left alone.
     */
    private synchronized void suspendForOperatorPause() {
        final State from = state.get();
        if (from == State.RUNNING) {
            if (state.compareAndSet(State.RUNNING, State.SUSPENDING)) {
                stopForOperatorPause();
            }
        } else if (from == State.SUSPENDING) {
            stopForOperatorPause();
        }
    }

    @SuppressWarnings(SUPPRESS_GENERIC_CATCH)
    // Total, and it does not rethrow, for the same reason stopForSuspension() does not: this runs on
    // the transition executor, where a thrown exception is reported by nobody. The next probe retries.
    private void stopForOperatorPause() {
        try {
            processor.stop();
        } catch (final RuntimeException refused) {
            LOG.error("intake could not be stopped for an operator pause; the next probe retries; "
                    + "queue={} type={}", processor.getQueueName(), refused.getClass().getName());
            return;
        }
        processorRunning = false;
        // The SDK's own stop() does not wait for an in-flight delivery to finish, so a POST could
        // still be going out here. Blocking this transition tick on the drain (bounded at
        // drainTimeout, the same wait stop() itself uses) is what keeps resend-leg, purge-queue and
        // rollback-close-in-flight from proceeding against a run that has not actually finished
        // (review of PR #8/#23, M3): none of them sees pausedByOperator() = true until this returns.
        awaitInFlightRunsToDrain();
        if (state.compareAndSet(State.SUSPENDING, State.SUSPENDED)) {
            setOperatorPaused(true);
            LOG.info("intake suspended by an operator pause; queue={}", processor.getQueueName());
        }
    }

    @SuppressWarnings(SUPPRESS_GENERIC_CATCH)
    // Deliberately total, and it rethrows: whatever the SDK raises when a processor refuses to start,
    // the state this method moved before the call has to be given back or the probe would see RUNNING
    // for ever and never retry. The failure is then the probe's to report.
    private void startConsuming(final State from) {
        processorRunning = true;
        try {
            processor.start();
        } catch (final RuntimeException refused) {
            processorRunning = false;
            state.compareAndSet(State.RUNNING, from);
            throw refused;
        }
        gatedStartCompleted = true;
        metrics.recordIntakeSuspended(false);
        if (from == State.SUSPENDED || from == State.SUSPENDING) {
            LOG.info("the processed log answered; intake resumed; queue={}", processor.getQueueName());
        } else {
            LOG.info("the processed log answered; intake started; queue={}", processor.getQueueName());
        }
    }

    private synchronized void suspend() {
        final State from = state.get();
        if (from == State.RUNNING) {
            if (!state.compareAndSet(State.RUNNING, State.SUSPENDING)) {
                return;
            }
            LOG.warn("the processed log is unreachable; stopping intake so the delivery budget is not "
                    + "spent on an outage of ours; queue={}", processor.getQueueName());
            stopForSuspension();
        } else if (from == State.SUSPENDING) {
            stopForSuspension();
        }
    }

    @SuppressWarnings(SUPPRESS_GENERIC_CATCH)
    // Total, and it does not rethrow: this runs as a task on the transition executor, where a thrown
    // exception is reported by nobody and simply ends the task. Catching it turns a silently lost
    // failure into an ERROR; the retry is the next delivery's request, and SUSPENDING keeps it live.
    private void stopForSuspension() {
        try {
            processor.stop();
        } catch (final RuntimeException refused) {
            LOG.error("intake could not be stopped, so the pod is still consuming with no store to "
                    + "record what it does; the next delivery asks again; queue={} type={}",
                    processor.getQueueName(), refused.getClass().getName());
            return;
        }
        processorRunning = false;
        // The SDK's own stop() does not wait for an in-flight delivery to finish, and reconcileOperatorPause()
        // treats any SUSPENDED state as proof nothing could be mid-delivery (review of PR #23 round 5): without
        // this wait, a store-outage suspension could reach SUSPENDED while a delivery begun before the outage
        // was still sending, and an operator pause requested in that window would be confirmed at once,
        // letting rollback-close-in-flight/purge-queue proceed against a run that has not actually finished.
        awaitInFlightRunsToDrain();
        if (state.compareAndSet(State.SUSPENDING, State.SUSPENDED)) {
            metrics.intakeSuspension();
            metrics.recordIntakeSuspended(true);
        }
    }

    private void migrateOnce() {
        if (migrated) {
            return;
        }
        final Flyway migrations = flyway.get();
        if (migrations == null) {
            LOG.info("no Flyway configured; nothing to migrate before intake starts");
        } else {
            LOG.info("running the deferred schema migration before intake starts");
            migrations.migrate();
        }
        migrated = true;
    }
}
