package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.RequestListingRepository.ListedAttempt;

/**
 * Pins the request listing's store reads (slice 004, US6, FR-004-043, FR-004-034): by hearing and
 * day every attempt of every request, and by status, reason and {@code updated_at} range the
 * latest attempt only, read from {@code outbound_message_latest} — never the request's status.
 */
@DisplayName("the request listing's store reads")
class JdbcRequestListingRepositoryIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";
    private static final LocalDate DAY = LocalDate.parse("2026-08-19");
    private static final String CANARY = "CANARY-" + "never-listed";

    private final JdbcRequestListingRepository repository = new JdbcRequestListingRepository(jdbc());

    @Nested
    @DisplayName("by hearing and day")
    class ByHearing {

        @Test
        @DisplayName("shows each request of that hearing and day with every attempt of its leg")
        void byHearing_twoRequests_should_list_every_attempt() {
            final UUID hearingId = UUID.randomUUID();
            final UUID failed = seedRequest(hearingId, DAY, "FAILED", "leg-exhausted", null);
            final Instant base = window();
            seedAttempt(failed, 1, "FAILED", "gateway-transient", base);
            final UUID completed = seedRequest(hearingId, DAY, "COMPLETED", null, "recovered");
            seedAttempt(completed, 1, "FAILED", "payload-unavailable", base);
            seedAttempt(completed, 2, "SENT", null, base.plusSeconds(1));
            seedRequest(hearingId, DAY.plusDays(1), "COMPLETED", null, "both-legs-terminal");

            final List<ListedAttempt> rows = repository.byHearing(hearingId, DAY, 501);

            assertThat(rows).hasSize(3);
            assertThat(rows).extracting(ListedAttempt::requestId)
                    .containsOnly(failed, completed);
            final ListedAttempt failedRow = rowOf(rows, failed, 1);
            assertThat(failedRow.hearingId()).isEqualTo(hearingId);
            assertThat(failedRow.hearingDay()).isEqualTo(DAY);
            assertThat(failedRow.requestStatus()).isEqualTo("FAILED");
            assertThat(failedRow.failureReason()).contains("leg-exhausted");
            assertThat(failedRow.completionReason()).isEmpty();
            assertThat(failedRow.attemptOrigin()).isEqualTo("queue");
            assertThat(failedRow.legStatus()).isEqualTo("FAILED");
            assertThat(failedRow.legReason()).contains("gateway-transient");
            assertThat(failedRow.httpStatus()).hasValue(503);
            assertThat(failedRow.updatedAt()).isEqualTo(base);
            assertThat(failedRow.sentAt()).isEmpty();
            final ListedAttempt resent = rowOf(rows, completed, 2);
            assertThat(resent.completionReason()).contains("recovered");
            assertThat(resent.attemptOrigin()).isEqualTo("resend");
            assertThat(resent.legStatus()).isEqualTo("SENT");
            assertThat(resent.legReason()).isEmpty();
            assertThat(resent.httpStatus()).hasValue(200);
            assertThat(resent.sentAt()).isPresent();
            assertThat(rowOf(rows, completed, 1).legReason()).contains("payload-unavailable");
        }

        @Test
        @DisplayName("orders by request and attempt and stops at the limit")
        void byHearing_overLimit_should_stop_at_limit() {
            final UUID hearingId = UUID.randomUUID();
            final UUID request = seedRequest(hearingId, DAY, "COMPLETED", null, "recovered");
            final Instant base = window();
            seedAttempt(request, 1, "FAILED", "gateway-transient", base);
            seedAttempt(request, 2, "FAILED", "gateway-transient", base.plusSeconds(1));
            seedAttempt(request, 3, "SENT", null, base.plusSeconds(2));

            final List<ListedAttempt> rows = repository.byHearing(hearingId, DAY, 2);

            assertThat(rows).extracting(ListedAttempt::attempt).containsExactly(1, 2);
        }

        @Test
        @DisplayName("answers nothing for a hearing and day with no request")
        void byHearing_unknown_should_be_empty() {
            assertThat(repository.byHearing(UUID.randomUUID(), DAY, 501)).isEmpty();
        }
    }

    @Nested
    @DisplayName("by the latest attempt's status, reason and updated_at range")
    class ByLatestAttempt {

        @Test
        @DisplayName("reads the latest attempt, not an earlier one and not the request's status")
        void byLatestAttempt_should_match_the_latest_row_only() {
            final Instant base = window();
            final UUID latestFailed = seedRequest(UUID.randomUUID(), DAY, "FAILED", "leg-exhausted",
                    null);
            seedAttempt(latestFailed, 1, "FAILED", "gateway-transient", base);
            final UUID recoveredByResend = seedRequest(UUID.randomUUID(), DAY, "FAILED",
                    "leg-exhausted", null);
            seedAttempt(recoveredByResend, 1, "FAILED", "gateway-transient", base);
            seedAttempt(recoveredByResend, 2, "SENT", null, base.plusSeconds(1));
            final UUID completedButLatestFailed = seedRequest(UUID.randomUUID(), DAY, "COMPLETED",
                    null, "both-legs-terminal");
            seedAttempt(completedButLatestFailed, 1, "SENT", null, base);
            seedAttempt(completedButLatestFailed, 2, "FAILED", "gateway-transient",
                    base.plusSeconds(2));

            final List<ListedAttempt> rows = repository.byLatestAttempt("FAILED",
                    Optional.empty(), Optional.of(base), Optional.of(base.plusSeconds(60)), 501);

            assertThat(rows).extracting(ListedAttempt::requestId)
                    .containsExactly(latestFailed, completedButLatestFailed);
            assertThat(rows.get(1).attempt()).isEqualTo(2);
            assertThat(rows.get(1).requestStatus()).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("filters by reason and by an inclusive-exclusive updated_at range")
        void byLatestAttempt_reasonAndRange_should_filter() {
            final Instant base = window();
            final UUID transientAtStart = seedRequest(UUID.randomUUID(), DAY, "FAILED",
                    "leg-exhausted", null);
            seedAttempt(transientAtStart, 1, "FAILED", "gateway-transient", base);
            final UUID unavailable = seedRequest(UUID.randomUUID(), DAY, "FAILED", "leg-exhausted",
                    null);
            seedAttempt(unavailable, 1, "FAILED", "payload-unavailable", base.plusSeconds(1));
            final UUID transientAtEnd = seedRequest(UUID.randomUUID(), DAY, "FAILED",
                    "leg-exhausted", null);
            seedAttempt(transientAtEnd, 1, "FAILED", "gateway-transient", base.plusSeconds(10));

            final List<ListedAttempt> rows = repository.byLatestAttempt("FAILED",
                    Optional.of("gateway-transient"), Optional.of(base),
                    Optional.of(base.plusSeconds(10)), 501);

            assertThat(rows).extracting(ListedAttempt::requestId).containsExactly(transientAtStart);
        }

        @Test
        @DisplayName("stops at the limit, oldest updated_at first")
        void byLatestAttempt_overLimit_should_stop_at_limit() {
            final Instant base = window();
            final UUID first = seedRequest(UUID.randomUUID(), DAY, "FAILED", "leg-exhausted", null);
            seedAttempt(first, 1, "FAILED", "gateway-transient", base);
            final UUID second = seedRequest(UUID.randomUUID(), DAY, "FAILED", "leg-exhausted", null);
            seedAttempt(second, 1, "FAILED", "gateway-transient", base.plusSeconds(1));

            final List<ListedAttempt> rows = repository.byLatestAttempt("FAILED", Optional.empty(),
                    Optional.of(base), Optional.empty(), 1);

            assertThat(rows).extracting(ListedAttempt::requestId).containsExactly(first);
        }
    }

    @Test
    @DisplayName("answers each known request's status and leaves an unknown one out")
    void requestStatuses_should_answer_known_requests_only() {
        final UUID completed = seedRequest(UUID.randomUUID(), DAY, "COMPLETED", null,
                "both-legs-terminal");
        final UUID failed = seedRequest(UUID.randomUUID(), DAY, "FAILED", "leg-exhausted", null);
        final UUID unknown = UUID.randomUUID();

        final Map<UUID, String> statuses = repository.requestStatuses(SOURCE,
                List.of(completed, failed, unknown));

        assertThat(statuses).containsExactlyInAnyOrderEntriesOf(
                Map.of(completed, "COMPLETED", failed, "FAILED"));
        assertThat(repository.requestStatuses(SOURCE, List.of())).isEmpty();
    }

    /** A fresh, far-future window no other suite's rows fall in. */
    private static Instant window() {
        return Instant.parse("2040-01-01T00:00:00Z")
                .plus(ThreadLocalRandom.current().nextLong(1, 5_000_000), ChronoUnit.MINUTES);
    }

    private static ListedAttempt rowOf(final List<ListedAttempt> rows, final UUID requestId,
                                       final int attempt) {
        return rows.stream()
                .filter(row -> row.requestId().equals(requestId) && row.attempt() == attempt)
                .findFirst().orElseThrow();
    }

    private UUID seedRequest(final UUID hearingId, final LocalDate day, final String status,
                             final String failureReason, final String completionReason) {
        final UUID requestId = UUID.randomUUID();
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, "
                        + "failure_reason, exhausted_message_id, completion_reason, command, "
                        + "audit_note) VALUES (:source, :requestId, :hearingId, :day, now(), "
                        + "'Hearing_Resulted', 'fp', :status, 1, :failureReason, :exhausted, "
                        + ":completionReason, :command, :auditNote)")
                .param("source", SOURCE)
                .param("requestId", requestId)
                .param("hearingId", hearingId)
                .param("day", day)
                .param("status", status)
                .param("failureReason", failureReason)
                .param("exhausted", "leg-exhausted".equals(failureReason)
                        ? "RESULTS:" + requestId : null)
                .param("completionReason", completionReason)
                .param("command", "{\"canary\":\"" + CANARY + "\"}")
                .param("auditNote", CANARY)
                .update();
        return requestId;
    }

    private void seedAttempt(final UUID requestId, final int attempt, final String status,
                             final String reason, final Instant updatedAt) {
        final boolean evidence = "SENT".equals(status) || "gateway-transient".equals(reason);
        final boolean read = !"payload-unavailable".equals(reason);
        final boolean resend = attempt > 1;
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, http_status, body_digest, "
                        + "service_version, payload_source, youth_switch, sent_at, claim_owner, "
                        + "claim_token, claim_expires_at, resend_run_id, operator, updated_at) "
                        + "VALUES (:id, :source, :requestId, 'HMPPS', :attempt, :origin, :status, "
                        + ":reason, 1, :http, :digest, :version, :payloadSource, :youth, :sentAt, "
                        + ":owner, :token, :expires, :runId, :operator, :updatedAt)")
                .param("id", UUID.randomUUID())
                .param("source", SOURCE)
                .param("requestId", requestId)
                .param("attempt", attempt)
                .param("origin", resend ? "resend" : "queue")
                .param("status", status)
                .param("reason", reason)
                .param("http", evidence ? ("SENT".equals(status) ? 200 : 503) : null)
                .param("digest", evidence ? "digest" : null)
                .param("version", evidence ? "test-version" : null)
                .param("payloadSource", read ? "cache-day" : null)
                .param("youth", read ? "ACTIVE" : null)
                .param("sentAt", "SENT".equals(status) ? utc(updatedAt) : null)
                .param("owner", resend ? UUID.randomUUID().toString() : null)
                .param("token", resend ? UUID.randomUUID() : null)
                .param("expires", resend ? utc(updatedAt) : null)
                .param("runId", resend ? UUID.randomUUID() : null)
                .param("operator", resend ? CANARY : null)
                .param("updatedAt", utc(updatedAt))
                .update();
    }

    private static OffsetDateTime utc(final Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }
}
