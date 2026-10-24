package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.CompletionReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.Leg;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.NoteForm;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

@Testcontainers
@DisplayName("the V1 SQL vocabularies equal the Java vocabularies")
class SchemaVocabularyIT extends SchemaTestSupport {

    @Nested
    @DisplayName("outbound_message closed value sets")
    class OutboundMessageValueSets {

        @Test
        @DisplayName("accepts every Java LegStatus and refuses an unknown status")
        void leg_status_set_matches() {
            for (final LegStatus status : LegStatus.values()) {
                assertAccepted(validRow(status, canonicalReason(status)));
            }
            assertRejected(Map.of("status", "'GONE'"));
        }

        @Test
        @DisplayName("accepts every Java LegReason under a compatible status and refuses an unknown")
        void leg_reason_set_matches() {
            for (final LegReason reason : LegReason.values()) {
                assertAccepted(validRow(statusFor(reason), reason.wireValue()));
            }
            assertRejected(Map.of("status", "'NOT_APPLICABLE'", "reason", "'unheard-of'",
                    "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'"));
        }

        @Test
        @DisplayName("refuses youth-switch-unreadable, which exists in no vocabulary")
        void youth_switch_unreadable_is_refused() {
            assertRejected(Map.of("status", "'FAILED'", "reason", "'youth-switch-unreadable'",
                    "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'"));
            assertThatThrownBy(() -> LegReason.fromValue("youth-switch-unreadable"))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("accepts every PayloadSource and YouthSwitchRecordedValue, refusing unknowns")
        void payload_source_and_youth_switch_sets_match() {
            for (final PayloadSource source : PayloadSource.values()) {
                assertAccepted(sentWith("payload_source", "'" + source.wireValue() + "'"));
            }
            for (final YouthSwitchRecordedValue value : YouthSwitchRecordedValue.values()) {
                assertAccepted(sentWith("youth_switch", "'" + value.wireValue() + "'"));
            }
            assertRejected(sentWith("payload_source", "'nowhere'"));
            assertRejected(sentWith("youth_switch", "'PERHAPS'"));
        }

        @Test
        @DisplayName("accepts every AttemptOrigin and refuses an unknown origin")
        void attempt_origin_set_matches() {
            assertAccepted(validRow(LegStatus.PENDING, null));
            final String replayReq = freshRequest();
            execute(message(replayReq, Map.of("status", "'PENDING'")));
            assertThatCode(() -> execute(message(replayReq, Map.of(
                    "attempt", "2", "attempt_origin", "'replay'", "status", "'PENDING'"))))
                    .doesNotThrowAnyException();
            final String resendReq = freshRequest();
            execute(message(resendReq, Map.of("status", "'PENDING'")));
            assertThatCode(() -> execute(message(resendReq, Map.of(
                    "attempt", "2", "attempt_origin", "'resend'", "status", "'PENDING'",
                    "claim_owner", "'o'", "claim_token", "gen_random_uuid()",
                    "claim_expires_at", "now()", "resend_run_id", "gen_random_uuid()"))))
                    .doesNotThrowAnyException();
            assertRejected(Map.of("attempt", "2", "attempt_origin", "'migrate'",
                    "status", "'PENDING'"));
        }

        @Test
        @DisplayName("accepts the one Leg value and refuses any other")
        void leg_set_matches() {
            assertThat(Arrays.stream(Leg.values()).map(Leg::wireValue).toList())
                    .containsExactly("HMPPS");
            assertRejected(Map.of("leg", "'VEP'", "status", "'PENDING'"));
        }
    }

    @Nested
    @DisplayName("processed_request closed value sets")
    class ProcessedRequestValueSets {

        @Test
        @DisplayName("accepts every CompletionReason on a COMPLETED row and refuses an unknown")
        void completion_reason_set_matches() {
            for (final CompletionReason reason : CompletionReason.values()) {
                final String req = UUID.randomUUID().toString();
                insertReceivedRequest("RESULTS", req);
                final String value = reason.wireValue();
                assertThatCode(() -> execute("UPDATE processed_request SET status = 'COMPLETED', "
                        + "completion_reason = '" + value + "' WHERE request_id = '" + req + "'"))
                        .doesNotThrowAnyException();
            }
            final String bad = UUID.randomUUID().toString();
            insertReceivedRequest("RESULTS", bad);
            assertThatThrownBy(() -> execute("UPDATE processed_request SET status = 'COMPLETED', "
                    + "completion_reason = 'finished' WHERE request_id = '" + bad + "'"))
                    .isInstanceOf(DataAccessException.class);
        }

        @Test
        @DisplayName("accepts every FailureReason on a FAILED row and refuses an unknown")
        void failure_reason_set_matches() {
            final String exhausted = UUID.randomUUID().toString();
            insertReceivedRequest("RESULTS", exhausted);
            assertThatCode(() -> execute("UPDATE processed_request SET status = 'FAILED', "
                    + "failure_reason = 'leg-exhausted', exhausted_message_id = 'RESULTS:x' "
                    + "WHERE request_id = '" + exhausted + "'")).doesNotThrowAnyException();

            final String rolledBack = UUID.randomUUID().toString();
            insertReceivedRequest("RESULTS", rolledBack);
            assertThatCode(() -> execute("UPDATE processed_request SET status = 'FAILED', "
                    + "failure_reason = 'rolled-back' WHERE request_id = '" + rolledBack + "'"))
                    .doesNotThrowAnyException();

            final String bad = UUID.randomUUID().toString();
            insertReceivedRequest("RESULTS", bad);
            assertThatThrownBy(() -> execute("UPDATE processed_request SET status = 'FAILED', "
                    + "failure_reason = 'gave-up' WHERE request_id = '" + bad + "'"))
                    .isInstanceOf(DataAccessException.class);
        }
    }

    @Nested
    @DisplayName("the note forms")
    class NoteForms {

        @Test
        @DisplayName("are exactly the seven of design section 8, two of them reserved")
        void are_the_seven_forms() {
            assertThat(Arrays.stream(NoteForm.values()).map(NoteForm::wireValue).toList())
                    .containsExactlyInAnyOrder(
                            "prev=<STATUS>/<reason>", "prev=PENDING/-", "rerun-by=<messageId>",
                            "override=allow-changed-payload", "override=force-resend",
                            "baseline=none", "contract=invalid");
            assertThat(NoteForm.BASELINE_NONE.reserved()).isTrue();
            assertThat(NoteForm.CONTRACT_INVALID.reserved()).isTrue();
        }

        @Test
        @DisplayName("keep contract-invalid reserved but permitted in the leg vocabulary")
        void contract_invalid_is_reserved_but_permitted() {
            assertAccepted(Map.of("status", "'FAILED'", "reason", "'contract-invalid'",
                    "payload_source", "'cache-day'", "youth_switch", "'ACTIVE'"));
        }
    }

    private static String canonicalReason(final LegStatus status) {
        return switch (status) {
            case PENDING, SENT -> null;
            case NOT_APPLICABLE -> "sjp-or-box-hearing";
            case PARKED -> "payload-unavailable";
            case FAILED -> "receiver-rejected";
        };
    }

    private static LegStatus statusFor(final LegReason reason) {
        return switch (reason) {
            case SJP_OR_BOX_HEARING, NOTHING_LEFT_AFTER_REDACTION -> LegStatus.NOT_APPLICABLE;
            case PAYLOAD_UNAVAILABLE, GATEWAY_TRANSIENT -> LegStatus.PARKED;
            case RECEIVER_REJECTED, RULES_EXCEPTION, CONTRACT_INVALID,
                 PAYLOAD_CHANGED, ROLLED_BACK -> LegStatus.FAILED;
        };
    }

    private static Map<String, String> validRow(final LegStatus status, final String reason) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("status", "'" + status.wireValue() + "'");
        if (reason != null) {
            row.put("reason", "'" + reason + "'");
        }
        if (status == LegStatus.SENT) {
            row.put("http_status", "200");
            row.put("body_digest", "'deadbeef'");
            row.put("service_version", "'v1'");
            row.put("payload_source", "'cache-day'");
            row.put("youth_switch", "'ACTIVE'");
            row.put("sent_at", "now()");
        } else if (status == LegStatus.NOT_APPLICABLE) {
            row.put("payload_source", "'cache-day'");
            row.put("youth_switch", "'ACTIVE'");
        } else if (status == LegStatus.PARKED || status == LegStatus.FAILED) {
            applyReasonEvidence(row, reason);
        }
        return row;
    }

    private static void applyReasonEvidence(final Map<String, String> row, final String reason) {
        final String value = reason == null ? "" : reason;
        final boolean rejected = "receiver-rejected".equals(value);
        final boolean withBody = rejected || "gateway-transient".equals(value);
        final boolean changedOnly = "payload-changed".equals(value);
        final boolean minimalExempt = "payload-unavailable".equals(value)
                || "rolled-back".equals(value);
        if (withBody) {
            row.put("payload_source", "'cache-day'");
            row.put("youth_switch", "'ACTIVE'");
            row.put("body_digest", "'deadbeef'");
            row.put("service_version", "'v1'");
            row.put("http_status", rejected ? "400" : "503");
        } else if (changedOnly) {
            row.put("payload_source", "'cache-day'");
        } else if (!minimalExempt) {
            row.put("payload_source", "'cache-day'");
            row.put("youth_switch", "'ACTIVE'");
        }
    }

    private static Map<String, String> sentWith(final String column, final String value) {
        final Map<String, String> row = validRow(LegStatus.SENT, null);
        row.put(column, value);
        return row;
    }

    private void assertAccepted(final Map<String, String> cols) {
        final String req = freshRequest();
        assertThatCode(() -> execute(message(req, cols)))
                .as("row %s should be accepted", cols)
                .doesNotThrowAnyException();
    }

    private void assertRejected(final Map<String, String> cols) {
        final String req = freshRequest();
        assertThatThrownBy(() -> execute(message(req, cols)))
                .as("row %s should be rejected", cols)
                .isInstanceOf(DataAccessException.class);
    }

    private String freshRequest() {
        final String req = UUID.randomUUID().toString();
        insertReceivedRequest("RESULTS", req);
        return req;
    }

    private static String message(final String requestId, final Map<String, String> cols) {
        final Map<String, String> row = new LinkedHashMap<>();
        row.put("id", "gen_random_uuid()");
        row.put("source", "'RESULTS'");
        row.put("request_id", "'" + requestId + "'");
        row.put("leg", "'HMPPS'");
        row.put("attempt", "1");
        row.put("attempt_origin", "'queue'");
        row.putAll(cols);
        final String names = String.join(", ", row.keySet());
        final String values = String.join(", ", row.values());
        return "INSERT INTO outbound_message (" + names + ") VALUES (" + values + ")";
    }
}
