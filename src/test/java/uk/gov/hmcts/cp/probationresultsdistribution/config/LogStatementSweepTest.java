package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
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

    private static final String MAIN = "src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/";
    private static final List<String> LISTING_SOURCES = List.of(
            MAIN + "application/RequestListingQuery.java",
            MAIN + "application/RequestListing.java",
            MAIN + "application/RequestListingRequest.java",
            MAIN + "application/DlqCompletedListingQuery.java",
            MAIN + "application/DlqCompletedListing.java",
            MAIN + "application/DeadLetterReader.java",
            MAIN + "cli/RequestListingRunner.java",
            MAIN + "cli/DlqCompletedListingRunner.java",
            MAIN + "persistence/RequestListingRepository.java",
            MAIN + "persistence/JdbcRequestListingRepository.java",
            MAIN + "inbound/DeadLetterQueuePeeker.java");
    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:[^\"\\\\]|\\\\.)*\"");
    private static final List<String> FORBIDDEN_IN_LISTINGS = List.of("oper" + "ator",
            "audit", "comm" + "and", "user" + "Id", "user_" + "id", "bo" + "dy", "pay" + "load",
            "CJSCPP" + "UID");

    private static final String OPERATOR_TOKEN = "oper" + "ator";
    private static final String REASON_TOKEN = "rea" + "son";
    private static final String CJSCPPUID_TOKEN = "CJSCPP" + "UID";

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

    @Test
    @DisplayName("no operations-API log line carries the operator, the reason, or CJSCPPUID (FR-005-023, FR-005-031)")
    void no_operations_log_line_carries_operator_reason_or_identity() {
        final List<String> offenders = new ArrayList<>();
        for (final Path file : javaSources("src/main/java/uk/gov/hmcts/cp/probationresultsdistribution/api")) {
            final String content = read(file);
            final Matcher matcher = LOG_CALL.matcher(content);
            while (matcher.find()) {
                final String call = matcher.group(1);
                if (call.contains(OPERATOR_TOKEN) || call.contains(REASON_TOKEN)
                        || call.contains(CJSCPPUID_TOKEN)) {
                    offenders.add(file + ": " + call.strip());
                }
            }
        }
        assertThat(offenders).as("operations-API log statements reaching for operator, reason or CJSCPPUID")
                .isEmpty();
    }

    @Test
    @DisplayName("no listing log line or store read reaches for a command, body, payload, audit "
            + "note, operator or userId (slice 004, FR-004-041, FR-004-043)")
    void no_listing_reaches_for_forbidden_content() {
        final List<String> offenders = new ArrayList<>();
        for (final String source : LISTING_SOURCES) {
            final Path file = Path.of(source);
            final String content = read(file);
            final Matcher logCall = LOG_CALL.matcher(content);
            while (logCall.find()) {
                forbiddenIn(logCall.group(1)).ifPresent(token ->
                        offenders.add(file + " log line: " + token));
            }
            // Every SQL fragment and every printed text is a string literal: none of them may name
            // the stored command, audit_note, an operator, a body or a user id.
            final Matcher literal = STRING_LITERAL.matcher(content);
            while (literal.find()) {
                forbiddenIn(literal.group()).ifPresent(token ->
                        offenders.add(file + " literal " + literal.group() + ": " + token));
            }
        }
        final String peeker = read(Path.of(MAIN + "inbound/DeadLetterQueuePeeker.java"));
        for (final String accessor : List.of("getApplication" + "Properties(",
                "getDeadLetterError" + "Description(", "getRaw" + "AmqpMessage(")) {
            if (peeker.contains(accessor)) {
                offenders.add("DeadLetterQueuePeeker reads " + accessor);
            }
        }
        assertThat(offenders).as("listing sources reaching for content they must never print")
                .isEmpty();
    }

    private static Optional<String> forbiddenIn(final String text) {
        final String lower = text.toLowerCase(Locale.ROOT);
        return FORBIDDEN_IN_LISTINGS.stream()
                .filter(token -> lower.contains(token.toLowerCase(Locale.ROOT)))
                .findFirst();
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
