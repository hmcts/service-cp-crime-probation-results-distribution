package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * {@link LegResender}'s pause-only stub result shape (slice 004, Phase 5 T036). This is
 * <strong>not</strong> the real resend-leg report (FR-004-039; T043 onward, Phase 6, replaces it
 * wholesale with the per-ID outcome report that phase's own tests specify) — it exists only to let
 * {@code ResendConcurrencyIT}'s pause-related cases (FR-004-021) compile and pass ahead of the full
 * eligibility/claim/send pipeline.
 *
 * @param wholeRunRefusal present when the run was refused before reading any id
 *                        ({@code intake-paused}, read before any id)
 * @param notRun          the ids not reached because a pause began mid-run
 */
public record LegResenderStubOutcome(Optional<WholeRunRefusal> wholeRunRefusal, List<UUID> notRun) {

    /**
     * The whole run was refused before any id was read (FR-004-021).
     *
     * @return the refused outcome
     */
    public static LegResenderStubOutcome refusedWhole() {
        return new LegResenderStubOutcome(Optional.of(WholeRunRefusal.INTAKE_PAUSED), List.of());
    }

    /**
     * The run proceeded, stopping early if a pause began mid-run.
     *
     * @param notRun the ids not reached
     * @return the outcome
     */
    public static LegResenderStubOutcome ranWithRemaining(final List<UUID> notRun) {
        return new LegResenderStubOutcome(Optional.empty(), List.copyOf(notRun));
    }
}
