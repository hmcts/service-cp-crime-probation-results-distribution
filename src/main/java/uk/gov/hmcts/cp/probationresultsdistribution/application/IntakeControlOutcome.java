package uk.gov.hmcts.cp.probationresultsdistribution.application;

/**
 * The bounded outcome of one call to {@link IntakePauseAction} (design §4.8 action 4; slice 004,
 * FR-004-051). An answer and report code only — never a row value.
 */
public sealed interface IntakeControlOutcome
        permits IntakeControlOutcome.Done, IntakeControlOutcome.NoOp,
                IntakeControlOutcome.StoreUnavailable {

    /** The pause or resume actually changed the persisted state. */
    record Done() implements IntakeControlOutcome {
    }

    /** Intake was already in the requested state; nothing changed (FR-004-051). */
    record NoOp() implements IntakeControlOutcome {
    }

    /** The processed log could not be reached; nothing changed. */
    record StoreUnavailable() implements IntakeControlOutcome {
    }
}
