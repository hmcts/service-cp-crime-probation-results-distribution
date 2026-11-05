package uk.gov.hmcts.cp.probationresultsdistribution.pipeline.support;

import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * The components the golden comparator checks by derivation instead of by equality.
 *
 * <p><strong>Why this exists at all.</strong> Every corpus expectation is a recording of the real
 * Node function app (or SIT's own logged HMPPS body) and is the oracle's truth. It is never
 * regenerated and never edited — a golden somebody adjusted to agree with the port has stopped
 * being evidence. So when a deviation is sanctioned and the port deliberately renders a component
 * differently, the recording still carries the legacy rendering and something has to reconcile the
 * two.
 *
 * <p><strong>The mechanism, and what it is not.</strong> It is not an exclusion. Excluding a field
 * would make its golden assertions stop looking at it, and the next mistake in that component would
 * sail through a green suite. Instead each registered component carries a <em>derivation</em>: given
 * the value the oracle recorded, it computes the value the port is now required to produce, and the
 * comparator demands exactly that. The check is as strict as equality was; only the expected string
 * moved.
 *
 * <p>Consequently the register can fail in a third way, and does so loudly: an oracle value the
 * derivation does not describe is reported as a difference rather than waved through, because a
 * field being "registered" is not a licence to stop comparing it.
 *
 * <p><strong>Registered here means registered there.</strong> Nothing belongs in this class that is
 * not a numbered entry of {@code doc/DEVIATIONS.md}, and every entry quotes its number into the
 * failure message so a reader of a red build is one grep from the reasoning and the sign-off.
 *
 * <p>This service's register is <strong>empty</strong> today. The informant pack's
 * {@code hearingStartTime} entry does not belong to this service's domain and was dropped when the
 * pack was copied (slice 001, task T002). This service's first derivation — the case-13
 * switch-read-failure derivation under {@code DV-25} — is a whole-outcome derivation rather than a
 * per-field one, and is added by user story 5 (tasks T047, T048), not here.
 */
public final class RegisteredFieldDeviations {

    /**
     * The register, by the property name the component reaches the wire under.
     *
     * <p>Matched by property name rather than by JSON pointer because the comparator is handed
     * fragments as well as whole documents, and the same component can sit at a different depth in
     * each. Empty in this service until a per-field deviation is registered.
     */
    private static final Map<String, Deviation> REGISTER = Map.of();

    private RegisteredFieldDeviations() {
    }

    /**
     * The deviation registered against a property name, if any.
     *
     * @param propertyName the property name as it reaches the wire
     * @return the deviation, or {@code null} when the property is compared by equality
     */
    public static Deviation forProperty(final String propertyName) {
        return REGISTER.get(propertyName);
    }

    /**
     * One registered component and the derivation that reconciles the port with the oracle.
     *
     * @param reference  the {@code doc/DEVIATIONS.md} entry, quoted into every failure message
     * @param derivation the renderings the port may produce, given the value the oracle recorded;
     *                   empty when the oracle's value is not one this deviation describes
     */
    public record Deviation(String reference, Function<String, List<String>> derivation) {

        /**
         * The renderings the port may produce for a value the oracle recorded.
         *
         * @param oracleValue the value in the golden file
         * @return the permitted renderings, or empty when the oracle's value is undescribed
         */
        public List<String> permittedFor(final String oracleValue) {
            return derivation.apply(oracleValue);
        }
    }
}
