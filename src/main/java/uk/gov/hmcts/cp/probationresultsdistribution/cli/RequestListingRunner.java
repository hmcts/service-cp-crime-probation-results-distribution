package uk.gov.hmcts.cp.probationresultsdistribution.cli;

import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
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
 * <p>The whole token stream is read by the shared {@link ArgumentParser} first (FR-004-020; PR #29
 * review): an unrecognised flag, a flag given twice or a stray trailing token is
 * {@code unreadable-argument}. A readable stream then goes to the listing's own check
 * ({@link RequestListingQuery#check}), and the run calls that same {@link RequestListingQuery}.
 * It prints one line per row — identifiers, statuses and reasons, attempt numbers and origins, the
 * HTTP status and times, nothing else (FR-004-041) — and a summary saying whether more rows exist
 * than the page shown. No supplied text is echoed.
 */
public final class RequestListingRunner {

    private static final Logger LOG = LoggerFactory.getLogger(RequestListingRunner.class);
    private static final String ABSENT = "-";
    private static final String HEARING_ID_FLAG = "--hearing-id";
    private static final String HEARING_DAY_FLAG = "--hearing-day";
    private static final String STATUS_FLAG = "--status";
    private static final String REASON_FLAG = "--reason";
    private static final String UPDATED_AFTER_FLAG = "--updated-after";
    private static final String UPDATED_BEFORE_FLAG = "--updated-before";
    private static final Set<String> VALUE_FLAGS = Set.of(HEARING_ID_FLAG, HEARING_DAY_FLAG,
            STATUS_FLAG, REASON_FLAG, UPDATED_AFTER_FLAG, UPDATED_BEFORE_FLAG);

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
        final Parsed parsed = parse(args);
        return parsed.refusal().isPresent() ? parsed.refusal()
                : RequestListingQuery.check(parsed.request().orElseThrow());
    }

    /**
     * Runs {@code list-requests} over the process arguments.
     *
     * @param args the full command-line arguments, including the leading action name
     * @return the bounded runner outcome
     */
    public RunnerOutcome run(final String... args) {
        final Parsed parsed = parse(args);
        final RunnerOutcome outcome;
        if (parsed.refusal().isPresent()) {
            LOG.warn("list-requests refused reason={}", parsed.refusal().get().value());
            outcome = RunnerOutcome.REFUSED;
        } else {
            final RequestListingRequest request = parsed.request().orElseThrow();
            final Optional<WholeRunRefusal> refusal = RequestListingQuery.check(request);
            if (refusal.isPresent()) {
                LOG.warn("list-requests refused reason={}", refusal.get().value());
                outcome = RunnerOutcome.REFUSED;
            } else {
                outcome = render(query.list(request));
            }
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

    private static Parsed parse(final String... args) {
        return switch (ArgumentParser.parse(args, VALUE_FLAGS, Set.of())) {
            case ArgumentParser.Result.Refused refused -> Parsed.refused(refused.refusal());
            case ArgumentParser.Result.Readable readable -> Parsed.readable(request(
                    readable.arguments()));
        };
    }

    private static RequestListingRequest request(final ArgumentParser arguments) {
        return new RequestListingRequest(arguments.value(HEARING_ID_FLAG),
                arguments.value(HEARING_DAY_FLAG), arguments.value(STATUS_FLAG),
                arguments.value(REASON_FLAG), arguments.value(UPDATED_AFTER_FLAG),
                arguments.value(UPDATED_BEFORE_FLAG));
    }

    /** The checked token stream: a refusal, or the request to check and run. */
    private record Parsed(Optional<WholeRunRefusal> refusal,
                          Optional<RequestListingRequest> request) {

        /* default */ static Parsed refused(final WholeRunRefusal refusal) {
            return new Parsed(Optional.of(refusal), Optional.empty());
        }

        /* default */ static Parsed readable(final RequestListingRequest request) {
            return new Parsed(Optional.empty(), Optional.of(request));
        }
    }
}
