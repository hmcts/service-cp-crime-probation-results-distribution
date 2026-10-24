# Feature Specification: Production readiness and cutover inputs

**Feature Branch**: `006-production-readiness`
**Created**: 2026-10-21
**Status**: Draft
**Input**: Slice 006 of the slice plan: "Production readiness and cutover inputs — the one-week
SIT → STE replay proof against a stub gateway, the alert rules and dashboards (page versus count),
the go/no-go evidence, the cutover and rollback runbook inputs, the 'both paths' alert and the
decommissioning list; the results producer change and platform provisioning are external
preconditions." Written against design rev 3.6 (decisions H1–H34; §6.4–§6.5, §9.2–§9.6,
§10.1–§10.4, §11), amended on 2026-10-23 by H36–H38 and row A3-118, and `doc/design/DIGEST.md` §2 (quality gate), §13 (observability), §14 (producer
contract), §15 (platform prerequisites and go-live gates) and §16 (open items). Where the two
differ, the design wins.

## Context

Slices 001–005 build the service and its operator actions. This slice adds nothing to the message
path. It turns the design's pre-cutover proof, its observability chapter and its cutover chapter
into artefacts that the owning team (the Resulting Assistant team, H31) can run and check:

1. **The pre-cutover proof** (H21): one week of SIT traffic is replayed into STE, and what the
   service stored there is compared, hearing by hearing, with what the legacy function app logged
   in SIT. Any unexplained difference blocks cutover.
2. **The alerts and the counts** (H34): one catalogue of the eleven alerts that page, the five
   known-issue classes that are only counted, and the dashboard and daily reconciliation that show
   them.
3. **The go/no-go evidence**: one index of every row of §9.2 and §9.5. For each row it says what
   proves the row and who supplies the proof. The evidence the build supplies is pinned by tests.
4. **The runbook inputs** (H22–H25, H32): the service-side steps and read-backs for cutover, the
   planned rollback, the urgent rollback and the roll-forward after a rollback.
5. **The decommissioning inputs** (§9.6): the service-side evidence that the soak is over.

The runbook itself, decommissioning, the results producer change and platform provisioning are
not this repository's work (DIGEST §2). They appear below as dependencies, each with the evidence
that shows it is met.

Two design facts shape every rule below. First, a `SENT` row means that the gateway answered 2xx,
and no more, until HMPPS confirms receipt (§10.4). Second, the HMPPS POST is not idempotent (H12).
So every ordering rule prefers a known gap that can be repaired to a duplicate at HMPPS (H25, H32).

## Clarifications

### Session 2026-10-21

- Q: Does this slice build the cutover and rollback runbook, or decommission anything? → A: No.
  The runbook is the DevOps runbook on each environment's cutover ticket, and decommissioning
  happens outside this repository. This slice supplies their service-side inputs and evidence
  (DIGEST §2; §9.2 row 22; A3-102; §9.6).
- Q: Which §9.2 rows need build support? → A: Rows 1, 3, 4, 5, 7, 9, 12, 21 and 23. Rows 2, 8
  and 22 are for operations only. G1 is parked, and every other row is closed or answered
  (DIGEST §15; H13).
- Q: Which alerts page, and which runs do they read? → A: Exactly the eleven alerts of §10.3 as
  H34 amends it. They read every origin (queue, replay and resend). Every other known issue is
  counted daily. The thresholds are proposals, tuned after go-live (H34; A3-115; A3-116; R1-31).
- Q: Does the leg-failed alert include `receiver-rejected`? Is a 2xx other than 200 alerted?
  → A: No to both. 400s are counted, and case-hearing 400s have a spike alert. 401s have their own
  alert. A non-200 2xx goes to the daily report. `payload-changed` and `rolled-back` stay on the
  leg-failed alert until Q28 is decided (A3-115; A3-116; Q28).
- Q: Is deployed alerting a cutover precondition? → A: It is not a §9.2 row. Deployed alerting,
  which closes P36, is a post-soak acceptance criterion (§9.6 row 1). This spec adds no gate
  (§10.3, the P36 paragraph).
- Q: A leg can have several attempts. Which STE body is compared? → A: The body of the leg's latest
  attempt: the row in the latest-attempt view. An attempt's body is its latest `outbound_body` row
  (§4.6; DIGEST §8).
- Q: What does the replay expect for a hearing whose switch read was defaulted in STE? → A: It is
  listed under DV-25 (§6.5 Report row). STE's default must equal STE's live reading (A3-29), and
  that reading must be inactive, as SIT's was (§6.5 Youth switch row). So the expected body is
  SIT's logged body, by the derivation rule of corpus case 13 (A3-28).
- Q: How is an STE body paired with a SIT logged body? → A: By share, as 001 identifies one: the
  same hearing id, hearing day and `sharedTime`, the `sharedTime` compared as an instant, because
  the two sides may render it differently. A share that cannot be paired one-to-one is unpaired:
  SIT-only, STE-only or ambiguous (FR-001-017; H17; §6.5 Comparison row).
- Q: What is the expected body when SIT's logged body is cut short at the telemetry's trace-length
  limit? → A: None yet. A cut-short body is never the expectation, and where the expectation comes
  from instead is 001's open question (FR-001-021), for the design owner. Until it is answered, the
  replay carries 001's interim rule: no expected body exists for such a share, the executed legacy
  is not used in its place, and the share is reported as SIT body unusable, which blocks cutover
  until an explanation entry covers it. Once FR-001-021 is answered, the replay follows the answer
  (H28; A3-67; FR-001-021).
- Q: Must the stub gateway be reached over https? → A: Yes. The service is deployed in STE as in
  any environment, and `distribution.hmpps.url` is checked at startup as https (DIGEST §12;
  §6.5 Service row).
- Q: Are the replay week's exports committed to this repository? → A: Not by this slice. They are
  inputs to a run, held under the corpus controls: SIT data only, never quoted. The committed SIT
  set is 001's corpus (§6.4; §6.5 Data row; constitution Principle VII).
- Q: Is the replay a build gate? → A: No. It is the pre-cutover proof (§9.2 row 5), and no CI
  build depends on it (constitution Principle I; H21).
- Q: Is the SJP parity check (§9.2 row 20) part of the replay evidence? → A: No. It is settled.
  In SIT and in PRD the legacy subscription filters on `Hearing_Resulted` only (Q18 closed; A3-100).
- Q: How long is the soak, and who runs the daily reconciliation? → A: The length is not fixed in
  advance. The owning team decides it with the other stakeholders, and the owning team runs the
  reconciliation for the whole soak (H31; A3-112).
- Q: Who changes the switches, and what is the switch-off filter? → A: DevOps change them by hand,
  on a ticket for each environment that carries a runbook. The subscription's filter is recorded
  first and then set to `TOGGLED-OFF`. Switching back on restores exactly the recorded filter
  (A3-102; H25; A3-77; A3-86).
- Q: How long after a flag flip does the next step wait? → A: At least 10 minutes, for the
  `FeatureControlGuard` cache to refresh. Reading the flag back for the label does not prove that
  Results has picked the flip up. Routing is proven when the first shares reach the queue once
  Common Platform is up (A3-103; A3-90).
- Q: In an urgent rollback, does the service process the paused messages for shares before T?
  → A: No. Those shares are repaired by a court re-share. Messages for shares after T are purged,
  and intake stays paused until a roll-forward (H32; A3-117 (iii)).
- Q: When does intake resume after a rollback? → A: Only at the end of the roll-forward: purge,
  reconcile since the cut-off, run a full new cutover, then resume (A2-52; A3-117 (ii)).
- Q: Does any readiness or cutover step read the Results switch from the service? → A: No. The
  service never reads it, and pause-intake is its only service-side stop (H26; A3-92; Q15).
- Q: Is the query of the legacy-app-active alert designed, and where does it run? → A: It is
  proposed, not designed: a query every 15 minutes over the last 15 minutes of the legacy function
  app's telemetry for the environment. The telemetry for environments other than SIT and PRD is not
  established, and those environments are shown as not covered (A3-75).
- Q: Do production baselines and figures go into this repository? → A: No. Thresholds are
  proposals, tuned after go-live and set per environment. The repository is public and holds no
  environment-specific values (H34; the repository's public-repository rule).

### Session 2026-10-23

The design owner decided H36 (how the alerts are delivered), H37 (three months ahead) and H38 (the
body-retention job's last success kept in the database), with row A3-118 (alerting when partitions
are not being created). Slice 003 answers its open question 2 with them (003's AS-15).

- Q: What is alert 9's condition now? → A: The DEFAULT partition not empty; any `create` or `drop`
  failure; fewer than two consecutive months ahead with a partition (H37: three are kept); more than
  48 hours since the last successful run (H38); any pod reporting `-1` (a failed database read); or a
  body gauge with no sample in the window. Every pod reads the three body gauges from the database,
  so the rule reads each pod's latest sample before it aggregates (A3-118). Applied in alert 9,
  FR-006-018 and FR-006-053.
- Q: How are the alerts delivered? → A: H36. Nothing scrapes the pods; the App Insights Java agent
  exports the service's meters to `customMetrics`. Every alert is an Azure Monitor alert rule: a
  metric alert on `DeadletteredMessages` for alert 1, log search alerts for the rest. Applied in
  FR-006-019 and FR-006-053.
- Q: Who creates the rules, and where do they go? → A: H36. DevOps create them by hand on a ticket,
  with the runbook, as for the switches; they email the owning team, the Resulting Assistant team
  (H31), through an action group. The address lives on the ticket, not in this public repository.
  Applied in FR-006-014, assumptions 3 and 7, and Dependencies.
- Q: Which App Insights resource does the service report to? → A: Not decided (Q29). The catalogue
  names it as an input of each rule. Applied in FR-006-053 and the open items.

## User Scenarios & Testing *(mandatory)*

### User Story 1 - A week of real SIT traffic proves the service sends what the legacy sent (Priority: P1)

The owning team chooses one week of SIT shares, one full QA regression cycle. It exports that
week's SIT logged HMPPS bodies and `EXT_` payloads at once, before they expire. It then replays the
week into an STE stack that runs the service against a stub gateway, which answers 200 and forwards
nothing. A comparison pairs each hearing's body in STE's store with the body the legacy logged in
SIT. The report classes every share as a match, a registered deviation, an explained difference
or an unexplained difference, or reports it as unpaired or its SIT body as unusable. Any unexplained
item blocks cutover.

**Why this priority**: it is go/no-go row 5 of §9.2, and the only run-time evidence that the port
sends what the legacy sends (H21). No environment is cut over without it.

**Independent Test**: run the comparison over synthetic inputs built to hold one example of each
class: a match, a defaulted switch read, a difference at a known path, the same difference with an
explanation, a SIT-only share and an STE-only share, a re-shared hearing with an ambiguous share,
and a cut-short logged body, once without and once with an explanation entry. Check each class,
the paths reported and the verdict, and check that no input value appears in the report. The test
needs neither STE nor SIT, so it does not wait for the injection mechanics (FR-006-010).

**Acceptance Scenarios**:

1. **Given** a hearing whose STE body and SIT logged body are equal after canonicalisation,
   **When** the comparison runs, **Then** the hearing is a match.
2. **Given** a hearing whose STE attempt records `youth_switch = UNREADABLE_DEFAULTED_INACTIVE` and
   whose body equals SIT's logged body, **When** the comparison runs, **Then** the hearing is
   listed under DV-25 and is not an unexplained difference.
3. **Given** a hearing whose bodies differ at one JSON path, and no explanation that names it,
   **When** the comparison runs, **Then** the hearing is an unexplained difference, reported with
   that path and with neither value, and the verdict is "blocks cutover".
4. **Given** an explanation entry that names that hearing, that path and a reason, **When** the
   comparison runs again, **Then** the hearing is an explained difference shown with its reason.
   A difference at any path the entry does not name is still unexplained.
5. **Given** a share with a SIT logged body and no STE body, or the reverse, **When** the
   comparison runs, **Then** the share is reported as unpaired, SIT-only or STE-only, and the
   verdict is "blocks cutover" until an explanation entry covers it.
6. **Given** a hearing shared twice in the week, **When** the comparison runs, **Then** each share
   is paired with its own counterpart by hearing id, hearing day and `sharedTime` instant. A share
   that cannot be paired one-to-one is reported as unpaired (ambiguous), never dropped.
7. **Given** that STE's `EMYouthFilter` reads active, or that STE's configured default differs
   from STE's live reading, or that the service's HMPPS target is not the stub, **When** the replay
   is about to start, **Then** it does not start, and the failed check is recorded.
8. **Given** any report the comparison writes, **When** it is searched for any value taken from
   the inputs, **Then** none is found. The report carries identifiers, classes, DV ids, counts and
   JSON paths only.
9. **Given** a share whose SIT logged body is cut short at the trace-length limit, or is not
   complete JSON, **When** the comparison runs, **Then** the logged body is not the expected body,
   nothing is used in its place while 001's FR-001-021 is open, the share is SIT body unusable,
   and the verdict is "blocks cutover" until an explanation entry covers it.

---

### User Story 2 - Go/no-go is decided on evidence, row by row (Priority: P1)

Before each environment's cutover, the owning team and operations walk through the §9.2
preconditions against one evidence index, and after the flip they walk through the §9.5 checks.
For every row the index says what proves it, where that evidence comes from and who supplies it.
For each row the build supports, the evidence is a named artefact or a read-only query pinned by a
test. No row that another team supplies is marked as met on this repository's authority. The
youth-switch default is checked against the environment's live `EMYouthFilter` reading at cutover,
and again after any change to the live toggle.

**Why this priority**: the go/no-go is the only gate between a built service and production
traffic. A row met by assertion, not by evidence, is the failure this slice exists to prevent.

**Independent Test**: seed a store, created by 002's V1, with the rows that each build-supported
row's evidence query looks for: an admission fed by the producer, a `SENT` test call, a request
dead-lettered `leg-exhausted` and then recovered by a resend from the dead-letter queue, and a day's
mix of outcomes. Check that each query returns its evidence, and returns none when the rows are
absent. Then check that the index lists every row of §9.2 and §9.5.

**Acceptance Scenarios**:

1. **Given** the index, **When** it is read, **Then** every row of §9.2 (G1 and rows 1–23) and of
   §9.5 (rows 1–9) appears with its check, its evidence, its supplier and where it is obtained:
   once in SIT, in STE, or in each environment. The rows the design closed or answered appear as
   such, with their design reference.
2. **Given** one real SIT share that has passed from the producer through the queue to the service,
   with SIT's Results switch on, **When** the row-1 query runs, **Then** it shows a
   `processed_request` row with `source = RESULTS` and one HMPPS `outbound_message` row for that
   share.
3. **Given** that the startup log line reports the default
   `distribution.youth-switch.default-when-unreadable` as `ACTIVE`, and that a switch read from the
   service's pod returns `INACTIVE`, **When** row 7 is checked, **Then** the row is no-go, because
   a mismatch fails in either direction. It stays no-go until the default is changed to equal the
   live reading and the startup log line shows the new value.
4. **Given** a send in SIT that failed, was redelivered up to `maxDeliveryCount` 5 and was
   dead-lettered `leg-exhausted`, and was then resent from the dead-letter queue, **When** the
   row-21 query runs, **Then** it shows the request `FAILED(leg-exhausted)` with its exhausted
   message id, then admitted as a replay by the `copy` route, then `COMPLETED(recovered)`.
5. **Given** that the HMPPS contact has not yet confirmed receipt of the sample hearings, **When**
   row 6 of §9.5 is read, **Then** it is shown as outstanding and supplied by HMPPS, never as met
   by this repository.
6. **Given** that the live `EMYouthFilter` changes after cutover, **When** the index is
   consulted, **Then** the row-7 check is due again, and the default must be changed to match.

---

### User Story 3 - The cutover and rollback runbook has every service-side step it needs (Priority: P1)

DevOps carry each environment's cutover ticket and its runbook (§9.2 row 22). This repository gives
them, and the owning team, the service-side inputs for four procedures: cutover, the planned
rollback, the urgent out-of-hours rollback and the roll-forward after a rollback. The inputs follow
the design's order step by step. Each step has its actor, the service action it uses and the
read-back that proves it. Every environment-specific name is left as a placeholder, which the ticket
fills in.

**Why this priority**: row 22 is a cutover precondition, and the rollback is the cutover's safety
net. The receiver is not idempotent, so a step taken out of order means a duplicate at HMPPS.

**Independent Test**: rehearse the service-side steps on a local or SIT stack, checking each step's
read-back: pause intake; close the in-flight rows, once the design places that step's execution
path (until then the rehearsal records the gap and checks the remaining read-backs); record the
cut-off; purge the paused queue; reconcile since the cut-off; and resume only after a new cutover.
Review the inputs against §9.3 and §9.4 step by step, and check that no environment-specific value
appears.

**Acceptance Scenarios**:

1. **Given** the cutover inputs, **When** they are read, **Then** they set this order: the
   subscription's current filter, advanced filters included, is recorded before it is set to
   `TOGGLED-OFF`; the subscription is switched off before the Results switch is turned on; the flag
   is read back for the environment's label; and the next step waits at least 10 minutes after the
   flip.
2. **Given** the planned rollback inputs, **When** they are read, **Then** the order is: 1 pause
   intake; 2 turn the Results switch off and wait at least 10 minutes; 2a restore exactly the
   recorded filter; 3 record the cut-off; 4 close the in-flight rows; 5 reconcile the live period;
   6 purge the queue; 6a replay only through the service's replay action; 7 repair only the
   hearings not known to be delivered.
3. **Given** the urgent rollback inputs, **When** they are read, **Then** the order is: pause
   intake; restore the recorded filter at once and record T; turn the Results switch off and wait
   at least 10 minutes; close the in-flight sends; reconcile the paused queue against T, sending
   shares from before T to a court re-share, purging messages from after T and not resuming
   intake, then planned steps 5–7; purge the queue. No variant restores the filter before intake
   is paused.
4. **Given** that intake is paused after a rollback, **When** the roll-forward inputs are read,
   **Then** intake resumes only after a purge, a reconciliation since the cut-off and a full new
   cutover, in that order.
5. **Given** a rehearsal in which step 4 has run through the execution path the design places,
   **When** the in-flight query runs, **Then** no leg's latest row is `PENDING` or `PARKED`, every
   request that was `RECEIVED` or `RETRYING` is `FAILED(rolled-back)`, and no `COMPLETED` request
   has changed. Until a path is placed, the rehearsal records the gap and checks the remaining
   read-backs.
6. **Given** the inputs, **When** they are searched, **Then** no subscription, topic, store,
   label, host or resource name appears, only named placeholders.

---

### User Story 4 - Only genuine issues page; known behaviour is counted (Priority: P2)

Eleven conditions page, because each needs someone to act: the dead-letter queue not empty; a leg
parked, or a request not terminal, for over 30 minutes; a send still failing after its last
delivery, or a rules exception; repeated 401s; a hearing sent on an assumed youth-switch value;
a send re-run after a crash; a body-maintenance failure; a spike in case-hearing 400s; Results
unable to read its switch; and the legacy function app sending after cutover. Known lift-and-shift
behaviour is counted on the dashboard and in the daily reconciliation, and pages no one.

**Why this priority**: the alerts watch the live service and the soak. Deployed alerting is the
post-soak criterion that closes P36 (§9.6 row 1), not a cutover precondition. But a service cut
over without its pages is watched by nobody.

**Independent Test**: check the catalogue against the design's eleven alerts and five counted
classes. Run the catalogue test, which shows that the service emits every series and label value
the rules select. Evaluate each rule's condition against sample series at its threshold and around
it.

**Acceptance Scenarios**:

1. **Given** a resend that ends `FAILED(gateway-transient)`, **When** the leg-failed alert
   evaluates, **Then** it fires, because every origin is read, and its runbook entry says to
   confirm with HMPPS before any re-send.
2. **Given** application-only hearings rejected with 400 at their usual daily level, **When** the
   alerts evaluate, **Then** nothing pages, and the day's count appears on the dashboard and in the
   reconciliation.
3. **Given** case-hearing 400s at more than double the environment's normal daily level, **When**
   the day's count is evaluated, **Then** the spike alert pages. At or below that level nothing
   pages, and the count is still shown.
4. **Given** more than three 401s in an hour, **When** the repeated-401 alert evaluates, **Then**
   it pages.
5. **Given** a 2xx other than 200, or a 500 or 429 that is later recovered, **When** the alerts
   evaluate, **Then** nothing pages, and each is counted on the daily report.
6. **Given** a resend that ends `FAILED(payload-changed)`, **When** the leg-failed alert
   evaluates, **Then** it fires, until Q28 decides otherwise.
7. **Given** a rule that names a metric or a label value the service does not emit, **When** the
   catalogue test runs, **Then** the test fails.
8. **Given** fewer than two consecutive months ahead with a partition, **When** alert 9 evaluates,
   **Then** it pages; with three or two ahead, and every other alert-9 condition healthy, it does
   not (A3-118; H37).
9. **Given** one pod reporting `-1` for a body gauge while another reports a healthy value, or no
   sample of a body gauge in the window, **When** alert 9 evaluates, **Then** it pages, because each
   pod's latest sample is read first and missing data is not healthy (A3-118).
10. **Given** more than 48 hours since the last successful body-maintenance run, **When** alert 9
    evaluates, **Then** it pages; **and given** only skipped runs within those 48 hours, after a
    successful one, **Then** it does not (H38).

---

### User Story 5 - The daily reconciliation shows nothing lost between results, the queue and HMPPS (Priority: P2)

Every day of the soak, the owning team compares results' shares with the requests the service
received and with each leg's current state. The current state is read from the leg's latest attempt
and never from the request's status, so a hearing recovered by a resend counts once, and a completed
request whose resend failed counts as failed. A message the service never admitted is a class of
its own, with its own repair route.

**Why this priority**: the reconciliation is §9.5 row 2 and the soak's daily check (§9.4, H31).
It is how a silent loss, a "neither" share or a "both" share is found.

**Independent Test**: seed a store with a day of mixed outcomes. Include a `COMPLETED` request
whose latest leg attempt is a failed resend, a leg recovered by a resend, `PARKED` and `PENDING`
legs in flight, and a dead-letter-only message. Check every count against the expected figure.

**Acceptance Scenarios**:

1. **Given** a day of shares, **When** the reconciliation runs, **Then** it shows the requests
   received, and the legs `SENT`, `NOT_APPLICABLE` by reason, `FAILED` by reason, `PARKED` and
   `PENDING`, all from each leg's latest attempt, against results' share count for the day.
2. **Given** a `COMPLETED` request whose leg's latest attempt is a failed resend, **When** the
   reconciliation runs, **Then** the leg counts as `FAILED` with its reason.
3. **Given** a leg that failed and was then sent by a resend, **When** the reconciliation runs,
   **Then** the leg counts once, as `SENT`.
4. **Given** a dead-letter entry whose message has no `processed_request` row, **When** the
   reconciliation runs, **Then** it is counted in its own class with its repair route: a resend from
   the dead-letter queue for `exhausted`, otherwise submit-hearing. It is never counted as
   replayable.
5. **Given** legs still `PARKED` or `PENDING`, **When** the reconciliation runs, **Then** the
   expected gap equals their number, and the oldest-age gauges show how long they have waited.

---

### User Story 6 - A share sent by both paths is caught the same day (Priority: P2)

After cutover, a mistake on a ticket could restore the legacy subscription's filter. That would
turn the function app back on, and every share would be sent twice. So the legacy app's own
telemetry is watched. An alert fires on any HMPPS send line or orchestration start since cutover,
and the daily soak reconciliation makes the same check.

**Why this priority**: a "both" is otherwise undetected (A3-75), and the receiver is not
idempotent.

**Independent Test**: evaluate the alert's query against sample telemetry, once with a send line
after the cutover instant and once without. Check that the soak check in the daily reconciliation
reads the same lines.

**Acceptance Scenarios**:

1. **Given** cutover at instant C and an HMPPS send line in the legacy telemetry after C, **When**
   the alert's query runs, **Then** it pages. Its runbook entry says to check the legacy
   subscription's filter and switch it off again.
2. **Given** an orchestration-start trace after C and no send line, **When** the query runs,
   **Then** it pages too.
3. **Given** no such line since C, **When** the query runs, **Then** nothing pages.
4. **Given** an environment whose legacy telemetry resource is not established, **When** the
   catalogue is read, **Then** the alert is shown as not deployable there, never silently absent.
5. **Given** decommissioning, **When** the subscription and the function app's alerts are removed,
   **Then** this alert and the soak's legacy-silence check end with them.

---

### User Story 7 - Decommissioning has the service-side evidence it needs (Priority: P3)

When the owning team and the stakeholders judge that the soak is over, the service supplies the
post-soak evidence of §9.6 row 1. The rest of the decommissioning checklist is carried out outside
this repository, on an inventory of what each environment actually has.

**Why this priority**: it comes last in the migration, and it changes nothing in the service.
Without it, though, the rollback path cannot be retired on evidence.

**Independent Test**: assemble the post-soak evidence from the index for a rehearsed soak period,
and check each item of §9.6 row 1 against its source.

**Acceptance Scenarios**:

1. **Given** a soak, **When** the owning team considers decommissioning, **Then** the post-soak
   evidence shows: §9.5 green over the agreed soak period; no unexplained reconciliation gap; the
   dead-letter queue empty; and P36's alerting deployed, meaning its rules, routing, owner and
   runbook.
2. **Given** decommissioning, **When** it completes, **Then** no change to the service's code or
   configuration was needed, and the service's dashboards are the only ones.

---

### Edge Cases

- **A hearing re-shared within the replay week.** Each share is paired with its counterpart by
  hearing id, hearing day and `sharedTime` instant (FR-006-002). A share that cannot be paired
  one-to-one is unpaired (ambiguous), never dropped.
- **A SIT logged body cut short** by the telemetry's trace-length limit, or not complete JSON. It is
  never the expected body, and nothing stands in for it while 001's FR-001-021 is open: the share
  is SIT body unusable and blocks until it is explained (FR-006-007).
- **STE could fetch only the fallback form**, because its Redis lacked the `EXT_` payload. The body
  differs from SIT's cache-form body. The report shows `payload_source = query` beside the
  difference, and the difference blocks until it is explained (FR-006-005, FR-006-006).
- **STE parked or failed a hearing that SIT sent**, for example `PARKED(payload-unavailable)` in
  STE. The share is unpaired (SIT-only) and blocks until it is explained.
- **STE sent a hearing for which SIT logged nothing.** For example, SIT's legacy could not read the
  switch that day (so it sent nothing, P16), or it crashed in a rule. The share is unpaired
  (STE-only) and blocks until it is explained.
- **Neither side sent**: `NOT_APPLICABLE` in STE and no SIT log line. This is not a comparison
  item. It appears in the week's STE outcome totals.
- **The service was redeployed in STE in the middle of the week.** The report lists the builds,
  and each compared attempt carries its `service_version` (FR-006-005).
- **STE was rebuilt before the run.** What the switch read depends on must be in place again
  first: the service's system user and STE's `EMYouthFilter` permission (pre-run check (c) of
  FR-006-008). Otherwise every switch read defaults, and check (c) stops the run. The Results
  switch's static-data patch is the UI's copy and does not affect the read.
- **A defaulted switch read in STE.** The hearing is listed under DV-25. The default must equal
  the week's inactive reading, so the body must equal SIT's (FR-006-004).
- **The Results flag reads back as on, but Results has not picked it up.** The 10-minute wait
  governs, not the read-back. Routing is proven by the first admitted shares (FR-006-038).
- **The legacy subscription's filter is restored by mistake after cutover.** This is a "both".
  Alert 11 and the soak check catch it (FR-006-020, FR-006-027).
- **Results cannot read its flag after cutover.** This is a "neither": results sends the share
  nowhere. Alert 10 fires. The repair is submit-hearing while the `EXT_` payload lasts (about
  91 days), and a re-share after that.
- **A share arrives between steps 1 and 2 of an urgent rollback.** It reached neither path, and is
  repaired by a court re-share (FR-006-042).
- **Owed producer events arrive after the switch is off.** They wait on the paused queue, and are
  purged and reconciled (FR-006-044).
- **A `COMPLETED` request whose resend failed.** The leg counts by its latest attempt, as
  `FAILED` (FR-006-023).
- **A dead-letter entry with no request row.** It has a class of its own and a repair route of its
  own (FR-006-026).
- **The live toggle changes after cutover.** The row-7 check is due again. Until the default is
  changed, an outage defaults to the old value (FR-006-033).
- **An environment with no established legacy telemetry resource.** Alert 11 is shown as not
  covered there (FR-006-020).
- **The row-9 test call in an environment where the legacy still sends.** A real hearing sent
  before cutover would be a genuine duplicate at HMPPS. What the call sends is open (FR-006-032).
- **A rollback before step 4 has an execution path.** The inputs record the gap and its owner, the
  design owner (FR-006-040).

## Requirements *(mandatory)*

### Functional Requirements

**The one-week SIT → STE replay, the pre-cutover proof**

- **FR-006-001**: The repository MUST provide a replay comparison. For the replayed week, it
  compares, hearing by hearing, the HMPPS bodies the service stored in STE with SIT's logged HMPPS
  bodies. The STE side is the body of each leg's latest attempt. The comparison MUST use the
  comparator and the deviation derivation of 001-parity-kernel unchanged: semantic JSON equality
  after canonicalisation, numbers compared by their decimal value, ignoring scale and with no
  tolerance, and registered deviations asserted by derivation, keyed on `doc/DEVIATIONS.md`.
  *(H21; A3-68; §6.2; §6.5 Comparison row; FR-001-013)*
- **FR-006-002**: Each STE body and each SIT logged body MUST be paired by share, as 001 identifies
  a share: the same hearing id, hearing day and `sharedTime`, the `sharedTime` compared as an
  instant, because the two sides may render it differently. A hearing re-shared within the week is
  therefore several shares. A share that cannot be paired one-to-one MUST be reported as unpaired,
  with its sub-reason: SIT-only, STE-only, or ambiguous (more than one candidate on a side). It MUST
  NOT be dropped. *(§6.5 Comparison row; A3-68; H17; FR-001-017)*
- **FR-006-003**: Every share in the union of the two sides MUST appear in the report in exactly one
  class: match; registered deviation, with its DV id; explained difference; unexplained difference;
  unpaired, with its sub-reason (FR-006-002); or SIT body unusable (FR-006-007). A hearing shared
  once is one share. *(§6.5 Report row)*
- **FR-006-004**: A share whose STE attempt records a defaulted switch read (`youth_switch` is
  `UNREADABLE_DEFAULTED_ACTIVE` or `UNREADABLE_DEFAULTED_INACTIVE`) has its expected body derived
  as for corpus case 13: the output for the configured default. STE's default equals the week's
  reading (FR-006-008), so that expected body is SIT's logged body. A share whose SIT logged body
  is cut short has no expected body and is classed SIT body unusable instead (FR-006-007). When the
  STE body equals the expected body, the share MUST be classed as a registered deviation under
  DV-25. When it differs, the share MUST be classed as an unexplained difference, with the
  defaulted read noted beside it. Other registered deviations that change output are asserted by
  derivation (§6.2). *(DV-25; H8; A3-28; A3-29; §6.5 Report row)*
- **FR-006-005**: For each share, the report MUST show: its class; the DV id, where one applies;
  the JSON paths at which the two bodies differ; and the STE attempt's `status`, `payload_source`,
  `youth_switch` and `service_version`. For an unpaired share, it MUST show the sub-reason. For a
  share whose SIT body is unusable, it MUST say why (cut short, or not complete JSON) and that its
  expected body is pending 001's FR-001-021 (FR-006-007). The report MUST also give the week's
  totals per class, and the STE latest outcomes by status and reason. *(§6.5; §4.6; H19)*
- **FR-006-006**: A difference MAY move from unexplained to explained only through an explanation
  entry. The entry names the share, the JSON paths it covers, the reason and the role of whoever
  gave it. Example reasons are a difference rooted in STE's own reference data, or a payload that
  STE could obtain only in the fallback form. The report MUST show every explained difference
  beside its reason. A difference at a path the entry does not name stays unexplained. An unpaired
  share, or one whose SIT body is unusable, is explained the same way, by an entry that names the
  share and the reason and covers it as a whole; it keeps its class and is shown with its reason.
  An entry that matches no current difference or share MUST be reported as stale. Nothing is waived
  silently. *(§6.5 Report row; A3-68)*
- **FR-006-007**: A SIT logged body that is cut short, because its log line reaches the
  telemetry's trace-length limit (H28) or the logged body is not complete JSON, MUST NOT be used as
  the expected body, and no match is ever declared against it. Where the expected body for such a
  share comes from instead is 001's open question (FR-001-021), for the design owner; this slice
  carries that question unchanged and decides nothing. Until it is answered:
  - no expected body exists for the share, and the executed legacy is not used in its place;
  - the share MUST be classed SIT body unusable, and it blocks cutover until an explanation entry
    covers it (FR-006-006, FR-006-011).

  Once FR-001-021 is answered, the expected body for such a share is whatever that answer gives.
  *(H28; A3-67; FR-001-021; §6.1; §6.5)*
- **FR-006-008**: Before the replay runs, these checks MUST pass and MUST be recorded in the report.
  A failed check stops the run.
  - (a) STE's `EMYouthFilter` reads inactive, as SIT's did for the week.
  - (b) STE's `distribution.youth-switch.default-when-unreadable` equals that live reading.
  - (c) The switch is readable from the service's pod in STE, under the service's own system user,
    and the read is not defaulted. If STE was rebuilt, what the read depends on has been re-applied
    first: the service's system user (§8.4 ask 16; H30) and the `EMYouthFilter` feature permission
    in users-groups. The Results switch's static-data patch (§8.4 ask 7) is the UI's copy of that
    switch and does not affect the read.
  - (d) The service's HMPPS target in STE is the stub gateway, which answers 200 and forwards
    nothing. STE's legacy probation container has no HMPPS publish URL.
  - (e) The stub is reached over https. `distribution.hmpps.url` is checked at startup as https,
    and the check is not relaxed for the replay.

  *(§6.5 Service, No egress and Youth switch rows; §9.2 row 5; A3-29; A3-68; H30; A3-106; §8.4
  asks 6a, 16; DIGEST §12)*
- **FR-006-009**: When the week is chosen, its SIT logged HMPPS bodies and its `EXT_` payloads MUST
  be exported at once, before they expire. The logged bodies are kept for about 90 days, and SIT's
  configured retention was not read; `EXT_` keys live about 91 days. The exports MUST be held under
  the corpus controls of constitution Principle VII: SIT data only, never production data, and
  never quoted in a log, report, document, pull request or finding. The report records the week and
  the export dates. *(A3-78; §6.5 Export and Data rows; §6.4; A3-67)*
- **FR-006-010**: The replay MUST feed the week's SIT `Hearing_Resulted` shares, one full QA
  regression cycle, to the service in STE by [NEEDS CLARIFICATION: two things are not established.
  First, what is injected into STE: an Event Grid event, a results command or event, or a
  `probation.requests` message. Second, where STE's results, and the service's Redis read and
  query-API fallback, get the `EXT_` payloads for SIT hearings. The design's Inputs row has
  results' flag ON in the STE stack so that its producer publishes to the stack's queue, which
  assumes a replay fed through the producer. The owning team settles this before the replay is
  planned (A3-78; §6.5 Mechanics row)]. *(H21; A3-68; A3-78; §6.5 Inputs and Mechanics rows)*
- **FR-006-011**: The report MUST end with one verdict. The verdict is **blocks cutover** while any
  unexplained difference remains, or any unpaired share or share whose SIT body is unusable has no
  explanation entry (FR-006-006, FR-006-007). Otherwise the verdict is **no unexplained
  differences**. The verdict is the evidence for §9.2 row 5 and §9.5 row 8. *(§6.5 Report row;
  §9.2 row 5; §9.5 row 8; H21)*
- **FR-006-012**: The comparison MUST NOT write any value from a payload or a body, whether to its
  report or anywhere else. It carries identifiers, classes, DV ids, counts and JSON paths only. Its
  own tests MUST use synthetic inputs only. This slice commits no SIT body or payload.
  *(Principle VII; §6.4; A3-60)*
- **FR-006-013**: The owning team runs the replay and owns its report. The replay MUST run in STE
  against the stub only. It is the pre-cutover proof, not a build gate, so a CI build MUST NOT
  depend on it. *(H21; H31; A3-111; constitution Principle I)*

**Paging alerts: genuine issues only (H34)**

- **FR-006-014**: The repository MUST hold one alert catalogue that lists exactly the eleven paging
  alerts below, which are the alerts of design §10.3 as H34 amends it. For each alert the catalogue
  gives: its backing series and the bounded label values it selects; its condition and evaluation
  window; whether its threshold is a design proposal, tuned after go-live; its Azure Monitor rule
  type (FR-006-053); its routing (email to the owning team, the Resulting Assistant team, through an
  action group; H36, H31, A3-111); its runbook entry; and when it lapses. DevOps create the rules by
  hand on a ticket, with the runbook (H36): the catalogue and the rule definitions are this
  repository's input to that ticket. The catalogue is the
  complete list of paging alerts this service asks the platform to deploy, and the operations
  reference states that no other alert is requested, 005's proposed operations-failure counter
  included (FR-006-022). *(H34; A3-115; §10.3; R1-18; H31; A3-111; H36)*

| # | Alert | Backing series | Condition | Runbook entry (from the design) | Design ids |
|---|---|---|---|---|---|
| 1 | DLQ not empty | Azure Monitor's `DeadletteredMessages` metric (not service code) | > 0 | The dead-letter runbook (004). Resend `leg-exhausted` and `exhausted`; never resend `validation` or `collision`. Once a replayed request is `COMPLETED`, remove the original and any copy that was dead-lettered again. An `exhausted` entry whose request is `COMPLETED` is duplicate-copy noise: remove it | §10.3; A3-32; A3-33; A3-55 |
| 2 | Leg parked too long | `probation_oldest_parked_age` | > 30 min | The leg is waiting for broker redelivery. On the final delivery the leg becomes `FAILED`, the message is dead-lettered `leg-exhausted`, and the dead-letter runbook applies | §10.3; R1-18 |
| 3 | Silent drop | `probation_oldest_non_terminal_age` | > 30 min | The oldest request that is not terminal: the silent-drop detector | §10.3; §10.1 |
| 4 | Leg failed | `probation_leg_outcomes_total{status="FAILED", reason=~"gateway-transient\|payload-unavailable\|rules-exception\|payload-changed\|rolled-back"}`, every origin | Any | FR-006-016, FR-006-017 | H34; A3-115; Q28 |
| 5 | Youth switch unreadable | rate of `probation_youth_switch_unreadable_total{cause, defaulted}` | Not zero for > 5 min | Each counted read is a hearing sent on an assumed switch value. Re-check the default against the live toggle (FR-006-033) | §10.3; H8; A3-27; A3-29 |
| 6 | Repeated 401s | `probation_leg_outcomes_total{reason="receiver-rejected", http="401"}` | > 3 in an hour (proposal) | The gateway is refusing the service's credentials. A 401 is terminal at once | H34; A3-115 |
| 7 | Send re-run after a crash | `probation_crash_reruns_total` | Any increase | A possible duplicate at HMPPS: record the hearing for HMPPS | A3-115; A3-47 |
| 8 | Case-hearing 400 spike | daily increase of `probation_leg_outcomes_total{reason="receiver-rejected", http="400", shape="case"}` | > 2 × the environment's normal daily level (proposal) | The daily count itself is counted, not paged (FR-006-022) | H34; A3-116 |
| 9 | Body partition maintenance | `probation_body_default_partition_rows` (003's FR-003-022), `probation_body_partitions_ahead_months` (FR-003-031), `probation_body_maintenance_last_success_seconds` (FR-003-032) — all three read from the database at collection on every pod, `-1` on a failed read; `probation_body_maintenance_failures_total{step}` | DEFAULT partition not empty; any `create` or `drop` failure; fewer than 2 consecutive months ahead (H37); more than 48 h since the last success (H38); any pod reporting `-1`; or a body gauge with no sample in the window. Evaluated hourly over 2 h, each pod's latest sample first (proposals) | 003's runbook: for a DEFAULT spill, detach the DEFAULT partition, create the missing month, move its rows, re-attach; for partitions not being created, check the scheduler and the advisory lock, wait for the next run, or create the missing month by hand with V1's name and bounds; for `-1`, check the database connection. The ERROR lines give the cause (003's FR-003-017; FR-003-032 for a `state` or `read` failure), and the run lines show whether the job ran (FR-003-020, FR-003-030) | H20; A3-64; A3-118; H37; H38; 003's AS-15 |
| 10 | Results switch unreadable | results' `probation_flag_read_failures_total` (the producer change) | Any increase | After cutover, each failed read is a share on neither path. Reconcile, and repair with submit-hearing while the `EXT_` payload is still in Redis | H26; A3-71; A3-89 |
| 11 | Legacy function app active after cutover | The legacy function app's telemetry for the environment: HMPPS send lines and the orchestration-start trace | Any count > 0 after cutover; a query every 15 min over 15 min (proposal) | A "both": check the legacy subscription's filter and set it back to `TOGGLED-OFF`. Lapses at decommissioning | A3-75; §9.5 row 9 |

- **FR-006-015**: Every alert over `probation_leg_outcomes_total` MUST read every origin (`queue`,
  `replay` and `resend`), so that a failing resend pages like a failing first send. *(R1-31;
  R1-31b; §10.3)*
- **FR-006-016**: The leg-failed alert MUST select `status="FAILED"` with `reason` in
  {`gateway-transient`, `payload-unavailable`, `rules-exception`, `payload-changed`,
  `rolled-back`}, and nothing else. `receiver-rejected` is outside it: 400s are counted, and 401s
  have their own alert. `contract-invalid` is reserved and unused (H13). `payload-changed` and
  `rolled-back` stay in the alert until Q28 is decided. *(H34; A3-115; Q28; H13)*
- **FR-006-017**: The leg-failed alert's runbook entry MUST state that a `FAILED(gateway-transient)`
  after a 5xx, a 408 or a read timeout is outcome-unknown, because HMPPS may have accepted the
  hearing. Such a leg is confirmed with HMPPS before any re-send. *(H11; H15; A3-115; A3-117 (i);
  §9.4 step 7)*
- **FR-006-018**: The thresholds the design marks as proposals MUST be parameters of their rules.
  They are: more than three 401s in an hour; a day's case-hearing 400s above double the normal
  daily level; the 15-minute schedule of the legacy-app query; and alert 9's fewer than two months
  ahead, its 48 hours since the last success, and its hourly evaluation over 2 hours (A3-118, H37,
  H38). The normal daily level is set
  for each environment from that environment's observed baseline. No production figure is written
  into this repository. *(H34; A3-115; A3-116; A3-75; A3-118)*
- **FR-006-019**: Every service series that an alert or a dashboard panel reads MUST be one the
  service emits, and the label values the rule selects MUST come from the bounded sets of design
  §10.1. A test MUST prove this; inspection is not enough. A series the service does not emit MUST
  be named with its owner. There are three: the dead-letter count (Azure Monitor's
  `DeadletteredMessages`), results' `probation_flag_read_failures_total` (the producer change) and
  the legacy function app's telemetry. The service's series reach Azure as App Insights
  `customMetrics` through the Java agent (H36); the names the rules use MUST be the names the agent
  exports, checked in SIT before the rules are written (Q29). *(§10.1; §10.3; R1-18; Principle VII;
  H36)*
- **FR-006-020**: The legacy-app-active alert MUST count, in the legacy function app's telemetry
  for the environment, the HMPPS send lines and the orchestration-start trace since cutover. It
  MUST page on any count above zero after cutover. Its query is proposed, not designed: a
  scheduled query every 15 minutes over the last 15 minutes. An environment whose legacy telemetry
  resource is not established MUST be shown as not covered, and never left out silently. The alert
  lapses at decommissioning. *(A3-75; §9.5 row 9; §10.3; §9.6 rows 3, 8)*
- **FR-006-021**: P36 MUST be recorded as open until the alert rules, their routing, an owner and a
  runbook are deployed. A design is not enough. Whether counting 400s instead of paging on them
  satisfies the legacy alert-on-failure ticket MUST be recorded as a question for that ticket's
  owner, not answered here. *(P36; §10.3; R1-18; H34)*
- **FR-006-053**: Each alert definition MUST name its Azure Monitor rule type: a metric alert on the
  Service Bus `DeadletteredMessages` metric for alert 1; log search alerts for the others — over the
  legacy function app's telemetry for alert 11, results' telemetry for alert 10, and the service's
  `customMetrics` for the rest. On `customMetrics` a counter arrives as per-interval values, so a
  counter condition sums them over the rule's window. Alert 9's three body gauges MUST be read as
  each pod's latest sample before aggregating, so that a failed read on one pod is not hidden by
  another pod's healthy sample, and alert 9 MUST page when one of them has no sample in its window. The App
  Insights resource each environment reports to is open (Q29): the catalogue names it as an input
  of each rule, never as a value. *(H36; A3-118; H38; §10.3)*

**Counted, not paged**

- **FR-006-022**: The five known-issue classes below MUST appear as daily counts on the dashboard
  and in the daily reconciliation, and MUST page no one. Only the spike in case-hearing 400s pages
  (alert 8). The counter 005 proposes for failed operations calls,
  `probation_operations_failures_total{action, reason}` (005's FR-005-043; registered in 002's
  FR-002-057), is neither one of these classes nor an alert: it is outside design §10.1 and §10.3,
  so no rule or panel of this slice reads it until the design owner adds it. *(H33; H34; A3-114;
  A3-116; D16 (a))*

| Class | Series and labels |
|---|---|
| Application-only 400s | `probation_leg_outcomes_total{reason="receiver-rejected", shape="application-only"}` |
| Case-hearing 400s | `probation_leg_outcomes_total{reason="receiver-rejected", http="400", shape="case"}` |
| 500s recovered by a later delivery | `probation_gateway_recovered_total{after="5xx-redelivery"}` |
| 429s recovered by an in-run retry | `probation_gateway_recovered_total{after="429-in-run"}` |
| 2xx answers other than 200 | `probation_gateway_non200_success_total{status}` |

**Dashboard and daily reconciliation**

- **FR-006-023**: The dashboard and the daily reconciliation MUST show, per day, the requests
  received and the HMPPS legs `SENT`, `NOT_APPLICABLE` by reason, `FAILED` by reason, and `PARKED`
  and `PENDING` in flight. They MUST read only from each leg's latest attempt
  (`outbound_message_latest`), never from `processed_request.status`, because a request's
  completion is historical. *(§10.4; §9.5 row 2; A2-49; DIGEST §8)*
- **FR-006-024**: The service-side counts MUST be compared with results' daily share count for the
  same day, obtained from [NEEDS CLARIFICATION: the source of results' daily share count is not
  established. It could be a viewstore query, the Event Grid publish count or a producer-side
  counter. With the source, the day boundary that both sides count on is also not established
  (§10.4; owner: the results team)]. *(§10.4; §9.5 row 2; DIGEST §16)*
- **FR-006-025**: Until nothing is in flight, the expected gap MUST be shown as the number of
  latest attempts that are `PARKED` or `PENDING`, beside `probation_oldest_parked_age` and
  `probation_oldest_non_terminal_age`. *(§10.4)*
- **FR-006-026**: A dead-letter entry whose message has no `processed_request` row, which is a
  message the service did not admit, MUST be counted as a class of its own. It is counted from the
  dead-letter queue and `probation_deadlettered_total{reason}`, and shown with its repair route:
  for `exhausted`, a resend from the dead-letter queue, which is processed as a first delivery;
  otherwise, submit-hearing. The repair route is never the service's replay, which needs a stored
  command. *(A2-55; A2-48; A3-32; §10.4)*
- **FR-006-027**: During the soak, the daily reconciliation MUST also report the legacy function
  app's HMPPS sends and orchestration starts since cutover. Any such send or start is a "both", and
  is handled as for the legacy-app-active alert (FR-006-020). *(§9.4 recommendation; §9.5 row 9;
  A3-75)*
- **FR-006-028**: The owning team MUST run the daily reconciliation for the whole soak. The soak's
  length is not fixed in advance: the owning team decides it with the other stakeholders, on the
  production issues reported. *(H31; A3-112; O-3)*
- **FR-006-029**: The dashboard MUST state that `SENT` means that the gateway answered 2xx, and no
  more, until HMPPS confirms receipt (§9.5 row 6). *(§10.4; H12)*
- **FR-006-030**: The reconciliation, and every evidence query of this slice over the service's
  store, MUST be read-only, MUST return counts and identifiers only, never a payload or body value,
  and MUST be pinned by a test over a seeded store. *(Principle VII; §10.4; A3-60)*

**Go/no-go evidence**

- **FR-006-031**: The repository MUST hold a go/no-go evidence index with one entry per row of §9.2
  (G1 and rows 1–23) and of §9.5 (rows 1–9). Each entry gives: the check; the evidence that proves
  it; who supplies the evidence; and where it is obtained (once in SIT for every later environment,
  in STE, or in each environment). For a row the design closed, parked or answered, the entry gives
  that status and its design reference. Those rows are: G1, parked (H13); rows 6 and 18, closed
  (A3-108); rows 10, 13 and 19, closed (H5); row 11, read, with the VEP setting no longer needed
  (H5); row 14, answered (A3-109); row 15, no longer a gate (H12, H13); row 16, answered (A3-107);
  row 17, answered by H12 and confirmed by row 9's status; row 20, settled (Q18; A3-100); and row 7
  of §9.5, closed (H5). *(§9.2; §9.5; A2-32; DIGEST §15)*
- **FR-006-032**: Each row the build supports MUST have its evidence as a named artefact or as a
  read-only query pinned by a test, as below. The row-9 entry MUST also state what the test call
  sends, and by which action, in an environment where the legacy still sends:
  [NEEDS CLARIFICATION: the design does not say. Before cutover the legacy still sends every share,
  so a real hearing sent by the call is a genuine duplicate at HMPPS (H12), in production too,
  where row 9 is the reachability proof (A3-108). Is the call a real hearing sent by submit-hearing
  with HMPPS's agreement, a hearing the legacy did not deliver, or is production's row 9 shown by
  the first shares after the flip?] The row-9 evidence query reads the call's attempt row whatever
  the answer. *(DIGEST §15; §9.2; §9.5; A2-10; A2-32; H25; A3-87; H30; A3-106; §11.3; A3-108; H12)*

| Row | Evidence | Supplied by |
|---|---|---|
| §9.2 row 1 | Results' healthcheck green in the environment (§4.3). For one real SIT share, with SIT's Results switch on: a `processed_request` row with `source = RESULTS` and its one HMPPS `outbound_message` row. The share proof is obtained once in SIT, and is a precondition for every later environment | the results team (the producer and its healthcheck); evidence query: this slice |
| §9.2 row 3 | Readiness is UP (it reads the store only); config review | the deployment, through the readiness probe; the deployment reviewer, for the config review |
| §9.2 row 4 | The CI run of 001's SIT-data tests, with registered deviations only | 001 |
| §9.2 row 5 | The replay report's verdict (FR-006-011) | this slice; the owning team |
| §9.2 row 7 | The youth-switch check (FR-006-033) | this slice; 002's startup log line |
| §9.2 row 9 | The test call's attempt row, `SENT` with its status code. It also shows whether the gateway returns a request id header (§11.3) and that the gateway's internal CA is trusted | operations; evidence query: this slice |
| §9.2 row 12 | 001's check, over the whole corpus, that every body has `hearing.id` (Q13) | 001 |
| §9.2 row 21 | SIT negative tests. First, the redeliveries up to `maxDeliveryCount` 5 and the dead-letter entry `leg-exhausted`, with the request `FAILED(leg-exhausted)` and its exhausted message id. Then the resent message admitted as a replay by the `copy` route, and the request `COMPLETED(recovered)` | operations in SIT; evidence query: this slice |
| §9.2 row 23 | The pod started with the system user id (startup refuses without it), and rows 7 and 9 succeeded with it | the platform (the patch and Key Vault); the evidence: this slice |
| §9.5 row 1 | The service is consuming (`probation_servicebus_up`, intake not suspended), the queue is draining, and the dead-letter queue is empty | service metrics; the platform's queue metrics |
| §9.5 row 2 | The daily reconciliation (FR-006-023 to FR-006-026) | this slice |
| §9.5 row 3 | Every request is terminal by the end of the day, and `probation_oldest_non_terminal_age` is below its alert | evidence query; metric |
| §9.5 row 4 | `NOT_APPLICABLE` by reason, understood before it is judged. `sjp-or-box-hearing` is expected from the box flag | evidence query |
| §9.5 row 5 | FR-006-033 and FR-006-034 | this slice |
| §9.5 row 8 | The replay report (FR-006-011) | this slice |

- **FR-006-033**: The youth-switch default MUST be checked against the environment's live
  `EMYouthFilter` reading at cutover, and again after any change to the live toggle. Three values
  MUST agree: the default reported by the startup log line; a switch read from the service's pod in
  the environment, for example the `youth_switch` on the attempt row of the row-9 test call; and
  the legacy's last logged reading. A mismatch in either direction is no-go. `INACTIVE` against a
  live `ACTIVE` is the more serious direction, because it sends youth defendants that production
  removes, which is a data-protection failure. The row stays no-go until the default is changed and
  the startup log line shows the new value. *(A3-24; A3-29; §9.2 row 7; §9.5 row 5; H7; H8)*
- **FR-006-034**: After the flip, the evidence for row 5 of §9.5 MUST also show three things.
  First, the `youth_switch` on the HMPPS rows matches the environment's permission state. Second,
  each defaulted row is matched by a `probation_youth_switch_unreadable_total{cause, defaulted}`
  count, with the configured default. Third, for sample hearings with youth defendants, the bodies
  carry the youth defendants where the recorded value is `INACTIVE` (or a defaulted `INACTIVE`),
  and omit them where it is `ACTIVE` (or a defaulted `ACTIVE`). The sample is reported as counts,
  never as body content, by someone entitled to read the bodies. *(§9.5 row 5; H7; H8; A3-27; H18;
  A3-60)*
- **FR-006-035**: Some rows are supplied by operations, DevOps, the results team, the platform team
  or HMPPS: §9.2 rows 2, 8 and 22, §9.5 row 6, and every external precondition (Dependencies).
  These rows MUST be listed with the evidence that shows them met, and MUST NOT be marked as met on
  this repository's authority. The evidence for row 8 includes:
  - the flag exists, off, for the environment's label, and is read back;
  - the users-groups permission exists, inactive;
  - Results' `FeatureControlGuard` read is proven OFF and ON in SIT;
  - no local feature file defines the flag;
  - the subscription's switch-off and switch-on have been rehearsed in SIT;
  - each environment's pre-cutover filter is recorded, advanced filters included.

  *(§9.2 rows 2, 8, 22; §9.5 row 6; H24; A3-77; A3-90; A3-91; A3-102; A3-103; DIGEST §15)*
- **FR-006-036**: The index MUST also carry two readiness items that are not §9.2 rows. The first is
  the service's `RequestIdRecipe` vector, which must equal the test vector of the landed producer
  helper; it is pending until Q20 lands. The second is that the data owner and the DPIA are
  informed that stored bodies hold personal data, youth defendants included; this is a note, not a
  gate. *(Q20; D9; A2-25; DIGEST §14; H18; A3-60)*

**Runbook inputs for cutover and rollback**

- **FR-006-037**: The repository MUST provide the service-side inputs to the DevOps runbook on each
  environment's cutover ticket. The inputs cover four procedures: cutover, the planned rollback,
  the urgent rollback and the roll-forward after a rollback. For every step, in the design's order,
  they give: the actor (DevOps, operations or the owning team); what is done; the service action or
  runner used, if any; the read-back that proves the step; and the step it must precede.
  Environment-specific values MUST appear only as named placeholders, which the ticket fills in:
  the subscription, its topic, the configuration store, the label, hosts and resource names. The
  runbook itself lives on the ticket, not in this repository. *(§9.2 row 22; A3-102; DIGEST §2;
  the public-repository rule)*
- **FR-006-038**: The cutover inputs MUST state the following. Cutover happens in the weekend
  deployment window, with Common Platform down.
  1. Record the legacy subscription's current filter (included event types, subject filters and
     advanced filters), then set its included event type to `TOGGLED-OFF`.
  2. Turn the Results flag on under the environment's label, and read it back for that label. Wait
     at least 10 minutes before Common Platform comes back up, because the read-back does not prove
     that Results has picked the flip up. As a secondary check, for the UI only, the users-groups
     permission shows active.

  The order is fixed: the subscription is switched off before the switch is turned on. There is no
  cutover reconciliation step. Routing is proven when the first shares are admitted once Common
  Platform is up (§9.5). SIT is cut over first, and its switch-ON proofs are preconditions for
  every later environment. The function app stays deployed, with its subscription filtered, until
  the soak ends: it is the rollback. *(H22; H25; A3-70; A3-77; A3-86; A3-88; A3-90; A3-103; A2-32;
  §9.3)*
- **FR-006-039**: The planned rollback inputs MUST state the steps below, in this order. The
  rollback happens in the weekend window, with Common Platform down.
  - 1: Pause intake. `probation_intake_suspended{cause="operator"}` reads 1, and intake stays
    paused for the whole rolled-back period.
  - 2: Turn the Results flag off, read it back for the label, and wait at least 10 minutes. As a
    secondary check, for the UI only, the users-groups permission shows inactive.
  - 2a: Restore exactly the filter recorded before cutover.
  - 3: Record the cut-off instant.
  - 4: Close the in-flight rows `FAILED(rolled-back)`, one transaction per row, fencing late
    runners (FR-006-040).
  - 5: Reconcile the period the service was live, from cutover to the start of the rollback
    window. The legacy app must show no HMPPS send or orchestration in that period.
  - 6: Purge the queue, recording the count removed and the cut-off. This is done only after the
    reconciliation, and only while intake is paused.
  - 6a: Once the Results switch is on again, replay goes only through the service's replay action:
    the operations API (005), with its runner as the fallback. The dead-letter queue holds no copy.
  - 7: Repair only the hearings not known to be delivered (FR-006-041).

  *(H23; H9; R1-14; R1-28; R1-28b; A2-40; A2-43; A2-51; A2-52; A3-73; §9.4)*
- **FR-006-040**: Rollback step 4 MUST be executed only through a supported execution path that the
  design owner places. None exists today: the transition is specified (004's FR-004-049a) and
  pinned (`RollbackFencingIT`), but no §4.8 action or runner executes it (§9.4 step 4; DIGEST
  §16), and 004 raises the question (FR-004-049). Until a path is placed, the inputs MUST record the gap and name the design owner as
  its owner, and MUST NOT describe a statement to run by hand in its place (this slice's rule). The
  step's read-back is: no leg's latest attempt is `PENDING` or `PARKED`; each request that was
  `RECEIVED` or `RETRYING` is now `FAILED(rolled-back)`; a `FAILED` request keeps its reason and
  exhausted id; and no `COMPLETED` request has changed. *(§9.4 step 4; A2-27; A2-43; A2-51;
  A2-66; DIGEST §16)*
- **FR-006-041**: The repair inputs MUST state the following rules.
  - Only these are repaired: legs closed `FAILED(rolled-back)`, legs `FAILED` for another reason,
    and dead-lettered messages.
  - The repair is a business re-share or, once a new cutover has re-enabled the service,
    submit-hearing (step 7). Once the service is re-enabled, a `FAILED` request,
    `FAILED(rolled-back)` included, may also be replayed by the service's replay action (step 6a),
    and a rolled-back resend row (attempt ≥ 2) is resend-eligible. Resend-leg is refused while
    intake is paused.
  - A `FAILED(gateway-transient)` after a 5xx, a 408 or a read timeout is outcome-unknown, and is
    confirmed with HMPPS before it is repaired by any route, a replay or a resend included.
  - A `FAILED(receiver-rejected)` re-sent unchanged is rejected again, so re-sending is not its
    repair.
  - A dead-letter entry with no `processed_request` row is repaired by a resend from the
    dead-letter queue for `exhausted`, and otherwise by submit-hearing.

  *(H23; A3-73; A2-40; A2-55; A2-62; R1-28b; H9; A3-32; D16; §9.4 steps 6a and 7)*
- **FR-006-042**: The urgent-rollback inputs MUST state the steps below, in this order. The
  urgent rollback happens out of hours, with Common Platform up. Restoring the subscription before
  intake is paused MUST NOT be offered: while the service still consumes, every share would go by
  both paths, for up to 10 minutes because of the feature cache (§9.4, "Why out of hours";
  A3-103; A3-113). Step 1 always precedes step 2.
  1. Pause intake.
  2. Restore the recorded filter at once, and record the time T. From T, the function app handles
     every new share.
  3. Turn the Results flag off and wait at least 10 minutes. Meanwhile Results keeps publishing to
     the paused queue.
  4. Close the in-flight sends, as in planned step 4.
  5. Reconcile the paused queue against T. Messages for shares before T reached neither path, and
     are repaired by a court re-share, not by the service. Messages for shares after T were handled
     by the function app, and are purged. Intake is not resumed. Then do planned steps 5–7.
  6. Purge the queue, recording the count and the cut-off.

  *(H32; A3-113; A3-117 (iii); §9.4)*
- **FR-006-043**: The roll-forward inputs MUST state the steps below, in this order, with intake
  still paused: (1) purge-queue; (2) reconcile every message received since the recorded cut-off;
  (3) run a full new cutover in a weekend window: the subscription off, then the Results flag on,
  then the 10-minute wait; (4) only then resume intake. *(A2-52; A3-117 (ii); §9.3)*
- **FR-006-044**: The inputs MUST also state what a rollback does not bring back. Events filtered
  out while the subscription carried `TOGGLED-OFF` were never delivered to the function app, and
  are not retained. There is no legacy replay tool. The legacy's silent-failure modes return, so
  the reconciliation keeps running. The inputs MUST also state that owed producer events arriving
  after the switch is off wait on the paused queue, and are counted and purged at step 6 or at the
  next roll-forward. *(A3-74; §9.4)*
- **FR-006-045**: A step MUST NOT use a property, endpoint or deployment value of this service as a
  routing lever. The two switches are Results' flag and the legacy subscription. The service never
  reads the Results switch, and pause-intake is its only service-side stop. *(H22; H26; A3-92;
  Q15; constitution Principle IX)*
- **FR-006-046**: Each service-side read-back that the inputs name MUST be an evidence query, or a
  recorded output of the action (for example the purge's count and cut-off), and MUST be pinned by
  a test. *(§9.4; §4.8 actions 4 and 6)*

**Decommissioning inputs**

- **FR-006-047**: The repository MUST supply the post-soak acceptance evidence of §9.6 row 1:
  §9.5 green for the agreed soak period; no unexplained reconciliation gap; the dead-letter queue
  empty; and P36's alerting deployed, meaning its rules, routing, an owner and a runbook. *(§9.6
  row 1; H31; A3-112; P36)*
- **FR-006-048**: Decommissioning MUST need no change to the service's code or configuration. At
  decommissioning, the legacy-app-active alert and the soak's legacy-silence check end, together
  with the subscription and the function app's alerts. The service's dashboards become the only
  ones. *(§9.6 rows 3, 8; A3-75)*
- **FR-006-049**: The rest of §9.6 MUST be listed as external steps, each depending on the
  per-environment inventory:
  - the inventory itself, per environment;
  - the owner's approval that rollback is no longer needed;
  - removing the subscription, the function app, its plan (only where no other app shares it), the
    STE container route, obsolete settings and the legacy code;
  - updating the ownership record;
  - the cost check;
  - the VEP back-out, after the business confirms H5;
  - keeping the LAA feed's owner informed: the shared subscription key and the shared `EXT_`
    payload are unchanged, and nothing needs that owner's agreement (D12).

  The gateway subscription key stays in use by the service and by the LAA feed. *(§9.6; R1-19;
  R1-32; R1-32b; D4a; D12; H5)*

**Boundaries and repository hygiene**

- **FR-006-050**: What this slice adds to the repository MUST NOT carry personal names, hostnames,
  URLs other than the design page's, cloud resource names, environment labels, production figures,
  or payload or body content. Roles stand for people, and named placeholders stand for environment
  values. *(the public-repository rule; Principle VII)*
- **FR-006-051**: This slice MUST NOT add an operator action, an HTTP endpoint, a metric or label
  value, a `distribution.*` property or a read of the Results switch. It MUST NOT change what is
  sent (D10), the gateway (D4a) or the legacy function app's code (H22). *(O-1; H26; A3-92;
  DIGEST §12; D10; D4a; H22)*
- **FR-006-052**: The operations reference that this slice adds MUST carry rules, steps and queries
  only, each tagged with its design row id, and no rationale. The reference is the alert catalogue,
  the dashboard and reconciliation definitions, the evidence index and the runbook inputs. Its
  file layout is the plan's to choose. The reasoning stays on the design page. *(the repository's
  no-design-narrative rule; DIGEST §1)*

### Key Entities

- **Replay run**: one comparison of a chosen week. It holds the week, the export dates, the
  results of the pre-run checks, the STE build or builds, and the report with its verdict.
- **Hearing comparison**: one share of a hearing (hearing id, hearing day and `sharedTime`
  instant) in the union of the two sides. It holds its class (match; registered deviation, with its
  DV id; explained difference; unexplained difference; unpaired, with its sub-reason SIT-only,
  STE-only or ambiguous; or SIT body unusable), the JSON paths that differ, for a SIT-body-unusable
  share why its body is unusable (its expected body pending 001's FR-001-021), and the STE
  attempt's `status`, `payload_source`, `youth_switch` and `service_version`.
- **Explanation entry**: a reason attached by a named role to the differing paths of one share, or
  to an unpaired or SIT-body-unusable share as a whole. It is shown in the report, and is stale
  when it matches no current difference or share.
- **Alert definition**: one of the eleven paging alerts. It holds the backing series and the
  bounded labels it selects, its condition and window, the status of its threshold (fixed, or a
  proposal), its Azure Monitor rule type, its routing, its runbook entry and its lapse.
- **Counted signal**: one of the five known-issue classes. It holds its series and labels, and its
  place on the dashboard and in the daily reconciliation.
- **Daily reconciliation**: one day's comparison of results' shares, the requests received and
  each leg's latest state. It also holds the dead-letter-only class, the gap in flight, the
  known-issue counts and, during the soak, the legacy-silence check.
- **Go/no-go evidence row**: one row of §9.2 or §9.5. It holds the check, the evidence, the
  supplier, where the evidence is obtained, and the row's design status.
- **Runbook input step**: one numbered step of one of the four procedures. It holds the actor,
  the action, the service capability used, the read-back and the step it must precede.
- **External precondition**: work owned by another team, with the design ask or row it comes from
  and the evidence that shows it is met.

## Success Criteria *(mandatory)*

### Measurable Outcomes

- **SC-006-001**: For seeded inputs that cover every class and every unpaired sub-reason, 100% of the
  shares in the union of the two sides appear in exactly one class of the replay report, and none
  is dropped.
- **SC-006-002**: In 100% of seeded runs with at least one unexplained difference, or an unpaired or
  SIT-body-unusable share with no explanation entry, the verdict is "blocks cutover". With nothing
  left unexplained, the verdict is "no unexplained differences".
- **SC-006-003**: Zero input values appear in any report or evidence output of this slice. A sweep for
  sentinel values planted in every seeded payload and body finds none of them.
- **SC-006-004**: The alert catalogue holds all 11 paging alerts of design §10.3 as H34 amends it, each
  with its series, condition, rule type, runbook entry and routing (email to the owning team, H36). None of the 5 counted classes
  pages.
- **SC-006-005**: There are no dead alerts. A test shows that the service emits every service series
  and label value that an alert or a dashboard panel names.
- **SC-006-006**: 100% of the rows of §9.2 (24 rows) and §9.5 (9 rows) are in the evidence index, each
  with its status, evidence and supplier. 100% of the rows the build supports have a test-pinned
  evidence query or a named artefact.
- **SC-006-007**: The runbook inputs cover 100% of the numbered steps of the four procedures, in the
  design's order, each with its actor and its read-back: cutover (2 steps), planned rollback (9),
  urgent rollback (6) and roll-forward (4).
- **SC-006-008**: A hygiene sweep over everything this slice adds finds zero personal names,
  hostnames, URLs other than the design page's, cloud resource names, environment labels and
  production figures.
- **SC-006-009**: Over a seeded store, the reconciliation's per-day counts equal the expected counts in
  100% of the seeded cases. These include a `COMPLETED` request whose latest leg attempt is
  `FAILED`, a leg recovered by a resend, and a share that exists only in the dead-letter queue.
- **SC-006-010**: For every environment cut over, the go/no-go record shows the youth default equal to
  the live `EMYouthFilter` reading at cutover. In a rehearsal, a mismatch in either direction is
  recorded as no-go.
- **SC-006-011**: After cutover, a "both" is detected within one evaluation period of the
  legacy-app query (15 minutes, as proposed) plus the telemetry's ingestion delay, and in any case
  by the next daily reconciliation.

## Assumptions

1. **The comparator is 001's.** The canonicalisation, the comparator, the deviation derivation and
   the share identity come from 001-parity-kernel, and so does the open question on a cut-short SIT
   logged body (FR-001-021), whose interim rule the replay carries unchanged. The replay adds only
   the pairing, the classes, the explanations and the report.
2. **The replay exports are inputs to a run, not fixtures.** They are SIT data, held under the
   corpus controls, and this slice commits none of them.
3. **Alerting is deployed outside this repository.** The alert rules, the dashboards and the
   legacy-app query are deployed through the platform's monitoring. This repository holds their
   catalogue and definitions, and the plan chooses their machine-readable form. DevOps create the
   rules by hand on a ticket, with the runbook, not in infrastructure code (H36).
4. **The normal daily level is set per environment.** The level behind the spike in case-hearing
   400s is set from each environment's observed baseline, and tuned after go-live. No production
   baseline is written here.
5. **Queries are run only by people entitled to the data.** The evidence and reconciliation
   queries are run by the service role and the security-cleared operations team (H18). Only people
   entitled to read bodies run the body-sampling check of FR-006-034.
6. **The deployed alerting is recommended before the first production cutover**, so that the soak
   is watched. The design makes it a post-soak criterion (§9.6 row 1), not a §9.2 row, and this
   spec adds no gate.
7. **The owning team owns** the replay and its report, the alert routing and the daily
   reconciliation (H31; A3-111). The alerts email the Resulting Assistant team (H36).
8. **Names the design marks as proposed are used as proposed.** These include
   `probation_crash_reruns_total`, `probation_gateway_recovered_total`, the `shape` and `http`
   labels, `probation_flag_read_failures_total` and `RequestIdRecipe`.
9. **The stub gateway may reuse the local stack's gateway stub mapping.** Deploying it in STE, with
   https and a certificate the service trusts, is done outside this repository.
10. **The rows of §9.2 and §9.5 are taken as rev 3.6 states them.** If a later revision changes a
    row, the index is amended with it.

## Dependencies

### Slices

- **001-parity-kernel**: the comparator, the canonicalisation and the deviation derivation keyed
  on `doc/DEVIATIONS.md`; the share identity (FR-001-017); the open question on a cut-short SIT
  logged body (FR-001-021), whose interim rule FR-006-007 carries unchanged and whose answer, from
  the design owner, the replay follows; the corpus check that every body has `hearing.id`
  (row 12); and the SIT-data unit tests (row 4).
- **002-hmpps-distribution**: V1 (the tables and the view that the queries read), every metric and
  log line that the alerts and panels read (H34), readiness on the store only, the startup log line
  of the youth default, and the attempt rows that rows 1, 7 and 9 read.
- **003-body-retention**: the body-maintenance signals behind alert 9 — the DEFAULT-partition,
  months-ahead and last-success gauges, read from the database on every pod (FR-003-022,
  FR-003-031, FR-003-032), the last success kept in `scheduled_job_status` by 003's Flyway `V2`
  (H38), and the failure counter — the run log lines (FR-003-020, FR-003-030), and the runbook for
  the DEFAULT partition. 003's open question 2 is answered (A3-118; its AS-15).
- **004-recovery**: the command-line runners (FR-004-042, FR-004-043) for the actions the runbook
  inputs use: pause and resume intake (FR-004-051), purge-queue (FR-004-053), replay (FR-004-014),
  submit-hearing (FR-004-054) and the request listing; replay of a request closed
  `FAILED(rolled-back)` (FR-004-048); the dead-letter runbook (FR-004-046); and the pinned
  `RequestIdRecipe` vector (FR-004-055). How an operator pause holds on every replica and across a
  restart is 004's open question (FR-004-052). 004 specifies the close-in-flight statement of
  rollback step 4 (FR-004-049a) and pins it (`RollbackFencingIT`), but delivers no execution path
  for it: it raises that question (FR-004-049), and the design owner places the path (External
  preconditions).
- **005-operations-api**: the HTTP exposure of replay and resend-leg behind the five gates. After a
  rollback, replay goes through the operations API.

### External preconditions (not built here)

**The results producer change** (built in `cpp-context-results` by the results team; DIGEST §14,
§4.3):

- The probation publish sits on the non-SJP `Hearing_Resulted` branch only. It runs after the
  `EXT_` and `INT_` cache writes, the Event Grid publish and `add-hearing-result-for-day`.
- The publish is gated by `FeatureControlGuard.isFeatureEnabled("ProbationResultsDistributionService")`,
  read from App Configuration under the environment's label. The gate is fail-closed: a missing
  entry or a failed read sends nothing. A failed read is counted on
  `probation_flag_read_failures_total` (to be added), which alert 10 reads (H26; A3-89).
- The change copies the informant chain with the names changed: command
  `results.command.request-probation-distribution-publish` → `ProbationDistributionHandler` → event
  `results.events.probation-distribution-publish-requested` →
  `ProbationDistributionPublishRequestedProcessor` → `ProbationDistributionQueuePublisher` (JNDI
  `probationDistributionQueueNamespace` and `probationDistributionQueueName`; workload identity).
  It keeps the informant chain's two failure behaviours and adds
  `ProbationDistributionQueueConfigurationHealthcheck`.
- It keeps the `userId` gate, and sets the message properties `messageId = RESULTS:{requestId}`,
  `correlationId = hearingId` and `contentType = application/json`.
- It carries the `requestId` recipe and its test vector. The vector is taken again when the
  landing order of the results branch chains is settled (Q20; D9).
- Results sees a flip within up to 10 minutes (A3-103).
- Known paths it does not cover: the older `public.hearing.resulted` path, which is unused in
  production and would show in the reconciliation if used again; and a reference-data failure in
  results' handler, which produces neither the `EXT_` payload nor the command (A3-107; A2-14).

**Platform provisioning** (the platform team; the asks of §8.4; DIGEST §15):

- Ask 1: the queue `probation.requests` and its dead-letter queue in each environment. Duplicate
  detection off, lock 5 min, `maxDeliveryCount` 5; stack-prefixed in deployed environments.
- Ask 2: the service's workload identity. The receiver role on the namespace, the sender role on
  the queue and the Key Vault secrets role; no App Configuration role.
- Asks 3 and 4: the producer's sender role, confirmed for each environment, and the producer's JNDI
  queue values for each environment.
- Ask 5: the database on the environment's Results PostgreSQL server, created by the platform's
  dbsetup job and owned by the service role. Flyway runs in the service.
- Ask 6: Key Vault through the CSI driver, holding the gateway subscription key (the same value as
  the legacy's), the Redis key, the datasource credentials, the system user id and the telemetry
  connection string. No deployment value carries a secret.
- Ask 6a: a network path from the pod to the users-groups query API, and the system user permitted
  on the permissions query.
- Ask 7: the App Configuration flag `ProbationResultsDistributionService`, off, under every
  environment label; and the users-groups static-data patch that creates the feature and its
  permission, inactive. The patch is re-applied after every STE rebuild.
- Ask 9: a network path from the pod to the gateway, and the gateway's internal CA in the
  truststore.
- Asks 12 and 14: the five gates of the operations API, including an authored NetworkPolicy.
- Ask 15: DevOps rights to change the legacy subscription's filter, by hand, on the cutover ticket.
- Ask 16: the service's own system user. A static-data patch adds it with the system-users role,
  and its id is held in Key Vault. The patch is re-applied after every STE rebuild.
- The deployment of the alerting and the dashboards: Azure Monitor rules created by DevOps by hand
  on a ticket, with the runbook, emailing the Resulting Assistant team through an action group (H36;
  A3-111; P36), on the App Insights resource each environment reports to (Q29).
- For the replay: an STE stack with the service deployed; a stub gateway that answers 200 over
  https; STE's network policy, which stops egress to HMPPS; and STE's `EMYouthFilter` feature
  permission in users-groups, reading inactive, in place again with the service's system user after
  any STE rebuild (FR-006-008).

**Operations and DevOps**: the cutover ticket and its runbook for each environment (row 22); the
SIT negative tests (row 21); the switch rehearsals and the filter records (row 8); the test call
(row 9); and the export of the replay week (FR-006-009).

**HMPPS**: confirmation that sample hearings were received (§9.5 row 6), and confirmation before
an outcome-unknown leg is re-sent.

**The design owner**: a supported execution path for rollback step 4, whose statement is 004's
FR-004-049a (§9.4 step 4; DIGEST §16; raised in 004 as FR-004-049); once it is placed, the
cross-spec pass records which slice builds it. Also the answer, with HMPPS, to what the row-9 test
call sends (FR-006-032), and the answer to 001's FR-001-021 (the expected body of a share whose SIT
logged body is cut short).

**The business**: the re-shares used in repair (§9.4 step 7; H32 step 5); changes to the live
toggle, each of which triggers the row-7 check again; and confirmation of H5 before the VEP
back-out.

**The data owner and the DPIA**: informed that stored bodies hold personal data (a note, not a
gate; H18).

## Out of Scope

- The results producer change, and every platform ask above. They are dependencies, not work in
  this repository.
- The DevOps runbook itself and carrying it out: switching the legacy subscription and flipping the
  flag are done by hand on the ticket.
- Carrying out decommissioning (§9.6), and the VEP back-out.
- Building or changing any metric, log line, operator action, endpoint or property (002–005), and
  HTTP exposure of further actions (O-1).
- A service-side read of the Results switch, or any second lever (H26; A3-92).
- A shadow run or a shadow capture (H21).
- Any change to what is sent (D10), to the gateway (D4a, D4b) or to the legacy function app's code
  (H22).
- The legacy topic's ad hoc replay subscription (H29).
- Processing only the messages from before T in an urgent rollback. This is not established, and a
  court re-share is the route (A3-117).
- The SJP parity check, which is settled (Q18; A3-100).
- VEP (H5).

## Open items carried from the design (not decided here)

- **A3-78**: the replay mechanics (FR-006-010). The owning team settles them before the replay is
  planned.
- **001's open question 2** (FR-001-021): the expected body for a share whose SIT logged body is
  cut short or not complete JSON. Until the design owner answers, such a share is SIT body
  unusable and blocks cutover until it is explained (FR-006-007).
- **§10.4**: the source of results' daily share count, and its day boundary (FR-006-024). The
  results team owns this.
- **Q28**: whether `FAILED(payload-changed)` and `FAILED(rolled-back)` page. They stay on the
  leg-failed alert until Q28 is decided.
- **P36 and §10.3**: whether counting 400s satisfies the legacy alert-on-failure ticket. That
  ticket's owner decides.
- **A3-75**: the query of the legacy-app-active alert is proposed, not designed. The telemetry
  resources for environments other than SIT and PRD are not established.
- **§9.4 step 4**: the close-in-flight statement is specified in 004 (FR-004-049a), but no
  supported execution path runs it (DIGEST §16). The design owner places one (004 raises the
  question, FR-004-049). Once it is placed, the cross-spec pass records which slice builds it, and
  FR-006-040's inputs name it.
- **Q29 (H36)**: the App Insights resource the service reports to in each environment
  (`APPLICATIONINSIGHTS_CONNECTION_STRING`), which fixes where DevOps create the rules, and the metric
  names as the agent exports them (FR-006-019, FR-006-053).
- **Q20 and D9**: the landing order of the results branch chains, whether the helper is shared or
  forked, and taking the vector again.
- **§11.3**: which header, if any, carries a gateway request id. This is known after the row-9 test
  call.
- **§9.2 row 9 where the legacy still sends** (FR-006-032): the design does not say what the test
  call sends, or by which action. A real hearing sent before cutover would be a genuine duplicate
  at HMPPS (H12). The design owner decides, with HMPPS.
- **A3-89**: the timeout of results' `FeatureControlGuard` call, on the results side.
- **H5**: the business has not yet confirmed that VEP is not needed. This gates the VEP back-out in
  §9.6.
- **Where the platform keeps the alert-rule and dashboard definitions** (platform team).
- **Which Confluence sections already carry the rev 3.6 text.** Where a section lags, the rev 3.6
  decision applies.

## Design traceability

| Design id | Functional requirements |
|---|---|
| H21; A3-66; A3-68; §6.5 | FR-006-001 – FR-006-013 |
| A3-78 | FR-006-009, FR-006-010 |
| H28; A3-67; §6.1; §6.4 | FR-006-007, FR-006-009, FR-006-012 |
| H17 | FR-006-002 |
| §6.2 | FR-006-001, FR-006-004 |
| DV-25; H8; A3-28 | FR-006-004 |
| H7; A3-24; A3-27; A3-29 | FR-006-004, FR-006-008, FR-006-033, FR-006-034 |
| H19 | FR-006-005 |
| H30; A3-106; §8.4 asks 6a, 16 | FR-006-008, FR-006-032 |
| H24 | FR-006-035 |
| DIGEST §12 | FR-006-008, FR-006-051 |
| H34; A3-115; R1-18 | FR-006-014, FR-006-016, FR-006-018, FR-006-019, FR-006-021 |
| R1-31; R1-31b | FR-006-015 |
| H13; Q28 | FR-006-016 |
| H11; H15; A3-117 (i) | FR-006-017, FR-006-041 |
| H33; A3-114; A3-116; D16 | FR-006-022, FR-006-041 |
| H20; A3-64 | FR-006-014 (alert 9) |
| H26; A3-71; A3-89 | FR-006-014 (alert 10), FR-006-045, FR-006-051 |
| A3-75; §9.5 row 9 | FR-006-020, FR-006-027, FR-006-048 |
| A3-32; A3-33; A3-55; A2-48; A2-55 | FR-006-014 (alert 1), FR-006-026, FR-006-041 |
| A3-47 | FR-006-014 (alert 7) |
| P36 | FR-006-021, FR-006-047 |
| §10.4; A2-49 | FR-006-023 – FR-006-030 |
| H12 | FR-006-029, FR-006-031, FR-006-032 |
| H31; A3-111; A3-112; O-3 | FR-006-013, FR-006-014, FR-006-028, FR-006-047 |
| H36; H37; H38; A3-118 | FR-006-014, FR-006-018, FR-006-019, FR-006-053 |
| §9.2; §9.5; DIGEST §15; A2-10; A2-32 | FR-006-031, FR-006-032, FR-006-035, FR-006-038 |
| A3-107; A3-108; A3-109 | FR-006-031 |
| Q13 | FR-006-032 (row 12) |
| H25; A3-87 | FR-006-032 (row 21), FR-006-038 |
| §11.3; A3-108 | FR-006-032 (row 9) |
| H18; A3-60 | FR-006-012, FR-006-030, FR-006-034, FR-006-036 |
| Q20; D9; A2-25 | FR-006-036 |
| §9.2 row 22; A3-102 | FR-006-035, FR-006-037 |
| H22; A3-70; A3-77; A3-86; A3-88; A3-90; A3-91; A3-103 | FR-006-035, FR-006-038, FR-006-045 |
| H23; H9; R1-14; R1-28; R1-28b; A2-40; A2-43; A2-51; A2-52; A2-62; A3-73 | FR-006-039, FR-006-041 |
| §9.4 step 4; A2-27; A2-66 | FR-006-040 |
| H32; A3-113; A3-117 (iii); A3-103 | FR-006-042 |
| A2-52; A3-117 (ii) | FR-006-043 |
| A3-74 | FR-006-044 |
| §9.4; §4.8 actions 4 and 6 | FR-006-046 |
| Q15; A3-92; constitution Principle IX | FR-006-045, FR-006-051 |
| §9.6; R1-19; R1-32; R1-32b; D4a; D12; H5 | FR-006-047 – FR-006-049 |
| D10; O-1 | FR-006-051 |
| DIGEST §1, §2; the public-repository and no-design-narrative rules; Principle VII | FR-006-037, FR-006-050, FR-006-052 |
| Q18; A3-100; H29; A3-101; H5 | Out of Scope |
