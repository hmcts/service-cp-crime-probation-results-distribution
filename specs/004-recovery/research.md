# Phase 0 Research: Operator recovery

FR-004-024 is confirmed by the 2026-10-26 clarification (option B: refuse expired resend
claims without automatic reclaim). FR-004-049 is confirmed as option A: this service owns the
guarded rollback CLI runner. FR-004-052 is confirmed as option A: persist the pause in the shared store. The remaining sections record implementation choices and their constraints.

---

## Decision 1 — FR-004-024: an expired `PENDING` resend row, met by another resend

**The conflict, as the spec and DIGEST §16 state it**: the claim contract ((c) in design §4.4) and
§4.5's "resend column" (plus A2-72) say an expired `PENDING` resend row is **reclaimed in place** —
owner, token and expiry rewritten, attempt number kept, then run. A3-56 and the §4.4 "Selects" row
say resend-leg refuses it `resend-in-progress`, "live or expired" — no reclaim. A2-72's own worked
procedure for a deferred replay ("run resend-leg, then replay") depends on the reclaim reading,
because otherwise a dead resend runner's `PENDING` row is never closed by resend-leg at all.

**Decision — confirmed 2026-10-26 (option B)**: resend-leg refuses an expired `PENDING` resend row `resend-in-progress`, exactly as it
refuses a live one. It does **not** reclaim the row in place.

**Rationale**:
1. **FR-004-003 already decided the symmetric case and is not itself marked `NEEDS CLARIFICATION`.**
   It requires a *replay* to defer (never admit) over a `PENDING` resend row "whether its lease is
   live or expired". If replay treats live and expired alike, a resend-leg rule that instead
   reclaims an expired row would be the one place in the whole eligibility table where "expired"
   silently means "free to take". US3 scenario 16 now explicitly covers both live and expired
   resend claims with the same refusal.
2. **The risk the Edge Cases section itself names**: "a reclaim may send again a body the dead
   runner had already sent" — a duplicate the design does not count (A3-115). Refusing is the
   chosen behaviour. The row stays blocked until operator recovery through the guarded rollback
   close-in-flight procedure (FR-004-049/FR-004-049a).
3. **It needs no new mechanism.** `resend-in-progress` is already a defined refusal code
   (FR-004-022); treating "expired" the same as "live" for this one check is a one-line predicate
   (`claim_owner IS NOT NULL` regardless of `claim_expires_at`), not a reclaim statement with its own
   fencing.

**Consequence for A2-72's procedure**: a `PENDING` resend row left by
a dead runner is closed only by the rollback's close-in-flight statement (FR-004-049a) — which this
slice ships as a runner precisely because no other action does (Decision 2 confirms this service owns that guarded runner; recovery requires the
operator pause and rollback procedure). This is called out explicitly in `quickstart.md`'s operator-facing
notes and in the runbook text (FR-004-047) as a known gap, not hidden behind a reclaim that might
duplicate a send.

**Status**: confirmed for this feature by the user on 2026-10-26. The conflicting reclaim
reading is not implemented; changing it requires an explicit new decision and acceptance tests.

**Alternatives considered**:
- *Reclaim in place, as (c)/A2-72 read.* Rejected for the duplicate-send risk above and because it
  contradicts FR-004-003's already-settled symmetric case.
- *Ask at plan time and block.* Rejected: the spec explicitly defers this to the owning team as a
  post-hoc confirmation, not a precondition to planning (it is listed under Dependencies →
  "External" → "The owning team (H31)", not under a blocking precondition).

---

## Decision 2 — FR-004-049 / FR-004-049a: who executes the rollback's close-in-flight statement

**The question**: the statement itself (FR-004-049a) is fully specified and pinned
(`RollbackFencingIT`), but the design names no §4.8 action or runner that calls it. FR-004-049 asks
whether it is (a) a runner this slice delivers, refused unless intake is paused; (b) an action
another slice owns; or (c) a step of the rollback runbook run outside the service entirely (e.g. a
hand-run SQL script by an operator with DBA access).

**Decision — confirmed 2026-10-26 (option A)**: this slice ships it as a command-line runner, `rollback-close-in-flight`,
refused `intake-not-paused` unless intake is paused (the same guard purge-queue already uses,
FR-004-053), with no HTTP exposure (consistent with slice 005 "keeping the rollback's close-in-flight
step off HTTP" per Dependencies).

**Rationale**:
1. **Every other rollback-adjacent action in design §4.8 that this slice owns is already a runner**
   (pause/resume, purge-queue, replay, submit-hearing, the listings) — a hand-run SQL script would
   be the one rollback step without the safety the rest of §4.8 gets: argument-free, auditable,
   token-guarded by construction because it reuses `application`'s existing row-locking and
   fencing discipline rather than ad-hoc SQL.
2. **Option (c)'s risk is exactly what Principle VI exists to prevent**: a hand-run statement outside
   the service's transaction discipline cannot guarantee the lock order (request row, then leg row)
   or the fencing rewrite (a fresh `claim_token` on a resend row in the same transaction) that
   FR-004-049a requires. A runner inside the service gets both for free from the same JDBC
   transaction template every other action uses.
3. **It keeps slice 006's rollback runbook simple**: 006's inputs can name one more runner
   alongside pause/purge/replay/submit-hearing, rather than a separate out-of-band procedure with its
   own review and access-control story.

**Scope boundary respected**: this decision answers *who executes the statement*, not the rollback
protocol itself (out of scope, per spec "Out of Scope"). The runner takes no argument beyond
confirming intake is paused — it closes every `PENDING`/`PARKED` row in the database, which is what
FR-004-049a already specifies; it does not select a subset.

**Status**: confirmed for this feature by the user on 2026-10-26. This slice owns the guarded
CLI execution path; the operations API does not expose it.

**Alternatives considered**:
- *Another slice (006) owns it.* Rejected as the default: 006 is explicitly "production readiness"
  (alerts, SIT evidence, runbook *inputs*), not where application-layer statements with their own
  pinned fencing test naturally live; FR-004-049a's statement is written in this spec's own
  Functional Requirements, strongly suggesting this slice is its natural owner.
- *Outside the service (hand-run SQL).* Rejected per the fencing risk above.

---

## Decision 3 — FR-004-052: how the operator pause reaches every replica and survives a restart

**The question**: `ConsumerLifecycleController` (slice 002) holds pause/resume state per pod, in
memory. The operator pause must hold "for the whole rolled-back period" (A2-52) — potentially days —
across 1–2 replicas (O-2) and across a pod restart. In-memory, per-pod state cannot do that alone.

**Decision — confirmed 2026-10-26 (option A)**: persist a one-row control table, `intake_control` (columns: a single fixed-id row,
`paused_by_operator boolean NOT NULL DEFAULT false`, `paused_at timestamptz`, `updated_at
timestamptz NOT NULL DEFAULT now()`), added by a new Flyway migration at this slice's own version
number. `ConsumerLifecycleController` keeps its existing in-memory flag as the **fast path** for the
per-pod decision on every delivery, but on startup — and on a short interval thereafter (same cadence
as the existing store-probe, `distribution.store.probe-interval`, default 10 s) — each pod reads this
row and reconciles its in-memory flag to it. The pause and resume actions write the row first, inside
the same transactional discipline as every other write in this service, and the lifecycle controller reconciles the local
flag on its next probe; `probation_intake_suspended{cause="operator"}` reflects the reconciled, not the requested,
state.

**Rationale**:
1. **Precedent already set.** Slice 003 added `body_maintenance_state`, a single-row control table,
   by its own Flyway migration (`V2`), for exactly the same shape of problem — a cross-pod,
   restart-surviving piece of state the design does not put in the three original tables. This
   slice's `intake_control` is the same pattern, one row, applied to a different control value.
2. **It needs no new infrastructure.** The alternative of flipping `distribution.consumer.enabled`
   (the scaffold's static intake switch, DIGEST §12) requires a redeploy or a Helm value change for
   every pause and resume — operationally wrong for an action operators must be able to take and
   reverse quickly during an active rollback, and it could not record `paused_at` for the "30 minutes
   non-terminal" style alerting this constitution already requires elsewhere (Principle VI).
3. **It does not become a second cutover lever** (constitution Principle IX): the table holds only
   the operator-pause bit, is read by nothing outside this service, and is never compared against
   `ProbationResultsDistributionService` or any App Configuration value. It is scoped exactly to
   "the service's only stop" language the spec's Context section already uses.
4. **Reconciliation bounds propagation.** Existing pods honour pause changes by their next
   successful store probe. A restarted pod reads the persisted state before starting consumption;
   read failure keeps intake suspended. A database recovery does not clear the operator hold.

**Status**: confirmed for this feature by the user on 2026-10-26. The shared database row is the
operator control; deployment configuration is not the pause/resume mechanism.

**Alternatives considered**:
- *In-memory only, as slice 002 left it.* Rejected: does not meet A2-52 ("the whole rolled-back
  period") against O-2's 1–2 replicas and a restart.
- *The deployment switch (`distribution.consumer.enabled`).* Rejected per point 2 above — right
  mechanism for "intake master switch set at deploy time", wrong mechanism for an operator action
  taken and reversed during live operation.
- *A distributed lock / external coordination service (e.g. a dedicated Redis key).* Rejected:
  introduces a new external dependency for a service whose Redis use is already narrowly scoped to
  the payload cache (constitution IV), and the database the service already owns and migrates is a
  simpler, already-audited place for control state.

---

## Decision 4 — resend run order (Assumption 12)

**Decision**: resend-leg runs its distinct IDs **one after another**, not concurrently, within one
run. Each ID still gets its own transaction, claim and deadline exactly as the spec requires; "one
after another" describes only the outer loop's scheduling, not a shared transaction.

**Rationale**: the spec explicitly leaves this open ("IDs may run one after another or
concurrently — the plan chooses", Assumption 12) and ties it to slice 005's still-open question of
whether a resend HTTP call answers synchronously. Sequential execution is the simpler implementation,
needs no thread pool sizing or bounded-concurrency property (there is none in the closed
`distribution.*` catalogue for this, §8.1), and keeps worst-case latency a simple, auditable bound
(`cap × per-attempt deadline`) that the runbook (FR-004-047) and any later HTTP wrapper can state
without qualification. Nothing in the spec's acceptance scenarios (including the concurrency races of
story 3, scenario 17) requires IDs *within one run* to overlap — the races tested are between
separate runs/replays/queue deliveries, which sequential single-run execution does not prevent from
being tested.

**Alternatives considered**: bounded concurrency (e.g. a fixed-size pool) — deferred, not rejected
outright: if operational experience post-go-live shows the worst-case sequential bound is too slow
for the cap's default of 100 IDs, this is a localized change inside `LegResender`'s loop, because
each ID's work is already fully self-contained (its own transaction, claim and deadline).

---

## Decision 5 — the two listings' paging (Assumption 10)

**Decision**: both the request listing and the `dlq-completed` listing take a fixed, code-level page
size (500 rows) with no configuration property (the `distribution.*` catalogue is closed, §8.1, and
the spec gives neither listing a property of its own). Each listing's output states explicitly when
it has shown fewer rows than exist (FR-004-043), rather than silently truncating.

**Rationale**: matches Assumption 10 exactly ("the plan fixes the listings' page bound in code"); 500
is chosen as large enough for a single rollback's resend-ID-selection session (a resend run itself
caps at 100 distinct IDs) while bounded enough that a runner's output stays readable on a terminal
and in a log line.

---

## Decision 6 — runner invocation mechanism

**Decision**: the same executable's `Application` entry point inspects the first argument before
starting Spring. An action selects `OperatorCommandLine`'s explicit non-web composition root and
`RunnerDispatcher` registry. That root scans no components, so the server, ASB listener and
body-maintenance scheduler are never constructed (FR-004-045). Each invocation runs one action,
closes its context and exits with the done/refused/failed code. Without an action, the normal
service starts. T037 registers pause/resume, purge and rollback; other actions await their wiring.

**Rationale**: this is the shape YOT's and the informant register's own command-line runners already
take (named as the pattern to copy in design_rules.md and the spec's own "names the design marks as
proposals" convention for runner names); a single dispatch table is simpler to keep in sync with
`FR-004-042`'s "same inputs ... same rules ... same outcomes" guarantee than N separate
profile-conditional bean definitions, because the dispatch table is the one place a new action is
registered. Selecting a separate explicit configuration before context creation prevents both
background consumers and scheduled maintenance from starting, without adding a configuration
property or requiring operators to remember flags. A standalone command reads the persisted pause
state before a guarded action; live service replicas continue reconciling their in-memory flag on
the store-probe interval.

**Alternatives considered**: a `@ConditionalOnProperty`-gated bean per action — rejected as six near
copies of the same conditional wiring for no behavioural difference; a separate `main()` per runner
(a second JAR/entry point) — rejected, it would duplicate `Application`'s context configuration and
contradicts "the pod's own workload identity and network path, with no role issued for it alone"
(FR-004-044), which reads most naturally as "the same deployed artifact, invoked differently."

---

## T037 native purge capability check (2026-10-27)

The installed Java SDK 7.17.19 has no public native cutoff-delete operation. Published source
artifacts show that [7.18.0-beta.1](https://github.com/Azure/azure-sdk-for-java/blob/azure-messaging-servicebus_7.18.0-beta.1/sdk/servicebus/azure-messaging-servicebus/src/main/java/com/azure/messaging/servicebus/ServiceBusReceiverClient.java)
exposed `deleteMessages`/`purgeMessages`, but those APIs were removed from beta.2, beta.3 and stable
7.18.1. The old beta predates emulator connection-string support. A current BOM/SDK upgrade therefore
does not provide a compatible native purge client. No dependency override was introduced. Emulator
1.1.2's support for the native batch-delete operation remains unverified; it is not asserted to be
unsupported. T037 remains open for a supported integration and successful cutoff/DLQ smoke proof.

## Standing findings carried from the spec (not re-researched)

These are already resolved by the spec's own Clarifications session (2026-10-21) and are restated
here only because Phase 1 artifacts reference them directly:

- The replay route (`service`/`support`/`copy`) is an audit label only — no eligibility, settlement
  or sending rule may read `origin` (FR-004-005).
- A resend never parks (FR-004-027) — the only run kind in this service with no delivery to park
  against.
- `sharedTime` is compared as instants at stored precision, never as strings (FR-004-029).
- A normal (unforced) resend's reason is required as input but persisted and logged nowhere
  (Assumption 4) — flagged for the design owner in the spec itself, carried here unchanged.
- `RequestIdRecipe`'s vector (`0aa5bf35-…` → `e74ed270-0b62-36d9-bb10-12bd6baa5bea`) is pinned now and
  re-taken only when Q20 (the results branch landing order) lands (DIGEST §14) — no action needed
  from this slice beyond `RequestIdRecipeTest` asserting the current vector.
