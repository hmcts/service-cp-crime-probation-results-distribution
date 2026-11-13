# Data Model: Production readiness and cutover inputs

Phase 1 output for `006-production-readiness`. This slice adds **no table, column, view or
migration** — 002's V1, 003's V2 and 004's V3 are unchanged and read-only from here. What follows
are the typed Java entities this slice originates, and the existing rows/columns it reads without
redefining them.

## Entities this slice originates

### `HearingComparison` (one share of one hearing in the replay week)

| Field | Type | Notes |
|---|---|---|
| `hearingId` | `UUID` | identity, with `hearingDay` and `sharedTime` (FR-006-002) |
| `hearingDay` | `LocalDate` | |
| `sharedTime` | `Instant` | compared as an instant, never as a rendered string (H17 precedent) |
| `class` | `HearingComparisonClass` | see below |
| `differingPaths` | `List<String>` | JSON paths that differ; empty for a match or a registered deviation with no path difference |
| `explanation` | `Optional<ExplanationEntry>` | present once an operator has named a reason |
| `steStatus` | `String` | the STE attempt's `status` (bounded by the existing `LegStatus` vocabulary) |
| `stePayloadSource` | `String` | the STE attempt's `payload_source` |
| `steYouthSwitch` | `String` | the STE attempt's `youth_switch` |
| `steServiceVersion` | `String` | the STE attempt's `service_version`, so a mid-week redeploy is visible (Edge Cases) |
| `unusableReason` | `Optional<String>` | set only when `class = SIT_BODY_UNUSABLE`; names why (cut short, incomplete JSON) |

Never carries a payload value, a body fragment, or any value taken from the compared bytes
themselves (FR-006-012) — only identifiers, the class, the JSON paths' names, and the bounded
column values above.

### `HearingComparisonClass` (enum)

```
MATCH
REGISTERED_DEVIATION   // carries the DV id, e.g. "DV-25"
EXPLAINED_DIFFERENCE
UNEXPLAINED_DIFFERENCE
UNPAIRED_SIT_ONLY
UNPAIRED_STE_ONLY
UNPAIRED_AMBIGUOUS     // re-shared within the week, cannot be paired one-to-one (FR-006-003)
SIT_BODY_UNUSABLE      // cut short or incomplete JSON (FR-006-007)
```

A share classed `NOT_APPLICABLE` in STE with no SIT log line is **not** a `HearingComparison` at
all (Edge Cases: "neither side sent") — it appears only in the week's STE outcome totals, never in
the comparison's own list.

### `ExplanationEntry`

| Field | Type | Notes |
|---|---|---|
| `role` | `String` | the named person/role attaching the reason |
| `reason` | `String` | free text, never echoed into a metric label; sanitised the way `audit_note` already is |
| `coveredPaths` | `List<String>` | the specific JSON paths this entry explains; empty when it covers an unpaired or SIT-body-unusable share as a whole |
| `recordedAt` | `Instant` | |

Stale when it matches no current difference or share (the report MUST say so, not silently drop
it) — a `ReplayReport` field, not a property of the entry itself.

### `ReplayReport`

| Field | Type | Notes |
|---|---|---|
| `week` | `LocalDate` range or label | the chosen SIT week (FR-006-009) |
| `exportDates` | `List<LocalDate>` | when the SIT bodies/payloads were exported |
| `preRunChecks` | `List<PreRunCheckResult>` | FR-006-008 (a)-(e), each pass/fail and recorded regardless |
| `steBuilds` | `List<String>` | every `service_version` seen across the week, for a mid-week redeploy (Edge Cases) |
| `comparisons` | `List<HearingComparison>` | |
| `staleExplanations` | `List<ExplanationEntry>` | entries matching no current difference/share |
| `verdict` | `"blocks cutover" \| "no unexplained differences"` | FR-006-011 |

### `AlertDefinition`

| Field | Type | Notes |
|---|---|---|
| `id` | `int` | 1–11, the design's own numbering (§10.3) |
| `series` | `String` | the Micrometer series name, e.g. `probation_leg_outcomes_total` |
| `labels` | `Map<String, String>` | the bounded label values this alert's condition selects on |
| `condition` | `String` | human-readable condition (the Azure Monitor rule's own logic lives on the ticket, not here) |
| `window` | `Duration` | the evaluation window, where fixed (e.g. 30 minutes, 1 hour) |
| `thresholdStatus` | `"fixed" \| "proposal"` | tuned after go-live where `proposal` (Assumption 4) |
| `ruleType` | `"metric" \| "log-search"` | H36 |
| `routing` | `String` | always "email, owning team, via action group" today (H36) |
| `runbookEntry` | `String` | what the on-call reader does |
| `lapse` | `Optional<String>` | when this alert stops applying (e.g. alert 11, at decommissioning) |

### `CountedClass` (the five known-issue classes, counted never paged)

| Field | Type | Notes |
|---|---|---|
| `name` | `String` | application-only 400s; case-hearing 400s; 500s recovered; 429s recovered; non-200 2xx |
| `series` | `String` | the backing Micrometer series + bounded label value |
| `shownOn` | `"dashboard" \| "reconciliation" \| "both"` | |

### `GoNoGoEvidenceRow`

| Field | Type | Notes |
|---|---|---|
| `section` | `"§9.2" \| "§9.5"` | |
| `row` | `String` | e.g. `"G1"`, `"1"`, `"23"` |
| `check` | `String` | what the row asks |
| `status` | `"built" \| "closed" \| "parked" \| "answered" \| "external"` | this repository's own classification |
| `evidence` | `String` | the named artefact or the evidence-query class/method, for a `built` row; the design reference, for `closed`/`answered`/`parked` |
| `supplier` | `String` | this repository, HMPPS, operations, DevOps, the business, … — never "this repository" for a row another team supplies (FR-006-031's own rule) |
| `obtainedWhere` | `"once in SIT" \| "in STE" \| "in each environment"` | |

### `RunbookInputStep`

| Field | Type | Notes |
|---|---|---|
| `procedure` | `"cutover" \| "planned-rollback" \| "urgent-rollback" \| "roll-forward"` | |
| `stepNumber` | `String` | the design's own numbering, including lettered sub-steps (`"2a"`, `"6a"`) |
| `actor` | `"DevOps" \| "operations" \| "owning-team"` | |
| `action` | `String` | what is done, in the design's words |
| `serviceCapability` | `Optional<String>` | the CLI runner or operations-API action used, if any (e.g. `pause-intake`, `replay`) |
| `readBack` | `String` | the evidence query or recorded output that proves the step; MUST be pinned by a test (FR-006-046) |
| `precedes` | `Optional<String>` | the step number this one must precede, where ordering is load-bearing |

Every environment-specific value (subscription, topic, configuration store, label, host, resource
name) is a **named placeholder** in every field above — never a real value (FR-006-037, FR-006-050).

## Entities already owned by 002/003/004 (read, never redefined)

- **`outbound_message_latest`** (002's view): the source of every "current state" read in this
  slice — the go/no-go evidence, the daily reconciliation and the decommissioning evidence all read
  it, never `processed_request.status` directly (FR-006-023, constitution row A2-49).
- **`processed_request`**: read for admission evidence (row 1), the replay-route audit trail (row
  21), and the rollback read-backs (`FAILED(rolled-back)`, `COMPLETED` unchanged).
- **`scheduled_job_status`** (003's V2, H38): the `body-retention` row's `last_success_at`, read for
  alert 9's 48-hour condition — this slice's alert catalogue entry for alert 9 cites it, it does not
  re-read it independently of 003's own gauge.
- **`outbound_body`**: read, never written, by the replay comparison — the STE side of the
  comparison is each attempt's stored body, read through the same digest/body pairing 002 already
  guarantees (H39).

## State transitions

None. Every entity this slice originates is a read, or a report over reads; nothing here has a
lifecycle of its own beyond "exists in this run's report" or "exists in the catalogue." The
`ReplayReport`'s `verdict` is derived fresh each run from its `comparisons`, never stored or
transitioned.
