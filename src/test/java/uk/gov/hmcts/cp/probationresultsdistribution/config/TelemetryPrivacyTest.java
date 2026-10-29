package uk.gov.hmcts.cp.probationresultsdistribution.config;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DlqCompletedListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.DlqCompletedListingRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.cli.RequestListingRunner;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;

@DisplayName("telemetry privacy")
class TelemetryPrivacyTest {

    private static final String CANARY = "CANARY-" + "never-printed";
    private static final Pattern KEY = Pattern.compile("(\\w+)=");
    private static final Set<String> LISTING_KEYS = Set.of("requestId", "hearingId", "hearingDay",
            "requestStatus", "failureReason", "completionReason", "attempt", "attemptOrigin",
            "legStatus", "legReason", "httpStatus", "updatedAt", "sentAt", "rows", "more", "reason",
            "sequenceNumber", "messageId", "deadLetterReason", "enqueuedAt", "entries");

    @Test
    @DisplayName("defines exactly the seven MDC fields and never userId")
    void mdc_fields_exclude_user_id() {
        assertThat(MdcFields.KEYS).containsExactlyInAnyOrder(
                "requestId", "hearingId", "hearingDay", "source", "sequenceNumber", "deliveryCount",
                "leg");
        assertThat(MdcFields.KEYS).doesNotContain("userId");
    }

    @Test
    @DisplayName("the listings print only identifiers, statuses, reasons, attempts, origins, HTTP "
            + "statuses and times, and no text chosen elsewhere (slice 004, FR-004-041, FR-004-043)")
    void listings_print_only_their_bounded_fields() {
        final UUID completed = UUID.randomUUID();
        final DeadLetterReader reader = new CanaryDeadLetters(completed);
        final StoreWithOneRow store = new StoreWithOneRow(completed);
        final List<String> lines = capture(() -> {
            new RequestListingRunner(new RequestListingQuery(store)).run("list-requests",
                    "--status", "FAILED");
            new RequestListingRunner(new RequestListingQuery(store)).run("list-requests",
                    "--status", CANARY);
            new RequestListingRunner(new RequestListingQuery(store)).run("list-requests",
                    "--hearing-id", CANARY, "--hearing-day", CANARY);
            new DlqCompletedListingRunner(new DlqCompletedListingQuery(reader, store)).run();
        });

        assertThat(lines).hasSizeGreaterThanOrEqualTo(5);
        assertThat(lines).noneMatch(line -> line.contains(CANARY));
        for (final String line : lines) {
            final Matcher key = KEY.matcher(line);
            while (key.find()) {
                assertThat(LISTING_KEYS).as("a listing line's key in: %s", line)
                        .contains(key.group(1));
            }
        }
    }

    private static List<String> capture(final Runnable listings) {
        final List<Logger> loggers = List.of(
                (Logger) LoggerFactory.getLogger(RequestListingRunner.class),
                (Logger) LoggerFactory.getLogger(DlqCompletedListingRunner.class),
                (Logger) LoggerFactory.getLogger(RequestListingQuery.class),
                (Logger) LoggerFactory.getLogger(DlqCompletedListingQuery.class));
        final ListAppender<ILoggingEvent> logs = new ListAppender<>();
        logs.start();
        loggers.forEach(logger -> logger.addAppender(logs));
        try {
            listings.run();
        } finally {
            loggers.forEach(logger -> logger.detachAppender(logs));
            logs.stop();
        }
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** A dead-letter queue whose entries carry text chosen elsewhere in every free field. */
    private record CanaryDeadLetters(UUID completed) implements DeadLetterReader {

        @Override
        public List<DeadLetterEntry> peek(final long fromSequenceNumber, final int maxEntries) {
            return fromSequenceNumber > 0 ? List.of() : List.of(
                    new DeadLetterEntry(0L, Optional.of(CANARY), Optional.of(completed),
                            Optional.of(CANARY), Optional.empty()),
                    new DeadLetterEntry(1L, Optional.of("RESULTS:" + completed),
                            Optional.of(completed), Optional.of("leg-exhausted"),
                            Optional.of(Instant.parse("2026-08-19T16:42:07.512Z"))));
        }
    }

    /** A store answering one listed row, and every request as {@code COMPLETED}. */
    private record StoreWithOneRow(UUID requestId) implements RequestListingRepository {

        @Override
        public List<ListedAttempt> byHearing(final UUID hearingId, final LocalDate hearingDay,
                                             final int limit) {
            return List.of(row());
        }

        @Override
        public List<ListedAttempt> byLatestAttempt(final String status,
                                                   final Optional<String> reason,
                                                   final Optional<Instant> updatedAfter,
                                                   final Optional<Instant> updatedBefore,
                                                   final int limit) {
            return List.of(row());
        }

        @Override
        public Map<UUID, String> requestStatuses(final String source,
                                                 final Collection<UUID> requestIds) {
            final Map<UUID, String> statuses = new HashMap<>();
            requestIds.forEach(id -> statuses.put(id, "COMPLETED"));
            return statuses;
        }

        private ListedAttempt row() {
            return new ListedAttempt(requestId, UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                    "FAILED", Optional.of("leg-exhausted"), Optional.empty(), 1, "queue", "FAILED",
                    Optional.of("gateway-transient"), OptionalInt.of(503),
                    Instant.parse("2026-08-19T16:42:07.512Z"), Optional.empty());
        }
    }
}
