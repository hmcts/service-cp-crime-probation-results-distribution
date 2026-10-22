package uk.gov.hmcts.cp.probationresultsdistribution.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import uk.gov.hmcts.cp.probationresultsdistribution.config.AgeGaugeRefresher;
import uk.gov.hmcts.cp.probationresultsdistribution.config.MetricRegistry;

@DisplayName("the database-derived age gauges")
class AgeGaugeQueryIT extends SchemaTestSupport {

    private static final String REQUEST_CREATED = "2010-01-01T00:00:00Z";
    private static final String LEG_UPDATED = "2015-01-01T00:00:00Z";

    @Test
    @DisplayName("uses request created_at and latest parked leg updated_at in seconds")
    void reads_the_correct_persisted_timestamps() {
        final UUID requestId = UUID.randomUUID();
        final UUID legId = UUID.randomUUID();
        insertReceivedRequest("RESULTS", requestId.toString());
        try {
            jdbc().sql("UPDATE processed_request SET created_at = :created, updated_at = now() "
                            + "WHERE source = 'RESULTS' AND request_id = :requestId")
                    .param("created", OffsetDateTime.parse(REQUEST_CREATED))
                    .param("requestId", requestId).update();
            jdbc().sql("INSERT INTO outbound_message (id, source, request_id, leg, attempt, "
                            + "attempt_origin, status, reason, updated_at) VALUES "
                            + "(:id, 'RESULTS', :requestId, 'HMPPS', 1, 'queue', 'PARKED', "
                            + "'payload-unavailable', :updated)")
                    .param("id", legId).param("requestId", requestId)
                    .param("updated", OffsetDateTime.parse(LEG_UPDATED)).update();
            final SimpleMeterRegistry meters = new SimpleMeterRegistry();
            final MetricRegistry metrics = new MetricRegistry(meters);
            new AgeGaugeRefresher(jdbc(), metrics).refresh();

            final double expectedRequestAge = ageSince(REQUEST_CREATED);
            final double expectedParkedAge = ageSince(LEG_UPDATED);
            assertThat(meters.get("probation_oldest_non_terminal_age").gauge().value())
                    .isCloseTo(expectedRequestAge, within(3.0d));
            assertThat(meters.get("probation_oldest_parked_age").gauge().value())
                    .isCloseTo(expectedParkedAge, within(3.0d));
        } finally {
            jdbc().sql("DELETE FROM outbound_message WHERE id = :id").param("id", legId).update();
            jdbc().sql("DELETE FROM processed_request WHERE source = 'RESULTS' "
                    + "AND request_id = :requestId").param("requestId", requestId).update();
        }
    }

    private double ageSince(final String timestamp) {
        return jdbc().sql("SELECT EXTRACT(EPOCH FROM (now() - CAST(:timestamp AS timestamptz)))"
                        + "::double precision")
                .param("timestamp", timestamp).query(Double.class).single();
    }
}
