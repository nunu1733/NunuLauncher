---
issue: "#435"
status: draft
requirements:
  - TOR-AC-01
  - TOR-AC-02
  - TOR-AC-03
  - TOR-AC-04
  - TOR-AC-05
  - TOR-AC-06
risk: []
updated: 2026-10-03
---

# TwoPanelOrientationCaptureInstrumentationTest の stale-rejection oracle を時間依存でなくす

> Risk tier: L（テストのみの変更。高リスクpath一覧
> （`tools/repo-contract/validate_high_risk_evidence.py` の
> `HIGH_RISK_PATH_PREFIXES` / `HIGH_RISK_PATH_FILES`）には `tests/` は意図的に
> 含まれておらず、本Issueもproduction書込み経路を変更しない。階層Lは本来
> PRのみで完結するが、Issue本文の終了条件が「oracle改訂方針の記録
> （flaky maskingではなく競合順序の契約化）」を要求するため、その正本として
> 本specを draft で用意する。実装PRの本文にも階層L判定を明記する。）

## Problem / outcome（問題と成果）

### Problem

`TwoPanelOrientationCaptureInstrumentationTest.orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite`
（spec [#130](../130-two-panel-orientation-capture/spec.md) の
「orientation値の変化はrevision変化であり、変更前planの適用はstale拒否されDB書込みが
発生しない」シナリオの検証instrumentation test）は、
`expected:<STALE_REVISION> but was:<...>` 形式のassertion 1点で結果を判定している。
CIではこれまでに2種類の「別の拒否理由」が負荷下で観測されている。

1. `expected:<STALE_REVISION> but was:<WRITER_BUSY>` — PR #432（Issue #417）のCI実行中
   に1回（API-35 emulator）。同一headのrerunはgreen（Issue #435本文）。
2. `expected:<STALE_REVISION> but was:<RECOVERY_STORE_UNAVAILABLE>` — 2026-09-25以降
   に4回。main run [36108678234](https://github.com/nunu1733/NunuLauncher/actions/runs/36108678234)
   （2026-09-25、head `7508bbf0d5`）、PR #467 run
   [36245553636](https://github.com/nunu1733/NunuLauncher/actions/runs/36245553636)
   （2026-09-26、production-input lane job 108414015160。26/26完走・1 skippedで、失敗は
   本testのみ）、`issue-449-multi-select-surface` run
   [36404446036](https://github.com/nunu1733/NunuLauncher/actions/runs/36404446036)
   （2026-09-28、head `d1c7386b`）、main run
   [36922593372](https://github.com/nunu1733/NunuLauncher/actions/runs/36922593372)
   （2026-10-01、head `8b8b5e3ab`、test L193。#422 category 6分類済みで、当該runの
   JUnit XML・per-test logcat・live captureからは2候補経路の判別不可。
   [分類コメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5945597879)）。
   #422由のCI portfolio再編成後、本testは
   `organizer-instrumentation-production-input-tests` laneで実行されている。

原因はproductionの適用経路そのものではない。`LayoutApplicationModule.apply` は
stale判定（`ApplyProtocol` の A2 capture後のrevision比較）に到達する前に、複数の
fail-closedな直列化段階を通る。現行のtest harnessはこれらの段階が「まだ通過可能な
状態」にあることを一切確立しないまま単発の `apply` を呼ぶため、CI負荷でこれらの段階
が先に拒否を返すと、testが検証したい契約（stale rejection）には到達しない。すなわち
oracleが時間依存になっている（Issue #435本文の「writer busyとstale revisionの競合順序が
負荷で入れ替わりうる現行テスト設計は、時間依存のflaky oracleである」）。

コード上の到達順序（`ApplyProtocol.kt` `applyWithRunMutex` / `applyWithOuterLease`、
`LayoutApplicationModule.kt` `applyWithRunId`）:

```text
readinessGate.runWhenReady        # gate が READY 以外: FAILED -> RECOVERY_STORE_UNAVAILABLE
                                  #                        IDLE/RECONCILING -> WRITER_BUSY
validatePlan                      # INVALID_PLAN
store.availability()              # READY 以外 -> RECOVERY_STORE_UNAVAILABLE (A2)
writer.tryAcquireLease(ORGANIZER) # process-wide LayoutWriteCoordinator を誰かが保持 ->
                                  #   WRITER_BUSY (A0)
writer.captureCurrent             # ここで初めて revision を観測
capture.revision != plan.sourceRevision -> STALE_REVISION (A2)
```

負荷で先に拒否しうる段階と、それを正当に発生させる同process内の並行activity:

- **writer lease（WRITER_BUSY）**: 回転（landscape rebind）後、launcher自身の
  relayout（folder子の `screen`/`cellX`/`cellY`/`modified` 書込み。#292で確定済みの
  正当な挙動）は `MODEL_WRITER` leaseを
  `LayoutWriteCoordinator.runModelWriterWithCallTimeReservation` 経由で取得する。
  testはhost configurationのlandscape反映（`awaitOrientation`）しか待たず、この書込み
  のdrainを待たない。`tryAcquire` は他のあらゆる種類のlease保持中にnullを返す。
- **recovery store可用性（RECOVERY_STORE_UNAVAILABLE）**: 2つの候補経路があり、
  どちらがCIで発生したかはまだ証拠確定していない（本Issueの調査ステップで確定させる。
  修正architectureはいずれでも同一）。
  1. test moduleの `reconcileAtStart()` が失敗し readiness gate が `FAILED` になる
     経路（`LayoutApplicationModule.reconcileAtStart` → `readinessGate.reconcile`）。
  2. `ApplyProtocol` A2の `store.availability()` probeが失敗する経路。
     `RecoveryDbVersionGate.probe` は `SQLiteDatabase.openDatabase(READONLY)` +
     `PRAGMA user_version` を実行し、`SQLiteException` を `ReadFailed` →
     `READ_FAILED` へ写像する。testは `setUp` でrecovery DB fileを削除するが、
     instrumentationはapp process内で動き、`LawnchairApp` のproduction moduleは
     launcher resume時に `ensureOrganizerStartupReconciliation()`（別スレッドで
     model load完了待ち→production `reconcileAtStart()`）を起動する。testの
     `bringLauncherToForeground()` はこのtriggerを引くため、同じrecovery DB fileへの
     並行accessが同process内で起こりうる。

なお、これらの拒否自体はproduction契約として正しいfail-closed挙動である
（書込み前の拒否であり、DBは変わらない）。本Issueの対象は、stale契約を検証するtestが
これらの拒否と排他に排されているかのように単発assertionで書かれていることにある。

### Outcome（成果）

本testのoracleが時間依存でなくなる。testは、stale-rejection契約の検証に到達できる
前置条件（readiness gate READY・recovery store可用・writer lease空き）を明示的な
待機規律で確立した上で `apply` を呼び、結果が `Rejected(STALE_REVISION)` であることを
厳密に検証する。直列化段階の拒否（WRITER_BUSY / RECOVERY_STORE_UNAVAILABLE）が
観測された場合は、それが書込み前の正当な拒否であること（no-write不変条件）を検証した
上で、明示的なbounded retry規律で前置条件確立からやり直す。いかなる場合も
「retryして期待値が出るまで回す」だけのflaky maskingとは区別され、retry可能な
中間結果の集合・回数・期限・retry時にも成立すべき不変条件が契約として明示される。

## Scope

- `tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`
  の `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` のoracleと、それを
  支える同file内のhelper（前置条件待機・retry規律・観測記録）。
- 失敗分類（#422 policyの分類記録）のため、CIで次回観測された際にどちらの候補経路
  （gate FAILED / availability probe失敗）かをself-classifyできる観測記録をtestに持たせる。
- Issue本文の終了条件（連続CI実行3回以上green、oracle改訂方針のPR記録）に対応する
  検証計画（plan.md参照）。

## Non-goals

- production側（`ApplyProtocol`、`LayoutApplicationModule`、`ReadinessGate`、
  `RecoveryStore`、`LayoutWriteCoordinator`、`LauncherLayoutAdapter`）のあらゆる変更。
  拒否の順序・意味論は既存accepted spec群（#13 適用契約、#130、#292等）のまま扱う。
- productionのapply経路へのretry導入、stale判定の前倒し、WRITER_BUSY /
  RECOVERY_STORE_UNAVAILABLEの意味変更。
- 同classの他2 test（`capturedOrientationMatchesConstructedDeviceProfileAuthority`、
  `productionComposerPreservesCapturedOrientationIntoPlannerInput`）の変更。
- #292/#297で確定済みの行同一性規律（`awaitLauncherModelLoaded`、desktop/hotseat行
  filter）の変更。本Issueのoracle規律はその後に重ねる形で適用する。
- test frameworkのretry annotation導入、lane構成・CI workflow変更、
  TestProtocol経由の新規操作。
- `RECOVERY_STORE_UNAVAILABLE` の根本（同process内のproduction reconciliationとの
  並行access）をproduction側で排除する対応。観測・分類のみ本Issueで行う。

## Prior art

省略（理由: 階層Lのtest/refactorのみの変更。#292/#297で確定済みの行同一性規律と
既存待機helperの横展開であり、新規seam・非自明な状態管理・platform APIの新規扱いを
含まない）

## Observable behavior（observable contract of the test oracle）

### Terminology / identity

- 「前置条件（preconditions）」: stale-rejection検証のためにtestが確立する状態。
  (a) host configurationがlandscape、(b) `launcher.model.isModelLoaded == true`
  （回転によってloader taskが走る場合はその完了を含む。すでにload済みなら即座に
  満たされる）、(c) test moduleのreadiness gateがREADY（`reconcileAtStart()` の
  要約がunresolved failuresなし。失敗した場合はTOR-AC-03のretry規律の対象）、
  (d) `RecoveryStore.availability()` がREADY。
- 「retry可能な中間拒否（retryable pre-write rejection）」:
  `Rejected(WRITER_BUSY)` と `Rejected(RECOVERY_STORE_UNAVAILABLE)` のみ。
  この2つは書込み前に返る拒否であり、retryは書込み前の段階からやり直す。
  writer leaseの空きは外部から決して観測できない（probe→applyの間に他writerが
  入るTOCTOUが残る）ため、lease空きの確立はapply自身のatomicな
  `tryAcquireLease` の再実行、すなわちWRITER_BUSYに対するbounded retryで行う。
- 「確定失敗（hard failure）」: 上記以外のすべての結果
  （`Applied`、`NoChanges`、`Rejected(INVALID_PLAN)`、
  `Rejected(EXACT_PRECONDITION_FAILED)`、`RolledBack`、`Recovered`、`Unresolved`、
  `RecoveryFailed`、`ConcurrentRun`、および例外）。

### Normal path（TOR-AC-01）

Given 回転前capture由来のplanがあり、landscape回転後に前置条件 (a)〜(d) が確立された。
When testが `apply(plan)` を1回呼ぶ。
Then 結果は `Rejected(STALE_REVISION)` であり、marker title（`orientation-stale`）を
持つ行は存在せず、plan行（`_id == plannedRowId`）のbefore/after完全一致が成立する
（#292の行同一性規律は維持）。

### 前置条件が確立できない場合（TOR-AC-02）

Given 前置条件のいずれかが期限内に確立できない（model loadが完了しない、
`reconcileAtStart()` が繰り返し失敗する、availabilityがREADYに戻らない等）。
When 待機・retry予算が尽きる。
Then testは「どの前置条件が・何回・どの観測値で確立できなかったか」を列挙した
明示的メッセージで失敗する。時間依存の無言の失敗シグネチャ（oracle mismatch）は
発生しない。

### Retry可能な中間拒否が観測された場合（TOR-AC-03）

Given 前置条件確立後に `apply` が `Rejected(WRITER_BUSY)` または
`Rejected(RECOVERY_STORE_UNAVAILABLE)` を返した（両者とも書込み前拒否でありDBは
変わらない）。
When retry予算内である。
Then testは (1) その時点でno-write不変条件（marker title不在・plan行不変）が
成立していることを検証し、(2) 拒否理由と観測時の補助観測値（直後の
`RecoveryStore.availability()` の値等）を記録し、(3) 前置条件確立からやり直して
`apply` を呼び直す。
And retry予算を超えた場合はTOR-AC-02と同じ明示的失敗になる（観測された拒否理由の
全列挙付き）。

### 確定失敗結果（TOR-AC-04）

Given `apply` がretry可能な中間拒否以外の結果を返した。
When いかなretry予算内でも。
Then testは即座に失敗する（retryしない）。とくに `Applied` / `NoChanges` /
`EXACT_PRECONDITION_FAILED` はstale契約違反の直接証拠であり、無視・retryで隠蔽
しない。

### 分類観測（TOR-AC-05）

Given CIでRECOVERY_STORE_UNAVAILABLE系の中間拒否が再観測された。
When testの失敗メッセージまたは成功時の観測記録が読まれる。
Then gate FAILED経路とavailability probe失敗経路のどちら（または両方）が関与したかを
判別できる補助観測（直後のavailability値・`reconcileAtStart()` 要約など、実装時に
plan.mdの調査ステップで確定する最小集合）が含まれる。

### 並行性・stale state（TOR-AC-06）

- retry中もplan rowの `_id` とmarker titleによる同一性は不変（#292規律の延長）。
- retryのたびに新しい `RunId` が発行される（`module.apply` が内部で発行する）。
  testは `RunId` を固定しない。
- testはwriter leaseを自分で保持したまま `apply` を呼ばない（保持するとtest自身が
  WRITER_BUSYの原因になる）。またlease空きを事前probeするためだけに
  `LayoutWriteCoordinator` のleaseを取得しない（TOCTOUが残るため、規律は
  TOR-AC-03のretryで置き換える）。

## Unsupported case（非対象）

- two-panel host（`TYPE_MULTI_DISPLAY`）での実行。現行CI laneはphone相当emulatorのみ
  （spec #130のtwo-panel cellは別件）。
- 本test以外のtest class・laneで観測される同種のraceへの本規律の自動適用。
  必要になった場合は個別Issueで扱う。

## Recovery / rollback expectations

テストのみの変更であり、productionのrecovery・rollbackへの影響はない。変更のrollbackは
当該commitのrevertで完了する。

## Dependency / compatibility / migration

- 依存: なし（#422由の分類記録運用は継続利用するが新規依存ではない）。
- 関連: #130（検証対象契約の正本。product側挙動は変更しないためspec #130自体の改訂は
  不要。実装時に#130の本文が本testのoracle言及を持つかだけ確認する）、#292/#297
  （同testの行同一性安定化。本Issueはその下地の上に成り、#292規律を維持する）、
  #170 item 4（本Issueへsupersedeされた監査記録）、#422（lane再編成と分類policy）、
  #315（bounded failure evidence capture。既存のCI側証跡と併用）。
- schema・DB・backup/restore互換性への影響なし。

## Acceptance criteria（受入条件）

Issue #435の終了条件（連続CI green・改訂方針の記録）に対応させる。

- [ ] TOR-AC-01: 前置条件確立後の単発 `apply` が `Rejected(STALE_REVISION)` であり、
  no-write検証（marker title不在・plan行before/after一致）が成立する。
- [ ] TOR-AC-02: 前置条件が期限内に確立できない場合、どの条件が失敗したかを列挙した
  明示的失敗になる。
- [ ] TOR-AC-03: retry可能な中間拒否はno-write検証付きのbounded retryとして扱われ、
  予算超過時は観測理由列挙付きの明示的失敗になる。
- [ ] TOR-AC-04: retry可能な中間拒否以外の結果は即座に確定失敗となる。
- [ ] TOR-AC-05: 中間拒否の観測記録が、RECOVERY_STORE_UNAVAILABLEの経路判別に
  十分な補助観測を含む。
- [ ] TOR-AC-06: retry規律が#292の行同一性規律と両立し、testがleaseを保持したまま
  `apply` を呼ぶ経路がない。
- [ ] 修正headでCIの当該lane（`organizer-instrumentation-production-input-tests` を
  起動するrun）が連続3回以上green（Issue終了条件1）。
- [ ] oracle改訂方針（前置条件・retry契約・no-write不変条件・分類観測）が実装PR本文に
  記録される（Issue終了条件2）。本specはdraftであり、実装PRはこの時点の正本と
  矛盾しない限り参照する。

## Unresolved decisions（未決定事項）

1. **RECOVERY_STORE_UNAVAILABLEの経路確定**: gate FAILED経路とA2 availability probe
   経路のどちらがCIの4観測を説明するかは未確定（両方の可能性が残る）。2026-10-02に
   最新観測（run 36922593372）のJUnit XML・per-test logcat・live captureを確認したが
   判別できなかった（[分類コメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5945597879)）。
   plan.mdの調査ステップの残り（TOR-AC-05の観測記録によるself-classify）で次回CI観測時
   に判別する。修正architectureはいずれの経路でも同一（前置条件 (c)/(d) で
   両方を確立する）ため、確定は実装のblockerではない。
2. **retry予算の具体的な値**: 待機・再試行の期限・回数の実装値（既存helperの
   timeout（20秒）に揃えるか等）はplan.mdで提案し、実装PRのreviewで確定する。
3. **観測記録の恒久化範囲**: TOR-AC-05の補助観測を成功時も常に取るか、中間拒否
   観測時のみ取るか。常時取得は分類価値を上げるが、testの複雑度が増える。実装PRで
   確定する。

## Status

draft（2026-09-28起草。2026-10-03にre-entry ruleに従い再突合し改訂: 対象test fileと
適用経路の拒否順序・意味論は `origin/main` = `87a2eb3bb4` 時点で不変であることを確認
（詳細はplan.mdのRe-entry record）。acceptanceは行っていない。実装着手前に再度、
最新の `origin/main` とIssue #435の全コメントと照合し、必要なら改訂すること）。
