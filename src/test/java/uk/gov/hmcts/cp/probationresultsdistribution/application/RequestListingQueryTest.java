package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository.ListedAttempt;

/**
 * Pins the request listing (slice 004, US6; FR-004-043, FR-004-020, FR-004-041; research.md
 * Decision 5): two forms, its input checked whole before any store read, one page of 500 rows and
 * a flag saying when more exist, and a row shape that cannot carry a stored command, a body, a
 * payload, {@code audit_note} text, an operator or a {@code userId}.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the request listing")
class RequestListingQueryTest {

    private static final UUID HEARING_ID = UUID.fromString("0aa5bf35-1e51-45e5-9e42-64bd57e15c11");
    private static final String DAY = "2026-08-19";
    private static final int ONE_MORE_THAN_A_PAGE = 501;

    @Mock
    private RequestListingRepository repository;

    private RequestListingQuery query() {
        return new RequestListingQuery(repository);
    }

    @Nested
    @DisplayName("by hearing and day")
    class ByHearing {

        @Test
        @DisplayName("shows every attempt row of every request for that hearing and day")
        void list_byHearing_should_return_the_rows() {
            final List<ListedAttempt> rows = rows(3);
            when(repository.byHearing(HEARING_ID, LocalDate.parse(DAY), ONE_MORE_THAN_A_PAGE))
                    .thenReturn(rows);

            final RequestListing listing = query().list(byHearing(HEARING_ID.toString(), DAY));

            assertThat(listing).isEqualTo(new RequestListing.Listed(rows, false));
        }

        @Test
        @DisplayName("pages at 500 and says when more exist")
        void list_overAPage_should_show_500_and_say_more() {
            final List<ListedAttempt> rows = rows(ONE_MORE_THAN_A_PAGE);
            when(repository.byHearing(any(), any(), anyInt())).thenReturn(rows);

            final RequestListing listing = query().list(byHearing(HEARING_ID.toString(), DAY));

            assertThat(listing).isInstanceOf(RequestListing.Listed.class);
            final RequestListing.Listed listed = (RequestListing.Listed) listing;
            assertThat(listed.rows()).hasSize(RequestListingQuery.PAGE_SIZE)
                    .isEqualTo(rows.subList(0, RequestListingQuery.PAGE_SIZE));
            assertThat(listed.more()).isTrue();
        }

        @Test
        @DisplayName("says nothing more exists for exactly one page")
        void list_exactlyAPage_should_not_say_more() {
            when(repository.byHearing(any(), any(), anyInt()))
                    .thenReturn(rows(RequestListingQuery.PAGE_SIZE));

            final RequestListing listing = query().list(byHearing(HEARING_ID.toString(), DAY));

            assertThat(((RequestListing.Listed) listing).more()).isFalse();
            assertThat(((RequestListing.Listed) listing).rows())
                    .hasSize(RequestListingQuery.PAGE_SIZE);
        }
    }

    @Nested
    @DisplayName("by the latest attempt")
    class ByLatestAttempt {

        @Test
        @DisplayName("reads the latest attempts by status, reason and updated_at range")
        void list_byLatestAttempt_should_pass_every_filter() {
            final List<ListedAttempt> rows = rows(2);
            final Instant after = Instant.parse("2026-08-19T00:00:00Z");
            final Instant before = Instant.parse("2026-08-20T00:00:00Z");
            when(repository.byLatestAttempt("FAILED", Optional.of("gateway-transient"),
                    Optional.of(after), Optional.of(before), ONE_MORE_THAN_A_PAGE)).thenReturn(rows);

            final RequestListing listing = query().list(byLatest("FAILED", "gateway-transient",
                    "2026-08-19T00:00:00Z", "2026-08-20T01:00:00+01:00"));

            assertThat(listing).isEqualTo(new RequestListing.Listed(rows, false));
        }

        @Test
        @DisplayName("reads an updated_at bound with a non-Z offset as its instant")
        void list_offsetBound_should_read_the_instant() {
            when(repository.byLatestAttempt("FAILED", Optional.empty(),
                    Optional.of(Instant.parse("2026-08-19T00:00:00Z")), Optional.empty(),
                    ONE_MORE_THAN_A_PAGE)).thenReturn(rows(1));

            final RequestListing listing = query().list(byLatest("FAILED", null,
                    "2026-08-19T01:00:00+01:00", null));

            assertThat(((RequestListing.Listed) listing).rows()).hasSize(1);
        }

        @Test
        @DisplayName("takes the status alone")
        void list_statusOnly_should_pass_no_other_filter() {
            when(repository.byLatestAttempt("PARKED", Optional.empty(), Optional.empty(),
                    Optional.empty(), ONE_MORE_THAN_A_PAGE)).thenReturn(rows(1));

            final RequestListing listing = query().list(byLatest("PARKED", null, null, null));

            assertThat(((RequestListing.Listed) listing).rows()).hasSize(1);
        }

        @Test
        @DisplayName("pages at 500 and says when more exist")
        void list_overAPage_should_say_more() {
            when(repository.byLatestAttempt(any(), any(), any(), any(), anyInt()))
                    .thenReturn(rows(ONE_MORE_THAN_A_PAGE));

            final RequestListing listing = query().list(byLatest("FAILED", null, null, null));

            assertThat(((RequestListing.Listed) listing).rows())
                    .hasSize(RequestListingQuery.PAGE_SIZE);
            assertThat(((RequestListing.Listed) listing).more()).isTrue();
        }
    }

    @Nested
    @DisplayName("checks its input whole, before any store read, never echoing it")
    class Input {

        @ParameterizedTest(name = "{0}")
        @CsvSource(nullValues = "null", value = {
            "no form at all,null,null,null,null,null,null,missing-argument",
            "hearing id without a day,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,null,null,null,null,null,"
                    + "missing-argument",
            "day without a hearing id,null,2026-08-19,null,null,null,null,missing-argument",
            "blank day,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,' ',null,null,null,null,missing-argument",
            "hearing id not a UUID,not-a-uuid-SECRET,2026-08-19,null,null,null,null,"
                    + "unreadable-argument",
            "day not YYYY-MM-DD,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,19/08/2026,null,null,null,null,"
                    + "unreadable-argument",
            "day short form,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,2026-8-19,null,null,null,null,"
                    + "unreadable-argument",
            "day not a date,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,2026-02-30,null,null,null,null,"
                    + "unreadable-argument",
            "both forms,0aa5bf35-1e51-45e5-9e42-64bd57e15c11,2026-08-19,FAILED,null,null,null,"
                    + "unreadable-argument",
            "reason without a status,null,null,null,gateway-transient,null,null,missing-argument",
            "range without a status,null,null,null,null,2026-08-19T00:00:00Z,null,missing-argument",
            "blank status,null,null,' ',null,null,null,missing-argument",
            "status outside the closed set,null,null,failed-SECRET,null,null,null,"
                    + "unreadable-argument",
            "reason outside the closed set,null,null,FAILED,SECRET-reason,null,null,"
                    + "unreadable-argument",
            "after not an instant,null,null,FAILED,null,yesterday-SECRET,null,unreadable-argument",
            "before not an instant,null,null,FAILED,null,null,2026-08-19,unreadable-argument",
            "after with no offset,null,null,FAILED,null,2026-08-19T00:00:00,null,unreadable-argument",
            "before with no offset,null,null,FAILED,null,null,2026-08-19T00:00:00,"
                    + "unreadable-argument"
        })
        void list_badInput_should_refuse_whole(final String name, final String hearingId,
                                               final String hearingDay, final String status,
                                               final String reason, final String after,
                                               final String before, final String refusal) {
            final RequestListing listing = query().list(new RequestListingRequest(
                    Optional.ofNullable(hearingId), Optional.ofNullable(hearingDay),
                    Optional.ofNullable(status), Optional.ofNullable(reason),
                    Optional.ofNullable(after), Optional.ofNullable(before)));

            assertThat(listing).isEqualTo(
                    new RequestListing.Refused(WholeRunRefusal.fromValue(refusal)));
            assertThat(listing.toString()).doesNotContain("SECRET");
            verifyNoInteractions(repository);
        }
    }

    @Test
    @DisplayName("answers store-unavailable when the store cannot be read")
    void list_storeDown_should_answer_store_unavailable() {
        when(repository.byLatestAttempt(any(), any(), any(), any(), anyInt()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(query().list(byLatest("FAILED", null, null, null)))
                .isEqualTo(new RequestListing.StoreUnavailable());
    }

    @Test
    @DisplayName("asks the store for one row more than a page, to know whether more exist")
    void list_should_ask_for_one_more_than_a_page() {
        when(repository.byHearing(any(), any(), anyInt())).thenReturn(List.of());

        query().list(byHearing(HEARING_ID.toString(), DAY));

        verify(repository).byHearing(HEARING_ID, LocalDate.parse(DAY), ONE_MORE_THAN_A_PAGE);
    }

    @Test
    @DisplayName("a listed row has no component for a command, body, payload, audit note, operator "
            + "or userId")
    void listedRow_should_carry_no_forbidden_component() {
        final List<String> components = Arrays.stream(ListedAttempt.class.getRecordComponents())
                .map(component -> component.getName().toLowerCase(Locale.ROOT)).toList();

        assertThat(components).noneMatch(name -> name.contains("command") || name.contains("body")
                || name.contains("payload") || name.contains("audit") || name.contains("operator")
                || name.contains("user"));
    }

    private static RequestListingRequest byHearing(final String hearingId, final String day) {
        return new RequestListingRequest(Optional.of(hearingId), Optional.of(day),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static RequestListingRequest byLatest(final String status, final String reason,
                                                  final String after, final String before) {
        return new RequestListingRequest(Optional.empty(), Optional.empty(),
                Optional.ofNullable(status), Optional.ofNullable(reason),
                Optional.ofNullable(after), Optional.ofNullable(before));
    }

    private static List<ListedAttempt> rows(final int count) {
        return IntStream.range(0, count).mapToObj(index -> new ListedAttempt(UUID.randomUUID(),
                HEARING_ID, LocalDate.parse(DAY), "FAILED", Optional.of("leg-exhausted"),
                Optional.empty(), 1, "queue", "FAILED", Optional.of("gateway-transient"),
                OptionalInt.of(503), Instant.parse("2026-08-19T16:42:07.512Z"), Optional.empty()))
                .toList();
    }
}
