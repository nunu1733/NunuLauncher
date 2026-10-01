# High-risk audit: PR #498 新規アプリの配置先ポリシーの実装（ADR-0015、B1/B6）

> Status: accepted
> Audit date: 2026-10-02

- Auditor: 独立audit session（実装sessionとは別作業。solo保守のため独立sessionによる再実行・再確認）
- PR: https://github.com/nunu1733/NunuLauncher/pull/498
- Head SHA: 7bf27ae18463f0f047340ae310b90fdefc30d791
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36919528350
- Criteria: specs/497-new-app-destination-policy-impl/spec.md FR-008, NFR-014, AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11; docs/adr/0015-new-app-destination-policy.md NFR-010; docs/adr/0013-direct-edit-write-contract.md NFR-013; docs/adr/0016-layout-application-test-surface.md AC-7

## Scope

- 対象diff: `git diff origin/main...HEAD`（base/main → head `7bf27ae18463f0f047340ae310b90fdefc30d791`）。29 files changed, 2789 insertions, 14 deletions。主な変更path:
  - `src/com/android/launcher3/model/ModelWriter.java`（高リスクpath。新op `addPendingInstallForDirectEdit` + `DirectEditAddPendingInstallTask`、+163行）
  - `src/com/android/launcher3/model/AddWorkspaceItemsTask.java`（1分岐のbridge + post-admission bind、+71/-14行）
  - `src/com/android/launcher3/model/ItemInstallQueue.java`（enqueue時snapshot capture、flush時route付与、+76行）
  - `src/com/android/launcher3/model/DirectEditContract.java`（destination契約型、+195行）
  - `src/com/android/launcher3/util/PersistedItemArray.java`（entry attribute extension hook、+39行）
  - `lawnchair/src/app/lawnchair/homeedit/`（`AppDestinationPlanner.kt`、`AppDestinationAdapter.kt`、`AppDestinationNotice.kt`、`ui/AppDestinationPreference.kt`）、`HomeScreenPreferences.kt`、`PreferenceManager.kt`、`LawnchairProcessInitializer.kt`、strings（values / values-ja）
- 確認したruntime書き込み経路: `favorites`への新規1行INSERTのみ（`DirectEditAddPendingInstallTask.runImpl` → `getModelDbController().insert(Favorites.TABLE_NAME, ...)`。folder routeはcontainer=folder id + 末尾rank、default routeは`CONTAINER_DESKTOP` + `WorkspaceItemSpaceFinder`座標）。schema/migration変更なし。`organizerLockState`列は書かない。既存行の書き換えなし。`PackageUpdatedTask.java`への変更なし（diff外を確認）。
- CI run（head SHA一致を`gh run view`で確認。event: pull_request、conclusion: success）で走ったlane: `organizer-unit-tests`、`organizer-instrumentation-shared-writer-tests`（新destination class群 `com.android.launcher3.InstallDestinationModelWriterTest`、`com.android.launcher3.model.InstallDestinationQueueTest`、`app.lawnchair.homeedit.AppDestinationNoticeTest`が`.github/workflows/ci.yml`のshared-writer class listへ追加されていることをdiffで確認）、`build-debug-apk`、`validate-repo-contract`、`check-style`、`final-status`（success）。他のorganizer instrumentation lane（production-input / method-choice / db-migration / manual-organization-ui / onboarding-proposal / category-override / restore-capture / exchange-import-ui / reservation-recovery）もすべてsuccess。

## Criteria check

- **AC-1: 設定の3択ポリシー行 — PARTIALLY VERIFIED**

- `HomeScreenPreferences.kt:87` で「ホームにアイコンを追加」スイッチ（`pref_add_icon_to_home`）と同じ画面に `DestinationPolicyPreference(enabled = lockHomeScreenAdapter.state.value.not())` を配置。ロック中は無効。独立した「追加しない」toggleは存在しない（「追加しない」選択は `addIconToHomeAdapter.onChange(false)` のみ。`AppDestinationPreference.kt` のchoice dialog。抑制分岐の新設なし）。
- フォルダ選択dialogはDockフォルダを除外（`HomeEditExecutor.kt` `fetchAllFolderOptions` が `container == HomeEditContainers.DESKTOP` のみ）し、profile注記（`destination_policy_other_profile`）と「指定をやめる」付き。文言はすべてstring resource由来（`values` + `values-ja`）。
- 3択とスイッチの表示整合: summary分岐が `!addIconOn → dont_add` / `selectedFolderId != null → folder` / else upstream で常時整合。
- 未確認（owner-pending）: エミュレータスクリーンショット・実機での表示・操作確認。

- **AC-2: 指定フォルダへの追加操作0配置 — PARTIALLY VERIFIED**

- 書込み経路harness `tests/organizer-instrumentation/com/android/launcher3/InstallDestinationModelWriterTest.java` の `folderTargetInsertAppendsAtTailRankWithoutLockColumnChange`（AndroidJUnit4 + test DB）がINSERT 1行・末尾rank・既存子rank不変・lock列不変を検証。CI run 36919528350 の `organizer-instrumentation-shared-writer-tests` で成功。
- ModelWriter側は末尾rank = `folderChildCount(folderId)`、単一INSERT。
- 未確認（owner-pending）: 実機でのB1挙動確認と会計（追加操作0）のPR記録。

- **AC-3: フォールバックとtyped理由・一度だけの通知 — VERIFIED**

- 純粋計画関数 `AppDestinationPlanner.plan` が `FOLDER_MISSING` / `PROFILE_MISMATCH` / `DOCK_FOLDER` / `CONSTRAINT_VIOLATION` / `SNAPSHOT_INVALID` をtypedに返す。package名は出力に含まない（`AppDestinationBridge.ResultCallback` のFileLog出力は理由コードのみ）。
- one-shot通知state: `AppDestinationNotice.postPending` / `consumePending`（表示時に消費）。`AppDestinationNoticeTest` で検証。
- 既定ポリシー時: `route()` がupstream snapshotに対してnullを返しstock経路のまま。記録・通知・新規書込み経路は発生しない。
- 同名フォルダ再作成で指定が復活しない: 指定はid（`pref_new_app_destination` = `folder:<id>`）で保持。

- **AC-4: 再flush決定性・first enqueue wins・snapshot分類 — VERIFIED**

- captureは `ItemInstallQueue.queueItem(String,UserHandle)` のみ（manual `AddItemActivity` overloadはcaptureしない。diffで確認）。snapshotはqueue XML attribute `destination_policy` として永続化（`PersistedItemArray.EntryExtension`。旧format readerは未知attributeを無視するため後方/前方互換）。
- flushは `attachDestinationRoute` がpersisted snapshotを読むだけでcurrent policyを再読しない。
- first enqueue wins: `InstallDestinationQueueTest.enqueuePersistsSnapshotAndDuplicateKeepsFirst` が検証。`addToQueue`の重複排除の意味論は変更していない。
- 分類: `AppDestinationClassifier.classify` — MISSING/INVALID → `UpstreamDefault(SNAPSHOT_INVALID)`、IDENTITY_MISMATCH → `Reject(SNAPSHOT_INVALID)`（無変更typed failure）。`AppDestinationStage2Validator` 経由で実装どおり接続。基底entry自体のdecode不能は`PersistedItemArray.read`の既存読み飛ばしのまま（変更なし）。

- **AC-5: 書込み構造（ADR-0013契約4） — VERIFIED**

- (a) admission前無変更: `DirectEditAddPendingInstallTask.runImpl` は最初に `mValidator.validate(buildDirectEditSnapshot())` のみ。`mPayload.id` 採番・field代入・INSERT・screen id採番はすべてvalidateの後。新opは既存 `addItemToDatabase`（admission前の`updateItemInfoProps`・ID採番・bindItems callback構造）を使わない。
- (b) admission内の再計画: validatorはadmission内で現状態snapshotに対して同一の純粋計画関数（`AppDestinationPlanner.plan`）を再実行する。
- (c) upstream defaultの座標はadmission内で `WorkspaceItemSpaceFinder.findSpaceForItem` を上流そのままの意味論で実行（fork側の走査複製なし。QSB時1ページ目除外・満杯時新規screen割当はfinder内）。新規screen idは `workspaceScreens` のlocal list差分からadmission内で取得し、`addedScreens` listもadmission内localで、admission成立前に漏れない。
- (d) Rejectは真のinvariant failureのみ（`REJECT`時は無変更・typed理由のみでreturn）。
- (e) post-success bind: `AddWorkspaceItemsTask` のpolicy routeは `addedItemsFinal` への事前追加を使わず、書込み成功callbackが最終配置 + 新規screen idを運び、`bindAppsAdded` を1回だけ `scheduleCallbackTask` で行う（新規screenを先に追加）。defer中はbind・folder refreshが発生しない（callbackはwriter報告後のみ発火）。既定routeのstock経路は変更なし。単一行INSERTでatomic。失敗時はtyped `FAIL_WRITE_FAILED` で握りつぶしなし。
- canonical test: `InstallDestinationModelWriterTest` — `deferredWriteReplansToUpstreamDefaultWhenFolderDeleted`（ADR-0015要求テスト表1行目: defer→replan oracle）、`upstreamDefaultWritesAtUpstreamPlacementInsideAdmission`、`fullScreensFallbackAllocatesNewScreenInsideAdmission`、`rejectWritesNothingAndReportsTypedFailure`。shared-writer laneでCI成功。

- **AC-6: bridgeの限定 — VERIFIED**

- bridgeは `ItemInstallQueue`（capture/route）→ `AddWorkspaceItemsTask`（1分岐）の1箇所。`PackageUpdatedTask.java` はdiffに含まれない（`git diff origin/main...HEAD --stat` で確認）。手動配置overload（`queueItem(ShortcutInfo)` / widget）はcapture対象外。

- **AC-7: テスト構成（ADR-0016 canonical surface） — VERIFIED**

- canonical owner: `tests/organizer-instrumentation/` の `InstallDestinationModelWriterTest.java`（5 test、AndroidJUnit4 + test DB）、`model/InstallDestinationQueueTest.java`（2 test）、`app/lawnchair/homeedit/AppDestinationNoticeTest.kt`。いずれもorganizer shared-writer lane（ADR-0016が正本とするsurface）に属し、`.github/workflows/ci.yml` のclass listへ追加済み（diffで確認）。CI run 36919528350 で `organizer-instrumentation-shared-writer-tests` success。
- 補助JVM test: `tests/unit/app/lawnchair/homeedit/AppDestinationPlannerTest.kt`（17 @Test。planner / 分類 / wire format往復 / stage-2決定意味論）。`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.AppDestinationPlannerTest' --tests 'app.lawnchair.homeedit.AppDestinationNoticeTest'` をaudit sessionで再実行しBUILD SUCCESSFUL（17 test含む、green）。
- 新規CI laneなし（既存shared-writer laneへのclass追加のみ）。重複scenarioなし。coordinator排他・process死は既存shared-writer seam / process-death smokeの慣行のまま（変更なし）。

- **AC-8: ベンチマーク — PARTIALLY VERIFIED**

- B6目標の確定: `docs/engineering/editing-burden-benchmark.md` §6 のB6セルが「追加操作0（2026-10-02にspec 497で確定）」へ更新済み（diffで確認）。
- 未確認（owner-pending）: B1/B6の実装後の会計（決定的再算出）とベンチマーク§7準拠のエミュレータ実行記録（fixture seeding + install実証）のPR本文への記録。実機確認はowner確認に含める。

- **AC-9: patch surface（PR本文記録前提） — PARTIALLY VERIFIED**

- `python3 tools/repo-contract/validate_writer_inventory.py` をaudit sessionで再実行: `PASS: 19 writer files verified against allowlist (1554 source files scanned, 0 errors, 0 warnings)`。書込みは既存allowlist内の `ModelWriter.java` に留まり、allowlist変更は不要だった（新規DB書込みfileを作っていないことをdiffで確認）。
- `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` のPR本文への記録はPR本文で確認する事項であり、本auditではPR本文の記載内容までは機械検証していない（src/側増分は上記scopeのbridge+契約型でNFR-010の理由づけ対象）。

- **AC-10: 文書更新 — VERIFIED**

- `docs/product/requirements.md`: FR-008 → `implemented`（2026-10-02、#497/PR #498根拠。diffで確認）。
- ベンチマーク§6のB6目標セル更新済み。ADR-0016新設（`docs/adr/0016-layout-application-test-surface.md`、status: accepted）+ ADR-0013/ADR-0015のChange historyへ関連リンク1行ずつ（diffで確認。両ADRの本文は不変）。
- `DESIGN.md`（module構成・gate行、+10行）、`CONTEXT.md`（+12行）更新済み。`validate-repo-contract` jobがCIでsuccess。

- **AC-11: アクセシビリティ — PARTIALLY VERIFIED**

- ポリシー行・dialog・通知文言はすべてstring resource由来（`lawnchair/res/values/strings.xml` +18行、`values-ja/strings.xml` +17行。diffで確認）。`AppDestinationNoticeTest` が通知文言のリソース由来を検証。
- 未確認（owner-pending）: エミュレータTalkBack読み上げ確認と実機確認。

## Executed test surface

- `gh repo view -R nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` 確認（GitHub操作前の規約確認）。
- `gh pr view 498 -R nunu1733/NunuLauncher --json headRefName,headRefOid,state` → headRefOid = `7bf27ae18463f0f047340ae310b90fdefc30d791`（audit対象headと一致）、branch `issue-497-new-app-destination-policy-impl`、state OPEN。
- `gh run view 36919528350 -R nunu1733/NunuLauncher --json conclusion,headSha,event,jobs` → conclusion `success`、headSha = audit対象head、event `pull_request`。job: `final-status` を含む全job success。`organizer-instrumentation-shared-writer-tests` に新destination class（`InstallDestinationModelWriterTest`、`model.InstallDestinationQueueTest`、`AppDestinationNoticeTest`）が `.github/workflows/ci.yml` のrunner引数class listに含まれることをdiffで確認。
- `git diff origin/main...HEAD --stat` → 29 files changed, 2789 insertions(+), 14 deletions(-)。ModelWriter / AddWorkspaceItemsTask / ItemInstallQueue / PersistedItemArray / DirectEditContract / homeedit module / tests / docs / strings を含む。全文diffを確認（partial viewではない）。
- `python3 tools/repo-contract/validate_writer_inventory.py` → `PASS: 19 writer files verified against allowlist (1554 source files scanned, 0 errors, 0 warnings)`。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.AppDestinationPlannerTest' --tests 'app.lawnchair.homeedit.AppDestinationNoticeTest'` → BUILD SUCCESSFUL（macOS arm64、JDK 21。audit sessionでの独立再実行。planner 17 testを含みgreen）。
- instrumentation（`InstallDestinationModelWriterTest` 等）は本audit sessionではローカル実行せず、CI run 36919528350 の `organizer-instrumentation-shared-writer-tests`（audit対象head上、success）を証跡とする。

## Findings

- 実装はspec/ADRの契約に整合している。特に: (1) 新op `addPendingInstallForDirectEdit` はADR-0013契約4どおりadmission内で検証→ID採番→INSERT→model sync→verifierが完結し、既存 `addItemToDatabase` のadmission前変更構造を踏んでいない。(2) 既定配置はfork側の走査複製を作らずadmission内で上流 `WorkspaceItemSpaceFinder` の意味論を使い、新規screen idはadmission前に漏れない。(3) `PackageUpdatedTask.java` は無変更。(4) reject時に `folderChildCount` 等のmodel読み取りだけが発生し書込みはしない（無変更）。
- 軽微な観察（merge blockerではない）: `AppDestinationBridge.ResultCallback.refreshFolderIcon` のUI側folder refreshは `folder.getContents().lastOrNull()` を新しい子と仮定する（model thread側のsilent addが末尾追加であるため成立。#448 precedentどおりview-only）。将来rank操作が増える場合は明示的な子id運搬がより堅牢。
- owner-pending / 未検証項目（merge判定時にowner確認として別途記録が必要）:
  - 実機でのB1確認と会計（追加操作0）のPR記録（AC-2、AC-8の一部）。
  - ベンチマーク§7準拠のエミュレータ実行記録（fixture seeding + install実証。B1/B6会計。AC-8）。
  - TalkBack読み上げ確認（エミュレータ）と実機での表示・操作確認（AC-1、AC-11）。
  - エミュレータスクリーンショットによる設定構造確認（AC-1）。
  - `measure_upstream_patch_surface.py` 出力のPR本文記録（AC-9。PR本文は本auditの機械検証対象外）。
- JVM test（planner/notice）はaudit sessionで独立再実行しgreen。instrumentation canonical test群はCI run 36919528350（audit対象head上）で成功を確認した。
