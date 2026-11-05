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

/** US5 Acceptance Scenario 7: a parked gateway result recovers on a broker redelivery. */
@DisplayName("a gateway parked attempt recovers in place on redelivery")
class GatewayRecoveryIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String OLD_BODY = "{\"previous\":true}";
    private static final String PAYLOAD = "{\"policeCases\":[{\"x\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";

    @Test
    @DisplayName("overwrites the parked body and counts a 5xx redelivery recovery")
    void recovers_parked_gateway_attempt_in_place() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        final DistributionCommand command = new DistributionCommand("RESULTS", requestId, hearingId,
                HEARING_DAY, Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted",
                Optional.empty());
        seedParkedGatewayTransientLeg(command, OLD_BODY);
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(200);
        startProcessor();

        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        awaitRequestStatus(requestId, "COMPLETED");
        final Map<String, Object> row = awaitLegStatus(hearingId, HEARING_DAY, "SENT")
                .orElseThrow();
        assertThat(row.get("note")).isEqualTo("prev=PARKED/gateway-transient");
        assertThat(row.get("request_status")).isEqualTo("COMPLETED");
        assertThat(((Number) row.get("attempts")).intValue()).isGreaterThanOrEqualTo(2);
        assertThat(outboundBodyCount(requestId)).isEqualTo(1);
        assertThat(row.get("body")).isEqualTo(gatewayPostBody());
        assertThat(row.get("body_digest")).isEqualTo(sha256Hex(gatewayPostBody()));
        assertThat(meterRegistry().get("probation_gateway_recovered_total")
                .tag("after", "5xx-redelivery").counter().count()).isGreaterThanOrEqualTo(1.0d);
    }

    @Test
    @DisplayName("retries a 429 after Retry-After and records in-run recovery")
    void retries_rate_limited_gateway_in_run() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGatewaySequence(429, "2", 200);
        startProcessor();

        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        awaitRequestStatus(requestId, "COMPLETED");
        final Map<String, Object> row = awaitLegStatus(hearingId, HEARING_DAY, "SENT")
                .orElseThrow();
        assertThat(row.get("http_status")).isEqualTo(200);
        assertThat(gatewayPostCount()).isEqualTo(2);
        assertThat(outboundBodyCount(requestId)).isEqualTo(1);
        assertThat(row.get("body")).isEqualTo(gatewayPostBody());
        assertThat(meterRegistry().get("probation_gateway_recovered_total")
                .tag("after", "429-in-run").counter().count()).isEqualTo(1.0d);
    }
}
