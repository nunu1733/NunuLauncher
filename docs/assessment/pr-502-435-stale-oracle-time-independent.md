# Independent audit: PR #502 TwoPanelOrientationCaptureInstrumentationTest の stale-rejection oracle を時間依存でなくす（#435）

> Status: **GO（コード・spec受入条件）/ merge gate は CI 完了待ち**（下記「CI run status」）
> Audit date: 2026-10-03

- Auditor: 独立session（subagent監査者）。本PRの実装・review・検証実行に関与していない。
  作業tree（= PR head そのもの。`git rev-parse HEAD` と監査対象SHAが一致、tracked変更なし。
  untracked `.zcodeignore` のみ存在し本PR diff外）で read-only 照合と `spotlessCheck`
  再実行のみを行った。本record以外のfileは作成・変更していない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/502 （head branch `issue-435-impl`、
  base `main`。tests-only の tier L 変更につき `risk: layout-data` / `risk: migration`
  label は付与されておらず、高リスクPRの独立エビデンス要件（`high-risk-gate` 機械検証）
  の対象外。本recordはその要件ではなく、監査証跡として任意に作成するもの）
- 対象head SHA: `e2218350b8d3c2988b17b6427655df92c59cba4d`
  （`gh pr view 502 --json headRefOid` と一致。base `main` = `87a2eb3bb41c70694974ad6acf30ae6a586630c2`。
  `git merge-base main issue-435-impl` = 同一SHAでrebase不要。PR commits: `fe83714624`
  spec/plan起草、`74150d7bc5` re-entry、`9af27414ca` round 1対応、`9849199a39` round 2対応、
  `7f1fcc7ff1` oracle実装、`e2218350b8` A2限定修正）
- Diff: `git diff main...issue-435-impl` = 3 files, +1266/-19。
  `specs/435-two-panel-orientation-oracle-race/plan.md`（新規 +427）、
  `specs/435-two-panel-orientation-oracle-race/spec.md`（新規 +353）、
  `tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`
  （+486/-19）。**production code（`lawnchair/src/**`、`src/**`）、CI config、
  `ci_portfolio_map.yml` は diff に一切出現しない**（`git diff main...issue-435-impl --
  'lawnchair/src' 'src'` は空）。
- Criteria: [spec 435](../../specs/435-two-panel-orientation-oracle-race/spec.md)
  （draft、TOR-AC-01…TOR-AC-07）と plan.md、参照契約として
  [spec 130](../../specs/130-two-panel-orientation-capture/spec.md) AC-5
  （stale拒否 + plan行事前事後一致 + marker title不在の検証方法規定）および
  #292 の行同一性規律（`docs/assessment/pr-297-orientation-row-stability.md`）。
- Review経緯の照合: Issue #435 の ChatGPT review 4件
  （[5957956148](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5957956148)
  spec/plan round 1: reason-only retryのmasking指摘（高）、
  [5958524041](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5958524041)
  spec/plan round 2: 解消確認、
  [5959375903](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5959375903)
  実装 round 1: STALE_REVISIONのA2限定指摘（高）、
  [5959755455](https://github.com/nunu1733/NunuLauncher/issues/435#issuecomment-5959755455)
  実装 round 2（対象head `e2218350b8`）: **「blocking finding はありません」** を本文で確認）。

## Scope

- production変更なしはdiffで直接確認済み（上記）。oracle改訂はtest 1 fileとspec/planのみ。
- test本体の変更内容: `orientationChangeRejectsPreChangePlanAsStaleWithoutDbWrite` の
  oracleを、(1) 前置条件helper `establishStaleOraclePreconditions`
  （`TwoPanelOrientationCaptureInstrumentationTest.kt:636-656`）、(2) 副作用のない表駆動
  判定helper `decideStaleOracleOutcome`（同:135-177）、(3) bounded retry loop
  （同:384-442、予算20秒 `ORACLE_BUDGET_MS` 同:180、backoff 100ms 同:183）、
  (4) `RecordingDiagnosticsPort` によるterminal stage観測（同:189-230）、
  (5) no-write不変条件helper `assertStaleOracleNoWrite`（同:664-679）、
  (6) 決定的表テスト `staleOracleRetryDecisionTableIsContractual`（同:453-627）へ置換。
  同classの他2 test（`capturedOrientationMatchesConstructedDeviceProfileAuthority`、
  `productionComposerPreservesCapturedOrientationIntoPlannerInput`）は変更なし（diff外）。
- spec/planは実装と突き合わせ済み（下記Criteria check）。spec statusは `draft` のまま
  （acceptanceはownerの作業であり本auditでは扱わない）。

## Criteria check

総合: **TOR-AC-01〜07 すべて PASS（コード照合とproduction側の独立再導出に基づく）**。
行番号は特記ない限り対象headの
`tests/organizer-instrumentation/app/lawnchair/organizer/application/TwoPanelOrientationCaptureInstrumentationTest.kt`。

- **TOR-AC-01（前置条件確立後の単発applyが `Rejected(STALE_REVISION)`、terminal stage
  A2限定、no-write検証）**: **PASS**。成功は `decideStaleOracleOutcome` で
  `STALE_REVISION` かつ terminal stage == A2 のときのみ
  （:143-148、`staleAtCaptureComparison` :121-122）。SUCCESS branchはreasonの厳密一致を
  再assertし（:409-413）、`assertStaleOracleNoWrite`（:414）でmarker title
  （`orientation-stale`）不在（:670-673）とplan行 `_id` のbefore/after完全一致
  （:674-678）を検証してから成功確定（:415）。最終no-write検証をloop後にも1回実施
  （:442）。plan行は `awaitLauncherModelLoaded` 後にpinし（:338-339）、`rowsBefore` は
  回転前に取得（:360）。「単発」は満たす: SUCCESS到達時に再applyしない。
- **TOR-AC-02（前置条件確立失敗の明示的列挙）**: **PASS**。前置条件helperはmodel未load
  （:640-642）/ gate非READY + reconcile要約（:643-650）/ store非READY（:651-655）の
  それぞれ観測値付き文字列を返し、loopが `attempt N: precondition not established: ...`
  を観測記録へ追加（:392-395）。予算超過時は観測列挙付きの明示的 `assertTrue` 失敗
  （:437-441）。retry可否判定中の予算切れもHARD_FAILに変換され
  （`decideStaleOracleOutcome` :172-176）、result/stage/gate/観測列挙付きで失敗
  （:430-434）。表テスト行(f)（:606-626）で決定的に検証。
- **TOR-AC-03（retryable集合のstage限定とno-write検証付きbounded retry）**: **PASS**。
  retryableは `WRITER_BUSY @ A0`（:149-150）、`RECOVERY_STORE_UNAVAILABLE @ A2`
  （:151-152）、gate段階拒否（terminal eventなし、`gateState` がFAILED/IDLE/RECONCILING、
  :106-110）のみ。同一reasonの他stageはHARD_FAIL（:105）、terminal eventなし + gate
  READYは「説明不能」としてHARD_FAIL（:111-113、reason文字列だけではretryしない）。
  RETRY_PRECONDITIONS branchは **retry前に** no-write不変条件を検証し
  （:417-425）、観測記録（reason・terminalStage・gateState・attempt番号、:426-427）を
  残して前置条件再確立へ戻る。矛盾組合せ（RECOVERY@null+READY、WRITER_BUSY@A2）も
  HARD_FAILを表テストで固定（:586-605）。
- **TOR-AC-04（その他すべて即座確定失敗）**: **PASS**。`RECOVERY_STORE_UNAVAILABLE`
  のA4/A5（:498-518の表テスト行、実装:105）、`STALE_REVISION` のA5・stage不明
  （:529-548、実装:143-148）、`Applied` / `NoChanges` / `EXACT_PRECONDITION_FAILED` /
  `ConcurrentRun`（:549-585、実装:153-170）がすべてHARD_FAIL。`apply` のthrowは
  観測列挙付きAssertionError（:397-405）。production側の対応するstage投影を独立に確認:
  - A2のcapture/revision比較による `STALE_REVISION`:
    `ApplyProtocol.kt:154-157`。
  - A4のcheckpoint `StoreUnavailable` → `RECOVERY_STORE_UNAVAILABLE`:
    `ApplyProtocol.kt:235-238`。A5の `markApplying` 失敗 → 同reason:
    `ApplyProtocol.kt:268-273`。
  - A5のtransaction内再読 → `classifyApplyOutcome` が `ApplyTxOutcome.PreconditionFailed`
    をterminal stage **A5** で `Rejected(runId, outcome.rejection)` として投影
    （`ApplyProtocol.kt:317-332`、A5設定は:322）。adapterの再読が
    `PreconditionFailed(STALE_REVISION)` を返す点は `LauncherLayoutAdapter.kt:312`。
    → A5で `STALE_REVISION` が返る経路は実在し、A5を成功扱いしない本実装は
    plan.md「成功側もstage限定する」の主張と整合。
  - gate段階写像（FAILED → `RECOVERY_STORE_UNAVAILABLE`、IDLE/RECONCILING →
    `WRITER_BUSY`、protocol未到達・terminal eventなし）: `LayoutApplicationModule.kt:152-162`。
- **TOR-AC-05（分類観測）**: **PASS**。各attemptの観測記録はattempt番号 +
  reason + terminalStage + gateStateを含む（retryable: :426-427、hard fail: :430-434、
  前置条件失敗: :393、例外: :400-403）。terminal stageは `runId` 一致 + terminal
  phase一致で取得し「最後のevent」一致を使わない（`terminalApplyStage` :205-213、
  2件以上検出時は `check` でエラー:209-211）。gate段階拒否ではterminal eventが存在
  しないため `gateState` で分類（spec Terminologyどおり）。次回
  `RECOVERY_STORE_UNAVAILABLE` 観測時、gate FAILED（terminalStage=null + gateState=
  FAILED）/ A2 probe（terminalStage=A2）/ A4・A5後段（hard fail messageにterminalStage
  記載）は失敗メッセージから決定的に判別できる。分類のためのpost-hocな `availability()`
  再probeや `reconcileAtStart()` 再実行は存在しない（`reconcileAtStart` は次attemptの
  前置条件確立としてのみ呼ばれる:643-650）。
- **TOR-AC-06（#292規律との両立・lease非保持・RunId非固定）**: **PASS**。
  `awaitLauncherModelLoaded`（:689-700）と `ensureLauncherRow` のdesktop/hotseat行
  filter（:806-864）はdiff非変更でbyte等価、plan行pinはmodel load後（:338-339）。
  testは `tryAcquireLease` / `withLease` を一切呼ばず、loop外の `writer.captureCurrent`
  （:341）は読み取り専用（`LauncherLayoutAdapter.kt:94` → `capture()`、lease取得なし）。
  apply経路でleaseを取るのは `ApplyProtocol.applyWithRunMutex` の `tryAcquireLease` のみ
  （`ApplyProtocol.kt:127`）。`RunId` は `module.apply` が呼び出しごとに新発行
  （`LayoutApplicationModule.kt:145`）で、testは渡さない。
- **TOR-AC-07（決定的表テストが同一class・同一laneで(a)〜(f)を検証）**: **PASS**。
  `staleOracleRetryDecisionTableIsContractual` は同classの `@Test`（:453-627）で、
  判定対象の `decideStaleOracleOutcome` はAndroid frameworkに依存しない純粋関数
  （:135-177）。行対応: (a) A0 WRITER_BUSY → RETRY（:458-467）、(b) A2
  RECOVERY_STORE_UNAVAILABLE → RETRY（:468-477）+ gate段階2行（:478-497）、
  (c) A4/A5 RECOVERY → HARD_FAIL（:498-518）、(d) A2 STALE → SUCCESS / A5・stage不明
  STALE → HARD_FAIL（:519-548）、(e) Applied/NoChanges/EXACT_PRECONDITION_FAILED/
  ConcurrentRun → HARD_FAIL（:549-585）+ 矛盾組合せ（:586-605）、(f) 予算超過 →
  HARD_FAIL（STALE成功は予算外でもSUCCESS、:606-626）。lane追加・別lane複製なし。
- **参照契約 spec #130 AC-5**: **PASS（維持確認）**。検証対象契約（回転後のpre-change
  plan適用がstale拒否され `favorites` 行が不変）は本testのSUCCESS branch + 最終no-write
  検証が担い、検証方法（plan行事前事後一致 + marker title不在、全表一致は要求しない）
  も `assertStaleOracleNoWrite`（:664-679）の実装と一致。
- **参照契約 #292（行同一性）**: **PASS（維持確認）**。前項TOR-AC-06のとおり、
  #292由来helper・filter・規律は不変で、本oracle規律はその上に重ねられている。

## Terminal-stage captureの健全性（audit task 3の独立検証）

- `RecordingDiagnosticsPort.TERMINAL_APPLY_PHASES`（:217-229）は
  `APPLY_VERIFIED / APPLY_NO_CHANGES / APPLY_REJECTED / CHECKPOINT_REJECTED /
  CONCURRENT_RUN_REJECTED / APPLY_ROLLED_BACK / APPLY_RECOVERED / APPLY_UNRESOLVED /
  APPLY_RECOVERY_FAILED` の9種。`PhaseCode.kt` がterminalと-markするapply familyの
  terminal phaseと**正確に一致**する（`PhaseCode.kt:21-30`。他のterminal
  （INPUT_NOT_READY / PLANNING_* / USER_CANCELLED / RECOVERY_*）はinput・planning・
  recovery系projection専用で、`module.apply(plan)` の経路では発生しない）。
- 発行数の確認: protocol到達runは `ApplyProtocol.apply` が結果を1つ返すたび
  `emitTerminalApplyEvent` で **ちょうど1件** のterminal eventを発行する
  （`ApplyProtocol.kt:72-77, 626-644`）。途中eventは `CHECKPOINTED`（:249-255）と
  `APPLY_COMMITTED`（:390-396）のみで、いずれも非terminal phaseのため集合外。
  `ConcurrentRun` はmutex拒否時に `CONCURRENT_RUN_REJECTED` を1件発行するが
  （:58-66）、callerは内部runIdを知り得ない（`ApplyResult.ConcurrentRun` はrunIdを持たない
  → testの `runIdForDiagnostics` はnull、:240）ためterminalStage=nullとなり、
  `ConcurrentRun` はreason段階でHARD_FAIL（:164）なので分類に影響しない。
- gate段階拒否はprotocol未到達のためevent 0件 → `terminalApplyStage(runId)` がnullを
  返し、`gateState` 分類に合流（仕様どおり）。protocol到達後にevent 0件の経路は
  存在しない（上記のとおり全return pathで1件発行。diagnostics port自体の例外は
  `emitSafely` でfail-openだが、その場合もterminalStage=null + gate READY →
  HARD_FAILのfail-closed側に倒れる）。
- **gap検査の結果**: 発行されうる全terminal eventのphaseが集合に含まれ、
  逆に集合内phaseで複数発行される経路もない（runIdはrandom発行で衝突前提なし、
  かつ1 runにつきterminal 1件）。capture機構は健全。
- [info] `ApplyProtocol.kt:203-206` は `WriteSetPreparation.ContextMismatch` を
  STALE_REVISION@A2へ写像するが、現行production adapterの `prepareApplyWriteSet` は
  ContextMismatchを返さない（`LauncherLayoutAdapter.kt:184-186` は同一条件で
  `InvalidPlan` を返す。ContextMismatchを返すのはrecovery系の
  `prepareRecoveryWriteSet` のみ: `LauncherLayoutAdapter.kt:282-283`）。
  よってplain apply経路で到達しうるA2 `STALE_REVISION` の実質唯一のsourceは
  capture/revision比較であり、A2成功判定の証拠性は現行codeに対して正確。
  将来のwriter port実装が `prepareApplyWriteSet` でContextMismatchを返すように
  なった場合、そのA2 staleも契約成立の証拠として扱われる点は仕様粒度
  （reason × terminal stage）の限界であり、本PRの範囲では問題なし（Findings 1）。

## CI routing（audit task 4の独立検証）

- `.github/workflows/ci.yml:583` のjob `organizer-instrumentation-production-input-tests`
  は `tools/ci/run-production-input-instrumentation.sh` を実行し、その先頭stageの
  `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest
  -Pandroid.testInstrumentationRunnerArguments.class=...` が
  `app.lawnchair.organizer.application.TwoPanelOrientationCaptureInstrumentationTest`
  を **class単位** で指定する（method指定なし）。Gradle class filterは当該classの全
  `@Test` methodを実行するため、追加method `staleOracleRetryDecisionTableIsContractual`
  は自動的にroutingされる。lane追加・`ci_portfolio_map.yml` 変更は不要で、実際に
  diffへ含まれない（mapのlane↔surface edgeも不変）。

## Executed test surface

独立sessionによる対象head `e2218350b8d3c2988b17b6427655df92c59cba4d`（作業tree = head、
tracked変更なし）での再実行:

```bash
git diff --check main...issue-435-impl   # → 出力なし、exit 0
./gradlew spotlessCheck                  # → BUILD SUCCESSFUL（up-to-date）
./gradlew spotlessCheck --rerun-tasks    # → BUILD SUCCESSFUL in 20s（5 tasks executed）
```

- emulator再実行は **本auditでは行っていない**（audit手順として除外）。代わりに
  実装sessionが記録した実行（PR本文・commit記録）を証跡として引用:
  `connectedLawnWithQuickstepGithubDebugAndroidTest` class指定、API 35 local emulator、
  **A2修正後head `e2218350b8` で 4 tests / 0 failures / 0 skipped**（JUnit XML:
  `tests="4" failures="0" errors="0" skipped="0"`）、A2修正前head `7f1fcc7ff1` でも
  4 tests / 0 failures（参考）。headはtests/docsのみ変更のためproduction codeは
  両headで同一。
- 表テスト `staleOracleRetryDecisionTableIsContractual` を含むclass実行のCI証跡は
  下記のとおり監査時点で進行中。

## CI run status（2026-10-03監査時点）

- 対象headへのPR run [37053896883](https://github.com/nunu1733/NunuLauncher/actions/runs/37053896883)
  （pull_request event、head `e2218350b8d3c2988b17b6427655df92c59cba4d`、2026-10-02T19:24:23Z開始）:
  **in_progress**。確認できた分:
  - SUCCESS: `changes`（job 110993719073）、`high-risk-evidence`
    （run [37053897346](https://github.com/nunu1733/NunuLauncher/actions/runs/37053897346) job 110993720696）、
    `validate-repo-contract`、`check-style`、`build-debug-apk`、`organizer-unit-tests`。
  - **IN_PROGRESS（pending）**:
    `organizer-instrumentation-production-input-tests`（job
    [110993843911](https://github.com/nunu1733/NunuLauncher/actions/runs/37053896883/job/110993843911)）、
    `organizer-instrumentation-shared-writer-tests`、
    `organizer-instrumentation-reservation-recovery-tests`。
  - SKIPPED: db-migration / restore-capture / manual-organization-ui /
    category-override / exchange-import-ui / method-choice-journey /
    onboarding-proposal の各lane（path filterによる）。
- したがって **当該laneのgreenは監査時点で未確定（pending）**。またIssue #435終了条件1
  （修正headで当該laneを含むCIが **連続3回以上** green）は本PRでは未充足のまま
  （merge後のmain runでの確認をPR本文も記載）。`final-status` merge gateの結果を
  待ってmerge判断すること。

## Findings

**blocking finding なし。**

- [info] A2 `STALE_REVISION` には `ApplyProtocol.kt:203-206` の
  `ContextMismatch` 由来という理論上の第2sourceがあるが、現行production adapterでは
  到達不能（「Terminal-stage captureの健全性」節参照）。粒度はspecが選んだ
  reason × terminal stageの限界内であり、対応不要。将来 `prepareApplyWriteSet` が
  ContextMismatchを返す実装を許す場合は、その時のIssueで成功根拠の粒度を再検討する
  こと（本PRの作業項目ではない）。
- [info] gate段階拒否の分類は「rejection直後の `readinessGate.state` 読み取り」であり、
  拒否と読み取りの間にgateが非READY → READYへ遷移すると説明不能（HARD_FAIL）側に
  倒れる。ただしtest用module instanceはtest内で新規生成し（:371-378）、そのgateへ
  同時に触るin-process経路が存在しないため、この窓は現構成では実行不能。
  挙動も保守側（fail-closed）であり対応不要。
- [info] spec statusは `draft` のままである（plan.mdのDocumentation updatesどおり、
  acceptanceはmerge前後のowner作業）。本auditはacceptanceを代替しない。

## 明示的な未確認範囲

- 本auditはemulator testを再実行していない（実行したのは `git diff --check` と
  `spotlessCheck` のみ）。class実行のgreen（4 tests / 0 failures）は実装sessionの
  記録の引用であり、実行log/XML自体は本sessionで直接確認していない。
- 対象headの `organizer-instrumentation-production-input-tests` を含む3 instrumentation
  laneは監査時点で実行中であり、結果（green/red）を確認していない。連続3回greenの
  終了条件も当然未検証。
- 実processのrace（回転relayoutのMODEL_WRITER lease保持・production reconciliation
  とapply窓の重なり）が修正後も実際にretry規律で吸収される頻度は、単発のlocal green
  からは検証できない（spec/planの未確認範囲を引き継ぐ。連続CI greenでの事後確認が
  正当）。
- 過去4観測の `RECOVERY_STORE_UNAVAILABLE` の経路は記録不足で判別不能のまま
  （spec Unresolved decisions 1どおり。本PRの観測記録により次回観測時にself-classify
  される）。
- `staleOracleRetryDecisionTableIsContractual` がCI（production-input lane内）で
  実行されることはrouting構造の確認（class filter・lane script）までで、実際の
  lane logでのmethod実行は確認していない（CI完了後に確認可能）。

## Conclusion

**approve（request-changesなし）。** TOR-AC-01〜07はすべて実装どおり成立し、
production変更なし・#292/#130契約の維持・terminal stage captureの健全性・CI routingを
独立に確認した。実装review round 2（ChatGPT）の「blocking finding なし」とも矛盾しない。
mergeは対象headのCI（特に `organizer-instrumentation-production-input-tests` と
`final-status`）の完了・greenを待って判断すること（Issue終了条件1の連続3回greenは
merge後のmain run証跡で別途確認）。
