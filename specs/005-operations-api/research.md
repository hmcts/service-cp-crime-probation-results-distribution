# Phase 0 Research: Operations API

Unlike 004, this slice's spec leaves very little design-level ambiguity — both `[NEEDS
CLARIFICATION]` markers were resolved in the 2026-10-25 clarification session (accepted-run `202`
for resend-leg; `Second Line Support` as the default caller group, pending H31). Research here is
concentrated on the handful of implementation-technology choices the spec explicitly leaves to this
plan, plus one genuine unknown about `cp-auth-rules-filter`'s action-derivation mechanism that could
not be resolved by reading this repository alone.

---

## Decision 1 — the Drools allow-rules file

**The question**: `authz.http.drools-classpath-pattern` is already configured as
`classpath:/acl/**/*.drl`, but no `.drl` file exists yet anywhere in the repository (`find ...
*.drl` returns nothing). FR-005-020 requires "exactly one explicit allow rule per action,
admitting `Second Line Support` and no other", with `deny-when-no-rules: true` already set (an
absent or non-compiling rules file denies everything, never admits).

**Decision**: add `src/main/resources/acl/operations-api.drl` with exactly two rules, one per
action (`probation-results-distribution-operations.replay`,
`probation-results-distribution-operations.resend-leg`), each matching on the resolved action
string and the caller's `Second Line Support` group membership fact, with no other condition and no
catch-all rule. The forced-resend override is **not** a separate rule (FR-005-019): it is a boolean
field of the resend-leg request, decided after authorisation, never an authorisation fact.

**Rationale**: this is the only artefact genuinely missing for `cp-auth-rules-filter` to do
anything — the Java/Spring wiring (`authz.http.*`) is already complete from the scaffold. Two rules,
one fact each, is the simplest form that satisfies "exactly one explicit allow rule per action" and
nothing else; it needs no Drools decision tables or salience tuning for two flat rules.

**Alternatives considered**: a single rule admitting both actions to one group — rejected, because
FR-005-019 and FR-005-022 both treat `replay` and `resend-leg` as distinct, independently
authorised actions (so that a caller admitted to one cannot reach the other by naming it in a
header), and a shared rule would blur that boundary even though today's group happens to be the
same for both.

---

## Decision 2 — how the action is derived from the path and method (open; needs verification)

**The question**: `authz.http.action-header: "CPP-ACTION"` and `action-required: false` are
already configured, but FR-005-022 requires the service — never the caller's header — to derive the
action from the path and the method. `cp-auth-rules-filter`'s own mechanism for this (an
OpenAPI-operationId lookup, a Spring `HandlerInterceptor` setting a request attribute the filter
reads, or something else) is not established by anything in this repository, and this plan has no
access to the library's own documentation or source beyond its Gradle coordinate
(`uk.gov.hmcts.cp:cp-auth-rules-filter:1.0.7`).

**Decision (provisional)**: assume the filter resolves the action from the OpenAPI document it is
already configured to read (the same `probation-results-distribution-openapi.yaml` the audit filter
uses, via each operation's `operationId`), consistent with `action-required: false` meaning "a
header is optional because the filter can derive the action itself." Each operation's `operationId`
in the filled-in OpenAPI document is set to the exact action string FR-005-019 proposes
(`probation-results-distribution-operations.replay`,
`probation-results-distribution-operations.resend-leg`), so that if the filter does read
`operationId`, the two already agree.

**Flagged for implementation-time verification**: the first task that wires
`OperationsAuthorisationIT` (an integration test with the real `cp-auth-rules-filter` on the
classpath) must confirm this against the library's actual behaviour before relying on it. If the
library instead needs an explicit `HandlerInterceptor` or a per-path properties map, that is a
small, isolated addition to `config/` — it does not change FR-005-022's requirement or this slice's
controller/mapper split, only how the action string reaches the filter.

**Alternatives considered**: none meaningfully different — every option converges on "the filter
needs the action string from somewhere other than the caller," and the OpenAPI `operationId` is the
only candidate source already wired into this service's configuration.

---

## Decision 3 — the service's own audit publisher (FR-005-027, Assumption 6)

**Decision**: implement `OperationsAuditPublisher` as a thin wrapper that calls
`cp-audit-filter-springboot`'s publish mechanism directly (rather than through whatever default
publisher bean the library auto-configures) and propagates a publish failure as a checked outcome
the controller can act on: the request event's failure becomes `503 audit-unavailable` before the
application service is called (FR-005-027); the response event's failure is caught, logged at
ERROR, and counted on `probation_operations_failures_total{reason="audit-response-unpublished"}`
(already a registered label value in `OperationsFailure`) without changing the call's already-decided
outcome.

**Rationale**: the spec states plainly that "the estate audit library's default publisher logs and
discards its own publishing failures," so the only way to meet FR-005-027's "refuse before the
request event's failure" rule is to not use that default — this is the spec's own stated
architecture decision (Assumption 6), not a new one this plan introduces; research here is limited
to confirming the shape (a wrapper around the library's lower-level publish call, not a
reimplementation of the Artemis transport itself) and naming the class.

**Alternatives considered**: catching the default publisher's swallowed failure via a
`try`/exception-translating wrapper around the whole filter chain — rejected, because the spec's
own reading is that the failure is swallowed *inside* the default publisher, before it would reach
any wrapper placed outside it; the fix has to be upstream of that swallow, i.e. a different
publisher, not a catch around it.

---

## Decision 4 — the accepted-run response shape and its correlation story

**Decision**: `ResendLegAcceptedResponse` carries exactly `resendRunId` (the UUID 004's
`LegResender` assigns at claim time) and nothing else — no per-ID detail, since none is
necessarily known yet at `202` time (Clarifications, Session 2026-10-25). The controller's only job
after calling `LegResender` is to read the `resendRunId` off whatever 004 hands back and return it;
it does not wait for, poll, or synthesise a report.

**Rationale**: this directly implements the resolved Clarification; the one design choice left is
confirming the response carries *only* the run id and not a premature, possibly-wrong summary. Per
FR-005-014, the real report (with every per-ID outcome) lives in 004's run-report log line and the
attempt rows, read today via 004's `list-requests` runner — this slice does not duplicate or
anticipate that structure in its own response body.

**Alternatives considered**: returning a `Location` header pointing at a future read-back resource —
rejected for now, since no such resource exists while O-1 is open (no HTTP list endpoint); adding one
later is additive (a new header on an existing `202`), not a breaking change to this decision.

---

## Decision 5 — test strategy for a slice whose dependency isn't built yet

**The situation**: `ReplayAction` and `LegResender` (slice 004's application services) do not exist
in code on this branch yet — only 004's `cli` package skeleton and two vocabulary enums are
implemented so far. This slice's controllers call those services directly (no new port), so they
cannot compile against the real classes today.

**Decision**: write this slice's controller/mapper/authorisation/audit/error-handling tests
(`@WebMvcTest`-based, per `plan.md`'s "Testing" line) against **004's own published interface
shape** — the method signatures and result types `004-recovery`'s `plan.md` and
`contracts/cli-runners.md` already commit to (`ReplayAction.replay(UUID requestId) ->
ReplayOutcome`-shaped, `LegResender.resendLeg(ResendLegCommand) -> ResendRunAccepted`-shaped) —
using Mockito to stand in for the real classes until 004's implementation lands. Treat 004's
application-layer method signatures as a contract this slice depends on, the same way a consumer
depends on a not-yet-deployed producer's documented schema.

**Rationale**: this mirrors exactly how 004 itself was planned against 002's not-yet-merged pipeline
(004's `research.md`, "this slice is implemented against slice 002's merged pipeline") — the
established pattern on this branch is to plan and write interface-level tests against a sibling
slice's committed contract, then wire the real implementation in once it lands, rather than block
planning on implementation order.

**Risk flagged**: if 004's actual method signatures differ from what this plan assumes once
implemented, this slice's mocks and tests need a mechanical update — isolated to the mapper classes
that translate between the controller and 004's services, not a redesign.

---

## Standing findings carried from the spec (not re-researched)

- The error model (RFC 9457, status families, no echo, one `unexpected` fallback) is the sibling
  YOT service's, copied with the API design (Assumption 6) — not reinvented here.
- `probation_operations_failures_total{action, reason}` is already registered in
  `MetricRegistry`/`OutcomeMetrics` with both label enums (`OperationsAction`, `OperationsFailure`)
  already defined in `domain/` — this slice only calls `MetricRegistry.operationsFailure(...)` at
  the right points; it adds no new metric infrastructure.
- `OperationsAuthorisationIT`/`OperationsAuditIT` need the real `cp-auth-rules-filter`/
  `cp-audit-filter-springboot` on the test classpath (already a main dependency) with users-groups
  and the Artemis audit transport stubbed at their HTTP/JMS boundaries — consistent with this
  repository's existing `*IT` conventions (WireMock for HTTP, an embedded/stubbed broker for JMS).
