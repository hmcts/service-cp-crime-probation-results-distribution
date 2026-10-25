package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * US2 Acceptance Scenarios 5, 6 (FR-002-007, FR-002-008): a live-claim copy is abandoned without
 * delay and, on its final permitted delivery, dead-lettered {@code exhausted}; a different-fingerprint
 * copy of a known key is dead-lettered {@code collision} with the existing row untouched. The third
 * case this class covered — a copy of a {@code FAILED} request dead-lettered {@code exhausted} at
 * once with no run — was the interim FR-002-008 rule; slice 004's DV-26 predicate replaces it
 * (FR-004-001), so the copy is now admitted as a replay at the exhausted {@code messageId}'s first
 * delivery (design A3-30). The full DV-26 admission/dead-letter matrix is pinned by
 * {@code DlqResubmissionReplayIT} (004 tasks.md T024), not duplicated here.
 */
@DisplayName("the guard dedupes every copy and sends none twice")
class RequestDedupeIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final Instant SHARED_TIME = Instant.parse("2026-08-19T16:42:07.512Z");

    @Test
    @DisplayName("a live-claim copy is abandoned, then dead-lettered exhausted on the final delivery")
    void live_claim_copy_is_exhausted_on_the_final_delivery() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final DistributionCommand command = command(requestId, hearingId);
        seedRequest(command, "RECEIVED", true);
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, body(requestId, hearingId));

        final Optional<ServiceBusReceivedMessage> parked = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(parked)
                .as("a live-claim copy is abandoned every delivery and dead-lettered on the last")
                .isPresent();
        assertThat(parked.orElseThrow().getDeadLetterReason())
                .as("the final-delivery dead-letter carries the service's own exhausted reason")
                .isEqualTo("exhausted");
        assertThat(gatewayPostCount()).as("a live-claim copy never runs").isZero();
        assertThat(requestStatus(requestId))
                .as("the live-claim request row is left untouched")
                .contains("RECEIVED");
    }

    @Test
    @DisplayName("a different-fingerprint copy of a known key is dead-lettered collision, row untouched")
    void different_fingerprint_is_a_collision() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        seedCollisionRow("RESULTS", requestId);
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, body(requestId, hearingId));

        final Optional<ServiceBusReceivedMessage> parked = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(parked).isPresent();
        assertThat(parked.orElseThrow().getDeadLetterReason()).isEqualTo("collision");
        assertThat(gatewayPostCount()).isZero();
        assertThat(processedRequestCount(requestId))
                .as("the existing row is untouched: no new row, no second run")
                .isEqualTo(1);
        assertThat(requestStatus(requestId)).contains("RECEIVED");
    }

    @Test
    @DisplayName("a copy under the exhausted messageId at its first delivery is admitted as a "
            + "replay and recovers the request (DV-26, FR-004-001, FR-004-008)")
    void failed_request_copy_at_first_delivery_is_admitted_as_replay() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final DistributionCommand command = command(requestId, hearingId);
        seedRequest(command, "FAILED", false);
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, http_status, body_digest, "
                        + "payload_source, service_version, youth_switch) VALUES (:id, :source, "
                        + ":requestId, 'HMPPS', 1, 'queue', 'FAILED', 'gateway-transient', 1, 503, "
                        + ":digest, 'cache-day', 'test-version', 'ACTIVE')")
                .param("id", UUID.randomUUID())
                .param("source", command.source())
                .param("requestId", command.requestId())
                .param("digest", sha256Hex("prior-failed-body"))
                .update();
        final String payload = "{\"policeCases\":[{\"x\":1}],"
                + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
                + "{\"isYouth\":false,\"offences\":[]}]}]}}";
        putDatedCachePayload(hearingId, HEARING_DAY, payload);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, body(requestId, hearingId));

        awaitRequestCompleted(requestId);
        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, HEARING_DAY);
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.orElseThrow();
        assertThat(row.get("leg_status")).isEqualTo("SENT");
        assertThat(row.get("note")).asString().contains("prev=FAILED/gateway-transient")
                .contains("rerun-by=RESULTS:" + requestId);
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("recovered");
        assertThat(gatewayPostCount()).as("the replay sends exactly once").isEqualTo(1);
    }

    private void awaitRequestCompleted(final UUID requestId) {
        final long deadline = System.nanoTime() + Duration.ofSeconds(90).toNanos();
        while (System.nanoTime() < deadline) {
            if (requestStatus(requestId).filter("COMPLETED"::equals).isPresent()) {
                return;
            }
            try {
                Thread.sleep(250L);
            } catch (final InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while awaiting completion", interrupted);
            }
        }
    }

    private DistributionCommand command(final UUID requestId, final UUID hearingId) {
        return new DistributionCommand("RESULTS", requestId, hearingId, HEARING_DAY, SHARED_TIME,
                "Hearing_Resulted", Optional.empty());
    }

    private String body(final UUID requestId, final UUID hearingId) {
        return commandJson(requestId, hearingId, HEARING_DAY, SHARED_TIME.toString());
    }
}
