---
issue: "#526"
status: draft
tier: M
requirements: []
updated: 2026-10-06
---

# targetSdk 37でのfork UI表示・操作契約 — 編集画面のinset消費・適用中back gate・再作成時の破棄案内

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを含まない。
既存の適用seam（ADR-0013、spec 452のedit surface確認flow、#449のprocess-local/cancel契約、
#450のUndo契約）は変更せず、その外側の表示・入力とprocess内の操作状態権威のみを扱う。

## Problem

targetSdk 37ではedge-to-edge強制・predictive back既定有効・大画面での向き/resize制約無視が
opt-out不可になる（#521 assessment T01/T02/T03/T05/T24）。共通`PreferenceActivity`は
edge-to-edge対応済みだが、別Activityである`HomeEditSurfaceActivity`は適用外で、
(1) system bar / cutout / IMEが上段CTA（Reset/Cancel）・下部ActionBar（Confirm等）・図を被覆し、
(2) 適用進行中のシステムbackが確認なしでActivityをfinishさせ画面を離脱でき、
(3) 回転/resizeによるActivity再作成で未確定セッションが案内なく黙示的に破棄され、
(4) 適用進行中の再作成では、旧instanceのapplyと新instanceのcapture/再操作が
Activity field状態（`busy`/`applying`/`surfaceExecutor`）の分離により並行し得る。

## Baseline（本specの前提事実）

- 16-dev採用baseline: upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`
  （ADR-0018固定）。本spec作業branchは `e8aced7dbee4480c178a9cb994b8d55686ef41ce`
  （Phase 2 review closure後の `issue-532-phase2-restart` head）から分岐する。
- Gradle DSL: compileSdk 37（minor 2）、buildTools 37.0.0、minSdk 26、targetSdk 37
  （`build.gradle`）。
- merged manifest（`lawnWithQuickstepGithubDebug`、2026-10-06に生成して取得。
  生成物はrepositoryへcommitしない）:
  `uses-sdk android:minSdkVersion="26" android:targetSdkVersion="37"`、
  application-level `android:enableOnBackInvokedCallback="true"`
  （quickstep flavorの`quickstep/AndroidManifest.xml`）。
  `HomeEditSurfaceActivity`宣言には`configChanges`/`screenOrientation`がなく、
  targetSdk 37では回転/resize/foldで再作成され、resizeableが既定で有効。

## Benchmark

対象課題: B3〜B7（[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md)）。
目標を2つに分けて定義する。

- **被覆による増分ゼロ**: これらの既存編集フローにおいて、system bar/IME被覆が
  原因で追加される操作（CTAを探す・一度閉じる等）を発生させない。
- **再作成時の消失の明示化**: 回転/resizeの再作成では、黙示的な消失と無確認writeを
  ゼロにする。未確定セッションは破棄するため再入力（選択・計画のやり直し）が
  発生するが、これは本specの破棄決定に伴う **明示的なtrade-offとして受容する**
  （保持による暗黙のstaleリスクを取らない）。ベンチマーク操作数の実測合格条件を
  この増分に用いない。

操作数の短縮そのものは各担当specの目標値が保持する。純粋な表示契約の追従であり、
新たなベンチマーク課題の起票は不要と判断する。

## Prior art

- Android公式: [edge-to-edge enforcement](https://developer.android.com/about/versions/16/behavior-changes-16#edge-to-edge)、[predictive back](https://developer.android.com/about/versions/16/behavior-changes-16#predictive-back)、[elegant text height](https://developer.android.com/about/versions/16/behavior-changes-16#elegant-text-height)、[ignore orientation](https://developer.android.com/about/versions/16/behavior-changes-16#ignore-orientation)、[large screen ignore constraints](https://developer.android.com/about/versions/17/behavior-changes-17#large-screen-ignore-constraints)。確認日2026-10-06。採用: targetSdk 37ではopt-out不可のため、inset消費とdispatcher経由backでの対応が必須。
- Android公式: [`enableEdgeToEdge`](https://developer.android.com/reference/androidx/activity/EdgeToEdge)（androidx.activity）。確認日2026-10-06。採用: 同一repositoryの既存経路`PreferenceActivity`（`lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt:37`）と同一のAPI。
- Android公式: [Compose accessibility — alerts/pop-ups](https://developer.android.com/develop/ui/compose/accessibility/semantics#alerts-pop-ups)。確認日2026-10-06。採用: 再作成後に動的に出現する破棄案内は`liveRegion`（Polite）による一度限りの通知を行う。
- それ以外の外部UI実装例は省略（調査済み）。対応はplatform契約の追従であり、機能設計の新規採用がない。

## Outcome

targetSdk 37環境（API 36/37、gesture/3-button、cutout/IME、sw600dp以上の回転/resize）
でも、編集画面の確認・キャンセル・図操作が被覆されず、適用進行中の戻るが画面離脱を
起こさず、再作成で未確定セッションが案内付きで安全に破棄され、適用進行中の再作成でも
1 apply = 1 recovery point = 1 Undoが維持される。organizer hub/preview/exchange/
diagnostics、backup/restore、destination picker（PreferenceActivity配下）の表示も
同一matrixで確認し、実測した回帰のみ修正する。

## Scope

- `HomeEditSurfaceActivity`のedge-to-edge対応: `enableEdgeToEdge()`呼び出しと、
  編集画面ルートでの`WindowInsets.safeDrawing`消費（status bar / cutout / navigation
  bar / IME）。manifestへの`android:enableOnBackInvokedCallback="true"`付与
  （`PreferenceActivity`と同一）。
- **適用中操作の単一権威**: in-flightなDB適用（`applying`相当）の状態をActivity
  instance外の**process-wideな単一holder**へ置く。`HomeEditSurfaceAccess.get(context)`
  は呼び出しごとに新しいwrapper instanceを返すため、wrapperのfieldへ置かない
  （既存process singletonに属するstate、または`HomeEditSurfaceAccess` companion等、
  全wrapper・全Activityが同一stateを参照するidentityとする。Activity/wrapperごとの
  コピーは禁止）。状態機械は `Idle → InFlight →（旧applyのterminal結果受領）→
  Correlating（相関capture再取得中）→ Idle` とし、terminal結果（Applied / stale /
  rejected / error のいずれも）を受けたあとは**新instanceが相関captureを再取得して
  完了するまでConfirmを再有効化しない**。状態の二重化（Activity fieldと権威の併存）
  はしない。表示用途のみでwrite経路は不変。
- back gate: **in-flightなDB適用が存在する間**のシステムbackを握り潰すCompose
  `BackHandler`。capture再読込・初回読込などのzero-write待機中は従来どおり
  finish（= cancel）可能とする。非適用時のbackは既存どおりzero-write終了。
- 再作成時の未確定セッション: **保持せず破棄し、再作成後に案内を表示する**。
  黙示的保持は実装しない（将来保持する場合はstale検証契約ごと別specで決める）。
  破棄はzero-writeであり、capture/session/selectionはいずれもDBへ未反映。
  案内の対象は単一の述語「ユーザー作業が存在した」（`selection.isNotEmpty() ||
  session.changes.isNotEmpty()`。将来の作業状態はこの述語へ集約）で判定する。
- 再作成と適用の相互契約: 適用進行中の再作成では、進行中の適用は旧instance側で
  完走させ（再発行・リトライUIはしない）、新instanceは単一権威により二重Confirmを
  許さない。stale captureは既存gateどおり適用しない。1 apply = 1 recovery point =
  1 Undoを維持する（Undo記録・snackbarは既存flowのまま）。
- PreferenceActivity配下のorganizer・backup・picker系routeの回転/resize・fontScale・
  対象言語での表示確認と、実測した回帰の修正（修正が生じた場合のみコード変更）。
- 追加文字列（破棄案内）は`lawnchair/res/values/strings.xml`と`values-ja/strings.xml`。

## Non-goals

- targetSdk値の変更、API 37 Quickstep/recents（#520/#524）。
- DB schema/backup format（#522）、既存write経路・適用契約・recovery point契約の変更。
- 未確定セッションの回転保持、適用進行中操作の再発行・リトライUI
  （黙示的保持を避ける契約は#526本文どおり）。
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

### Scenario: 適用進行中のシステムbackは離脱しない

Given 編集画面でConfirmを実行し、DB適用が進行中（単一権威がin-flightを示す）
When システムback（gesture/3-button）を実行する
Then Activityはfinishせず適用完了まで同一画面が維持される。適用完了後の遷移は
既存契約（Applied→finish+Undo記録、stale→zero-writeで再読込）どおり。
back自体によるpersistent changeは発生しない

### Scenario: zero-write待機中のbackはcancelとして既存契約を維持

Given 編集画面で選択のみ行い、in-flightなDB適用が存在しない（初回capture再読込中を含む）
When システムbackを実行する
Then Activityはfinishし、適用・書込みは行われない（Cancel CTAと同じzero-write終了）。
back gateはこの状態では発動しない

### Scenario: 再作成で未確定セッションは案内付きで破棄される

Given 編集画面で「ユーザー作業が存在した」状態（selection-only、または計画あり）で
When sw600dp以上の画面で回転し（またはresizeし）Activityが再作成される
Then 未確定セッションは保持されず、capture再読込後に「前回の未確定の編集内容を
破棄した」旨の案内が表示される。DB・ホーム配置に変化はなく、図は最新captureから
再構築される。案内は次のユーザー操作で消える。何も操作していない状態での再作成では
案内も通知も出ない

### Scenario: 適用進行中の再作成でも二重適用しない

Given 編集画面でConfirmを実行しDB適用が進行中の状態で
When sw600dp以上の画面で回転しActivityが再作成される
Then 進行中の適用は完走し（1 recovery point / 1 Undo）、再発行されない。
新instanceは単一権威によりin-flight中のConfirmを受け付けず、適用完了まで
適用が始まらない旨が示される。適用のterminal後も、新instanceが相関captureを
再取得して完了するまでConfirmは再有効化されない（旧適用前のcaptureをstale図として
編集→Confirm→再読込の往復を発生させない）。terminalがApplied以外
（stale / rejected / error）でも同じ解除条件を経る。新instanceのcaptureが
旧適用のstale判定に達する場合は既存gateどおり零書込みで再読込する。
DBは旧適用の1回のtransactionのみで更新される

## Verification

- emulator matrix（主証跡。保守者実機Pixel 9a / API 37はEpic #516 Phase 3の
  実機matrixの対象で、本spec時点でアクセス不能なため、実機確認をowner手順として
  Epic側へ引き継ぐことをPRへ明記する）:
  API 36とAPI 37のemulator、gesture/3-button navigation、portrait↔landscape、
  sw600dp以上のtablet設定での回転/resize、cutout/IME表示、fontScale 1.3/2.0、
  ja/en、および **TalkBack ON** でのScenario 1〜5を実測し（破棄案内の自動通知を
  含む）、スクリーンショット/録画をPRへ添付する。
- PreferenceActivity配下のorganizer hub/preview/exchange/diagnostics、backup/restore、
  destination picker、編集pickerを同一emulator matrixで回転/resize・fontScale・ja表示し、
  実測した回帰のみ修正して記録する。
- **必須のinstrumentation oracle**（既存seam（`ActivityScenario`、internal hook）のみで
  構成し、productionのtest専用hookを広げない）:
  (1) `recreate()` × {selection-only, 計画あり, 未操作} の3ケースで案内の有無を固定、
  (2) 適用進行中のシステムbackでfinishしないこと、
  (3) 適用進行中の`recreate()`で二重適用が起きないこと（DB更新は旧適用の1回のみ、
  1 recovery point / 1 Undo）と、terminal後・相関capture再取得完了前にConfirmが
  不能であること、
  (4) 案内の`liveRegion` semantics（作業あり=Polite設定、作業なし=対象要素なし）と、
  再compositionで通知条件が再発火しないこと。
- 既存edit surface系unit/instrumentation testがすべてgreenであること。
- 書込み経路を追加しないことの確認: 変diffは`HomeEditSurfaceActivity.kt`、
  `EditSurfaceScreen.kt`、`HomeEditSurfaceAccess.kt`（in-flight権威の追加。適用経路
  の呼び出し順と引数は不変）、`lawnchair/AndroidManifest.xml`、`lawnchair/res`の
  strings、instrumentation testに限る（適用・DB・recovery pathの実体に触れない）。
- 上流UIへのbridge変更（popup・menu項目追加等）は含まないため、
  `measure_upstream_patch_surface.py`の再計測は不要。

## Accessibility and localization

- 破棄案内は`liveRegion = Polite`相当のalert semanticsで **一度だけ** TalkBackへ
  通知する。再compositionでの繰り返し通知をせず、「ユーザー作業が存在した」述語が
  偽の再作成では通知しない。error色の`reasonRes`は流用しない。
- fontScale 1.3/2.0で上段・ActionBar・案内のclipping/被覆がないことを確認する
  （セル寸法はwidth基準の既存規則を維持）。
- 対象言語はja（第一）とen。追加文字列は両言語で確認する。Arabic/Thai等の
  elegantTextHeight影響は実測し、不備がなければ確認記録のみ（実測した回帰は修正）。

## Change history

- 2026-10-06: Draft created for #526。
- 2026-10-06: Review round 1（PR #536コメント）対応 — Benchmarkの矛盾解消
  （被覆増分ゼロと再作成trade-offの分離）、適用進行中と再作成の相互契約
  （単一権威・二重適用禁止）を追加、back gateをin-flight適用限定に締め直し、
  破棄案内の述語定義と3ケースoracle・TalkBack liveRegion契約を明記。
- 2026-10-06: Review round 2（PR #536コメント）対応 — 単一権威をprocess-wide
  holderとしてidentityを明確化（wrapper fieldの禁止）、状態機械にterminal後の
  Correlating（相関capture再取得完了までConfirm無効）を追加、必須oracleに
  1 recovery point / 1 Undo固定とliveRegion semantics oracleを追加、
  emulator matrixへTalkBack ON実測を明記。
