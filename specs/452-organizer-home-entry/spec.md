---
issue: "#452"
status: implemented
tier: M
requirements:
  - FR-022
updated: 2026-09-30
---

# ワークスペース長押しメニューの「ホームを整理」項目（organizer runへの直接導線）

> Risk tier: M — Issue本文の終了条件3（メモ§4.7）どおり、上流UIへのbridge（popupの項目追加。model、loader、DBに触れない）は階層Mである。Launcher DB書込み・schema migration・recovery store・上流model/loader bridgeを含まないため、本specは軽量spec形式（[spec-lite](../_template/spec-lite.md)）を基礎にする。同一PRで `tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の計測結果をPR本文へreportする（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
> Epic: [#439](https://github.com/nunu1733/NunuLauncher/issues/439)（再焦点化）。出典: 再焦点化方針メモ（2026-09-24承認、Revision 5）§4.5（R-7 Now「#452 導線」）、§4.9（FR-022）。
> 依存の実装状況: #443（CLOSED、main反映済み `ea8d57d068`。AI相談toggle既定OFF・OFF時の方法選択面スキンプログラム済み）、#441（CLOSED。操作数の定義は [editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) §4 が正本）、および #449（CLOSED、PR #476 merge済み。長押しメニューの `edit_surface` 項目がmainに存在する）を前提とする。

## Problem

ホーム画面から直接届く整理の導線が存在しない。ホームの空きスペース長押しで出るworkspace options popup（`lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` が項目を定義）には整理への項目がなく、整理を始めるには「設定 → ホーム画面 → Organizer（hub）→『整理を開始』（run面へ遷移）→ run面の『整理を開始』」という中継を通る必要がある。「散らかったホームを整える」という編集負担の中心操作（FR-022）への導線が最も重い位置にある。

## Benchmark

本変更は純粋な導線の追加であり、B1〜B7の課題（[editing-burden-benchmark](../../docs/engineering/editing-burden-benchmark.md) §2）のいずれも直接の対象ではない。代わりに、同書§4が定義する副指標 **操作数**（利用者の手順の数。tapも長押しも1と数える。#452の「n操作以下」の数え方の正本）を指標とする。

- **目標（FR-022、メモ§4.5）**: 未配置候補がない場合、ホーム画面から確認面（整理案の確認）まで **4操作以下**。
- 本specの導線の固定手順: 空きスペース長押し（1）→「ホームを整理」項目tap（1）→ run面の開始CTA「整理を開始」tap（1）→ （検出 → 空cutのscope凍結 → #443 OFF既定による方法選択面スキップ → planning）→ 確認面到達。**3操作**であり目標を満たす。AI相談toggleがONの間は方法選択面を経由するため+1操作（4操作）で依然として目標内。

## Prior art

- AOSP Launcher3 `OptionsPopupView.java`（対象: `packages/apps/Launcher3/src/com/android/launcher3/views/OptionsPopupView.java` / URL: https://cs.android.com/android/platform/superproject/main/+/main:packages/apps/Launcher3/src/com/android/launcher3/views/OptionsPopupView.java / 確認日: 2026-09-30）— workspace長押しpopupの項目は `OptionItem(labelRes, icon, event, handler)` で構成し、設定系項目（Home settings）はpopupから直接settings activityを開く。採用: 本項目も同じ `OptionItem` 構成（labelResつきconstructor）で設定Activityのrun面を直接開く、という上流と同一のpatternに乗せる。repository内に同一seamがあるため外部例より既存architectureとの整合を優先した（workflow正本の規定どおり）。
- Lawnchair upstream `LauncherOptionsPopup.kt`（対象: upstream 15-dev branch / URL: https://github.com/LawnchairLauncher/lawnchair/blob/15-dev/lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt / 確認日: 2026-09-30）— upstreamの `restoreMissingPopupOptions` は欠落項目を先頭にprependし、organize系項目は存在しない。採用: 本specのDEFAULT_ORDER相対の位置挿入はupstream挙動からの意図的なfork分岐であると記録し、upstream同期時のconflict源としてpatch surface計測（AC-8）で追跡する。upstream側に倣う理由（prepend）は、edit_mode隣接のIssue要求（Scope 3）と衝突するため不採用。

## Outcome

ワークスペースの空きスペース長押しメニュー（workspace options popup）に「ホームを整理」項目が現れ、既定で有効である。選ぶと設定Activityがorganizer run面（`HomeScreenManualOrganization` route。entry既定 `MANUAL`、trigger `MANUAL_FULL`）で直接開く。hubの中継を経ない。run開始の権限（admission、RUN lease）はrun面の開始CTAが持ち続け、本項目はrun面への到達だけを短縮する。Lawnchairのホーム画面ロック（`lockHomeScreen`）が有効な間は既存の `edit_mode`・`widgets` と同じく項目が出ない。

## Scope

- **popup項目の追加**（`LauncherOptionsPopup.kt`）:
  - **identifier**: `organize_home`。
  - **label（確定）**: 英語 "Organize home screen"（string resource `home_screen_organize`）/ 日本語「ホームを整理」。隣接する2項目 — `edit_home_screen`（"Edit home screen" /「ホーム画面を編集」）と #449 の `edit_surface_menu_open`（"Edit layout grid" /「編集画面」）— のいずれとも区別できる語であり、本語を最終語として確定する（Issue終了条件1の「labelの確定」への対応。Phase 2で再判断しない）。日本語copyは #161（ja LQA）の運用に従う。
  - **icon（確定）**: fork側に新設する単色vector drawable `lawnchair/res/drawable/ic_organize_home.xml`。glyphは **2×2のrounded-square grid（整列されたセル）+ 右上に4-point sparkle**（自動整理のmetaphor）と確定する。24dp・viewport 24x24・`android:tint="?android:attr/textColorPrimary"` で `ic_folder`（edit_surface）と同style。`enter_home_gardening_icon`（edit_mode）・`ic_folder`（edit_surface）・`ic_widget`・`ic_lock`・`ic_setting`・`ic_palette`/`ic_wallpaper`・`ic_home_screen` のいずれとも視覚的に区別できる。path dataの実装はPhase 2で行うが、glyph・意味・resource名をこのとおり確定する（Issue終了条件1の「iconの確定」への対応）。
  - **既定の有効/無効**: `LauncherOptionPopupItem("organize_home", true)` — 既定で有効。Issue本文のOutcome「項目が現れ」および終了条件2（未設定状態からの操作数実測）が既定ONを要求するため。利用者は既存のpopup編集画面（`LauncherPopupPreference`）でoffにできる。
  - **stats log**: 既存のfork追加項目（`lock`、`wallpaper`）と同じ `LauncherEvent.IGNORE`。
- **DEFAULT_ORDERでの配置（#449の実装を受けて確定）**: 現行mainの `DEFAULT_ORDER` は #449（PR #476）により `carousel, lock, edit_mode, edit_surface, wallpaper, widgets, home_settings, sys_settings` になっている。本Issueは `edit_mode` と `edit_surface` の間に `organize_home` を挿入し、最終形を `carousel, lock, edit_mode, organize_home, edit_surface, wallpaper, widgets, home_settings, sys_settings` とする。根拠はIssue Scope 3の取り決めそのものである: 「ホームを整理」は `edit_mode` の近くに置く（編集系の操作として隣接させる）、#449の入口は「その隣」に置き、2項目（organize_home と edit_surface）が編集系のグループを成す。この配置により、整理（全体の再配置・安全な適用）と編集画面（項目単位・複数選択）の違いはlabel・説明文でのみ区別する。#449のspec・実装は `edit_surface` の位置（`edit_mode` の直後）以外の並びを固定しておらず、本specがIssue Scope 3の取り決めに従って編集系グループ内の最終並びを確定する。
- **run面への遷移（到達方法の確定。Issue未解決事項1の解消）**: 項目のclick handlerはfork側（`LauncherOptionsPopup`内）で定義し、`PreferenceActivity.createIntent(launcher, HomeScreenManualOrganization())`（`lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt` の `EXTRA_DESTINATION_ROUTE` 仕組み）で設定Activityをrun面から直接開く。実例は onboarding提案の導線（`OrganizationOnboardingProposal.kt` が同じrouteを同じ方法で開く）。上流 `OptionsPopupView.getOptions`（`src/com/android/launcher3/views/OptionsPopupView.java:199-209`）へのcallback引数追加は **行わない**。handlerは `OptionsPopupView` 側の内部状態に依存せず `launcher` だけで完結するため、未patchの上流fileに触れる理由がない（AGENTS.md「Launcher3/AOSP由来コードへの変更はbridgeとなる最小箇所に限定」）。`LawnchairLauncher.showDefaultOptions` の両分岐（carousel有無）は既に `OptionsPopupView.getOptions` → fork `getLauncherOptions` 経由であり、変更不要。
- **lockHomeScreen中の非表示（メモ§4.5で確定済み）**: `getLauncherOptions` の既存フィルタ（`edit_mode`・`widgets`・`edit_surface`（#449追加）を隠す箇所）へ `organize_home` を追加する。popup編集画面（`LauncherPopupPreference`）のswitchも `edit_mode`・`widgets` と同じ扱い（ロック中は無効化し「ホーム画面はロックされています」の説明を表示）にする。なお現行mainの `LauncherPopupPreference` のロック無効化分岐は `edit_mode`・`widgets` のみで、`edit_surface` は無効化対象に入っていない（#449はpopup側フィルタのみを要求した）が、これは #449 の残課題であり本Issueでは `organize_home` の分岐追加のみを行う。ADR-0004のロック意味論（organizer run自体はロックを尊重）は変更しない。
- **既存利用者への補完（`restoreMissingPopupOptions` の扱いの確定。Issue Scope 1の未確定項目）**: 現行（#449後のmainを含む）の補完は「欠落項目を先頭にprepend」であり、かつ **missing項目が空でも無条件に `launcherPopupOrder` へ書き戻す**。#449もこのprependで `edit_surface` を補完したため、#449のupgrade時に保存済みorderを持つ利用者には `edit_surface` が先頭側に補完されている状態があり得る。本Issueは補完を **DEFAULT_ORDER相対の位置挿入** へ変更する: 欠落したdefault項目を、現在のorder内で「DEFAULT_ORDER上で自分より後に現れる最初の項目」の直前に挿入する（そのような項目がなければ末尾に追加する）。欠落項目が複数ある場合はDEFAULT_ORDERの順に1個ずつ挿入する。挿入される項目のenabledはDEFAULT_ORDERの値を使い、既存項目のorder・enabledは一切変更しない。あわせて **書込みguard** を追加する: 位置挿入の結果が現在のorderと等しい（欠落なし・未保存default利用者）場合は `launcherPopupOrder` へ書かない。これにより書込みは実際に補完が発生するupgrade時のみ1回となる（現行の起動ごとの無条件writeをやめる）。#449のprependで `edit_surface` が先頭に補完済みのorderでは、本規則により `organize_home` はその `edit_surface` の直前（先頭側）に挿入される。編集系グループから離れた位置への挿入は、既存項目のorderを勝手に書き換えないこと（利用者が意図した並びの保存）より優先した結果である。この決定は将来の項目追加にも同じ規則を与える。

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

### Scenario: ホーム画面ロック中は項目が出ない

Given `lockHomeScreen` が有効である
When ワークスペースの空きスペースを長押しする
Then popupに「ホームを整理」が現れない（`edit_mode`・`widgets` と同じフィルタ）
And popup編集画面では「ホームを整理」のswitchが無効化され、「ホーム画面はロックされています」の説明が表示される
Then ロック解除後は項目が再び現れる（preferenceのenabled状態は保持される）

### Scenario: 既存利用者のpopup orderへ位置を保って補完される

Given 更新前のbuildで `launcher_popup_order` が保存済み（`organize_home` を含まない）である
When 更新後のbuildでlauncherが起動する（`restoreMissingPopupOptions` が走る）
Then 「ホームを整理」が、当該order内でDEFAULT_ORDER相対の位置に挿入され、有効になる
And 既存項目の並び順とenabled/disabledは変化しない

補完結果は保存済みorderの世代で次のとおり決定的である:

- #449より前に保存されたorder（`edit_surface` も `organize_home` もない）: 両方がDEFAULT_ORDER相対で挿入され、既定order相当なら `edit_mode, organize_home, edit_surface, wallpaper, ...` となる。
- #449以降・本Issueより前に保存されたorder（`edit_surface` あり。prepend補完で先頭にあるものを含む）: `organize_home` は現在のorder内の `edit_surface` の直前に挿入される（prepend補完で `edit_surface` が先頭にある利用者では先頭2項目が `organize_home, edit_surface` になる）。
- 一度も保存していない利用者: default値（新しい `DEFAULT_ORDER`）がそのまま使われ、位置挿入の結果が現在のorderと等しいため **書込みは発生しない**（書込みguard）。

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
- 書くdata: `launcher_popup_order` のみ（既存利用者への位置補完時に1回。`restoreMissingPopupOptions` はlauncher起動時に既に呼ばれている箇所で走る）。現行実装はmissingなしでも無条件に書き戻すため、本Issueは「位置挿入の結果が現在のorderと等しければ書かない」guardを仕様として追加する（上記Scope）。書込みはDataStore経由の既存経路を使い、形式（`+id|-id` を `|` 連結）は変更しない。guard付きの冪等性: 補完が発生したupgrade時の1回のみ書き、以降の起動では結果が等しくなり書かない。
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
- #449（CLOSED・PR #476で実装済み）: 長押しメニューの `edit_surface` 項目が存在する。編集系グループ内の最終並び（`edit_mode, organize_home, edit_surface`）は本specがIssue Scope 3の取り決めに従って確定した。`LauncherPopupPreference` のロック無効化分岐に `edit_surface` が入っていない点は #449 側の残課題として観察記録のみ行う（本Issueは `organize_home` の分岐のみ追加）。
- 関連: #453（OPEN。導線とは独立）、ADR-0014（accepted。#449の入口方針の文脈）。
- 被依存: なし。

## Acceptance criteria

- [ ] AC-1: workspace options popupに「ホームを整理」が既定で有効に現れ、`DEFAULT_ORDER` 上 `edit_mode` と `edit_surface` の間に配置される（最終形: `carousel, lock, edit_mode, organize_home, edit_surface, wallpaper, widgets, home_settings, sys_settings`）。identifierは `organize_home`。labelは確定値 `home_screen_organize`（EN "Organize home screen" / JA「ホームを整理」）、iconは確定値 `ic_organize_home`（2×2 rounded-square grid + 右上4-point sparkle。Scopeの定義）。
- [ ] AC-2: 項目をtapすると、設定Activityが `HomeScreenManualOrganization`（entry=MANUAL既定）のrun面で直接開く。hubを経由しない。backでlauncherへ戻る。runのadmission・開始契約に変更がない（`start()` はrun面の開始行のみ）。
- [ ] AC-3: `lockHomeScreen` 有効中、popupに項目が出ない。popup編集画面ではswitchが無効化され、既存項目と同じロック説明が表示される。解除で再表示する。
- [ ] AC-4: 既存利用者（保存済みorderに `organize_home` なし）の起動時補完がDEFAULT_ORDER相対の位置挿入で行われ、既存項目のorder・enabledが変化しない。#449より前のorder（`edit_surface` なし）では両項目が位置挿入され、#449以降のorder（`edit_surface` あり・prepend済みを含む）では `organize_home` が `edit_surface` の直前に挿入される（Scenarioの3世代の決定性をJVM testで固定する）。位置挿入の結果が現在のorderと等しい場合（欠落なし・未保存default）は `launcher_popup_order` への書込みが発生しない（書込みguard。merge純粋関数の等価戻り値をJVM testで、guardのwiringをcode reviewで確認する）。
- [ ] AC-5: 実機（物理端末。emulatorは補助証跡）で、**利用者が置かれた実条件のまま**（未配置候補の有無を問わない。AI相談toggleはOFF既定）ホーム画面長押し →「ホームを整理」→（run開始）→ 確認面までの操作数（手順の数。tapも長押しも1）を録画またはスクリーンショットで記録し、**4操作以下**であることを示す。実機で選択面が介在する場合、その1操作を勘定に含めて4操作以下であることを記録する。operational bar（≤4操作）はIssue終了条件2から引き継ぐが、**「未配置候補0件」の前提はowner判断によりsupersedeした**（[判断記録](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5923580963)。日常端末では候補0件を作れないため）。あわせて、**未配置候補0件・AI相談OFFの正規パス3操作**（「長押し → 項目tap → 開始tap → 確認面到達」）を別の記録でcross-checkする（環境は物理端末を優先するが、候補0件の端末がない場合はemulator録画/スクリーンショットでよい）。
- [ ] AC-6: **実機TalkBackで項目を利用して起動できること**（TalkBack有効下でpopupから「ホームを整理」へ到達し、起動してrun面が開く記録）+ 項目が **既存options項目と同じ公開機構**（labelResつき `OptionItem` constructor、`AccessibilityActionsView` が同じlabelResをaction id・読み上げlabelに使用するnode tree）で公開されていることの構造確認。TTS音声そのものの録音記録は要求しない（labelの等価性は機構の同一性で担保する）。Switch AccessはTalkBackと同一のaccessibility node treeを走査するため、上記の構造確認をもって到達機構の同一性確認とし、端末でのSwitch Accessの手動確認は **owner判断で省略できる**（省略する場合はその判断をIssueへ記録する。#449のTalkBack読み上げ省略判断の先例と同じ運用）（Issue終了条件4）。
- [ ] AC-7: 変更diffが書込み経路を追加しないことの確認: 触れるfileはpopup項目定義・popup編集画面・string/drawable resource（およびtest）に限り、`organizer/` 配下、`src/com/android/launcher3/model/**`、DB・migrationに関わるpathを含まない。
- [ ] AC-8: 同一PRで `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline` の計測結果をPR本文へreportし、counted growthに対するIssue所有のrationaleとbaselineの再採択を同じPRで行う。現行のbaseline状況: `LauncherOptionsPopup.kt` は #449 により既に `homeedit-edit-surface` bridge groupへcounted済み（本Issueは同fileへの追記でgroupの成長。popupの入口定義fileとして #449/#452 が共用する旨をrationaleに記す）、`lawnchair/res/values/strings.xml`・`values-ja/strings.xml` は `organizer-ui-and-lock-authoring` groupへcounted済み、`LauncherPopupPreference.kt` は現時点でfork未patchであり本Issueで新たにcounted pathとなるため #452 所有のbridge group（または既存groupの責務拡張）への登録を要する（Issue終了条件3）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | JVM unit test（DEFAULT_ORDERの構成・`getMetadataForOption` の分岐）+ 実機スクリーンショット（popup表示・配置） |
| AC-2 | instrumentation test（項目tapで `HomeScreenManualOrganization` のdestinationが開く。run面のface描画）または実機録画（AC-5と同一evidenceで代用可） |
| AC-3 | JVM unit test（`getLauncherOptions` のlockフィルタ）+ 実機スクリーンショット（ロック中のpopup・編集画面） |
| AC-4 | JVM unit test（`restoreMissingPopupOptions` の位置挿入: 既定order・並べ替え済みorder・一部無効orderでの決定性と既存項目の不変性、merge結果が現orderと等価になるno-missing case＝書込みguard条件）+ guard wiringのcode review |
| AC-5 | 実機の録画またはスクリーンショット（実条件での操作数の勘定を明記。選択面が介在する場合はその旨と含めた合計を記録）+ 正規パス3操作のcross-check記録（候補0件の環境。録画またはスクリーンショット） |
| AC-6 | 実機TalkBackでの到達・起動の記録（スクリーンショット。TalkBack有効化〜項目起動までの過程が分かるもの）+ node tree等価の構造確認（uiautomator dump等）。Switch Access手動確認の省略はowner判断のIssue記録による |
| AC-7 | PR diffのpath列挙 |
| AC-8 | 計測commandの出力（PR本文） |

## Unresolved decisions

- なし。label・icon・並び・到達方法・補完規則はすべて本specで確定済み（Phase 1 review round 1の指摘に伴い、label/iconの実装reviewへの持ち越しを撤回して確定した）。

## Change history

- 2026-09-28: Draft created for #452（Issue未解決事項1「popupからrun面への到達方法」を `PreferenceActivity.createIntent` + onboarding実例の確認により解消、`restoreMissingPopupOptions` の扱いを位置挿入へ決定、`OptionsPopupView.java` 無変更の方針を記録）。
- 2026-09-30: Re-entry revision — baseline `c5a7840b88` から `56624406fd` へのrebaseに伴う正本再照合。①#449実装（PR #476）の反映: `edit_surface` が `DEFAULT_ORDER` の `edit_mode` 直後に存在する現状を確認し、編集系グループ内の最終並びを「`edit_mode, organize_home, edit_surface`」へ確定（Issue Scope 3の取り決めどおり。未解決事項3を解消）。②`restoreMissingPopupOptions` は #449 でもprependのままであることを確認し、位置挿入への変更と3世代（#449前・#449後・未保存）の補完結果をScenario/AC-4へ固定。③#482（External reference scan導入）の反映: Prior art欄を追加（AOSP Launcher3 `OptionsPopupView`・Lawnchair upstream `LauncherOptionsPopup.kt`）。④patch surface baseline状況の更新（`LauncherOptionsPopup.kt` は #449 により `homeedit-edit-surface` groupへcounted済み）をAC-8へ反映。⑤`LauncherPopupPreference` のロック無効化分岐に `edit_surface` が無い現状を #449 残課題として観察記録。
- 2026-09-30: Revision 3 — Phase 1 review round 1（[判定](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912068245): Request changes）の3指摘に対応。指摘1: label（`home_screen_organize` = "Organize home screen"／「ホームを整理」）とicon（`ic_organize_home` = 2×2 rounded-square grid + 右上4-point sparkle）をPhase 1で確定し、Scope・AC-1・Unresolved decisionsの持ち越しを撤回。指摘2: `restoreMissingPopupOptions` の書込み契約を「位置挿入結果が現orderと等しければ書かない」guardの仕様化で確定（現行の無条件writeを明示的に変える。AC-4・Test oracleへguardのoracle追加）。指摘3: plan Dependencies/blockersの残存していた#449協調記述を削除し実装済み前提へ統一。
- 2026-09-30: **accepted** — Phase 1 review round 2（[判定](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912289396): 3指摘の解消を確認。snapshot同期を残課題として指摘）と round 3（[判定](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912426507): **Clear**。blocking findingなし）を経て受理。Revision 3 snapshot（[comment](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5912395484)）がcurrent headを指す。実装PRへの持ち越し（実機evidence、gate filter判断、bridge group登録）は明示済み。
- 2026-09-30: **implemented（実装完了。ただし受入はAC-5/AC-6の実機確認待ち）** — Phase 2実装を [PR #486](https://github.com/nunu1733/NunuLauncher/pull/486) で提出。実装: \`organize_home\` 項目（DEFAULT_ORDER・handler・metadata・lock filter）、\`restoreMissingPopupOptions\` の位置挿入 + 書込みguard、\`filterVisiblePopupOptions\` 切り出し、popup編集画面のlock分岐、strings（EN/JA）・\`ic_organize_home\`（review round 1でrounded-squareへ修正）、JVM test 9件（\`app.lawnchair.ui.popup.*\` をgate filterへ追加。#458と同じroute）、patch surface baseline再採択（\`organizer-home-entry\` bridge group新設。93 counted files, +20327/-1089）。検証: full unit suite 1852 tests green、emulator証跡（[\`docs/assessment/452-organizer-home-entry-evidence\`](../../docs/assessment/452-organizer-home-entry-evidence.md)。AC-1/2/3/5/6、3操作で確認面到達、TalkBack起動確認、ja表示確認）。**AC-5の実機記録とAC-6の実機TalkBack/Switch Access確認は未完了（ownerの物理デバイスでの実施が必要）であり、それらが揃うまで本specの受入（Exit criteria充足）は完了しない。**Phase 2 review round 2（[判定](https://github.com/nunu1733/NunuLauncher/pull/486)参照）の指摘に従い、frontmatterのstatusは実機確認完了まで \`accepted\` を維持する。
- 2026-10-01: **実機evidence採取により受入条件が充足 → \`implemented\` 化** — Pixel 9a（ja-JP、#449検証と同一端末）でAC-5/AC-6を実機記録（[\`docs/assessment/452-organizer-home-entry-device-evidence.md\`](../../docs/assessment/452-organizer-home-entry-device-evidence.md)）。AC-5: 実機は未配置候補ありのため4操作（長押し→項目tap→開始tap→続行tap）で確認面到達 **≤4目標内**（0件前提の3操作はemulator `v2-05/06/07` で実証済み。選択面の介入とusage JIT promptの初回出現を正直に記録）。AC-6: TalkBack有効下で「ホームを整理」への到達・起動を実機確認（既存service設定は完全復元。Switch Accessは端末の日常設定に介入するため未実施と正直に記録し、同一node treeの構造根拠を明記）。実装treeは17/17 green run（`36796217597`）とbyte同等のため証跡の再取得は不要。Phase 2 review round 3（[判定](https://github.com/nunu1733/NunuLauncher/pull/486#issuecomment-5922501799):「evidence追加だけならcode再reviewは不要」）の残件を解消。
- 2026-09-30〜10-01: 上記の \`implemented\` 化はPhase 2 review round 4（[判定](https://github.com/nunu1733/NunuLauncher/pull/486#issuecomment-5923335874): Request changes）により時期尚早と判定され、statusを \`accepted\` へ戻した。指摘は (a) AC-5の「未配置候補0件・実機3操作」というoracleの読み（実機では候補0件の状態を作れない — 利用者のappをuninstallするのは非受入）と、(b) AC-6のSwitch Access実機確認・TalkBack連続focus/読み上げ記録の不足である。
- 2026-10-01: **Revision 4** — round 4指摘をowner承認のもと受入oracleの明確化として処理。①AC-5: operational bar（**実機で4操作以下**）はIssue終了条件2から引き継ぎ、「未配置候補0件」の前提はowner判断により **supersede**（[判断記録](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5923580963)。日常端末では候補0件を作れないため）。「文言どおりの明確化」ではなく **owner承認によるoracle変更** である。正規パス3操作はcross-check記録（環境不問）へ位置づけ。②AC-6: TalkBack実機での到達・起動 + labelRes/node tree機構の同一性確認をoracleとし、TTS録音を要求しない。Switch Accessは同一node treeの構造確認で代替、端末での手動確認省略をowner判断事項として明記（[AC-6分の判断記録](https://github.com/nunu1733/NunuLauncher/issues/452#issuecomment-5923486520)。#449の省略判断の先例どおり）。受入条件の実質変更にあたるため、本revisionをspec再レビューに付した。
- 2026-10-01: **Revision 4同期** — Revision 4再レビュー（[判定](https://github.com/nunu1733/NunuLauncher/pull/486#issuecomment-5923520815): Request changes。正本間同期3指摘）への対応。①AC-5の「Issue終了条件2の文言どおり」表現を「bar引き継ぎ+0件前提のsupersede（owner判断コメント参照）」へ修正。②AC-6本文とTest oracleに残っていた「同等の読み上げ」「focus screenshot」要求を、owner意図（到達・起動+機構の同一性確認。TTS録音不要求）へ統一。③plan.md・PR本文・device evidence文書へRevision 4 oracleを同期。
- 2026-10-01: **\`implemented\` 化** — 同期後の再レビュー（[判定](https://github.com/nunu1733/NunuLauncher/pull/486#issuecomment-5923694469): **Clear**。新規blocking findingなし。既存の実機4操作evidence・3操作cross-check・TalkBack起動/node tree evidenceがRevision 4 oracleに対応すると確認）により受入oracleが確定。実装PR #486をmergeする。
