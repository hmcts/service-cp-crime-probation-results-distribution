package uk.gov.hmcts.cp.probationresultsdistribution.maintenance;

import java.time.OffsetDateTime;

/**
 * One {@code outbound_body} partition's name and bounds, read from the PostgreSQL catalogue
 * (FR-003-010) — never parsed from the name. {@code from}/{@code to} are {@code null} for the
 * DEFAULT partition, whose catalogue bound expression is the literal string {@code DEFAULT}, not a
 * range; callers test {@link #isDefault()} rather than null-checking the bounds themselves.
 *
 * @param partitionName the partition's table name
 * @param from          the inclusive lower bound, or {@code null} for the DEFAULT partition
 * @param to            the exclusive upper bound, or {@code null} for the DEFAULT partition
 */
public record PartitionBound(String partitionName, OffsetDateTime from, OffsetDateTime to) {

    /**
     * Whether this is the DEFAULT partition.
     *
     * @return {@code true} for the DEFAULT partition
     */
    public boolean isDefault() {
        return from == null;
    }
}
