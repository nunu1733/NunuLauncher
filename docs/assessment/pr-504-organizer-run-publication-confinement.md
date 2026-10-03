# Independent audit: PR #504 organizer runのUI状態公開のmain thread収束（#418）

> Status: **GO（対象head `e8e9723401` でのmerge可。CI `final-status` merge gate green。
> ただしFindings 3のlow残課題とFindings 4の記録依存を併記）**
> Audit date: 2026-10-03

- Auditor: 独立session（subagent監査者）。本PRの実装・review・検証実行に一切関与していない。
  spec/plan review chain（Issue #418上のPhase 1 review 5ラウンド → Approve、Phase 2 review
  4ラウンド → Clear）はChatGPT-assistedな別作業主体によるものであり、本監査sessionとは独立。
  本監査はread-onlyなコード照合・diff照合、localでのunit test / spotless再実行、
  hosted CI結果の観測のみを行った。本record以外のfileは作成・変更していない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/504 （head branch `issue-418-impl`、
  base `main`。`gh repo view` で `nunu1733/NunuLauncher` / default branch `main` を確認済み。
  Risk tier L（path基準）だがspec 375 Amendmentを同梱するため階層H相当の手続きを踏むPRであり、
  本recordはworkflowの独立エビデンス要件に沿って作成した）
- 対象head SHA: `e8e97234014a054a8e713b1b3ac25421d69318ca`
  （`gh pr view 504 --json headRefOid` と一致。監査sessionの作業treeを同headへ
  fast-forwardし、tracked変更なしで照合・local再実行を行った）。
  **監査の経緯**: 最初にhead `77eba665f0387e0dd7474c2330355aa26b2fc1a5` を監査した結果、
  CI `organizer-instrumentation-onboarding-proposal-tests` が **本変更起因の実障害**
  （新guardによるmain起点machine入口のdeterministic fail-fast、Findings 1）でredとなり、
  実装側が修正commit `e8e9723401` をpush。本recordは同headに対する再監査である。
  `77eba665f0` のCI failure（run 37089761014）は、guardが実際のproduction暴露経路を
  決定的に検出した証跡として本文に記録する。
- Diff（GitHub PR diff、13 files = base実装12 + fix commit 1）:
  `RunPublicationThread.kt`（新）、`ManualOrganizationRunStateBus.kt`（新）、
  `ManualOrganizationRun.kt`、`UsageAccessJitRequest.kt`、
  tests 2 class新規（`ManualOrganizationRunPublicationConfinementTest.kt` /
  `ManualOrganizationRunAdmissionPublicationTest.kt`）、
  `ManualOrganizationRunTestSupport.kt`、`ManualOrganizationPreferences.kt`（fix commit
  `e8e9723401`、1 file / 2 hunk + import — 後述）、`ci-test-portfolio.md`、
  specs（418 spec/plan、375 spec/plan）。
  plan.md Change set表との差分はFindings 5のとおり（`ManualOrganizationRunTest.kt` への
  変更は「必要最小限」どおり zero であった。fix commitのdiffは
  `git diff 77eba665f0..e8e9723401 --stat` で **1 file / +17/-2** のみを確認）。
- Criteria: [spec 418](../../specs/418-organizer-run-publication-confinement/spec.md)
  （accepted、Revision 4）AC-1〜AC-5、同plan.md、Amendment本体としての
  [spec 375](../../specs/375-scope-remedy-rebind/spec.md)
  「gate下のUI待機禁止の例外（#418）」（spec本文423-436行・例外条件(i)(ii)(iii)）、
  「gate下の処理時間の界限」の例外対象外化（464-473行）、header Amendment note（37-43行）、
  SR-AC-08への「gate下のUI待機禁止のoracle（#418 publication hop例外を除く経路が対象）」
  scope限定（609-613行）と「publication hop例外の不変条件oracle（#418で追加）」（614-619行）、
  および375 plan.mdのAmendment note（291-298行）。ADRはplanどおり不要（該当判断なし）。
- Review経緯の照合: Issue #418 comment群（Phase 1 round 1-5、Phase 2 round 1-4）を取得し、
  round 3で確定した「spec 375を本PRで明示Amendする」方針、round 2/3の
  uninterruptible join共通primitive化（`awaitPublicationCompletion()` がUnit戻りで
  interrupt status復元まで担う形）が現headのコードと一致することを確認した。

## Scope

- 対象diffは上記13 files。Launcher DB・recovery store・schema・journal形式・
  upstream model/loader bridgeへの書込み経路は **一切変更されていない** ことをdiffで確認
  （書込み系変更はメモリ上のStateFlow 3 holderのみ。journal appendの実行thread・時点・
  durabilityは不変。migration/backup/restoreへの影響なし）。
- 独立に確認した構造（行番号は対象head）:
  - **(a) UI状態書込みの全量hop化**: `ManualOrganizationRun.kt` 内に
    `stateHolder` / `preparationPhaseHolder` / `operationActiveHolder` への直接参照は
    存在しない（grepで0件）。3 holderは `ManualOrganizationRunStateBus` がprivateに所有し
    （`ManualOrganizationRunStateBus.kt:21-24`）、run側の唯一の書込み経路は
    `publishState` / `publishPreparationPhase` / `publishOperationActive`
    （`ManualOrganizationRun.kt:798-808`）で、いずれも
    `publicationThread.run { stateBus.publishXxx(...) }` のblock-join hop。
    `publicationThread.run` のcall siteはproduction内でこの3箇所のみ
    （grep確認。`updateOperationActiveLocked` :857 もhelper経由）。
  - **(b) machine入口guard**: `publicationThread.assertNotPublicationThread()` は
    20 methodの入口に存在（start 2 overload / confirmSelection / planWithConfirmedScope /
    reopenSelection / continueAfterUsageAccessGate / attachIntent / claimGenerationEpoch /
    invalidateGenerationEpoch / isCurrentEpoch / isLiveScopeOwner / retryPlanPreview /
    cancel / confirm / beginRecoveryPreview / beginRecoveryPreviewFromDurableEntry /
    leaveRecoveryResultToHub / cancelRecoveryPreview / confirmRecovery / dismiss）。
    全20箇所について、guard行が各function内の最初の `synchronized(lock)` より前に
    あることを行番号で機械的に確認した（例: start :861→:908、dismiss :2410→:2411）。
    **ただし `commitGeneratedSession` / `cleanupBoundExport` / `discardScopeBoundRequest` /
    `savePendingImportForLiveOwner` の4 methodにはguardが存在しない**（Findings 3）。
  - **(c) hop taskのlock-free性**: 3 hop taskのbodyはいずれも
    `stateBus.publishXxx(...)` 1呼出しのみで、run lock・exchange mutation gate・
    usage access gate・journal・durable storeへの取得/呼出しを含まない
    （spec 375 Amendment例外条件(i)どおり）。
  - **(d) join primitiveの共有**: `CountDownLatch.awaitPublicationCompletion()`
    （`RunPublicationThread.kt:68-79`）はuninterruptibleなlatch join +
    完了後のinterrupt status復元を1つの `internal` helperで行い、
    `HandlerRunPublicationThread.run()`（:101）と `DedicatedThreadPublication.run()`（:147）
    の両方が同一primitiveを1行で呼ぶ。Phase 2 review round 2/3で指摘された
    「test double側だけが固定されるdrift」はこの構造で閉じている。
  - **(e) lock-free mirror**: `boundScopeRunId` は `@Volatile`
    （`ManualOrganizationRun.kt:1664-1665`）。`hasBoundScopeRequest()`（:1687-1690）は
    StateFlow読み取り + volatile readのみでrun lockを取らない。
    mirrorの更新は `Operation.boundExportId` を変異する同一critical section内に存在する
    （:922-923 start、:1487-1488 commitGeneratedSession のbind、:1524-1525 cleanup、
    :1577-1578 discard。`boundExportId` の変異箇所は全diff照査でこの3 + mirror組のみ）。
  - **(f) JIT resumeの非main化**: `UsageAccessJitRequest.kt` の3 resume経路
    （waiter wakeup :388/:392、grantCheckTick後 :422、dialog onContinue :435）はすべて
    `resumeRunOnWorker()` → `scope.launch(Dispatchers.IO) { run.continueAfterUsageAccessGate() }`
    （:362-364）に統一されている。production wiringは
    `ManualOrganizationModule.get` 内 `publicationThread = HandlerRunPublicationThread()`
    （`ManualOrganizationRun.kt:253`）。
- production `get(context)` 経路以外の `ManualOrganizationRun` 構築（instrumentation test等）
  はdefault `DirectRunPublicationThread`（同一thread、guard no-op）で既存動作を維持する
  ことを確認。
- **fix commit `e8e9723401` の照合（2 hunk、いずれも `ManualOrganizationPreferences.kt`）**:
  1. `interruptAndNavigate()`（:421-427）— main-confined `rememberCoroutineScope` の
     `scope.launch` 内にあった `coordinator.leaveRecoveryResultToHub()` を
     `withContext(Dispatchers.IO)` へ移し、戻り値 `hubReturned` で以降の分岐を維持。
  2. `DisposableEffect(coordinator)` の `onDispose`（:490-499）— main上で実行される
     host disposalの `coordinator.dismiss()` を、detachedな
     `CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { ... }` への
     fire-and-forget dispatchへ変更。
     評価: dismissの戻り値は変更前から捨てており契約不変。detached scopeのため
     activity destroy後もworkerで完結し、process deathと交差した場合の
     「terminal eventなしの中断」は既存のprocess-death契約
     （diagnostics fail-open / durable status由来の再導入）の域内で増分なし。
     2つのdismissalが連続してもrun lockで直列化され、guardはIO上で通過する。
     修正により、preferences面の全machine入口呼出し（dismiss / leaveRecoveryResultToHub /
     cancelRecoveryPreview / execute経由の各操作）が非mainで実行されることをgrepで再確認した
     （:308 / :427 / :429 / :497 の残存直接呼出しはすべてIO context内）。

## Criteria check

総合: **AC-1〜AC-5すべて成立（fix後headでのコード照合・JVM oracle再実行・CI観測）。**

- **AC-1（決定的oracle・red-first・構成的検出）: PASS（構造）**。
  busが3 holderをprivate所有しbypassが構成的に不存在なこと、
  `writeThreadTracker`（既定null、test注入のみ）が全書込みの実行threadを記録すること、
  oracle `statePublicationsLandOnThePublicationThreadWhileTheMachineStaysOnItsWorker`
  が全field書込みのthreadを `DedicatedThreadPublication.thread` と比較することを確認。
  red-firstの記録はPR本文の主張（hop無しheadで
  `field 'state' was published off the publication thread expected:<confinement-pub>
  but was:<machine-driver>`、3 tests 2 failed → hop後green）であり、
  **本監査では再現していない**（read-only制約。Findings 4）。
  付随証跡: `77eba665f0` のCI run 37089761014で、実production経路
  （main上のonDispose → dismiss）がguardにより **deterministicに検出** され
  （`IllegalStateException: organizer run state machine must not run on the publication
  (main) thread` ← `ManualOrganizationRun.dismiss` ← Compose onDispose）、
  構成的検出の実効性が実環境で示された（Findings 1）。
- **AC-2（既存JVM test群の維持）: PASS**。CI `organizer-unit-tests` jobが両headで成功
  （対象headのrun 37091014633で成功。PR本文のlocal 1816 tests / 0 failures のCI mirror）。
  `DirectRunPublicationThread` の既定注入により既存JVM testはhopがno-op化され
  同一thread実行を維持する。fix後、UI経路の既存挙動（画面離脱時のdismiss 等）も
  threadだけを変えて維持される。
- **AC-3（machine非main実行とemit契約）: PASS**。
  (a) `journalAppendsStayOnTheMachineThreadAndOffThePublicationThread`
  （append thread == machine worker、publication threadでない）、
  (b) `machineEntryFailsFastOnThePublicationThread`（`start()` がpublication thread上で
  `IllegalStateException`、stateは `Idle` のまま）、
  (c) admission 3 oracle（gate内で`State.Capturing`可視・`AdmissionRefused`無公開・
  cancel競合でjournal空 + `RUN_STARTED`先行）、
  (d) `admittedAnchorPublishesCapturingOnThePublicationThreadInsideTheGateHold`
  がspec 375 Amendment例外不変条件 (a)(b)(c) を決定的に検証
  （gate保持中のhop完了観測 `wroteDuringGateHold`、`stateInsideHold == Capturing`）。
  2 class 8 testを **対象head上でlocal再実行し全green**（下記Executed test surface）。
- **AC-4（instrumentation回帰）: PASS**。対象headのCI run
  [37091014633](https://github.com/nunu1733/NunuLauncher/actions/runs/37091014633) で
  `organizer-instrumentation-manual-organization-ui-tests` が **成功**。
  対象oracle `UsageAccessJitInstrumentationTest.crossOriginExchangePresentationPausesTheRunUntilResolution`
  は同laneに含まれる。
- **AC-5（文書同期）: PASS**。`ci-test-portfolio.md` のdiffが
  T2 = 「production off-main publication軸の除去対象（本変更）」、
  T1/T3 = 「category 6（unknown）・本変更の対象外」と回帰oracleの所有
  （既存 `organizer-unit-tests` gate内の2 class、新laneなし）を記録。
  新規lane / `ci_portfolio_map.yml` 変更は不要で、実際にdiffへ含まれない
  （test-audit authoring gateのPR本文記載と一致）。

## Executed test surface

独立sessionによる対象head `e8e97234014a054a8e713b1b3ac25421d69318ca`（作業tree = head、
tracked変更なし。`77eba665f0` 上でも同2 commandを実行し同結果）での再実行:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.ui.ManualOrganizationRunPublicationConfinementTest' \
  --tests 'app.lawnchair.organizer.ui.ManualOrganizationRunAdmissionPublicationTest'
# → BUILD SUCCESSFUL in 1m 1s（2 class / 8 test、0 failure。2026-10-03）

./gradlew spotlessCheck
# → BUILD SUCCESSFUL（up-to-date、5 tasks。2026-10-03）
```

## CI run status（両headともcompleted）

- **対象head `e8e9723401` のPR run**
  [37091014633](https://github.com/nunu1733/NunuLauncher/actions/runs/37091014633)
  （workflow "CI"、pull_request event、2026-10-03T02:47:28Z開始）:
  **conclusion = success**。job別:
  - SUCCESS（11）: `changes`、`check-style`、`validate-repo-contract`、`build-debug-apk`、
    `organizer-unit-tests`、**`final-status`（merge gate green）**、
    instrumentation lane 5件 — `method-choice-journey-tests`、
    `manual-organization-ui-tests`（AC-4対象lane）、
    `category-override-tests`、`exchange-import-ui-tests`、
    **`onboarding-proposal-tests`（前headで失敗したlaneが修正後green）**。
  - SKIPPED（5 lane、path filterによる設計内skip。`.github/workflows/ci.yml` の
    `if: needs.changes.outputs.surface_*` 条件に本PRの変更pathが該当しないため）:
    `production-input-tests`、`restore-capture-tests`、`reservation-recovery-tests`、
    `shared-writer-tests`、`db-migration-tests`（両runで同一のskip集合）。
- **High-risk gate run（対象head）**
  [37091014602](https://github.com/nunu1733/NunuLauncher/actions/runs/37091014602):
  **success**（`high-risk-evidence` job）。
- **参考（監査過程のhead）** `77eba665f0`:
  CI run [37089761014](https://github.com/nunu1733/NunuLauncher/actions/runs/37089761014)
  は **failure**（`organizer-instrumentation-onboarding-proposal-tests` job
  [111107450720](https://github.com/nunu1733/NunuLauncher/actions/runs/37089761014/job/111107450720)、
  test `OnboardingOrganizationProposalInstrumentationTest >
  reentryHintAutoDismissesAfterTheTimeoutWithoutTouchingTheOutcome[emulator-5554 - 16]`、
  signature: `IllegalStateException: organizer run state machine must not run on the
  publication (main) thread` at `HandlerRunPublicationThread.assertNotPublicationThread`
  （`RunPublicationThread.kt:106`）← `ManualOrganizationRun.dismiss`
  ← `ManualOrganizationPreferences` のCompose `onDispose`（main thread）。
  直後に `RuntimeException: Unable to destroy activity …PreferenceActivity` で
  process crash。`final-status` もfailure。同laneはmain最新success run
  （[37061400421](https://github.com/nunu1733/NunuLauncher/actions/runs/37061400421)）で
  greenであり、flakeではなく本変更起因と判定（詳細はFindings 1）。
  High-risk gate run [37089761009](https://github.com/nunu1733/NunuLauncher/actions/runs/37089761009)
  はsuccess。

## Findings

**Findings 1 [解決済み / guardの有効性証跡]: 前head `77eba665f0` のCI失敗は
本変更起因の実障害検出であり、修正commit `e8e9723401` で解消された。**

- 直接原因は `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt:484`
  （当時）の `DisposableEffect(coordinator) { onDispose { coordinator.dismiss() } }`。
  `coordinator` は `ManualOrganizationRun` 本体（同file :129）であり、onDisposeは
  composition破棄時に **main thread** で実行され、新guardがfail-fastした。
  同一fileの他のdismiss呼出しは `withContext(Dispatchers.IO)` で包まれていた一方、
  この1箇所が転換漏れだった。plan.mdの実装時grep監査
  （「公開methodをUI/main呼出し経路でgrepし、直接呼出しが残っていないことを監査」）と
  spec 418 Scenario「main起点のUI呼び出しはworkerへdispatchされる」の転換漏れであり、
  Phase 2 review 4ラウンドでも指摘されなかった。分類: **product-sideの
  machine-on-main暴露をguardがdeterministicに検出した事例**（Activity destroy時の
  process crashとして表面化）。変更前はこの経路でmachineがmain上で実行されていた
  （T2と同型の潜在違反）。
- 修正: fix commit `e8e9723401`（diffは本recordのScope節どおり1 file / 2 hunk）。
  修正後の対象headで同laneはgreen（CI run 37091014633）。
  監査として両hunkの妥当性を確認済み（Scope節「fix commitの照合」参照）。

**Findings 2 [解決済み / 同型のlatent経路]: `leaveRecoveryResultToHub()` の
main-confined scopeからの直接呼出しも同一commitで解消された。**

- 前headでは `interruptAndNavigate()` が `scope.launch { coordinator.leaveRecoveryResultToHub() }`
  （`scope` はmain-confined `rememberCoroutineScope()`）であり、recovery result状態での
  interrupt navigation時に同一signatureのcrashが生じうるlatent経路だった
  （本監査がCI結果とは独立にgrep監査で検出）。fix commitのhunk 1が
  `withContext(Dispatchers.IO)` 化で閉じた。現headのpreferences面には
  main-originのmachine入口直接呼出しは残存しない（Scope節のgrep結果）。

**Findings 3 [low / 残課題]: gate取得系4 methodにguardがない。**

- plan.mdはmachine入口として `commitGeneratedSession` / `cleanupBoundExport` /
  `discardScopeBoundRequest` / `savePendingImportForLiveOwner` をguard対象に列挙するが、
  実装はこの4 methodにguardを置いていない（:1470 / :1510 / :1557 / :1611 は
  直接 `synchronized(lock)` へ入る）。現状の全production callerは
  `scope.launch(Dispatchers.IO)` 内であることを独立に確認した
  （`ExchangeFlowUi.kt:704` startGeneration→generateWithCommit、:839-840 settleExportCleanup、
  :1081 pre-send discard、:1631 launchDurablePendingIntentSave、:2418-2420
  discardScopeBoundRequest wrapper。`ManualOrganizationPreferences.kt:1326` も
  当該wrapper経由）。よって既知のmain-origin暴露はないが、
  spec 375 Amendment例外条件(ii)の「gate取得経路はmain上で実行されない」の保証が
  call-site規律のみで守られており、mainから呼ばれた場合にguardではなく
  run lock待ち（hop workerとの潜在循環）へ入る。defense-in-depthとしての
  guard追加を推奨（Phase 2 reviewでは不問のまま。merge阻害ではない）。
  後続対応が必要な場合は別Issueへの分離を推奨する。

**Findings 4 [info]: AC-1のred-first記録は独立再現していない。**
PR本文のred log（hop無しhead、`expected:<confinement-pub> but was:<machine-driver>`、
2 failed）は実装sessionの記録を引用したもので、本監査はread-only制約により
red状態の再現を行っていない（コード変更が必要なため）。構造的な検出主体
（bus private所有 + tracker）の合理性は高く、実production経路のdeterministic検出は
Findings 1のCI failureで実証されたが、oracle自体のred→green記録はPR本文記録に
依存することを明示する。

**Findings 5 [info]: plan Change setとの差分は benign。**
planは `ManualOrganizationRunTest.kt` の「seam注入への追従（必要最小限）」を
想定していたが、seam注入が `ManualOrganizationRunTestSupport.kt` に集約されたため
当該fileの変更は不要だった（「必要最小限」= 0で整合）。
fix commitは `ManualOrganizationPreferences.kt` がplan Change set表の外側に追加される形
（実装時grep監査のやり直し結果として妥当な位置）。
またspec 418のstatusは `accepted` のまま（`implemented` 更新はmerge時のowner作業）、
plan.mdのExecution checklistも未checkのまま残る。本監査はこれらを代替しない。
spec 375側のAmendment（header note、例外節、処理時間界限の例外対象外化、
SR-AC-08 oracle追加・scope限定、375 plan.md Amendment note、`updated` 同期）は
diffにすべて含まれることを確認した。

## 明示的な未確認範囲

- 本PRの変更pathに対して、5 instrumentation lane（production-input / restore-capture /
  reservation-recovery / shared-writer / db-migration）はpath filterにより
  **両headとも未実行**（skipped。設計内の挙動）。これらの表面は本変更の影響外という
  路面分類に依存しており、本headでの直接的な実行証拠は存在しない。
- AC-1のred-first、およびPR本文の `app.lawnchair.organizer.*` 1816 test / assemble成功は
  実装session記録の引用であり、本監査は2 oracle classのlocal再実行と
  CI `organizer-unit-tests` の成功のみを独立確認した。
- instrumentation面は本監査では再実行していない（hosted CI結果の観測のみ）。
- #418の終了条件（root cause文書化・3連続full-workflow成功）は本PRでは未達であり、
  T1（SnapshotStateObserver multithreaded access）・T3（CalledFromWrongThreadException）は
  category 6（原因未解決）のまま #418 がopenで継続する。本PRは #418 をcloseしない
  （PR本文どおり `Refs #418`）。T2の非再現は統計的観察（#418継続）で判断する。

## Conclusion

**GO（対象head `e8e97234014a054a8e713b1b3ac25421d69318ca` でのmerge可）。**
spec 418 AC-1〜AC-5とspec 375 Amendmentの実装構造をコード照合・local JVM oracle再実行
（対象head上で8/8 green）・`spotlessCheck`・CI（run 37091014633: `final-status` 含む
全実行job success、`manual-organization-ui` lane含む5 lane green）で独立に確認した。
(a)〜(f)の不変条件はすべて成立する。前headでのCI failureは、本PRのguardが
planのgrep監査漏れだった2つのproduction main-origin経路
（onDispose dismiss / interruptAndNavigate leaveRecoveryResultToHub）を
deterministicに検出したものであり、fix commit `e8e9723401` で解消・green化された。
残存はFindings 3（gate取得系4 methodへのguard追加推奨、low・merge阻害なし）と
Findings 4（red-first記録はPR本文依存）のみ。T1/T3はcategory 6のまま #418 がopenで
継続し、T2の非再現判定はmerge後の統計観察に委ねられる。
