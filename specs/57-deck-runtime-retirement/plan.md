# Implementation Plan: Retire Deck while preserving the current home layout

> Issue: [#57](https://github.com/nunu1733/NunuLauncher/issues/57)
> Spec: [accepted spec](./spec.md)
> ADR: [accepted ADR-0006](../../docs/adr/0006-retire-deck-runtime.md)
> Status: draft
> Local baseline: `6bfad79bd96b5b6271a5cf857ea46c03b6d556ef`
> Upstream baseline: Lawnchair `505dbc40e6154c05158b5d0271c45f6a885a411b`

## Authority and start condition

[Issue #57](https://github.com/nunu1733/NunuLauncher/issues/57) exclusively owns
dependencies, current state, and the implementation start condition. This plan
neither restates nor amends that authority. The plan follows the accepted contract
and adds no replacement organizer package-event hook.

## Pre-edit rollback baseline gate

The sole source of truth for `PRE_RETIREMENT_SHA` is one dated Issue #57
implementation-start comment created before the first #57 production edit. The
comment records the 40-character SHA plus Deck-existence and branch-base evidence.
The plan and PR/audit link that immutable comment and never copy its literal SHA.
After the authoritative Issue start condition is satisfied, fetch `origin/main`,
require the branch HEAD to equal it, prove Deck exists, post the comment, and retain
its URL. If the base differs or Deck is absent, stop. Do not use `HEAD^` after
implementation begins.

## Current evidence

The completed [Issue #56 assessment](../../docs/assessment/issue-56-deck-runtime-retirement.md)
is the evidence inventory. ADR-0006 owns durable layout-authority, tombstone,
historical-artifact, package-hook, and downgrade decisions. This plan owns the
exact source/artifact disposition, migration ordering, rollback evidence, and
verification for the accepted spec.

## Design

### Modules and interfaces

There is no new public seam. Production startup and Android-context tests invoke
the same internal `DeckRetirementMigration` entry point. `PreferenceManager2`
removes the live public `deckLayout` and `showDeckLayout` properties and reader
surface, retains private/internal ownership of the persisted tombstone keys, and
exposes one internal operation that atomically normalizes both to `false` for the
migration owner.

### Data flow

Startup enters `DeckRetirementMigration`, which calls internal normalization and
cleanup phases sequentially in production. `DeckRetirementPhaseObserver` is an
internal seam with a production default no-op. Normalization performs one atomic
DataStore edit for both tombstones without writing `swipeUpGesture` or
`addIconToHome`. A failed edit stops before cleanup. On success, cleanup derives
only exact `bk_` and `lawndeck_` database and journal candidates from
`LauncherFiles.GRID_DB_FILES`. It never restores, replaces, or changes the active
Launcher database. A failed delete leaves the exact inert artifact for the next
startup retry.

### Test-only pause control

The migration has no production fault or user control. For a pause scenario, the
androidTest `DeckRetirementTestRunner` overrides `callApplicationOnCreate`. Only
explicit `new_pause` mode requires a fresh nonce and installs the instrumentation
observer before `super.callApplicationOnCreate`; default, `old_compat`, and
`new_typed` modes install no pause observer. The real `LawnchairApp.onCreate` then
executes the production migration and, in `new_pause` mode, reaches the observer
after atomic normalization and before cleanup. The observer/nonce/control
files/`FileObserver`/`CountDownLatch`/markers
live only under `tests/organizer-instrumentation`; the androidTest manifest selects
this runner. The runner itself uses reflection and resolves new migration classes
only in the explicit new-target pause mode; old-target compatibility mode does not
load or verify new-only migration classes before `super.callApplicationOnCreate`.
The host passes the nonce with instrumentation argument
`-e deck_retirement_nonce <n>`. It persists and logs `PAUSED phase=AFTER_NORMALIZATION_BEFORE_CLEANUP
nonce=<n> typed=true`, blocks without sleep, and the host waits bounded before
capture. The host either force-stops for rollback or writes the exact release file
through `run-as`; `FileObserver` counts down and emits `ACK_RECEIVED nonce=<n>
typed=true`. Missing or wrong ACK, stale marker/nonce, or timeout fails. A release
scan proves marker/control implementation is absent from production/release sources;
only the internal no-op observer seam remains.

The instrumentation-only control root is exactly
`cache/logs/deck-retirement-control/`. The host generates exactly 16 random bytes
as 32 lowercase hexadecimal characters and rejects any value that does not match
`^[0-9a-f]{32}$`. The runner independently applies the same full-match validation
before constructing a path or installing an observer. For validated nonce
`<nonce>`, the observer creates `<nonce>.paused`, watches for `<nonce>.release`,
and writes `<nonce>.ack` after counting down. Before startup the runner requires all
three nonce files to be absent. The host waits for the typed PAUSED output and
`<nonce>.paused`, then releases only with
`adb exec-in run-as app.lawnchair.debug sh -c 'mkdir -p cache/logs/deck-retirement-control && cat > cache/logs/deck-retirement-control/'"$NONCE"'.release' < /dev/null`.
It requires the typed ACK output and `<nonce>.ack` using the same validated value
before allowing cleanup evidence.

The host and runner validate independently before any interpolation:

```bash
NONCE="$(LC_ALL=C od -An -N16 -tx1 /dev/urandom | tr -d '[:space:]')"
[[ "$NONCE" =~ ^[0-9a-f]{32}$ ]] || { echo 'invalid deck retirement nonce'; exit 1; }
```

The runner applies `Regex("^[0-9a-f]{32}$").matches(nonce)` before creating any
marker, release, ACK, or archive path. Both host scripts use only that validated
value for all nonce-derived names.

### Custom runner mode contract

Every instrumentation invocation uses
`app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner`.

| Mode | Classes and behavior | Required process evidence |
|---|---|---|
| `default` | Normal non-pause migration, UI, delete, and package classes. | Exact `MODE_READY mode=default typed=true` and `INSTRUMENTATION_CODE: -1`; no failure marker. |
| `old_compat` | Only reflection-only `DeckRetirementOldTargetCompatInstrumentationTest`. | Exact `OLD_COMPAT_READY typed=true`, `OK (1 test)`, and `INSTRUMENTATION_CODE: -1`. |
| `new_typed` | Typed new-target backup/restore and capture class. | Exact `NEW_TYPED_READY typed=true` and `INSTRUMENTATION_CODE: -1`; no failure marker. |
| `new_pause` | Production-startup observer/nonce handshake used only by downgrade host scenarios. | Release probe: typed `PAUSED`, matching `ACK_RECEIVED`, and `INSTRUMENTATION_CODE: -1`. Rollback probe: typed `PAUSED` followed by host-confirmed force-stop instead of a success code. |

Both host scripts invoke their internal `am instrument` commands through this exact
component, pass `deck_retirement_target_mode`, and fail on a missing mode-ready
marker, missing exact success result code, transport failure, or failure/crash
marker. The intentional rollback force-stop is the only mode that does not require
a completed instrumentation success code.

`tools/deck-retirement-backup-restore-smoke.sh` invokes the exact typed new class
after upgrade with `-e deck_retirement_target_mode new_typed -e class
app.lawnchair.migration.DeckRetirementBackupRestoreInstrumentationTest
app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner` and
requires exact `NEW_TYPED_READY typed=true` plus `INSTRUMENTATION_CODE: -1`. The downgrade host invokes the
exact pause fixture with `-e deck_retirement_target_mode new_pause -e
deck_retirement_nonce <n> -e class
app.lawnchair.migration.DeckRetirementDowngradeFixtureInstrumentationTest
app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner`, then
requires the nonce-specific PAUSED and ACK markers plus exact
`INSTRUMENTATION_CODE: -1` for the release probe; the intentional rollback
force-stop follows the exception defined in the mode table.

### Alternatives rejected

ADR-0006 already rejects restoration of historical copies, broad prefix deletion,
reconstruction of unknown preference values, and a replacement package hook.

## Canonical disposition inventory

| Source or artifact | Disposition |
|---|---|
| `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | Remove the live runtime, enable/disable flow, raw copy/restore, and package-placement entry points. |
| `lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt` | Remove the Deck-only folder-writing task. |
| `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | Remove live `deckLayout` and `showDeckLayout` properties; retain `enable_lawn_deck` and `show_deck_layout` as private/internal persisted-false tombstones; add the one internal atomic normalization operation. |
| `lawnchair/src/app/lawnchair/migration/DeckRetirementMigration.kt` | Add the sole startup normalization and post-success exact-cleanup owner. |
| `lawnchair/src/app/lawnchair/LawnchairApp.kt` | Invoke the migration at application startup. |
| `DESIGN.md` | Update the permanent architecture authority for the internal startup migration, no-op observer, retry, target layout, and verification contract. |
| `lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt` | Remove Deck control, manager construction, loading UI, and Deck preference side effects; retain unrelated home-layout UI. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Remove Deck visibility/read gates; retain ordinary home-screen settings. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt` | Remove Deck preference exposure; retain unrelated experimental settings. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/PreferencesDashboard.kt` | Remove the Deck gate hiding App Drawer; retain baseline dashboard navigation. |
| `lawnchair/src/app/lawnchair/ui/preferences/components/GestureHandlerPreference.kt` | Remove Deck-specific gesture filtering; retain ordinary gesture choices. |
| `src/com/android/launcher3/dragndrop/DragController.java` | Remove the Deck-specific delete-target cancellation branch and preference dependency; retain baseline drag flow. |
| `src/com/android/launcher3/DeleteDropTarget.java` | Remove both Deck-specific delete/accessibility branches and preference dependency; retain baseline persisted-item deletion. |
| `src/com/android/launcher3/model/PackageUpdatedTask.java` | Remove the Deck import, preference read, and `OP_ADD` placement tail; retain ordinary package/widget/binding behavior. Do not add a replacement hook. |
| `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | Remove `OwnerKind.DECK_FILE_RESTORE` after source scan proves no consumer; retain unrelated owner kinds. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` | Remove `WriterKind.DECK_FILE_RESTORE` with its coordinator peer; retain enum-name bridge symmetry and unrelated kinds. |
| `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | Retain the supported active-DB and preference-store backup/restore format without in-process mutation. |
| `lawnchair/res/values*/strings.xml` | Remove `show_deck_layout`, `show_deck_layout_description`, `home_lawn_deck_label`, `home_lawn_deck_label_beta`, and `home_lawn_deck_description` from all 58 localized files; retain unrelated resources. |
| `bk_<grid-db>`, `lawndeck_<grid-db>`, and exact `-journal` companions | Clean up only after atomic normalization succeeds; do not restore, prefix-match, or touch unknown files. |
| `tests/unit/app/lawnchair/organizer/planning/harness/ExampleCorpus.kt` and `SyntheticFixtureGeneratorTest.kt` | Retain `deck-output-compatibility` as planner-ingestion evidence only. |
| `tools/deck-retirement-downgrade-smoke.sh` | Add as the host orchestrator for real-APK AC-008 transitions, evidence capture, and archive creation. |
| `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementDowngradeFixtureInstrumentationTest.kt` | Add only for typed synthetic-state preparation and capture; it does not perform APK transitions. |
| `tools/deck-retirement-backup-restore-smoke.sh` | Add as the host orchestrator for AC-006 real archive creation, APK transition, actual restore, restart, and evidence archive. |
| `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementTestRunner.kt` | Add the androidTest runner that installs the nonce observer before `Application.onCreate`. |
| `tests/organizer-instrumentation/AndroidManifest.xml` | Register `DeckRetirementTestRunner` as the androidTest runner; do not add production controls. |
| `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementOldTargetCompatInstrumentationTest.kt` | Add reflection-only old-target preflight, synthetic seeding/capture, and old `LawnchairBackup.create` invocation without references to new migration APIs. |
| `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementBackupRestoreInstrumentationTest.kt` | Add typed new-target package/version verification and actual `readInfoAndPreview`/`restore` invocation. |
| `cache/logs/deck-retirement-backup/<nonce>.lawnchairbackup` | Retain as the exact archive subpath under the existing `cache-path path="logs"` provider coverage; use target `LawnchairApp.getUriForFile` with no production provider expansion. |

## Migration, rollback, and downgrade

1. Normalize both tombstones in one DataStore transaction. If it fails, perform no
   cleanup and retry on the next process start.
2. After success, derive the exact recognized historical names from
   `LauncherFiles.GRID_DB_FILES` and attempt their cleanup. A delete failure is
   inert and retryable at restart; it does not change the active database.
3. Do not mutate `LawnchairBackup` in process. An old Lawnchair backup restore
   makes its restored database current, then the next startup normalizes tombstones.

| State | Required evidence and boundary |
|---|---|
| Rollback before cleanup | Use the dynamically recorded `PRE_RETIREMENT_SHA` rollback APK; capture layout DB digest plus preference and historical-file state before and after rollback. Record best effort only. |
| Cleanup-complete downgrade | Capture layout DB digest plus preference and historical-file state before cleanup, after cleanup, and after downgrade. Do not promise restored Deck snapshots or runtime. |
| Old-binary downgrade before new-version initialization | Capture the same before/after evidence and record this boundary as unsupported. |
| Old-backup restore before new-version initialization | Capture real old archive/restore and the same before/after evidence; record this boundary as unsupported. |

Cross-grid downgrade is excluded pending #59.

## Test-first verification

Write each RED test before production changes, then make it GREEN.

| ID | AC | Test surface and proposed path | Method intent |
|---|---|---|---|
| DRR-RED-001 / GREEN-001 | AC-001, AC-002 | Instrumentation: `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementMigrationInstrumentationTest.kt` | `enabledDisabledAndInconsistentStatesPreserveActiveDbAndNormalizeAtomically` drives the internal entry point with Android DataStore and startup context. |
| DRR-RED-002 / GREEN-002 | AC-003 | Same instrumentation class | `normalizationAndDeleteFailuresLeaveActiveDbUntouchedAndRetryOnRestart` injects failures and relaunches. |
| DRR-RED-003 / GREEN-003 | AC-004 | JVM: `tests/unit/app/lawnchair/migration/DeckRetirementArtifactNamesTest.kt` | `derivesOnlyExactGridDbArtifactNames` covers active/inactive grid names, journals, and unknown files. |
| DRR-RED-004 / GREEN-004 | AC-006 | Host: `tools/deck-retirement-backup-restore-smoke.sh`; old/new entrypoints: `DeckRetirementOldTargetCompatInstrumentationTest.kt` and `DeckRetirementBackupRestoreInstrumentationTest.kt` | The host invokes the exact old compatibility class before upgrade and exact typed new class after upgrade; real production archive/restore and restart prove the restored DB becomes current. |
| DRR-RED-005 / GREEN-005 | AC-005 | Instrumentation: `tests/organizer-instrumentation/com/android/launcher3/DeckRetirementDeleteRegressionTest.java` | `persistedItemDragAndAccessibilityDeleteUseBaselineBehavior`. |
| DRR-RED-006 / GREEN-006 | AC-005, AC-007 | Instrumentation: `tests/organizer-instrumentation/com/android/launcher3/DeckRetirementPackageRegressionTest.java` | `packageAddKeepsWidgetAndBindingBehaviorWithoutDeckPlacementOrReplacementHook`. |
| DRR-RED-007 / GREEN-007 | AC-005 | Instrumentation: `tests/organizer-instrumentation/app/lawnchair/ui/preferences/DeckRetirementPreferencesInstrumentationTest.kt` | `appDrawerAndGesturesRemainVisibleWhileDeckControlsAreAbsent`. |
| DRR-RED-008 / GREEN-008 | AC-005, AC-007 | Repository scan: `tools/repo-contract/test_validate_deck_retirement.py` | `testRuntimeInventoryIsRetired` asserts no Deck readers, five keys absent from all values files, `DECK_FILE_RESTORE` absent from both enums, fixture retention, and an AST/import/type-reference scan proving old compatibility code has no static new migration observer/runner/typed-fixture reference. Reflection string lookup is allowed; the runner `old_compat` branch must resolve no new target class before `Application.onCreate`. |
| DRR-RED-009 / GREEN-009 | AC-007 | JVM: `tests/unit/app/lawnchair/organizer/planning/harness/SyntheticFixtureGeneratorTest.kt` | `deckOutputCompatibilityFixtureIsRegistered`. |
| DRR-RED-010 / GREEN-010 | AC-008 | Host: `tools/deck-retirement-downgrade-smoke.sh --scenario rollback-before-cleanup` | Uses the test-only normalization pause handshake, real APK transitions, actual old-binary HOME launch, and before/after capture. |
| DRR-RED-011 / GREEN-011 | AC-008 | Host: `tools/deck-retirement-downgrade-smoke.sh --scenario downgrade-after-cleanup` | Completes cleanup, performs a real old-APK downgrade, launches HOME under the old binary, and captures before/after state. |
| DRR-EV-012 | AC-008 | Host: `tools/deck-retirement-downgrade-smoke.sh --scenario pre-initialization-old-binary` | Records the unsupported old-binary boundary with real APK ordering and no false pass. |
| DRR-EV-013 | AC-008 | Host: `tools/deck-retirement-downgrade-smoke.sh --scenario pre-initialization-old-backup` | Records the unsupported old-backup boundary with real archive, restore, restart, APK ordering, and no false pass. |
| DRR-RED-012 / GREEN-012 | AC-008 | androidTest runner/fixture: `DeckRetirementTestRunner.kt` and `DeckRetirementDowngradeFixtureInstrumentationTest.kt` | The runner installs the observer before real `Application.onCreate`; the fixture prepares/captures typed state and validates the nonce pause handshake only. |

## Real-APK downgrade smoke orchestration

`tools/deck-retirement-downgrade-smoke.sh` is the AC-008 host orchestrator. Each
invocation runs exactly one scenario and accepts required `--scenario`, `--serial`,
`--pre-retirement-apk`, `--retirement-apk`, `--test-apk`, `--evidence-dir`, and
recorded `--pre-retirement-record-url`. It rejects missing, duplicate, or unknown
arguments.

For every phase, the script preserves app data: it upgrades with `adb install -r`
and downgrades with `adb install -r -d`; it never calls `pm clear` or uninstalls
within a scenario. Each scenario uses a separate fresh AVD boot created with
`emulator -avd <name> -wipe-data -no-snapshot ...`; no snapshots, data, archives,
or raw artifacts are reused across scenarios. Reset occurs only between scenarios.
Before every capture the script force-stops the package, verifies installed package
version code, version name, and APK path, launches HOME after every transition, and
archives evidence in a separate scenario subdirectory.

1. `rollback-before-cleanup`: install the pre-retirement APK and invoke only
   `DeckRetirementOldTargetCompatInstrumentationTest` in `old_compat` mode to
   prepare synthetic Deck state. Install the retirement APK with `-r`; only then
   use the `new_pause` runner/typed fixture, wait for the unique
   post-normalization marker, and capture. Downgrade with `-r -d`, launch HOME
   under the actual old binary, and use only `old_compat` capture afterward.
2. `downgrade-after-cleanup`: install the pre-retirement APK and use only the
   reflection-based `old_compat` entrypoint to prepare state. Install the
   retirement APK with `-r`, use new-target typed capture after cleanup completes,
   downgrade with `-r -d`, launch HOME under the actual old binary, and use only
   `old_compat` capture afterward.
3. `pre-initialization-old-binary`: install the old APK and use only `old_compat`
   seed/capture. Install retirement with `-r` but never launch, instrument, or
   initialize it; immediately downgrade old APK with `-r -d`, launch old HOME, and
   capture. Classify unsupported and fail if any evidence marks it supported.
4. `pre-initialization-old-backup`: under old APK/`old_compat`, create a real
   Lawnchair archive, record entry list and SHA, mutate state, reinject the archive,
   and call actual old-target `LawnchairBackup.readInfoAndPreview`/`restore`
   reflectively. Force-stop and relaunch old HOME, then capture restored state.
   Install retirement with `-r` but never launch, instrument, or initialize it;
   immediately downgrade old APK with `-r -d`, launch old HOME, and capture.
   Classify unsupported and fail if any evidence marks it supported.

Instrumentation is limited to reflection-only old-target preparation/capture and
typed new-target pause/capture. New migration classes never load in either
pre-initialization subcase. The script, not instrumentation, performs each APK
transition and old-binary launch.

## Real old-backup restore smoke orchestration

`tools/deck-retirement-backup-restore-smoke.sh` accepts `--serial`,
`--pre-retirement-apk`, `--retirement-apk`, `--test-apk`, `--evidence-dir`, and
`--pre-retirement-record-url`. It installs the real pre-retirement NunuLauncher APK
and invokes exact old-target
`DeckRetirementOldTargetCompatInstrumentationTest` before upgrade. That
reflection-only entrypoint references no new migration APIs/classes, preflights the
required old production class/method signatures and installed target package
version/path against the expected old APK before mutation, then only seeds/captures and calls old
`LawnchairBackup.create` with `INCLUDE_LAYOUT_AND_SETTINGS`.

The single androidTest APK has two binary-compatible entrypoints. The host invokes
the old class with `-e deck_retirement_target_mode old_compat`,
`-e expected_target_version_code <old-code>`, and
`-e expected_target_apk_path <old-path>`; the reflection-only runner/class path
must not load new migration classes. After upgrade it invokes the typed new class
with `deck_retirement_target_mode=new_typed` and matching new target version/path
arguments. The custom runner also branches on this mode before
`callApplicationOnCreate`: old compatibility mode installs no migration observer,
while new pause mode installs it reflectively before the production Application.

Before any old-target seed, archive, or restore mutation, the host installs the
supplied old APK and the single test APK, derives expected package, versionCode, and
versionName from the old APK with Android Build Tools `aapt dump badging`, obtains
the device sourceDir through `pm path` and device version metadata through `dumpsys
package`, and compares the host old-APK SHA with a digest streamed from that device
sourceDir. It does not claim the host path equals the device path. It invokes exact
`DeckRetirementOldTargetCompatInstrumentationTest` through the custom runner with
the `old_compat` mode and expected version/path arguments, then requires
`OLD_COMPAT_READY typed=true` and `OK (1 test)`. Any package, version, path, SHA, or
reflection preflight failure stops before mutation.

The host script exclusively owns this old-target invocation. The outer verification
command never invokes the old compatibility class separately. For each requested
old-target action, one instrumentation method performs all nonmutating preflight
checks first, emits `OLD_COMPAT_READY typed=true` immediately before its single
allowed seed/create/restore mutation, then emits a typed completion marker. A
preflight failure throws before any target state is changed.

Inside the host script, the one owning invocation has this exact shape after it
derives and verifies package/version/path and host/device APK SHA metadata:

```bash
run_instrument "$OLD_COMPAT_LOG" 'OLD_COMPAT_READY typed=true' \
  -e deck_retirement_target_mode old_compat \
  -e expected_target_version_code "$EXPECTED_VERSION_CODE" \
  -e expected_target_version_name "$EXPECTED_VERSION_NAME" \
  -e expected_target_apk_path "$DEVICE_APK_PATH" \
  -e class "app.lawnchair.migration.DeckRetirementOldTargetCompatInstrumentationTest#$OLD_COMPAT_ACTION" \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
rg -F -x 'OK (1 test)' "$OLD_COMPAT_LOG"
```

`OLD_COMPAT_ACTION` selects exactly one mutation method after its internal
nonmutating preflight; the script never calls the same action twice.

The old target produces its URI with `LawnchairApp.getUriForFile` at exact subpath
`cache/logs/deck-retirement-backup/<nonce>.lawnchairbackup`; the existing
`provider_paths` `cache-path path="logs"` covers it. No production provider
expansion occurs. Same-target access is used; if instrumentation context crosses
package identity, it grants explicit read/write URI permissions only for the test
duration and revokes them afterward. The script pulls exactly with
`adb exec-out run-as app.lawnchair.debug cat cache/logs/deck-retirement-backup/<nonce>.lawnchairbackup > "$HOST_ARCHIVE"`.
It verifies host/device SHA before use, installs retirement with `adb install -r`,
creates a distinct current layout, and reinjects exactly with
`adb exec-in run-as app.lawnchair.debug sh -c 'mkdir -p cache/logs/deck-retirement-backup && cat > cache/logs/deck-retirement-backup/<nonce>.lawnchairbackup' < "$HOST_ARCHIVE"`.

After upgrade the host invokes exact typed new-target
`DeckRetirementBackupRestoreInstrumentationTest`, which verifies new target
package/version and calls actual `LawnchairBackup.readInfoAndPreview` and `restore`.
If either old/new API or version preflight fails, the host stops before mutation.
It then force-stops and launches HOME to cross restart. Before every capture it
force-stops, verifies package version code, version name, and APK path, launches
HOME, then captures state.

The AC-006 oracle records archive entry list plus version and grid metadata, the
pre-archive canonical layout digest, distinct pre-restore digest, and post-restore
canonical digest. It requires the post-restore digest to equal the archive digest,
the restored DB to be current, both tombstones to be false together after restart,
swipe/add-icon to equal archived restored values, and no `bk` or `lawndeck` source
to be used. It records only synthetic digests, counts, and metadata, never rows.
Synthetic identities and screenshots are used, while real production archive and
restore code and format are exercised.

## Dynamic rollback APK build and evidence oracle

Build the pre-retirement APK from the dynamically resolved NunuLauncher
`PRE_RETIREMENT_SHA`, not from the upstream Lawnchair ancestry baseline. Use an
isolated worktree with recursive submodules, build the debug APK, read it from
`build/outputs/apk/lawnWithQuickstepGithub/debug/`, and record its SHA-256 with the
authoritative Issue comment URL rather than copying the resolved SHA into another
record.

Each phase archives package version code, version name, installed APK path, the
pre-retirement Issue comment URL and pre-retirement/retirement APK SHA-256 values, launch success, a
canonical ordered `favorites` layout digest, DB file digest, schema, row count,
typed `enable_lawn_deck`, `show_deck_layout`, swipe-up, and add-icon values, and
exact raw-artifact presence. Capture before and after every phase. `run-as` and
host-side SQLite are permitted only for synthetic test-device data. Evidence records
digests, counts, and metadata only, never private rows.

The oracle classifies rollback-before-cleanup as `best effort`, cleanup-complete
downgrade as `active layout intact, no Deck restoration promise`, and the
pre-initialization boundary as `unsupported boundary recorded, no false pass`.
Cross-grid downgrade is excluded pending #59.

## Commands and high-risk evidence

Run the pre-edit gate before the first #57 production edit. The authenticated Issue
comment URL is the sole retained baseline reference:

```bash
set -euo pipefail
git fetch origin main
for ISSUE in 56 58 59 60; do
  ISSUE_STATE="$(gh issue view "$ISSUE" --repo nunu1733/NunuLauncher --json state --jq .state)"
  test "$ISSUE_STATE" = CLOSED || { echo "Issue #$ISSUE is not CLOSED: $ISSUE_STATE"; exit 1; }
done
for AUTHORITY_PATH in docs/adr/0006-retire-deck-runtime.md specs/57-deck-runtime-retirement/spec.md; do
  AUTHORITY_BODY="$(git show "origin/main:$AUTHORITY_PATH")" || { echo "$AUTHORITY_PATH is absent from origin/main"; exit 1; }
  printf '%s\n' "$AUTHORITY_BODY" | rg -q '^status: accepted$' || { echo "$AUTHORITY_PATH is not accepted on origin/main"; exit 1; }
done
PRE_RETIREMENT_SHA="$(git rev-parse origin/main)"
test "${#PRE_RETIREMENT_SHA}" -eq 40
test "$(git rev-parse HEAD)" = "$PRE_RETIREMENT_SHA" || { echo 'branch base differs from origin/main'; exit 1; }
git show "$PRE_RETIREMENT_SHA:lawnchair/src/app/lawnchair/deck/LawndeckManager.kt" | rg 'class LawndeckManager' || { echo 'Deck runtime absent at rollback base'; exit 1; }
COMMENT_BODY="$(printf 'Issue: #57\nKind: implementation-start-baseline\nRepository: nunu1733/NunuLauncher\nBranch: main\nCommit: %s\nDeck: LawndeckManager.kt exists' "$PRE_RETIREMENT_SHA")"
PRE_RETIREMENT_RECORD_URL="$(gh api -X POST repos/nunu1733/NunuLauncher/issues/57/comments -f body="$COMMENT_BODY" --jq .html_url)"
test -n "$PRE_RETIREMENT_RECORD_URL"
```

After production edits, obtain the immutable SHA only from that Issue comment,
then build and verify. This harness is Bash 3.2-compatible: it uses neither GNU
`timeout` nor `wait -n`.

```bash
set -euo pipefail
PRE_RETIREMENT_RECORD_URL="${PRE_RETIREMENT_RECORD_URL:?set authenticated Issue #57 comment URL}"
EVIDENCE_DIR="${EVIDENCE_DIR:?set EVIDENCE_DIR}"
AVD_NAME="${AVD_NAME:?set AVD_NAME}"
EMULATOR_PORT="${EMULATOR_PORT:?set numeric even EMULATOR_PORT}"
ANDROID_HOME="${ANDROID_HOME:?set ANDROID_HOME}"
mkdir -p "$EVIDENCE_DIR"
[[ "$EMULATOR_PORT" =~ ^[0-9]+$ ]] && test $((EMULATOR_PORT % 2)) -eq 0 || { echo 'EMULATOR_PORT must be numeric and even'; exit 1; }
[[ "$PRE_RETIREMENT_RECORD_URL" =~ ^https://github\.com/nunu1733/NunuLauncher/issues/57#issuecomment-[0-9]+$ ]] || { echo 'invalid Issue #57 comment URL'; exit 1; }
PRE_RETIREMENT_COMMENT_ID="${PRE_RETIREMENT_RECORD_URL##*#issuecomment-}"
[[ "$PRE_RETIREMENT_COMMENT_ID" =~ ^[0-9]+$ ]] || { echo 'non-numeric comment ID'; exit 1; }
COMMENT_JSON_FILE="$EVIDENCE_DIR/pre-retirement-comment.json"
gh api "repos/nunu1733/NunuLauncher/issues/comments/$PRE_RETIREMENT_COMMENT_ID" > "$COMMENT_JSON_FILE"
PRE_RETIREMENT_SHA="$(python3 - "$COMMENT_JSON_FILE" "$PRE_RETIREMENT_RECORD_URL" <<'PY'
import json
import re
import sys

path, expected_url = sys.argv[1:]
with open(path, "rb") as stream:
    comment = json.load(stream)
if comment.get("html_url") != expected_url:
    raise SystemExit("comment html_url mismatch")
if comment.get("user", {}).get("login") != "nunu1733":
    raise SystemExit("comment author mismatch")
if comment.get("author_association") != "OWNER":
    raise SystemExit("comment author association mismatch")
body = comment.get("body")
if not isinstance(body, str):
    raise SystemExit("comment body missing")
match = re.search(r"^Commit: ([0-9a-f]{40})$", body, re.MULTILINE)
if match is None:
    raise SystemExit("comment Commit line missing")
sha = match.group(1)
expected_body = (
    "Issue: #57\n"
    "Kind: implementation-start-baseline\n"
    "Repository: nunu1733/NunuLauncher\n"
    "Branch: main\n"
    f"Commit: {sha}\n"
    "Deck: LawndeckManager.kt exists"
)
if body.encode("utf-8") != expected_body.encode("utf-8"):
    raise SystemExit("comment body byte mismatch")
print(sha)
PY
)"
git merge-base --is-ancestor "$PRE_RETIREMENT_SHA" HEAD || { echo 'recorded baseline is not an ancestor'; exit 1; }
git show "$PRE_RETIREMENT_SHA:lawnchair/src/app/lawnchair/deck/LawndeckManager.kt" | rg 'class LawndeckManager' || { echo 'Deck runtime absent at recorded baseline'; exit 1; }
SERIAL="emulator-$EMULATOR_PORT"
ROLLBACK_TEMP_ROOT=""
ROLLBACK_WORKTREE=""
ACTIVE_CHILD_PID=""
EMULATOR_PID=""
WORKTREE_CREATED=0
EMULATOR_CREATED=0

device_reachable() { adb -s "$SERIAL" get-state 2>/dev/null | tr -d '\r' | rg -qx device; }
force_stop_if_reachable() { device_reachable || return 0; adb -s "$SERIAL" shell am force-stop app.lawnchair.debug || true; adb -s "$SERIAL" shell am force-stop app.lawnchair.debug.test || true; }
run_bounded() {
  local LOG="$1" TIMEOUT_SECONDS="$2" STATUS NOW DEADLINE
  shift 2
  "$@" >"$LOG" 2>&1 & ACTIVE_CHILD_PID=$!
  DEADLINE=$(( $(date +%s) + TIMEOUT_SECONDS ))
  while kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; do
    NOW="$(date +%s)"
    if test "$NOW" -ge "$DEADLINE"; then
      kill -TERM "$ACTIVE_CHILD_PID" 2>/dev/null || true
      sleep 1
      kill -KILL "$ACTIVE_CHILD_PID" 2>/dev/null || true
      wait "$ACTIVE_CHILD_PID" 2>/dev/null || true
      printf 'HOST_TIMEOUT typed=true seconds=%s\n' "$TIMEOUT_SECONDS" >>"$LOG"
      force_stop_if_reachable
      ACTIVE_CHILD_PID=""
      return 124
    fi
    sleep 1
  done
  if wait "$ACTIVE_CHILD_PID"; then STATUS=0; else STATUS=$?; fi
  ACTIVE_CHILD_PID=""
  return "$STATUS"
}
run_instrument() {
  local LOG="$1" MARKER="$2" NORMALIZED STATUS
  shift 2
  if run_bounded "$LOG" 180 adb -s "$SERIAL" shell am instrument -r -w "$@"; then STATUS=0; else STATUS=$?; fi
  NORMALIZED="${LOG%.log}.normalized.log"
  tr -d '\r' <"$LOG" >"$NORMALIZED"
  test "$STATUS" -eq 0 || return "$STATUS"
  rg -F -x "$MARKER" "$NORMALIZED"
  rg -F -x 'INSTRUMENTATION_CODE: -1' "$NORMALIZED"
  if rg -q 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|Fatal signal|HOST_TIMEOUT' "$NORMALIZED"; then return 1; fi
}
cleanup() {
  local ORIGINAL_STATUS="$?" CLEANUP_STATUS=0
  trap - EXIT INT TERM
  if test -n "$ACTIVE_CHILD_PID" && kill -0 "$ACTIVE_CHILD_PID" 2>/dev/null; then kill -TERM "$ACTIVE_CHILD_PID" 2>/dev/null || true; sleep 1; kill -KILL "$ACTIVE_CHILD_PID" 2>/dev/null || true; wait "$ACTIVE_CHILD_PID" 2>/dev/null || true; fi
  force_stop_if_reachable
  if test "$EMULATOR_CREATED" -eq 1 && test -n "$EMULATOR_PID"; then adb -s "$SERIAL" emu kill 2>/dev/null || true; kill -TERM "$EMULATOR_PID" 2>/dev/null || true; sleep 1; kill -KILL "$EMULATOR_PID" 2>/dev/null || true; wait "$EMULATOR_PID" 2>/dev/null || true; EMULATOR_PID=""; EMULATOR_CREATED=0; fi
  if test "$WORKTREE_CREATED" -eq 1; then git worktree remove "$ROLLBACK_WORKTREE" || CLEANUP_STATUS=1; WORKTREE_CREATED=0; fi
  if test -n "$ROLLBACK_TEMP_ROOT"; then rmdir "$ROLLBACK_TEMP_ROOT" 2>/dev/null || CLEANUP_STATUS=1; fi
  if test "$ORIGINAL_STATUS" -ne 0; then exit "$ORIGINAL_STATUS"; fi
  test "$CLEANUP_STATUS" -eq 0 || exit 125
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
ROLLBACK_TEMP_ROOT="$(mktemp -d "${TMPDIR:-/tmp}/deck-retirement.XXXXXX")"
ROLLBACK_WORKTREE="$ROLLBACK_TEMP_ROOT/checkout"
git worktree add --detach "$ROLLBACK_WORKTREE" "$PRE_RETIREMENT_SHA"
WORKTREE_CREATED=1
git -C "$ROLLBACK_WORKTREE" submodule update --init --recursive
git -C "$ROLLBACK_WORKTREE" status --short
"$ROLLBACK_WORKTREE/gradlew" -p "$ROLLBACK_WORKTREE" --no-daemon --no-parallel assembleLawnWithQuickstepGithubDebug
resolve_one_apk() {
  local OUTPUT_DIR="$1" EXPECTED_PACKAGE="$2" LABEL="$3" CANDIDATE BADGING ACTUAL_PACKAGE
  local MATCHES=()
  test -x "$ANDROID_HOME/build-tools/36.1.0/aapt" || { echo 'missing executable aapt 36.1.0'; return 1; }
  for CANDIDATE in "$OUTPUT_DIR"/*.apk; do test -f "$CANDIDATE" && MATCHES+=("$CANDIDATE"); done
  test "${#MATCHES[@]}" -eq 1 || { echo "$LABEL requires exactly one APK in $OUTPUT_DIR"; return 1; }
  BADGING="$("$ANDROID_HOME/build-tools/36.1.0/aapt" dump badging "${MATCHES[0]}")"
  ACTUAL_PACKAGE="$(printf '%s\n' "$BADGING" | sed -n "s/^package: name='\([^']*\)'.*/\1/p")"
  test "$ACTUAL_PACKAGE" = "$EXPECTED_PACKAGE" || { echo "$LABEL package mismatch: $ACTUAL_PACKAGE"; return 1; }
  printf '%s\n' "${MATCHES[0]}"
}
PRE_RETIREMENT_APK="$(resolve_one_apk "$ROLLBACK_WORKTREE/build/outputs/apk/lawnWithQuickstepGithub/debug" app.lawnchair.debug PRE_RETIREMENT_APK)"
python3 tools/repo-contract/test_validate_deck_retirement.py
./gradlew --no-daemon --no-parallel testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.migration.DeckRetirementArtifactNamesTest' --tests 'app.lawnchair.organizer.planning.harness.SyntheticFixtureGeneratorTest'
./gradlew --no-daemon --no-parallel testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew --no-daemon --no-parallel spotlessCheck
./gradlew --no-daemon --no-parallel assembleLawnWithQuickstepGithubDebug assembleLawnWithQuickstepGithubDebugAndroidTest
RETIREMENT_APK="$(resolve_one_apk build/outputs/apk/lawnWithQuickstepGithub/debug app.lawnchair.debug RETIREMENT_APK)"
TEST_APK="$(resolve_one_apk build/outputs/apk/androidTest/lawnWithQuickstepGithub/debug app.lawnchair.debug.test TEST_APK)"
shasum -a 256 "$PRE_RETIREMENT_APK" "$RETIREMENT_APK" "$TEST_APK" >"$EVIDENCE_DIR/apk-sha256.txt"
wait_for_boot() { local DEADLINE=$(( $(date +%s) + 120 )); while test "$(date +%s)" -lt "$DEADLINE"; do device_reachable && test "$(adb -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = 1 && return 0; sleep 1; done; return 124; }
mkdir -p "$EVIDENCE_DIR/instrumentation"
emulator -avd "$AVD_NAME" -port "$EMULATOR_PORT" -wipe-data -no-snapshot -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >"$EVIDENCE_DIR/instrumentation/emulator.log" 2>&1 & EMULATOR_PID=$!; EMULATOR_CREATED=1
wait_for_boot
./gradlew --no-daemon --no-parallel installLawnWithQuickstepGithubDebug installLawnWithQuickstepGithubDebugAndroidTest
run_instrument "$EVIDENCE_DIR/instrumentation/default.log" 'MODE_READY mode=default typed=true' -e deck_retirement_target_mode default -e class 'app.lawnchair.migration.DeckRetirementMigrationInstrumentationTest,app.lawnchair.ui.preferences.DeckRetirementPreferencesInstrumentationTest,com.android.launcher3.DeckRetirementDeleteRegressionTest,com.android.launcher3.DeckRetirementPackageRegressionTest' app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
adb -s "$SERIAL" shell am force-stop app.lawnchair.debug
adb -s "$SERIAL" shell am force-stop app.lawnchair.debug.test
adb -s "$SERIAL" emu kill
wait "$EMULATOR_PID"; EMULATOR_PID=""; EMULATOR_CREATED=0
mkdir -p "$EVIDENCE_DIR/backup-restore"
emulator -avd "$AVD_NAME" -port "$EMULATOR_PORT" -wipe-data -no-snapshot -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >"$EVIDENCE_DIR/backup-restore/emulator.log" 2>&1 & EMULATOR_PID=$!; EMULATOR_CREATED=1
wait_for_boot
run_bounded "$EVIDENCE_DIR/backup-restore/host.log" 900 tools/deck-retirement-backup-restore-smoke.sh --serial "$SERIAL" --pre-retirement-apk "$PRE_RETIREMENT_APK" --retirement-apk "$RETIREMENT_APK" --test-apk "$TEST_APK" --evidence-dir "$EVIDENCE_DIR/backup-restore" --pre-retirement-record-url "$PRE_RETIREMENT_RECORD_URL"
adb -s "$SERIAL" shell am force-stop app.lawnchair.debug
adb -s "$SERIAL" shell am force-stop app.lawnchair.debug.test
adb -s "$SERIAL" emu kill
wait "$EMULATOR_PID"; EMULATOR_PID=""; EMULATOR_CREATED=0
for SCENARIO in rollback-before-cleanup downgrade-after-cleanup pre-initialization-old-binary pre-initialization-old-backup; do mkdir -p "$EVIDENCE_DIR/$SCENARIO"; emulator -avd "$AVD_NAME" -port "$EMULATOR_PORT" -wipe-data -no-snapshot -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect >"$EVIDENCE_DIR/$SCENARIO/emulator.log" 2>&1 & EMULATOR_PID=$!; EMULATOR_CREATED=1; wait_for_boot; run_bounded "$EVIDENCE_DIR/$SCENARIO/host.log" 900 tools/deck-retirement-downgrade-smoke.sh --scenario "$SCENARIO" --serial "$SERIAL" --pre-retirement-apk "$PRE_RETIREMENT_APK" --retirement-apk "$RETIREMENT_APK" --test-apk "$TEST_APK" --evidence-dir "$EVIDENCE_DIR/$SCENARIO" --pre-retirement-record-url "$PRE_RETIREMENT_RECORD_URL"; force_stop_if_reachable; adb -s "$SERIAL" emu kill; wait "$EMULATOR_PID"; EMULATOR_PID=""; EMULATOR_CREATED=0; done
git worktree remove "$ROLLBACK_WORKTREE"; WORKTREE_CREATED=0
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
git diff --check
```

Both future smoke scripts implement the same Bash 3.2-compatible bounded-child
handling for their internal instrumentation/control children: wall-clock deadline,
TERM then KILL, typed `HOST_TIMEOUT`, reachable-device force-stop, preserved child
status, and idempotent EXIT/INT/TERM traps for their control files. They preserve
their evidence directories and cannot leave a child hanging beyond the outer
`run_bounded` 900-second limit.

The outer EXIT/INT/TERM trap preserves the original failure status; it kills a live
owned child, force-stops only when the selected device is reachable, terminates and
waits only the emulator PID it started, removes only the exact owned rollback
worktree with non-force `git worktree remove`, then removes an empty owned temp
root. It returns 125 only when cleanup is the sole failure. Per-phase teardown
clears child/emulator/worktree ownership flags after normal cleanup. It never
removes `EVIDENCE_DIR`; evidence remains owned by the invoking operator for
independent review.

The implementation PR requires Issue #43 independent evidence: successful
`final-status` for its head SHA and an independent assessment record covering the
accepted spec, ADR-0006, the executed test surfaces, and CI link.

## Documentation updates

- [x] Update `DESIGN.md` with the permanent internal startup migration seam, retry,
  no-op observer, target layout, and startup/old-backup/process-restart verification
  contract. The seam adds no public organizer API or system invariant.
- [ ] Do not update `CONTEXT.md`: no domain term changes.
- [ ] Keep ADR-0006 and the accepted spec as the decision and behavior authorities.
- [ ] Update `building.md` only after the scripts succeed from a clean checkout and
  become verified commands.
- [ ] Update `AGENTS.md` only if workflow or mandatory commands change.
- [ ] Keep the high-risk PR audit separate from implementation work.

## Execution checklist

- [ ] Create and retain the authoritative Issue #57 baseline-comment URL before any production edit and prove Deck exists at its recorded SHA.
- [ ] Make every DRR test RED, then GREEN.
- [ ] Create, pull, checksum, restore, and verify the real old-target archive.
- [ ] Run all four downgrade scenarios on separately wiped AVD boots.
- [ ] Verify normalization, cleanup, retry, restore, rollback, and downgrade evidence.
- [ ] Verify bounded child timeout, authenticated baseline comment, exact APK resolution, and idempotent trap evidence.
- [ ] Run repository validation, formatter, builds, tests, and `git diff --check`.
- [ ] Record PR and independent high-risk evidence.
- [ ] Tear down worktrees, emulators, package processes, and retain owned evidence archives.
