package uk.gov.hmcts.cp.probationresultsdistribution.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome.Ran;
import uk.gov.hmcts.cp.probationresultsdistribution.application.ResendEntryOutcome.Refused;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.ResendRefusal;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.WholeRunRefusal;

/**
 * Resend-leg for an explicit list of {@code requestId}s (slice 004, US3; FR-004-018–FR-004-025,
 * FR-004-039; the resend halves of DV-07 and DV-17, FR-004-050a). Each distinct ID is checked,
 * claimed and run on its own, in the fixed refusal order {@code unknown-request} →
 * {@code request-claimed} → {@code resend-in-progress} → {@code not-eligible}; the new attempt row
 * is committed with its claim before any POST; a resend never parks.
 */
@DisplayName("resend-leg for an explicit list of requestIds")
class ResendByRequestIdsIT extends ResendTestSupport {

    @Nested
    @DisplayName("the mixed-list scenario (US3 independent test)")
    class MixedList {

        @Test
        @DisplayName("two FAILED legs resent, one SENT, one claimed and one unknown refused by code")
        void mixed_list_resends_the_eligible_and_refuses_the_rest() {
            final UUID failedA = seedFailedPayloadUnavailable();
            final UUID failedB = seedFailedPayloadUnavailable();
            final UUID sent = UUID.randomUUID();
            seedRequestRow(sent, cachedHearing(), "COMPLETED", null);
            seedSentAttemptOne(sent, "SENT", null, 200);
            final UUID claimed = UUID.randomUUID();
            seedRequestRow(claimed, cachedHearing(), "RECEIVED", null, true);
            seedBareAttemptOne(claimed, "PENDING", null);
            final UUID unknown = UUID.randomUUID();
            stubYouthSwitchActive();
            stubGateway(200);

            final ResendRunResult result = resender().resend(
                    request(false, failedA, sent, claimed, failedB, unknown));

            assertThat(result.wholeRunRefusal()).isEmpty();
            assertThat(result.resendRunId()).isPresent();
            assertThat(result.entries()).extracting(ResendRunResult.Entry::requestId)
                    .containsExactly(failedA, sent, claimed, failedB, unknown);
            assertThat(entryFor(result, failedA)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            assertThat(entryFor(result, failedB)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            assertThat(entryFor(result, sent)).contains(new Refused(ResendRefusal.NOT_ELIGIBLE));
            assertThat(entryFor(result, claimed)).contains(new Refused(ResendRefusal.REQUEST_CLAIMED));
            assertThat(entryFor(result, unknown)).contains(new Refused(ResendRefusal.UNKNOWN_REQUEST));
            assertThat(gatewayPostCount()).isEqualTo(2);
            for (final UUID resent : List.of(failedA, failedB)) {
                final Map<String, Object> row = latestAttempt(resent);
                assertThat(row.get("attempt")).isEqualTo(2);
                assertThat(row.get("attempt_origin")).isEqualTo("resend");
                assertThat(row.get("status")).isEqualTo("SENT");
                assertThat(row.get("resend_run_id")).isEqualTo(result.resendRunId().orElseThrow());
                assertThat(row.get("operator")).isEqualTo(OPERATOR);
                assertThat(row.get("claim_token")).as("the claim stays as the record").isNotNull();
            }
            assertThat(attemptCount(sent)).isEqualTo(1);
            assertThat(attemptCount(claimed)).isEqualTo(1);
            assertThat(refusals("not-eligible")).isEqualTo(1.0d);
            assertThat(refusals("request-claimed")).isEqualTo(1.0d);
            assertThat(refusals("unknown-request")).isEqualTo(1.0d);
        }
    }

    @Nested
    @DisplayName("the run's input (FR-004-019, FR-004-020)")
    class Input {

        @Test
        @DisplayName("duplicates are collapsed and run once")
        void duplicates_are_collapsed() {
            final UUID failed = seedFailedPayloadUnavailable();
            stubYouthSwitchActive();
            stubGateway(200);

            final ResendRunResult result = resender().resend(request(false, failed, failed, failed));

            assertThat(result.entries()).hasSize(1);
            assertThat(attemptCount(failed)).isEqualTo(2);
            assertThat(gatewayPostCount()).isEqualTo(1);
        }

        @Test
        @DisplayName("an empty list is refused whole missing-argument")
        void empty_list_is_refused() {
            final ResendRunResult result = resender().resend(request(false));

            assertThat(result.wholeRunRefusal()).contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(result.entries()).isEmpty();
        }

        @Test
        @DisplayName("more distinct ids than the cap is refused whole before any read")
        void over_cap_is_refused_before_any_read() {
            final UUID failed = seedFailedPayloadUnavailable();
            stubGateway(200);

            final ResendRunResult result = resender(sharedRedisConnection(), 2, () -> false, null)
                    .resend(request(false, failed, UUID.randomUUID(), UUID.randomUUID(), failed));

            assertThat(result.wholeRunRefusal()).contains(WholeRunRefusal.TOO_MANY_REQUEST_IDS);
            assertThat(attemptCount(failed)).isEqualTo(1);
            assertThat(gatewayPostCount()).isZero();
        }

        @Test
        @DisplayName("a missing or over-long operator or reason is refused whole")
        void operator_and_reason_are_checked() {
            final UUID failed = seedFailedPayloadUnavailable();
            final LegResender resender = resender();

            assertThat(resender.resend(new ResendRequest(List.of(failed), " ", REASON, false))
                    .wholeRunRefusal()).contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(resender.resend(new ResendRequest(List.of(failed), OPERATOR, null, false))
                    .wholeRunRefusal()).contains(WholeRunRefusal.MISSING_ARGUMENT);
            assertThat(resender.resend(new ResendRequest(List.of(failed), "o".repeat(201), REASON,
                    false)).wholeRunRefusal()).contains(WholeRunRefusal.ARGUMENT_TOO_LONG);
            assertThat(resender.resend(new ResendRequest(List.of(failed), OPERATOR, "r".repeat(201),
                    false)).wholeRunRefusal()).contains(WholeRunRefusal.ARGUMENT_TOO_LONG);
            assertThat(attemptCount(failed)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("the per-ID check order and the eligibility table (FR-004-022, FR-004-023)")
    class CheckOrder {

        @Test
        @DisplayName("request-claimed is refused before resend-in-progress")
        void request_claimed_before_resend_in_progress() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "RECEIVED", null, true);
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            seedResendRow(requestId, 2, "PENDING", null, false);

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId))
                    .contains(new Refused(ResendRefusal.REQUEST_CLAIMED));
        }

        @Test
        @DisplayName("a live PENDING resend row is refused resend-in-progress, not not-eligible")
        void live_pending_resend_row_is_resend_in_progress() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            seedResendRow(requestId, 2, "PENDING", null, false);

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId))
                    .contains(new Refused(ResendRefusal.RESEND_IN_PROGRESS));
        }

        @Test
        @DisplayName("an attempt-1 FAILED(rolled-back) row is not-eligible; replay re-runs it")
        void attempt_one_rolled_back_is_not_eligible() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "FAILED", "rolled-back");
            seedBareAttemptOne(requestId, "FAILED", "rolled-back");

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId)).contains(new Refused(ResendRefusal.NOT_ELIGIBLE));
            assertThat(attemptCount(requestId)).isEqualTo(1);
        }

        @Test
        @DisplayName("an attempt-2 FAILED(rolled-back) resend row is eligible")
        void attempt_two_rolled_back_is_eligible() {
            final UUID hearingId = cachedHearing();
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, hearingId, "FAILED", "rolled-back");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            seedResendRow(requestId, 2, "FAILED", "rolled-back", true);
            stubYouthSwitchActive();
            stubGateway(200);

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            assertThat(latestAttempt(requestId).get("attempt")).isEqualTo(3);
        }

        @Test
        @DisplayName("a PARKED latest row is not-eligible")
        void parked_is_not_eligible() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "RETRYING", null);
            seedBareAttemptOne(requestId, "PARKED", "payload-unavailable");

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId)).contains(new Refused(ResendRefusal.NOT_ELIGIBLE));
        }

        @Test
        @DisplayName("a PENDING attempt 1 under an expired request claim is not-eligible")
        void pending_attempt_one_expired_claim_is_not_eligible() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "RETRYING", null);
            seedBareAttemptOne(requestId, "PENDING", null);

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId)).contains(new Refused(ResendRefusal.NOT_ELIGIBLE));
        }

        @Test
        @DisplayName("an expired PENDING resend row is refused resend-in-progress, never reclaimed (T042)")
        void expired_pending_resend_row_is_never_reclaimed() {
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            final UUID token = seedResendRow(requestId, 2, "PENDING", null, true);
            stubGateway(200);

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId))
                    .contains(new Refused(ResendRefusal.RESEND_IN_PROGRESS));
            final Map<String, Object> row = latestAttempt(requestId);
            assertThat(row.get("attempt")).isEqualTo(2);
            assertThat(row.get("status")).isEqualTo("PENDING");
            assertThat(row.get("claim_token")).isEqualTo(token);
            assertThat(attemptCount(requestId)).isEqualTo(2);
            assertThat(gatewayPostCount()).isZero();
            assertThat(refusals("resend-in-progress")).isEqualTo(1.0d);
        }
    }

    @Nested
    @DisplayName("the claim is committed before any POST (FR-004-025)")
    class ClaimBeforePost {

        @Test
        @DisplayName("the PENDING resend row, its claim, run id and operator are committed before the POST")
        void attempt_row_is_committed_before_the_post() {
            final UUID failed = seedFailedPayloadUnavailable();
            stubYouthSwitchActive();
            stubGateway(200);
            final List<Map<String, Object>> seenAtSend = new ArrayList<>();
            final HmppsSender real = publisher(new uk.gov.hmcts.cp.probationresultsdistribution
                    .config.MetricRegistry(meterRegistry()));
            final HmppsSender observing = redacted -> {
                // A separate, auto-committing connection sees only committed rows.
                seenAtSend.add(latestAttempt(failed));
                return real.send(redacted);
            };

            resender(sharedRedisConnection(), 100, () -> false, observing)
                    .resend(request(false, failed));

            assertThat(seenAtSend).hasSize(1);
            final Map<String, Object> atSend = seenAtSend.get(0);
            assertThat(atSend.get("attempt")).isEqualTo(2);
            assertThat(atSend.get("status")).isEqualTo("PENDING");
            assertThat(atSend.get("attempt_origin")).isEqualTo("resend");
            assertThat(atSend.get("claim_owner")).isNotNull();
            assertThat(atSend.get("claim_token")).isNotNull();
            assertThat(atSend.get("claim_expires_at")).isNotNull();
            assertThat(atSend.get("resend_run_id")).isNotNull();
            assertThat(atSend.get("operator")).isEqualTo(OPERATOR);
        }
    }

    @Nested
    @DisplayName("the resend halves of DV-07 and DV-17 (FR-004-050a)")
    class RegisteredDeviations {

        @Test
        @DisplayName("DV-07: a Redis failure falls back to the query API and fails payload-unavailable, never parks")
        void redis_failure_falls_back_and_fails_without_parking() {
            final UUID hearingId = UUID.randomUUID();
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, hearingId, "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            stubResultsQuery(hearingId, 404, "{}");
            final RedisClient client = RedisClient.create("redis://" + redisHost() + ":" + redisPort());
            final StatefulRedisConnection<String, String> broken = client.connect();
            broken.close();
            try {
                final ResendRunResult result = resender(broken, 100, () -> false, null)
                        .resend(request(false, requestId));

                assertThat(entryFor(result, requestId)).contains(
                        new Ran(LegStatus.FAILED, Optional.of(LegReason.PAYLOAD_UNAVAILABLE)));
                final Map<String, Object> row = latestAttempt(requestId);
                assertThat(row.get("attempt")).isEqualTo(2);
                assertThat(row.get("status")).isEqualTo("FAILED");
                assertThat(row.get("reason")).isEqualTo("payload-unavailable");
                assertThat(resultsQueryUserHeaders(hearingId)).as("the query API was read").hasSize(1);
            } finally {
                client.shutdown();
            }
        }

        @Test
        @DisplayName("DV-17: every resend's query-API read carries the system user, whatever the stored userId")
        void query_read_carries_the_system_user() {
            final UUID hearingId = UUID.randomUUID();
            final UUID requestId = UUID.randomUUID();
            seedRequestRow(requestId, hearingId, "FAILED", "leg-exhausted");
            seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
            stubResultsQuery(hearingId, 200, payload(SHARED_TIME));
            stubYouthSwitchActive();
            stubGateway(200);

            final ResendRunResult result = resender().resend(request(false, requestId));

            assertThat(entryFor(result, requestId)).contains(new Ran(LegStatus.SENT, Optional.empty()));
            assertThat(latestAttempt(requestId).get("payload_source")).isEqualTo("query");
            assertThat(resultsQueryUserHeaders(hearingId)).containsExactly(SYSTEM_USER_ID);
        }
    }

    private UUID seedFailedPayloadUnavailable() {
        final UUID requestId = UUID.randomUUID();
        seedRequestRow(requestId, cachedHearing(), "FAILED", "leg-exhausted");
        seedBareAttemptOne(requestId, "FAILED", "payload-unavailable");
        return requestId;
    }

    private UUID cachedHearing() {
        final UUID hearingId = UUID.randomUUID();
        putDatedCachePayload(hearingId, HEARING_DAY, payload(SHARED_TIME));
        return hearingId;
    }

    private double refusals(final String reason) {
        final io.micrometer.core.instrument.Counter counter = meterRegistry()
                .find("probation_resend_refusals_total").tag("reason", reason).counter();
        return counter == null ? 0.0d : counter.count();
    }
}
