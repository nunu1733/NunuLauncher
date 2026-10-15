# Issue #565 cold-start fontManager lazy deadlock: fix evidence

Status: fix implemented, deterministic RED→GREEN + natural restart batches recorded.
Refs #565. Oracle: `docs/assessment/563-home-empty-overview-evidence/anr/cold-start-deadlock-anr-trace.txt`
@ commit `b043c85be580847422c4b4057443cdf64e3e2830` (dropped in PR #566; present at base HEAD
`0c737bd45c9f592922c09bbf3ca25a48f3e874a2`). Issue body (owner acceptance of root cause + exit
criteria) retrieved 2026-10-10T12:45Z: https://github.com/nunu1733/NunuLauncher/issues/565

## Environment (QA-created, torn down at PR end)

- AVD `issue108_api36_pixel_tablet` (headless, own instance on `emulator-5558`,
  system image `android-36.1/google_apis/arm64-v8a` — same API 36 family as the reporting AVD).
  Reporting AVD `issue142_api36` was the user's live instance (emulator-5556) — never touched
  destructively; only read-only `pm list packages` / `pm path` / `adb pull /product/overlay/...apk`
  were used to identify the reproduction condition below.
- Reproduction condition discovered: Lawnchair only runs the quickstep RecentsView/ViewPool
  preinflation path when `android:config_recentsComponentName` points at its own
  `com.android.quickstep.RecentsActivity`. On the reporting AVD this comes from the static RRO
  `nunu.overlay.quickstepconfig` (`/product/overlay/nunu-overlay-quickstepconfig.apk`,
  `isStatic=true`, priority 13, STATE_ENABLED, mapping
  `string/config_recentsComponentName -> app.lawnchair.debug/com.android.quickstep.RecentsActivity`).
  With the RRO absent, `LawnchairApp.checkRecentsComponent` logs `disabling recents` and no
  ViewPool-init threads spawn (confirmed in logcat and `ps -T`). The QA instance was therefore
  relaunched `-writable-system`, the pulled RRO pushed to `/product/overlay/`, rebooted;
  `cmd overlay list android` shows `[x] nunu.overlay.quickstepconfig`; 3 `ViewPool` threads
  observed during cold start after the change; Lawnchair made default HOME via
  `cmd role add-role-holder --user 0 android.app.role.HOME app.lawnchair.debug`.
- Global animation scales set to 0 (matching the #563 harness record).

## APK identities

| role | sha256 | source |
|---|---|---|
| pre-fix natural | `b4191ba2b42258c651b3a39396a9119963c2705d0d6b1ce2cb9de1b47c5b192a` | `lawnchair/src/app/lawnchair/LawnchairLayoutFactory.kt` checked out at `0c737bd45c` (lazy kept), assembled `lawnWithQuickstepGithubDebug` |
| pre-fix natural (initial build) | `517e9d449cd9de88b9b2c841c3c02616d53e49ba5a7d61809a136c8660f90597` | build of clean `0c737bd45c` |
| post-fix | `e7a45ca96430e48d36eb594f28b354ebd50b04fefe052329e1bc879f42622b6c` | fixed factory (`28b5800a88` content), assembled from branch HEAD |

## Deterministic device RED (oracle-signature)

Command (production code = pre-fix):

```text
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.startup.ColdStartFontLazyInversionInstrumentationTest
```

Result: `a1ColdConcurrentFontAccessNeverBlocksASecondCallerOnAMonitor` FAILED with
`REGRESSION #565: second factory caller is BLOCKED on a monitor held across the main-thread init
wait` — captured verbatim in [red-dump-api36.txt](./565-cold-start-deadlock-evidence/red-dump-api36.txt);
full console in [logs/red-run-gradle-console.log](./565-cold-start-deadlock-evidence/logs/red-run-gradle-console.log).
Caller-1 frame chain `MainThreadInitializedObject.get:66 <- fontManager_delegate$lambda$0:18 <-
SynchronizedLazyImpl.getValue:86` (monitor held, parked) and caller-2 `SynchronizedLazyImpl.getValue:81`
(waiting to lock) match the retained oracle threads (`ViewPool-init` tid=32 `locked` + park;
`main` `waiting to lock` Blocked). `a0`/`bWarm`/`cFactory` passed on the pre-fix build too (the
failure is exactly the held-across-wait inversion).

## Deterministic device GREEN (same class, post-fix)

```text
issue108_api36_pixel_tablet(AVD) - 16 Tests 4/4 completed. (0 skipped) (0 failed)
BUILD SUCCESSFUL
```

Run on the QA instance **with** the quickstep RRO enabled (production code = `28b5800a88`):
[logs/green-run-overlay-enabled.txt](./565-cold-start-deadlock-evidence/logs/green-run-overlay-enabled.txt);
first pre-RRO run [logs/green-run-summary.txt](./565-cold-start-deadlock-evidence/logs/green-run-summary.txt).

## Natural cold-start restart batches

`am force-stop app.lawnchair.debug` -> `am start -a android.intent.action.MAIN -c
android.intent.category.HOME` -> settle 8-10s -> verdict = `mCurrentFocus` +
`uiautomator dump` + dropbox `data_app_anr` delta + `/data/anr` listing.

| batch | config | trials | wedge | ANR |
|---|---|---|---|---|
| [pre-fix fresh state (no RRO)](./565-cold-start-deadlock-evidence/logs/pre-fix-5558-freshstate-12.txt) | recents disabled | 12 | 0 | 0 |
| [pre-fix fresh state 2](./565-cold-start-deadlock-evidence/logs/pre-fix-5558-freshstate-batch20.txt) | recents disabled | 20 | 0 | 0 |
| [pre-fix RRO enabled passive](./565-cold-start-deadlock-evidence/logs/pre-fix-overlay-enabled-passive-24.txt) | recents active | 24 | 0 | 0 |
| [pre-fix RRO enabled + swipe-into-overview](./565-cold-start-deadlock-evidence/logs/pre-fix-overlay-enabled-swipe-24.txt) | recents active | 24 | 0 | 0 |
| [post-fix RRO enabled mixed](./565-cold-start-deadlock-evidence/logs/post-fix-overlay-enabled-24.txt) | recents active | 24 | 0 | 0 |
| post-fix recents disabled (earlier) | recents disabled | 20 | 0 | 0 |

Constraint recorded honestly: the pre-fix intermittent wedge (owner-reported ~1/2 per restart on
`issue142_api36` during the #563 harness, 4 events in 17 min) did **not** self-fire on the QA
tablet AVD in 80 natural restarts even with the RRO + HOME role + overview swipes — a fresh,
sparsely populated home changes the inflate ordering/timing that makes the window observable.
The regression proof therefore rests on: (1) the deterministic device test at the production seam
(RED pre-fix / GREEN post-fix, exact oracle signature, same AVD class API 36), and (2) post-fix
absence of any `data_app_anr` / `/data/anr` entry across natural batches, plus owner's original
pre-fix trace and rate. No trace is fabricated; none was expected post-fix.

## Adjacent surface (post-fix, RRO enabled, default HOME)

- [png/postfix-home-overlay.png](./565-cold-start-deadlock-evidence/png/postfix-home-overlay.png)
  — home rendered: `uiautomator dump` = 33 nodes, `mCurrentFocus` =
  `app.lawnchair.debug/app.lawnchair.LawnchairLauncher`, ids include `id/hotseat`,
  `id/drag_layer`, `id/launcher`, smartspace; no ANR/error window.
- [png/postfix-overview.png](./565-cold-start-deadlock-evidence/png/postfix-overview.png)
  — overview opened via swipe-up from the same session (ViewPool consumer surface).
- (earlier recents-disabled reference shots: `postfix-home.png`, `postfix-recents.png`.)

## Static gates (post-fix)

```text
./gradlew spotlessCheck                                        -> PASS
./gradlew compileLawnWithQuickstepGithubDebugKotlin            -> PASS
./gradlew assembleLawnWithQuickstepGithubDebug                 -> BUILD SUCCESSFUL
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' -> PASS
python3 tools/repo-contract/validate_ci_portfolio.py           -> CI portfolio validation OK
python3 tools/repo-contract/validate_repo_contract.py          -> repository contract OK
python3 tools/repo-contract/validate_high_risk_evidence.py ... -> (low-risk: not required; PR labels none)
```

## Teardown record

QA instance `emulator-5558` (self-created) is shut down after evidence capture; the pushed RRO
lives only in that AVD's qcow2 overlays (`/product/overlay/nunu-overlay-quickstepconfig.apk`,
`-writable-system` temp mount — discarded with the instance). The user's live emulators
(5554/5556) and all user untracked files (`evidence-r1-tmp/`, `evidence-work/`,
`vmruntime_java.java`, `worktree-*` dirs other than `worktree-565/`) were not modified.
No `pm clear`, no data deletion on any device.

## Affected-version range (Issue exit criterion 2)

Evidence-backed, exactance/history (commands: `git log -S`, `git merge-base --is-ancestor`,
`git show`):

- The deadlock line `private val fontManager by lazy { FontManager.INSTANCE.get(context) }` in
  `LawnchairLayoutFactory.kt` traces to upstream `a7c69072b7` (2021-10-20, "Migrate view overrides
  to Factory2"), removed by `d2f4294aeb`, re-introduced by `7767d76320` (2022-07-17, revert); both
  are ancestors of the fork product baseline `505dbc40e6...` (Lawnchair `v15.0.0-beta3.0`).
  `git show 0c737bd45c:...LawnchairLayoutFactory.kt` and `git show 6595f22:...` both contain it;
  the oracle ANR fired on the #559 fixed-APK tree `54ce5074` build. The background `ViewPool-init`
  preinflation exists since upstream `d1a67d0d7238` (2019-11-11, AOSP "Preventing dead lock in
  layout inflation", Bug 143353100) — present through the baseline and HEAD.
- As of 2026-10-10 the pattern is still present on upstream Lawnchair `16-dev`
  (GitHub code search result `blob/16-dev/lawnchair/src/app/lawnchair/LawnchairLayoutFactory.kt`
  and local mirror `upstream/16-dev @ 4793ba3a238b...`).

=> Range: all NunuLauncher builds from the fork baseline (`505dbc40e6...`, v15.0.0-beta3.0) through
main `0c737bd45c` (and the 16-dev stack, plus any upstream Lawnchair build carrying
`7767d76320`..HEAD). Condition: only observable when the quickstep recents path is active
(`config_recentsComponentName` RRO pointing at Lawnchair's RecentsActivity — the shipping
configuration on real devices) and the ViewPool-init preinflation races main's first factory
font resolution during cold start; API/level not a gate (mechanism = Kotlin lock + Looper
ordering). Intermittent (timing-dependent): ~1/2 per restart on the reporting AVD, 0/80 on a
fresh sparsely-populated tablet AVD.
