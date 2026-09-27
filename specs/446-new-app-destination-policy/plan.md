# Implementation Plan: 新規アプリの配置先ポリシー（ADR-0015）の起草・受入

> Issue: #446
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — #446（メモ§4.7）がADR-0015をADR-0013の対象(b)「上流が行う単一アイテムの追加の配置先決定」の所有ADRとして階層Hに割り当てている。本PR自体はrisk label・高リスクpath変更なしのdocs-onlyであるため `high-risk-evidence` gateの機械的発火対象ではなく、evidenceはrepository contract gateで足りる（workflow 適用条件の該当性による判断。tier Lへの下げではない）。保守者の指示により独立監査（別session）を追加実施する。
> Revision 1: 2026-09-27 — 初版。Phase1 reviewへ提出。
> Revision 2: 2026-09-27 — Phase1 review（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5853940527)）の指摘1〜6に対応。指摘1: bridge設計を「既存`addItemToDatabase`の再利用」から「ADR-0013契約4どおりadmission内で再検証と最初のmodel/DB変更を完結する最小のModelWriter操作の追加・使用」へ修正（`addItemToDatabase`がadmission前に`updateItemInfoProps`・ID採番・bindItems callbackを行う事実（`ModelWriter.java:290-313`）をCurrent evidenceへ追記）。指摘3: requirements.mdのFR-008 status変更をscopeから削除（D-015参照更新と#85整合解消のみ）。指摘4: `DESIGN.md` §11へのADR-0015 gate行追加を変更対象へ追加。指摘5: 再flush決定性を「queue時点のpolicy snapshot永続化」へ修正。指摘6: Documentation updates checkboxを未完了へ修正。
> Revision 3: 2026-09-27 — Phase1 review round 2（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854110602)）の指摘1〜3に対応。指摘1: stale時のfallbackを「検証失敗による無変更」から「同一の純粋計画関数のclosed result（`FolderTarget` / `UpstreamDefault(reason)` / `Reject(reason)`）への再計画」へ修正し、bridge判断節とADR修正一覧7を更新。指摘2: policy snapshotのcapture位置を「queue投入（enqueue/`queuePendingShortcutInfo`）時」に明確化し、flush/`getItemInfo`時はpersist済みsnapshotを読むだけでcurrent policyから再生成しないことを明記（Revision 2の「getItemInfoの再構築位置と同じ場所で作成」表現を撤回）。将来実装testへ「snapshot=Aでqueue → policyをBへ変更 → process restart → flush → Aを使用」のoracleとsnapshot欠損/破損時のfail-closed契約を追加。指摘3: Execution checklistへ`DESIGN.md` gate行を追加。
> Revision 4: 2026-09-27 — Phase1 review round 3（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854244634)）の指摘1に対応。
> Revision 5: 2026-09-27 — Phase1 review round 4（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854335016)）の指摘1に対応。Revision 4注記のround 3 review permalinkを参照可能なcomment 5854244634へ修正。契約内容の変更なし。
> Revision 6: 2026-09-27 — Phase 2実施。Execution checklistのPhase 2項目を完了へ更新し、Documentation updatesのcheckboxを実施済みへ更新。ADR-0015のDecision番号が受入時に1〜13から1〜15へ再編されたため、本planとspecのDecision参照を現行番号へ同期した（契約内容の変更なし）。
> Revision 7: 2026-09-27 — Phase 2 review round 1（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854548402)）の指摘1〜2に対応。指摘1: spec/planのDecision参照をADR-0015の現行番号1〜15へ同期（promise icon=11、Undo=14、設定=15、snapshot=10等）。指摘2: PR本文・titleをPhase 2最終PRへ更新（Closes #446、成果物9ファイル、handoff更新）、Execution checklistのPhase 1 clear等を完了へ更新。
> Revision 8: 2026-09-27 — Phase 2 review round 2（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854653373)）の指摘1〜2に対応。指摘1: specのbridge Scenario GivenへDecision 12（module所有）を追加、AC-6 Test oracleをDecision 9/10/12へ更新。指摘2: Review / handoff packetをPhase 2最終状態へ更新（Phase 1 clear/Phase 2 review履歴、CI evidence、Revision番号）。契約内容の変更なし。
> Revision 9: 2026-09-27 — Phase 2 review round 3（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854732932)）の指摘1に対応。Revision 8注記のround 2 review permalinkが参照不能なcomment ID（5854627832）だったため、実際に参照可能なcomment 5854653373へ修正した。契約内容の変更なし。
> Revision 10: 2026-09-27 — Phase 2 review round 4（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854813350)）の指摘1〜2に対応。指摘1: PR本文のReview recommendationへround 3のRequest changes判定とRevision 9対応済み状態を明記。指摘2: 本packetをRevision 9/round 3/current head/次の1手へ同期。
> Revision 11: 2026-09-27 — Phase 2 review round 5（[PR #470 comment](https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854890057)）の指摘1〜3に対応。指摘1: PR本文のReview recommendationへround 4のRequest changes判定とRevision 10対応済み状態を明記。指摘2: 本packetにRevision 10履歴を追加し、round 4/current head/次の1手を同期。指摘3: PR本文のPlan revision・diff数値をcurrent head基準へ更新。

## Current evidence

本planの記載に用いた実コードの根拠は、2026-09-27に本branch上で検証した。ADR-0015本文に収録する `path:line` 根拠のうち主要なものを検証し、すべて実コードと一致した:

- `src/com/android/launcher3/SessionCommitReceiver.java:76-87` — install reasonが`INSTALL_REASON_USER`でない・promise icon済みの場合の追加拒否。`:98-102` — `isEnabled`: `lockHomeScreen`（prefs2/DataStore、`PreferenceManager2.kt:338-342`）と `pref_add_icon_to_home`（`Utilities.getPrefs`、`Utilities.java:932-937`、`LauncherFiles.java:25`）の両方を確認。`:51` — `ADD_ICON_PREFERENCE_KEY` 定義。
- `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt:51` — `addIconToHome = BoolPref("pref_add_icon_to_home", true)`。`BoolPref`は`Utilities.getPrefs`と同一のSharedPreferencesに保存する（`BasePreferenceManager.kt:29,177,185`）。設定UIは`HomeScreenPreferences.kt:74-82`（ロック中は無効化）。
- `src/com/android/launcher3/pm/InstallSessionHelper.java:224-242` — `tryQueuePromiseAppIcon`（trusted installer、USER reason、icon/label、未install、未queueの検証。`:244-259` `verifySessionInfo`。unarchivalはpromise iconを新規に置かない `:229-237`）。`:203-213` `promiseIconAddedForId`/`removePromiseIconId`。`:101-124` `getPromiseIconIds`（`LauncherPrefs.PROMISE_ICON_IDS`、backedUpItem。`LauncherPrefs.kt:270`）。
- `src/com/android/launcher3/model/ItemInstallQueue.java:135-145` — flushが`getItemInfo(mContext)`で`WorkspaceItemInfo`を再構築し`addAndBindAddedWorkspaceItems`へ流す。`:87-88` — `PersistedItemArray("apps_to_install")`でqueue永続化。`:194-215` `queuePendingShortcutInfo`（MODEL_EXECUTOR上でaddToQueue→flushInstallQueue）。`:225-237` `resumeModelPush`/`flushInstallQueue`（disabled flagsでgate）。`:296-318` — `usePackageIcon`（activity listが空）のとき`FLAG_AUTOINSTALL_ICON`を設定。`:345-356` — `PendingInstallShortcutInfo.equals`（user+itemType+intent）。
- `src/com/android/launcher3/LauncherModel.java:205-211` — `addAndBindAddedWorkspaceItems`が`AddWorkspaceItemsTask`をenqueue。`:262-275` `enqueueModelUpdateTask`（model未loadなら破棄）。`:427` — loader開始時に`FLAG_LOADER_RUNNING`でpause（`BaseLauncherBinder.java:422,562`でresume）。
- `src/com/android/launcher3/model/AddWorkspaceItemsTask.java:67-79` — constructorの`WorkspaceItemSpaceFinder`注入点（test用。本番は`:67-69`でdefault構築）。`:100-107` — `shortcutExists`重複除外（`:236-281`本体。promise iconは同一packageで照合 `:274-281`）。`:123-126` — `findSpaceForItem`で空きセル探索。`:139-191` — promise iconのsession検証とinstall済みの場合の本物アイコン差し替え。`:194-197` — `addItemToDatabase(itemInfo, CONTAINER_DESKTOP, screenId, coords[1], coords[2])`。
- `src/com/android/launcher3/model/WorkspaceItemSpaceFinder.java:44-98` — 空きセル探索（`collectWorkspaceScreens`のscreen集合を走査。QSB有効時は1ページ目を除外 `:68-71`相当）。
- `src/com/android/launcher3/model/PackageUpdatedTask.java:261-320` — `OP_ADD`で既存promise icon（`isPromise` `:261`、`FLAG_AUTOINSTALL_ICON` `:290-292`）のintent/statusを更新するだけで配置を動かさない。`:474-486` `updateWorkspaceItemIntent`（intent差し替えのみ）。
- `src/com/android/launcher3/model/ModelLauncherCallbacks.kt:40-42` — `onPackageAdded` → `PackageUpdatedTask(OP_ADD)`（Lawnchair追加file。deck非依存のdispatcher）。
- フォルダの容量とrank: `src/com/android/launcher3/folder/FolderPagedView.java:558` — `getMaxItemsPerPage()`でページング。ハードな上限item数の定数は存在しない。`FolderGridOrganizer.java:50-53,91` — `mMaxItemsPerPage = mMaxCountX × mMaxCountY`（`DeviceProfile.numFolderColumns/numFolderRows`由来。`:59-61`）。既定`4_by_5`グリッドでは3×3（`lawnchair/res/xml/device_profiles.xml:56-58`）。`FolderGridOrganizer.setContentSize`/`calculateGridSize`（`:73-81,96-130`）はmax gridで張り付くがページ数の上限はない。`FolderInfo.add`（`FolderInfo.java:131-143`）はrankを`boundToRange(rank, 0, contents.size())`で末尾に追加でき、既存子のrankを変えない。`Folder.onAdd`（`Folder.java:1513-1522`）が`addOrMoveItemInDatabase`で書く（比較用。新規アプリの追加はこの経路を使わない）。
- フォルダの検出とprofile: `BgDataModel.collections`（`BgDataModel.java:109-112`、id→`CollectionInfo`）。`ItemInfo.container`はuser folderではフォルダのid（`ItemInfo.java:115-121`）。`ItemInfo.user`（`:192`）でprofile比較。`UserCache.getSerialNumberForUser`/`getUserInfo`（`UserCache.java:157,165-170`、`UserIconInfo.isWork()`）。Dock判定は`ItemInfo.isInHotseat()`（`ItemInfo.java:340-343`）。フォルダはDockにも置ける（`WorkspaceLayoutManager.java:110-118`がFolderIconのhotseat配置を処理）。
- フォルダ削除の経路: loader sanitation（`LoaderTask.java:327,729-740`、`ModelDbController.deleteEmptyFolders` `:1009-1034`。items deleted時のみ）、folder UI dissolve（`Folder.java:596-598,983-985,1151`、`LauncherDelegate.java:80-137`、`Launcher.removeItem` `:2105-2155`、`ModelWriter.deleteCollectionAndContentsFromDatabase` `:357-370`）。`docs/product/empty-folder-policy.md` §3がbaseline 4経路を記録（baseline SHA基準。現行branchで3 fileが乖離と明記）。
- lock列: `LauncherSettings.java:356-357` — `ORGANIZER_LOCK_STATE`。`src/`の編集経路（`ModelWriter`、`Workspace`、`Folder`、`Launcher`）にはlock列を書く呼出しがない（grep確認。#445の実測と同じ結果）。lock値の読み取りはorganizer側の`RowManifestCodec.capture`（`RowManifestCodec.kt:69-72,244-246`）と`LauncherLayoutAdapter.capture`（`LauncherLayoutAdapter.kt:97-131`）がDBから直接読む。model data class（`ItemInfo`等）にはlock fieldが存在しないため、書込み前検証でlock状態を参照する場合はorganizer側のcapture経路（`LockCapturePort`、`LockPorts.kt:11-22`、`LockStateDbAdapter.kt:34-40`）を使うか、DB readを直接行う必要がある（将来の実装Issueの調査事項としてplan末尾に記載）。
- diagnostics: `docs/engineering/organizer-diagnostics.md` §1 — run journalのscopeは「organization run / recovery操作だけ」。§7 — package名は**Never**。`RunEvent`/`Trigger`/`PhaseCode`はclosed集合（`RunEvent.kt:208-240`、`Trigger.kt:9-12`、`PhaseCode.kt:10-35`）。`DiagnosticsPort`はorganizer用seam（`DiagnosticsPort.kt:18-27`）。
- organizer runとの排他: `ModelWriter.ModelTask.executeOnModelThread`（`ModelWriter.java:570-579`）が`runOrDefer(MODEL_WRITER, token=0)`でgate。`LayoutWriteCoordinator.java:460-497` `runOrDefer`、`:525-527` `defersTokenlessWork`（ORGANIZERとrestore-familyのみdefer）。新規アプリの追加はこのMODEL_WRITER経路を通るため、organizer run適用中の新規installは既存機構でdeferされる（追加の排他実装は不要。ADR-0013契約4の既存機構）。
- **既存`addItemToDatabase`のadmission前変更（Phase1 review指摘1の根拠）**: `ModelWriter.addItemToDatabase`（`ModelWriter.java:290-313`）は`executeOnModelThread()`（admission）より前に`updateItemInfoProps(item, ...)`（`:292`）、`item.id = generateNewItemId()`（`:294`）、`notifyOtherCallbacks(bindItems)`（`:295`）を実行し、admissionの内側のrunnableはその後のinsert+`mBgDataModel.addItem`+`ModelVerifier`のみを行う（`:299-313`）。つまり既存メソッドをそのまま使うと、organizer lease保持中のdefer時にmodel-visible state（`ItemInfo`のcontainer/screenId/cellX/cellY、ID、UI bind）がadmission成立前に変わる。これはADR-0013契約4が「直接編集の書込み構造は踏まない」と明示した既存メソッドの性質（`ModelWriter.java:190-192,252-256`と同型）であり、対象(b)の書込みにも適用される。指定フォルダへの追加は、この既存メソッドの再利用ではなく、admissionの内側で検証と変更が完結する最小の`ModelWriter`操作（同経路に追加する）として実装する必要がある（ADR-0013契約4の「同経路に追加する最小の操作」）。
- **branch freshness**: 現行main `824b468614c39c3f60353f09499f2588db4f4028`（#445 merge後）へrebase済み（2026-09-27）。ADR-0013が受入済みであり、ADR-0015 Decision 7の委譲先が有効である。`git diff 824b468614..HEAD -- src/ lawnchair/src/` が空であることを確認済みであり、本節の `path:line` 根拠は現行mainでも有効である。

## Design

### 変更対象と変更内容

| File | 変更 | 内容 |
|---|---|---|
| `docs/adr/0015-new-app-destination-policy.md` | 新設 | #446 付録の承認済み草案（2026-09-24）を基に、下記「ADR本文の修正一覧」を適用して収録。frontmatter `status: accepted` |
| `docs/adr/0005-fresh-install-presence-evidence.md` | Change historyへ1行追加 | 本文（Decision、Context、Verification obligations等）は不変。ADR-0015への関連リンクと適用範囲の狭めの明示 |
| `docs/engineering/package-provenance.md` | §7へ1行追加 | 本文（§4分類表、§6 handoff等）は不変。ADR-0015への関連リンク |
| `docs/product/requirements.md` | D-015行の参照更新、未解決事項の整理 | ADR-0015のaccepted反映。FR-008行のstatusは変更しない（指摘3）。D-013行は触れない |
| `docs/product/organization-run-ux.md` | §2.3へ注記1文 | 安全契約本体（§1、§3〜§6）と§2.3の表・diagramは不変 |
| `DESIGN.md` | §11 Design gatesへgate行1件追加 | 「新規アプリの配置先ポリシー」行。source of truthはADR-0015/#446。ADR-0013分のgate行は追加しない（指摘4） |
| `CONTEXT.md` | 用語1件追加 | 「配置先ポリシー (New App Destination Policy)」（spec Domain languageどおり。_Avoid_を含む） |
| `specs/446-new-app-destination-policy/spec.md` `plan.md` | 新設 | 本spec/plan |

コード・テスト・CI workflowの変更はない（seam、interface、migrationの変更なし。rollbackはPR revert）。

### ADR本文の修正一覧（承認済み草案からの差分）

1. frontmatter: `status: proposed` → `status: accepted`。ヘッダのStatus注記を受入済み（#446、本PR）へ更新し、Dateを受入日へ更新する。
2. RF-ID → Issue番号の置換（メモ§3の対応表と #446 本文どおり）: RF-06→#446、RF-07→#448、RF-09→#450、RF-02→#441。ADR-0013への参照は`docs/adr/0013-direct-edit-write-contract.md`（accepted済み）への直接参照へ更新する（#445で受入済みのため、起草Issue経由の参照からfile参照へ進める）。
3. 未収録文書への参照の置換: `refocus-drafts/00-decision-memo.md` への参照は「再焦点化方針メモ（2026-09-24に承認、Revision 5）」（初出時に出典を明記し、以降「メモ§x」）とする。`refocus-drafts/product/editing-burden-benchmark.md` への参照は `docs/engineering/editing-burden-benchmark.md` へ置き換える。
4. 未解決事項の解決の反映（spec Open questions 1〜5の決着）:
   - **フォルダ満杯の扱い** → Decision 4を更新: 「満杯」をfallback条件としない。上流のfolderにハードな上限item数の定数は存在せず、ページングで拡張するため「満杯」は非決定的な状態である。指定フォルダへの追加は常に末尾rankへの追加として成立し、fallback条件は観測可能な制約違反（device profile外・重なり等、書込み前の計画関数がtypedに検出するもの）に限定する。草案のDecision 4末尾の「フォルダが満杯の場合の扱いは未確定である」の文を削除し、この確定を記録する。
   - **同名フォルダ再作成時の再指定のUX** → 未解決事項から削除し、Decision 5の実装Issueへの委譲として記録（「再指定を促すUIの詳細は実装Issueのspecで決める」）。
   - **promise iconの配置先決定の実装位置** → 未解決事項から削除し、Decision 9に「bridgeの具体位置は実装Issueのplanが`ItemInstallQueue`のflush時と`AddWorkspaceItemsTask`内の候補から確定する」ことを記録。本plan「bridge実装位置の調査」節の調査結果を参照する。
   - **promise icon再flush時の決定性** → Decision 10に「配置先ポリシーの決定に使うcanonical inputはqueue投入（enqueue/`queuePendingShortcutInfo`）時点でcaptureしたpolicy snapshot（policy選択、指定folder id、user、package）とし、これをqueue永続化とともに保持して再flushでも同じsnapshotを使う。flush/`getItemInfo`時はpersist済みsnapshotを読むだけでcurrent policyから再生成しない。snapshot欠損・破損時もcurrent policyを再読しない単一のclosed result（identityが読める範囲なら`UpstreamDefault(SNAPSHOT_INVALID)`へ明示fallback、identity自体が信頼できない場合だけ`Reject(SNAPSHOT_INVALID)`）とする」ことを要求として追加（Phase1 review指摘5＋round 2指摘2＋round 3指摘1。当初の「package名+userからの純関数」案は不十分のため撤回。round 3で「current policyでの再計画または明示fallback」の二択も撤回し、current policyは再読しない契約へ一意化）。policy snapshotの保持方法は実装Issueのplanが決める。
   - **fallback理由の記録方法** → Decision 4の「diagnosticsへ記録」を「fallback理由が後から確認できる形で記録されること」へ文言調整し、organizer-diagnostics run journalのscope制約（§1: organization run / recovery操作のみ）とpackage名のNever分類（§7）を注記する。記録方法の確定は実装Issueのspecが行う。
5. 草案ヘッダの「本節は出典の全文であり…」等の草案固有の注記は削除する（`docs/adr/` に収録された本文が正となるため）。
6. Decision 14の「RF-07/FR-018」参照を「#448/FR-018」へ、Decision 15の設定UI参照を行番号付きの現行参照へ更新する。（受入時にADR-0015のDecisionを1〜15へ再編したため、草案の番号12/13は現行14/15に対応する。）
7. **書込み構造とfallback意味論の明記（Phase1 review指摘1＋round 2指摘1）**: Decision 7（ADR-0013への委譲）へ、指定フォルダへの書込みがADR-0013契約4どおり「validation → MODEL_WRITER admission → admission後の再検証 → model/DB変更」の順序が保たれる構造（admissionの内側で検証と変更が完結する最小の`ModelWriter`操作の追加・使用）で実装されること、配置先の決定が同一の純粋計画関数のclosed result（`FolderTarget(folderId)` / `UpstreamDefault(reason)` / `Reject(reason)`）として行われ、admission後に同じ関数が現状態へ再実行されること、(a) admission前の無変更（`ItemInfo`変更・ID採番・bindItems callback・DB書込みを含む。既存`addItemToDatabase`の`ModelWriter.java:290-313`の構造を踏まない）、(b) 指定folderがstale（削除・別profile・Dock・制約違反）な場合は検証失敗ではなく`UpstreamDefault(reason)`という有効planへ再計画し、default配置自体のbounds/container等を同じadmission内で検証してから1 transactionで書くこと、(c) default側も成立しない真のinvariant failureだけが`Reject`（無変更・typed failure）であること（「どこにも置かれない」状態は`pref_add_icon_to_home`が有効である限り作らない）を要求として追加する。将来の実装testへのdefer後再検証oracle要求（ADR-0013の要求テスト表と同じ既存surface。新規laneなし。snapshot再使用oracleとsnapshot欠損時fail-closed契約を含む）をConsequencesまたは要求テスト参照として記録する。

### `docs/product/requirements.md` の変更の機械的確認

- D-015行: 参照先を`[#446](https://github.com/nunu1733/NunuLauncher/issues/446)`から`docs/adr/0015-new-app-destination-policy.md`へのfile linkへ更新する（#440未解決事項「D-013〜016のADR link」のD-015分）。D-013行は#445で未実施のまま残す（本PRのscope外。#445の見落としであり、#446のscopeでない。将来の#448系PRまたは別のmaintenance PRで更新する）。
- FR-008行: **statusは変更しない**（`proposed（再定義 2026-09-24。旧: deferred by [Issue #85](https://github.com/nunu1733/NunuLauncher/issues/85)）` を維持。Phase1 review指摘3）。現行のstatus語彙（`docs/product/requirements.md:10`）に「ADR gate受入のみで実装未着手」を示す語はなく、plan Revision 1が想定した `accepted（ADR-0015受入、実装未着手）` は定義済み語彙にない。FR-008の再定義自体は#440で既に反映済みであり、#446の終了条件「FR-008の再定義がproduct文書へ反映されている」は現行行で満たされている。FR-008のstatusを進める場合は、status語彙・Traceability rule・#440 owner decision（FR-008のstatus表記をPR #463で承認済み）を変更する明示的なowner decisionを先に記録する必要があり、本Issueのscopeではない。
- 未解決事項: 「FR-008と旧Issue #85決定（Option B）の整合」の行を削除し、解決の記録（「ADR-0015 Decision 2で解決。fail-closed維持と再定義の関係はADR-0005の適用範囲の狭めで接続」）を同じ節の形式で残すか、Decision historyへの追記で置き換える（reviewで形式を確定する）。
- mvp-release-readiness.md は変更しない（#440で「再焦点化要件は本書のinventory対象外」が明記済み。FR-008の旧行はOption Bの履歴記録として維持される）。

### `docs/product/organization-run-ux.md` の変更の機械的確認

§2.3の冒頭段落（`organization-run-ux.md:140-146`付近）の末尾へ1文追加する。「FR-008の再定義（ADR-0015、[#446](https://github.com/nunu1733/NunuLauncher/issues/446)）により、上流が追加を決めたアイコンの配置先決定はorganizerのincremental proposalの枠外へ移った。ADR-0005が防ぐ対象は『既存アイテムを動かす増分整理提案』である（`docs/adr/0015-new-app-destination-policy.md`）。」§2.3の表・state diagram、§1、§3〜§6は1字も変えない。

### `CONTEXT.md` の変更

Language節へ1語追加する（spec Domain languageどおり。定義文案はspecから写す。追加位置は既存の並び順規約に従う）。実装詳細は書かない。

### bridge実装位置の調査（spec AC-10(a)。ADR-0015草案の未解決事項の解決入力）

`AddWorkspaceItemsTask`への影響を最小化する観点で2候補を比較した。**policy snapshotのcapture/read境界（round 2指摘2）**: snapshotのcaptureは**queue投入（`queuePendingShortcutInfo`、`ItemInstallQueue.java:194-215`）時**にpolicy選択+指定folder id+user+packageをcaptureしてqueue itemと同時に永続化する。flush/`getItemInfo`時（`ItemInstallQueue.java:135`）はpersist済みsnapshotを**読むだけ**であり、current policyから再生成しない。Revision 2の「policy snapshotの作成が`getItemInfo`の再構築位置と同じ場所」という表現は、process死後にcurrent settingsからsnapshotを作り直せてしまうため撤回する。

- **候補1: `ItemInstallQueue`のflush時（`ItemInstallQueue.java:135-145`）**: flushが`getItemInfo(mContext)`で`WorkspaceItemInfo`を構築した直後に、persist済みのpolicy snapshot（queue投入時にcapture済み）を参照して配置先を決め、その決定を`AddWorkspaceItemsTask`へ運ぶ（`Pair`のsecondか追加引数）。`AddWorkspaceItemsTask`の変更は「フォルダ指定がある場合に`findSpaceForItem`をスキップし、フォルダ内の末尾rankへ書込む分岐」に限定できる。**長所**: ポリシー計画（書込み前検証の一段階目）がqueue層に留まり、AOSP由来の`AddWorkspaceItemsTask`への追記が最小（1分岐）。snapshotのcapture（enqueue時）とread（flush時）がqueue層内で完結し、process死後の再flush一貫性（Open question 4）が構造的に満たされる。**短所**: `ItemInstallQueue`もAOSP由来fileであり、patch surfaceの観点では同等。widget/deep shortcut経路（`AddItemActivity`）にも同じqueueが使われるため、分岐の適用対象を「`ITEM_TYPE_APPLICATION`かつinstall session由来」に限定する必要がある。
- **候補2: `AddWorkspaceItemsTask`内（`AddWorkspaceItemsTask.java:123-126`付近）**: `findSpaceForItem`呼出しの直前でpersist済みpolicy snapshotを参照し、フォルダ指定があれば座標計算を置き換える。**長所**: 配置決定と書込みが同一task内で完結し、`shortcutExists`検証との順序が自明。**短所**: AOSP fileへの追記が大きくなり、`PackageUpdatedTask`型のNFR-010違反に近づく。snapshotのread位置がtask層になり、capture（enqueue時）とread（flush/task時）が層をまたぐ。
- **判断**: 候補1（`ItemInstallQueue`のflush時）を優先する。根拠は (i) ADR-0013契約4の「検証→admission→変更」順序と整合する（ポリシー計画=書込み前検証の一段階目をqueue層で行い、task内ではadmission後の再計画のみ）、(ii) snapshotのcapture（enqueue時）とread（flush時）がqueue層内で完結し、process死後の再flush一貫性が構造的に満たされる、(iii) `AddWorkspaceItemsTask`への追記が「フォルダ指定の解決1分岐」に限定される。**書込み構造とfallback意味論（round 2指摘1対応）**: いずれの候補でも、指定フォルダへの書込みは既存の`ModelWriter.addItemToDatabase`をそのまま使わない。`addItemToDatabase`はadmission前に`updateItemInfoProps`・ID採番・bindItems callbackを実行するため（`ModelWriter.java:290-313`。Current evidence参照）、ADR-0013契約4どおり「validation → MODEL_WRITER admission → admission後の再検証 → model/DB変更」の順序が保たれる構造、すなわちadmissionの内側で検証と変更が完結する最小の`ModelWriter`操作（同経路に追加する）として実装する。配置先の決定は、同一の純粋計画関数のclosed result（`FolderTarget(folderId)` / `UpstreamDefault(reason)` / `Reject(reason)`）として行う。admission後に同じ関数を現状態へ再実行し、指定folderがstale（削除・別profile・Dock・制約違反）な場合は**検証失敗として無変更で終えるのではなく**、`UpstreamDefault(reason)`という有効planへ再計画し、default配置自体のbounds/container等を同じadmission内で検証してから1 transactionで書く。default側も成立しない真のinvariant failureだけが`Reject`（無変更・typed failure）である（「どこにも置かれない」状態は`pref_add_icon_to_home`が有効である限り作らない。`Reject`は書込み経路のinvariant failureであり、意図的な追加抑制ではない）。将来の実装testは、ADR-0013の要求テスト表「admission後の再検証（defer後のstale検証）」行と同じ既存surfaceで (a)「ORGANIZER lease保持中にqueue→対象folder変更/削除→lease解放後、pre-admission mutationなし・同一関数の再実行で`UpstreamDefault`へ再計画・default配置の検証・書込み」、(b)「policy snapshot=Aでqueue投入 → policy設定をBへ変更 → process restart → 永続化されたqueueをflush → snapshot Aを使用（current policy Bを再読しない）」、(c) snapshot欠損/破損時もcurrent policyを再読しない単一のclosed result（identityが読める範囲なら`UpstreamDefault(SNAPSHOT_INVALID)`へ明示fallback、identity自体が信頼できない場合だけ`Reject(SNAPSHOT_INVALID)`）を検証するoracleを要求する。ただし「追加しない」選択肢の扱い（`pref_add_icon_to_home`と同じ結果をポリシーで表す方法）と、`SessionCommitReceiver.isEnabled`との二重判定の排除は、実装Issueのspecで確定する。本判断は実装Issueのplanが再検討できる参考情報であり、ADR-0015本文には「bridgeは1箇所」「書込みはADR-0013契約4の構造に従う」「配置先決定は同一純粋関数のclosed resultでadmission後に現状態へ再実行する」という契約だけを記録する（closed result=Decision 8、bridge=Decision 9、snapshot=Decision 10）。

### 配置先ポリシー実装PRの高リスク判定の予告（spec AC-10(b)）

- 指定フォルダへの追加はfavorites行の追加を伴うため、最初の実装PRは `risk: layout-data` の対象になる見込みである（#446 リスク節、ADR-0013 Consequences）。実装PRは独立auditと `final-status` を必要とする。
- 実装が新たなDB書込みfileを追加した場合、`tools/repo-contract/validate_writer_inventory.py` のsource-scan allowlistの更新がCIで要求される（既存の仕組みで自動検出されるbackstop。#445 planと同じ位置づけ）。
- 実装が高リスクpath一覧（`tools/repo-contract/validate_high_risk_evidence.py` の `HIGH_RISK_PATH_*`）に含まれない新規path（例: `lawnchair/src/app/lawnchair/homeedit/**`）にDB書込みを置く場合は、その実装PRが同じPRで高リスクpath一覧へpathを追加する（#445 planのhomeedit追加方針と同じ規則。`validate_high_risk_evidence.py` と `DocConsistencyTests` の一致強制に注意）。
- 実装PRで`AddWorkspaceItemsTask.java`等の`src/com/android/launcher3/model/`配下を変更する場合、そのpathは現行のCI surface map（`.github/workflows/ci.yml` の `surface_layout_write` 等）に含まれないため、per-path fail-closed規則により全source laneが起動する（`tools/ci/compute_ci_gating.py`。追加のsurface mapping変更は実装PRの判断に委ねる）。
- 書込み前検証でlock状態を参照する場合、model data classにlock fieldが存在しないため（`ItemInfo.java` に `organizerLockState` なし）、organizer側のcapture経路（`LockCapturePort` → `LauncherLayoutAdapter.capture` → `RowManifestCodec`）を使うか、検証関数内でDB readを行う必要がある。どちらもADR-0013契約2の「副作用のない計画関数」の入力として許容されるが、capture経路の利用は「organizerのplanner/application/locks/recovery protocolに依存しない」（ADR-0015 Decision 12）との緊張がある。依存の許容範囲（locksのcaptureのみを共有する。メモ§4.8「locksと副作用のない不変条件の検証は共有してよい」）を実装Issueのspecで確定する。

### 順序制約（spec AC-10(c)）

`Criteria: ADR-0015` 参照は `accepted`（または `implemented`）のADRに対してのみ有効である（`tools/repo-contract/validate_high_risk_evidence.py` の `_ACCEPTED_STATUSES`）。本PRでADR-0015を `accepted` として収録するため、配置先ポリシーの実装PR（将来起票）は本PR merge後に開始する。#448/#450のspec起草は本ADRを参照できるが、ADR-0015への `Criteria` 参照を含む実装PRは本PR merge後である。本PR自身はdocs-onlyであり、この順序制約の影響を受けない。

### PR構成とclosing keyword

- 単一PR。base `main`。branch `issue-446-new-app-destination-policy`。
- ADR-0015の新設、ADR-0005のChange history追記、package-provenanceの追記、requirements.md/organization-run-ux.md/`DESIGN.md`/CONTEXT.mdの更新、spec/planをすべて同じPRに入れる。
- #446 の全終了条件を本PRで満たす最終PRであるため `Closes #446` を使う（AGENTS.md規則7。spec Open questions 6の判断）。merge時にIssueが自動closeされる。
- 本PRはdocs-onlyのため高リスクgateの対象外であるが、保守者の指示により、別session（general-purposeサブエージェント）による独立監査を実施し、結果をPRへ記録する。

## Migration and recovery

- schema/rule migration: なし（文書変更のみ）。
- failure中のrollback: PR revert。
- release rollback/downgrade: 影響なし。
- backup/restore compatibility: 影響なし。

## Verification

実行する検証と、PR本文への記録:

1. `python3 tools/repo-contract/validate_repo_contract.py` — markdown内部link、required filesの検証（ADR-0015内の相対参照、spec内リンクを含む）。注意: worktreeに未trackの `refocus-drafts/` があるとvalidatorがその内部linkを検査して失敗するため、clean checkoutまたはstash状態で実行する（#445と同じ既知状態）。
2. `python3 tools/repo-contract/test_validate_repo_contract.py` — validator self-test。
3. `git diff origin/main -- docs/adr/0005-fresh-install-presence-evidence.md docs/engineering/package-provenance.md` の目視 + 機械確認 — 両fileともChange history/§7の1行追加のみであること（本文の削除・変更行0）。
4. `path:line` 根拠のspot check — 上記「Current evidence」のとおり2026-09-27に実施済み。結果をPRに記録する。
5. CI: docs-onlyのためsource jobはpath filterでskipされ、`validate-repo-contract` のみ実行される。これがdocs-only PRの必要十分なevidenceである（`docs/project/github-workflow.md` Evidence選択原則）。

実施しない検証と理由: build/`spotlessCheck`（markdownのみの変更であり、対象のlint対象に入らない。docs-only PRの既定のevidence範囲）。instrumentation（コード変更なし）。

## Documentation updates（Phase 2で実施済み）

- [x] spec status/history（本spec/plan。Phase 2でspecをacceptedへ進めた）
- [x] CONTEXT.md（「配置先ポリシー」の追加。AC-9）
- [x] DESIGN.md（§11 Design gatesへ「新規アプリの配置先ポリシー」gate行を追加。#440 other-doc-impactsの承認済み判断「各ADRがacceptedになった時点で追加」に従う。ADR-0013分のgate行は#445で未実施のため本PRでは触れない。homeeditのmodule位置づけ・不変条件の2層化はmodule実体が存在しないため実装Issueが担当）
- [x] ADR（ADR-0015新設、ADR-0005 Change history）
- [ ] AGENTS.md（変更しない。ADR-0013のcarve-out段落が対象(b)をすでに含むため、ADR-0015の受入で追記は不要）

## Execution checklist

- [x] Current behavior verified（上流の追加経路の `path:line` 実測。Current evidence節）
- [x] Phase 1 review → clear（round 5、comment 5854429674）
- [x] Phase 2: ADR-0015収録、ADR-0005/package-provenance接続、product文書更新（D-015参照・#85整合解消）、`DESIGN.md` §11 gate行追加（ADR-0015分のみ。ADR-0013分は触れない）、CONTEXT.md用語追加
- [x] Full relevant verification completed（repo contract validator + self-test + diff機械確認。Phase 1/2とも実施）
- [x] PR evidence and remaining risks recorded（PR本文をPhase 2最終PRへ更新）

## Review / handoff packet（Phase 2最終時点）

- Issue and all comments: https://github.com/nunu1733/NunuLauncher/issues/446; retrieved at 2026-09-27; state=OPEN; labels=type: feature
- Scope type: research/decision（成果物は文書。Issue labelはtype: featureだが、決定Issueであり実装は別Issue）
- Accepted spec + commit: specs/446-new-app-destination-policy/spec.md（status: accepted。本PR内でaccepted化）
- Bug oracle: N/A（research/decision。成果物は文書）
- Plan + revision: specs/446-new-app-destination-policy/plan.md（本書、Revision 11）
- Base SHA: 824b468614c39c3f60353f09499f2588db4f4028（#445 merge後の現行main）
- Head SHA: 本PRのcurrent head（PR本文に記録）
- Phase 1 review: round 1〜5。round 5 Clear（https://github.com/nunu1733/NunuLauncher/pull/470#issuecomment-5854429674）
- Phase 2 review: round 1〜5すべてRequest changes。round 3（permalink誤りとPR本文handoff未同期）はRevision 9、round 4（Review recommendation同期とplan handoffのrev 9同期）はRevision 10、round 5（review provenance同期の残り3点）はRevision 11で対応済み
- CI evidence: current headのCI / High-risk gateともにsuccess（PRのChecksで確認）
- Executed evidence: 上記Verification参照
- 次の1手: round 6 review結果を本packetとPR本文へ反映 → clear後に独立監査（別session）→ merge operator check → merge
