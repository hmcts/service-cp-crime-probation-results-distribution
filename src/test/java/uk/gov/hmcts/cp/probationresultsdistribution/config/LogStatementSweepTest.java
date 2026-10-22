package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The copied-pattern privacy sweep over the service's own source (FR-002-065, DV-09). It is a guard:
 * it fails the build the moment a log statement reaches for a payload, an outbound body or a userId,
 * or any source writes to the console or prints a stack trace. The forbidden tokens are assembled
 * from fragments so this scanner never flags its own text.
 */
class LogStatementSweepTest {

    private static final Pattern LOG_CALL = Pattern.compile(
            "(?:log|LOG)\\.(?:trace|debug|info|warn|error)\\((.*?)\\);", Pattern.DOTALL);

    private static final String USER_ID = "user" + "Id";
    private static final String BODY_ACCESSOR = ".body" + "(";
    private static final String GET_BODY = "get" + "Body";
    private static final String PAYLOAD_ACCESSOR = ".pay" + "load(";
    private static final String GET_BYTES = "get" + "Bytes";
    private static final String SYS_OUT = "System" + ".out";
    private static final String SYS_ERR = "System" + ".err";
    private static final String PRINT_STACK = "printStack" + "Trace";

    @Test
    @DisplayName("no log statement carries a payload, an outbound body or a userId")
    void no_log_statement_carries_sensitive_data() {
        final List<String> offenders = new ArrayList<>();
        for (final Path file : javaSources("src/main/java", "src/test/java")) {
            final String content = read(file);
            final Matcher matcher = LOG_CALL.matcher(content);
            while (matcher.find()) {
                final String call = matcher.group(1);
                if (call.contains(USER_ID) || call.contains(BODY_ACCESSOR)
                        || call.contains(GET_BODY) || call.contains(PAYLOAD_ACCESSOR)
                        || call.contains(GET_BYTES)) {
                    offenders.add(file + ": " + call.strip());
                }
            }
        }
        assertThat(offenders).as("log statements reaching for sensitive data").isEmpty();
    }

    @Test
    @DisplayName("no source writes to the console or prints a stack trace")
    void no_source_uses_console_or_stack_trace() {
        final List<String> offenders = new ArrayList<>();
        for (final Path file : javaSources("src/main/java", "src/test/java")) {
            final String content = read(file);
            if (content.contains(SYS_OUT) || content.contains(SYS_ERR)
                    || content.contains(PRINT_STACK)) {
                offenders.add(file.toString());
            }
        }
        assertThat(offenders).as("sources using console output or a stack-trace print").isEmpty();
    }

    private static List<Path> javaSources(final String... roots) {
        final List<Path> files = new ArrayList<>();
        for (final String root : roots) {
            final Path base = Path.of(root);
            if (!Files.isDirectory(base)) {
                continue;
            }
            try (Stream<Path> walk = Files.walk(base)) {
                walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
            } catch (final IOException e) {
                throw new UncheckedIOException(e);
            }
        }
        return files;
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
