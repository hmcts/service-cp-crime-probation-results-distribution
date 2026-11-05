package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

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
 * US3 Acceptance Scenario 9 (FR-002-041): a leg parked {@code payload-unavailable}, whose payload is
 * now available on the redelivery, is re-run in the same attempt-1 row — no new {@code PENDING} is
 * written first, {@code attempts} is incremented, {@code note} reads
 * {@code prev=PARKED/payload-unavailable}, the outcome is {@code SENT}, and the request is
 * {@code COMPLETED}.
 *
 * <p>The parked state is seeded directly (a {@code RETRYING} request and its attempt-1
 * {@code PARKED(payload-unavailable)} leg, no claim held) and one delivery is then driven with the
 * payload present, so the reclaim-and-rerun is deterministic.
 */
@DisplayName("a parked leg whose payload arrives is re-run in place on redelivery")
class PayloadRerunInPlaceIT extends EndToEndTestSupport {

    @Test
    @DisplayName("re-runs the same attempt-1 row, rewriting note=prev, ending SENT and COMPLETED")
    void reruns_the_parked_row_in_place() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        final DistributionCommand command = new DistributionCommand("RESULTS", requestId, hearingId,
                hearingDay, Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted",
                Optional.empty());
        seedParkedPayloadUnavailableLeg(command);

        final String payload = "{\"policeCases\":[{\"x\":1}],"
                + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
                + "{\"isYouth\":false,\"offences\":[]}]}]}}";
        putDatedCachePayload(hearingId, hearingDay, payload);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, commandJson(requestId, hearingId, hearingDay,
                "2026-08-19T16:42:07.512Z"));

        // The seeded row is already PARKED (non-PENDING), so wait for the re-run to complete the
        // request rather than for the row to merely settle.
        awaitRequestCompleted(requestId);
        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, hearingDay);
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.orElseThrow();
        assertThat(row.get("leg_status")).isEqualTo("SENT");
        assertThat(row.get("note")).isEqualTo("prev=PARKED/payload-unavailable");
        assertThat(((Number) row.get("attempts")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("both-legs-terminal");
        assertThat(outboundRowCount(requestId)).as("no new PENDING row is written").isEqualTo(1);
    }

    private void awaitRequestCompleted(final UUID requestId) {
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(90).toNanos();
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

    private int outboundRowCount(final UUID requestId) {
        return jdbc().sql("SELECT count(*) FROM outbound_message WHERE request_id = :id")
                .param("id", requestId)
                .query(Integer.class)
                .single();
    }
}
