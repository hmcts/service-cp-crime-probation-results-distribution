package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.Objects;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RollbackFencingRepository.CloseInFlightResult;

/**
 * The bounded outcome of one call to {@link RollbackCloseInFlightAction} (design §9.4 step 4;
 * slice 004, provisional FR-004-049; its statement is FR-004-049a).
 */
public sealed interface RollbackCloseInFlightOutcome
        permits RollbackCloseInFlightOutcome.Done, RollbackCloseInFlightOutcome.Refused {

    /**
     * The statement ran, closing every {@code PENDING}/{@code PARKED} leg row.
     *
     * @param result the number of rows closed by prior status
     */
    record Done(CloseInFlightResult result) implements RollbackCloseInFlightOutcome {

        /** Rejects a null result. */
        public Done {
            Objects.requireNonNull(result, "result");
        }
    }

    /**
     * The run was refused whole, with nothing closed.
     *
     * @param reason the refusal code ({@code intake-not-paused})
     */
    record Refused(WholeRunRefusal reason) implements RollbackCloseInFlightOutcome {

        /** Rejects a null reason. */
        public Refused {
            Objects.requireNonNull(reason, "reason");
        }
    }
}
