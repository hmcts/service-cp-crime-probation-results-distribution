package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.Counter;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;

/**
 * US3 Acceptance Scenario 8 (FR-002-018): on a delivery that is not the final permitted one, when
 * every payload source misses the leg is {@code PARKED(payload-unavailable)}, the switch is not read,
 * no body exists, the request is {@code RETRYING} with no failure reason, and the message is handed
 * back for redelivery, counting {@code probation_parked_total}.
 *
 * <p>The listener's delivery budget is set above the broker's five so every delivery this IT observes
 * is non-final; the broker eventually dead-letters the churning message under its own reason, which
 * does not touch the durable {@code PARKED}/{@code RETRYING} state this IT asserts.
 */
@DisplayName("a payload that cannot be read yet parks and is retried by redelivery")
class PayloadUnavailableParkIT extends EndToEndTestSupport {

    @Test
    @DisplayName("parks payload-unavailable, leaves the request RETRYING and never reads the switch")
    void parks_when_no_source_yields_a_payload() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        // No cache payload, and the unstubbed results query answers 404 (a miss), so every source
        // misses. The listener budget (10) is above the broker's five, so no delivery is ever final.
        startProcessorWith(
                listenerOver(sharedDataSource(), alwaysAvailableStore(), 10), 1);

        sendCommand(requestId, commandJson(requestId, hearingId, hearingDay,
                "2026-08-19T16:42:07.512Z"));

        final Optional<Map<String, Object>> leg = awaitSettledLeg(hearingId, hearingDay);
        assertThat(leg).isPresent();
        final Map<String, Object> row = leg.orElseThrow();
        assertThat(row.get("leg_status")).isEqualTo("PARKED");
        assertThat(row.get("reason")).isEqualTo("payload-unavailable");
        assertThat(row.get("request_status")).isEqualTo("RETRYING");
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("youth_switch")).as("the switch is not read when the payload is missing")
                .isNull();
        assertThat(row.get("payload_source")).isNull();
        assertThat(row.get("body_digest")).isNull();
        assertThat(parkedCount()).isGreaterThanOrEqualTo(1.0d);

        // The listener budget (10) is above the broker's five, so the service never dead-letters this
        // churning message itself; the broker exhausts it after its own five deliveries. Wait for it
        // to leave the shared queue for the dead-letter sub-queue, so it cannot bleed into a later
        // suite that consumes the same queue.
        assertThat(awaitDeadLetter("RESULTS:" + requestId))
                .as("the parked message leaves the shared main queue before the suite ends")
                .isPresent();
    }

    private double parkedCount() {
        final Counter counter = meterRegistry().find("probation_parked_total")
                .tag("reason", "payload-unavailable").counter();
        return counter == null ? 0.0d : counter.count();
    }
}
