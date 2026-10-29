package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

/**
 * The rollback's close-in-flight statement (design §9.4 step 4; slice 004, provisional FR-004-049;
 * its statement is FR-004-049a). It closes every {@code PENDING}/{@code PARKED} leg row, one
 * transaction per row, mapping each row's request according to FR-004-049a's rules.
 */
public interface RollbackFencingRepository {

    /**
     * Closes every {@code PENDING}/{@code PARKED} leg row, one transaction per row.
     *
     * @return how many rows of each prior status were closed
     */
    CloseInFlightResult closeInFlight();

    /**
     * The result of one close-in-flight run.
     *
     * @param pendingClosed the number of {@code PENDING} rows closed
     * @param parkedClosed  the number of {@code PARKED} rows closed
     */
    record CloseInFlightResult(int pendingClosed, int parkedClosed) {

        /**
         * The total number of rows closed.
         *
         * @return {@code pendingClosed + parkedClosed}
         */
        public int total() {
            return pendingClosed + parkedClosed;
        }
    }
}
