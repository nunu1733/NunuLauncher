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

Two independent samples were captured, each with its own force-stop/restart
cycle. Times are device-local and taken from the system log
(`ActivityManager` process boundaries), which is also what disambiguates which
run each interruption belongs to.

Sample 1 — run `dd31f7a2…`, recovery point `e0951a37…`:

1. Install the debug APK, set it as HOME, launch, and verify model loading
   (no reconciliation error in logcat).
2. Settings → Home screen → Organize home layout → **Review organization** →
   **Apply reviewed organization** (15:17–15:18). The apply reached
   `APPLY_COMMITTED` (A6) and then hit the accepted terminal failure
   `APPLY_RECOVERY_FAILED` (A7) — see
   [#150](https://github.com/nunu1733/NunuLauncher/issues/150). The automatic
   recovery had accepted the `RESTORING` transition before its own verification
   failed, leaving a real recovery record in `RESTORING`.
3. Process interruption: `adb shell am force-stop app.lawnchair.debug` —
   `ActivityManager` logged `Force stopping app.lawnchair.debug` at
   **15:43:15.998** and killed the run's process (pid 5933) at 15:43:16.000.
4. Restart: `adb shell am start …LawnchairLauncher` — new process (pid 7089)
   started **15:43:18.123** for the HOME activity; startup reconciliation ran
   in that process and emitted `RESTART_RECONCILED` at **15:43:20.680**.

Sample 2 — run `03bbc0bc…`, recovery point `2bb1cd27…`:

1. Steps 1–2 repeated (run started 15:45:38, confirmed 15:46; same A7 terminal
   failure at 15:46:29, record left `RESTORING`).
2. Process interruption: `adb shell am force-stop app.lawnchair.debug` at
   **16:01:00.677** (pid 7089 killed at 16:01:00.687; empty `pidof` confirmed
   process death before relaunch).
3. Restart: relaunch started new process (pid 7371) at **16:01:03.389**;
   `RESTART_RECONCILED` at **16:01:37.348**.

Finally, the journal was exported through Settings → Home screen → Organizer
diagnostics → **Export organizer diagnostics** (system `ACTION_CREATE_DOCUMENT`
picker, saved to Downloads, pulled over adb).

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

## Addendum (2026-08-28): the `pointOriginRunId` leg, captured after #150

> Status: Durable redacted subset — closes the one leg the 2026-08-26 capture
> left blocked
> Source commit: `0a43f616b4` (`main`; production sources identical to the
> PR #160 implementation head `44b4bad0c2` — later commits touch only `tests/`
> and `docs/`)
> Debug APK: `Lawnchair.15.Dev.(0a43f61).github.debug.apk`, SHA-256
> `a53506f659b0534bcbacc02f73fd7eeaa5995c8a46b21b34bd10a33f8aa5a7dd`
> (`app.lawnchair.debug`), fresh install (`pm clear`) set as HOME
> Runtime: `nunu_qpr2_api36_1` AVD (Pixel 6 definition, Google APIs arm64-v8a),
> Android 16 / API 36, 1080x2400 @ 420 dpi, 4x5 grid, `emulator-5554`.
> Only contract-allowed fields (random opaque correlation IDs, phase/stage
> codes, counts, lifecycle names) are quoted below. No raw layout data,
> package names, component names, or coordinates.

With [#150](https://github.com/nunu1733/NunuLauncher/issues/150) fixed
(PR #160), a run reaches `APPLY_VERIFIED`, so the explicit recovery flow — the
only surface that projects `pointOriginRunId` onto the journal — is reachable.

### Procedure (exact)

1. Settings → Home screen → Organize home layout → **Review organization** →
   **Apply reviewed organization**. The apply reached `APPLY_VERIFIED` (A8);
   UI: "Organization was applied and verified." Journal sequence 1–8:
   `RUN_STARTED`, `CAPTURED`, `PLANNED`, `PREVIEWED`, `USER_CONFIRMED`,
   `CHECKPOINTED` (A4), `APPLY_COMMITTED` (A6), `APPLY_VERIFIED` (A8), all with
   run `d93238d0…` and point `40b608ec…`.
2. **Restore the previous layout** → preview → **Restore saved layout**
   (confirmed 10:40:49 device-local). `RECOVERY_REQUESTED` was journaled
   (sequence 9) at **10:40:49.234** carrying
   `pointId=40b608ec…` and `pointOriginRunId=d93238d0…` — the origin run ID of
   the verified apply.
3. Process interruption: a device-side watcher polled the journal and ran
   `adb shell am force-stop app.lawnchair.debug` **40 ms** after
   `RECOVERY_REQUESTED` was persisted (**10:40:49.274**); empty `pidof`
   confirmed process death. The recovery record was left mid-restore in
   lifecycle `RESTORING`.
4. Restart: relaunch started pid 8888 at **10:47:19.094** for the HOME
   activity; startup reconciliation emitted `RESTART_RECONCILED`
   (sequence 10) at **10:47:22.781**.
5. The journal was exported through Settings → Home screen → Organizer
   diagnostics → **Export organizer diagnostics** (system `ACTION_CREATE_DOCUMENT`
   picker, saved to Downloads as `organizer_diagnostics.jsonl (3)`, 2634 bytes,
   pulled over adb).

### Correlation evidence (exported journal, redacted)

Journal sequence, phase, and correlation fields only — this is the complete
event list of the export:

```text
6  CHECKPOINTED        run=d93238d0… point=40b608ec… stage=A4
7  APPLY_COMMITTED     run=d93238d0… point=40b608ec… stage=A6
8  APPLY_VERIFIED      run=d93238d0… point=40b608ec… stage=A8
9  RECOVERY_REQUESTED  point=40b608ec… pointOriginRunId=d93238d0…
10 RESTART_RECONCILED  run=d93238d0… point=40b608ec…
                       subjectRunId=d93238d0… priorLifecycle=RESTORING
                       classification=PRE_STATE resultLifecycle=RESTORED
```

`pointOriginRunId` on the recovery event equals the verified apply's run ID,
and the restart-reconciled event resolves the same `pointId` from `RESTORING`
to `RESTORED` — all three required correlation fields now appear in one
exported journal.

Corresponding logcat (debug build logs ordinary transitions):

```text
08-28 10:47:22.781 D OrganizerDiag: run=d93238d0… phase=RESTART_RECONCILED subjectRun=d93238d0… priorLifecycle=RESTORING classification=PRE_STATE resultLifecycle=RESTORED
```

Post-restore invariants: `favorites` contains the exact pre-apply 15 rows, and
the recovery record's final lifecycle is `RESTORED`. A field-closure and
non-containment check over the export (every key/value compared against the
approved representation; scans for coordinate-like, package-like, and
component-like strings) found no field outside the approved event
representation.

### Capture note (not diagnosed as part of this capture)

An intermediate adb relaunch at 10:41:12.090 (pid 8654, between the
force-stop in step 3 and the restart in step 4) produced no reconciliation
event during its ~3-minute lifetime, and no model-load timeout error was
logged. The clean relaunch in step 4 reconciled normally. This capture records
the observation without a diagnosis; the accepted evidence uses the clean
relaunch.

### Status

- Real recovery point created by an organizer run: **captured**
  (2026-08-26 samples and this addendum).
- Process interruption/restart after an accepted recovery transition
  (`RESTORING`): **captured** (both captures).
- Matching `pointId`, `pointOriginRunId`, and `RESTART_RECONCILED` correlation
  fields in the exported journal: **captured** (this addendum).
- `pointOriginRunId` in the exported journal: **captured**; the #150 blocker
  is resolved.

**#104: complete.** All required evidence is captured with redaction checks.

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
**Restore**. App-private file listings around the cycle (`run-as … ls -la
files/organizer_diagnostics/`; file names, sizes, and timestamps only — no
layout content):

Before (captured 16:17, before the archive was created):

```text
total 36
drwx------ 2 u0_a223 u0_a223 4096 2026-08-26 15:17 .
drwxrwx--x 5 u0_a223 u0_a223 4096 2026-08-26 15:09 ..
-rw------- 1 u0_a223 u0_a223    2 2026-08-26 16:01 journal_seq
-rw------- 1 u0_a223 u0_a223 4549 2026-08-26 16:01 organizer_diagnostics.journal
```

with `md5sum`: `9f873c54f8e667ed3fcb7193fdbfdaa4` (journal),
`6f4922f45568161a8cdf4ad2299f6d23` (`journal_seq`).

After the restore (captured 16:27, after the post-restore launcher start):

```text
files/organizer_diagnostics:
total 20
drwx------ 2 u0_a223 u0_a223 4096 2026-08-26 16:18 .
drwxrwx--x 5 u0_a223 u0_a223 4096 2026-08-26 16:18 ..
-rw------- 1 u0_a223 u0_a223    0 2026-08-26 16:18 organizer_diagnostics.journal
```

Result:

- Layout was restored: `launcher_5_4_4.db` contains the backed-up 15 favorites
  rows (restore path demonstrably executed).
- `organizer_diagnostics.journal` exists only as a fresh **0-byte** file
  created by the eagerly opened store (`journal_seq` is absent until the next
  append); the 18 pre-backup events were not restored.

### Check 3 — Android backup/restore

`bmgr enable true`, transport switched to `com.android.localtransport/.LocalTransport`.
A fresh journal was created first (one run's first four events
`RUN_STARTED`/`CAPTURED`/`PLANNED`/`PREVIEWED`, run `38f968cf…`; final
pre-backup state 1024 bytes, md5 `165d736f3b6bb8081c6535b607bdb53e`), then
`bmgr backupnow app.lawnchair.debug` (result: Success), `pm clear`, and
`bmgr restore 1 app.lawnchair.debug` (`restoreFinished: 0`).

Before (captured 16:52, after the journal-creating run):

```text
total 32
drwx------ 2 u0_a223 u0_a223 4096 2026-08-26 16:33 .
drwxrwx--x 5 u0_a223 u0_a223 4096 2026-08-26 16:46 ..
-rw------- 1 u0_a223 u0_a223    1 2026-08-26 16:46 journal_seq
-rw------- 1 u0_a223 u0_a223  588 2026-08-26 16:46 organizer_diagnostics.journal
```

(the final pre-backup size after one more appended run was 1024 bytes, the md5
above; content pulled over `run-as cat` before `backupnow`)

After the clean restore (captured 16:57, after the post-restore launcher
start):

```text
total 20
drwx------ 2 u0_a223 u0_a223 4096 2026-08-26 16:57 .
drwxrwx--x 5 u0_a223 u0_a223 4096 2026-08-26 16:57 ..
-rw------- 1 u0_a223 u0_a223    0 2026-08-26 16:57 organizer_diagnostics.journal
```

Result:

- The pre-backup journal (4 events, md5 `165d736f…`) was **not restored**; the
  store contains only a fresh **0-byte** journal.
- `launcher_5_4_4.db` was restored with 15 favorites rows (restore path
  demonstrably executed).

This matches the static exclusion surface (`res/xml/backupscheme.xml` includes
only specific databases/prefs; the Lawnchair ZIP allowlist copies only the
launcher DB and preference files).

### Observation recorded during this check

After the ZIP restore, `composeFullOrganization()` returned `NotReady`
("input unavailable") on every retry across several processes and roughly 30
minutes of attempts (including process restarts at 16:27/16:41), although the
restored layout was intact. A later fresh process (started 16:49) could reach
the preview again, so the condition is not permanent for the database state;
the failing sub-check cannot be identified on-device because the composer's
`NotReady` diagnostic code is not logged. Recorded as a related symptom in
[#150](https://github.com/nunu1733/NunuLauncher/issues/150).

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
- **#104: complete.** Recovery point creation, interruption/restart procedure,
  `pointId` + `RESTART_RECONCILED` correlation, and redaction are captured in
  the 2026-08-26 section; the `pointOriginRunId` leg was unblocked by
  [#150](https://github.com/nunu1733/NunuLauncher/issues/150) (PR #160) and is
  captured in the 2026-08-28 addendum above.
