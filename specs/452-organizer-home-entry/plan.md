# Implementation Plan: ワークスペース長押しメニューの「ホームを整理」項目

> Issue: #452
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: M（spec冒頭のとおり。階層Mはplan.mdを要求しないが、Issue未解決事項の解消根拠と実装順序を残すために作成する）

## Current evidence

確認時点: `origin/main` = `c5a7840b880ed4c436b67170930ca87d4ef7f148`（2026-09-28）。行番号はこのcommitでの値。

### 関連code pathと現在の振る舞い（確認済み事実）

- `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt`
  - `DEFAULT_ORDER`（16-24行）: `carousel(+), lock(-), edit_mode(-), wallpaper(+), widgets(+), home_settings(+), sys_settings(-)`。lock/edit_mode/sys_settingsは新規install既定で無効。
  - `restoreMissingPopupOptions`（26-42行）: 保存済みorderに無いDEFAULT項目を **先頭にprepend** する。`LawnchairLauncher.kt:262` から起動時に1回呼ばれる。
  - `getLauncherOptions`（47-127行）: `lockHomeScreen` 有効時に `edit_mode`・`widgets` を隠すフィルタ（116-122行）、`isEnabled && identifier != "carousel"` フィルタ（112-115行）、`optionsList` map（66-109行）から `mapNotNull` で組む。
  - `getMetadataForOption`（129-169行）: popup編集画面用のlabel/icon。未知identifierは `IllegalArgumentException`。
  - このfileは **fork未patch**（Lawnchair上流v15 beta3時点と同一。`git diff --stat 505dbc40..HEAD -- <本file>` が空）。
- `src/com/android/launcher3/views/OptionsPopupView.java`（Launcher3/AOSP由来、fork未patch）
  - `getOptions`（199-209行）: `LauncherOptionsPopup.getLauncherOptions` へ6つのstatic handler（lock/sys_settings/edit_mode/wallpaper/widgets/home_settings）を渡す。
  - `handleViewClick`（100-113行）: handlerがtrueを返すとpopupを閉じる。
  - `OptionItem`（294-321行）: `labelRes` つきconstructor（`AccessibilityActionsView` がaction idに使う）とlabel文字列のみのconstructorがある。
- `src/com/android/launcher3/views/AccessibilityActionsView.java`（68-77, 80-100行付近）: `OptionsPopupView.getOptions(l)` の各項目を `item.labelRes` をidとするaccessibility actionとして公開し、`performAccessibilityAction` が同じclickListenerを呼ぶ。
- popupの出現経路（変更不要）: `WorkspaceTouchListener.java:223` と `KeyboardShortcutsDelegate.java:159` → `Launcher.showDefaultOptions`（`Launcher.java:2855-2858`）→ forkでは `LawnchairLauncher.showDefaultOptions`（364-376行）がcarousel有無で2分岐するが、両方 `OptionsPopupView.getOptions(this)` → fork `getLauncherOptions` を通る。
- `lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt`（33-70行）: `EXTRA_DESTINATION_ROUTE`（`app.lawnchair.ui.preferences.DESTINATION_ROUTE`）で渡された直列化routeを `startDestination` として開く `createIntent(context, destination)` を持つ。**Issue未解決事項1（popupからrun面への到達方法）はこの既存仕組みで解消する。**
- `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt`（152-169行）: `HomeScreenManualOrganization(entry = MANUAL既定, durableRecovery = false, exchangeOpen = null)` → `trigger = MANUAL_FULL`。`PreferenceNavigation.kt:126-138` がrouteから `ManualOrganizationPreferences(trigger = route.trigger, ...)` へ結線。
- launcher側からrun面を開く実例: `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt`（421-431行）が `launcher.startActivity(PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization(OrganizationEntry.ONBOARDING)))` で開く（flagなしのplain `startActivity`）。
- run面: `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`
  - 開始CTA（632-641行）: `execute { coordinator.start(trigger) }`。admission（RUN lease）はここだけが持つ。
  - #443実装（`ea8d57d068`、567-581行）: AI相談toggle OFF（既定）のとき `State.ScopeConfirmed` を自動で `planWithConfirmedScope()` へ進めるeffect。toggle読み取りはcomposition開始時1回（141-143行）。
  - 検出候補0件は空cutでscope凍結へ直行（spec 417/443の契約）。
- popup編集画面: `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt`（97-127行）: `getMetadataForOption` でlabel/iconを引き、`enabled` のwhen分岐（99-103行）で `edit_mode`/`widgets` は `lockHomeScreen` 中無効化、`home_settings` は常に無効化。`ReorderablePreferenceGroup(defaultList = LauncherOptionsPopup.DEFAULT_ORDER)`。このfileもfork未patch。
- preference: `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt`（348-352行）: `launcherPopupOrder`（string、defaultは `DEFAULT_ORDER.toOptionOrderString()`）。
- strings: `lawnchair/res/values/strings.xml`（organizer系は1030行以近）、`lawnchair/res/values-ja/strings.xml`。`edit_home_screen` = "Edit home screen"／「ホーム画面を編集」（`res/values*/strings.xml`。上流由来）。
- 上流patch surface baseline（`docs/assessment/upstream-patch-surface-baseline.json`）: `HomeScreenPreferences.kt`・`ManualOrganizationPreferences.kt`・`PreferenceRoutes.kt`・base `values/strings.xml`・`values-ja` は既にcounted。`LauncherOptionsPopup.kt`・`LauncherPopupPreference.kt`・`OptionsPopupView.java` は **未counted**（forkが未patchのため）。
- CI surface mapping（`.github/workflows/ci.yml` の `changes` filter）: `lawnchair/src/app/lawnchair/ui/**` と `lawnchair/res/**` は `surface_organizer_ui`。`LauncherOptionsPopup.kt`（`ui/popup/`）と `LauncherPopupPreference.kt`（`ui/preferences/destinations/`）はこのglobに含まれるためmappedであり、per-path fail-closedは発火しない。

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

- **`OptionsPopupView.getOptions` へのcallback引数追加**（Issue Scope 1の予想形）: 機能上不要（handlerは `OptionsPopupView` 内部状態に依存しない）で、未patchのLauncher3/AOSP fileに新規patch surfaceを作るだけのため不採用。
- **popup項目からのrun自動開始**（handler内で直接 `coordinator.start`）: `start()` はrun面の開始行専用（T-07。hubのstart CTAと同じ制約）であり、admission gate（RUN lease・readiness）をpopupから再実装・迂回する形になるため不採用。Issue Scope 2の明示する方針。
- **`restoreMissingPopupOptions` のprepend維持**: 既存利用者のpopup先頭に置かれ、edit_mode隣接の要求（Issue Scope 3）を満たさないため不採用。
- **`Trigger` への新規値（導線由来の区別）**: diagnostics契約（§3）の語彙を増やしてまでの価値がなく、Issueも要求していないため不採用（Non-goalsに記録）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` | ① `DEFAULT_ORDER` へ `LauncherOptionPopupItem("organize_home", true)` を `edit_mode` の直後に追加。② `getLauncherOptions` の `optionsList` へ `"organize_home" to OptionItem(launcher, R.string.home_screen_organize, R.drawable.ic_organize_home, LauncherEvent.IGNORE, handler)` を追加。handlerは同file内で `PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization())` を `startActivity` し true を返す。③ lockフィルタへ `organize_home` を追加。④ `getMetadataForOption` へ分岐追加。⑤ `restoreMissingPopupOptions` をDEFAULT_ORDER相対の位置挿入へ変更（純粋関数として切り出す） | 項目定義の正本。fork未patch fileへの最小変更 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt` | `enabled` のwhen分岐へ `organize_home` を追加（`edit_mode`/`widgets` と同じロック中無効化） | 編集画面のロック表示整合 |
| `lawnchair/res/values/strings.xml` | `home_screen_organize` = "Organize home screen" を追加 | label正本 |
| `lawnchair/res/values-ja/strings.xml` | `home_screen_organize` = 「ホームを整理」を追加 | ja copy（#161 LQA運用） |
| `lawnchair/res/drawable/ic_organize_home.xml`（新規） | 単色24dp vector drawable | icon（`enter_home_gardening_icon` と区別可能。具象はreviewで確定） |
| `tests/unit/app/lawnchair/ui/popup/LauncherOptionsPopupOrderTest.kt`（新規・仮称） | 位置挿入の決定性・既存項目不変性・lockフィルタのJVM test | AC-3/AC-4の自動oracle |

実装時に `app.lawnchair.ui.popup` packageのJVM testが恒久gate（`organizer-unit-tests` の `--tests` filter）に含まれない点に注意（filterは `app.lawnchair.organizer.*` 等。quality-strategy.md §Organizer unit-test CI gate）。実装PRでgate filterへ追加する場合はci.yml・quality-strategy.md mirror・portfolio監査表の同じPR更新を要する（§新規test審査ルール）。追加しない場合はfull unit test実行とreviewで担保する旨をPRへ記録する。

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
| AC-4 | JVM unit test（位置挿入: 既定order・並べ替え済みorder・一部無効order・欠落複数） | 同AC-1 |
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

- 既存利用者のpopup order変更が利用者に見える（新項目の挿入）。誤挿入はorder破壊ではなく見た目の変化に留まる（stringのparse/writeは形式不変）。位置挿入の決定性をJVM testで固定する。
- fork未patch file（`LauncherOptionsPopup.kt`・`LauncherPopupPreference.kt`）への新規patchはLawnchair上流同期時のconflict源。patch surface計測（AC-8）で記録し、橋の所有をIssue #452に紐付ける。
- accessibilities: labelResなしconstructorを使うとa11y actionとして出現しない（実装規約としてplan/specに明記済み）。

## Explicitly unverified areas

- iconの具象designと英語copyの最終語（実装reviewで確定。spec Unresolved decisions）。
- 編集系グループ内の最終並び（#449のspecと合わせて決定。同上）。
- JVM testの恒久gate filter取り込みの要否（実装PRで判断。CI/portfolio文書の同じPR更新を要する）。
- 実機でのpopup描画・TalkBack挙動（本taskは文書整備のみのため未実施。実装PRのevidenceで埋める）。
