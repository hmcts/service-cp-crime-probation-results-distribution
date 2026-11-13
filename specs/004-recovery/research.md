# Phase 0 Research: Operator recovery

FR-004-024 confirmed 2026-10-26 (option B: refuse expired resend claims, no automatic reclaim).
FR-004-049 confirmed as option A: this service owns the guarded rollback CLI runner. FR-004-052
confirmed as option A: persist the pause in the shared store. Remaining sections record
implementation choices and their constraints.

---

## Decision 1 — FR-004-024: an expired `PENDING` resend row, met by another resend

**The conflict** (spec, DIGEST §16): the claim contract ((c), design §4.4) and §4.5's "resend
column" (plus A2-72) say an expired `PENDING` resend row is **reclaimed in place** — owner, token
and expiry rewritten, attempt number kept, then run. A3-56 and the §4.4 "Selects" row say
resend-leg refuses it `resend-in-progress`, "live or expired" — no reclaim. A2-72's worked
procedure for a deferred replay ("run resend-leg, then replay") depends on the reclaim reading,
else a dead resend runner's `PENDING` row is never closed by resend-leg.

**Decision — confirmed 2026-10-26 (option B)**: resend-leg refuses an expired `PENDING` resend row
`resend-in-progress`, exactly as a live one. It does **not** reclaim the row in place.

**Rationale**:
1. FR-004-003 already decided the symmetric case (not itself `NEEDS CLARIFICATION`): a *replay*
   must defer (never admit) over a `PENDING` resend row "whether its lease is live or expired". A
   resend-leg rule that reclaims an expired row would be the one place in the eligibility table
   where "expired" silently means "free to take". US3 scenario 16 covers both live and expired
   resend claims with the same refusal.
2. The Edge Cases section's own named risk: "a reclaim may send again a body the dead runner had
   already sent" — a duplicate the design does not count (A3-115). Refusing is the chosen
   behaviour; the row stays blocked until operator recovery (FR-004-049/FR-004-049a, now also
   FR-004-059 — see Amendment below).
3. No new mechanism needed. `resend-in-progress` is already a defined refusal code (FR-004-022);
   treating "expired" the same as "live" is a one-line predicate (`claim_owner IS NOT NULL`
   regardless of `claim_expires_at`), not a reclaim statement with its own fencing.

**Consequence for A2-72's procedure, as confirmed 2026-10-26**: a `PENDING` resend row left by a
dead runner was closed only by the rollback's close-in-flight statement (FR-004-049a) — shipped as
a runner because no other action does (Decision 2), requiring the operator pause and rollback
procedure. Called out in `quickstart.md`'s operator notes and the runbook (FR-004-047) as a known
gap, not hidden behind a reclaim that might duplicate a send.

**Status**: confirmed 2026-10-26. The conflicting reclaim reading is not implemented; changing it
requires an explicit new decision and acceptance tests.

**Amended 2026-11-09 (H40)**: the consequence above — recovery only through the paused,
whole-intake rollback close-in-flight procedure — is superseded. A single-row command,
`close-expired-resend` (FR-004-059), closes that one row `FAILED(rolled-back)` with no intake
pause; the operator then confirms with HMPPS before resending it (FR-004-023). The refusal itself
(option B, this decision) is unchanged: resend-leg still refuses an expired `PENDING` row
`resend-in-progress`, never reclaiming it. Shutdown now also waits for a resend-leg run in
progress up to the processing deadline (FR-004-060), so a rolling deploy is less likely to leave a
row needing `close-expired-resend` at all.

**Alternatives considered**:
- *Reclaim in place, as (c)/A2-72 read.* Rejected: duplicate-send risk above; contradicts
  FR-004-003's settled symmetric case.
- *Ask at plan time and block.* Rejected: the spec defers this to the owning team as a post-hoc
  confirmation (Dependencies → External → "the owning team", H31), not a planning precondition.

---

## Decision 2 — FR-004-049 / FR-004-049a: who executes the rollback's close-in-flight statement

**The question**: FR-004-049a's statement is fully specified and pinned (`RollbackFencingIT`),
but the design names no §4.8 action or runner that calls it. FR-004-049 asks whether it is (a) a
runner this slice delivers, refused unless intake is paused; (b) an action another slice owns; or
(c) a rollback-runbook step run outside the service (e.g. a hand-run SQL script by an operator
with DBA access).

**Decision — confirmed 2026-10-26 (option A)**: ships as a command-line runner,
`rollback-close-in-flight`, refused `intake-not-paused` unless intake is paused (the same guard
purge-queue uses, FR-004-053), no HTTP exposure (consistent with slice 005 keeping this step off
HTTP).

**Rationale**:
1. Every other rollback-adjacent §4.8 action this slice owns is already a runner (pause/resume,
   purge-queue, replay, submit-hearing, the listings) — a hand-run SQL script would be the one
   rollback step without the rest of §4.8's safety: argument-free, auditable, token-guarded by
   construction via `application`'s existing row-locking/fencing discipline rather than ad-hoc SQL.
2. Option (c)'s risk is exactly what Principle VI exists to prevent: a hand-run statement outside
   the service's transaction discipline cannot guarantee the lock order (request row, then leg
   row) or the fencing rewrite (a fresh `claim_token` on a resend row, same transaction) FR-004-049a
   requires. A runner gets both free from the same JDBC transaction template every other action
   uses.
3. Keeps slice 006's rollback runbook simple: one more runner alongside pause/purge/replay/
   submit-hearing, not a separate out-of-band procedure with its own review/access-control story.

**Scope boundary respected**: answers *who executes the statement*, not the rollback protocol
itself (out of scope). The runner takes no argument beyond confirming intake is paused — it closes
every `PENDING`/`PARKED` row, per FR-004-049a; it does not select a subset.

**Status**: confirmed 2026-10-26. This slice owns the guarded CLI execution path; the operations
API does not expose it.

**Alternatives considered**:
- *Slice 006 owns it.* Rejected: 006 is "production readiness" (alerts, SIT evidence, runbook
  *inputs*), not where application-layer statements with their own pinned fencing test naturally
  live; FR-004-049a is written in this spec's own Functional Requirements.
- *Outside the service (hand-run SQL).* Rejected per the fencing risk above.

---

## Decision 3 — FR-004-052: how the operator pause reaches every replica and survives a restart

**The question**: `ConsumerLifecycleController` (slice 002) holds pause/resume state per pod, in
memory. The operator pause must hold "for the whole rolled-back period" (A2-52) — potentially days
— across 1–2 replicas (O-2) and a pod restart. In-memory, per-pod state cannot do that alone.

**Decision — confirmed 2026-10-26 (option A)**: persist a one-row control table,
`intake_control` (a single fixed-id row: `paused_by_operator boolean NOT NULL DEFAULT false`,
`paused_at timestamptz`, `updated_at timestamptz NOT NULL DEFAULT now()`), added by a new Flyway
migration at this slice's own version number. `ConsumerLifecycleController` keeps its in-memory
flag as the **fast path** for the per-pod decision on every delivery, but on startup — and
thereafter on the same interval as the existing store-probe (`distribution.store.probe-interval`,
default 10 s) — each pod reads this row and reconciles its in-memory flag. Pause/resume write the
row first, under the same transactional discipline as every other write here; the lifecycle
controller reconciles the local flag on its next probe; `probation_intake_suspended{cause="operator"}`
reflects the reconciled, not the requested, state.

**Rationale**:
1. Precedent already set: slice 003's `scheduled_job_status` (keyed by `job_name`, row
   `body-retention`) via its own Flyway migration (`V2`) solves the same shape of problem —
   cross-pod, restart-surviving state outside the three original tables. `intake_control` is the
   same pattern, one row, different control value.
2. No new infrastructure needed. The alternative — flipping `distribution.consumer.enabled` (the
   scaffold's static intake switch, DIGEST §12) — requires a redeploy/Helm change per pause/resume:
   operationally wrong for a quickly reversible action, and it cannot record `paused_at` for this
   constitution's "30 minutes non-terminal" alerting (Principle VI).
3. Not a second cutover lever (Principle IX): the table holds only the operator-pause bit, read by
   nothing outside this service, never compared against `ProbationResultsDistributionService` or
   any App Configuration value — scoped exactly to the spec's "the service's only stop" language.
4. Reconciliation bounds propagation: existing pods honour pause changes by their next successful
   store probe; a restarted pod reads the persisted state before consuming; a read failure keeps
   intake suspended; a database recovery does not clear the operator hold.

**Status**: confirmed 2026-10-26. The shared database row is the operator control; deployment
configuration is not the pause/resume mechanism.

**Alternatives considered**:
- *In-memory only, as slice 002 left it.* Rejected: does not meet A2-52 against O-2's 1–2 replicas
  and a restart.
- *The deployment switch (`distribution.consumer.enabled`).* Rejected per point 2 — right for
  "intake master switch set at deploy time", wrong for a live operator action.
- *A distributed lock / external coordination (e.g. a dedicated Redis key).* Rejected: a new
  external dependency for a service whose Redis use is already narrowly scoped to the payload
  cache (Principle IV); the already-owned, already-migrated database is simpler.

---

## Decision 4 — resend run order (Assumption 12)

**Decision**: resend-leg runs its distinct IDs **one after another**, not concurrently, within one
run. Each ID still gets its own transaction, claim and deadline as the spec requires; "one after
another" describes only the outer loop's scheduling, not a shared transaction.

**Rationale**: the spec leaves this open ("IDs may run one after another or concurrently — the
plan chooses", Assumption 12), tied to slice 005's open question of whether a resend HTTP call
answers synchronously. Sequential is the simpler implementation, needs no thread-pool sizing or
bounded-concurrency property (none exists in the closed `distribution.*` catalogue, §8.1), and
keeps worst-case latency a simple, auditable bound (`cap × per-attempt deadline`) the runbook
(FR-004-047) and any later HTTP wrapper can state without qualification. No acceptance scenario
(including story 3's concurrency races, scenario 17) requires IDs *within one run* to overlap —
the races tested are between separate runs/replays/queue deliveries, unaffected by sequential
single-run execution.

**Alternatives considered**: bounded concurrency (fixed-size pool) — deferred, not rejected: if
post-go-live experience shows the sequential worst case too slow for the cap's default of 100 IDs,
this is a localized change inside `LegResender`'s loop, since each ID's work is already
self-contained (own transaction, claim, deadline).

---

## Decision 5 — the two listings' paging (Assumption 10)

**Decision**: both the request listing and the `dlq-completed` listing take a fixed, code-level
page size (500 rows), no configuration property (the `distribution.*` catalogue is closed, §8.1;
the spec gives neither listing a property of its own). Each listing states explicitly when it has
shown fewer rows than exist (FR-004-043), rather than silently truncating.

**Rationale**: matches Assumption 10 ("the plan fixes the listings' page bound in code"); 500 is
large enough for a single rollback's resend-ID-selection session (a resend run itself caps at 100
distinct IDs) while bounded enough to stay readable on a terminal and in a log line.

---

## Decision 6 — runner invocation mechanism

**Decision**: the same executable's `Application` entry point inspects the first argument before
starting Spring. An action selects `OperatorCommandLine`'s explicit non-web composition root and
`RunnerDispatcher` registry. That root scans no components, so the server, ASB listener and
body-maintenance scheduler are never constructed (FR-004-045). Each invocation runs one action,
closes its context, exits with the done/refused/failed code. Without an action, the service starts
normally. T037 registers pause/resume, purge and rollback; other actions await their wiring.

**Rationale**: this is the shape YOT's and the informant register's own command-line runners
already take (the pattern design_rules.md names to copy); a single dispatch table is simpler to
keep in sync with FR-004-042's "same inputs ... same rules ... same outcomes" guarantee than N
separate profile-conditional bean definitions, since the dispatch table is the one place a new
action is registered. Selecting a separate explicit configuration before context creation prevents
both background consumers and scheduled maintenance from starting, with no new configuration
property or operator-remembered flag. A standalone command reads the persisted pause state before
a guarded action; live replicas keep reconciling their in-memory flag on the store-probe interval.

**Alternatives considered**: a `@ConditionalOnProperty`-gated bean per action — rejected as six
near-identical copies of the same conditional wiring for no behavioural difference; a separate
`main()` per runner (a second JAR/entry point) — rejected, duplicates `Application`'s context
configuration and contradicts FR-004-044's "the pod's own workload identity and network path, with
no role issued for it alone" — read most naturally as "the same deployed artifact, invoked
differently."

---

## T037 native purge capability check (2026-10-27)

The installed Java SDK 7.17.19 has no public native cutoff-delete operation. Published source
shows [7.18.0-beta.1](https://github.com/Azure/azure-sdk-for-java/blob/azure-messaging-servicebus_7.18.0-beta.1/sdk/servicebus/azure-messaging-servicebus/src/main/java/com/azure/messaging/servicebus/ServiceBusReceiverClient.java)
exposed `deleteMessages`/`purgeMessages`, removed again from beta.2, beta.3 and stable 7.18.1. The
old beta predates emulator connection-string support, so a current BOM/SDK upgrade does not
provide a compatible native purge client; no dependency override was introduced. Emulator 1.1.2's
support for native batch-delete is unverified, not asserted unsupported. T037 remains open for a
supported integration and successful cutoff/DLQ smoke proof.

## Standing findings carried from the spec (not re-researched)

Already resolved by the spec's own Clarifications session (2026-10-21); restated here only because
Phase 1 artifacts reference them directly:

- The replay route (`service`/`support`/`copy`) is an audit label only — no eligibility,
  settlement or sending rule may read `origin` (FR-004-005).
- A resend never parks (FR-004-027) — the only run kind here with no delivery to park against.
- `sharedTime` is compared as instants at stored precision, never as strings (FR-004-029).
- A normal (unforced) resend's reason is required as input but persisted and logged nowhere
  (Assumption 4) — flagged for the design owner in the spec itself, carried here unchanged.
- `RequestIdRecipe`'s vector (`0aa5bf35-…` → `e74ed270-0b62-36d9-bb10-12bd6baa5bea`) is pinned now,
  re-taken only when Q20 (results branch landing order) lands (DIGEST §14) — no action needed
  beyond `RequestIdRecipeTest` asserting the current vector.
