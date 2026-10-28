package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import java.util.Optional;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * One listed ID's outcome in a resend-leg report (slice 004, FR-004-039): the leg's resulting
 * status and reason, a refusal code, {@code not-run} or {@code claimed-incomplete}. Report codes
 * only, never row values.
 */
public sealed interface ResendEntryOutcome permits ResendEntryOutcome.Ran,
        ResendEntryOutcome.Refused, ResendEntryOutcome.NotRun, ResendEntryOutcome.ClaimedIncomplete {

    /**
     * The bounded report text for this outcome.
     *
     * @return {@code SENT}, {@code NOT_APPLICABLE(<reason>)}, {@code FAILED(<reason>)}, a refusal
     *     code, {@code not-run} or {@code claimed-incomplete}
     */
    String reportValue();

    /**
     * The ID was claimed and run; its new attempt row ended on this status and reason.
     *
     * @param status the leg status recorded
     * @param reason the bounded reason, empty on {@code SENT}
     */
    record Ran(LegStatus status, Optional<LegReason> reason) implements ResendEntryOutcome {

        /** Rejects null components. */
        public Ran {
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String reportValue() {
            return reason.map(value -> status.wireValue() + "(" + value.wireValue() + ")")
                    .orElse(status.wireValue());
        }
    }

    /**
     * The ID was refused; nothing changed for it.
     *
     * @param reason the refusal code
     */
    record Refused(ResendRefusal reason) implements ResendEntryOutcome {

        /** Rejects a null reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }

        @Override
        public String reportValue() {
            return reason.wireValue();
        }
    }

    /**
     * The ID was never claimed: an operator pause began before it was reached (FR-004-021), or the
     * store failed, or could not confirm the pause, before its claim committed.
     */
    record NotRun() implements ResendEntryOutcome {

        @Override
        public String reportValue() {
            return "not-run";
        }
    }

    /**
     * The ID's attempt row was claimed and committed {@code PENDING} — so its POST may have gone
     * out — but its outcome could not be recorded: the store failed during or after the run, or
     * the completion was fenced as a stale runner (FR-004-028; spec edge case "The store fails
     * during a resend run"). The row is left for the guarded rollback close-in-flight procedure.
     */
    record ClaimedIncomplete() implements ResendEntryOutcome {

        @Override
        public String reportValue() {
            return "claimed-incomplete";
        }
    }
}
