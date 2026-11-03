package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Pins FR-006-031, SC-006-006: every row of §9.2 (G1, 1-23) and §9.5 (1-9), once each. */
@DisplayName("GoNoGoEvidenceIndex")
class GoNoGoEvidenceIndexTest {

    @Test
    @DisplayName("carries one entry per row of §9.2 (G1, 1-23) and §9.5 (1-9), 33 rows, no duplicates")
    void carries_every_row_once() {
        final List<GoNoGoEvidenceRow> rows = GoNoGoEvidenceIndex.rows();

        assertThat(rows).hasSize(33);
        final List<String> keys = rows.stream()
                .map(row -> row.section() + "/" + row.row())
                .collect(Collectors.toList());
        assertThat(keys).doesNotHaveDuplicates();

        final List<String> section92 = List.of("G1", "1", "2", "3", "4", "5", "6", "7", "8", "9",
                "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23");
        final List<String> section95 =
                List.of("1", "2", "3", "4", "5", "6", "7", "8", "9");

        assertThat(rows.stream().filter(row -> "§9.2".equals(row.section())).map(GoNoGoEvidenceRow::row))
                .containsExactlyInAnyOrderElementsOf(section92);
        assertThat(rows.stream().filter(row -> "§9.5".equals(row.section())).map(GoNoGoEvidenceRow::row))
                .containsExactlyInAnyOrderElementsOf(section95);
    }

    @Test
    @DisplayName("never names this repository as supplier for a row FR-006-035 assigns externally")
    void external_rows_never_name_this_repository_as_supplier() {
        final List<String> externallySupplied = List.of("§9.2/2", "§9.2/8", "§9.2/22", "§9.5/6");

        GoNoGoEvidenceIndex.rows().stream()
                .filter(row -> externallySupplied.contains(row.section() + "/" + row.row()))
                .forEach(row -> {
                    assertThat(row.status()).isEqualTo("external");
                    assertThat(row.supplier()).doesNotContain("this repository");
                });
    }
}
