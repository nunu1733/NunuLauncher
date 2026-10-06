---
issue: "#526"
status: draft
tier: M
requirements: []
updated: 2026-10-06
---

# targetSdk 37でのfork UI表示・操作契約 — 編集画面のinset消費・busy中back gate・再作成時の破棄案内

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
既存の適用seam（ADR-0013、spec 452のedit surface確認flow、#449のprocess-local/cancel契約、
#450のUndo契約）は変更せず、その外側の表示・入力のみを扱う。

## Problem

targetSdk 37ではedge-to-edge強制・predictive back既定有効・大画面での向き/resize制約無視が
opt-out不可になる（#521 assessment T01/T02/T03/T05/T24）。共通`PreferenceActivity`は
edge-to-edge対応済みだが、別Activityである`HomeEditSurfaceActivity`は適用外で、
(1) system bar / cutout / IMEが上段CTA（Reset/Cancel）・下部ActionBar（Confirm等）・図を被覆し、
(2) busy/applying中のシステムbackが確認なしでActivityをfinishさせ適用中の画面を離脱でき、
(3) 回転/resizeによるActivity再作成で未確定セッションが案内なく黙示的に破棄される。

## Baseline（本specの前提事実）

- 16-dev採用baseline: upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`
  （ADR-0018固定）。本spec作業branchは `e8aced7dbee4480c178a9cb994b8d55686ef41ce`
  （Phase 2 review closure後の `issue-532-phase2-restart` head）から分岐する。
- Gradle DSL: compileSdk 37（minor 2）、buildTools 37.0.0、minSdk 26、targetSdk 37
  （`build.gradle`）。
- merged manifest（`lawnWithQuickstepGithubDebug`、2026-10-06取得）:
  `uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37"`、
  application-level `android:enableOnBackInvokedCallback="true"`
  （quickstep flavorの`quickstep/AndroidManifest.xml`）。
  `HomeEditSurfaceActivity`宣言には`configChanges`/`screenOrientation`がなく、
  targetSdk 37では回転/resize/foldで再作成され、resizeableが既定で有効。

## Benchmark

改善する課題: B3〜B7（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md)）。
目標: これらの既存編集フローについて、system bar/IME被覆による追加操作と、
回転/resizeでの未確定セッション消失による再入力（選択・計画のやり直しで操作が
増える事態）を発生させない。操作数の短縮そのものは各担当specの目標値が保持する
ため、本specでは「被覆・再作成による増分ゼロ」を目標とする。純粋な表示契約の
追従であり、新たなベンチマーク課題の起票は不要と判断する。

## Prior art

- Android公式: [edge-to-edge enforcement](https://developer.android.com/about/versions/16/behavior-changes-16#edge-to-edge)、[predictive back](https://developer.android.com/about/versions/16/behavior-changes-16#predictive-back)、[elegant text height](https://developer.android.com/about/versions/16/behavior-changes-16#elegant-text-height)、[ignore orientation](https://developer.android.com/about/versions/16/behavior-changes-16#ignore-orientation)、[large screen ignore constraints](https://developer.android.com/about/versions/17/behavior-changes-17#large-screen-ignore-constraints)。確認日2026-10-06。採用: targetSdk 37ではopt-out不可のため、inset消費とdispatcher経由backでの対応が必須。
- Android公式: [`enableEdgeToEdge`](https://developer.android.com/reference/androidx/activity/EdgeToEdge)（androidx.activity）。確認日2026-10-06。採用: 同一repositoryの既存経路`PreferenceActivity`（`lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt:37`）と同一のAPI。
- それ以外の外部UI実装例は省略（調査済み）。対応はplatform契約の追従であり、機能設計の新規採用がない。

## Outcome

targetSdk 37環境（API 36/37、gesture/3-button、cutout/IME、sw600dp以上の回転/resize）
でも、編集画面の確認・キャンセル・図操作が被覆されず、戻るが既存のzero-write/cancel
契約を維持し、再作成で未確定セッションが案内付きで安全に破棄される。organizer
hub/preview/exchange/diagnostics、backup/restore、destination picker（PreferenceActivity
配下）の表示も同一matrixで確認し、実測した回帰のみ修正する。

## Scope

- `HomeEditSurfaceActivity`のedge-to-edge対応: `enableEdgeToEdge()`呼び出しと、
  編集画面ルートでの`WindowInsets.safeDrawing`消費（status bar / cutout / navigation
  bar / IME）。manifestへの`android:enableOnBackInvokedCallback="true"`付与
  （`PreferenceActivity`と同一）。
- 編集画面のback gate: `busy`（`applying`を含む）中のシステムbackを握り潰す
  Compose `BackHandler`。非busy時は既存どおりfinish（= cancel、applyしない）。
- 再作成時の未確定セッション: **保持せず破棄し、再作成後に案内を表示する**。
  黙示的保持は実装しない（将来保持する場合はstale検証契約ごと別specで決める）。
  破棄はzero-writeであり、capture/session/selectionはいずれもDBへ未反映。
- PreferenceActivity配下のorganizer・backup・picker系routeの回転/resize・fontScale・
  対象言語での表示確認と、実測した回帰の修正（修正が生じた場合のみコード変更）。
- 追加文字列（破棄案内）は`lawnchair/res/values/strings.xml`と`values-ja/strings.xml`。

## Non-goals

- targetSdk値の変更、API 37 Quickstep/recents（#520/#524）。
- DB schema/backup format（#522）、既存write経路・適用契約の変更。
- 未確定セッションの回転保持（黙示的保持を避ける契約は#526本文どおり）。
- B3〜B7の操作数短縮そのもの、organizer計画・適用ロジックの変更。
- AOSP側Activity（`SettingsActivity`/`AddItemActivity`/`WidgetPickerActivity`等）の変更。
- `PreferenceActivity`配下のedge-to-edge作り直し（既存`enableEdgeToEdge`+M3
  Scaffold経路を確認対象とし、実測回帰がなければ触れない）。

## Behavior scenarios

### Scenario: system bar / cutout / IME下でもCTAと図が操作可能

Given API 37 emulator、gesture navigationで編集画面を開き、図を表示している
When 画面上部・下部のCTAと、IMEを表示した状態の下部ActionBarを確認する
Then 上段（タイトル/Reset/Cancel）はstatus bar・cutoutと重ならず、下部ActionBar
（Confirm含む）はnavigation bar・IMEのinset分だけ持ち上がって被覆されずタップ可能。
図は上段・ActionBarの間に表示される

### Scenario: busy中のシステムbackは適用を中断させない

Given 編集画面でConfirmを実行し、DB適用が進行中（`applying`かつ`busy`が真）
When システムback（gesture/3-button）を実行する
Then Activityはfinishせず適用完了まで同一画面が維持される。適用完了後の遷移は
既存契約（Applied→finish+Undo記録、stale→zero-writeで再読込）どおり。
back自体によるpersistent changeは発生しない

### Scenario: 再作成で未確定セッションは案内付きで破棄される

Given 編集画面で選択・計画を作成済み（未確定、zero-write）の状態で
When sw600dp以上の画面で回転し（またはresizeし）Activityが再作成される
Then 未確定セッションは保持されず、capture再読込後に「前回の未確定の編集内容を
破棄した」旨の案内が表示される。DB・ホーム配置に変化はなく、図は最新captureから
再構築される。案内はユーザーの次の操作（選択・アクション・reset）で消える

### Scenario: 非busyのbackはcancelとして既存契約を維持

Given 編集画面で選択のみ行い、`busy`/`applying`が偽
When システムbackを実行する
Then Activityはfinishし、適用・書込みは行われない（Cancel CTAと同じzero-write終了）

## Verification

- emulator matrix（主証跡。保守者実機Pixel 9a / API 37はEpic #516 Phase 3の
  実機matrixの対象で、本spec時点でアクセス不能なため、実機確認をowner手順として
  Epic側へ引き継ぐことをPRへ明記する）:
  API 36とAPI 37のemulator、gesture/3-button navigation、portrait↔landscape、
  sw600dp以上のtablet設定での回転/resize、cutout/IME表示、fontScale 1.3/2.0、
  ja/enでScenario 1〜4を確認し、スクリーンショット/録画をPRへ添付する。
- PreferenceActivity配下のorganizer hub/preview/exchange/diagnostics、backup/restore、
  destination picker、編集pickerを同一emulator matrixで回転/resize・fontScale・ja表示し、
  実測した回帰のみ修正して記録する。
- instrumentation oracle（追加する場合）: 再作成時の破棄案内表示（非空セッションで
  `recreate()`→案内あり、空セッション→案内なし）、busy中back握り潰し。既存
  edit surface系unit/instrumentation testがすべてgreenであること。
- 書込み経路を追加しないことの確認: 変diffは`HomeEditSurfaceActivity.kt`、
  `EditSurfaceScreen.kt`、`lawnchair/AndroidManifest.xml`、`lawnchair/res`のstrings、
  instrumentation testに限る（適用・DB・recovery pathに触れない）。
- 上流UIへのbridge変更（popup・menu項目追加等）は含まないため、
  `measure_upstream_patch_surface.py`の再計測は不要。

## Accessibility and localization

- 破棄案内は通常テキストとして読み上げ可能にし（error色の`reasonRes`は流用しない）、
  TalkBackで提示されることをemulatorで確認する。
- fontScale 1.3/2.0で上段・ActionBar・案内のclipping/被覆がないことを確認する
  （セル寸法はwidth基準の既存規則を維持）。
- 対象言語はja（第一）とen。追加文字列は両言語で確認する。Arabic/Thai等の
  elegantTextHeight影響は実測し、不備がなければ確認記録のみ（実測した回帰は修正）。

## Change history

- 2026-10-06: Draft created for #526。
