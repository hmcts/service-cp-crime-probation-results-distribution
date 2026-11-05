package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.EnumerablePropertySource;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.FileSystemResource;

/**
 * Pins SC-005-011 and FR-005-039/FR-005-040: this slice adds zero properties to the closed
 * {@code distribution.*} catalogue, and no source file it adds reads App Configuration or the
 * Results-side cutover switch.
 */
@DisplayName("the operations API adds no new distribution.* property and reads no App Configuration")
class OperationsConfigPropertiesTest {

    /**
     * Every {@code distribution.*} property that existed before this slice's work, read directly
     * from {@code application.yaml} with {@link YamlPropertySourceLoader} rather than retyped by
     * hand, so a transcription slip cannot mask a real addition. A failure here means either a new
     * property was added (closing SC-005-011) or an existing one was renamed/removed — both are
     * reviewable, deliberate changes to this list, never silent.
     */
    private static final Set<String> CLOSED_DISTRIBUTION_PROPERTIES = Set.of(
            "distribution.body-maintenance.cron",
            "distribution.body-retention",
            "distribution.budget.leg",
            "distribution.budget.payload",
            "distribution.budget.youth-switch",
            "distribution.claim.lease",
            "distribution.claim.processing-deadline",
            "distribution.consumer.enabled",
            "distribution.gateway.connect-timeout",
            "distribution.gateway.initial-backoff",
            "distribution.gateway.max-attempts",
            "distribution.gateway.max-backoff",
            "distribution.gateway.read-timeout",
            "distribution.gateway.subscription-key",
            "distribution.hmpps.url",
            "distribution.redis.command-timeout",
            "distribution.redis.connect-timeout",
            "distribution.redis.host",
            "distribution.redis.key",
            "distribution.redis.key-prefix",
            "distribution.redis.port",
            "distribution.redis.ssl",
            "distribution.redis.verify-peer",
            "distribution.resend.max-ids-per-run",
            "distribution.results.base-url",
            "distribution.results.connect-timeout",
            "distribution.results.initial-backoff",
            "distribution.results.max-attempts",
            "distribution.results.max-backoff",
            "distribution.results.read-timeout",
            "distribution.results.system-user-id",
            "distribution.servicebus.connection-string",
            "distribution.servicebus.health-staleness",
            "distribution.servicebus.lock-duration",
            "distribution.servicebus.max-auto-lock-renew-duration",
            "distribution.servicebus.max-concurrent-calls",
            "distribution.servicebus.max-delivery-count",
            "distribution.servicebus.namespace",
            "distribution.servicebus.queue-name",
            "distribution.store.probe-interval",
            "distribution.usersgroups.base-url",
            "distribution.usersgroups.connect-timeout",
            "distribution.usersgroups.max-attempts",
            "distribution.usersgroups.read-timeout",
            "distribution.usersgroups.retry-wait",
            "distribution.usersgroups.system-user-id",
            "distribution.usersgroups.youth-filter-key",
            "distribution.youth-switch.default-when-unreadable");

    private static final List<String> FORBIDDEN_TOKENS = List.of("AppConfiguration", "appconfiguration",
            "FeatureControlGuard", "ProbationResultsDistributionService", "azconfig.io");

    @Test
    @DisplayName("every distribution.* property in application.yaml is already in the closed set")
    void adds_no_new_distribution_property() throws IOException {
        final List<PropertySource<?>> sources = new YamlPropertySourceLoader().load("application",
                new FileSystemResource("src/main/resources/application.yaml"));
        final Set<String> actual = new TreeSet<>();
        for (final PropertySource<?> source : sources) {
            if (source instanceof EnumerablePropertySource<?> enumerable) {
                for (final String name : enumerable.getPropertyNames()) {
                    if (name.startsWith("distribution.")) {
                        actual.add(name);
                    }
                }
            }
        }

        assertThat(actual).containsExactlyInAnyOrderElementsOf(CLOSED_DISTRIBUTION_PROPERTIES);
    }

    @Test
    @DisplayName("no source this slice adds reads App Configuration or the Results-side cutover switch")
    void reads_no_app_configuration_or_results_switch() throws IOException {
        final List<String> offenders = new java.util.ArrayList<>();
        for (final Path file : javaSourcesUnder("src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/api",
                "src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/config/OperationsConfiguration.java",
                "src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/config/OperationsActionResolutionFilter.java")) {
            final String content = Files.readString(file);
            for (final String token : FORBIDDEN_TOKENS) {
                if (content.contains(token)) {
                    offenders.add(file + ": " + token);
                }
            }
        }

        assertThat(offenders).isEmpty();
    }

    private static List<Path> javaSourcesUnder(final String... roots) {
        final List<Path> files = new java.util.ArrayList<>();
        for (final String root : roots) {
            final Path path = Path.of(root);
            if (Files.isDirectory(path)) {
                try (Stream<Path> walk = Files.walk(path)) {
                    walk.filter(candidate -> candidate.toString().endsWith(".java")).forEach(files::add);
                } catch (final IOException e) {
                    throw new UncheckedIOException(e);
                }
            } else if (Files.isRegularFile(path)) {
                files.add(path);
            }
        }
        return files;
    }
}
