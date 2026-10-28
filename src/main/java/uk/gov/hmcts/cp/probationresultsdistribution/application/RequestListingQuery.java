package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import org.springframework.dao.DataAccessException;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository.ListedAttempt;

/**
 * The read-only request listing (design §4.8 action 1; slice 004, FR-004-043, FR-004-020,
 * FR-004-034; research.md Decision 5). Two forms, exactly one per listing: by hearing and day,
 * every attempt of every request for them; or by the latest HMPPS attempt's status, with an
 * optional reason and {@code updated_at} range, read from the latest attempts — ready to pass to
 * resend-leg. Its input is checked whole before any store read, and a refusal never echoes the
 * text supplied. One page of {@value #PAGE_SIZE} rows is shown, and the outcome says when more
 * exist. It writes nothing.
 */
public final class RequestListingQuery {

    /** The fixed page size (research.md Decision 5): no property sets it. */
    public static final int PAGE_SIZE = 500;

    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    private final RequestListingRepository repository;

    /**
     * Creates the query.
     *
     * @param repository the listing store reads
     */
    public RequestListingQuery(final RequestListingRepository repository) {
        this.repository = repository;
    }

    /**
     * Checks the arguments whole, before any store read (FR-004-020): what {@link #list} would
     * refuse them with, so that a runner can refuse before it opens anything.
     *
     * @param request the arguments as supplied
     * @return the whole refusal, or empty when the arguments are readable
     */
    public static Optional<WholeRunRefusal> check(final RequestListingRequest request) {
        return plan(request).refusal();
    }

    /**
     * Lists requests by one of the two forms.
     *
     * @param request the arguments as supplied
     * @return the bounded outcome
     */
    public RequestListing list(final RequestListingRequest request) {
        final Plan plan = plan(request);
        return plan.refusal().<RequestListing>map(RequestListing.Refused::new)
                .orElseGet(() -> read(() -> plan.read().rows(repository)));
    }

    private static Plan plan(final RequestListingRequest request) {
        final boolean hearingForm = request.hearingId().isPresent()
                || request.hearingDay().isPresent();
        final boolean latestForm = request.status().isPresent() || request.reason().isPresent()
                || request.updatedAfter().isPresent() || request.updatedBefore().isPresent();
        final Plan plan;
        if (hearingForm && latestForm) {
            plan = Plan.refused(WholeRunRefusal.UNREADABLE_ARGUMENT);
        } else if (hearingForm) {
            plan = byHearing(request);
        } else if (latestForm) {
            plan = byLatestAttempt(request);
        } else {
            plan = Plan.refused(WholeRunRefusal.MISSING_ARGUMENT);
        }
        return plan;
    }

    // Each check exits as soon as it decides, so the first unreadable argument names the refusal.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Plan byHearing(final RequestListingRequest request) {
        if (blank(request.hearingId()) || blank(request.hearingDay())) {
            return Plan.refused(WholeRunRefusal.MISSING_ARGUMENT);
        }
        final Optional<UUID> hearingId = uuid(request.hearingId().get());
        final Optional<LocalDate> hearingDay = date(request.hearingDay().get());
        if (hearingId.isEmpty() || hearingDay.isEmpty()) {
            return Plan.refused(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }
        return Plan.reading(store -> store.byHearing(hearingId.get(), hearingDay.get(),
                PAGE_SIZE + 1));
    }

    // Each check exits as soon as it decides, so the first unreadable argument names the refusal.
    @SuppressWarnings("PMD.OnlyOneReturn")
    private static Plan byLatestAttempt(final RequestListingRequest request) {
        if (blank(request.status())) {
            return Plan.refused(WholeRunRefusal.MISSING_ARGUMENT);
        }
        final Optional<LegStatus> status = closed(() -> LegStatus.fromValue(request.status().get()));
        final Optional<Optional<LegReason>> reason = optional(request.reason(),
                value -> closed(() -> LegReason.fromValue(value)));
        final Optional<Optional<Instant>> after = optional(request.updatedAfter(),
                RequestListingQuery::instant);
        final Optional<Optional<Instant>> before = optional(request.updatedBefore(),
                RequestListingQuery::instant);
        if (status.isEmpty() || reason.isEmpty() || after.isEmpty() || before.isEmpty()) {
            return Plan.refused(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }
        return Plan.reading(store -> store.byLatestAttempt(status.get().wireValue(),
                reason.get().map(LegReason::wireValue), after.get(), before.get(),
                PAGE_SIZE + 1));
    }

    private static RequestListing read(final StoreRead read) {
        RequestListing listing;
        try {
            final List<ListedAttempt> rows = read.rows();
            final boolean more = rows.size() > PAGE_SIZE;
            listing = new RequestListing.Listed(more ? rows.subList(0, PAGE_SIZE) : rows, more);
        } catch (final DataAccessException storeFailure) {
            // Not swallowed: mapped to the bounded store-unavailable outcome the runner reports.
            listing = new RequestListing.StoreUnavailable();
        }
        return listing;
    }

    /**
     * Reads an optional argument: absent stays absent; present must parse, or the whole is empty.
     */
    private static <T> Optional<Optional<T>> optional(final Optional<String> raw,
                                                      final Parser<T> parser) {
        return raw.isEmpty() ? Optional.of(Optional.empty()) : parser.parse(raw.get()).map(Optional::of);
    }

    private static boolean blank(final Optional<String> value) {
        return value.isEmpty() || value.get().isBlank();
    }

    private static Optional<UUID> uuid(final String raw) {
        Optional<UUID> parsed;
        try {
            parsed = Optional.of(UUID.fromString(raw));
        } catch (final IllegalArgumentException notAUuid) {
            // Refused unreadable-argument; the text is never echoed.
            parsed = Optional.empty();
        }
        return parsed;
    }

    private static Optional<LocalDate> date(final String raw) {
        Optional<LocalDate> parsed = Optional.empty();
        if (ISO_DATE.matcher(raw).matches()) {
            try {
                parsed = Optional.of(LocalDate.parse(raw));
            } catch (final DateTimeParseException notADate) {
                // Refused unreadable-argument; the text is never echoed.
                parsed = Optional.empty();
            }
        }
        return parsed;
    }

    private static Optional<Instant> instant(final String raw) {
        Optional<Instant> parsed;
        try {
            parsed = Optional.of(OffsetDateTime.parse(raw).toInstant());
        } catch (final DateTimeParseException notAnInstant) {
            // Refused unreadable-argument; the text is never echoed.
            parsed = Optional.empty();
        }
        return parsed;
    }

    private static <T> Optional<T> closed(final Supplier<T> lookup) {
        Optional<T> parsed;
        try {
            parsed = Optional.of(lookup.get());
        } catch (final IllegalArgumentException outsideTheSet) {
            // Refused unreadable-argument; the text is never echoed.
            parsed = Optional.empty();
        }
        return parsed;
    }

    /** One store read of the listing's rows. */
    @FunctionalInterface
    private interface StoreRead {
        List<ListedAttempt> rows();
    }

    /** The store read a checked request asks for. */
    @FunctionalInterface
    private interface PlannedRead {
        List<ListedAttempt> rows(RequestListingRepository store);
    }

    /** A checked request: refused whole, or the store read it asks for. */
    private record Plan(Optional<WholeRunRefusal> refusal, PlannedRead read) {

        /* default */ static Plan refused(final WholeRunRefusal refusal) {
            return new Plan(Optional.of(refusal), store -> List.of());
        }

        /* default */ static Plan reading(final PlannedRead read) {
            return new Plan(Optional.empty(), read);
        }
    }

    /** Parses one optional argument; empty when it is unreadable. */
    @FunctionalInterface
    private interface Parser<T> {
        Optional<T> parse(String raw);
    }
}
