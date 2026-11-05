package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * The one place a wire string is parsed into a closed-vocabulary constant. A value outside the set
 * is an explicit, non-transient failure: there is no silent default (technical-rules, Enums).
 */
public final class Vocabularies {

    private Vocabularies() {
    }

    /**
     * Resolves a wire string to its enum constant, throwing when the value is not in the set.
     *
     * @param type  the vocabulary enum class
     * @param value the wire string to resolve
     * @param <E>   the enum type, which must carry a wire value
     * @return the matching constant
     * @throws IllegalArgumentException when no constant has that wire value
     */
    public static <E extends Enum<E> & WireValued> E fromValue(final Class<E> type,
                                                               final String value) {
        for (final E candidate : type.getEnumConstants()) {
            if (candidate.wireValue().equals(value)) {
                return candidate;
            }
        }
        throw new IllegalArgumentException(
                type.getSimpleName() + " has no value '" + value + "'");
    }
}
