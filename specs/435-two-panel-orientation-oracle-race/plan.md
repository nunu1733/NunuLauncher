# Implementation Plan: orientation stale-rejection oracle を時間依存でなくす

> Issue: #435
> Spec: [spec.md](./spec.md)
> Status: draft（2026-09-28起草、2026-10-03にre-entry ruleに従い再突合・改訂。実装着手前に再度再突合すること）
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

## Current evidence

### 確認済み（現行codeとCI/Issue記録から裏取り済み）

**失敗記録（すべて attempt 1 失敗・rerun green、#422 policyの分類記録済み）**:

| 日付 | run | signature | 備考 |
|---|---|---|---|
| 2026-09-20頃 | PR #432（Issue #417）CI | `expected:<STALE_REVISION> but was:<WRITER_BUSY>` | API-35 emulator。Issue #435本文 |
| 2026-09-25 | main [36108678234](https://github.com/nunu1733/NunuLauncher/actions/runs/36108678234)（head `7508bbf0d5`） | `... but was:<RECOVERY_STORE_UNAVAILABLE>` | #170 triage comment |
| 2026-09-26 | PR #467 [36245553636](https://github.com/nunu1733/NunuLauncher/actions/runs/36245553636) job 108414015160 | 同上 | 26/26完走・1 skipped、失敗は本testのみ。#435 comment |
| 2026-09-28 | `issue-449-multi-select-surface` [36404446036](https://github.com/nunu1733/NunuLauncher/actions/runs/36404446036)（head `d1c7386b`） | 同上 | #170 triage comment |
| 2026-10-01 | main [36922593372](https://github.com/nunu1733/NunuLauncher/actions/runs/36922593372)（head `8b8b5e3ab`、PR #494 merge） | 同上（test L193） | 26/26完走・1 skipped、失敗は本testのみ。#422 category 6。[分類コメント（2026-10-02、証跡artifacts取得済み・経路判別不可）](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5945597879) |

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
- **RECOVERY_STORE_UNAVAILABLE観測（4回）の経路**は次の2候補のいずれか（または両方）:
  1. test moduleの `reconcileAtStart` が失敗してgate `FAILED`（空storeでの
     `reconcileAll` は通常cleanであるため、これが起こったならstore access自体が
     負荷で失敗したことを意味する）、
  2. A2のavailability probeが `SQLiteException`（production側のrecovery DB accessとの
     contentions等）で `READ_FAILED`。
  assertion結果だけでは両者を区別できない（どちらも
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
  `ApplyResult.Rejected.reason`（`PreWriteRejection`）。新規のproduction surfaceは
  作らない。readiness gateの状態は `LayoutApplicationModule` から公開されていない
  ため、gate READYの確立は `reconcileAtStart()` の戻り値
  （`RestartReconciler.ReconciliationSummary`）で代理する
  （`hasUnresolvedFailures()` がfalseであること）。

### Oracle規律（修正後のtest 3 の制御流れ）

```text
(既存) capture → plan生成 → rowsBefore
  → lockRotationTo(LANDSCAPE) + awaitOrientation        # 前置条件 (a)
  → retry loop（bounded: 期限は既存helperのtimeoutに揃える。値はreviewで確定）:
      1. 前置条件を確立する:
         (b) isModelLoaded == true（falseなら短間隔poll）
         (c) reconcileAtStart() の要約が unresolved failures なし
             （失敗時は観測記録に追加し、(d)と一緒に待機してやり直す）
         (d) store.availability() == READY
      2. module.apply(plan) を1回呼ぶ
      3. 結果分類:
         - Rejected(STALE_REVISION) → loop終了、成功へ
         - Rejected(WRITER_BUSY) / Rejected(RECOVERY_STORE_UNAVAILABLE)
           → no-write検証（marker title不在・plan行不変）をその場で実施し、
             観測記録（理由・時刻・直後のavailability値・reconcileAtStart要約）に
             追加して 1. へ戻る
         - その他すべて（Applied / NoChanges / EXACT_PRECONDITION_FAILED /
           INVALID_PLAN / RolledBack / Recovered / Unresolved / RecoveryFailed /
           ConcurrentRun / 例外）→ 即座に確定失敗
      4. 予算超過 → 観測列挙付きの明示的失敗
  → (既存) rowsAfter検証（marker title不在・plan行before/after完全一致）
```

設計根拠:

- **retryは「書込み前拒否」に限定**する。`WRITER_BUSY` はlease取得前、
  `RECOVERY_STORE_UNAVAILABLE` はgate/availability段階で返り、どちらもDB書込みを
  行わない（`ApplyProtocol` の当該return pathはcheckpoint生成より前）。したがって
  retryは「同じplanをもう一度前置条件から適用しようとする」だけで、状態を進めない。
- **lease空きの確立を外部probeで行わない**。`tryAcquireLease` の成否が唯一のatomic
  な判定であり、apply自身が再実行する。testが事前にleaseを取って確認する方法は
  probe→apply間のTOCTOUが残るため採らない（TOR-AC-06）。
- **flaky maskingとの区別**。期待する最終結果（STALE_REVISION）が得られるまで
  無差別にretryするのではなく、(i) retryしてよい中間結果の集合を列挙し、(ii) retry
  中もno-write不変条件を毎回検証し、(iii) 期待外の結果は即座に確定失敗とする。
  これがIssue本文の言う「競合順序の契約化」である。
- **観測記録は分類証拠を兼ねる**（TOR-AC-05）。中間拒否観測時に直後の
  `availability()` の値を記録しておけば、`RECOVERY_STORE_UNAVAILABLE` が
  gate失敗由来かprobe失敗由来かの判別資料になる（拒否直後もprobeが失敗するなら
  availability経路、直後はREADYに戻るならgate/一過性の別の情報になる）。

### Alternatives rejected

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
| `tests/organizer-instrumentation/.../TwoPanelOrientationCaptureInstrumentationTest.kt` | test 3 のoracleを上記規律へ書き換え（前置条件helper・bounded retry・結果分類・観測記録・予算超過時の明示的失敗）。#292由来のhelper・filter・tearDown規律は不変 | 原因がtest harness側の待機規律欠落のみであり、production・他testに影響しない |

## Investigation step（RECOVERY_STORE_UNAVAILABLE経路の確定）

実装と同じPRで行う。blockerではない（architectureは経路非依存）。

1. **CI側の既存証拠の再確認**（2026-10-02実施済み — [分類コメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5945597879)）:
   最新観測（run 36922593372）のJUnit XML・当該methodのper-test logcat・live capture
   artifactを取得・確認したが、2候補経路（gate FAILED / availability probe失敗）の
   判別はできなかった。それ以前の3 runのartifactは7日expireで失効済み。
   → 本ステップの結果は「判別不可」。経路確定の残りの手段は次項のself-classify。
2. **観測記録によるself-classify**: 上記の観測記録（拒否理由 + 直後の
   availability値 + reconcileAtStart要約）をtestに恒久的に持たせる。次回CIで
   中間拒否または期限切れが観測された時点で、失敗メッセージ/成功ログから経路が
   判別できる。判別結果はIssue #435へ記録する（#422 policyの分類記録）。
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
| TOR-AC-02/03 前置条件・retry規律 | code review + 実装PR本文の規律記述。retry分岐の決定的注入は「testが意図的にleaseを保持する」等の本規律と矛盾する操作が必要なため行わない（代替証拠としてPRに理由を記載。AGENTS.md テスト規約に従う） | — |
| TOR-AC-04 確定失敗の即時性 | code review（結果分類の全列挙） | — |
| TOR-AC-05 分類観測 | 観測記録の内容確認（code review）。CIで次回観測された場合はその記録をIssue #435へ反映 | — |
| TOR-AC-06 #292規律との両立・lease非保持 | code review | — |
| Issue終了条件1 連続green | 修正headで当該laneが起動するCI runが**連続3回以上** green（attempt 1基準。#292 AC-5と同じ考え方） | GitHub Actions `organizer-instrumentation-production-input-tests` |
| Issue終了条件2 方針記録 | 実装PR本文に前置条件・retry契約・no-write不変条件・分類観測・flaky maskingとの区別を記載 | — |
| lint/format | `./gradlew spotlessCheck` | JDK 21 / Android SDK 36.1 |

unit/property/DB-integration/UI testの追加は対象外（instrumentation test単独の
修正のため）。失敗を再現する新規testの追加も行わない（修正対象がtest自身であり、
「失敗を再現するテスト」に相当するものは修正前のCI失敗記録4件と、可能なら
ローカル再現が担う）。

## Incremental implementation order

1. 本planの再突合（再entry rule: 最新 `origin/main`・Issue #435全コメント・対象
   fileの変更確認）— 2026-10-03実施済み（本plan冒頭のRe-entry record）。
2. Investigation step 1（既存CI artifact確認）— 2026-10-02実施済み（結果: 判別不可）。
3. test 3 のoracle書き換え（前置条件helper → retry loop → 結果分類 → 観測記録 →
   明示的失敗）。#292 helperは変更しない。
4. ローカル検証（上記command + `spotlessCheck`）。
5. Opportunistic なローカル再現試行と結果のPR記録。
6. PR作成（本文にtier L判定・oracle改訂方針・検証結果・未確認範囲）。
   `Refs #435`。CI連続green 3回でIssue終了条件1を満たした最終PRのみclose運用に
   従う。
7. 事後: spec status更新（acceptanceはownerが行う）、Investigation stepの結果を
   specのUnresolved decisionsへ反映。

## Dependency / blocker

- 依存なし。Unresolved decisions（spec参照: RECOVERY_STORE_UNAVAILABLE経路・retry
  予算の値・観測記録の恒久化範囲）はいずれも実装着手のblockerではなく、PR review
  で確定する。

## Risk

- 低（testのみ）。最大のリスクは「retry規律が実質的なflaky maskingに滑り落ちる」
  こと。TOR-AC-03/04（retry対象の限定列挙・no-write不変条件の毎回検証・期待外結果の
  即時確定失敗）で構造的に防ぎ、reviewで確認する。
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
