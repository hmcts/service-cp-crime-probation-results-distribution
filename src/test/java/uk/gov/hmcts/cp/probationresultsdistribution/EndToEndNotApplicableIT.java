package uk.gov.hmcts.cp.probationresultsdistribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * US1 Acceptance Scenario 3 (T031): a payload the rules empty — here an SJP hearing (R1) — is not
 * POSTed. The attempt is {@code NOT_APPLICABLE(sjp-or-box-hearing)}, carrying the payload source and
 * switch value but no digest and no stored body, and the request is {@code COMPLETED}.
 */
@DisplayName("end to end: an emptied payload is recorded NOT_APPLICABLE and never sent")
class EndToEndNotApplicableIT extends EndToEndTestSupport {

    @Test
    @DisplayName("records NOT_APPLICABLE(sjp-or-box-hearing) with no POST and no stored body")
    void records_not_applicable_without_sending() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        final String payload = "{\"hearing\":{\"isSJPHearing\":true,"
                + "\"prosecutionCases\":[{\"defendants\":[{\"offences\":[]}]}]}}";
        putDatedCachePayload(hearingId, hearingDay, payload);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, commandJson(requestId, hearingId, hearingDay,
                "2026-08-19T16:42:07.512Z"));

        final Optional<Map<String, Object>> settled = awaitSettledLeg(hearingId, hearingDay);
        assertThat(settled).isPresent();
        final Map<String, Object> row = settled.get();
        assertThat(row.get("leg_status")).isEqualTo("NOT_APPLICABLE");
        assertThat(row.get("reason")).isEqualTo("sjp-or-box-hearing");
        assertThat(row.get("payload_source")).isEqualTo("cache-day");
        assertThat(row.get("youth_switch")).isEqualTo("ACTIVE");
        assertThat(row.get("body_digest")).isNull();
        assertThat(row.get("body")).isNull();
        assertThat(row.get("http_status")).isNull();
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("both-legs-terminal");
        assertThat(gatewayPostCount()).isZero();
    }
}
