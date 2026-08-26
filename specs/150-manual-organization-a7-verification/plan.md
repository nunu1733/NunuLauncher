# Implementation Plan: Manual organization A7 completion barrier and device verification

> Issue: [#150](https://github.com/nunu1733/NunuLauncher/issues/150)
> Spec: [spec.md](./spec.md)
> Status: proposed
> Risk: `layout-data`
> Evidence baseline: Issue #150 reproduction at commit `74c2156767`; repository baseline remains governed by `AGENTS.md`.

## Current evidence

The issue body, both comments, and the redacted evidence in [PR #151's device
evidence](https://github.com/nunu1733/NunuLauncher/blob/main/docs/assessment/evidence/issue-104-105-106-device-evidence.md)
are the current reproduction record. On `nunu_qpr2_api36_1` (Pixel 6,
Android 16/API 36, 1080x2400, 420 dpi, 4x5), debug reproduced 3/3 and release
1/1: A6 committed, A7 verification failed, and automatic recovery ended in
`APPLY_RECOVERY_FAILED`. `favorites` was byte-identical before and after the
full apply/recovery cycle, and a subsequent restart reconciled the pre-state
successfully. This is evidence of a transient in-process visibility or
completion-order problem, not permission to weaken verification.

Confirmed existing paths and seams:

- `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt`
  performs A6, requests `LayoutWriterPort.requestCorrelatedReload`, then
  recaptures and verifies in A7/A8. Automatic recovery follows the same
  `requestCorrelatedReload` seam.
- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
  is the production `LayoutWriterPort`; it delegates reload correlation to
  `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java`.
- `OrganizerModelReloadAdapter.requestAndWait()` waits on the existing
  request token with `TIMEOUT_MILLIS = 10_000`. The current completion signal
  is connected to the Launcher bind path, so it must be proven to mean the
  exact loader transaction is finished rather than merely initial workspace
  bind work.
- `src/com/android/launcher3/LauncherModel.java` starts the token-scoped
  `LoaderTask` and rejects stale completion tokens. `src/com/android/launcher3/model/LoaderTask.java`
  owns the loader transaction and its commit. `src/com/android/launcher3/model/BaseLauncherBinder.java`
  currently executes the organizer completion signal before its normal initial
  bind-complete callback in the relevant bind paths. These are the candidate
  minimal bridge locations; the exact relocation remains a test-backed
  implementation choice.
- `src/com/android/launcher3/model/LayoutWriteCoordinator.java` and the
  existing lease token serialize organizer and ordinary model writes. The
  correction must preserve this seam and must not add a second writer lock.
- `tests/unit/app/lawnchair/organizer/application/protocol/ApplyProtocolTest.kt`
  already covers reload failure and automatic recovery, but its post-apply
  verification case does not yet inject a real recapture mismatch or early
  completion. `FakeLayoutWriter.kt` needs only the smallest failure hook
  required by that test.
- `tests/organizer-instrumentation/com/android/launcher3/organizer/OrganizerReloadSupersessionTest.java`
  is the existing request-token/supersession surface. It must also assert the
  transaction-completion ordering; it is concurrently edited by another
  worker and must not be overwritten by this task.
- `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationProductionE2EInstrumentationTest.kt`
  is the existing seeded production flow. The default-workspace evidence may
  use the Issue #150-specific instrumentation fixture, but that fixture must
  wait for the causal model barrier and inspect only approved journal fields.
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt`
  produces typed `NotReady` diagnostics, while
  `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` currently
  projects only the reason to its UI state. The ZIP symptom therefore has a
  genuine observability gap; it is not an invitation to change the composer
  while repairing A7.
- Existing #67 diagnostics projection and `LayoutApplicationModule` recovery
  correlation already carry `pointOriginRunId` from the recovery record. The
  device test must prove this field survives the explicit recovery flow rather
  than adding a new journal shape.

The completion-order defect is a hypothesis until the new failing-path test
demonstrates it. A passing test that only waits longer, retries recapture, or
compares a restarted process is insufficient evidence.

## Design

### Modules and interfaces

The public application and diagnostics contracts remain unchanged:

```text
LayoutWriterPort.requestCorrelatedReload(lease) -> ReloadResult
ApplyProtocol.apply(ValidatedLayoutPlan) -> ApplyResult
LayoutApplicationModule.recover(...) -> RecoveryResult
```

The internal contract of `requestCorrelatedReload` is sharpened: `Completed`
means the exact request's loader transaction has committed and closed, and all
token-scoped loader work that can affect the adapter's next capture has ended.
It does not mean merely that a bind callback was posted or that a fixed delay
elapsed. Token replacement, cancellation, and timeout remain typed failures;
late completion from an old token is ignored.

Candidate minimal bridge work is confined to the existing
`LauncherModel`/`LoaderTask`/`BaseLauncherBinder` path:

1. Keep the request and lease token through the exact `LoaderTask` instance.
2. Move or defer the organizer completion signal until the loader transaction
   commit/close boundary is complete; do not wait for unrelated UI rendering.
3. Preserve identity checking in `completeOrganizerReload`, cancellation,
   supersession, and the non-blocking executor rule. The model executor must
   never synchronously wait on its own loader task.
4. Leave `OrganizerModelReloadAdapter`, `LauncherLayoutAdapter`,
   `LayoutWriteCoordinator`, and `ApplyProtocol` public shapes unchanged unless
   a focused test proves a private adapter correction is required.

The verification seam remains independent: after `Completed`, the adapter
recaptures the DB and the protocol compares the model/manifest and all
Issue #13 invariants. No verification is changed to accept a partial model or
to ignore a mismatch.

### Data flow

```text
A6 commit
  -> COMMITTED_UNVERIFIED
  -> request token-scoped LoaderTask
  -> LoaderTask transaction commit/close
  -> correlated Completed
  -> independent DB/model recapture
  -> A8 VERIFIED, or existing automatic recovery
```

Automatic recovery repeats the same sequence against the checkpoint pre-state.
On explicit recovery, the existing revision-bound application flow emits
`RECOVERY_REQUESTED`; after exact DB/model restore verification it emits
`RECOVERY_RESTORED`. Both events use the point ID and the record's origin run
ID as `pointOriginRunId`.

For ZIP restore, the investigation records the current `NotReady` boundary
and its eventual diagnostic code only in the separately owned follow-up. No
composition or diagnostics code is changed as a side effect of the A7 fix.

### Alternatives rejected

| Alternative | Reason rejected |
|---|---|
| Add `sleep`, `postDelayed`, or a longer timeout | Timing is not a causal completion signal and would violate the safety/quality contracts. |
| Retry DB recapture until it matches | Masks an incomplete reload, adds nondeterministic latency, and can falsely convert a race into success. |
| Ignore changed rows or weaken manifest equality | Would violate Issue #13 exact verification and could report false success. |
| Reload the process or use a backup as verification/undo | Not a request-correlated model barrier; ADR-0003 and `AGENTS.md` reject raw-copy/delay/restart shortcuts. |
| Add a second writer/reload adapter or public callback API | Duplicates an existing seam and expands the Launcher3 bridge without need. |
| Fix ZIP `NotReady` in this patch | The symptom can resolve in a later process and lacks an observable code; it needs an independently scoped diagnostic contract. |

## Change set

Only implementation work after this spec is approved should touch the paths
below. This planning task edits only `spec.md` and `plan.md`.

| Area | Intended change | Why here |
|---|---|---|
| `src/com/android/launcher3/LauncherModel.java`, `src/com/android/launcher3/model/LoaderTask.java`, `src/com/android/launcher3/model/BaseLauncherBinder.java` | Move the token-scoped completion signal to the committed/closed loader boundary, preserving supersession and non-blocking behavior. | Smallest existing Launcher3 bridge where the current early signal is produced and the transaction owner is known. |
| `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java` | Change only if the failing test shows token/result handling must preserve the sharpened completion contract; no public shape or timeout-as-success change. | Existing correlation adapter is the sole production wait seam. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt` and `.../RecoveryProtocol.kt` | Normally no behavior change; add only seam-level assertions/diagnostic plumbing if required to consume the existing `Completed` contract. | A7 and recovery must remain symmetrical and Issue #13-compatible. |
| `tests/unit/app/lawnchair/organizer/application/protocol/FakeLayoutWriter.kt` | Add a deterministic early-completion/recapture-mismatch fault hook if absent. | Enables a failure-path test without mocking internal production helpers. |
| `tests/unit/app/lawnchair/organizer/application/protocol/ApplyProtocolTest.kt` | Add A7 early-completion, mismatch, recovery-reload, and no-false-success assertions through `LayoutWriterPort`. | Existing public/internal application seam is the unit oracle. |
| `tests/organizer-instrumentation/com/android/launcher3/organizer/OrganizerReloadSupersessionTest.java` | Extend the existing token/supersession test with transaction-completion ordering. | Verifies the Launcher bridge and stale callback behavior on device. |
| `tests/organizer-instrumentation/app/lawnchair/organizer/ui/Issue150DefaultWorkspaceApplyInstrumentationTest.kt` (if retained by its owner) | Exercise default workspace apply/recovery, wait for the causal barrier, and assert approved journal correlation. | Issue-specific device evidence; do not replace the existing seeded E2E contract. |
| `docs/assessment/pr-<PR>-manual-organization-a7-verification.md` | Add independent high-risk audit record after implementation CI succeeds. | Required by `AGENTS.md`; authored by a separate session/agent. |

No `specs/13`, `specs/52`, `specs/67`, `specs/83`, `ADR-0003`, database
schema, backup allowlist, or ZIP composer path is changed by this plan.

## Migration and recovery

- **Database/recovery migration:** none. No Launcher schema, recovery format,
  journal schema, preferences, lock columns, or backup files change.
- **Runtime rollback:** reverting the bridge restores the prior reload behavior
  and requires no data conversion. A failed implementation must not be shipped
  merely because automatic recovery is still safe; A8 evidence is required.
- **Apply rollback:** existing Issue #13 A7/A8 behavior remains authoritative:
  any reload, recapture, or verification failure invokes the existing automatic
  recovery; `Applied` is never returned without exact post-state verification.
- **Recovery rollback:** explicit recovery remains a preconditioned,
  row-accounted transaction. If its barrier fails, return the existing typed
  `RestoreFailed`/unresolved outcome and preserve the recovery point for
  restart reconciliation. No table wipe or raw database copy is permitted.
- **Backup/restore:** unchanged. The ZIP `NotReady` follow-up is separate and
  must not alter ZIP compatibility under this issue.
- **Process death:** existing restart reconciliation remains the authority for
  `COMMITTED_UNVERIFIED`/`RESTORING`; the new barrier does not create a new
  persistent lifecycle state.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-150-01 | Reproducer test drives an early completion signal and observes transient recapture; test passes only when the completion contract is causal. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.ApplyProtocolTest'` |
| AC-150-02 | Supersession/stale callback and transaction-order instrumentation; no timing sleep or retry is accepted as evidence. | `./gradlew -PandroidSerialNumber=<serial> connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.OrganizerReloadSupersessionTest` |
| AC-150-03 | Apply/recovery failure-injection matrix, exact post/pre manifest assertions, and existing organizer unit surface. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-150-04 | Default workspace on `nunu_qpr2_api36_1`: debug and release run, exported redacted journal, before/after `favorites` invariant comparison. | API 36.1 Pixel 6 AVD; build with `./gradlew assembleLawnWithQuickstepGithubDebug` and `./gradlew assembleLawnWithQuickstepGithubRelease`; run the approved manual/device fixture and record exact head SHA. |
| AC-150-05 | Explicit recovery preview/confirm and event sequence with matching point ID and non-null `pointOriginRunId`. | Same API 36.1 AVD; supported Settings diagnostics export; no raw journal/row payload in evidence. |
| AC-150-06 | Follow-up Issue/spec records the precise `NotReady` diagnostic code; shared-root discovery is documented as a stop, not patched opportunistically. | Issue link and approved diagnostic observation surface before #150 completion. |
| AC-150-07 | Formatting, build, repository contract, CI merge gate, and independent audit on the exact implementation head. | `./gradlew spotlessCheck`; `./gradlew assembleLawnWithQuickstepGithubDebug`; `python3 tools/repo-contract/validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_high_risk_evidence.py`; PR `CI / final-status`. |

Instrumentation commands follow [building.md](../../docs/engineering/building.md)
and must be run on a clean, submodule-initialized checkout. Commands that are
not executed must not be reported as passing. The device evidence records
build type, serial/AVD, API/ABI, tested commit, journal phase sequence, and
redacted invariant result only.

## Documentation updates

- [ ] Change this spec and plan from `proposed` to `accepted` only after Issue
  #150 owner review and the plan's open implementation details are resolved.
- [ ] On implementation completion, update both statuses/history to
  `implemented` only after AC-150-01 through AC-150-07, device evidence, CI,
  and the independent audit are complete.
- [ ] `CONTEXT.md`: no change expected; no product term is added.
- [ ] `DESIGN.md`: no change expected unless the tested completion bridge
  changes a system-level interface/seam; stop and review if so.
- [ ] ADR: no new ADR expected; use ADR-0003 unchanged. Create/revise one only
  if implementation introduces a high-cost persistence or cross-module choice.
- [ ] `AGENTS.md`: no change expected; verified commands are already documented.
- [ ] Separate ZIP-restore follow-up spec/plan: required before #150 completion,
  with an observable diagnostic code and explicit owner.

## Execution checklist

- [ ] Reproduce Issue #150 on the stated default workspace and preserve current
  worktree changes.
- [ ] Add the failing early-completion/reload-order test at existing seams.
- [ ] Implement the smallest causal completion-barrier bridge.
- [ ] Run unit, supersession, instrumentation, and automatic-recovery tests.
- [ ] Run debug and release default-workspace device evidence and explicit
  recovery correlation evidence.
- [ ] Create and link the ZIP `NotReady` diagnostic follow-up; stop if a shared
  root or public/schema change is required.
- [ ] Run formatting/build/repository-contract checks and record exact results.
- [ ] Obtain PR `CI / final-status`, then a separate independent audit whose
  target SHA exactly matches the audited implementation head.

## Stop conditions

Stop implementation and update the owning Issue/spec before changing code if:

1. The failing test cannot distinguish loader transaction completion from an
   initial bind callback without a new public API or a broad Launcher3 bridge.
2. The proposed fix requires a DB/recovery/journal schema or migration,
   planner/application public contract, permission, network/transport,
   backup-format, or ZIP-composer change.
3. A test still passes only after a delay, retry loop, process restart, weakened
   manifest comparison, or unconditional table replacement.
4. Default-workspace failure is caused by an unrelated input/composition,
   LoaderCursor overlap, device-profile, or policy issue rather than the A7
   completion boundary. Split that cause into a separate issue; do not mask it
   in this fix.
5. ZIP-restore `NotReady` is found to share the A7 capture/verification root,
   or the diagnostic code cannot be made observable without a contract/schema
   change. Stop, open the separately owned diagnostic-code issue/spec, and do
   not proceed under #150 until its acceptance and ownership are explicit.
6. Automatic recovery or explicit recovery loses a point, changes a locked
   placement, leaves an unexplained item, or reports success without a stable
   post-reload verification. Preserve the evidence and return to the Issue #13
   recovery owner.

## High-risk merge gate

Because this Issue is labeled `risk: layout-data`, implementation cannot merge
on local tests alone. The final PR must include `Closes #150`, map its evidence
to the accepted AC-150 criteria and ADR-0003/spec-13 invariants, and pass the
PR-triggered `CI / final-status` on the exact head SHA. After that CI run, a
different session/agent must create
`docs/assessment/pr-<PR-number>-manual-organization-a7-verification.md` with
the exact 40-character head SHA, links to the successful CI run, referenced
spec/ADR criteria, concrete test commands/surfaces, scope, and findings. Any
source change after the audit requires a new CI result and independent audit.

## Change history

- 2026-08-26: Proposed implementation plan created for Issue #150. It records
  the existing A7/reload seams, the causal-barrier test-first sequence, device
  and recovery-correlation evidence, no-migration rollback, ZIP `NotReady`
  split/stop conditions, and the required independent layout-data audit.
