package uk.gov.hmcts.cp.probationresultsdistribution.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusClientBuilder.ServiceBusReceiverClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
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
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;

/**
 * The dead-letter fixtures shared by {@link DlqResubmissionReplayIT} (DV-26) and
 * {@link RunbookCrossReferenceIT} (the dead-letter runbook, FR-004-046): seeded requests and leg
 * rows, real broker sends with or without an {@code origin} marker, a dead-letter entry made by the
 * broker tools and copied back under its own {@code messageId} as an operator resubmits it, and a
 * production listener driven for a counted number of deliveries.
 */
class DeadLetterTestSupport extends EndToEndTestSupport {

    protected static final LocalDate DAY = LocalDate.parse("2026-08-19");
    protected static final Instant SHARED = Instant.parse("2026-08-19T16:42:07.512Z");
    protected static final String PAYLOAD = "{\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
            + "{\"isYouth\":false,\"offences\":[]}]}]}}";
    protected static final String NO_ORIGIN = "NONE";
    private static final Duration DETACH_GRACE = Duration.ofSeconds(2);

    /**
     * A fresh command whose request is seeded {@code FAILED(leg-exhausted)} under its exhausted
     * {@code messageId}, with no leg row yet.
     *
     * @return the command
     */
    protected final DistributionCommand failedRequest() {
        final DistributionCommand command = newCommand();
        seedRequest(command, "FAILED", false);
        return command;
    }

    /**
     * A fresh command with nothing seeded for it.
     *
     * @return the command
     */
    protected static DistributionCommand newCommand() {
        return new DistributionCommand("RESULTS", UUID.randomUUID(), UUID.randomUUID(), DAY, SHARED,
                "Hearing_Resulted", Optional.empty());
    }

    /**
     * Seeds an attempt-1 queue leg row.
     *
     * @param command the command
     * @param status  the leg status
     * @param reason  the leg reason, or {@code null}
     */
    protected final void seedLeg(final DistributionCommand command, final String status,
                                 final String reason) {
        seedLeg(command, status, reason, 1);
    }

    /**
     * Seeds a leg row at an attempt; above 1 it is a resend row with an expired leg claim.
     *
     * @param command the command
     * @param status  the leg status
     * @param reason  the leg reason, or {@code null}
     * @param attempt the attempt number
     */
    protected final void seedLeg(final DistributionCommand command, final String status,
                                 final String reason, final int attempt) {
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

    /**
     * Caches a sendable payload for the command and stubs the switch and the gateway.
     *
     * @param command the command
     * @param status  the gateway's answer
     */
    protected final void prepareGateway(final DistributionCommand command, final int status) {
        putDatedCachePayload(command.hearingId(), DAY, PAYLOAD);
        stubYouthSwitchActive();
        stubGateway(status);
    }

    /**
     * Asserts the request recovered by a replay on the given route, counted once.
     *
     * @param command the command
     * @param route   the replay route
     */
    protected final void assertRecovered(final DistributionCommand command, final String route) {
        final Map<String, Object> row = requestRow(command);
        assertThat(row.get("status")).isEqualTo("COMPLETED");
        assertThat(row.get("completion_reason")).isEqualTo("recovered");
        assertThat(row.get("audit_note")).asString().contains("REPLAYED_AFTER_FAILURE", "route=" + route);
        assertThat(replayCount(route)).isEqualTo(1);
    }

    /**
     * The request row.
     *
     * @param command the command
     * @return its columns
     */
    protected final Map<String, Object> requestRow(final DistributionCommand command) {
        return jdbc().sql("SELECT * FROM processed_request WHERE request_id = :id")
                .param("id", command.requestId()).query().singleRow();
    }

    /**
     * The leg's latest attempt row.
     *
     * @param command the command
     * @return its columns
     */
    protected final Map<String, Object> legRow(final DistributionCommand command) {
        return jdbc().sql("SELECT * FROM outbound_message_latest WHERE request_id = :id")
                .param("id", command.requestId()).query().singleRow();
    }

    /**
     * The replays counted on a route.
     *
     * @param route the replay route
     * @return the count
     */
    protected final double replayCount(final String route) {
        final Counter counter = meterRegistry().find("probation_replays_total").tag("route", route).counter();
        return counter == null ? 0 : counter.count();
    }

    /**
     * The command's wire body.
     *
     * @param command the command
     * @return the JSON body
     */
    protected final String commandBody(final DistributionCommand command) {
        return commandJson(command.requestId(), command.hearingId(), DAY, SHARED.toString());
    }

    /**
     * The producer's {@code messageId}, which is also a dead-lettered request's exhausted identity.
     *
     * @param command the command
     * @return {@code RESULTS:{requestId}}
     */
    protected static String originalId(final DistributionCommand command) {
        return "RESULTS:" + command.requestId();
    }

    /**
     * Sends the command's body to the queue.
     *
     * @param command the command
     * @param id      the {@code messageId}
     * @param origin  the {@code origin} application property, or {@link #NO_ORIGIN}
     */
    protected final void send(final DistributionCommand command, final String id, final String origin) {
        sendBody(commandBody(command), id, origin);
    }

    /**
     * Sends a body to the queue.
     *
     * @param body   the body
     * @param id     the {@code messageId}
     * @param origin the {@code origin} application property, or {@link #NO_ORIGIN}
     */
    protected final void sendBody(final String body, final String id, final String origin) {
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).sender().queueName(QUEUE).buildClient()) {
            final ServiceBusMessage message = new ServiceBusMessage(body).setMessageId(id);
            if (!NO_ORIGIN.equals(origin)) {
                message.getApplicationProperties().put("origin", origin);
            }
            sender.sendMessage(message);
        }
    }

    /**
     * Puts the command on the dead-letter queue with no reason, as the broker tools can, then
     * resubmits it under the same {@code messageId}.
     *
     * @param command the command
     * @param id      the {@code messageId}
     * @param origin  the {@code origin} application property, or {@link #NO_ORIGIN}
     */
    protected final void resubmitDeadLetter(final DistributionCommand command, final String id,
                                            final String origin) {
        deadLetterDirectly(commandBody(command), id, origin, Optional.empty());
        resubmitFromDeadLetter(id);
    }

    /**
     * Sends a body and dead-letters it at once with the given reason, as the broker would have left
     * it, without the service consuming it.
     *
     * @param body   the body
     * @param id     the {@code messageId}
     * @param origin the {@code origin} application property, or {@link #NO_ORIGIN}
     * @param reason the dead-letter reason to record, or empty for none
     */
    protected final void deadLetterDirectly(final String body, final String id, final String origin,
                                            final Optional<String> reason) {
        sendBody(body, id, origin);
        try (ServiceBusReceiverClient receiver = receiver(false)) {
            final ServiceBusReceivedMessage original = receive(receiver);
            if (reason.isPresent()) {
                receiver.deadLetter(original, new DeadLetterOptions().setDeadLetterReason(reason.get()));
            } else {
                receiver.deadLetter(original);
            }
        }
    }

    /**
     * Resubmits a dead-letter entry as an operator does with the broker tools: copies its body and
     * {@code origin} back to the queue under the same {@code messageId}, then completes (removes) the
     * dead-letter entry.
     *
     * @param id the dead-lettered {@code messageId}
     */
    protected final void resubmitFromDeadLetter(final String id) {
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
                                    retainedOrigin == null ? NO_ORIGIN : retainedOrigin.toString());
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

    /** Receives the next delivery and abandons it, so the following one arrives at count 1. */
    protected final void abandonOnce() {
        try (ServiceBusReceiverClient receiver = receiver(false)) {
            final ServiceBusReceivedMessage message = receive(receiver);
            assertThat(message.getDeliveryCount()).isZero();
            receiver.abandon(message);
        }
    }

    /**
     * Runs the production listener until it has settled the given number of deliveries.
     *
     * @param expectedDeliveries the deliveries to settle
     * @return the broker delivery count of the first delivery seen
     */
    protected final long processDeliveries(final int expectedDeliveries) {
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
        // As EndToEndTestSupport.stopProcessorNow: let the closed processor's receive link detach
        // before anything else is sent, so the next message is not handed to it and left locked.
        detachGrace();
        return firstCount.get();
    }

    private static void detachGrace() {
        try {
            Thread.sleep(DETACH_GRACE.toMillis());
        } catch (final InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted waiting for the processor to detach",
                    interrupted);
        }
    }
}
