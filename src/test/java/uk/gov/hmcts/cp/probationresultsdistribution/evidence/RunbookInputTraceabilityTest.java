package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Pins FR-006-046: every service-side read-back this slice names is an evidence query already
 * built (US2), a 004 action's already-pinned recorded output, or an openly-tracked forward
 * reference to a later story of this same slice — never a bare statement with nothing behind it.
 */
@DisplayName("RunbookInputStep traceability")
class RunbookInputTraceabilityTest {

    private static final List<String> PINNED_TESTS = List.of("RollbackFencingIT",
            "PurgeQueueActionTest", "ReplayActionTest", "SubmitHearingActionTest",
            "LegResenderTest", "IntakePauseResumeTest", "DailyReconciliationQueryIT",
            "LegacySilenceCheckSharedDefinitionTest");
    private static final String FORWARD_REFERENCE_MARKER = "not yet built — corrected once";

    @Test
    @DisplayName("carries 21 steps across the four procedures")
    void carries_21_steps() {
        assertThat(RunbookInputs.steps()).hasSize(21);
    }

    @Test
    @DisplayName("every step naming a service capability has a read-back traceable to a test, "
            + "or is an openly-tracked forward reference")
    void every_service_capability_step_has_a_traceable_read_back() {
        RunbookInputs.steps().stream()
                .filter(step -> step.serviceCapability().isPresent())
                .forEach(step -> assertThat(step.readBack())
                        .as("step %s/%s", step.procedure(), step.stepNumber())
                        .satisfiesAnyOf(
                                readBack -> assertThat(readBack).contains("GoNoGoEvidenceQuery"),
                                readBack -> assertThat(PINNED_TESTS)
                                        .anyMatch(readBack::contains),
                                readBack -> assertThat(readBack).contains(FORWARD_REFERENCE_MARKER)));
    }

    @Test
    @DisplayName("every step's procedure and actor are in the closed sets RunbookInputStep enforces")
    void every_step_has_a_valid_procedure_and_actor() {
        RunbookInputs.steps().forEach(step -> {
            assertThat(RunbookInputStep.PROCEDURES).contains(step.procedure());
            assertThat(RunbookInputStep.ACTORS).contains(step.actor());
        });
    }
}
