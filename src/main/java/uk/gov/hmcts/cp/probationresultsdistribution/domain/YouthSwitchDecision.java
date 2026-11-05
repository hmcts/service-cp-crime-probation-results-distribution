package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.util.Objects;

/**
 * The three-valued result of reading the youth switch, before any default is applied (FR-002-022).
 * It is kept separate from {@link YouthSwitchRecordedValue} so an un-defaulted
 * {@link Unreadable} with its cause has somewhere to live in the pipeline: only after the configured
 * default is applied does the leg produce a four-valued recorded value.
 */
public sealed interface YouthSwitchDecision
        permits YouthSwitchDecision.Active,
                YouthSwitchDecision.Inactive,
                YouthSwitchDecision.Unreadable {

    /** The switch read {@code ACTIVE}: youth defendants are removed. */
    record Active() implements YouthSwitchDecision {
    }

    /** The switch read {@code INACTIVE}: youth defendants are kept. */
    record Inactive() implements YouthSwitchDecision {
    }

    /**
     * The switch could not be read; the leg applies the configured default.
     *
     * @param cause why the read was unreadable, never {@code null}
     */
    record Unreadable(YouthSwitchUnreadableCause cause) implements YouthSwitchDecision {

        /**
         * Rejects a null cause: an unreadable reading always carries exactly one bounded cause.
         *
         * @param cause the unreadable cause
         */
        public Unreadable {
            Objects.requireNonNull(cause, "cause");
        }
    }
}
