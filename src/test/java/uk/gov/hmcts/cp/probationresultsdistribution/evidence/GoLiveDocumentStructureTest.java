package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins SC-006-006, SC-006-007 by reading the documents themselves, never by inspection: the
 * go/no-go evidence index lists every row of §9.2 (G1, 1-23) and §9.5 (1-9), and the runbook
 * inputs document covers all 21 numbered steps across its four procedures.
 */
@DisplayName("Go-live document structure")
class GoLiveDocumentStructureTest {

    private static final Path EVIDENCE_INDEX = Path.of("doc", "go-live", "evidence-index.md");
    private static final Path RUNBOOK_INPUTS =
            Path.of("doc", "operations", "cutover-rollback-runbook-inputs.md");
    private static final Pattern TABLE_ROW =
            Pattern.compile("^\\|\\s*([A-Za-z0-9.]+)\\s*\\|", Pattern.MULTILINE);

    @Test
    @DisplayName("evidence-index.md lists G1 and rows 1-23 of §9.2")
    void evidence_index_lists_every_section_92_row() {
        final List<String> rows = tableRowKeys(sectionTable(read(EVIDENCE_INDEX), "## §9.2"));
        final List<String> expected = expectedSection92Rows();
        assertThat(rows).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    @DisplayName("evidence-index.md lists rows 1-9 of §9.5")
    void evidence_index_lists_every_section_95_row() {
        final List<String> rows = tableRowKeys(sectionTable(read(EVIDENCE_INDEX), "## §9.5"));
        assertThat(rows).containsExactlyInAnyOrder("1", "2", "3", "4", "5", "6", "7", "8", "9");
    }

    @Test
    @DisplayName("evidence-index.md's two tables together hold 33 rows (SC-006-006)")
    void evidence_index_holds_thirty_three_rows_in_total() {
        final int section92 = tableRowKeys(sectionTable(read(EVIDENCE_INDEX), "## §9.2")).size();
        final int section95 = tableRowKeys(sectionTable(read(EVIDENCE_INDEX), "## §9.5")).size();
        assertThat(section92 + section95).isEqualTo(33);
    }

    @Test
    @DisplayName("the runbook inputs document covers all 21 numbered steps across the four procedures (SC-006-007)")
    void runbook_inputs_covers_all_twenty_one_steps() {
        final String content = read(RUNBOOK_INPUTS);
        final int cutover = tableRowKeys(sectionTable(content, "## Cutover")).size();
        final int planned = tableRowKeys(sectionTable(content, "## Planned rollback")).size();
        final int urgent = tableRowKeys(sectionTable(content, "## Urgent rollback")).size();
        final int rollForward = tableRowKeys(sectionTable(content, "## Roll-forward")).size();

        assertThat(cutover).isEqualTo(2);
        assertThat(planned).isEqualTo(9);
        assertThat(urgent).isEqualTo(6);
        assertThat(rollForward).isEqualTo(4);
        assertThat(cutover + planned + urgent + rollForward).isEqualTo(21);
    }

    private static List<String> expectedSection92Rows() {
        final List<String> rows = new java.util.ArrayList<>();
        rows.add("G1");
        for (int i = 1; i <= 23; i++) {
            rows.add(String.valueOf(i));
        }
        return rows;
    }

    /** The content between a {@code ##} heading and the next {@code ##} heading (or end of file). */
    private static String sectionTable(final String content, final String heading) {
        final int start = content.indexOf(heading);
        assertThat(start).as("heading '%s' present", heading).isGreaterThanOrEqualTo(0);
        final int afterHeading = start + heading.length();
        final int nextHeading = content.indexOf("\n## ", afterHeading);
        return nextHeading < 0 ? content.substring(afterHeading) : content.substring(afterHeading, nextHeading);
    }

    /** Every markdown table row's first cell, skipping the header and separator rows. */
    private static List<String> tableRowKeys(final String section) {
        final List<String> keys = new java.util.ArrayList<>();
        final Matcher matcher = TABLE_ROW.matcher(section);
        while (matcher.find()) {
            final String key = matcher.group(1);
            if (!"Row".equals(key) && !"Step".equals(key)) {
                keys.add(key);
            }
        }
        return keys;
    }

    private static String read(final Path file) {
        try {
            return Files.readString(file);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
