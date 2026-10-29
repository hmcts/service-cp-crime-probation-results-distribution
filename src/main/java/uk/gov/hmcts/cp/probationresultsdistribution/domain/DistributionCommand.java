package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The parsed inbound command for one resulted hearing share (design Appendix B.1). It is a claim
 * check: it carries identifiers, never the hearing payload. Its identity is
 * {@code (source, requestId)} and its fingerprint covers the four immutable fields, excluding
 * {@code userId}. Broker application properties ({@code origin}, {@code operator}, {@code runId},
 * {@code reason}) are not fields of this record and have no accessor: no run reads them (FR-002-004).
 *
 * @param source    the publishing system; the only value is {@code RESULTS}
 * @param requestId the deterministic request identity
 * @param hearingId the resulted hearing
 * @param hearingDay the hearing day of this share
 * @param sharedTime when the hearing was shared
 * @param eventType the only value is {@code Hearing_Resulted}
 * @param userId    the sharing user; absence is valid, and it is not part of the fingerprint
 */
public record DistributionCommand(
        String source,
        UUID requestId,
        UUID hearingId,
        LocalDate hearingDay,
        Instant sharedTime,
        String eventType,
        Optional<UUID> userId) {

    /**
     * Rejects a null required field and a null {@code userId} optional (absence is {@code empty}).
     */
    public DistributionCommand {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(requestId, "requestId");
        Objects.requireNonNull(hearingId, "hearingId");
        Objects.requireNonNull(hearingDay, "hearingDay");
        Objects.requireNonNull(sharedTime, "sharedTime");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(userId, "userId");
    }
}
