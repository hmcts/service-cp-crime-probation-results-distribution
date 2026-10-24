package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.OffsetDateTime;
import java.time.Period;
import java.util.ArrayList;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class BodyRetentionCalendarIT extends MaintenanceTestStore {

    @Test
    void fourteen_month_calendar_should_never_remove_early_or_retain_beyond_window() {
        final OffsetDateTime start = OffsetDateTime.parse("2040-01-01T00:00:00Z");
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final MaintenanceMetrics metrics = metrics(registry, start);
        final List<List<UUID>> months = new ArrayList<>();
        for (int index = 0; index < 14; index++) {
            final OffsetDateTime month = start.plusMonths(index);
            job(month, Period.ofMonths(6), metrics).run();
            months.add(List.of(insertBody(month), insertBody(month.plusDays(14)),
                    insertBody(month.plusMonths(1).minusSeconds(1))));
            for (final OffsetDateTime day : List.of(month, month.plusDays(14),
                    month.plusMonths(1).minusSeconds(1))) {
                final List<String> requestsBefore = metadata();
                final List<String> attemptsBefore = attempts();
                final MaintenanceRunOutcome outcome = job(day, Period.ofMonths(6), metrics).run();
                assertThat(outcome.failures()).isEmpty();
                assertThat(metadata()).isEqualTo(requestsBefore);
                assertThat(attempts()).isEqualTo(attemptsBefore);
                assertThat(catalogue.partitions()).extracting(PartitionBound::partitionName)
                        .contains(name(month), name(month.plusMonths(1)), name(month.plusMonths(2)));
                final List<UUID> retained = sql.sql("SELECT outbound_message_id FROM outbound_body")
                        .query(UUID.class).list();
                for (int written = 0; written <= index; written++) {
                    if (written < index - 6) {
                        assertThat(retained).doesNotContainAnyElementsOf(months.get(written));
                    } else {
                        assertThat(retained).containsAll(months.get(written));
                    }
                }
            }
        }
        final OffsetDateTime lastMonth = start.plusMonths(13);
        job(lastMonth, Period.ofMonths(3), metrics).run();
        final long shortened = sql.sql("SELECT count(*) FROM outbound_body").query(Long.class).single();
        job(lastMonth, Period.ofMonths(6), metrics).run();
        assertThat(sql.sql("SELECT count(*) FROM outbound_body").query(Long.class).single())
                .isEqualTo(shortened).isEqualTo(12);
    }

    @Test
    void catalogue_identifier_with_quote_should_be_dropped_as_one_identifier() {
        sql.sql("CREATE TABLE \"outbound_body_odd\"\"name\" PARTITION OF outbound_body "
                + "FOR VALUES FROM ('2001-01-01') TO ('2001-02-01')").update();
        final SimpleMeterRegistry registry = new SimpleMeterRegistry();
        final OffsetDateTime now = OffsetDateTime.parse("2026-10-21T00:00:00Z");
        final MaintenanceRunOutcome outcome = job(now, Period.ofMonths(6), metrics(registry, now)).run();

        assertThat(outcome.failures()).isEmpty();
        assertThat(outcome.partitionsDropped()).contains("outbound_body_odd\"name");
    }

    @ParameterizedTest
    @ValueSource(strings = {"UTC", "Europe/London"})
    void boundary_and_year_rollover_should_be_identical_in_each_zone(final String zone) {
        final TimeZone previous = TimeZone.getDefault();
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        final java.util.Properties connection = new java.util.Properties();
        connection.setProperty("options", "-c TimeZone=" + zone);
        source.setConnectionProperties(connection);
        try {
            final OffsetDateTime april = OffsetDateTime.parse("2044-04-01T00:00:00Z");
            repository.createPartition(name(april), april, april.plusMonths(1));
            final UUID message = insertBody(april);
            final List<String> before = attempts();
            final SimpleMeterRegistry registry = new SimpleMeterRegistry();
            final MaintenanceMetrics metrics = metrics(registry, april);
            final MaintenanceRunOutcome october = job(OffsetDateTime.parse("2044-10-31T23:59:59Z"),
                    Period.ofMonths(6), metrics).run();
            assertThat(october.partitionsDropped()).doesNotContain(name(april));
            final MaintenanceRunOutcome november = job(OffsetDateTime.parse("2044-11-01T00:00:00Z"),
                    Period.ofMonths(6), metrics).run();
            assertThat(november.partitionsDropped()).contains(name(april));
            // The October run already created the current month and the next three (H37) — Oct,
            // Nov, Dec 2044 and Jan 2045 — so only the newly-added fourth month ahead, Feb 2045, is
            // created here.
            assertThat(november.partitionsCreated()).contains("outbound_body_2045_02");
            assertThat(attempts()).isEqualTo(before);
            assertThat(sql.sql("SELECT count(*) FROM outbound_body WHERE outbound_message_id=:id")
                    .param("id", message).query(Long.class).single()).isZero();
            final MaintenanceRunOutcome repeated = job(OffsetDateTime.parse("2044-11-01T12:00:00Z"),
                    Period.ofMonths(6), metrics).run();
            assertThat(repeated.partitionsCreated()).isEmpty();
            assertThat(repeated.partitionsDropped()).isEmpty();
            assertThat(repeated.defaultRowsDeleted()).isZero();
            assertThat(repeated.failures()).isEmpty();
        } finally {
            TimeZone.setDefault(previous);
        }
    }
}
