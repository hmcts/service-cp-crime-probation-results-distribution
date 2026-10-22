package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Testcontainers;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.AttemptOrigin;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.DistributionCommand;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegOutcome;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegReason;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.LegStatus;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.PayloadSource;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RunClaim;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.YouthSwitchRecordedValue;

@Testcontainers
@DisplayName("claim fencing and the guarded outcome write (V1)")
class ClaimFencingIT extends SchemaTestSupport {

    private JdbcProcessedRequestRepository requests;
    private OutboundMessageRepository outbound;
    private DistributionCommand command;

    @BeforeEach
    void setUp() {
        final JdbcClient client = JdbcClient.create(datasource());
        final TransactionTemplate tx =
                new TransactionTemplate(new DataSourceTransactionManager(datasource()));
        requests = new JdbcProcessedRequestRepository(client, tx, Duration.ofSeconds(270));
        outbound = new OutboundMessageRepository(client, tx, "test-service-1");
        command = new DistributionCommand(
                "RESULTS", UUID.randomUUID(), UUID.randomUUID(), LocalDate.parse("2026-08-19"),
                Instant.parse("2026-08-19T16:42:07.512Z"), "Hearing_Resulted", Optional.empty());
    }

    @Test
    @DisplayName("admission inserts the request and the attempt-1 PENDING row in one transaction")
    void admission_inserts_both_rows() {
        assertThat(requests.insertClaim(command, "{}", "fp", RunClaim.fresh())).isTrue();

        assertThat(requests.find("RESULTS", command.requestId()))
                .get()
                .extracting(ProcessedRequestRepository.RequestRecord::status)
                .isEqualTo("RECEIVED");
        assertThat(attemptStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("a second insert of the same key loses the race and is not admitted")
    void second_insert_loses_the_race() {
        assertThat(requests.insertClaim(command, "{}", "fp", RunClaim.fresh())).isTrue();
        assertThat(requests.insertClaim(command, "{}", "fp", RunClaim.fresh())).isFalse();
    }

    @Test
    @DisplayName("reclaim is refused while the claim is live and granted once it has expired")
    void reclaim_only_after_expiry() {
        requests.insertClaim(command, "{}", "fp", RunClaim.fresh());

        assertThat(requests.reclaim("RESULTS", command.requestId(), RunClaim.fresh())).isFalse();

        execute("UPDATE processed_request SET claim_expires_at = now() - interval '1 minute' "
                + "WHERE request_id = '" + command.requestId() + "'");
        assertThat(requests.reclaim("RESULTS", command.requestId(), RunClaim.fresh())).isTrue();
        assertThat(attempts()).isEqualTo(2);
    }

    @Test
    @DisplayName("a stale runner's guarded outcome write matches no row")
    void stale_runner_write_matches_no_row() {
        requests.insertClaim(command, "{}", "fp", RunClaim.fresh());

        final int rows = outbound.writeOutcome(sent("the bytes"), RunClaim.fresh(),
                LegStatus.PENDING);

        assertThat(rows).isZero();
        assertThat(attemptStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("the claim holder's write records the outcome, its body, and touches nothing else")
    void claim_holder_write_records_outcome_and_body() {
        final RunClaim claim = RunClaim.fresh();
        requests.insertClaim(command, "{}", "fp", claim);

        final int rows = outbound.writeOutcome(sent("the exact bytes"), claim, LegStatus.PENDING);

        assertThat(rows).isEqualTo(1);
        assertThat(attemptStatus()).isEqualTo("SENT");
        assertThat(storedBody()).isEqualTo("the exact bytes");
        assertThat(rowCount("processed_request")).isEqualTo(1);
        assertThat(rowCount("outbound_message")).isEqualTo(1);
        assertThat(auditNoteIsNull()).isTrue();
    }

    @Test
    @DisplayName("an in-place re-run replaces the attempt's body, leaving exactly one")
    void rerun_replaces_the_body() {
        final RunClaim claim = RunClaim.fresh();
        requests.insertClaim(command, "{}", "fp", claim);

        outbound.writeOutcome(parked("first body"), claim, LegStatus.PENDING);
        outbound.writeOutcome(sent("second body"), claim, LegStatus.PARKED);

        assertThat(attemptStatus()).isEqualTo("SENT");
        assertThat(storedBody()).isEqualTo("second body");
        assertThat(bodyRowCount()).isEqualTo(1);
    }

    private LegOutcome sent(final String body) {
        return new LegOutcome(command, AttemptOrigin.QUEUE, LegStatus.SENT, Optional.empty(),
                Optional.of(YouthSwitchRecordedValue.ACTIVE), Optional.of(PayloadSource.CACHE_DAY),
                Optional.of(command.sharedTime()), Optional.of(sha256Hex(body)), OptionalInt.of(200),
                Optional.of(body));
    }

    private LegOutcome parked(final String body) {
        return new LegOutcome(command, AttemptOrigin.QUEUE, LegStatus.PARKED,
                Optional.of(LegReason.GATEWAY_TRANSIENT),
                Optional.of(YouthSwitchRecordedValue.ACTIVE), Optional.of(PayloadSource.CACHE_DAY),
                Optional.of(command.sharedTime()), Optional.of(sha256Hex(body)), OptionalInt.of(503),
                Optional.of(body));
    }

    private String attemptStatus() {
        return jdbc().sql("SELECT status FROM outbound_message WHERE request_id = :id AND attempt = 1")
                .param("id", command.requestId()).query(String.class).single();
    }

    private int attempts() {
        return jdbc().sql("SELECT attempts FROM processed_request WHERE request_id = :id")
                .param("id", command.requestId()).query(Integer.class).single();
    }

    private String storedBody() {
        return jdbc().sql("SELECT b.body FROM outbound_body b JOIN outbound_message m "
                        + "ON b.outbound_message_id = m.id WHERE m.request_id = :id")
                .param("id", command.requestId()).query(String.class).single();
    }

    private boolean auditNoteIsNull() {
        return jdbc().sql("SELECT audit_note IS NULL FROM processed_request WHERE request_id = :id")
                .param("id", command.requestId()).query(Boolean.class).single();
    }

    private int rowCount(final String table) {
        return jdbc().sql("SELECT count(*) FROM " + table + " WHERE request_id = :id")
                .param("id", command.requestId()).query(Integer.class).single();
    }

    private int bodyRowCount() {
        return jdbc().sql("SELECT count(*) FROM outbound_body b JOIN outbound_message m "
                        + "ON b.outbound_message_id = m.id WHERE m.request_id = :id")
                .param("id", command.requestId()).query(Integer.class).single();
    }

    private static String sha256Hex(final String body) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(body.getBytes(StandardCharsets.UTF_8)));
        } catch (final NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
