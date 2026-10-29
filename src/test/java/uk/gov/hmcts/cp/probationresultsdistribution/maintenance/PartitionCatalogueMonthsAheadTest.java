package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("PartitionCatalogue.monthsAhead (FR-003-031, H37)")
class PartitionCatalogueMonthsAheadTest {

    private static final OffsetDateTime OCT = OffsetDateTime.of(2026, 10, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime NOV = OffsetDateTime.of(2026, 11, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime DEC = OffsetDateTime.of(2026, 12, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime JAN = OffsetDateTime.of(2027, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);
    private static final OffsetDateTime FEB = OffsetDateTime.of(2027, 2, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Test
    @DisplayName("counts zero when no month after the current one has a partition")
    void zero_when_nothing_covered() {
        assertThat(PartitionCatalogue.monthsAhead(OCT,
                List.of(new PartitionBound("outbound_body_2026_10", OCT, NOV))))
                .isZero();
    }

    @Test
    @DisplayName("counts every month when all are contiguously covered")
    void counts_full_contiguous_coverage() {
        assertThat(PartitionCatalogue.monthsAhead(OCT, List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_2026_11", NOV, DEC),
                new PartitionBound("outbound_body_2026_12", DEC, JAN),
                new PartitionBound("outbound_body_2027_01", JAN, FEB))))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("stops counting at the first gap, never summing total coverage past it")
    void stops_at_the_first_gap() {
        // November is covered from the current month with no gap (count 1); January is also
        // covered, but December (the month in between) is not, so the run of consecutive coverage
        // stops at November — the count must not jump to 2 for "two months somewhere ahead are
        // covered" by also crediting the disconnected January partition.
        assertThat(PartitionCatalogue.monthsAhead(OCT, List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_2026_11", NOV, DEC),
                new PartitionBound("outbound_body_2027_01", JAN, FEB))))
                .isEqualTo(1);
    }

    @Test
    @DisplayName("counts up to, but not past, a gap that starts after some consecutive coverage")
    void counts_up_to_a_later_gap() {
        assertThat(PartitionCatalogue.monthsAhead(OCT, List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_2026_11", NOV, DEC),
                new PartitionBound("outbound_body_2027_01", JAN, FEB))))
                .isEqualTo(1);
        assertThat(PartitionCatalogue.monthsAhead(OCT, List.of(
                new PartitionBound("outbound_body_2026_11", NOV, DEC),
                new PartitionBound("outbound_body_2026_12", DEC, JAN))))
                .isEqualTo(2);
    }

    @Test
    @DisplayName("excludes a partition whose bounds only overlap the target month rather than matching it exactly")
    void excludes_a_partial_overlap() {
        // Covers only the second half of November through the first half of December: not an exact
        // November-shaped partition, so November must not count as covered.
        final OffsetDateTime midNovember = OffsetDateTime.of(2026, 11, 15, 0, 0, 0, 0, ZoneOffset.UTC);
        final OffsetDateTime midDecember = OffsetDateTime.of(2026, 12, 15, 0, 0, 0, 0, ZoneOffset.UTC);
        assertThat(PartitionCatalogue.monthsAhead(OCT, List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_odd_range", midNovember, midDecember))))
                .isZero();
    }

    @Test
    @DisplayName("never counts the DEFAULT partition as coverage of any month")
    void excludes_the_default_partition() {
        assertThat(PartitionCatalogue.monthsAhead(OCT, List.of(
                new PartitionBound("outbound_body_2026_10", OCT, NOV),
                new PartitionBound("outbound_body_default", null, null))))
                .isZero();
    }
}
