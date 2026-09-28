# Audit: PR #432 scope-first method choice (Issue #417 Phase 2 implementation)

> Status: completed（verdict: **NO-GO — merge gate red**。`CI / final-status` が失敗しており、merge条件を満たさない）
> Audit date: 2026-09-24

- Auditor: 独立audit session（ZCode / GLM-5.3-flash）。PR #432の実装・reviewを行ったsessionとは別の作業主体であり、本recordの作成を除きcode・文書の変更を行っていない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/432
- Head SHA: `f37b7330dd9fd9b4916f25c67ffe1124186d6b7f`（Phase 2 review最終Approved対象head。本audit確定後に追加されるのは本audit記録のdocs commitのみであり、それ以外の変更はない）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35952398740 — **FAILURE**（`pull_request` event、head `f37b7330dd9f`、`CI / final-status` 失敗）。成功したCI runは本headに存在しない。併記: High-risk gate run https://github.com/nunu1733/NunuLauncher/actions/runs/35952398651 は success（本PRは `risk:` label・高リスクpathなしのためaudit記録なしでpass。CI失敗の代替にはならない）。
- Criteria: specs/417-scope-first-method-choice/spec.md AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11

## Scope

対象diff: base `17d883a012d66833244d10c00d551215064c35d9`（main）→ head `f37b7330dd9f`、39 files changed, 7120 insertions(+), 640 deletions(-)。

- production: `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt`（`State.ScopeConfirmed`新設・run-owned transaction API）、`organizer/integration/exchange/ExchangeFlowController.kt`（prepare/durable mutation分割）、`organizer/ui/exchange/ExchangeFlowUi.kt`（hosting再配置・origin読み書き・ImportReview遷移）、`organizer/integration/AndroidExportSessionStore.kt`＋`organizer/personalization/{ExportSessionStore,ContextExportModels}.kt`（additiveな`entryOrigin` field＋`invalidateIf -> Committed/NoMatch/WriteFailed`）、`ui/preferences/destinations/ManualOrganizationPreferences.kt`・`organizer/ui/{ManualOrganizationFace,MissingAppSelectionScreen}.kt`・strings（EN/ja）。
- docs先行（AC-10）: `docs/product/organizer-to-be-ux.md`、`docs/product/organizer-disposition-migration.md`、specs 331/367/369/372/374/375のAmend/Supersede標記、`CONTEXT.md`用語。commit順序は docs `488f9ca23f74` → production `b22adea292ac` でspec要求どおり先行している。
- test: JVM unit tests（`ManualOrganizationRunTest`等648行追加ほか）、connected instrumentation（`ManualOrganizationPreferencesInstrumentationTest` +631行、`MissingAppSelectionInstrumentationTest`、`ExchangeImportSurfaceInstrumentationTest`、新規 `MethodChoiceConnectedJourneyInstrumentationTest` 1878行・20 test）。

本PRは `risk: layout-data` / `risk: migration` labelを持たず、高リスクpath一覧（`organizer/application/**`、`LauncherProvider`、`ModelWriter`等）を変更しないため、`high-risk-evidence` gateの対象外である（実際にgateはpass）。ただしowner依頼により本独立audit記録を作成する。Launcher DB（favorites/workspace）への書込み経路は本diffの対象外であり、session store（AtomicFile）とpending intent storeへのdurable mutationのみを含む。

## Criteria check

spec受入条件ごとの確認結果。verdictは本audit独自の再実行・再読取りに基づく。

- **AC-1 / AC-3 / AC-4（scope-first順序・0候補pass-through・0件明示）**: **GO（unit由来のみ）**。JVM gateで `ManualOrganizationRunTest`・`MissingAppSelectionInstrumentationTest` の更新版が通過。ただしconnected journeyのCI実行は下記Findingsのとおり不完全。
- **AC-2（同一scopeからAI exportとplanner additionsを組成）**: **GO（unit由来のみ）**。`ContextExportBuilderTest`拡張ほかがJVM gate通過。
- **AC-5（凍結条件・reopen guard・`WriteFailed`凍結維持・durable依頼はadmissionを妨げない）**: **条件付き**。unit oracleは通過。connected (c)(e) journeyのうち(e)はCI通過、(c)は**CI失敗**（下記）。
- **AC-6（origin書込み・legacy decode規則・`entryKind`導出・`generationEpoch`原子commit・取り込み遮断・ImportReview遷移・entry面import-only）**: **条件付き**。unit oracleは全通過。connected (g)〜(q)に対応する新規 `MethodChoiceConnectedJourneyInstrumentationTest`（20 test）は**どのCI laneでも実行されていない**（下記Findings 2）。`invalidateIf`の実装（`AndroidExportSessionStore.kt:130-155`、tombstone書込みで`Committed/NoMatch/WriteFailed`を返す）はsource確認済み。
- **AC-7（stale候補fail-closed）**: **条件付き**。unit回帰は通過。Home変化後のtyped再試行instrumentationはCI失敗lane内の判別が不可能（下記）。
- **AC-8（instrumentation (a)〜(v)）**: **NO-GO**。KDocの1:1 mapping（(a)〜(v)）は実在し、(a)〜(f)は `ManualOrganizationPreferencesInstrumentationTest` / `MissingAppSelectionInstrumentationTest`、(g)〜(v)＋(d)後半は `MethodChoiceConnectedJourneyInstrumentationTest`（20 @Test、実在確認済み）に対応する。しかしCIの manual-organization-ui laneで **11 failure** が発生し、うち4件がAC-8 journey (a)(c)(d) そのものである（下記Findings 1）。(g)〜(v)はCI実行が存在しない。
- **AC-9（semantics/live region/focus/200%自動oracle＋実機evidence）**: **未確認**。自動oracleは実装済み（JVM/CI緑の範囲）。実機TalkBack・キーボード・Switch Accessの操作evidenceは**未実施**（PR本文も自認）。merge前の必須evidenceが欠落している。
- **AC-10（docs改訂のproduction先行・同一PR内適用）**: **GO**。commit順序とdiff確認済み（上記Scope）。
- **AC-11（既存回帰＋failure注入oracle）**: **NO-GO**。既存回帰のうち `ManualOrganizationProductionE2EInstrumentationTest` 5件が**CI失敗**（本PR未改訂の旧journey oracle。下記Findings 1）。JVM gate上のfailure注入oracle（破棄`WriteFailed`・owning run喪失・stale epoch・lock順序交差・process死相当）は通過。

## Executed test surface

本audit sessionが独立に実行した検証（環境: JDK 21.0.12 / Android SDK Platform 36.1 / Build Tools 36.1.0 / macOS arm64、head `f37b7330dd9f` clean tree）:

- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — **PASS**。`BUILD SUCCESSFUL in 1m 17s`。JUnit XML集計: 156 classes / **1782 tests / 0 failures / 0 errors / 0 skipped**（`build/test-results/testLawnWithQuickstepGithubDebugUnitTest/`）。
- `./gradlew spotlessCheck` — **PASS**（`BUILD SUCCESSFUL`。同headでのimplementer実行後に入力未変化のためup-to-date判定を含む）。
- `gh run view 35952398740 -R nunu1733/NunuLauncher --log-failed` および report artifact（`organizer-instrumentation-manual-organization-ui-reports`、artifact id 10789736996）の取得と解析 — 11 failureの特定（下記）。
- `git diff --stat 17d883a012..f37b7330dd`、`git diff origin/main..HEAD -- .github/`（空＝ci.yml不変の確認）、PR commit一覧照合（AC-10順序）。
- Review記録の取得: `gh api repos/nunu1733/NunuLauncher/issues/423/comments`（11件）、`gh api .../issues/417/comments`（4件）、各最終コメント本文確認。

CI run 35952398740（`pull_request` event、head `f37b7330dd9f`）のjob別結論:

| Job | 結論 |
|---|---|
| changes | success |
| check-style | success |
| validate-repo-contract | success |
| build-debug-apk | success |
| organizer-unit-tests | success（`app.lawnchair.organizer.*` を含む同一JVM gate） |
| organizer-instrumentation-production-input-tests | success |
| organizer-instrumentation-category-override-tests | success |
| organizer-instrumentation-onboarding-proposal-tests | success |
| organizer-instrumentation-exchange-import-ui-tests | success（`ExchangeImportSurfaceInstrumentationTest`のみ） |
| **organizer-instrumentation-manual-organization-ui-tests** | **FAILURE**（140 tests中11 failures、0 skipped） |
| organizer-instrumentation-restore-capture / shared-writer / reservation-recovery / db-migration tests | skipped（impact-based portfolio gating。対象surface `surface_backup_restore` / `surface_layout_write` / `surface_db_schema` がdiffに含まれず、正当なskip） |
| **final-status** | **FAILURE**（required merge gate） |

実装sessionの報告するローカルconnected lane実行（API 36 emulator、`MethodChoiceConnectedJourneyInstrumentationTest` 20/20、(l)(o)改訂後focused re-run含む、androidTest compile PASS）は **実装sessionの補助証拠** であり、本auditは再実行していない。本audit自身の証拠は上記の独立JVM gate再実行とCI実行結果である。

## Findings

1. **Blocking — CI merge gateが失敗している（run [35952398740](https://github.com/nunu1733/NunuLauncher/actions/runs/35952398740)、`CI / final-status` FAILURE）。** manual-organization-ui laneで11 failures:
   - `ManualOrganizationProductionE2EInstrumentationTest`（本PR未改訂・5件）: `manualRunUsesProductionCaptureApplyVerificationAndRecovery`（`IllegalStateException: Production manual run did not reach preview: ScopeConfirmed(...)`）、`qsbDisabledRowlessFirstScreenCaptureAndPlanningRemainNoChanges`（`expected:<NoChanges> but was:<ScopeConfirmed(...)>`）、`staleProductionConfirmationDoesNotWrite`、`manualActionWaitsForStartupReconciliationBeforeAcceptingCapture`、`recoveryConfirmationExplainsTargetAndRestoresPreStateAfterExternalChange`。根因は同一: これらのoracleは旧journey（`startPlain()` → `State.Preview`直行）を前提とし、scope-first変更後のmanual runが`ScopeConfirmed`（方法選択面）で停止するようになったため`assertTrue(run.state is Preview)`等が失敗する。**既存production E2E oracleが本変更に追随して改訂されていない**構造的なtest-gapであり、JVM gateでは検出できない（instrumentation限定test）。
   - `ManualOrganizationPreferencesInstrumentationTest`（本PR改訂済み・4件）: AC-8(a) `emptyHomeSelectAllMethodFaceAiArmImportAttachReachesThePreview`（`ComposeTimeoutException` 10s）、AC-8(d) `backFromTheMethodFaceWithoutARequestReopensTheEditableSelection`（`'1 app selected' is not displayed`）、AC-8(c) `backWithAnActiveRequestDiscardsItAndReopensTheSelection`・`backDiscardWriteFailedKeepsTheFaceAndShowsTheTypedFailure`（いずれも`ComposeTimeoutException` 10s）。CI emulator上でのタイミング脆弱性か実機能回帰かは本auditでは確定できない（ローカルでは通過報告あり）。
   - `MissingAppSelectionInstrumentationTest`（1件）: `confirmForwardsTheSelectedIdentitiesToTheScopeComposedCompose`（`ComposeTimeoutException` 1s）。
   - `UsageAccessJitInstrumentationTest`（1件）: `crossOriginExchangePresentationPausesTheRunUntilResolution` — `IllegalArgumentException: Key "exchange-usage-access-jit" was already used`。LazyColumn重複keyのCompose crashであり、environment timingではなく決定的な不具合の疑いが強い。exchange hosting再配置に伴い同key行が同一面に2つ出現する状況が示唆される。
   対応: 旧journey oracle 5件の改訂（または新journeyへの置換）、Prefs/MissingAppSelection 5件の原因特定と修正、UsageAccessJit重複keyの修正が必要。修正push後は新headでreview recommendation更新・CI再実行・**本auditのやり直し**が必要である（github-workflow「auditで問題が見つかった場合は…新しいhead SHAに対してauditをやり直す」）。
2. **High — AC-8 (g)〜(v)のconnected evidenceがCIで実行されない。** 新規 `MethodChoiceConnectedJourneyInstrumentationTest`（20 test）は `.github/workflows/ci.yml` のどのlaneの `testInstrumentationRunnerArguments.class` にも含まれていない（本branch・現mainの両方で確認。本PRでci.ymlは未変更）。CIが実行するのは (a)〜(f)（manual-organization-ui lane内の `ManualOrganizationPreferencesInstrumentationTest` / `MissingAppSelectionInstrumentationTest`）と `ExchangeImportSurfaceInstrumentationTest` のみである。spec「Test oracle」節は focused connected test lane を必須evidenceと定めており、(g)〜(v)は現状 **実装sessionのローカルemulator実行（20/20、補助証拠）のみ** で裏付けられている。CI laneへの追加（#332の前例どおり）がmerge前の是正事項である。なお、androidTest source set全体はconnected laneのbuildでcompileされるため、本classのcompile自体はCIで検証されている。
3. **Medium — AC-9の実機device evidenceが未実施。** TalkBack・キーボード・Switch Access・200% font scaleの操作evidenceは記録されていない（PR本文も自認）。自動a11y oracleはJVM/CI緑の範囲で通過しているが、specは自動oracleと実機evidenceの両方を要求する。
4. **Confirmed limitation — AC-8(o)のprocess deathは実OSのprocess killではない。** `journeyO...` は実 `AndroidExportSessionStore` のdurable fileを境界にしたin-process fault injection（gate transaction capabilityがdurable mutation後にthrow）で固定されており、実際のOS process death（crash-test territory）は未検証である。review round 4もこの点を「Process death itself remains device/crash-test territory」と明記済み。spec措定の物理的crash boundaryとの等価性はsource-levelの論証に依存する。
5. **Review経緯の確認（本auditの検証結果）**: Phase 1（spec/plan）は PR #423 でreview 11回（round 5/6は解消判定コメントを含む。comments 5799407873, 5799855593, 5800222038, 5800480754, 5800788106, 5801067598, 5801305118, 5801490465, 5801661397, 5801885964, [最終 **Approved**](https://github.com/nunu1733/NunuLauncher/pull/423#issuecomment-5802041454) at head `bc459e9fa0f4066c89a008a46c8ba1a0c4457ff3`）。Phase 2（実装）は Issue #417 でreview 4回（[round1 Changes requested](https://github.com/nunu1733/NunuLauncher/issues/417#issuecomment-5805174432) at `b22adea292`、[round2 Changes requested](https://github.com/nunu1733/NunuLauncher/issues/417#issuecomment-5805799603) at `a56c1d00cd`、[round3 Changes requested](https://github.com/nunu1733/NunuLauncher/issues/417#issuecomment-5806453167) at `b6b1ef9670`、[round4 **Approved**](https://github.com/nunu1733/NunuLauncher/issues/417#issuecomment-5807060470) at **head `f37b7330dd9fd9b4916f25c67ffe1124186d6b7f`** — 本audit対象headと一致）。ただしround 4 approvalの投稿時刻は2026-09-24T03:34:53Zで、CI run開始（03:40:03Z）より前であり、approvalはCI結果を参照していない。github-workflowの承認失効規定（実質変更を含む新commit・条件未解消）に基づき、11 failures修正のpushがあれば既存approvalは失効し新headの再reviewが必要である。
6. **非blocking観察**: 本PRのdiffはsession/pending store（AtomicFile）へのdurable mutationのみで、Launcher DB書込み・schema・permission・通信の追加はなかった（高リスクpath一覧との突合済み）。`entryOrigin`追加はadditiveでlegacy decode規則（origin欠落＋scope非空＝RUN_IN／scope空＝IDLE）の実装とunit oracleを確認。downgrade時のfail-closed倒し（旧codecの未知key decode失敗）はspec明示の許容どおり。

## Verdict

**NO-GO / not merge-ready（head `f37b7330dd9fd9b4916f25c67ffe1124186d6b7f`）。** 実装本体（state machine・session store primitive・unit oracle群）は本auditの独立JVM gate再実行（1782/0）とsource照合で健全に見えるが、(1) CI merge gate `final-status` が11 failuresで失敗している（うち5件は既存production E2E oracleの未改訂、1件は決定的疑いの強いLazyColumn重複key crash）、(2) AC-8 (g)〜(v)のconnected evidenceがCI laneに存在しない、(3) AC-9実機evidenceが未実施、の3点がmerge条件の未充足である。修正・CI再実行・新headでの再review・本auditのやり直しのうえ、`CI / final-status` が成功したrunを対象にauditを更新すること。

## Addendum（2026-09-24）: 新head `7aa5c7f157` での追試

> Status: addendum（追試結果: 初回記録時の **NO-GOは解消**。ただしdelta再review（round 5）未完 — merge前のowner判断項目。下記「Verdict更新」参照）
> Addendum date: 2026-09-24

- 追試対象head: `7aa5c7f157d65a1484bbf69be36686d9b14b284e`（旧head `f37b7330dd9fd9b4916f25c67ffe1124186d6b7f` からのdeltaは本audit記録のdocs commit `f882aebc11` ＋是正commit群。内容は下記Delta要約のとおり）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35962531277 — **SUCCESS**（`pull_request` event、head `7aa5c7f157d`、2026-09-24T06:01:23Z開始）。**全16 job green・`CI / final-status` success**。新lane `organizer-instrumentation-method-choice-journey-tests` を含みsuccess。manual-organization-ui laneもsuccess — 初回Findings 1の11 failuresとFindings 2（AC-8 (g)〜(v)がCIで実行されない）はいずれも本runで解消。
- High-risk gate run: https://github.com/nunu1733/NunuLauncher/actions/runs/35962531280 — **success**。
- 本addendumの検証根拠: `gh run view` による両runのmetadata・job別conclusion確認、両head間のfirst-parent commit一覧・commit message・diff確認、`.github/workflows/ci.yml` のlane↔class wiring確認（`MethodChoiceConnectedJourneyInstrumentationTest` → `organizer-instrumentation-method-choice-journey-tests` lane）。gradleによるJVM/instrumentationの再実行は本addendumでは行っておらず、上記CI runに依拠する。

### Delta内容の要約（`f37b7330dd` → `7aa5c7f157`）

first-parentは docs `f882aebc11` → fix `e43f4cd056` → main merge `898152f571` → test追従 `7aa5c7f157`。

- **production修正2件**（`e43f4cd056`。いずれも再現→修正）:
  1. **選択面remember keyのrun-id化** — 確定→method-choice面→Back（reopenSelection）の往復でremember keyが`runId → null → runId`と変わり、確定済み選択が黙って消失していた。ScopeConfirmed駐在中の選択保持違反（planの「選択内容はUI stateが保持」に反する）であり、選択を所有するrunのrunIdにkeyを固定して修正。新runは従来どおり未選択で開始する。
  2. **exchangeFlowItems二重hostの修正** — Idle/Cancelled面のhostingとScopeConfirmed面のhostingが同一item listへ同時emissionし、LazyColumn重複key（`exchange-usage-access-jit`）でframe crashしていた（初回Findings 1の`UsageAccessJitInstrumentationTest` failureに対応）。hosting判定をlambda内の単一state読み`faceState`に統一し、両hostを1 frameで排他的にした。
- **旧契約テスト更新**: `ManualOrganizationProductionE2EInstrumentationTest` 5 journey — `startPlain()`→`State.Preview`直行を前提とする旧journeyを正規scope-first flow（確認→ScopeConfirmed停止→このまま整理 `planWithConfirmedScope`）へ更新。0候補も`NoChanges`到達からAC-3どおり`ScopeConfirmed(空)`直行を同一arm経由で固定する形へ変更。`MissingAppSelectionInstrumentationTest` 1件（`confirmForwardsTheSelectedIdentitiesToTheScopeComposedCompose`をscope-first flowへ）。`Issue265ManualEditRecoveryInstrumentationTest` 5件（`7aa5c7f157` — main merge後にreservation-recovery laneで初実行され、ScopeConfirmed停止でAppliedに到達できなかったjourneyをrunStart helperへのこのまま整理arm追加で正規flow観測へ更新）。
- **tap geometry修正**: `ManualOrganizationPreferencesInstrumentationTest` の`performScrollToNode`後CTAがviewport最下部に張り付き、tap中心がsystem navigation gesture zoneに落ちてonClickが発火しない（API 36 emulator geometryで決定的）失敗への対処 — 初回Findings 1のPrefs lane 4 failuresに対応。
- **MethodChoiceクラス用新CI lane追加＋portfolio同期＋main merge解決**: `MethodChoiceConnectedJourneyInstrumentationTest` をper-class lane `organizer-instrumentation-method-choice-journey-tests` として新設（#332の前例、surface_organizer_ui）。final-status needs・`ci_portfolio_map` edge・portfolio doc rowへ登録。#422のlane再構築（`compute_ci_gating.py`・portfolio validator等）と同期し、main merge（#422/#425/#433、#415/352）を取り込み。

### Verdict更新

- 初回記録時（head `f37b7330dd`・CI赤）の**NO-GOは解消**。現時点のstate: CI merge gate（`final-status`）green、Phase 2 review round1-4（Issue #417コメント）、round4 **Approved**（head `f37b7330dd`。初回Findings 5参照）。
- **delta再review（round 5）完了: [Approved](https://github.com/nunu1733/NunuLauncher/issues/417#issuecomment-5809897430)（2026-09-24、head `7aa5c7f157`を対象にdelta `f882aebc11`..`7aa5c7f157`の2件のproduction修正・テスト追従・新CI laneを確認、新規指摘なし）。** 初回試行ではChatGPT側stream errorで2回中断したが、3回目の送信で完了。これにより本PR（head `17c970ddde` = `7aa5c7f157` + 本監査doc追記）はreview・CIとも現headで整合する。
- 未確認範囲は初回記載を維持: AC-9の実機device evidence（TalkBack・キーボード・Switch Access）未実施、AC-8(o)は実OSのprocess deathではなくin-process durable fault injectionである点等（初回Findings 3/4）。
