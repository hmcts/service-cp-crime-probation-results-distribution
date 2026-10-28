package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The result of one resend-leg run (slice 004, FR-004-039): either refused whole before anything
 * was read, or the run's report — its {@code resend_run_id}, whether {@code allow-changed-payload}
 * and the forced-resend override ({@code allowTerminalSuccess}) were requested, every distinct listed ID exactly once with its outcome, and whether the store
 * failed during the run, which makes the run's result failed although its report stands (spec edge
 * case "The store fails during a resend run").
 *
 * @param wholeRunRefusal               present when the run was refused whole
 * @param resendRunId                   the run's id, present when it was not refused whole
 * @param allowChangedPayloadRequested  whether {@code allow-changed-payload} was requested
 * @param allowTerminalSuccessRequested whether the forced-resend override was requested
 * @param entries                       every distinct listed ID with its outcome, in list order
 * @param storeFailed                   whether a store failure occurred during the run
 */
public record ResendRunResult(Optional<WholeRunRefusal> wholeRunRefusal, Optional<UUID> resendRunId,
                              boolean allowChangedPayloadRequested,
                              boolean allowTerminalSuccessRequested, List<Entry> entries,
                              boolean storeFailed) {

    /** Rejects null components. */
    public ResendRunResult {
        Objects.requireNonNull(wholeRunRefusal, "wholeRunRefusal");
        Objects.requireNonNull(resendRunId, "resendRunId");
        Objects.requireNonNull(entries, "entries");
        entries = List.copyOf(entries);
    }

    /**
     * A run refused whole: nothing read or changed (FR-004-019–FR-004-021).
     *
     * @param refusal the whole-run refusal code
     * @return the refused result
     */
    public static ResendRunResult refusedWhole(final WholeRunRefusal refusal) {
        return new ResendRunResult(Optional.of(refusal), Optional.empty(), false, false, List.of(),
                false);
    }

    /**
     * The ids the run did not reach (FR-004-021).
     *
     * @return the {@code not-run} ids, in list order
     */
    public List<UUID> notRun() {
        return entries.stream().filter(entry -> entry.outcome() instanceof ResendEntryOutcome.NotRun)
                .map(Entry::requestId).toList();
    }

    /**
     * One listed ID's report entry.
     *
     * @param requestId the listed id
     * @param outcome   its outcome
     */
    public record Entry(UUID requestId, ResendEntryOutcome outcome) {

        /** Rejects null components. */
        public Entry {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(outcome, "outcome");
        }
    }
}
