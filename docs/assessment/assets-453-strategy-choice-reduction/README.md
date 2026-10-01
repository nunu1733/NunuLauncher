# Issue #453 visual evidence — T-05 strategy picker choice reduction

Captured 2026-10-01 (JST) on API 36 emulators (`issue142_api36`,
`issue209_pixel_7_pro`, en-US locale) by a scratch instrumentation capture
(not committed) that renders the real `OrganizerStrategyPreferences`
destination — the same composition as
`StrategyT05VisualEvidenceTest`/`StrategyPickerInstrumentationTest` — and
saves PNGs via MediaStore (`Pictures/Issue453-evidence`).

**Evidence tier**: these are **emulator captures (auxiliary evidence)**. The
spec ([specs/453-strategy-choice-reduction/spec.md](../../../specs/453-strategy-choice-reduction/spec.md))
requires owner confirmation with physical-device screenshots for the tier M
acceptance; physical-device capture is pending owner action at PR review.

| File | What it shows |
|---|---|
| `issue453-a-firstrun-3rows.png` | First run (no stored selection): the picker composes exactly the three offered intent choices — Standard compaction (default, selected), Tidy each screen (STABLE_PAGE_TIDY_V2), Bottom-half layout (BOTTOM_REGION_V1). No hidden strategy row. |
| `issue453-b-hidden-selected-appended-row.png` | Stored selection `STABLE_PAGE_TIDY_V1` (runtime-supported, hidden from the offered set): the three offered rows plus the appended 4th row "Tidy each screen (widgets stay in place)" as the sole selected row. |
| `issue453-c-reentry-persistence-same-row-selected.png` | Persistence: after selecting the offered "Bottom-half layout" row, the surface is disposed and recomposed (re-entry); the same row remains the sole selected row — the committed selection survives re-entry through the unchanged store/read path. |

## Semantics oracles behind the captures

The displayed states are asserted by the committed instrumentation suite
(`tests/organizer-instrumentation/app/lawnchair/organizer/ui/StrategyPickerInstrumentationTest.kt`):
offered-three composition and hidden-row absence
(`pickerListsTheOfferedIntentChoicesWithLocalizedNames`), the appended
selected row and its disappearance after a change
(`hiddenRuntimeSupportedSelectionStaysSelectedAsAnAppendedRowUntilChanged`),
the unknown-ID fail-closed boundary
(`unknownStoredSelectionAddsNoRowAndShowsNoSelection`), and the radio-group /
parent-row / font-scale contracts updated to the 3+1 composition.

## Known local failure (pre-existing, unchanged by #453)

`StrategyT05VisualEvidenceTest.captureTwoPaneOperationActiveFrozen` times out
waiting for `State.Selecting` on the local AVDs. Verified to fail identically
on clean `main` (5755236d68) before the #453 changes; the class is evidence
tooling outside the CI lanes. Not a #453 regression.
