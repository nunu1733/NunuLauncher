# High-risk audit: PR #481 編集の取り消し（Undo）（#450）

> Status: **GO（追跡更新 2026-09-30: run 36664678412 の失敗2 laneを再実行し、対象head `37a670b35f0a8c89e3d057a7e150c9cdc6a3e81f` 上で `final-status` = success を取得済み。本recordのpush後の `high-risk-evidence` が本headを機械検証する）**
> Audit date: 2026-09-30

- Auditor: 独立session（general-purpose subagent）。実装sessionではなく、本PRのdiff作成・review・検証実行に関与していない。監査は実装sessionとは別の作業として、作業tree（= PR headそのもの）でread-only照合とテスト再実行を行った。
- PR: https://github.com/nunu1733/NunuLauncher/pull/481 （`risk: layout-data` label確認済み。head branch `issue-450-spec-plan`）
- Head SHA: 37a670b35f0a8c89e3d057a7e150c9cdc6a3e81f
  （作業treeの `git rev-parse HEAD` と一致。base `be7576c30a864574c28b0fd3450b6e767c435ea1`）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36664678412 （pull_request event、head `37a670b35f0a8c89e3d057a7e150c9cdc6a3e81f`。**final-status = success（2026-09-30、失敗2 laneの再実行後に確定。全15 job pass）**。初回runの失敗は `organizer-instrumentation-onboarding-proposal-tests`（nexuslauncherフォーカス、emulator resume timeout。再実行でgreen）と `organizer-instrumentation-manual-organization-ui-tests`（初回=当PR無関係テストのWrongThread例外、再実行1回目=SystemUI ANR起点の環境unhealthy連鎖 — いずれも#477記録の環境class。再実行2回目で153 tests全green））
- 監査対象commit上で本記録が未存在だったため、run 36664678412の `high-risk-evidence` はfailure（34s、audit記録欠如）。本recordのpush後のrunで再評価される。
- Criteria: [spec 450](../../specs/450-edit-undo/spec.md)（accepted Rev 3）の AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11 / FR-020 / NFR-013 / NFR-014
- Criteria: [直接編集の書込み契約ADR](../../docs/adr/0013-direct-edit-write-contract.md) の ADR-0013（accepted。契約5「Undo（fail-closedな逆操作）」と契約2〜4の順序が本PRの逆操作に適用される）
- Criteria: [操作面のADR](../../docs/adr/0014-edit-surface.md) の ADR-0014（accepted。案B。編集画面確定1回 = 適用1回 = 復元点1点。本PRは確定のundoをorganizer復元経路へ流すのみ）

## Scope

- 対象diff: base `be7576c30a`.. head `37a670b35f` の全diff（78 files, +7142/-365）。
- **`src/`（上流Launcher3 bridge）の変更は3ファイルのみ**: `src/com/android/launcher3/model/ModelWriter.java`（+431、逆操作3タスクの追加）、`src/com/android/launcher3/model/DirectEditContract.java`（+104、fork所有bridge契約の明示的拡張）、`src/com/android/launcher3/folder/Folder.java`（+29、bind/close/removeのsingle-child cleanupをOPTIONS bit抑止の共通判断へ集約）。**`FolderInfo.java` はdiff内に出現しない**ことを `git diff --stat` で確認。schema/migration・dependency追加・permission追加なし。
- 本auditが実コードで追跡した経路:
  - **ModelWriter逆操作3タスク**（`DirectEditRestorePlacementTask` / `DirectEditRestoreRemovedTask` / `DirectEditUndoCreateFolderTask`）: いずれも `executeOnModelThread()` → `LayoutWriteCoordinator.runOrDefer(MODEL_WRITER, token=0)` のadmission内で実行（ModelWriter.java:574-583）。`DirectEditTask.runImpl`（:726-735）はadmission内でvalidator（stage-2）を先に実行し、非proceedならtyped失敗で零書込み。`DirectEditRestoreRemovedTask.runImpl`（:1154付近）も同様にadmission内でvalidatorを先に実行する。
  - **単一transaction**: restore placement = 1行UPDATE、restore removed = 1行INSERT（`generateNewItemId()` で新id。captureした行内容をverbatim復元、`organizerLockState` 含む）、undo create folder = `newTransaction()` 内の子UPDATE+フォルダ行DELETE+commit（失敗時rollback、`DirectEditUndoCreateFolderTask.runAdmitted`）。
  - **typed失敗key**: `DirectEditContract` に `UNDO_STALE / UNDO_NO_SPACE / UNDO_FOLDER_CHANGED / UNDO_ITEM_UNAVAILABLE / UNDO_WRITE_FAILED` を追加。ModelWriterはこのkeyのみを報告する。
  - **削除前行payloadのadmission内capture**: `DirectEditRemoveTask.runAdmitted` がDELETE直前に `queryUndoRowPayload(item.id)` で全行投影をcapture（同一admission内、失敗時は `FAIL_UNDO_WRITE_FAILED` で全remove失敗=fail-closed）し、`ResultCallback` の拡張引数（成功時のみ非null）でfork側recordへ運ぶ。
  - **Undo記録**（`HomeEditUndoRecord.kt`）: `AtomicLong` 世代 + `AtomicReference` 単一slot。`compareAndConsume` は世代一致時のみCASで消費し、不一致/空ではslotを触らない（零書込みの無操作）。`HomeEditUndoExecutor.start`（:66-78）はconsume失敗で即return。
  - **逆操作計画**（`HomeEditUndoPlanner.kt`）: 純関数。結果位置一致（STALE）、復帰先の空き/grid範囲/hotseat容量（NO_SPACE。`Snapshot.hotseatCount` 照合込み）、作成フォルダの intact判定+子数==1（FOLDER_CHANGED）、availability（AVAILABLE以外すべて拒否。UNKNOWN含むfail-closed=ITEM_UNAVAILABLE）。`HomeEditUndoStage2Validator` が同一plannerをadmission内で再実行し、stage-1 planと一致したときのみproceed。
  - **availability照合**（`HomeEditUndoVerifier.kt`）: component × profile（`LauncherApps.getActivityList`）/ deep shortcut（`getShortcuts` PINNED|MANIFEST）。serial不解決・検証例外はUNKNOWN（fail-closed）。同一production verifierがstage-1/stage-2で共有される。
  - **編集画面確定のundo**（ADR-0014 case B経路）: `ApplyProtocol.continueCommitted` が `Applied` 組み立て点（mutex保持中・invocation-local `ApplyContext`）で `RevisionCalculator.revisionOf(writeSet.intendedState)` をcapture（:440-444。`writeSet.intendedState` は直前のexact-DB検証のoperand）。`ManualOrganizationApplication` への `applyWithUndoReceipt` / `recover` はadditive methodのみでpublic契約（`ApplyResult` / `RecoveryRequest` / `RecoveryResult`）は不変。`HomeEditSurfaceActivity.handleApplyResult` の `Applied` 分岐が `HomeEditUndoEntry.EditSession(pointId, verifiedPostRevision)` をrecordしsnackbarをlauncherへ要求。undo tapは `HomeEditUndoExecutor.runEditSessionUndo` が専用スレッド（`homeedit-undo-recover`。MODEL_EXECUTOR上での相関reload待ちデッドロック回避）からorganizer復元経路 `recover` へ流す。復元結果の型は `HomeEditUndoRecoveryText.kt` でtyped表示語彙へ対応付け（JVM test付き）。
  - **adapter正規化**（`LauncherLayoutAdapter.kt`）: フォルダ子のcellX/cellYをlauncherのbind-time正規化grid cell（`FolderGridOrganizer` 相当のfork側port）でmaterializeする修正。bind時の正規化書込み（`modified` 更新を伴う）をno-op化し、receipt revisionと適用後の実状態の乖離（正当なcreate-folder undoの `STALE_REVISION` 誤拒否）を排除。canonical `FolderChild` placementは(parent, rank)のみのためcanonical stateからは不可視。
  - **`organizerLockState` 列**: 逆操作は既存列の値を保存・復元するのみで、新たな書込み経路・schema変更はない（`UndoRowPayload.organizerLockState` をINSERTでverbatim復元。移動系逆操作は列に触れない）。
- 対象外: 実機owner確認（AC-1/AC-9の実機項目。owner decision itemとして記録済み）、`final-status` green runの取得（merge operator段階の機械要件）。

## Criteria check

総合: **内容面は全受入条件が確認済み（PASS）。ただしmerge gateの機械要件（対象head上のgreen `final-status` run）が監査時点で未充足**（Header参照）。

- **AC-1（snackbar Undo tap 1操作で元に戻る。エミュレータ証跡+実機はowner）**: **PASS（エミュレータ証跡）/ 実機はowner確認pending**。証跡 `docs/assessment/450-edit-undo-ac1-screenshots/` に17ファイルが存在することを確認: 項目単位4アクション（move / add-to-folder / create-folder / remove）それぞれ before + after + undo-t1 の15枚（`direct-move-before.png` ほか）、編集画面確定 before/after 2枚（`session-confirm-before.png` / `-after.png`）。判定とAC別根拠は `docs/assessment/450-edit-undo-b5-evidence.md`（round 13記録）。実UI操作（実snackbar tap）によるoracleは `HomeEditUndoEvidenceToolingTest` の `moveUndoByTappingTheRealSnackbarAction` / `addToFolderUndo...` / `createFolderUndo...` / `removeUndo...` / `editSessionConfirmUndo...`（本auditで9 tests再実行green）。
- **AC-2（実行前照合+ずれで零書込みのtyped失敗。availability・失敗注入込み。ADR-0013要求テスト表「Undoのfail-closed」行）**: **PASS**。JVM: `HomeEditUndoPlannerTest` 17 tests（STALE/NO_SPACE/hotseat縮小/FOLDER_CHANGED/availability拒否+UNKNOWN fail-closed/決定性）。instrumentation: `DirectEditUndoModelWriterTest`（admission内注入: `restorePlacementRejectsStaleWithoutWrite`、`undoCreateFolderRejectsInsideAdmissionWithZeroWrite`、`restoreRemovedRejectsWithZeroWriteWhenTheStage2ValidatorRejectsAvailability`）、`HomeEditUndoAvailabilityInstrumentationTest` 6 tests（package消失・shortcut消失・serial不解決・verifier例外→UNKNOWNの零書込みsemantics）、`EditSurfaceUndoInstrumentationTest`（`writeAfterConfirmMakesTheUndoStaleWithZeroWrite` ほか）。payloadがadmission内capture→callback→recordへ格納される経路のoracleは `HomeEditUndoRecordTest.directEntryCarriesTheRemovePayloadForTheInverseINSERT`。
- **AC-3（1 DB transactionで成功or変更前へ戻る。test DB）**: **PASS**。`DirectEditUndoModelWriterTest.undoCreateFolderRestoresChildAndDeletesFolderInOneTransaction`、`undoCreateFolderRollsBackBothWritesWhenTheDeleteFails`（2行目失敗注入でrollback、model/DB一致）、`folderUndoAbandonedMidTransactionLeavesThePreStateAfterReopen`。実装側は `newTransaction()` + commit（ModelWriter `DirectEditUndoCreateFolderTask.runAdmitted`）。単一rowのrestore placement/restore removedは1行書込みでpartial stateが構造上存在しない。
- **AC-4（MODEL_WRITER admission経由。ORGANIZER lease中はdefer、解放後admission内再検証）**: **PASS**。3逆操作すべて `executeOnModelThread()` 経由（admission gate、ModelWriter.java:574-583）で、`undoDeferredDuringOrganizerLeaseRevalidatesAndRejectsAfterRelease` がdefer→解放後stage-2再検証→零書込み拒否をoracle化（spec 448 AC-5と同一seam）。
- **AC-5（編集画面確定のundoがreceiptの `pointId + verified post revision` でorganizer復元経路を呼び `Restored` で戻る。新規フォルダ込み正常系・revision等価固定・`STALE_REVISION` 零書込み・evict/復元済み/busyのtyped表示・#449 flow接続）**: **PASS**。receipt正本は `ApplyProtocol.continueCommitted` の `Applied` 組み立て点で `revisionOf(writeSet.intendedState)` をinvocation-local contextへcapture（mutex内。post-hoc capture禁止を構造で満たす）。接続は `HomeEditSurfaceActivity.handleApplyResult` の `Applied` 分岐。oracle群 `EditSurfaceUndoInstrumentationTest` 18 tests: `receiptRevisionEqualsPostApplyCaptureForANewFolderConfirm`（等価固定）、`productionConfirmFlowWithNewFolderUndoRestoresToThePreApplyState`（新規フォルダ込み正常系 `Restored`）、`undoWithAMismatchedRevisionIsRejectedAsStale` / `writeAfterConfirmMakesTheUndoStaleWithZeroWrite`（STALE_REVISION零書込み）、`undoAfterRetentionEvictionIsRejectedAsExpiredWithZeroWrite` / `undoAfterARestoreIsRejectedAsAlreadyRestored` / `undoUnderSerializationContentionReportsWriterBusyWithZeroWrite`、executor経由のtyped表示3件（`undoThroughExecutorDisplays*`）。新規フォルダundo成立の技術的根拠（adapter正規化修正）はコードで確認済み。
- **AC-6（記録の寿命: 次の編集で置換、undo試行終了で消費、process死で消失）**: **PASS**。JVM `HomeEditUndoRecordTest.recordReplacesThePreviousEntryAndTokensAreStrictlyIncreasing` + `mismatchedConsumeLeavesTheCurrentRecordConsumable`。process死後の入口消失は `EditSurfaceUndoInstrumentationTest.processDeathSmoke` が `HomeEditUndoRecord.inspectForTest()` 経由で観測。実装はprocess内slotのみ（永続化なし、preference/store追加なし）でdiff確認済み。
- **AC-7（照合関数がinterface経由でテスト。fixture・境界値・typed拒否・決定性）**: **PASS**。`HomeEditUndoPlannerTest`（fixture + 境界 + typed拒否 + `verificationIsDeterministicForIdenticalInputs`）、`HomeEditUndoRecordTest`、`HomeEditUndoRecoveryTextTest` 5 tests（復元結果→typed文言の全分岐）。`HomeEditUndoStage2Validator` も `stage2ProceedsOnlyOnTheStage1Result` で検証。
- **AC-8（ベンチマークB5実測記録。snackbarの時間窓の有無を含む）**: **PASS（記録あり）**。`docs/assessment/450-edit-undo-b5-evidence.md`: fork実装のB5 = 削除1 / 移動1（undo snackbar tap 1操作。目標1操作を達成）、時間窓あり（上流 `Snackbar` 同一機構、accessibility設定準拠実時間・基準4000ms）。対象commit上の記録であることのみ確認（実測操作の再現は本auditの対象外）。
- **AC-9（a11y: TalkBack読めるlabel/action、accessibility準拠の表示時間、理由が文字列リソース由来）**: **PASS（エミュレータ）/ 実機はowner確認pending**。strings 10件（label + 9エラー文言）を `lawnchair/res/values/strings.xml` と `values-ja/strings.xml` の両方に追加（diff確認）。空でないことのJVM oracle `HomeEditAcceptanceOraclesTest.allUndoStringsAreDefinedAndNonEmptyInEnAndJa`。TalkBack証跡 `450-edit-undo-ac9-talkback-snackbar.png` / `ac9-accessibility-dump.xml` / `ac9-node-record.txt` が存在。`Snackbar.show` のaccessibility準拠timeoutは上流機構の再利用（変更なし）。失敗表示は全て `R.string.*` 由来（`HomeEditUndoExecutor.reportUndoRejection` / `reportFailureKey` / `HomeEditUndoRecoveryText.kt`）。
- **AC-10（文書更新）**: **PASS（実装時点の範囲）**。`CONTEXT.md` +12（domain language 3語）、`DESIGN.md` +7（homeedit undo構成）、`docs/product/requirements.md` FR-020 → implemented。spec本文のstatusは `accepted` のまま（workflow運用どおりclose時の最終PRで `implemented` へ更新する物であり、AC-10の条件「受入・実装を経てimplementedとなるとき」の充足はclose PRで確定する。`validate_repo_contract.py` はCI `validate-repo-contract` jobでPASS確認済み）。
- **AC-11（世代紐付け。置換前recordの消費要求は零書込みの無操作、現行recordを消費しない）**: **PASS**。`HomeEditUndoRecord.compareAndConsume` は世代不一致でnullかつslot無変更（CAS loop、コード確認）。executor `start()` はconsume失敗で即return（LAUNCHER_UNDO logも書かない零書込みの無操作）。JVM oracle `HomeEditUndoRecordTest`。executor層の競合oracleは `EditSurfaceUndoInstrumentationTest.productionConfirmFlowUndoObservesTheInternalExpiredReason` 系と `undoUnderSerializationContentionReportsWriterBusyWithZeroWrite`。
- **ADR-0013 契約5（fail-closedな逆操作。1操作Undo、実行前照合、typed失敗、逆row書込み）**: **PASS**。契約2〜4（二段階検証・1 transaction・validation→admission→admission内再検証→model/DB変更の順序）も逆操作3タスクの構造で満たす（Scope節参照）。上流の `prepareToUndoDelete`/`commitDelete`/`abortDelete` 遅延commit機構は無変更（diff内に触れていないことを確認）。
- **ADR-0014 case B（確定1回=適用1回=復元点1点。編集画面確定の適用はorganizer apply経路）**: **PASS**。undoは `RecoveryRequest(pointId, expectedRevision)` で既存の復元mutation entryを流すのみ。復元点・transaction・相関reloadは復元経路の既存契約（spec 13）に従い、本PRは復元経路を新設・変更していない（`LayoutApplicationModule` への追加はreceipt accessorとtest-only mutex注入のみ）。
- **安全規約の例外の適用確認（bulk delete/reinsert不存在）**: diff全体へのgrepでproduction側の新規削除は (a) undo create folderのトランザクション内フォルダ行DELETE（1行、`itemIdMatch` 限定）、(b) 既存remove操作へのDELETE直前payload capture追加、の2箇所のみ。`db.delete(Favorites.TABLE_NAME, null, null)` の全件削除はinstrumentation testのfixture seeding/restore（`EditSurfaceUndoInstrumentationTest` / `HomeEditUndoEvidenceToolingTest` のprivate helper）のみで、production経路に洗い替え・無条件全削除は存在しない。

## Executed test surface

独立sessionによる対象headでの再実行（emulator-5554 / AVD `nunu_qpr2_api36_1`、API 36.1。APKは `Lawnchair.15.Dev.(37a670b).github.debug.apk` としてインストールされることを確認）:

```bash
git rev-parse HEAD   # 37a670b35f0a8c89e3d057a7e150c9cdc6a3e81f
./gradlew installLawnWithQuickstepGithubDebug installLawnWithQuickstepGithubDebugAndroidTest
# → BUILD SUCCESSFUL in 51s

adb -s emulator-5554 shell am instrument -w -e class \
  app.lawnchair.homeedit.EditSurfaceUndoInstrumentationTest,com.android.launcher3.DirectEditUndoModelWriterTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
# → app.lawnchair.homeedit.EditSurfaceUndoInstrumentationTest:..................
#   com.android.launcher3.DirectEditUndoModelWriterTest:...........
#   Time: 292.949
#   OK (29 tests)

adb -s emulator-5554 shell am instrument -w -e class \
  app.lawnchair.homeedit.HomeEditUndoEvidenceToolingTest \
  app.lawnchair.debug.test/app.lawnchair.migration.DeckRetirementTestRunner
# → app.lawnchair.homeedit.HomeEditUndoEvidenceToolingTest:.........
#   Time: 164.652
#   OK (9 tests)

./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests "app.lawnchair.homeedit.*" -q
# → exit 0

python3 tools/repo-contract/measure_upstream_patch_surface.py --verify
# → PASS: measurement completed with complete bridge ownership. (exit 0)

python3 tools/repo-contract/measure_upstream_patch_surface.py --target 37a670b35f0a8c89e3d057a7e150c9cdc6a3e81f --enforce-baseline
# → Baseline delta: files +0, additions +0, deletions +0
#   PASS: measurement completed with complete bridge ownership. (exit 0)
```

JVM unit testの内訳（CI `organizer-unit-tests` gateと同一scope）: `HomeEditUndoPlannerTest` 17 / `HomeEditUndoRecordTest` 6 / `HomeEditUndoRecoveryTextTest` 5 / `HomeEditAcceptanceOraclesTest` +4（undo関係）ほか、既存homeedit test群すべてgreen。patch-surface計測のbridge group `homeedit-edit-undo` は8 files / +932 / -3、baseline差分ゼロ。

## Findings

1. **merge gate red（監査時点。解消手続きは明確）**: run [36664678412](https://github.com/nunu1733/NunuLauncher/actions/runs/36664678412) で2つのemulator laneが失敗し `final-status` = failure。高リスク契約どおり、このhead上で `final-status` が成功するpull_request runの取得（`gh run rerun --failed` 等による失敗laneの再実行）がmergeの前提である。
   - `organizer-instrumentation-onboarding-proposal-tests`（8m1s）: `IllegalStateException: ... launcher-resume-timeout ... focusedWindow=...NexusLauncherActivity ... LawnchairLauncher did not reach an attached, laid-out RESUMED state after HOME launch`。フォーカスがデフォルトlauncher（NexusLauncher）に残ったままのemulator起動環境failureであり、本PRの変更対象（undo書込み・organizer適用・フォルダbind）と接点のないboot/resume環境class。#477で分類済みのlane不安定家族と同じ環境/harness起因と判断する。
   - `organizer-instrumentation-manual-organization-ui-tests`（17m32s）: 153 tests中1件のみ失敗（`MissingAppSelectionInstrumentationTest.zeroCandidatesContinuesWithoutShowingTheSelectionSurface`、`CalledFromWrongThreadException ... Calling: DefaultDispatcher-worker-8`）。本PRのこのtestへの変更はfakeへの `error("not reached")` 2メソッド追加のみで、到達時の例外型もメッセージも異なるため本変更を原因と断定できない。当該testはselection面のzero-write契約でありapplyを呼ばない（`applyCalls == 0` をassert）ため、本PRのproduction変更（adapter正規化・receipt追加）はこの経路に到達しない。同一laneは #477（closed）でemulator/UI-timing flake家族として記録されており、直近でも別commit `2dbc82891c`（branch `issue-477-manual-lane-flake`）で別testが同様に単発失敗している。**ただし本auditはこの1件を環境flakeと証明できていない**。green re-runで解消されることをmerge前に確認すること（解消しなければ別Issueでtriage）。
2. **既知の残課題（実装側が記録済み。`docs/assessment/450-edit-undo-b5-evidence.md`。本auditで実装との不一致がないことを確認）**:
   - 編集画面（#449経路）の1項目選択での新規フォルダ作成は、organizer作成フォルダにOPTIONS bitが付かないため適用直後のbind-time cleanup書込みがpost-write検証と衝突し、適用がself-recover（VERIFIED_FAILED→復帰）する。2項目選択では成立。#449表面のowner triage item。
   - 削除undoの再挿入は新idで行われる（契約どおり）。行idの不変性を仮定する経路は存在しないが、以降の機能でidを追う場合は注意。
   - 実機owner確認（AC-1/AC-9の実機項目・TalkBack含む）がpending。owner decision item。
3. **新規観察（本auditによる。いずれも阻塞りではない）**:
   - `HomeEditUndoExecutor` は呼出しごとに `HomeEditUndoExecutor(launcher)` を新規生成する（`HomeEditUndoSnackbar.show`）。stateは `lazy` のみで実害はないが、実行経路の単一性はsnakebar→executorの呼出し側に依存する。将来の複数入口追加（Next）時にsingleton化を再検討するのがよい。
   - `HomeEditExecutor.kt` の `recordUndoEvidence` 後に `} private fun showDestinationPage` のように同一行に波括弧と宣言が続く書式箇所が残る（`spotlessCheck` はCI `check-style` PASS。動作・規約違反ではないが可読性の指摘）。
   - spec本文のstatusは現時点で `accepted`（FR-020のみrequirements.mdでimplemented化済み）。close時の最終PRでspec statusの `implemented` 化と残余docs更新を行うこと（AC-10の完全充足はその時点）。
4. **検証の限定**: 本auditはエミュレータ上のtest再実行とコード照合であり、実機でのTalkBack・B5操作は再実施していない（owner確認item）。`final-status` のgreen runは監査時点で存在しないため、Headerの機械要件は未充足のまま記録する。

## 判定

**pass-with-notes → GO（追跡更新 2026-09-30）** — 全12受入条件（AC-1〜AC-11+AC-10、ADR-0013契約5、ADR-0014 case B）を対象headの実コードとtest oracleで確認し、独立再実行（instrumentation 29+9、unit、patch-surface）はすべてgreen。機械要件も充足: run 36664678412 の再実行で対象head上の `final-status` = success（全15 job pass）。残るnoteは実機owner確認など対象外項目のみ。
