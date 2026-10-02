---
issue: "#418"
status: draft
requirements: []
updated: 2026-10-03
---

# Organizer runのUI状態公開がmain threadに収束する

> Risk tier: **L**（refactor。高リスクpath一覧に触れず、新しい書込み経路・migration・
> recovery契約・上流model/loader bridgeも作らない。`organizer/ui/`配下の動作維持refactor。
> 本増分はユーザー指定の手順により、Lの要求を超えてspec/plan review・独立監査を行う）

## Problem

Issue #418が追跡する断続的なAPI 36 organizer instrumentation failureのうち、
thread affinity違反系の3 signatureが未解決である。いずれも同一の静的暴露経路を持ち:

- **T1**: `IllegalArgumentException: Detected multithreaded access to SnapshotStateObserver`
  （[run 35886970989](https://github.com/nunu1733/NunuLauncher/actions/runs/35886970989)、
  [記録](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5798596828)）。
  Focus detach pathで発生。
- **T2**: `IllegalStateException: Method removeObserver must be called on the main thread`
  （[run 35990634088](https://github.com/nunu1733/NunuLauncher/actions/runs/35990634088)、
  [記録](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5813099208)）。
  `DialogWrapper.disposeComposition` / `WrappedComposition.dispose` がworker thread上で
  実行された。
- **T3**: `CalledFromWrongThreadException: Expected: main Calling: DefaultDispatcher-worker-6`
  （[run 36251746356](https://github.com/nunu1733/NunuLauncher/actions/runs/36251746356)、
  [記録](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5847614455)）。
  `withContext(IO)`完了後のsnapshot advanceでRecomposerがIO worker上でinline
  applyChangesを実行した。

静的な暴露経路: `ManualOrganizationPreferences` の全run操作は
`scope.launch { withContext(Dispatchers.IO) { action() } }`（`ManualOrganizationPreferences.kt`
の `execute`）で実行され、`ManualOrganizationRun` はcaller thread上でUI状態
（`stateHolder` / `preparationPhaseHolder` / `operationActiveHolder` の各StateFlow）を
公開する。すなわち **productionで今日、UI状態の公開がIO worker上で行われている**。
Composeのcomposition・lifecycle処理がmain thread前提である以上、非main threadからの
publicationがCompose内部の非同期処理と重なったときに、上記signatureの温床になる。

これらのfailureは断続的で、lane全体の失敗・merge evidenceの阻害を繰り返してきた
（#418の終了条件の主要障害）。

## Outcome

`ManualOrganizationRun` のUI状態公開がすべてmain thread上で実行され、domain作業
（planner・capture・apply等の重い呼び出し）とjournal書込み（fsync付き同期append）が
main threadを塞がない。これによりthread affinity違反系の断続失敗が、観測された
production暴露経路について構造的に排除される。

## Scope

- `ManualOrganizationRun` のUI状態publication（`stateHolder`、`preparationPhaseHolder`、
  `operationActiveHolder`への書込み）を、注入可能なpublication seam経由でmain threadに
  収束する。
- journal emit（`JournalStore.append` の同期fsync）をpublication threadから分離する。
  emitとpublicationが同一critical sectionにある箇所（spec 369 RD-7の
  phase→state→journal単一区間を含む）は、区間内でのstaging・区間外での同時順序flushに
  再構成し、既存のjournal順序契約を維持する。
- 上記を決定的に検証するred-firstなJVM oracle test。
- 既存JVM test / instrumentation testの動作維持（同一seam注入）。

## Non-goals

- LazyList `Index 4, size 4` family、SlotWriter/Activity-destroy process-crash family、
  focus gate（#304）、ComposeTimeout（#473）、receipt test race（#443）の原因解決。
  これらは別signatureとして #418 / 各追跡Issueに残る。
- #418の終了条件（root cause文書化の全体、3回連続full-workflow成功）は本増分では主張しない。
  #418は本増分後もopenのまま。
- UIの表示・操作・状態遷移の変化なし。診断recordの内容変化なし。
- `ExchangeFlowStateHolder` の変更なし（既に `settleDispatcher` / `uiDispatcher` で
  Main-confined。repository内の先行seam）。
- journalのdurability・順序・fail-open契約の変更なし
  （正本: [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §11/§13）。

## Domain language

実装語のみ（publication seam、staged event）。`CONTEXT.md` への反映なし。

## Prior art

- repository内先行例: `ExchangeFlowStateHolder`（`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`）
  の `settleDispatcher` / `uiDispatcher` seam（「Where transport results hop back to the UI
  (Main in production)」）— 2026-10-03 確認。同一問題への既存解答であり、これとの
  整合を優先して採用（[GitHub workflowのExternal reference scan](../../docs/project/github-workflow.md#external-reference-scan設計時の外部参照調査)
  の「repository内に既に同じ契約・seamがある場合は既存architectureとの整合を優先」に従う）。
- 外部例: `省略（理由: 階層Lのrefactorであり、既存pattern（上記先行seam）の横展開を主体とする）`

## Behavior scenarios

### Scenario: worker thread上のrun操作がUI状態を公開する

Given UI（main thread）が `run.stateFlow` を収集している
When 非main thread（`Dispatchers.IO` workerまたは生thread）から `start()` 等のrun操作が完走する
Then すべての `State` / `PreparationPhase` / `operationActive` の書込みはpublication thread
（main）上で実行される
And 操作の結果状態・診断recordは既存と同一である

### Scenario: publicationとemitが同一critical sectionにある（RUN_STARTED）

Given run操作がcomposed phaseに入り、phase→state→journal openを単一区間で行う
When 区間がpublication thread上で実行される
Then journal appendはpublication thread上では実行されない（worker上で同時にflushされる）
And journal内のイベント順序は区間の実行順序と一致する（RUN_STARTEDが後続イベントに先行する）

### Scenario: 区間実行中のcancel

Given run操作がpublication thread上のcritical section内にある
When 別workerから `cancel()` が呼ばれる
Then 既存の勝者規則（`isActiveLocked` による取込判定）が維持され、
cancelが勝った場合RUN_STARTEDはjournalに現れない
And gate commitが先の場合USER_CANCELLEDはRUN_STARTEDに後続する

### Scenario: UI threadからの直接呼び出し

Given JIT host等がmain threadから `continueAfterUsageGate` 等を呼ぶ
When publication thread上からの呼び出しである
Then hopは行われず、inlineで実行される（既存の応答性を維持）

### Scenario: journal append失敗（failure/edge case）

Given journal storeがappendに失敗する（fail-open契約）
When staged eventのflushでappendがfalseを返す
Then run操作は失敗せず継続し、ユーザー可視の状態遷移は変わらない
And 既存のdiagnostics fail-open契約どおりdiagnostics側のみが影響を受ける

## Data and state

- 永続化data・identity・retentionの変化なし。journal書込みの **実行thread** のみが変わる。
- migration / backup / restore / rollbackへの影響なし。Launcher DBへの書込み経路は触れない。

## Permissions, privacy, and security

None（permission・外部送信・sensitive dataの扱いは変わらない）。

## Accessibility and localization

None（表示・focus・文言は変わらない。focus復元等の既存挙動は状態遷移順序が不変であることで維持される）。

## Acceptance criteria

- [ ] AC-1（決定的oracle・red-first）: 非main threadから駆動したrun操作の全UI状態
  publicationがpublication thread上で実行されたことを、JVM unit testが決定的に検証する。
  変更前のcodeでred、変更後greenであることをPRへ記録する。
- [ ] AC-2（既存振る舞いの維持）: 既存 `ManualOrganizationRunTest` 等、
  `app.lawnchair.organizer.*` のJVM test群が、test用publication seam注入でgreenである。
- [ ] AC-3（emit分離）: journal appendがpublication thread上で実行されないことが
  JVM testで検証される。journal順序契約（RUN_STARTED先行・cancel-vs-startの勝者規則）
  を既存testが維持する。
- [ ] AC-4（instrumentation回帰）: `UsageAccessJitInstrumentationTest.crossOriginExchangePresentationPausesTheRunUntilResolution`
  （生thread軸を保持する既存oracle）がCIのmanual-organization-ui laneでgreenである。
- [ ] AC-5（文書同期）: [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md)
  の断続failure分類にT1/T2/T3 signatureと本修正の対応が記録される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 新規JVM test（`tests/unit/app/lawnchair/organizer/ui/`、既存 `organizer-unit-tests` gate内）。recording publication doubleで公開threadを記録し断定する。red-first記録はPR本文 |
| AC-2 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`（CI `organizer-unit-tests` jobのmirror） |
| AC-3 | 同上JVM test群（JournalStore doubleがappend呼出しthreadを記録）＋既存順序契約testの維持 |
| AC-4 | CI `organizer-instrumentation-manual-organization-ui-tests` job（full portfolio run） |
| AC-5 | PR同梱のportfolio文書diff |

## Open questions

- なし（accepted時点で空）。

## Change history

- 2026-10-03: Draft created for #418（Phase1 review待ち）。
