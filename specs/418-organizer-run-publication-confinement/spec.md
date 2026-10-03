---
issue: "#418"
status: implemented
requirements: []
updated: 2026-10-03
---

# Organizer runのUI状態公開がmain threadに収束する

> Revision 4: 2026-10-03 — Phase1 review round 3
> （[Issue #418 comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963754655)）
> の指摘への対応。「gate下のUI待機禁止」（spec 375）と Revision 3の同期Main hopが
> 非両立である指摘を受け、[spec 375](../375-scope-remedy-rebind/spec.md) を
> **本IssueのPRで明示的にAmend** する: gate保持中のUI待機禁止に、条件固定の
> 同期publication hop例外（hop taskはstate bus書込みのみ・lock等を取得しない・
> machine入口のmain fail-fast・gate取得経路の非main実行）を契約化し、SR-AC-08の
> deadlock oracleへ例外の不変条件を追加する。spec 375のadmission線形化点
> （gate内でのoperation生成→`State.Capturing`発行）・処理内容・既存oracleの網羅は不変。
> Risk tierの判定根拠をpath基準（高リスクpath外・新規書込み経路なし）+ Amendment所有の
> 明示へ更新。Round 4指摘
> （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963853590)）
> への同期済み: gate保持時間のwall-clock短時間保証を例外経路で対象外化
> （Main scheduling delayはtask実行時間と独立）、既存UI待機禁止oracleを
> 「例外経路を除く」とscope明示、spec 375 plan.mdへのAmendment note追加と
> `updated` metadata同期。
>
> Revision 3: 2026-10-03 — round 2指摘（gate release後遅延・非同期flusher）の撤回と
> 「machine非main実行 + lock-freeな書込みhop」への統一。
>
> Revision 2: 2026-10-03 — round 1指摘（T1/T3の因果限定・外部参照scan）の反映。
>
> Risk tier: **L**。判定基準は変更pathである（[workflowの階層判定](../../docs/project/github-workflow.md)）:
> 高リスクpath一覧（`organizer/application/`、Launcher3 provider/model、backup、deck等）に
> 触れず、Launcher DB・recovery store・schema・upstream model/loader bridgeへの
> 新しい書込み経路も作らない。ただし本増分は accepted spec 375 の
> 「gate下のUI待機禁止」契約へ狭い例外をAmendする spec-level の変更を含むため、
> 実装PRはそのAmendment（`specs/375-scope-remedy-rebind/spec.md`のdiff）を同じPRで
> owner reviewに付し、手続きは階層H相当（spec/plan review・独立監査・Owner merge判断）を
> 踏む。本増分はユーザー指定の手順により、Lの要求を超えてspec/plan review・独立監査を行う。

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
  今日、run state machine自体がmain上で実行され、journal append
  （`JournalStore.append`のfsync）がmainを塞いでいる。

すなわち現行codeは「UI状態の公開がworker上で行われる経路」と「state machineと
journal I/Oがmain上で実行される経路」の両方をproductionに持つ。Android UI toolkitは
main thread以外からのUI操作を禁止し（[Prior art](#prior-art)参照）、Composeの
composition・lifecycle処理と重なった際の断続違反（T2は実測）の温床である。
これらのfailureはlane全体の失敗・merge evidenceの阻害を繰り返してきた
（#418終了条件の主要障害）。

## Outcome

`ManualOrganizationRun` のUI状態公開がすべてmain thread上で実行され、run state machineの
実行とjournal書込み（fsync付き同期append）はmain thread上からなくなる。
これによりT2のproduction暴露経路が構造的に除去され、journal I/Oのmain-thread占有も
解消される。T2の断続redがCIで非再現になることをもって修正の証明とはせず、
#418の終了判定は引き続き統計的な観察に委ねられる。

## Scope

- **state machineのmain-thread排除**（契約ではなく現状の暴露の除去）:
  main起点のUI呼出し（JIT hostの `LaunchedEffect` からの
  `continueAfterUsageAccessGate` 等）を、既存の `execute` と同一の
  `scope.launch(Dispatchers.IO)` patternでworkerへdispatchする。
  machine入口には、publication thread（main）上での実行をfail-fastするguardを置く
  （production実装のみ活性、test doubleはno-op）。
- **UI状態書込みのmain収束**: `ManualOrganizationRun` の3 holder
  （`stateHolder` / `preparationPhaseHolder` / `operationActiveHolder`）への書込みを、
  専用state bus経由の短いblock-join hop `publicationThread.run { bus.write(...) }`
  でmain上へ収束する。hop taskはrun lock・exchange gate・journalを一切取らない
  lock-freeなStateFlow書込みである。各区間は現行どおりworker上でrun lockを保持したまま
  hopの完了を待つため、区間の原子性（spec 369 RD-7のphase→state→journal単一区間、
  cancel-vs-start勝者規則）とspec 375のgate内完結
  （operation生成→`State.Capturing`発行→gate release）は **現行と同一の時点で維持される**。
- **spec 375のAmendment**（本PRで実施）: 「gate下のUI待機禁止」へ、上記hopに限定した
  狭い例外を契約化する（条件: hop taskはstate bus書込みのみ・lock等を取得しない、
  machine入口のmain fail-fast guard、gate取得経路の非main実行）。
  SR-AC-08のdeadlock oracleへは、例外の不変条件
  （workerがrun lock + gate保持でhop完了を待つ間、Main側がlock/gate待ちへ入らず
  publicationを完了すること、gate release時点で`State.Capturing`可視）を追加する。
- holder群はstate busが唯一の書込み経路として所有し、bypassを構成的に不可能にする。
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
- **既存契約の変更範囲（明示）**: spec 375のadmission線形化点
  （gate内でのoperation生成・`State.Capturing`発行、`AdmissionRefused`無公開）、
  gate下処理の内容、spec 369 RD-7の単一critical sectionと勝者規則、
  [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) の
  journal生存・同期追記・fail-open契約、UIの表示・操作・状態遷移、診断recordの内容は
  いずれも不変。変更するのはspec 375「gate下のUI待機禁止」の **例外契約の追加のみ**
  （本PRでのAmendment。Scope参照）。
- `ExchangeFlowStateHolder` の変更なし（既に `settleDispatcher` / `uiDispatcher` で
  Main-confined。repository内の先行seam）。

## Domain language

実装語のみ（publication seam、state bus）。`CONTEXT.md` への反映なし。

## Prior art

- repository内先行例: `ExchangeFlowStateHolder`
  （`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`）の
  `settleDispatcher` / `uiDispatcher` seam — 2026-10-03確認。Main-confined表示更新の
  既存解答として方向を採用。ただし本変更のsynchronousなcross-thread join・state busは
  このseamより契約が広く、単純横展開ではない（次の外部根拠で補強）。
- Android公式: Processes and threads
  （https://developer.android.com/guide/components/processes-and-threads） —
  2026-10-03確認。「Don't block the UI thread」「Don't access the Android UI toolkit
  from outside the UI thread」の2規則と、worker threadでの作業 + UI threadへのpostと
  いう承認pattern。**採用**: 重いstate machine実行とjournal I/Oは非main、UI状態操作はmain、
  という本設計の直接の根拠（main起点呼出しをworkerへdispatchする規則もここから導かれる）。
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

### Scenario: main起点のUI呼び出しはworkerへdispatchされる

Given JIT hostの `LaunchedEffect` がmain threadから `continueAfterUsageAccessGate` を呼ぶ
When UI呼出しが `scope.launch(Dispatchers.IO)` でdispatchされ、machineがworker上で走る
Then run state machine・journal appendはmain上で実行されない
And pause解除の結果状態は既存と同一である
And もしmain上でmachine入口が呼ばれた場合、guardがfail-fastする

### Scenario: admission anchor（rebind再開）のgate内完結は変わらない

Given rebind再開が `scope.launch(Dispatchers.IO)` からanchor付き `start()` で行われる
When anchorのfresh検証が成立し `complete` がoperationを生成する（gate保持下・worker上）
Then `State.Capturing` を含む初回公開はgate release前に完了する
（書込みのみがblock-join hopでmain上へ収束する。Mainはlock/gate待ちへ入らない）
And anchor検証が不成立の場合（`AdmissionRefused`）はoperation生成も公開も行われない
And 既存のcancel競合規則（cancelはrun lockで直列化）が維持される
And gate下のUI待機禁止は、例外条件を満たすpublication hop以外については維持される

### Scenario: gate下UI待機禁止の例外不変条件（spec 375 SR-AC-08 Amendment）

Given workerがrun lock + exchange mutation gateを保持して同期publication hopの完了を待つ
When hop taskがmain上で実行される
Then Main側はrun lock・exchange gate待ちへ入らずにpublicationを完了できる
And gate releaseの時点で `State.Capturing` が既に可視である
And 例外条件を満たさないMain切替（hop taskからのlock/gate/journal取得）は存在しない

### Scenario: publicationとemitが同一critical sectionにある（RUN_STARTED）

Given run操作がcomposed phaseに入り、phase→state→journal openを単一区間で行う
When 区間がworker上でrun lockを保持したまま実行される
Then state書込みはblock-join hopでmain上へ収束し、その完了後にjournal appendが
同一worker上で同期実行される
And journal内のイベント順序は区間の実行順序と一致する（RUN_STARTEDが後続イベントに先行する）
And observerがRUN_STARTEDを観測するとき、`State.Capturing`は既に観測可能である

### Scenario: 区間実行中のcancel

Given run操作がworker上のcritical section内にある
When 別workerから `cancel()` が呼ばれる
Then 既存の勝者規則（`isActiveLocked` による取込判定）が維持され、
cancelが勝った場合RUN_STARTEDはjournalに現れない
And gate commitが先の場合USER_CANCELLEDはRUN_STARTEDに後続する

### Scenario: journal append失敗（failure/edge case）

Given journal storeがappendに失敗する（fail-open契約）
When 区間内のemitがfalseを返す
Then run操作は失敗せず継続し、ユーザー可視の状態遷移は変わらない
And 既存のdiagnostics fail-open契約どおりdiagnostics側のみが影響を受ける

## Data and state

- 永続化data・identity・retentionの変化なし。journal書込みの実行threadは
  worker上のまま（変更なし）。UI状態書込みの実行threadのみが変わる。
- spec 375のdurable record・export sessionへの書込み経路は変更しない。
- migration / backup / restore / rollbackへの影響なし。Launcher DBへの書込み経路は触れない。

## Permissions, privacy, and security

None（permission・外部送信・sensitive dataの扱いは変わらない）。

## Accessibility and localization

None（表示・focus・文言は変わらない。focus復元等の既存挙動は状態遷移順序が不変であることで維持される）。

## Acceptance criteria

- [x] AC-1（決定的oracle・red-first・構成的検出）: UI状態holder群を専用state busが
  唯一の書込み経路として所有し、busの各書込みが実行threadを記録する。非main threadから
  駆動したrun操作の全state書込みがpublication thread上で実行されたことを、
  JVM unit testが決定的に検証する。holderへの直接書込みはbus所有により構成的に存在しない。
  変更前のcodeでred、変更後greenであることをPRへ記録する。
- [x] AC-2（既存振る舞いの維持）: 既存 `ManualOrganizationRunTest` 等、
  `app.lawnchair.organizer.*` のJVM test群が、test用publication seam注入でgreenである。
- [x] AC-3（machine非main実行とemit契約）: 次の4点をJVM testが決定的に検証する。
  (a) main起点経路のUI dispatch後、journal appendがpublication thread上で実行されない
  （appendはmachine実行thread＝worker上に留まる）。
  (b) publication thread上でmachine入口が呼ばれた場合、guardがfail-fastする
  （production意味論のtest double）。
  (c) 既存の順序契約（RUN_STARTED先行、cancel-vs-start勝者規則、
  `AdmissionRefused`無公開、gate release時点で`State.Capturing`可視）を
  競合oracleが維持する。
  (d) spec 375 Amendmentの例外不変条件（workerがrun lock + gate保持でhop完了を待つ間、
  Main側がlock/gate待ちへ入らずpublicationを完了する）を決定的に検証する。
- [x] AC-4（instrumentation回帰）: `UsageAccessJitInstrumentationTest.crossOriginExchangePresentationPausesTheRunUntilResolution`
  （生thread軸を保持する既存oracle）がCIのmanual-organization-ui laneでgreenである。
- [x] AC-5（文書同期）: [ci-test-portfolio.md](../../docs/engineering/ci-test-portfolio.md)
  の断続failure分類に、T2を「production off-main publication軸の除去対象（本変更）」、
  T1/T3を「category 6・原因未解決（本変更の対象外）」として記録する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | 新規JVM test（`tests/unit/app/lawnchair/organizer/ui/`、既存 `organizer-unit-tests` gate内）。state busのthread記録で全書込みの実行threadを検証。red-first記録はPR本文 |
| AC-2 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`（CI `organizer-unit-tests` jobのmirror） |
| AC-3 | 同JVM test群: guard oracle・append thread oracle・競合順序oracle（DiagnosticsPort doubleがappend呼出しthreadと順序、gate doubleがrelease時点のstate可視性を記録） |
| AC-4 | CI `organizer-instrumentation-manual-organization-ui-tests` job（full portfolio run） |
| AC-5 | PR同梱のportfolio文書diff |

## Open questions

- なし（accepted時点で空）。

## Change history

- 2026-10-03: Draft created for #418。
- 2026-10-03: Revision 2 — Phase1 review round 1
  （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963456834)）
  の指摘1〜4を反映。
- 2026-10-03: Revision 3 — Phase1 review round 2
  （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963610854)）
  の指摘1・2へ再設計。初回公開遅延と非同期flusherを撤回し、
  「machine非main実行 + lock-freeな書込みhop」へ統一。既存契約（375/369/diagnostics）は不変。
- 2026-10-03: Revision 4 — Phase1 review round 3
  （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963754655)）
  の指摘へ対応。spec 375「gate下のUI待機禁止」への狭い例外契約とSR-AC-08 deadlock
  oracleの不変条件追加を、本IssueのPRで明示的にAmendする方針へ確定
  （[specs/375-scope-remedy-rebind/spec.md](../375-scope-remedy-rebind/spec.md) 参照）。
  Round 4
  （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963853590)）
  でAmendmentの波及先同期（処理時間契約の例外対象外化・既存oracleのscope明示・
  375 plan.mdのAmendment note）を完了。
- 2026-10-03: **Accepted** — Phase1 review round 5のApprove
  （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5963896376)、
  head `2363fcd0de`）。Phase 2実装へ進む。
- 2026-10-03: **Implemented** — [PR #504](https://github.com/nunu1733/NunuLauncher/pull/504)
  merge `63c71833c`（head `b624428b82`）。Phase2 review round 4 Clear
  （[comment](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5964557718)）、独立監査
  [docs/assessment/pr-504-organizer-run-publication-confinement.md](../../docs/assessment/pr-504-organizer-run-publication-confinement.md)（GO）、
  CI [run 37092146212](https://github.com/nunu1733/NunuLauncher/actions/runs/37092146212) attempt 3 final-status green
  （AC-4: manual-organization-ui lane 155/155、AC-2: organizer JVM 1816 tests 0 failure）、
  AC-1 red-first記録はPR本文。AC-3はoracle 2クラス8 test。AC-5はportfolio文書同期済み。
  PR CI中に新guardがmachine-on-main暴露2経路（onDispose dismiss・
  interruptAndNavigate内leaveRecoveryResultToHub）を決定的に捕捉し同PRで修正。
  #418自体の終了条件（T1/T3 root cause・3連続full-workflow）は未達でIssue はopen。
