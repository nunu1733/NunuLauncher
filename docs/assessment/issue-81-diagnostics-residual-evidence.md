# Issue #81: Organizer diagnostics residual evidence

> Status: partial — code and emulator evidence recorded; the Issue is **not ready to close** until the explicitly listed device evidence is completed or split into blocker Issues.
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
| AC-67-13 — localized label and operability | On the same screen, dumped the accessibility tree. The row containing **Export organizer diagnostics** and its explanatory subtitle was `clickable=true`, `enabled=true`, and `focusable=true`. At `font_scale=2.0`, both complete strings remained present in the tree and the parent row remained clickable/focusable. From the preceding row, one hardware `TAB` event moved focus to the export row. The setting was restored to `font_scale=1.0`. | **Pass for label, keyboard focus, and 200% font scale on emulator.** Direct TalkBack speech output and Switch Access traversal were not exercised in this session; that narrow manual check remains open below. |

The source-level boundary remains consistent with the emulator observation. `OrganizerDiagnosticsExportPreference` creates the `ACTION_CREATE_DOCUMENT` intent only inside `ClickablePreference.onClick`; cancellation returns without write, retry, network, or raw-log fallback. `ExportWriter.writeToUri()` receives the same live diagnostics port snapshot used by the running store.

## Reviewer-acknowledged Low observations

| Observation | Resolution | Revert-detecting evidence |
|---|---|---|
| Host JVM directory fsync was effectively best-effort/no-op on macOS because `RandomAccessFile(directory, "r")` cannot open a directory there. | **Fixed.** `SyncHook.forceDirectoryMetadata()` now opens the parent directory with `FileChannel.open(directory.toPath(), READ)` and forces its metadata. Production still catches unsupported-platform failures as best-effort, while Android/Linux and macOS now use the same requested directory-sync operation. A standalone macOS JDK 21 check completed with `DIRECTORY_FORCE_OK`. | `JournalStoreTest.productionDirectorySyncUsesReadOnlyFileChannel` executes the same read-only directory channel operation on the host JVM. Existing rewrite-order and copy-fallback tests continue to assert that the directory sync occurs after replacement. |
| Export of a corrupt journal may observe the corruption-isolation reset path. | **Accepted and documented.** Export itself reads a stable snapshot and never calls reset, prune, or append. If normal store initialization has already isolated a corrupt journal under §8, export reads the valid empty post-reset snapshot. This is a store-wide fail-open isolation action, not an export-side mutation. | The contract now states the distinction in §9. `JournalStoreTest.corruptionResetsJournal` proves isolation; `ExportWriterTest` continues to cover export parity and non-mutation on write failure/cancellation. |
| A `RESTART_RECONCILED` event might release recovery point protection while the result is still in-flight. | **Accepted behavior clarified and already enforced.** Protection releases only for a terminal recovery event or matching `RESTART_RECONCILED` event whose `resultingLifecycle` is resolved. In-flight resulting lifecycles retain protection; a different pointId cannot release a group. | `RetentionPolicyTest` includes same-point resolved release, different-point non-release, unresolved-resulting-lifecycle non-release, and missing-context non-release cases. The §8 contract now makes this ownership explicit. |
| Incomplete non-protected histories could escape every retention cap. | **Already fixed in PR #82 and reconfirmed.** The retention policy partitions histories into protected and non-protected sets. Incomplete non-protected histories are age/size candidates and count toward the 512 KiB limit; the 10-run cap intentionally remains resolved-run-only. | `RetentionIncompleteRunTest` covers age pruning, READY/checkpoint classification, and size pruning before protected history. |

## Remaining manual/device evidence

The following evidence is intentionally not claimed by this record. No source change is required to preserve the accepted #67 contract, but the Issue must remain open until each item is run or a concrete blocker Issue is created.

| Item | Exact next procedure | Current status |
|---|---|---|
| TalkBack and Switch Access traversal | On the API 36 emulator or a supported physical device, enable TalkBack and then Switch Access; navigate to the export action through the Debug Menu and verify its localized name, focus announcement, activation, and return from picker cancellation. | Open — keyboard focus and 200% font scale are recorded above, but assistive-service traversal itself was not run. |
| Representative real recovery/restart `pointOriginRunId` correlation | Create a recovery point, interrupt the process after an accepted recovery transition, restart the Launcher, and inspect the exported journal for matching `pointId`, `pointOriginRunId`, and `RESTART_RECONCILED` correlation fields only. | Open — JVM projection and reconciler tests are present, but this record contains no independent device run. |
| Backup/restore exclusion | Create a diagnostic journal, execute both the Lawnchair ZIP and Android backup/restore paths in an isolated emulator state, then verify that the restored app does not contain the prior journal. | Open — repository contract tests establish allowlist exclusion only. |
| Release-build logcat | Install a release variant, trigger one accepted failure and one ordinary transition, and verify that `OrganizerDiag` emits only the failure as a redacted WARN entry. | Open — JVM logger tests verify the branch behavior, but no release device logcat capture is recorded. |

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

# Emulator target and package installation
/opt/homebrew/share/android-commandline-tools/platform-tools/adb devices -l
# emulator-5554 device product:sdk_gphone64_arm64 model:sdk_gphone64_arm64
/opt/homebrew/share/android-commandline-tools/platform-tools/adb -s emulator-5554 install -r \
  build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(cfa8c69).github.debug.apk
# Success
```

## Close judgment

**Do not close #81 yet.** The host fsync correction and the documented Low-observation resolutions are complete; the emulator evidence also demonstrates the explicit user-action export path, cancellation behavior, keyboard focus, and 200% font-scale rendering. The four manual evidence items in the preceding table remain necessary to satisfy the Issue exit criteria without overstating structural or JVM verification as device evidence.

## Change history

- 2026-08-22: Added host macOS directory-sync correction, clarified §8/§9 diagnostics-contract semantics, recorded API 36 emulator export/accessibility evidence, and listed remaining manual evidence without claiming completion.
