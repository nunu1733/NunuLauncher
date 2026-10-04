# Issue 137 proposal touch activation — fix verification evidence

> Status: Durable redacted subset
> Date: 2026-08-25 (updated after PR #144 review)
> Fix commit: `3cc9cb742b` (`fix(onboarding): activate proposal actions on the first touch`) plus review-response commits on `issue-137-proposal-touch-activation`
> Test commit: `b00b9802fc` (`test(onboarding): exercise proposal actions with real touch streams`)
> Release APK (review-response head): `Lawnchair.15.Dev.(454670a).github.release.apk`
> APK SHA-256: `339a44df32426371d36b3e5bbe0dd19bd47e1a94d6a3211a2b6da9663b2a8cc5`
> Earlier pre-review artifact: `Lawnchair.15.Dev.(3cc9cb7).github.release.apk`,
> SHA-256 `1f16426be8d090acbb179c4a37aba05fc1ced175fb3c9dfe6bb74724922516e3`
> Runtime: `nunu_qpr2_api36_1` AVD (Pixel 6 definition, Google APIs arm64-v8a),
> Android 16 / API 36, portrait 1080x2400 @ 420 dpi; cross-check run on
> `issue108_api36_pixel_tablet` AVD (API 36).
> All layouts shown are the emulator's synthetic/default workspace. No personal
> user layout is included.

## 1. Phase 0 go/no-go gate record (pre-fix, debug build)

Instrumented real-touch injection (`UiAutomation.injectInputEvent`, never
`performClick()`) against the real launcher floating host recorded, for both
`Later` and `Skip`:

```text
[tap 1] focusBefore=title focusAfter=laterButton events=Later:DOWN, Later:UP
        open=true outcome=null
[tap 2] action fired, proposal closed
```

Gate conclusions:

- The debug build reproduces the defect class: the first delivered tap is
  consumed as a focus change and the click never runs; the action fires on
  the second tap. Single-touch activation is violated.
- No `CANCEL` was observed in any tap stream, so the drag-layer
  interception-cancel hypothesis is refuted. The
  `focusableInTouchMode` first-tap-focus mechanism is confirmed as the
  cause of the first-tap loss.
- Difference from the original release observation (repeated taps never
  fired): on the debug build the second tap does fire. The minified release
  matrix in section 3 verifies single-touch activation on the reported
  build type, which is the acceptance surface.
- Gate verdict: **GO** — proceed with the button FITM removal and use the
  real-touch instrumentation as the regression oracle.

## 2. Automated coverage (post-fix)

`OnboardingOrganizationProposalInstrumentationTest` — 14/14 passed on both
`nunu_qpr2_api36_1` (re-run after the PR #144 review response) and
`issue108_api36_pixel_tablet` (API 36):

- `realTouchStreamActivatesLaterWithASingleTap` — one delivered touch
  activates Later; asserts outcome `DEFERRED` and closure.
- `realTouchStreamActivatesSkipWithASingleTap` — same for Skip; asserts
  `SKIPPED`.
- `realTouchStreamOnReviewAdmitsAFreshRunAndRoutesToTheReviewSurface` —
  touch on Review admits the faked fresh run, records `REVIEWED`, and
  opens the review surface.
- `busyReviewKeepsProposalOutcomeUntouchedAndRetryableByRealTouch` — busy
  admission leaves the proposal open, outcome untouched, and touch-retryable.
- `recreatingLauncherWhileProposalIsShownLeavesNoDuplicateOrOrganizerRun` —
  drives the showing through presentation owners that share one
  process-presentation state (mirroring the production companion state),
  recreates the launcher, then asserts on the **new** launcher instance
  that the re-fired owner shows no duplicate or stuck proposal, with no
  outcome write and no admission.
- `skippedAndReviewedOutcomesNeverResurfaceAfterAColdStart` — terminal
  outcomes stay ineligible for a fresh controller (cold-start equivalent).
- The 200% font scale test now also asserts the **system-bar safe area**:
  every action's bottom edge must stay above
  `viewport.bottom - navigationBar/displayCutout inset`, so inset intrusion
  cannot regress silently.
- Pre-existing keyboard/DPAD traversal, 200% font scale, production-owner
  routing, provenance, and admission tests all pass unchanged.

Supporting gates: `spotlessCheck`, `testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`, repository contract validator, and
`assembleLawnWithQuickstepGithubDebug/Release(+AndroidTest)` all succeeded.

## 3. Minified release manual matrix (reported build type)

Fresh install of the SHA-256-recorded release APK, selected as HOME, cold
launcher start. Steps 1–8 were executed on the pre-review artifact and
re-verified on the review-response artifact (inset-respecting position):
single-touch Later, keyboard activation, and the new Review→cold-start cell
below ran against the review-response APK.

| Step | Action | Observed |
|---|---|---|
| 1 | Cold start, no input | Proposal visible at the bottom-sheet position (`release-01-proposal-bottom-sheet.png`, `release-05-inset-respected-position.png`) |
| 2 | One ordinary touch on `Later` | Proposal closed; pref `launcher.organization_proposal_outcome=DEFERRED` (`release-02-after-single-touch-later.png`) |
| 3 | Cold start after Later | Proposal resurfaced (gate log `showing proposal` at `21:09:52.418` with `DEFERRED` outcome); same-process redisplay suppressed (`claim denied`) |
| 4 | One ordinary touch on `Skip` | Proposal closed; outcome `SKIPPED` (`release-04` flow, `06_after_skip_tap` state) |
| 5 | Cold start after Skip | Proposal never resurfaced; gate log shows `claim denied` (ineligible) (`release-03-skipped-terminal-cold-start.png`) |
| 6 | DPAD_DOWN then ENTER on a fresh proposal | Proposal closed with `DEFERRED`; keyboard activation intact |
| 7 | One ordinary touch on `Review organization` | Fresh organizer run admitted, outcome `REVIEWED`, review surface opened with preview counts (`release-04-review-organizer-surface.png`) |
| 8 | Cold start after `REVIEWED` | Proposal never resurfaced (`release-06-reviewed-terminal-cold-start.png`); outcome stayed `REVIEWED` |
| 9 | Final artifact rebuild sanity | Fresh install → proposal visible above the navigation bar (`release-05-inset-respected-position.png`) → single touch on `Later` → closed, `DEFERRED` |

## 4. Observations recorded during verification

- A HOME intent delivered while the proposal is open dismisses it as
  `DEFERRED` (`Launcher` closes open floating views on the ACTION_MAIN new
  intent). This matches the accepted #53 semantics "user dismissal is
  treated as defer".
- Reinstalling over an existing install (`adb install -r`) moves
  provenance to `UPGRADE` and the proposal stops auto-showing
  (`claim denied` in the gate log). This is the accepted fail-closed
  eligibility behavior, confirmed working on the release build.
- The proposal renders at its intended bottom-sheet position only when the
  layout params are created as `BaseDragLayer.LayoutParams`; plain
  `FrameLayout.LayoutParams` are converted, losing gravity, and inset
  adjustment pinned the popup under the status bar (fixed in the same
  change). Per PR #144 review, `ignoreInsets` is left `false`: the parent
  adds the system-bar insets to the margins, so the popup keeps clearing
  the navigation bar (`release-05-inset-respected-position.png`), and the
  instrumentation safe-area oracle guards this property.
