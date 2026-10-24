package uk.gov.hmcts.cp.probationresultsdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * US1 Acceptance Scenario 7 (T033, P19): a payload on which a rule throws — a missing null guard the
 * legacy also lacks — is not POSTed. The attempt is {@code FAILED(rules-exception)}, carrying the
 * switch value but no digest, and the request is {@code COMPLETED}.
 */
@DisplayName("end to end: a rule that throws is recorded FAILED(rules-exception) and never sent")
class EndToEndRulesExceptionIT extends EndToEndTestSupport {

    @Test
    @DisplayName("records FAILED(rules-exception) with no POST and no digest")
    void records_rules_exception_without_sending() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        // A prosecution-case defendant with no offences: R5 reads offences without a guard and throws.
        final String payload = "{\"hearing\":{\"prosecutionCases\":[{\"defendants\":[{}]}]}}";
        putDatedCachePayload(hearingId, hearingDay, payload);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, commandJson(requestId, hearingId, hearingDay,
                "2026-08-19T16:42:07.512Z"));

        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, hearingDay);
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.get();
        assertThat(row.get("leg_status")).isEqualTo("FAILED");
        assertThat(row.get("reason")).isEqualTo("rules-exception");
        assertThat(row.get("youth_switch")).isEqualTo("ACTIVE");
        assertThat(row.get("payload_source")).isEqualTo("cache-day");
        assertThat(row.get("body_digest")).isNull();
        assertThat(row.get("body")).isNull();
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(gatewayPostCount()).isZero();
    }
}
