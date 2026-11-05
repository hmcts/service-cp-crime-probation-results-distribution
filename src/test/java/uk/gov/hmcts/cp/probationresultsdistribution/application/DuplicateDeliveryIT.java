package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;

/**
 * US2 Acceptance Scenarios 3-4 (DV-08, H14, SC-002-002): the processed-log guard is the only duplicate
 * protection, so every copy reaches it and no share is sent twice. A republish of a {@code COMPLETED}
 * share is completed with no run and no POST; two concurrent first copies produce exactly one
 * admission, one run and one POST, the loser abandoned quietly and completed as a no-op on redelivery.
 */
@DisplayName("no copy of a share is sent twice")
class DuplicateDeliveryIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String SHARED_TIME = "2026-08-19T16:42:07.512Z";
    private static final String PAYLOAD = "{\"policeCases\":[{\"x\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";

    @Test
    @DisplayName("a republish of a COMPLETED share is a no-op with no second POST")
    void republish_of_a_completed_share_is_a_noop() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();
        final String body = commandJson(requestId, hearingId, HEARING_DAY, SHARED_TIME);

        sendCommand(requestId, body);
        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, HEARING_DAY);
        assertThat(settled).isPresent();
        assertThat(settled.orElseThrow().get("request_status")).isEqualTo("COMPLETED");
        assertThat(gatewayPostCount()).isEqualTo(1);

        sendCommand(requestId, body);
        assertNoSecondPost();

        assertThat(gatewayPostCount())
                .as("a republish of a completed share is a no-op; nothing is sent again")
                .isEqualTo(1);
        assertThat(outboundMessageCount(requestId))
                .as("the attempt rows are unchanged by the duplicate")
                .isEqualTo(1);
        assertThat(requestStatus(requestId)).contains("COMPLETED");
        assertThat(awaitDeadLetter("RESULTS:" + requestId))
                .as("the duplicate is completed, never dead-lettered")
                .isEmpty();
    }

    @Test
    @DisplayName("two concurrent first copies produce exactly one admission, run and POST")
    void two_concurrent_first_copies_run_once() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();
        final String body = commandJson(requestId, hearingId, HEARING_DAY, SHARED_TIME);

        sendCommand(requestId, body);
        sendCommand(requestId, body);

        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, HEARING_DAY);
        assertThat(settled).isPresent();
        assertThat(settled.orElseThrow().get("request_status")).isEqualTo("COMPLETED");
        assertNoSecondPost();

        assertThat(gatewayPostCount())
                .as("the guard admits exactly one of two concurrent copies")
                .isEqualTo(1);
        assertThat(outboundMessageCount(requestId))
                .as("exactly one attempt row is written")
                .isEqualTo(1);
    }

    private int outboundMessageCount(final UUID requestId) {
        return jdbc().sql("SELECT count(*) FROM outbound_message WHERE request_id = :requestId")
                .param("requestId", requestId)
                .query(Integer.class)
                .single();
    }

    private void assertNoSecondPost() {
        final long deadline = System.nanoTime() + java.time.Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            assertThat(gatewayPostCount())
                    .as("the duplicate must never produce a second POST")
                    .isLessThanOrEqualTo(1);
            try {
                Thread.sleep(250L);
            } catch (final InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while watching for a second POST", e);
            }
        }
    }
}
