package uk.gov.hmcts.cp.probationresultsdistribution.pipeline;

import java.io.Serial;

/**
 * Thrown by a redaction rule at the point the legacy JavaScript would throw — a read through an
 * absent or null value the legacy iterates, measures the length of, or reads a property off (the
 * carried missing guards, P19; the {@code subject.masterDefendant} read, P18). It is caught only at
 * {@link HmppsRedaction}'s outermost boundary and mapped to
 * {@link uk.gov.hmcts.cp.probationresultsdistribution.domain.RedactionResult.RulesFailure}; nothing
 * else is caught there, so a genuine defect in the port's own logic still surfaces (research.md R1).
 *
 * <p>No guard is added, no rule is skipped and no partial body is returned (FR-001-008, standing
 * rule §7.0). This is an internal control-flow signal, never part of the service's public surface.
 */
final class RuleFailureSignal extends RuntimeException {

    @Serial
    private static final long serialVersionUID = 1L;

    /** The rule in which the read failed, e.g. {@code R10}. */
    private final String failedRule;

    /**
     * Signals a rules failure in a named rule.
     *
     * @param failedRule the rule id, e.g. {@code R10}
     */
    /* default */ RuleFailureSignal(final String failedRule) {
        super("rule " + failedRule + " read through an absent or null value");
        this.failedRule = failedRule;
    }

    /**
     * The rule in which the read failed.
     *
     * @return the rule id
     */
    /* default */ String ruleId() {
        return failedRule;
    }
}
