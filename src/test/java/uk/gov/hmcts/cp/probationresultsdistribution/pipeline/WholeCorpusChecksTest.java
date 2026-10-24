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
import tools.jackson.databind.JsonNode;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase;
import uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support.ParityCase.Form;

/**
 * The two whole-corpus checks that fail the build when they fail (FR-001-027; SC-001-005,
 * SC-001-006; §6.3, §5.3):
 *
 * <ul>
 *   <li>(a) every body the redaction produces over the corpus, in every switch state and form,
 *       carries a non-null {@code hearing.id} — APIM builds the backend path from it (Q13; §9.2 row
 *       12);</li>
 *   <li>(b) each carried guard — {@code defendants}, {@code offences}, {@code courtOrderOffences},
 *       {@code courtOrderOffence.offence} and {@code judicialResults} (P19), and
 *       {@code subject.masterDefendant} (P18) — is reached by at least one corpus case in which that
 *       member is missing where the rule reads it, or has an accepted-risk entry in
 *       {@code doc/DEVIATIONS.md} (FR-001-035).</li>
 * </ul>
 *
 * <p>No corpus value appears here: only the {@code hearing.id}'s presence is asserted (never its
 * value), and guard coverage is asserted by case id and register token (FR-001-031).
 */
@DisplayName("Whole-corpus checks")
class WholeCorpusChecksTest {

    private static final Path REGISTER = Path.of("doc", "DEVIATIONS.md");

    private final HmppsRedaction redaction = new HmppsRedaction();

    @Test
    @DisplayName("every body produced over the corpus carries a non-null hearing.id (SC-001-005)")
    void redact_overTheCorpus_should_produce_bodies_that_carry_a_non_null_hearing_id() {
        final List<ParityCase> cases = ParityCase.loadAll(corpusRoot());

        for (final ParityCase parityCase : cases) {
            for (final Form form : parityCase.formsHeld()) {
                for (final EffectiveSwitchState state : EffectiveSwitchState.values()) {
                    final RedactionResult result =
                            redaction.redact(parityCase.inputs().get(form), state);
                    if (result instanceof RedactionResult.Body body) {
                        final JsonNode hearingId = body.payload().get("hearing").get("id");
                        assertThat(hearingId)
                                .as("case %s (%s/%s): the produced body must carry hearing.id",
                                        parityCase.caseId(), form, state)
                                .isNotNull();
                        assertThat(hearingId.isNull())
                                .as("case %s (%s/%s): hearing.id must not be null",
                                        parityCase.caseId(), form, state)
                                .isFalse();
                    }
                }
            }
        }
    }

    @Test
    @DisplayName("the judicialResults guard (P19) is reached by case 4")
    void redact_case04_should_fail_in_R10_where_judicialResults_is_missing() {
        assertThat(outcome("case-04-no-judicial-results", Form.CACHE, EffectiveSwitchState.ACTIVE))
                .isInstanceOf(RedactionResult.RulesFailure.class);
        assertThat(((RedactionResult.RulesFailure)
                outcome("case-04-no-judicial-results", Form.CACHE, EffectiveSwitchState.ACTIVE))
                .ruleId())
                .isEqualTo("R10");
    }

    @Test
    @DisplayName("the courtOrderOffences guard (P19) is reached by case 5")
    void redact_case05_should_fail_in_R7_where_courtOrderOffences_is_missing() {
        final RedactionResult result =
                outcome("case-05-no-court-order-offences", Form.CACHE, EffectiveSwitchState.INACTIVE);

        assertThat(result).isInstanceOf(RedactionResult.RulesFailure.class);
        assertThat(((RedactionResult.RulesFailure) result).ruleId()).isEqualTo("R7");
    }

    @Test
    @DisplayName("the masterDefendant guard (P18) is reached by case 3")
    void redact_case03_should_remove_the_application_under_active() {
        assertThat(outcome("case-03-no-masterdefendant", Form.CACHE, EffectiveSwitchState.ACTIVE))
                .isInstanceOf(RedactionResult.EmptiedByR11.class);
    }

    @Test
    @DisplayName("every carried guard with no corpus case is an accepted risk in the register")
    void register_should_record_an_accepted_risk_for_each_unreached_guard() {
        final String acceptedRisks = acceptedRisksSection();

        assertThat(acceptedRisks)
                .as("the `defendants` guard (P19) must be an accepted risk")
                .contains("`defendants`");
        assertThat(acceptedRisks)
                .as("the `offences` guard (P19) must be an accepted risk")
                .contains("`offences`");
        assertThat(acceptedRisks)
                .as("the `courtOrderOffence.offence` guard (P19) must be an accepted risk")
                .contains("`courtOrderOffence.offence`");
    }

    private RedactionResult outcome(
            final String caseId, final Form form, final EffectiveSwitchState state) {
        final ParityCase parityCase = ParityCase.loadAll(corpusRoot()).stream()
                .filter(c -> c.caseId().equals(caseId))
                .findFirst()
                .orElseThrow(() -> new AssertionError("corpus case not found: " + caseId));
        return redaction.redact(parityCase.inputs().get(form), state);
    }

    private static String acceptedRisksSection() {
        final String register = readRegister();
        final int heading = register.indexOf("## Accepted risks");
        if (heading < 0) {
            throw new AssertionError("the register has no '## Accepted risks' section");
        }
        return register.substring(heading);
    }

    private static String readRegister() {
        try {
            return Files.readString(REGISTER);
        } catch (final IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path corpusRoot() {
        final URL url = WholeCorpusChecksTest.class.getResource("/corpus");
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
