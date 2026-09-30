# Implementation Plan: ワークスペース長押しメニューの「ホームを整理」項目

> Issue: #452
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: M（spec冒頭のとおり。階層Mはplan.mdを要求しないが、Issue未解決事項の解消根拠と実装順序を残すために作成する）

## Current evidence

確認時点: `origin/main` = `56624406fd`（2026-09-30。rebase後のbase）。行番号はこのcommitでの値。

### 関連code pathと現在の振る舞い（確認済み事実）

- `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` — **#449（PR #476）でfork patch済み**（`homeedit-edit-surface` bridge groupへcounted）
  - `DEFAULT_ORDER`（16-25行）: `carousel(+), lock(-), edit_mode(-), edit_surface(+), wallpaper(+), widgets(+), home_settings(+), sys_settings(-)`。lock/edit_mode/sys_settingsは新規install既定で無効。`edit_surface` は #449 追加。
  - `restoreMissingPopupOptions`（27-43行）: 保存済みorderに無いDEFAULT項目を **先頭にprepend** する（`(missingItems + currentOptions)`）。#449もこのprependで `edit_surface` を補完した。`LawnchairLauncher.kt:262` から起動時に1回呼ばれる。
  - `getLauncherOptions`（48-143行）: `lockHomeScreen` 有効時に `edit_mode`・`widgets`・`edit_surface` を隠すフィルタ（131-138行）、`isEnabled && identifier != "carousel"` フィルタ（127-130行）、`optionsList` map（67-124行）から `mapNotNull` で組む。`edit_surface` のhandlerは #449 がfile内lambda（`HomeEditSurfaceActivity.start`）で定義しており、上流 `OptionsPopupView` のsignatureは不変のまま — 本Issueも同じ形に乗る。
  - `getMetadataForOption`（145-190行）: popup編集画面用のlabel/icon。`edit_surface` 分岐あり（168-171行）。未知identifierは `IllegalArgumentException`。
- `src/com/android/launcher3/views/OptionsPopupView.java`（Launcher3/AOSP由来、fork未patch）
  - `getOptions`（199-209行）: `LauncherOptionsPopup.getLauncherOptions` へ6つのstatic handler（lock/sys_settings/edit_mode/wallpaper/widgets/home_settings）を渡す。#449・#452ともhandlerをfork file内に閉じるためこのsignatureは不変。
  - `handleViewClick`（100-113行）: handlerがtrueを返すとpopupを閉じる。
  - `OptionItem`（294-321行）: `labelRes` つきconstructor（`AccessibilityActionsView` がaction idに使う）とlabel文字列のみのconstructorがある。
- `src/com/android/launcher3/views/AccessibilityActionsView.java`（68-77, 80-100行付近）: `OptionsPopupView.getOptions(l)` の各項目を `item.labelRes` をidとするaccessibility actionとして公開し、`performAccessibilityAction` が同じclickListenerを呼ぶ。
- popupの出現経路（変更不要）: `WorkspaceTouchListener.java:223` と `KeyboardShortcutsDelegate.java:159` → `Launcher.showDefaultOptions`（`Launcher.java:2855-2858`）→ forkでは `LawnchairLauncher.showDefaultOptions` がcarousel有無で2分岐するが、両方 `OptionsPopupView.getOptions(this)` → fork `getLauncherOptions` を通る。
- `lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt`（33-70行）: `EXTRA_DESTINATION_ROUTE`（`app.lawnchair.ui.preferences.DESTINATION_ROUTE`）で渡された直列化routeを `startDestination` として開く `createIntent(context, destination)` を持つ。**Issue未解決事項1（popupからrun面への到達方法）はこの既存仕組みで解消する。**
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt`（152-169行）: `HomeScreenManualOrganization(entry = MANUAL既定, durableRecovery = false, exchangeOpen = null)` → `trigger = MANUAL_FULL`。`PreferenceNavigation.kt:126-138` がrouteから `ManualOrganizationPreferences(trigger = route.trigger, ...)` へ結線。
- launcher側からrun面を開く実例: `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt`（421-431行）が `launcher.startActivity(PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization(OrganizationEntry.ONBOARDING)))` で開く（flagなしのplain `startActivity`）。
- run面: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - 開始CTA: `execute { coordinator.start(trigger) }`。admission（RUN lease）はここだけが持つ。#451のmergeで行数は後半に変化があるが（重複launch target警告のwording追加）、run面の契約・開始CTAの構造は不変。
  - #443実装（`ea8d57d068`）: AI相談toggle OFF（既定）のとき `State.ScopeConfirmed` を自動で `planWithConfirmedScope()` へ進めるeffect。toggle読み取りはcomposition開始時1回。
  - 検出候補0件は空cutでscope凍結へ直行（spec 417/443の契約）。
- popup編集画面: `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt`（97-127行）: `getMetadataForOption` でlabel/iconを引き、`enabled` のwhen分岐（99-103行）で `edit_mode`/`widgets` は `lockHomeScreen` 中無効化、`home_settings` は常に無効化。**`edit_surface` は分岐に入っておらず `else -> true`**（#449はpopup側フィルタのみ要求。#449側の残課題として観察記録し、本Issueでは `organize_home` の分岐のみ追加）。`ReorderablePreferenceGroup(defaultList = LauncherOptionsPopup.DEFAULT_ORDER)`。このfileはfork未patch（本Issueで初めてpatchする）。
- preference: `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt`: `launcherPopupOrder`（string、defaultは `DEFAULT_ORDER.toOptionOrderString()`）。
- strings: `lawnchair/res/values/strings.xml`（`edit_surface_menu_open` = "Edit layout grid"、959行付近）、`lawnchair/res/values-ja/strings.xml`（`edit_surface_menu_open` = 「編集画面」、91行付近）。`edit_home_screen` = "Edit home screen"／「ホーム画面を編集」（上流由来）。
- 上流patch surface baseline（`docs/assessment/upstream-patch-surface-baseline.json`、captured_on 2026-09-29）: `LauncherOptionsPopup.kt` は `homeedit-edit-surface` groupへcounted済み（#449）。`values/strings.xml`・`values-ja/strings.xml` は `organizer-ui-and-lock-authoring` groupへcounted済み。`LauncherPopupPreference.kt`・`OptionsPopupView.java` は未counted（fork未patch）。
- CI surface mapping（`.github/workflows/ci.yml`）: `lawnchair/src/app/lawnchair/ui/**` と `lawnchair/res/**` は `surface_organizer_ui`。`LauncherOptionsPopup.kt`（`ui/popup/`）と `LauncherPopupPreference.kt`（`ui/preferences/destinations/`）はこのglobに含まれるためmappedであり、per-path fail-closedは発火しない。
- JVM test配置: `tests/unit/app/lawnchair/` 配下にpackage単位のdirectory（`homeedit/`、`organizer/`、`ui/preferences/` 等）。`organizer-unit-tests` jobの `--tests` filterは `app.lawnchair.organizer.*`、`app.lawnchair.homeedit.*`、`app.lawnchair.ui.preferences.navigation.*`、`app.lawnchair.bugreport.*`、`app.lawnchair.backup.*`、`app.lawnchair.migration.*`、`app.lawnchair.DeviceProfileOverridesPresetResolutionTest`（354行）。`app.lawnchair.ui.popup.*` はfilter外。

### 推測で書いていないこと

- `PreferenceActivity` のmanifest上のexported（`lawnchair/AndroidManifest.xml` 61-63行付近で `exported="true"`）は確認済み。route extra経由の起動はonboarding実例が既にproductionで使っている。

## Design

### Modules and interfaces

- 変更するmodule: fork側のpopup項目定義（`LauncherOptionsPopup.kt`）とpopup編集画面（`LauncherPopupPreference.kt`）。新規string/drawable resource。
- 触れないmodule: `organizer/` 配下全部（coordinator/run/application）、`PreferenceRoutes.kt`/`PreferenceNavigation.kt`/`PreferenceActivity.kt`（routeと起動仕組みは既存のまま使う）、`OptionsPopupView.java` を含む `src/com/android/launcher3/**`、`LawnchairLauncher.kt`。
- seam: `OptionsPopupView.getOptions` → `LauncherOptionsPopup.getLauncherOptions`（既存seam。signature不変）。項目handlerは `LauncherOptionsPopup` 内のprivate関数（`launcher` を捕獲）とし、`OptionItem` はlabelResつきconstructorで構築する（accessibility action idのため）。

### Data flow

長押し → `showDefaultOptions` → `getLauncherOptions`（`launcherPopupOrder` 読み取り → lock/isEnabled/carouselフィルタ → `OptionItem` list）→ popup描画。項目tap → handler → `PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization())` → `startActivity` → handler true → popup close。設定側でrun面が開き、開始CTAから既存の `coordinator.start(trigger)`。本変更が書くのは `launcher_popup_order` の補完（起動時1回）のみ。

### Alternatives rejected

- **`OptionsPopupView.getOptions` へのcallback引数追加**（Issue Scope 1の予想形）: 機能上不要（handlerは `OptionsPopupView` 内部状態に依存しない）で、未patchのLauncher3/AOSP fileに新規patch surfaceを作るだけのため不採用。#449も同じ判断でhandlerをfork file内lambdaに閉じた。
- **popup項目からのrun自動開始**（handler内で直接 `coordinator.start`）: `start()` はrun面の開始行専用（T-07。hubのstart CTAと同じ制約）であり、admission gate（RUN lease・readiness）をpopupから再実装・迂回する形になるため不採用。Issue Scope 2の明示する方針。
- **`restoreMissingPopupOptions` のprepend維持**: upstream Lawnchair（15-dev）と #449 の実装はprependだが、既存利用者のpopup先頭に置かれ、edit_mode隣接の要求（Issue Scope 3）を満たさないため不採用。upstreamからの意図的な分岐であり、patch surface計測で追跡する（spec Prior art参照）。
- **編集系グループ内で `edit_surface` の直後に配置する案（`edit_mode, edit_surface, organize_home`）**: 既存（#449後）orderの `edit_surface` 位置を動かさない利点はあるが、Issue Scope 3が「ホームを整理」の `edit_mode` への隣接を明示的に要求しているため不採用。最終並びは `edit_mode, organize_home, edit_surface`（spec Scope参照）。
- **`Trigger` への新規値（導線由来の区別）**: diagnostics契約の語彙を増やしてまでの価値がなく、Issueも要求していないため不採用（Non-goalsに記録）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` | ① `DEFAULT_ORDER` へ `LauncherOptionPopupItem("organize_home", true)` を `edit_mode` と `edit_surface` の間に追加。② `getLauncherOptions` の `optionsList` へ `"organize_home" to OptionItem(launcher, R.string.home_screen_organize, R.drawable.ic_organize_home, LauncherEvent.IGNORE, handler)` を追加。handlerは同file内で `PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization())` を `startActivity` し true を返す（#449 の `edit_surface` handlerと同じfile内lambda形式）。③ lockフィルタへ `organize_home` を追加。④ `getMetadataForOption` へ分岐追加。⑤ `restoreMissingPopupOptions` をDEFAULT_ORDER相対の位置挿入へ変更（純粋関数として切り出す） | 項目定義の正本。#449がpatch済みのfileへの最小追加 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt` | `enabled` のwhen分岐へ `organize_home` を追加（`edit_mode`/`widgets` と同じロック中無効化） | 編集画面のロック表示整合 |
| `lawnchair/res/values/strings.xml` | `home_screen_organize` = "Organize home screen" を追加 | label正本 |
| `lawnchair/res/values-ja/strings.xml` | `home_screen_organize` = 「ホームを整理」を追加 | ja copy（#161 LQA運用） |
| `lawnchair/res/drawable/ic_organize_home.xml`（新規） | 単色24dp vector drawable | icon（`enter_home_gardening_icon`（edit_mode）・`ic_folder`（edit_surface）と区別可能。具象はreviewで確定） |
| `tests/unit/app/lawnchair/ui/popup/LauncherOptionsPopupOrderTest.kt`（新規・仮称。package `app.lawnchair.ui.popup`） | 位置挿入の決定性・既存項目不変性・3世代（#449前後・未保存）の補完結果・lockフィルタのJVM test | AC-1/AC-3/AC-4の自動oracle |

実装時に `app.lawnchair.ui.popup` packageのJVM testが恒久gate（`organizer-unit-tests` の `--tests` filter）に含まれない点に注意（filterは `app.lawnchair.organizer.*`、`app.lawnchair.homeedit.*`、`app.lawnchair.ui.preferences.navigation.*` 等。quality-strategy.md §Organizer unit-test CI gate）。実装PRでgate filterへ追加する場合はci.yml・quality-strategy.md mirror・portfolio監査表の同じPR更新を要する（§新規test審査ルール）。追加しない場合はfull unit test実行とreviewで担保する旨をPRへ記録する。

## Patch surface（AC-8の実装手順）

- `LauncherOptionsPopup.kt` は #449 により `homeedit-edit-surface` bridge groupへcounted済み。本Issueの追記は同groupのcounted growthになるため、PR本文にIssue #452所有のrationale（popup ENTRIES fileとして #449/#452 が共用する）を書き、baselineのexpected measurementを同じPRで再採択する（#450の「adopt the patch-surface baseline delta」手順に倣う）。
- `LauncherPopupPreference.kt` は本Issueで初めてfork patchされるため新規counted pathとなる。#452 所有のbridge group（例: `organizer-home-entry`）へ登録するか、既存group（`organizer-ui-and-lock-authoring`）の責務拡張として登録する。`--enforce-baseline` が通ることを同じPRで確認する。
- `values/strings.xml`・`values-ja/strings.xml` は既にcounted（`organizer-ui-and-lock-authoring`）。行追加は同じrationaleで扱う。

## Migration and recovery

- schema/rule migration: なし。
- preference補完: 起動時の `restoreMissingPopupOptions` が既存の呼び出し箇所（`LawnchairLauncher.kt:262`）で走る。書込みはDataStoreの既存経路、1回のみ、冪等（2回目はmissingなし）。
- failure中のrollback: 書込みがpreference stringのみのため、適用中途の状態は存在しない。handlerの失敗は `startActivity` 例外のみで、同一app内Activityのため発生経路がない（onboarding実例と同じ構造）。
- release rollback/downgrade: 旧buildではpopup描画は安全（`mapNotNull` で未知idを落とす）だがpopup編集画面が `getMetadataForOption` でcrashする既知制限（spec「Compatibility and rollback」参照）。一般解は本Issueの対象外。
- backup/restore: preference stringが既存経路でround-tripするのみ。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | JVM unit test（DEFAULT_ORDER構成・metadata分岐）+ 実機スクリーンショット | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.ui.popup.*'`（filter追加の判断は上記記載どおり） |
| AC-2 | 実機録画/スクリーンショット（run面が直接開く、backでlauncherへ戻る）。instrumentation testは `tests/organizer-instrumentation/app/lawnchair/ui/preferences/**` へ追加可能（surface_organizer_uiで発火する既存lane） | organizer-instrumentation（manual-organization-ui lane） |
| AC-3 | JVM unit test（lockフィルタ）+ 実機スクリーンショット | 同上 + 実機 |
| AC-4 | JVM unit test（位置挿入: #449前のorder（両方欠落）・#449後のorder（prepend済みedit_surface含む）・並べ替え済みorder・一部無効orderでの決定性と既存項目の不変性） | 同AC-1 |
| AC-5 | 実機の録画またはスクリーンショット（3操作の勘定を明記） | 実機（emulatorは補助） |
| AC-6 | 実機のTalkBack/Switch Access確認。labelResつきconstructorはcode review | 実機 |
| AC-7 | PR diffのpath列挙 | `git diff --name-only <base>..<head>` |
| AC-8 | patch surface計測 | `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` |

含める観点: UI/accessibility（AC-5/6）、失敗系（Scenario「状態を書かない」のdiff確認、AC-7）。DB/integration・performance・failure injectionは書込み経路がないため対象外（理由をPRへ記録）。

## Documentation updates

- [ ] spec status/history（実装PRで `implemented` へ）
- [ ] CONTEXT.md: 変更なし（新domain用語なし。「workspace options popup」は既存語の組合せ）
- [ ] DESIGN.md: 変更なし（module構造不変。UI adapterの導線追加のみ）
- [ ] ADR: 不要（3条件を満たさない。到達方法の判断根拠はspec/planに記録済み）
- [ ] AGENTS.md / building guide: 変更なし（新規commandなし）
- [ ] `docs/engineering/ci-test-portfolio.md` + `tools/repo-contract/ci_portfolio_map.yml`: JVM testをgate filterへ追加した場合のみ

## Execution checklist

- [ ] 現在のpopup項目一覧・編集画面の挙動を再確認（本planのCurrent evidenceが現行codeと一致すること）
- [ ] 位置挿入のJVM testが先に失敗することを確認（red）
- [ ] 最小実装（DEFAULT_ORDER・optionsList・metadata・lockフィルタ・編集画面分岐・strings・drawable）
- [ ] 位置挿入の実装とtest green
- [ ] 実機evidence（AC-1/2/3/5/6）
- [ ] patch surface計測とPR本文への記録（AC-8）
- [ ] PR evidenceと残リスク（rollback制限・gate filter判断）を記録

## Incremental implementation order

1. `restoreMissingPopupOptions` の位置挿入を純粋関数として切り出し + JVM test（既存挙動を変える唯一の共有部分を先に固定する）。
2. popup項目本体（DEFAULT_ORDER・optionsList handler・metadata・lockフィルタ）+ strings/drawable。
3. popup編集画面のlock分岐。
4. 実機evidenceとpatch surface計測。

## Dependencies / blockers

- #443（実装済み）・#441（確定済み）に新規blockerなし。#449のspecとは並びの最終決定で協調するが、実装の先行/待機は保守者判断（spec Unresolved decisions参照）。

## Risk

- 既存利用者のpopup order変更が利用者に見える（新項目の挿入。#449のprependで `edit_surface` が先頭にある利用者では `organize_home` も先頭側に並ぶ）。誤挿入はorder破壊ではなく見た目の変化に留まる（stringのparse/writeは形式不変）。位置挿入の決定性をJVM testで固定する。
- `LauncherOptionsPopup.kt` は #449 でfork patch済み（`homeedit-edit-surface` group）。本Issueの追記はupstream同期時のconflict源を #449 分と共有する。`LauncherPopupPreference.kt` への新規patchは本Issueが初。patch surface計測（AC-8）で記録し、橋の所有をIssue #452に紐付ける。
- accessibilities: labelResなしconstructorを使うとa11y actionとして出現しない（実装規約としてplan/specに明記済み）。
- #449の `edit_surface` がpopup編集画面のロック無効化分岐に入っていない現状は、本Issueでは直さない（#449残課題。本Issueは `organize_home` の分岐のみ追加）。

## Explicitly unverified areas

- iconの具象designと英語copyの最終語（実装reviewで確定。spec Unresolved decisions）。
- JVM testの恒久gate filter取り込みの要否（実装PRで判断。CI/portfolio文書の同じPR更新を要する）。
- 実機でのpopup描画・TalkBack挙動（本taskは文書整備のみのため未実施。実装PRのevidenceで埋める）。
- `LauncherPopupPreference.kt` を新規bridge groupへ登録するか既存groupの責務拡張にするか（実装PRのpatch surface計測時に確定）。
