package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * The bounded outcome of one call to {@link CloseExpiredResendAction} (H40; slice 004,
 * FR-004-059).
 */
public sealed interface CloseExpiredResendOutcome
        permits CloseExpiredResendOutcome.Done, CloseExpiredResendOutcome.Refused {

    /** The row was closed {@code FAILED(rolled-back)}. */
    record Done() implements CloseExpiredResendOutcome {
    }

    /**
     * The call was refused; nothing changed.
     *
     * @param reason the refusal code ({@code unknown-request}, {@code not-eligible} or
     *               {@code resend-in-progress})
     */
    record Refused(ResendRefusal reason) implements CloseExpiredResendOutcome {

        /** Rejects a null reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
