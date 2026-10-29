package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DlqCompletedListing;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DlqCompletedListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * Pins {@code contracts/cli-runners.md} §8: {@code list-dlq-completed}, no arguments — one line per
 * completed dead-letter entry with its reason, a summary saying whether more exist (FR-004-043),
 * and the bounded exit code (FR-004-045).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the list-dlq-completed runner")
class DlqCompletedListingRunnerTest {

    private static final UUID REQUEST_ID = UUID.fromString("e74ed270-0b62-36d9-bb10-12bd6baa5bea");

    @Mock
    private DlqCompletedListingQuery query;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(DlqCompletedListingRunner.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Test
    @DisplayName("prints one line per completed entry and a summary, and answers done")
    void prints_entries() {
        when(query.list()).thenReturn(new DlqCompletedListing.Listed(List.of(
                new DlqCompletedListing.Entry(42L, "RESULTS:" + REQUEST_ID, REQUEST_ID,
                        "leg-exhausted", Optional.of(Instant.parse("2026-08-19T16:42:07.512Z"))),
                new DlqCompletedListing.Entry(43L, "other", REQUEST_ID, "none",
                        Optional.empty())), false));

        assertThat(new DlqCompletedListingRunner(query).run()).isEqualTo(RunnerOutcome.DONE);
        assertThat(lines()).containsExactly(
                "list-dlq-completed entry sequenceNumber=42 messageId=RESULTS:" + REQUEST_ID
                        + " requestId=" + REQUEST_ID + " deadLetterReason=leg-exhausted"
                        + " enqueuedAt=2026-08-19T16:42:07.512Z",
                "list-dlq-completed entry sequenceNumber=43 messageId=other requestId="
                        + REQUEST_ID + " deadLetterReason=none enqueuedAt=-",
                "list-dlq-completed done entries=2 more=false");
    }

    @Test
    @DisplayName("says when more completed entries exist than the 500 shown")
    void says_when_more_exist() {
        when(query.list()).thenReturn(new DlqCompletedListing.Listed(List.of(), true));

        assertThat(new DlqCompletedListingRunner(query).run()).isEqualTo(RunnerOutcome.DONE);
        assertThat(lines()).contains("list-dlq-completed done entries=0 more=true");
        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .contains("more entries exist than the 500 shown"));
    }

    @Test
    @DisplayName("answers failed when the broker cannot be reached")
    void broker_failure_is_failed() {
        when(query.list()).thenReturn(new DlqCompletedListing.BrokerUnavailable());

        assertThat(new DlqCompletedListingRunner(query).run()).isEqualTo(RunnerOutcome.FAILED);
        assertThat(lines()).contains("list-dlq-completed failed reason=broker-unavailable");
    }

    @Test
    @DisplayName("answers failed when the store cannot be read")
    void store_failure_is_failed() {
        when(query.list()).thenReturn(new DlqCompletedListing.StoreUnavailable());

        assertThat(new DlqCompletedListingRunner(query).run()).isEqualTo(RunnerOutcome.FAILED);
        assertThat(lines()).contains("list-dlq-completed failed reason=store-unavailable");
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }
}
