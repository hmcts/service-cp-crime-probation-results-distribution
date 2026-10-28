package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.micrometer.core.instrument.Counter;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome.Ran;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome.Refused;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;

/**
 * The forced resend (slice 004, US5; FR-004-036–FR-004-038, FR-004-006; design H9 (c), A3-36). With
 * {@code allowTerminalSuccess} a leg whose latest row is {@code SENT} or {@code NOT_APPLICABLE} is
 * run again on a new {@code resend} attempt row exactly as any resend — re-fetched,
 * {@code sharedTime}-checked, the switch re-read, re-redacted, digested and sent — recording the
 * operator, {@code override = true} and {@code override=force-resend}, with the sanitised reason
 * appended to {@code audit_note}; the request stays {@code COMPLETED} whatever the outcome, and the
 * outcome is counted and logged {@code forced=true}. Without the override such a leg is
 * {@code not-eligible}; for a {@code FAILED} leg the override is simply not used.
 */
@DisplayName("the forced resend")
class ForcedResendIT extends ResendTestSupport {

    private static final String LATER = "2026-08-19T16:45:00Z";
    private static final String FORCE_RESEND = "override=force-resend";
    private static final String COMPLETED = "COMPLETED";
    private static final String STATUS = "status";
    private static final String OUTCOMES = "probation_leg_outcomes_total";

    private final Logger runnerLogger = (Logger) LoggerFactory.getLogger(DistributionRunner.class);
    private final Logger resenderLogger = (Logger) LoggerFactory.getLogger(LegResender.class);
    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();

    @BeforeEach
    void attachAppender() {
        appender.start();
        runnerLogger.addAppender(appender);
        resenderLogger.addAppender(appender);
    }

    @AfterEach
    void detachAppender() {
        runnerLogger.detachAppender(appender);
        resenderLogger.detachAppender(appender);
    }

    @Nested
    @DisplayName("without the override (FR-004-036)")
    class WithoutOverride {

        @Test
        @DisplayName("a SENT latest row is not-eligible and nothing is sent")
        void sent_latest_row_is_not_eligible() {
            final UUID requestId = seedCompletedSent(SHARED_TIME);

            final ResendRunResult result = resendOnce(requestId, false, false, 200);

            assertThat(entryFor(result, requestId))
                    .contains(new Refused(ResendRefusal.NOT_ELIGIBLE));
            assertThat(attemptCount(requestId)).isEqualTo(1);
            assertThat(gatewayPostCount()).isZero();
        }

        @Test
        @DisplayName("a NOT_APPLICABLE latest row is not-eligible and nothing is sent")
        void not_applicable_latest_row_is_not_eligible() {
            final UUID requestId = seedCompletedNotApplicable(payload(SHARED_TIME));

            final ResendRunResult result = resendOnce(requestId, false, false, 200);

            assertThat(entryFor(result, requestId))
                    .contains(new Refused(ResendRefusal.NOT_ELIGIBLE));
            assertThat(attemptCount(requestId)).isEqualTo(1);
            assertThat(gatewayPostCount()).isZero();
        }
    }

    @Nested
    @DisplayName("with allowTerminalSuccess (FR-004-036–FR-004-038)")
    class WithOverride {

        @Test
        @DisplayName("a SENT leg is run again on a new resend row, audited, the request left COMPLETED")
        void sent_leg_is_run_again_as_a_forced_resend() {
            final UUID requestId = seedCompletedSent(SHARED_TIME);

            final ResendRunResult result = resendOnce(requestId, true, false, 200);

            assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("attempt")).isEqualTo(2);
            assertThat(row.get("attempt_origin")).isEqualTo("resend");
            assertThat(row.get(STATUS)).isEqualTo("SENT");
            assertThat(row.get("operator")).isEqualTo(OPERATOR);
            assertThat(row.get("override")).isEqualTo(true);
            assertThat(row.get("note")).isEqualTo(FORCE_RESEND);
            assertThat(row.get("youth_switch")).isEqualTo("ACTIVE");
            assertThat(row.get("body_digest")).isNotNull();
            assertThat(usersGroupsReadCount()).as("the switch is re-read").isEqualTo(1);
            assertThat(gatewayPostCount()).as("a genuine duplicate is sent").isEqualTo(1);
            assertRequestStillCompleted(requestId);
            assertThat((String) requestRow(requestId).get("audit_note")).contains(REASON);
            assertThat(legOutcomes("true", "SENT")).isEqualTo(1.0d);
            assertThat(legOutcomes("false", "SENT")).isZero();
            assertThat(outcomeLine().getFormattedMessage()).contains("forced=true", "runOrigin=RESEND");
            assertThat(reportLine()).contains("allowTerminalSuccess=true").doesNotContain(REASON);
        }

        @Test
        @DisplayName("a NOT_APPLICABLE leg has its rules run again, the outcome what they now give")
        void not_applicable_leg_is_run_again() {
            final UUID requestId = seedCompletedNotApplicable(payload(SHARED_TIME));

            final ResendRunResult result = resendOnce(requestId, true, false, 200);

            assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            assertThat(latestAttempt(requestId).get("note")).isEqualTo(FORCE_RESEND);
            assertThat(gatewayPostCount()).isEqualTo(1);
            assertRequestStillCompleted(requestId);
            assertThat(legOutcomes("true", "SENT")).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("a NOT_APPLICABLE leg the rules still empty stays NOT_APPLICABLE, labelled forced")
        void not_applicable_leg_still_emptied_stays_not_applicable() {
            final UUID requestId = seedCompletedNotApplicable("{\"sharedTime\":\"" + SHARED_TIME
                    + "\",\"hearing\":{\"isSJPHearing\":true,"
                    + "\"prosecutionCases\":[{\"defendants\":[{\"offences\":[]}]}]}}");

            final ResendRunResult result = resendOnce(requestId, true, false, 200);

            assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.NOT_APPLICABLE,
                    Optional.of(LegReason.SJP_OR_BOX_HEARING)));
            assertThat(latestAttempt(requestId).get("attempt")).isEqualTo(2);
            assertThat(latestAttempt(requestId).get("override")).isEqualTo(true);
            assertThat(gatewayPostCount()).isZero();
            assertRequestStillCompleted(requestId);
            assertThat(legOutcomes("true", "NOT_APPLICABLE")).isEqualTo(1.0d);
        }

        @Test
        @DisplayName("the reason is sanitised to one line before it is appended to audit_note")
        void reason_is_sanitised_to_one_line() {
            final UUID requestId = seedCompletedSent(SHARED_TIME);
            stubYouthSwitchActive();
            stubGateway(200);

            resender().resend(new ResendRequest(List.of(requestId), OPERATOR,
                    "first line\nsecond line\t\u0007end", false, true));

            final String auditNote = (String) requestRow(requestId).get("audit_note");
            assertThat(auditNote).contains("first line second line end")
                    .doesNotContain("first line\n").doesNotContain("\u0007");
        }

        @Test
        @DisplayName("a changed sharedTime without allow-changed-payload fails payload-changed")
        void changed_shared_time_fails_payload_changed() {
            final UUID requestId = seedCompletedSent(LATER);

            final ResendRunResult result = resendOnce(requestId, true, false, 200);

            assertThat(entryFor(result, requestId))
                    .contains(new Ran(LegStatus.FAILED, Optional.of(LegReason.PAYLOAD_CHANGED)));
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("attempt")).isEqualTo(2);
            assertThat(row.get("override")).isEqualTo(true);
            assertThat(row.get("note")).isEqualTo(FORCE_RESEND);
            assertThat(gatewayPostCount()).isZero();
            assertRequestStillCompleted(requestId);
        }

        @Test
        @DisplayName("a changed sharedTime with allow-changed-payload is sent, the note holding both forms")
        void changed_shared_time_with_allow_changed_payload_records_both_forms() {
            final UUID requestId = seedCompletedSent(LATER);

            final ResendRunResult result = resendOnce(requestId, true, true, 200);

            assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("override")).isEqualTo(true);
            assertThat(row.get("note"))
                    .isEqualTo("override=force-resend;override=allow-changed-payload");
            assertThat(gatewayPostCount()).isEqualTo(1);
            assertRequestStillCompleted(requestId);
        }

        @Test
        @DisplayName("a forced resend that fails leaves the request COMPLETED and the leg eligible again")
        void failed_forced_resend_leaves_request_completed_and_leg_eligible() {
            final UUID requestId = seedCompletedSent(SHARED_TIME);

            final ResendRunResult forced = resendOnce(requestId, true, false, 400);

            assertThat(entryFor(forced, requestId))
                    .contains(new Ran(LegStatus.FAILED, Optional.of(LegReason.RECEIVER_REJECTED)));
            assertThat(latestAttempt(requestId).get(STATUS)).isEqualTo("FAILED");
            assertRequestStillCompleted(requestId);
            assertThat(legOutcomes("true", "FAILED")).isEqualTo(1.0d);
            final ILoggingEvent failedLine = outcomeLine();
            assertThat(failedLine.getLevel()).isEqualTo(Level.ERROR);
            assertThat(failedLine.getFormattedMessage()).contains("forced=true");

            stubGateway(200);
            final ResendRunResult again = resender().resend(request(false, requestId));

            assertThat(entryFor(again, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("attempt")).isEqualTo(3);
            assertThat(row.get("override")).isEqualTo(false);
            assertThat(row.get("note")).isNull();
            assertRequestStillCompleted(requestId);
        }
    }

    @Nested
    @DisplayName("the override applies per ID (FR-004-037)")
    class PerId {

        @Test
        @DisplayName("for a FAILED leg the override is not used: no override, no note, no reason line")
        void failed_leg_is_a_normal_resend() {
            final UUID forcedId = seedCompletedSent(SHARED_TIME);
            final UUID failedId = UUID.randomUUID();
            final UUID hearingId = UUID.randomUUID();
            putDatedCachePayload(hearingId, HEARING_DAY, payload(SHARED_TIME));
            seedRequestRow(failedId, hearingId, "FAILED", "leg-exhausted");
            seedBareAttemptOne(failedId, "FAILED", "payload-unavailable");
            stubYouthSwitchActive();
            stubGateway(200);

            final ResendRunResult result = resender().resend(new ResendRequest(
                    List.of(failedId, forcedId), OPERATOR, REASON, false, true));

            assertThat(entryFor(result, failedId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            assertThat(entryFor(result, forcedId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            final Map<String, Object> row = latestAttempt(failedId);
            assertThat(row.get("override")).isEqualTo(false);
            assertThat(row.get("note")).isNull();
            assertThat((String) requestRow(failedId).get("audit_note")).doesNotContain(REASON);
            assertThat(latestAttempt(forcedId).get("note")).isEqualTo(FORCE_RESEND);
            assertThat(legOutcomes("false", "SENT")).isEqualTo(1.0d);
            assertThat(legOutcomes("true", "SENT")).isEqualTo(1.0d);
        }
    }

    private UUID seedCompletedSent(final String payloadSharedTime) {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(payloadSharedTime));
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, hearingId, COMPLETED, null);
        seedSentAttemptOne(requestId, "SENT", null, 200);
        return requestId;
    }

    private UUID seedCompletedNotApplicable(final String cachedPayload) {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, cachedPayload);
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, hearingId, COMPLETED, null);
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, payload_source, youth_switch) "
                        + "VALUES (gen_random_uuid(), :source, :requestId, 'HMPPS', 1, 'queue', "
                        + "'NOT_APPLICABLE', 'sjp-or-box-hearing', 1, 'cache-day', 'ACTIVE')")
                .param("source", SOURCE)
                .param("requestId", requestId)
                .update();
        return requestId;
    }

    private ResendRunResult resendOnce(final UUID requestId, final boolean allowTerminalSuccess,
                                       final boolean allowChangedPayload, final int gatewayStatus) {
        stubYouthSwitchActive();
        stubGateway(gatewayStatus);
        return resender().resend(new ResendRequest(List.of(requestId), OPERATOR, REASON,
                allowChangedPayload, allowTerminalSuccess));
    }

    private void assertRequestStillCompleted(final UUID requestId) {
        final Map<String, Object> request = requestRow(requestId);
        assertThat(request.get(STATUS)).isEqualTo(COMPLETED);
        assertThat(request.get("completion_reason")).isEqualTo("both-legs-terminal");
        assertThat(request.get("failure_reason")).isNull();
    }

    private double legOutcomes(final String forced, final String status) {
        return meterRegistry().find(OUTCOMES).tag("origin", "resend").tag("forced", forced)
                .tag(STATUS, status).counters().stream().mapToDouble(Counter::count).sum();
    }

    private ILoggingEvent outcomeLine() {
        return appender.list.stream()
                .filter(event -> event.getFormattedMessage().contains("HMPPS leg outcome"))
                .reduce((first, second) -> second).orElseThrow();
    }

    private String reportLine() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage)
                .filter(message -> message.contains("resend-leg report")).findFirst().orElseThrow();
    }
}
