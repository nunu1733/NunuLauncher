# Implementation Plan: オーガナイザーUIをLawnchairのビジュアル言語とローカリゼーションへ収束させる

> Issue: #123
> Spec: [spec.md](./spec.md)
> Status: draft（spec `accepted` 待ち。AGENTS.mdの手順により、承認後に実装を開始する）

## Current evidence

### Surface inventory（baseline `505dbc40e6` 時点、2026-08-26確認）

| # | Surface | 主要file | 現在の構成 | 主な差 |
|---|---|---|---|---|
| 1 | Manual organization（start/progress/preview/result/recovery） | `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `PreferenceScaffold` + `PreferenceLazyColumn` + `ClickablePreference`（上流component再利用済み） | status・summary行が裸 `Text`。heading/typography規約なし |
| 2 | Placement lock管理/review画面 | `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt` | `PreferenceScaffold` + `PreferenceTemplate` + M3 `AlertDialog`（上流も同構成: `PermissionDialog` 等） | `StateBadge` がpadding指定の素Text。message行を `ClickablePreference` で代用。description/contentDescriptionをKotlin連結（`"$title, $stateLabel"`、`joinToString(" · ")`）で構築 |
| 3 | Placement lock popup entry + dialog | `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt` | Launcher3 `SystemShortcut` + platform `AlertDialog.Builder` + Toast（`LawnchairShortcut.kt` と同系統） | launcher conventionに概ね整合。収束対象は最小 |
| 4 | Onboarding proposal popup | `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | hand-rolled View（`LinearLayout` + 標準 `Button`） | 背景白/文字黒/濃灰をhardcode、`GradientDrawable` 角丸24dp、dark非対応。最大の外れ値 |
| 5 | Category override authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/CategoryOverridePreferences.kt` | `PreferenceScaffold` + `PreferenceTemplate` + `ClickablePreference` | icon 30dp / padding 12dpの個別指定、status行が裸Text、`Text("$profile · $state")` / `contentDescription = "$label, $profile, $state"` のKotlin側文構築 |
| 6 | Organizer diagnostics/export画面 | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerDiagnosticsPreferences.kt` + `organizer/diagnostics/export/ExportUi.kt` | `PreferenceLayout` + 裸Text + `ClickablePreference` + Toast | description行のpresentation、Toast feedbackの統一可否 |
| 7 | HomeScreen設定entries ×4 | `ui/preferences/destinations/HomeScreenPreferences.kt` | 上流 `NavigationActionPreference` のみ | 収束済み。変更不要のはず |

### Localization delta（機械集計）

- baseline以降のdefault `strings.xml` 追加string: **168名**（`manual_organization_*` 94、`organizer_lock_*` 62、`organization_onboarding_*` 5、`organizer_diagnostics_*` 6、他1）。
- `values-ja` 存在: **6名**（すべて #138 diagnostics由来）。**162名が欠落**。
- 追加文字列に `plurals` / `translatable="false"` は0件。
- 未参照（dead）resource: `manual_organization_warnings_present`、`organizer_lock_screen_item_lock`、`organizer_lock_screen_item_unlock`、`organizer_lock_result_reviewed`、`organizer_lock_screen_profile_unavailable` の5名。
- 他localeファイルのbaseline差分はdeck退役による削除のみ（crowdin同期分を含む）。Nunu organizer文字列の他locale展開は0件。

### 既存検証基盤

- Screenshot capture前例: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/ManualOrganizationPreferencesInstrumentationTest.kt` の `captureReviewScreenshot`（MediaStoreへPNG保存）。Compose test rule + 実activity。
- font scale操作前例: 同testおよび `OnboardingOrganizationProposalInstrumentationTest` が `Settings.System.FONT_SCALE=2.0` を実施済み。
- CI: `ci.yml` に `organizer-unit-tests`、`organizer-instrumentation-*` 6 lane、`final-status` merge gate。workflow変更はpath filterを迂回して全gateを実行する規約あり（quality-strategy.md）。
- `pseudoLocalesEnabled true`: `build.gradle:256`。
- Robolectric/Roborazzi/Paparazzi: 導入実績なし（`tests/unit` は純粋JVM、UI検証はinstrumentation laneが正本）。

## Design

### 方針

「新規design systemを作らない」ことを最優先とし、各surfaceを**既存Lawnchair参照への置換または理由付き維持**で処理する。behavior/hosting変更を伴う書き直しは行わない。

### Surface → 参照mapping（実装時の初期案、AC-1 evidence文書へ確定版を記録）

| Nunu surface | Lawnchair参照 | 変更方針 |
|---|---|---|
| Onboarding proposal popup | theme解決されるfloating view（Launcher3 popup系のtheme attribute利用、Lawnchair settingsのcard表現） | 色をtheme attribute解決へ移行しshape/typography/paddingをtoken化。View hostingと #137 focus traversalは温存（下記Alternatives参照） |
| Manual organization status/summary | placement lock画面の `preferenceGroupItems(heading=)` + `PreferenceTemplate` 行構成 | 裸Text行をheading+row構成へ置換。liveRegion semanticsは保持 |
| Placement lock StateBadge/message行 | `PreferenceTemplate.endWidget` の既存使用例（上流settings内のtext widget表現） | badge typographyを共通styleへ、message行をstatus表示の正規手段へ。description/contentDescriptionはformat resource化 |
| Category override icon/status | 上流app list系preferenceのicon size token | 個別dp値を既存dimension/styleへ置換（等価物が無ければ値を文書化）。status文・contentDescriptionをformat resource化 |
| Diagnostics description行 | 兄弟surface（placement lock）のsummary表示構成 | heading/body構成へ揃える |
| Popup dialog / M3 dialog / Toast | 上流 `LawnchairShortcut.kt` / `PermissionDialog` 等 | 原則変更不要。判定根拠をAC-1文書に記録 |

### Alternatives rejected

- **Onboarding proposalのCompose移植**: Lawnchair settings UIの主要表現ではあるが、hostingがLauncher dragLayer上の `AbstractFloatingView` であり、#137で固定済みのkeyboard/touch mode挙動とfocus復元がView実装に密結合している。presentation目的でこの意味論を触ることは本specのnon-goalに抵触するため不採用。theme解決のみで収束する。
- **Roborazzi前提でのJVM screenshot gate**: 新dependency + Compose描画の安定化 + 新CI lane costがあり、既存instrumentation captureの前例が機能している。先にproof（1 surface × matrix の局所導入）で価値を実証できた場合のみsplit Issueとして再提案する。本planの既定経路は「既存instrumentation capture拡張」である。
- **ja以外localeへの一括翻訳**: Issue本文のsplit criteriaどおり範囲外。crowdin workflows（`crowdin_*.yml`）は上流同期用であり、Nunu固有文字列の管理方式変更が必要になった場合に別Issueで扱う。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/ui/OrganizationOnboardingProposal.kt` | hardcoded色/shape除去、theme attribute解決、button style統一 | 最大の視覚外れ値。behavior/store keyは不変 |
| `ui/preferences/destinations/ManualOrganizationPreferences.kt` | status/summary行のrow構成化（heading + typography） | 表示品質の収束。state machine呼び出しは不変 |
| `ui/preferences/destinations/PlacementLockPreferences.kt` | StateBadge/message行の正規化、description/contentDescriptionのformat resource移行 | 同上。Kotlin連結による読み上げ文生成を解消（AC-4） |
| `ui/preferences/destinations/CategoryOverridePreferences.kt` | dp指定のtoken置換、status行整理、`"$profile · $state"` / contentDescription合成のformat resource移行 | 同上 |
| `ui/preferences/destinations/OrganizerDiagnosticsPreferences.kt` | description行の構成化 | 同上 |
| `res/values/strings.xml` | dead resource整理、複合文用format resource追加（app/profile/state合成・区切り子含む）、必要に応じ文案調整 | 正本がdefault resourceであるため。localeごとの語順・区切り替えを可能にする（AC-4） |
| `res/values-ja/strings.xml` | dead resource整理・複合文format resource追加後の、全active/user-visible/translatable Nunu organizer resourceの日本語訳被覆 | localization完成が本Issueの中核成果。件数は実装後の最終name集合で確定し、AC-5/test oracleのname-set比較を正本とする |
| `tests/organizer-instrumentation/...` | screenshot/evidence capture手順のmatrix化（default locale/ja/en-XA × light/dark × font scale） | AC-6〜8の再現可能な証拠取得 |
| `docs/assessment/evidence/issue-123-ui-mapping.md` | AC-1 mapping + before/after記録 | issue完了条件の正証明場所 |

## Migration and recovery

- schema/rule migration: なし。
- failure中のrollback: 対象外（layout data pathに接触しない）。ホームレイアウト安全規約の適用条件を満たさない変更（DB書き込み）自体が存在しない。
- release rollback/downgrade: PR revertで戻る。string resource追加はdowngrade時に単に未解決になるだけで、旧APKには影響しない。
- backup/restore compatibility: preference key・journal形式は不変のため影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | evidence文書review | 手動（PR添付） |
| AC-2 | mapping表 + diff review | 手動 |
| AC-3 | dark/light capture + grep（hardcoded色消失） | API 36 emulator |
| AC-4 | literal grep + Kotlin側文字列構築の洗い出し結果 + 複合文format resource化diff + res差分 | local（Nunu path限定grep: `Text("` への補間、`contentDescription = "` 合成、`buildString` / `joinToString`） |
| AC-5 | name集合比較（default Nunu追加集合 ⊆ ja集合）+ placeholder確認 | local script（`git diff baseline -- values*/strings.xml` ベース） |
| AC-6 | ja locale代表flow screenshot一式 | API 36 emulator（`Settings.System.putString(locales)` or per-app locale） |
| AC-7 | en-XA実行screenshot + pseudo展開assertion | API 36 emulator（pseudoLocalesEnabled活用） |
| AC-8 | surface別capture matrix表 + capture手順書 + 画像 | 既存 `captureReviewScreenshot` 拡張test。matrixはdefault/ja/en-XA × light/dark × font scaleからsurfaceごとに定義 |
| AC-9 | 既存automated gateのCI green（新規/fullなSwitch Access実行は行わない。#109 ownership） | `./gradlew spotlessCheck` / `assembleLawnWithQuickstepGithubDebug` / `testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` / organizer instrumentation lanes → `final-status` URL |
| AC-10 | tooling判断記録 | PR本文 |
| AC-11 | diff scope記述 + JVM gate無編集pass | CI run URL |

実装順（縦切り）:

1. **Slice A — onboarding proposal収束**（surface 4）+ light/dark evidence。behavior不変を既存 #53/#137 instrumentationで担保。
2. **Slice B — 設定surface収束**（surfaces 1, 2, 5, 6）。surface毎にbefore/after capture。Kotlin側の複合文構築（`"$profile · $state"`、contentDescription合成等）のformat resource移行もここで実施する。
3. **Slice C — localization完成**（dead resource整理→ja一括追加→placeholder監査）。layout・format resource確定後に入る（issue推奨順序どおり）。
4. **Slice D — evidence matrix整備**。default locale / ja / en-XA × light/dark × 代表font scaleについて、surfaceごとの適用組み合わせ表をevidence文書へ明文化し、それに基づきcapture手順を確立してAC-6〜8の証拠を取得する。
5. Roborazzi spikeは本Issueの完了条件外であり、本体PRに含めない。価値が実証された場合のみ別Issueとして提案する。本Issueはinstrumentation capture拡張（既定経路）で完了判定を受ける。

## Documentation updates

- [ ] spec status/history（acceptance時に `accepted`、merge時に `implemented`）
- [ ] CONTEXT.md — 用語追加なし（確認済み）
- [ ] DESIGN.md — system structure変更なし（presentationのみのため更新不要見込み）
- [ ] ADR — Roborazzi採用等、後から変更困難かつ選択肢が実際にあった判断が出た場合のみ
- [ ] AGENTS.md — 新必須commandが確定した場合のみ（building guide追記を伴う）
- [ ] docs/engineering/building.md — en-XA/ja emulator手順を検証済みcommandとして載せる場合

## Execution checklist

- [ ] Spec approval recorded on Issue #123.
- [ ] Current behavior reproduced (before captures per slice).
- [ ] Slice A–D implemented with per-slice evidence.
- [ ] Full relevant verification completed (`final-status` green).
- [ ] Evidence doc (`docs/assessment/evidence/issue-123-ui-mapping.md`) completed.
- [ ] PR links issue & spec, records remaining risks and split follow-ups.
