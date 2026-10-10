# Issue #563 empty-overview fix evidence (bisection session 2026-10-11)

Refs #563. Fix PR head: branch `issue-563-empty-overview-fix`
(base `issue-559-api36-recents-compat` @ `63d15f00dc`, implementation base `54ce507496`).
Investigation branches: `issue-563-bisect` @ `5762b022d2` (+ probe-extension commit).
Supersedes the three earlier sessions' mechanism record
(`docs/assessment/issue-563-api36-home-empty-overview.md` on main, PR #566/#568/#569):
the "suspend fetch dead lane" and "TasksRepository/TaskViewModel emission veto" conclusions
are retired — fetches, repository requests and tile-state emissions all run normally in the
stuck entry; the previous instrumentation simply could not observe the fast paths and the
render/accessibility layer.

## Device identity

emulator-5556 (AVD `issue142_api36`), fingerprint
`google/sdk_gphone64_arm64/emu64a:16/BE2A.250530.026.F3/13894323:userdebug/dev-keys`,
GLOBAL animation scales 0/0/0, gesture nav, provider overlay `nunu.overlay.quickstepconfig`.
No AVD wipe, no `pm clear`. Two emulator reboots (no data reset) during the session after the
known #565-family cold-start ANR wedged the launcher; fingerprint re-verified after each.

## Root cause (bisection-decided)

Three stacked defects, all required for the observed empty overview:

1. **Unset render pivot (NaN)** — a panel bounds change while the previous overview session
   exits resets the framework pivot; the HOME-toggle re-entry path never calls
   `updatePivots()`. Observed: stuck-entry `pivotX=NaN pivotY=NaN` vs first-entry
   `pivotX=540 pivotY=1200` with otherwise identical geometry (`BB4-nan-discovery-extract.txt`).
   - `PagedView.getScreenCenter` → `Math.round(NaN)=0` → `getPageNearestToCenterOfScreen()`
     resolves to the trailing ClearAll pseudo-page → `loadVisibleTaskData` requests the wrong
     visible set (`setVisibleTasks ids=[844, 843] range=[3,5]` instead of `[879, 1005, 855]`).
   - The NaN transformation matrix poisons `boundsInScreen` for the whole panel subtree →
     uiautomator/TalkBack drop the overview panel from the accessibility tree
     (0 `task_view_single` in every stuck dump; overview_actions sibling unaffected).
2. **Tile states never applied (559-stack-only)** — `TaskContainer.setState` (and
   `bind/destroy/refreshOverlay`) are expression-body lambdas on this stack; the fixed bodies
   exist on main (#555 PR #561, #562 PR #567) but predate the stack. Even with a correct
   visible set the cards render nothing (`FF1-setstate-extract.txt`: `[879:true]` delivered,
   no pixels). This alone made all nine earlier fix candidates untestable.
3. No "dead" repository/fetch/emission lane: `844/843 suspendgot bitmap=true` at the stuck
   entry (BA1), `updateThumbnail` fires, `TaskViewModel` re-emits thumbnail-bearing states
   (`state tasks=[879:true] central=true`).

## Fix set (this PR)

| commit | content |
|---|---|
| `c03ece9b1b` (cherry-pick of main `4a8508498b`, PR #561) | `TaskContainer.setState` block body |
| `965c2eb32f` (cherry-pick of main `a37af58430`, PR #567) | `TaskContainer.bind/destroy/refreshOverlay` block bodies |
| `c116160796` (cherry-pick of main `7a8ef68fdd`, PR #567) | flag-aware `isRealSnapshot()` in `initOverlay` |
| `0a6b3061fd` (cherry-pick of main `f93d29a619`, PR #567) | `initOverlay` disabled-flag guard |
| `b11f174efb` (new, #563) | `RecentsView.applyLoadPlan` re-derives pivot (`updatePivots()`); `PagedView.computeScreenCenter` NaN guard (+ `tests/unit` regression owner) |

## Oracle matrix (driver: `oracle-driver.sh`; criterion: `task_view_single` count)

| run | build | gesture entry | stuck re-entry | verdict |
|---|---|---|---|---|
| BA1 | v8 (previous session, no fix) | 1 | 0 at 0..8s (6/6 dumps) | RED |
| BB1 | clean stack `54ce507496` | 1 | 0 (6/6) | RED |
| BB3 | clean stack | 1 | 0 | RED |
| FG4 | full fix set | 1 | **1 @2s, 1 @8s** | GREEN |
| FG5 | full fix set | 1 | **1 @2s** | GREEN |
| FG6 | full fix set | 1 | **1 @2s** | GREEN |
| FC1 | full fix set (control: direct APP_SWITCH, no antecedent) | n/a | **1** | PASS |

Unit regression: `com.android.launcher3.PagedViewScreenCenterTest` — with the guard removed,
`nan pivot resolves to the view center` / `nan pivot with scale resolves to the view center`
fail with `expected: 4005/1540 but was: 0`; 4/4 GREEN with the guard.

## Selected artifacts

- `logs/BB4-nan-discovery-extract.txt` — decisive geometry: same scroll/children, stuck entry
  `pivotX=NaN` + `screenCenter=0` + `centerPage=5` vs first entry `pivot=540/1200` +
  `screenCenter=4005` + `centerPage=0`.
- `logs/FD8-dispatchdraw-extract.txt` — draw timeline: panel draws at first entry and exit
  only (supersedes the stale-display-list theory; see FE4 in the bisect branch log for the
  INVISIBLE→VISIBLE flip at the stuck entry).
- `logs/FF1-setstate-extract.txt` — states delivered (`tasks=[879:true]`) while pixels stay
  empty on the NaN fix alone; the setState cherry-pick turns the card region from uniform to
  659-color content.
- `png/FD5-RED-*.png` — pre-fix first entry (card rendered) vs stuck entry (uniform scrim).
- `png/FG5-stuck-fixed.png`, `png/FG6-stuck-fixed.png`, `png/FC1-direct-entry.png` —
  post-fix stuck re-entry and direct-entry control (card region rendered).
- `xml/*.xml` — oracle dumps for the matrix above.
- `manifest-sha256.txt` — per-file hashes.

## Environment notes

- Two `am force-stop` sequences hit the known #565 cold-start deadlock (ANR dialog,
  launcher pid wedged) — recovered by reboot; this defect is fixed on main (PR #570) and is
  not part of the 559 stack. It delays testing but does not affect the oracle verdicts.
- The TIS rebind gap (assessment main record §related defects) dropped toggles during two
  runs (FE1/FE2-class); those runs are excluded (antecedent not completed), matching the
  previous sessions' F4-class classification.
