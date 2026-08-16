# Implementation plan: lock authoring and unknown-state review

> Issue: [#38](https://github.com/nunu1733/NunuLauncher/issues/38)
> Spec: [spec.md](./spec.md)
> Status: implemented (PR #73)
> Updated: 2026-08-16

## Current-code basis

- Schema-33 `favorites.organizerLockState` (tri-state, `INTEGER NOT NULL
  DEFAULT 1`) exists from Issue #14: column definition
  `src/com/android/launcher3/LauncherSettings.java:357,393`, non-wiping
  upgrade `DatabaseHelper.java:285`, grid-migration `UNKNOWN` marking
  `GridSizeMigrationUtil.java:180-205`.
- The canonical capture boundary is
  `app.lawnchair.organizer.application.adapter.RowManifestCodec`
  (`internal`): `capture(db, capabilities, orderedPages, profiles)` returns
  the public `LayoutState` plus the lossless `PersistenceManifest`
  (`PersistentRow` carries `rowId` + `organizerLockState`).
- Writer serialization is `src/com/android/launcher3/model/LayoutWriteCoordinator.java`
  (`OwnerKind.ORGANIZER`, token-bearing `Lease`); DB transactions are opened
  via `ModelDbController.newTransaction(token)` (see
  `LauncherLayoutAdapter.applyWriteSet` for the A5-style in-transaction
  reread discipline this plan reuses).
- Production capture composition (capabilities/profile/page inventory) exists
  in `LauncherLayoutAdapter.capture()`; this plan mirrors it without editing
  Issue #14 files.
- Popup seam: `LawnchairLauncher.getSupportedShortcuts()` appends
  `SystemShortcut.Factory` instances (`LawnchairShortcut.kt` is the pattern);
  `PopupContainerWithArrow.showForIcon` consults the stream only for
  shortcut-capable icons (application/deep-shortcut rows, workspace and
  non-taskbar hotseat).
- Preferences seam: `@Serializable` route objects in
  `ui/preferences/navigation/PreferenceRoutes.kt`, `composable<Route>`
  registration in `PreferenceNavigation.kt`, screens in
  `ui/preferences/destinations/` (`HiddenAppsPreferences.kt` is the list
  template), dashboard entry in `PreferencesDashboard.kt`.
- Strings: `lawnchair/res/values/strings.xml` (default locale; Crowdin owns
  translations).

## Modules changed

| Path | Change |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/locks/LockAuthoring.kt` | New. Public request/result types, decision `LockAuthoringDecision`, pure. |
| `.../locks/EffectiveLocks.kt` | New. Effective-lock computation + typed effect/explanation model, pure. |
| `.../locks/LockReview.kt` | New. `UNKNOWN` review listing + batch review plan, pure. |
| `.../locks/LockAuthoringModule.kt` | New. Orchestration over capture/writer ports; preview + setLock + review surfaces. |
| `.../locks/LockPorts.kt` | New. `LockCapturePort`, `LockStateWriterPort`, outcomes. |
| `.../locks/adapter/LockStateDbAdapter.kt` | New. Production adapter: capabilities/profile/page capture mirror, coordinator lease, single-column UPDATE in one `ModelDbController` transaction with in-transaction revision + exact-precondition reread. |
| `.../locks/OrganizerLocks.kt` | New. Process-wide composition holder (no `LawnchairApp` edit). |
| `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt` | New. `SystemShortcut.Factory` + state-aware confirmation `AlertDialog` for application/deep-shortcut rows. |
| `lawnchair/src/app/lawnchair/LauncherLauncher.kt` (`LawnchairLauncher.kt`) | Append the lock factory to `getSupportedShortcuts()`. |
| `.../ui/preferences/destinations/OrganizerLockPreferences.kt` | New. Management/review screen (list, state chips, confirm dialogs, batch review). |
| `.../ui/preferences/navigation/PreferenceRoutes.kt` / `PreferenceNavigation.kt` + `HomeScreenPreferences.kt` | Route + registration + entry row in the Home screen `layout` group (audit note: implemented there rather than the dashboard root). |
| `lawnchair/res/values/strings.xml` | New localized strings (default locale). |
| `tests/unit/app/lawnchair/organizer/locks/**` | New JVM tests. |
| `tests/organizer-instrumentation/app/lawnchair/organizer/locks/**` | New instrumentation tests (real DB + Compose UI). |
| `gradle/libs.versions.toml`, `build.gradle` | Test-only dependency: `androidx.compose.ui:ui-test-junit4` (+ `ui-test-manifest` debugImplementation) for the Compose semantics tests. No production dependency change. |
| `DESIGN.md` §9 | `locks/` added to the organizer package map. |

No file under `organizer/application/**`, `ModelWriter.java`,
`LauncherProvider.java`, `DatabaseHelper.java`, `GridSizeMigrationUtil.java`,
or `LawnchairApp.kt` is modified.

## Interface and seam decisions

- The locks module depends only on `application.public.*` types
  (`LayoutState`, `CanonicalItemState`, `OrganizerLockState`,
  `ApplicationItemRef`) plus `CapturedSnapshot`-equivalent data exposed through
  its own ports; tests and production share the same seam
  (`LockAuthoringModule`), mirroring the Issue #14 discipline.
- `LockWritePlan` carries, per targeted row, the DB `rowId` and the exact
  captured `CanonicalItemState` precondition, so the in-transaction reread can
  reject any concurrent change (same exactness as Issue #14 A5, scoped to one
  column write).
- Target states are closed: `LOCKED`/`UNLOCKED` only; `UNKNOWN` is never a
  write target. Requests carry `ReviewedIntent` (issued by the UI only from an
  explicit confirm action) — the decision layer rejects its absence for any
  transition (locking, unlocking, and review alike), which is stricter than
  the issue requires and keeps intent uniform.
- Effective-lock precedence follows ADR-0004: parent `LOCKED` overrides child
  `UNLOCKED`; child `LOCKED` binds independently of parent state; app-pair
  parent lock covers both members.
- UI surfaces call `previewChange` first (state + effects + explanation
  resource keys) and `setLock`/`reviewBatch` only from confirm-button
  callbacks.

## Migration and rollback

- No schema, preference, or data migration. Writes update one existing column;
  rollback of a released change is reverting the APK (rows keep their authored
  states, which remain valid schema-33 values).
- Failure semantics: transaction rollback leaves the layout byte-identical;
  rejected requests perform no DB I/O mutation.

## Tests

- JVM (`testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.locks.*'`,
  auto-included in the Issue #41 CI gate):
  - `LockAuthoringDecisionTest` — accept/reject matrix: missing item,
    unavailable/absent profile, unsupported kind/container, out-of-profile
    placement, intent required, closed target states, corrupt-value review.
  - `EffectiveLockEffectsTest` — folder parent/child precedence, app-pair
    parent/member, Dock, widget, plain row; explanation keys per effect.
  - `LockReviewListingTest` — deterministic UNKNOWN listing/ordering, batch
    plan concretization + atomicity preconditions.
  - `LockAuthoringModuleProtocolTest` — fake ports: busy lease, stale
    revision, precondition mismatch, commit success path asserts the write set
    touches only `organizerLockState` of targeted rowIds.
- Instrumentation (`tests/organizer-instrumentation/`, API 36.1 emulator):
  - `LockStateDbAdapterInstrumentationTest` — throwaway SQLite DBs: lock/
    unlock/review round-trips for folder parent+child, Dock, widget, app pair,
    both profile rows; stale revision rejection; precondition rejection;
    rollback on forced failure; lease contention returns busy.
  - `LockAuthoringInstrumentationTest` — production module against the real
    launcher DB with favorites snapshot/restore (Issue #47 pattern): E2E
    setLock + UNKNOWN review.
  - `OrganizerLockScreenTest` — Compose `createComposeRule` over the
    management/review screen and confirmation content: localized text
    presence, non-color-only state, semantics/content descriptions, error
    rendering.

## Verification commands

```bash
git submodule update --init --recursive
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest
# API 36.1 AVD (nunu_qpr2_api36_1):
./gradlew -PandroidSerialNumber=emulator-5554 connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.package=app.lawnchair.organizer.locks
```

The connected run is local evidence; CI runs the JVM gate per Issue #41. The
PR carries `risk: layout-data` and follows the Issue #43 independent-audit
gate (`docs/assessment/pr-<n>-<slug>.md` by a separate session).

## Rollout

Single PR closing #38. Docs updated in the same PR: spec status, this plan,
and (if behavior wording changes) `DESIGN.md` §7 pointer already covers lock
ownership; no ADR change (ADR-0004 already assigns this surface to #38).
