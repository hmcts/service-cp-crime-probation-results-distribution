package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Resend-leg (design §4.8 action 3; slice 004, FR-004-018 onward).
 *
 * <p><strong>Pause-only stub (Phase 5, T036).</strong> This phase wires only the pause-check this
 * capability shares with {@link PurgeQueueAction} (FR-004-021): refuse the whole run while intake
 * is paused by an operator — read against the reconciled in-memory flag
 * ({@link IntakePauseGate}, research.md Decision 3), never a fresh store read, exactly like
 * purge-queue's own read-before-remove — and stop claiming further ids the moment a pause is read
 * mid-run. Phase 6 (T043 onward) replaces {@link #resend(List)}'s body with the full eligibility,
 * claim and send pipeline (FR-004-018–FR-004-041), keeping this method's two pause checks as its
 * first and per-id guard; nothing here should block or complicate that work — this class is
 * deliberately minimal and narrowly scoped to the one behaviour {@code ResendConcurrencyIT}'s
 * pause-related cases need (FR-004-021).
 */
public final class LegResender {

    private final IntakePauseGate pauseGate;

    /**
     * Creates the stub.
     *
     * @param pauseGate the reconciled operator-pause read
     */
    public LegResender(final IntakePauseGate pauseGate) {
        this.pauseGate = pauseGate;
    }

    /**
     * The pause-only stub run (T036). Phase 6 replaces the body with the real per-id pipeline.
     *
     * @param requestIds the distinct ids to resend (duplicate-collapsing is T043's job)
     * @return a whole-run refusal when paused at the start; otherwise the ids not reached because
     *     a pause began mid-run (every other id is, for now, left untouched by this stub)
     */
    public LegResenderStubOutcome resend(final List<UUID> requestIds) {
        final LegResenderStubOutcome outcome;
        if (pauseGate.pausedByOperator()) {
            outcome = LegResenderStubOutcome.refusedWhole();
        } else {
            final List<UUID> notRun = new ArrayList<>();
            for (final UUID requestId : requestIds) {
                if (pauseGate.pausedByOperator()) {
                    notRun.add(requestId);
                }
                // Phase 6 (T043 onward) claims and runs requestId here when not paused.
            }
            outcome = LegResenderStubOutcome.ranWithRemaining(notRun);
        }
        return outcome;
    }
}
