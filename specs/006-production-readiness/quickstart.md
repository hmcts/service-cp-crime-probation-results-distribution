# Quickstart: Production readiness and cutover inputs

This slice adds no runtime behaviour and no new container to the local stack
(`docker compose up -d`: Postgres, the Service Bus emulator, Redis, WireMock — unchanged). Its
checks are build-time and documentation-time, except the replay comparison, which is run by the
owning team against a real STE stack this repository does not provision.

## Build and test

```bash
./gradlew build    # compile + the full suite + PMD + Checkstyle + the JaCoCo gate
./gradlew test     # the replay comparator's synthetic tests (no Docker needed), the evidence and
                   # reconciliation queries' *IT (Testcontainers Postgres), the alert catalogue's
                   # coverage test
```

New test classes this slice adds, named per `contracts/evidence-and-catalogue-shapes.md` and
`data-model.md` (exact names confirmed at `/speckit.tasks`):
`ReplayComparatorTest`, `ReplayReportPrivacyTest`, `GoNoGoEvidenceQueryIT`,
`DailyReconciliationQueryIT`, `DecommissioningEvidenceQueryIT`, `AlertCatalogueCoverageIT` — all run
under `./gradlew test`, since there is no separate `integrationTest` task in this repository.

## Try it locally

With the local stack running (`docker compose up -d`) and a seeded store (the same seeding helpers
002–004's own `*IT` suites already use):

1. **Run the go/no-go evidence queries** against the seeded store and confirm each returns what the
   corresponding acceptance scenario expects (User Story 2, scenarios 2–4): a row-1 admission, a
   row-21 recovered-by-replay sequence, a row-7 mismatch reported as no-go.

2. **Run the daily reconciliation query** over a day of mixed outcomes — a `COMPLETED` request
   whose latest leg attempt is a failed resend, a leg recovered by a resend, `PARKED`/`PENDING` legs
   in flight, and a dead-letter-only message — and check every count against User Story 5's
   Independent Test figures.

3. **Run the alert catalogue coverage test** and confirm it fails if a series or label name is
   changed or removed anywhere in the codebase — this is the regression the test exists to catch
   (SC-006-005).

4. **Run the replay comparator's synthetic suite** — one example of every `HearingComparisonClass`,
   built as fixtures, no STE or SIT connection required — and confirm the verdict and the per-class
   counts match `ReplayComparatorTest`'s own expectations (User Story 1's Independent Test).

## What the owning team runs, not this repository's build

5. **The real SIT → STE replay** (User Story 1, the real run): choose a week; export its SIT logged
   HMPPS bodies and `EXT_` payloads at once (FR-006-009); seed STE's Redis from the export
   (research.md Decision 1); publish each share onto STE's `probation.requests` queue directly
   (research.md Decision 1 — not a tool this repository ships); once the week has run through STE,
   point the comparison at STE's database and the export file and read its report. Held under the
   corpus controls — SIT data only, never committed, never quoted (Principle VII).

6. **The daily reconciliation, in full**, during the soak: this service's own
   `DailyReconciliationQuery` plus results' own viewstore count for the same UTC day, compared by
   the owning team (research.md Decision 5) — this repository does not call out to results.

## What "done" looks like for this slice

- Every acceptance scenario of the seven user stories passes as a test, or is documented as an
  external step this repository does not execute (the real replay, the real cutover ticket).
- The go/no-go evidence index lists all 33 rows of §9.2 (G1, 1-23) and §9.5 (1-9) (SC-006-006).
- The alert catalogue holds exactly 11 paging alerts and 5 counted classes, with the coverage test
  green (SC-006-004, SC-006-005).
- The runbook inputs cover all 21 numbered steps across the four procedures, each with its
  test-pinned read-back (SC-006-007, FR-006-046).
- The hygiene sweep (SC-006-003, SC-006-008) finds zero personal names, hostnames, environment
  labels, production figures or input values anywhere this slice adds.
- `./gradlew build` is green with the coverage thresholds unchanged — this slice adds queries and
  typed records, not a new exemption.
- Rollback step 4 names its real execution path, 004's `rollback-close-in-flight` CLI action
  (FR-006-040, corrected 2026-11-02), never a hand-run statement.

<!-- token-budget: compacted (level=medium) on 2026-11-13T14:02:15Z; original at quickstart.full.md -->
