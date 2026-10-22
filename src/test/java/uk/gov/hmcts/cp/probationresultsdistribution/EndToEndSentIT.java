package uk.gov.hmcts.cp.probationresultsdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * US1 Acceptance Scenario 1 (T030): a valid command, its payload under the dated cache key, the switch
 * reading {@code ACTIVE} and a gateway answering 200 produce exactly one POST, a {@code SENT} attempt
 * recording where the payload came from and what was sent, a byte-identical stored body that hashes to
 * the digest, a {@code COMPLETED(both-legs-terminal)} request, and a message completed only after
 * those writes.
 */
@DisplayName("end to end: a resulted hearing reaches HMPPS once and is recorded")
class EndToEndSentIT extends EndToEndTestSupport {

    @Test
    @DisplayName("sends the redacted body once, records SENT and stores the exact bytes")
    void sends_once_and_records_the_outcome() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        final String payload = "{\"policeCases\":[{\"x\":1}],"
                + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
                + "{\"isYouth\":true,\"offences\":[]},{\"isYouth\":false,\"offences\":[]}]}]}}";
        putDatedCachePayload(hearingId, hearingDay, payload);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, commandJson(requestId, hearingId, hearingDay,
                "2026-08-19T16:42:07.512Z"));

        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, hearingDay);
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.get();
        assertThat(row.get("leg_status")).isEqualTo("SENT");
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(row.get("youth_switch")).isEqualTo("ACTIVE");
        assertThat(row.get("payload_source")).isEqualTo("cache-day");
        assertThat(row.get("service_version")).isNotNull();
        assertThat(row.get("sent_at")).isNotNull();
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("both-legs-terminal");

        assertThat(gatewayPostCount()).isEqualTo(1);
        final String posted = gatewayPostBody();
        assertThat(posted).doesNotContain("policeCases");
        assertThat(row.get("body")).isEqualTo(posted);
        assertThat(sha256Hex(posted)).isEqualTo(row.get("body_digest"));
    }
}
