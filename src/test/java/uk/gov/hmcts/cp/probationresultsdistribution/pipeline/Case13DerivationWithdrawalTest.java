package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.Case13Derivation;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase;

/**
 * The case-13 derivation is load-bearing, not decorative (User Story 5, Acceptance Scenario 4;
 * SC-001-011; FR-001-037, FR-001-038). The derivation reads DV-25's permitted outcome from
 * {@code doc/DEVIATIONS.md}; withdrawing DV-25's row in a scratch copy of the register MUST break
 * every case-13 derivation, each failure quoting DV-25.
 *
 * <p>Value-free by construction (FR-001-031): the scratch register carries no corpus content, and
 * the assertions name only DV-25 and counts, never a payload or a body.
 */
@DisplayName("Case 13 derivation — withdrawing DV-25 breaks every derivation")
class Case13DerivationWithdrawalTest {

    private static final int STATUS_CELL = 2;

    private final HmppsRedaction redaction = new HmppsRedaction();

    @Test
    @DisplayName("withdrawing DV-25 makes every case-13 derivation fail, quoting DV-25")
    void withdrawingDv25_should_break_every_case13_derivation(@TempDir final Path scratch) {
        final Path scratchRegister = writeRegisterWithDv25Withdrawn(scratch);
        final List<ParityCase> cases = ParityCase.loadAll(corpusRoot());

        final Case13Derivation.Result result =
                Case13Derivation.derive(cases, redaction, scratchRegister);

        assertThat(result.differences())
                .as("withdrawing DV-25 must make every case-13 derivation fail")
                .isNotEmpty();
        assertThat(result.differences())
                .as("every case-13 failure must quote DV-25")
                .allSatisfy(line -> assertThat(line).contains("DV-25"));
        assertThat(result.derivedStates())
                .as("no case-13 derivation may be counted as derived once DV-25 is withdrawn")
                .isEmpty();
    }

    private static Path writeRegisterWithDv25Withdrawn(final Path scratch) {
        final List<String> withdrawn = realRegister().lines()
                .map(Case13DerivationWithdrawalTest::withdrawDv25Row)
                .toList();
        final Path target = scratch.resolve("DEVIATIONS.md");
        try {
            Files.write(target, withdrawn);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return target;
    }

    private static String withdrawDv25Row(final String line) {
        if (!line.startsWith("| DV-25 ")) {
            return line;
        }
        final String[] cells = line.split("\\|", -1);
        if (cells.length > STATUS_CELL) {
            cells[STATUS_CELL] = " Withdrawn ";
        }
        return String.join("|", cells);
    }

    private static String realRegister() {
        try {
            return Files.readString(Path.of("doc", "DEVIATIONS.md"));
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path corpusRoot() {
        final URL url = Case13DerivationWithdrawalTest.class.getResource("/corpus");
        if (url == null) {
            throw new IllegalStateException("the corpus is not on the test classpath: /corpus");
        }
        try {
            return Path.of(url.toURI());
        } catch (final URISyntaxException e) {
            throw new IllegalStateException("corpus URL is not a valid URI", e);
        }
    }
}
