package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import com.azure.messaging.servicebus.ServiceBusClientBuilder;
import com.azure.messaging.servicebus.ServiceBusReceivedMessage;
import com.azure.messaging.servicebus.ServiceBusReceiverClient;
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
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestIdRecipe;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcIntakeControlRepository;

/**
 * Executes the packaged {@code submit-hearing} command (slice 004, US8; {@code
 * contracts/cli-runners.md} §5) against the shared Redis, PostgreSQL, WireMock and the Service Bus
 * emulator: it reads the {@code EXT_} cache only, enqueues one support command whatever the intake
 * state, writes nothing to the store, prints no operator or reason, and exits with the contract's
 * code.
 */
@DisplayName("the packaged submit-hearing command")
class PackagedSubmitHearingCommandIT extends EndToEndTestSupport {

    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final String SHARED_TIME = "2026-08-19T16:42:07.512Z";
    private static final String OPERATOR = "OPERATOR-" + "never-printed";
    private static final String REASON = "REASON " + "never printed";

    @TempDir
    private Path outputDirectory;

    @AfterEach
    void resumeIntake() {
        new JdbcIntakeControlRepository(jdbc()).resume();
    }

    @Test
    @DisplayName("enqueues one support command from the cache while intake is paused, and exits 0")
    void submit_hearing_enqueues_while_paused() throws Exception {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, DAY, "{\"sharedTime\":\"" + SHARED_TIME + "\"}");
        new JdbcIntakeControlRepository(jdbc()).pause();
        final UUID requestId = RequestIdRecipe.requestId(hearingId.toString(), DAY.toString(),
                SHARED_TIME);

        final RunResult result = run("submit-hearing", "--hearing-id", hearingId.toString(),
                "--hearing-day", DAY.toString(), "--operator", OPERATOR, "--reason", REASON);

        assertThat(result.exitCode()).withFailMessage("command failed: %s", result.output())
                .isZero();
        assertThat(result.output()).contains("submit-hearing done outcome=enqueued requestId="
                + requestId).doesNotContain(OPERATOR, REASON, "Tomcat started");
        final ServiceBusReceivedMessage message = receive("RESULTS:" + requestId).orElseThrow(
                () -> new AssertionError("the support command was not on the queue"));
        assertThat(message.getCorrelationId()).isEqualTo(hearingId.toString());
        assertThat(message.getContentType()).isEqualTo("application/json");
        assertThat(message.getApplicationProperties()).containsEntry("origin", "SUPPORT")
                .containsEntry("operator", OPERATOR).containsEntry("reason", REASON)
                .containsKey("runId");
        assertThat(message.getBody().toString()).contains("\"sharedTime\":\"" + SHARED_TIME + "\"")
                .doesNotContain("userId", OPERATOR);
        assertThat(processedRequestCount(requestId)).isZero();
    }

    @Test
    @DisplayName("refuses payload-unavailable with exit 1 when neither key holds the payload, "
            + "never asking the results query API")
    void submit_hearing_never_reads_the_query_api() throws Exception {
        final UUID hearingId = UUID.randomUUID();
        stubResultsQuery(hearingId, 200, "{\"sharedTime\":\"" + SHARED_TIME + "\"}");

        final RunResult result = run("submit-hearing", "--hearing-id", hearingId.toString(),
                "--hearing-day", DAY.toString(), "--operator", OPERATOR, "--reason", REASON);

        assertThat(result.exitCode()).withFailMessage("command output: %s", result.output())
                .isEqualTo(1);
        assertThat(result.output()).contains("submit-hearing refused reason=payload-unavailable")
                .doesNotContain(OPERATOR, REASON);
        assertThat(resultsQueryUserHeaders(hearingId)).isEmpty();
    }

    @Test
    @DisplayName("refuses an unreadable hearing day with exit 1 before anything is opened, never "
            + "echoing it")
    void submit_hearing_refuses_unreadable_input() throws Exception {
        final RunResult result = run("submit-hearing", "--hearing-id", UUID.randomUUID().toString(),
                "--hearing-day", "SECRET-day", "--operator", OPERATOR, "--reason", REASON);

        assertThat(result.exitCode()).withFailMessage("command output: %s", result.output())
                .isEqualTo(1);
        assertThat(result.output()).contains("unreadable-argument")
                .doesNotContain("SECRET", OPERATOR, REASON);
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
        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("app.jar")));
        command.addAll(List.of(args));
        final Path output = Files.createTempFile(outputDirectory, "submit-hearing-", ".log");
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
