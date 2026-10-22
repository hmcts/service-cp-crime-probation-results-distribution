package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.JsonNodeFactory;

/**
 * Pins the two domain types this slice originates: the effective youth-switch state the redaction
 * takes (FR-001-001), and the four-way result it returns (FR-001-007; data-model.md).
 */
@DisplayName("domain types")
class DomainTypesTest {

    @Nested
    @DisplayName("EffectiveSwitchState")
    class SwitchState {

        @Test
        @DisplayName("has exactly ACTIVE and INACTIVE")
        void values_should_be_exactly_active_and_inactive() {
            assertThat(EffectiveSwitchState.values())
                    .containsExactly(EffectiveSwitchState.ACTIVE, EffectiveSwitchState.INACTIVE);
        }
    }

    @Nested
    @DisplayName("RedactionResult")
    class Result {

        @Test
        @DisplayName("Body exposes the redacted payload")
        void body_should_expose_its_payload() {
            final JsonNode payload = JsonNodeFactory.instance.objectNode().put("kept", true);
            assertThat(new RedactionResult.Body(payload).payload()).isSameAs(payload);
        }

        @Test
        @DisplayName("RulesFailure exposes its rule id")
        void rules_failure_should_expose_its_rule_id() {
            assertThat(new RedactionResult.RulesFailure("R10").ruleId()).isEqualTo("R10");
        }

        @Test
        @DisplayName("the empty variants construct and are RedactionResult")
        void empty_variants_should_construct() {
            assertThat((RedactionResult) new RedactionResult.EmptiedByR1())
                    .isInstanceOf(RedactionResult.EmptiedByR1.class);
            assertThat((RedactionResult) new RedactionResult.EmptiedByR11())
                    .isInstanceOf(RedactionResult.EmptiedByR11.class);
        }
    }
}
