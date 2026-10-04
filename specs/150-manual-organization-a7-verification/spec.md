---
issue: "#150"
status: implemented
requirements:
  - A7-COMPLETION-BARRIER
  - A7-FAILURE-SEAM
  - DEFAULT-WORKSPACE-A8
  - RECOVERY-CORRELATION
  - ZIP-NOTREADY-FOLLOWUP
risk:
  - layout-data
updated: 2026-08-26
---

# Manual organization A7 completion barrier and device verification

The GitHub [Issue #150](https://github.com/nunu1733/NunuLauncher/issues/150)
and its comments are the authority for the reproduction history, device
environment, and current progress. This accepted specification defines only
the observable correction and evidence needed to close that issue; it does not
copy a second product contract into the repository.

## Problem

On the default launcher workspace, a confirmed manual full-organization run
reaches `APPLY_COMMITTED` (A6), then fails the in-process A7 reload/recapture
verification with `APPLY_FAILURE.VERIFICATION_FAILED`. Automatic recovery
uses the same reload path and fails its verification as well, ending in
`APPLY_RECOVERY_FAILED`. The persisted `favorites` rows and recovery point are
safe and the layout returns to the pre-apply state, but a run cannot reach
`APPLY_VERIFIED` and the user cannot reach explicit recovery.

Issue evidence supports, but does not yet prove, a completion-boundary defect:
the initial reload is classified `Completed`, its following DB recapture
mismatches, the same manifest comparisons pass after a process restart, and
the terminal failure occurs near the existing 10-second correlated-reload
wait. Depending on the workspace-inflation path and thread scheduling, the
current callback may be observed before the loader transaction and its
remaining work have completed. AC-150-01 requires a deterministic barrier test
before this hypothesis is accepted as the root cause; timing alone is not
proof.

The ZIP-restore-related `composeFullOrganization()` `NotReady` symptom is a
separate observability/product problem tracked by
[Issue #153](https://github.com/nunu1733/NunuLauncher/issues/153). Its later
resolution in a new process does not establish a shared root with A7, and the
failing composition sub-check has no observable diagnostic code.

Accepted spec 13 also requires comparison of the specific reload generation's
model snapshot with an independent DB recapture. Production currently performs
only the DB-derived recapture/intended-state comparison. That pre-existing
implementation gap is tracked by
[Issue #152](https://github.com/nunu1733/NunuLauncher/issues/152); this spec does
not claim that #150 proves DB/model convergence.

## Outcome

At the existing `LayoutWriterPort`/`OrganizerModelReloadAdapter` seam, a
request-correlated reload is reported complete only after the exact loader
transaction has committed and closed and no token-scoped loader work remains.
The A7/A8 protocol then recaptures a stable model/DB view, identifies the
actual failure path in a test, and either verifies the intended state or invokes
the existing safe automatic recovery. On the API 36.1 default workspace, debug
and release evidence show `APPLY_VERIFIED`; an explicit recovery preview and
confirmation show `RECOVERY_REQUESTED` and `RECOVERY_RESTORED` with the
checkpoint's `pointOriginRunId`.

## Scope

- Identify and correct the request-scoped A7 completion barrier at the existing
  Launcher model/loader bridge. The barrier must be causal; fixed delays and
  retry loops are not completion signals.
- Add failing-path tests through the existing `LayoutWriterPort`,
  `ApplyProtocol`, correlated-reload, and reload-supersession seams. A
  latch/barrier must deterministically hold loader transaction commit/close
  incomplete and inspect the existing completion runnable synchronously at
  that boundary. The pre-fix ordering must have fired completion before the
  barrier, while the corrected ordering must not fire it until transaction
  commit/close. The tests must also cover automatic recovery using the same
  completion contract.
- Add default-workspace device evidence on the Issue #150 environment, with
  at least one debug and one release run reaching A8/`APPLY_VERIFIED`.
- Verify an explicit recovery preview/confirmation after a verified apply and
  the journal correlation from `RECOVERY_REQUESTED` to `RECOVERY_RESTORED`,
  including the originating run ID as `pointOriginRunId`.
- Triage the ZIP-restore `NotReady` symptom only far enough to preserve a
  reproducible boundary and open a follow-up issue with an observable
  diagnostic code. It is not an implementation target of this spec.

## Non-goals

- Changes to the planner, #83 production input composition policy, or the
  public planner/application/recovery result contracts.
- A diagnostics schema, new journal fields, permission, transport, telemetry,
  or user data export change. Existing #67 projections and redaction rules are
  reused.
- Launcher or recovery database schema/rule migration, recovery-point format,
  backup/restore format, or retention changes. ADR-0003 remains authoritative.
- A new writer/reload adapter, a second capture/verification path, or a change
  that bypasses the existing layout-writer serialization.
- Unconditional `favorites` deletion/reinsertion, a raw database copy, process
  restart as verification, fixed sleeps, or masking a mismatch by weakening
  verification.
- Fixing ZIP-restore `NotReady` in this work. Its diagnostic-code issue must be
  specified and implemented by Issue #153.
- Adding the missing specific-generation model snapshot comparison required by
  spec 13. Issue #152 owns that gap; #150 must not describe its DB-only oracle
  as proof of DB/model convergence.

## Domain language

No new product/domain term is introduced. **A7 completion barrier** is an
internal shorthand for the causal boundary between the correlated reload and
the existing independent DB recapture. It does not imply that the missing
specific-generation model snapshot comparison in Issue #152 exists.
**Default workspace evidence** means an observable run on the device
configuration in Issue #150, not a synthetic fixture.

## Behavior scenarios

### Scenario: completed loader produces a verified apply

Given a fresh default-workspace capture and a confirmed, non-empty plan
When A6 commits and A7 requests a correlated model reload
Then the reload outcome is not `Completed` until the request's loader
transaction has committed and closed
And A7 recaptures after that boundary and A8 verifies the intended state
And the recovery point becomes `VERIFIED` and the journal emits
`APPLY_VERIFIED`, rather than a false success before the barrier.

### Scenario: the existing early-completion failure path is reproduced

Given a test seam that signals reload completion before the loader transaction
is complete
When `ApplyProtocol` performs its A7 recapture
Then the test fails before the fix (or records the typed verification/recovery
failure) and proves that the signal was early
And after the fix the protocol cannot consume that early signal as completion.

### Scenario: reload is superseded, cancelled, or times out

Given a request-correlated reload is replaced or never reaches its exact
completion boundary
When A7 or automatic recovery waits for the outcome
Then the stale callback cannot complete the newer request
And the operation returns the existing typed reload/verification failure,
attempts the existing safe recovery path, and never reports `Applied` or
`Restored` without exact verification.

### Scenario: automatic recovery uses the same completion contract

Given A6 committed but post-apply verification fails
When automatic recovery writes the checkpoint pre-state and requests reload
Then recovery uses the same request-scoped completion barrier
And `APPLY_RECOVERED` is returned only after the existing pre-state DB-derived
verification, otherwise the truthful unresolved/recovery-failed result is
returned
And this result is not cited as evidence for Issue #152's missing model
snapshot convergence.

### Scenario: default workspace reaches A8 on device

Given the API 36.1 `nunu_qpr2_api36_1` Pixel 6 AVD, 4x5 grid, and the default
workspace from Issue #150
When a user starts, reviews, and confirms manual full organization
Then the run reaches `APPLY_VERIFIED`/A8 in debug and release evidence
And no item, lock, profile, folder, widget, or recovery safety invariant is
weakened.

### Scenario: explicit recovery retains origin correlation

Given a verified apply has a recovery point with its creating run ID
When the user opens the revision-bound recovery preview and confirms it
Then the journal emits `RECOVERY_REQUESTED` and, after exact restore
verification, `RECOVERY_RESTORED`
And both events carry the point identity and `pointOriginRunId`
And a stale, missing, or failed point produces the existing typed result with
no blind write.

### Scenario: ZIP restore NotReady is stopped and split

Given ZIP restore leaves manual composition at `NotReady` for one or more
processes and the failing sub-check is not observable
When the A7 investigation reaches the composition boundary
Then this work does not add a speculative fix, alter the diagnostics schema, or
change planner/application behavior
And a separate issue/spec is opened to expose the existing readiness diagnostic
code (for example, the exact capture/gate code) on an approved observation
surface
And if the investigation suggests a shared root, implementation of that path
stops until the follow-up is accepted and ownership is explicit.

## Data and state

- The Launcher `favorites` database remains the current-layout authority; the
  recovery database remains the separate private store selected by
  [ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md).
- No database, recovery format, journal schema, or backup allowlist changes.
  No migration is required. A source rollback restores the prior code behavior
  without a data conversion step.
- A7 preserves the existing revision, recovery point, row accounting,
  lock/profile, bounds, reference, transaction, and DB-derived post-reload
  checks. A reload callback is not a revision or commit substitute. The
  specific-generation model snapshot comparison required by
  [spec 13](../13-safe-layout-application/spec.md) is not present in production
  and remains explicitly tracked by Issue #152.
- The completion token is request-scoped and in-memory. Stale/superseded
  tokens are ignored; no new persistent identity or payload is introduced.
- Explicit recovery is still a separate, revision-bound application operation.
  Its journal correlation reads the existing recovery record's origin run ID;
  it does not duplicate the recovery manifest or row data.

## Permissions, privacy, and security

None. This work adds no permission, network, telemetry, or export destination.
Device evidence and journal assertions use existing opaque run/point IDs,
closed phases/codes, and counts only. Raw rows, package names, coordinates,
profiles, revisions, exceptions, and recovery payloads remain excluded by the
diagnostics contract.

## Accessibility and localization

No new UI or localized string is required. The existing success and recovery
surfaces must become reachable only after their typed verified result; existing
focus, warning, failure, and recovery accessibility behavior remains intact.
The follow-up ZIP-restore diagnostic code is developer/support evidence, not a
raw user-facing exception.

## Acceptance criteria

- [ ] **AC-150-01 — Root cause and seam regression:** The early/incomplete A7
  completion condition is confirmed or falsified with a deterministic
  latch/barrier test at the existing reload/application seam. Under the pre-fix
  ordering, the existing organizer completion runnable has synchronously fired
  when the barrier is reached while loader transaction commit/close is still
  blocked. No assertion about how quickly a waiting thread is scheduled may be
  used as the root-cause oracle.
- [ ] **AC-150-02 — Causal completion barrier:** A request is completed only
  after the exact loader transaction has committed/closed; supersession,
  cancellation, timeout, and stale callbacks cannot produce false success.
- [ ] **AC-150-03 — Safe protocol behavior:** A7/A8 and automatic recovery use
  the same barrier, preserve the existing DB/recovery safety behavior, and
  return typed failures when the barrier cannot be proven. This criterion does
  not claim the missing spec-13 model snapshot convergence owned by Issue #152.
- [ ] **AC-150-04 — Default-workspace device evidence:** On the Issue #150 API
  36.1 environment, a debug and a release manual run reaches
  `APPLY_VERIFIED`/A8 with redacted evidence and no layout/recovery corruption.
- [ ] **AC-150-05 — Explicit recovery correlation:** Device evidence includes
  recovery preview/confirmation and journal `RECOVERY_REQUESTED` plus
  `RECOVERY_RESTORED`, both with the same point identity and its
  `pointOriginRunId`.
- [ ] **AC-150-06 — ZIP-restore split:** A linked follow-up issue/spec owns the
  ZIP-restore `NotReady` diagnostic-code observability gap (Issue #153). If its root is
  found to be shared with A7, implementation stops until that follow-up is
  accepted; #150 does not absorb an unscoped composition or diagnostics fix.
- [ ] **AC-150-07 — Scope and high-risk evidence:** No public contract,
  diagnostics schema, permission, transport, database migration, or backup
  behavior changes; Issue #152 explicitly owns the unimplemented model snapshot
  convergence contract; the implementation PR passes the `risk: layout-data`
  independent audit gate.

## Test oracle

| AC | Evidence |
|---|---|
| AC-150-01 | A launcher instrumentation test invokes the existing organizer reload completion seam directly and inspects its completion flag when a latch/barrier holds loader commit/close incomplete. Pre-fix has fired at the barrier; corrected code has not. No timed non-return assertion is the root-cause oracle. `ApplyProtocolTest` separately injects the resulting early/incomplete outcome through `FakeLayoutWriter`. |
| AC-150-02 | `OrganizerReloadSupersessionTest` covers token identity and stale callbacks; after the deterministic completion-order assertion, adapter completion is checked after barrier release without treating scheduler timing as ordering evidence. |
| AC-150-03 | Unit failure matrix covers A7 recapture mismatch, reload failure, recovery reload failure, and typed unresolved results; existing layout/recovery invariant tests remain green. |
| AC-150-04 | API 36.1 `nunu_qpr2_api36_1` default-workspace debug and release runs with redacted journal and before/after invariant evidence. |
| AC-150-05 | Device export plus journal projection assertions show `RECOVERY_REQUESTED` → `RECOVERY_RESTORED` and non-null matching `pointOriginRunId`. |
| AC-150-06 | Issue #153 and its future accepted spec own a reproducible NotReady observation that records the exact readiness diagnostic code on an approved surface. |
| AC-150-07 | `spotlessCheck`, organizer unit tests, debug build, repository-contract checks, PR `final-status`, and independent audit record. |

## Open questions

- The exact minimal Launcher callback relocation (loader transaction owner or
  binder completion bridge) is an implementation choice, but it must satisfy
  AC-150-01/02 without changing a public API. The plan names the candidate
  existing seams and a stop condition.
- Issue #152 owns the specific-generation model snapshot gap. If the causal
  completion fix cannot be verified without absorbing that gap, #150 stops
  until ownership/spec status is resolved rather than silently expanding.
- Device evidence must use the available API 36.1 environment and record the
  exact tested commit; no unverified local command is an acceptance claim.

## Change history

- 2026-08-26: Proposed for Issue #150 from the issue body, both comments, the
  PR #151 device evidence, accepted specs 13/52/67/83, and ADR-0003.
- 2026-08-26: Accepted by the issue owner after the re-review P1 was resolved:
  the root-cause oracle inspects the existing completion runnable's flag
  synchronously at a latch/barrier-held loader transaction boundary with no
  timed non-return or scheduling assertion (commit `38d33a622a`).
- 2026-08-28: Implemented and verified via PR #160 (merged at `8740f8c136`).
  Causal completion barrier, canonical capture ordering, and post-close
  supersession terminalization; all AC-150 criteria met with an accepted
  independent audit (`docs/assessment/pr-160-manual-organization-a7-verification.md`)
  and redacted device evidence (`docs/assessment/evidence/issue-150-device-verification.md`).
