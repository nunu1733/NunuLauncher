# High-risk audit: PR #472 項目単位の編集アクション（ページへ移動 / フォルダへ入れる / ホームから外す）

> Status: accepted
> Audit date: 2026-09-27

- Auditor: 独立session（general-purpose subagent）。実装sessionではなく、本PRのdiff作成・Phase 1/2 review・検証実行に関与していない。
- PR: https://github.com/nunu1733/NunuLauncher/pull/472
- Head SHA: 226d4ac9c01a8fd27e659de0bdbf4c0c62337f13
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36339364515
- Criteria: [spec 448](../../specs/448-edit-actions-per-item/spec.md) の FR-018 / NFR-013 / AC-5 / AC-6 / AC-7

## Scope

- 対象diff: base `d3b5aba550503c6023224e64452426d8a1b32353`（current main）.. head `226d4ac9c01a8fd27e659de0bdbf4c0c62337f13` の全diff（32 files, +3508/-6）。実装だけでなくspec（`implemented`）/ plan Revision 7 / CI path filter / 文書 / 証跡画像を含む。
- platform bridge:
  - `src/com/android/launcher3/model/DirectEditContract.java`（新設。Row / Snapshot / Decision / Validator / ResultCallback の最小型。147行）
  - `src/com/android/launcher3/model/ModelWriter.java`（+304行。`moveItemForDirectEdit` / `createFolderAndMoveForDirectEdit` / `removeItemForDirectEdit` と `DirectEditTask` 基底 + 3 task、`buildDirectEditSnapshot`。既存メソッド・既存classの変更なし）
- fork module: `lawnchair/src/app/lawnchair/homeedit/{HomeEditModel,HomeEditPlanner,HomeEditAdapter,HomeEditExecutor,HomeEditUndoLog}.kt` と `ui/EditActionsShortcuts.kt`、`LawnchairLauncher.getSupportedShortcuts()` への3 Factory追加、strings en/ja。
- runtime書込み経路（本auditが追跡した経路）: popup確定 → `HomeEditExecutor.confirm`（MODEL_EXECUTOR）で stage-1 snapshot（DB投影）+ `HomeEditPlanner` → `ModelWriter` の3操作 → `ModelTask.executeOnModelThread` → `LayoutWriteCoordinator.runOrDefer(MODEL_WRITER, token=0, exact=false)` → admission内 `DirectEditTask.runImpl` で stage-2 validator → `favorites` 行の UPDATE（移動/フォルダ追加）/ INSERT+UPDATE（新規フォルダ、`newTransaction()`）/ DELETE（外す）。schema変更・migration・backup/restore契約への変更はない。
- 対象外: Undo本体・UI・寿命（#450）、一括確定適用（#449）、widget/folder/app pairのdrag経路、上流accessibility経路。エミュレータ/実機のUX・応答時間（AC-1/10/11/14）と実機TalkBackはowner確認事項であり、本auditはPR記録とCIの範囲で確認した。

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
