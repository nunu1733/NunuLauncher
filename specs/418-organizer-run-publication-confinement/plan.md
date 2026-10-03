# Implementation Plan: Organizer runのUI状態公開のmain thread収束

> Issue: #418
> Spec: [spec.md](./spec.md)（Revision 4。spec 375へのAmendmentを含む）
> Status: implemented
> Risk tier: L（path基準）。ただしspec 375「gate下のUI待機禁止」への例外契約のAmendmentを
> 同PRでowner reviewに付すため、手続きは階層H相当（spec/plan review・独立監査・
> Owner merge判断）を踏む（spec冒頭の判定を参照）。

## Current evidence

### 暴露経路（確認済み・base `53f90652cf`）

- `ManualOrganizationPreferences.kt:312-316` — `execute(action)` が
  `scope.launch { withContext(Dispatchers.IO) { action() } }`。call sites:
  start / planWithConfirmedScope / confirmSelection / confirm / retryPlanPreview /
  beginRecoveryPreview / confirmRecovery / cancelRecoveryPreview / reopenSelection
  （`:446, :639, :726, :815, :905, :937, :965, :977, :1008, :1066, :1095, :1130, :1157,
  :1194, :1201, :1245, :1330`）。
- `ManualOrganizationRun.kt` — `stateHolder` / `preparationPhaseHolder` /
  `operationActiveHolder`（`:744, :755, :763`）へのwriteはcaller thread上
  （33 site、`:823`〜`:2431`）。大半が `synchronized(lock)` 区間内
  （例: admission `:870-905`、RD-7区間 `:1699-1722`、terminal `:2405-2431`）。
- admission anchor経路: `ExchangeFlowUi.kt` の `continuePendingImport` が
  `scope.launch(Dispatchers.IO)` からanchor付き `run.start(...)` を呼ぶ
  （`:2231-2251`）。anchorの `complete`（`ManualOrganizationRun.kt:876-894`）は
  run lock + exchange mutation gate保持下でoperation生成と初回公開
  （`:890-891`）を行う。spec 375 SR-AC-08は「gate内でのoperation生成・
  `State.Capturing`発行までの完結」をoracle化する。
- **gate下のUI待機禁止（spec 375、4th review指摘1）**: gate保持中の
  `withContext(uiDispatcher)` 等によるMain切替・完了待機は禁止。根拠は
  「Main: run lock → gate待ち / IO: gate → Main待ち」の循環防止
  （[spec 375](../375-scope-remedy-rebind/spec.md) 該当節、
  [plan](../375-scope-remedy-rebind/plan.md) のdeadlock解析参照）。
  Revision 3の同期Main hopはこのletterに該当するため（round 3 指摘）、
  本revisionで **狭い例外をspec 375へAmend** する（hop taskのlock-free性により
  同循環が構成的に成立しないことを根拠とする）。
- Main起点のmachine実行: `UsageAccessJitRequest.kt:378, :382, :412, :425` の
  `LaunchedEffect` / dialog callbackから `run.continueAfterUsageAccessGate()` が
  直接呼ばれ、今日は `runComposedPhase` → `emit` → `JournalStore.append`（fsync）が
  **main上で実行される**。
- journal: `JournalStore.append`（`diagnostics/journal/JournalStore.kt:128-164`）は
  file open + write + `fd.sync()`を毎appendで実行し、`@Synchronized`。
  正本契約はprocess death生存・同期追記
  （[organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §11/§13）。

### 観測済みfailureと因果の限界

- T2（removeObserver on worker）のみが `runComposedPhase` のworker publicationまで
  静的に追えている（#issuecomment-5824983838）。本変更の直接のregression根拠。
- T1（SnapshotStateObserver）は [静的監査](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5798752755)
  によりfailure後のrun操作が説明要因から除外されており、原因未確定。
- T3（CalledFromWrongThread）はrun面を経由しないhub画面の記録で、別signature。
- T1/T3を本変更の解決対象に含めない（round 1 指摘1）。

### 先行seamと外部根拠

- `ExchangeFlowStateHolder`（`ExchangeFlowUi.kt:330-343`）の `settleDispatcher` /
  `uiDispatcher`。方向の採用根拠。契約の広さは本変更の方が大きい（外部根拠で補強、
  spec Prior art欄参照）。

## Design

### 基本原則（Revision 4）

1. **run state machineはpublication thread（main）上では実行しない。**
   main起点のUI呼出しは既存 `execute` と同一のworker dispatchに統一し、
   machine入口にfail-fast guardを置く。これにより **mainはrun lockを取らない**
   （run lockの取得者はmachine実行のみであるため、構成的に成立する）。
2. **UI状態の書込みのみをmainへ収束する。** 書込みはrun lock・gate・journalを
   取らない短いblock-join hop（`publicationThread.run { bus.write(...) }`）で実行する。
   呼出し元の区間はworker上でrun lockを保持し続けるため、区間の原子性・公開時点は
   現行と同一である（spec 375のgate内完結・RD-7の単一区間は構造ごと保持）。
3. **journal・診断契約は触らない。** emitは区間内のworker上で同期のまま
   （durability barrierの変更なし。非同期flusherは廃止）。
4. **spec 375「gate下のUI待機禁止」への例外をAmendする。** 同期publication hopの
   みを許す条件固定の例外（hop taskはstate bus書込みのみ・lock等を取得しない・
   machine入口のmain fail-fast・gate取得経路の非main実行）を
   `specs/375-scope-remedy-rebind/spec.md` へ契約化し、SR-AC-08のdeadlock oracleへ
   例外の不変条件を追加する（round 3 reviewの修正案どおり）。

### Modules and interfaces

- **新seam `RunPublicationThread`**（`lawnchair/src/app/lawnchair/organizer/ui/`に新file）:
  ```kotlin
  interface RunPublicationThread {
      val isCurrent: Boolean
      fun <T> run(block: () -> T): T            // publication thread上で実行してcallerを再開
      fun assertNotPublicationThread() {}        // machine入口guard。productionはmainでthrow
  }
  ```
  - production: `HandlerRunPublicationThread`（`Handler(Looper.getMainLooper())`）。FIFO順。
    `assertNotPublicationThread()` はmain threadで `IllegalStateException` を投げる。
  - test double: `DirectRunPublicationThread`（同一thread、guard no-op。
    既存JVM testの既定動作維持）、oracle用recording / dedicated-thread double。
- **内部state bus `ManualOrganizationRunStateBus`**（同file群）:
  3つのholderを **privateに所有** し、書込みmethod（`publishState` 等）のみを公開。
  各書込みで `writeThreadTracker: ((String, Thread) -> Unit)?`（test注入、既定null）を呼ぶ。
  run本体はholderへ直接触れられないため、bypassは構成的に不可能（AC-1の検出主体）。
- **`ManualOrganizationRun`**: constructorへ `publicationThread: RunPublicationThread`
  を追加。33 siteのholder書込みを `publishXxx(...)` helper（bus経由のhop）へ置換。
  公開method入口に `publicationThread.assertNotPublicationThread()` を置く
  （start / cancel / dismiss / confirmSelection / planWithConfirmedScope /
  reopenSelection / continueAfterUsageAccessGate / attachIntent / claimGenerationEpoch /
  commitGeneratedSession / cleanupBoundExport / discardScopeBoundRequest /
  savePendingImportForLiveOwner / recovery一式）。
- **UI呼出しのdispatch統一（machine非main実行）**:
  - `UsageAccessJitRequest.kt` — waiter wakeup（`:375-388`）、
    `grantCheckTick` effect（`:407-413`）、dialog `onContinue`（`:425`）の
    `run.continueAfterUsageAccessGate()` を `scope.launch(Dispatchers.IO) { ... }` へ
    統一（`markPresented` 等のgate単独呼出しは対象外）。
  - 実装時に `ManualOrganizationRun` の公開methodをUI/main呼出し経路で
    grepし、直接呼出しが残っていないことを監査する（checklist）。

### Section書換規則（実装時の監査表）

`ManualOrganizationRun` の全publication site（33 site）を次に分類し、全てを(a)として書換:

- (a) `holder.value = X` → `publicationThread.run { bus.publishXxx(X) }`
  （hop taskはlock-free。呼出し元区間のlock保持・区間構造・emit位置は不変）。
- 分離・遅延・flusherの新設はしない（Revision 2で撤回済み）。

### Deadlock監査規則（実装とreviewの両方が確認）

1. hop taskはrun lock / exchange mutation gate / usage access gate / journal /
   durable storeを **一切取らない** lock-freeなStateFlow書込みに限定する
   （spec 375 Amendment例外条件(i)）。
2. machineはpublication thread上で実行されない（guard、例外条件(ii)）。
   よってmainがrun lockを待つことは構成的に生じない。
3. workerがrun lock（±gate）を保持したままhopの完了を待つ関係は、
   mainが待つ対象を持たないことで常に解消される（循環なし）。
   これはspec 375が禁止する「Main: run lock → gate待ち / IO: gate → Main待ち」
   循環が成立しないことの構成的根拠であり、Amendment例外の根拠でもある。
4. mainがexchange mutation gateを取る経路を設けない
   （既存のgate取得はholder coroutineのIO scope上。実装時にgrepで監査。
   Amendment例外条件(ii)の後半）。
5. JVM testではDirect doubleがhopをno-opにするため、既存の同一thread実行が維持される。

### Data flow

既存と同一の状態遷移・診断record。変化はUI状態書込みの実行threadのみ:
machine実行（worker、run lock保持）→ state書込みのみmain上へblock-join →
完了後workerへ復帰 → journal append（同一worker上、同期・fsync込み）→ 操作完了。

### Alternatives rejected

- **spec 375を不変としたままのanchor経路residual化（caller thread残置）**:
  callerが `scope.launch(Dispatchers.IO)` でありmain-only証明ができない
  （round 1 指摘2）。owner decisionなしにresidual化することもreview契約上不可。不採用。
- **Revision 2の初回公開遅延（gate release後の再検証付き公開）**:
  spec 375 SR-AC-08の線形化点を変えるため不採用（round 2 指摘1）。
- **Revision 2の非同期flusher（Main起点のjournal引渡し）**:
  同期durability契約（crash直前eventからのphase特定）を緩めるため不採用
  （round 2 指摘2）。flushLock・staged queueも一緒に廃止。
- **公開method群のsuspend化 + 入口でのMain hop**: 重いdomain呼び出しがmain上に移り、
  さらに内部でIO hopが必要。約20 method・全callerのsignature変更でblast radiusが過大。
- **区間ごとのmain実行（Revision 1のsection hop）**: emitが区間に同在するため
  main上でfsyncが発生する。machine非main原則と矛盾。不採用。
- **Handler.post（非同期・joinなし）**: state machineが直後に自stateを読むため
  可視性/順序の論理raceを生む。不採用。
- **何もしない（観測継続）**: T2の静的経路がproduction codeに存在する以上、
  #418の終了条件（安定化）に進めない。不採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/375-scope-remedy-rebind/spec.md` | 「gate下のUI待機禁止」への同期publication hop例外の契約化 + SR-AC-08例外不変条件oracleの追記 + header Amendment note | round 3指摘の正本同期（Amendment本体） |
| `lawnchair/src/app/lawnchair/organizer/ui/RunPublicationThread.kt`（新） | seam interface + production Handler実装 + guard | 収束先threadの単一点とmachine非main実行の強制 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRunStateBus.kt`（新） | 3 holderの所有と書込みmethod、thread記録hook | AC-1の構成的検出（bypass不能化） |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | seam注入、33 siteの書込みhop化、入口guard、`StartAdmissionAnchor`契約commentの同期 | publication本体（区間構造・emit位置は不変） |
| `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt` | main起点の `continueAfterUsageAccessGate` 3箇所をworker dispatchへ統一 | machine非main実行（現状main上でfsyncする経路の除去） |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTestSupport.kt` | `DirectRunPublicationThread`既定注入 | 既存JVM testの動作維持 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunPublicationConfinementTest.kt`（新） | AC-1（全書込みthread検証）とAC-3(a)(b)(d)のoracle | 決定的検証（最低層） |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunAdmissionPublicationTest.kt`（新） | AC-3(c)(d): anchor gate内完結・`AdmissionRefused`無公開・cancel競合順序・hop例外不変条件oracle | spec 375 Amendmentの検証 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` | seam注入への追従（必要最小限） | compile/動作維持 |
| `docs/engineering/ci-test-portfolio.md` | T2 = 本変更対象 / T1・T3 = category 6未解決 の分類同期 | AC-5 |
| instrumentation tests / spec 369 / organizer-diagnostics.md | 変更なし | 既存契約の不変を明示 |

## Migration and recovery

- schema / rule / store migrationなし。データ移行なし。
- journal書込みの実行thread・時点・durabilityは現行のまま（barrier変更なし）。
- failure中のrollback: 通常のcommit revert。永続stateへの影響なし。
- release rollback / downgrade: 影響なし（journal形式不変）。
- backup/restore compatibility: 影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 新規oracle test（red→green、両方のlogをPR記録。redは「seam+bus導入のみでhop前」のhead、greenはhop後head） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.ManualOrganizationRunPublicationConfinementTest'` |
| AC-2 | 既存organizer JVM test全green | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` ＋ CI `organizer-unit-tests` |
| AC-3 | guard oracle / append thread oracle / 競合順序oracle（red-first記録を含む） | 同上（新規2 test class） |
| AC-4 | CI `organizer-instrumentation-manual-organization-ui-tests` green（PR run） | hosted CI（full portfolio） |
| AC-5 | portfolio文書diff | PR同梱 |

- 補助: `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`。
- instrumentation oracleが断続的にred化した場合の取り扱いは
  [quality-strategyの分類規則](../../docs/engineering/quality-strategy.md)に従い、
  signature分類と証拠保存を先に行う。
- testの新規作成では [test-audit skill](../../.agents/skills/test-audit/SKILL.md) を
  手順として適用する（protected contract / impact surface / 重複の確定をPRへ記録）。

## Documentation updates

- [x] spec status/history（acceptance・実装完了時）
- [x] spec 375 Amendment（「gate下のUI待機禁止」例外契約 + SR-AC-08不変条件oracle。
  本planのChange setどおり同PRで実施済み）
- [ ] CONTEXT.md — 不要（domain language変更なし）
- [ ] DESIGN.md — 不要（module構造・interfaceの外見は不変）
- [ ] ADR — 不要（ADR 3条件を満たす判断なし。契約変更はspec 375のAmendmentとして正本側で行う）
- [ ] AGENTS.md — 不要（新必須commandなし）
- [x] ci-test-portfolio.md — 失敗分類表の同期（AC-5）

## Execution checklist

- [x] Current behavior: AC-1/AC-3 oracleが「seam+bus導入のみ」のheadでredであることを記録。
- [x] Seam + state bus + 33 siteのhop化（監査表に従う）。
- [x] 入口guard + UI呼出しdispatch統一（実装中にCI guardがmachine-on-main 2経路を追加捕捉し同PRで修正）（UsageAccessJitRequest 3箇所、grep監査）。
- [x] oracle green化、既存test群green。
- [x] spotlessCheck / assemble 成功。
- [x] hosted CI（PR run）: organizer-unit-tests、manual-organization-ui lane、final-status。
- [x] PR evidence（head SHA、red/green記録、CI run URL、残存risk）を記録。
