# Issue #453 visual evidence — T-05 strategy picker choice reduction

Two capture sets, both produced by a scratch instrumentation capture (not
committed) that renders the real `OrganizerStrategyPreferences` destination —
the same composition as `StrategyT05VisualEvidenceTest` /
`StrategyPickerInstrumentationTest` — and saves PNGs via MediaStore
(`Pictures/Issue453-evidence`):

- **Physical device (authoritative, spec Verification)**: 2026-10-01,
  Pixel 9a (`tegu`), Android 17, locale **ja-JP**, app build
  `15.Dev.(92b39bc)` — the PR head `92b39bc6d5` build. Satisfies the
  accepted spec's physical-device requirement and the ja LQA check
  (「ページごとに整える」vs「下半分に集める」の意図差をja文言で確認)。
- **Emulator (auxiliary)**: 2026-10-01, API 36 emulators
  (`issue142_api36`, `issue209_pixel_7_pro`), en-US.

The committed instrumentation suite
(`tests/organizer-instrumentation/app/lawnchair/organizer/ui/StrategyPickerInstrumentationTest.kt`
+ `StrategyPickerFreezeInstrumentationTest`, 18 tests) was additionally run
**on the physical device** at the same head — all green.

| File | What it shows |
|---|---|
| `issue453-device-a-firstrun-3rows.png` | **(ja, physical)** First run (no stored selection): exactly the three offered intent choices — 標準コンパクト (default, selected), ページごとに整える (`STABLE_PAGE_TIDY_V2`), 下半分に集める (`BOTTOM_REGION_V1`). No hidden strategy row. |
| `issue453-device-b-hidden-selected-appended-row.png` | **(ja, physical)** Stored selection `STABLE_PAGE_TIDY_V1`: the three offered rows plus the appended 4th row 「ページ内で隙間を詰める（ウィジェットは移動しません）」 as the sole selected row — the V1/V2 distinction is legible in ja. |
| `issue453-device-c-reentry-persistence-same-row-selected.png` | **(ja, physical)** Persistence: after selecting the offered 「下半分に集める」 row, the surface is disposed and recomposed (re-entry); the same row remains the sole selected row and the appended row is gone — the committed selection survives re-entry through the unchanged store/read path. |
| `issue453-a-firstrun-3rows.png` | (en, emulator) Same first-run composition in en-US. |
| `issue453-b-hidden-selected-appended-row.png` | (en, emulator) Same hidden-selected 3+1 composition in en-US. |
| `issue453-c-reentry-persistence-same-row-selected.png` | (en, emulator) Same re-entry persistence observation in en-US. |

## Semantics oracles behind the captures

The displayed states are asserted by the committed instrumentation suite
(`tests/organizer-instrumentation/app/lawnchair/organizer/ui/StrategyPickerInstrumentationTest.kt`):
offered-three composition and hidden-row absence
(`pickerListsTheOfferedIntentChoicesWithLocalizedNames`), the appended
selected row and its disappearance after a change
(`hiddenRuntimeSupportedSelectionStaysSelectedAsAnAppendedRowUntilChanged`),
the unknown-ID fail-closed boundary
(`unknownStoredSelectionAddsNoRowAndShowsNoSelection`), and the radio-group /
parent-row / font-scale contracts updated to the 3+1 composition. The same
suite ran green on the physical device (18/18) at head `92b39bc6d5`.

## Known local failure (pre-existing, unchanged by #453)

`StrategyT05VisualEvidenceTest.captureTwoPaneOperationActiveFrozen` times out
waiting for `State.Selecting` on the local AVDs. Verified to fail identically
on clean `main` (5755236d68) before the #453 changes; the class is evidence
tooling outside the CI lanes. Not a #453 regression.
