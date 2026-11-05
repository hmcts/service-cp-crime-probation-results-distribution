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

/** US5 Acceptance Scenario 8: a transient send fails visibly on the final permitted delivery. */
@DisplayName("a transient gateway failure is exhausted on the final delivery")
class GatewayExhaustedIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String PAYLOAD = "{\"policeCases\":[{\"x\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";

    @Test
    @DisplayName("fails the leg and request, and dead-letters with leg-exhausted")
    void exhausts_transient_gateway_failure() {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(503);
        startProcessorWith(listenerOver(sharedDataSource(), alwaysAvailableStore(), 1), 1);

        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        final Map<String, Object> row = awaitLegStatus(hearingId, HEARING_DAY, "FAILED")
                .orElseThrow();
        assertThat(row.get("reason")).isEqualTo("gateway-transient");
        assertThat(row.get("http_status")).isEqualTo(503);
        assertThat(row.get("request_status")).isEqualTo("FAILED");
        assertThat(row.get("failure_reason")).isEqualTo("leg-exhausted");
        assertThat(gatewayPostCount()).isEqualTo(1);
        final Optional<ServiceBusReceivedMessage> dead = awaitDeadLetter("RESULTS:" + requestId);
        assertThat(dead).isPresent();
        assertThat(dead.orElseThrow().getDeadLetterReason()).isEqualTo("leg-exhausted");
        assertThat(meterRegistry().get("probation_deadlettered_total")
                .tag("reason", "leg-exhausted").counter().count()).isEqualTo(1.0d);
        assertThat(meterRegistry().get("probation_processed_total")
                .tag("status", "FAILED").counter().count()).isEqualTo(1.0d);
        assertThat(outboundBodyCount(requestId)).isEqualTo(1);
    }
}
