package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusMessage;
import com.azure.messaging.servicebus.ServiceBusProcessorClient;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
import com.azure.messaging.servicebus.ServiceBusSenderClient;
import com.azure.messaging.servicebus.models.SubQueue;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.inbound.ConsumerLifecycleController;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcIntakeControlRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedLogProbe;

/** T037: executes the packaged main entry point against the real broker and store. */
class PackagedIntakeCommandsIT extends EndToEndTestSupport {

    @TempDir
    private Path outputDirectory;

    @BeforeEach
    void clearActiveQueue() {
        resumeIntake();
        try (ServiceBusReceiverClient receiver = receiver()) {
            List<ServiceBusReceivedMessage> batch;
            do {
                batch = receiver.receiveMessages(100, Duration.ofSeconds(1)).stream().toList();
                batch.forEach(receiver::complete);
            } while (!batch.isEmpty());
        }
    }

    @AfterEach
    void resumeIntake() {
        new JdbcIntakeControlRepository(jdbc()).resume();
    }

    @Test
    void packaged_pause_resume_controls_live_intake_and_unavailable_purge_preserves_queues() throws Exception {
        assertRun(0, "pause-intake");
        final String deadLetterId = UUID.randomUUID().toString();
        try (ServiceBusSenderClient sender = sender(); ServiceBusReceiverClient receiver = receiver()) {
            sender.sendMessage(new ServiceBusMessage("synthetic").setMessageId(deadLetterId));
            final List<ServiceBusReceivedMessage> messages = receiver.receiveMessages(1, Duration.ofSeconds(10))
                    .stream().toList();
            assertThat(messages).hasSize(1);
            receiver.deadLetter(messages.getFirst());
        }
        final CountDownLatch consumed = new CountDownLatch(1);
        try (ServiceBusProcessorClient processor = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).processor().queueName(QUEUE)
                .disableAutoComplete().processMessage(context -> {
                    context.complete();
                    consumed.countDown();
                }).processError(context -> {
                    throw new IllegalStateException("smoke processor failed", context.getException());
                }).buildProcessorClient()) {
            final ConsumerLifecycleController controller = new ConsumerLifecycleController(processor,
                    new ProcessedLogProbe(jdbc()), new JdbcIntakeControlRepository(jdbc()), () -> null,
                    new MetricRegistry(new SimpleMeterRegistry()), Duration.ofMillis(50));
            try {
                controller.start();
                await(controller::pausedByOperator);
                try (ServiceBusSenderClient sender = sender()) {
                    sender.sendMessage(new ServiceBusMessage("synthetic paused message"));
                }
                assertThat(consumed.await(1, TimeUnit.SECONDS)).isFalse();
                assertRun(2, "purge-queue");
                assertThat(deadLetterExists(deadLetterId)).isTrue();
                try (ServiceBusReceiverClient receiver = receiver()) {
                    assertThat(receiver.peekMessage()).isNotNull();
                }
                assertRun(0, "resume-intake");
                await(controller::intakeStarted);
                assertThat(consumed.await(10, TimeUnit.SECONDS)).isTrue();
            } finally {
                controller.stop();
            }
        }
    }

    @Test
    void packaged_rollback_refuses_while_running_then_closes_pending_and_parked_rows() throws Exception {
        assertRun(0, "resume-intake");
        final DistributionCommand pending = command();
        final DistributionCommand parked = command();
        seedPendingAttemptOne(pending);
        seedParkedGatewayTransientLeg(parked, "synthetic-body");

        assertRun(1, "purge-queue");
        assertRun(1, "rollback-close-in-flight");
        assertThat(status(pending)).isEqualTo("RETRYING");
        assertThat(status(parked)).isEqualTo("RETRYING");
        assertRun(0, "pause-intake");
        assertRun(0, "pause-intake");
        assertRun(0, "rollback-close-in-flight");

        for (final DistributionCommand command : List.of(pending, parked)) {
            assertThat(status(command)).isEqualTo("FAILED");
            assertThat(jdbc().sql("SELECT failure_reason, exhausted_message_id, claim_token "
                            + "FROM processed_request WHERE request_id = :id")
                    .param("id", command.requestId()).query().singleRow())
                    .containsEntry("failure_reason", "rolled-back")
                    .containsEntry("exhausted_message_id", null).containsEntry("claim_token", null);
            assertThat(jdbc().sql("SELECT status, reason FROM outbound_message_latest WHERE request_id = :id")
                    .param("id", command.requestId()).query().singleRow())
                    .containsEntry("status", "FAILED").containsEntry("reason", "rolled-back");
        }
    }

    @Test
    void rejects_unexpected_arguments_before_startup_without_echoing_them() throws Exception {
        final RunResult result = run("pause-intake", "private-operator-text");
        assertThat(result.exitCode()).isEqualTo(1);
        assertThat(result.output()).contains("unreadable-argument").doesNotContain("private-operator-text");
    }

    @Test
    void store_startup_failure_exits_failed() throws Exception {
        final RunResult result = run(Map.of("SPRING_DATASOURCE_URL",
                "jdbc:postgresql://127.0.0.1:1/unavailable?connectTimeout=1",
                "SPRING_DATASOURCE_HIKARI_CONNECTION_TIMEOUT", "500"), "pause-intake");
        assertThat(result.exitCode()).isEqualTo(2);
    }

    private void assertRun(final int expected, final String... args) throws Exception {
        final RunResult result = run(args);
        assertThat(result.exitCode()).withFailMessage("command failed: %s", result.output()).isEqualTo(expected);
        assertThat(result.output()).doesNotContain("Tomcat started", "intake is gated");
    }

    private RunResult run(final String... args) throws IOException, InterruptedException {
        return run(Map.of(), args);
    }

    private RunResult run(final Map<String, String> environment, final String... args)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("app.jar")));
        command.addAll(List.of(args));
        final Path output = Files.createTempFile(outputDirectory, "command-", ".log");
        final ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true)
                .redirectOutput(output.toFile());
        final DriverManagerDataSource source = (DriverManagerDataSource) sharedDataSource();
        builder.environment().putAll(Map.of(
                "SPRING_PROFILES_ACTIVE", "local", "SPRING_DATASOURCE_URL", source.getUrl(),
                "SPRING_DATASOURCE_USERNAME", source.getUsername(), "SPRING_DATASOURCE_PASSWORD", source.getPassword(),
                "SERVICEBUS_CONNECTION_STRING", serviceBusConnectionString(),
                "AUTHZ_HTTP_ENABLED", "false", "HTTP_AUDIT_ENABLED", "false",
                "REDIS_PORT", "1"));
        builder.environment().putAll(environment);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(40, TimeUnit.SECONDS)).as("operator process must exit").isTrue();
            return new RunResult(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private String status(final DistributionCommand command) {
        return jdbc().sql("SELECT status FROM processed_request WHERE request_id = :id")
                .param("id", command.requestId()).query(String.class).single();
    }

    private static DistributionCommand command() {
        return new DistributionCommand("RESULTS", UUID.randomUUID(), UUID.randomUUID(),
                LocalDate.parse("2026-10-26"), Instant.parse("2026-10-26T12:00:00Z"),
                "Hearing_Resulted", Optional.empty());
    }

    private static ServiceBusSenderClient sender() {
        return new ServiceBusClientBuilder().connectionString(serviceBusConnectionString())
                .sender().queueName(QUEUE).buildClient();
    }

    private static ServiceBusReceiverClient receiver() {
        return new ServiceBusClientBuilder().connectionString(serviceBusConnectionString())
                .receiver().queueName(QUEUE).prefetchCount(0).buildClient();
    }

    private static boolean deadLetterExists(final String id) {
        boolean found = false;
        boolean more = true;
        long sequence = 0;
        try (ServiceBusReceiverClient dlq = new ServiceBusClientBuilder()
                .connectionString(serviceBusConnectionString()).receiver().queueName(QUEUE)
                .subQueue(SubQueue.DEAD_LETTER_QUEUE).buildClient()) {
            while (!found && more) {
                final List<ServiceBusReceivedMessage> batch = dlq.peekMessages(100, sequence).stream().toList();
                more = !batch.isEmpty();
                for (final ServiceBusReceivedMessage message : batch) {
                    found |= id.equals(message.getMessageId());
                    sequence = message.getSequenceNumber() + 1;
                }
            }
        }
        return found;
    }

    private static void await(final BooleanSupplier condition) throws InterruptedException {
        final long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private record RunResult(int exitCode, String output) {
    }
}
