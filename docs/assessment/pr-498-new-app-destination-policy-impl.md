# High-risk audit: PR #498 新規アプリの配置先ポリシーの実装（ADR-0015、B1/B6）

> Status: accepted
> Audit date: 2026-10-02

- Auditor: 独立audit session（実装sessionとは別作業。solo保守のため独立sessionによる再実行・再確認）
- PR: https://github.com/nunu1733/NunuLauncher/pull/498
- Head SHA: 72689feb85a06bac49344e1cce938bab5c9ceac0
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36931656649
- Criteria: specs/497-new-app-destination-policy-impl/spec.md FR-008, NFR-014, AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8, AC-9, AC-10, AC-11; docs/adr/0015-new-app-destination-policy.md NFR-010; docs/adr/0013-direct-edit-write-contract.md NFR-013; docs/adr/0016-layout-application-test-surface.md AC-7

## Scope

- 対象diff: `git diff origin/main...HEAD`（base/main → head `72689feb85a06bac49344e1cce938bab5c9ceac0`）。主な変更path:
  - `src/com/android/launcher3/model/ModelWriter.java`（高リスクpath。新op `addPendingInstallForDirectEdit` + `DirectEditAddPendingInstallTask`）
  - `src/com/android/launcher3/model/AddWorkspaceItemsTask.java`（1分岐のbridge + post-admission bind）
  - `src/com/android/launcher3/model/ItemInstallQueue.java`（enqueue時snapshot capture、flush時route付与）
  - `src/com/android/launcher3/model/DirectEditContract.java`（destination契約型、`isValidUpstreamSnapshot`）
  - `src/com/android/launcher3/util/PersistedItemArray.java`（entry attribute extension hook）
  - `lawnchair/src/app/lawnchair/homeedit/`（`AppDestinationPlanner.kt`、`AppDestinationAdapter.kt`、`AppDestinationNotice.kt`、`AppDestinationSettingsText.kt`、`ui/AppDestinationPreference.kt`）、`HomeScreenPreferences.kt`、`PreferenceManager.kt`、`LawnchairProcessInitializer.kt`、strings（values / values-ja）
- 確認したruntime書き込み経路: `favorites`への新規1行INSERTのみ（`DirectEditAddPendingInstallTask.runImpl` → `getModelDbController().insert(Favorites.TABLE_NAME, ...)`。folder routeはcontainer=folder id + 末尾rank、default routeは`CONTAINER_DESKTOP` + `WorkspaceItemSpaceFinder`座標）。schema/migration変更なし。`organizerLockState`列は書かない。既存行の書き換えなし。`PackageUpdatedTask.java`への変更なし（diff外を確認。再auditでも改めて確認）。
- CI run（head SHA一致を`gh run view`で確認。event: pull_request、conclusion: success）で走ったlane: `organizer-unit-tests`、`organizer-instrumentation-shared-writer-tests`（destination class群 `com.android.launcher3.InstallDestinationModelWriterTest`、`com.android.launcher3.model.InstallDestinationQueueTest`、`app.lawnchair.homeedit.AppDestinationNoticeTest`が`.github/workflows/ci.yml`のshared-writer class listへ含まれることを確認）、`build-debug-apk`、`validate-repo-contract`、`check-style`、`final-status`（success）。他のorganizer instrumentation lane（production-input / method-choice / db-migration / manual-organization-ui / onboarding-proposal / category-override / restore-capture / exchange-import-ui / reservation-recovery）もすべてsuccess。

## Re-audit note（2026-10-02、head `72689feb85a06bac49344e1cce938bab5c9ceac0`）

初回audit（head `7bf27ae18463f0f047340ae310b90fdefc30d791`、CI run 36919528350）の後、Phase 2 review round 1の修正（commit `72689feb85`、delta `git diff 7bf27ae184..72689feb85`: 12 files, +457/-70）が入ったため、deltaに絞った再auditを行った。

### Delta検証（4件の修正が実在しscopeどおりであることをdiff全文で確認）

1. **指摘1（高、AC-6）: flush時のroute付与対象のapplication限定** — `ItemInstallQueue.attachDestinationRoute` は新引数 `itemType` を受け、`itemType != Favorites.ITEM_TYPE_APPLICATION` のときresolver呼出し前にstock pathへ返す（`src/com/android/launcher3/model/ItemInstallQueue.java:191`）。filterは**item type**をキーにしておりsnapshot有無ではない。手動配置overload（deep shortcut / widget）はsnapshot attributeを持たない構造だが、仮にstray attributeが付いてもtype判定で排除される。旧formatの自動アプリentry（snapshot=null）はtype=APPLICATIONのためrouteされ、AC-4どおりstage-2のtyped `SNAPSHOT_INVALID`フォールバックへ流れる。regression oracle: `InstallDestinationQueueTest.attachRouteReadsOnlyApplicationEntriesAndTheirSnapshot` がdeep shortcut / widget + snapshotAの組合せで `routedSnapshot.get()` がnull（resolver不呼出し）まで検証。
2. **指摘2（中）: stock bypassの完全decode + identity一致要求** — prefix照合の `DirectEditContract.isUpstreamSnapshot` は削除され（全ソースのgrepで残留0）、`isValidUpstreamSnapshot(raw, baseUserSerial, basePackageName)`（`DirectEditContract.java:329`）が `parseDestinationSnapshot` による完全decode → kind=UPSTREAM → user serial一致 → package一致を要求する。production resolverのstock bypass（`AppDestinationAdapter.kt:165`）とproduction bridgeのflush（`ItemInstallQueue.attachDestinationRoute`経由）の両方がこのpredicateを使う。corrupt upstream → routeされstage-2が `SNAPSHOT_INVALID` → `DEST_ACTION_DEFAULT` 再計画、identity不一致 → routeされtyped reject（無変更）。oracle: `InstallDestinationQueueTest.productionRouteBypassesStockOnlyForValidUpstreamSnapshots`（production bridge `AppDestinationBridge.install` 経由でresolver実体を検証）。既知の破綻型（`Long.parseLong` の非数値input）は `parseDestinationSnapshot` 内でcatchされtyped扱いになることをtestが固定。
3. **指摘3（中、AC-3）: フォルダ実在とtitleの区別** — 新純粋mapper `AppDestinationSummaryState.resolve(addIconOn, designatedFolderId, folderExists, folderTitle)` が `FOLDER_MISSING`（id非実在 → 「選び直し」summary）を返し、`AppDestinationPreference.kt` は実在判定を `folderIcon(launcher, it) != null`（id解決）に寄せた。titleは存在する場合のみ `FOLDER_NAMED` に使われ、同名再作成でもid基準のため誤復活表示しない。新resource `destination_policy_summary_folder_missing` をvalues / values-jaへ追加。
4. **指摘4（中、AC-1/AC-11）: JVM oracle新設** — `lawnchair/src/app/lawnchair/homeedit/AppDestinationSettingsText.kt`（pure mapper）+ `tests/unit/app/lawnchair/homeedit/AppDestinationSettingsTextTest.kt`（6 test）。AC-1の3択↔toggle整合状態遷移（toggle OFFはいかなるstored policyでもDONT_ADD、指定はON時のみ表示）とAC-11のresource mapping（summary 5状態→resource、notice理由キー→resource、unknown keyはfail-closedでsnapshot notice）を検証。`AppDestinationPreference.kt` は分岐をこのmapperへ委譲（local `noticeText` when句は削除）。新規CI lane・重複instrumentationなし。

### 再確認した既存構造（spot-check。初回auditからcarry-overした部分を明記）

- **再確認（deltaで壊れ得るもの）**: `attachDestinationRoute` のroute付与条件（`usePolicyWrite`時のみ）、capture点が自動追加overload `queueItem(String,UserHandle)` のみであること（`ItemInstallQueue.java:247`。manual `queueItem(ShortcutInfo)` / widget overloadはcaptureしない — 変更なしをdiffの非含有で確認）、`AddWorkspaceItemsTask` / `ModelWriter.java` / `PersistedItemArray.java` / `InstallDestinationModelWriterTest.java` がdelta diffに含まれない（0行差分）ため初回検証結果をそのまま適用。
- **再確認（新head上で直接読み直し）**: `ModelWriter.addPendingInstallForDirectEdit` と `DirectEditAddPendingInstallTask` がADR-0013契約4どおり（admission内でvalidate → 採番 → INSERT → model sync、新opは既存 `addItemToDatabase` を踏まない）であること、`AddWorkspaceItemsTask` のpolicy routeが `addedItemsFinal` 事前追加を使わず書込み成功callback経由で `bindAppsAdded` を1回だけ行うこと（`AddWorkspaceItemsTask.java:213-264`）。
- **再実行**: JVM test（下記Executed test surface）、`validate_writer_inventory.py`（PASS）。
- **carry-over（再導出していない）**: `AddWorkspaceItemsTask` 内の挙動詳細、`PersistedItemArray` attribute hookの互換性、planner/classifierの個別test内容、schema/migration非変更、初回auditのCI run 36919528350でのinstrumentation成功（新headではCI run 36931656649で同等laneがsuccess）。

## Criteria check

- **AC-1: 設定の3択ポリシー行 — VERIFIED（code/test level）**

- `HomeScreenPreferences.kt` で「ホームにアイコンを追加」スイッチ（`pref_add_icon_to_home`）と同じ画面に `DestinationPolicyPreference(enabled = lockHomeScreenAdapter.state.value.not())` を配置。ロック中は無効。独立した「追加しない」toggleは存在しない（「追加しない」選択は `addIconToHomeAdapter.onChange(false)` のみ。`AppDestinationPreference.kt` のchoice dialog。抑制分岐の新設なし）。
- 3択とスイッチの表示整合は、round 1の修正で純粋mapper `AppDestinationSummaryState.resolve`（`AppDestinationSettingsText.kt`）に寄せられ、JVM oracle `AppDestinationSettingsTextTest`（6 test、toggle OFF全patternでDONT_ADD等）がstate遷移を固定。`AppDestinationPreference.kt` のsummary構築はこのmapper経由のみ。
- フォルダ選択dialogはDockフォルダを除外し、profile注記（`destination_policy_other_profile`）と「指定をやめる」付き。文言はすべてstring resource由来（`values` + `values-ja`）。
- 未確認（owner-pending）: エミュレータスクリーンショット・実機での表示・操作確認。

- **AC-2: 指定フォルダへの追加操作0配置 — PARTIALLY VERIFIED**

- 書込み経路harness `tests/organizer-instrumentation/com/android/launcher3/InstallDestinationModelWriterTest.java` の `folderTargetInsertAppendsAtTailRankWithoutLockColumnChange`（AndroidJUnit4 + test DB）がINSERT 1行・末尾rank・既存子rank不変・lock列不変を検証。CI run 36931656649 の `organizer-instrumentation-shared-writer-tests` で成功。
- ModelWriter側は末尾rank = `folderChildCount(folderId)`、単一INSERT。
- 未確認（owner-pending）: 実機でのB1挙動確認と会計（追加操作0）のPR記録。

- **AC-3: フォールバックとtyped理由・一度だけの通知 — VERIFIED**

- 純粋計画関数 `AppDestinationPlanner.plan` が `FOLDER_MISSING` / `PROFILE_MISMATCH` / `DOCK_FOLDER` / `CONSTRAINT_VIOLATION` / `SNAPSHOT_INVALID` をtypedに返す。package名は出力に含まない（`AppDestinationBridge.ResultCallback` のFileLog出力は理由コードのみ）。
- one-shot通知state: `AppDestinationNotice.postPending` / `consumePending`（表示時に消費）。`AppDestinationNoticeTest` で検証。
- 既定ポリシー時: production resolver `route()` が完全decode + identity一致のUPSTREAM snapshotに対してのみnullを返しstock経路のまま（round 1修正。corrupt / identity不一致はstage-2へ流れtypedに扱われる）。記録・通知・新規書込み経路は発生しない。
- 同名フォルダ再作成で指定が復活しない: 指定はid（`pref_new_app_destination` = `folder:<id>`）で保持。設定row側もround 1修正で `FOLDER_MISSING` をid基準で表示し（`AppDestinationSummaryState.resolve`、titleとは独立）、誤復活表示しない。

- **AC-4: 再flush決定性・first enqueue wins・snapshot分類 — VERIFIED**

- captureは `ItemInstallQueue.queueItem(String,UserHandle)` のみ（manual `AddItemActivity` overloadはcaptureしない。新head上で再確認）。snapshotはqueue XML attribute `destination_policy` として永続化（`PersistedItemArray.EntryExtension`。旧format readerは未知attributeを無視するため後方/前方互換）。
- flushは `attachDestinationRoute` がpersisted snapshotを読むだけでcurrent policyを再読しない。旧formatの自動アプリentry（snapshot=null）もAPPLICATION typeでrouteされ、stage-2でtyped `SNAPSHOT_INVALID` → upstream default（`attachRouteReadsOnlyApplicationEntriesAndTheirSnapshot` がmissing snapshot caseを検証）。
- first enqueue wins: `InstallDestinationQueueTest.enqueuePersistsSnapshotAndDuplicateKeepsFirst` が検証。`addToQueue`の重複排除の意味論は変更していない。
- 分類: `AppDestinationClassifier.classify` — MISSING/INVALID → `UpstreamDefault(SNAPSHOT_INVALID)`、IDENTITY_MISMATCH → `Reject(SNAPSHOT_INVALID)`（無変更typed failure）。`AppDestinationStage2Validator` 経由で実装どおり接続。基底entry自体のdecode不能は`PersistedItemArray.read`の既存読み飛ばしのまま（変更なし）。

- **AC-5: 書込み構造（ADR-0013契約4） — VERIFIED**

- (a) admission前無変更: `DirectEditAddPendingInstallTask.runImpl` は最初に `mValidator.validate(buildDirectEditSnapshot())` のみ。`mPayload.id` 採番・field代入・INSERT・screen id採番はすべてvalidateの後。新opは既存 `addItemToDatabase`（admission前の`updateItemInfoProps`・ID採番・bindItems callback構造）を使わない（新head上で直接読み直し済み）。
- (b) admission内の再計画: validatorはadmission内で現状態snapshotに対して同一の純粋計画関数（`AppDestinationPlanner.plan`）を再実行する。
- (c) upstream defaultの座標はadmission内で `WorkspaceItemSpaceFinder.findSpaceForItem` を上流そのままの意味論で実行（fork側の走査複製なし。QSB時1ページ目除外・満杯時新規screen割当はfinder内）。新規screen idは `workspaceScreens` のlocal list差分からadmission内で取得し、`addedScreens` listもadmission内localで、admission成立前に漏れない。
- (d) Rejectは真のinvariant failureのみ（`REJECT`時は無変更・typed理由のみでreturn）。
- (e) post-success bind: `AddWorkspaceItemsTask` のpolicy routeは `addedItemsFinal` への事前追加を使わず、書込み成功callbackが最終配置 + 新規screen idを運び、`bindAppsAdded` を1回だけ `scheduleCallbackTask` で行う（新規screenを先に追加）。defer中はbind・folder refreshが発生しない（callbackはwriter報告後のみ発火）。既定routeのstock経路は変更なし。単一行INSERTでatomic。失敗時はtyped `FAIL_WRITE_FAILED` で握りつぶしなし。
- canonical test: `InstallDestinationModelWriterTest` — `deferredWriteReplansToUpstreamDefaultWhenFolderDeleted`（ADR-0015要求テスト表1行目: defer→replan oracle）、`upstreamDefaultWritesAtUpstreamPlacementInsideAdmission`、`fullScreensFallbackAllocatesNewScreenInsideAdmission`、`rejectWritesNothingAndReportsTypedFailure`。shared-writer laneでCI run 36931656649上でsuccess。

- **AC-6: bridgeの限定 — VERIFIED**

- bridgeは `ItemInstallQueue`（capture/route）→ `AddWorkspaceItemsTask`（1分岐）の1箇所。`PackageUpdatedTask.java` はdiffに含まれない（初回auditと再auditの両方で確認）。
- round 1修正で、手動配置（deep shortcut / widget overload）は **item type基準** でflush時にもpolicy対象外となり、snapshot attributeの有無にかかわらずstock pathを保持（`attachRouteReadsOnlyApplicationEntriesAndTheirSnapshot` がcapture側のみでなくflush側の不routingまで検証）。初回audit時に残っていた「snapshot attributeが手動entryへ混入した場合」の懸念はこの修正で解消。

- **AC-7: テスト構成（ADR-0016 canonical surface） — VERIFIED**

- canonical owner: `tests/organizer-instrumentation/` の `InstallDestinationModelWriterTest.java`（5 test、AndroidJUnit4 + test DB）、`model/InstallDestinationQueueTest.java`（3 test。round 1修正でproduction route testを追加）、`app/lawnchair/homeedit/AppDestinationNoticeTest.kt`。いずれもorganizer shared-writer lane（ADR-0016が正本とするsurface）に属し、`.github/workflows/ci.yml` のclass listに含まれる（確認済み）。CI run 36931656649 で `organizer-instrumentation-shared-writer-tests` success。
- 補助JVM test: `tests/unit/app/lawnchair/homeedit/AppDestinationPlannerTest.kt`（17 @Test。planner / 分類 / wire format往復 / stage-2決定意味論）+ round 1修正で追加の `AppDestinationSettingsTextTest.kt`（6 @Test。AC-1状態遷移 / AC-11 resource mapping、unknown key fail-closed）。audit sessionで `--rerun-tasks` による独立再実行でgreen（下記参照）。新規CI laneなし（既存homeedit unit test gate内）。
- 新規CI laneなし（既存shared-writer laneへのclass追加のみ）。重複scenarioなし。coordinator排他・process死は既存shared-writer seam / process-death smokeの慣行のまま（変更なし）。

- **AC-8: ベンチマーク — PARTIALLY VERIFIED**

- B6目標の確定: `docs/engineering/editing-burden-benchmark.md` §6 のB6セルが「追加操作0（2026-10-02にspec 497で確定）」へ更新済み（初回auditでdiff確認。deltaで非変更）。
- 未確認（owner-pending）: B1/B6の実装後の会計（決定的再算出）とベンチマーク§7準拠のエミュレータ実行記録（fixture seeding + install実証）のPR本文への記録。実機確認はowner確認に含める。

- **AC-9: patch surface（PR本文記録前提） — VERIFIED（機械検証分）**

- `python3 tools/repo-contract/validate_writer_inventory.py` を再audit sessionで再実行: `PASS: 19 writer files verified against allowlist (1555 source files scanned, 0 errors, 0 warnings)`。書込みは既存allowlist内の `ModelWriter.java` に留まり、allowlist変更は不要（round 1修正も新規DB書込みfileを作らない）。
- `measure_upstream_patch_surface.py --target HEAD --enforce-baseline` のPR本文への記録はPR本文で確認する事項であり、本auditではPR本文の記載内容までは機械検証していない（src/側増分はscopeのbridge+契約型でNFR-010の理由づけ対象）。

- **AC-10: 文書更新 — VERIFIED**

- `docs/product/requirements.md`: FR-008 → `implemented`（初回auditでdiff確認。deltaで非変更）。
- ベンチマーク§6のB6目標セル更新済み。ADR-0016新設（`docs/adr/0016-layout-application-test-surface.md`、status: accepted）+ ADR-0013/ADR-0015のChange historyへ関連リンク（初回auditで確認。deltaで非変更）。
- round 1修正でspec Revision 6（Phase 2 review round 1対応記録）を追加済み。`DESIGN.md` / `CONTEXT.md` 更新済み。`validate-repo-contract` jobがCI run 36931656649でsuccess。

- **AC-11: アクセシビリティ — PARTIALLY VERIFIED**

- ポリシー行・dialog・通知文言はすべてstring resource由来。round 1修正で純粋mapper `destinationSummaryText` / `destinationNoticeText`（`AppDestinationSettingsText.kt`）がresource idのみを返し（unknown理由キーはfail-closedでsnapshot notice）、空文字列・内部idがUIへ出ないことを `AppDestinationSettingsTextTest` が検証。新resource `destination_policy_summary_folder_missing` もvalues / values-ja両方に追加済み。
- 未確認（owner-pending）: エミュレータTalkBack読み上げ確認と実機確認。

## Executed test surface

- `gh repo view -R nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main` 確認（GitHub操作前の規約確認）。
- `git rev-parse 72689feb85a06bac49344e1cce938bab5c9ceac0` → 一致。`gh pr view 498 -R nunu1733/NunuLauncher --json headRefOid` → `72689feb85a06bac49344e1cce938bab5c9ceac0`（audit対象headと一致）。
- `gh run view 36931656649 -R nunu1733/NunuLauncher` → conclusion `success`、head = audit対象head、event `pull_request`。job: `final-status` を含む全16 job success。`organizer-instrumentation-shared-writer-tests` success。
- `git diff 7bf27ae184..72689feb85 --stat` → 12 files changed, 457 insertions(+), 70 deletions(-)。deltaの全文diffを確認（`ItemInstallQueue.java`、`DirectEditContract.java`、`AppDestinationAdapter.kt`、`AppDestinationSettingsText.kt`（新規）、`AppDestinationPreference.kt`、strings、`spec.md` Revision 6、test 3件）。`AddWorkspaceItemsTask.java` / `ModelWriter.java` / `PersistedItemArray.java` は0行差分。
- `grep -rn "isUpstreamSnapshot"` → 削除済みpredicateの残留呼出しなし（`isValidUpstreamSnapshot` への全置換を確認）。
- `python3 tools/repo-contract/validate_writer_inventory.py` → `PASS: 19 writer files verified against allowlist (1555 source files scanned, 0 errors, 0 warnings)`。
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*' --rerun-tasks` → BUILD SUCCESSFUL（macOS arm64、JDK 21。再audit sessionでの独立再実行）。`AppDestinationPlannerTest` 12 test / `AppDestinationStage2ValidatorTest` 5 test / `AppDestinationSettingsTextTest` 6 test すべて 0 failures / 0 errors（JUnit XMLから集計。planner testの@Test数は17で、XMLのクラス集計方法の違いによる。全testがgreen）。`AppDestinationNoticeTest` はinstrumentation testであり、CI run 36931656649 の `organizer-instrumentation-shared-writer-tests`（audit対象head上、success）を証跡とする。
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo nunu1733/NunuLauncher --pr-number 498 --head-sha 72689feb85a06bac49344e1cce938bab5c9ceac0` → PASS。
- instrumentation canonical test群（`InstallDestinationModelWriterTest` 等）は本audit sessionではローカル実行せず、CI run 36931656649 の `organizer-instrumentation-shared-writer-tests`（audit対象head上、success）を証跡とする。

## Findings

- 初回audit（head `7bf27ae184`）で指摘されていたowner-pending項目のうち、AC-6の「手動entryへのstray snapshot混入時のflush経路」はround 1修正（item type filter）で解消済み。
- 実装はspec/ADRの契約に整合している。特に: (1) 新op `addPendingInstallForDirectEdit` はADR-0013契約4どおりadmission内で検証→ID採番→INSERT→model sync→verifierが完結し、既存 `addItemToDatabase` のadmission前変更構造を踏んでいない（新headで再確認）。(2) 既定配置はfork側の走査複製を作らずadmission内で上流 `WorkspaceItemSpaceFinder` の意味論を使い、新規screen idはadmission前に漏れない。(3) `PackageUpdatedTask.java` は無変更（再auditでも再確認）。(4) reject時にmodel読み取りだけが発生し書込みはしない（無変更）。
- delta検証での軽微な観察（merge blockerではない）: `ItemInstallQueue.attachDestinationRoute` の新引数 `itemType` はcall site 1箇所（flush）からのみ供給されるpackage-visible staticの引数であり、production flushのtype供給は `PendingInstallShortcutInfo` のconstructorで確定するため、type情報とqueue entryの乖離は構造上発生しない。harnessからの直接呼出し用のvisibilityは既存の設計判断（初回audit時に確認済み）の延長。
- owner-pending / 未検証項目（merge判定時にowner確認として別途記録が必要）:
  - 実機でのB1確認と会計（追加操作0）のPR記録（AC-2、AC-8の一部）。
  - ベンチマーク§7準拠のエミュレータ実行記録（fixture seeding + install実証。B1/B6会計。AC-8）。
  - TalkBack読み上げ確認（エミュレータ）と実機での表示・操作確認（AC-1、AC-11）。
  - エミュレータスクリーンショットによる設定構造確認（AC-1）。
  - `measure_upstream_patch_surface.py` 出力のPR本文記録（AC-9。PR本文は本auditの機械検証対象外）。
- JVM test（planner / stage-2 validator / settings text）はaudit sessionで `--rerun-tasks` 独立再実行しgreen。instrumentation canonical test群はCI run 36931656649（audit対象head `72689feb85` 上）で成功を確認した。
