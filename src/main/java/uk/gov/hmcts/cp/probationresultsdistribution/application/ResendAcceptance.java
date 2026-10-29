package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * The answer of resend-leg's accept step ({@link LegResender#accept}): the run refused whole,
 * before anything was read or claimed (FR-004-019–FR-004-021), or accepted with its
 * {@code resendRunId} assigned and nothing yet claimed, ready for the run step ({@link
 * LegResender#run}).
 */
public sealed interface ResendAcceptance {

    /**
     * A run refused whole: nothing read, claimed or changed, and no run id assigned.
     *
     * @param refusal the whole-run refusal code
     */
    record Refused(WholeRunRefusal refusal) implements ResendAcceptance {

        /** Rejects a null code. */
        public Refused {
            Objects.requireNonNull(refusal, "refusal");
        }
    }

    /**
     * A run accepted: every whole-run check passed and its id assigned; nothing is claimed until
     * the run step takes it.
     *
     * @param resendRunId        the run's id
     * @param requestIds         the distinct listed ids, in list order
     * @param operator           the sanitised operator, recorded on each attempt row the run creates
     * @param allowChangedPayload the {@code allow-changed-payload} override
     * @param forcedResendReason the sanitised reason, present only when the forced-resend override
     *                           was requested
     */
    record Accepted(UUID resendRunId, List<UUID> requestIds, String operator,
                    boolean allowChangedPayload, Optional<String> forcedResendReason)
            implements ResendAcceptance {

        /** Rejects null components. */
        public Accepted {
            Objects.requireNonNull(resendRunId, "resendRunId");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(forcedResendReason, "forcedResendReason");
            requestIds = List.copyOf(requestIds);
        }

        /**
         * Identifiers and bounded values only: never the operator's name or the reason's text
         * (FR-004-041).
         *
         * @return the run id, the id count and the overrides requested
         */
        @Override
        public String toString() {
            return "Accepted[resendRunId=" + resendRunId + ", ids=" + requestIds.size()
                    + ", allowChangedPayload=" + allowChangedPayload
                    + ", allowTerminalSuccess=" + forcedResendReason.isPresent() + "]";
        }
    }
}
