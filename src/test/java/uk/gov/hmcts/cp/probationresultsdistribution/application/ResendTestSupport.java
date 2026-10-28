package uk.gov.hmcts.cp.probationresultsdistribution.application;

import io.lettuce.core.api.StatefulRedisConnection;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import uk.gov.hmcts.cp.probationresultsdistribution.EndToEndTestSupport;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.gateway.HmppsPublisher;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.CachedHearingPayloadAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ChainedHearingPayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.payload.ResultsQueryExternalHearingClient;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.persistence.OutboundLogAdapter;
import uk.gov.hmcts.cp.probationresultsdistribution.adapter.usersgroups.FeaturePermissionClient;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.JdbcProcessedRequestRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.OutboundMessageRepository;
import uk.gov.hmcts.cp.probationresultsdistribution.persistence.ProcessedRequestRepository;

/**
 * Shared base for the resend-leg suites (slice 004, US3). It wires a real {@link LegResender} over
 * the shared PostgreSQL, Redis and WireMock of {@link EndToEndTestSupport} — the production payload
 * chain, users-groups client, gateway publisher and processed log — and seeds request and attempt
 * rows directly, in every shape the eligibility table names (FR-004-022, FR-004-023). No broker is
 * involved: resend-leg is a direct call, never a queue delivery.
 */
public class ResendTestSupport extends EndToEndTestSupport {

    protected static final String SOURCE = "RESULTS";
    protected static final LocalDate HEARING_DAY = LocalDate.parse("2026-08-19");
    protected static final String SHARED_TIME = "2026-08-19T16:42:07.512Z";
    protected static final String OPERATOR = "local-dev";
    protected static final String REASON = "manual resend";
    protected static final Duration LEASE = Duration.ofMinutes(4).plusSeconds(30);

    private static final String REQUEST_ID = "requestId";

    /**
     * A sendable hearing payload carrying the given top-level {@code sharedTime}.
     *
     * @param sharedTime the payload's {@code sharedTime} member, or {@code null} for none
     * @return the payload JSON
     */
    protected static String payload(final String sharedTime) {
        final String sharedTimeMember = sharedTime == null ? ""
                : "\"sharedTime\":\"" + sharedTime + "\",";
        return "{" + sharedTimeMember + "\"hearing\":{\"prosecutionCases\":[{\"defendants\":["
                + "{\"isYouth\":false,\"offences\":[]}]}]}}";
    }

    /**
     * A resend-leg capability over the shared stack and the given cache connection.
     *
     * @param cache        the Redis connection the payload cache reads through
     * @param maxIdsPerRun the run cap
     * @param pauseGate    the operator-pause read
     * @param sender       the HMPPS send port, or {@code null} for the production publisher
     * @return the capability
     */
    protected final LegResender resender(final StatefulRedisConnection<String, String> cache,
                                         final int maxIdsPerRun, final IntakePauseGate pauseGate,
                                         final HmppsSender sender) {
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(sharedDataSource()));
        final MetricRegistry registry = new MetricRegistry(meterRegistry());
        final ChainedHearingPayloadSource payload = new ChainedHearingPayloadSource(
                new CachedHearingPayloadAdapter(cache, CACHE_PREFIX),
                new ResultsQueryExternalHearingClient(usersGroupsBaseUrl(), SYSTEM_USER_ID,
                        Duration.ofSeconds(5), Duration.ofSeconds(30), 1, Duration.ofMillis(100),
                        Duration.ofSeconds(1)),
                Duration.ofSeconds(60));
        final FeaturePermissionClient youthFilter = new FeaturePermissionClient(
                usersGroupsBaseUrl(), SYSTEM_USER_ID, "EMYouthFilter", Duration.ofSeconds(2),
                Duration.ofSeconds(5), 3, Duration.ofMillis(100), registry);
        final HmppsSender hmpps = sender != null ? sender : publisher(registry);
        final DistributionRunner runner = new DistributionRunner(payload, youthFilter, hmpps,
                new OutboundLogAdapter(new OutboundMessageRepository(jdbc(), tx, "test-version")),
                YouthSwitchRecordedValue.ACTIVE, registry);
        return new LegResender(pauseGate, requestStore(), runner, registry, maxIdsPerRun);
    }

    /**
     * The production gateway publisher over the shared WireMock.
     *
     * @param registry the metric registry
     * @return the publisher
     */
    protected final HmppsPublisher publisher(final MetricRegistry registry) {
        return new HmppsPublisher(gatewayUrl(), SUBSCRIPTION_KEY, Duration.ofSeconds(5),
                Duration.ofSeconds(60), registry);
    }

    /**
     * A resend-leg capability over the shared cache, a cap of 100, never paused.
     *
     * @return the capability
     */
    protected final LegResender resender() {
        return resender(sharedRedisConnection(), 100, () -> false, null);
    }

    /**
     * The JDBC processed-log store over the shared data source.
     *
     * @return the store
     */
    protected final ProcessedRequestRepository requestStore() {
        return new JdbcProcessedRequestRepository(jdbc(),
                new TransactionTemplate(new DataSourceTransactionManager(sharedDataSource())), LEASE);
    }

    /**
     * Seeds a request row with no claim, its stored command carrying a {@code userId}.
     *
     * @param requestId     the request id
     * @param hearingId     the hearing id
     * @param status        the request status
     * @param failureReason the failure reason ({@code FAILED} only), or {@code null}
     */
    protected final void seedRequestRow(final UUID requestId, final UUID hearingId,
                                        final String status, final String failureReason) {
        seedRequestRow(requestId, hearingId, status, failureReason, false);
    }

    /**
     * Seeds a request row, optionally under a live request claim.
     *
     * @param requestId     the request id
     * @param hearingId     the hearing id
     * @param status        the request status
     * @param failureReason the failure reason ({@code FAILED} only), or {@code null}
     * @param claimLive     whether the request holds a live claim
     */
    protected final void seedRequestRow(final UUID requestId, final UUID hearingId,
                                        final String status, final String failureReason,
                                        final boolean claimLive) {
        final String exhausted = "leg-exhausted".equals(failureReason) ? "RESULTS:" + requestId : null;
        final String claim = claimLive
                ? "gen_random_uuid()::text, gen_random_uuid(), now() + interval '1 hour'"
                : "NULL, NULL, NULL";
        jdbc().sql("INSERT INTO processed_request (source, request_id, hearing_id, hearing_day, "
                        + "shared_time, event_type, request_fingerprint, status, attempts, command, "
                        + "failure_reason, exhausted_message_id, completion_reason, claim_owner, "
                        + "claim_token, claim_expires_at) VALUES (:source, :requestId, :hearingId, "
                        + ":hearingDay, :sharedTime, 'Hearing_Resulted', 'fp', :status, 1, :command, "
                        + ":failureReason, :exhausted, :completionReason, " + claim + ")")
                .param("source", SOURCE)
                .param(REQUEST_ID, requestId)
                .param("hearingId", hearingId)
                .param("hearingDay", HEARING_DAY)
                .param("sharedTime", OffsetDateTime.parse(SHARED_TIME).withOffsetSameInstant(
                        ZoneOffset.UTC))
                .param("status", status)
                .param("command", storedCommand(requestId, hearingId))
                .param("failureReason", failureReason)
                .param("exhausted", exhausted)
                .param("completionReason", "COMPLETED".equals(status) ? "both-legs-terminal" : null)
                .update();
    }

    /**
     * The stored command body, carrying a {@code userId} a resend must never send (DV-17).
     *
     * @param requestId the request id
     * @param hearingId the hearing id
     * @return the command JSON
     */
    protected static String storedCommand(final UUID requestId, final UUID hearingId) {
        return "{\"source\":\"RESULTS\",\"requestId\":\"" + requestId + "\",\"hearingId\":\""
                + hearingId + "\",\"hearingDay\":\"" + HEARING_DAY + "\",\"sharedTime\":\""
                + SHARED_TIME + "\",\"eventType\":\"Hearing_Resulted\","
                + "\"userId\":\"11111111-2222-3333-4444-555555555555\"}";
    }

    /**
     * Seeds an attempt-1 queue row with a status and reason that need no outcome columns:
     * {@code PENDING}, {@code PARKED(payload-unavailable)}, {@code FAILED(payload-unavailable)} or
     * {@code FAILED(rolled-back)}.
     *
     * @param requestId the request id
     * @param status    the status
     * @param reason    the reason, or {@code null}
     */
    protected final void seedBareAttemptOne(final UUID requestId, final String status,
                                            final String reason) {
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts) VALUES (gen_random_uuid(), "
                        + ":source, :requestId, 'HMPPS', 1, 'queue', :status, :reason, 1)")
                .param("source", SOURCE)
                .param(REQUEST_ID, requestId)
                .param("status", status)
                .param("reason", reason)
                .update();
    }

    /**
     * Seeds an attempt-1 queue row that was sent or rejected: {@code SENT}, or
     * {@code FAILED(receiver-rejected)}/{@code FAILED(gateway-transient)}, with its outcome columns.
     *
     * @param requestId  the request id
     * @param status     the status
     * @param reason     the reason, or {@code null} for {@code SENT}
     * @param httpStatus the HTTP status recorded
     */
    protected final void seedSentAttemptOne(final UUID requestId, final String status,
                                            final String reason, final int httpStatus) {
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, attempts, http_status, body_digest, "
                        + "payload_source, service_version, youth_switch, sent_at) VALUES "
                        + "(gen_random_uuid(), :source, :requestId, 'HMPPS', 1, 'queue', :status, "
                        + ":reason, 1, :httpStatus, :digest, 'cache-day', 'test-version', 'ACTIVE', "
                        + "CASE WHEN :status = 'SENT' THEN now() ELSE NULL END)")
                .param("source", SOURCE)
                .param(REQUEST_ID, requestId)
                .param("status", status)
                .param("reason", reason)
                .param("httpStatus", httpStatus)
                .param("digest", sha256Hex("{}"))
                .update();
    }

    /**
     * Seeds a resend row at an attempt above 1, with its leg claim and run id.
     *
     * @param requestId the request id
     * @param attempt   the attempt number
     * @param status    {@code PENDING} or {@code FAILED}
     * @param reason    {@code null} for {@code PENDING}; {@code rolled-back} or
     *                  {@code payload-unavailable} for {@code FAILED}
     * @param expired   whether the leg claim's lease has expired
     * @return the seeded row's claim token
     */
    protected final UUID seedResendRow(final UUID requestId, final int attempt, final String status,
                                       final String reason, final boolean expired) {
        final UUID token = UUID.randomUUID();
        jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                        + "attempt_origin, status, reason, claim_owner, claim_token, claim_expires_at, "
                        + "resend_run_id, operator) VALUES (gen_random_uuid(), :source, :requestId, "
                        + "'HMPPS', :attempt, 'resend', :status, :reason, 'an-owner', :token, "
                        + "CASE WHEN :expired THEN now() - interval '1 minute' "
                        + "ELSE now() + interval '4 minutes' END, gen_random_uuid(), 'someone')")
                .param("source", SOURCE)
                .param(REQUEST_ID, requestId)
                .param("attempt", attempt)
                .param("status", status)
                .param("reason", reason)
                .param("token", token)
                .param("expired", expired)
                .update();
        return token;
    }

    /**
     * The number of attempt rows a request's HMPPS leg has.
     *
     * @param requestId the request id
     * @return the row count
     */
    protected final int attemptCount(final UUID requestId) {
        return jdbc().sql("SELECT count(*) FROM outbound_message WHERE request_id = :requestId")
                .param(REQUEST_ID, requestId).query(Integer.class).single();
    }

    /**
     * The latest HMPPS attempt row of a request.
     *
     * @param requestId the request id
     * @return the row's columns
     */
    protected final Map<String, Object> latestAttempt(final UUID requestId) {
        return jdbc().sql("SELECT * FROM outbound_message_latest WHERE request_id = :requestId "
                        + "AND leg = 'HMPPS'")
                .param(REQUEST_ID, requestId).query().singleRow();
    }

    /**
     * The request row.
     *
     * @param requestId the request id
     * @return the row's columns
     */
    protected final Map<String, Object> requestRow(final UUID requestId) {
        return jdbc().sql("SELECT * FROM processed_request WHERE request_id = :requestId")
                .param(REQUEST_ID, requestId).query().singleRow();
    }

    /**
     * A resend-leg request over the given ids, with the fixed operator and reason.
     *
     * @param allowChangedPayload whether {@code allow-changed-payload} is requested
     * @param requestIds          the ids
     * @return the request
     */
    protected static ResendRequest request(final boolean allowChangedPayload,
                                           final UUID... requestIds) {
        return new ResendRequest(java.util.List.of(requestIds), OPERATOR, REASON, allowChangedPayload);
    }

    /**
     * The report entry for one id.
     *
     * @param result    the run result
     * @param requestId the id
     * @return its outcome
     */
    protected static Optional<ResendEntryOutcome> entryFor(final ResendRunResult result,
                                                           final UUID requestId) {
        return result.entries().stream().filter(entry -> entry.requestId().equals(requestId))
                .map(ResendRunResult.Entry::outcome).findFirst();
    }
}
