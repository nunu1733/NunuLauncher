# Issue #81: Organizer diagnostics residual evidence

> Status: complete for Issue #81 — code and available emulator evidence are recorded; the remaining device-only evidence is explicitly split into follow-up blocker Issues [#103](https://github.com/nunu1733/NunuLauncher/issues/103), [#104](https://github.com/nunu1733/NunuLauncher/issues/104), [#105](https://github.com/nunu1733/NunuLauncher/issues/105), and [#106](https://github.com/nunu1733/NunuLauncher/issues/106).
> Recorded: 2026-08-22
> Base commit: `cfa8c69b920b815a6f834c3b08b87b6243fd3d59`
> Related Issue: [#81](https://github.com/nunu1733/NunuLauncher/issues/81)
> Contract: [`docs/engineering/organizer-diagnostics.md`](../engineering/organizer-diagnostics.md), §8–§11; [spec 67](../../specs/67-organizer-diagnostics/spec.md), AC-67-08, AC-67-10, AC-67-11, and AC-67-13.

## Scope

This record closes the host-directory-sync implementation gap, records a concrete emulator check for the explicit export and accessibility surfaces, and resolves the three reviewer-acknowledged contract interpretations without widening the diagnostics schema, public planner/application contracts, permissions, telemetry, or transport.

The test target was the existing API 36 AOSP emulator `emulator-5554` (`sdk_gphone64_arm64`), running the locally built `app.lawnchair.debug` APK. The debug menu was opened only after normal `LawnchairLauncher` initialization; launching `PreferenceActivity` directly in a cold process is not a supported production entry path because `layoutApplicationModule` is initialized by the Launcher activity.

## Explicit export and accessibility evidence

| Requirement | Environment and exact procedure | Result |
|---|---|---|
| AC-67-08 — explicit export only | Built and installed the debug APK. Started `app.lawnchair.LawnchairLauncher`, opened the initialized Debug Menu, enabled its local debug switch, and selected **Export organizer diagnostics**. | **Pass on emulator.** No picker or export intent appeared before the row was selected. Selecting the row opened the system-owned `com.google.android.documentsui.picker.PickActivity` CreateDocument picker with the proposed name `organizer_diagnostics.jsonl`. Pressing Back returned to the debug menu; no automatic retry or second picker was launched. |
| AC-67-13 — localized label and operability | On the same screen, dumped the accessibility tree. The row containing **Export organizer diagnostics** and its explanatory subtitle was `clickable=true`, `enabled=true`, and `focusable=true`. At `font_scale=2.0`, both complete strings remained present in the tree and the parent row remained clickable/focusable. From the preceding row, one hardware `TAB` event moved focus to the export row. TalkBack was enabled through the API 36 accessibility service setting; selecting the export action with the service active opened the system picker, and Back cancelled it. The setting was restored to `font_scale=1.0`. | **Pass for localized semantics, TalkBack action path, keyboard focus, and 200% font scale on emulator.** Switch Access scan/selection could not be completed because the emulator had no switch input and its Camera Switch setup download failed; this is tracked in [#103](https://github.com/nunu1733/NunuLauncher/issues/103). |

The source-level boundary remains consistent with the emulator observation. `OrganizerDiagnosticsExportPreference` creates the `ACTION_CREATE_DOCUMENT` intent only inside `ClickablePreference.onClick`; cancellation returns without write, retry, network, or raw-log fallback. `ExportWriter.writeToUri()` receives the same live diagnostics port snapshot used by the running store.

## Reviewer-acknowledged Low observations

| Observation | Resolution | Revert-detecting evidence |
|---|---|---|
| Host JVM directory fsync was effectively best-effort/no-op on macOS because `RandomAccessFile(directory, "r")` cannot open a directory there. | **Fixed.** `SyncHook.forceDirectoryMetadata()` now opens the parent directory with `FileChannel.open(directory.toPath(), READ)` and forces its metadata. Production still catches unsupported-platform failures as best-effort, while Android/Linux and macOS now use the same requested directory-sync operation. A standalone macOS JDK 21 check completed with `DIRECTORY_FORCE_OK`. | `JournalStoreTest.productionDirectorySyncUsesReadOnlyFileChannel` calls `SyncHook.PRODUCTION.syncDirectory(...)` and asserts the production wrapper reports a successful operation, so reverting only the wrapper to the old `RandomAccessFile` path fails on the host JVM. Existing rewrite-order and copy-fallback tests continue to assert that the directory sync occurs after replacement. |
| Export of a corrupt journal may observe the corruption-isolation reset path. | **Accepted and documented.** Export itself reads a stable snapshot and never calls reset, prune, or append. If normal store initialization has already isolated a corrupt journal under §8, export reads the valid empty post-reset snapshot. This is a store-wide fail-open isolation action, not an export-side mutation. | The contract now states the distinction in §9. `JournalStoreTest.corruptionResetsJournal` proves isolation; `ExportWriterTest` continues to cover export parity and non-mutation on write failure/cancellation. |
| A `RESTART_RECONCILED` event might release recovery point protection while the result is still in-flight. | **Accepted behavior clarified and already enforced.** Protection releases only for a terminal recovery event or matching `RESTART_RECONCILED` event whose `resultingLifecycle` is resolved. In-flight resulting lifecycles retain protection; a different pointId cannot release a group. | `RetentionPolicyTest` includes same-point resolved release, different-point non-release, unresolved-resulting-lifecycle non-release, and missing-context non-release cases. The §8 contract now makes this ownership explicit. |
| Incomplete non-protected histories could escape every retention cap. | **Already fixed in PR #82 and reconfirmed.** The retention policy partitions histories into protected and non-protected sets. Incomplete non-protected histories are age/size candidates and count toward the 512 KiB limit; the 10-run cap intentionally remains resolved-run-only. | `RetentionIncompleteRunTest` covers age pruning, READY/checkpoint classification, and size pruning before protected history. |

## Remaining manual/device evidence

The following evidence is intentionally not claimed as completed device evidence. No source change is required to preserve the accepted #67 contract; each remaining item now has a concrete follow-up Issue so #81 can close without treating JVM/source evidence as device evidence.

| Item | Exact next procedure | Current status |
|---|---|---|
| TalkBack and Switch Access traversal | Enable both services on a supported device, navigate to the export action through the Debug Menu, and verify its localized name, focus announcement, activation, and return from picker cancellation. | **Switch Access blocked; follow-up [#103](https://github.com/nunu1733/NunuLauncher/issues/103).** USB setup found no device; Camera Switch setup failed while downloading its model. TalkBack, keyboard focus, and 200% font scale are recorded above. |
| Representative real recovery/restart `pointOriginRunId` correlation | Create a recovery point, interrupt the process after an accepted recovery transition, restart the Launcher, and inspect the exported journal for matching `pointId`, `pointOriginRunId`, and `RESTART_RECONCILED` correlation fields only. | **Blocked; follow-up [#104](https://github.com/nunu1733/NunuLauncher/issues/104).** The API 36 emulator repeatedly failed Launcher model loading, so manual organization stopped at input-unavailable/reconciliation-pending and created no representative recovery point. |
| Backup/restore exclusion | Create a diagnostic journal, execute both the Lawnchair ZIP and Android backup/restore paths in an isolated emulator state, then verify that the restored app does not contain the prior journal. | **Blocked; follow-up [#105](https://github.com/nunu1733/NunuLauncher/issues/105).** The backup preview never completed and the Create action remained disabled on the same model-not-ready emulator. |
| Release-build logcat | Install a release variant, trigger one accepted failure and one ordinary transition, and verify that `OrganizerDiag` emits only the failure as a redacted WARN entry. | **Blocked; follow-up [#106](https://github.com/nunu1733/NunuLauncher/issues/106).** Release assembly succeeded, but the emulator could not reach an accepted terminal failure; absence of output would not distinguish filtering from suppressing all release logs. |

## Commands and results

```text
# Focused regression test for the host-directory-sync fix
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.diagnostics.journal.JournalStoreTest' \
  --no-daemon
# BUILD SUCCESSFUL (386 actionable tasks: 17 executed, 369 up-to-date)

# Debug APK used for the emulator evidence
./gradlew assembleLawnWithQuickstepGithubDebug --no-daemon
# BUILD SUCCESSFUL (445 actionable tasks: 4 executed, 441 up-to-date)

# Release APK build for the release-logcat follow-up
./gradlew assembleLawnWithQuickstepGithubRelease --no-daemon --console=plain
# BUILD SUCCESSFUL (491 actionable tasks)

# Emulator target and package installation
/opt/homebrew/share/android-commandline-tools/platform-tools/adb devices -l
# emulator-5554 device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64
/opt/homebrew/share/android-commandline-tools/platform-tools/adb -s emulator-5554 install -r \
  build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(088a07b).github.debug.apk
# Success

# Release artifact
build/outputs/apk/lawnWithQuickstepGithub/release/Lawnchair.15.Dev.(088a07b).github.release.apk

# Diagnostics/contract verification
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.diagnostics.*' --no-daemon --console=plain
# BUILD SUCCESSFUL (386 actionable tasks)
./gradlew spotlessCheck --no-daemon --console=plain
# BUILD SUCCESSFUL
python3 tools/repo-contract/validate_repo_contract.py
# repository contract OK
python3 tools/repo-contract/validate_diagnostics_contract.py
# PASS: No AC-67-12 diagnostics contract violations found.
```

## Close judgment

**Ready to close #81.** The host fsync correction, the documented Low-observation resolutions, and the available emulator evidence are complete. The explicit export path, cancellation behavior, localized semantics, TalkBack action path, keyboard focus, and 200% font-scale rendering are recorded. The remaining device-only evidence is not silently waived: it is split into the concrete follow-up Issues [#103](https://github.com/nunu1733/NunuLauncher/issues/103), [#104](https://github.com/nunu1733/NunuLauncher/issues/104), [#105](https://github.com/nunu1733/NunuLauncher/issues/105), and [#106](https://github.com/nunu1733/NunuLauncher/issues/106).

## Change history

- 2026-08-22: Added host macOS directory-sync correction, clarified §8/§9 diagnostics-contract semantics, recorded API 36 emulator export/accessibility evidence, and listed remaining manual evidence without claiming completion.
- 2026-08-22: Exercised TalkBack and Switch Access setup on the API 36 emulator, recorded the model-loading/backup/release-logcat blockers, and split the remaining device evidence into Issues #103–#106 so #81 can close without overstating evidence.
