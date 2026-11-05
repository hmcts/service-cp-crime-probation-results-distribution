package uk.gov.hmcts.cp.probationresultsdistribution.application;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.probationresultsdistribution.application.CommandEnqueuer.OutgoingCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Submit-hearing (design §4.8 action 5; slice 004, FR-004-054–FR-004-058): the repair route for a
 * share the service never admitted. It checks its input whole before the cache is read
 * (FR-004-020), reads the cached payload by the dated {@code EXT_} key and then the undated one —
 * through a cache-only {@link HearingPayloadSource}, never the results query API —, rebuilds the
 * producer's {@code requestId} with {@link RequestIdRecipe} from the payload's raw
 * {@code sharedTime} string, and enqueues one normal command: {@code source = RESULTS}, no
 * {@code userId}, {@code messageId = RESULTS:{requestId}}, {@code correlationId} the hearing id,
 * and the broker application properties {@code origin = SUPPORT}, the operator, a run id new to
 * this invocation and the reason (never body fields).
 *
 * <p>It writes nothing to the store and reads no intake state, so it is available whatever the
 * intake state (FR-004-058); every admission effect happens when the message is delivered
 * (FR-004-057). A refusal never echoes the text supplied.
 */
public final class SubmitHearingAction {

    private static final int MAX_TEXT_LENGTH = 200;
    private static final String SOURCE = "RESULTS";
    private static final String EVENT_TYPE = "Hearing_Resulted";
    private static final String SHARED_TIME = "sharedTime";
    private static final Pattern ISO_DATE = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");
    private static final Pattern CONTROL = Pattern.compile("\\p{Cntrl}+");
    private static final UUID NO_REQUEST_YET = new UUID(0L, 0L);

    private final HearingPayloadSource cache;
    private final CommandEnqueuer enqueuer;
    private final Supplier<UUID> runIds;

    /**
     * Creates the action.
     *
     * @param cache    the {@code EXT_} cache reads only (dated key, then undated key)
     * @param enqueuer the enqueue port
     * @param runIds   the source of a fresh run id per invocation
     */
    public SubmitHearingAction(final HearingPayloadSource cache, final CommandEnqueuer enqueuer,
                               final Supplier<UUID> runIds) {
        this.cache = cache;
        this.enqueuer = enqueuer;
        this.runIds = runIds;
    }

    /**
     * Checks the arguments whole, before the cache is read (FR-004-020): a missing or blank
     * argument is {@code missing-argument}; a hearing id that is not a UUID or a day that is not a
     * {@code YYYY-MM-DD} date is {@code unreadable-argument}; an operator or reason over 200
     * characters is {@code argument-too-long}.
     *
     * @param request the arguments as supplied
     * @return the refusal, or empty when readable
     */
    // Each check exits as soon as it decides, so the first unreadable argument names the refusal.
    @SuppressWarnings("PMD.OnlyOneReturn")
    public static Optional<WholeRunRefusal> check(final SubmitHearingRequest request) {
        if (blank(request.hearingId()) || blank(request.hearingDay())
                || blank(request.operator()) || blank(request.reason())) {
            return Optional.of(WholeRunRefusal.MISSING_ARGUMENT);
        }
        if (uuid(request.hearingId().get()).isEmpty() || date(request.hearingDay().get()).isEmpty()) {
            return Optional.of(WholeRunRefusal.UNREADABLE_ARGUMENT);
        }
        if (request.operator().get().length() > MAX_TEXT_LENGTH
                || request.reason().get().length() > MAX_TEXT_LENGTH) {
            return Optional.of(WholeRunRefusal.ARGUMENT_TOO_LONG);
        }
        return Optional.empty();
    }

    /**
     * Submits one hearing.
     *
     * @param request the arguments as supplied
     * @return the bounded outcome
     */
    public SubmitHearingOutcome submit(final SubmitHearingRequest request) {
        final Optional<WholeRunRefusal> refusal = check(request);
        final SubmitHearingOutcome outcome;
        if (refusal.isPresent()) {
            outcome = new SubmitHearingOutcome.Refused(refusal.get());
        } else {
            final UUID hearingId = uuid(request.hearingId().get()).orElseThrow();
            final LocalDate hearingDay = date(request.hearingDay().get()).orElseThrow();
            outcome = rawSharedTime(hearingId, hearingDay)
                    .map(sharedTime -> enqueue(hearingId, hearingDay, sharedTime,
                            sanitised(request.operator().get()), sanitised(request.reason().get())))
                    .orElseGet(SubmitHearingOutcome.PayloadUnavailable::new);
        }
        return outcome;
    }

    /**
     * The cached payload's {@code sharedTime}, exactly as the payload carries it, when a key holds
     * a payload whose {@code sharedTime} is a string that reads as an instant (FR-004-054,
     * FR-004-055). The cache is keyed by hearing id and day only; the lookup command's other
     * fields are placeholders no cache read uses.
     */
    private Optional<String> rawSharedTime(final UUID hearingId, final LocalDate hearingDay) {
        final DistributionCommand lookup = new DistributionCommand(SOURCE, NO_REQUEST_YET,
                hearingId, hearingDay, Instant.EPOCH, EVENT_TYPE, Optional.empty());
        return cache.fetch(lookup)
                .filter(fetched -> fetched.sharedTime().isPresent())
                .map(fetched -> fetched.payload().get(SHARED_TIME))
                .filter(JsonNode::isString)
                .map(JsonNode::stringValue);
    }

    private SubmitHearingOutcome enqueue(final UUID hearingId, final LocalDate hearingDay,
                                         final String sharedTime, final String operator,
                                         final String reason) {
        final UUID requestId = RequestIdRecipe.requestId(hearingId.toString(),
                hearingDay.toString(), sharedTime);
        final UUID runId = runIds.get();
        final ObjectNode body = JsonNodeFactory.instance.objectNode()
                .put("source", SOURCE)
                .put("requestId", requestId.toString())
                .put("hearingId", hearingId.toString())
                .put("hearingDay", hearingDay.toString())
                .put(SHARED_TIME, sharedTime)
                .put("eventType", EVENT_TYPE);
        SubmitHearingOutcome outcome;
        try {
            enqueuer.enqueue(new OutgoingCommand("RESULTS:" + requestId, hearingId.toString(),
                    body.toString(), Map.of("origin", "SUPPORT", "operator", operator,
                    "runId", runId.toString(), "reason", reason)));
            outcome = new SubmitHearingOutcome.Enqueued(requestId, runId);
        } catch (final CommandEnqueuer.CommandEnqueueException brokerFailure) {
            // Not swallowed: mapped to the bounded broker-unavailable outcome the runner reports.
            outcome = new SubmitHearingOutcome.BrokerUnavailable();
        }
        return outcome;
    }

    private static boolean blank(final Optional<String> value) {
        return value.isEmpty() || sanitised(value.get()).isEmpty();
    }

    private static String sanitised(final String text) {
        return CONTROL.matcher(text).replaceAll(" ").strip();
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
}
