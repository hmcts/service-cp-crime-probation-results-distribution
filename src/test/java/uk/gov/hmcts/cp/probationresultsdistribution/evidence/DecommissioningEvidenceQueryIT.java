package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins FR-006-047, §9.6 row 1: over a rehearsed soak period, composes US2's and US5's own
 * evidence into the post-soak acceptance shape — adding no new read path of its own. User Story
 * 7's own Independent Test.
 */
@DisplayName("DecommissioningEvidenceQuery")
class DecommissioningEvidenceQueryIT extends EvidenceTestStore {

    @Test
    @DisplayName("a clean soak period, dead-letter queue empty, alerting deployed is accepted")
    void clean_soak_period_is_accepted() {
        final DailyReconciliationQuery reconciliation = new DailyReconciliationQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", requestId);
        insertSentLeg("RESULTS", requestId, 200, "ACTIVE");

        final List<DailyCounts> soakDays = List.of(reconciliation.countsFor(day()));

        final DecommissioningEvidence evidence =
                DecommissioningEvidenceQuery.compose(soakDays, true, true, true);

        assertThat(evidence.section95Green()).isTrue();
        assertThat(evidence.unexplainedGap()).isZero();
        assertThat(evidence.deadLetterQueueEmpty()).isTrue();
        assertThat(evidence.alertingDeployed()).isTrue();
        assertThat(evidence.accepted()).isTrue();
    }

    @Test
    @DisplayName("a day with a PARKED leg still in flight leaves an unexplained gap, not accepted")
    void an_in_flight_leg_leaves_a_gap_and_blocks_acceptance() {
        final DailyReconciliationQuery reconciliation = new DailyReconciliationQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("RESULTS", requestId);
        insertInFlightLeg("RESULTS", requestId, "PARKED");

        final List<DailyCounts> soakDays = List.of(reconciliation.countsFor(day()));

        final DecommissioningEvidence evidence =
                DecommissioningEvidenceQuery.compose(soakDays, true, true, true);

        assertThat(evidence.unexplainedGap()).isEqualTo(1);
        assertThat(evidence.accepted()).isFalse();
    }

    @Test
    @DisplayName("a non-empty dead-letter queue blocks acceptance even with a clean reconciliation")
    void non_empty_dead_letter_queue_blocks_acceptance() {
        final DecommissioningEvidence evidence = DecommissioningEvidenceQuery.compose(
                List.of(new DailyCounts(LocalDate.parse("2026-08-19"), 0, 0, java.util.Map.of(),
                        java.util.Map.of(), 0, 0)),
                true, false, true);

        assertThat(evidence.deadLetterQueueEmpty()).isFalse();
        assertThat(evidence.accepted()).isFalse();
    }

    @Test
    @DisplayName("alerting not yet deployed blocks acceptance even with everything else clean")
    void alerting_not_deployed_blocks_acceptance() {
        final DecommissioningEvidence evidence = DecommissioningEvidenceQuery.compose(
                List.of(new DailyCounts(LocalDate.parse("2026-08-19"), 0, 0, java.util.Map.of(),
                        java.util.Map.of(), 0, 0)),
                true, true, false);

        assertThat(evidence.alertingDeployed()).isFalse();
        assertThat(evidence.accepted()).isFalse();
    }

    @Test
    @DisplayName("the gap is summed across every soak day, not just the last")
    void gap_is_summed_across_the_whole_soak_period() {
        final DailyCounts dayOne = new DailyCounts(LocalDate.parse("2026-08-19"), 0, 0,
                java.util.Map.of(), java.util.Map.of(), 1, 0);
        final DailyCounts dayTwo = new DailyCounts(LocalDate.parse("2026-08-20"), 0, 0,
                java.util.Map.of(), java.util.Map.of(), 0, 2);

        final DecommissioningEvidence evidence =
                DecommissioningEvidenceQuery.compose(List.of(dayOne, dayTwo), true, true, true);

        assertThat(evidence.unexplainedGap()).isEqualTo(3);
    }
}
