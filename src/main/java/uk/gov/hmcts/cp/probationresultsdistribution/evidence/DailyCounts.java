package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;

/**
 * One day's reconciliation counts, read only from each HMPPS leg's latest attempt — never from
 * {@code processed_request.status}, because a request's completion is historical (slice 006,
 * FR-006-023, FR-006-025).
 *
 * @param day                    the UTC share day these counts are for — {@code shared_time}'s
 *                               calendar day, never {@code hearing_day} (FR-006-024)
 * @param requestsReceived       admitted {@code processed_request} rows for this day
 * @param sent                   latest attempts {@code SENT}
 * @param notApplicableByReason  latest attempts {@code NOT_APPLICABLE}, by reason
 * @param failedByReason         latest attempts {@code FAILED}, by reason
 * @param parked                 latest attempts {@code PARKED}
 * @param pending                latest attempts {@code PENDING}
 */
public record DailyCounts(
        LocalDate day,
        int requestsReceived,
        int sent,
        Map<String, Integer> notApplicableByReason,
        Map<String, Integer> failedByReason,
        int parked,
        int pending) {

    /** Rejects null components; the reason maps are defensively copied. */
    public DailyCounts {
        Objects.requireNonNull(day, "day");
        Objects.requireNonNull(notApplicableByReason, "notApplicableByReason");
        notApplicableByReason = Map.copyOf(notApplicableByReason);
        Objects.requireNonNull(failedByReason, "failedByReason");
        failedByReason = Map.copyOf(failedByReason);
    }

    /**
     * The expected gap until nothing is in flight: the count of latest attempts still
     * {@code PARKED} or {@code PENDING} (FR-006-025).
     *
     * @return {@link #parked} plus {@link #pending}
     */
    public int expectedGap() {
        return parked + pending;
    }
}
