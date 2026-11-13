# Contract: this slice's outputs to their human consumers

This service has no business REST API (constitution Principle III) and this slice adds no HTTP
surface. Its "interface" is what it hands to the owning team, DevOps and operations: the replay
report, the go/no-go evidence index, the alert catalogue and the runbook inputs. This file is the
authority for their shape until `/speckit.tasks` names the exact classes and documents that realise
them.

## 1. The replay report (`ReplayReport`, User Story 1)

Every run MUST produce a report that:

- names the week and its export dates (FR-006-009);
- records every pre-run check of FR-006-008 (a)-(e), pass or fail, even when a failure stops the
  run before any comparison;
- classes every share in the union of both sides into exactly one of
  `HearingComparisonClass` (data-model.md) — SC-006-001's "100%, none dropped";
- for `UNEXPLAINED_DIFFERENCE` and `SIT_BODY_UNUSABLE`, lists the differing JSON paths or the
  unusable reason, and neither value either side of the path;
- lists every stale `ExplanationEntry` (one that matches no current difference or share) as such,
  not silently;
- ends with exactly one verdict, `"blocks cutover"` or `"no unexplained differences"`
  (SC-006-002);
- contains no value read from a payload or a body anywhere in it (SC-006-003) — identifiers,
  classes, DV ids, counts and JSON paths only.

## 2. The go/no-go evidence index (`doc/go-live/evidence-index.md`, User Story 2)

One row per `GoNoGoEvidenceRow` (data-model.md), covering every row of design §9.2 (G1, 1–23) and
§9.5 (1–9) — SC-006-006's "100%, 32 rows total". For a row this repository supports, the evidence
column names the query class and method a reviewer can run; it is never "met" by this document's
own say-so for a row another team supplies (FR-006-031's own rule — the `supplier` field is never
this repository for an externally-supplied row). The youth-switch default row (7) is re-checkable:
its evidence names the startup log line and a live-read comparison, not a point-in-time snapshot
(SC-006-010).

## 3. The alert catalogue (`config.AlertCatalogue`, User Story 4)

Exactly 11 `AlertDefinition` entries (the design's own numbering, §10.3 as H34 amends it) and
exactly 5 `CountedClass` entries. `AlertCatalogueCoverageIT` (or its equivalent, named at
`/speckit.tasks`) MUST assert, over the real Micrometer registry a representative scenario
populates:

- every series and label value an `AlertDefinition` or `CountedClass` names actually exists
  (SC-006-005 — "no dead alerts"; a renamed or removed series fails this test, not silently);
- every alert's condition evaluated at and around its threshold gives the expected page/no-page
  result (User Story 4's acceptance scenarios 1–10).

**Alert 6, amended by H41 (2026-11-09)**: name `"Gateway refusing"` (not `"Repeated 401s"`), labels
`reason="receiver-rejected"`, `http=~"401|4xx-other"` (a 403 or 404 pages too — each as silent a
credential, key/policy or route failure as a 401), threshold status `fixed` (no longer `proposal`).
As of this plan's last amendment, `config/AlertCatalogue.java` on `main` still carries the
pre-H41 definition — a build gap, not a contract ambiguity; `AlertCatalogueCoverageIT`'s coverage
assertion above applies to alert 6 exactly as it does to the other ten once the code catches up.

## 4. The runbook inputs (`doc/operations/cutover-rollback-runbook-inputs.md`, User Story 3)

One `RunbookInputStep` per numbered step of each of the four procedures (SC-006-007: 2 + 9 + 6 + 4
= 21 steps total), in the design's own order. A step naming a service capability (a CLI runner or
an operations-API action) names it by its existing, already-shipped name (004/005) — this slice
adds no new one. Every read-back is either an `evidence` package query or a recorded action output,
and every one is pinned by a test (FR-006-046) — a read-back with no test is an incomplete step,
not a documentation nit. No environment-specific value appears anywhere in this document; every
place one would go is a named placeholder (FR-006-050; SC-006-008's hygiene sweep covers this file
by name).

## 5. The daily reconciliation (`evidence.DailyReconciliationQuery`, User Story 5)

Returns, per day (UTC calendar day — the 2026-11-02 clarification): requests received; each leg's
`SENT`/`NOT_APPLICABLE`-by-reason/`FAILED`-by-reason/`PARKED`/`PENDING` count, from
`outbound_message_latest` only; the dead-letter-only class, counted separately from anything
replayable; and the expected gap (`PARKED` + `PENDING` counts) beside the oldest-age gauges
(FR-006-025). It does **not** call out to results' own viewstore (research.md Decision 5) — the
results-side count for the same UTC day is supplied by the owning team from their own query and
compared alongside this service's figures as a process step, not a field this query returns.

<!-- token-budget: compacted (level=medium) on 2026-11-13T14:02:15Z; original at evidence-and-catalogue-shapes.full.md -->
