package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
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
 * <p>Each id is checked and claimed in its own transaction ({@link ProcessedRequestRepository
 * #claimResend}), in the fixed refusal order {@code unknown-request} → {@code request-claimed} →
 * {@code resend-in-progress} → {@code not-eligible}; an eligible id's new attempt row is committed
 * before its run begins, and the run ({@link DistributionRunner#runResend}) completes the row under
 * its own leg claim. One id's refusal or failure never stops or changes another's. Every refused id
 * is counted on {@code probation_resend_refusals_total{reason}}, and the run's report is written as
 * one structured log line carrying identifiers and bounded codes only — never the operator's name or
 * the reason's text (FR-004-041).
 *
 * <p>The forced-resend override ({@code allowTerminalSuccess}) is not part of this capability yet:
 * a leg whose latest row is {@code SENT} or {@code NOT_APPLICABLE} is {@code not-eligible}.
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
     * Runs resend-leg over an explicit list of ids.
     *
     * @param request the run's input
     * @return the run's report, or its whole-run refusal
     */
    public ResendRunResult resend(final ResendRequest request) {
        final List<UUID> distinct = new ArrayList<>(new LinkedHashSet<>(request.requestIds()));
        final Optional<WholeRunRefusal> refusal = refusalOf(request, distinct);
        final ResendRunResult result;
        if (refusal.isPresent()) {
            LOG.warn("resend-leg refused reason={}", refusal.get().value());
            result = ResendRunResult.refusedWhole(refusal.get());
        } else {
            result = run(distinct, sanitised(request.operator()), request.allowChangedPayload());
            logReport(result);
        }
        return result;
    }

    private Optional<WholeRunRefusal> refusalOf(final ResendRequest request,
                                                final List<UUID> distinct) {
        final Optional<WholeRunRefusal> refusal;
        if (distinct.isEmpty() || blank(request.operator()) || blank(request.reason())
                || sanitised(request.operator()).isEmpty()) {
            refusal = Optional.of(WholeRunRefusal.MISSING_ARGUMENT);
        } else if (request.operator().length() > MAX_TEXT_LENGTH
                || request.reason().length() > MAX_TEXT_LENGTH) {
            refusal = Optional.of(WholeRunRefusal.ARGUMENT_TOO_LONG);
        } else if (distinct.size() > maxIdsPerRun) {
            refusal = Optional.of(WholeRunRefusal.TOO_MANY_REQUEST_IDS);
        } else if (pauseGate.pausedByOperator()) {
            refusal = Optional.of(WholeRunRefusal.INTAKE_PAUSED);
        } else {
            refusal = Optional.empty();
        }
        return refusal;
    }

    // One report entry per distinct id is the report itself (FR-004-039).
    @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
    private ResendRunResult run(final List<UUID> distinct, final String operator,
                                final boolean allowChangedPayload) {
        final UUID resendRunId = UUID.randomUUID();
        final List<ResendRunResult.Entry> entries = new ArrayList<>();
        boolean paused = false;
        for (final UUID requestId : distinct) {
            paused = paused || pauseGate.pausedByOperator();
            final ResendEntryOutcome outcome = paused ? new ResendEntryOutcome.NotRun()
                    : runOne(requestId, resendRunId, operator, allowChangedPayload);
            entries.add(new ResendRunResult.Entry(requestId, outcome));
        }
        return new ResendRunResult(Optional.empty(), Optional.of(resendRunId), allowChangedPayload,
                entries, false);
    }

    private ResendEntryOutcome runOne(final UUID requestId, final UUID resendRunId,
                                      final String operator, final boolean allowChangedPayload) {
        ResendEntryOutcome outcome;
        try {
            final ResendClaim claim = store.claimResend(SOURCE, requestId, resendRunId, operator,
                    RunClaim.fresh());
            outcome = switch (claim) {
                case ResendClaim.Refused refused -> refuse(requestId, resendRunId, refused);
                case ResendClaim.Claimed claimed -> runClaimed(requestId, resendRunId, claimed,
                        allowChangedPayload);
            };
        } catch (final DataAccessException storeFailure) {
            // The store failed for this id only: it is reported not-run, counted nowhere else, and
            // the run carries on with the next id (FR-004-022). Not swallowed — logged at ERROR.
            LOG.error("resend-leg store-unavailable requestId={} resendRunId={}", requestId,
                    resendRunId);
            outcome = new ResendEntryOutcome.NotRun();
        }
        return outcome;
    }

    private ResendEntryOutcome refuse(final UUID requestId, final UUID resendRunId,
                                      final ResendClaim.Refused refused) {
        metrics.resendRefusal(refused.reason());
        LOG.info("resend-leg refused requestId={} resendRunId={} reason={}", requestId, resendRunId,
                refused.reason().wireValue());
        return new ResendEntryOutcome.Refused(refused.reason());
    }

    private ResendEntryOutcome runClaimed(final UUID requestId, final UUID resendRunId,
                                          final ResendClaim.Claimed claimed,
                                          final boolean allowChangedPayload) {
        final RunResult result = runner.runResend(claimed.command(), claimed.attempt(),
                allowChangedPayload);
        final ResendEntryOutcome outcome;
        if (result.recorded()) {
            outcome = new ResendEntryOutcome.Ran(result.status(), result.reason());
        } else {
            metrics.staleRunnerRejection();
            LOG.error("resend-leg stale-runner rejection requestId={} resendRunId={} attempt={}",
                    requestId, resendRunId, claimed.attempt().attempt());
            outcome = new ResendEntryOutcome.NotRun();
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
                        + "allowTerminalSuccess=false ids={} taken={} sent={} summary={} entries={}",
                result.resendRunId().orElseThrow(), result.allowChangedPayloadRequested(),
                result.entries().size(), taken,
                byOutcome.getOrDefault(LegStatus.SENT.wireValue(), 0), byOutcome, entries);
    }

    private static boolean blank(final String value) {
        return value == null || value.isBlank();
    }

    private static String sanitised(final String operator) {
        return LINE_BREAKS.matcher(operator).replaceAll(" ").strip();
    }
}
