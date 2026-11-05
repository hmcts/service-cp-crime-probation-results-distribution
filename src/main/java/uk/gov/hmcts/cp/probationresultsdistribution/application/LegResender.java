package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository.ResendClaim;

/**
 * Resend-leg (design §4.8 action 3; slice 004, FR-004-018–FR-004-039): re-runs the HMPPS leg for an
 * explicit list of {@code requestId}s, each checked, claimed and run on its own.
 *
 * <p>The input is refused whole, before anything is read, when it is incomplete or too long, when
 * the distinct ids exceed {@code distribution.resend.max-ids-per-run}, or while intake is paused by
 * an operator — read against the reconciled in-memory flag ({@link IntakePauseGate}), never a fresh
 * store read (FR-004-019–FR-004-021). Duplicates are collapsed first. The ids then run one after
 * another (research.md Decision 4); before each, the pause is read again, and once a pause has begun
 * no further id is claimed and the rest are reported {@code not-run}.
 *
 * <p>The run is split in two (slice 005, FR-005-015): {@link #accept} applies every whole-run check
 * and assigns the {@code resendRunId}, claiming nothing, and {@link #run} takes an accepted run's
 * ids one after another. {@link #resend} is the two in turn, as the command line calls it. The run
 * step also reads a plain stop signal before each id, ahead of the pause read: once it is raised
 * (the serving context shutting down) the run claims nothing further and reports the rest
 * {@code not-run}, exactly as a pause that begins mid-run does.
 *
 * <p>Each id is checked and claimed in its own transaction ({@link ProcessedRequestRepository
 * #claimResend}), in the fixed refusal order {@code unknown-request} → {@code request-claimed} →
 * {@code resend-in-progress} → {@code not-eligible}; an eligible id's new attempt row is committed
 * before its run begins, and the run ({@link DistributionRunner#runResend}) completes the row under
 * its own leg claim. One id's refusal or failure never stops or changes another's. Every refused id
 * is counted on {@code probation_resend_refusals_total{reason}}, and the run's report is written as
 * one structured log line carrying identifiers and bounded codes only — never the operator's name or
 * the reason's text (FR-004-041).
 *
 * <p>A store failure is never swallowed (spec edge case "The store fails during a resend run"):
 * an id whose claim fails is {@code not-run}; an id claimed but whose outcome could not be recorded
 * — the store failed after the claim, or the completion was fenced — is {@code claimed-incomplete},
 * its row left for the guarded rollback close-in-flight procedure; each is logged at ERROR with a
 * bounded cause, and the run's report still stands but its result is failed. A store that cannot
 * answer the first pause read refuses the run whole {@code store-unavailable}; one that cannot
 * answer a later pause read stops the run claiming, as a pause would, and fails it.
 *
 * <p>The forced-resend override ({@code allowTerminalSuccess}, FR-004-036–FR-004-038) is applied
 * per ID by the claim: a leg whose latest row is {@code SENT} or {@code NOT_APPLICABLE} is
 * {@code not-eligible} without it, and with it is claimed on a new resend row and run exactly as any
 * resend; for a {@code FAILED} latest row the override is not used.
 */
public final class LegResender {

    private static final Logger LOG = LoggerFactory.getLogger(LegResender.class);
    private static final String SOURCE = "RESULTS";
    private static final int MAX_TEXT_LENGTH = 200;
    private static final Pattern LINE_BREAKS = Pattern.compile("\\p{Cntrl}+");

    private final IntakePauseGate pauseGate;
    private final ProcessedRequestRepository store;
    private final DistributionRunner runner;
    private final OutcomeMetrics metrics;
    private final int maxIdsPerRun;

    /**
     * Creates the capability.
     *
     * @param pauseGate    the reconciled operator-pause read
     * @param store        the processed-log store the per-ID check and claim run against
     * @param runner       the pipeline each claimed attempt runs through
     * @param metrics      the telemetry port
     * @param maxIdsPerRun {@code distribution.resend.max-ids-per-run}
     */
    public LegResender(final IntakePauseGate pauseGate, final ProcessedRequestRepository store,
                       final DistributionRunner runner, final OutcomeMetrics metrics,
                       final int maxIdsPerRun) {
        this.pauseGate = pauseGate;
        this.store = store;
        this.runner = runner;
        this.metrics = metrics;
        this.maxIdsPerRun = maxIdsPerRun;
    }

    /**
     * Runs resend-leg over an explicit list of ids: the accept step, then, unless the run was
     * refused whole, the run step with no stop signal.
     *
     * @param request the run's input
     * @return the run's report, or its whole-run refusal
     */
    public ResendRunResult resend(final ResendRequest request) {
        return switch (accept(request)) {
            case ResendAcceptance.Refused refused -> ResendRunResult.refusedWhole(refused.refusal());
            case ResendAcceptance.Accepted accepted -> run(accepted, () -> false);
        };
    }

    /**
     * The accept step: collapses duplicates, applies every whole-run check and, when none refuses
     * the run, assigns its {@code resendRunId}. Nothing is claimed or written here.
     *
     * @param request the run's input
     * @return the run refused whole, or accepted with its run id
     */
    public ResendAcceptance accept(final ResendRequest request) {
        final List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(request.requestIds()));
        final Optional<WholeRunRefusal> refusal = refusalOf(request, distinct);
        final ResendAcceptance acceptance;
        if (refusal.isPresent()) {
            LOG.warn("resend-leg refused reason={}", refusal.get().value());
            acceptance = new ResendAcceptance.Refused(refusal.get());
        } else {
            final Optional<String> forcedResendReason = request.allowTerminalSuccess()
                    ? Optional.of(sanitised(request.reason())) : Optional.empty();
            acceptance = new ResendAcceptance.Accepted(UUID.randomUUID(), distinct,
                    sanitised(request.operator()), request.allowChangedPayload(),
                    forcedResendReason);
        }
        return acceptance;
    }

    /**
     * The run step: checks, claims and runs each accepted id in turn and logs the run's report.
     * Before each id the stop signal is read, then the pause; once either says stop, no further id
     * is claimed and the rest are reported {@code not-run} (FR-004-021).
     *
     * @param accepted      a run the accept step accepted
     * @param stopRequested raised when the run must claim nothing further, for example at shutdown
     * @return the run's report
     */
    public ResendRunResult run(final ResendAcceptance.Accepted accepted,
                               final BooleanSupplier stopRequested) {
        final ResendRunResult result = runIds(accepted, stopRequested);
        logReport(result);
        return result;
    }

    private Optional<WholeRunRefusal> refusalOf(final ResendRequest request,
                                                final List<UUID> distinct) {
        Optional<WholeRunRefusal> refusal;
        if (distinct.isEmpty() || blank(request.operator()) || blank(request.reason())
                || sanitised(request.operator()).isEmpty()) {
            refusal = Optional.of(WholeRunRefusal.MISSING_ARGUMENT);
        } else if (request.operator().length() > MAX_TEXT_LENGTH
                || request.reason().length() > MAX_TEXT_LENGTH) {
            refusal = Optional.of(WholeRunRefusal.ARGUMENT_TOO_LONG);
        } else if (distinct.size() > maxIdsPerRun) {
            refusal = Optional.of(WholeRunRefusal.TOO_MANY_REQUEST_IDS);
        } else {
            try {
                refusal = pauseGate.pausedByOperator()
                        ? Optional.of(WholeRunRefusal.INTAKE_PAUSED) : Optional.empty();
            } catch (final DataAccessException storeFailure) {
                // The pause cannot be confirmed, so nothing is claimed: refused whole, nothing changed.
                LOG.error("resend-leg pause read failed reason=store-unavailable cause={}",
                        storeFailure.getClass().getSimpleName());
                refusal = Optional.of(WholeRunRefusal.STORE_UNAVAILABLE);
            }
        }
        return refusal;
    }

    // One report entry per distinct id is the report itself (FR-004-039).
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private ResendRunResult runIds(final ResendAcceptance.Accepted accepted,
                                   final BooleanSupplier stopRequested) {
        final RunState state = new RunState(accepted.resendRunId(), accepted.forcedResendReason(),
                stopRequested);
        final List<ResendRunResult.Entry> entries = new ArrayList<>();
        for (final UUID requestId : accepted.requestIds()) {
            final ResendEntryOutcome outcome = stopClaiming(state, requestId)
                    ? new ResendEntryOutcome.NotRun()
                    : runOne(requestId, state, accepted.operator(), accepted.allowChangedPayload());
            entries.add(new ResendRunResult.Entry(requestId, outcome));
        }
        return new ResendRunResult(Optional.empty(), Optional.of(state.resendRunId),
                accepted.allowChangedPayload(), accepted.forcedResendReason().isPresent(), entries,
                state.storeFailed);
    }

    private boolean stopClaiming(final RunState state, final UUID requestId) {
        if (!state.stopped && state.stopRequested.getAsBoolean()) {
            LOG.warn("resend-leg stopped claiming reason=stop-requested requestId={} resendRunId={}",
                    requestId, state.resendRunId);
            state.stopped = true;
        }
        if (!state.stopped) {
            try {
                state.stopped = pauseGate.pausedByOperator();
            } catch (final DataAccessException storeFailure) {
                // The pause cannot be confirmed: claim nothing further, as a pause would, and fail
                // the run (spec edge case "The store fails during a resend run").
                LOG.error("resend-leg pause read failed reason=store-unavailable requestId={} "
                                + "resendRunId={} cause={}", requestId, state.resendRunId,
                        storeFailure.getClass().getSimpleName());
                state.stopped = true;
                state.storeFailed = true;
            }
        }
        return state.stopped;
    }

    private ResendEntryOutcome runOne(final UUID requestId, final RunState state,
                                      final String operator, final boolean allowChangedPayload) {
        return claim(requestId, state, operator)
                .map(claim -> outcomeOf(claim, requestId, state, allowChangedPayload))
                .orElseGet(ResendEntryOutcome.NotRun::new);
    }

    private ResendEntryOutcome outcomeOf(final ResendClaim claim, final UUID requestId,
                                         final RunState state, final boolean allowChangedPayload) {
        return switch (claim) {
            case ResendClaim.Refused refused -> refuse(requestId, state.resendRunId, refused);
            case ResendClaim.Claimed claimed -> runClaimed(requestId, state, claimed,
                    allowChangedPayload);
        };
    }

    private Optional<ResendClaim> claim(final UUID requestId, final RunState state,
                                        final String operator) {
        Optional<ResendClaim> claim;
        try {
            claim = Optional.of(store.claimResend(SOURCE, requestId, state.resendRunId, operator,
                    RunClaim.fresh(), state.forcedResendReason));
        } catch (final DataAccessException storeFailure) {
            // The claim did not commit: nothing was written for this id, so it is not-run. The run
            // carries on with the next id (FR-004-022) and its result is failed.
            LOG.error("resend-leg claim failed reason=store-unavailable requestId={} resendRunId={} "
                    + "cause={}", requestId, state.resendRunId,
                    storeFailure.getClass().getSimpleName());
            state.storeFailed = true;
            claim = Optional.empty();
        }
        return claim;
    }

    private ResendEntryOutcome refuse(final UUID requestId, final UUID resendRunId,
                                      final ResendClaim.Refused refused) {
        metrics.resendRefusal(refused.reason());
        LOG.info("resend-leg refused requestId={} resendRunId={} reason={}", requestId, resendRunId,
                refused.reason().wireValue());
        return new ResendEntryOutcome.Refused(refused.reason());
    }

    private ResendEntryOutcome runClaimed(final UUID requestId, final RunState state,
                                          final ResendClaim.Claimed claimed,
                                          final boolean allowChangedPayload) {
        ResendEntryOutcome outcome;
        try {
            final RunResult result = runner.runResend(claimed.command(), claimed.attempt(),
                    allowChangedPayload);
            if (result.recorded()) {
                outcome = new ResendEntryOutcome.Ran(result.status(), result.reason());
            } else {
                metrics.staleRunnerRejection();
                LOG.error("resend-leg stale-runner rejection requestId={} resendRunId={} "
                        + "attempt={}", requestId, state.resendRunId, claimed.attempt().attempt());
                outcome = new ResendEntryOutcome.ClaimedIncomplete();
            }
        } catch (final DataAccessException storeFailure) {
            // The attempt row is committed PENDING and its POST may have gone out, but its outcome
            // was not recorded: reported claimed-incomplete, never not-run, and the run is failed.
            LOG.error("resend-leg completion failed reason=store-unavailable requestId={} "
                            + "resendRunId={} attempt={} cause={}", requestId, state.resendRunId,
                    claimed.attempt().attempt(), storeFailure.getClass().getSimpleName());
            state.storeFailed = true;
            outcome = new ResendEntryOutcome.ClaimedIncomplete();
        }
        return outcome;
    }

    private static void logReport(final ResendRunResult result) {
        final Map<String, Integer> byOutcome = new TreeMap<>();
        int taken = 0;
        for (final ResendRunResult.Entry entry : result.entries()) {
            byOutcome.merge(entry.outcome().reportValue(), 1, Integer::sum);
            if (entry.outcome() instanceof ResendEntryOutcome.Ran) {
                taken++;
            }
        }
        final List<String> entries = result.entries().stream()
                .map(entry -> entry.requestId() + "=" + entry.outcome().reportValue()).toList();
        LOG.info("resend-leg report resendRunId={} leg=HMPPS allowChangedPayload={} "
                        + "allowTerminalSuccess={} ids={} taken={} sent={} storeFailed={} "
                        + "summary={} entries={}",
                result.resendRunId().orElseThrow(), result.allowChangedPayloadRequested(),
                result.allowTerminalSuccessRequested(), result.entries().size(), taken,
                byOutcome.getOrDefault(LegStatus.SENT.wireValue(), 0), result.storeFailed(),
                byOutcome, entries);
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    private static String sanitised(final String text) {
        return LINE_BREAKS.matcher(text).replaceAll(" ").strip();
    }

    /**
     * One run's id, the forced-resend reason when the override was requested, its stop signal, and
     * its mutable progress: whether claiming has stopped, whether the store failed.
     */
    private static final class RunState {

        private final UUID resendRunId;
        private final Optional<String> forcedResendReason;
        private final BooleanSupplier stopRequested;
        private boolean stopped;
        private boolean storeFailed;

        private RunState(final UUID resendRunId, final Optional<String> forcedResendReason,
                         final BooleanSupplier stopRequested) {
            this.resendRunId = resendRunId;
            this.forcedResendReason = forcedResendReason;
            this.stopRequested = stopRequested;
        }
    }
}
