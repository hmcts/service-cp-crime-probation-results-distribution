# Skill: Generate Project Documents

> **Mostly not used in this repository.** The design narrative for
> `service-cp-crime-probation-results-distribution` lives on Confluence — the "Probation Results
> Distribution Service" page in the CRA space — which is the single source of truth. Do **not**
> generate `doc/SOLUTION_BRIEF.md`, `doc/TECHNICAL_DESIGN.md` or `doc/MIGRATION_PLAN.md` here. The
> one design document in the repository is `doc/design/DIGEST.md`: the design's rules, each with its
> row id and no rationale, updated alongside the constitution when the design changes; this skill
> does not generate it. The `doc/` artefacts this skill keeps are listed below; `doc/DEVIATIONS.md` is a
> quality-gate ledger (constitution Principle I), not a document this skill produces — it changes
> only with the code and the pinning test it records.

Generate or update the repository's own documents.

## Documents

| #  | Document           | File                        | Purpose                          |
|----|--------------------|-----------------------------|----------------------------------|
| 1  | API Contracts      | doc/API_CONTRACTS.md        | Inbound message (design §4.2, Appendix B.1), outbound HMPPS publish (design §3.1, §4.4; no outbound schema — §6.6 is parked, H13), and the operations API's OpenAPI document once built (H9) |
| 2  | Runbook            | doc/RUNBOOK.md              | Operations, monitoring, alerts (design §4.8, §9, §10): the dead-letter runbook, resend by `requestId` list, the 6-month stored-body window |
| 3  | Changelog          | doc/CHANGELOG.md            | Version history                  |

## Instructions

When invoked, generate or update the requested documents by:
1. Reading the current codebase to understand what is implemented
2. Reading the existing documents and the design for context
3. Generating accurate documentation that reflects the actual implementation
4. Flagging any drift between docs and code

Never include internal hostnames, keys, personal data or hearing payload content — the repository is
public. (Recorded SIT payloads may be committed as test fixtures; docs never quote them.)

## Output

Return a compact status table:

```
| Document         | Status  | Notes                    |
|------------------|---------|--------------------------|
| API_CONTRACTS    | Updated | Inbound schema described |
| CHANGELOG        | Updated | 3 entries added          |
```
