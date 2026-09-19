# Issue #368 visual evidence — T-05 strategy materials surface

Captured 2026-09-19 (JST 2026-09-20) on API 36 emulator (`nunu_qpr2_api36_1`,
1080×2400) by `StrategyT05VisualEvidenceTest`
(`tests/organizer-instrumentation/app/lawnchair/organizer/ui/`), which renders
the real `OrganizerStrategyPreferences` destination and saves PNGs via
MediaStore (`Pictures/Issue368-t05-evidence`). Re-run that class and pull the
files to reproduce. The class is evidence tooling and is intentionally not in
the CI instrumentation lanes (explicit class lists in `ci.yml`).

| File | What it shows |
|---|---|
| `t05-light-selected-unselected.png` | T-05 in light theme: the effective selection (Standard compaction, bundle default) renders the selected affordance; the other rows render unselected (spec 283 AC-1/AC-2 applied to T-05). Scrolled so selected and unselected rows are on screen together. |
| `t05-dark-selected-unselected.png` | Same composition in dark theme. |
| `t05-200percent-font.png` | T-05 at 200% font scale (Density fontScale=2f): rows reflow, remain within the window width (asserted in the test: no row exceeds the window bounds), and stay reachable by scrolling. |
| `t05-twopane-operation-active-frozen.png` | Expanded (two-pane) composition hosting the run surface and the T-05 detail simultaneously with an active run operation (State.Selecting): T-05 renders the frozen affordance — disabled radio rows plus the live-region reason line (`strategy-picker-frozen-reason`, asserted in the test). The structural gate is the AUTHORING lease; the frozen display derives from the same operation-lifetime projection. |

## Two-pane production navigation finding (measured)

The accepted plan expected the T-05 surface and the run surface to be
simultaneously composable under expanded (two-pane) settings. Measured against
the production shell, that premise does not hold:

- `Preferences.kt` two-pane mode renders `first = PreferencesDashboard` and
  `second =` a single movable `NavHost` — one destination composes at a time.
  `HomeScreenManualOrganization` and `HomeScreenOrganizerStrategy` are separate
  destinations, so they are never composed together.
- Leaving the run surface disposes its composition, and
  `ManualOrganizationBackHandler`'s `onDispose` calls `coordinator.dismiss()`
  (pre-checkpoint zero-write cancel). By the time T-05 composes, the operation
  is over and `operationActive == false`, so T-05 renders unfrozen.

Consequence: operation-active frozen T-05 is unreachable through current
production navigation; the structural gate (AUTHORING lease) plus the
operation-lifetime projection remain as navigation-independent defenses for
future navigation changes (#369). Accordingly:

- `t05-twopane-operation-active-frozen.png` is captured from a synthetic
  side-by-side composition (`LocalIsExpandedScreen = true`, both destinations,
  one injected runner parked on `State.Selecting`). It is a **defensive
  composition oracle**, not a substitute for production two-pane evidence —
  production never composes both destinations at once.
- The accepted plan's two-pane premise is updated in the same commit (plan
  Risk 4 / Verification / Unverified areas; spec scenario wording).

## Runtime navigation ordering oracle (measured, supported path)

`StrategyT05ProductionNavigationTest` drives the REAL production
`PreferenceNavigation` (real NavHost, real shared-axis transitions, real
destination registrations, expanded window) through the SUPPORTED user path
with an active run, with the animation clock pinned:

- run surface -> hub (hop 1): mid-transition the outgoing run surface and
  the incoming hub coexist and the run operation is STILL alive (the
  `onDispose` -> `dismiss()` cleanup has not run yet); when hop 1 completes
  the run surface has disposed, `dismiss()` ran, and the operation is over
  (`operationActive == false`, `State.Cancelled`).
- hub -> T-05 (hop 2): the transition starts with the operation already
  over, so T-05 composes UNFROZEN (no frozen reason row).
- T-05 is writable on the supported path: a selection publishes through the
  validated write command.

Measured conclusion: on production-supported navigation, T-05 never composes
while the operation is still alive — the operation always ends at run-surface
disposal (hop 1), before T-05 is entered. The frozen affordance is therefore
a defensive structure for future navigation changes (#369). This supersedes
the earlier wording that run+T-05 "never coexist" without the transition
nuance: the pair that transiently coexists mid-transition is run+hub; T-05
only enters after the operation ended.

Two further measured environment findings (recorded as the reason for the
oracle's binding choices):

1. A real run started through the production `ManualOrganizationModule`
   singleton inside the instrumentation process never reaches an active
   operation — the application module answers
   `InputUnavailable(ReconciliationPending)` even after the readiness gate
   settles. The oracle therefore binds an injected runner via the
   `PreferenceNavigation` `runOverride` seam (mirroring the destinations'
   own `run` params; production callers omit it).
2. Composing the full `Preferences` two-pane shell under the compose-test
   activity throws (`No compose hierarchies` — the shell composition fails
   to start) on all three AVDs, so the oracle drives the production
   `PreferenceNavigation` directly. `Preferences.kt`'s TwoPane structure
   (first = dashboard, second = single movable NavHost) is cited in the plan
   as the code-level evidence that supported paths compose one second-pane
   destination at a time.
