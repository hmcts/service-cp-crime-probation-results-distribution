package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.EffectiveSwitchState;
import uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult;

/**
 * The HMPPS redaction: the legacy filter (`PF/functions.js` at {@code 122e2152}) ported to one pure
 * function of a hearing payload and the effective youth-switch state (design §4.4, §5.1;
 * FR-001-001 … FR-001-010).
 *
 * <p>The rules run in the legacy's order — R0, R1, (R3, R4 only when the switch is {@code ACTIVE}),
 * R5, R6, R7, R8, R9, R10, R11 — there is no R2, and R12 (which logs the payload) is not ported
 * (DV-09). Every test a rule makes follows JavaScript truthiness, as the legacy evaluates it
 * (FR-001-005). Where the legacy reads through an absent or null value it throws on, the port throws
 * {@link RuleFailureSignal} at the same point and reports {@link RedactionResult.RulesFailure};
 * no guard is added, no rule is skipped, no partial body is returned (P19, FR-001-008).
 *
 * <p>The function performs no I/O, reads no clock, uses no randomness and writes no log line, and it
 * returns the same result for the same inputs (constitution Principle V). It deep-copies the payload
 * once on entry and never mutates the caller's argument (FR-001-002).
 */
// PMD.OnlyOneReturn: the early returns mirror the legacy source's own guard-and-return functions,
// line for line — funnelling them through a single exit would reshape the very control flow the
// parity harness pins (constitution Principle I, bug-for-bug parity; research.md R4).
@SuppressWarnings("PMD.OnlyOneReturn")
public final class HmppsRedaction {

    /**
     * Redacts a hearing payload for HMPPS.
     *
     * @param hearingPayload the hearing payload, in either form; never mutated
     * @param switchState    the effective youth-switch state
     * @return the redaction outcome (FR-001-007)
     */
    public RedactionResult redact(final JsonNode hearingPayload,
                                  final EffectiveSwitchState switchState) {
        final ObjectNode json = (ObjectNode) hearingPayload;

        removePoliceCases(json);
        if (isSjpOrBoxHearing(json)) {
            return new RedactionResult.EmptiedByR1();
        }
        return new RedactionResult.Body(json);
    }

    /** R0 (`PF/functions.js:5-7`): remove {@code policeCases} if it is truthy. */
    private static void removePoliceCases(final ObjectNode json) {
        if (truthy(json.get("policeCases"))) {
            json.remove("policeCases");
        }
    }

    /**
     * R1 (`PF/functions.js:9, 26-28`): the hearing is SJP, box or virtual-box.
     *
     * @param json the payload
     * @return whether R1 empties the payload
     */
    private static boolean isSjpOrBoxHearing(final JsonNode json) {
        final JsonNode hearing = requireHearing(json, "R1");
        return truthy(hearing.get("isSJPHearing"))
                || truthy(hearing.get("isBoxHearing"))
                || truthy(hearing.get("isVirtualBoxHearing"));
    }

    /**
     * The hearing, which the legacy reads as {@code json.hearing} without a guard; an absent or null
     * {@code hearing} throws in the legacy, so it is a rules failure here.
     *
     * @param json   the payload
     * @param ruleId the rule reading the hearing
     * @return the hearing node
     */
    private static JsonNode requireHearing(final JsonNode json, final String ruleId) {
        final JsonNode hearing = json.get("hearing");
        if (hearing == null || hearing.isNull()) {
            throw new RuleFailureSignal(ruleId);
        }
        return hearing;
    }

    /**
     * JavaScript truthiness, as the legacy evaluates every rule's test (FR-001-005 (a)).
     *
     * <p>{@code false}, {@code null}, an absent member, any numeric zero however written, and the
     * empty string are falsy. Everything else is truthy, including an empty array, an empty object
     * and the strings {@code "false"} and {@code "0"}.
     *
     * @param node the node, or {@code null} for an absent member
     * @return whether the node is truthy
     */
    private static boolean truthy(final JsonNode node) {
        if (node == null || node.isNull()) {
            return false;
        }
        if (node.isBoolean()) {
            return node.booleanValue();
        }
        if (node.isNumber()) {
            return node.decimalValue().signum() != 0;
        }
        if (node.isString()) {
            return !node.stringValue().isEmpty();
        }
        return true;
    }
}
