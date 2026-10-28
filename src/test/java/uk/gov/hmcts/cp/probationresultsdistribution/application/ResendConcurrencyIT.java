package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * Pins FR-004-021 (US7 half): a resend run submitted while intake is paused is refused whole,
 * reading nothing; a pause that begins mid-run claims no further id. These cases need no resend
 * machinery beyond the pause read, so they are written first, against {@link LegResender}'s T036
 * pause-only stub (research.md Decision 3 — the reconciled in-memory flag, never a fresh store
 * read).
 *
 * <p><strong>Shared file with US3's T038/T041.</strong> Once Phase 6 lands the real eligibility,
 * claim and send pipeline, this class gains the per-id and cross-run concurrency cases (two resend
 * runs or a replay racing for one leg) that need Testcontainers Postgres and the Service Bus
 * emulator; nothing here should need to change for that extension beyond adding to it.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("resend-leg concurrency and the operator pause (US7 half)")
class ResendConcurrencyIT {

    private static final UUID ID_1 = UUID.fromString("6f1e9b2c-1a3d-4c58-9a0e-2b7f0a5c1d34");
    private static final UUID ID_2 = UUID.fromString("1c9d3f7a-88b1-4d5e-9c33-0f2a6b4e77aa");

    @Mock
    private IntakePauseGate pauseGate;
    @Mock
    private ProcessedRequestRepository store;
    @Mock
    private DistributionRunner runner;
    @Mock
    private OutcomeMetrics metrics;

    private LegResender resender;

    @BeforeEach
    void setUp() {
        resender = new LegResender(pauseGate, store, runner, metrics, 100);
    }

    @Test
    @DisplayName("refuses a whole run submitted while intake is paused, reading no id")
    void refuses_whole_run_while_paused() {
        when(pauseGate.pausedByOperator()).thenReturn(true);

        final ResendRunResult outcome = resender.resend(
                new ResendRequest(List.of(ID_1, ID_2), "an-operator", "a reason", false));

        assertThat(outcome.wholeRunRefusal()).contains(WholeRunRefusal.INTAKE_PAUSED);
        assertThat(outcome.notRun()).isEmpty();
    }

    @Test
    @DisplayName("proceeds when intake is not paused at the start")
    void proceeds_when_not_paused() {
        when(pauseGate.pausedByOperator()).thenReturn(false);

        final ResendRunResult outcome = resender.resend(
                new ResendRequest(List.of(ID_1, ID_2), "an-operator", "a reason", false));

        assertThat(outcome.wholeRunRefusal()).isEmpty();
    }

    @Test
    @DisplayName("a pause beginning mid-run claims no further id, reporting the rest not-run")
    void pause_mid_run_stops_claiming_further_ids() {
        // Not paused for the initial whole-run check and the first id's own check, then paused
        // from the second id's check onward.
        when(pauseGate.pausedByOperator()).thenReturn(false, false, true);

        final ResendRunResult outcome = resender.resend(
                new ResendRequest(List.of(ID_1, ID_2), "an-operator", "a reason", false));

        assertThat(outcome.wholeRunRefusal()).isEmpty();
        assertThat(outcome.notRun()).containsExactly(ID_2);
    }
}
