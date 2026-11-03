package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.util.Objects;
import java.util.Set;

/**
 * One row of design §9.2 or §9.5's go/no-go evidence index (slice 006, FR-006-031, data-model.md).
 *
 * @param section       {@code "§9.2"} or {@code "§9.5"}
 * @param row           the design's own row name, e.g. {@code "G1"}, {@code "1"}, {@code "23"}
 * @param check         what the row asks
 * @param status        this repository's own classification: {@link #STATUSES}
 * @param evidence      the named artefact or evidence-query class/method for a {@code built} row;
 *                      the design reference for {@code closed}/{@code parked}/{@code answered}
 * @param supplier      who supplies the evidence; never {@code "this repository"} for a row
 *                      another team supplies (FR-006-031's own rule)
 * @param obtainedWhere {@code "once in SIT"}, {@code "in STE"} or {@code "in each environment"}
 */
public record GoNoGoEvidenceRow(
        String section,
        String row,
        String check,
        String status,
        String evidence,
        String supplier,
        String obtainedWhere) {

    /** The closed set of values {@link #status} may take. */
    public static final Set<String> STATUSES =
            Set.of("built", "closed", "parked", "answered", "external");

    /** Rejects null components and a status outside the closed set. */
    public GoNoGoEvidenceRow {
        Objects.requireNonNull(section, "section");
        Objects.requireNonNull(row, "row");
        Objects.requireNonNull(check, "check");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(evidence, "evidence");
        Objects.requireNonNull(supplier, "supplier");
        Objects.requireNonNull(obtainedWhere, "obtainedWhere");
        if (!STATUSES.contains(status)) {
            throw new IllegalArgumentException("unknown go/no-go row status: " + status);
        }
    }
}
