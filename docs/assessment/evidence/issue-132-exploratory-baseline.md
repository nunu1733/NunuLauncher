# Issue 132 exploratory dogfooding evidence

> Status: Redacted durable subset
> Capture date: 2026-08-24
> Source commit: `05329be2d7d368a19997f981fb371a54113c7bb0`
> Candidate: `LawnWithQuickstepGithubRelease` (release/minified)
> APK SHA-256: `e23920ca366225cc006f3565a027e1b41cfe2ae53cfbcbd8fbd586955812eb5e`
> Runtime: Pixel 6 AVD, Android 16 / API 36, 1080x2400 at 420 dpi,
> portrait, 4x5 grid, Dock 4

This file preserves the smallest reviewable subset behind the exploratory
findings in [Issue #132](https://github.com/nunu1733/NunuLauncher/issues/132).
It is not a substitute for the final post-fix run. The source captures were
made on a fresh emulator with a synthetic/default layout. Package inventories,
full UI hierarchies, host paths, device serials, and unrelated logcat were
removed. No credentials or private user layout are included.

## Planning rejection — Issue #136

Run ID: `881a80255beee1a426cc7f516571c463`

The terminal result surface displayed this state:

```text
This layout cannot be organized safely. Nothing was changed.
Full layout scope: 15 targets across 1 profiles and 2 pages.
Device profile: 4 columns × 5 rows, 4 Dock slots.
0 placements will move
0 placements will be preserved
0 new folders will be created
0 new home screen pages will be created
Overlapping placement: 1
Broken reference: 7
Item/target mismatch: 8
Try again
```

The correlated redacted release logcat excerpt was:

```text
08-24 21:11:49.935 W OrganizerDiag: run=881a80255beee1a426cc7f516571c463 phase=PLANNING_REJECTED err=PLANNING_INVALID.OVERLAP reasons=16
```

The result appeared in approximately 2.1 seconds, reported that nothing had
changed, and did not expose Confirm or Apply. No visible HOME mutation was
observed. `Try again` created a distinct run,
`f38b92ecd0a2ad80a532c4b5c2281e54`, which reached the same visible counts and
rejection. Root-cause investigation belongs to
[#136](https://github.com/nunu1733/NunuLauncher/issues/136).

## Onboarding proposal touch state — Issue #137

After a cold launcher restart, the proposal surface displayed:

```text
Organize your Home screen?
NunuLauncher can group your current Home screen into categories.
Nothing changes until you review and confirm.
Skip
Later
Review organization
```

Repeated ordinary touches on `Later` left the proposal visible. The redacted
UI state after touch was:

```text
text="Later" clickable=true enabled=true focused=true
dialog="Organize your Home screen?" visible=true
```

Keyboard `Enter` then activated the focused action and closed the proposal.
No visible layout mutation followed, and a later cold restart displayed the
proposal again.

On a new proposal display, an ordinary touch on `Review organization` also
left the proposal visible:

```text
text="Review organization" clickable=true enabled=true focused=true
dialog="Organize your Home screen?" visible=true
```

Keyboard `Enter` activated Review and entered a fresh manual organizer run,
`b1fda06d0835fb47f0b72422740a5e22`. `Skip` was not directly exercised in this
exploratory run; it remains a regression and acceptance case, not a confirmed
observation. The confirmed Later/Review finding belongs to
[#137](https://github.com/nunu1733/NunuLauncher/issues/137).

## Diagnostics export route — Issue #138

The normal Home settings destinations did not expose `Export organizer
diagnostics`. The app drawer's visible search entry delegated to Google global
search. Entering the secret there produced this redacted UI state and did not
enable Lawnchair's Debug menu:

```text
package="com.google.android.googlequicksearchbox"
resource-id="com.google.android.googlequicksearchbox:id/googleapp_search_box"
text="/lawnchairdebug" focused=true
```

The source at the recorded commit corroborates the observed route boundary:

- [`OrganizerDiagnosticsExportPreference`](../../../lawnchair/src/app/lawnchair/ui/preferences/destinations/DebugMenuPreferences.kt)
  is composed in the Debug menu.
- [`enableDebugMenu`](../../../lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt)
  defaults to `false`.
- [`/lawnchairdebug`](../../../lawnchair/src/app/lawnchair/allapps/AllAppsSearchInput.kt)
  is handled by Lawnchair's own search input.
- The manual organizer's diagnostics action is conditional in
  [`ManualOrganizationPreferences`](../../../lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt).

Accordingly, this is recorded as a release integration defect rather than a
permanent environment limitation. The supported route and retest are owned by
[#138](https://github.com/nunu1733/NunuLauncher/issues/138).
