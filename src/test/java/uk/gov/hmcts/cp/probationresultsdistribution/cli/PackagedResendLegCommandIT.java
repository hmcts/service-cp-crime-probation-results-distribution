package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendTestSupport;

/**
 * Executes the packaged {@code resend-leg} command (slice 004, US3; {@code contracts/cli-runners.md}
 * §2) against the shared PostgreSQL, Redis and WireMock: the separate CLI composition root builds the
 * resend pipeline, runs the service's startup validation first, and exits with the contract's code.
 */
@DisplayName("the packaged resend-leg command")
class PackagedResendLegCommandIT extends ResendTestSupport {

    @TempDir
    private Path outputDirectory;

    @Test
    @DisplayName("resends a FAILED request's leg, recording attempt 2, pushes its metrics and exits 0")
    void packaged_resend_leg_sends_and_recovers() throws Exception {
        final UUID requestId = seedFailedCachedRequest();
        stubYouthSwitchActive();
        stubGateway(200);
        final String otlpReceiver = stubOtlpMetricsReceiver();

        final RunResult result = run(Map.of("MANAGEMENT_OTLP_METRICS_EXPORT_URL", otlpReceiver),
                "resend-leg", "--leg", "HMPPS", "--request-ids", requestId.toString(),
                "--operator", OPERATOR, "--reason", REASON);

        assertThat(result.exitCode()).withFailMessage("command failed: %s", result.output())
                .isZero();
        assertThat(result.output()).contains("resend-leg report", "SENT")
                .doesNotContain(REASON, "Tomcat started");
        final Map<String, Object> row = latestAttempt(requestId);
        assertThat(row.get("attempt")).isEqualTo(2);
        assertThat(row.get("attempt_origin")).isEqualTo("resend");
        assertThat(row.get("status")).isEqualTo("SENT");
        assertThat(row.get("operator")).isEqualTo(OPERATOR);
        assertThat(requestRow(requestId).get("status")).isEqualTo("COMPLETED");
        assertThat(requestRow(requestId).get("completion_reason")).isEqualTo("recovered");
        assertThat(gatewayPostCount()).isEqualTo(1);
        assertThat(otlpMetricsBodies()).as("the run's counters are pushed before the process exits")
                .anySatisfy(body -> assertThat(body).contains("probation_leg_outcomes"));
    }

    @Test
    @DisplayName("refuses a send stage that does not fit its budget, exiting 2 before any client runs")
    void packaged_resend_leg_runs_the_startup_validation() throws Exception {
        final UUID requestId = seedFailedCachedRequest();
        stubYouthSwitchActive();
        stubGateway(200);

        final RunResult result = run(Map.of("DISTRIBUTION_GATEWAY_READTIMEOUT", "200s"),
                "resend-leg", "--leg", "HMPPS", "--request-ids", requestId.toString(),
                "--operator", OPERATOR, "--reason", REASON);

        assertThat(result.exitCode()).withFailMessage("command output: %s", result.output())
                .isEqualTo(2);
        assertThat(result.output()).contains("invalid-configuration");
        assertThat(attemptCount(requestId)).isEqualTo(1);
        assertThat(gatewayPostCount()).isZero();
    }

    @Test
    @DisplayName("refuses a blank gateway subscription key outside the local profile, exiting 2")
    void packaged_resend_leg_requires_the_subscription_key() throws Exception {
        final UUID requestId = seedFailedCachedRequest();
        stubYouthSwitchActive();

        final RunResult result = run(Map.of("SPRING_PROFILES_ACTIVE", "deployed-check",
                        "HMPPS_PUBLISH_URL", "https://127.0.0.1:1/probation/api/v1/hearing/results",
                        "GATEWAY_SUBSCRIPTION_KEY", ""),
                "resend-leg", "--leg", "HMPPS", "--request-ids", requestId.toString(),
                "--operator", OPERATOR, "--reason", REASON);

        assertThat(result.exitCode()).withFailMessage("command output: %s", result.output())
                .isEqualTo(2);
        assertThat(result.output()).contains("invalid-configuration",
                "distribution.gateway.subscription-key");
        assertThat(attemptCount(requestId)).isEqualTo(1);
    }

    private UUID seedFailedCachedRequest() {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(SHARED_TIME));
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, hearingId, "FAILED", "leg-exhausted");
        seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
        return requestId;
    }

    private RunResult run(final Map<String, String> overrides, final String... args)
            throws IOException, InterruptedException {
        final List<String> command = new ArrayList<>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-jar", System.getProperty("app.jar")));
        command.addAll(List.of(args));
        final Path output = Files.createTempFile(outputDirectory, "resend-leg-", ".log");
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
        environment.putAll(overrides);
        builder.environment().putAll(environment);
        final Process process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("operator process must exit")
                    .isTrue();
            return new RunResult(process.exitValue(), Files.readString(output));
        } finally {
            process.destroyForcibly();
        }
    }

    private record RunResult(int exitCode, String output) {
    }
}
