# Go/no-go evidence index

The human-readable mirror of `evidence.GoNoGoEvidenceIndex` (slice 006, FR-006-031, FR-006-032).
One row per check of design rev 3.6 §9.2 (G1, rows 1-23) and §9.5 (rows 1-9) — 33 rows in total
(SC-006-006). A row the design already closed, parked or answered carries that status and its
design reference, never a value this repository asserts on its own authority. A `built` row names
the class and method that proves it, pinned by a test over a seeded store
(`GoNoGoEvidenceQueryIT`, `DailyReconciliationQueryIT`). Every other row names its external
supplier.

Row 9 of §9.5 names `AlertCatalogue` alert 11's query mechanics, completed by the both-paths alert
(User Story 6, later in this same slice); its entry is updated in place once that phase lands,
rather than carried as a separate table. Rows 2-5 of §9.5 are complete: `DailyReconciliationQuery`
(User Story 5) landed before this table's final pass.

## §9.2

| Row | Check | Status | Evidence | Supplier | Obtained |
|---|---|---|---|---|---|
| G1 | Outbound schema validation gate | parked | parked, no schema validation in this release (H13) | this repository (design decision) | once in SIT |
| 1 | Results' healthcheck green; one real SIT share admitted and sent HMPPS | built | `GoNoGoEvidenceQuery.resultsShareEvidence()` | the results team (producer, healthcheck); this repository (evidence query) | once in SIT |
| 2 | Operations/DevOps precondition | external | the cutover ticket and runbook (A3-102) | operations; DevOps | in each environment |
| 3 | Readiness is UP; config review | built | the readiness probe (`/actuator/health/readiness`, store-only); the config review | the deployment, through the readiness probe; the deployment reviewer | in each environment |
| 4 | 001's SIT-data tests, registered deviations only | external | 001-parity-kernel's own CI run | 001 | once in SIT |
| 5 | The replay report's verdict | built | `ReplayComparator`/`ReplayReport` (User Story 1), FR-006-011 | this repository; the owning team | in STE |
| 6 | Outbound contract validator | closed | closed (A3-108) | this repository (design decision) | once in SIT |
| 7 | The youth-switch check | built | `GoNoGoEvidenceQuery.youthSwitchCheck()`, FR-006-033; 002's startup log line | this repository | in each environment |
| 8 | Operations/DevOps precondition | external | the cutover ticket (A3-102) | operations; DevOps | in each environment |
| 9 | The test call's attempt row, `SENT` with its status code | built | `GoNoGoEvidenceQuery.testCallEvidence()` | operations; this repository | in each environment |
| 10 | VEP schema | closed | closed, no VEP leg (H5) | this repository (design decision) | once in SIT |
| 11 | The VEP setting | closed | read; the VEP setting no longer needed (H5) | this repository (design decision) | once in SIT |
| 12 | Every body has `hearing.id`, over the whole corpus | external | 001-parity-kernel's own corpus check (Q13) | 001 | once in SIT |
| 13 | VEP stub | closed | closed, no VEP leg (H5) | this repository (design decision) | once in SIT |
| 14 | Reserved | answered | answered (A3-109) | this repository (design decision) | once in SIT |
| 15 | Outbound contract-invalid gate | answered | no longer a gate (H12, H13) | this repository (design decision) | once in SIT |
| 16 | Reserved | answered | answered (A3-107) | this repository (design decision) | once in SIT |
| 17 | Reserved | answered | answered by H12, confirmed by row 9's status | this repository (design decision) | once in SIT |
| 18 | Outbound contract validator mode | closed | closed (A3-108) | this repository (design decision) | once in SIT |
| 19 | VEP key | closed | closed, no VEP leg (H5) | this repository (design decision) | once in SIT |
| 20 | Reserved | answered | settled (Q18, A3-100) | this repository (design decision) | once in SIT |
| 21 | SIT negative tests: leg-exhausted, then recovered by a replay | built | `GoNoGoEvidenceQuery.recoveryEvidence()` | operations in SIT; this repository | once in SIT |
| 22 | The environment's cutover ticket and runbook | external | the cutover ticket (A3-102) | DevOps | in each environment |
| 23 | The pod started with the system user id; rows 7 and 9 succeeded with it | built | the startup validator's system-user check; `GoNoGoEvidenceQuery.testCallEvidence()`/`youthSwitchCheck()` | the platform (the patch and Key Vault); this repository | in each environment |

## §9.5

| Row | Check | Status | Evidence | Supplier | Obtained |
|---|---|---|---|---|---|
| 1 | Consuming, queue draining, dead-letter queue empty | built | `probation_servicebus_up`; the dead-letter queue depth metric | service metrics; the platform's queue metrics | in each environment |
| 2 | The daily reconciliation | built | `DailyReconciliationQuery` (User Story 5), FR-006-023 to FR-006-026 | this repository | in each environment |
| 3 | Every request terminal by day's end; oldest-non-terminal age below alert | built | `DailyReconciliationQuery` (User Story 5); `probation_oldest_non_terminal_age` | this repository; metric | in each environment |
| 4 | `NOT_APPLICABLE` by reason, understood before judged | built | `DailyReconciliationQuery` (User Story 5) | this repository | in each environment |
| 5 | FR-006-033 and FR-006-034 | built | `GoNoGoEvidenceQuery.youthSwitchCheck()`; `probation_youth_switch_unreadable_total{cause, defaulted}` | this repository | in each environment |
| 6 | HMPPS confirms receipt (beyond 2xx) | external | HMPPS's own confirmation | HMPPS | in each environment |
| 7 | VEP readiness | closed | closed, no VEP leg (H5) | this repository (design decision) | once in SIT |
| 8 | The replay report | built | `ReplayComparator`/`ReplayReport` (User Story 1), FR-006-011 | this repository | in STE |
| 9 | Legacy function app inactive after cutover | built | `AlertCatalogue` alert 11; `DailyReconciliationQuery`'s legacy-silence check (User Story 6) | this repository | in each environment |

Row 9's alert and legacy-silence check share one telemetry-line definition
(`evidence.LegacyBothPathsCheck`) and end together with the subscription and the function app's
own alerts at decommissioning (FR-006-020, FR-006-027) — see
[alert-catalogue.md](../operations/alert-catalogue.md)'s own note on alert 11.

## §9.6 row 1 — post-soak decommissioning evidence (User Story 7)

Composed from this index's §9.5 rows and the reconciliation gap figure — see
`evidence.DecommissioningEvidenceQuery.compose()` (User Story 7), pinned by
`DecommissioningEvidenceQueryIT`. It adds no new read path: every input is a result already
obtained from `GoNoGoEvidenceQuery` (User Story 2) or `DailyReconciliationQuery` (User Story 5),
one call per soak day, plus the dead-letter-queue-empty and P36-alerting-deployed flags the
owning team supplies. §9.6 row 1 is accepted only when §9.5 read green for the whole soak period,
the summed gap across every soak day is zero, the dead-letter queue was empty, and P36's alerting
is deployed.

**Decommissioning needs no change to this service's code or configuration** (FR-006-048). At
decommissioning, the legacy-app-active alert (alert 11) and the soak's legacy-silence check end,
together with the legacy subscription and the function app's own alerts; the service's dashboards
become the only ones.

### §9.6's external steps (FR-006-049)

The rest of §9.6, each depending on the per-environment inventory, is external to this
repository — the service's own acceptance evidence above is a precondition for starting them, not
a step this repository executes:

- the inventory itself, per environment;
- the owner's approval that rollback is no longer needed;
- removing the subscription, the function app, its plan (only where no other app shares it), the
  STE container route, obsolete settings and the legacy code;
- updating the ownership record;
- the cost check;
- the VEP back-out, after the business confirms H5 (no VEP leg was ever built here);
- keeping the LAA feed's owner informed: the shared gateway subscription key and the shared
  `EXT_` payload are unchanged by decommissioning, and nothing needs that owner's agreement (D12).
  The gateway subscription key stays in use by this service and by the LAA feed.
