package uk.gov.hmcts.cp.probationresultsdistribution.domain;

import tools.jackson.databind.JsonNode;

/**
 * The redaction's result: exactly one of the four outcomes of FR-001-007 (§4.5; data-model.md).
 *
 * <p>Slice 002 maps these to the HMPPS leg's (status, reason): {@link Body} is sent;
 * {@link EmptiedByR1} is {@code NOT_APPLICABLE(sjp-or-box-hearing)}; {@link EmptiedByR11} is
 * {@code NOT_APPLICABLE(nothing-left-after-redaction)}; {@link RulesFailure} is
 * {@code FAILED(rules-exception)}. When R1 empties the payload the result is {@link EmptiedByR1},
 * even though R11 would later see the same empty payload.
 *
 * <p>The payload crosses the core as a Jackson {@link JsonNode} by design (constitution Principle
 * IV): the HMPPS body is the redacted tree, not bound to a typed model. {@link Body#payload()} is
 * treated as immutable by its holders.
 */
public sealed interface RedactionResult
        permits RedactionResult.Body, RedactionResult.EmptiedByR1,
                RedactionResult.EmptiedByR11, RedactionResult.RulesFailure {

    /**
     * A redacted body to send: the surviving payload, not empty.
     *
     * @param payload the redacted hearing payload
     */
    record Body(JsonNode payload) implements RedactionResult {}

    /** R1 emptied the payload: the hearing is SJP, box or virtual-box. */
    record EmptiedByR1() implements RedactionResult {}

    /** R11 emptied the payload: no prosecution case and no court application is left. */
    record EmptiedByR11() implements RedactionResult {}

    /**
     * A rule threw on the payload's shape (a carried missing guard, P19): no body (FR-001-008).
     *
     * @param ruleId the rule in which the failure occurred, e.g. {@code R10}
     */
    record RulesFailure(String ruleId) implements RedactionResult {}
}
