package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;

/** US5 Acceptance Scenarios 3–5: outcome-unknown gateway responses park without in-run retries. */
@DisplayName("outcome-unknown gateway responses park the HMPPS leg")
class GatewayTransientParkIT extends EndToEndTestSupport {

    private static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    private static final String PAYLOAD = "{\"policeCases\":[{\"x\":1}],"
            + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";

    private static Stream<Arguments> unknownOutcomes() {
        return Stream.of(Arguments.of(503, 0, 60_000, 503),
                Arguments.of(408, 0, 60_000, 408),
                Arguments.of(200, 350, 100, null));
    }

    @ParameterizedTest(name = "status {0}, delay {1}ms, read timeout {2}ms")
    @MethodSource("unknownOutcomes")
    @DisplayName("parks with the exact body evidence and returns the message for broker redelivery")
    void parks_without_retrying_an_outcome_unknown_answer(final int status, final int delayMillis,
            final int readTimeoutMillis, final Integer expectedStatus) {
        final UUID requestId = UUID.randomUUID();
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, PAYLOAD);
        stubYouthSwitchActive();
        if (delayMillis == 0) {
            stubGateway(status);
        } else {
            stubGatewayWithDelay(status, delayMillis);
        }
        startProcessorWith(listenerOver(sharedDataSource(), alwaysAvailableStore(), 10,
                Duration.ofSeconds(1), Duration.ofMillis(readTimeoutMillis)), 1);

        sendCommand(requestId, commandJson(requestId, hearingId, HEARING_DAY,
                "2026-08-19T16:42:07.512Z"));

        final Map<String, Object> row = awaitLegStatus(hearingId, HEARING_DAY, "PARKED")
                .orElseThrow();
        assertThat(row.get("reason")).isEqualTo("gateway-transient");
        assertThat(row.get("http_status")).isEqualTo(expectedStatus);
        assertThat(row.get("request_status")).isEqualTo("RETRYING");
        assertThat(row.get("body")).isNotNull();
        assertThat(row.get("body_digest")).isEqualTo(sha256Hex((String) row.get("body")));
        assertThat(outboundBodyCount(requestId)).isEqualTo(1);
        assertThat(gatewayPostCount()).isGreaterThanOrEqualTo(1);
        // Keep the processor active until the broker exhausts its own delivery limit, preventing
        // this abandoned message from leaking into the next integration test.
        assertThat(awaitDeadLetter("RESULTS:" + requestId)).isPresent();
    }
}
