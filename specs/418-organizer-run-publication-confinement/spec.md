---
issue: "#418"
status: draft
requirements: []
updated: 2026-10-03
---

# Organizer runのUI状態公開がmain threadに収束する

> Revision 2: 2026-10-03 — Phase1 review
> （[Issue #418 comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963456834)）
> の指摘1〜4を反映。指摘1: 因果主張をT2とproductionのoff-main publication軸に限定し、
> T1/T3をNon-goals（原因未解決）へ移す。指摘2: spec 375 admission anchorの初回publicationを
> gate release後の再検証付きhopped区間へ分離する設計をspec化し、AC-1のoracleを
> 「seam経由の呼出し」ではなく「全state writeの収束」を構成的に検出する形へ変更。
> 指摘3: journal flushを単一serialized flush ownerで直列化し、Main起点経路では
> publication thread上でappendしない設計へ変更（決定的oracle2件をAC-3へ追加）。
> 指摘4: External reference scanを実施しPrior artへ記録。
>
> Risk tier: **L**（refactor。高リスクpath一覧に触れず、新しい書込み経路・migration・
> recovery契約・上流model/loader bridgeも作らない。`organizer/ui/`配下の動作維持refactor。
> 本増分はユーザー指定の手順により、Lの要求を超えてspec/plan review・独立監査を行う）

## Problem

Issue #418が追跡する断続的なAPI 36 organizer instrumentation failureのうち、
thread affinity違反系として次の3 signatureが記録されている:

- **T1**: `IllegalArgumentException: Detected multithreaded access to SnapshotStateObserver`
  （[run 35886970989](https://github.com/nunu1733/NunuLauncher/actions/runs/35886970989)、
  [記録](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5798596828)、
  [静的監査](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5798752755)）。
  静的監査は、一次failureがIdle(5)→Idle(4)の収縮中のfocus detach経路で発生し、
  後段のrun操作はfailure後に位置するため説明要因にならないこと、worker accessの
  原因は未確定であることを記録している。**原因未解決**。
- **T2**: `IllegalStateException: Method removeObserver must be called on the main thread`
  （[run 35990634088](https://github.com/nunu1733/NunuLauncher/actions/runs/35990634088)、
  [記録](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5813099208)、
  [静的経路](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5824983838)）。
  生threadで駆動された `runComposedPhase` のworker上で `DialogWrapper` /
  `WrappedComposition` のdisposeとlifecycle observer解除が実行された。
  **本変更が対象とする唯一の、`ManualOrganizationRun` publicationまで静的に追えたsignature**。
- **T3**: `CalledFromWrongThreadException: Expected: main Calling: DefaultDispatcher-worker-6`
  （[run 36251746356](https://github.com/nunu1733/NunuLauncher/actions/runs/36251746356)、
  [記録](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5847614455)）。
  run面を経由しないhub画面の記録で、DataStore writeとCompose test環境のinline applyの
  timing raceと分類されている。**原因未解決（別signature）**。

このうち本変更の根拠になる **確認済みのproduction暴露経路** は:

- `ManualOrganizationPreferences` の全run操作は
  `scope.launch { withContext(Dispatchers.IO) { action() } }`（`execute`）で実行され、
  `ManualOrganizationRun` はcaller thread（IO worker）上でUI状態（`stateHolder` /
  `preparationPhaseHolder` / `operationActiveHolder`）を公開している。
- exchange再開面のadmission（`continuePendingImport`）も
  `scope.launch(Dispatchers.IO)` からanchor付き `run.start(...)` を呼び、
  exchange mutation gate保持下のworker上で初回UI状態を公開している。
- 逆に、Main起点の `continueAfterUsageAccessGate`（JIT hostの`LaunchedEffect`）経路では
  今日、journal append（`JournalStore.append`のfsync）がmain thread上で実行されている。

すなわち現行codeは「UI状態の公開がworker上で行われる経路」と「journal I/Oがmain上で
行われる経路」の両方をproductionに持つ。Android UI toolkitはmain thread以外からの
UI操作を禁止し（[Prior art](#prior-art)参照）、Composeのcomposition・lifecycle処理と
重なった際の断続違反（T2は実測）の温床である。これらのfailureはlane全体の失敗・
merge evidenceの阻害を繰り返してきた（#418終了条件の主要障害）。

## Outcome

`ManualOrganizationRun` のUI状態公開がすべてmain thread上で実行され、domain作業と
journal書込み（fsync付き同期append）がmain threadを塞ぐことがなくなる。
これによりT2のproduction暴露経路が構造的に除去され、journal I/Oのmain-thread占有も
解消される。T2の断続redがCIで非再現になることをもって修正の証明とはせず、
#418の終了判定は引き続き統計的な観察に委ねられる。

## Scope

- `ManualOrganizationRun` のUI状態publication（`stateHolder`、`preparationPhaseHolder`、
  `operationActiveHolder`への書込み）を、注入可能なpublication seam経由でmain threadに
  収束する。state holder群は専用の内部state busが唯一の書込み経路として所有し、
  bypassを構成的に不可能にする。
- spec 375のadmission anchor（rebind再開）経路の初回publication時点を変更する:
  gate保持下の `complete` はoperation生成までを行い、初回UI状態の公開は
  gate release後のhopped区間で `isActiveLocked` 再検証付きで行う
  （admission決定の原子性・`AdmissionRefused`で無公開の契約は維持）。
- journal emitをpublication threadから分離する。区間内でのstaging・単一serialized
  flush ownerによるdequeue→appendの直列化とし、Main起点経路ではpublication thread上で
  appendせずflusherへ引き渡す。journal順序契約（spec 369 RD-7のphase→state→journal、
  cancel-vs-start勝者規則）とdurability契約は維持する。
- 上記を決定的に検証するred-firstなJVM oracle test。
- 既存JVM test / instrumentation testの動作維持（同一seam注入）。

## Non-goals

- **T1（SnapshotStateObserver multithreaded access）とT3（CalledFromWrongThreadException）
  の原因解決**。両者は本変更との因果が確立していない別signatureとして
  #418にcategory 6（unknown / investigation required）のまま残る。本変更のACでは
  解決済みと分類しない。
- LazyList `Index 4, size 4` family、SlotWriter/Activity-destroy process-crash family、
  focus gate（#304）、ComposeTimeout（#473）、receipt test race（#443）の原因解決。
- #418の終了条件（root cause文書化の全体、3回連続full-workflow成功）は本増分では主張しない。
  #418は本増分後もopenのまま。
- UIの表示・操作・状態遷移の変化なし。診断recordの内容変化なし。
- `ExchangeFlowStateHolder` の変更なし（既に `settleDispatcher` / `uiDispatcher` で
  Main-confined。repository内の先行seam）。
- journalのdurability・順序・fail-open契約の変更なし
  （正本: [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §11/§13）。
- spec 375のadmission決定（gate内fresh検証とoperation生成の原子性、
  `AdmissionRefused`で無公開）の変更なし。変更するのは初回UI状態公開の **時点** のみ。

## Domain language

実装語のみ（publication seam、state bus、staged event、flush owner）。
`CONTEXT.md` への反映なし。

## Prior art

- repository内先行例: `ExchangeFlowStateHolder`
  （`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`）の
  `settleDispatcher` / `uiDispatcher` seam — 2026-10-03確認。Main-confined表示更新の
  既存解答として方向を採用。ただし本変更のsynchronousなcross-thread join・state bus・
  serialized flushはこのseamより契約が広く、単純横展開ではない（次の外部根拠で補強）。
- Android公式: Processes and threads
  （https://developer.android.com/guide/components/processes-and-threads） —
  2026-10-03確認。「Don't block the UI thread」「Don't access the Android UI toolkit
  from outside the UI thread」の2規則と、worker threadでの作業 + UI threadへのpostと
  いう承認pattern。**採用**: 重いdomain作業とjournal I/Oは非main、UI状態操作はmain、
  という本設計の直接の根拠。
- Android公式: Composeのmental model
  （https://developer.android.com/develop/ui/compose/mental-model） — 2026-10-03確認。
  「Compose operates on the main thread」でありcomposition外部からの状態変更は
  UI threadのcallbackから行うべきこと。**部分的採用**: publicationをmainに収束する保守
  的解釈を採る（Compose将来のmultithread化への揺らぎは本変更の契約を変えない）。

## Behavior scenarios

### Scenario: worker thread上のrun操作がUI状態を公開する

Given UI（main thread）が `run.stateFlow` を収集している
When 非main thread（`Dispatchers.IO` workerまたは生thread）から `start()` 等のrun操作が完走する
Then すべての `State` / `PreparationPhase` / `operationActive` の書込みはpublication thread
（main）上で実行される
And 操作の結果状態・診断recordは既存と同一である

### Scenario: admission anchor（rebind再開）での初回公開

Given rebind再開が `scope.launch(Dispatchers.IO)` からanchor付き `start()` で行われる
When anchorのfresh検証が成立し `complete` がoperationを生成する（gate保持下・worker上）
Then 初回UI状態の公開はgate release後のhopped区間で行われ、
その区間は `isActiveLocked` を再検証する
And anchor検証が不成立の場合（`AdmissionRefused`）はoperation生成も初回公開も行われない
And 初回公開の再検証前にcancelが勝った場合、初回公開は行われずcancel面へ遷移する

### Scenario: publicationとemitが同一critical sectionにある（RUN_STARTED）

Given run操作がcomposed phaseに入り、phase→state→journal openを単一区間で行う
When 区間がpublication thread上で実行される
Then journal appendはpublication thread上では実行されない
（worker起点ではworker上に、Main起点では単一flush owner上に引き渡される）
And journal内のイベント順序は区間の実行順序と一致する（RUN_STARTEDが後続イベントに先行する）

### Scenario: Main起点のJIT resume

Given JIT hostの `LaunchedEffect` がmain threadから `continueAfterUsageAccessGate` を呼ぶ
When publication区間がinline実行され、journal eventがstagingされる
Then journal appendはflush owner（非main）上で実行され、mainはappendを待たない
And pause解除の結果状態は既存と同一である

### Scenario: 区間実行中のcancel

Given run操作がpublication thread上のcritical section内にある
When 別workerから `cancel()` が呼ばれる
Then 既存の勝者規則（`isActiveLocked` による取込判定）が維持され、
cancelが勝った場合RUN_STARTEDはjournalに現れない
And gate commitが先の場合USER_CANCELLEDはRUN_STARTEDに後続する

### Scenario: UI threadからの直接呼び出し

Given JIT host等がmain threadから `continueAfterUsageAccessGate` 等を呼ぶ
When publication thread上からの呼び出しである
Then state公開のhopは行われずinlineで実行される（既存の応答性を維持）

### Scenario: journal append失敗（failure/edge case）

Given journal storeがappendに失敗する（fail-open契約）
When flush ownerのappendがfalseを返す
Then run操作は失敗せず継続し、ユーザー可視の状態遷移は変わらない
And 既存のdiagnostics fail-open契約どおりdiagnostics側のみが影響を受ける

## Data and state

- 永続化data・identity・retentionの変化なし。journal書込みの **実行threadと時点**
  （admission初回公開の分離、Main起点のflush引き渡し）のみが変わる。
- spec 375のdurable record・export sessionへの書込み経路は変更しない。
- migration / backup / restore / rollbackへの影響なし。Launcher DBへの書込み経路は触れない。

## Permissions, privacy, and security

None（permission・外部送信・sensitive dataの扱いは変わらない）。

## Accessibility and localization

None（表示・focus・文言は変わらない。focus復元等の既存挙動は状態遷移順序が不変であることで維持される）。

## Acceptance criteria

- [ ] AC-1（決定的oracle・red-first・構成的検出）: UI状態holder群を専用state busが
  唯一の書込み経路として所有し、busの各書込みが実行threadを記録する。非main threadから
  駆動したrun操作の全state書込みがpublication thread上で実行されたことを、
  JVM unit testが決定的に検証する。holderへの直接書込みはbus所有により構成的に存在しない。
  変更前のcodeでred、変更後greenであることをPRへ記録する。
- [ ] AC-2（既存振る舞いの維持）: 既存 `ManualOrganizationRunTest` 等、
  `app.lawnchair.organizer.*` のJVM test群が、test用publication seam注入でgreenである。
- [ ] AC-3（emit分離と順序）: 次の3点をJVM testが決定的に検証する。
  (a) journal appendがpublication thread上で実行されない（worker起点・Main起点の両経路）。
  (b) 単一flush ownerによりdequeue→appendの順序が保存される
  （並行drainでもRUN_STARTED→USER_CANCELLEDの順序が崩れない）。
  (c) admission anchor経路で、`AdmissionRefused`時にjournal event・state公開が生じないこと、
  およびgate commit後のcancel競合でRUN_STARTED→USER_CANCELLED順が維持されること。
  既存の順序契約testもgreenを維持する。
- [ ] AC-4（instrumentation回帰）: `UsageAccessJitInstrumentationTest.crossOriginExchangePresentationPausesTheRunUntilResolution`
  （生thread軸を保持する既存oracle）がCIのmanual-organization-ui laneでgreenである。
- [ ] AC-5（文書同期）: [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md)
  の断続failure分類に、T2を「production off-main publication軸の除去対象（本変更）」、
  T1/T3を「category 6・原因未解決（本変更の対象外）」として記録する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 新規JVM test（`tests/unit/app/lawnchair/organizer/ui/`、既存 `organizer-unit-tests` gate内）。state busのthread記録で全書込みの実行threadを検証。red-first記録はPR本文 |
| AC-2 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`（CI `organizer-unit-tests` jobのmirror） |
| AC-3 | 同JVM test群: flush owner直列化oracle・Main起点resume oracle・admission競合oracle ＋ 既存順序契約testの維持（DiagnosticsPort/JournalStore doubleがappend呼出しthreadと順序を記録） |
| AC-4 | CI `organizer-instrumentation-manual-organization-ui-tests` job（full portfolio run） |
| AC-5 | PR同梱のportfolio文書diff |

## Open questions

- なし（accepted時点で空）。

## Change history

- 2026-10-03: Draft created for #418。
- 2026-10-03: Revision 2 — Phase1 review
  （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963456834)）
  の指摘1〜4を反映。
