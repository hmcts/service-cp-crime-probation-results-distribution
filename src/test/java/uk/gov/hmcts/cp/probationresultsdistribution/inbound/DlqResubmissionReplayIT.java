package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder.ServiceBusReceiverClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/** Pins DV-26 against real broker deliveries, persisted requests and the HTTP gateway. */
class DlqResubmissionReplayIT extends EndToEndTestSupport {

    private static final int INITIAL_ATTEMPT = 1;
    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final Instant SHARED = Instant.parse("2026-08-19T16:42:07.512Z");
    private static final String PAYLOAD = "{\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";

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

    private DistributionCommand failedRequest() {
        final DistributionCommand command = new DistributionCommand("RESULTS", UUID.randomUUID(),
                UUID.randomUUID(), DAY, SHARED, "Hearing_Resulted", Optional.empty());
        seedRequest(command, "FAILED", false);
        return command;
    }

    private void seedLeg(final DistributionCommand command, final String status, final String reason) {
        seedLeg(command, status, reason, 1);
    }

    private void seedLeg(final DistributionCommand command, final String status, final String reason,
                         final int attempt) {
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, http_status, sent_at, payload_source, "
                        + "body_digest, service_version, youth_switch, "
                        + "claim_owner, claim_token, claim_expires_at, resend_run_id) VALUES "
                        + "(:id, 'RESULTS', :requestId, 'HMPPS', :attempt, :origin, :status, :reason, 1, "
                        + "CASE WHEN :status = 'SENT' THEN 200 ELSE NULL END, "
                        + "CASE WHEN :status = 'SENT' THEN now() ELSE NULL END, 'cache-day', "
                        + ":digest, :version, CASE WHEN :status = 'PENDING' THEN NULL ELSE 'ACTIVE' END, :owner, :token, :expires, :runId)")
                .param("id", UUID.randomUUID()).param("requestId", command.requestId())
                .param("status", status).param("reason", reason)
                .param("attempt", attempt).param("origin", attempt == 1 ? "queue" : "resend")
                .param("owner", attempt == 1 ? null : UUID.randomUUID())
                .param("token", attempt == 1 ? null : UUID.randomUUID())
                .param("expires", attempt == 1 ? null : java.time.OffsetDateTime.now().minusMinutes(1))
                .param("runId", attempt == 1 ? null : UUID.randomUUID())
                .param("digest", "SENT".equals(status) || "gateway-transient".equals(reason)
                        ? sha256Hex("prior-body") : null)
                .param("version", "SENT".equals(status) || "gateway-transient".equals(reason)
                        ? "test-version" : null).update();
    }

    private void prepareGateway(final DistributionCommand command, final int status) {
        putDatedCachePayload(command.hearingId(), DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(status);
    }

    private void assertRecovered(final DistributionCommand command, final String route) {
        final Map<String, Object> row = requestRow(command);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("recovered");
        assertThat(row.get("audit_note")).asString().contains("REPLAYED_AFTER_FAILURE", "route=" + route);
        assertThat(replayCount(route)).isEqualTo(1);
    }

    private Map<String, Object> requestRow(final DistributionCommand command) {
        return jdbc().sql("SELECT * FROM processed_request WHERE request_id = :id")
                .param("id", command.requestId()).query().singleRow();
    }

    private Map<String, Object> legRow(final DistributionCommand command) {
        return jdbc().sql("SELECT * FROM outbound_message_latest WHERE request_id = :id")
                .param("id", command.requestId()).query().singleRow();
    }

    private double replayCount(final String route) {
        final Counter counter = meterRegistry().find("probation_replays_total").tag("route", route).counter();
        return counter == null ? 0 : counter.count();
    }

    private String commandBody(final DistributionCommand command) {
        return commandJson(command.requestId(), command.hearingId(), DAY, SHARED.toString());
    }

    private static String originalId(final DistributionCommand command) {
        return "RESULTS:" + command.requestId();
    }

    private void send(final DistributionCommand command, final String id, final String origin) {
        sendBody(commandBody(command), id, origin);
    }

    private void sendBody(final String body, final String id, final String origin) {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).sender().queueName(QUEUE).buildClient()) {
            final ServiceBusMessage message = new ServiceBusMessage(body).setMessageId(id);
            if (!"NONE".equals(origin)) {
                message.getApplicationProperties().put("origin", origin);
            }
            sender.sendMessage(message);
        }
    }

    private void resubmitDeadLetter(final DistributionCommand command, final String id,
                                   final String origin) {
        send(command, id, origin);
        try (ServiceBusReceiverClient receiver = receiver(false)) {
            final ServiceBusReceivedMessage original = receive(receiver);
            receiver.deadLetter(original);
        }
        try (ServiceBusReceiverClient dlq = receiver(true)) {
            final List<ServiceBusReceivedMessage> unrelated = new ArrayList<>();
            boolean resubmitted = false;
            final long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            try {
                while (!resubmitted && System.nanoTime() < deadline) {
                    for (final ServiceBusReceivedMessage parked
                            : dlq.receiveMessages(100, Duration.ofSeconds(1))) {
                        if (id.equals(parked.getMessageId())) {
                            final Object retainedOrigin = parked.getApplicationProperties().get("origin");
                            sendBody(parked.getBody().toString(), id,
                                    retainedOrigin == null ? "NONE" : retainedOrigin.toString());
                            dlq.complete(parked);
                            resubmitted = true;
                        } else {
                            unrelated.add(parked);
                        }
                    }
                }
            } finally {
                unrelated.forEach(dlq::abandon);
            }
            assertThat(resubmitted).as("the original dead letter was copied back to the queue").isTrue();
        }
    }

    private ServiceBusReceiverClient receiver(final boolean deadLetter) {
        final ServiceBusReceiverClientBuilder builder = new ServiceBusClientBuilder().connectionString(serviceBusConnectionString())
                .receiver().queueName(QUEUE).prefetchCount(0);
        if (deadLetter) {
            builder.subQueue(SubQueue.DEAD_LETTER_QUEUE);
        }
        return builder.buildClient();
    }

    private static ServiceBusReceivedMessage receive(final ServiceBusReceiverClient receiver) {
        final Iterator<ServiceBusReceivedMessage> messages =
                receiver.receiveMessages(1, Duration.ofSeconds(20)).iterator();
        assertThat(messages.hasNext()).as("broker delivery should arrive").isTrue();
        return messages.next();
    }

    private void abandonOnce() {
        try (ServiceBusReceiverClient receiver = receiver(false)) {
            final ServiceBusReceivedMessage message = receive(receiver);
            assertThat(message.getDeliveryCount()).isZero();
            receiver.abandon(message);
        }
    }

    private long processDeliveries(final int expectedDeliveries) {
        final ProbationMessageListener listener = listener();
        final CountDownLatch deliveries = new CountDownLatch(expectedDeliveries);
        final AtomicLong firstCount = new AtomicLong(-1);
        try (ServiceBusProcessorClient processor = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).processor().queueName(QUEUE)
                .disableAutoComplete().maxConcurrentCalls(1).processMessage(context -> {
                    firstCount.compareAndSet(-1, context.getMessage().getDeliveryCount());
                    listener.processMessage(context);
                    deliveries.countDown();
                }).processError(context -> {
                    throw new IllegalStateException("broker processor failed", context.getException());
                }).buildProcessorClient()) {
            processor.start();
            assertThat(deliveries.await(60, TimeUnit.SECONDS)).as("production listener settled deliveries").isTrue();
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted awaiting broker settlement", interrupted);
        }
        return firstCount.get();
    }
}
