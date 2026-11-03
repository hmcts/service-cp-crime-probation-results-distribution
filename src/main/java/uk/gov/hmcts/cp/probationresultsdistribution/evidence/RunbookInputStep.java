package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * One service-side step of the DevOps cutover/rollback runbook (slice 006, FR-006-037,
 * data-model.md). Every environment-specific value the ticket fills in is a named placeholder —
 * never a real value — and never appears here (FR-006-037, FR-006-050).
 *
 * @param procedure         {@code "cutover"}, {@code "planned-rollback"}, {@code "urgent-rollback"}
 *                          or {@code "roll-forward"}
 * @param stepNumber        the design's own numbering, lettered sub-steps included (e.g. "2a", "6a")
 * @param actor             {@code "DevOps"}, {@code "operations"} or {@code "owning-team"}
 * @param action            what is done, in the design's words
 * @param serviceCapability the CLI runner or operations-API action used, if any
 * @param readBack          the evidence query or recorded action output that proves the step,
 *                          pinned by a test (FR-006-046)
 * @param precedes          the step number this one must precede, where ordering is load-bearing
 */
public record RunbookInputStep(
        String procedure,
        String stepNumber,
        String actor,
        String action,
        Optional<String> serviceCapability,
        String readBack,
        Optional<String> precedes) {

    /** The closed set of procedures. */
    public static final Set<String> PROCEDURES =
            Set.of("cutover", "planned-rollback", "urgent-rollback", "roll-forward");

    /** The closed set of actors. */
    public static final Set<String> ACTORS = Set.of("DevOps", "operations", "owning-team");

    /** Rejects null components and a procedure or actor outside its closed set. */
    public RunbookInputStep {
        Objects.requireNonNull(procedure, "procedure");
        Objects.requireNonNull(stepNumber, "stepNumber");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(action, "action");
        Objects.requireNonNull(serviceCapability, "serviceCapability");
        Objects.requireNonNull(readBack, "readBack");
        Objects.requireNonNull(precedes, "precedes");
        if (!PROCEDURES.contains(procedure)) {
            throw new IllegalArgumentException("unknown runbook procedure: " + procedure);
        }
        if (!ACTORS.contains(actor)) {
            throw new IllegalArgumentException("unknown runbook actor: " + actor);
        }
    }
}
