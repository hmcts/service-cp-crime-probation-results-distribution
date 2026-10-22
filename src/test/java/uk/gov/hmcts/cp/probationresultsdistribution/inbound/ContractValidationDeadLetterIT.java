package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;

/**
 * US2 Acceptance Scenario 1 (FR-002-003, DV-16, SC-002-003): every inbound-contract violation is
 * dead-lettered {@code validation} on the delivery that carries it, before any {@code processed_request}
 * row is written, and the outcome is counted. The dead-letter carries the bounded reason code only;
 * the offending field name and any value from the message are never quoted back.
 */
@DisplayName("a contract-invalid message is dead-lettered validation with no record")
class ContractValidationDeadLetterIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String SHARED_TIME = "2026-08-19T16:42:07.512Z";

    @Test
    @DisplayName("dead-letters each invalid shape validation, writes no row, and counts it")
    void dead_letters_every_invalid_shape() {
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();

        final UUID unknownField = UUID.randomUUID();
        final UUID missingField = UUID.randomUUID();
        final UUID badEventType = UUID.randomUUID();
        final UUID nullUserId = UUID.randomUUID();
        final UUID wrongType = UUID.randomUUID();
        final UUID nonJson = UUID.randomUUID();

        send(unknownField, withExtraField(unknownField));
        send(missingField, withoutHearingId(missingField));
        send(badEventType, withEventType(badEventType, "Something_Else"));
        send(nullUserId, withNullUserId(nullUserId));
        send(wrongType, withNumericHearingDay(wrongType));
        send(nonJson, "this is not json");

        assertDeadLetteredValidation(unknownField);
        assertDeadLetteredValidation(missingField);
        assertDeadLetteredValidation(badEventType);
        assertDeadLetteredValidation(nullUserId);
        assertDeadLetteredValidation(wrongType);
        assertDeadLetteredValidation(nonJson);

        assertThat(gatewayPostCount())
                .as("a contract-invalid message never reaches a downstream call")
                .isZero();
        assertThat(processedRequestCount(unknownField))
                .as("a contract-invalid message never enters the state machine")
                .isZero();
        assertThat(processedRequestCount(missingField)).isZero();
        assertThat(deadLetteredValidationCount())
                .as("every validation dead-letter is counted")
                .isEqualTo(6.0d);
    }

    private void send(final UUID requestId, final String body) {
        sendCommand(requestId, body);
    }

    private void assertDeadLetteredValidation(final UUID requestId) {
        final Optional<ServiceBusReceivedMessage> parked = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(parked)
                .as("the message is dead-lettered on its delivery")
                .isPresent();
        assertThat(parked.orElseThrow().getDeadLetterReason())
                .as("the dead-letter carries the bounded reason code, not the field name")
                .isEqualTo("validation");
    }

    private double deadLetteredValidationCount() {
        final var counter = meterRegistry().find("probation_deadlettered_total")
                .tag("reason", "validation").counter();
        return counter == null ? 0.0d : counter.count();
    }

    private String withExtraField(final UUID requestId) {
        return "{"
                + "\"source\":\"RESULTS\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"hearingId\":\"" + UUID.randomUUID() + "\","
                + "\"hearingDay\":\"" + HEARING_DAY + "\","
                + "\"sharedTime\":\"" + SHARED_TIME + "\","
                + "\"eventType\":\"Hearing_Resulted\","
                + "\"unexpected\":\"value\""
                + "}";
    }

    private String withoutHearingId(final UUID requestId) {
        return "{"
                + "\"source\":\"RESULTS\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"hearingDay\":\"" + HEARING_DAY + "\","
                + "\"sharedTime\":\"" + SHARED_TIME + "\","
                + "\"eventType\":\"Hearing_Resulted\""
                + "}";
    }

    private String withEventType(final UUID requestId, final String eventType) {
        return "{"
                + "\"source\":\"RESULTS\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"hearingId\":\"" + UUID.randomUUID() + "\","
                + "\"hearingDay\":\"" + HEARING_DAY + "\","
                + "\"sharedTime\":\"" + SHARED_TIME + "\","
                + "\"eventType\":\"" + eventType + "\""
                + "}";
    }

    private String withNullUserId(final UUID requestId) {
        return "{"
                + "\"source\":\"RESULTS\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"hearingId\":\"" + UUID.randomUUID() + "\","
                + "\"hearingDay\":\"" + HEARING_DAY + "\","
                + "\"sharedTime\":\"" + SHARED_TIME + "\","
                + "\"eventType\":\"Hearing_Resulted\","
                + "\"userId\":null"
                + "}";
    }

    private String withNumericHearingDay(final UUID requestId) {
        return "{"
                + "\"source\":\"RESULTS\","
                + "\"requestId\":\"" + requestId + "\","
                + "\"hearingId\":\"" + UUID.randomUUID() + "\","
                + "\"hearingDay\":20260819,"
                + "\"sharedTime\":\"" + SHARED_TIME + "\","
                + "\"eventType\":\"Hearing_Resulted\""
                + "}";
    }
}
