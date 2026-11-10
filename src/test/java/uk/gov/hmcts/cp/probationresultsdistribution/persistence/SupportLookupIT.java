package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.SupportLookupRepository.SupportLookupResult;

@DisplayName("the support lookup")
class SupportLookupIT extends SchemaTestSupport {

    private static final String SOURCE = "RESULTS";
    private final SupportLookupRepository repository = new SupportLookupRepository(jdbc());

    @Test
    @DisplayName("finds the request, its latest attempt and the exact stored bytes by hearing id/day")
    void finds_a_sent_leg_with_its_stored_body() {
        final UUID hearingId = UUID.randomUUID();
        final UUID requestId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        final UUID legId = UUID.randomUUID();
        final String body = "{\"hearing\":{\"id\":\"" + hearingId + "\"}}";
        insertCompletedSentLeg(requestKey(requestId, hearingId, hearingDay), legId, body);

        final Optional<SupportLookupResult> found = repository.lookup(hearingId, hearingDay);

        assertThat(found).isPresent();
        final SupportLookupResult result = found.get();
        assertThat(result.source()).isEqualTo(SOURCE);
        assertThat(result.requestId()).isEqualTo(requestId);
        assertThat(result.requestStatus()).isEqualTo("COMPLETED");
        assertThat(result.legStatus()).isEqualTo("SENT");
        assertThat(result.httpStatus()).hasValue(200);
        assertThat(result.youthSwitch()).contains("ACTIVE");
        assertThat(result.payloadSource()).contains("cache-day");
        assertThat(result.serviceVersion()).contains("test-version");
        assertThat(result.sentAt()).isPresent();
        assertThat(result.bodyDigest()).isPresent();
        assertThat(result.body()).contains(body);
        assertThat(sha256(result.body().orElseThrow())).isEqualTo(result.bodyDigest().orElseThrow());
    }

    @Test
    @DisplayName("returns empty when no request matches the hearing id and day")
    void returns_empty_when_nothing_matches() {
        assertThat(repository.lookup(UUID.randomUUID(), LocalDate.parse("2026-08-19"))).isEmpty();
    }

    @Test
    @DisplayName("answers with the latest share when a hearing was re-shared the same day, instead "
            + "of throwing on more than one matching request (S10)")
    void returns_the_latest_share_when_resent_the_same_day() {
        final UUID hearingId = UUID.randomUUID();
        final LocalDate hearingDay = LocalDate.parse("2026-08-19");
        final UUID earlierRequestId = UUID.randomUUID();
        final UUID laterRequestId = UUID.randomUUID();
        insertCompletedSentLeg(requestKey(earlierRequestId, hearingId, hearingDay),
                UUID.randomUUID(), "{\"hearing\":{\"shared\":\"first\"}}",
                Instant.parse("2026-08-19T09:00:00Z"));
        insertCompletedSentLeg(requestKey(laterRequestId, hearingId, hearingDay), UUID.randomUUID(),
                "{\"hearing\":{\"shared\":\"second\"}}", Instant.parse("2026-08-19T15:00:00Z"));

        final Optional<SupportLookupResult> found = repository.lookup(hearingId, hearingDay);

        assertThat(found).isPresent();
        assertThat(found.get().requestId()).isEqualTo(laterRequestId);
        assertThat(found.get().body()).contains("{\"hearing\":{\"shared\":\"second\"}}");
    }

    private record RequestKey(UUID requestId, UUID hearingId, LocalDate hearingDay) {
    }

    private RequestKey requestKey(final UUID requestId, final UUID hearingId, final LocalDate day) {
        return new RequestKey(requestId, hearingId, day);
    }

    private void insertCompletedSentLeg(final RequestKey key, final UUID legId, final String body) {
        insertCompletedSentLeg(key, legId, body, Instant.now());
    }

    private void insertCompletedSentLeg(final RequestKey key, final UUID legId, final String body,
                                        final Instant sharedTime) {
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, "
                        + "completion_reason, command) VALUES (:source, :requestId, :hearingId, "
                        + ":hearingDay, :sharedTime, 'Hearing_Resulted', 'fp', 'COMPLETED', 1, "
                        + "'both-legs-terminal', '{}')")
                .param("source", SOURCE)
                .param("requestId", key.requestId())
                .param("hearingId", key.hearingId())
                .param("hearingDay", key.hearingDay())
                .param("sharedTime", OffsetDateTime.ofInstant(sharedTime, ZoneOffset.UTC))
                .update();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, attempts, http_status, body_digest, "
                        + "payload_source, service_version, youth_switch, sent_at, updated_at) "
                        + "VALUES (:id, :source, :requestId, 'HMPPS', 1, 'queue', 'SENT', 1, 200, "
                        + "encode(sha256(convert_to(:body, 'UTF8')), 'hex'), 'cache-day', "
                        + "'test-version', 'ACTIVE', now(), now())")
                .param("id", legId)
                .param("source", SOURCE)
                .param("requestId", key.requestId())
                .param("body", body)
                .update();
        jdbc().sql("INSERT INTO outbound_body (outbound_message_id, body) VALUES (:id, :body)")
                .param("id", legId)
                .param("body", body)
                .update();
    }

    private static String sha256(final String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (final java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
