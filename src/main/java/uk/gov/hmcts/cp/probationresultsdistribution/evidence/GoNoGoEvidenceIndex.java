package uk.gov.hmcts.cp.probationresultsdistribution.evidence;

import java.util.List;

/**
 * The full go/no-go evidence index: one entry per row of design §9.2 (G1, rows 1-23) and §9.5
 * (rows 1-9) — 33 entries in total (slice 006, FR-006-031, FR-006-032). A row the design already
 * closed, parked or answered carries that status and its design reference. A row this repository
 * builds names the class and method that proves it, test-pinned. Every other row names its
 * external supplier, never this repository's own authority (FR-006-035).
 *
 * <p>Row 9 of §9.5 names {@code AlertCatalogue} alert 11's query mechanics, completed by the
 * both-paths alert (User Story 6, later in this same slice); its entry is updated in place once
 * that phase lands, rather than carried as a separate index. Rows 2-5 of §9.5 are complete:
 * {@code DailyReconciliationQuery} (User Story 5) landed before this index's final pass.
 */
public final class GoNoGoEvidenceIndex {

    private static final String BUILT = "built";
    private static final String CLOSED = "closed";
    private static final String ANSWERED = "answered";
    private static final String EXTERNAL = "external";
    private static final String DESIGN_DECISION = "this repository (design decision)";
    private static final String ONCE_IN_SIT = "once in SIT";
    private static final String IN_EACH_ENVIRONMENT = "in each environment";
    private static final String THIS_REPOSITORY = "this repository";
    private static final String CLOSED_NO_VEP_LEG = "closed, no VEP leg (H5)";
    private static final String RESERVED = "Reserved";

    private GoNoGoEvidenceIndex() {
    }

    /**
     * The full catalogue, in design order: §9.2's G1 and rows 1-23, then §9.5's rows 1-9.
     *
     * @return the 33-row index
     */
    public static List<GoNoGoEvidenceRow> rows() {
        return List.of(
                row92("G1", "Outbound schema validation gate", "parked",
                        "parked, no schema validation in this release (H13)",
                        DESIGN_DECISION, ONCE_IN_SIT),
                row92("1", "Results' healthcheck green; one real SIT share admitted and sent HMPPS",
                        BUILT,
                        "GoNoGoEvidenceQuery.resultsShareEvidence()",
                        "the results team (producer, healthcheck); this repository (evidence query)",
                        ONCE_IN_SIT),
                row92("2", "Operations/DevOps precondition", EXTERNAL,
                        "the cutover ticket and runbook (A3-102)", "operations; DevOps",
                        IN_EACH_ENVIRONMENT),
                row92("3", "Readiness is UP; config review", BUILT,
                        "the readiness probe (/actuator/health/readiness, store-only); the config "
                                + "review",
                        "the deployment, through the readiness probe; the deployment reviewer",
                        IN_EACH_ENVIRONMENT),
                row92("4", "001's SIT-data tests, registered deviations only", EXTERNAL,
                        "001-parity-kernel's own CI run", "001", ONCE_IN_SIT),
                row92("5", "The replay report's verdict", BUILT,
                        "ReplayComparator/ReplayReport (User Story 1), FR-006-011",
                        "this repository; the owning team", "in STE"),
                row92("6", "Outbound contract validator", CLOSED, "closed (A3-108)",
                        DESIGN_DECISION, ONCE_IN_SIT),
                row92("7", "The youth-switch check", BUILT,
                        "GoNoGoEvidenceQuery.youthSwitchCheck(), FR-006-033; 002's startup log line",
                        THIS_REPOSITORY, IN_EACH_ENVIRONMENT),
                row92("8", "Operations/DevOps precondition", EXTERNAL,
                        "the cutover ticket (A3-102)", "operations; DevOps",
                        IN_EACH_ENVIRONMENT),
                row92("9", "The test call's attempt row, SENT with its status code", BUILT,
                        "GoNoGoEvidenceQuery.testCallEvidence()", "operations; this repository",
                        IN_EACH_ENVIRONMENT),
                row92("10", "VEP schema", CLOSED, CLOSED_NO_VEP_LEG, DESIGN_DECISION, ONCE_IN_SIT),
                row92("11", "The VEP setting", CLOSED,
                        "read; the VEP setting no longer needed (H5)",
                        DESIGN_DECISION, ONCE_IN_SIT),
                row92("12", "Every body has hearing.id, over the whole corpus", EXTERNAL,
                        "001-parity-kernel's own corpus check (Q13)", "001", ONCE_IN_SIT),
                row92("13", "VEP stub", CLOSED, CLOSED_NO_VEP_LEG, DESIGN_DECISION, ONCE_IN_SIT),
                row92("14", RESERVED, ANSWERED, "answered (A3-109)", DESIGN_DECISION, ONCE_IN_SIT),
                row92("15", "Outbound contract-invalid gate", ANSWERED,
                        "no longer a gate (H12, H13)", DESIGN_DECISION, ONCE_IN_SIT),
                row92("16", RESERVED, ANSWERED, "answered (A3-107)", DESIGN_DECISION, ONCE_IN_SIT),
                row92("17", RESERVED, ANSWERED,
                        "answered by H12, confirmed by row 9's status",
                        DESIGN_DECISION, ONCE_IN_SIT),
                row92("18", "Outbound contract validator mode", CLOSED, "closed (A3-108)",
                        DESIGN_DECISION, ONCE_IN_SIT),
                row92("19", "VEP key", CLOSED, CLOSED_NO_VEP_LEG, DESIGN_DECISION, ONCE_IN_SIT),
                row92("20", RESERVED, ANSWERED, "settled (Q18, A3-100)", DESIGN_DECISION,
                        ONCE_IN_SIT),
                row92("21", "SIT negative tests: leg-exhausted, then recovered by a replay",
                        BUILT, "GoNoGoEvidenceQuery.recoveryEvidence()",
                        "operations in SIT; this repository", ONCE_IN_SIT),
                row92("22", "The environment's cutover ticket and runbook", EXTERNAL,
                        "the cutover ticket (A3-102)", "DevOps", IN_EACH_ENVIRONMENT),
                row92("23", "The pod started with the system user id; rows 7 and 9 succeeded with it",
                        BUILT,
                        "the startup validator's system-user check; "
                                + "GoNoGoEvidenceQuery.testCallEvidence()/youthSwitchCheck()",
                        "the platform (the patch and Key Vault); this repository",
                        IN_EACH_ENVIRONMENT),
                row95("1", "Consuming, queue draining, dead-letter queue empty", BUILT,
                        "probation_servicebus_up; the dead-letter queue depth metric",
                        "service metrics; the platform's queue metrics", IN_EACH_ENVIRONMENT),
                row95("2", "The daily reconciliation", BUILT,
                        "DailyReconciliationQuery (User Story 5), FR-006-023 to FR-006-026",
                        THIS_REPOSITORY, IN_EACH_ENVIRONMENT),
                row95("3", "Every request terminal by day's end; oldest-non-terminal age below alert",
                        BUILT, "DailyReconciliationQuery (User Story 5); "
                                + "probation_oldest_non_terminal_age", "this repository; metric",
                        IN_EACH_ENVIRONMENT),
                row95("4", "NOT_APPLICABLE by reason, understood before judged", BUILT,
                        "DailyReconciliationQuery (User Story 5)", THIS_REPOSITORY,
                        IN_EACH_ENVIRONMENT),
                row95("5", "FR-006-033 and FR-006-034", BUILT,
                        "GoNoGoEvidenceQuery.youthSwitchCheck(); "
                                + "probation_youth_switch_unreadable_total{cause, defaulted}",
                        THIS_REPOSITORY, IN_EACH_ENVIRONMENT),
                row95("6", "HMPPS confirms receipt (beyond 2xx)", EXTERNAL,
                        "HMPPS's own confirmation", "HMPPS", IN_EACH_ENVIRONMENT),
                row95("7", "VEP readiness", CLOSED, CLOSED_NO_VEP_LEG, DESIGN_DECISION, ONCE_IN_SIT),
                row95("8", "The replay report", BUILT,
                        "ReplayComparator/ReplayReport (User Story 1), FR-006-011",
                        THIS_REPOSITORY, "in STE"),
                row95("9", "Legacy function app inactive after cutover", BUILT,
                        "AlertCatalogue alert 11; DailyReconciliationQuery's legacy-silence check "
                                + "(User Story 6)",
                        THIS_REPOSITORY, IN_EACH_ENVIRONMENT));
    }

    private static GoNoGoEvidenceRow row92(final String row, final String check,
            final String status, final String evidence, final String supplier,
            final String obtainedWhere) {
        return new GoNoGoEvidenceRow("§9.2", row, check, status, evidence, supplier, obtainedWhere);
    }

    private static GoNoGoEvidenceRow row95(final String row, final String check,
            final String status, final String evidence, final String supplier,
            final String obtainedWhere) {
        return new GoNoGoEvidenceRow("§9.5", row, check, status, evidence, supplier, obtainedWhere);
    }
}
