package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/** Pins DV-26 against real broker deliveries, persisted requests and the HTTP gateway. */
class DlqResubmissionReplayIT extends DeadLetterTestSupport {

    private static final int INITIAL_ATTEMPT = 1;

    @ParameterizedTest
    @CsvSource({"NONE,copy", "SUPPORT,support", "REPLAY,service", "unexpected,copy"})
    void dlq_copy_at_first_delivery_should_recover_and_audit_route(final String origin,
                                                                  final String route) {
        final DistributionCommand command = failedRequest();
        final String messageId = originalId(command);
        seedLeg(command, "FAILED", "gateway-transient");
        prepareGateway(command, 200);
        final Logger logger = (Logger) LoggerFactory.getLogger(ProbationMessageListener.class);
        final ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
        try {
            resubmitDeadLetter(command, messageId, origin);
            assertThat(processDeliveries(1)).isZero();
            assertRecovered(command, route);
            assertThat(gatewayPostCount()).isEqualTo(1);
            assertThat(logs.list).anySatisfy(event -> assertThat(event.getFormattedMessage())
                    .contains("replay admitted", "requestId=" + command.requestId(), "route=" + route));
        } finally {
            logger.detachAppender(logs);
            logs.stop();
        }
    }

    @Test
    void exhausted_identity_on_second_delivery_should_dead_letter_without_changing_request() {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "FAILED", "gateway-transient");
        final Map<String, Object> before = requestRow(command);
        send(command, originalId(command), "NONE");
        abandonOnce();

        assertThat(processDeliveries(1)).isEqualTo(1);

        assertThat(awaitDeadLetter(originalId(command)).orElseThrow().getDeadLetterReason())
                .isEqualTo("exhausted");
        assertThat(requestRow(command)).isEqualTo(before);
        assertThat(gatewayPostCount()).isZero();
        assertThat(replayCount("copy")).isZero();
    }

    @Test
    void new_identity_at_higher_delivery_count_should_replay() {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "FAILED", "gateway-transient");
        prepareGateway(command, 200);
        send(command, UUID.randomUUID().toString(), "NONE");
        abandonOnce();

        assertThat(processDeliveries(1)).isEqualTo(1);

        assertRecovered(command, "copy");
        assertThat(gatewayPostCount()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"SENT,1", "NOT_APPLICABLE,1", "SENT,2", "NOT_APPLICABLE,2"})
    void producer_republish_should_preserve_terminal_success(final String status, final int attempt) {
        final DistributionCommand command = failedRequest();
        if (attempt > INITIAL_ATTEMPT) {
            seedLeg(command, "FAILED", "gateway-transient");
        }
        seedLeg(command, status, "NOT_APPLICABLE".equals(status)
                ? "nothing-left-after-redaction" : null, attempt);
        final Map<String, Object> before = legRow(command);
        prepareGateway(command, 200);
        send(command, originalId(command), "NONE");

        processDeliveries(1);

        assertThat(gatewayPostCount()).as("terminal-success legs must never be POSTed again").isZero();
        assertRecovered(command, "copy");
        assertThat(legRow(command)).isEqualTo(before);
    }

    @Test
    void reclaimed_retry_after_terminal_success_should_complete_without_reposting() {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "SENT", null);
        jdbc().sql("UPDATE processed_request SET status = 'RETRYING', failure_reason = NULL, "
                        + "exhausted_message_id = NULL WHERE request_id = :id")
                .param("id", command.requestId()).update();
        final Map<String, Object> before = legRow(command);
        prepareGateway(command, 200);
        send(command, UUID.randomUUID().toString(), "REPLAY");

        processDeliveries(1);

        assertThat(requestRow(command).get("status")).isEqualTo("COMPLETED");
        assertThat(requestRow(command).get("completion_reason")).isEqualTo("both-legs-terminal");
        assertThat(legRow(command)).isEqualTo(before);
        assertThat(gatewayPostCount()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NONE", "SUPPORT", "REPLAY", "unexpected"})
    void completed_request_should_ignore_every_copy(final String origin) {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "SENT", null);
        jdbc().sql("UPDATE processed_request SET status = 'COMPLETED', failure_reason = NULL, "
                        + "completion_reason = 'both-legs-terminal' WHERE request_id = :id")
                .param("id", command.requestId()).update();
        final Map<String, Object> before = requestRow(command);
        final Map<String, Object> legBefore = legRow(command);
        send(command, originalId(command), origin);

        processDeliveries(1);

        assertThat(requestRow(command)).isEqualTo(before);
        assertThat(legRow(command)).isEqualTo(legBefore);
        assertThat(gatewayPostCount()).isZero();
        assertThat(replayCount("copy") + replayCount("support") + replayCount("service")).isZero();
    }

    @Test
    void rolled_back_request_with_no_exhausted_identity_should_replay_on_redelivery() {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "FAILED", "rolled-back");
        jdbc().sql("UPDATE processed_request SET failure_reason = 'rolled-back', "
                        + "exhausted_message_id = NULL WHERE request_id = :id")
                .param("id", command.requestId()).update();
        prepareGateway(command, 200);
        send(command, originalId(command), "NONE");
        abandonOnce();

        assertThat(processDeliveries(1)).isEqualTo(1);

        assertRecovered(command, "copy");
        assertThat(gatewayPostCount()).isEqualTo(1);
    }

    @Test
    void replay_exhaustion_should_record_the_actual_new_message_identity() {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "FAILED", "gateway-transient");
        prepareGateway(command, 503);
        final String replayId = UUID.randomUUID().toString();
        send(command, replayId, "REPLAY");

        processDeliveries(MAX_DELIVERY_COUNT);

        assertThat(awaitDeadLetter(replayId).orElseThrow().getDeadLetterReason())
                .isEqualTo("leg-exhausted");
        assertThat(requestRow(command).get("exhausted_message_id")).isEqualTo(replayId);
        assertThat(replayCount("service")).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"true,true", "true,false", "false,true", "false,false"})
    void pending_resend_should_defer_until_identity_or_delivery_budget_is_exhausted(
            final boolean sameIdentity, final boolean liveLease) {
        final DistributionCommand command = failedRequest();
        seedLeg(command, "FAILED", "gateway-transient");
        seedLeg(command, "PENDING", null, 2);
        jdbc().sql("UPDATE outbound_message SET claim_expires_at = now() "
                        + "+ make_interval(secs => :seconds) WHERE request_id = :id AND attempt = 2")
                .param("seconds", liveLease ? 3600 : -60).param("id", command.requestId()).update();
        final Map<String, Object> before = requestRow(command);
        final Map<String, Object> legBefore = legRow(command);
        final String id = sameIdentity ? originalId(command) : UUID.randomUUID().toString();
        send(command, id, "REPLAY");

        processDeliveries(sameIdentity ? 2 : MAX_DELIVERY_COUNT);

        assertThat(awaitDeadLetter(id).orElseThrow().getDeadLetterReason()).isEqualTo("exhausted");
        assertThat(requestRow(command)).isEqualTo(before);
        assertThat(legRow(command)).isEqualTo(legBefore);
        assertThat(gatewayPostCount()).isZero();
        assertThat(replayCount("service")).isZero();
        assertThat(meterRegistry().get("probation_replay_deferred_total").counter().count())
                .isEqualTo(sameIdentity ? 1 : MAX_DELIVERY_COUNT);
    }

    @ParameterizedTest
    @ValueSource(strings = {"validation", "collision"})
    void invalid_copy_of_failed_request_should_remain_dead_lettered(final String reason) {
        final DistributionCommand command = failedRequest();
        final Map<String, Object> before = requestRow(command);
        final String body = "validation".equals(reason)
                ? commandBody(command).replace("Hearing_Resulted", "invalid")
                : commandJson(command.requestId(), UUID.randomUUID(), DAY, SHARED.toString());
        sendBody(body, originalId(command), "REPLAY");

        processDeliveries(1);

        assertThat(awaitDeadLetter(originalId(command)).orElseThrow().getDeadLetterReason())
                .isEqualTo(reason);
        assertThat(requestRow(command)).isEqualTo(before);
        assertThat(gatewayPostCount()).isZero();
        assertThat(replayCount("service")).isZero();
    }
}
