package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome.Ran;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;

/**
 * The resend {@code sharedTime} check (slice 004, FR-004-029–FR-004-031; design H17, A3-57). Directly
 * after the payload fetch and before any leg stage, the payload's {@code sharedTime} is compared with
 * the request's stored {@code shared_time} as instants — the ISO-8601 string parsed honouring its
 * offset or zone, compared at the stored precision. Equal proceeds; different, absent or
 * unparseable is {@code FAILED(payload-changed)} with no switch read and nothing sent, unless
 * {@code allow-changed-payload} lets it through with {@code override = true} and its note form.
 */
@DisplayName("the resend sharedTime check")
class ResendSharedTimeCheckIT extends ResendTestSupport {

    private static final String LATER = "2026-08-19T16:45:00Z";

    @Nested
    @DisplayName("an equal instant proceeds (FR-004-029)")
    class Proceeds {

        @Test
        @DisplayName("the stored rendering proceeds and records payload_shared_time")
        void same_rendering_proceeds() {
            final UUID requestId = seedFailedWithCachedSharedTime(SHARED_TIME);

            final ResendRunResult result = resendOnce(requestId, false);

            assertSent(result, requestId);
            assertThat(instantOf(latestAttempt(requestId).get("payload_shared_time")))
                    .isEqualTo(OffsetDateTime.parse(SHARED_TIME).toInstant());
            assertThat(latestAttempt(requestId).get("override")).isEqualTo(false);
            assertThat(latestAttempt(requestId).get("note")).isNull();
        }

        @Test
        @DisplayName("a different offset rendering of the same instant still proceeds")
        void offset_rendering_proceeds() {
            final UUID requestId = seedFailedWithCachedSharedTime("2026-08-19T17:42:07.512+01:00");

            assertSent(resendOnce(requestId, false), requestId);
        }

        @Test
        @DisplayName("a rendering that names a zone still proceeds")
        void zone_rendering_proceeds() {
            final UUID requestId =
                    seedFailedWithCachedSharedTime("2026-08-19T17:42:07.512+01:00[Europe/London]");

            assertSent(resendOnce(requestId, false), requestId);
        }

        @Test
        @DisplayName("a finer rendering equal at the stored precision still proceeds")
        void finer_precision_proceeds() {
            final UUID requestId = seedFailedWithCachedSharedTime("2026-08-19T16:42:07.512000400Z");

            assertSent(resendOnce(requestId, false), requestId);
        }
    }

    @Nested
    @DisplayName("a changed, absent or unparseable sharedTime fails payload-changed (FR-004-029, FR-004-030)")
    class Changed {

        @Test
        @DisplayName("a later sharedTime fails payload-changed, reading no switch and sending nothing")
        void later_shared_time_fails() {
            final UUID requestId = seedFailedWithCachedSharedTime(LATER);

            final ResendRunResult result = resendOnce(requestId, false);

            assertPayloadChanged(result, requestId);
            assertThat(instantOf(latestAttempt(requestId).get("payload_shared_time")))
                    .isEqualTo(OffsetDateTime.parse(LATER).toInstant());
        }

        @Test
        @DisplayName("an absent sharedTime fails payload-changed")
        void absent_shared_time_fails() {
            final UUID requestId = seedFailedWithCachedSharedTime(null);

            assertPayloadChanged(resendOnce(requestId, false), requestId);
            assertThat(latestAttempt(requestId).get("payload_shared_time")).isNull();
        }

        @Test
        @DisplayName("an unparseable sharedTime fails payload-changed")
        void unparseable_shared_time_fails() {
            final UUID requestId = seedFailedWithCachedSharedTime("not-a-time");

            assertPayloadChanged(resendOnce(requestId, false), requestId);
            assertThat(latestAttempt(requestId).get("payload_shared_time")).isNull();
        }
    }

    @Nested
    @DisplayName("allow-changed-payload (FR-004-031)")
    class AllowChangedPayload {

        @Test
        @DisplayName("lets a changed sharedTime through, recording override=true and its note form")
        void override_lets_a_change_through() {
            final UUID requestId = seedFailedWithCachedSharedTime(LATER);

            assertSent(resendOnce(requestId, true), requestId);
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("override")).isEqualTo(true);
            assertThat(row.get("note")).isEqualTo("override=allow-changed-payload");
        }

        @Test
        @DisplayName("is not recorded when the sharedTime matched")
        void override_unused_is_not_recorded() {
            final UUID requestId = seedFailedWithCachedSharedTime(SHARED_TIME);

            assertSent(resendOnce(requestId, true), requestId);
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("override")).isEqualTo(false);
            assertThat(row.get("note")).isNull();
        }
    }

    private static Instant instantOf(final Object column) {
        return column instanceof OffsetDateTime offset ? offset.toInstant()
                : ((Timestamp) column).toInstant();
    }

    private UUID seedFailedWithCachedSharedTime(final String payloadSharedTime) {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(payloadSharedTime));
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, hearingId, "FAILED", "leg-exhausted");
        seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
        return requestId;
    }

    private ResendRunResult resendOnce(final UUID requestId, final boolean allowChangedPayload) {
        stubYouthSwitchActive();
        stubGateway(200);
        return resender().resend(request(allowChangedPayload, requestId));
    }

    private void assertSent(final ResendRunResult result, final UUID requestId) {
        assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
        assertThat(gatewayPostCount()).isEqualTo(1);
    }

    private void assertPayloadChanged(final ResendRunResult result, final UUID requestId) {
        assertThat(entryFor(result, requestId))
                .contains(new Ran(LegStatus.FAILED, Optional.of(LegReason.PAYLOAD_CHANGED)));
        final Map<String, Object> row = latestAttempt(requestId);
        assertThat(row.get("attempt")).isEqualTo(2);
        assertThat(row.get("status")).isEqualTo("FAILED");
        assertThat(row.get("reason")).isEqualTo("payload-changed");
        assertThat(row.get("payload_source")).isEqualTo("cache-day");
        assertThat(row.get("youth_switch")).isNull();
        assertThat(row.get("body_digest")).isNull();
        assertThat(row.get("service_version")).isNull();
        assertThat(row.get("override")).isEqualTo(false);
        assertThat(gatewayPostCount()).as("nothing is sent").isZero();
        assertThat(usersGroupsReadCount()).as("no switch read").isZero();
        final Map<String, Object> request = requestRow(requestId);
        assertThat(request.get("status")).isEqualTo("FAILED");
        assertThat(request.get("failure_reason")).isEqualTo("leg-exhausted");
        assertThat((String) request.get("audit_note")).contains("payload-changed")
                .doesNotContain("2026-08-19T16:45").doesNotContain("not-a-time")
                .doesNotContain("prosecutionCases");
    }
}
