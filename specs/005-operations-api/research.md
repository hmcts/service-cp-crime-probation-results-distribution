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

## Decision 2 — how the action is derived from the path and method (CONFIRMED by decompiling the
real library; supersedes the original provisional decision below)

**The question**: `authz.http.action-header: "CPP-ACTION"` and `action-required: false` are
already configured, but FR-005-022 requires the service — never the caller's header — to derive the
action from the path and the method.

**What the real library (`cp-auth-rules-filter:1.0.7`, package `uk.gov.moj.cpp.authz.http`) actually
does**, read directly from its bytecode (no source/docs available, and the earlier provisional
decision's OpenAPI-`operationId` guess was wrong — the filter never reads the OpenAPI document at
all; that file is only `audit.http.openapi-rest-spec`'s, a different library):
`RequestActionResolver.resolve(request, actionHeaderName, path)` picks the action name in this
**priority order**, each one entirely caller-controlled except the last:
1. a `vnd.<token>` vendor suffix on the request's `Content-Type` header, lower-cased;
2. failing that, the first `vnd.<token>` vendor suffix found in a comma-separated `Accept` header;
3. failing that, the configured action header (`CPP-ACTION`) verbatim, if present and non-blank;
4. **only if none of the above is present**, a synthesised default: literally
   `"{HTTP method} {servlet path within the application}"` (e.g.
   `"POST /operations/requests/3fa8.../replay"` — the real path, UUID interpolated, not a template).

`HttpAuthzFilter.doFilter` builds one `Action(name, attributes)` Drools fact from whichever of the
four wins, inserts it alongside an `AuthzPrincipal` fact (`groups: Set<String>` among its fields),
and calls `DroolsAuthzEngine.evaluate(principal, action)`; a rule firing sets `Outcome.success`,
otherwise the filter answers `403`. Case 4's result could in principle still be matched in Drools
with a regex (`Action(name matches "POST /operations/requests/[^/]+/replay")`), but cases 1–3 all
let an unauthenticated caller choose the resolved action themselves by setting a header or a vendor
media type — the exact bypass FR-005-022 exists to close ("a caller admitted to one action cannot
reach the other by naming it").

**Decision**: this slice adds one small `OncePerRequestFilter`
(`OperationsActionResolutionFilter`, `config/` — not `api/`, since it only talks to the servlet
layer and the Drools fact shape, nothing of this slice's own domain), registered with a
`FilterRegistrationBean` ordered **before** `cp-auth-rules-filter`'s own filter and scoped to
`/operations/*`. For a request matching `POST /operations/requests/{requestId}/replay` or
`POST /operations/resend-leg` (matched by this filter's own path/method check, not by trusting
anything in the request), it wraps the request so that, as seen by every filter after it:
`getHeader("CPP-ACTION")` always returns this slice's own fixed action string
(`probation-results-distribution-operations.replay` /
`...resend-leg`, FR-005-019's proposed names, now confirmed reachable), and `getContentType()` /
`getHeader("Accept")` are stripped of any `vnd.` vendor token (forced to plain `application/json`),
so cases 1–2 above can never fire ahead of the header this filter itself set. The two Drools rules
(research.md Decision 1) then match on `Action(name == "...")` by plain literal equality — no regex
needed, and FR-005-019's human-readable proposed action names survive into the real rule file. A
request outside `/operations/**` is untouched.

**Why not just rely on case 4's fallback instead of building a filter**: it is per-instance unique
(the real path, UUID included) and would force every Drools rule to a regex the resend-leg one
doesn't even need; a caller could still hijack the SAME request onto a different, pre-existing
action name via cases 1–3, since this slice does not control whether some other `.drl` file ever
lands on this classpath (`drools-classpath-pattern: classpath:/acl/**/*.drl` loads all of them, not
just this slice's) — closing the bypass at the filter is the only way that holds regardless.

**Risk retired, one risk remains**: the Drools fact types and `DroolsAuthzEngine.evaluate` contract
above are read directly from the library's bytecode, so the rule-matching design is now solid.
What is *not* yet re-confirmed by a running test is `HttpAuthzFilter`'s exact precedence when more
than one of cases 1–3 is present at once in a single request (this slice's own filter prevents that
by construction, so it should not matter) — `OperationsAuthorisationIT` (US4) should still include
one assertion sending a conflicting `CPP-ACTION` header from the caller and confirming it has no
effect, to pin this filter's ordering against the real filter chain, not just against the decompiled
logic read here.

---

### The original provisional decision (superseded, kept for the record)

**Decision (provisional, now known wrong)**: assume the filter resolves the action from the OpenAPI
document it is already configured to read, via each operation's `operationId`. This was wrong: the
library never reads the OpenAPI document for action resolution; see above for the confirmed
mechanism.

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
