package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * One read-only query per build-supported go/no-go row (design §9.2 rows 1, 7, 9, 21; slice 006,
 * FR-006-030, FR-006-032). Every result carries identifiers and bounded column values only — never
 * a payload or body value.
 */
public final class GoNoGoEvidenceQuery {

    private static final String SOURCE = "source";
    private static final String REQUEST_ID = "requestId";

    private final JdbcClient jdbc;

    /**
     * Creates the query.
     *
     * @param jdbc the JDBC client over the service's own store
     */
    public GoNoGoEvidenceQuery(final JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * §9.2 row 1: one real SIT share admitted from the producer, with its HMPPS leg's outcome.
     *
     * @return the evidence, or empty while no such share has been admitted
     */
    public Optional<ResultsShareEvidence> resultsShareEvidence() {
        return jdbc.sql(
                        "SELECT pr.request_id, pr.hearing_id, m.status "
                        + "FROM processed_request pr "
                        + "JOIN outbound_message_latest m "
                        + "  ON m.source = pr.source AND m.request_id = pr.request_id "
                        + "  AND m.leg = 'HMPPS' "
                        + "WHERE pr.source = 'RESULTS' "
                        + "ORDER BY pr.created_at LIMIT 1")
                .query((rs, rowNum) -> new ResultsShareEvidence(
                        (UUID) rs.getObject("request_id"), (UUID) rs.getObject("hearing_id"),
                        rs.getString("status")))
                .optional();
    }

    /**
     * §9.2 row 9 (and the reading row 7 compares): the test call's latest HMPPS attempt.
     *
     * @param source    the command source
     * @param requestId the request id
     * @return the evidence, or empty when no attempt exists for this request
     */
    public Optional<TestCallEvidence> testCallEvidence(final String source, final UUID requestId) {
        return jdbc.sql("SELECT status, http_status, youth_switch FROM outbound_message_latest "
                        + "WHERE source = :source AND request_id = :requestId AND leg = 'HMPPS'")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> new TestCallEvidence(rs.getString("status"),
                        Optional.ofNullable((Integer) rs.getObject("http_status")),
                        Optional.ofNullable(rs.getString("youth_switch"))))
                .optional();
    }

    /**
     * §9.2 row 7: the test call's recorded youth switch compared against the configured default.
     *
     * @param source            the command source
     * @param requestId         the request id
     * @param configuredDefault {@code "ACTIVE"} or {@code "INACTIVE"}, the live reading
     * @return the evidence, or empty when no attempt exists for this request
     */
    public Optional<YouthSwitchCheckEvidence> youthSwitchCheck(final String source,
            final UUID requestId, final String configuredDefault) {
        return testCallEvidence(source, requestId)
                .flatMap(TestCallEvidence::youthSwitch)
                .map(recorded -> new YouthSwitchCheckEvidence(recorded, configuredDefault,
                        recorded.equals(configuredDefault)));
    }

    /**
     * §9.2 row 21: a request dead-lettered {@code leg-exhausted}, then recovered by a replay.
     *
     * @param source    the command source
     * @param requestId the request id
     * @return the evidence, or empty when the request is unknown
     */
    public Optional<RecoveryEvidence> recoveryEvidence(final String source, final UUID requestId) {
        return jdbc.sql("SELECT status, failure_reason, exhausted_message_id, "
                        + "(replayed_at IS NOT NULL) AS replayed FROM processed_request "
                        + "WHERE source = :source AND request_id = :requestId")
                .param(SOURCE, source)
                .param(REQUEST_ID, requestId)
                .query((rs, rowNum) -> new RecoveryEvidence(rs.getString("status"),
                        Optional.ofNullable(rs.getString("failure_reason")),
                        Optional.ofNullable(rs.getString("exhausted_message_id")),
                        rs.getBoolean("replayed")))
                .optional();
    }

    /** §9.2 row 1's evidence: the admitted share and its HMPPS leg outcome. */
    public record ResultsShareEvidence(UUID requestId, UUID hearingId, String hmppsStatus) {
    }

    /** §9.2 row 9's evidence: the test call's latest HMPPS attempt. */
    public record TestCallEvidence(String status, Optional<Integer> httpStatus,
                                   Optional<String> youthSwitch) {
    }

    /** §9.2 row 7's evidence: the recorded youth switch against the configured default. */
    public record YouthSwitchCheckEvidence(String recordedYouthSwitch, String configuredDefault,
                                           boolean matches) {
    }

    /** §9.2 row 21's evidence: the dead-letter-then-replay recovery trail. */
    public record RecoveryEvidence(String status, Optional<String> failureReason,
                                   Optional<String> exhaustedMessageId, boolean replayed) {
    }
}
