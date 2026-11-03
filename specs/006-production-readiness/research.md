# Phase 0 Research: Production readiness and cutover inputs

All three `[NEEDS CLARIFICATION]` markers the spec carried are resolved in the spec's own
2026-11-02 Clarifications session (FR-006-010, FR-006-024, FR-006-032). This file records the
planning-level decisions those answers, and the rest of the spec, leave to `/speckit.plan` — each
with its rationale and the alternative rejected.

---

## Decision 1 — the replay's injection mechanism is not code this repository builds

**The question**: FR-006-010 now says the replay publishes a `probation.requests` message
directly, bypassing results. Does this slice build a publisher/injector tool?

**Decision**: No. Assumption 1 already scopes this precisely: "The replay adds only the pairing,
the classes, the explanations and the report" over 001's existing comparator — not the injection
mechanism. Publishing a message onto STE's `probation.requests` queue is an owning-team operational
activity, using existing tooling (the Azure CLI, Service Bus Explorer, or a throwaway script), the
same way this repository's own manual quickstarts already seed test messages without a dedicated
in-repo publisher.

**Rationale**:
1. Nothing in the spec's Functional Requirements asks for a new sender; FR-006-010 describes the
   *mechanism* the replay uses, not a deliverable.
2. The exact shape of a `probation.requests` message is already fully specified by the closed
   inbound schema (constitution Principle III) — the owning team needs no new tool to construct one
   correctly; they need the week's SIT shares re-expressed as that schema, which is a data-export
   and transformation step on the SIT side, not a service-side build task.
3. Building a publisher in this repository would be new code with no test surface of its own (it
   would just prove the Azure SDK works), and Out of Scope explicitly forbids "building or changing
   any ... operator action" beyond what 002–005 already specify.

**Alternatives considered**: a new CLI runner that reads the SIT export and publishes each share —
rejected: it duplicates what a short, disposable script already does, outside this repository's
test-and-review loop, for a one-time-per-environment activity.

---

## Decision 2 — the replay comparison tool is pure logic plus a thin, test-covered read path

**The question**: how does the owning team actually run the comparison against STE's real data and
the SIT export?

**Decision**: The comparator itself (`pipeline/support/ReplayComparator` and its collaborators) is
pure — JSON trees and typed records in, a typed `ReplayReport` out, no I/O — exactly like 001's
existing comparator, and is fully covered by synthetic-input tests that need neither STE nor SIT
(User Story 1's own Independent Test). Running it against STE's real `outbound_body` rows and the
SIT export is a focused, test-suite-shaped activity: a dedicated test class the owning team invokes
directly (`./gradlew test --tests '*SitSteReplayIT'`), reading STE's database through the same
`JdbcClient`/connection-property pattern every other `*IT` in this repository already uses, and the
SIT export from a file path supplied at run time (never committed, per Assumption 2). The exact
class name and its connection-property contract are an implementation detail for `/speckit.tasks`,
not fixed here.

**Rationale**: this keeps the parity-critical logic (the comparator) testable in milliseconds with
no broker, no cache and no real STE, exactly as Principle V requires for the redaction pipeline —
the same discipline 001 already established for comparator-adjacent code. A bespoke CLI runner
would add a new operator-action-shaped surface for a pre-cutover, run-once-per-environment activity
that this repository's existing test-invocation mechanism already serves.

**Alternatives considered**: a new `cli` runner (`replay-compare`), following 004's
`RunnerDispatcher` pattern — rejected for now: 004's runners all mutate state (pause, purge,
resend, submit) or list from the service's own store; this tool reads two stores that are not the
running service's own (STE's, inspected after the fact, and the SIT export file), which is a
different shape of operation. Revisit if the owning team finds the test-invocation mechanism
awkward in practice — the comparator itself does not change either way, only its wrapper.

---

## Decision 3 — the alert catalogue is a typed Java registry, not an external config file

**The question**: Assumption 3 says "the plan chooses their machine-readable form." What form?

**Decision**: `config/AlertCatalogue` — a `List<AlertDefinition>` of Java records, each holding the
backing series and bounded labels, the condition and window, the threshold's status (fixed or
proposal), its Azure Monitor rule type, its routing, its runbook entry and its lapse. No YAML, JSON
or properties file, and no new parsing dependency.

**Rationale**:
1. Constitution Principle IV: "everything this service originates... MUST be typed Java records and
   enums with bounded value sets." An alert catalogue is exactly this shape of thing.
2. No new dependency: `build.gradle` carries no YAML library today (checked: no
   `jackson-dataformat-yaml`, no explicit `snakeyaml`); a config-file catalogue would need one
   purely to parse itself, adding a dependency this repository has otherwise avoided.
3. SC-006-005's "no dead alerts" test and SC-006-004's "all 11 alerts, each with series, condition,
   rule type, runbook entry and routing" are trivial to assert over a `List<AlertDefinition>` in
   plain Java — a config file would need the same assertions after parsing, for no added clarity.
4. The design's own names for these series are already Java constants or proposed Java-shaped names
   (Assumption 8) — a Java registry keeps them as the single source of truth, rather than a second,
   driftable copy in a file.

**Alternatives considered**: a YAML resource under `src/main/resources/alerts/`, parsed at startup
— rejected per points 2–4 above; a Markdown-only catalogue with no code — rejected, because
SC-006-005 requires the catalogue to be test-checkable against the service's real metric
vocabulary, which needs it to exist as data a test can iterate, not prose a human reads.

---

## Decision 4 — go/no-go evidence lives in a new `evidence` package, not `persistence`

**The question**: where do the read-only queries behind the evidence index and the daily
reconciliation live?

**Decision**: a new top-level package, `evidence`, sibling to `persistence`. It depends on nothing
but the existing `JdbcClient` pattern and `domain` (closed enums); nothing depends on it.

**Rationale**: `persistence` is described, by its own `package-info.java` and every class in it, as
owning the *write* paths 002–004 built (`ProcessedRequestRepository`, `OutboundMessageRepository`,
the body-maintenance writes). Every class this slice adds is read-only by construction (FR-006-030:
"MUST be read-only, MUST return counts and identifiers only"). Keeping them in a separate package
makes that invariant visible at the package boundary, not just by convention within one package —
exactly the reasoning `cli` and `api` already followed as new driving adapters rather than folding
into `application`.

**Alternatives considered**: adding the queries to `persistence` directly — rejected, since it
would mix read-only evidence queries with the write-path repositories a reviewer currently trusts
not to read speculatively; a new package under `api` — rejected, since these queries are not HTTP
endpoints and this slice adds none (constitution Principle III; Out of Scope).

---

## Decision 5 — results' daily share count is supplied externally, not queried by this service

**The question**: FR-006-024 names a viewstore query over results' own store as the count source.
Does this service call it?

**Decision**: No. This service's own `DailyReconciliationQuery` (`evidence` package) produces only
its own side of the comparison — requests received and each leg's latest state, from its own store.
Results' daily share count, from the viewstore query the clarification names, is obtained by the
owning team through results' own tooling and compared against this service's figures as part of the
daily reconciliation *process* (User Story 5), not as a service-to-service call this repository
builds.

**Rationale**: adding an HTTP (or database) client from this service to results' viewstore would be
a new external integration — exactly what constitution Principle IX and this slice's Out of Scope
forbid ("a service-side read of the Results switch, or any second lever"; "building or changing any
... property"). The design never asks this service to read results' store; it asks the *daily
reconciliation* — a process the owning team runs — to compare the two numbers. This service's job
is to make its own number trustworthy and query-able, not to go and fetch the other one.

**Alternatives considered**: a scheduled job that polls results' viewstore — rejected outright, same
reasoning; a shared dashboard panel reading both sources — out of scope here (dashboards are
deployed outside this repository, Assumption 3) and does not change what code this repository adds.

---

## Standing findings carried from the spec (not re-researched)

- The comparator, canonicalisation and deviation derivation are 001's; this slice does not
  reimplement them, only extends the surrounding pairing/classing/report.
- The runbook inputs are documentation with named placeholders — no environment-specific value is
  ever correct in this repository (FR-006-037, FR-006-050).
- Rollback step 4's execution path (FR-006-040; 004's FR-004-049a is the statement) is 004's own
  `rollback-close-in-flight` CLI action, built and wired (commits 33787cf/f1e4c1f) after this
  slice's spec was first written; superseded 2026-11-02. The runbook-input documentation names
  this runner and its recorded outcome, not a gap.
- Decommissioning's service-side evidence (FR-006-047) reuses the go/no-go evidence index's own
  machinery (§9.5 green, the reconciliation gap, the dead-letter queue count) — it is not a fourth,
  separate query surface.
