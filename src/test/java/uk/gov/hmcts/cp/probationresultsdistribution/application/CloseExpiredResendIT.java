package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendAttempt;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcCloseExpiredResendRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * {@code close-expired-resend} end to end over the shared PostgreSQL (H40; slice 004, FR-004-059):
 * the single-row route back for a {@code PENDING} resend row a dead runner left behind, proven
 * against a real row rather than a mock, exactly as {@code RollbackFencingIT} proves the rollback
 * statement's own correctness. US3's independent test: an expired resend lease is recoverable
 * without the paused, whole-intake rollback procedure.
 */
@DisplayName("close-expired-resend over a real store")
class CloseExpiredResendIT extends ResendTestSupport {

    @Nested
    @DisplayName("the single-row recovery (US3 independent test, H40)")
    class SingleRowRecovery {

        @Test
        @DisplayName("closes an expired PENDING resend row that resend-leg still refuses "
                + "resend-in-progress, then a fresh resend-leg run proceeds to a new attempt row")
        void closes_the_expired_row_and_unblocks_a_fresh_resend() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            final UUID staleToken = seedResendRow(requestId, 2, "PENDING", null, true);

            // T038/T042 unchanged: resend-leg still refuses a PENDING resend row, live or expired.
            final ResendRunResult stillBlocked = resender().resend(request(false, requestId));
            assertThat(entryFor(stillBlocked, requestId))
                    .contains(new ResendEntryOutcome.Refused(ResendRefusal.RESEND_IN_PROGRESS));

            final CloseExpiredResendOutcome outcome = action().close(requestId, "an operator",
                    "closing an abandoned resend lease");

            assertThat(outcome).isEqualTo(new CloseExpiredResendOutcome.Done());
            final Map<String, Object> closedRow = latestAttempt(requestId);
            assertThat(closedRow.get("attempt")).isEqualTo(2);
            assertThat(closedRow.get("status")).isEqualTo("FAILED");
            assertThat(closedRow.get("reason")).isEqualTo("rolled-back");
            assertThat(closedRow.get("note")).isEqualTo("prev=PENDING/-");
            assertThat(closedRow.get("claim_token"))
                    .as("a fresh claim_token is rotated onto the row")
                    .isNotNull().isNotEqualTo(staleToken);
            final Map<String, Object> requestRow = requestRow(requestId);
            assertThat(requestRow.get("status")).as("processed_request.status is left untouched")
                    .isEqualTo("FAILED");
            assertThat(requestRow.get("failure_reason")).isEqualTo("leg-exhausted");
            assertThat((String) requestRow.get("audit_note"))
                    .contains("CLOSE_EXPIRED_RESEND operator=an operator",
                            "reason=closing an abandoned resend lease");

            // The closed row is then resend-eligible (FR-004-023): a fresh resend-leg run proceeds
            // to a new attempt row rather than refusing resend-in-progress again.
            stubYouthSwitchActive();
            stubGateway(200);
            final ResendRunResult unblocked = resender().resend(request(false, requestId));

            assertThat(entryFor(unblocked, requestId))
                    .contains(new ResendEntryOutcome.Ran(LegStatus.SENT, Optional.empty()));
            assertThat(attemptCount(requestId)).isEqualTo(3);
            assertThat(latestAttempt(requestId).get("attempt_origin")).isEqualTo("resend");
        }

        @Test
        @DisplayName("replaces any earlier prev= form while keeping every other note form "
                + "(the merge contracts/cli-runners.md §9 and JdbcRollbackFencingRepository share)")
        void replaces_an_earlier_prev_form_keeping_other_note_forms() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            seedResendRow(requestId, 2, "PENDING", null, true);
            // A row already carrying an earlier prev= form (from some prior transition) and an
            // unrelated override form this statement must leave alone.
            jdbc().sql("UPDATE outbound_message SET note = :note WHERE request_id = :requestId "
                            + "AND attempt = 2")
                    .param("note", "prev=leg-exhausted/-;override=allow-changed-payload")
                    .param("requestId", requestId)
                    .update();

            final CloseExpiredResendOutcome outcome = action().close(requestId, "an operator",
                    "closing an abandoned resend lease");

            assertThat(outcome).isEqualTo(new CloseExpiredResendOutcome.Done());
            assertThat(latestAttempt(requestId).get("note"))
                    .as("the earlier prev= is replaced, not duplicated; the override form survives")
                    .isEqualTo("prev=PENDING/-;override=allow-changed-payload");
        }

        @Test
        @DisplayName("a late runner's completion of the now-closed row under its stale claim_token "
                + "updates no row (H40; the same fencing guarantee as any other stale completion)")
        void late_completion_under_the_stale_claim_updates_nothing() {
            final UUID requestId = UUID.randomUUID();
            final UUID hearingId = cachedHearing();
            seedRequestRow(requestId, hearingId, "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            final UUID staleToken = seedResendRow(requestId, 2, "PENDING", null, true);
            final UUID attemptId = jdbc().sql(
                            "SELECT id FROM outbound_message WHERE request_id = :requestId "
                                    + "AND attempt = 2")
                    .param("requestId", requestId)
                    .query(UUID.class)
                    .single();

            final CloseExpiredResendOutcome outcome = action().close(requestId, "an operator",
                    "closing an abandoned resend lease");
            assertThat(outcome).isEqualTo(new CloseExpiredResendOutcome.Done());

            // The dead runner finally wakes up and tries to complete the row it originally claimed,
            // under its now-stale token: the guarded UPDATE (claim_token = :token AND
            // status = 'PENDING') matches nothing, because close-expired-resend has already rotated
            // the token and moved the row to FAILED.
            final ResendAttempt staleAttempt = new ResendAttempt(attemptId, 2,
                    new RunClaim(UUID.randomUUID(), staleToken), UUID.randomUUID());
            final LegOutcome lateCompletion = new LegOutcome(command(requestId, hearingId),
                    AttemptOrigin.RESEND, LegStatus.SENT, Optional.empty(), Optional.empty(),
                    Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.of(200),
                    Optional.empty());

            final int rowsUpdated = outboundMessages().writeResendOutcome(lateCompletion,
                    staleAttempt, false);

            assertThat(rowsUpdated).isZero();
            final Map<String, Object> rowAfter = latestAttempt(requestId);
            assertThat(rowAfter.get("status")).as("the close-expired-resend transition stands")
                    .isEqualTo("FAILED");
            assertThat(rowAfter.get("reason")).isEqualTo("rolled-back");
        }
    }

    @Nested
    @DisplayName("the three refusal codes, in the fixed order the statement decides them")
    class Refusals {

        @Test
        @DisplayName("unknown-request: no request row exists")
        void refuses_unknown_request() {
            final CloseExpiredResendOutcome outcome = action().close(UUID.randomUUID(),
                    "an operator", "a reason");

            assertThat(outcome)
                    .isEqualTo(new CloseExpiredResendOutcome.Refused(ResendRefusal.UNKNOWN_REQUEST));
        }

        @Test
        @DisplayName("not-eligible: the leg's latest row is not a PENDING resend row")
        void refuses_not_eligible() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");

            final CloseExpiredResendOutcome outcome = action().close(requestId, "an operator",
                    "a reason");

            assertThat(outcome)
                    .isEqualTo(new CloseExpiredResendOutcome.Refused(ResendRefusal.NOT_ELIGIBLE));
            assertThat(latestAttempt(requestId).get("status")).isEqualTo("FAILED");
        }

        @Test
        @DisplayName("resend-in-progress: the row's lease is still live")
        void refuses_resend_in_progress() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            final UUID liveToken = seedResendRow(requestId, 2, "PENDING", null, false);

            final CloseExpiredResendOutcome outcome = action().close(requestId, "an operator",
                    "a reason");

            assertThat(outcome).isEqualTo(
                    new CloseExpiredResendOutcome.Refused(ResendRefusal.RESEND_IN_PROGRESS));
            assertThat(latestAttempt(requestId).get("claim_token")).isEqualTo(liveToken);
            assertThat(latestAttempt(requestId).get("status")).isEqualTo("PENDING");
        }
    }

    private CloseExpiredResendAction action() {
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(sharedDataSource()));
        return new CloseExpiredResendAction(new JdbcCloseExpiredResendRepository(jdbc(), tx));
    }

    private OutboundMessageRepository outboundMessages() {
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(sharedDataSource()));
        final ProcessedRequestRepository processedRequests = requestStore();
        return new OutboundMessageRepository(jdbc(), tx, "test-version", processedRequests);
    }

    private UUID cachedHearing() {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(SHARED_TIME));
        return hearingId;
    }
}
