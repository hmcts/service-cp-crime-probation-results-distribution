package uk.gov.hmcts.cp.probationresultsdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * US1 Acceptance Scenario 4 (T032, D16(a)): an application-only hearing that the gateway answers 400 is
 * POSTed once with no retry, recorded {@code FAILED(receiver-rejected)} with its body and digest
 * stored, counted with {@code shape = application-only} and {@code http = 400}, and the request is
 * {@code COMPLETED}. What was sent is not changed to avoid the rejection.
 */
@DisplayName("end to end: a 400 is recorded FAILED(receiver-rejected) and stays rejected")
class EndToEndReceiverRejectedIT extends EndToEndTestSupport {

    @Test
    @DisplayName("POSTs once, records FAILED(receiver-rejected), stores the body and counts the shape")
    void records_receiver_rejected_with_the_stored_body() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        final String payload = "{\"hearing\":{\"courtApplications\":[{\"subject\":"
                + "{\"masterDefendant\":{\"isYouth\":false}}}]}}";
        putDatedCachePayload(hearingId, hearingDay, payload);
        stubYouthSwitchActive();
        stubGateway(400);
        startProcessor();

        sendCommand(requestId, commandJson(requestId, hearingId, hearingDay,
                "2026-08-19T16:42:07.512Z"));

        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, hearingDay);
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.get();
        assertThat(row.get("leg_status")).isEqualTo("FAILED");
        assertThat(row.get("reason")).isEqualTo("receiver-rejected");
        assertThat(row.get("http_status")).isEqualTo(400);
        assertThat(row.get("body_digest")).isNotNull();
        assertThat(row.get("body")).isNotNull();
        assertThat(sha256Hex((String) row.get("body"))).isEqualTo(row.get("body_digest"));
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(gatewayPostCount()).isEqualTo(1);

        assertThat(meterRegistry().find("probation_leg_outcomes_total")
                .tag("shape", "application-only").tag("http", "400").counter()).isNotNull();
    }
}
