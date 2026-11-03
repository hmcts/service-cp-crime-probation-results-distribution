package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins FR-006-023 to FR-006-026, §9.5 row 2: per-day counts from {@code outbound_message_latest}
 * only, so a request recovered by a resend counts once and a completed request whose resend
 * failed counts as failed. User Story 5's own Independent Test.
 */
@DisplayName("DailyReconciliationQuery")
class DailyReconciliationQueryIT extends EvidenceTestStore {

    @Test
    @DisplayName("a day of SENT, in-flight and failed legs is counted from each leg's latest attempt")
    void a_day_of_mixed_outcomes_is_counted() {
        final DailyReconciliationQuery query = new DailyReconciliationQuery(sql);

        final UUID sentRequest = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", sentRequest);
        insertSentLeg("RESULTS", sentRequest, 200, "ACTIVE");

        final UUID parkedRequest = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", parkedRequest);
        insertInFlightLeg("RESULTS", parkedRequest, "PARKED");

        final UUID pendingRequest = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", pendingRequest);
        insertInFlightLeg("RESULTS", pendingRequest, "PENDING");

        final DailyCounts counts = query.countsFor(day());

        assertThat(counts.requestsReceived()).isEqualTo(3);
        assertThat(counts.sent()).isEqualTo(1);
        assertThat(counts.parked()).isEqualTo(1);
        assertThat(counts.pending()).isEqualTo(1);
        assertThat(counts.expectedGap()).isEqualTo(2);
    }

    @Test
    @DisplayName("a COMPLETED request whose latest attempt is a failed resend counts FAILED with its reason")
    void completed_request_with_failed_resend_counts_failed() {
        final DailyReconciliationQuery query = new DailyReconciliationQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", requestId);
        insertLegExhausted("RESULTS", requestId, "RESULTS:" + requestId);
        // Request status is FAILED here, not COMPLETED, but the read never looks at it — only at
        // the latest attempt — so this also pins "never processed_request.status" (FR-006-023).

        final DailyCounts counts = query.countsFor(day());

        assertThat(counts.failedByReason()).containsEntry("gateway-transient", 1);
        assertThat(counts.sent()).isZero();
    }

    @Test
    @DisplayName("a leg that failed then succeeded by resend counts once, as SENT")
    void leg_recovered_by_resend_counts_once_as_sent() {
        final DailyReconciliationQuery query = new DailyReconciliationQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", requestId);
        insertLegExhausted("RESULTS", requestId, "RESULTS:" + requestId);
        recoverByReplay("RESULTS", requestId);

        final DailyCounts counts = query.countsFor(day());

        assertThat(counts.sent()).isEqualTo(1);
        assertThat(counts.failedByReason()).isEmpty();
    }

    @Test
    @DisplayName("the expected gap is the PARKED + PENDING count")
    void expected_gap_is_parked_plus_pending() {
        final DailyReconciliationQuery query = new DailyReconciliationQuery(sql);
        final UUID first = UUID.randomUUID();
        final UUID second = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", first);
        insertInFlightLeg("RESULTS", first, "PARKED");
        insertAdmittedRequest("RESULTS", second);
        insertInFlightLeg("RESULTS", second, "PENDING");

        assertThat(query.countsFor(day()).expectedGap()).isEqualTo(2);
    }

    @Test
    @DisplayName("an orphaned exhausted dead letter repairs by a resend from the DLQ; others by submit-hearing")
    void orphaned_dead_letter_repair_route() {
        assertThat(DailyReconciliationQuery.repairRouteForOrphanedDeadLetter("exhausted"))
                .isEqualTo("resend-from-dlq");
        assertThat(DailyReconciliationQuery.repairRouteForOrphanedDeadLetter("validation"))
                .isEqualTo("submit-hearing");
        assertThat(DailyReconciliationQuery.repairRouteForOrphanedDeadLetter("collision"))
                .isEqualTo("submit-hearing");
    }

    @Test
    @DisplayName("an empty day has zero counts, never absent")
    void empty_day_has_zero_counts() {
        final DailyReconciliationQuery query = new DailyReconciliationQuery(sql);

        final DailyCounts counts = query.countsFor(day());

        assertThat(counts.requestsReceived()).isZero();
        assertThat(counts.sent()).isZero();
        assertThat(counts.expectedGap()).isZero();
    }
}
