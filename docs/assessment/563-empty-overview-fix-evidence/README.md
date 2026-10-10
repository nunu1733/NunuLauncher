# Issue #563 empty-overview fix evidence (bisection session 2026-10-11)

Refs #563. Fix PR head: branch `issue-563-empty-overview-fix` (head `80203dc80f` and
successor review-fix commits; base `issue-559-api36-recents-compat` @ `63d15f00dc`,
implementation base `54ce507496`). Investigation branches: `issue-563-bisect` @
`5762b022d2` (+ probe-extension commit). Supersedes the three earlier sessions' mechanism
record (`docs/assessment/issue-563-api36-home-empty-overview.md` on main, PR #566/#568/#569):
the "suspend fetch dead lane" and "TasksRepository/TaskViewModel emission veto" conclusions
are retired — fetches, repository requests and tile-state emissions all run normally in the
stuck entry; the earlier instrumentation could not observe the data-source fast paths
(silent) or the render/accessibility layer at all.

## Device identity

emulator-5556 (AVD `issue142_api36`), fingerprint
`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`,
GLOBAL animation scales 0/0/0, gesture nav, provider overlay `nunu.overlay.quickstepconfig`.
No AVD wipe, no `pm clear`. Emulator reboots (no data reset) occurred twice after the known
#565-family cold-start ANR wedged the launcher; fingerprint re-verified after each.

## Root cause (bisection-decided)

Three stacked defects, all required for the observed empty overview:

1. **Unset render pivot (NaN)** — a panel bounds change while the previous overview session
   exits resets the framework pivot; the HOME-toggle re-entry path never calls
   `updatePivots()`. Observed: stuck-entry `pivotX=NaN pivotY=NaN` vs first-entry
   `pivotX=540 pivotY=1200` with otherwise identical geometry
   (`logs/BB4-nan-discovery-extract.txt`; BB4 run on the probe build).
   - `PagedView.getScreenCenter` → `Math.round(NaN)=0` → `getPageNearestToCenterOfScreen()`
     resolves to the trailing ClearAll pseudo-page → `loadVisibleTaskData` requests the wrong
     visible set (`setVisibleTasks ids=[844, 843] range=[3,5]` instead of `[879, 1005, 855]`).
   - The NaN transformation matrix poisons `boundsInScreen` for the whole panel subtree →
     uiautomator/TalkBack drop the overview panel from the accessibility tree. Proven by run
     FF1 (NaN guard + TaskContainer repairs, before the re-pivot fix): card content RENDERED
     (pixel analysis below) while every a11y dump still had 0 `task_view_single` nodes.
2. **Tile states never applied (559-stack-only)** — `TaskContainer.setState` (and
   `bind/destroy/refreshOverlay`) were expression-body lambdas on this stack; the fixed
   block bodies exist on main (#555 PR #561, #562 PR #567) but predate the stack. Proven by
   run FE4 (NaN guard, probe build, before the TaskContainer repairs): states delivered
   (`state tasks=[1008:true] central=true`, `logs/FE4-flip-and-states-extract.txt`) and the
   panel drew (dispatchDraw fired, `logs/FD8-dispatchdraw-extract.txt`) while pixels stayed
   uniform. This alone made all nine earlier fix candidates untestable.
3. No "dead" repository/fetch/emission lane: `suspendgot id=844/843 bitmap=true` at a stuck
   entry (BA1), `updateThumbnail` fires, `TaskViewModel` re-emits thumbnail-bearing states
   (FE4 extract above).

### FF1 pixel measurement (a11y absence with rendered content)

Input `xml/FF1` dumps (0 nodes) and the FF1 stuck screenshot analyzed with stdlib PNG decode
(zlib inflate + unfilter), sampling the card region x=100..1000, y=600..1800:

```
distinct colors: 659   top: (56,57,63) 64%, (30,31,37) 13%, (68,76,97) 11%
```

vs the pre-fix uniform stuck screen (`png/FD5-RED-*` from the earlier session, same method:
distinct 1..3, 99-100% single scrim color). The uniform→content transition appeared exactly
when the TaskContainer block-body repairs landed; a11y stayed at 0 until the re-pivot fix
(NaN pivot → NaN boundsInScreen), then recovered to 2 nodes (FG8/FG9/FG10).

## Fix set (this PR)

| commit | content |
|---|---|
| `c03ece9b1b` (cherry-pick of main `4a8508498b`, PR #561) | `TaskContainer.setState` block body |
| `965c2eb32f` (cherry-pick of main `a37af58430`, PR #567) | `TaskContainer.bind/destroy/refreshOverlay` block bodies |
| `c116160796` (cherry-pick of main `7a8ef68fdd`, PR #567) | flag-aware `isRealSnapshot()` in `initOverlay` |
| `0a6b3061fd` (cherry-pick of main `f93d29a619`, PR #567) | `initOverlay` disabled-flag guard |
| `b11f174efb` (new, #563) | `RecentsView.applyLoadPlan` re-derives pivot (`updatePivots()`); `PagedView.computeScreenCenter` NaN guard (+ `tests/unit` regression owner) |
| review round 2 | `TaskContainer` side-effect methods annotated `: Unit` so a reintroduced `= { ... }` lambda is a compile error |

## Bug oracle (standard driver: `oracle-driver.sh`)

Criterion: after `force-stop → Settings → gesture UP → card tap → HOME → 10s dwell →
APP_SWITCH`, the uiautomator dump at each checkpoint 0/0.5/1/3/6/8s must contain
`>= 1` `task_view_single` node (`grep -o | wc -l` node counting; the XML is single-line so
`grep -c` would count lines). RED = all checkpoints 0 while overview is the settled state.

| run | build | gesture entry | 0s | 0.5s | 1s | 3s | 6s | 8s | verdict |
|---|---|---|---|---|---|---|---|---|---|
| BB1 | clean stack `54ce507496` (probe build) | 1 (grep -c) | 0 | 0 | 0 | 0 | 0 | 0 (node counts: `xml/BB1-RED-node-counts.txt`) | **RED** |
| FG7 | full fix @HEAD | 0 | 0 | 0 | 0 | 0 | 0 | 0 | **excluded** — TIS rebind gap dropped the toggle (`xml/FG7-events-excluded-TISdrop.log`: "Skipping connection to TouchInteractionService", zero `goToState` in logcat); antecedent never ran |
| FG8 | full fix @HEAD | 0 (session completed as live tile) | 2 | 2 | 2 | 2 | 2 | 2 | **GREEN** |
| FG9 | full fix @HEAD | 2 | 2 | 2 | 2 | 2 | 2 | 2 | **GREEN** |
| FG10 | full fix @HEAD | 2 | 2 | 2 | 2 | 2 | 2 | 2 | **GREEN** |
| FC2 | control: clean HOME history, direct APP_SWITCH (no antecedent) | n/a | 2 (single dump at 2s) | | | | | | PASS |

GREEN 3/3 (FG8/FG9/FG10) with full per-checkpoint dumps, events and screenshots under
`xml/` and `png/`; the original 2026-10-11 morning RED baseline is BA1/BB1/BB3 (BB1
retained here with node counts). Card tap after the re-entry returned to the task (FC2).

Unit regression: `com.android.launcher3.PagedViewScreenCenterTest` — with the guard removed,
`nan pivot resolves to the view center` / `nan pivot with scale resolves to the view center`
fail with `expected: 4005/1540 but was: 0`; 4/4 GREEN with the guard. The TaskContainer
expression-body failure mode is protected structurally (`: Unit` annotations make
`= { ... }` a compile error) rather than by a unit owner; recorded in the PR test-audit note.

## Selected artifacts

- `logs/BB4-nan-discovery-extract.txt` — decisive geometry: same scroll/children, stuck entry
  `pivotX=NaN` + `screenCenter=0` + `centerPage=5` vs first entry `pivot=540/1200` +
  `screenCenter=4005` + `centerPage=0` (probe build).
- `logs/FD8-dispatchdraw-extract.txt` — draw timeline on the pre-TaskContainer-fix build:
  the panel draws at first entry and exit; no draw at the stuck entry until the visibility
  flip proven in FE4.
- `logs/FE4-flip-and-states-extract.txt` — INVISIBLE→VISIBLE flip + dispatchDraw at the
  stuck entry, states delivered (`[1008:true] central=true`), pixels still uniform →
  TaskContainer no-op proof.
- `logs/FF1-states-extract.txt` — TaskView bind sequence of the FF1 build (probe-free; role
  described above).
- `xml/FG{8,9,10}-*` — full oracle dumps (gesture entry + 6 checkpoints) and events logs.
- `png/FG{8,9,10}-stuck-8s.png`, `png/FC2-direct-entry.png` — post-fix screens.
- `xml/BB1-RED-node-counts.txt` — RED baseline node counts from the stored BB1 dumps.
- `oracle-driver.sh` — the exact standard driver used for FG7–FG10.
- `manifest-sha256.txt` — per-file hashes of all other files (the manifest excludes itself;
  verify with `shasum -a 256 -c manifest-sha256.txt` from this directory).

## Environment notes

- `am force-stop` sequences intermittently hit the known #565 cold-start deadlock (ANR,
  launcher wedged; fixed on main by PR #570, not on this stack) and the SystemUI TIS rebind
  gap ("Skipping connection to TouchInteractionService", documented in the main assessment
  record) which drops toggles for a while after a force-stop. Affected runs (FE1/FE2/FE3,
  FG2/FG3, FG7) are excluded with the observable reason recorded; they do not affect the
  verdict runs above, which all show the launcher-side `goToState` transitions in their
  logcat.
