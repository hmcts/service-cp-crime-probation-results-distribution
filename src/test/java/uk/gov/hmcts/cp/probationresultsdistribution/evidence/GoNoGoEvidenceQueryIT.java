package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.evidence.GoNoGoEvidenceQuery.RecoveryEvidence;
import uk.gov.hmcts.cp.probationresultsdistribution.evidence.GoNoGoEvidenceQuery.ResultsShareEvidence;
import uk.gov.hmcts.cp.probationresultsdistribution.evidence.GoNoGoEvidenceQuery.TestCallEvidence;
import uk.gov.hmcts.cp.probationresultsdistribution.evidence.GoNoGoEvidenceQuery.YouthSwitchCheckEvidence;

/**
 * Pins design §9.2 rows 1, 7, 9 and 21's build-supported evidence queries (slice 006, FR-006-030,
 * FR-006-032). User Story 2's own Independent Test: seed the store 002's V1 creates, read each
 * query's evidence, and confirm none when the rows are absent.
 */
@DisplayName("GoNoGoEvidenceQuery")
class GoNoGoEvidenceQueryIT extends EvidenceTestStore {

    @Test
    @DisplayName("row 1: empty until one real SIT share has passed from RESULTS through to a HMPPS outcome")
    void row1_results_share_evidence() {
        final GoNoGoEvidenceQuery query = new GoNoGoEvidenceQuery(sql);

        assertThat(query.resultsShareEvidence()).isEmpty();

        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = insertAdmittedRequest("RESULTS", requestId);
        insertSentLeg("RESULTS", requestId, 200, "ACTIVE");

        final ResultsShareEvidence evidence = query.resultsShareEvidence().orElseThrow();
        assertThat(evidence.requestId()).isEqualTo(requestId);
        assertThat(evidence.hearingId()).isEqualTo(hearingId);
        assertThat(evidence.hmppsStatus()).isEqualTo("SENT");
    }

    @Test
    @DisplayName("row 9: the test call's attempt row, SENT with its status code")
    void row9_test_call_evidence() {
        final GoNoGoEvidenceQuery query = new GoNoGoEvidenceQuery(sql);
        final UUID requestId = UUID.randomUUID();

        assertThat(query.testCallEvidence("SUPPORT", requestId)).isEmpty();

        insertAdmittedRequest("SUPPORT", requestId);
        insertSentLeg("SUPPORT", requestId, 200, "ACTIVE");

        final TestCallEvidence evidence = query.testCallEvidence("SUPPORT", requestId).orElseThrow();
        assertThat(evidence.status()).isEqualTo("SENT");
        assertThat(evidence.httpStatus()).contains(200);
        assertThat(evidence.youthSwitch()).contains("ACTIVE");
    }

    @Test
    @DisplayName("row 7: the recorded youth switch matches the configured default")
    void row7_youth_switch_check_matches() {
        final GoNoGoEvidenceQuery query = new GoNoGoEvidenceQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("SUPPORT", requestId);
        insertSentLeg("SUPPORT", requestId, 200, "ACTIVE");

        final YouthSwitchCheckEvidence evidence =
                query.youthSwitchCheck("SUPPORT", requestId, "ACTIVE").orElseThrow();

        assertThat(evidence.recordedYouthSwitch()).isEqualTo("ACTIVE");
        assertThat(evidence.configuredDefault()).isEqualTo("ACTIVE");
        assertThat(evidence.matches()).isTrue();
    }

    @Test
    @DisplayName("row 7: a mismatch in either direction is no-go")
    void row7_youth_switch_check_mismatch_is_no_go() {
        final GoNoGoEvidenceQuery query = new GoNoGoEvidenceQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("SUPPORT", requestId);
        insertSentLeg("SUPPORT", requestId, 200, "INACTIVE");

        final YouthSwitchCheckEvidence evidence =
                query.youthSwitchCheck("SUPPORT", requestId, "ACTIVE").orElseThrow();

        assertThat(evidence.matches()).isFalse();
    }

    @Test
    @DisplayName("row 21: FAILED(leg-exhausted) with its exhausted message id, then recovered by a replay")
    void row21_dead_letter_then_recovery() {
        final GoNoGoEvidenceQuery query = new GoNoGoEvidenceQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("SUPPORT", requestId);
        insertLegExhausted("SUPPORT", requestId, "RESULTS:" + requestId);

        final RecoveryEvidence exhausted = query.recoveryEvidence("SUPPORT", requestId).orElseThrow();
        assertThat(exhausted.status()).isEqualTo("FAILED");
        assertThat(exhausted.failureReason()).contains("leg-exhausted");
        assertThat(exhausted.exhaustedMessageId()).contains("RESULTS:" + requestId);
        assertThat(exhausted.replayed()).isFalse();

        recoverByReplay("SUPPORT", requestId);

        final RecoveryEvidence recovered = query.recoveryEvidence("SUPPORT", requestId).orElseThrow();
        assertThat(recovered.status()).isEqualTo("COMPLETED");
        assertThat(recovered.replayed()).isTrue();
    }

    @Test
    @DisplayName("row 21: a replay admitted but not yet settled reads replayed from replayed_at, "
            + "the live signal (FR-006-032)")
    void row21_mid_replay_reads_the_live_signal() {
        final GoNoGoEvidenceQuery query = new GoNoGoEvidenceQuery(sql);
        final UUID requestId = UUID.randomUUID();
        insertAdmittedRequest("SUPPORT", requestId);
        insertLegExhausted("SUPPORT", requestId, "RESULTS:" + requestId);

        markMidReplayAdmission("SUPPORT", requestId);

        final RecoveryEvidence midReplay = query.recoveryEvidence("SUPPORT", requestId).orElseThrow();
        assertThat(midReplay.status()).isEqualTo("RECEIVED");
        assertThat(midReplay.replayed()).isTrue();
    }

    @Test
    @DisplayName("row 21: empty for a request that was never admitted")
    void row21_empty_for_unknown_request() {
        final GoNoGoEvidenceQuery query = new GoNoGoEvidenceQuery(sql);

        assertThat(query.recoveryEvidence("SUPPORT", UUID.randomUUID())).isEmpty();
    }
}
