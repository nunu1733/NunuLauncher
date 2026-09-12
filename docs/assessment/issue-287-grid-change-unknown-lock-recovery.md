# Assessment: Issue #287 — Organizer capture unavailable after grid configuration change

Status: `implemented` (fix + regression evidence on emulator)
Date: 2026-09-13
Issue: https://github.com/nunu1733/NunuLauncher/issues/287
Spec: [specs/287-grid-change-unknown-lock-recovery/spec.md](../specs/287-grid-change-unknown-lock-recovery/spec.md) (accepted)
Plan: [specs/287-grid-change-unknown-lock-recovery/plan.md](../specs/287-grid-change-unknown-lock-recovery/plan.md) (rev 3)
Related: #172 (diagnostics), #38 (lock authoring/review), #83 (input sources), #59 (migration failure), #298 / #299 (separate Nova-restore session defects)

## Root cause

The device investigation recorded in the issue (T0/T5, Pixel 9a, main HEAD
`d0f40446c7`) bounded the failure; this change completes it with code-level
identification:

1. **By design (ADR-0004):** grid migration marks every `favorites` row
   `organizerLockState = UNKNOWN`
   (`GridSizeMigrationUtil#markOrganizerLocksUnknown`, `UNKNOWN_MARK` step of
   `migrateGridInTransaction`). The state is persisted, which is why neither
   the user nor a process replacement recovered the device.
2. **By design (spec #83 / #172):** `OrganizationInputComposer` fail-closes
   with `INPUT_NOT_READY / CAPTURE_UNKNOWN_LOCK` while any captured row is
   `UNKNOWN`. Fresh capture per composition; no generation cache.
3. **The defect (fixed here):** the designed recovery path — the placement
   lock review screen (spec #38) — could not complete.
   `LockAuthoringDecision.fitsProfile` rejected folder-child placements whose
   rank reached the captured single-page folder capacity
   (`folderMaxColumns * folderMaxRows`) as `PLACEMENT_OUT_OF_PROFILE`, which
   renders as 「このアイテムはロックできません。」
   (`organizer_lock_error_unsupported`). That product is a display pagination
   constant (`FolderPagedView` paginates folders: `pageNo = rank /
   maxItemsPerPage`), not a per-folder platform limit, and grid presets
   declare folder grids smaller than the workspace
   (`res/xml/device_profiles.xml`: `5_by_5` 4×4=16, `6_by_5` 3×3=9;
   Lawnchair's ceiling-matched preset also changes with custom slider grids,
   which is what the device hit). Persisted members at ranks beyond the new
   capacity were therefore displayed as reviewable but always rejected —
   individually and (by atomicity) in batch, so the UNKNOWN set could never
   be cleared and `CAPTURE_UNKNOWN_LOCK` was permanent.

The secondary observation from the device (batch confirm copy said "78件の
配置を…" while 0 rows changed) is the same listing-vs-write divergence: the
listing/explain surfaces filter by kind only, the write decision adds the
bounds check. With the bound removed, the displayed reviewable set and the
writable set agree again (JVM-pinned invariant; rows outside the writable
predicate — negative rank, unsupported container, unavailable profile —
remain fail-closed by design).

## Fix

`lawnchair/src/app/lawnchair/organizer/locks/LockAuthoring.kt`
(`LockAuthoringDecision.fitsProfile`): the folder-child bound is now only
`rank >= 0`. Workspace cell, Dock rank, kind/container actionability, profile
checks, batch atomicity, exact preconditions, and the composer's fail-closed
semantics are unchanged. No writer, migration, or composer file is touched.

## Emulator verification (AC-6)

Environment: `nunu_qpr2_api36_1` AVD (API 36 / Platform 36.1), debug build
`assembleLawnWithQuickstepGithubDebug` from this branch. The issue
reproduction runs as an executable same-process chain —
`GridChangeUnknownLockRecoveryInstrumentationTest#organizerRecoversThroughReviewAfterInProcessGridChange`
— through the production seams (real DB, real `LauncherModel` loader, real
`tryMigrateDB` → `GridSizeMigrationUtil.migrateGridInTransaction`, real
organizer run, real lock-authoring writer). The seeded layout is a folder
with 40 members (ranks 0..39) on a 4-column grid; the in-process grid change
switches preferences to 5 columns × 5 rows and drives a real migration
(`launcher_5_4_4.db` → `launcher_5_5_5.db`), matching the issue flow
"apply → revert → grid change → preview fails → review → preview succeeds".

`OrganizerDiag` logcat captured during the run (opaque run ids only, per the
#172 diagnostics contract):

```text
run=9161ea63… phase=RUN_STARTED
run=9161ea63… phase=CAPTURED
run=9161ea63… phase=PLANNED captured=41 moved=1 preserved=40
run=9161ea63… phase=PREVIEWED
run=9161ea63… phase=USER_CONFIRMED
run=9161ea63… phase=CHECKPOINTED stage=A4
run=9161ea63… phase=APPLY_COMMITTED stage=A6
run=9161ea63… phase=APPLY_VERIFIED stage=A8 preserveActions=40 updateActions=1 insertActions=0
phase=RECOVERY_REQUESTED
phase=RECOVERY_RESTORED
run=fd1b6608… phase=RUN_STARTED
run=fd1b6608… phase=INPUT_NOT_READY err=INPUT_READINESS.CAPTURE_UNKNOWN_LOCK   ← issue reproduced after the in-process grid change
run=8eaef317… phase=RUN_STARTED                                                ← same process, after review resolution
run=8eaef317… phase=CAPTURED
run=8eaef317… phase=PLANNED captured=41 moved=1 preserved=40
run=8eaef317… phase=PREVIEWED                                                  ← recovered onto the new profile
```

The test additionally asserts: the post-migration revision differs from the
pre-change capture (the generation changed), every captured row is `UNKNOWN`,
the composer returns exactly
`InvalidCanonicalCapture(CaptureFailureCategory.UNKNOWN_LOCK)`, members with
rank beyond the new grid's one-page capacity are present in the review
listing, the atomic batch commits exactly the listed set, and the recapture is
free of `UNKNOWN` rows.

## Executed evidence

| Command | Result |
|---|---|
| `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` | 1090 tests, 0 failures (organizer unit gate) |
| `./gradlew spotlessCheck` | success |
| `./gradlew assembleLawnWithQuickstepGithubDebug` | success |
| `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` | success |
| `connectedLawnWithQuickstepGithubDebugAndroidTest` (classes: `LockAuthoringInstrumentationTest`, `GridChangeUnknownLockRecoveryInstrumentationTest`, `OrganizerLockScreenTest`) on `nunu_qpr2_api36_1` | 9 tests, 0 failures (1 pre-existing annotation skip `capturesLockDialogTargetEvidence`); repeated consecutively twice with identical result |

New JVM tests pin the capacity-boundary acceptance (ranks capacity−1 /
capacity / beyond, single + batch + module protocol), the
listing-vs-write predicate invariant and its fail-closed counter-case, and
negative-rank rejection. New instrumentation tests pin the real-DB review
after the real `markOrganizerLocksUnknown`, the stale pre-migration plan
rejection (`STALE_REVISION`, no mutation — the revision is a content digest
of the canonical state, so the marking always invalidates it), and the
same-process chain above.

## Not covered / deviations

- The device investigation used a real Nova backup; the emulator chain seeds
  the equivalent layout (folder with members beyond the new grid's one-page
  folder capacity) directly, because the Nova conversion path itself is the
  separately tracked #298/#299 territory. The remaining trigger (grid change
  through the production migration) is exercised for real.
- The recovery-step UI taps (settings screens) were exercised through the
  compose UI suite `OrganizerLockScreenTest` (review dialog, batch review,
  busy/failure copy) rather than manual instrumented taps; the settings-side
  flow is unchanged by this fix.
- Multi-profile (work/private) behavior is not exercised on the emulator;
  unchanged by this fix (profile checks untouched).
- Mid-test migration targets a grid db without prior artifacts; switching
  back to a previously-used grid (where `ModelDbController.migrateGridIfNeeded`
  skips a new migration by the EMPTY_DATABASE_CREATED flag) is existing
  production behavior outside this issue's scope.
