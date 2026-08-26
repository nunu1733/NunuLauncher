# Issue #123 organizer UI convergence — surface mapping and visual evidence

> Status: Executed（2026-08-26にmapping・capture・CI検証完了）
> Recorded: 2026-08-26
> Spec: [specs/123-organizer-ui-convergence/spec.md](../../../specs/123-organizer-ui-convergence/spec.md)（AC-1, AC-6〜9のevidence正本）
> Baseline for comparison: `505dbc40e6154c05158b5d0271c45f6a885a411b` (Lawnchair `v15.0.0-beta3.0`)
> Implementation head: `4f98120743`（debug APK `Lawnchair.15.Dev.(4f98120).github.debug.apk`, `app.lawnchair.debug`）
> Capture runtime: 専用AVD `issue142_api36`（`emulator-5654`, Android 16 / API 36, 1080x2400 @ 420dpi,
> portrait, cold boot）。並行作業セッションの `nunu_qpr2_api36_1` インスタンスとは分離した。
> Locale切替はper-app locale（`cmd locale set-app-locales app.lawnchair.debug`）、
> appearanceは `cmd uimode night yes/no`、font scaleは `settings put system font_scale 2.0`。
> onboarding popupはfresh install（uninstall→install）毎に新規provenanceで表示させた。
> Capture 23枚はcapture実施端末の `/tmp/nunu123-captures/` に保管（local evidence、必要に応じて添付）。

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

## AC-8 — Capture matrix（実行結果、2026-08-26）

font scaleは200%（既存回帰testと同じ条件）。セル番号は `/tmp/nunu123-captures/` のcapture番号に対応。

| Surface | default en light | default en dark | ja light | ja dark | en-XA light | font scale 200% (ja) |
|---|---|---|---|---|---|---|
| Onboarding proposal popup | 01 ✓ | 08 ✓ | 10 ✓ | 15 ✓ | 17 ✓ | 19 ✓ |
| Manual organization idle | 04 ✓ | 09 ✓ | 12 ✓ | 16 ✓ | 18 ✓ | 20 ✓ |
| Manual organization preview | 07 ✓ | − | − | − | − | − |
| Placement lock管理/review | 03 ✓ | − | 11 ✓ | − | − | − |
| Category override authoring | 06 ✓ | − | 14 ✓ | − | − | − |
| Organizer diagnostics/export | 05 ✓ | − | 13 ✓ | − | − | − |

「−」は他列で主リスクが担保されるため省略（初回定義どおり）。観察結果:

- **AC-3 (light/dark)**: popup背景・文字が `?android:colorBackground`/textPrimary/textSecondary解決で、light=白系/dark=`#121212`系に正しく追従（01/08/10/15）。実装前の `Color.WHITE` 固定では成立しなかった描画である。
- **AC-6 (ja fallbackなし)**: 10/12/11/14/13で、popup・設定画面のorganizer文言すべてが日本語resource由来。英語fallbackは観察されず。lock行の「ホーム画面 1」「フォルダ内の位置 1」、category行の「個人用 · 自動カテゴリを使用中」等のformat resource合成文も日本語で正しく描画。
- **AC-7 (en-XA)**: 17/18で、popup・explainer・action labelを含む全organizer文言がpseudo展開され、素通しのraw文字列なし。拡張後もcritical action（「ŔéVîéŵ öŕĝåñîžåţîöñ」）が到達可能。
- **font scale 200% (ja)**: 19でpopupの全button（後で/スキップ/整理を確認）がviewport内（UI dumpでbounds 94–986 × ≤2211 / 2400確認）。20でmanual org explainerは3行折返しになりつつ「整理を確認」actionが到達可能。

### 取得手順（再現用）

1. `emulator -avd issue142_api36 -port 5654 -no-window -no-snapshot`（API 36, 420dpi）
2. `adb install -r Lawnchair.15.Dev.(4f98120).github.debug.apk`
3. locale: `adb shell cmd locale set-app-locales app.lawnchair.debug --locales ja`（ja-JP/en-XA/en）
4. appearance: `adb shell cmd uimode night yes|no`（appの再起動後に反映）
5. font scale: `adb shell settings put system font_scale 2.0`
6. popup: fresh install（uninstall→install）→ `am start -n app.lawnchair.debug/app.lawnchair.LawnchairLauncher` → 15s待機
7. 設定画面: `am start -n app.lawnchair.debug/app.lawnchair.ui.preferences.PreferenceActivity` →「ホーム画面」→ Layout group
8. capture: `adb exec-out screencap -p`、UI確認: `uiautomator dump`

## Localization coverage check（AC-5 oracle実行結果）

- 検証集合 `required = active/user-visible/translatableなNunu organizer default resource`:
  - baseline差分: `lawnchair/res` 168名 + root `res` 54名 = 222名（うちdead 5名を実装で削除）
  - 新規format resource 6名（複合文移行）+ `translatable="false"` 1名（export既定file名）
- 機械確認script（name集合抽出→`required ⊆ values-ja names`、placeholder `%N$X` 一致比較）:
  required **223名**すべてja被覆、placeholder不一致**0件**。実行記録は実装PR本文へ貼付。

## CI verification (AC-9)

Head `4f98120743` の PR [#154](https://github.com/nunu1733/NunuLauncher/pull/154) で全job pass、
merge gate `final-status` green（run: https://github.com/nunu1733/NunuLauncher/actions/runs/32973322535）:
`validate-repo-contract` / `check-style` / `build-debug-apk` / `organizer-unit-tests` /
`organizer-instrumentation-{api35,db-migration,issue52,issue53,issue99,shared-writer}-tests`。
既存accessibility/behavior regressionはすべて無編集でpassした。

## Findings

- 初回調査でroot `res/values/strings.xml` 側の #99カテゴリ文字列54名の取りこぼしが判明（spec Problem事実1を訂正済み）。
- Category overrideの30dp/12dp指定は上流 `AppItem.kt` と同一valueであり、独自conventionではなかった（変更せず記録）。
- capture実行中に、manual organization / diagnostics / category override の裸Text情報行が画面左端（x=0）で描画されるconvention乖離を発見し、`4f98120743` でplacement lock画面と同じ16dp paddingへ修正した（captureは修正後のAPKで取得）。
- per-app localeはpackage再installでクリアされるため、fresh install毎のlocale再設定が必要だった（手順書に反映済み）。
- popup・各設定画面とも英語fallback・dark破綻・clippingは観察されなかった。
