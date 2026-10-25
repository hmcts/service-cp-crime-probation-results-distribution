package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;

/** US5 Acceptance Scenarios 10 and 13: count only an expired-claim PENDING-row rerun. */
@DisplayName("crash reruns are counted only for a pending attempt")
class CrashRerunCountingIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String PAYLOAD = "{\"policeCases\":[{\"x\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";

    @Test
    @DisplayName("counts a reclaimed PENDING attempt and records its previous state")
    void counts_reclaimed_pending_attempt() throws InterruptedException {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        simulatePostThenOutcomeWriteCrash(requestId, hearingId);

        final Map<String, Object> row = awaitLegStatus(hearingId, HEARING_DAY, "SENT")
                .orElseThrow();
        assertThat(row.get("note")).isEqualTo("prev=PENDING/-");
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(meterRegistry().get("probation_crash_reruns_total")
                .tag("leg", "HMPPS").counter().count()).isEqualTo(1.0d);
    }

    @Test
    @DisplayName("does not classify a parked gateway recovery as a crash rerun")
    void does_not_count_parked_gateway_recovery_as_crash() throws InterruptedException {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final DistributionCommand command = command(requestId, hearingId);
        seedParkedGatewayTransientLeg(command, "{\"old\":true}");
        simulatePostThenOutcomeWriteCrash(requestId, hearingId);

        final Map<String, Object> row = awaitLegStatus(hearingId, HEARING_DAY, "SENT")
                .orElseThrow();
        assertThat(row.get("note")).isEqualTo("prev=PARKED/gateway-transient");
        assertThat(meterRegistry().find("probation_crash_reruns_total")
                .tag("leg", "HMPPS").counter()).isNull();
    }

    private DistributionCommand command(final UUID requestId, final UUID hearingId) {
        return new DistributionCommand("RESULTS", requestId, hearingId, HEARING_DAY,
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
    }

    private void simulatePostThenOutcomeWriteCrash(final UUID requestId, final UUID hearingId)
            throws InterruptedException {
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(200);
        final CountDownLatch outcomeWriteAttempted = new CountDownLatch(1);
        final OutboundLog crashBeforeRecord = new OutboundLog() {
            @Override
            public boolean record(final LegOutcome outcome, final RunClaim claim) {
                outcomeWriteAttempted.countDown();
                throw new IllegalStateException("simulated process crash after successful POST");
            }

            @Override
            public boolean recordReplay(final LegOutcome outcome, final RunClaim claim,
                                        final String rerunByMessageId) {
                throw new UnsupportedOperationException("not exercised by this test");
            }
        };
        startProcessorWith(listenerOverWithOutboundLog(sharedDataSource(), alwaysAvailableStore(),
                10, crashBeforeRecord), 1);
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        assertThat(outcomeWriteAttempted.await(10, TimeUnit.SECONDS)).isTrue();
        Thread.sleep(Duration.ofMillis(150));
        stopProcessorNow();
        assertThat(gatewayPostCount()).isEqualTo(1);
        jdbc().sql("UPDATE processed_request SET claim_expires_at = now() - interval '1 second' "
                        + "WHERE request_id = :requestId")
                .param("requestId", requestId)
                .update();

        startProcessor();
        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));
        awaitRequestStatus(requestId, "COMPLETED");
        assertThat(gatewayPostCount()).isEqualTo(2);
    }
}
