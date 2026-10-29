package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;

/**
 * The read-only store reads behind the two operator listings (design §4.8 action 1 and the
 * {@code dlq-completed} listing; slice 004, FR-004-043, FR-004-034). Every read is a plain
 * {@code SELECT}: nothing here writes, claims or locks. A row carries identifiers, statuses and
 * reasons, attempt numbers and origins, HTTP statuses and times only — never the stored command,
 * {@code audit_note}, an operator, a body or a {@code userId} (FR-004-041), so a listing cannot
 * print what it never reads.
 */
public interface RequestListingRepository {

    /**
     * Every attempt of the HMPPS leg of every request for one hearing and day, ordered by request
     * and attempt.
     *
     * @param hearingId  the hearing id
     * @param hearingDay the hearing day
     * @param limit      the most rows to return
     * @return the attempt rows, at most {@code limit}
     */
    List<ListedAttempt> byHearing(UUID hearingId, LocalDate hearingDay, int limit);

    /**
     * The requests whose latest HMPPS attempt ({@code outbound_message_latest}, a leg's truth) has
     * the given status and, when given, the reason and an {@code updated_at} at or after
     * {@code updatedAfter} and strictly before {@code updatedBefore}; ordered by that
     * {@code updated_at}, then request id.
     *
     * @param status        the latest attempt's status
     * @param reason        the latest attempt's reason, when filtered
     * @param updatedAfter  the inclusive lower bound on the latest attempt's {@code updated_at}
     * @param updatedBefore the exclusive upper bound on the latest attempt's {@code updated_at}
     * @param limit         the most rows to return
     * @return the latest-attempt rows, at most {@code limit}
     */
    List<ListedAttempt> byLatestAttempt(String status, Optional<String> reason,
                                        Optional<Instant> updatedAfter,
                                        Optional<Instant> updatedBefore, int limit);

    /**
     * The status of each of the given requests that has a row.
     *
     * @param source     the command source
     * @param requestIds the request ids
     * @return each found request's status, keyed by request id; an unknown id is absent
     */
    Map<UUID, String> requestStatuses(String source, Collection<UUID> requestIds);

    /**
     * One listed attempt row: the request's identity and state, and one attempt of its leg.
     *
     * @param requestId        the request id
     * @param hearingId        the hearing id
     * @param hearingDay       the hearing day
     * @param requestStatus    the request status
     * @param failureReason    the request's failure reason, on a {@code FAILED} request
     * @param completionReason the request's completion reason, on a {@code COMPLETED} request
     * @param attempt          the attempt number
     * @param attemptOrigin    the attempt's origin ({@code queue}, {@code replay}, {@code resend})
     * @param legStatus        the attempt's status
     * @param legReason        the attempt's bounded reason, when any
     * @param httpStatus       the gateway's status for the attempt, when any
     * @param updatedAt        when the attempt row was last written
     * @param sentAt           when the attempt was sent, on a {@code SENT} row
     */
    record ListedAttempt(UUID requestId, UUID hearingId, LocalDate hearingDay, String requestStatus,
                         Optional<String> failureReason, Optional<String> completionReason,
                         int attempt, String attemptOrigin, String legStatus,
                         Optional<String> legReason, OptionalInt httpStatus, Instant updatedAt,
                         Optional<Instant> sentAt) {

        /** Rejects null components; absence is an empty optional, never null. */
        public ListedAttempt {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(hearingId, "hearingId");
            Objects.requireNonNull(hearingDay, "hearingDay");
            Objects.requireNonNull(requestStatus, "requestStatus");
            Objects.requireNonNull(failureReason, "failureReason");
            Objects.requireNonNull(completionReason, "completionReason");
            Objects.requireNonNull(attemptOrigin, "attemptOrigin");
            Objects.requireNonNull(legStatus, "legStatus");
            Objects.requireNonNull(legReason, "legReason");
            Objects.requireNonNull(httpStatus, "httpStatus");
            Objects.requireNonNull(updatedAt, "updatedAt");
            Objects.requireNonNull(sentAt, "sentAt");
        }
    }
}
