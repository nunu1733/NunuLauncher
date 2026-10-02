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
  - TOR-AC-07
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
- **recovery store可用性（RECOVERY_STORE_UNAVAILABLE）**: 同じreasonが3系統の経路で
  返りうる（review round 1で後段経路を追記）。どちらがCIで発生したかはまだ証拠確定して
  いない（TOR-AC-05のstage記録で次回観測時に判別する。修正architectureはgate/probe系
  であれば同一）。
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
  3. （理論上の第3経路）revision比較を通過した後のA4（checkpoint
     `StoreUnavailable`）/A5（`markApplying` 失敗）。captureがrotation前のrevisionを
     観測し続けた場合に到達しうる。この系統は書込み前ではない段階での拒否であるため、
     retry対象に含めない（TOR-AC-03/04）。

なお、これらの拒否のうちgate / A0 / A2経路はproduction契約として正しいfail-closedな
書込み前拒否であり、Launcher `favorites` は変わらない。A4/A5経路（revision比較
通過後）は少なくともA5がcheckpoint作成後であり、RecoveryStore側の記録生成を
伴いうる点で書込み前とは言えない。本testのno-write不変条件の対象はLauncher
`favorites` のplan行・marker titleである（RecoveryStore側の副作用の有無とは
区別する。review round 2で文言整理）。本Issueの対象は、stale契約を検証するtestが
これらの拒否と排他に排されているかのように単発assertionで書かれていることにある。

### Outcome（成果）

本testのoracleが時間依存でなくなる。testは、stale-rejection契約の検証に到達できる
前置条件（readiness gate READY・recovery store可用・writer lease空き）を明示的な
待機規律で確立した上で `apply` を呼び、結果が `Rejected(STALE_REVISION)` であることを
厳密に検証する。直列化段階の拒否のうちretryしてよいものは、拒否理由（reason）に加えて
拒否されたprotocol段階（terminal apply stage。既存 `DiagnosticsPort` の `RunEvent` を
`runId` で対応付けて取得）まで含めて限定する。reason文字列だけでのretry判定は行わない
（同一reasonがrevision比較通過後の後段でも返りうるため。review round 1で修正）。
retry対象の拒否が観測された場合は、それが書込み前の正当な拒否であること（no-write不変
条件）を検証した上で、明示的なbounded retry規律で前置条件確立からやり直す。いかなる
場合も「retryして期待値が出るまで回す」だけのflaky maskingとは区別され、retry可能な
中間結果の集合（reason × stage）・回数・期限・retry時にも成立すべき不変条件が契約と
して明示される。

## Scope

- `tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`
  の `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` のoracleと、それを
  支える同file内のhelper（前置条件待機・retry判定・観測記録）。
- retry判定を集約するtest内の判定helper（結果 × terminal stage × gate state →
  成功/retry/確定失敗の純粋な表駆動関数）と、その決定的表テスト（TOR-AC-07。
  同class内の追加test methodであり、lane追加・別laneへの複製はしない）。
- 失敗分類（#422 policyの分類記録）のため、CIで次回観測された際にどの経路（gate FAILED /
  A2 availability probe / A4・A5後段）かをstage情報から判別できる観測記録をtestに持たせる。
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
  要約がunresolved failuresなし、かつapply直前に `module.readinessGate.state ==
  READY` を確認。失敗した場合はTOR-AC-03のretry規律の対象）、
  (d) `RecoveryStore.availability()` がREADY。
- 「terminal stage」: `apply` が返した `runId` と一致するterminal（apply結果を投影した）
  `RunEvent` が持つ `applyStage`。test moduleへ注入した既存 `DiagnosticsPort` の記録から
  引く。対応付けは「最後のevent」ではなく `runId` 一致とterminal phaseで行う（1 runに
  checkpoint等の複数eventが流れうるため）。gate段階（`runWhenReady` のunavailable path）
  で拒否された場合はprotocolに到達していないためterminal eventは存在せず、代わりに
  rejection直後の `module.readinessGate.state` で分類する。
- 「retry可能な中間拒否（retryable pre-write rejection）」: stageまで含めて次に限る。
  - `Rejected(WRITER_BUSY)` かつ terminal stage A0（protocolの `tryAcquireLease` 拒否）
  - `Rejected(RECOVERY_STORE_UNAVAILABLE)` かつ terminal stage A2
    （`store.availability()` probe拒否）
  この2つはrevision比較より前に必ず評価される書込み前拒否であり、retryは書込み前の
  段階からやり直す。gate段階の拒否（terminal eventなし）は前置条件 (c) がapply時点で
  実際には成立していなかったことを意味するため、reason文字列でretry可否を判定せず、
  rejection直後の `readinessGate.state` を記録した上で前置条件未確立として
  TOR-AC-03の前置条件再確立で扱う。
  writer leaseの空きは外部から決して観測できない（probe→applyの間に他writerが
  入るTOCTOUが残る）ため、lease空きの確立はapply自身のatomicな
  `tryAcquireLease` の再実行、すなわちWRITER_BUSYに対するbounded retryで行う。
- 「確定失敗（hard failure）」: 上記以外のすべての結果。とくに、revision比較を
  通過した後の段階で返る `Rejected(RECOVERY_STORE_UNAVAILABLE)`（terminal stage A4:
  checkpoint `StoreUnavailable`、A5: `markApplying` 失敗）は、stale判定を通過した
  planに対する後段拒否であり、これをretry対象にすると契約違反を後続attemptの
  STALE_REVISIONが隠蔽しうる（review round 1で指摘）ため、即座に確定失敗とする。
  同様に `Rejected(STALE_REVISION)` もterminal stage A2のcapture/revision比較のときのみ
  成功根拠となる。同じreasonはtransaction内再読
  （`ApplyTxOutcome.PreconditionFailed` → `classifyApplyOutcome`）でも返り、その場合は
  terminal stage A5として投影される。A5はcheckpoint作成後の経路であり、A2の
  orientation-stale契約が成立した証拠にならないため、A5等のA2以外のstage・stage不明の
  `STALE_REVISION` は確定失敗とする（implementation review round 1で指摘）。
  ほかに `Applied`、`NoChanges`、`Rejected(INVALID_PLAN)`、
  `Rejected(EXACT_PRECONDITION_FAILED)`、`RolledBack`、`Recovered`、`Unresolved`、
  `RecoveryFailed`、`ConcurrentRun`、および例外。

### Normal path（TOR-AC-01）

Given 回転前capture由来のplanがあり、landscape回転後に前置条件 (a)〜(d) が確立された。
When testが `apply(plan)` を1回呼ぶ。
Then 結果は `Rejected(STALE_REVISION)`（terminal stage A2。A5のtransaction内再読による
拒否は成功根拠にならない。TOR-AC-04）であり、marker title（`orientation-stale`）を
持つ行は存在せず、plan行（`_id == plannedRowId`）のbefore/after完全一致が成立する
（#292の行同一性規律は維持）。

### 前置条件が確立できない場合（TOR-AC-02）

Given 前置条件のいずれかが期限内に確立できない（model loadが完了しない、
`reconcileAtStart()` が繰り返し失敗する、availabilityがREADYに戻らない等）。
When 待機・retry予算が尽きる。
Then testは「どの前置条件が・何回・どの観測値で確立できなかったか」を列挙した
明示的メッセージで失敗する。時間依存の無言の失敗シグネチャ（oracle mismatch）は
発生しない。

### Retry可能な中間拒否・前置条件未確立が観測された場合（TOR-AC-03）

Given 前置条件確立後に `apply` がretry可能な中間拒否（A0 `WRITER_BUSY` / A2
`RECOVERY_STORE_UNAVAILABLE`）またはgate段階の拒否（terminal eventなし）を返した
（いずれも書込み前でありDBは変わらない）。
When retry予算内である。
Then testは (1) その時点でno-write不変条件（marker title不在・plan行不変）が
成立していることを検証し、(2) 拒否理由・terminal stage（`runId` 対応のterminal
event。ない場合はrejection直後の `readinessGate.state`）・attempt番号を観測記録に
追加し、(3) 前置条件確立からやり直して `apply` を呼び直す。
And retry予算を超えた場合はTOR-AC-02と同じ明示的失敗になる（観測された拒否理由と
stageの全列挙付き）。

### 確定失敗結果（TOR-AC-04）

Given `apply` がretry可能な中間拒否でもgate段階の拒否でもない結果を返した。
When いかなretry予算内でも。
Then testは即座に失敗する（retryしない）。とくに `Applied` / `NoChanges` /
`EXACT_PRECONDITION_FAILED`、revision比較を通過した後の段階で返る
`Rejected(RECOVERY_STORE_UNAVAILABLE)`（terminal stage A4/A5）、およびA2以外のstage・
stage不明で返る `Rejected(STALE_REVISION)`（A5のtransaction内再読を含む）は契約違反の
直接証拠であり、無視・retryで隠蔽しない。

### 分類観測（TOR-AC-05）

Given CIで中間拒否または確定失敗が観測された。
When testの失敗メッセージが読まれる。
Then 各attemptについて拒否理由とterminal stage（`runId` で対応付けたterminal
`RunEvent` の `applyStage`。gate段階の拒否ではrejection直後の `readinessGate.state`）
が列挙されており、`RECOVERY_STORE_UNAVAILABLE` がgate FAILED・A2 availability probe・
A4/A5後段のどれで返ったかを判別できる。post-hocな `availability()` 再probeや
`reconcileAtStart()` 再実行を分類のために行わない（一過性競合後にREADYへ戻りうる
非決定性と、gate/state側効果があるため。review round 1で修正）。

### 並行性・stale state（TOR-AC-06）

- retry中もplan rowの `_id` とmarker titleによる同一性は不変（#292規律の延長）。
- retryのたびに新しい `RunId` が発行される（`module.apply` が内部で発行する）。
  testは `RunId` を固定しない。
- testはwriter leaseを自分で保持したまま `apply` を呼ばない（保持するとtest自身が
  WRITER_BUSYの原因になる）。またlease空きを事前probeするためだけに
  `LayoutWriteCoordinator` のleaseを取得しない（TOCTOUが残るため、規律は
  TOR-AC-03のretryで置き換える）。

### retry判定の決定的検証（TOR-AC-07）

Given retry判定（結果 × terminal stage × gate state → 成功/retry/確定失敗）が、
test内の判定helper（副作用のない表駆動関数）に集約されている。
When 同一class・同一lane（production-input surface）で、判定helperの決定的な
表テストを実行する。
Then 少なくとも次の行が決定的に検証される。(a) A0 `WRITER_BUSY` → retry、
(b) A2 `RECOVERY_STORE_UNAVAILABLE` → retry、(c) A4/A5 `RECOVERY_STORE_UNAVAILABLE` →
確定失敗、(d) A2 `STALE_REVISION` → 成功（A5 `STALE_REVISION`・stage不明の
`STALE_REVISION` → 確定失敗）、(e) `Applied` / `NoChanges` /
`EXACT_PRECONDITION_FAILED` 等 → 確定失敗、(f) 予算超過 → 明示的失敗。
判定対象のscenarioを別laneへ複製しない。

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
- [ ] TOR-AC-03: retry可能な中間拒否（A0/A2のstage限定）とgate段階の拒否は、
  no-write検証付きのbounded retry（後者は前置条件再確立）として扱われ、予算超過時は
  観測理由とstage列挙付きの明示的失敗になる。
- [ ] TOR-AC-04: retry可能な中間拒否・gate段階の拒否以外の結果（revision比較通過後の
  A4/A5 `RECOVERY_STORE_UNAVAILABLE`、A2以外のstage・stage不明の `STALE_REVISION` を
  含む）は即座に確定失敗となる。
- [ ] TOR-AC-05: 中間拒否・確定失敗の観測記録が拒否理由とterminal stage（gate段階では
  gate state）を含み、`RECOVERY_STORE_UNAVAILABLE` の経路判別（gate FAILED / A2 probe /
  A4・A5後段）が決定的に行える。
- [ ] TOR-AC-06: retry規律が#292の行同一性規律と両立し、testがleaseを保持したまま
  `apply` を呼ぶ経路がない。
- [ ] TOR-AC-07: retry判定helperの決定的表テストが、(a)〜(f) の各行を同一class・
  同一laneで検証する。
- [ ] 修正headでCIの当該lane（`organizer-instrumentation-production-input-tests` を
  起動するrun）が連続3回以上green（Issue終了条件1）。
- [ ] oracle改訂方針（前置条件・retry契約・no-write不変条件・分類観測）が実装PR本文に
  記録される（Issue終了条件2）。本specはdraftであり、実装PRはこの時点の正本と
  矛盾しない限り参照する。

## Unresolved decisions（未決定事項）

1. **RECOVERY_STORE_UNAVAILABLEの経路確定（過去4観測）**: 過去4観測がどの経路
   （gate FAILED / A2 availability probe / A4・A5後段）かは判別できない（当時はstage
   記録がなく、A4/A5後段経路を候補外とする根拠もない）。2026-10-02に最新観測
   （run 36922593372）のJUnit XML・per-test logcat・live captureを確認したが判別でき
   なかった（[分類コメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5945597879)）。
   本specのTOR-AC-05（terminal stageの記録）により、次回以降の観測は決定的に判別できる。
   修正architectureはgate/probe系のいずれでも同一（前置条件 (c)/(d) で両方を確立する）
   ため、過去分の確定は実装のblockerではない。
2. **retry予算の具体的な値**: 待機・再試行の期限・回数の実装値（既存helperの
   timeout（20秒）に揃えるか等）はplan.mdで提案し、実装PRのreviewで確定する。
3. ~~**観測記録の恒久化範囲**~~（review round 1で解消）: 観測記録は中間拒否・gate段階
   拒否・確定失敗の各attemptで必ず取り、成功時に恒久記録は取らない。TOR-AC-05の
   stage記録が分類の正本であり、post-hocな再probe・再実行は行わない。

## Status

draft（2026-09-28起草。2026-10-03にre-entry ruleに従い再突合・改訂し、同日にreview
round 1（ChatGPT。
[レビューコメント](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5957956148)）
の指摘へ対応してstage-aware retry契約（TOR-AC-03/04/05/07）へ改訂。acceptanceは
行っていない。実装着手前に再度、最新の `origin/main` とIssue #435の全コメントと
照合し、必要なら改訂すること）。
