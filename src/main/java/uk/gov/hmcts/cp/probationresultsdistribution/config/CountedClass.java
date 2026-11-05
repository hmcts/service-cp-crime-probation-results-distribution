package uk.gov.hmcts.cp.probationresultsdistribution.config;

import java.util.Objects;
import java.util.Set;

/**
 * One of the five known-issue classes that are counted on the dashboard and in the daily
 * reconciliation, and MUST page no one (slice 006, FR-006-022).
 *
 * @param name     the class's name
 * @param series   the backing Micrometer series, with its bounded label value
 * @param shownOn  {@code "dashboard"}, {@code "reconciliation"} or {@code "both"}
 */
public record CountedClass(String name, String series, String shownOn) {

    /** The closed set {@link #shownOn} may take. */
    public static final Set<String> SHOWN_ON = Set.of("dashboard", "reconciliation", "both");

    /** Rejects null components and a {@code shownOn} outside its closed set. */
    public CountedClass {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(series, "series");
        Objects.requireNonNull(shownOn, "shownOn");
        if (!SHOWN_ON.contains(shownOn)) {
            throw new IllegalArgumentException("unknown shownOn: " + shownOn);
        }
    }
}
