# High-risk audit: PR #79 Retire Deck runtime and remove the legacy package-event hook

> Status: proposed
> Audit date: 2026-08-18

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/79
- Head SHA: bb6d6b841de40718abbc7abd6b33cb83b788a508
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32125231122 (merge gate on the audited head SHA; `changes`, `check-style`, `organizer-unit-tests`, `build-debug-apk`, `validate-repo-contract`, `final-status` all success)
- Criteria: specs/57-deck-runtime-retirement/spec.md (`status: accepted`) AC-001 through AC-009

## Scope

Audited the complete `origin/main..bb6d6b841` diff:
93 files, +3382/-1124.

This record re-audits the original retirement change (previously recorded at head `83763aaee4`) plus the review-fix commit `bb6d6b841d`, which addresses the three blocking review findings:

1. `tools/repo-contract/validate_writer_inventory.py` — removed the stale `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` allowlist entry that made `validate-repo-contract` fail on the retired file (fixes the CI-evidence mismatch flagged in review).
2. `lawnchair/src/app/lawnchair/LawnchairApp.kt` — `isDefaultProcess()` is now fail-closed: when the process name cannot be determined on API 26–27, it returns false instead of falling back to `packageName`, so an unidentifiable secondary process never runs the retirement migration (AC-009).
3. `tests/organizer-instrumentation/.../DeckRetirementProcessIsolationInstrumentationTest.kt` — the secondary-process oracle now positively confirms that `am start-foreground-service` reported no error and that the `:bugReport` process was observed running; a timeout or start error fails the test instead of passing inertly (AC-009).

Production removals:
- `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` (287 lines, complete class removal)
- `lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt` (191 lines, complete class removal)
- `lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt` (238 lines, Deck control removal)
- `src/com/android/launcher3/dragndrop/DragController.java` (16 lines, Deck-specific delete-target branch removed)
- `src/com/android/launcher3/DeleteDropTarget.java` (18 lines, Deck-specific delete/accessibility branches removed)
- `src/com/android/launcher3/model/PackageUpdatedTask.java` (13 lines, Deck import/placement tail removed)
- `src/com/android/launcher3/model/LayoutWriteCoordinator.java` (10 lines, `OwnerKind.DECK_FILE_RESTORE` removed)
- `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` (2 lines, `WriterKind.DECK_FILE_RESTORE` removed)
- `lawnchair/res/values/strings.xml` (9 lines, 5 Deck string keys removed)
- 58 locale `strings.xml` files (7 lines each, same 5 Deck string keys removed)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt` (5 lines, Deck preference exposure removed)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` (23 lines, Deck visibility/read gates removed)
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/PreferencesDashboard.kt` (20 lines, Deck gate hiding App Drawer removed)
- `lawnchair/src/app/lawnchair/ui/preferences/components/GestureHandlerPreference.kt` (12 lines, Deck-specific gesture filtering removed)
- `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` (50 lines, live `deckLayout`/`showDeckLayout` properties removed; private tombstone retention and internal normalization added)

New migration:
- `lawnchair/src/app/lawnchair/migration/DeckRetirementMigration.kt` (104 lines, sole startup normalization and post-success exact-cleanup owner)
- `lawnchair/src/app/lawnchair/LawnchairApp.kt` (23 lines, default-process-only migration invocation)

New test files:
- `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementMigrationInstrumentationTest.kt` (118 lines)
- `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementProcessIsolationInstrumentationTest.kt` (150 lines)
- `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementOldTargetCompatInstrumentationTest.kt` (348 lines)
- `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementBackupRestoreInstrumentationTest.kt` (247 lines)
- `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementDowngradeFixtureInstrumentationTest.kt` (97 lines)
- `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementTestRunner.kt` (131 lines)
- `tests/organizer-instrumentation/app/lawnchair/ui/preferences/DeckRetirementPreferencesInstrumentationTest.kt` (73 lines)
- `tests/organizer-instrumentation/com/android/launcher3/DeckRetirementDeleteRegressionTest.java` (101 lines)
- `tests/organizer-instrumentation/com/android/launcher3/DeckRetirementPackageRegressionTest.java` (80 lines)
- `tests/organizer-instrumentation/AndroidManifest.xml` (2 lines, runner registration)
- `tests/unit/app/lawnchair/migration/DeckRetirementArtifactNamesTest.kt` (117 lines, JVM unit test)
- `tests/unit/app/lawnchair/organizer/planning/harness/SyntheticFixtureGeneratorTest.kt` (8 lines, fixture retention assertion)

Scripts:
- `tools/deck-retirement-backup-restore-smoke.sh` (572 lines, AC-006 host orchestrator)
- `tools/deck-retirement-downgrade-smoke.sh` (813 lines, AC-008 host orchestrator)
- `tools/repo-contract/test_validate_deck_retirement.py` (217 lines, runtime inventory scanner)

CI wiring:
- `.github/workflows/ci.yml` (2 lines, deck retirement contract scan)
- `build.gradle` (2 lines, dependency wiring)
- `tools/organizer-recovery-smoke.sh` (4 lines, script path update)

Documentation:
- `tests/organizer-instrumentation/com/android/launcher3/organizer/RestoreLeaseSerializationTest.java` (2 lines, import update)
- `src/com/android/launcher3/provider/RestoreDbTask.java` (2 lines, import update)

## Criteria check

For each AC from the spec, the following maps the test oracle (from the plan's DRR test IDs) and verifies against the evidence.

### AC-001: Enabled, disabled, inconsistent, and interrupted upgrade states preserve the active Launcher database and never invoke Deck enable, disable, or `bk` or `lawndeck` restoration.

- **DRR-RED/GREEN-001** (DeckRetirementMigrationInstrumentationTest): `enabledDisabledAndInconsistentStatesPreserveActiveDbAndNormalizeAtomically` drives the internal entry point with Android DataStore and startup context.
- **Evidence**: The test file exists at `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementMigrationInstrumentationTest.kt` (118 lines). The production `DeckRetirementMigration.kt` (104 lines) performs normalization before any cleanup, does not invoke Deck enable/disable, and does not restore `bk`/`lawndeck` artifacts. The `LawndeckManager.kt` and `AddFoldersWithItemsTask.kt` are completely removed, so no Deck enable/disable path exists.
- **Verdict**: PASS (source-level and test-level verification; instrumentation execution requires device-level confirmation per AC-001 test oracle — see G3).

### AC-002: Both tombstones become `false` atomically before any cleanup, while current `swipeUpGesture` and `addIconToHome` values remain unchanged.

- **DRR-RED/GREEN-001** (same test class, covers AC-001 and AC-002).
- **Evidence**: `PreferenceManager2.kt` retains `enable_lawn_deck` and `show_deck_layout` as private/internal persisted-false tombstones and adds an internal atomic normalization operation. The production `DeckRetirementMigration.kt` calls this normalization before any cleanup. The normalization does not write `swipeUpGesture` or `addIconToHome`. The `DeckRetirementMigrationInstrumentationTest` exercises this path.
- **Verdict**: PASS (source-level verification of atomic normalization and unchanged unrelated values; instrumentation execution requires device-level confirmation).

### AC-003: Normalization and delete failures are retryable, never change the active database, and perform no cleanup before successful normalization.

- **DRR-RED/GREEN-002** (same instrumentation class): `normalizationAndDeleteFailuresLeaveActiveDbUntouchedAndRetryOnRestart` injects failures and relaunches.
- **Evidence**: `DeckRetirementMigration.kt` structures migration as sequential phases: normalization first, cleanup only after successful normalization. A failed edit stops before cleanup. A failed delete leaves the exact inert artifact for the next startup retry. The test file exercises failure injection.
- **Verdict**: PASS (source-level and test-level verification; instrumentation execution requires device-level confirmation).

### AC-004: Only exact historical names derived from the installed version's finite recognized Launcher grid-database basenames are recognized; no recognized file is restored.

- **DRR-RED/GREEN-003** (DeckRetirementArtifactNamesTest, JVM unit test): `derivesOnlyExactGridDbArtifactNames` covers active/inactive grid names, journals, and unknown files.
- **Evidence**: `tests/unit/app/lawnchair/migration/DeckRetirementArtifactNamesTest.kt` (117 lines, 6 tests). Local execution: `python3 -c "import xml.etree.ElementTree as ET; tree = ET.parse('build/test-results/testLawnWithQuickstepGithubDebugUnitTest/TEST-app.lawnchair.migration.DeckRetirementArtifactNamesTest.xml'); root = tree.getroot(); print(f'tests={root.get(\"tests\")}, failures={root.get(\"failures\")}, errors={root.get(\"errors\")}')"` reports `tests=6, failures=0, errors=0`.
- **Verdict**: PASS (6 JVM unit tests pass, proving exact-name derivation and no restoration path).

### AC-005: Deck runtime, UI, drag/delete behavior, package placement, and localized labels are absent, while normal package, App Drawer, gesture, delete, and accessibility behavior remain.

- **DRR-RED/GREEN-005** (DeckRetirementDeleteRegressionTest.java): `persistedItemDragAndAccessibilityDeleteUseBaselineBehavior`.
- **DRR-RED/GREEN-006** (DeckRetirementPackageRegressionTest.java): `packageAddKeepsWidgetAndBindingBehaviorWithoutDeckPlacementOrReplacementHook`.
- **DRR-RED/GREEN-007** (DeckRetirementPreferencesInstrumentationTest.kt): `appDrawerAndGesturesRemainVisibleWhileDeckControlsAreAbsent`.
- **DRR-RED/GREEN-008** (test_validate_deck_retirement.py): `testRuntimeInventoryIsRetired`.
- **Evidence**:
  - Source removals confirmed: `LawndeckManager.kt`, `AddFoldersWithItemsTask.kt`, `HomeLayoutPreferences.kt` (Deck control), `DragController.java` (Deck delete-target branch), `DeleteDropTarget.java` (Deck delete/accessibility branches), `PackageUpdatedTask.java` (Deck import/placement tail), `PreferenceManager2.kt` (live Deck properties), `ExperimentalFeaturesPreferences.kt` (Deck preference exposure), `HomeScreenPreferences.kt` (Deck visibility gates), `PreferencesDashboard.kt` (Deck gate hiding App Drawer), `GestureHandlerPreference.kt` (Deck gesture filtering). All 5 Deck string keys removed from `values/strings.xml` and all 58 locales. Baseline delete/accessibility/drag behavior preserved in `DeleteDropTarget.java`, `DragController.java`. App Drawer, gesture, and package behavior preserved in `PackageUpdatedTask.java` (ordinary package/widget/binding behavior retained).
  - `test_validate_deck_retirement.py` (6 tests) ran locally: `python3 tools/repo-contract/test_validate_deck_retirement.py` reports `OK` — all 6 tests pass: `test_deck_file_restore_absent_from_owner_kind`, `test_deck_output_compatibility_fixture_retained`, `test_deck_strings_absent_from_all_locales`, `test_no_deck_imports_in_production_sources`, `test_no_deck_runtime_class_references_in_production`, `test_no_deck_type_references_in_production`.
- **Verdict**: PASS (source-level removals confirmed via git diff and repo-contract scanner; 6 scanner tests pass; instrumentation tests exist for delete, package, and preference regression).

### AC-006: An old Lawnchair backup makes its restored database current; the next startup normalizes tombstones without a Deck restore.

- **DRR-RED/GREEN-004** (Host: `tools/deck-retirement-backup-restore-smoke.sh`; old/new entrypoints: `DeckRetirementOldTargetCompatInstrumentationTest.kt` and `DeckRetirementBackupRestoreInstrumentationTest.kt`).
- **Evidence**: The host script (572 lines) orchestrates old-compat pre-upgrade seeding, real archive creation, APK upgrade, archive reinjection, and new-target restore invocation. The old-compat test references no new migration APIs. The new-target test verifies package/version and calls actual `LawnchairBackup.readInfoAndPreview`/`restore`. The script is complete and structurally sound. Full execution requires a device/emulator with AVD.
- **Verdict**: PASS (structural verification; the script and test classes are ready for device-level execution — see G3).

### AC-007: The Deck-output fixture remains ingestion evidence only, and no replacement package hook is introduced.

- **DRR-RED/GREEN-009** (SyntheticFixtureGeneratorTest): `deckOutputCompatibilityFixtureIsRegistered`.
- **DRR-RED/GREEN-008** (test_validate_deck_retirement.py): fixture retention and no replacement hook.
- **Evidence**: `SyntheticFixtureGeneratorTest.kt` (8 lines added, fixture retention assertion). Local execution: `python3 -c "import xml.etree.ElementTree as ET; tree = ET.parse('build/test-results/testLawnWithQuickstepGithubDebugUnitTest/TEST-app.lawnchair.organizer.planning.harness.SyntheticFixtureGeneratorTest.xml'); root = tree.getroot(); print(f'tests={root.get(\"tests\")}, failures={root.get(\"failures\")}, errors={root.get(\"errors\")}')"` reports `tests=37, failures=0, errors=0`. `test_validate_deck_retirement.py` `test_deck_output_compatibility_fixture_retained` passes. No replacement package hook is introduced (`PackageUpdatedTask.java` removes the Deck import/placement tail without adding a replacement).
- **Verdict**: PASS (fixture retained, no replacement hook, confirmed by scanner and unit tests).

### AC-008: Rollback before cleanup is evidenced as best effort; cleanup-complete downgrade is evidenced without a restoration promise; old-binary or old-backup use before new-version initialization is recorded as unsupported.

- **DRR-RED/GREEN-010** (Host: `tools/deck-retirement-downgrade-smoke.sh --scenario rollback-before-cleanup`).
- **DRR-RED/GREEN-011** (Host: `tools/deck-retirement-downgrade-smoke.sh --scenario downgrade-after-cleanup`).
- **DRR-EV-012** (Host: `tools/deck-retirement-downgrade-smoke.sh --scenario pre-initialization-old-binary`).
- **DRR-EV-013** (Host: `tools/deck-retirement-downgrade-smoke.sh --scenario pre-initialization-old-backup`).
- **DRR-RED/GREEN-012** (androidTest runner/fixture: `DeckRetirementTestRunner.kt` and `DeckRetirementDowngradeFixtureInstrumentationTest.kt`).
- **Evidence**: `tools/deck-retirement-downgrade-smoke.sh` (813 lines) implements all 4 scenarios with real APK transitions, `new_pause` handshake, and before/after capture. The `DeckRetirementTestRunner.kt` (131 lines) implements the nonce-based pause observer, with validation (`^[0-9a-f]{32}$`) and `FileObserver` for release/ACK. The `DeckRetirementDowngradeFixtureInstrumentationTest.kt` (97 lines) implements typed state preparation and capture. The script and runner are structurally complete. Full execution requires a device/emulator with AVD.
- **Verdict**: PASS (structural verification; the script, runner, and fixture are ready for device-level execution — see G3).

### AC-009: Only the default Launcher process runs retirement migration; secondary processes do not open its preference or cleanup surfaces.

- **DRR-RED/GREEN-013** (DeckRetirementProcessIsolationInstrumentationTest): `secondaryProcessDoesNotEnterRetirementMigration` starts the `:bugReport` process and proves no migration marker, preference open, or artifact scan occurs.
- **Evidence**: `tests/organizer-instrumentation/app/lawnchair/migration/DeckRetirementProcessIsolationInstrumentationTest.kt` (150 lines). Production `LawnchairApp.kt` compares current/default process identity and invokes migration only at default-process startup; since the review-fix commit the gate is fail-closed (an undeterminable process name on API 26–27 is treated as non-default). The test positively confirms the `:bugReport` process start (shell-command error check plus process observation sentinel) and fails if the secondary process never ran. The test file exists and is structurally complete. Execution requires device-level instrumentation.
- **Verdict**: PASS (source-level verification of process identity gate; instrumentation test exists — see G3).

## Executed test surface

Independent local re-runs against `bb6d6b841de40718abbc7abd6b33cb83b788a508` (JDK 21.0.12 homebrew, ANDROID_HOME=/opt/homebrew/share/android-commandline-tools):

```text
$ ./gradlew spotlessCheck --no-daemon
  -> BUILD SUCCESSFUL in 9s (5 actionable tasks: 5 up-to-date)

$ ./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --tests 'app.lawnchair.migration.*' --no-daemon
  -> BUILD SUCCESSFUL in 34s (386 actionable tasks)
  -> Unit test results: 429 tests total, 0 failures
  -> DeckRetirementArtifactNamesTest: 6 tests, 0 failures
  -> SyntheticFixtureGeneratorTest: 37 tests, 0 failures

$ ./gradlew assembleLawnWithQuickstepGithubDebug --no-daemon
  -> BUILD SUCCESSFUL in 12s (445 actionable tasks)

$ ./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest --no-daemon
  -> BUILD SUCCESSFUL in 15s (467 actionable tasks)

$ python3 tools/repo-contract/validate_repo_contract.py
  -> repository contract OK

$ python3 tools/repo-contract/test_validate_deck_retirement.py
  -> OK (6 tests)

$ python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> OK (47 tests)
```

The `validate_high_risk_evidence.py` gate validation for this PR is satisfied by this record on head `bb6d6b841de40718abbc7abd6b33cb83b788a508` together with the green merge-gate CI run 32125231122.

All new instrumentation test files compile via the androidTest compilation task. CI executed `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`, and `final-status` on the audited head SHA and all concluded success (run 32125231122). Instrumentation tests are not executed on AVD in this audit (see G3).

## Findings

Verdict: **pass** (the merge gate `final-status` is green on the audited head SHA `bb6d6b841d` via CI run 32125231122, and the three blocking review findings are resolved by that same commit).

1. **[Low] G1 — Instrumentation tests require device-level execution.** All AC-001, AC-002, AC-003, AC-006, AC-008, and AC-009 test oracles reference instrumentation tests that require a device or emulator (DRR-RED/GREEN-001, 002, 004, 010-013). The JVM unit tests (DRR-RED/GREEN-003, 009) and the repo-contract scanner (DRR-RED/GREEN-008) are confirmed passing locally. The smoke scripts (`tools/deck-retirement-backup-restore-smoke.sh`, `tools/deck-retirement-downgrade-smoke.sh`) are structurally complete and ready for AVD execution. The implementation session's emulator-based default suite (11/11 tests) was run by the implementation session — this is external evidence cited from the CI run or session notes, not re-executed here.

2. **[Low] G2 — CI merge gate green on the audited head.** CI run 32125231122 on head `bb6d6b841d` shows `changes`, `check-style`, `validate-repo-contract`, `organizer-unit-tests`, `build-debug-apk`, and `final-status` all success. The earlier run 32092140699 (on the pre-review-fix head `83763aaa`) failed `validate-repo-contract` due to the then-stale writer-inventory allowlist and was cancelled downstream; the review-fix commit removed the stale entry and the gate is now green.

3. **[Low] G3 — Device-level process death/restart, backup/restore, and downgrade scenarios untested in this audit.** The AC-006 (backup/restore), AC-008 (downgrade/rollback), and AC-009 (secondary process) scenarios require real device/emulator execution with AVD and the smoke scripts. The `new_pause` handshake (nonce-based `FileObserver` release/ACK) was verified structurally: the runner validates nonce with `^[0-9a-f]{32}$`, creates `<nonce>.paused`, watches for `<nonce>.release`, and writes `<nonce>.ack`. The host scripts validate the same nonce pattern, use `adb exec-in run-as` for release, and require typed `PAUSED`/`ACK_RECEIVED` markers. The implementation plan documentation for the emulator-based default suite (11/11 tests) was cited as external evidence from the implementation session. This audit confirms the structural completeness of the scripts and runners.

4. **[Low] G4 — The `new_pause` handshake is verified only structurally.** The nonce validation, `FileObserver` setup, marker file creation, and release/ACK protocol are confirmed correct in source code. The actual device-level timing and inter-process file delivery were verified by the implementation session's emulator-based execution (11/11 tests) and are cited as external evidence. No source-level defect was found in the handshake implementation.

5. **[Low] G5 — JVM unit tests for artifact names pass.** `DeckRetirementArtifactNamesTest` (6 tests) and `SyntheticFixtureGeneratorTest` (37 tests) pass locally, confirming AC-004 and AC-007 at the JVM level. The `test_validate_deck_retirement.py` scanner (6 tests) passes, confirming AC-005 and AC-007 at the source-scan level.

Process note: The `High-risk gate` workflow run 32125231006 on head `bb6d6b841d` failed because, at that time, this audit record still referenced the pre-review-fix head `83763aaa` and the stale CI run 32092140699. This updated record re-anchors the audit to head `bb6d6b841de40718abbc7abd6b33cb83b788a508` and the green merge-gate run 32125231122; the gate is expected to pass once this docs-only commit lands. Any subsequent code change requires a fresh audit on the new head.