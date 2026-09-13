# Issue #283: strategy picker evidence

> Status: observed
> Capture date: 2026-09-13 JST
> Source revision: `c66d0ee1793d582b3d8d9cf431c3dfbed3656168`

- Issue: https://github.com/nunu1733/NunuLauncher/issues/283
- Device: API 36 Android emulator, `issue142_api36`, 1080x2400
- Locale: en
- Capture provenance: local debug APK built from the source revision above; screenshots are repository-retained evidence
- Privacy: synthetic emulator contents only; no personal account or device data is shown

## AC-3 / AC-7: light and dark visual distinction

The selected `Standard compaction` row shows a radio ring with a filled center. The other rows show empty rings. The distinction remains visible in both themes and is not dependent on selected-row background or text color.

### Light theme, 100% font scale

![Strategy picker in light theme](./issue-283-strategy-picker/light.png)

- Theme: light
- Font scale: 1.0
- Selected row: `Standard compaction`

### Dark theme, 100% font scale

![Strategy picker in dark theme](./issue-283-strategy-picker/dark.png)

- Theme: dark
- Font scale: 1.0
- Selected row: `Standard compaction`

## AC-5: 200% font scale

![Strategy picker at 200% font scale](./issue-283-strategy-picker/font-scale-200.png)

- Theme: dark
- Font scale: 2.0
- The captured rows wrap onto multiple lines without visible overlap between the radio indicator and text, and without clipping within the visible row bounds.
- This is a scrolled representative frame, not evidence that every row is simultaneously visible. The focused instrumentation test scrolls each of the eight strategy names into view and asserts display at `fontScale = 2f`.

## Accessibility and deterministic checks

The focused instrumentation test verifies that the picker has eight parent-row click targets and selectable targets, all with `Role.RadioButton`, and that the visual-only child radio does not add an independent selectable or focus target. Parent-row selection movement, single-selection uniqueness, default-as-effective, fail-closed, and re-selection no-op are covered by the same suite.

TalkBack speech output was not manually verified in this environment. The emulator has the TalkBack package installed, but this session cannot provide reliable human confirmation of spoken output. The remaining constraint is therefore recorded rather than treated as passed by screenshot or semantics assertions.

## Executed local commands

- `./gradlew spotlessCheck` -> PASS
- `./gradlew assembleLawnWithQuickstepGithubDebug` -> PASS
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` -> PASS
- `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.ui.StrategyPickerInstrumentationTest` -> PASS on `issue142_api36` and `nunu_qpr2_api36_1`; 11/11 tests on each emulator
