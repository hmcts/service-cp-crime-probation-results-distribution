package uk.gov.hmcts.cp.probationresultsdistribution.domain;

/**
 * A closed vocabulary value whose persisted or wire form is a fixed string, distinct from its Java
 * constant name. Implemented by every enum in this package so that {@link Vocabularies#fromValue}
 * can parse a wire string into its constant and refuse any value outside the set.
 */
public interface WireValued {

    /**
     * The exact string this value takes in the database, on the wire or in a metric label.
     *
     * @return the canonical wire form, never {@code null}
     */
    String wireValue();
}
