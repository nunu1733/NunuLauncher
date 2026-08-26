# Issues #104/#105/#106 organizer diagnostics device evidence

> Status: Durable redacted subset
> Recorded: 2026-08-26
> Source commit: `74c2156767` (`main`)
> Debug APK: `Lawnchair.15.Dev.(74c2156).github.debug.apk` (`app.lawnchair.debug`)
> Release APK: `Lawnchair.15.Dev.(74c2156).github.release.apk` (`app.lawnchair`),
> SHA-256 `ffcf0c4b1ef35e3836d85f72804ee46fbae974b3faeb3c753639c37c467b4e2c`
> Runtime: `nunu_qpr2_api36_1` AVD (Pixel 6 definition, Google APIs arm64-v8a),
> Android 16 / API 36, portrait 1080x2400 @ 420 dpi, 4x5 grid, `emulator-5554`.
> All layouts are the emulator's synthetic/default workspace. Only contract-allowed
> fields (random opaque correlation IDs, phase/stage/error codes, counts) are quoted.
> No raw layout data, package names, component names, or coordinates are included.
> Related: [#104](https://github.com/nunu1733/NunuLauncher/issues/104),
> [#105](https://github.com/nunu1733/NunuLauncher/issues/105),
> [#106](https://github.com/nunu1733/NunuLauncher/issues/106),
> defect found during capture: [#150](https://github.com/nunu1733/NunuLauncher/issues/150).

The 2026-08-22 blocker ("Organizer startup reconciliation began without a
completed model load" on an AOSP emulator image) did not reproduce: on this
model-ready AVD both build types completed Launcher model loading, the manual
organization surface was reachable, and the Lawnchair backup preview completed
so the Create action was enabled.

## Issue #104 — recovery/restart diagnostics correlation

### Procedure (exact)

1. Install the debug APK, set it as HOME, launch, and verify model loading
   (no reconciliation error in logcat).
2. Settings → Home screen → Organize home layout → **Review organization** →
   **Apply reviewed organization** (run `dd31f7a2…`, recovery point `e0951a37…`).
   The apply reached `APPLY_COMMITTED` (A6) and then hit the accepted terminal
   failure `APPLY_RECOVERY_FAILED` (A7) — see [#150](https://github.com/nunu1733/NunuLauncher/issues/150).
   The automatic recovery had accepted the `RESTORING` transition before its own
   verification failed, leaving a real recovery record in `RESTORING`.
3. Process interruption: `adb shell am force-stop app.lawnchair.debug`
   (16:01:00 local; process death confirmed via empty `pidof`).
4. Restart: `adb shell am start app.lawnchair.debug/app.lawnchair.LawnchairLauncher`
   (16:01:02 local). Startup reconciliation ran in the new process.
5. A second, independent sample repeated steps 2–4 for run `03bbc0…` /
   point `2bb1cd27…` (interruption 16:01:00 was for run `dd31f7a2…`; run
   `03bbc0…` was interrupted by the same force-stop and reconciled at
   16:01:37 in the relaunched process).
6. Exported the journal through Settings → Home screen → Organizer diagnostics
   → **Export organizer diagnostics** (system `ACTION_CREATE_DOCUMENT` picker,
   saved to Downloads, pulled over adb).

### Correlation evidence (exported journal, redacted)

Both interrupted runs show the same matching-field chain. Journal sequence,
phase, correlation IDs only:

```text
6  CHECKPOINTED          run=dd31f7a2… point=e0951a37… stage=A4
7  APPLY_COMMITTED       run=dd31f7a2… point=e0951a37… stage=A6
8  APPLY_RECOVERY_FAILED run=dd31f7a2… point=e0951a37… stage=A7
                          err=APPLY_FAILURE.VERIFICATION_FAILED
9  RESTART_RECONCILED    run=dd31f7a2… point=e0951a37…
                          subjectRunId=dd31f7a2… priorLifecycle=RESTORING
                          classification=PRE_STATE resultLifecycle=RESTORED
15 CHECKPOINTED          run=03bbc0bc… point=2bb1cd27… stage=A4
16 APPLY_COMMITTED       run=03bbc0bc… point=2bb1cd27… stage=A6
17 APPLY_RECOVERY_FAILED run=03bbc0bc… point=2bb1cd27… stage=A7
18 RESTART_RECONCILED    run=03bbc0bc… point=2bb1cd27…
                          subjectRunId=03bbc0bc… priorLifecycle=RESTORING
                          classification=PRE_STATE resultLifecycle=RESTORED
```

Corresponding logcat (debug build logs ordinary transitions):

```text
08-26 15:43:20.680 D OrganizerDiag: run=dd31f7a2… phase=RESTART_RECONCILED subjectRun=dd31f7a2… priorLifecycle=RESTORING classification=PRE_STATE resultLifecycle=RESTORED
08-26 16:01:37.348 D OrganizerDiag: run=03bbc0bc… phase=RESTART_RECONCILED subjectRun=03bbc0bc… priorLifecycle=RESTORING classification=PRE_STATE resultLifecycle=RESTORED
```

The recovery store independently maps each `pointId` to its creating run
(`recovery_points`: `e0951a37… → dd31f7a2…`, `2bb1cd27… → 03bbc0…`), and both
records ended `RESTORED` after reconciliation. A D-09-style non-containment
check over the exported journal found no Never-classified content and no
fields outside the approved event representation.

### Status

- Real recovery point created by an organizer run: **captured** (two points).
- Process interruption/restart after an accepted recovery transition
  (`RESTORING`): **captured** with the exact procedure above.
- Matching `pointId` and `RESTART_RECONCILED` correlation fields in the
  exported journal: **captured**.
- `pointOriginRunId` in the exported journal: **blocked** by
  [#150](https://github.com/nunu1733/NunuLauncher/issues/150). That field is
  only projected onto `RECOVERY_REQUESTED`/`RECOVERY_*` events of the explicit
  recovery flow, which requires one successful apply (`APPLY_VERIFIED`) to
  reach; no run can currently complete verification on-device. #104 stays open
  for that leg.

## Issue #105 — diagnostics journal exclusion from backup/restore

Pre-state: `files/organizer_diagnostics/` contained
`organizer_diagnostics.journal` (4549 bytes, 18 events) and `journal_seq`.

### Check 1 — Lawnchair ZIP backup archive

Settings → ⋮ → Create backup (preview completed; Create enabled) → saved via
the system picker to Downloads. Archive entry listing (complete):

```text
info.pb
screenshot.png
launcher.db
com.android.launcher3.prefs.xml
preferences
preferences.preferences_pb
```

No `organizer_diagnostics` journal or sequence file is present, and a content
search of the archive found no journal markers. Backup ZIP SHA-256:
`62af434c2cfe2e52347c259633029b59b1366d8e3dab4e00ca1ab1c7b8e91518`.

### Check 2 — ZIP restore into an isolated app state

`pm clear app.lawnchair.debug` (verified `files/` gone) → launcher set as HOME
and relaunched → Settings → ⋮ → Restore backup → selected the archive →
**Restore**. Result:

- Layout was restored: `launcher_5_4_4.db` contains the backed-up 15 favorites
  rows (restore path demonstrably executed).
- `files/organizer_diagnostics/organizer_diagnostics.journal` exists only as a
  fresh **0-byte** file created by the eagerly opened store; the 18 pre-backup
  events were not restored.

### Check 3 — Android backup/restore

`bmgr enable true`, transport switched to `com.android.localtransport/.LocalTransport`.
A fresh journal (4 `RUN_STARTED` events, 1024 bytes, md5 `165d736f…`) was
created first, then `bmgr backupnow app.lawnchair.debug` (result: Success),
`pm clear`, and `bmgr restore 1 app.lawnchair.debug` (`restoreFinished: 0`).
Result after the post-restore launcher start:

- `files/organizer_diagnostics/` contains only a fresh **0-byte** journal; the
  4 pre-backup events were not restored.
- `launcher_5_4_4.db` was restored with 15 favorites rows (restore path
  demonstrably executed).

This matches the static exclusion surface (`res/xml/backupscheme.xml` includes
only specific databases/prefs; the Lawnchair ZIP allowlist copies only the
launcher DB and preference files).

### Observation recorded during this check

After the ZIP restore, `composeFullOrganization()` returned `NotReady`
("input unavailable") on every retry and in every later process, although the
restored layout was intact. Recorded as a related symptom in
[#150](https://github.com/nunu1733/NunuLauncher/issues/150) (the composer's
`NotReady` diagnostic code is not observable on-device).

## Issue #106 — release failure-only OrganizerDiag logcat

Fresh install of the minified release APK (`app.lawnchair`, SHA-256 above),
`pm clear` first, set as HOME, model load verified (no reconciliation error,
zero `OrganizerDiag` lines during startup). Logcat was cleared immediately
before the run. One manual run (Smartspace at its default ON): start → preview
→ confirm → apply. All ordinary transitions (`RUN_STARTED`, `CAPTURED`,
`PLANNED`, `PREVIEWED`, `USER_CONFIRMED`, `CHECKPOINTED`, `APPLY_COMMITTED`)
produced **no** logcat output, and the accepted terminal failure produced
**exactly one** line:

```text
08-26 17:06:34.086 10511 10669 W OrganizerDiag: run=f9ee2f06… phase=APPLY_RECOVERY_FAILED stage=A7 err=APPLY_FAILURE.VERIFICATION_FAILED
```

`grep -c OrganizerDiag` over the cleared-buffer window returned `1`. The line
contains only the opaque run ID, phase, stage, and error family/code — no raw
identifiers and no exception text.

Persistence-before-logging proof: the release Settings route
(Home screen → Organizer diagnostics → Export, the supported #138 surface)
exported the live journal in the same state. Its persisted lines (timestamps
are `recordedAtWallMillis`):

```text
1 RUN_STARTED           17:05:01.805  run=f9ee2f06…
5 USER_CONFIRMED        17:06:23.385  run=f9ee2f06…
6 CHECKPOINTED          17:06:23.652  run=f9ee2f06…
7 APPLY_COMMITTED       17:06:23.799  run=f9ee2f06…
8 APPLY_RECOVERY_FAILED 17:06:34.075  run=f9ee2f06… err=APPLY_FAILURE.VERIFICATION_FAILED
```

The persisted `APPLY_RECOVERY_FAILED` event (17:06:34.075) precedes the WARN
logcat line (17:06:34.086) by 11 ms, and every ordinary transition is present
in the journal while absent from logcat — release filtering suppresses exactly
the non-terminal events. The export passed the same redaction/field-closure
checks as above.

## Commands and results

```bash
./gradlew assembleLawnWithQuickstepGithubDebug --no-daemon --console=plain
# BUILD SUCCESSFUL in 29s (445 actionable tasks)
./gradlew assembleLawnWithQuickstepGithubRelease --no-daemon --console=plain
# BUILD SUCCESSFUL in 1m 31s (491 actionable tasks)

adb install -r Lawnchair.15.Dev.(74c2156).github.debug.apk   # Success
adb install Lawnchair.15.Dev.(74c2156).github.release.apk    # Success
adb shell pm clear app.lawnchair.debug                        # Success (isolation steps)
adb shell bmgr backupnow app.lawnchair.debug                  # Success
adb shell bmgr restore 1 app.lawnchair.debug                  # restoreFinished: 0
adb shell run-as … ls/cat files/organizer_diagnostics/        # listings above
unzip -l "Lawnchair_Backup … .lawnchairbackup"                # entry list above
```

## Close judgment

- **#105: complete.** All three required checks pass with archive/before-after evidence.
- **#106: complete.** Release failure-only filtering, single redacted WARN,
  and persistence-before-logging are proven on-device.
- **#104: evidence recorded; one leg blocked.** Recovery point creation,
  interruption/restart procedure, `pointId` + `RESTART_RECONCILED` correlation,
  and redaction are captured. The `pointOriginRunId` journal field requires the
  explicit recovery flow, which is unreachable until [#150](https://github.com/nunu1733/NunuLauncher/issues/150)
  restores a successful apply path. #104 remains open, tracking #150.
