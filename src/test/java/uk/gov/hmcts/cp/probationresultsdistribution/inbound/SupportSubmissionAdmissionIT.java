package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import io.micrometer.core.instrument.Counter;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * Pins the admission of a submit-hearing command against real broker deliveries and the store
 * (slice 004, US8; FR-004-057): a command marked {@code origin = SUPPORT} is a queue delivery —
 * {@code origin = queue} — with its operator, run id and reason on {@code audit_note}, unless it
 * meets a {@code FAILED} request, when it is a replay with route {@code support}; a
 * {@code COMPLETED} request makes it a no-op.
 */
@DisplayName("a submit-hearing command's admission")
class SupportSubmissionAdmissionIT extends DeadLetterTestSupport {

    private static final String RUN_ID = "7d1f0c9a-55b2-4c1e-8f3a-9b6e2d4c1a77";
    private static final String LINE = "SUPPORT_SUBMISSION operator=local-dev runId=" + RUN_ID
            + " reason=share never admitted";

    @Test
    @DisplayName("a first delivery runs as a queue delivery with the markers on audit_note")
    void first_delivery_should_run_as_queue_and_audit_the_markers() {
        final DistributionCommand command = newCommand();
        prepareGateway(command, 200);

        sendSupport(command);
        assertThat(processDeliveries(1)).isZero();

        final Map<String, Object> request = requestRow(command);
        assertThat(request.get("status")).isEqualTo("COMPLETED");
        assertThat(request.get("completion_reason")).isEqualTo("both-legs-terminal");
        assertThat((String) request.get("audit_note")).contains(LINE)
                .doesNotContain("REPLAYED_AFTER_FAILURE");
        final Map<String, Object> leg = legRow(command);
        assertThat(leg.get("attempt_origin")).isEqualTo("queue");
        assertThat(leg.get("status")).isEqualTo("SENT");
        assertThat(outcomes("queue")).isEqualTo(1.0d);
        assertThat(replayCount("support")).isZero();
    }

    @Test
    @DisplayName("a FAILED request's share is a replay, route support, with the markers")
    void failed_request_should_replay_on_route_support() {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "FAILED", "gateway-transient");
        prepareGateway(command, 200);

        sendSupport(command);
        assertThat(processDeliveries(1)).isZero();

        assertRecovered(command, "support");
        final String[] lines = ((String) requestRow(command).get("audit_note")).split("\n");
        assertThat(lines[lines.length - 1]).endsWith(LINE);
        assertThat(gatewayPostCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a COMPLETED request's share is a no-op, auditing nothing")
    void completed_request_should_be_a_no_op() {
        final DistributionCommand command = newCommand();
        prepareGateway(command, 200);
        sendSupport(command);
        assertThat(processDeliveries(1)).isZero();
        final Object before = requestRow(command).get("audit_note");

        sendSupport(command);
        assertThat(processDeliveries(1)).isZero();

        assertThat(requestRow(command).get("status")).isEqualTo("COMPLETED");
        assertThat(requestRow(command).get("audit_note")).isEqualTo(before);
        assertThat(gatewayPostCount()).isEqualTo(1);
    }

    private void sendSupport(final DistributionCommand command) {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).sender().queueName(QUEUE)
                .buildClient()) {
            final ServiceBusMessage message = new ServiceBusMessage(commandBody(command))
                    .setMessageId(originalId(command))
                    .setCorrelationId(command.hearingId().toString())
                    .setContentType("application/json");
            message.getApplicationProperties().putAll(Map.of("origin", "SUPPORT",
                    "operator", "local-dev", "runId", RUN_ID, "reason", "share never admitted"));
            sender.sendMessage(message);
        }
    }

    private double outcomes(final String origin) {
        final Counter counter = meterRegistry().find("probation_leg_outcomes_total")
                .tag("origin", origin).counter();
        return counter == null ? 0 : counter.count();
    }
}
