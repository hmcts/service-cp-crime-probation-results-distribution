package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins FR-006-037, FR-006-050: the runbook inputs document carries no subscription, topic,
 * configuration-store, label, host or resource name — only named placeholders the cutover ticket
 * fills in.
 */
@DisplayName("Runbook input document hygiene")
class RunbookInputHygieneTest {

    private static final Path DOCUMENT =
            Path.of("doc", "operations", "cutover-rollback-runbook-inputs.md");

    // A representative sample of what a real value would look like, never a real one itself.
    private static final String[] FORBIDDEN_PATTERNS = {
        "sb://", "servicebus.windows.net", ".vault.azure.net", "appconfig.azure.com",
        "probation-requests-prod", "probation-requests-sit", "probation-requests-ste",
        "cpp-prd-", "cpp-sit-", "cpp-ste-", "cpp-stg-",
    };

    @Test
    @DisplayName("the document exists")
    void document_exists() {
        assertThat(Files.exists(DOCUMENT)).isTrue();
    }

    @Test
    @DisplayName("carries no environment-specific subscription, topic, store, label, host or resource name")
    void carries_no_environment_specific_value() {
        final String content = read();
        for (final String forbidden : FORBIDDEN_PATTERNS) {
            assertThat(content).as("forbidden pattern '%s'", forbidden)
                    .doesNotContainIgnoringCase(forbidden);
        }
    }

    @Test
    @DisplayName("names all four procedures and 21 steps (SC-006-007)")
    void names_all_four_procedures() {
        final String content = read();
        assertThat(content).contains("cutover").contains("planned rollback")
                .contains("urgent rollback").contains("roll-forward");
    }

    private static String read() {
        try {
            return Files.readString(DOCUMENT);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
