package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.LongStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader.DeadLetterEntry;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;

/**
 * Pins the {@code dlq-completed} listing (slice 004, US6; FR-004-043, FR-004-041; design A3-33;
 * research.md Decision 5): it peeks the dead-letter queue, lists only the entries whose request is
 * {@code COMPLETED} with their dead-letter reason, removes nothing, pages at 500, and prints only
 * bounded codes and identifiers.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("the dlq-completed listing")
class DlqCompletedListingQueryTest {

    private static final String SOURCE = "RESULTS";
    private static final Instant ENQUEUED = Instant.parse("2026-08-19T16:42:07.512Z");

    @Mock
    private DeadLetterReader reader;

    @Mock
    private RequestListingRepository repository;

    private DlqCompletedListingQuery query() {
        return new DlqCompletedListingQuery(reader, repository);
    }

    @Test
    @DisplayName("lists only the entries whose request is COMPLETED, with their dead-letter reason")
    void list_mixedEntries_should_list_completed_only() {
        final UUID completed = UUID.randomUUID();
        final UUID failed = UUID.randomUUID();
        final UUID unknown = UUID.randomUUID();
        final UUID completedReplay = UUID.randomUUID();
        final String replayId = "REPLAY:" + UUID.randomUUID();
        when(reader.peek(0L, 100)).thenReturn(List.of(
                entry(3, "RESULTS:" + completed, completed, "leg-exhausted"),
                entry(4, "RESULTS:" + failed, failed, "leg-exhausted"),
                entry(5, "RESULTS:" + unknown, unknown, "exhausted"),
                new DeadLetterEntry(6, Optional.of("garbage"), Optional.empty(),
                        Optional.of("validation"), Optional.of(ENQUEUED)),
                entry(7, replayId, completedReplay, "exhausted")));
        when(reader.peek(8L, 100)).thenReturn(List.of());
        when(repository.requestStatuses(eq(SOURCE), any())).thenReturn(Map.of(
                completed, "COMPLETED", failed, "FAILED", completedReplay, "COMPLETED"));

        final DlqCompletedListing listing = query().list();

        assertThat(listing).isEqualTo(new DlqCompletedListing.Listed(List.of(
                new DlqCompletedListing.Entry(3, "RESULTS:" + completed, completed,
                        "leg-exhausted", Optional.of(ENQUEUED)),
                new DlqCompletedListing.Entry(7, replayId, completedReplay, "exhausted",
                        Optional.of(ENQUEUED))), false));
    }

    @Test
    @DisplayName("bounds a reason or messageId chosen elsewhere to a code, never echoing it")
    void list_foreignReasonAndId_should_be_bounded() {
        final UUID tooled = UUID.randomUUID();
        final UUID broker = UUID.randomUUID();
        final UUID none = UUID.randomUUID();
        when(reader.peek(0L, 100)).thenReturn(List.of(
                entry(1, "tool-chosen-SECRET", tooled, "MaxDeliveryCountExceeded-SECRET"),
                entry(2, "RESULTS:" + broker, broker, "some broker text SECRET"),
                new DeadLetterEntry(3, Optional.empty(), Optional.of(none), Optional.empty(),
                        Optional.empty())));
        when(reader.peek(4L, 100)).thenReturn(List.of());
        when(repository.requestStatuses(eq(SOURCE), any())).thenReturn(Map.of(
                tooled, "COMPLETED", broker, "COMPLETED", none, "COMPLETED"));

        final DlqCompletedListing listing = query().list();

        final List<DlqCompletedListing.Entry> entries =
                ((DlqCompletedListing.Listed) listing).entries();
        assertThat(entries).extracting(DlqCompletedListing.Entry::messageId)
                .containsExactly("other", "RESULTS:" + broker, "other");
        assertThat(entries).extracting(DlqCompletedListing.Entry::deadLetterReason)
                .containsExactly("other", "other", "none");
        assertThat(listing.toString()).doesNotContain("SECRET");
    }

    @Test
    @DisplayName("pages through the dead-letter queue by sequence number until it is exhausted")
    void list_severalPeekPages_should_follow_the_sequence() {
        final List<DeadLetterEntry> first = entries(0, 100);
        final List<DeadLetterEntry> second = entries(100, 40);
        when(reader.peek(0L, 100)).thenReturn(first);
        when(reader.peek(100L, 100)).thenReturn(second);
        when(reader.peek(140L, 100)).thenReturn(List.of());
        when(repository.requestStatuses(eq(SOURCE), any())).thenAnswer(
                invocation -> allCompleted(invocation.getArgument(1)));

        final DlqCompletedListing listing = query().list();

        assertThat(((DlqCompletedListing.Listed) listing).entries()).hasSize(140);
        assertThat(((DlqCompletedListing.Listed) listing).more()).isFalse();
    }

    @Test
    @DisplayName("pages at 500 and says when more exist")
    void list_overAPage_should_show_500_and_say_more() {
        when(reader.peek(anyLong(), eq(100))).thenAnswer(invocation -> {
            final long from = invocation.getArgument(0);
            return from >= 1_000 ? List.of() : entries(from, 100);
        });
        when(repository.requestStatuses(eq(SOURCE), any())).thenAnswer(
                invocation -> allCompleted(invocation.getArgument(1)));

        final DlqCompletedListing listing = query().list();

        final DlqCompletedListing.Listed listed = (DlqCompletedListing.Listed) listing;
        assertThat(listed.entries()).hasSize(DlqCompletedListingQuery.PAGE_SIZE);
        assertThat(listed.entries().get(0).sequenceNumber()).isZero();
        assertThat(listed.more()).isTrue();
    }

    @Test
    @DisplayName("removes nothing: it only peeks and reads statuses")
    void list_should_only_peek() {
        when(reader.peek(0L, 100)).thenReturn(entries(0, 2));
        when(reader.peek(2L, 100)).thenReturn(List.of());
        when(repository.requestStatuses(eq(SOURCE), any())).thenAnswer(
                invocation -> allCompleted(invocation.getArgument(1)));

        query().list();

        verify(reader).peek(0L, 100);
        verify(reader).peek(2L, 100);
        verifyNoMoreInteractions(reader);
        assertThat(Arrays.stream(DeadLetterReader.class.getMethods()).map(Method::getName))
                .containsExactly("peek");
    }

    @Test
    @DisplayName("answers broker-unavailable when the dead-letter queue cannot be peeked")
    void list_brokerDown_should_answer_broker_unavailable() {
        when(reader.peek(anyLong(), anyInt())).thenThrow(
                new DeadLetterReader.DeadLetterReadException(new IllegalStateException("down")));

        assertThat(query().list()).isEqualTo(new DlqCompletedListing.BrokerUnavailable());
        verifyNoInteractions(repository);
    }

    @Test
    @DisplayName("answers store-unavailable when the request statuses cannot be read")
    void list_storeDown_should_answer_store_unavailable() {
        when(reader.peek(0L, 100)).thenReturn(entries(0, 1));
        when(repository.requestStatuses(eq(SOURCE), any()))
                .thenThrow(new DataAccessResourceFailureException("down"));

        assertThat(query().list()).isEqualTo(new DlqCompletedListing.StoreUnavailable());
    }

    @Test
    @DisplayName("a listed entry has no component for a body, payload, command, operator, "
            + "description or userId")
    void entry_should_carry_no_forbidden_component() {
        final List<String> components = new ArrayList<>();
        for (final Class<?> type : List.of(DlqCompletedListing.Entry.class, DeadLetterEntry.class)) {
            Arrays.stream(type.getRecordComponents())
                    .map(component -> component.getName().toLowerCase(Locale.ROOT))
                    .forEach(components::add);
        }

        assertThat(components).noneMatch(name -> name.contains("body") || name.contains("payload")
                || name.contains("command") || name.contains("operator")
                || name.contains("description") || name.contains("user")
                || name.contains("propert"));
    }

    private static DeadLetterEntry entry(final long sequence, final String messageId,
                                         final UUID requestId, final String reason) {
        return new DeadLetterEntry(sequence, Optional.of(messageId), Optional.of(requestId),
                Optional.of(reason), Optional.of(ENQUEUED));
    }

    private static List<DeadLetterEntry> entries(final long from, final int count) {
        return LongStream.range(from, from + count).mapToObj(sequence -> {
            final UUID requestId = UUID.randomUUID();
            return entry(sequence, "RESULTS:" + requestId, requestId, "leg-exhausted");
        }).toList();
    }

    private static Map<UUID, String> allCompleted(final Collection<UUID> requestIds) {
        final Map<UUID, String> statuses = new HashMap<>();
        requestIds.forEach(id -> statuses.put(id, "COMPLETED"));
        return statuses;
    }
}
