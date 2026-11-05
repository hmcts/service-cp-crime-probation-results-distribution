package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Optional;
import java.util.OptionalInt;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListing;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListingQuery;
import uk.gov.hmcts.cp.probationresultsdistribution.application.RequestListingRequest;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunnerOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository.ListedAttempt;

/**
 * The read-only {@code list-requests} command-line runner (design §4.8 action 1; slice 004,
 * FR-004-043; {@code contracts/cli-runners.md} §7):
 * {@code list-requests --hearing-id <uuid> --hearing-day <YYYY-MM-DD>} or
 * {@code list-requests --status <status> [--reason <reason>] [--updated-after <instant>]
 * [--updated-before <instant>]}.
 *
 * <p>The arguments are checked whole before anything is opened (FR-004-020) by the listing's own
 * check ({@link RequestListingQuery#check}), and the run calls that same {@link RequestListingQuery}.
 * It prints one line per row — identifiers, statuses and reasons, attempt numbers and origins, the
 * HTTP status and times, nothing else (FR-004-041) — and a summary saying whether more rows exist
 * than the page shown. No supplied text is echoed.
 */
public final class RequestListingRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RequestListingRunner.class);
    private static final String ABSENT = "-";

    private final RequestListingQuery query;

    /**
     * Creates the runner.
     *
     * @param query the request listing
     */
    public RequestListingRunner(final RequestListingQuery query) {
        this.query = query;
    }

    /**
     * Checks the arguments before anything is opened (FR-004-020).
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the whole-run refusal, or empty when the arguments are readable
     */
    public static Optional<WholeRunRefusal> checkArguments(final String... args) {
        return RequestListingQuery.check(request(args));
    }

    /**
     * Runs {@code list-requests} over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final RequestListingRequest request = request(args);
        final Optional<WholeRunRefusal> refusal = RequestListingQuery.check(request);
        final RunnerOutcome outcome;
        if (refusal.isPresent()) {
            LOG.warn("list-requests refused reason={}", refusal.get().value());
            outcome = RunnerOutcome.REFUSED;
        } else {
            outcome = render(query.list(request));
        }
        return outcome;
    }

    private static RunnerOutcome render(final RequestListing listing) {
        return switch (listing) {
            case RequestListing.Listed listed -> {
                listed.rows().forEach(RequestListingRunner::print);
                LOG.info("list-requests done rows={} more={}", listed.rows().size(), listed.more());
                if (listed.more()) {
                    LOG.warn("list-requests more rows exist than the {} shown; narrow the filter",
                            RequestListingQuery.PAGE_SIZE);
                }
                yield RunnerOutcome.DONE;
            }
            case RequestListing.Refused refused -> {
                LOG.warn("list-requests refused reason={}", refused.reason().value());
                yield RunnerOutcome.REFUSED;
            }
            case RequestListing.StoreUnavailable ignored -> {
                LOG.error("list-requests failed reason=store-unavailable");
                yield RunnerOutcome.FAILED;
            }
        };
    }

    private static void print(final ListedAttempt row) {
        LOG.info("list-requests row requestId={} hearingId={} hearingDay={} requestStatus={} "
                        + "failureReason={} completionReason={} attempt={} attemptOrigin={} "
                        + "legStatus={} legReason={} httpStatus={} updatedAt={} sentAt={}",
                row.requestId(), row.hearingId(), row.hearingDay(), row.requestStatus(),
                text(row.failureReason()), text(row.completionReason()), row.attempt(),
                row.attemptOrigin(), row.legStatus(), text(row.legReason()),
                text(row.httpStatus()), row.updatedAt(), text(row.sentAt()));
    }

    private static String text(final Optional<?> value) {
        return value.map(Object::toString).orElse(ABSENT);
    }

    private static String text(final OptionalInt value) {
        return value.isPresent() ? Integer.toString(value.getAsInt()) : ABSENT;
    }

    private static RequestListingRequest request(final String... args) {
        return new RequestListingRequest(valueOf(args, "--hearing-id"),
                valueOf(args, "--hearing-day"), valueOf(args, "--status"),
                valueOf(args, "--reason"), valueOf(args, "--updated-after"),
                valueOf(args, "--updated-before"));
    }

    /**
     * A flag's value; a flag given last, with no value after it, is present but blank, so it is
     * refused {@code missing-argument} rather than read as absent.
     */
    private static Optional<String> valueOf(final String[] args, final String flag) {
        Optional<String> value = Optional.empty();
        for (int i = 0; i < args.length; i++) {
            if (flag.equals(args[i])) {
                value = Optional.of(i + 1 < args.length ? args[i + 1] : "");
                break;
            }
        }
        return value;
    }
}
