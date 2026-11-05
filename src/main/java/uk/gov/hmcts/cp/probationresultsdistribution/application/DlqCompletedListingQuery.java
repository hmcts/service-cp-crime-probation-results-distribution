package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DeadLetterReader.DeadLetterEntry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DeadLetterReason;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;

/**
 * The read-only {@code dlq-completed} listing (design A3-33; slice 004, FR-004-043; research.md
 * Decision 5): the dead-letter entries whose request is now {@code COMPLETED} — the runbook's
 * "remove the original once the replayed request is {@code COMPLETED}" candidates (FR-004-046).
 *
 * <p>It peeks the dead-letter queue a batch at a time by sequence number — a receiver peek, which
 * locks and removes nothing — joins each batch's {@code requestId}s against
 * {@code processed_request.status}, and keeps the {@code COMPLETED} ones until it has one page of
 * {@value #PAGE_SIZE} and knows whether more exist. It prints identifiers and bounded codes only: a
 * dead-letter reason other than this service's own is {@code other} (absent, {@code none}), and a
 * {@code messageId} not of this service's forms is {@code other}, so text chosen elsewhere is
 * never echoed.
 */
public final class DlqCompletedListingQuery {

    /** The fixed page size (research.md Decision 5): no property sets it. */
    public static final int PAGE_SIZE = 500;

    private static final int PEEK_BATCH = 100;
    private static final String SOURCE = "RESULTS";
    private static final String COMPLETED = "COMPLETED";
    private static final String OTHER = "other";
    private static final String NONE = "none";
    private static final Pattern OWN_MESSAGE_ID = Pattern.compile(
            "(RESULTS|REPLAY):[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    private final DeadLetterReader reader;
    private final RequestListingRepository repository;

    /**
     * Creates the query.
     *
     * @param reader     the dead-letter peek port
     * @param repository the request-status lookup
     */
    public DlqCompletedListingQuery(final DeadLetterReader reader,
                                    final RequestListingRepository repository) {
        this.reader = reader;
        this.repository = repository;
    }

    /**
     * Lists the dead-letter entries whose request is {@code COMPLETED}.
     *
     * @return the bounded outcome
     */
    public DlqCompletedListing list() {
        DlqCompletedListing listing;
        try {
            final List<DlqCompletedListing.Entry> completed = collect();
            final boolean more = completed.size() > PAGE_SIZE;
            listing = new DlqCompletedListing.Listed(
                    more ? completed.subList(0, PAGE_SIZE) : completed, more);
        } catch (final DeadLetterReader.DeadLetterReadException brokerFailure) {
            // Not swallowed: mapped to the bounded broker-unavailable outcome the runner reports.
            listing = new DlqCompletedListing.BrokerUnavailable();
        } catch (final DataAccessException storeFailure) {
            // Not swallowed: mapped to the bounded store-unavailable outcome the runner reports.
            listing = new DlqCompletedListing.StoreUnavailable();
        }
        return listing;
    }

    private List<DlqCompletedListing.Entry> collect() {
        final List<DlqCompletedListing.Entry> completed = new ArrayList<>();
        long from = 0L;
        List<DeadLetterEntry> batch = reader.peek(from, PEEK_BATCH);
        while (!batch.isEmpty() && completed.size() <= PAGE_SIZE) {
            final List<UUID> requestIds = batch.stream().map(DeadLetterEntry::requestId)
                    .flatMap(Optional::stream).distinct().toList();
            final Map<UUID, String> statuses = repository.requestStatuses(SOURCE, requestIds);
            for (final DeadLetterEntry entry : batch) {
                entry.requestId().filter(id -> COMPLETED.equals(statuses.get(id)))
                        .ifPresent(id -> completed.add(new DlqCompletedListing.Entry(
                                entry.sequenceNumber(), messageId(entry), id, reason(entry),
                                entry.enqueuedAt())));
            }
            from = batch.get(batch.size() - 1).sequenceNumber() + 1;
            batch = completed.size() > PAGE_SIZE ? List.of() : reader.peek(from, PEEK_BATCH);
        }
        return completed;
    }

    private static String messageId(final DeadLetterEntry entry) {
        return entry.messageId().filter(id -> OWN_MESSAGE_ID.matcher(id).matches()).orElse(OTHER);
    }

    private static String reason(final DeadLetterEntry entry) {
        return entry.deadLetterReason().map(DlqCompletedListingQuery::ownReason).orElse(NONE);
    }

    private static String ownReason(final String raw) {
        String bounded;
        try {
            bounded = DeadLetterReason.fromValue(raw).wireValue();
        } catch (final IllegalArgumentException notOurs) {
            // A reason another tool or the broker chose is reported as a code, never echoed.
            bounded = OTHER;
        }
        return bounded;
    }
}
