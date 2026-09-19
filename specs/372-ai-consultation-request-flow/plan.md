# Implementation Plan: AI相談のT-07方法選択統合と依頼作成（T-15）・送信前確認（T-16）の再構成

> Issue: #372
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

以下は2026-09-19時点のmain（`a2b6aba3189922a6e1cb314eb52cff1d1f973e02`）での確認である。
`3076bdae7e` → `a2b6aba318` の差分は `docs/product/organizer-disposition-migration.md` と
`docs/project/seed-backlog.md` のみ（docs-only）であり、以下のcode事実は#369 draftの調査
（baseline `3076bdae7e`）と同一である。

### 現行のexchange flow UI（`ExchangeFlowUi.kt`、1860行）

`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`:

- `ExchangeScreen` sealed interface（L87-141）: `Closed` / `SelectingPrivacy` /
  `ReplacementConfirm` / `Generating` / `Disclosing` / `Importing` / `ImportOutcomeScreen` /
  `ImportSuccess`。flow画面はhost面のinline blockとして描画される（新規destinationではない）。
- `ExchangeDisclosureState`（L172-208）: 生成済みpackageのimmutableな状態束
  （`session` / `packageText` / `tier` / `sent` / `transportInFlight` / `cancelling`）。
  `cancelable`（未送信・非in-flight・非cancelling）と`transportAllowed`が同一値であり、
  確認と送信の順序契約（spec 205 AC-12）の構造gateである。
- `ExchangeFlowStateHolder`（L217-828）: `openFlow()`（L256-259。`activeSession()` の有無のみを
  `SelectingPrivacy.replacementConfirmationRequired` に写像し、session自体は保持しない）、
  `requestGeneration`（L278-290。確認要否で`ReplacementConfirm`へ分岐）、
  `generate`/`generateScoped`（L305-329）、`closeDisclosure`（L365-381。Main上で
  `cancelling`確定 → IO上で`controller.cancelDisclosure` → `close()`。**確認dialogは存在しない**）、
  import attempt機構（L526-685。spec 328: attempt anchor・`importAttemptActive` /
  `importContinuationActive` freeze述語）。
- `exchangeFlowItems`（L882-1018）: host面の`LazyListScope`へflow blockを供給する。
  `Closed`時にscopedなら`ExchangeScopedEntryRow`（run-in）、idleなら`ExchangeEntryRow`。
  flow画面ごとにkey付きitem、末尾にstatus行（L1006-1017）。
- `ExchangeEntryRow`（L1021-1051、V-27）: idle entry row。title＋subtitle＋
  `ExchangeCapabilityNotes`＋「依頼文を作成」（`openFlow`）「回答を取り込む」（`openImport`）
  の2ボタン。**本Issueの撤去対象**。
- `ExchangeCapabilityNotes`（L1063-1086）＋`exchangeCapabilityExampleResourceIds`
  （L1089-1095）: spec 327のcapability説明4要素（具体例5行・直接変更しない・会話flow・
  NunuLauncherを経由しない）。idle/run-in両entryから使われている。
- `ExchangePrivacySelection`（L1136-1197、V-29）: tier radio 2択
  （`exchange_privacy_redacted` 既定 / `exchange_privacy_labels`）＋label付き警告＋
  置換notice（`requiresConfirmation`時）＋「依頼文を生成」「キャンセル」。
- `ExchangeReplacementConfirm`（L1200-1229、V-30）: 置換確認のinline block。
  ja copy「破棄して作成」/「既存を維持」（既存の破棄語彙。warning文言は「無効化される」語彙）。
- `ExchangeDisclosure`（L1232-1319、V-32）: title＋tier別開示文言＋hint＋
  **package全文の常時提示**（L1264-1273。`heightIn(max = 240.dp)`＋verticalScroll）＋
  copy/share/fileボタン＋cancel/close（L1306-1317。`cancelable`なら
  `exchange_cancel`=「キャンセル」、そうでなければ`exchange_close`=「閉じる」）。
- `ExchangeImportField`（L1331以降、V-33）/`ExchangeImportSuccess`（L1503以降）:
  spec 332/328の現行UI。本Issueでは変更しない（#373対象）。
- `strategyWriteStartBlockedFor` / `strategyRestartSuppressedFor`（L144-159）:
  spec 328のstrategy相互排他truth table（純関数）。host側から`importAttemptActive`等が渡される。

### 現行のhost（`ManualOrganizationPreferences.kt`、1750行）

`lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt`:

- `ExchangeFlowStateHolder` の構築（L112-118、`remember`、controller factory遅延）と
  `strategyArbiterBusy` 接続（L158）。
- Idle/Cancelled分岐（L331-373）: checking行＋durable status行群（spec 271）＋
  idle start row（L348-372。`importAttemptActive`中はdisabled＋
  `exchange_start_frozen_import` subtitle。spec 328のfreeze affordance）。
- `Selecting`分岐（L393-445）: `missingAppSelectionItems`＋SCOPE_MISMATCH行＋
  `exchangeFlowItems`（run-in、L433-444）。
- strategy picker（L797-810、`strategyPickerEnabled`/`strategyFrozenReason`）と
  **idle `exchangeFlowItems` のhost（L816-829、list最下部。「strategy pickerより下」の
  spec 205規定・監査D-8）**。
- `ManualOrganizationBackHandler`（L1002-1038付近）: Back → `coordinator.dismiss()`。
  exchange flow画面自体への個別Back handlerは成功状態のみ
  （`ExchangeImportSuccessBackHandler`、`ExchangeFlowUi.kt` L1462）。

### 現行のcontroller / gate / session

- `ExchangeFlowController.kt`（198行）: `activeSession()`（`store.active(clock())`）、
  `generationGate()`（`ExchangeGenerationGate.evaluate`）、`generate(tier)` の順序契約
  （gate → build → save → compose → disclose。encode失敗時のghost session除去あり）、
  `cancelDisclosure(session)`（当該sessionのみ`invalidate`）。
- `ExchangeGenerationGate.kt`（40行）: active sessionなし→Proceed / 確認未了→
  RequiresConfirmation / 承認→Proceed / 辞退→Aborted。**本Issueでは不変**。
- `ContextExportModels.kt` L411-461: `ExportSession` は `expiresAtEpochMs`（T-15残時間の導出源）と
  `itemRefs`（T-16件数の導出源。exported ref全体を保持）を持つ。新fieldは不要である。
- `ContextExportBuilder.kt`: tier分岐は `LOCAL_FULL` と `EXTERNAL_WITH_LABELS` を同一扱い
  （監査D-2）。**本Issueでは触れない**（契約値維持、D-14）。

### 現行のstrings / test

- `lawnchair/res/values-ja/strings.xml` L379-439（en `values/strings.xml` 同name集合）:
  `exchange_entry_*`（entry row、撤去対象）、`exchange_capability_*`（4要素。維持・移設）、
  `exchange_privacy_*`（tier語彙。D-14語彙へ改訂）、`exchange_replacement_*`
  （「破棄して作成」等の置換語彙）、`exchange_disclosure_*`（送信前確認。要約への再構成対象）、
  `exchange_cancel`=「キャンセル」/`exchange_close`=「閉じる」。
- unit: `tests/unit/app/lawnchair/organizer/ui/exchange/`
  `ExchangeFlowStateHolderTest.kt`（1747行。generation/import/cancel/attempt anchorの広範な契約）、
  `ExchangeDisclosureStateTest.kt`、`ExchangeCapabilityCopyTest.kt`。
- instrumentation: `tests/organizer-instrumentation/app/lawnchair/organizer/ui/exchange/`
  `ExchangeImportSurfaceInstrumentationTest.kt`、`ExchangeImportSuccessInstrumentationTest.kt`、
  `tests/.../destinations/StrategyPickerFreezeInstrumentationTest.kt`（freeze affordance回帰）。

## Design

### Modules and interfaces

- **`ExchangeFlowUi.kt`（ui/exchange。主変更先）**:
  - `ExchangeScreen.SelectingPrivacy` をT-15面として再構成する。pre-display用に
    `openFlow()`が取得したactive session（存在と`expiresAtEpochMs`）を保持する
    （現行はBooleanのみ。sealed class parameterの追加はholder内の閉じた変更であり、
    `ExportSession` をUIへ渡してもよいが、表示に必要な最小projection
    （存在 + 残時間导出用の`expiresAtEpochMs`）に限定する）。
  - `ExchangePrivacySelection` → T-15: 見出し、active依頼事前表示（存在＋残時間。
    format resource）、D-09期待明示行、tier 2択（語彙改訂）、依頼を作成CTA
    （置換確認経由）、回答を取り込む導線（`openImport`）。cancelは「キャンセル」（確認不要）。
  - `ExchangeDisclosure` → T-16: 要約主面（種別=既存tier別開示文言、件数=session
    `itemRefs.size`、上限=spec 204 V1 content limitsの告知文言）＋全文の折りたたみ/展開
    （展開stateはTalkBackへ伝える）＋D-09期待明示行＋transport 3経路（既存）＋
    破棄（pre-send cancelable時。確認dialog経由で`closeDisclosure()`）/
    閉じる（送信後。確認なし）。
  - `ExchangeEntryRow`（idle）を削除し、`exchangeFlowItems` の`Closed`分岐は
    scoped（run-in）のときのみentry rowを描画する。idleの`Closed`は何も描画しない。
    `ExchangeCapabilityNotes`はT-15から参照する形で維持する（run-in entryも継続利用）。
  - 要約導出は純関数に切り出し、unit testから直接検証可能にする
    （例: `exchangeDisclosureSummary(state)`。`itemRefs.size`・tier・上限定数の射影）。
  - **holderの状態機械は変更しない**: `closeDisclosure` のMain上`cancelling`確定 →
    `invalidate` の構造gate、`settleTransport` のdisclosure束縛、attempt anchorは
    現行契約のまま（破棄確認dialogはUI層のaffordance。confirm応答後に既存
    `closeDisclosure()` を呼ぶのみ）。
- **`ManualOrganizationPreferences.kt`（host。T-07面への統合）**:
  - Idle/Cancelled分岐（#369適用後のT-07前置き面）に「AIに相談」方法選択rowを追加する
    （短い説明＝既存`exchange_entry_subtitle`の継承または改訂。Contract notes 1）。
    onClickは`exchangeHolder.openFlow()`のみであり、`coordinator.start(...)` を発行しない。
  - idle `exchangeFlowItems` のhost（現L816-829）から`Closed`時のentry blockが消える。
    flow open中の表示位置（方法選択の直下/置換等）はspec 123収束の範囲で実装PRが確定する。
  - idle start row freeze（`importAttemptActive` → disabled＋subtitle）は
    「そのまま整理」CTAがそのまま継承する（#369規定の回帰。本Issueでの変更なし）。
  - strategy picker・arbiter関連のwiringは#368適用後の構成が前提である（下記
    Dependencies / Unverified areas参照。本Issueではexchange側のgate接続を壊さない範囲で
    現行のまま維持する）。
- **strings（`lawnchair/res/values/` + `values-ja/`）**: 新規（T-15見出し・事前表示・
  残時間format・D-09期待明示・要約・全文展開・破棄確認dialog）と改訂（tier語彙のD-14化、
  置換確認の依頼/破棄語揃え、`exchange_cancel`の破棄分離）。format resource規約
  （spec 123 AC-4/AC-5）。未使用化stringの削除はreference grepで確定する。
- **spec改訂（同じPR）**: spec 205 / 327 / 204（spec.mdのScope節「spec改訂」のとおり。
  変更箇所は「Spec amendments」参照）。

### Data flow

T-07「AIに相談」→ `openFlow()`（active session読取。存在ならT-15に事前表示materialを添付）
→ T-15（tier選択＋D-09＋[active依頼があるとき置換確認]）→ `requestGeneration` /
`confirmReplacementAndGenerate` → `generate(tier)`（既存順序契約: gate → build → save →
compose → disclose）→ T-16（要約主面＋全文展開＋D-09＋transport＋破棄/閉じる）→
`startTransport`（同一immutable値の送出）/ `closeDisclosure`（未送信なら確認dialog経由で
当該sessionのみ失効）。取り込み導線: T-15「回答を取り込む」→ `openImport()` →
既存T-17入力面（現行のまま）。表示に必要な追加dataはactive sessionの
`expiresAtEpochMs` と生成済みsessionの `itemRefs`（既存fieldのみ）であり、
新規の読取seam・永続化・diagnostics eventはない。

### Alternatives rejected

- **T-07に「AIに相談」の4要素本体を常時展開する案**: 方法選択面がAI説明で支配され、
  「そのまま整理」の最短性（TO-BE B′採用理由）を損なう。T-15へ本体を置けば依頼作成を
  実行する前に必ず通るため、informed choiceの実効性は保たれる（spec Contract notes 1）。
- **idle entry rowを「AIに相談」のsub-rowとして残す案**: D-04の「独立サブシステムとして
  見せない」と矛盾し、F-07が残る。撤去がIssue本文のscopeである。
- **holderに破棄確認状態を追加する案**: `cancelling` まで含む既存の構造gateは
  `ExchangeDisclosureStateTest`/`ExchangeFlowStateHolderTest`で固定済みであり、
  UI層のaffordance（spec 328の「UI disabledはaffordanceにすぎない」原則と同型）として
  dialogを実装する方が契約面の変更が最小である。
- **T-16要約の件数をlive compositionから再計算する案**: 確認対象とtransport対象の同一性
  （AC-12）が同一immutable値の受渡しで担保されている現行設計を壊す。表示値も同一対象
  （session）から導出する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | `ExchangeEntryRow`（idle）削除、`SelectingPrivacy`のT-15再構成（事前表示・D-09・tier語彙・取り込み導線）、`ExchangeDisclosure`のT-16再構成（要約主面・全文展開・D-09・破棄確認dialog）、要約導出の純関数、`exchangeFlowItems`のClosed分岐変更 | flow面の再構成が収束する唯一のUI module。既存composable/testTag/helper群を再利用 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | T-07前置き面への「AIに相談」方法選択row追加（`openFlow`のみ。start不発行）、idle `exchangeFlowItems` hostの調整 | T-07面とentry統合のhostはrun面のみ（#369適用後のIdle/Cancelled分岐） |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 新規・改訂・削除（specのLocalization節） | spec 123契約（EN/ja双方・format resource） |
| `specs/205-external-agent-exchange/spec.md` | entry規定supersede、AC-3/AC-12表示形式、AC-13語彙、T-15/T-16追記、Change history | disposition §3.12（#372所有の分段改訂） |
| `specs/327-agent-exchange-interview-first/spec.md` | Decision 4配置前提・AC-4/AC-5配置の更新、Change history | disposition §2.3（#372所有） |
| `specs/204-ai-personalization-context-intent-contract/spec.md` | privacy tier節へのD-14文言追記、Change history | disposition §3.11（#372所有・文言のみ） |
| `tests/unit/.../exchange/ExchangeFlowStateHolderTest.kt` ほかunit/instrumentation | T-15事前表示・T-16要約・破棄確認・entry統合の新規oracleと表示面移設のみの既存oracle更新 | spec Test oracle表 |

## Spec amendments（実装PRで実施する正本改訂の箇所）

- **spec 205**: (1) Behavior scenarios「export package生成と送信前確認」のGiven前提を
  T-07方法選択経由へ、「privacy mode選択」に2択固定（D-14語彙・`LOCAL_FULL`不出現）を追記、
  「process recreation後のrun再構築」末尾のV1 entry規定をpre-run request flow規定へ、
  Data and state の「exchange導線の提示はmanual run操作非active時に限定する (V1)」を
  T-07経由idle相談＋run-in維持へ改訂。(2) T-15事前表示・D-09期待明示・pre-send cancelの
  破棄＋確認のscenario/AC追加。(3) AC-3/AC-12の表示形式（要約主面＋全文展開）への改訂と
  gate構造不変の明示、AC-13の破棄語彙化。(4) Change historyへ#372分（9th）を追記。
- **spec 327**: Scope「アプリ内capability説明」・Decision 4・AC-4/AC-5の配置前提を
  「idle entry」→「idle相談導線（T-07短い説明＋T-15本体）」へ。run-in entryへの適用は維持。
  Change history追記。instruction契約（AC-1〜AC-3、`Issue348AiFacingContractSyncTest`）は触れない。
- **spec 204**: 「privacy tier」節に「UI選択肢は2種（redacted / labels、TO-BE D-14語彙）。
  `LOCAL_FULL`は内部契約値として維持し外部workflow UIに出現させない（#206向けDefer）」を
  追記。tier matrix・schema・validatorは不変。Change history追記。

## Migration and recovery

- schema/rule migration: なし。persistent state・session store format・backup/restore
  （backup除外class）への接触なし。
- failure中のrollback: 表示変更のみであり、PR revertで旧entry row＋旧確認面へ戻る。
  revert時にsession/import互換の問題はない（session契約不変）。
- release rollback/downgrade: 残留物なし。旧版のentry rowと新版のsessionは同一契約で
  互換である（`ExportSessionStore` v2のまま）。
- 依頼の実効性は生成時gate・取り込み時検証が担保するため、表示変更の失敗（表示不整合）は
  zero-writeであり、取り込み時のtyped失敗（`SESSION_EXPIRED`/`EXPORT_MISMATCH`/
  `CONTEXT_STALE`）でfail-closedになる。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| EX-AC-01 | instrumentation: T-07「AIに相談」→T-15表示（coordinator `Idle`維持・`start`不発行）、取り込み導線到達、idle entry row不在の否定的観測、run-in entry存在回帰 | `connectedLawnWithQuickstepGithubDebugAndroidTest`（organizer instrumentation lane） |
| EX-AC-02 | unit: flow状態下でのauthoring lease取得成功 + holder/controllerがlease seamに接触しないことの構造確認。instrumentation: flow表示中の材料編集経路回帰 | JVM unit test + organizer instrumentation lane |
| EX-AC-03 | unit: 事前表示material（session有無）と置換確認経由の生成開始 + 既存AC-13系test green。instrumentation: 事前表示の表示/非表示・破棄語彙確認dialog | unit + instrumentation lane |
| EX-AC-04 | unit: 要約導出純関数（件数/種別/上限）+ `ExchangeDisclosureStateTest`回帰。instrumentation: 要約主面・展開・D-09両面表示 | unit + instrumentation lane |
| EX-AC-05 | unit/instrumentation: 選択肢2個・`LOCAL_FULL`文字列UI不在（strings走査含む）・契約値対応回帰 | unit + instrumentation + strings grep |
| EX-AC-06 | spec 205 AC-3/AC-12対応test green + transport 3経路回帰 | JVM unit test |
| EX-AC-07 | unit: `ExchangeCapabilityCopyTest`拡張（T-15での4要素）。instrumentation: run-in entry説明回帰 | unit + instrumentation lane |
| EX-AC-08 | unit: 破棄確認受付→当該sessionのみ`invalidate`・辞退時生存・`cancelling`後不受理（既存disclosure契約の継承）。instrumentation: 破棄ラベル・dialog・送信後「閉じる」確認なし | unit + instrumentation lane |
| EX-AC-09 | specs 205/327/204のdiff review + `Issue348AiFacingContractSyncTest`/`ExchangePackageComposerTest`無編集green | CI + PR review |
| EX-AC-10 | Compose semantics assertion（name/role/state・展開state・dialog role・traversal）+ focus restoration + 200% font scale + light/dark × ja/default screenshot。EN/ja name集合・placeholder一致の機械確認 + 削除string reference grep（0件） | instrumentation + 手動a11y evidence + grep |

含めるべき観点: unit/contract（holder・gate・要約導出の決定性）、UI（T-07/T-15/T-16の遷移と
a11y）、回帰（AC-3/AC-12/AC-13・attempt anchor・freeze・run-in契約・instruction契約の
無編集green）、failure injection（生成失敗3種の既存status回帰、TTL失効後の操作）。

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、organizer instrumentation lane、CI `final-status` green。
`risk: layout-data`/`risk: migration` は付けない（表示・導線のみ）。

## Documentation updates

- [ ] spec status/history（specs 205 / 327 / 204の改訂と、本specのstatus進行）
- [ ] CONTEXT.md（idle AI相談・依頼・語彙規約の用語は#365が所有。本PRでは触れない）
- [ ] DESIGN.md（gate 13の記述変更は不要。module構造・seamの変更なし）
- [ ] ADR（該当なし。IA・表示変更でありADRの3条件を満たさない。disposition §5も「追加・改訂なし」）
- [ ] AGENTS.md（該当なし）

## Execution checklist

- [ ] Current behavior reproduced（#369適用後のT-07面上で現行idle entryがhostされている状態を再現。#369 merge前は`Selecting`分岐のrun-in entryとIdle分岐のidle entryで確認可能）
- [ ] Tests fail for the missing behavior（T-15事前表示・T-16要約・破棄確認・entry統合の新規oracleを先に追加）
- [ ] Minimal implementation completed（host → holder parameter → T-15 → T-16 → strings → spec改訂の順）
- [ ] Migration/recovery verified（該当なし。revert可能性のみ確認）
- [ ] Full relevant verification completed（共通gate + a11y evidence）
- [ ] PR evidence and remaining risks recorded（旧表示oracleのobsolete理由: F-07/E-3/E-5/監査D-2の解消、Contract notesの解釈確定状況）

## Dependencies / blockers / risk

- **#369（blocker。実装着手条件）**: T-07前置き面の実体が前提（Issue本文 `Depends on`）。
  spec執筆・reviewは#369 draft（`3c39ceb2f8`）との整合で可能。#369 Contract notes 1の
  解釈（#372がidle entry統合を所有）と本specは一致している。
- **#365（実装着手条件。正本を先に）**: `CONTEXT.md`語彙のmerge後。
- **#368の適用後構成（前提。実装時に再確認）**: 本planのhost変更は#368適用後のrun面
  （strategy picker撤去・`StrategyWriteArbiter` restart path廃止）を基準にする必要がある。
  `exchangeHolder.strategyArbiterBusy` 接続と`strategyWriteStartBlockedFor`系truth tableの
  #368適用後の形状は、#368 merge時点の実装に合わせて調整する（本specの契約範囲は
  「exchange側gateの非退化」であり、形状指定はしない）。
- **#373/#374との整合（後続）**: T-17入力面・失敗表示（#373）、status card依頼表示と
  置換確認copyへの取り込み済み提案破棄の追記（#374）は本PRでは行わない。
  T-15事前表示は#374のstatus card実装までの暫定的な再発見面である（spec明記済み）。
- **risk**: 低。表示・導線の再構成のみであり、persistent state・DB書込み経路・同意gate構造・
  session契約に触れない。主要リスクは（a）既存exchange契約testの表示面移設時の
  意図しない緩和、（b）破棄確認dialog追加によるcancel経路の重複実行（`cancelling` gateで
  構造的に防止）、（c）#368/#369の並行変更とのhost面競合。いずれも回帰test
  （EX-AC-03/06/08）とhost面の変更範囲限定で対処する。

## Explicitly unverified areas

- #369のT-07前置き面の最終実装形状（方法選択rowのUI pattern・`exchangeFlowItems` host位置の
  再編有無）は#369 draft（未merge）に基づく推定であり、#369 merge後に本planのhost変更箇所を
  再確認する。
- #368適用後の`StrategyWriteArbiter`/strategy freeze wiringの残存形状（上記参照）。
- `ExportSession` の残時間表示に必要な最小projectionの最終形（`ExportSession`渡し vs
  表示用projection data class）は実装PRで確定する（いずれも既存fieldのみから導出可能）。
- 実機clipboard/Share Sheet挙動・200% font scaleでのT-16要約面のreflowはa11y/device
  evidence（EX-AC-10）で確定する。
