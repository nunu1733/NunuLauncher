# Implementation Plan: Organizer runのUI状態公開のmain thread収束

> Issue: #418
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: L（spec冒頭の判定を参照）

## Current evidence

### 暴露経路（確認済み・本日base `53f90652cf`で確認）

- `ManualOrganizationPreferences.kt:312-316` — `execute(action)` が
  `scope.launch { withContext(Dispatchers.IO) { action() } }`。call sites:
  start / planWithConfirmedScope / confirmSelection / confirm / retryPlanPreview /
  beginRecoveryPreview / confirmRecovery / cancelRecoveryPreview / reopenSelection
  （`:446, :639, :726, :815, :905, :937, :965, :977, :1008, :1066, :1095, :1130, :1157,
  :1194, :1201, :1245, :1330`）。
- `ManualOrganizationRun.kt` — `stateHolder` / `preparationPhaseHolder` /
  `operationActiveHolder`（`:744, :755, :763`）へのwriteはcaller thread上
  （合計33 site、`:823`〜`:2431`）。`synchronized(lock)` 区間内のwriteが大半。
- JIT host（`UsageAccessJitRequest.kt:351-352`）とexchange host
  （`ExchangeFlowUi.kt:2771`）は `collectAsStateWithLifecycle` で収集し、
  `DisposableEffect`（`UsageAccessJitRequest.kt:364, :397`、`ExchangeFlowUi.kt:2783, :2809`）
  でlifecycle observerを登録/解除する。
- journal: `JournalStore.append`（`diagnostics/journal/JournalStore.kt:128-164`）は
  file open + write + **`fd.sync()`（fsync）を毎appendで実行**。
  正本契約はprocess death生存・同期追記
  （[organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §11/§13）。
- RD-7契約（`ManualOrganizationRun.kt:1699-1722`のcomment）:
  phase→state→journal openを単一critical sectionで行い、
  cancel-vs-startの勝者規則（USER_CANCELLEDはRUN_STARTEDに後続、cancel勝利時は
  RUN_STARTED不記録）を担保。

### 観測済みfailure（spec Problem欄のT1/T2/T3）

- T1: run 35886970989 / SnapshotStateObserver multithreaded access（focus detach経路）
- T2: run 35990634088 / removeObserver must be called on main（worker上のdialog dispose）
- T3: run 36251746356 / CalledFromWrongThread（IO worker上のinline applyChanges）

### 先行seam（採用patternの根拠）

- `ExchangeFlowStateHolder`（`ExchangeFlowUi.kt:330-343`）の `settleDispatcher` /
  `uiDispatcher`（Main in production、JVM testでは注入）。

### 推測と事実の区別

- 事実: productionのpublicationがIO worker上で行われること（上記code）、
  3 signatureがmain-thread前提処理の違反であること。
- 推測（限界として明記）: 各redのCompose内部での正確なメカニズム。
  保存report artifactは失効しており、Issueコメントのstack記録が正である。
  本増分は「非main publicationという共通軸の排除」であり、
  個々のredの内部機構の証明は主張しない。

## Design

### Modules and interfaces

- **新seam `RunPublicationThread`**（`lawnchair/src/app/lawnchair/organizer/ui/`に新file）:
  ```kotlin
  interface RunPublicationThread {
      val isCurrent: Boolean
      fun <T> run(block: () -> T): T   // publication thread上で実行してcallerを再開
  }
  ```
  - production: `HandlerRunPublicationThread`（`Handler(Looper.getMainLooper())`）。FIFO順。
  - test double: `DirectRunPublicationThread`（同一thread、既存JVM testの既定動作維持）、
    oracle用recording double。
- **`ManualOrganizationRun`**: constructorへ `publicationThread: RunPublicationThread`
  を追加（既定はproduction実装。既存JVM testは `ManualOrganizationRunTestSupport`
  経由で `DirectRunPublicationThread` を注入）。
- **staged event機構**（`ManualOrganizationRun`内・run-level）:
  `lock`でguardされた `stagedEvents: ArrayDeque<RunEvent>`。
  hopped区間内の `emit(...)` を `stageEvent(...)` へ置換。
  区間直後（caller worker上）で `drainStagedEvents()` がFIFO順に
  `application.diagnostics.emit(event)` を実行する。
  区間外の直接emit（`emitInputNotReady` / `emitStaleRejection`）は
  `drainStagedEvents()` を前置してからemitする。
  順序根拠: publication threadが単一FIFOであるため、staging順 = 区間実行順 =
  既存のlock順序と同値。journal順序契約（RD-7、cancel-vs-start）は維持される。

### Section書換規則（実装時の監査表）

`ManualOrganizationRun` の全publication site（33 site）を次に分類し、(a)/(b)のみ書換:

- (a) UI状態writeを含む `synchronized(lock)` 区間 →
  `publicationThread.run { synchronized(lock) { ... } }` で包む。区間内のemitは
  stageへ置換し、直後にdrainする。
- (b) lock区間に属さない単発write（`:1200, :1227`等）→ 同様に最小区間で包む。
- (c) **書換しない残存**:
  - spec 375のadmission anchor callback内（exchange mutation gate保持下で走る
    `complete`）— gate再入の未確立を理由にcaller thread維持。residualとして記録。
  - UI threadからのみ呼ばれることが保証された経路はhopがno-opになるため無害
    （`isCurrent`でinline）。

### Deadlock監査規則（実装とreviewの両方が確認）

1. hopはlock取得の **前** に行う（lockを保持したままpublication threadを待たない）。
2. publication thread（main）はhopされない（`isCurrent`でinline）。
3. journal storeへの呼出し（fsync）はpublication thread上で行わない
   （staging+worker drain）。
4. exchange mutation gate保持下ではhopしない（規則(c)）。
5. drainはcaller worker上で行い、drain中にlockを長時間保持しない
   （pop単位で短区間取得）。

### Data flow

既存と同一の状態遷移・診断record。変化は実行threadのみ:
caller worker → (hop) → main上の区間実行（state write + event staging）→
worker再開 → journal append（worker上、fsync含む）→ 操作完了。

### Alternatives rejected

- **公開method群のsuspend化 + 入口でのMain hop**: 重いdomain呼び出し
  （compose/plan/apply等）がmain上に移り、さらに内部でIO hopが必要になる。
  約20 method・2 test群・全callerのsignature変更でblast radiusが過大。
- **非同期journal writer（バッファ + 常駐thread）**: journal生存
  （process deathで失われない）・同期追記の正本契約に反する。不採用。
- **Handler.post（非同期・joinなし）**: state machineが直後に自stateを読むため
  可視性/順序の論理raceを生む。不採用。
- **lock保持下でのhop**: mainがlock待ちしている間にlock保持workerがmain実行を
  待つ循環が成立し得る。規則1で排除。
- **何もしない（観測継続）**: 3 signatureの共通静的軸がproduction codeに存在する
  以上、#418の終了条件（安定化）に進めない。不採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/RunPublicationThread.kt`（新） | seam interface + production Handler実装 | 収束先threadの単一点 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | constructor seam注入、(a)/(b)区間のhop化、staged event機構、(c)残存の記録 | publication本体 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTestSupport.kt` | `DirectRunPublicationThread`既定注入 | 既存JVM testの動作維持 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunPublicationConfinementTest.kt`（新） | AC-1/AC-3のred-first oracle | 決定的検証（最低層） |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` | seam注入への追従（必要最小限） | compile/動作維持 |
| `docs/engineering/ci-test-portfolio.md` | T1/T2/T3 signatureの分類表へ本修正対応を追記 | AC-5 |
| `tests/organizer-instrumentation/…`（該当があれば） | 直接変更なし（AC-4は既存oracleのCI再実行） | 回帰確認 |

## Migration and recovery

- schema / rule / store migrationなし。データ移行なし。
- failure中のrollback: 通常のcommit revert。staged未flushイベントは既存の
  fail-openに従い、永続stateを変えない。
- release rollback / downgrade: 影響なし（journal形式不変）。
- backup/restore compatibility: 影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 新規oracle test（red→green、両方のlogをPR記録） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.ManualOrganizationRunPublicationConfinementTest'` |
| AC-2 | 既存organizer JVM test全green | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` ＋ CI `organizer-unit-tests` |
| AC-3 | 同oracle test内のemit分離断定＋既存順序契約test green | 同上 |
| AC-4 | CI `organizer-instrumentation-manual-organization-ui-tests` green（PR run） | hosted CI（full portfolio） |
| AC-5 | portfolio文書diff | PR同梱 |

- 補助: `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug`。
- instrumentation oracleが断続的にred化した場合の取り扱いは
  [quality-strategyの分類規則](../../docs/engineering/quality-strategy.md)に従い、
  signature分類と証拠保存を先に行う。
- testの新規作成では [test-audit skill](../../.agents/skills/test-audit/SKILL.md) を
  手順として適用する（protected contract / impact surface / 重複の確定をPRへ記録）。

## Documentation updates

- [ ] spec status/history（acceptance・実装完了時）
- [ ] CONTEXT.md — 不要（domain language変更なし）
- [ ] DESIGN.md — 不要（module構造・interfaceの外見は不変）
- [ ] ADR — 不要（ADR 3条件を満たす判断なし。採用判断はrepository内先行seamの横展開）
- [ ] AGENTS.md — 不要（新必須commandなし）
- [x] ci-test-portfolio.md — 失敗分類表の同期（AC-5）

## Execution checklist

- [ ] Current behavior: AC-1 oracleが現行codeでredであることを記録。
- [ ] Seam導入 + 区間書換（監査表に従い、(c)残存を明記）。
- [ ] staged event機構 + drain（規則3/5の遵守）。
- [ ] oracle green化、既存test群green。
- [ ] spotlessCheck / assemble 成功。
- [ ] hosted CI（PR run）: organizer-unit-tests、manual-organization-ui lane、final-status。
- [ ] PR evidence（head SHA、red/green記録、CI run URL、残存risk・(c)残存）を記録。
