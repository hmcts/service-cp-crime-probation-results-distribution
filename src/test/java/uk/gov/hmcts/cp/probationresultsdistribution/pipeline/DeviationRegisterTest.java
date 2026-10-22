package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The deviation register's shape and its full field set (FR-001-033, FR-001-034; SC-001-011). It
 * reads {@code doc/DEVIATIONS.md} as text and asserts, value-free, that:
 *
 * <ul>
 *   <li>the registered-deviations table holds exactly one row for each id DV-01 … DV-26 — the
 *       withdrawn, unallocated and reserved ids included — and no row beyond it;</li>
 *   <li>the register names DV-27 as the next free id;</li>
 *   <li>every <em>active</em> row carries the whole FR-001-034 field set: the legacy behaviour, the
 *       new behaviour, the reason, the approval (by role and decision id), whether it changes a
 *       message body, its pinning test and whether it is built.</li>
 * </ul>
 *
 * <p>This test is made green by T047, which completes the register's field set; against the seeded
 * table (which merged legacy and new behaviour into one column and carried neither a reason nor an
 * approval column) its column-presence assertions fail.
 */
@DisplayName("Deviation register — doc/DEVIATIONS.md shape and full field set")
class DeviationRegisterTest {

    private static final Path REGISTER = Path.of("doc", "DEVIATIONS.md");
    private static final Pattern DECISION_ID = Pattern.compile("\\b[HD]\\d+[a-z]?\\b");
    private static final List<String> EXPECTED_IDS = expectedIds();

    @Test
    @DisplayName("holds exactly one row per DV-01 … DV-26 and names DV-27 as the next free id")
    void register_should_hold_one_row_per_dv_and_name_dv27() {
        final List<String> ids = new ArrayList<>(registeredRows().keySet());

        assertThat(ids)
                .as("the registered-deviations table must hold exactly DV-01 … DV-26, once each")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_IDS);
        assertThat(readRegister())
                .as("the register must name DV-27 as the next free id (FR-001-033)")
                .contains("next free id is **DV-27**");
    }

    @Test
    @DisplayName("every active row carries the full FR-001-034 field set")
    void active_rows_should_carry_the_full_field_set() {
        final List<String> header = header();
        final int statusCol = columnIndex(header, "status");
        final int legacyCol = columnIndex(header, "legacy behaviour");
        final int newCol = columnIndex(header, "new behaviour");
        final int reasonCol = columnIndex(header, "reason");
        final int approvalCol = columnIndex(header, "approval");
        final int changesCol = columnIndex(header, "changes body");
        final int pinningCol = columnIndex(header, "pinning test");
        final int builtCol = columnIndex(header, "built");

        assertThat(legacyCol).as("the table must have a 'Legacy behaviour' column").isNotNegative();
        assertThat(newCol).as("the table must have a 'New behaviour' column").isNotNegative();
        assertThat(reasonCol).as("the table must have a 'Reason' column (FR-001-034)").isNotNegative();
        assertThat(approvalCol)
                .as("the table must have an 'Approval' column (FR-001-034)").isNotNegative();
        assertThat(changesCol)
                .as("the table must have a 'Changes body?' column").isNotNegative();
        assertThat(pinningCol).as("the table must have a 'Pinning test' column").isNotNegative();
        assertThat(builtCol).as("the table must have a 'Built?' column").isNotNegative();

        registeredRows().forEach((id, cells) -> {
            if (!cell(cells, statusCol).toLowerCase(Locale.ROOT).contains("active")) {
                return;
            }
            assertThat(nonBlank(cell(cells, legacyCol)))
                    .as("%s: active rows state the legacy behaviour", id).isTrue();
            assertThat(nonBlank(cell(cells, newCol)))
                    .as("%s: active rows state the new behaviour", id).isTrue();
            assertThat(nonBlank(cell(cells, reasonCol)))
                    .as("%s: active rows state a reason (FR-001-034)", id).isTrue();
            assertThat(DECISION_ID.matcher(cell(cells, approvalCol)).find())
                    .as("%s: active rows name an approving decision id (FR-001-034)", id).isTrue();
            assertThat(cell(cells, changesCol))
                    .as("%s: active rows state whether the body changes", id)
                    .matches("(?i)\\**(yes|no).*");
            assertThat(nonBlank(cell(cells, pinningCol)))
                    .as("%s: active rows name a pinning test", id).isTrue();
            assertThat(nonBlank(cell(cells, builtCol)))
                    .as("%s: active rows state whether the behaviour is built", id).isTrue();
        });
    }

    private static List<String> expectedIds() {
        final List<String> ids = new ArrayList<>();
        for (int n = 1; n <= 26; n++) {
            ids.add(String.format(Locale.ROOT, "DV-%02d", n));
        }
        return List.copyOf(ids);
    }

    private static List<String> header() {
        return splitRow(tableLines().get(0));
    }

    private static Map<String, List<String>> registeredRows() {
        final Map<String, List<String>> rows = new LinkedHashMap<>();
        for (final String line : tableLines()) {
            final List<String> cells = splitRow(line);
            final String first = cells.isEmpty() ? "" : cells.get(0);
            if (first.matches("DV-\\d+")) {
                rows.put(first, cells);
            }
        }
        return rows;
    }

    private static List<String> tableLines() {
        final List<String> all = registerLines();
        final List<String> table = new ArrayList<>();
        boolean inSection = false;
        for (final String line : all) {
            if (line.startsWith("## Registered deviations")) {
                inSection = true;
                continue;
            }
            if (inSection && line.startsWith("## ")) {
                break;
            }
            if (inSection && line.startsWith("|") && !line.contains("---")) {
                table.add(line);
            }
        }
        if (table.isEmpty()) {
            throw new AssertionError("no registered-deviations table found in doc/DEVIATIONS.md");
        }
        return table;
    }

    private static List<String> splitRow(final String line) {
        final String trimmed = line.strip();
        final String inner = trimmed.substring(1, trimmed.length() - 1);
        final List<String> cells = new ArrayList<>();
        for (final String cell : inner.split("\\|", -1)) {
            cells.add(cell.strip());
        }
        return cells;
    }

    private static int columnIndex(final List<String> header, final String needle) {
        for (int i = 0; i < header.size(); i++) {
            if (header.get(i).toLowerCase(Locale.ROOT).contains(needle)) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(final List<String> cells, final int index) {
        return index >= 0 && index < cells.size() ? cells.get(index) : "";
    }

    private static boolean nonBlank(final String value) {
        return !value.isBlank() && !"—".equals(value) && !"-".equals(value);
    }

    private static List<String> registerLines() {
        return readRegister().lines().toList();
    }

    private static String readRegister() {
        try {
            return Files.readString(REGISTER);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
