# Issue #211 device evidence

Representative evidence for the lock confirmation dialog naming its target
(Issue #211, spec `specs/211-lock-dialog-target-identity/spec.md` AC-1/AC-2/AC-4).
Captured on the local emulator (`issue209_pixel_7_pro` AVD, Android 16 / API 36,
`app.lawnchair.debug` build at head `c10f05813b`, which adds the D4
disambiguator line including the parent's own placement) from the real
`PlacementLockPreferences` composable with real `values` / `values-ja`
resources, driven by the fixture-based Compose instrumentation
(`OrganizerLockScreenTest.sameTitleState`, which stages two same-named
`Google` rows — home screen and inside a folder — plus an
effectively-protected row, mirroring the UX review F-05 observation).
Captures were taken through the evidence gate
(`-Pandroid.testInstrumentationRunnerArguments.captureEvidence=true`); the
dialog screenshots use PixelCopy of the dialog window itself so transient
emulator system dialogs cannot overlay the evidence.

| File | Locale | What it shows |
|---|---|---|
| `en-01-rows.png` | en-US | The management screen with two same-named `Google` rows (`Home screen 1` / `Inside a folder, position 1`) and an effectively-protected row — the exact ambiguity from UX review F-05. |
| `en-02-dialog-home-screen-row.png` | en-US | The confirmation dialog after tapping the home-screen `Google` row: `Target: Google` + `Home screen 1` + `Position: row 1, column 1` (D4 disambiguator) before `Current state: Unlocked`. |
| `en-03-dialog-folder-row.png` | en-US | The same dialog after tapping the folder-child `Google` row: `Target: Google` + `Inside a folder, position 1` + `Folder: F · Home screen 2 · Position: row 1, column 1` — the dialog text alone distinguishes the two rows, including the colliding-description case. |
| `ja-01-dialog-home-screen-row.png` | ja-JP | `対象: Google` / `ホーム画面 1` / `位置: 1 行 1 列` / `現在の状態: ロック解除済み`. |
| `ja-02-dialog-folder-row.png` | ja-JP | `対象: Google` / `フォルダ内の位置 1` / `フォルダ: F · ホーム画面 2 · 位置: 1 行 1 列` — ja copy follows the #161 glossary (`配置`, `フォルダ内の位置`). |
| `ja-03-dialog-200-percent-font-scale.png` | en-US | The dialog at 200% font scale (dialog-window capture): the target block including the disambiguator line wraps without truncation or layout breakage. Captured while the device locale was en-US; the ja normal-scale captures above already exercise the localized strings. |

Note: during capture the emulator repeatedly threw transient ANR dialogs
(System UI / Pixel Launcher) — the same pre-existing environment behavior
recorded in `docs/evidence/issue-231/README.md`, unrelated to the launcher
build. Captures where such an overlay obscured the dialog were retaken; the
committed dialog evidence uses dialog-window PixelCopy so the overlay cannot
appear. At 200% font scale the collision test can fail on the un-scrolled
protected row; evidence capture is gated and this does not affect the
regression suite at normal scale.
