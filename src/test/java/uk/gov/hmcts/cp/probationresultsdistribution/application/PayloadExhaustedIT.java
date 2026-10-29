package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;

/**
 * US3 Acceptance Scenario 10 (FR-002-018, FR-002-047): when every payload source still misses on the
 * final permitted delivery, the leg is {@code FAILED(payload-unavailable)}, the request is
 * {@code FAILED(leg-exhausted)} with {@code exhausted_message_id} set to the delivery's
 * {@code messageId}, and the message is dead-lettered {@code leg-exhausted} and counted.
 *
 * <p>The listener's delivery budget is {@code 1}, so the first delivery is the final permitted one.
 */
@DisplayName("a payload that is never found fails visibly on the final permitted delivery")
class PayloadExhaustedIT extends EndToEndTestSupport {

    @Test
    @DisplayName("fails the leg and the request leg-exhausted and dead-letters leg-exhausted")
    void fails_when_no_source_ever_yields_a_payload() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        // No cache payload and the unstubbed results query answers 404: every source misses. The
        // listener budget is 1, so this first delivery is the final permitted one.
        startProcessorWith(listenerOver(sharedDataSource(), alwaysAvailableStore(), 1), 1);

        sendCommand(requestId, commandJson(requestId, hearingId, hearingDay,
                "2026-08-19T16:42:07.512Z"));

        final Optional<Map<String, Object>> leg = awaitSettledLeg(hearingId, hearingDay);
        assertThat(leg).isPresent();
        final Map<String, Object> row = leg.orElseThrow();
        assertThat(row.get("leg_status")).isEqualTo("FAILED");
        assertThat(row.get("reason")).isEqualTo("payload-unavailable");
        assertThat(row.get("request_status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("leg-exhausted");
        assertThat(exhaustedMessageId(requestId)).isEqualTo("RESULTS:" + requestId);

        final Optional<ServiceBusReceivedMessage> dead = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(dead).isPresent();
        assertThat(dead.orElseThrow().getDeadLetterReason()).isEqualTo("leg-exhausted");
    }

    private String exhaustedMessageId(final UUID requestId) {
        return jdbc().sql(
                        "SELECT exhausted_message_id FROM processed_request WHERE request_id = :id")
                .param("id", requestId)
                .query(String.class)
                .single();
    }
}
