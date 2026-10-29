package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DlqCompletedListing;
import uk.gov.hmcts.cp.probationresultsdistribution.application.DlqCompletedListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;

/**
 * The read-only {@code list-dlq-completed} command-line runner (design A3-33; slice 004,
 * FR-004-043; {@code contracts/cli-runners.md} §8): no arguments. Calls the same
 * {@link DlqCompletedListingQuery} slice 005 would, printing one line per dead-letter entry whose
 * request is {@code COMPLETED} — sequence number, {@code messageId}, {@code requestId}, the bounded
 * dead-letter reason and the enqueued time, nothing else (FR-004-041) — and a summary saying whether
 * more exist than the page shown. It removes nothing.
 */
public final class DlqCompletedListingRunner {

    private static final Logger LOG = LoggerFactory.getLogger(DlqCompletedListingRunner.class);

    private final DlqCompletedListingQuery query;

    /**
     * Creates the runner.
     *
     * @param query the dlq-completed listing
     */
    public DlqCompletedListingRunner(final DlqCompletedListingQuery query) {
        this.query = query;
    }

    /**
     * Runs {@code list-dlq-completed}.
     *
     * @return the bounded runner outcome
     */
    public RunnerOutcome run() {
        return switch (query.list()) {
            case DlqCompletedListing.Listed listed -> {
                listed.entries().forEach(DlqCompletedListingRunner::print);
                LOG.info("list-dlq-completed done entries={} more={}", listed.entries().size(),
                        listed.more());
                if (listed.more()) {
                    LOG.warn("list-dlq-completed more entries exist than the {} shown",
                            DlqCompletedListingQuery.PAGE_SIZE);
                }
                yield RunnerOutcome.DONE;
            }
            case DlqCompletedListing.BrokerUnavailable ignored -> {
                LOG.error("list-dlq-completed failed reason=broker-unavailable");
                yield RunnerOutcome.FAILED;
            }
            case DlqCompletedListing.StoreUnavailable ignored -> {
                LOG.error("list-dlq-completed failed reason=store-unavailable");
                yield RunnerOutcome.FAILED;
            }
        };
    }

    private static void print(final DlqCompletedListing.Entry entry) {
        LOG.info("list-dlq-completed entry sequenceNumber={} messageId={} requestId={} "
                        + "deadLetterReason={} enqueuedAt={}", entry.sequenceNumber(),
                entry.messageId(), entry.requestId(), entry.deadLetterReason(),
                entry.enqueuedAt().map(Object::toString).orElse("-"));
    }
}
