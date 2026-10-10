# Issue #563 API36 HOME-origin empty overview: antecedent and classification

Status: investigation record (classification established; production fix pending its own spec/review).
Refs #563. Related: #559 (fixed APK source), #554 / #555 (explicitly not the attributed cause).

## Identity

- Device: emulator-5556 (AVD `issue142_api36`), SDK36, fingerprint
  `google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`,
  SystemUI sha256 `a895f88ae0539d9950a74e49f0471ca81237337f0b31d6eb5f4fe29e814d14f5`.
  Same provider as the #559 evidence set. GLOBAL animation scales 0/0/0, gesture nav,
  provider overlay `nunu.overlay.quickstepconfig`. No AVD wipe, no `pm clear`, no user-data
  deletion at any point in this investigation.
- APKs (all three byte-verified before use):
  - fixed `d6f50206401950c2d502e60c3e6d0777fd1cbf116d823f232bc2fc062c58ef60`
    (#559 impl `54ce507496`, the same artifact hashed in the #559 proof),
  - baseline peer `936cca0dda198e81c1d78fb7b47dce64a9dd6d69ac07d127a14c76a28c909a6c`
    (build of `6595f22eac`; docs/spec-only diff to `f131e8e0`),
  - candidate2 priv-app base `5098ceb4d7af93c90684e07b1bdbe01408a92356631923fdde367a5fbc213278`
    (install `-r` updates layered on it; removed with `uninstall -k` at teardown).
- Full matrix, artifact hashes and per-run summaries: [proof.json](./563-home-empty-overview-evidence/proof.json).

## Reproduced event and antecedent

The original 2026-10-09 10:15 observation (fixed APK, after direct APP_SWITCH and gesture
verification had passed) is reproducible and the antecedent is now identified:

- **Antecedent: a completed app-origin overview session followed by the card-tap task
  launch in the same launcher process.** All three EMPTY runs completed the gesture entry
  with a card tap (F1/F2/F5); the direct entry also completed with a card tap in F1/F5,
  and in F2 the direct entry reached Overview ~4.3 s after the key (the +2.5 s dump raced
  it, so no tap) with its recents-animation cleanup overlapping the gesture step. The
  minimal component verified present in every EMPTY run is therefore a **completed
  gesture overview session with card-tap launch**; a direct-only antecedent (no gesture)
  was not run and remains an open control for the fix phase.
- Then `Settings -> HOME -> APP_SWITCH`: overview is reached
  (`LauncherActivityInterface.switchToRecentsIfVisible` -> `goToState Normal->Overview`,
  transition completes), but the screen shows no task cards: 0 `task_view_single` in all
  six checkpoints over 8 s, overview XML byte-identical to the original morning failure
  (sha256 `6285a51a433fb900f5609676fa0e4316b426c1bb61a62251c4cbed0c92b9fb17`), uniform
  background screenshot, and 0/0/0/0 wire signatures (no `unread size: 12`, no
  BadParcelableException, no FATAL, no 5000 ms pending timeout).
- Determinism on the fixed APK: **3/3 EMPTY (F1, F2, F5)** with a completed app-origin
  session; **PASS (F4)** when neither app-origin entry completes in the window. The clean
  HOME history control already stood at PASS/PASS both APKs (#559 paired control), and
  baseline BH1/BH3 second entries passed here as well.

## Baseline peer contrast (same input sequence, screen-level)

On the baseline peer the same input sequence cannot complete the app-origin entries
(direct fails with `unread12` x2 + 5000 ms pending x2; gesture also stays in Settings),
and the subsequent `HOME -> APP_SWITCH` **misfires**: the launcher itself runs
`TaskView: launchAsStaticTile - startActivityFromRecents: [855]` and the screen becomes
the SafetyCenter task (permissioncontroller XML, sha256 `f748f829…`). This is the same
misfire family the #559 independent QA recorded on candidate2 (B1/B3/B5: "runs immediately
following a failed app-origin run").

## Classification

**Product bug: pre-existing latent defect in the fork tree, exposed/unmasked by the #559
fix. Not #555.**

- Not harness: the failures are screen-level and each outcome is a launcher-initiated,
  logged action (`launchAsStaticTile` on baseline; Overview state entered but unpopulated
  on fixed). Inputs are plain keyevents/taps; the oracle dumps match the settled screen.
- Not platform: SystemUI delivers the HOME-origin toggle in both builds; the incorrect
  consumption happens inside the launcher.
- Relationship to the #559 fix, stated precisely: the candidate-latch code
  (RecentsView/overview state handling) is shared by baseline and fixed and is untouched
  by the wire change, so the empty-overview **mechanism** predates the fix (latent
  defect). The observed **screen-level change** — baseline misfires into a task launch,
  fixed lands in an empty overview — is attributable to the fix in the limited sense that
  the fix makes the app-origin overview session complete, which is the antecedent that
  reaches the latent defect. Because the baseline cannot complete that antecedent, this
  comparison does **not** prove the baseline would show the same empty overview under an
  equivalent completed antecedent; that equivalence remains untested (see open control
  above).

## Mechanism (2026-10-10 second session; deepened with diagnostic builds D2–D9)

Diagnostic builds with temporary instrumentation
(branch `issue-563-overview-recovery-fix`, runs D2/D5/D6/D7/D8/D9 — all reproduced EMPTY)
narrowed the mechanism substantially; the earlier L1/L2 candidates are superseded:

- **Refuted L1 (`mPendingAnimation` defer)**: the diagnostic never observed a
  `mPendingAnimation` creation/defer/clear in the failing window. `applyLoadPlan` runs and
  binds all five task groups on the stuck entry; nothing is deferred.
- **Refuted "page/scroll desync"**: `getScrollForPage(0) == 3465` is *correct* — child 0 is
  the most-recent task (879) laid at x=3627, so scroll 3465 centers it on screen. The pager
  geometry is internally consistent.
- **Observed (strong inference, not yet directly instrumented): the visible-data pipeline
  never feeds the on-screen cards.** In the stuck entry the bound task views are VISIBLE,
  alpha 1, correctly positioned, but thumbnail state settles as `thumb=true` for the
  OFF-SCREEN tasks 844/843 and `thumb=false` for the ON-SCREEN 879/855/848. That loaded set
  matches the visible range computed with **primary scroll 0** instead of the actual 3465
  (`enableRefactorTaskThumbnail()` is hardcoded `true`, so this range drives
  `RecentsViewModel.updateVisibleTasks` — the card content pipeline). The primary-scroll-0
  reading is an inference from the loaded-set inversion; directly logging the computed set
  and `getPrimaryScroll` inside `loadVisibleTaskData` remains the outstanding next step. An
  unloaded `TaskView` renders nothing (transparent card) — matching the uniform background
  screenshot — and the overview_panel subtree is correspondingly absent from the
  accessibility dump while the actions bar (sibling) is present. `dispatchDraw` fires at
  entry and then the screen is static.
- **In-place interventions that do NOT heal** (five fix variants, all built, installed and
  measured against the oracle — all still EMPTY): `loadVisibleTaskData(FLAG_UPDATE_ALL)`
  re-runs at three hook points (`onStateTransitionComplete` postOnAnimation, end of
  `applyLoadPlan` postOnAnimation, `onPageScrollsInitialized` override), `requestLayout()`
  on entry, and `updateOrientationHandler(forceRecreate=true)` on entry.
- **Operations that DO heal (in place, same process)**: (a) any shell-animated app-origin
  entry (launch any app from HOME, then APP_SWITCH — cards render immediately,
  `heal-apporigin-entry.xml`); (b) a configuration change (rotation with in-place
  `configChanges` handling — no activity recreation — heals and keeps the subsequent toggle
  entries healthy, `heal-config-change.xml`). Neither launcher force-stop-free gesture,
  swipe, nor an in-place HOME→APP_SWITCH retry heals.
- Upstream prior art: upstream `16-dev` has zero commits touching
  `RecentsView.java` after the ADR-0018 anchor (re-verified 2026-10-10); the defect exists
  in the vendored 16-dev code as-is.

The remaining unknown is which exact input of the healing paths (orientation-state refresh,
DeviceProfile update, or `RecentsViewModel` payload) resets the pipeline. The next
diagnostic step is instrumentation at the pipeline boundary: log the computed visible set
and `getPrimaryScroll` inside `loadVisibleTaskData`, and the payload delivered to
`RecentsViewModel.updateVisibleTasks`, plus a uiMode config-change heal test to separate
orientation refresh from generic profile refresh. **Per the STOP rule, no production change
is shipped: five candidate fixes were built and measured and none passed the oracle.**
Provenance of the attempts (implementation snapshots, not proposed for merge): the
instrumentation plus fix v2 snapshot is `issue-563-overview-recovery-fix@d4a1cd50aa`; the
spec draft with the cumulative v3+v5 snapshot is `issue-563-overview-fix@6f7c7fcd30`; the
v1 and v4 implementation snapshots were amended away and are not durably preserved. The
selected run artifacts are published in this evidence directory.

## Second-session continuation (2026-10-10 evening; instrumentation D- and V-runs)

Pipeline-boundary instrumentation was applied on the investigation branch
(`issue-563-overview-fix`, DIAG563b/DIAG563c/DIAG563d logs) and four further fix
candidates were built, installed and measured against the oracle:

- **v6** — `TaskThumbnailCache`: skip caching empty snapshots and unconditionally call
  `takeTaskThumbnail` when the fetched snapshot is empty. Oracle still EMPTY.
- **v7** — `RecentsView.loadVisibleTaskData`: clamp the centered page into the real task
  page range when `getPageNearestToCenterOfScreen()` resolves to the trailing ClearAll
  child (instrumentation confirmed it does in the stuck entry). Oracle still EMPTY.
- **v7b** — additionally widen the computed range to always include the running task's
  page. Oracle still EMPTY.
- **v8** — combined clamp + widen + empty-snapshot-skip. Oracle still EMPTY.

Directly established facts from the instrumentation (proof.json `thirdSession`):

1. Repository state: every overview exit clears the three recent tasks' thumbnails
   (`removeTasks` sets thumbnail=null); the next entry re-issues
   `updateTaskRequests needs=[879,1005,855]` with the correct visible set, and
   `getAllTaskData` refreshes.
2. `TaskThumbnailCache` suspend fetch ran once early in the launcher's life
   (`id=879 lowRes=true -> bitmap=false`; `1005/855 -> bitmap=true`) and **never runs
   again on subsequent entries** even though `requestTaskData` fires — the fetch lane goes
   dead after the request identity was previously requested-and-cancelled.
3. `TaskViewModel.bind` fires correctly with `[879]` in the stuck entry, but the tile state
   flow does not re-emit a thumbnail-bearing state after the earlier
   bind([])->bind([879]) cycle.

Consequently the remaining defect sits between `TasksRepository` (request/cancel identity
handling, `tasks` flow emission) and `TaskViewModel` (debounced tile state flow) — a fix
must restore the emission chain after request cancellation, which is beyond the minimal
fix scope of this investigation session. **Per the STOP rule no production change is
shipped: nine candidate fixes (five earlier + four here) were built and measured against
the oracle; none passed.** Investigation branches: `issue-563-overview-fix` (cumulative
fix+instrumentation state) and `issue-563-overview-recovery-fix` (per-attempt history).
Selected run artifacts (DD/DD-state probe summaries and proof.json `thirdSession`) are
published in this evidence directory.

## Bug oracle (established; gates any future production change under #563)

On the fixed APK (`d6f502064…`) on this provider: force-stop launcher -> launch Settings ->
gesture UP to overview -> verify >=1 `task_view_single` in the uiautomator dump -> tap
(540,1200) to return -> HOME key -> 10 s dwell -> APP_SWITCH -> dumps at 0/0.5/1/3/6/8 s.
The oracle FAILS when every dump shows 0 `task_view_single` while the settled state is
Overview (expected: >=1 card within 1 s). RED 3/3 (F1/F2/F5); controls: F4 PASS on fixed
without a completed app-origin session; BH1/BH3 PASS on baseline; #559 paired control
PASS/PASS both APKs on clean HOME history.

## Related defects observed during harnessing (not classified under #563)

1. **Launcher cold-start deadlock (fork defect, separate issue)**:
   `LawnchairLayoutFactory.fontManager` lazy initialization on a `ViewPool-init` thread
   blocks in `MainThreadInitializedObject.get()` (waiting for main-thread initialization)
   while holding the lazy lock the main thread needs for inflation -> launcher ANR / wedged
   start. ANR trace excerpt retained in
   [anr/cold-start-deadlock-anr-trace.txt](./563-home-empty-overview-evidence/anr/cold-start-deadlock-anr-trace.txt).
   Observed 2026-10-09 23:45 – 2026-10-10 00:16 (one full ANR with dropbox trace; three
   further wedged restarts).
2. **Transient `SystemUiProxy.mRecentTasks` null bind race** on young launcher processes:
   `getRecentTasks() failed due to null mRecentTasks` -> `RecentTasksList` returns
   `INVALID_RESULT` -> one `applyLoadPlan - taskGroups: []` application (F1 23:30:43.943).
   Recoverable by later loads; not the proximate cause of the stuck empty state.
3. **SystemUI-side TIS rebind gap** after launcher restart: overview toggle presses during
   the gap are dropped (BH5/BH6: no `onOverviewToggle` in the window). Restores by waiting
   or reinstalling the launcher. Harness-relevant; recorded as environment behavior.

## Reproduction commands (as executed; adb = platform-tools 37.0.1)

```text
adb -s emulator-5556 install -r <fixed.apk>            # sha256 d6f502064…
adb -s emulator-5556 shell am force-stop app.lawnchair.debug
adb -s emulator-5556 shell input keyevent KEYCODE_HOME   # + settle wait
adb -s emulator-5556 shell am start -n com.android.settings/.homepage.SettingsHomepageActivity
adb -s emulator-5556 shell input touchscreen motionevent DOWN 540 2388
adb -s emulator-5556 shell input touchscreen motionevent MOVE 540 2340
adb -s emulator-5556 shell input touchscreen motionevent MOVE 540 1920
adb -s emulator-5556 shell input touchscreen motionevent MOVE 540 1440
adb -s emulator-5556 shell input touchscreen motionevent UP 540 1440
adb -s emulator-5556 shell uiautomator dump /data/local/tmp/x.xml   # expect task_view_single >= 1
adb -s emulator-5556 shell input tap 540 1200                       # card tap -> return
adb -s emulator-5556 shell input keyevent KEYCODE_HOME              # + 10 s dwell
adb -s emulator-5556 shell input keyevent KEYCODE_APP_SWITCH
# dumps at 0/0.5/1/3/6/8 s: oracle FAIL = 0 task_view_single throughout, state Overview
adb -s emulator-5556 shell dumpsys activity top    # clear_all at focused x=162, cards at x>=435
```

Driver + raw run directory (events.jsonl per run, all checkpoints, PNGs) were kept in
session scratch; only the selected set under `563-home-empty-overview-evidence/` is
published (per-file sha256 values in
[proof.json](./563-home-empty-overview-evidence/proof.json); same selection policy as
#559).

## Next step

Production fix under #563 proceeds only with this oracle plus independent review. The
first fix session (2026-10-10) built five candidate fixes against the oracle; none passed
(see Mechanism). Before a new spec can be accepted, the pipeline-boundary diagnostic must
identify why the no-shell-animation entry computes the visible set with primary scroll 0:
instrument `loadVisibleTaskData` (computed set + `getPrimaryScroll`) and
`RecentsViewModel.updateVisibleTasks` payload, and test a uiMode config change to separate
orientation refresh from DeviceProfile refresh. The two open antecedent controls
(direct-only session; an equivalent completed antecedent that does not depend on the #559
wire path) remain outstanding. Attempt provenance is recorded above in Mechanism.
No production change is included in this record.
