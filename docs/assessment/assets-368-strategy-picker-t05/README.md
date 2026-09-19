# Issue #368 visual evidence — T-05 strategy materials surface

Captured 2026-09-19 (JST 2026-09-20) on API 36 emulator (`nunu_qpr2_api36_1`,
1080×2400) by `StrategyT05VisualEvidenceInstrumentationTest`
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
