package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.DeadLetterOptions;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;

/**
 * Executes the packaged read-only listings (slice 004, US6; {@code contracts/cli-runners.md} §7,
 * §8) against the shared PostgreSQL and the Service Bus emulator: the separate CLI composition root
 * builds each listing, prints its rows and nothing it must not, and exits with the contract's code.
 */
@DisplayName("the packaged listing commands")
class PackagedListingCommandsIT extends EndToEndTestSupport {

    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final String CANARY = "CANARY-" + "never-printed";

    @TempDir
    private Path outputDirectory;

    @Test
    @DisplayName("list-requests by hearing and day prints the request's attempts and exits 0")
    void list_requests_by_hearing_prints_rows() throws Exception {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = seedCompletedRequest(hearingId);

        final RunResult result = run("list-requests", "--hearing-id", hearingId.toString(),
                "--hearing-day", DAY.toString());

        assertThat(result.exitCode()).withFailMessage("command failed: %s", result.output())
                .isZero();
        assertThat(result.output()).contains("list-requests row requestId=" + requestId,
                "attempt=1 attemptOrigin=queue legStatus=SENT", "list-requests done rows=1 more=false")
                .doesNotContain(CANARY, "Tomcat started");
    }

    @Test
    @DisplayName("list-requests refuses an unreadable status with exit 1, never echoing it")
    void list_requests_refuses_unreadable_input() throws Exception {
        final RunResult result = run("list-requests", "--status", "SECRET-status");

        assertThat(result.exitCode()).withFailMessage("command output: %s", result.output())
                .isEqualTo(1);
        assertThat(result.output()).contains("unreadable-argument").doesNotContain("SECRET");
    }

    @Test
    @DisplayName("list-dlq-completed lists a completed request's entry only, removes nothing, "
            + "and exits 0")
    void list_dlq_completed_prints_completed_entries() throws Exception {
        final UUID completed = seedCompletedRequest(UUID.randomUUID());
        final UUID unknown = UUID.randomUUID();
        deadLetter(completed, "leg-exhausted");
        deadLetter(unknown, "exhausted");

        final RunResult result = run("list-dlq-completed");

        assertThat(result.exitCode()).withFailMessage("command failed: %s", result.output())
                .isZero();
        assertThat(result.output()).contains("list-dlq-completed entry", "messageId=RESULTS:"
                        + completed + " requestId=" + completed + " deadLetterReason=leg-exhausted",
                "list-dlq-completed done").doesNotContain("requestId=" + unknown, CANARY);
        assertThat(peekDeadLetterNow("RESULTS:" + completed)).isPresent();
        assertThat(peekDeadLetterNow("RESULTS:" + unknown)).isPresent();
    }

    private UUID seedCompletedRequest(final UUID hearingId) {
        final UUID requestId = UUID.randomUUID();
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, "
                        + "completion_reason, command, audit_note) VALUES ('RESULTS', :requestId, "
                        + ":hearingId, :day, now(), 'Hearing_Resulted', 'fp', 'COMPLETED', 1, "
                        + "'recovered', :command, :auditNote)")
                .param("requestId", requestId)
                .param("hearingId", hearingId)
                .param("day", DAY)
                .param("command", "{\"canary\":\"" + CANARY + "\"}")
                .param("auditNote", CANARY)
                .update();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts, http_status, body_digest, "
                        + "service_version, payload_source, youth_switch, sent_at) VALUES (:id, "
                        + "'RESULTS', :requestId, 'HMPPS', 1, 'queue', 'SENT', 1, 200, 'digest', "
                        + "'test-version', 'cache-day', 'ACTIVE', now())")
                .param("id", UUID.randomUUID())
                .param("requestId", requestId)
                .update();
        return requestId;
    }

    private static void deadLetter(final UUID requestId, final String reason) {
        final String messageId = "RESULTS:" + requestId;
        try (ServiceBusSenderClient sender = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).sender().queueName(QUEUE)
                .buildClient()) {
            sender.sendMessage(new ServiceBusMessage("{\"source\":\"RESULTS\",\"requestId\":\""
                    + requestId + "\",\"hearingId\":\"" + UUID.randomUUID() + "\",\"hearingDay\":\""
                    + DAY + "\",\"sharedTime\":\"2026-08-19T16:42:07.512Z\",\"eventType\":"
                    + "\"Hearing_Resulted\"}").setMessageId(messageId));
        }
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).receiver().queueName(QUEUE)
                .prefetchCount(0).buildClient()) {
            final DeadLetterOptions options = new DeadLetterOptions().setDeadLetterReason(reason);
            boolean deadLettered = false;
            final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (!deadLettered && System.nanoTime() < deadline) {
                final Iterator<ServiceBusReceivedMessage> messages =
                        receiver.receiveMessages(10, Duration.ofSeconds(2)).iterator();
                while (messages.hasNext()) {
                    final ServiceBusReceivedMessage message = messages.next();
                    if (messageId.equals(message.getMessageId())) {
                        receiver.deadLetter(message, options);
                        deadLettered = true;
                    } else {
                        receiver.abandon(message);
                    }
                }
            }
            assertThat(deadLettered).as("the message was dead-lettered").isTrue();
        }
    }

    private RunResult run(final String... args) throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("app.jar")));
        command.addAll(List.of(args));
        final Path output = Files.createTempFile(outputDirectory, "listing-", ".log");
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        final DriverManagerDataSource source = (DriverManagerDataSource) sharedDataSource();
        final Map<String, String> environment = new HashMap<>();
        environment.put("SPRING_PROFILES_ACTIVE", "local");
        environment.put("SPRING_DATASOURCE_URL", source.getUrl());
        environment.put("SPRING_DATASOURCE_USERNAME", source.getUsername());
        environment.put("SPRING_DATASOURCE_PASSWORD", source.getPassword());
        environment.put("SERVICEBUS_CONNECTION_STRING", serviceBusConnectionString());
        environment.put("AUTHZ_HTTP_ENABLED", "false");
        environment.put("HTTP_AUDIT_ENABLED", "false");
        environment.put("REDIS_HOST", redisHost());
        environment.put("REDIS_PORT", Integer.toString(redisPort()));
        environment.put("REDIS_SSL", "false");
        environment.put("RESULTS_BASE_URL", usersGroupsBaseUrl());
        environment.put("USERSGROUPS_BASE_URL", usersGroupsBaseUrl());
        environment.put("PROBATION_RESULTS_DISTRIBUTION_SYSTEM_USER_ID", SYSTEM_USER_ID);
        environment.put("HMPPS_PUBLISH_URL", gatewayUrl());
        environment.put("GATEWAY_SUBSCRIPTION_KEY", SUBSCRIPTION_KEY);
        builder.environment().putAll(environment);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(90, TimeUnit.SECONDS)).as("operator process must exit")
                    .isTrue();
            return new RunResult(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private record RunResult(int exitCode, String output) {
    }
}
