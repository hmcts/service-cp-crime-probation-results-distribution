package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import io.micrometer.core.instrument.Counter;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome.Ran;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome.Refused;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ReplayRoute;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcRollbackFencingRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * Pins FR-004-021 (US7 half): a resend run submitted while intake is paused is refused whole,
 * reading nothing; a pause that begins mid-run claims no further id. These cases need no resend
 * machinery beyond the pause read, so they are written against mocks (research.md Decision 3 — the
 * reconciled in-memory flag, never a fresh store read).
 *
 * <p>The US3 half (T041; FR-004-028, FR-004-035) races real runs over the shared PostgreSQL:
 * two resend runs for one leg, a late queue delivery or a replay meeting an in-flight resend, and
 * a late resend runner completing after the rollback protocol rotated its claim token. The race is
 * made deterministic by running the competitor from inside the first run's send, the moment its
 * attempt row is committed and its POST is about to go out.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("resend-leg concurrency and the operator pause")
class ResendConcurrencyIT extends ResendTestSupport {

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
        when(store.claimResend(any(), any(), any(), any(), any()))
                .thenReturn(new ProcessedRequestRepository.ResendClaim.Refused(
                        ResendRefusal.UNKNOWN_REQUEST));

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
        when(store.claimResend(any(), any(), any(), any(), any()))
                .thenReturn(new ProcessedRequestRepository.ResendClaim.Refused(
                        ResendRefusal.UNKNOWN_REQUEST));

        final ResendRunResult outcome = resender.resend(
                new ResendRequest(List.of(ID_1, ID_2), "an-operator", "a reason", false));

        assertThat(outcome.wholeRunRefusal()).isEmpty();
        assertThat(outcome.notRun()).containsExactly(ID_2);
    }

    @Nested
    @DisplayName("racing runs for one leg (US3 half, FR-004-035)")
    class Races {

        @Test
        @DisplayName("two resend runs for one leg: exactly one runs it and exactly one POST results")
        void two_resend_runs_one_runs_the_leg() {
            final UUID requestId = seedFailedCached();
            final List<ResendRunResult> competitor = new ArrayList<>();
            final LegResender first = resenderSending(() ->
                    competitor.add(resender().resend(request(false, requestId))));

            final ResendRunResult result = first.resend(request(false, requestId));

            assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            assertThat(competitor).hasSize(1);
            assertThat(entryFor(competitor.get(0), requestId))
                    .contains(new Refused(ResendRefusal.RESEND_IN_PROGRESS));
            assertThat(gatewayPostCount()).isEqualTo(1);
            assertThat(attemptCount(requestId)).isEqualTo(2);
        }

        @Test
        @DisplayName("a late queue delivery meeting an in-flight resend is deferred, not run")
        void late_queue_delivery_is_deferred() {
            final UUID hearingId = cachedHearing();
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, hearingId, "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            final IdempotencyGuard guard = new IdempotencyGuard(requestStore(),
                    new RequestFingerprint());
            final List<GuardDecision> decisions = new ArrayList<>();
            final LegResender first = resenderSending(() -> decisions.add(guard.admit(
                    command(requestId, hearingId), storedCommand(requestId, hearingId),
                    "RESULTS:" + requestId, 0L, ReplayRoute.COPY)));

            first.resend(request(false, requestId));

            assertThat(decisions).hasSize(1);
            assertThat(decisions.get(0)).isInstanceOf(GuardDecision.ReplayDeferred.class);
            assertThat(gatewayPostCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("a replay admitted first leaves the resend refused request-claimed")
        void replay_first_refuses_the_resend() {
            final UUID requestId = seedFailedCached();
            assertThat(requestStore().admitReplay(SOURCE, requestId, RunClaim.fresh(),
                    ReplayRoute.SERVICE)).isTrue();

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId))
                    .contains(new Refused(ResendRefusal.REQUEST_CLAIMED));
            assertThat(attemptCount(requestId)).isEqualTo(1);
            assertThat(gatewayPostCount()).isZero();
        }

        @Test
        @DisplayName("a replay statement reached after a resend claimed the leg is not admitted")
        void replay_statement_after_a_resend_claim_is_not_admitted() {
            final UUID requestId = seedFailedCached();
            final List<Boolean> admitted = new ArrayList<>();
            // The guard read the request before the resend claimed it; its replay statement runs
            // only once the resend's attempt row is committed.
            final LegResender first = resenderSending(() -> admitted.add(requestStore().admitReplay(
                    SOURCE, requestId, RunClaim.fresh(), ReplayRoute.COPY)));

            first.resend(request(false, requestId));

            assertThat(admitted).containsExactly(false);
            assertThat(requestRow(requestId).get("replayed_at")).isNull();
            assertThat(gatewayPostCount()).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("a late resend runner (FR-004-028)")
    class LateResendRunner {

        @Test
        @DisplayName("a completion after the rollback rotated the claim token changes nothing")
        void late_completion_after_rollback_is_fenced() {
            final UUID requestId = seedFailedCached();
            final JdbcRollbackFencingRepository fencing = new JdbcRollbackFencingRepository(jdbc(),
                    new TransactionTemplate(new DataSourceTransactionManager(sharedDataSource())));
            final LegResender first = resenderSending(fencing::closeInFlight);

            final ResendRunResult result = first.resend(request(false, requestId));

            assertThat(entryFor(result, requestId)).contains(new ResendEntryOutcome.NotRun());
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("attempt")).isEqualTo(2);
            assertThat(row.get("status")).isEqualTo("FAILED");
            assertThat(row.get("reason")).isEqualTo("rolled-back");
            assertThat(row.get("body_digest")).isNull();
            assertThat(requestRow(requestId).get("status")).isEqualTo("FAILED");
            assertThat(staleRejections()).isEqualTo(1.0d);
        }
    }

    private LegResender resenderSending(final Runnable duringSend) {
        stubYouthSwitchActive();
        stubGateway(200);
        final HmppsSender real = publisher(new uk.gov.hmcts.cp.probationresultsdistribution.config
                .MetricRegistry(meterRegistry()));
        final HmppsSender racing = redacted -> {
            duringSend.run();
            return real.send(redacted);
        };
        return resender(sharedRedisConnection(), 100, () -> false, racing);
    }

    private UUID seedFailedCached() {
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
        seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
        return requestId;
    }

    private UUID cachedHearing() {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(SHARED_TIME));
        return hearingId;
    }

    private double staleRejections() {
        final Counter counter = meterRegistry().find("probation_stale_runner_rejections_total")
                .counter();
        return counter == null ? 0.0d : counter.count();
    }
}
