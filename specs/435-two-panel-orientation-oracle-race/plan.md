# Implementation Plan: orientation stale-rejection oracle を時間依存でなくす

> Issue: #435
> Spec: [spec.md](./spec.md)
> Status: draft（2026-09-28起草、2026-10-03に再突合・review round 1対応の改訂。実装着手前に再度再突合すること）
> Risk tier: L（テストのみ。高リスクpath一覧には `tests/` は含まれない）

分析baseline: `origin/main` = `87a2eb3bb41c70694974ad6acf30ae6a586630c2`（2026-10-03 fetch）。

## Re-entry record（2026-10-03）

前版の分析baseline `c5a7840b88`（2026-09-28）から現行main `87a2eb3bb4` まで160 commitを
再突合した。結果:

- **対象test fileは無変更**（`git diff c5a7840b88..87a2eb3bb4 -- '*TwoPanelOrientation*'`
  空。前版の行引用 L154/L161-172/L173/L175-179/L181-188/L189/L190/L192-206 は現行も有効）。
- **適用経路の拒否順序・意味論は不変**。`ReadinessGate` / `RecoveryStore` /
  `RecoveryDbVersionGate` / `LayoutWriteCoordinator` / `LawnchairApp` は無変更。
  `LayoutApplicationModule.applyWithRunId` のgate写像（FAILED → RECOVERY_STORE_UNAVAILABLE、
  それ以外 → WRITER_BUSY）と `ApplyProtocol.applyWithRunMutex` の順序
  （validatePlan → availability → fault → lease → capture/revision比較）は同値のまま。
  baseline以降の当該3 fileの差分は次の通りで、いずれも本oracle経路へ影響しない:
  - Issue #450 `applyWithUndoReceipt`（undo receipt用の新規wrapper。`apply` の
    公開契約・拒否写像は変更なし）。
  - Issue #449 `inspectCapture`（編集画面用の読み取り専用capture。moduleのrun mutexを
    短時間保持する新規使用者だが、testは本test固有のmodule instanceを構成し、編集画面は
    本test中に開かれないため本testの窓では発火しない。`ConcurrentRun` はspecの
    確定失敗集合のまま）。
  - Issue #497 由来の `LauncherLayoutAdapter` 変更（新規app配置先policy）。
    `tryAcquireLease`（L79）と拒否経路への変更はなし。
- **新規ADRは本Issueに関係しない**: ADR-0015（新規app配置先policy）、ADR-0016
  （ADR-0013/0015要求テスト表の実現surface具体化。本testは実writer・実DBの
  production-input laneであり、test DB書込み経路harnessを対象としない）。
- **文書・process**: External reference scan（Issue #482、2026-09-30導入）によりspecへ
  `Prior art` 欄（省略記録）を追加。lane名・surfaceは不変（`ci.yml:583` に移動）。
- **観測の追加分**: 2026-10-01の4件目（main run 36922593372）。その証跡調査
  （2026-10-02分類コメント）で経路判別は不確定 — Investigation step 1の結果に反映済み。

## Review round 1 record（2026-10-03）

ChatGPT review（[レビューコメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5957956148)、
対象head `74150d7bc5`）の3指摘と対応:

1. **高: reason-only retryのmasking可能性** → 採用し契約を修正。`RECOVERY_STORE_UNAVAILABLE`
   はA2 availability probeに加え、revision比較通過後のA4（checkpoint `StoreUnavailable`、
   `ApplyProtocol.kt:236-239`）とA5（`markApplying` 失敗、`ApplyProtocol.kt:271-273`）
   でも返る（現行codeで確認済み）。retry可能集合を「reason × terminal stage」に限定
   （A0 WRITER_BUSY / A2 RECOVERY_STORE_UNAVAILABLEのみ）。A4/A5は確定失敗。
   gate段階の拒否（terminal eventなし）は `module.readinessGate.state`（public val、
   `ReadinessGate.kt:40`）で分類し前置条件再確立へ。stage取得は既存 `DiagnosticsPort`
   （test module constructorの既存parameter、現行はdefault NOOP）に注入したcaptureから、
   `runId` 一致かつterminal eventの `RunEvent.applyStage`（`RunEvent.kt:219`）を引く。
   production変更・新規laneは不要。
2. **中: retry判定の決定的検証欠落** → 採用。retry判定をtest内の副作用のない表駆動
   判定helperに集約し、(a)〜(f) の表テストを同一class・同一lane（production-input
   surface）に追加する（TOR-AC-07）。別laneへの複製はしない。
3. **低: 証拠表の「すべて rerun green」過大主張** → 採用し修正。run 36922593372は
   rerun未実施（分類コメント明記、直前parent main run 36882569838はgreen）。下表に
   run別の事実を記載。

## Current evidence

### 確認済み（現行codeとCI/Issue記録から裏取り済み）

**失敗記録（#422 policyの分類記録済み。rerunの事実はrun別に記載）**:

| 日付 | run | signature | 備考 |
|---|---|---|---|
| 2026-09-20頃 | PR #432（Issue #417）CI | `expected:<STALE_REVISION> but was:<WRITER_BUSY>` | API-35 emulator。同一headのrerunはgreen（Issue #435本文） |
| 2026-09-25 | main [36108678234](https://github.com/nunu1733/NunuLauncher/actions/runs/36108678234)（head `7508bbf0d5`） | `... but was:<RECOVERY_STORE_UNAVAILABLE>` | #170 triage comment。rerunの記録なし |
| 2026-09-26 | PR #467 [36245553636](https://github.com/nunu1733/NunuLauncher/actions/runs/36245553636) job 108414015160 | 同上 | 26/26完走・1 skipped、失敗は本testのみ。#435 comment。rerunの記録なし |
| 2026-09-28 | `issue-449-multi-select-surface` [36404446036](https://github.com/nunu1733/NunuLauncher/actions/runs/36404446036)（head `d1c7386b`） | 同上 | #170 triage comment。rerunの記録なし |
| 2026-10-01 | main [36922593372](https://github.com/nunu1733/NunuLauncher/actions/runs/36922593372)（head `8b8b5e3ab`、PR #494 merge） | 同上（test L193） | 26/26完走・1 skipped、失敗は本testのみ。#422 category 6。[分類コメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5945597879)。**rerun未実施**（分類コメント明記）。直前のparent main run [36882569838](https://github.com/nunu1733/NunuLauncher/actions/runs/36882569838)は16/16 jobs green |

いずれも `organizer-instrumentation-production-input-tests` lane
（`.github/workflows/ci.yml:583`、surface `production_input` + `layout_write`）。

**testの現行構造**
（`tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`）:

- L154: 回転前 `captureCurrent`、L161-172: plan組み立て、L173: `rowsBefore`。
- L175-179: `lockRotationTo(LANDSCAPE)` + `awaitOrientation` — 待つのはhost
  configurationのorientation反映のみ。launcherのrelayout書込みdrainは待たない。
- L181-188: test自身のmodule構成（`FaultInjector` はdefault `NOOP`）。
- L189: `module.reconcileAtStart()` — **戻り値（要約）は無視**。
- L190: `module.apply(plan)` — 単発。
- L192-206: `assertTrue(result is ApplyResult.Rejected)` +
  `assertEquals(STALE_REJECTION理由, ...)` の厳密一致とno-write検証
  （marker title不在・plan行before/after一致。#292規律）。

**apply経路の拒否順序**（`lawnchair/src/app/lawnchair/organizer/application/protocol/`）:

1. `LayoutApplicationModule.applyWithRunId`
   （`LayoutApplicationModule.kt:145-165`）: `readinessGate.runWhenReady` —
   gate `FAILED` → `Rejected(RECOVERY_STORE_UNAVAILABLE)`、
   `IDLE`/`RECONCILING` → `Rejected(WRITER_BUSY)`。
2. `ApplyProtocol.applyWithRunMutex`（`ApplyProtocol.kt:114-139`）:
   `validatePlan`（INVALID_PLAN）→ `store.availability()`（非READY →
   `RECOVERY_STORE_UNAVAILABLE`、A2）→ `faults.serializationContention()`
   （本testはNOOPなので常にfalse）→ `writer.tryAcquireLease(ORGANIZER)`
   （null → `WRITER_BUSY`、A0）。
3. `applyWithOuterLease`（`ApplyProtocol.kt:141-281`）: `captureCurrent` 後に
   lockState（LOCK_STATE_UNAVAILABLE）→ **revision比較（STALE_REVISION）** →
   exact precondition（EXACT_PRECONDITION_FAILED）→ NoChanges。

つまり `WRITER_BUSY` と `RECOVERY_STORE_UNAVAILABLE` はrevision比較より前に必ず
評価され、testはこれらの段階の通過可能性を一切確立していない。

**各段階を正当に発生させる同process内の並行activity（code確認済み）**:

- writer lease: `LauncherLayoutAdapter.tryAcquireLease`
  （`LauncherLayoutAdapter.kt:79`）はprocess-wide
  `LayoutWriteCoordinator.tryAcquire`
  （`src/com/android/launcher3/model/LayoutWriteCoordinator.java:98-108`）へ委譲し、
  他のあらゆる所有種類のlease保持中はnullを返す。回転relayoutのfolder子配置書込みは
  `ModelWriter` 経由（#292 planで経路確定:
  `Folder.updateItemLocationsInDatabaseBatch` → `moveItemsInDatabase`）で
  MODEL_WRITER leaseを取る。
- recovery store: `RecoveryStore.availability()`（`RecoveryStore.kt:95-97`）は
  `RecoveryDbVersionGate.probe`（`RecoveryDbVersionGate.kt:26-57`）で
  `SQLiteDatabase.openDatabase(READONLY)` + `PRAGMA user_version` を実行し、
  `SQLiteException` を `ReadFailed` → `READ_FAILED` へ写像する。testは `setUp`
  （`cleanRecoveryArtifacts`）でrecovery DB fileを削除するが、instrumentationは
  app process内で動作し、production module
  （`LawnchairApp.kt:114` で生成）は launcher resumeで
  `ensureOrganizerStartupReconciliation()`（`LawnchairApp.kt:293-298`）を起動し、
  別スレッドでmodel load完了待ちの後 production 側 `reconcileAtStart()`
  （`LawnchairApp.kt:128-172`）を実行する。testの `bringLauncherToForeground()` は
  このtriggerを引く。同じrecovery DB fileへの並行accessが同process内で可能。
- readiness gate: `LayoutApplicationModule.reconcileAtStart`
  （`LayoutApplicationModule.kt:544-567`）はmutex・reconciliation lease・session openの
  各失敗で早期returnし（この場合はgateが `IDLE` のまま → 次のapplyは
  `WRITER_BUSY`）、`readinessGate.reconcile` 内で `reconcileAll` が失敗すると
  gate `FAILED`（→ 次のapplyは `RECOVERY_STORE_UNAVAILABLE`）。
  `ReadinessGate.kt:34-67` 参照。

**前歴の整理**:

- #292/#297（spec `specs/292-two-panel-orientation-row-stability/`、
  `docs/assessment/pr-297-orientation-row-stability.md`）は同一testの
  **行同一性**race（default workspace loadによる抹消・再採番とfolder子のrelayout
  書込み）を安定化した。現行testの `awaitLauncherModelLoaded`・desktop/hotseat
  filterはその成果であり、維持する。#292は**拒否理由oracle**には触れていない。
- #170 item 4は本Issueへsupersede済み（2026-09-28 triage）。

### 推測（メカニズム成立の説明として妥当だが、直接証拠なし）

- **WRITER_BUSY観測（PR #432）の直接原因が回転relayoutのMODEL_WRITER lease**である
  こと。順序とlease意味論から高い確度で言えるが、当該runのlogcat証拠はなく、
  「時々applyと重なる」ことの直接的実証はない。
- **RECOVERY_STORE_UNAVAILABLE観測（4回）の経路**は次のいずれか（または複数）:
  1. test moduleの `reconcileAtStart` が失敗してgate `FAILED`（空storeでの
     `reconcileAll` は通常cleanであるため、これが起こったならstore access自体が
     負荷で失敗したことを意味する）、
  2. A2のavailability probeが `SQLiteException`（production側のrecovery DB accessとの
     contentions等）で `READ_FAILED`。
  3. revision比較通過後のA4/A5（理論上。captureが回転前revisionを観測し続けた場合に
     成立しうる。review round 1で追記）。
  assertion結果だけでは両者を区別できない（どれも
  `Rejected(RECOVERY_STORE_UNAVAILABLE)`）。test moduleのdiagnostics portはNOOP
  なのでjournalにも残らない。
- production reconciliation threadの実行がtestのapply窓と重なる頻度・条件。

これらの推測は**修正architectureの前提ではない**（後述の設計は経路がどちらでも
同一に機能する）。経路確定は調査ステップ（下記）で行う。

## Design

### Modules and interfaces（seams）

- 変更は1ファイルのみ:
  `tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`
  の `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` と同file内helper。
- 検査対象のseam（`LayoutApplicationModule.apply` の stale rejection + no-write、
  spec #130の契約）は不変。productionのinterface・moduleには触れない。
- testが追加で使う既存public/internal surface:
  `RecoveryStore.availability()`（testは既に `RecoveryStore` を構成・使用している）、
  `LauncherModel.isModelLoaded()`（既に `awaitLauncherModelLoaded` で使用）、
  `ApplyResult.Rejected.reason`（`PreWriteRejection`）、
  `LayoutApplicationModule.readinessGate`（public val、`LayoutApplicationModule.kt:121`）と
  `ReadinessGate.state`（`ReadinessGate.kt:40`。review round 1で初版の「非公開」認識を
  修正）、`LayoutApplicationModule` constructorの既存 `diagnosticsPort` parameter
  （現行testはdefault NOOPのためcapture実装を注入）、diagnostics modelの
  `RunEvent.applyStage`（`RunEvent.kt:219`）と `ApplyStage`。いずれも既存surfaceであり、
  新規のproduction surfaceは作らない。gate READYの確立は `reconcileAtStart()` の戻り値
  （`RestartReconciler.ReconciliationSummary`、`hasUnresolvedFailures()` がfalse）に
  加え、apply直前の `readinessGate.state == READY` 確認で行う。

### Oracle規律（修正後のtest 3 の制御流れ）

```text
(既存) capture → plan生成 → rowsBefore
  → lockRotationTo(LANDSCAPE) + awaitOrientation        # 前置条件 (a)
  → retry loop（bounded: 期限は既存helperのtimeoutに揃える。値はreviewで確定）:
      1. 前置条件を確立する:
         (b) isModelLoaded == true（falseなら短間隔poll）
         (c) reconcileAtStart() の要約が unresolved failures なし、かつ apply直前に
             module.readinessGate.state == READY
             （失敗時は観測記録に追加し、(d)と一緒に待機してやり直す）
         (d) store.availability() == READY
      2. module.apply(plan) を1回呼ぶ
      3. 結果分類（判定helper: 結果 × terminal stage × gate state。副作用のない
         表駆動関数として同fileに置き、TOR-AC-07の表テストで決定的に検証する）:
         - terminal stageの取得: module構成時に注入したDiagnosticsPortのcaptureから、
           返されたrunIdに一致するterminal（apply結果投影）eventのapplyStageを引く。
           対応付けは「最後のevent」でなくrunId一致 + terminal phase（1 runに
           checkpoint等の複数eventが流れうるため）。gate段階の拒否ではterminal
           eventが存在しない → 代わりにrejection直後のreadinessGate.stateを記録。
         - Rejected(STALE_REVISION) → loop終了、成功へ
         - Rejected(WRITER_BUSY) @ A0 / Rejected(RECOVERY_STORE_UNAVAILABLE) @ A2
           → no-write検証（marker title不在・plan行不変）をその場で実施し、
             観測記録（理由・terminal stage・attempt番号・時刻）に追加して 1. へ戻る
         - gate段階の拒否（terminal eventなし）→ rejection直後のreadinessGate.stateを
             観測記録に追加し、前置条件未確立として 1. へ戻る（reason-only retryは
             行わない）
         - その他すべて（A4/A5のRECOVERY_STORE_UNAVAILABLE、Applied / NoChanges /
           EXACT_PRECONDITION_FAILED / INVALID_PLAN / RolledBack / Recovered /
           Unresolved / RecoveryFailed / ConcurrentRun / 例外）→ 即座に確定失敗
      4. 予算超過 → 観測列挙（理由・stage・attempt番号）付きの明示的失敗
  → (既存) rowsAfter検証（marker title不在・plan行before/after完全一致）
```

設計根拠:

- **retryは「reason × terminal stage」で限定**する。`WRITER_BUSY` はprotocolの
  lease取得（A0）、`RECOVERY_STORE_UNAVAILABLE` はavailability probe（A2）で返る場合
  のみrevision比較より前の書込み前拒否である。同じreasonがrevision比較を通過した後の
  A4（checkpoint `StoreUnavailable`、`ApplyProtocol.kt:236-239`）とA5（`markApplying`
  失敗、`ApplyProtocol.kt:271-273`）でも返るため、reason文字列だけでretryすると、
  stale判定を通過したplanに対する後段拒否（契約違反の可能性）を後続attemptの
  `STALE_REVISION` が隠蔽しうる（review round 1指摘。初版のreason-only案は却下）。
- **stage取得に新規production surfaceを作らない**。terminal stageは既存の
  `DiagnosticsPort`（test moduleの既存constructor parameter。本testは現行NOOPのため
  capture実装を注入する）へ流れるterminal `RunEvent` の `applyStage` から取得し、
  gate状態は既存のpublic `module.readinessGate.state` から読む。`apply` の公開契約
  （`ApplyResult`）は不変。
- **lease空きの確立を外部probeで行わない**。`tryAcquireLease` の成否が唯一のatomic
  な判定であり、apply自身が再実行する。testが事前にleaseを取って確認する方法は
  probe→apply間のTOCTOUが残るため採らない（TOR-AC-06）。
- **flaky maskingとの区別**。期待する最終結果（STALE_REVISION）が得られるまで
  無差別にretryするのではなく、(i) retryしてよい中間結果の集合をreason × stageで
  列挙し、(ii) retry中もno-write不変条件を毎回検証し、(iii) 期待外の結果
  （A4/A5後段拒否を含む）は即座に確定失敗とする。これがIssue本文の言う
  「競合順序の契約化」である。
- **観測記録は分類証拠を兼ねる**（TOR-AC-05）。terminal stage（またはgate state）を
  attemptごとに記録するため、`RECOVERY_STORE_UNAVAILABLE` がgate FAILED / A2 probe /
  A4・A5後段のどれで返ったかの判別は決定的。post-hocな `availability()` 再probeや
  `reconcileAtStart()` 再実行は分類に使わない（一過性競合後にREADYへ戻りうる
  非決定性と、reconcileのgate/state側効果のため。review round 1で削除）。

### retry判定helperと決定的表テスト（TOR-AC-07）

retry判定（`ApplyResult` × terminal stage nullable × `ReadinessGate.State?` ×
attempt/予算 → 成功 / retry前置条件 / 確定失敗）を、Android frameworkに依存しない
副作用のない関数として同fileに置く。同じclassに表駆動のtest methodを追加し、
少なくとも次の行を決定的に検証する: (a) A0 WRITER_BUSY → retry、
(b) A2 RECOVERY_STORE_UNAVAILABLE → retry、(c) A4/A5 RECOVERY_STORE_UNAVAILABLE →
確定失敗、(d) STALE_REVISION → 成功、(e) Applied / NoChanges /
EXACT_PRECONDITION_FAILED 等 → 確定失敗、(f) 予算超過 → 明示的失敗。
test-audit規約の確定: ownerは既存の本class（production-input surface）のまま。
新規lane・別laneへの複製・production hookは作らない。決定的な判定logicを最も低い
決定的境界（純粋関数）で検証するものであり、既存coverageとの重複はない。

### Alternatives rejected

- **reason文字列のみによるretry判定（初版案。review round 1で却下）**:
  `RECOVERY_STORE_UNAVAILABLE` はA2だけでなくrevision比較通過後のA4/A5でも返るため、
  reasonだけでは後段拒否（契約違反の可能性）をretryで隠蔽しうる。
- **期待理由の緩和（`assertTrue(result is ApplyResult.Rejected)` だけにする）**:
  stale契約の検証が消える。maskingそのもの。
- **`@FlakyTest` / test frameworkのretry機構の導入**:
  失敗を隠すだけでなく、確定失敗（`Applied` 等）までretry対象になり得る。契約化に
  反する。Non-goalsにも明記済み。
- **production側でrevision判定をlease取得より前に動かす**:
  階層Hのproduction契約変更であり、本Issueのnon-goal。fail-closed順序は正当。
- **testが事前にleaseを取得して「空き」を確認してからapply**:
  TOCTOUが残り、かつtest自身が一時的にwriterを塞ぐ。
- **production reconciliationをtestから止める（production側に新規test hook）**:
  production surfaceの新規追加（階層H）。本testの目的はむしろ実processの並行性の
  下でのstale契約検証である。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `tests/organizer-instrumentation/.../TwoPanelOrientationCaptureInstrumentationTest.kt` | test 3 のoracleを上記規律へ書き換え（前置条件helper・判定helper・bounded retry・stage観測記録・予算超過時の明示的失敗）と、判定helperの決定的表テストの同class内追加（TOR-AC-07）。#292由来のhelper・filter・tearDown規律は不変 | 原因がtest harness側の待機規律欠落のみであり、production・他testに影響しない。表テストも同一owner surfaceに置き、lane追加・別lane複製をしない |

## Investigation step（RECOVERY_STORE_UNAVAILABLE経路の確定）

実装と同じPRで行う。blockerではない（architectureは経路非依存）。

1. **CI側の既存証拠の再確認**（2026-10-02実施済み — [分類コメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5945597879)）:
   最新観測（run 36922593372）のJUnit XML・当該methodのper-test logcat・live capture
   artifactを取得・確認したが、2候補経路（gate FAILED / availability probe失敗）の
   判別はできなかった。それ以前の3 runのartifactは7日expireで失効済み。
   → 本ステップの結果は「判別不可」。経路確定の残りの手段は次項のself-classify。
2. **観測記録によるself-classify**: 観測記録（拒否理由 + terminal stage（`runId`
   対応のterminal `RunEvent.applyStage`。gate段階の拒否ではrejection直後の
   `readinessGate.state`）+ attempt番号）をtestに恒久的に持たせる。次回CIで
   中間拒否または期限切れが観測された時点で、失敗メッセージから経路（gate FAILED /
   A2 probe / A4・A5後段）が決定的に判別できる（review round 1でpost-hoc probe方式を
   こちらへ置換）。判別結果はIssue #435へ記録する（#422 policyの分類記録）。
3. **（ opportunistic ）ローカル再現**: api35 emulatorで当該classを反復実行し、
   CPU負荷をかけた状態での再現を試みる。再現すれば観測記録とlogcatで経路を確定
   する。低頻度（約1週間で4回）のため再現しなくてもよく、その旨をPRに記載する。

確定結果の反映先: 本specのUnresolved decisions 1を解消し、推測節を事実/却下へ
更新する。production側の対応が必要という結論になった場合は、本Issueとは別の
階層H扱いのIssueへ分離する（本Issueではtest oracleの安定化のみ完了とする）。

## Compatibility constraints / rollback

- production source・build設定・dependency・他test・lane構成への影響なし。
- retry予算はlane timeout（job timeout 50分、当該classの実行は数秒〜数十秒）内に
  十分収まる範囲で設定する。
- rollback: 当該commitのrevertのみ。

## Failure handling（修正後のtest自体の失敗取扱い）

- 前置条件確立失敗・予算超過: 「どの前置条件が・何回・どの観測値で」失敗したかを
  列挙したメッセージで `assertTrue` 失敗（#292の `awaitLauncherModelLoaded` と同じ
  形式）。
- 期待外のapply結果: 即座に確定失敗（理由付き）。
- 例外（`apply` のthrowを含む）: 確定失敗として扱い、観測記録ごと報告。

## Testing strategy / Verification

Issue #435の終了条件（連続CI green 3回以上・改訂方針のPR記録）とspecの受入条件に
対応する。

| Acceptance criterion | Evidence | Command or environment |
|---|---|---|
| TOR-AC-01 stale拒否とno-write検証 | 修正headでのclass実行 green + code review | `ANDROID_SERIAL=<api35> ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest`（#292 planと同一command形式） |
| TOR-AC-02 前置条件確立失敗の明示性 | 判定helper表テスト（予算超過行）+ code review + 実装PR本文の規律記述 | 同class内の表テスト（同一lane） |
| TOR-AC-03 stage限定retry規律 | 判定helper表テスト（(a)(b)行・gate段階行）+ code review（loop本体のwiring）+ no-write検証の実装確認。race自体の決定的なend-to-end注入は「testが意図的にleaseを保持する」等の本規律と矛盾する操作が必要なため行わない（代替証拠としてPRに理由を記載。AGENTS.md テスト規約に従う） | 同class内の表テスト（同一lane） |
| TOR-AC-04 確定失敗の即時性 | 判定helper表テスト（(c)(e)行）+ code review（結果分類の全列挙） | 同class内の表テスト（同一lane） |
| TOR-AC-05 分類観測 | code review（terminal stage取得の `runId` 対応実装）。CIで次回観測された場合はその記録をIssue #435へ反映 | — |
| TOR-AC-06 #292規律との両立・lease非保持 | code review | — |
| TOR-AC-07 判定helperの決定的検証 | 同class内の表駆動test method（(a)〜(f)）の実行 green。新規lane・別lane複製なし（test-audit確定: ownerは本class、既存coverageとの重複なし） | 修正headでのclass実行（下記command） |
| Issue終了条件1 連続green | 修正headで当該laneが起動するCI runが**連続3回以上** green（attempt 1基準。#292 AC-5と同じ考え方） | GitHub Actions `organizer-instrumentation-production-input-tests` |
| Issue終了条件2 方針記録 | 実装PR本文に前置条件・retry契約・no-write不変条件・分類観測・flaky maskingとの区別を記載 | — |
| lint/format | `./gradlew spotlessCheck` | JDK 21 / Android SDK 36.1 |

DB-integration/UI testの追加は対象外。追加する新規testは、判定helperの決定的
表テスト（同class内の1 test method。TOR-AC-07）のみである。失敗を再現する新規testの
追加は行わない（修正対象がtest自身であり、「失敗を再現するテスト」に相当するものは
修正前のCI失敗記録4件と、可能ならローカル再現が担う）。

## Incremental implementation order

1. 本planの再突合（再entry rule: 最新 `origin/main`・Issue #435全コメント・対象
   fileの変更確認）— 2026-10-03実施済み（本plan冒頭のRe-entry record）。
2. Investigation step 1（既存CI artifact確認）— 2026-10-02実施済み（結果: 判別不可）。
3. test 3 のoracle書き換え（前置条件helper → 判定helper（表駆動・純粋関数） →
   retry loop → stage観測記録 → 明示的失敗）と、判定helperの表テスト追加
   （同class内、TOR-AC-07）。#292 helperは変更しない。
4. ローカル検証（上記command + `spotlessCheck`）。
5. Opportunistic なローカル再現試行と結果のPR記録。
6. PR作成（本文にtier L判定・oracle改訂方針・検証結果・未確認範囲）。
   `Refs #435`。CI連続green 3回でIssue終了条件1を満たした最終PRのみclose運用に
   従う。
7. 事後: spec status更新（acceptanceはownerが行う）、Investigation stepの結果を
   specのUnresolved decisionsへ反映。

## Dependency / blocker

- 依存なし。Unresolved decisions（spec参照: 過去4観測の経路（次回観測時にstage記録で
  判別）・retry予算の値）はいずれも実装着手のblockerではなく、PR reviewで確定する。
  観測記録の恒久化範囲はreview round 1で解消済み（中間拒否・gate拒否・確定失敗の
  各attemptで記録、成功時は恒久記録なし）。

## Risk

- 低（testのみ）。最大のリスクは「retry規律が実質的なflaky maskingに滑り落ちる」
  こと。TOR-AC-03/04（retry対象のreason × stage限定列挙・no-write不変条件の毎回検証・
  A4/A5後段拒否を含む期待外結果の即時確定失敗）で構造的に防ぎ、判定helperの表テスト
  （TOR-AC-07）とreviewで確認する。
- 逆方向のリスク（retry予算が厳しすぎて明示的失敗が増える）は、失敗メッセージが
  分類情報を含むため、むしろ調査資料になる。予算は既存timeout（20秒）を基本とする。

## Explicitly unverified areas（このplan作成時点で未確認のこと）

- 4件のCI失敗runについて、拒否がどの経路（gate FAILED / availability probe /
  writer lease）を通ったかの直接証拠。2026-10-02に最新run（36922593372）の
  artifact（JUnit XML・per-test logcat・live capture）を確認したが判別不可。
  残りの手段は観測記録（TOR-AC-05）によるself-classifyと、可能ならローカル再現。
- 回転relayoutのMODEL_WRITER lease保持がapply窓と重なる頻度の実測。
- production reconciliation thread（`organizer-startup-reconciliation`）がtestの
  apply窓と重なる頻度の実測。
- 回転時にloader taskが常に走るか否か（`LauncherModel` のbindDirectly経由の可能性）。
  前置条件 (b) は「`isModelLoaded == true` を確認する」形なので、どちらでも成立する。
- retry予算の具体的な値の実効性（CI負荷での必要十分性）。連続green 3回のCI証拠で
  事後確認する。

## Documentation updates

- [ ] spec.md（同directory）— acceptance後に `accepted`、実装merge後に
      `implemented`（本taskはdraftのまま）。
- [ ] spec #130（`specs/130-two-panel-orientation-capture/spec.md`）— product契約は
      変わらないため改訂不要。実装時に同specが本testのoracle言及を持つかだけ確認
      する（持つ場合は本specへの参照追加を検討。重複記述はしない）。
- [ ] CONTEXT.md / DESIGN.md / ADR / AGENTS.md — 変更なし（domain用語・構造・
      verified commandの変化なし）。

## Execution checklist

- [ ] Current behavior reproduced（CI失敗記録4件＋可能ならローカル再現）。
- [ ] Investigation step 1-3 実施と結果記録。
- [ ] Minimal implementation completed（test 1ファイル）。
- [ ] Migration/recovery verified（対象外。testのみ）。
- [ ] Full relevant verification completed（ローカルclass実行・spotlessCheck・
      CI連続green 3回）。
- [ ] PR evidence and remaining risks recorded（規律の記載・未確認範囲の明示）。
