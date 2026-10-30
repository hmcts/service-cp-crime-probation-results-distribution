package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcIntakeControlRepository;

/**
 * Executes the packaged {@code replay} command (T078; slice 004, FR-004-014–017; {@code
 * contracts/cli-runners.md} §1) beside {@link PackagedIntakeCommandsIT}: {@code java -jar app.jar
 * replay --request-id <uuid>} dispatches {@link ReplayRunner} over the production replay enqueuer,
 * which puts the stored {@code processed_request.command} on {@code probation.requests} byte for
 * byte under a fresh {@code messageId}, marked only {@code origin = REPLAY}, writing nothing to the
 * store; an argument it cannot read is refused before anything is opened.
 */
@DisplayName("the packaged replay command")
class PackagedReplayCommandIT extends EndToEndTestSupport {

    private static final Pattern ENQUEUED = Pattern.compile(
            "replay done outcome=enqueued messageId=(REPLAY:[0-9a-f-]{36})");

    @TempDir
    private Path outputDirectory;

    @AfterEach
    void resumeIntake() {
        new JdbcIntakeControlRepository(jdbc()).resume();
    }

    @Test
    @DisplayName("enqueues a FAILED request's stored command under a fresh identity, even while "
            + "intake is paused, and exits 0")
    void replay_enqueues_the_stored_command() throws Exception {
        final DistributionCommand command = command();
        seedRequest(command, "FAILED", false);
        final String stored = "{\"source\":\"RESULTS\", \"requestId\":\"" + command.requestId()
                + "\",\n \"hearingId\":\"" + command.hearingId() + "\"}";
        jdbc().sql("UPDATE processed_request SET command = :command WHERE request_id = :id")
                .param("command", stored).param("id", command.requestId()).update();
        new JdbcIntakeControlRepository(jdbc()).pause();

        final RunResult result = run("replay", "--request-id", command.requestId().toString());

        assertThat(result.exitCode()).withFailMessage("command failed: %s", result.output())
                .isZero();
        assertThat(result.output()).doesNotContain("Tomcat started", "intake is gated");
        final Matcher enqueued = ENQUEUED.matcher(result.output());
        assertThat(enqueued.find()).withFailMessage("no enqueued line: %s", result.output())
                .isTrue();
        final String messageId = enqueued.group(1);
        assertThat(messageId).isNotEqualTo("RESULTS:" + command.requestId());
        final ServiceBusReceivedMessage message = receive(messageId).orElseThrow(
                () -> new AssertionError("the replayed command was not on the queue"));
        assertThat(message.getCorrelationId()).isEqualTo(command.hearingId().toString());
        assertThat(message.getContentType()).isEqualTo("application/json");
        assertThat(message.getBody().toBytes()).isEqualTo(stored.getBytes(StandardCharsets.UTF_8));
        assertThat(message.getApplicationProperties()).containsExactlyEntriesOf(
                Map.of("origin", "REPLAY"));
        assertThat(requestStatus(command.requestId())).contains("FAILED");
    }

    @Test
    @DisplayName("refuses a missing request id with exit 1 before anything is opened")
    void replay_refuses_a_missing_request_id() throws Exception {
        final RunResult result = run(Map.of("SPRING_DATASOURCE_URL",
                "jdbc:postgresql://127.0.0.1:1/never-opened"), "replay");

        assertThat(result.exitCode()).withFailMessage("command output: %s", result.output())
                .isEqualTo(1);
        assertThat(result.output()).contains("missing-argument")
                .doesNotContain("store-or-broker-unavailable");
    }

    @Test
    @DisplayName("refuses an unreadable request id with exit 1 before anything is opened, never "
            + "echoing it")
    void replay_refuses_an_unreadable_request_id() throws Exception {
        final RunResult result = run(Map.of("SPRING_DATASOURCE_URL",
                "jdbc:postgresql://127.0.0.1:1/never-opened"), "replay", "--request-id",
                "SECRET-not-a-uuid");

        assertThat(result.exitCode()).withFailMessage("command output: %s", result.output())
                .isEqualTo(1);
        assertThat(result.output()).contains("unreadable-argument")
                .doesNotContain("SECRET", "store-or-broker-unavailable");
    }

    private static DistributionCommand command() {
        return new DistributionCommand("RESULTS", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-10-26"), Instant.parse("2026-10-26T12:00:00Z"),
                "Hearing_Resulted", Optional.empty());
    }

    private static Optional<ServiceBusReceivedMessage> receive(final String messageId) {
        try (ServiceBusReceiverClient receiver = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).receiver().queueName(QUEUE)
                .prefetchCount(0).buildClient()) {
            final long deadline = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            while (System.nanoTime() < deadline) {
                final Iterator<ServiceBusReceivedMessage> messages =
                        receiver.receiveMessages(10, Duration.ofSeconds(2)).iterator();
                while (messages.hasNext()) {
                    final ServiceBusReceivedMessage message = messages.next();
                    if (messageId.equals(message.getMessageId())) {
                        receiver.complete(message);
                        return Optional.of(message);
                    }
                    receiver.abandon(message);
                }
            }
        }
        return Optional.empty();
    }

    private RunResult run(final String... args) throws IOException, InterruptedException {
        return run(Map.of(), args);
    }

    private RunResult run(final Map<String, String> overrides, final String... args)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("app.jar")));
        command.addAll(List.of(args));
        final Path output = Files.createTempFile(outputDirectory, "replay-", ".log");
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
        // Replay reads no cache: a Redis that cannot be reached must not matter.
        environment.put("REDIS_PORT", "1");
        environment.putAll(overrides);
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
