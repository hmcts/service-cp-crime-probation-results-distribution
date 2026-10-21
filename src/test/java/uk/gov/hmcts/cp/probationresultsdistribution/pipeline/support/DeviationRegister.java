package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;

/**
 * A read-only view of the deviation register {@code doc/DEVIATIONS.md} (FR-001-033, FR-001-037).
 * It parses the registered-deviations table and answers the two questions the case-13 derivation
 * needs: whether a DV id is an active row, and — for a deviation that changes an expected outcome —
 * which switch-state expectation the port must produce for each configured default.
 *
 * <p>This is what makes the case-13 derivation load-bearing rather than cosmetic (User Story 5): the
 * derivation reads DV-25's permitted outcome here, so withdrawing DV-25's row (its status no longer
 * {@code Active}) leaves the derivation with nothing to derive against, and it fails quoting DV-25.
 *
 * <p>Value-free by construction (FR-001-031): only DV ids, statuses and switch-state tokens are
 * read — never corpus content.
 */
public final class DeviationRegister {

    private static final String SECTION = "## Registered deviations";
    private static final Pattern DV_ID = Pattern.compile("DV-\\d+");
    private static final Pattern DEFAULT_MAPPING = Pattern.compile(
            "default\\s+(ACTIVE|INACTIVE)\\s+uses the\\s+(ACTIVE|INACTIVE)\\s+expectation");

    private final Map<String, Row> rows;

    private DeviationRegister(final Map<String, Row> rows) {
        this.rows = rows;
    }

    /**
     * Loads and parses a deviation register.
     *
     * @param registerPath the {@code doc/DEVIATIONS.md} file
     * @return the parsed register
     */
    public static DeviationRegister load(final Path registerPath) {
        return parse(readAll(registerPath));
    }

    /**
     * Whether a DV id is an active row of the register.
     *
     * @param dvId the deviation id, e.g. {@code DV-25}
     * @return {@code true} when the row exists and its status is {@code Active}
     */
    public boolean isActive(final String dvId) {
        final Row row = rows.get(dvId);
        return row != null && row.status().toLowerCase(Locale.ROOT).contains("active");
    }

    /**
     * The switch-state expectation the port must produce for a configured default, as the active
     * row's derivation rule states it (FR-001-037).
     *
     * @param dvId         the deviation id
     * @param defaultState the configured default the switch read resolved to
     * @return the switch state whose recorded expectation the derivation compares against, or
     *     {@code null} when the row is not active or does not describe this default
     */
    public EffectiveSwitchState sourceStateForDefault(
            final String dvId, final EffectiveSwitchState defaultState) {
        if (!isActive(dvId)) {
            return null;
        }
        return rows.get(dvId).defaultMapping().get(defaultState);
    }

    private static DeviationRegister parse(final List<String> lines) {
        final List<String> table = tableLines(lines);
        if (table.isEmpty()) {
            return new DeviationRegister(Map.of());
        }
        final List<String> header = splitRow(table.get(0));
        final int statusCol = columnIndex(header, "status");
        final int derivationCol = columnIndex(header, "derivation");

        final Map<String, Row> rows = new LinkedHashMap<>();
        for (final String line : table) {
            final List<String> cells = splitRow(line);
            final String first = cells.isEmpty() ? "" : cells.get(0);
            if (!DV_ID.matcher(first).matches()) {
                continue;
            }
            rows.put(first, rowFrom(cells, statusCol, derivationCol));
        }
        return new DeviationRegister(Map.copyOf(rows));
    }

    private static Row rowFrom(
            final List<String> cells, final int statusCol, final int derivationCol) {
        return new Row(cell(cells, statusCol), parseDefaultMapping(cell(cells, derivationCol)));
    }

    private static Map<EffectiveSwitchState, EffectiveSwitchState> parseDefaultMapping(
            final String derivationCell) {
        final Map<EffectiveSwitchState, EffectiveSwitchState> mapping =
                new EnumMap<>(EffectiveSwitchState.class);
        final Matcher matcher = DEFAULT_MAPPING.matcher(derivationCell);
        while (matcher.find()) {
            mapping.put(
                    EffectiveSwitchState.valueOf(matcher.group(1)),
                    EffectiveSwitchState.valueOf(matcher.group(2)));
        }
        return mapping;
    }

    private static List<String> tableLines(final List<String> lines) {
        final List<String> table = new ArrayList<>();
        boolean inSection = false;
        for (final String line : lines) {
            if (line.startsWith(SECTION)) {
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

    private static List<String> readAll(final Path registerPath) {
        try {
            return Files.readAllLines(registerPath);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private record Row(String status, Map<EffectiveSwitchState, EffectiveSwitchState> defaultMapping) {
    }
}
