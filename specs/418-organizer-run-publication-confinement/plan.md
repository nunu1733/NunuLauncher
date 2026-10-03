# Implementation Plan: Organizer runのUI状態公開のmain thread収束

> Issue: #418
> Spec: [spec.md](./spec.md)（Revision 2）
> Status: draft
> Risk tier: L（spec冒頭の判定を参照）

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
  （33 site、`:823`〜`:2431`）。大半が `synchronized(lock)` 区間内。
- admission anchor経路: `ExchangeFlowUi.kt` の `continuePendingImport` が
  `scope.launch(Dispatchers.IO)`（`:2140`付近）からanchor付き `run.start(...)` を呼び
  （`:2231-2251`）、exchange mutation gate保持下のworker上で初回UI状態を公開する。
  lock順序契約は「run lock → gate、逆は禁止」（`ManualOrganizationRun.kt` constructor
  comment `:305`付近）。**このためanchor区間をmainへhopすると
  「mainがgateを保持→run lock取得」の逆順となり、workerの「run lock保持→gate待ち」と
  deadlockする。hop不可能な区間である。**
- Main起点のJIT resume: `UsageAccessJitRequest.kt:378, :382, :412` の `LaunchedEffect`
  から `run.continueAfterUsageAccessGate()` が直接呼ばれ、今日はその中で
  `runComposedPhase` → `emit` → `JournalStore.append`（fsync）が **main上で実行される**。
- journal: `JournalStore.append`（`diagnostics/journal/JournalStore.kt:128-164`）は
  file open + write + **`fd.sync()`（fsync）を毎appendで実行**し、`@Synchronized`。
  正本契約はprocess death生存・同期追記
  （[organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §11/§13）。
- RD-7契約（`ManualOrganizationRun.kt:1699-1722`のcomment）:
  phase→state→journal openを単一critical sectionで行い、cancel-vs-startの勝者規則を担保。

### 観測済みfailureと因果の限界

- T2（removeObserver on worker）のみが `runComposedPhase` のworker publicationまで
  静的に追えている（#issuecomment-5824983838）。本変更の直接のregression根拠。
- T1（SnapshotStateObserver）は [静的監査](https://github.com/nunu1733/NunuLauncher/issues/418#issuecomment-5798752755)
  によりfailure後のrun操作が説明要因から除外されており、原因未確定。
- T3（CalledFromWrongThread）はrun面を経由しないhub画面の記録で、別signature。
- T1/T3を本変更の解決対象に含めない（spec Revision 2 指摘1）。

### 先行seamと外部根拠

- `ExchangeFlowStateHolder`（`ExchangeFlowUi.kt:330-343`）の `settleDispatcher` /
  `uiDispatcher`。方向の採用根拠。契約の広さは本変更の方が大きい（外部根拠で補強、
  spec Prior art欄参照）。

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
- **内部state bus `ManualOrganizationRunStateBus`**（同file群）:
  3つのholder（state / preparationPhase / operationActive）を **privateに所有** し、
  書込みmethod（`publishState` 等）のみを公開。各書込みで
  `writeThreadTracker: ((String, Thread) -> Unit)?`（test注入、既定null）を呼ぶ。
  run本体はholderへ直接触れられないため、bypassは構成的に不可能（AC-1の検出主体）。
- **staged event機構と単一flush owner**（`ManualOrganizationRun`内）:
  - `lock`でguardされた `stagedEvents: ArrayDeque<RunEvent>`。hopped区間内の
    `emit(...)` を `stageEvent(...)` へ置換。
  - **drain mutex（`flushLock`）が dequeue→append のloop全体を所有**する。
    非publication threadからのdrainは `flushLock` 下で同期的に実行（既存のdurability）。
    publication threadからのdrainは、単一threadの `journalFlusher: Executor`
    （production: daemon単一thread、test: 呼出しthread直列double）へtaskを引き渡して
    即returnし、mainを塞がない。flusherのtaskも `flushLock` を通るため、
    並行drainでもdequeue順 = append順が保存される（指摘3(b)の解消）。
  - 区間外の直接emit（`emitInputNotReady` / `emitStaleRejection`）は
    drain呼出しを前置してから既存どおり同期emitする（worker上）。
- **admission anchor経路の初回公開分離**（指摘2の解消）:
  - gate保持下の `complete`: operation生成・`activeOperation` 等のbookkeepingのみ行い、
    初回公開は `pendingFirstPublication` としてoperationに記録する。
  - anchor戻り後（gate release済み・`start` のlock区間抜け後）、
    `publicationThread.run { synchronized(lock) { if (isActiveLocked(op)) busへ初回公開 } }`
    を実行する。lock順序契約（run lock → gate）を崩さない。
  - `AdmissionRefused` では `pendingFirstPublication` が立たないため無公開のまま
    （spec 375契約維持）。初回公開の再検証前にcancelが勝った場合は公開をskipする。
  - plain `start()`（anchor無し）はlock区間ごとhopするため今日と同一の公開時点を保つ。

### Section書換規則（実装時の監査表）

`ManualOrganizationRun` の全publication site（33 site）を次に分類し、(a)/(b)/(d)のみ書換:

- (a) UI状態writeを含む `synchronized(lock)` 区間 →
  `publicationThread.run { synchronized(lock) { ... } }` で包む。区間内のemitは
  stageへ置換し、区間return後にdrainする。
- (b) lock区間に属さない単発write → 同様に最小区間で包む。
- (c) **書換しない残存**: なし（Revision 2で解消。anchor経路は初回公開分離により収束対象に含む）。
- (d) anchor経路の初回公開 → 上記の分離設計（gate release後の再検証付きhopped区間）。

### Deadlock監査規則（実装とreviewの両方が確認）

1. hopはlock取得の **前** に行う（lockを保持したままpublication threadを待たない）。
   ただしpublication thread上の区間がlockを取ることは許容する（inline除く）。
2. publication thread（main）自身はhopされない（`isCurrent`でinline）。
3. journal append（fsync）はpublication thread上で行わない
   （staging + flush owner。Main起点はflusherへの非同期引渡し）。
4. **exchange mutation gate保持下ではhopしない**（lock順序契約の逆順になるため）。
   gate保持区間のUI公開は(d)の分離設計でのみ収束させる。
5. drainは `flushLock` で直列化し、drain中にrun lockを保持し続けない
   （dequeue時のみ短区間取得）。`flushLock` の取得順は常時 run lock → flushLock の順
   （逆は発生しない）でcycleを作らない。
6. flusher executorは単一thread（FIFO）とし、その内部でpublication threadを待たない。

### Data flow

既存と同一の状態遷移・診断record。変化は実行threadと初回公開の時点のみ:
caller worker → (hop) → main上の区間実行（state bus経由のwrite + event staging）→
worker再開 → flush（worker直列 または Main起点はflusher引渡し）→ 操作完了。

### Alternatives rejected

- **公開method群のsuspend化 + 入口でのMain hop**: 重いdomain呼び出しがmain上に移り、
  さらに内部でIO hopが必要。約20 method・全callerのsignature変更でblast radiusが過大。
- **非同期journal writer（バッファ + 常駐threadへの全委譲）**: journal生存
  （process deathで失われない）・同期追記の正本契約に反する。flusherは
  publication thread起点の引渡しに限定し、worker起点は同期drainを維持。
- **Handler.post（非同期・joinなし）**: state machineが直後に自stateを読むため
  可視性/順序の論理raceを生む。不採用。
- **lock保持下でのhop / gate保持下でのhop**: それぞれmain待ちの循環、
  lock順序逆転による循環が成立する。規則1/4で排除。
- **anchor区間もhopする（含めない残存とする）案**: callerが
  `scope.launch(Dispatchers.IO)` でありmain-only証明ができない（指摘2）ため、
  Phase1 reviewの判定に従い分離設計で収束する。不採用。
- **何もしない（観測継続）**: T2の静的経路がproduction codeに存在する以上、
  #418の終了条件（安定化）に進めない。不採用。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/ui/RunPublicationThread.kt`（新） | seam interface + production Handler実装 | 収束先threadの単一点 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRunStateBus.kt`（新） | 3 holderの所有と書込みmethod、thread記録hook | AC-1の構成的検出（bypass不能化） |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | seam注入、(a)/(b)/(d)区間のhop化、staged event + flush owner、anchor初回公開分離、bus経由への置換 | publication本体 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTestSupport.kt` | `DirectRunPublicationThread`既定注入 | 既存JVM testの動作維持 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunPublicationConfinementTest.kt`（新） | AC-1（全書込みthread検証）とAC-3(a)(b)のoracle | 決定的検証（最低層） |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunAdmissionPublicationTest.kt`（新） | AC-3(c): anchor経路の初回公開分離・競合順序oracle | spec 375境界の検証 |
| `tests/unit/app/lawnchair/organizer/ui/ManualOrganizationRunTest.kt` | seam注入への追従（必要最小限） | compile/動作維持 |
| `docs/engineering/ci-test-portfolio.md` | T2 = 本変更対象 / T1・T3 = category 6未解決 の分類同期 | AC-5 |
| instrumentation tests | 直接変更なし（AC-4は既存oracleのCI再実行） | 回帰確認 |

## Migration and recovery

- schema / rule / store migrationなし。データ移行なし。
- failure中のrollback: 通常のcommit revert。staged未flushイベントは既存の
  fail-openに従い、永続stateを変えない。Main起点flusher引渡しの未実行taskは
  process deathで失われるが、これは「append前に死亡したイベントは記録されない」
  既存durability契約の範囲内であり、順序契約は維持される。
- release rollback / downgrade: 影響なし（journal形式不変）。
- backup/restore compatibility: 影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | 新規oracle test（red→green、両方のlogをPR記録） | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.ui.ManualOrganizationRunPublicationConfinementTest'` |
| AC-2 | 既存organizer JVM test全green | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` ＋ CI `organizer-unit-tests` |
| AC-3 | flush直列化oracle / Main起点resume oracle / admission競合oracle（red-first記録を含む） | 同上（新規2 test class） |
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
- [ ] ADR — 不要（ADR 3条件を満たす判断なし。採用判断は先行seam + 公式文書の採否記録で足りる）
- [ ] AGENTS.md — 不要（新必須commandなし）
- [x] ci-test-portfolio.md — 失敗分類表の同期（AC-5）

## Execution checklist

- [ ] Current behavior: AC-1/AC-3 oracleが現行codeでredであることを記録。
- [ ] Seam導入 + state bus + 区間書換（監査表に従う）。
- [ ] staged event + flush owner（規則3/5/6の遵守）。
- [ ] anchor初回公開分離（規則4の遵守、spec 375契約の検証）。
- [ ] oracle green化、既存test群green。
- [ ] spotlessCheck / assemble 成功。
- [ ] hosted CI（PR run）: organizer-unit-tests、manual-organization-ui lane、final-status。
- [ ] PR evidence（head SHA、red/green記録、CI run URL、残存risk）を記録。
