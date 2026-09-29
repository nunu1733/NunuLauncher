# High-risk audit: PR #476 複数選択の視覚的編集画面（#449 Phase 2）

> Status: **NO-GO（final-status red。ただし赤は main でも同一に再現する manual-org lane の既存flake 1件のみで、初回NO-GOの「androidTestコンパイル不能 / instrumentation evidenceゼロ」は解消）**。本節は初回監査（head `c74e305ff0`、NO-GO）に対する**追跡監査**である。初回監査の全文は末尾「前回監査」節に保持する。
> Audit date: 2026-09-28

- Auditor: 独立session（general-purpose subagent）。追跡監査を実施。実装sessionではなく、本PRのdiff作成・Phase 1/2 review・検証実行に関与していない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/476
- Head SHA: `0e0ffbd64f`（追跡監査2、rebase後。経緯: c74e305ff0 → e6190ef5bc → bc32e0311d → main `2ac104aa93` へrebase → 0e0ffbd64f）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36431255830（head `e6190ef5bc`、**failure**。final-status以外の15 job中14 success。`organizer-instrumentation-manual-organization-ui-tests` のみ failure（151 tests中150 pass、失敗は main と同一の既存flake 1件）→ `final-status` failure。**green な `final-status` run は branch に存在しない**）
- High-risk gate run: https://github.com/nunu1733/NunuLauncher/actions/runs/36431255694（failure。理由: (1) audited Head SHA 以後の非docs変更（旧recordが `c74e305ff0` を指していたため）、(2) 参照CI run 36402204992 が失敗、(3) 成功した pull_request run が無い。本recordの更新で (1) は解消する。(2)(3) は成功CIが存在しない以上残る＝正しい挙動）
- Criteria: [spec 449](../../specs/449-multi-select-surface/spec.md)（accepted）の AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13, AC-14, AC-15, AC-16 / FR-019 / NFR-013 / NFR-014
- Criteria: [操作面のADR](../../docs/adr/0014-edit-surface.md) の ADR-0014（accepted。操作面の決定 D-014。本PRは案B）
- Criteria: [直接編集の書込み契約ADR](../../docs/adr/0013-direct-edit-write-contract.md) の ADR-0013（accepted。#448が所有し、本PRは非干渉を確認するのみ）

## Scope

本節以下は追跡監査（head `e6190ef5bc`）である。

- 対象diff: base `main`（`c5a7840b88`）.. head `e6190ef5bc` の全diff（54 files, +5140/-5）。`src/`（上流Launcher3）の変更0ファイル、schema/migration・dependency追加・permission追加0は初回から不変。
- 前回監査head `c74e305ff0` からの追跡diff（17 files, +305/-36）:
  - `d1c7386ba6`: 初回NO-GO Finding 1の修正（`EditSurfaceApplyInstrumentationTest` の3コンパイルエラー修正 + 13 fakeへの `inspectCapture()` fail-closed stub）と初回audit record（本ファイル初版）の追加。
  - `0b1988c122`: shared-writer lane失敗の修正（raw plan intendedState と recapture の不正比較を撤去し、物理削除assertへ限定。MoveToPageを実行する2 testへpage-1 fixtureを追加）。
  - `9d8caf538a` / `af6b527b38`: stale test fixture driftの修正（min row id選択→seeded (0,2,1) のplacement選択、再seed→1行追加でtracked行を保持）。
  - `2370bdec31` / `1a5a2c4895` / `d65d9f3514`: hub traversal testの試行（DPAD順へ追加 → scrollでreveal → 最長scrollable対象）。最終的に `e6190ef5bc` が置換（rowそのものがhubになかったことが原因で、scroll修正では到達不能だった）。
  - `e6190ef5bc`: 実機検証（Pixel 9a）由来の2修正 — hub rowを run面（`ManualOrganizationPreferences`）から Organizer hub（`OrganizerHubPreferences`）へ移動、capture/applyの実行を `Executors.MODEL_EXECUTOR` から専用single-thread executor（`homeedit-surface`）へ移動。hub traversal testの最終形（scroll撤去・DPAD順にrowを含める）も同commit。
- 本auditが追跡した経路（変更点）: 入口2経路（`LauncherOptionsPopup.kt` は不変。hub rowの配置は `OrganizerHubPreferences.kt` へ移動）、capture/applyの実行スレッド（`HomeEditSurfaceActivity`）。適用seam（`HomeEditSurfaceAccess`）・契約型（`CapturedSnapshot` / `ValidatedLayoutPlan` / `ApplyResult`）・`ApplyProtocol`・結合点7の削除passは不変。
- 対象外（初回と同じ）: ベンチマーク実測（AC-12）、エミュレータスクリーンショット（AC-1/2/3）、TalkBack runtime（AC-15）、NFR-013実測（AC-11）。実機確認は実装sessionが commit `e6190ef5bc` のmessage（Pixel 9aで select→remove→confirm→checkpoint(A4)→commit(A6)→APPLY_VERIFIED(A8)、recovery point 1個、削除行の物理削除、相関reloadでのホーム更新）に記録しており、本auditは再実施していない。

## Criteria check

（追跡監査。head `e6190ef5bc`）総合: **NO-GO（merge gate red。ただし内容面のNO-GO要因は解消）**。初回のBLOCKER（androidTestコンパイル不能・instrumentation evidenceゼロ）は解消した。全instrumentation laneがクラス実行に到達し、新規 `EditSurfaceApplyInstrumentationTest`（実DB 3件）は shared-writer lane で実行され success、hub rowのtestを含む manual-org lane は151 tests中150 pass（唯一の失敗は既存flake）。しかし `final-status` は赤のままで、高リスク独立エビデンスの機械要件（検証対象commit上のgreen run）を満たさない。

- AC-1: **PASS（コード）/ runtimeはowner evidence**。hub rowは `OrganizerHubPreferences.kt` の start CTA と diagnostics row の間に移動（`e6190ef5bc`）。移動先が Organizer hub である根拠はコードで確認（`organizer_hub_label`、TO-BE D-02 のhub構成コメント、start CTA → `HomeScreenManualOrganization`＝run面）。workspace長押しメニュー（`LauncherOptionsPopup.kt`）と `lockHomeScreen` 非表示は不変。plan Designのhub同定（`ManualOrganizationPreferences.kt`）は実コードと不一致のままで、この移動によりAC-1に整合した。
- AC-2 / AC-3: 初回と同じ（図投影・選択述語はJVM testで支持。runtimeスクリーンショットはowner evidence）。
- AC-4 / AC-9: PASS（JVM）。99 testsを再実行してgreen（内訳は Executed test surface）。
- AC-5: **PARTIAL（改善）**。protocol経由のstale零書込み（`LayoutApplicationModule.apply` → `STALE_REVISION`）が実DBで実行されるようになった。一方、成功経路の protocol-level `Applied`（recovery point 1個・相関reload・A7 exact検証）は依然として実行oracleが無い（新testは `prepareApplyWriteSet` + `applyWriteSet` の adapter直駆動）。初回Findings 2の残差。
- AC-6 / AC-7 / AC-8: 初回と同じ（JVM）+ AC-6は実DB stale testで一部確認。`RolledBack` のpre-stateは注入失敗testが実DBで確認した（ただしadapter-level）。
- AC-10: **PARTIAL（改善）**。実DB test 3件（1 transaction + 物理削除 + 無題フォルダ + 注入rollbackのexact pre-state + stale零書込み）がCIで実行・pass。process死後の整合は未カバー。
- AC-11 / AC-12: UNVERIFIED（エミュレータ計測・ベンチマーク実行記録なし）。
- AC-13: FAIL（mainでもFAIL）。net-new unassigned pathは初回と同一の9件で、PR本文への結果記録も未実施（Findings 3）。
- AC-14: UNVERIFIED（未実施。spec `accepted`、plan `draft` Revision 5のまま、`DESIGN.md` / `CONTEXT.md` / `requirements.md` 未更新。device-verified修正のplan/spec revision entryも無い）。
- AC-15: PARTIAL（JVM wiring oracleまで。TalkBack runtime確認なし。初回と同じ）。
- AC-16: PARTIAL（`NoChanges` 到達不能はJVM、stale経路は実DB。`RolledBack` / `Recovered` / `Unresolved` / `RecoveryFailed` の表示分岐のtestは依然なし）。

### 前回NO-GO指摘の解消状況

- 初回Findings 1（**BLOCKER: androidTestコンパイル不能・instrumentation evidenceゼロ**）: **解消**。`d1c7386ba6` が3エラー（page ref narrowing、Long/Int、`FaultInjector by NOOP`）と13 fakeの `inspectCapture()` stubを修正。ローカル `compileLawnWithQuickstepGithubDebugAndroidTestKotlin` は BUILD SUCCESSFUL、CI run 36431255830 の全instrumentation laneがコンパイルに成功。shared-writer laneは75 testsを実行し success（class listに `app.lawnchair.homeedit.EditSurfaceApplyInstrumentationTest` を含む）。manual-org laneは151 tests中150 pass。
- 初回Findings 2（AC-5/AC-16のprotocol-level oracle不足）: **残存**（成功経路のprotocol-level oracleと `handleApplyResult` variant表示testは無い）。
- 初回Findings 3（結合点7削除passの実行oracleなし）: **部分的に解消**。物理DELETE（`bId` 行の削除）と注入rollbackのexact pre-stateが実DBで実行される。一方、削除文のfault hook（DELETE段への注入）は無く、成功時の「非選択行不変」の明示assertも無い（A7 exact検証に委ねる）。
- 初回Findings 4（AC-13 FAIL・PR本文未記録）: **残存**（mainでも同じ9 net-new unassigned path。PR本文は未記録のまま）。
- 初回Findings 5（PR本文・plan・specの鮮度、AC-14）: **残存・悪化**。device-verified修正のplan revision entryなし、plan Designのhub同定誤り、PR本文は40行のまま（「PR段階で実施する残りの検証」が未更新）。device verificationはcommit messageのみでPR本文・コメントに記録されていない。
- 初回Findings 6/7（`inspectCapture` のlease、AC-15 wiring oracle）: 情報のまま（変更なし）。

### e6190ef5bcの契約整合（hub行移動とスレッド変更）

- (a) **hub行移動（`ManualOrganizationPreferences.kt` −9 → `OrganizerHubPreferences.kt` +10）: AC-1と整合**。移動先はhubのstart CTA直後・diagnostics直前の `ClickablePreference`（零書込みnavigation。`HomeEditSurfaceActivity.start` のseamは不変）。旧位置（run面の `manual_organization_start` 近傍）はT-07 preambleのrun面であり、spec AC-1/ADR-0014の入口「Organizer hub」ではなかった。hub traversal testは同rowをDPAD順に含めて `maxPresses=14` とし（`2370bdec31`）、manual-org laneでこのtestはpass（laneの失敗はissue372の1件のみ。method単位の成功ログは無い）。
- (b) **スレッド変更: 適用経路の契約と矛盾しない**。変更は `HomeEditSurfaceActivity.reloadCapture()` / `confirm()` の `Executors.MODEL_EXECUTOR.execute` → 専用single-thread executor（`homeedit-surface`）の2箇所のみで、呼び出しseam・契約型・`ApplyProtocol`・recovery経路・結合点7は不変。根拠: `OrganizerModelReloadAdapter.requestAndWaitWithSnapshot` はblocking waitで、docの明示前提は「Blocking wait occurs off MODEL_EXECUTOR」。相関reloadのLoaderTaskはMODEL_EXECUTORへpostされるため、MODEL_EXECUTOR上で待つと自己待ち→`TIMEOUT`→（`ApplyProtocol.kt:489` 経由）`RecoveryFailed` となる（commit messageの実機観測と一致）。`ModelProjectionCodec` のMODEL_EXECUTOR confinementは loader完了lambda内の話で移動していない。ADR-0013は「視覚的編集画面の一括確定は対象外」と明記し、ADR-0014はスレッドを規定しない。実機確認はcommit message記録（A8 APPLY_VERIFIED）で、本auditは再実行していない。
- 残差（低）: `surfaceExecutor` は `onDestroy()` でshutdownされず、既定thread factoryの非daemon threadがactivity instanceごとに残る（Findings 6）。

## Executed test surface

（追跡監査。head `e6190ef5bc`）監査者が実際に実行したcommandと観測結果:

```
git rev-parse HEAD
  -> e6190ef5bc72708f15b9b7eacdae322f8539dde5

git log --oneline c74e305ff0..HEAD
  -> e6190ef5bc fix(449): device-verified apply — hub row placement and correlated-reload threading
     d65d9f3514 / 1a5a2c4895 / 2370bdec31 / af6b527b38 / 9d8caf538a / 0b1988c122 / d1c7386ba6

git diff c74e305ff0..HEAD --shortstat
  -> 17 files changed, 305 insertions(+), 36 deletions(-)

git diff main...HEAD --shortstat
  -> 54 files changed, 5140 insertions(+), 5 deletions(-)

git show e6190ef5bc --stat
  -> 4 files changed, 25 insertions(+), 30 deletions(-)
     （HomeEditSurfaceActivity.kt +15-3 / ManualOrganizationPreferences.kt -9 /
       OrganizerHubPreferences.kt +10 / OrganizerHubPreferencesInstrumentationTest.kt +3-21）

./gradlew testLawnWithQuickstepGithubDebugUnitTest -Pnunu.excludeAiExchangeUnitTests=true --tests 'app.lawnchair.homeedit.*'
  -> BUILD SUCCESSFUL in 31s。build/test-results/.../TEST-app.lawnchair.homeedit.*.xml = 99 tests, 0 failures, 0 errors, 0 skipped
     （A11yDescription 14 / LockGate 3 / PlanBuilder 11 / Projection 14 / SessionPlanner 15 /
       SyntheticIds 3 / HomeEditAcceptanceOracles 4 / HomeEditPlanner 24 / HomeEditStage2Validator 6 /
       HomeEditUndoLog 3 / SourcePlacementSnapshot 2）

./gradlew compileLawnWithQuickstepGithubDebugAndroidTestKotlin
  -> BUILD SUCCESSFUL in 5s（387 actionable tasks: 4 executed, 383 up-to-date）。
     初回NO-GOのBLOCKER（source setコンパイル不能）の解消をローカルで再確認。

python3 tools/repo-contract/validate_repo_contract.py
  -> exit 1。finding 2件（refocus-drafts/adr/0014-edit-surface.md:15、refocus-drafts/open-issue-dispositions.md:4）。
     `git ls-files refocus-drafts` は0件、`git check-ignore -v` は `.gitignore:24:refocus-drafts/` に一致。
     未追跡・ignore済みのローカルdraftで本PRのdiff外（初回監査の既知2件と同一）。

python3 tools/repo-contract/validate_ci_portfolio.py
  -> "CI portfolio validation OK"（exit 0）

python3 tools/repo-contract/measure_upstream_patch_surface.py --target main --enforce-baseline
  -> exit 1（build.gradle の pinned-content 不一致 + unassigned path 25件。mainでもFAIL）

python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline
  -> exit 1。mainとの差分（本PRのnet new unassigned）は初回と同一の9件:
     lawnchair/AndroidManifest.xml, homeedit/EditSurface{State,Projection,SessionPlanner,PlanBuilder}.kt,
     homeedit/HomeEditSurfaceAccess.kt, homeedit/ui/{EditSurfaceScreen,HomeEditSurfaceActivity}.kt,
     ui/popup/LauncherOptionsPopup.kt

gh pr checks 476 -R nunu1733/NunuLauncher
  -> final-status fail / high-risk-evidence fail / organizer-instrumentation-manual-organization-ui-tests fail、
     他14 checks pass（changes, validate-repo-contract, check-style, build-debug-apk, organizer-unit-tests,
     他のinstrumentation lane 9本）

gh run view 36431255830 -R nunu1733/NunuLauncher --json jobs
  -> completed / failure / e6190ef5bc72708f15b9b7eacdae322f8539dde5 / pull_request / CI。
     16 job。final-status以外の15 job中14 success、failureは manual-organization-ui-tests のみ + final-status。

gh run view --job 108990685196 -R nunu1733/NunuLauncher --log-failed
  -> manual-organization-ui-tests: emulator-5554 - 16 で 151 tests / 1 failed（150 pass）。
     app.lawnchair.ui.preferences.OrganizerDiagnosticsRouteInstrumentationTest >
     issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute[emulator-5554 - 16] FAILED
       androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 5000 ms

gh run view --job 108990749462 -R nunu1733/NunuLauncher --log
  -> shared-writer lane class list に app.lawnchair.homeedit.EditSurfaceApplyInstrumentationTest を含む。
     "Starting 75 tests on emulator-5554 - 16" → BUILD SUCCESSFUL in 8m 17s

gh run view 36367993676 / 36380059358 -R nunu1733/NunuLauncher --json jobs（main push runs）
  -> main f35ff4494f: 失敗 = category-override, manual-organization-ui, final-status
     main c5a7840b88: 失敗 = manual-organization-ui, final-status
     いずれの manual-organization-ui も上記と同一テスト・同一 ComposeTimeoutException signature。

gh issue view 477 -R nunu1733/NunuLauncher
  -> state OPEN「manual-organization-ui lane: issue372テストが恒常的に赤（mainでも同失敗）— flake分類と対応」
     （2026-09-28作成。main 36367993676/36380059358 と本PR run 36431255830 の失敗箇所を記載）

gh run view --job 108957721730 -R nunu1733/NunuLauncher --log（high-risk gate run 36431255694）
  -> FAIL: (1) audited Head SHA以後の非docs変更（旧recordのheadが c74e305ff0 のため）、
     (2) CI run 36402204992 が成功runでない、(3) 成功した pull_request run が参照されていない
```

補足（honesty）: 本追跡監査も instrumentation test を**ローカル実行していない**（emulator未使用）。確認したのは (i) ローカルの androidTest コンパイル成功、(ii) CI laneの結果とclass list、(iii) 失敗jobのlogである。実機（Pixel 9a）の動作確認は実装sessionのcommit message記録であり、本auditは再実行していない。エミュレータ計測（AC-11）、ベンチマーク（AC-12）、TalkBack（AC-15）、スクリーンショット（AC-1/2/3）も未実施。

## Findings

（追跡監査。head `e6190ef5bc`）

1. **解消: androidTest source set コンパイル不能（初回BLOCKER）**。
   `d1c7386ba6` が本PRの新規test3エラーと13 fakeの `inspectCapture()` stubを修正し、ローカル `compileLawnWithQuickstepGithubDebugAndroidTestKotlin` は BUILD SUCCESSFUL。CI run 36431255830 では全instrumentation laneがコンパイル・実行に到達し、shared-writer laneは75 tests（`EditSurfaceApplyInstrumentationTest` の3件を含む）で success、manual-org laneは151 tests中150 pass。初回の「instrumentation evidenceゼロ」は解消した。

2. **中（残差）: 成功経路のprotocol-level oracleと `ApplyResult` variant表示testが依然無い。**
   新testは `prepareApplyWriteSet` + `applyWriteSet` の adapter直駆動（staleのみ `LayoutApplicationModule.apply` 経由）で、specのTest oracleが求める (i) protocol経由の `Applied`（recovery point 1個・相関reload・A7 exact検証）、(ii) `RolledBack` / `Recovered` のpre-state表示、(iii) `Unresolved` / `RecoveryFailed` のfail-closed表示、の実行oracleにはならない（初回Findings 2の継続）。削除passの実DB oracles（物理削除・rollback exact pre-state）は追加されたが、成功時の「非選択行不変」の明示assertとDELETE段のfault注入は無い（初回Findings 3の残差）。

3. **中: AC-13はFAILのままで、PR本文への結果記録も未実施。**
   `--target HEAD --enforce-baseline` は exit 1、main も exit 1（`build.gradle` pinned-contentは本PR非因果の既存事項）。本PRのnet new unassigned pathは初回と同一の9件（`AndroidManifest.xml`、homeeditの新規7ファイル、`LauncherOptionsPopup.kt`）。`src/` 変更0のためNFR-010の「src側変更」には当たらないが、bridge ownershipへの分類は未了で、PR本文に結果が無い。本recordに結果を記載したので、PR本文へ転記することを推奨する。

4. **中: `final-status` は赤。赤の残る唯一の内容jobは main でも再現する既存flakeである（owner判断事項）。**
   run 36431255830 で failure の内容jobは `organizer-instrumentation-manual-organization-ui-tests` のみ（151 tests中150 pass）。失敗は `OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` の `ComposeTimeoutException`（5000 ms）1件で、main `f35ff4494f`（run 36367993676）および main `c5a7840b88`（run 36380059358、本PRのbase）でも同一テスト・同一signatureで失敗する。tracking issue #477 が作成済み。**この赤をblockerとしてmergeを止めるか、既存flakeとしてfinal-status赤のままmergeするかは owner の判断事項であり、本auditは判断を代行しない。** 機械gate（high-risk-evidence）は green run を要求するため、ownerが例外を認めない限り成立しない。

5. **低: plan / spec / PR本文の鮮度と plan Design のhub同定不一致。**
   plan Designは Organizer hub を `ManualOrganizationPreferences.kt` と記載しているが、実コードのhubは `OrganizerHubPreferences.kt`（start CTA・diagnostics・D-01/D-02）。`e6190ef5bc` は実コードに合わせたがplan文書は未修正。planは Revision 5 のまま（device-verified修正のrevision entryなし）、spec Change historyは Revision 4 止まり、PR本文は40行で「PR段階で実施する残りの検証」が未更新（device verificationはcommit messageのみ）。AC-14の正本更新（spec `implemented`、`DESIGN.md` / `CONTEXT.md` / `requirements.md`）も未実施。

6. **低（新規）: `surfaceExecutor` がshutdownされない。**
   `HomeEditSurfaceActivity` は `onDestroy()` でexecutorをshutdownしない。`Executors.newSingleThreadExecutor` の既定thread factoryは非daemon threadを作るため、編集画面を開くたびにidle thread（`homeedit-surface`）が1本残り、activity再生成でも同様になる。検証結果には影響しないが、ライフサイクル上の残差として記録する（`onDestroy` でのshutdownを推奨）。

7. **情報: high-risk gate run 36431255694 の失敗理由**は (1) audited Head SHA以後の非docs変更（旧recordが `c74e305ff0` を指していたため。本record更新で解消）、(2) 参照CI runが失敗、(3) 成功した pull_request run が無い、の3点。CIが赤の間は (2)(3) が残るのが正しい挙動である。

## 結論（merge可否の判断材料 — 追跡監査）

- 初回NO-GOの直接要因（androidTestコンパイル不能・instrumentation evidenceゼロ）は解消した。新headでは: androidTestコンパイル成功（ローカル+CI全lane）、新規実DB test 3件がshared-writer laneで実行されsuccess、hub entry rowとhub traversal testがmanual-org laneでpass（laneの唯一の失敗は既存flake）、JVM 99 tests green。
- `e6190ef5bc` の2修正は、AC-1（Organizer hub row）と適用経路のスレッド契約（blocking waitはMODEL_EXECUTOR off）に整合し、seam・契約型・`ApplyProtocol`・ADR-0013/0014の対象外の局所変更である。初回Findings 2/3の残差（protocol-level成功oracle・variant表示test）は残るが、新たな回帰は本auditの範囲では見つかっていない。
- しかし **CI `final-status` は赤**であり、高リスク独立エビデンスの機械要件（検証対象commit上で `final-status` が実際に成功していること + 独立audit record）のうち前者を満たさない。本recordを更新しても、green runが存在しない以上 high-risk-evidence gate は成立しない（正しい挙動）。したがって **現head `e6190ef5bc` は現行gate定義ではmerge不可（NO-GO）**。
- 赤の内訳は main でも再現する既存flake 1件（#477）であり、本PRのdiffに帰因する失敗は0件。**(a) final-status赤のままmergeすることの可否、(b) mainでも赤の既存flakeをmerge gateのblockerと扱うかどうかは、いずれも owner の判断事項である（本auditは判断を代行しない）。**
- ownerが (a)(b) を許容する場合の条件付きGO条件: (1) AC-13の実行結果（本record記載のnet-new 9 unassigned pathsとmain baseline超過）をPR本文へ記録する、(2) plan Designのhub同定とdevice-verified修正のplan/spec Change historyを更新する（AC-14残作業の少なくともplan分）、(3) #477の対応方針（quarantine / fix / waiver）をquality-strategyの分類手順に沿って確定する。
- owner確認事項（エミュレータ操作・スクリーンショット、TalkBack、ベンチマーク、NFR-013実測、実機確認）は本auditでは代替していない。実機確認の記録はcommit `e6190ef5bc` のmessageにある。

## 追跡監査2（head `0e0ffbd64f`、rebase後、2026-09-29）

**結論: GO（現行gate定義を満たす）。**

- **rebase**: main `2ac104aa93`（#478: manual-organization-ui laneのissue372恒常赤対応＝#477のCI改善）へrebaseし、force-push。新head `0e0ffbd64f`。
- **内容ドリフトの範囲**: 監査済みhead `bc32e0311d` → `0e0ffbd64f` の差分はmain由来の3ファイルのみ（#477/#479の `OrganizerDiagnosticsRouteInstrumentationTest` へのtouch-oracle quarantineとrunner argument、`run-manual-organization-ui-instrumentation.sh`、`ci-test-portfolio.md` の記録更新）。本PR自身のdiff（`origin/main..HEAD`、54ファイル）はreview・監査済み内容から変化なし。
- **quarantineの妥当性**: #479のquarantineは、本監査のFindings 4（owner判断事項）が指摘していた既存flake（#477）への実装側の対応であり、touch oracle 1 testをrunner argument `nunuQuarantineIssue479TouchOracle=true` でskipする形。signature・証拠・診断はlane内に維持され、local/manual実行ではoracle観測可能。quality-strategyの分類手順に沿った措置であることを確認。
- **CI run 36499580878（head `0e0ffbd64f`、pull_request）: 15 jobすべてpass、`final-status` PASS。** manual-organization-ui lane（18m11s、quarantine適用後）を含む全instrumentation laneがgreen。初回・追跡監査1で赤だった内容jobは解消した。
- **機械gate**: `final-status` が本commit上で成功したため、高リスク独立エビデンス要件の前者（検証対象commit上でのmerge gate成功）を満たす。後者は本record（この追跡監査2の更新を含む）。
- **Findings残差の更新**:
  - Findings 2/3（protocol-level成功oracle残差、AC-13未登録path）: 変化なし。AC-13は `--target HEAD` で引き続きbaseline超過（本PRのnet-new 9〜12 unassigned paths + 既存 `build.gradle` pinned-content）。PR本文への結果記録を推奨（前回どおり）。
  - Findings 4（final-status赤・owner判断事項）: **解消**（quarantineによりlaneがgreen、final-status pass）。
  - Findings 5（plan/spec鮮度）: 部分解消（plan Revision 6 = `bc32e0311d` でhub同定とdevice-verified修正を記録）。AC-14の正本更新（spec `implemented`、`DESIGN.md` / `CONTEXT.md` / `requirements.md`）は未実施のまま。
  - Findings 6（`surfaceExecutor` 非shutdown）: 変化なし（低。activity破棄でもprocess終了時には回収される）。
- **実機検証の記録**: head `e6190ef5bc` のcommit message（Pixel 9a、select→外す→確定→A4→A6→A8、1復元点、行削除、相関reload反映）。rebaseによりcommit SHAは変わったが内容は同一。
- **残るowner確認事項**: TalkBack読み上げ、ベンチマーク実測（AC-12）、NFR-013実測、実機での入口2経路の表示確認（エミュレータでの動作は実装sessionが確認済み）。

## 前回監査（head `c74e305ff0`、初回、NO-GO）— 記録保持

以下は初回監査の全文である（見出しレベルと節名のみ、機械検査のfirst-occurrenceを追跡監査側に保つため調整した。本文は当時のまま）。

> Status: **NO-GO（merge gate red）**。androidTest source set がコンパイルできず、instrumentation evidence が存在しない。green な `final-status` run は本branchに存在しない。
> Audit date: 2026-09-28

- Auditor: 独立session（general-purpose subagent）。実装sessionではなく、本PRのdiff作成・Phase 1/2 review・検証実行に関与していない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/476
- Head SHA: c74e305ff0a76f4a53ed55ee6fdc80d7830da3df
- CI run（初回当時）: https://github.com/nunu1733/NunuLauncher/actions/runs/36402204992（head `c74e305ff0`、**failure**。`organizer-unit-tests` / `check-style` / `build-debug-apk` / `validate-repo-contract` は success だが、全11 instrumentation lane が `:compileLawnWithQuickstepGithubDebugAndroidTestKotlin` のコンパイルエラーで failure → `final-status` failure）
- High-risk gate run: https://github.com/nunu1733/NunuLauncher/actions/runs/36402205074（failure。理由は「no docs/assessment/pr-476-<slug>.md audit record」。本record追加後も、上記CIが赤のためgateは成立しない）
- Criteria: [spec 449](../../specs/449-multi-select-surface/spec.md)（accepted）の AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11, AC-12, AC-13, AC-14, AC-15, AC-16 / FR-019 / NFR-013 / NFR-014
- Criteria: [操作面のADR](../../docs/adr/0014-edit-surface.md) の ADR-0014（accepted。操作面の決定 D-014。本PRは案B）
- Criteria: [直接編集の書込み契約ADR](../../docs/adr/0013-direct-edit-write-contract.md) の ADR-0013（accepted。#448が所有し、本PRは非干渉を確認するのみ）

### Scope（初回監査）

- 対象diff: base `main`（`c5a7840b88`）.. head `c74e305ff0` の全diff（41 files, +4870/-4）。spec（264行）と plan Revision 5（248行）を含む。`src/`（上流Launcher3）の変更は0ファイル、schema/migration・dependency追加・permission追加も0。差分は `lawnchair/`（fork module）・`tests/`・`specs/`・CI文書のみ。
- plan Change setとの一致: 下記「Criteria check」の「Scope整合」を参照。実装本体・test・CI routing・strings・manifestはすべて一致し、唯一 AC-14 の正本更新（spec `implemented`、`DESIGN.md` / `CONTEXT.md` / `docs/product/requirements.md`）が未実施（本PRは `Refs #449` を維持し、完了PRへ委ねる記述）。
- 本auditが追跡した経路:
  - 入口: `LauncherOptionsPopup.kt`（`edit_surface` option。`lockHomeScreen`時は `edit_mode` / `widgets` と同じく非表示）と `ManualOrganizationPreferences.kt` のhub row → `HomeEditSurfaceActivity` → `HomeEditSurfaceAccess`。
  - 読取り: `LayoutApplicationModule.inspectCapture()`（readinessGate → `ordinaryMutex.tryAcquire` → `writer.captureCurrent("edit-surface-inspect")`。失敗・未ready・競合は `null` のfail-closed）。
  - セッション計画: 純粋4層 `EditSurfaceState` / `EditSurfaceProjection` / `EditSurfaceSessionPlanner` / `EditSurfacePlanBuilder`（`#448` の `HomeEditPlanner.plan` を1アイテムずつ共有）。
  - 適用: `EditSurfacePlanBuilder` → `HomeEditSurfaceAccess.apply` → 既存 `LayoutApplicationModule.applyWithRunId` → `ApplyProtocol`（A2再capture検証 → checkpoint 1個 → 1 transaction → 相関reload → A7） → `LauncherLayoutAdapter.applyWriteSet`。本PRはこの通常branchへ「intendedManifestに欠落する行の削除pass」（plan 結合点7）を1段追加する。
- 対象外: ベンチマーク実測（AC-12）、エミュレータ/実機スクリーンショット（AC-1/2/3）、TalkBack（AC-15 runtime）、NFR-013の実測（AC-11）。いずれもPR本文で「PR段階で実施」または owner 確認とされており、本auditは再実施していない（後述「結論」のowner確認事項）。

### Criteria check（初回監査）

総合: **NO-GO**。JVMの純粋層と書込み契約のコードは設計どおりで、単体oracleは揃っている。しかし instrumentation の evidence が**1つも実行されていない**（source setがコンパイル不能）。specの Test oracle 表が instrumentation に割り当てた AC-5 / AC-6 / AC-7 / AC-8 / AC-10 / AC-16 は未達であり、AC-13 の結果記録も未実施、AC-14 の正本更新も未実施である。

- AC-1 / AC-2 / AC-3: **UNVERIFIED（owner evidence）**。入口2経路（`LauncherOptionsPopup` / hub row）と `lockHomeScreen` 非表示のコードは確認。図投影（`EditSurfaceProjection`）・選択可否述語（DESKTOPのAPPLICATION/DEEP_SHORTCUTのみ、フォルダ自身/widget/app pair/dock/ロック中は非選択）はJVM test（`EditSurfaceProjectionTest` 14件）があるが、エミュレータスクリーンショットは未取得。
- AC-4 / AC-9: **PASS（JVM）**。`EditSurfaceSessionPlannerTest` 15件・`EditSurfacePlanBuilderTest` 11件・`HomeEditPlannerTest` 24件。all-or-nothing（1個でも拒否ならセッション不変）、視覚順の決定性、touched再操作拒否、リセットのみで戻る、空セッション確定不可（`confirmGate`）をコードとtestで確認。
- AC-5: **PARTIAL / instrumentation未実行（NO-GO要因）**。適用プロトコル（A2再captureの `STALE_REVISION` / `EXACT_PRECONDITION_FAILED`、`LOCK_STATE_UNAVAILABLE`、checkpoint、1 transaction、相関reload、A7）は既存実装で、本PRは変更しない（削除passのみ追加）。しかし spec の Test oracle が求める「編集セッション→適用計画→applyの統合test（recovery point 1個、相関reload、選択外不変）」は head で実行不能（Findings 1）。新規 `EditSurfaceApplyInstrumentationTest` は `applyWriteSet` を直接駆動し、protocol経由の `Applied` / recovery point / 相関reload を検証しない（Findings 2）。
- AC-6 / AC-7 / AC-8: **PARTIAL（JVMのみ）**。stale時の零書込み・セッション破棄・開き直しは `HomeEditSurfaceActivity.handleApplyResult` に実装され、`LOCK_STATE_UNAVAILABLE` → `edit_surface_error_lock_unknown` の明示分岐、UNKNOWN行のconfirm gate（`EditSurfaceSessionPlanner.confirmGate`）、リセット/キャンセルの零書込み（書込みはconfirm経由のみ）をコードで確認。`EditSurfaceLockGateTest` 3件がgateとreason mappingを固定。instrumentationのstale/排他/blocked零書込みtestは未実行（Findings 1）。
- AC-10: **UNVERIFIED**。実DB test（`EditSurfaceApplyInstrumentationTest`）がコンパイル不能で一度も実行されていない。
- AC-11: **UNVERIFIED**。100ms/3秒のエミュレータ計測なし。
- AC-12: **UNVERIFIED**。ベンチマーク§7手順の実行記録なし（会計式の計算のみspecに記載）。
- AC-13: **FAIL（mainでもFAIL、記録は未実施）**。`measure_upstream_patch_surface.py --target HEAD --enforce-baseline` は exit 1。差分は Findings 4。PR本文は「PR段階で実施」のままで結果未記録。
- AC-14: **UNVERIFIED（未実施）**。spec statusは `accepted` のまま、`DESIGN.md` / `CONTEXT.md` / `docs/product/requirements.md` は本diffに含まれない。PRは `Refs #449` を維持しており、完了PRが所有する設計（AGENTS.md完了条件はこの限りで未充足）。
- AC-15: **PARTIAL（source/JVMまで）**。`edit_surface_*` 31文字列が en/ja 両方で定義・非空（`HomeEditAcceptanceOraclesTest`）。純descriptor `editSurfaceItemSemantics` + `editSurfaceItemDescription` がtitle/位置/選択状態/選択不可理由/ロック理由を供給し、`EditSurfaceA11yDescriptionTest` 14件が値を固定。`DiagramItemView` の3プロパティ代入を production source から読んで検証する wiring oracle もあるが、Compose semantics の実実行・エミュレータTalkBackの読み上げ記録は無い（Findings 7）。
- AC-16: **PARTIAL**。`NoChanges` 到達不能は `EditSurfacePlanBuilder` の「空差分拒否」（`intendedState == capture && actions全Preserve` を `Inconsistent` にする。`ApplyProtocol.isNoChange` と同条件）と `EditSurfacePlanBuilderTest` で確認。他のvariant表示は `HomeEditSurfaceActivity.handleApplyResult` に分岐があるが、同関数のtestはJVM/instrumentationとも無く、`RolledBack` / `Recovered` / `Unresolved` / `RecoveryFailed` のfail-closed表示は実行検証されていない（Findings 2）。

#### 結合点7（削除pass）のコード検証

- (a) **A2 exact precondition検証済みcaptureから派生: PASS**。`applyWriteSet` は同一transaction内で `before = capture()` を取り直し、`before.revision != writeSet.sourceRevision` → `STALE_REVISION`、`preconditionsHold(before, writeSet)` → `EXACT_PRECONDITION_FAILED` を書込み前に判定する（`LauncherLayoutAdapter.kt:301-313`）。削除集合は `before.manifest.rows` のうち `writeSet.intendedManifest.rows` に無い行のみ（`:379-382`）。`RowManifestCodec.capture` は favorites全行を1行=1canonical itemで投影し（`RowManifestCodec.kt:69-115`）、revisionは全itemを含むdigest（`RevisionCalculator.revisionOf`）なので、削除対象はA2/A5で一致検証済みのpre-state行に限られる。
- (b) **recovery branch非干渉: PASS**。削除passは `writeSet.recoveryActions.isEmpty()` の `else` branch内のみで、recovery branch（`RecoveryAction.DeleteRow` 等）と `prepareRecoveryWriteSet` は変更なし（本diffのadapter変更は+11行のみ）。
- (c) **1 transaction: PASS**。`tx = controller.newTransaction(lease.token)` は関数冒頭で開始され、削除は `tx.commit()` の前、`catch` は `tx.close()`（commit未到達ならrollback）→ `ApplyTxOutcome.Failed`。
- 残差: 削除文には `faults.beforeLauncherWrite/afterLauncherWrite` のhookが無く、DELETE失敗の注入testが書けない（transaction rollback自体は効く）。またこのpassは edit surface 専用ではなく**通常branchの全apply（organizer runを含む）に適用される**共有経路の挙動変更である。従来は intended欠落行が残りA7 exact検証が失敗して自動復旧に至っていたため、「成功していた挙動を壊す」ものではないが、実行oracleが現headに存在しない（Findings 2/3）。

#### Scope整合（plan Change setとの突合）

| plan Change set | head | 結果 |
|---|---|---|
| `HomeEditModel.kt` / `HomeEditPlanner.kt`（`CreateFolderAt` variant追加・additive） | +25 / +51 | 一致（既存4 intent不変。`HomeEditUndoLog` の exhaustive whenも総和維持） |
| `EditSurface{State,Projection,SessionPlanner,PlanBuilder}.kt` 新設（概算+600〜900） | +1033 | 一致（概算超過は許容） |
| `HomeEditSurfaceAccess.kt` 新設 | +57 | 一致 |
| `ui/HomeEditSurfaceActivity.kt` / `EditSurfaceScreen.kt`（概算+800〜1,200） | +397 / +688 | 一致 |
| `LayoutApplicationModule.inspectCapture()` | +25 | 一致 |
| `LauncherLayoutAdapter` 削除pass（結合点7） | +11 | 一致 |
| `ManualOrganizationRun` accessor | +31 | 一致 |
| `PlanningResult.kt` `FromUserCreation` + resolver mapping | +9 / +6 | 一致 |
| `LauncherOptionsPopup.kt` option | +22/-1 | 一致 |
| Organizer hub row | +9 | 一致 |
| strings en/ja | 各+33 | 一致 |
| JVM test | 8ファイル +1138 | 一致 |
| instrumentation test + lane class list | +347 / ci.yml +7-1 | 一致（ただしコンパイル不能。Findings 1） |
| CI portfolio文書/map | 一致 | `validate_ci_portfolio.py` OK |
| spec status / CONTEXT / DESIGN / requirements | なし | **未実施（AC-14）** |
| （Diff boundary記載のmanifest） | `AndroidManifest.xml` +9（`exported=false`） | 一致 |

### Executed test surface（初回監査）

監査者が実際に実行したcommandと観測結果:

```
git rev-parse HEAD
  -> c74e305ff0a76f4a53ed55ee6fdc80d7830da3df

git log --oneline -8
  -> c74e305ff0 fix(449): review round 5 — wiring oracle and default-label restoration
     d020fb4aca / 2a7617f34b / 6e24db85cd / 3a7f50a0cf / 42e1c6d1d4 / 7f2d13e22d / 0cdb2b0d88

git diff main...HEAD --shortstat
  -> 41 files changed, 4870 insertions(+), 4 deletions(-)

git diff main...HEAD --numstat -- src/
  -> （空。上流Launcher3の変更0）

./gradlew testLawnWithQuickstepGithubDebugUnitTest -Pnunu.excludeAiExchangeUnitTests=true --tests 'app.lawnchair.homeedit.*'
  -> BUILD SUCCESSFUL in 30s。build/test-results/.../TEST-app.lawnchair.homeedit.*.xml = 99 tests, 0 failures, 0 errors, 0 skipped
     （A11yDescription 14 / LockGate 3 / PlanBuilder 11 / Projection 14 / SessionPlanner 15 /
       SyntheticIds 3 / HomeEditAcceptanceOracles 4 / HomeEditPlanner 24 / HomeEditStage2Validator 6 /
       HomeEditUndoLog 3 / SourcePlacementSnapshot 2）

./gradlew compileLawnWithQuickstepGithubDebugAndroidTestKotlin
  -> BUILD FAILED（exit 1）。16 errors（詳細は Findings 1。CI run 36402204992 と同一signature）

python3 tools/repo-contract/validate_repo_contract.py
  -> exit 1。finding 2件（refocus-drafts/adr/0014-edit-surface.md:15、refocus-drafts/open-issue-dispositions.md:4）。
     いずれも `git check-ignore` で除外済み・`git ls-files` 0件の未追跡ローカルdraftで本PRのdiff外（既知2件）。

python3 tools/repo-contract/validate_ci_portfolio.py
  -> "CI portfolio validation OK"（exit 0）

python3 tools/repo-contract/measure_upstream_patch_surface.py --verify
  -> PASS（recorded baseline 47 counted files, +3993/-1017 を完全再現）

python3 tools/repo-contract/measure_upstream_patch_surface.py --target main --enforce-baseline
  -> exit 1（build.gradleのpinned-content不一致 + unassigned path 25件）。mainでもFAIL

python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline
  -> exit 1。mainとの差分（本PRのnet new）は unassigned path 9件:
     lawnchair/AndroidManifest.xml, homeedit/EditSurface{State,Projection,SessionPlanner,PlanBuilder}.kt,
     homeedit/HomeEditSurfaceAccess.kt, homeedit/ui/{EditSurfaceScreen,HomeEditSurfaceActivity}.kt,
     ui/popup/LauncherOptionsPopup.kt

gh run view 36402204992 -R nunu1733/NunuLauncher --json status,conclusion,headSha,event,workflowName
  -> completed / failure / c74e305ff0a76f4a53ed55ee6fdc80d7830da3df / pull_request / CI
     job: changes, validate-repo-contract, check-style, build-debug-apk, organizer-unit-tests = success
          organizer-instrumentation-{shared-writer,db-migration,restore-capture,production-input,
          reservation-recovery,manual-organization-ui,category-override,exchange-import-ui,
          onboarding-proposal,method-choice-journey} = failure、final-status = failure

gh api repos/nunu1733/NunuLauncher/actions/jobs/108862724916/logs | grep '^e: '
  -> e: .../EditSurfaceApplyInstrumentationTest.kt:76:139 Unresolved reference 'pageId'.
     e: .../EditSurfaceApplyInstrumentationTest.kt:153:37 Argument type mismatch: actual type is 'Long', but 'Int' was expected.
     e: .../EditSurfaceApplyInstrumentationTest.kt:190:23 Class '<anonymous>' is not abstract and does not implement abstract members
        （FaultInjector の未実装メンバー列挙: beforeRecoveryLifecycleCommit 等）
     ほか12ファイル（ManualOrganizationApplication の新規 abstract member `inspectCapture()` 未実装）

gh run view 36396548492 -R nunu1733/NunuLauncher --json jobs
  -> head 42e1c6d1d4 のrunも final-status failure。shared-writer job 108844531505 も
     ':compileLawnWithQuickstepGithubDebugAndroidTestKotlin' FAILED（コンパイル不能は実装commit以降一貫）

gh run view 36402205074 -R nunu1733/NunuLauncher --log-failed
  -> high-risk-evidence: "FAIL: no docs/assessment/pr-476-<slug>.md audit record for this PR"
```

補足（honesty）: 監査者は instrumentation test を**実行できていない**（source setがコンパイル不能）。エミュレータ・実機TalkBack・ベンチマーク・NFR-013実測も実施していない。JVM oracle は上記のとおり再実行して99件成功を確認した。

### Findings（初回監査）

1. **BLOCKER: androidTest source set がコンパイルできず、instrumentation evidence が存在しない（merge gate red）。**
   head `c74e305ff0` で `:compileLawnWithQuickstepGithubDebugAndroidTestKotlin` が16 errorsで失敗する（ローカル再現・CI run 36402204992 の全instrumentation laneと同一）。内訳:
   - 本PRの新規 `tests/organizer-instrumentation/app/lawnchair/homeedit/EditSurfaceApplyInstrumentationTest.kt` の3件: `:76:139` `p.page.pageId`（`ApplicationPageRef` に `pageId` は無い）、`:153:37` Long→Int、`:190:23` anonymous `FaultInjector` が `beforeRecoveryLifecycleCommit` / `afterRecoveryLifecycleCommit` / `restartBoundary` 等を未実装。
   - `ManualOrganizationApplication` へ追加した `inspectCapture()` を既存fake 13箇所が未実装: `ManualOrganizationPreferencesInstrumentationTest`、`MissingAppSelectionInstrumentationTest`、`OnboardingOrganizationProposalInstrumentationTest`、`OrganizerHubPreferencesInstrumentationTest`、`StrategyPickerInstrumentationTest`、`StrategyT05ProductionNavigationTest`、`StrategyT05VisualEvidenceTest`（2箇所）、`UsageAccessJitInstrumentationTest`、`ExchangeImportSuccessInstrumentationTest`、`ExchangeImportSurfaceInstrumentationTest`、`MethodChoiceConnectedJourneyInstrumentationTest`、`OrganizerDiagnosticsRouteInstrumentationTest`（同一エラーは head `42e1c6d1d4` の run 36396548492 でも発生しており、実装commit以降一貫）。
   - 帰結: spec Test oracle が instrumentation に割り当てた AC-5/6/7/8/10/16 は未達成。結合点7の削除passを含む新testは一度もコンパイル・実行されていない。`final-status` は赤で、高リスク要件（検証対象commit上のgreen）を満たさない。JVM laneだけが緑のため、PR本文・reviewの「検証済み」記録は instrumentation について成立していない。
   - 必要な対応: 新testの3エラー修正と13 fakeへの `override fun inspectCapture(): CapturedSnapshot? = null`（fail-closed）追加 → shared-writer lane（および全instrumentation lane）のgreen確認とrun URLの記録 → **再audit**（headが変わるため）。

2. **高（evidence整合）: AC-5 / AC-16 の instrumentation oracle 行は、仮にコンパイルが直っても新testでは満たされない。**
   `EditSurfaceApplyInstrumentationTest` は `prepareApplyWriteSet` + `applyWriteSet` を `pointId=null`・`FaultInjector.NOOP` で直接駆動し（`sessionAppliesAsOneTransactionWithPhysicalDeleteAndUntitledFolder`）、writer直下の失敗rollbackと module 経由の stale 拒否を検証するのみ。specの oracle が求める (i) protocol 経由の `Applied` と recovery point 1個・相関reload・A7、(ii) `RolledBack` の pre-state表示、(iii) `Unresolved` / `RecoveryFailed` の fail-closed 表示、は**どのtestにも無い**（`handleApplyResult` のvariant分岐にもtestが無い）。既存protocol suiteは本変更の削除passを通る plan（intended欠落行あり）を駆動しないため、削除passの実行検証は新testが唯一の予定口であり、それが閉じている。

3. **中: 削除passは共有適用経路の挙動変更であり、fault hookと実行oracleが無い。**
   コード検証では安全性はA2/A5のexact検証と1行=1item対応・revision digestに従属し、recovery branchと `prepareRecoveryWriteSet` は不変、削除は同一transaction内で正しい（「結合点7」節）。一方、(a) 削除文に `faults.beforeLauncherWrite/afterLauncherWrite` が無くDELETE失敗を注入できない、(b) organizer runを含む通常applyすべてに適用される、(c) 実行oracleが現headに存在しない、ため**変更の妥当性はコード読解のみ**で支持されている。instrumentation修正時に「削除行のDELETE・非選択行の不変・A7成功」に加え、削除を含むplanのrollback（delete段での注入）を固定することを推奨する。

4. **中: AC-13（patch surface）はFAILし、PR本文への結果記録が未実施。**
   `--target HEAD --enforce-baseline` は exit 1（`main` でも同様に exit 1: `build.gradle` の pinned-content不一致は本PR非因果の既存事項）。本PRのnet new unassigned pathは9件（`AndroidManifest.xml`、homeeditの新規6ファイル、`HomeEditSurfaceActivity`/`EditSurfaceScreen`、`LauncherOptionsPopup.kt`）。`src/` 変更0のため NFR-010 の「src側変更」には当たらないが、bridge ownershipへの分類が未了（#448の6 unassigned pathと同じ残差クラス）。PR本文は結果をまだ記録していない。planが「上流ファイル変更を想定しない」としていた `LauncherOptionsPopup.kt` と、Change setに行が無かった `AndroidManifest.xml` が、いずれもfork内ファイルながらbaseline上は未分類として計上される点を記録する。

5. **低（記録鮮度）: PR本文・planの検証値がheadと不一致で、AC-14 も未実施。**
   PR本文とplan Revision 5の Executed evidence は「78 tests」（head `7f2d13e2` 時点）、本headは99 tests。PR本文は review round 1〜5 の結果（instrumentation実行・AC-13結果）を反映していない。planは Revision 5 のまま review rounds の entry が無く、spec Change history も Revision 4 で止まる。AC-14が要求する spec `implemented` 化と `DESIGN.md` / `CONTEXT.md` / `requirements.md` 更新も本headには無い（PRは `Refs #449` を維持し完了PRへ委ねる方針だが、AGENTS.mdのIssue完了条件はこの限りで未充足）。instrumentation修正後のPR本文・plan revision更新を推奨する。

6. **情報: `inspectCapture` は plan preview seam と違い ORGANIZER writer lease を取らない。**
   `PlanPreviewProtocol.inspect` は module mutex に加えて `writer.tryAcquireLease(ORGANIZER)` を取って capture するが、`LayoutApplicationModule.inspectCapture` は `ordinaryMutex.tryAcquire` のみで `writer.captureCurrent` を直接呼ぶ（`detectMissingAppCandidates` と同じprecedent）。doc commentの「same non-blocking run-mutex lease as the other read-only inspections」は mutex について正確だが writer lease は取らない。安全性は確定時にA2/A5で再検証されるため実害は無いが、doc/説明の精度として記録する。

7. **情報: AC-15のwiring oracleは production source の文字列契約で、実行時のCompose semanticsを検証しない。**
   `EditSurfaceA11yDescriptionTest.diagram item semantics wiring reads only from the descriptor` は `EditSurfaceScreen.kt` を `user.dir` から読み、`.semantics {` block の3代入文字列とblock外のstray代入を検証する（round 5/6で承認された代替）。フォーマット変更に脆く、`substringAfter` のマーカー文字列に依存する。Compose UI test / エミュレータTalkBackの実行確認（spec AC-15の記録要求）は未実施のままである。

#### ChatGPT review（round 1〜6）のspot check

- round 1 高（CI runner class list未接続）: `ci.yml` の shared-writer lane class列挙に `app.lawnchair.homeedit.EditSurfaceApplyInstrumentationTest` を追加済み（差分で確認）。ただし round 1 の修正コメントが「connected runの成功はCI実行後に記録する」としたその成功は**一度も成立していない**（Findings 1）。validator側のrunner列挙整合検査は「別Issueへ分離を推奨」のままで、`gh issue list --search` の範囲では該当issueは見つからない。
- round 1 中（`LOCK_STATE_UNAVAILABLE` typed契約）: `editSurfaceRejectionText` に明示分岐、`EditSurfaceLockGateTest` のoracleあり。**解消を確認**。
- round 1 中/round 2〜5（AC-15）: 文字列非空oracle（31文字列×en/ja）→ 純description builder → 純descriptor → wiring source oracle → null-label既定title復元、の順で実装されている。round 6 は Clear。JVM範囲では**解消を確認**（runtime TalkBackはowner evidence待ち）。
- round 1 低/round 2（synthetic id範囲）: `editSurfaceReservationKey` は `Int.MIN_VALUE + index`、session folderは `[-1024-2^20, -1024)` の互いに素な負rangeで、container定数・正rowidと非衝突。`EditSurfaceSyntheticIdsTest` で境界を固定。**解消を確認**。
- 6 roundのreviewはいずれも diff/source の読解に基づき、`compileLawnWithQuickstepGithubDebugAndroidTestKotlin` の成功やCI job結果を確認していない。round 2 reviewは「shared-writer lane は in progress であり、成功結果はまだ確認できないが配線の残存不具合ではない」としているが、実際はコンパイル失敗であった。review clearの前提として、少なくとも androidTest のコンパイル確認（またはlane結果の確認）を手順化することを推奨する。

### 結論（merge可否の判断材料）（初回監査）

- 実装の設計は spec / plan / ADR-0013 / ADR-0014 と整合し、純粋4層・入口・strings・CI routing・manifestの変更範囲は plan Change set と一致する。JVMの書込みでない部分（計画・投影・conservation・NoChanges到達不能・lock gate・synthetic id・a11y descriptor）は再実行で99件greenを確認した。削除pass（結合点7）もコード上は (a)(b)(c) を満たす。
- しかし **androidTest source set がコンパイルできない**ため、本PRの instrumentation oracle（AC-5/6/7/8/10/16、削除passの実DB検証）は存在せず、CI `final-status` は失敗している。高リスク独立エビデンス要件（検証対象commit上で `final-status` が実際に成功していること + 独立audit record）のうち前者を満たさない。
- したがって **現head `c74e305ff0` はmerge不可（NO-GO）**。Findings 1のコンパイル修正と全instrumentation laneのgreen確認、AC-13結果と検証値の記録、可能なら Findings 2/3 のoracle補強を行った新しいheadに対して再auditが必要である。
- owner確認事項（エミュレータ操作・スクリーンショット、TalkBack、ベンチマーク、NFR-013実測、実機確認）は本auditでは代替していない。
