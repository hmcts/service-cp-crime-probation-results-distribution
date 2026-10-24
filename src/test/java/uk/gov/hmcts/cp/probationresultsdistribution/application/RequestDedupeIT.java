package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * US2 Acceptance Scenarios 5, 6, 10 (FR-002-007, FR-002-008): a live-claim copy is abandoned without
 * delay and, on its final permitted delivery, dead-lettered {@code exhausted}; a different-fingerprint
 * copy of a known key is dead-lettered {@code collision} with the existing row untouched; a copy of a
 * {@code FAILED} request is dead-lettered {@code exhausted} at once with no run (interim FR-002-008).
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
    @DisplayName("a copy of a FAILED request is dead-lettered exhausted at once with no run")
    void failed_request_copy_is_exhausted_at_once() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final DistributionCommand command = command(requestId, hearingId);
        seedRequest(command, "FAILED", false);
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, body(requestId, hearingId));

        final Optional<ServiceBusReceivedMessage> parked = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(parked).isPresent();
        assertThat(parked.orElseThrow().getDeadLetterReason()).isEqualTo("exhausted");
        assertThat(gatewayPostCount()).as("a FAILED request's copy never runs").isZero();
        assertThat(requestStatus(requestId)).contains("FAILED");
    }

    private DistributionCommand command(final UUID requestId, final UUID hearingId) {
        return new DistributionCommand("RESULTS", requestId, hearingId, HEARING_DAY, SHARED_TIME,
                "Hearing_Resulted", Optional.empty());
    }

    private String body(final UUID requestId, final UUID hearingId) {
        return commandJson(requestId, hearingId, HEARING_DAY, SHARED_TIME.toString());
    }
}
