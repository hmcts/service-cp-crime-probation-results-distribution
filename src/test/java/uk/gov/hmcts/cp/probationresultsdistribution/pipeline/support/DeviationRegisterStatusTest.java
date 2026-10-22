package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link DeviationRegister#isActive(String)} must match the {@code Status} cell's exact token, not
 * any cell that merely contains the substring "active" (FR-001-037). A naive
 * {@code toLowerCase().contains("active")} check also matches the status {@code Inactive}, which
 * would let a row explicitly turned off still drive the case-13 derivation — this pins the fix found
 * in code review.
 */
@DisplayName("DeviationRegister — isActive matches the exact status token")
class DeviationRegisterStatusTest {

    @Test
    @DisplayName("a row whose status is exactly 'Active' is active")
    void isActive_whenStatusIsActive_should_be_true(@TempDir final Path scratch) {
        final DeviationRegister register = load(scratch, "Active");

        assertThat(register.isActive("DV-25")).isTrue();
    }

    @Test
    @DisplayName("a row whose status is 'Inactive' is NOT active, despite containing \"active\"")
    void isActive_whenStatusIsInactive_should_be_false(@TempDir final Path scratch) {
        final DeviationRegister register = load(scratch, "Inactive");

        assertThat(register.isActive("DV-25"))
                .as("'Inactive' must not be read as active merely because it contains \"active\"")
                .isFalse();
    }

    @Test
    @DisplayName("a withdrawn row is not active")
    void isActive_whenStatusIsWithdrawn_should_be_false(@TempDir final Path scratch) {
        final DeviationRegister register = load(scratch, "Withdrawn");

        assertThat(register.isActive("DV-25")).isFalse();
    }

    @Test
    @DisplayName("an unknown DV id is not active")
    void isActive_whenDvIdIsUnknown_should_be_false(@TempDir final Path scratch) {
        final DeviationRegister register = load(scratch, "Active");

        assertThat(register.isActive("DV-99")).isFalse();
    }

    private static DeviationRegister load(final Path scratch, final String status) {
        final List<String> lines = List.of(
                "## Registered deviations",
                "",
                "| DV | Status | Derivation |",
                "|----|--------|------------|",
                "| DV-25 | " + status
                        + " | default ACTIVE uses the ACTIVE expectation; "
                        + "default INACTIVE uses the INACTIVE expectation |",
                "",
                "## Next section");
        final Path register = scratch.resolve("DEVIATIONS.md");
        try {
            Files.write(register, lines);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
        return DeviationRegister.load(register);
    }
}
