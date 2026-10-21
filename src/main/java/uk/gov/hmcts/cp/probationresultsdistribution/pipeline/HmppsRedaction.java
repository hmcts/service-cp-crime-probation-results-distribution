package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ArrayNode;
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

    private static final String HEARING = "hearing";
    private static final String POLICE_CASES = "policeCases";
    private static final String PROSECUTION_CASES = "prosecutionCases";
    private static final String COURT_APPLICATIONS = "courtApplications";
    private static final String DEFENDANTS = "defendants";
    private static final String OFFENCES = "offences";
    private static final String OFFENCE = "offence";
    private static final String LAA_APPLN_REFERENCE = "laaApplnReference";
    private static final String COURT_APPLICATION_CASES = "courtApplicationCases";
    private static final String COURT_ORDER = "courtOrder";
    private static final String COURT_ORDER_OFFENCES = "courtOrderOffences";
    private static final String IS_YOUTH = "isYouth";
    private static final String SUBJECT = "subject";
    private static final String MASTER_DEFENDANT = "masterDefendant";
    private static final String JUDICIARY = "judiciary";
    private static final String ASSOCIATED_PERSONS = "associatedPersons";
    private static final String JUDICIAL_RESULTS = "judicialResults";
    private static final String DELEGATED_POWERS = "delegatedPowers";

    /**
     * Redacts a hearing payload for HMPPS.
     *
     * @param hearingPayload the hearing payload, in either form; never mutated
     * @param switchState    the effective youth-switch state
     * @return the redaction outcome (FR-001-007)
     */
    public RedactionResult redact(final JsonNode hearingPayload,
                                  final EffectiveSwitchState switchState) {
        final ObjectNode json = (ObjectNode) hearingPayload.deepCopy();

        try {
            removePoliceCases(json);
            if (isSjpOrBoxHearing(json)) {
                return new RedactionResult.EmptiedByR1();
            }
            if (switchState == EffectiveSwitchState.ACTIVE) {
                removeYouthProsecutionCaseDefendants(json);
                removeYouthCourtApplications(json);
            }
            removeLaaReferenceFromProsecutionCase(json);
            removeLaaReferenceFromApplication(json);
            removeLaaReferenceFromCourtOrder(json);
            removeJudiciary(json);
            removeAssociatedPersons(json);
            removeDelegatedPowers(json);
            if (nothingSurvives(json)) {
                return new RedactionResult.EmptiedByR11();
            }
            return new RedactionResult.Body(json);
        } catch (final RuleFailureSignal signal) {
            return new RedactionResult.RulesFailure(signal.ruleId());
        }
    }

    /**
     * R3 (`PF/functions.js:11-13, 30-39`, {@code ACTIVE} only): remove every prosecution-case
     * defendant whose {@code isYouth} is truthy, then every case left with no defendant.
     * {@code defendants} is read without a guard (P19).
     *
     * @param json the payload
     */
    private static void removeYouthProsecutionCaseDefendants(final JsonNode json) {
        if (!hasAnyTruthyTopLevelMember(json)) {
            return;
        }
        final JsonNode prosecutionCases = json.get(HEARING).get(PROSECUTION_CASES);
        if (!truthy(prosecutionCases)) {
            return;
        }
        final ArrayNode cases = asArrayOrFail(prosecutionCases, "R3");
        for (int caseIndex = cases.size() - 1; caseIndex >= 0; caseIndex--) {
            final ArrayNode defendants =
                    asArrayOrFail(cases.get(caseIndex).get(DEFENDANTS), "R3");
            for (int defendantIndex = defendants.size() - 1; defendantIndex >= 0; defendantIndex--) {
                if (truthy(defendants.get(defendantIndex).get(IS_YOUTH))) {
                    defendants.remove(defendantIndex);
                }
            }
            if (defendants.isEmpty()) {
                cases.remove(caseIndex);
            }
        }
    }

    /**
     * R4 (`PF/functions.js:11-14, 41-47`, {@code ACTIVE} only): keep a court application only if its
     * {@code subject.masterDefendant} is truthy and not a youth. An application with no truthy
     * {@code masterDefendant} is removed (P18); a missing or null {@code subject} throws (P19).
     *
     * @param json the payload
     */
    private static void removeYouthCourtApplications(final JsonNode json) {
        if (!hasAnyTruthyTopLevelMember(json)) {
            return;
        }
        final JsonNode courtApplications = json.get(HEARING).get(COURT_APPLICATIONS);
        if (!truthy(courtApplications)) {
            return;
        }
        final ArrayNode apps = asArrayOrFail(courtApplications, "R4");
        for (int appIndex = apps.size() - 1; appIndex >= 0; appIndex--) {
            final JsonNode subject = apps.get(appIndex).get(SUBJECT);
            if (subject == null || subject.isNull()) {
                throw new RuleFailureSignal("R4");
            }
            final JsonNode masterDefendant = subject.get(MASTER_DEFENDANT);
            final boolean keep =
                    truthy(masterDefendant) && !truthy(masterDefendant.get(IS_YOUTH));
            if (!keep) {
                apps.remove(appIndex);
            }
        }
    }

    /** R0 (`PF/functions.js:5-7`): remove {@code policeCases} if it is truthy. */
    private static void removePoliceCases(final ObjectNode json) {
        if (truthy(json.get(POLICE_CASES))) {
            json.remove(POLICE_CASES);
        }
    }

    /**
     * R5 (`PF/functions.js:15, 54-64`): remove {@code laaApplnReference} from every offence of every
     * prosecution-case defendant. {@code defendants} and {@code offences} are read without a guard
     * (P19).
     *
     * @param json the payload
     */
    private static void removeLaaReferenceFromProsecutionCase(final JsonNode json) {
        if (!hasAnyTruthyTopLevelMember(json)) {
            return;
        }
        final JsonNode prosecutionCases = json.get(HEARING).get(PROSECUTION_CASES);
        if (!truthy(prosecutionCases)) {
            return;
        }
        for (final JsonNode prosecutionCase : asArrayOrFail(prosecutionCases, "R5")) {
            for (final JsonNode defendant
                    : asArrayOrFail(prosecutionCase.get(DEFENDANTS), "R5")) {
                for (final JsonNode offence : asArrayOrFail(defendant.get(OFFENCES), "R5")) {
                    removeField(offence, LAA_APPLN_REFERENCE);
                }
            }
        }
    }

    /**
     * R6 (`PF/functions.js:16, 66-80`): remove {@code laaApplnReference} from each application case's
     * offences, where they are present. Guarded in the legacy.
     *
     * @param json the payload
     */
    private static void removeLaaReferenceFromApplication(final JsonNode json) {
        if (!hasAnyTruthyTopLevelMember(json)) {
            return;
        }
        final JsonNode courtApplications = json.get(HEARING).get(COURT_APPLICATIONS);
        if (!truthy(courtApplications)) {
            return;
        }
        for (final JsonNode courtApplication : asArrayOrFail(courtApplications, "R6")) {
            final JsonNode courtApplicationCases = courtApplication.get(COURT_APPLICATION_CASES);
            if (!truthy(courtApplicationCases)) {
                continue;
            }
            for (final JsonNode courtApplicationCase
                    : asArrayOrFail(courtApplicationCases, "R6")) {
                final JsonNode offences = courtApplicationCase.get(OFFENCES);
                if (!truthy(offences)) {
                    continue;
                }
                for (final JsonNode offence : asArrayOrFail(offences, "R6")) {
                    removeField(offence, LAA_APPLN_REFERENCE);
                }
            }
        }
    }

    /**
     * R7 (`PF/functions.js:17, 83-93`): for each application whose {@code courtOrder} is truthy,
     * remove {@code laaApplnReference} from each court-order offence's {@code offence}.
     * {@code courtOrderOffences} and {@code offence} are read without a guard (P19).
     *
     * @param json the payload
     */
    private static void removeLaaReferenceFromCourtOrder(final JsonNode json) {
        if (!hasAnyTruthyTopLevelMember(json)) {
            return;
        }
        final JsonNode courtApplications = json.get(HEARING).get(COURT_APPLICATIONS);
        if (!truthy(courtApplications)) {
            return;
        }
        for (final JsonNode courtApplication : asArrayOrFail(courtApplications, "R7")) {
            final JsonNode courtOrder = courtApplication.get(COURT_ORDER);
            if (!truthy(courtOrder)) {
                continue;
            }
            for (final JsonNode courtOrderOffence
                    : asArrayOrFail(courtOrder.get(COURT_ORDER_OFFENCES), "R7")) {
                final JsonNode offence = courtOrderOffence.get(OFFENCE);
                if (offence == null || offence.isNull()) {
                    throw new RuleFailureSignal("R7");
                }
                removeField(offence, LAA_APPLN_REFERENCE);
            }
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
        final JsonNode hearing = json.get(HEARING);
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

    /**
     * The payload-level guard the legacy tests before R3–R11 (lodash {@code _.some} over the
     * payload's top-level values): true if and only if at least one top-level member's value is
     * truthy (FR-001-005 (b)).
     *
     * @param json the payload
     * @return whether any top-level member is truthy
     */
    private static boolean hasAnyTruthyTopLevelMember(final JsonNode json) {
        for (final JsonNode value : json) {
            if (truthy(value)) {
                return true;
            }
        }
        return false;
    }

    /**
     * The node as an array, or a rules failure where the legacy iterates, filters or reads the
     * length of an absent, null or non-array value and throws (P19, FR-001-008).
     *
     * @param node   the node to read as an array, or {@code null} for an absent member
     * @param ruleId the rule making the read
     * @return the array node
     */
    private static ArrayNode asArrayOrFail(final JsonNode node, final String ruleId) {
        if (node == null || !node.isArray()) {
            throw new RuleFailureSignal(ruleId);
        }
        return (ArrayNode) node;
    }

    /**
     * R8 (`PF/functions.js:18, 95-102`): remove a top-level {@code judiciary} if the key is present
     * (key presence, whatever the value), and {@code hearing.judiciary} if it is truthy.
     *
     * @param json the payload
     */
    private static void removeJudiciary(final JsonNode json) {
        if (hasAnyTruthyTopLevelMember(json)) {
            removeField(json, JUDICIARY);
        }
        final JsonNode hearing = json.get(HEARING);
        if (hasAnyTruthyTopLevelMember(json) && truthy(hearing.get(JUDICIARY))) {
            removeField(hearing, JUDICIARY);
        }
    }

    /**
     * R9 (`PF/functions.js:19, 104-115`): remove a top-level {@code associatedPersons} if the key is
     * present, and {@code associatedPersons} from every prosecution-case defendant. {@code defendants}
     * is read without a guard (P19); court applications are not touched (P21).
     *
     * @param json the payload
     */
    private static void removeAssociatedPersons(final JsonNode json) {
        if (hasAnyTruthyTopLevelMember(json)) {
            removeField(json, ASSOCIATED_PERSONS);
        }
        if (!hasAnyTruthyTopLevelMember(json)) {
            return;
        }
        final JsonNode prosecutionCases = json.get(HEARING).get(PROSECUTION_CASES);
        if (!truthy(prosecutionCases)) {
            return;
        }
        for (final JsonNode prosecutionCase : asArrayOrFail(prosecutionCases, "R9")) {
            for (final JsonNode defendant
                    : asArrayOrFail(prosecutionCase.get(DEFENDANTS), "R9")) {
                removeField(defendant, ASSOCIATED_PERSONS);
            }
        }
    }

    /**
     * R10 (`PF/functions.js:20, 117-131`): remove {@code delegatedPowers}, if the key is present,
     * from every judicial result of every prosecution-case offence. {@code defendants},
     * {@code offences} and {@code judicialResults} are read without a guard (P19);
     * {@code plea.delegatedPowers} and application offences are untouched (P35, P21).
     *
     * @param json the payload
     */
    private static void removeDelegatedPowers(final JsonNode json) {
        if (!hasAnyTruthyTopLevelMember(json)) {
            return;
        }
        final JsonNode prosecutionCases = json.get(HEARING).get(PROSECUTION_CASES);
        if (!truthy(prosecutionCases)) {
            return;
        }
        final String rule = "R10";
        for (final JsonNode prosecutionCase : asArrayOrFail(prosecutionCases, rule)) {
            for (final JsonNode defendant : asArrayOrFail(prosecutionCase.get(DEFENDANTS), rule)) {
                for (final JsonNode offence : asArrayOrFail(defendant.get(OFFENCES), rule)) {
                    for (final JsonNode judicialResult
                            : asArrayOrFail(offence.get(JUDICIAL_RESULTS), rule)) {
                        removeField(judicialResult, DELEGATED_POWERS);
                    }
                }
            }
        }
    }

    /**
     * Removes a member, mirroring the legacy's {@code delete}: a {@code delete} off a non-object is
     * a silent no-op in the legacy, so a non-object node is left untouched.
     *
     * @param node the node to remove the member from
     * @param name the member name
     */
    private static void removeField(final JsonNode node, final String name) {
        if (node instanceof ObjectNode objectNode) {
            objectNode.remove(name);
        }
    }

    /**
     * R11 (`PF/functions.js:21, 49-52`): the payload survives only if a prosecution case or a court
     * application is left. A present-but-null {@code prosecutionCases} or {@code courtApplications}
     * fails here, because the legacy reads its {@code .length}.
     *
     * @param json the payload
     * @return whether nothing survives, so the payload becomes {@code {}}
     */
    private static boolean nothingSurvives(final JsonNode json) {
        if (!hasAnyTruthyTopLevelMember(json)) {
            return true;
        }
        final JsonNode hearing = json.get(HEARING);
        return !(definedAndNonEmpty(hearing.get(PROSECUTION_CASES), "R11")
                || definedAndNonEmpty(hearing.get(COURT_APPLICATIONS), "R11"));
    }

    /**
     * Mirrors the legacy's {@code list !== undefined && list.length > 0}: an absent member is
     * {@code false} without reading a length; a present member has its length read, which throws on
     * a null (P19).
     *
     * @param node   the member, or {@code null} for an absent member
     * @param ruleId the rule making the read
     * @return whether the member is present and has a positive length
     */
    private static boolean definedAndNonEmpty(final JsonNode node, final String ruleId) {
        if (node == null) {
            return false;
        }
        return lengthOf(node, ruleId) > 0;
    }

    /**
     * The length the legacy reads off a value: an array's size; a throw on a null (JS
     * {@code null.length}); zero for anything else present, which the survival test treats as empty.
     *
     * @param node   the present node
     * @param ruleId the rule making the read
     * @return the length
     */
    private static int lengthOf(final JsonNode node, final String ruleId) {
        if (node.isArray()) {
            return node.size();
        }
        if (node.isNull()) {
            throw new RuleFailureSignal(ruleId);
        }
        return 0;
    }
}
