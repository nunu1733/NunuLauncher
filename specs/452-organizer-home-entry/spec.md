---
issue: "#452"
status: draft
tier: M
requirements:
  - FR-022
updated: 2026-09-28
---

# ワークスペース長押しメニューの「ホームを整理」項目（organizer runへの直接導線）

> Risk tier: M — Issue本文の終了条件3（メモ§4.7）どおり、上流UIへのbridge（popupの項目追加。model、loader、DBに触れない）は階層Mである。Launcher DB書込み・schema migration・recovery store・上流model/loader bridgeを含まないため、本specは軽量spec形式（[spec-lite](../_template/spec-lite.md)）を基礎にする。同一PRで `tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の計測結果をPR本文へreportする（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
> Epic: [#439](https://github.com/nunu1733/NunuLauncher/issues/439)（再焦点化）。出典: 再焦点化方針メモ（2026-09-24承認、Revision 5）§4.5（R-7 Now「#452 導線」）、§4.9（FR-022）。
> 依存の実装状況: #443（CLOSED、main反映済み `ea8d57d068`。AI相談toggle既定OFF・OFF時の方法選択面スキンプログラム済み）と #441（CLOSED。操作数の定義は [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) §4 が正本）を前提とする。

## Problem

ホーム画面から直接届く整理の導線が存在しない。ホームの空きスペース長押しで出るworkspace options popup（`lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` が項目を定義）には整理への項目がなく、整理を始めるには「設定 → ホーム画面 → Organizer（hub）→『整理を開始』（run面へ遷移）→ run面の『整理を開始』」という中継を通る必要がある。「散らかったホームを整える」という編集負担の中心操作（FR-022）への導線が最も重い位置にある。

## Benchmark

本変更は純粋な導線の追加であり、B1〜B7の課題（[editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) §2）のいずれも直接の対象ではない。代わりに、同書§4が定義する副指標 **操作数**（利用者の手順の数。tapも長押しも1と数える。#452の「n操作以下」の数え方の正本）を指標とする。

- **目標（FR-022、メモ§4.5）**: 未配置候補がない場合、ホーム画面から確認面（整理案の確認）まで **4操作以下**。
- 本specの導線の固定手順: 空きスペース長押し（1）→「ホームを整理」項目tap（1）→ run面の開始CTA「整理を開始」tap（1）→ （検出 → 空cutのscope凍結 → #443 OFF既定による方法選択面スキップ → planning）→ 確認面到達。**3操作**であり目標を満たす。AI相談toggleがONの間は方法選択面を経由するため+1操作（4操作）で依然として目標内。

## Outcome

ワークスペースの空きスペース長押しメニュー（workspace options popup）に「ホームを整理」項目が現れ、既定で有効である。選ぶと設定Activityがorganizer run面（`HomeScreenManualOrganization` route。entry既定 `MANUAL`、trigger `MANUAL_FULL`）で直接開く。hubの中継を経ない。run開始の権限（admission、RUN lease）はrun面の開始CTAが持ち続け、本項目はrun面への到達だけを短縮する。Lawnchairのホーム画面ロック（`lockHomeScreen`）が有効な間は既存の `edit_mode`・`widgets` と同じく項目が出ない。

## Scope

- **popup項目の追加**（`LauncherOptionsPopup.kt`）:
  - **identifier**: `organize_home`。
  - **label**: 英語 "Organize home screen" / 日本語「ホームを整理」。`edit_home_screen`（"Edit home screen" /「ホーム画面を編集」）および #449 の視覚的編集画面入口（仮称「ホームを編集」）と区別できる語とする。日本語copyは #161（ja LQA）の運用に従う。
  - **icon**: fork側に新設する単色vector drawable（例: `lawnchair/res/drawable/ic_organize_home.xml`）。24dp単色で他のoption icon（`ic_lock`、`ic_widget` 等）と同_style。`enter_home_gardening_icon`（edit_mode）と視覚的に区別できること。具象designは実装reviewで確定する。
  - **既定の有効/無効**: `LauncherOptionPopupItem("organize_home", true)` — 既定で有効。Issue本文のOutcome「項目が現れ」および終了条件2（未設定状態からの操作数実測）が既定ONを要求するため。利用者は既存のpopup編集画面（`LauncherPopupPreference`）でoffにできる。
  - **stats log**: 既存のfork追加項目（`lock`、`wallpaper`）と同じ `LauncherEvent.IGNORE`。
- **DEFAULT_ORDERでの配置**: 既存 `carousel, lock, edit_mode, wallpaper, widgets, home_settings, sys_settings` のうち `edit_mode` の直後に挿入する（編集系グループとして隣接。メモ§4.3の入口方針）。#449の入口が加わる際の編集系グループ内の最終並びは #449 のspecと合わせて確定する（Unresolved decisions参照）。
- **run面への遷移（到達方法の確定。Issue未解決事項1の解消）**: 項目のclick handlerはfork側（`LauncherOptionsPopup`内）で定義し、`PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization())`（`lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt` の `EXTRA_DESTINATION_ROUTE` 仕組み）で設定Activityをrun面から直接開く。実例は onboarding提案の導線（`OrganizationOnboardingProposal.kt` が同じrouteを同じ方法で開く）。上流 `OptionsPopupView.getOptions`（`src/com/android/launcher3/views/OptionsPopupView.java:199-209`）へのcallback引数追加は **行わない**。handlerは `OptionsPopupView` 側の内部状態に依存せず `launcher` だけで完結するため、未patchの上流fileに触れる理由がない（AGENTS.md「Launcher3/AOSP由来コードへの変更はbridgeとなる最小箇所に限定」）。`LawnchairLauncher.showDefaultOptions` の両分岐（carousel有無）は既に `OptionsPopupView.getOptions` → fork `getLauncherOptions` 経由であり、変更不要。
- **lockHomeScreen中の非表示（メモ§4.5で確定済み）**: `getLauncherOptions` の既存フィルタ（`edit_mode`・`widgets` を隠す箇所）へ `organize_home` を追加する。popup編集画面（`LauncherPopupPreference`）のswitchも `edit_mode`・`widgets` と同じ扱い（ロック中は無効化し「ホーム画面はロックされています」の説明を表示）にする。ADR-0004のロック意味論（organizer run自体はロックを尊重）は変更しない。
- **既存利用者への補完（`restoreMissingPopupOptions` の扱いの確定。Issue Scope 1の未確定項目）**: 現行の補完は「欠落項目を先頭にprepend」であるため、そのままでは既存利用者のpopup先頭に置かれ、edit_mode隣接の要求を満たさない。補完を **DEFAULT_ORDER相対の位置挿入** へ変更する: 欠落したdefault項目を、現在のorder内で「DEFAULT_ORDER上で自分より後に現れる最初の項目」の直前に挿入する（そのような項目がなければ末尾に追加する）。挿入される項目のenabledはDEFAULT_ORDERの値を使い、既存項目のorder・enabledは一切変更しない。この決定は将来の項目追加（#449の入口を含む）にも同じ規則を与える。

## Non-goals

- 方法選択面の省略・toggle（#443が所有。実装済み・既定OFF）。
- 視覚的編集画面の入口（#449。本Issueは並びの案のみ提供）。
- hubのstart CTA・run面のadmission契約の変更。popup項目からのrun自動開始（`start()` はrun面の開始行専用のまま。T-07）。
- 未配置候補の選択面・scope確定・確認面・適用の契約変更。
- `Trigger` enum（diagnostics契約）への新規値追加。popup経由のrunも既定の `MANUAL_FULL` で記録され、導線由来の区別はdiagnostics上しない。
- `OptionsPopupView.java`（Launcher3/AOSP由来）への変更。
- `organizer/` 配下（coordinator、run、application module）への変更。
- popup編集画面の新規機能（drag並べ替え等）の追加。`getMetadataForOption` の未知identifierに対するfail-soft化（後述のrollback制限の一般解）は本Issueでは行わない。

## Behavior scenarios

### Scenario: 既定状態で項目が現れ、run面へ直接届く（正常系）

Given `lockHomeScreen` が無効で、「ホームを整理」が有効（既定）である
When ワークスペースの空きスペースを長押しし、「ホームを整理」をtapする
Then popupが閉じ、設定Activityがorganizer run面（entry face。スコープ要約と開始CTA「整理を開始」を表示）で開く
And hubは経由しない（backで設定Activityを閉じるとlauncherへ戻る。hubのback stackは作らない）
And runのadmission・開始・検出・適用の契約は一切変わらない

### Scenario: 3操作で確認面へ届く（#443 OFF既定・未配置候補なし）

Given AI相談toggleがOFF（既定）で、未配置候補が0件の状態でrunを開始した
When 開始CTA「整理を開始」をtapする
Then 検出の空cutがscope凍結へ直行し、方法選択面を出ず「このまま整理」の流れ（planning）へ進み、確認面に到達する
And ホーム画面からの手順は「長押し（1）→ 項目tap（1）→ 開始tap（1）」の3操作である（#443 ON時は方法選択の1 tapが加わり4操作。いずれも目標4以下）

### Scenario: ホーム画面ロック中有効中は項目が出ない

Given `lockHomeScreen` が有効である
When ワークスペースの空きスペースを長押しする
Then popupに「ホームを整理」が現れない（`edit_mode`・`widgets` と同じフィルタ）
And popup編集画面では「ホームを整理」のswitchが無効化され、「ホーム画面はロックされています」の説明が表示される
Then ロック解除後は項目が再び現れる（preferenceのenabled状態は保持される）

### Scenario: 既存利用者のpopup orderへ位置を保って補完される

Given 更新前のbuildで `launcher_popup_order` が保存済み（`organize_home` を含まない）である
When 更新後のbuildでlauncherが起動する（`restoreMissingPopupOptions` が走る）
Then 「ホームを整理」が、当該order内でDEFAULT_ORDER相対の位置（既定orderを維持している利用者では `edit_mode` の直後）に挿入され、有効になる
And 既存項目の並び順とenabled/disabledは変化しない

### Scenario: 利用者が項目をoffにした場合は現れない

Given popup編集画面で「ホームを整理」をoffにしている
When ワークスペースの空きスペースを長押しする
Then popupに「ホームを整理」が現れない（既存の `isEnabled` フィルタ。他の項目と同じ挙動）

### Scenario: 項目からの遷移は状態を書かない（失敗・競合時の帰着）

Given organizer runが進行中、またはrun面がIdle、またはdurable状態が任意である
When popupの「ホームを整理」をtapする
Then 行われるのは設定Activityのrun面を開く遷移のみであり、run状態・RUN lease・durable状態・preference（`launcher_popup_order` 以外）への書込みはない
And runがBusy（RUN lease保持中）の場合も、run面は現行どおりそのrunの状態を表示し、admissionの再実装・迂回は行われない

### Scenario: キーボード/アクセシビリティ経由でも同じ導線が使える

Given options popupがキーボードshortcut（`KeyboardShortcutsDelegate`）またはaccessibility actions（`AccessibilityActionsView`）から開かれた
When 「ホームを整理」に相当するactionが選択される
Then 通常のtapと同じhandlerが動作し、run面が開く

## Data and state

- 読むdata: `launcherPopupOrder`（`PreferenceManager2`。string preference）、`lockHomeScreen`（既存読み取り）。
- 書くdata: `launcher_popup_order` のみ（既存利用者への位置補完時に1回。`restoreMissingPopupOptions` はlauncher起動時に既に呼ばれている箇所で走る）。書込みはDataStore経由の既存経路を使い、形式（`+id|-id` を `|` 連結）は変更しない。
- layout DBへの書込み: なし。runの書込み経路（admission・適用）は既存のまま。
- migration: なし（schema変更なし）。preferenceのdefault値は `LauncherOptionsPopup.DEFAULT_ORDER.toOptionOrderString()` から自動的に新項目を含む。

## Compatibility and rollback

- **新項目の追加はadditive**: 保存済みorder stringに新identifierが加わるだけで、形式・既存項目の解釈は不変。
- **旧buildへのrollback**: 旧buildは `getLauncherOptions` で未知identifierを `mapNotNull` で落とすためpopup描画は安全だが、popup編集画面（`LauncherPopupPreference` → `getMetadataForOption`）は未知identifierへ `IllegalArgumentException` を投げるため、新buildの使用歴がある端末を旧buildへ戻すとpopup編集画面の開示がcrashする。これは上流Lawnchairが項目追加時（例: carousel）から持つ既存のhazard classであり、本Issueで一般解（fail-soft化）は行わない（Non-goals）。制限として記録する。
- **backup/restore**: `launcher_popup_order` は既存のpreference backup経路でround-tripする。新version間のrestoreでは項目と順序が保存される。旧versionへのrestore先では上記rollback制限と同じ状態になる。

## Privacy and security

- 新規permission、外部送信、sensitive dataの追加: なし。項目のhandlerは同一app内の設定Activityを開くだけである。
- `PreferenceActivity` は `exported="true"` の既存設定Activityであり、`EXTRA_DESTINATION_ROUTE` 経由の起動は onboarding提案が既に使っている仕組みである。routeはrun状態も書込み権限も持たない（`PreferenceRoutes.kt` の規約どおり）。本変更は外部から見える攻撃面を増やさない。

## Accessibility and localization

- 項目は既存のoption項目と同じ `OptionItem`（labelResつきconstructor）で構成する。`AccessibilityActionsView.createAccessibilityNodeInfo` は `getOptions` の各項目を `item.labelRes` をaction idとするaccessibility actionとして公開するため、TalkBack・Switch Accessから既存項目と同等の読み上げ・選択で到達できる（`performAccessibilityAction` が同じhandlerを呼ぶ）。labelResなしconstructorを使うとaction idが0になり到達できないため、実装はlabelResつきconstructorを必須とする。
- popup編集画面の行は既存 `ReorderableSwitchPreference` のsemantics（label・説明・switch状態）に従う。
- 文言は `lawnchair/res/values/strings.xml` に英語を追加し、`values-ja/strings.xml` に「ホームを整理」を追加する（#448/#443と同じ運用）。

## Dependencies

- #443（CLOSED・実装済み）: OFF時導線（方法選択面スキップ）が操作数目標の後半を担う。本specは実装済み挙動を前提にするのみ。
- #441（CLOSED）: 操作数の定義（benchmark §4）を引用する。
- 関連: #449（OPEN。長押しメニューの並びを共有する。#449のspecは未作成）、#453（OPEN。導線とは独立）、ADR-0014（accepted。#449の入口方針の文脈）。
- 被依存: なし。

## Acceptance criteria

- [ ] AC-1: workspace options popupに「ホームを整理」が既定で有効に現れ、`DEFAULT_ORDER` 上 `edit_mode` の直後に配置される。identifierは `organize_home`。label・iconはScopeの定義に従う。
- [ ] AC-2: 項目をtapすると、設定Activityが `HomeScreenManualOrganization`（entry=MANUAL既定）のrun面で直接開く。hubを経由しない。backでlauncherへ戻る。runのadmission・開始契約に変更がない（`start()` はrun面の開始行のみ）。
- [ ] AC-3: `lockHomeScreen` 有効中、popupに項目が出ない。popup編集画面ではswitchが無効化され、既存項目と同じロック説明が表示される。解除で再表示する。
- [ ] AC-4: 既存利用者（保存済みorderに `organize_home` なし）の起動時補完がDEFAULT_ORDER相対の位置挿入で行われ、既存項目のorder・enabledが変化しない。既定orderの利用者では `edit_mode` 直後に挿入される。
- [ ] AC-5: 実機（emulatorは補助証跡のみ）で、未配置候補0件・AI相談toggle OFF（既定）の状態から「長押し → 項目tap → 開始tap → 確認面到達」の3操作を録画またはスクリーンショットで記録し、4操作以下であることを示す（Issue終了条件2）。
- [ ] AC-6: TalkBack・Switch Accessで項目に到達でき、既存options項目と同等の読み上げができる（labelResつきconstructorの使用を含む）（Issue終了条件4）。
- [ ] AC-7: 変更diffが書込み経路を追加しないことの確認: 触れるfileはpopup項目定義・popup編集画面・string/drawable resource（およびtest）に限り、`organizer/` 配下、`src/com/android/launcher3/model/**`、DB・migrationに関わるpathを含まない。
- [ ] AC-8: 同一PRで `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の計測結果をPR本文へreportする。`LauncherOptionsPopup.kt`・`LauncherPopupPreference.kt` は現在fork未patchのLawnchair側fileであり、新たにcounted pathとなる場合はbridge所有の記録（Issue #452）を伴う（Issue終了条件3）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | JVM unit test（DEFAULT_ORDERの構成・`getMetadataForOption` の分岐）+ 実機スクリーンショット（popup表示・配置） |
| AC-2 | instrumentation test（項目tapで `HomeScreenManualOrganization` のdestinationが開く。run面のface描画）または実機録画（AC-5と同一evidenceで代用可） |
| AC-3 | JVM unit test（`getLauncherOptions` のlockフィルタ）+ 実機スクリーンショット（ロック中のpopup・編集画面） |
| AC-4 | JVM unit test（`restoreMissingPopupOptions` の位置挿入: 既定order・並べ替え済みorder・一部無効orderでの決定性と既存項目の不変性） |
| AC-5 | 実機の録画またはスクリーンショット（操作数の勘定を明記） |
| AC-6 | 実機確認（TalkBack/Switch Access）。labelResつきconstructorの使用はcode reviewで確認 |
| AC-7 | PR diffのpath列挙 |
| AC-8 | 計測commandの出力（PR本文） |

## Unresolved decisions

- **#449の入口を含む編集系グループ内の最終並び**: 本specは「`edit_mode` の直後」に固定する。#449の入口（「ホームを編集」）が加わる時点で3項目のグループ内並びを #449 のspecと合わせて最終決定する（Issue Scope 3の取り決め）。#449の実装前に本項目だけを先に入れるか（並びの変更が利用者に2回見える）待つかは保守者の順序判断に委ねる（Issue未解決事項3）。
- **iconの具象design と英語copyの最終語**: 制約（単色24dp・`enter_home_gardening_icon` と区別可能／`edit_home_screen` と区別可能なlabel）のみ確定し、具象は実装PRのreviewで確定する。

## Change history

- 2026-09-28: Draft created for #452（Issue未解決事項1「popupからrun面への到達方法」を `PreferenceActivity.createIntent` + onboarding実例の確認により解消、`restoreMissingPopupOptions` の扱いを位置挿入へ決定、`OptionsPopupView.java` 無変更の方針を記録）。
