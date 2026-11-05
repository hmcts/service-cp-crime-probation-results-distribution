package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListing;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListingRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository.ListedAttempt;

/**
 * Pins {@code contracts/cli-runners.md} §7: {@code list-requests --hearing-id <uuid> --hearing-day
 * <YYYY-MM-DD>} or {@code list-requests --status <status> [--reason <reason>] [--updated-after
 * <instant>] [--updated-before <instant>]}. Arguments are checked whole before anything is opened
 * (FR-004-020); one line per row and a summary saying whether more rows exist (FR-004-043); the
 * bounded exit code (FR-004-045).
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the list-requests runner")
class RequestListingRunnerTest {

    private static final UUID HEARING_ID = UUID.fromString("0aa5bf35-1e51-45e5-9e42-64bd57e15c11");
    private static final UUID REQUEST_ID = UUID.fromString("e74ed270-0b62-36d9-bb10-12bd6baa5bea");
    private static final String DAY = "2026-08-19";

    @Mock
    private RequestListingQuery query;

    private ListAppender<ILoggingEvent> logs;
    private Logger logger;

    @BeforeEach
    void captureLogs() {
        logger = (Logger) LoggerFactory.getLogger(RequestListingRunner.class);
        logs = new ListAppender<>();
        logs.start();
        logger.addAppender(logs);
    }

    @AfterEach
    void releaseLogs() {
        logger.detachAppender(logs);
        logs.stop();
    }

    @Nested
    @DisplayName("the arguments are checked whole before anything is opened (FR-004-020)")
    class Arguments {

        @Test
        @DisplayName("either readable form passes")
        void readable_forms_pass() {
            assertThat(RequestListingRunner.checkArguments("list-requests", "--hearing-id",
                    HEARING_ID.toString(), "--hearing-day", DAY)).isEmpty();
            assertThat(RequestListingRunner.checkArguments("list-requests", "--status", "FAILED",
                    "--reason", "gateway-transient", "--updated-after", "2026-08-19T00:00:00Z",
                    "--updated-before", "2026-08-20T00:00:00Z")).isEmpty();
        }

        @Test
        @DisplayName("no form, or half a form, is missing-argument")
        void missing_arguments() {
            assertThat(RequestListingRunner.checkArguments("list-requests"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(RequestListingRunner.checkArguments("list-requests", "--hearing-id",
                    HEARING_ID.toString())).contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(RequestListingRunner.checkArguments("list-requests", "--reason",
                    "gateway-transient")).contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(RequestListingRunner.checkArguments("list-requests", "--status"))
                    .contains(WholeRunRefusal.MISSING_ARGUMENT);
        }

        @Test
        @DisplayName("an unreadable value, or both forms at once, is unreadable-argument")
        void unreadable_arguments() {
            assertThat(RequestListingRunner.checkArguments("list-requests", "--hearing-id",
                    "not-a-uuid", "--hearing-day", DAY))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
            assertThat(RequestListingRunner.checkArguments("list-requests", "--status", "LOST"))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
            assertThat(RequestListingRunner.checkArguments("list-requests", "--hearing-id",
                    HEARING_ID.toString(), "--hearing-day", DAY, "--status", "FAILED"))
                    .contains(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }

        @Test
        @DisplayName("a refused run calls nothing, answers refused and never echoes the value")
        void refused_run_echoes_nothing() {
            final RunnerOutcome outcome = new RequestListingRunner(query).run("list-requests",
                    "--status", "SECRET-status");

            assertThat(outcome).isEqualTo(RunnerOutcome.REFUSED);
            verifyNoInteractions(query);
            assertThat(lines()).anySatisfy(line -> assertThat(line)
                    .contains("list-requests refused reason=unreadable-argument"));
            assertThat(lines()).noneMatch(line -> line.contains("SECRET"));
        }
    }

    @Test
    @DisplayName("hands the hearing form to the listing and prints one line per row and a summary")
    void hearing_form_prints_rows() {
        when(query.list(any())).thenReturn(new RequestListing.Listed(List.of(row()), false));

        final RunnerOutcome outcome = new RequestListingRunner(query).run("list-requests",
                "--hearing-id", HEARING_ID.toString(), "--hearing-day", DAY);

        assertThat(outcome).isEqualTo(RunnerOutcome.DONE);
        final ArgumentCaptor<RequestListingRequest> request =
                ArgumentCaptor.forClass(RequestListingRequest.class);
        verify(query).list(request.capture());
        assertThat(request.getValue()).isEqualTo(new RequestListingRequest(
                Optional.of(HEARING_ID.toString()), Optional.of(DAY), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty()));
        assertThat(lines()).containsExactly(
                "list-requests row requestId=" + REQUEST_ID + " hearingId=" + HEARING_ID
                        + " hearingDay=2026-08-19 requestStatus=FAILED failureReason=leg-exhausted"
                        + " completionReason=- attempt=2 attemptOrigin=resend legStatus=FAILED"
                        + " legReason=gateway-transient httpStatus=503"
                        + " updatedAt=2026-08-19T16:42:07.512Z sentAt=-",
                "list-requests done rows=1 more=false");
    }

    @Test
    @DisplayName("hands the latest-attempt form over as supplied")
    void latest_form_is_handed_over() {
        when(query.list(any())).thenReturn(new RequestListing.Listed(List.of(), false));

        new RequestListingRunner(query).run("list-requests", "--status", "FAILED", "--reason",
                "gateway-transient", "--updated-after", "2026-08-19T00:00:00Z", "--updated-before",
                "2026-08-20T00:00:00Z");

        verify(query).list(new RequestListingRequest(Optional.empty(), Optional.empty(),
                Optional.of("FAILED"), Optional.of("gateway-transient"),
                Optional.of("2026-08-19T00:00:00Z"), Optional.of("2026-08-20T00:00:00Z")));
    }

    @Test
    @DisplayName("says when more rows exist than the 500 shown")
    void says_when_more_exist() {
        final List<ListedAttempt> page = IntStream.range(0, RequestListingQuery.PAGE_SIZE)
                .mapToObj(index -> row()).toList();
        when(query.list(any())).thenReturn(new RequestListing.Listed(page, true));

        final RunnerOutcome outcome = new RequestListingRunner(query).run("list-requests",
                "--status", "FAILED");

        assertThat(outcome).isEqualTo(RunnerOutcome.DONE);
        assertThat(lines()).contains("list-requests done rows=500 more=true");
        assertThat(lines()).anySatisfy(line -> assertThat(line)
                .contains("more rows exist than the 500 shown"));
    }

    @Test
    @DisplayName("answers failed when the store cannot be read")
    void store_failure_is_failed() {
        when(query.list(any())).thenReturn(new RequestListing.StoreUnavailable());

        assertThat(new RequestListingRunner(query).run("list-requests", "--status", "FAILED"))
                .isEqualTo(RunnerOutcome.FAILED);
        assertThat(lines()).contains("list-requests failed reason=store-unavailable");
    }

    @Test
    @DisplayName("answers refused when the listing refuses its input")
    void listing_refusal_is_refused() {
        when(query.list(any()))
                .thenReturn(new RequestListing.Refused(WholeRunRefusal.MISSING_ARGUMENT));

        assertThat(new RequestListingRunner(query).run("list-requests", "--status", "FAILED"))
                .isEqualTo(RunnerOutcome.REFUSED);
    }

    private List<String> lines() {
        return logs.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    private static ListedAttempt row() {
        return new ListedAttempt(REQUEST_ID, HEARING_ID, LocalDate.parse(DAY), "FAILED",
                Optional.of("leg-exhausted"), Optional.empty(), 2, "resend", "FAILED",
                Optional.of("gateway-transient"), OptionalInt.of(503),
                Instant.parse("2026-08-19T16:42:07.512Z"), Optional.empty());
    }
}
