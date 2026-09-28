# High-risk audit: PR #472 項目単位の編集アクション（ページへ移動 / フォルダへ入れる / ホームから外す）

> Status: accepted
> Audit date: 2026-09-27

- Auditor: 独立session（general-purpose subagent）。実装sessionではなく、本PRのdiff作成・Phase 1/2 review・検証実行に関与していない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/472
- Head SHA: acfc62fb0c14d677050348668a6df716f9495253
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36351985137
- Base audit: head `226d4ac9c01a8fd27e659de0bdbf4c0c62337f13`（run 36339364515）に対する初回audit。以下の「Re-audit」節がcode変更後の再監査（delta `226d4ac9..acfc62fb`）である。
- Re-audit: head `acfc62fb0c14d677050348668a6df716f9495253`（delta `226d4ac9..acfc62fb`、4 files, +166/-5）。実施 2026-09-27 UTC / 2026-09-28 JST。
- Criteria: [spec 448](../../specs/448-edit-actions-per-item/spec.md) の FR-018 / NFR-013 / AC-5 / AC-6 / AC-7

## Scope

- 対象diff: base `d3b5aba550503c6023224e64452426d8a1b32353`（current main）.. head `226d4ac9c01a8fd27e659de0bdbf4c0c62337f13` の全diff（32 files, +3508/-6）。実装だけでなくspec（`implemented`）/ plan Revision 7 / CI path filter / 文書 / 証跡画像を含む。
- platform bridge:
  - `src/com/android/launcher3/model/DirectEditContract.java`（新設。Row / Snapshot / Decision / Validator / ResultCallback の最小型。147行）
  - `src/com/android/launcher3/model/ModelWriter.java`（+304行。`moveItemForDirectEdit` / `createFolderAndMoveForDirectEdit` / `removeItemForDirectEdit` と `DirectEditTask` 基底 + 3 task、`buildDirectEditSnapshot`。既存メソッド・既存classの変更なし）
- fork module: `lawnchair/src/app/lawnchair/homeedit/{HomeEditModel,HomeEditPlanner,HomeEditAdapter,HomeEditExecutor,HomeEditUndoLog}.kt` と `ui/EditActionsShortcuts.kt`、`LawnchairLauncher.getSupportedShortcuts()` への3 Factory追加、strings en/ja。
- runtime書込み経路（本auditが追跡した経路）: popup確定 → `HomeEditExecutor.confirm`（MODEL_EXECUTOR）で stage-1 snapshot（DB投影）+ `HomeEditPlanner` → `ModelWriter` の3操作 → `ModelTask.executeOnModelThread` → `LayoutWriteCoordinator.runOrDefer(MODEL_WRITER, token=0, exact=false)` → admission内 `DirectEditTask.runImpl` で stage-2 validator → `favorites` 行の UPDATE（移動/フォルダ追加）/ INSERT+UPDATE（新規フォルダ、`newTransaction()`）/ DELETE（外す）。schema変更・migration・backup/restore契約への変更はない。
- 対象外: Undo本体・UI・寿命（#450）、一括確定適用（#449）、widget/folder/app pairのdrag経路、上流accessibility経路。エミュレータ/実機のUX・応答時間（AC-1/10/11/14）と実機TalkBackはowner確認事項であり、本auditはPR記録とCIの範囲で確認した。

### Re-audit（head `acfc62fb0c14d677050348668a6df716f9495253`、delta `226d4ac9..acfc62fb`）

- 対象delta: 4 files, +166/-5 = `ModelWriter.java` +7/-1、`DirectEditModelWriterTest.java` +59/-0、`specs/448-edit-actions-per-item/spec.md` +4/-4、本audit record +96/-0（record内のlink修正commit `acfc62fb` を含む）。baseのScope・書込み経路・対象外は不変であり、以下はbase auditに追加するdelta検証である。
- 検証1（ModelWriter remove）: **PASS**。deltaは `DirectEditRemoveTask.runAdmitted` の単一行DELETEを try/catch で包み、catchで `FileLog.e` + `reportFailure(FAIL_WRITE_FAILED)` + `return` するのみ（+7/-1、他の行変更なし）。old*値はローカルintへの読取だけで、`delete` が最初の書込み、`mBgDataModel.removeItem` / verifier / notify / success callback は削除成功後のみ（`ModelWriter.java:875-900`）。move/createと同形のtyped通知になった。
- 検証2（新test `deferredMoveRejectsStaleTargetWithoutWrite`）: **PASS**。production `ModelWriter.moveItemForDirectEdit` → `ModelTask.executeOnModelThread` → `runOrDefer(MODEL_WRITER, 0, false)` を駆動し、test thread保持のORGANIZER lease中はFIFOへdefer（300msでcallback未到達、DB/model cellY=4のまま）→ 競合writerのraw SQL `favorites` update（cellY=2）+ `item.cellY=2` → lease close → 実taskがstage-2でstale拒否（FAIL_STALE callback）→ 書込みなし（DB/model cellY=2）を固定する。raw SQLの理由コメントの結論（controller updateだとtest threadが自身のleaseでself-deadlock）は `ModelDbController.update → acquireMutationLease → acquireBlockingQuietly` の `lock.wait` として成立（mechanismの文言のみ不正確: R3）。
- 検証3（spec/plan wording）: **PARTIAL**。`spec.md` の4箇所（ロック注記、ロック済みScenario、Accessibility、AC-14）は「外す」のToast注記として実装（`EditActionsShortcuts.RemoveFromHome.loadOptions` が `homeedit_lock_note_locked_remove` をToast表示）と一致。`plan.md` はdeltaに含まれず未変更（Revision 7のまま、本roundのrevision entryなし）。`spec.md` のChange historyにも本roundのentryなし（R2）。
- リンク検証: **PASS**。本recordの `[spec 448](../../specs/448-edit-actions-per-item/spec.md)`、specの `../24-empty-folder-policy/spec.md` / `../../docs/adr/0013-...` / benchmark、planのmarkdownリンクはいずれも実在（抽出検査で破損0）。`acfc62fb` はbase auditのCriteriaリンクの壊れた相対path（`specs/...`）を修正したcommitで、修正後は有効。
- CI/run検証: run 36351985137 = `.github/workflows/ci.yml` / event `pull_request` / headSha `acfc62fb…` / PR #472 head branch `issue-448-edit-actions-per-item` / completed-success。16 job すべて success（`final-status`, `organizer-unit-tests`, `check-style`, `build-debug-apk`, `organizer-instrumentation-shared-writer-tests` を含む）。ただし `run_attempt=5` で、attempt 1〜4 は `organizer-instrumentation-manual-organization-ui-tests` のみ failure（attempt 1 logで `OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` の `ComposeTimeoutException` を確認）→ attempt 5で success・final-statusもattempt 5でsuccess。このflakeはissue #473（OPEN）で追跡され、ローカルbisectでdelta前head `226d4ac9` でも再現（delta非因果）。deltaは当該testファイル（`tests/organizer-instrumentation/app/lawnchair/ui/preferences/OrganizerDiagnosticsRouteInstrumentationTest.kt`）を変更していない。

## Criteria check

総合判定: ADR-0013契約(a)〜(f)はコード上すべて成立し、AC-5/6/7/8/9は確認できた。AC-12は記録の一部がheadと不一致（Findings 4/5）。以下は静的review + 検証済みCI runの結果である。

- AC-5(a)（admission前の無変更）: PASS。3つのpublicメソッドは task の構築と `executeOnModelThread()` のみを行う。`ItemInfo` の変更は `runAdmitted` 内、`generateNewItemId()` は `DirectEditCreateFolderTask.runAdmitted` 内、`notifyOtherCallbacks` / model更新は書込み（commit含む）後。`LayoutWriteCoordinator.runOrDefer` はORGANIZER lease中に runnable を実行しない。`DirectEditTask` のコンストラクタは stack trace の取得とfield代入のみ（model/DB変更なし）。`DirectEditModelWriterTest.directEditMoveDefersUntilOrganizerLeaseReleases` が lease 中の validator 未実行・DB/model無変化を固定。
- AC-5(b)（二段階検証）: PASS（実装）。`HomeEditStage2Validator` は admission 内で `HomeEditPlanner.plan(current, intent)` を再実行し、stage-1 の closed result と Kotlin data class の構造的等値のときだけ `Decision.proceed()`。target placement の不一致は STALE / ITEM_GONE、replan の Rejected は typed key へ写像、別destinationの成功planは STALE（無音の再計画なし）。`HomeEditStage2ValidatorTest`（6件）が検証。ただし「実taskがdefer解放後にstale rejectして無書込み」を end-to-end で固定するtestは無い（Findings 3）。
- AC-5(c)（MODEL_WRITER admission / defer）: PASS。`ModelTask.executeOnModelThread` が既存の `runOrDefer(MODEL_WRITER, 0, false)` を通る。production seam testの defer test と CI の `organizer-instrumentation-shared-writer-tests` success が裏付ける。
- AC-5(d)（1アクション=1 transaction / rollback / 握りつぶしなし）: PASS。新規フォルダは `ModelDbController.newTransaction()` の try-with-resources で insert → update → `t.commit()`。失敗時は `SQLiteTransaction.close()` → `endTransaction()`（commit未達のためrollback）→ catch で `FileLog.e` + `FAIL_WRITE_FAILED` を typed 通知。move / remove は単一行 UPDATE / DELETE。`DirectEditModelWriterTest.createFolderFailureLeavesModelAndDbAtOldPlacement`（update注入失敗で folder 0行・child旧配置・live ItemInfo旧状態）と `DirectEditWriteShapeTest` の rollback / process-death test が固定。既存 `UpdateItemsRunnable` の握りつぶし経路は新規経路が使っていない。
- AC-5(e)（removeは選択行のみ）: PASS。`delete(TABLE_NAME, itemIdMatch(item.id), null)` の1行削除で、`BgDataModel.removeItem` は in-memory のみ。アンインストール・フォルダ中身の暗黙削除・他行への書込みはない。`removeDeletesOnlyTargetRowAndCleansModel` が周辺行不変と model 整理を固定。
- AC-5(f)（organizerLockState を書かない）: PASS。3 task が書く列は CONTAINER/SCREEN/CELLX/CELLY/RANK/SPANX/SPANY（+ folder 行は `writeToValues` + PROFILE_ID + OPTIONS + `_ID`）のみで、lock列への参照は `ModelWriter.java` / `DirectEditContract.java` に存在しない。移動後にlock値が保持され、削除で行ごと消えるという契約と整合する。
- AC-6: PASS。homeedit JVM testは32件（HomeEditPlannerTest 18 / HomeEditStage2ValidatorTest 6 / HomeEditUndoLogTest 3 / HomeEditAcceptanceOraclesTest 3 / SourcePlacementSnapshotTest 2）。ci.yml の `organizer-unit-tests` filter に `app.lawnchair.homeedit.*` が追加され、CI run 36339364515 の `organizer-unit-tests` = success。
- AC-7: PASS（要求テスト表の本Issue行）。ci.yml の shared-writer lane class list に `DirectEditModelWriterTest`（5 test、production seam）と `DirectEditWriteShapeTest`（4 test、rollback / commit / admission内検証 / process死）が登録され、同 run の `organizer-instrumentation-shared-writer-tests` = success。Undo fail-closed行は #450 の実装PRが所有（本PRの対象外）。
- AC-8: PASS。`HomeEditPlanner` / `HomeEditModel` は platform import を持たず、`HomeEditAdapter` が `DirectEditContract` 境界、書込みは `HomeEditExecutor` → `ModelWriter` の direct-edit 操作のみ。`HomeEditAcceptanceOraclesTest` の構造検証は public signature/field ベースの弱い検証だが、ソースreviewでも純粋moduleの platform 非依存を確認した。
- AC-9: PASS。`HomeEditUndoEvidence` が itemId / 旧 container・screen・cell・span・rank / 新配置 / `createdFolderId` / 作成フォルダの配置を持ち、成功callbackで `HomeEditUndoLog` に記録。`HomeEditUndoLogTest`（3件）が3アクションのevidenceを検証。本体・寿命・UIを#450に委ねる旨はコードコメントとspecに記録されている。
- AC-12: PARTIAL。script実行結果（FAIL）とNFR-010（`DirectEditContract.java` の counted file +1）はPR本文に記録され、「main上でもFAIL」は再現確認できた。ただし記録値がheadと不一致（Findings 4/5）。
- FR-018 / NFR-013: FR-018の振る舞い契約は AC-2〜AC-5 の test/oracle とコード構造で確認。NFR-013（確定→即時反映）は単一行書込みとdefer時のlease解放後反映というspec記載の意味論までをコードで確認した。1秒以内の実測・エミュレータ計測はPR/owner evidenceの範囲であり、本auditでは再計測していない。

### Re-audit（head `acfc62fb…`）

- 総合: baseの判定はdeltaで変わらない。AC-5(a)〜(f)の構造、AC-6/7/8/9の証跡は不変であり、旧Finding 1（defer後staleのproduction seam証跡）と旧Finding 2（removeのtyped失敗）はdeltaで解消、AC-5(b)(c)とScopeの「失敗時は無変更でtypedな理由」はむしろ強化された。
- AC-5(b)(c): PASS（更新）。「実taskがdefer解放後にstage-2でstale拒否し無書込み」を `DirectEditModelWriterTest.deferredMoveRejectsStaleTargetWithoutWrite` がproduction taskで固定（残る注記はR4のみ）。AC-5(c)の `organizer-instrumentation-shared-writer-tests` successはattempt 5で確認（flake節参照）。
- AC-5(d): PASS（更新）。removeのDB失敗は `FAIL_WRITE_FAILED` でtyped通知され、例外はmodel threadを抜けずmodel/DBは無変更。ただしfailure注入testはupdate経路のみ（R1）。
- AC-6: 不変（homeedit JVM 32件の構成に変更なし）。
- AC-7: 不変。新testはshared-writer laneのclass list（`.github/workflows/ci.yml:417` の `DirectEditModelWriterTest`）に含まれる同classの一部としてlane successで実行された。
- AC-12: PARTIAL（記録は改善、正本に残差）。PR本文はhead整合へ更新済み（`ModelWriter.java +310` = `git diff --numstat d3b5aba..acfc62fb -- src/` と一致、`DirectEditWriteShapeTest` 4件、`DirectEditModelWriterTest` 6件、homeedit 6 unassigned path）。一方 `plan.md` のPhase 2 recordは `+283` / `DirectEditWriteShapeTest 3件` のまま（R2）。
- FR-018 / NFR-013: 不変。

## Executed test surface

監査者が実際に実行したcommandと観測結果:

```
git rev-parse HEAD
  -> 226d4ac9c01a8fd27e659de0bdbf4c0c62337f13

git diff --stat d3b5aba550503c6023224e64452426d8a1b32353..226d4ac9c01a8fd27e659de0bdbf4c0c62337f13
  -> 32 files changed, 3508 insertions(+), 6 deletions(-)

git diff --numstat d3b5aba550503c6023224e64452426d8a1b32353..226d4ac9c01a8fd27e659de0bdbf4c0c62337f13 -- src/
  -> 147  0  src/com/android/launcher3/model/DirectEditContract.java
  -> 304  0  src/com/android/launcher3/model/ModelWriter.java

python3 tools/repo-contract/validate_repo_contract.py
  -> exit 1。finding 2件（refocus-drafts/adr/0014-edit-surface.md:15、refocus-drafts/open-issue-dispositions.md:4）。
     いずれも未追跡ローカルdraft `refocus-drafts/` 配下で本PRのdiff外（既存2件）。

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> exit 0。Ran 51 tests / OK

python3 tools/repo-contract/validate_writer_inventory.py
  -> exit 0。PASS: 19 writer files verified against allowlist (1536 source files scanned, 0 errors, 0 warnings)

python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline
  -> exit 1。main（--target d3b5aba550503c6023224e64452426d8a1b32353）でも exit 1 を再現。
     HEAD追加分の unassigned path は src/com/android/launcher3/model/DirectEditContract.java と
     lawnchair/src/app/lawnchair/homeedit/ 配下6ファイル。

grep -c '@Test' tests/unit/app/lawnchair/homeedit/*.kt tests/organizer-instrumentation/com/android/launcher3/DirectEditModelWriterTest.java tests/organizer-instrumentation/com/android/launcher3/organizer/DirectEditWriteShapeTest.java
  -> homeedit JVM 32件、DirectEditModelWriterTest 5件、DirectEditWriteShapeTest 4件

gh run view 36339364515 -R nunu1733/NunuLauncher --json status,conclusion,headSha,event,workflowName
  -> completed / success / 226d4ac9c01a8fd27e659de0bdbf4c0c62337f13 / pull_request / CI
  -> final-status, organizer-unit-tests, check-style, build-debug-apk を含む全16 job が success（skipなし）

gh api repos/nunu1733/NunuLauncher/actions/runs/36339364515 --jq '.pull_requests[]'
  -> PR #472, head branch issue-448-edit-actions-per-item, base main, head SHA 226d4ac9

gh api repos/nunu1733/NunuLauncher/actions/jobs/108676339162
  -> organizer-instrumentation-shared-writer-tests: success。instrumentation step success、failure時upload stepは skipped
```

補足（honesty）: 監査者は `./gradlew` のunit/instrumentation suiteをローカル再実行していない。AC-6/AC-7 は上記CI runの `organizer-unit-tests` / `organizer-instrumentation-shared-writer-tests` の実job結果と、test sourceの内容確認に基づく。エミュレータ実操作・実機TalkBack・B2〜B4計測は再実施していない。

### Re-audit（head `acfc62fb0c14d677050348668a6df716f9495253`）で実行したcommandと観測結果

```
git rev-parse HEAD
  -> acfc62fb0c14d677050348668a6df716f9495253

git diff --stat 226d4ac9c01a8fd27e659de0bdbf4c0c62337f13..acfc62fb0c14d677050348668a6df716f9495253
  -> 4 files changed, 166 insertions(+), 5 deletions(-)

git diff --numstat 226d4ac9..acfc62fb
  -> 96  0  docs/assessment/pr-472-edit-actions.md
  -> 4   4  specs/448-edit-actions-per-item/spec.md
  -> 7   1  src/com/android/launcher3/model/ModelWriter.java
  -> 59  0  tests/organizer-instrumentation/com/android/launcher3/DirectEditModelWriterTest.java

git diff --numstat d3b5aba550503c6023224e64452426d8a1b32353..acfc62fb -- src/
  -> 147  0  src/com/android/launcher3/model/DirectEditContract.java
  -> 310  0  src/com/android/launcher3/model/ModelWriter.java（PR本文の+310と一致）

git show acfc62fb:tests/organizer-instrumentation/com/android/launcher3/DirectEditModelWriterTest.java | grep -c '@Test'
  -> 6（DirectEditWriteShapeTest は 4）

（読解）ModelWriter.java:561-583 / 869-901、LayoutWriteCoordinator.java:327-356 / 460-497 / 572-613、
  ModelDbController.java:232-241 / 319-344（executeOnModelThread → runOrDefer deferと、
  controller updateがacquireBlockingのlock.waitでself-deadlockする経路の確認）

python3 tools/repo-contract/validate_repo_contract.py
  -> exit 1。finding 2件（refocus-drafts/adr/0014-edit-surface.md:15、refocus-drafts/open-issue-dispositions.md:4）。
     base auditと同一の既存2件で、本deltaの4ファイルは含まれない。

python3 tools/repo-contract/test_validate_high_risk_evidence.py
  -> exit 0。Ran 51 tests / OK

python3 tools/repo-contract/validate_writer_inventory.py
  -> exit 0。PASS: 19 writer files verified against allowlist (1536 source files scanned, 0 errors, 0 warnings)

python3（markdownリンク抽出チェック: 本record / spec.md / plan.md）
  -> 破損ローカルリンク0（本recordの `../../specs/448-edit-actions-per-item/spec.md` を含む）

gh run view 36351985137 -R nunu1733/NunuLauncher --json status,conclusion,headSha,event,workflowName
  -> completed / success / acfc62fb0c14d677050348668a6df716f9495253 / pull_request / CI

gh run view 36351985137 -R nunu1733/NunuLauncher --json jobs --jq '.jobs[] | ...'
  -> 16 job すべて success（final-status, organizer-unit-tests, check-style, build-debug-apk,
     organizer-instrumentation-shared-writer-tests を含む。skipなし）

gh api repos/nunu1733/NunuLauncher/actions/runs/36351985137 --jq '{head_branch,head_sha,pull_requests}'
  -> PR #472 / base main / head branch issue-448-edit-actions-per-item / head_sha acfc62fb

gh api repos/nunu1733/NunuLauncher/actions/runs/36351985137/attempts/{1..5}/jobs?per_page=100
  -> organizer-instrumentation-manual-organization-ui-tests: attempt 1〜4 = failure、attempt 5 = success
  -> final-status: attempt 1〜4 = failure、attempt 5 = success
  -> organizer-instrumentation-shared-writer-tests: 全attempt success

gh api repos/nunu1733/NunuLauncher/actions/jobs/108712385301/logs | grep -E 'issue372|ComposeTimeoutException'
  -> attempt 1 の失敗 = OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute
     / androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 5000 ms

gh issue view 473 -R nunu1733/NunuLauncher
  -> OPEN「OrganizerDiagnosticsRouteInstrumentationTest.issue372... が断続的にtimeoutする」（type: maintenance）。
     CI 36351985137の2 attempts連続同一signature、ローカルbisect（arm64 AVD）で226d4ac9でも失敗＝PR delta非因果、を記録。
```

補足（honesty、Re-audit）: 監査者は今回も `./gradlew` suiteをローカル再実行していない。新testの実行はshared-writer lane job success（attempt 5）・class list登録・test source読解に基づく。エミュレータ実操作・実機TalkBack・B2〜B4計測は再実施していない。

## Findings

1. **中: ADR-0013要求テスト表「admission後の再検証（defer後のstale検証）」がproduction taskのend-to-end testで固定されていない。** `DirectEditWriteShapeTest.stage2ValidationRunsInsideAdmissionAndRejectsWithoutWrite` は `runOrDefer` + ローカルlambdaで task shape を模写しており、`ModelWriter.DirectEditTask` 自体を駆動しない。production seamの defer test（`DirectEditModelWriterTest.directEditMoveDefersUntilOrganizerLeaseReleases`）は proceed validator のみ。JVMの `HomeEditStage2ValidatorTest` が決定ロジックを、WriteShapeTest が coordinator の defer と「拒否時に書かない」を別々にカバーするが、「organizer適用で対象row/配置先が変わる → lease解放 → 実taskがstage-2でstale拒否 → model/DB無変更」の一連は自動testで固定されていない。書込み形状の不変条件は読解では成立している。推奨: `DirectEditModelWriterTest` に reject validator + organizer lease の production-seam testを追加する（merge前が望ましい）。
2. **低: `DirectEditRemoveTask` にDB失敗時のtyped通知がない。** `delete` は try/catch されておらず、例外はmodel threadを抜けて `ResultCallback` が呼ばれない（spec Scopeの「失敗時は無変更でtypedな理由」/AC-4と不整合。move/createは catch + `FAIL_WRITE_FAILED`）。単一行DELETE失敗は稀だが、非対称であり、未捕捉例外はmodel threadでクラッシュしうる。move/removeの失敗注入testも無い。推奨: deleteをtry/catchしてtyped失败を通知し、failure注入testを追加する。
3. **低: `ModelTask.run()` のloadId guardによるsilent skip。** `mLoadId != mModel.getLastLoadId()` のとき direct-edit task はcallbackなしで捨てられ、typed理由もUndo evidenceも出ない（書込みは発生しないため安全側）。base classの既存挙動だが、直接編集はtyped結果をspec契約に持つためresidual riskとして記録する。testなし。
4. **低: AC-12 / NFR-010の記録値がaudited headと不一致。** `ModelWriter.java` のdeltaは head で +304/-0（`git diff --numstat`）。PR本文とplanの「+283」は初期実装commit `03fe9f43b0` の値で、後続review roundの変更分が未計上。また `measure_upstream_patch_surface.py` は HEAD で `DirectEditContract.java` に加えて `lawnchair/src/app/lawnchair/homeedit/` 配下6ファイルを新規 unassigned path として列挙するが、PR本文はcounted src file +1のみを記録している。mainでもFAILという主張自体は再現確認済み。推奨: head時点のnumstatとunassigned path一覧（homeedit moduleのbaseline分類）をPR/planへ追記する。
5. **低: PR本文のinstrumentation件数がheadと不一致。** `DirectEditWriteShapeTest` は「3」と記録されているが、headのfileは `@Test` 4件（CIはclass単位実行のため4件とも実行され成功）。記録を更新するか、run URLと件数を再記録する。
6. **低: 「外す」のロック注記はdialogではなくToast。** spec Scope / ロック済みシナリオは「dialogの表示にロックも削除される旨が示される」と書くが、removeはdialogなしで即時実行される設計（planのAlternativesで確認済み）。Toastは文字列リソース由来でTalkBackも読めるため利用者への通知は成立するが、spec文言と実装が乖離している。spec側の表現を実装（または意図）に合わせて更新することを推奨。
7. **情報: `DirectEditContract` のjavadocは「Pure JDK types only」と書くが `androidx.annotation.Nullable` と `com.android.launcher3.model.data.FolderInfo` をimportする。** 実際の境界（`src` が `lawnchair` に依存しない、純粋計画層がAndroid/DB行型を受け取らない）は保たれており、`HomeEditPlanner` / `HomeEditModel` は `DirectEditContract` をimportしない。文言の精度のみ。
8. **情報: `PageOption.hasFreeCell` は計算されるがUIで未使用。** dialogは全既存ページを列挙し、空きがない場合は選択後にplannerが `NO_SPACE` を返す。デッドデータ。

総括: ADR-0013契約(a)〜(f)のいずれにも違反は見つからず、AC-5/6/7/8/9は確認できた。Finding 1は要求テスト表の証跡ギャップ（実装の欠陥ではない）であり、merge前にtest追加で解消することを推奨する。Finding 2は実装の堅牢性改善、Finding 4/5は記録の正確性、Finding 6はspec文言の同期。owner確認事項（実機popup操作・実機TalkBack・B2〜B4正式計測）はPR本文どおり未完了であり、本auditはそれを代替しない。

### Re-audit findings（head `acfc62fb0c14d677050348668a6df716f9495253`）

**Delta検証の結果（1行ずつ）**

- D1（ModelWriter remove）: PASS。try/catch + typed `FAIL_WRITE_FAILED` の追加のみで他の挙動変更なし。delete前にmutateされる値はなく（ローカルintの読取のみ）、model/DB変更・callbackは削除成功後のみ。
- D2（新test `deferredMoveRejectsStaleTargetWithoutWrite`）: PASS。production `moveItemForDirectEdit` → organizer-lease defer → 競合writerのDB move → lease解放後のstage-2 stale拒否・無書込み、の一連を実taskで固定。raw SQLの理由コメントは結論（self-deadlock回避）が正しい（mechanism文言の不正確さはR3）。
- D3（spec/plan wording）: PARTIAL。spec.mdの4箇所は実装のToastと一致。plan.mdは未変更でplan/specのrevision historyにも本roundのentryがない（R2）。

**base auditのFindingsの状態**

- Finding 1: **resolved**。旧「実taskがdefer解放後にstale拒否して無書込み」のend-to-endギャップはD2のtestで解消。
- Finding 2: **resolved（残R1）**。removeのDELETEがtry/catchされtyped `FAIL_WRITE_FAILED` を通知（D1）。ただしDELETE失敗を注入するtestは依然として存在しない。
- Finding 3（`ModelTask.run()` のloadId guardによるsilent skip）: **unchanged**（deltaはこの経路に触れていない）。
- Finding 4（AC-12/NFR-010記録値）: **resolved（残R2）**。PR本文は `ModelWriter.java +310`（headのnumstatと一致）・homeedit 6 unassigned pathへ更新済み。plan.mdのPhase 2 recordは `+283` のまま。
- Finding 5（instrumentation件数）: **resolved（残R2）**。PR本文は `DirectEditWriteShapeTest` 4件・`DirectEditModelWriterTest` 6件（headの `@Test` 数と一致）。plan.mdは `DirectEditWriteShapeTest 3件` のまま。
- Finding 6（「外す」のロック注記がdialogではなくToast）: **resolved**。spec.mdのScope/Scenario/Accessibility/AC-14がToast表現へ同期し、実装（`RemoveFromHome.loadOptions` の `homeedit_lock_note_locked_remove` Toast）と一致。
- Findings 7/8（javadoc文言の精度、`PageOption.hasFreeCell` の未使用）: **unchanged**（情報のみ）。

**新規Findings**

- R1. **低: remove の失敗分岐がtestで踏まれていない。** `DirectEditRemoveTask.runAdmitted` の新しいcatchは読解で正しいが、`FailableController` は `failOnUpdate` のみを注入し、`ModelDbController.delete` の失敗注入testが無い（grep: `failOnUpdate` 以外のfailure注入なし）。推奨: delete失敗注入で `FAIL_WRITE_FAILED` 通知・model/DB無変更を固定する（旧Finding 2の残差）。
- R2. **低（記録）: 正本plan.md/spec Change historyが本round未更新。** `plan.md` はRevision 7のまま（`e358514496` のentryなし）、Phase 2 recordの `ModelWriter.java +283` と `DirectEditWriteShapeTest 3件` はhead（+310、4件）と不一致。`spec.md` のChange historyにも本roundのentryがない（PR本文のみ更新済み）。AGENTS.mdの「正本」分担ではplanのverification記録が正本であり、実装は変わったが正本の記録が古い状態。推奨: plan Revision 8（audit対応の記録）とspec Change historyの追記。
- R3. **情報: 新testのraw SQLコメントのmechanism記述が不正確。** 「a controller update here would … queue behind our own deferred task behind the organizer lease」と書くが、`ModelDbController.update` はFIFOへは入らず `acquireMutationLease → acquireBlockingQuietly(MODEL_WRITER)` の `lock.wait()` でtest thread自身が保持するORGANIZER leaseを待ち続けるself-deadlockになる（queueではなくblocking）。結論（raw SQLで回避する必要がある）は正しく、testの選択自体は適切。文言のみの精度。
- R4. **情報: 新testのstage-2 validatorは本番 `HomeEditStage2Validator` ではなくtest lambda。** これはstale述語（stage-1配置との不一致）を再現してproduction taskのadmission/defer/拒否経路を実駆動しており、ADR-0013要求テスト行の証跡として成立する。validator自体のdecision logicはJVM `HomeEditStage2ValidatorTest` が所有（base audit AC-5(b)の記載どおり）。

**CI flake context（記録）**

- run 36351985137 の green は `run_attempt=5`。attempts 1〜4 は `organizer-instrumentation-manual-organization-ui-tests` のみ failure（attempt 1の失敗は `OrganizerDiagnosticsRouteInstrumentationTest.issue372ConsultationSessionSurvivesARealMaterialsWriteViaTheProductionRoute` / `ComposeTimeoutException`、attempt 1 logで確認）で、`final-status` も同attemptsでfailure。attempt 5で当該laneとfinal-statusがsuccess。
- issue #473（OPEN、type: maintenance）がこのflakeを追跡し、ローカルbisect（arm64 AVD）でdelta前head `226d4ac9` でも同一signatureで失敗することを記録済み（PR deltaは非因果）。deltaの4ファイルに当該testは含まれない。
- したがってmerge gate（final-status on acfc62fb）は成立しているが、そのgreenはunrelated laneのrerun-until-green後である。本auditはこれを隠さず記録する。

**Re-audit 総括**

- deltaはbase auditのFinding 1/2/6への対応であり、いずれもコード/test/spec文言として確認できた（Finding 2はtyped通知まで、失敗注入testはR1として残る）。deltaに新たな実装リスクは検出していない。
- 高リスク独立エビデンス要件（acfc62fb上の成功したmerge gate run + 独立audit record）は成立。ただしCI greenのflake経緯（#473）と、plan.md/spec Change historyの未更新（R2）を未解決の記録事項として残す。
