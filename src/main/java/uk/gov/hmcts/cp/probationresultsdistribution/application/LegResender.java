package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * Resend-leg (design §4.8 action 3; slice 004, FR-004-018 onward).
 *
 * <p>Seam (T038): the constructor carries the collaborators the per-ID check, claim and run need;
 * the run itself still has only the T036 pause checks (FR-004-021). T043 onward replaces the loop
 * body.
 */
public final class LegResender {

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
        final ResendRunResult outcome;
        if (pauseGate.pausedByOperator()) {
            outcome = ResendRunResult.refusedWhole(
                    uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal.INTAKE_PAUSED);
        } else {
            final List<ResendRunResult.Entry> entries = new ArrayList<>();
            for (final UUID requestId : request.requestIds()) {
                if (pauseGate.pausedByOperator()) {
                    entries.add(new ResendRunResult.Entry(requestId, new ResendEntryOutcome.NotRun()));
                }
            }
            outcome = new ResendRunResult(Optional.empty(), Optional.of(UUID.randomUUID()),
                    request.allowChangedPayload(), entries);
        }
        return outcome;
    }
}
