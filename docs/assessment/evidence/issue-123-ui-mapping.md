# Issue #123 organizer UI convergence — surface mapping and visual evidence

> Status: Partial（mapping確定 / capture実行はPR review時に添付）
> Recorded: 2026-08-26
> Spec: [specs/123-organizer-ui-convergence/spec.md](../../specs/123-organizer-ui-convergence/spec.md)（AC-1, AC-8のevidence正本）
> Baseline for comparison: `505dbc40e6154c05158b5d0271c45f6a885a411b` (Lawnchair `v15.0.0-beta3.0`)
> Implementation head: 実装PRのhead SHAをPR本文へ記録する

## AC-1 — Surface → Lawnchair reference mapping

| # | Nunu surface | 主要file | 参照したLawnchair/Launcher3 pattern | 判定と根拠 |
|---|---|---|---|---|
| 1 | Onboarding proposal popup | `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | Activity theme解決（`Theme.Lawnchair` → AppCompat DayNight、`values-night` で `android:colorBackground` 定義済み）、`Themes.getAttrColor`、Launcher3 dialog corner token `R.dimen.default_dialog_corner_radius` | **収束実施**。`Color.WHITE/BLACK/DKGRAY` と独自24dp角丸を削除し、背景= `?android:colorBackground`、title/textPrimary・summary/textSecondary、角丸=dialog tokenへ置換。View hostingと #137 focus/touch-mode semanticsは不変 |
| 2 | Manual organization画面 | `ui/preferences/destinations/ManualOrganizationPreferences.kt` | `PreferenceScaffold` + `PreferenceLazyColumn` + `ClickablePreference`（既存使用）、placement lock画面と同一の `MaterialTheme.typography.bodyMedium` 情報テキスト | **収束実施**。explainer・summary・safe terminalテキストをbodyMediumへ統一。status行のliveRegion/focus semantics、state machine呼び出しは不変 |
| 3 | Placement lock管理/review画面 | `ui/preferences/destinations/PlacementLockPreferences.kt` | `PreferenceTemplate.endWidget` のtext widget慣習、M3 `AlertDialog`（上流 `PermissionDialog` 等と同構成） | **収束実施**。row description・StateBadge読み上げをformat resource化（Kotlin連結廃止）。badge自体のlabelMedium text表現はendWidget慣習内であり維持（color-onlyでない状態表示という #38要件） |
| 4 | Placement lock popup entry | `ui/popup/OrganizerLockShortcut.kt` | Launcher3 `SystemShortcut` + platform `AlertDialog.Builder`（上流 `LawnchairShortcut.kt` と同一pattern）、結果通知Toast | **変更不要**。上流launcher conventionに既に整合。全文言はresource由来 |
| 5 | Category override authoring | `ui/preferences/destinations/CategoryOverridePreferences.kt` | 上流app list行 `AppItem.kt`（icon 30dp / verticalPadding 12dp — 本surfaceと同一値）、`PreferenceScaffold`/`PreferenceTemplate` | **収束実施**。サイズtokenはAppItem convention一致のため維持し、description `"$profile · $state"` とcontentDescription合成を読み上げ用format resourceへ移行 |
| 6 | Organizer diagnostics/export画面 | `ui/preferences/destinations/OrganizerDiagnosticsPreferences.kt`, `organizer/diagnostics/export/ExportUi.kt` | 兄弟organizer画面の情報テキスト表現（bodyMedium）、`ClickablePreference`、#67契約 | **収束実施**。descriptionをbodyMediumへ統一。SAF既定file名を `translatable="false"` resource化（固定technical identifierのresource経由供給）。export control/SAF flowは #67のまま不変 |
| 7 | HomeScreen設定entries ×4 | `ui/preferences/destinations/HomeScreenPreferences.kt` | 上流 `NavigationActionPreference` | **変更不要**。上流componentのみ |

残るcustom presentationと目的:

- onboarding proposalがComposeではなくView実装であること: Launcher dragLayer上の `AbstractFloatingView` hostingと #137 で確定したfocus/touch-mode traversalがView実装に紐づくため（behavior非変更のため移植しない）。
- placement lock状態を色でなくtext badgeで示すこと: #38 の「state is always shown as text, never color alone」要件。
- lock確認dialogのbodyが、完成した文であるlocalized部品を改行連結する構成であること（`PlacementLockPreferences.LockChangeDialog` / `OrganizerLockShortcut`）: 各部品が独立した完全文であり文法の跨りがないため、AC-4の「文構造をKotlinで生成しない」要件の対象外（文の並置）として維持。

## AC-8 — Capture matrix（代表surface別）

完全直積ではなく、surfaceごとの役割に応じた組み合わせを定義する。font scaleは200%（既存回帰testと同じ条件）。

| Surface | default en light | default en dark | ja light | ja dark | en-XA light | font scale 200% |
|---|---|---|---|---|---|---|
| Onboarding proposal popup | ○ | ○ | ○ | ○ | ○ | ○ |
| Manual organization（idle→preview→applied/recovery） | ○ | ○ | ○ | ○ | ○ | ○ |
| Placement lock管理/review + dialog | ○ | ○ | ○ | ○ | − | ○ |
| Category override authoring | ○ | − | ○ | − | − | − |
| Organizer diagnostics/export | ○ | − | ○ | − | − | − |

- 「−」はその検証の主リスク（翻訳fallback・dark描画・拡張clipping）が他列で担保されるため省略。
- 取得手順: API 36 emulator（Pixel 6定義, 420dpi）＋debug APK。
  - locale切替: `adb shell settings put system system_locales ja-JP`（en-XAはSettings appまたは `settings put system system_locales en-XA`）
  - dark: `adb shell cmd uimode night yes/no`
  - font scale: `adb shell settings put system font_scale 2.0`
  - capture: 既存instrumentation capture（`ManualOrganizationPreferencesInstrumentationTest.captureReviewScreenshot` 拡張）または `adb exec-out screencap -p`
- 取得画像と実行ログは実装PRへ添付し、本節の各セルを実行結果で更新する。

## Localization coverage check（AC-5 oracle実行結果）

- 検証集合 `required = active/user-visible/translatableなNunu organizer default resource`:
  - baseline差分: `lawnchair/res` 168名 + root `res` 54名 = 222名（うちdead 5名を実装で削除）
  - 新規format resource 6名（複合文移行）+ `translatable="false"` 1名（export既定file名）
- 機械確認script（name集合抽出→`required ⊆ values-ja names`、placeholder `%N$X` 一致比較）:
  required **223名**すべてja被覆、placeholder不一致**0件**。実行記録は実装PR本文へ貼付。

## Findings

- 初回調査でroot `res/values/strings.xml` 側の #99カテゴリ文字列54名の取りこぼしが判明（spec Problem事実1を訂正済み）。
- Category overrideの30dp/12dp指定は上流 `AppItem.kt` と同一valueであり、独自conventionではなかった（変更せず記録）。
- capture実行はPR review時までに実施し、本docへ結果を反映する。
