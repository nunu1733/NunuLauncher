---
issue: "#67"
status: accepted
requirements:
  - FR-015
  - NFR-008
  - NFR-011
updated: 2026-08-18
---

# Privacy-safe organizer diagnostics journal and export

## Problem

The accepted organizer diagnostics contract in
[`docs/engineering/organizer-diagnostics.md`](../../docs/engineering/organizer-diagnostics.md)
defines the allowed `RunEvent` data model, phase taxonomy, redaction rules,
retention, export behavior, logcat behavior, and restart correlation, but the
runtime has no diagnostics module that enforces that contract. Organizer runs
therefore cannot yet leave a durable privacy-safe phase history, application and
recovery outcomes are not correlated across process death through the journal,
and the user has no explicit way to export the approved diagnostic field set.

The implementation must not solve observability by logging raw planner,
application, recovery, Launcher, or user data. The diagnostic representation is
a deliberately smaller typed projection of already accepted contracts.

## Outcome

Organizer diagnostics are represented by the closed `RunEvent` contract and are
persisted in an app-private append-only journal with durable ordering. Existing
planner/application/recovery results can be projected into those events without
changing their public types. Application/recovery lifecycle attachment points
emit the required apply-stage and restart-reconciliation events, while later run
orchestrators emit their UI/run phases through the same diagnostics port.

Journal retention is bounded and protects unresolved recovery work. A user can
explicitly export the journal through a system-selected destination or share
flow, and organizer diagnostic logcat output uses one redacted sink. Journal,
export, and logcat expose no data outside the accepted Allowed field set and add
no telemetry or network transport.

## Scope

- The organizer diagnostics runtime seam and closed `RunEvent` value model
  defined by the accepted diagnostics contract §§3–6.
- An app-private durable append-only journal with monotonic sequence ordering,
  lazy retention, corruption recovery, and fail-open diagnostic failure
  semantics.
- Typed projection from the existing planning, application, and recovery result
  contracts into `RunEvent`; the public planner/application types do not change.
- Diagnostics-owned value types that do not exist yet in the codebase and are
  created by this Issue inside the diagnostics module or at its boundary:
  the `ApplyStage` (A0–A8) enum, `PlanSummary`, and `ApplySummary`. They are
  derived projections of existing lifecycle control flow and result data, and
  adding them must not alter the public Issue #10/#13 result types.
- Application/recovery attachment points needed to record `applyStage`,
  checkpoint/application terminal outcomes, and `RESTART_RECONCILED` at the
  existing Issue #13 lifecycle seams.
- A single organizer diagnostics logcat sink derived only from successfully
  persisted events.
- User-initiated journal export from Settings through SAF and/or the Android
  share flow, using the accepted line-delimited JSON shape.
- Contract, lifecycle, privacy-negative, backup-exclusion, and UI evidence for
  the behavior in this spec.

## Non-goals

- The manual, onboarding, or incremental run-flow orchestration itself. Issues
  #52, #53, and #55 own emission of UI/run phases such as capture, preview,
  confirmation, cancellation, and their trigger-specific sequencing through the
  diagnostics port defined here.
- A second planner, application, recovery, or orchestration seam.
- Changes to Issue #10 planning public types or Issue #13
  application/recovery public types.
- Crash collection, stack-trace collection, exception-message persistence, or a
  replacement for the platform crash buffer.
- Telemetry, analytics SDKs, background upload, server transport, automatic
  sharing, or any permission added for diagnostics.
- Performance measurement protocol or benchmark collection; Issue #15 may
  consume phase timestamps later without changing this contract.
- Lock-authoring or other non-organization-run operational diagnostics.

## Domain language

No new product/domain term is introduced. `RunEvent`, `RunId`,
`RecoveryPointId`, `PhaseCode`, `ApplyStage`, and recovery lifecycle terms use
the meanings in the accepted organizer diagnostics and Issue #13 contracts.

## Normative contract relationship

This spec owns the observable implementation behavior for Issue #67. It does
not duplicate the complete field/enumeration tables already accepted in
`docs/engineering/organizer-diagnostics.md`:

- diagnostics contract §§3–6 are the exact field, phase, error, and summary
  vocabulary consumed by this spec;
- §7 is the maximum data classification allowed on every output surface;
- §8 local retention, §9 export, §10 logcat, §11 restart correlation, and §12
  network/telemetry boundaries are exercised here;
- §13 D-01–D-10 are the representative serialization corpus.

If implementation requires a field not present in that accepted contract, the
implementation stops and the owning contract is revised first. It must not add
an ad-hoc message, metadata map, debug-only payload, or raw exception field.

## Observable diagnostics contract

### Closed event representation

A persisted event is one schema-versioned `RunEvent` from the accepted field
set. The runtime representation and serializer must make the following classes
of data unrepresentable as arbitrary diagnostic payload:

- free-form messages and notes;
- package/component/shortcut/widget-provider identity;
- user/profile identity values;
- layout coordinates, spans, ranks, page/folder membership, or row contents;
- rule content or item-level category decisions;
- planner `DiagnosticParam` values;
- content-derived IDs such as revisions, item IDs, page IDs, folder IDs, or
  digests;
- exception messages, stack traces, SQL, or DB/recovery-record content.

Random opaque `RunId` and `RecoveryPointId`, closed enum/code values, counts,
approved device dimensions, approved version identifiers, wall-clock time, and
journal sequence are allowed exactly as defined by the diagnostics contract.

Unknown source enum values are not converted to arbitrary strings. Where the
accepted diagnostics contract specifies `UNMAPPED`, the event records only that
closed fallback plus its family and never the source payload.

### Event ordering and durability

- `journalSequence` is strictly monotonic across the journal and continues after
  process restart.
- `recordedAtWallMillis` is recorded for correlation, but ordering is determined
  by `journalSequence`, not by wall-clock monotonicity.
- A lifecycle event that protects a dangerous transition is persisted before
  the subsequent dangerous step proceeds, at the attachment point defined by
  the accepted diagnostics/application contracts.
- Logcat rendering happens only after the corresponding journal append succeeds.
  Logcat is not a fallback path when persistence fails.
- A diagnostics persistence failure does not turn an otherwise valid organizer
  operation into planner/application failure and does not mutate layout or
  recovery state. Diagnostics are fail-open with respect to the organizer
  operation, while remaining fail-closed with respect to privacy.

### Projection from existing result contracts

The projection layer consumes existing typed results; it never parses localized
user-facing text and never copies raw diagnostic parameters.

For the current public variants, the projected phase/error/summary must match
`docs/engineering/organizer-diagnostics.md` §§4–6 and D-01–D-08, including at
least:

- planning success -> `PLANNED` with `PlanSummary` counts;
- planner `Rejected.Invalid` -> `PLANNING_REJECTED` with
  `PLANNING_INVALID` and accepted code/count information only;
- planner `Rejected.Impossible` -> `PLANNING_IMPOSSIBLE` with the approved
  count summary, without item-level details;
- application checkpoint success/failure -> `CHECKPOINTED` or
  `CHECKPOINT_REJECTED` at the applicable Issue #13 stage;
- application no-change/reject/concurrent/commit/verified/rollback/recovered/
  unresolved/recovery-failed outcomes -> the corresponding accepted
  `APPLY_*`/`CONCURRENT_RUN_REJECTED` phase with the stage and typed error or
  approved summary where applicable;
- explicit recovery request/results -> the corresponding `RECOVERY_*` phases
  correlated by recovery point and origin run when available;
- restart reconciliation -> `RESTART_RECONCILED` with the accepted prior
  lifecycle, classification, resulting lifecycle, and subject run correlation.

Every current planner rejection/result enum and Issue #13 public result variant
must be represented by a deterministic projection case or by an explicit
contract-approved no-event case. Adding a new public enum/result later requires
a projection-test update; silently falling through is not allowed.

### Ownership of event attachment points

Issue #60 is complete. Its implemented spec explicitly leaves run-journal,
`applyStage`, and `RESTART_RECONCILED` implementation to Issue #67. Therefore
this spec resolves the older handoff wording as follows:

- Issue #67 owns diagnostics attachment at existing application/recovery
  lifecycle seams where stage/reconciliation information exists only inside
  that module.
- Issues #52/#53/#55 own run-orchestration/UI phase emission and call the
  diagnostics seam defined by #67.
- Issue #67 does not invent UI lifecycle events before those run flows exist.

This ownership split must not create two event stores or two logger APIs.

## Behavior scenarios

### Scenario: successful append survives restart

Given an organizer diagnostic journal with a persisted sequence value
When a valid event is appended and the process is later restarted
Then the event is readable from app-private storage
And the next appended event has a strictly greater `journalSequence`
And event ordering does not depend on the device wall clock moving forward.

### Scenario: application stage is recorded before the next dangerous step

Given Issue #13 application has reached a stage with a required diagnostic event
When application advances through checkpoint, commit, verification, rollback,
or another contract-defined lifecycle transition
Then the corresponding `RunEvent` is synchronously persisted at the accepted
attachment point
And the event carries the applicable `ApplyStage`
And the application public result type is unchanged.

### Scenario: process death is reconciled without copying crash data

Given a run has a durable correlation ID and recovery lifecycle state
And the process dies after a persisted application event
When startup reconciliation classifies that recovery state
Then a `RESTART_RECONCILED` event is appended with the accepted reconciliation
fields
And it can be correlated to the prior run through the approved opaque IDs
And no stack trace, exception message, revision, row, or recovery manifest is
copied into the journal.

### Scenario: planner rejection is projected without raw params

Given planning returns a typed rejection that contains diagnostic parameters
When the rejection is projected into diagnostics
Then the event contains only the accepted phase, error family/code, and approved
counts
And `DiagnosticParam` values and any package/profile/layout identity are absent
from journal serialization, export, and logcat.

### Scenario: journal write failure does not fail the organizer run

Given the journal store cannot append because of an injected I/O failure
When the organizer emits a valid diagnostic event
Then the organizer operation continues according to its own planner/application
contract
And no layout or recovery mutation is performed solely to repair diagnostics
And the failed event is not emitted to logcat as if it had been persisted
And no raw fallback log line is produced.

### Scenario: corrupt journal is isolated from organizer state

Given the journal file is corrupt or unreadable at open
When diagnostics initialize or the next event is appended
Then diagnostics discard/reset only the diagnostics journal and start a valid
new journal
And layout, recovery, lock, rule, and preference state are not reset or
rewritten as a side effect.

### Scenario: retention evicts complete old run history

Given the journal contains resolved run histories eligible for deletion
When an append causes the 10-run, 7-day, or 512 KiB retention limit to be
exceeded
Then pruning is performed lazily from the oldest eligible run history
And eligible history is removed as a correlated run unit rather than deleting a
middle event from an otherwise retained run
And the resulting retained journal satisfies every applicable limit unless
protected unresolved history alone exceeds a limit.

### Scenario: unresolved history is protected

Given a run has recovery lifecycle `APPLYING`, `COMMITTED_UNVERIFIED`, or
`RESTORING`
When journal retention would otherwise evict that run
Then its diagnostic history is retained until a terminal resolution or
`RESTART_RECONCILED` resolves the protected state
And unresolved protection takes precedence over the 10-run, 7-day, and 512 KiB
caps
And the next eligible lazy prune after resolution may remove it according to
normal retention order.

### Scenario: explicit export produces only approved data

Given the journal contains persisted events
When the user explicitly chooses the diagnostics export action in Settings and
selects a destination/share target through the system flow
Then the app writes the accepted export header followed by line-delimited JSON
events in ascending `journalSequence`
And exported event fields are the same approved `RunEvent` fields already
present in the journal
And no verbose/debug/raw field is added during export
And export does not mutate or prune the journal.

### Scenario: export is never automatic

Given diagnostics exist and the app starts, runs in background, crashes,
restarts, or completes an organizer run
When the user has not selected the diagnostics export action
Then no diagnostics export or share intent is initiated
And no organizer diagnostic data is sent to an app-selected remote endpoint.

### Scenario: export cancellation or write failure is isolated

Given the user starts export
When the system destination flow is cancelled or the selected destination cannot
be written
Then no layout/recovery/organizer state changes
And the journal remains available for a later retry
And the app does not fall back to a network or raw-log export path.

### Scenario: debug logcat is a redacted event projection

Given a persisted organizer event in a debug build
When the diagnostics logger renders it
Then it uses the single organizer diagnostics tag
And the line contains only the accepted event subset such as opaque correlation,
phase, stage, typed error code, and approved counts
And a terminal failure is WARN while ordinary phase transitions are DEBUG
And no Never-classified value is rendered.

### Scenario: release logcat is failure-only

Given organizer events in a release build
When diagnostics logging is evaluated
Then non-failure phase transitions produce no organizer diagnostic log line
And accepted terminal failure events may produce WARN through the same single
tag and redacted renderer
And build type never relaxes the privacy classification.

### Scenario: diagnostics journal is not backed up

Given the normal Lawnchair backup/restore mechanisms and Android backup scheme
When backup inclusion is evaluated
Then the diagnostics journal resource is not included
And restoring launcher/application data does not restore a prior diagnostics
journal through those backup paths.

### Scenario: Settings export action is accessible and localizable

Given the Settings surface containing the diagnostics export action
When it is navigated by TalkBack, keyboard/switch navigation, or enlarged text
Then the control has an accessible localized label and remains operable without
color-only meaning
And activation opens the same explicit system destination/share flow used by
pointer/touch interaction.

## Data and state

- The journal is an app-private resource owned by organizer diagnostics and is
  separate from the Issue #13 recovery database and Launcher layout DB. It lives
  under the app-private storage directory (e.g. `context.filesDir`-based path)
  and is not named in the Lawnchair backup allowlist.
- Journal schema version 1 uses the accepted `RunEvent` schema. A later schema
  requires an explicit compatibility/migration decision; silent reinterpretation
  is not allowed.
- Durable sequence state belongs to the journal and must survive process restart.
- Retention is lazy on append; no alarm, periodic worker, background service, or
  resident pruning thread is introduced for retention.
- Retention limits are: at most the most recent 10 resolved run histories, at
  most 7 days for eligible history, and at most 512 KiB for eligible history.
  Oldest eligible run history is pruned first. Protected unresolved history may
  temporarily force the journal above a normal cap; safety correlation wins over
  space reclamation until resolution.
- Recovery events retain approved point/run correlation but never duplicate the
  recovery manifest, database rows, digest, raw columns, or revision.
- Export reads a stable journal snapshot for serialization and does not make the
  export destination the new source of truth.
- No migration of existing user layout, recovery records, locks, rules, or
  preferences is required by diagnostics creation.

## Permissions, privacy, and security

- No permission is added for diagnostics. In particular, this Issue does not add
  a permission in order to transmit or export diagnostics.
- Diagnostics introduce no telemetry SDK, network client, remote transport
  interface, upload worker, background sender, or analytics endpoint.
- A system share target chosen by the user may itself be a network-capable app;
  that user-selected transfer is outside the organizer transport boundary. The
  organizer app does not select or contact a remote recipient.
- Journal, export, and logcat share the same maximum Allowed field set. There is
  no more-permissive debug mode.
- D-09 negative strings/classes are tested for non-containment across all three
  serialized/rendered surfaces.
- Random opaque correlation IDs are generated independently of layout content.
  Content-derived identifiers must not be substituted for them.

## Accessibility and localization

- The Settings action that starts export has a localized user-facing label and
  accessible semantics consistent with nearby Settings controls.
- The action is reachable and operable with TalkBack and keyboard/switch
  navigation and remains usable at 200% font scaling without hiding the action.
- Privacy/error enum names are developer diagnostics and are not presented as
  raw user-facing error text. Any export UI failure/cancel presentation uses
  normal localized UI messaging rather than exposing exceptions.
- SAF/share destination UI is system-owned; this spec does not replace it with a
  custom inaccessible file picker.

## Failure behavior

| Failure | Required behavior |
|---|---|
| Journal append I/O failure | Organizer operation continues; no raw log fallback; failed event is not represented as persisted. |
| Journal parse/corruption failure | Reset/isolate diagnostics journal only; organizer/layout/recovery state remains untouched. |
| Unknown projected source code | Use the accepted closed fallback (`UNMAPPED` where defined), never raw source text/value. |
| Retention limit with unresolved run | Preserve unresolved history even if a normal cap is temporarily exceeded; prune after resolution when eligible. |
| Export picker/share cancellation | No organizer/journal mutation and no automatic retry/send. |
| Export destination write failure | Journal remains intact and retryable; no network/raw-log fallback. |
| Logcat failure/unavailability | Journal remains authoritative; organizer behavior is unaffected. |
| Backup/restore | Journal is excluded rather than merged/restored from normal backup paths. |

## Acceptance criteria

- [ ] **AC-67-01 — Closed schema:** production and test diagnostics values can
  serialize only the accepted `RunEvent` field set and closed enum/code values;
  no free-form payload field or debug-only extension exists.
- [ ] **AC-67-02 — Durable ordering:** append/reopen/restart tests prove strictly
  increasing durable `journalSequence`, event order independent of wall-clock
  rollback, and app-private persistence.
- [ ] **AC-67-03 — Complete typed projection:** contract tests enumerate every
  current Issue #10 planning rejection/result and Issue #13 application/recovery
  result used by diagnostics and verify the accepted phase/error/summary
  projection, including D-01–D-08 and `UNMAPPED` handling.
- [ ] **AC-67-04 — Application/restart attachment:** focused lifecycle tests prove
  required checkpoint/apply events carry the correct A0–A8 stage at the existing
  Issue #13 seams and startup reconciliation emits `RESTART_RECONCILED` with the
  accepted correlation fields, without changing public application types.
- [ ] **AC-67-05 — Privacy non-containment:** D-09 plus representative raw
  planner params, package/component/profile/layout/rule/revision values, DB
  content, and exception text are absent from journal bytes, exported bytes, and
  rendered logcat in debug and release configurations.
- [ ] **AC-67-06 — Retention:** lifecycle tests prove lazy whole-run FIFO pruning
  for 10-run, 7-day, and 512 KiB limits and prove unresolved
  `APPLYING`/`COMMITTED_UNVERIFIED`/`RESTORING` history is retained until
  resolution even when a cap would otherwise be exceeded.
- [ ] **AC-67-07 — Diagnostic fail-open/isolation:** injected write/corruption and
  logger failures do not change planner/application results, do not mutate
  layout/recovery state, and do not produce a less-redacted fallback path.
- [ ] **AC-67-08 — Explicit export only:** UI/integration evidence proves export
  can start only from an explicit Settings user action and that app startup,
  organizer completion, backgrounding, crash, or restart never auto-export.
- [ ] **AC-67-09 — Export parity:** D-10/export tests prove header +
  line-delimited JSON ordering and prove each exported event contains no field
  beyond the approved persisted event representation; cancel/failure leaves the
  journal intact.
- [ ] **AC-67-10 — Single redacted logcat sink:** debug/release tests prove one
  organizer diagnostics tag, DEBUG for ordinary debug phase transitions, WARN
  for accepted terminal failures, release failure-only behavior, and log output
  only after successful journal persistence.
- [ ] **AC-67-11 — Backup exclusion:** executable repository/instrumentation
  evidence proves the journal resource is absent from the Lawnchair backup
  allowlist and Android backup scheme and is not restored by those paths.
- [ ] **AC-67-12 — No transport or permission expansion:** source/build contract
  evidence proves Issue #67 adds no diagnostics permission, telemetry/network
  dependency, upload worker, transport API, or automatic recipient selection.
- [ ] **AC-67-13 — Accessible export action:** focused UI evidence proves the
  Settings action has localized accessible semantics and remains operable with
  TalkBack/keyboard or switch-style navigation and 200% font scaling.
- [ ] **AC-67-14 — Project verification:** organizer unit/contract tests,
  formatting, debug build, applicable UI/instrumentation evidence, repository
  contract validation, and exact commands/results are recorded in the
  implementation PR.

## Test oracle

| AC | Evidence |
|---|---|
| AC-67-01 | JVM compile/value-construction + serializer field-closure tests. |
| AC-67-02 | Journal store JVM/instrumented reopen and restart tests with wall-clock rollback fixture. |
| AC-67-03 | Projection table tests over complete current planner/application/recovery public variants; D-01–D-08 corpus. |
| AC-67-04 | Existing Issue #13 application/lifecycle seam tests plus restart-reconciler focused tests asserting phase/stage/correlation. |
| AC-67-05 | D-09 non-containment corpus executed against journal bytes, export bytes, and log renderer for debug/release. |
| AC-67-06 | Deterministic clock/size journal lifecycle tests covering each cap independently and unresolved precedence/resolution. |
| AC-67-07 | Failure injection for store open/append/corruption and logger failure while asserting unchanged planner/application result/state. |
| AC-67-08 | Settings UI/integration test plus source contract showing no background/automatic export entry point. |
| AC-67-09 | Export writer test for D-10 ordering/field parity and cancellation/write-failure integration test. |
| AC-67-10 | Log sink unit/contract tests for tag, levels, release filtering, persistence-before-log ordering, and redaction. |
| AC-67-11 | Backup allowlist/`backupscheme.xml` contract test; focused restore evidence if needed to prove exclusion. |
| AC-67-12 | Manifest/dependency/source contract checks limited to the Issue #67 diff and diagnostics module boundary. |
| AC-67-13 | Compose/Settings semantics test and font-scale/navigation evidence on the supported debug surface. |
| AC-67-14 | Standard repository CI/local verification recorded in the implementation PR. |

## Open questions

None are blocking spec definition. One stale ownership statement is resolved by
this spec: Issue #60 is already implemented and its accepted spec hands
run-journal/`applyStage`/`RESTART_RECONCILED` implementation back to #67.
Application/recovery lifecycle attachment therefore belongs to #67, while
#52/#53/#55 retain their run-orchestration/UI phase emission responsibilities.

The implementation plan may select concrete storage/file names, serializer
library choices already available in the repository, Settings placement, and
test class/file organization, provided those choices do not widen the behavior
or data contract above.

## Change history

- 2026-08-18: Draft created for #67 from the accepted Issue #16 organizer
  diagnostics contract; clarified post-#60 event-attachment ownership.
- 2026-08-18: Review follow-ups: scoped creation of the diagnostics-owned
  `ApplyStage`/`PlanSummary`/`ApplySummary` projection types, clarified the
  contract section references and the app-private journal location. Status
  moved to accepted.
