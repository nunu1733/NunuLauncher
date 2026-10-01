# Implementation Plan: 新規アプリの配置先ポリシーの実装（ADR-0015、B1/B6）

> Issue: #497
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — `favorites`行の追加を伴う新しい書込み経路（ADR-0013契約4どおりの`ModelWriter`操作追加）と、上流のmodel経路（`ItemInstallQueue` / `AddWorkspaceItemsTask`）へのbridgeを作るため。`ModelWriter.java`が高リスクpath一覧（`tools/repo-contract/validate_high_risk_evidence.py` の `HIGH_RISK_PATH_FILES`）に含まれるため、実装PRは `risk: layout-data` 対象であり、独立auditと`final-status`を要求する。ADR-0015は`accepted`であり（`_ACCEPTED_STATUSES`に対象）、実装PRの `Criteria: ADR-0015` 参照は有効である。
> Base SHA: 3d8f4dcca5452fd609a1c1e2f279231ca8edc0e4（main、2026-10-02時点。Phase 2のPR作成前にmain断面を再確認し、更新があればrebaseする）
> Revision 1: 2026-10-02 — 初版。Phase 1 reviewへ提出。
> Revision 2: 2026-10-02 — Phase 1 review round 1（[PR #498 comment](https://github.com/nunu1733/NunuLauncher/pull/498#issuecomment-5935261988)）の指摘1〜4に対応。指摘1（高）: 既定配置を`HomeEditPlanner.firstFreeCell`系で再計画する設計を撤回し、上流`WorkspaceItemSpaceFinder`の意味論をadmission内で用いる形へ修正（Current evidenceへ`WorkspaceItemSpaceFinder.java:69-71,77-86`の実測を追加。新規screen id採番・`workspaceScreens`/`addedWorkspaceScreensFinal`変更のadmission前漏出禁止を明記）。指摘2（中）: snapshot重複captureをfirst enqueue winsへ修正（`addToQueue`の重複排除は追加も書込みもスキップするため、last-winsの記述は現コードと不合だった）。指摘3（中）: 基底entry decode不能時の`Reject`を観測不能として契約から外し、`Reject(SNAPSHOT_INVALID)`をsnapshot部分のidentity不一致に限定、downgrade記述の「unknown attributeでentry skip」表現を修正。指摘4（中）: テスト所有を「決定意味論=JVM canonical、実接続=instrumentation（理由1行付き）」へ再割付け（Designへテスト所有節を新設）。

## Current evidence

本planの記載に用いた実コードの根拠は、2026-10-02に本branch上（base `3d8f4dcca5452fd609a1c1e2f279231ca8edc0e4`）で検証した。

**書込み構造の先例（#448/#450が実装したADR-0013契約4の構造。本実装は同型に載せる）**

- `src/com/android/launcher3/model/ModelWriter.java:709-763` — `DirectEditTask` 基底: `runImpl()` がadmission内で `mValidator.validate(buildDirectEditSnapshot())` を実行し（`:727-728`）、非proceedなら無変更でtyped failure（`:729-733`）、proceed時のみ `runAdmitted(item)`（`:734`）。
- `src/com/android/launcher3/model/ModelWriter.java:610-615` — `moveItemForDirectEdit`（既存フォルダへの追加を含む移動の先例）。`:623-628` — `createFolderAndMoveForDirectEdit`。Javadocに「The item id is generated inside admission」（新規行INSERTのID採番をadmission内で行う先例）。
- `src/com/android/launcher3/model/ModelWriter.java:783-836` — `DirectEditMoveTask.runAdmitted`: `ContentWriter` でliveな`ItemInfo`に触れず1行UPDATE（`:796-806`）、書込み後にmodel側の同期（フォルダ所属の無音追加 `:823-832`。`FolderInfo.add`のlistener通知をmodel threadで起こさない先例）、`updateItemArrays`、`reportSuccess`。
- `src/com/android/launcher3/model/ModelWriter.java:294-318` — 既存 `addItemToDatabase`: `updateItemInfoProps`（`:296`）・ID採番（`:298`）・bindItems callback（`:299`）がadmission前に走る。ADR-0015 Decision 7が「そのまま使わない」と定めた構造であり、本実装の新opは踏まない。
- `src/com/android/launcher3/model/DirectEditContract.java` — platform契約型（`FAIL_*` キー、`Snapshot`/`Row`、`Validator`、`ResultCallback`）。fork所有の契約fileであり、本実装のdestination型をここへ追加する（src/側が契約型のみを参照する依存方向を保つ）。
- `src/com/android/launcher3/model/ModelWriter.java:683-702` — `buildDirectEditSnapshot()`（admission内の現状態投影。全`itemsIdMap`行 + QSB予約行 + 格子寸法 + hotseat数。フォルダ行・フォルダ子行（`container=folder id`、`rank`）・userSerialを含むため、指定フォルダの検証入力として再利用できる）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditSnapshotMapper.kt:15-34` — `DirectEditContract.Snapshot` → 純粋型 `HomeEditSnapshot` の投影。`HomeEditModel.kt:35-67` — `HomeEditItem`（id、container、screen、cell、span、type、rank、userSerialを含む）と `HomeEditSnapshot`。**配置先ポリシーの純粋計画はこの既存の純粋snapshot型とmapperを再利用する**（新しい投影型を作らない）。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditPlanner.kt:67` — フォルダ追加rankの先例 `rank = snapshot.items.count { it.container == intent.folderId }`（末尾rank。既存子のrankを変えない。本実装のフォルダtargetでも同じ規約を使う）。`:198-220` — 純粋な空きセル探索 `firstFreeCell`（#448のページ移動用。**本実装の`UpstreamDefault`の既定配置には使わない**。Phase 1 review指摘1: 上流`WorkspaceItemSpaceFinder`と意味論が異なるため）。
- `src/com/android/launcher3/model/WorkspaceItemSpaceFinder.java:44-98` — 上流の既定配置の正。top QSB有効時に`FIRST_SCREEN_ID`を探索対象から除外し（`:69-71`）、既存screenに空きが無い場合は`getNewScreenId()`で新規screenを割り当ててそこへ置く（`:77-86`。**呼出し側の`workspaceScreens` / `addedWorkspaceScreensFinal`を変更し、`getNewScreenId`はDB controller経由の採番である**）。既定配置の座標計算をfork側の純粋走査で複製しない。新opがこの意味論をadmission内で使う場合、localなlistを渡し、採番・list変更をadmission内に限る（ADR-0013契約4のadmission前無変更）。

**自動追加経路とbridge候補（spec 446 planの調査の再確認）**

- `src/com/android/launcher3/model/ItemInstallQueue.java:179-181` — `queueItem(String packageName, UserHandle)` が自動追加専用のoverloadである。呼出し側は `SessionCommitReceiver.java:95` と `InstallSessionHelper.java:236`（promise icon）のみ。手動配置 `AddItemActivity.java:343,372` は `queueItem(ShortcutInfo)` / widget overloadを使うため、**captureを`queueItem(String, UserHandle)` overloadに置けば手動配置は構造的に対象外になる**。
- `src/com/android/launcher3/model/ItemInstallQueue.java:194-214` — `queuePendingShortcutInfo`（MODEL_EXECUTOR上で `addToQueue` → queue永続化）。`:87-88` — `mStorage = new PersistedItemArray<>(APPS_PENDING_INSTALL)`。`:113-120` — `addToQueue`（`equals`で重複排除し `mStorage.write`）。
- `src/com/android/launcher3/model/ItemInstallQueue.java:122-145` — `flushQueueInBackground`: `mItems` を `info.getItemInfo(mContext)` で`Pair<ItemInfo, Object>`へ写像し（`:134-136`）、`launcher.getModel().addAndBindAddedWorkspaceItems(installQueue)` へ渡す（`:141`）。**`Pair`のsecondは未使用の運搬スロットであり、flush時の決定運搬に使える**。
- `src/com/android/launcher3/LauncherModel.java:205-211` — `addAndBindAddedWorkspaceItems` が `AddWorkspaceItemsTask` をenqueue。
- `src/com/android/launcher3/model/AddWorkspaceItemsTask.java:100-107` — `shortcutExists`重複除外（`:236-287`。promise iconは同一package照合 `:274-281`）。`:139-192` — promise iconのsession検証と本物アイコン差し替え。`:125-127` — `findSpaceForItem`。`:195-197` — `addItemToDatabase(itemInfo, CONTAINER_DESKTOP, screenId, coords[1], coords[2])`。**本taskの変更は「運搬されたdestination決定がある場合に既定書込みを新opへ置き換える1分岐」に限定する**（spec 446 plan候補1の判断を本planで確定）。
- `src/com/android/launcher3/util/PersistedItemArray.java:70-101` — `write`はentryごとに `itemType` / `profileId` / `intent` の3 attributeを書く。`:115-160` — `read`は3 attributeを読みfactoryへ渡す。**policy snapshotの保持方法は「queue XML entryへの追加attribute」へ確定する**（別file方式と比べ、queueとsnapshotの原子性が保たれ、lockstep protocolが不要。format拡張は旧読み込み互換を保つ: 追加attributeが無いentryは `SNAPSHOT_INVALID` 扱い）。
- `src/com/android/launcher3/model/ItemInstallQueue.java:79` — `MainInitializedObject` によるsingleton（`INSTANCE = new MainThreadInitializedObject<>(ItemInstallQueue::new)`、constructorはprivate）。src/→lawnchairの依存を作らないため、resolverは契約file内の小さなinterface + static注入点とし、lawnchair側がprocess開始時に登録する（既存のprocess初期化hookの慣行に従う）。
- `src/com/android/launcher3/model/PackageUpdatedTask.java:261-320` — `OP_ADD`は既存promise iconのintent/icon更新のみ。**変更しない**（ADR-0015 Decision 9）。

**設定UIと既存seam**

- `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt:51` — `addIconToHome = BoolPref("pref_add_icon_to_home", true)`。policy用の新prefは同fileへ`StringPref`として追加する。
- `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt:70-81` — 「ホームにアイコンを追加」switch block（`general_label` group内。ロック中は無効化）。`:265-270` — `ListPreference`（bottom-sheet radio list）の既存使用例。ポリシー行はこのswitchの直下に置く。
- `lawnchair/src/app/lawnchair/homeedit/ui/EditActionsShortcuts.kt:227-297` — #448のフォルダ選択dialogの先例（`HomeEditExecutor.fetchFolderOptions` からのfolder一覧）。設定側のフォルダ選択dialogは同型に作り、Dock上のフォルダとwidget/app-pairを除外する。
- `lawnchair/src/app/lawnchair/homeedit/HomeEditExecutor.kt:53` — `fetchFolderOptions`（folder一覧の既存seam。設定UIから再利用する）。

**repo contract / CI**

- `tools/repo-contract/validate_writer_inventory.py:108-114` — `ModelWriter.java` はallowlist済み。本実装はDB書込みを`ModelWriter.java`に集約するため、**新規writer fileは作らずallowlist変更は不要**な見込み（homeedit配下のKotlinは書込みpatternに一致しない。2026-10-02にvalidator実行でPASS確認済み: `PASS: 19 writer files verified`）。
- `tools/repo-contract/validate_high_risk_evidence.py:56-66` — `HIGH_RISK_PATH_FILES` に `ModelWriter.java`（`:61`）。`:309` — `_ACCEPTED_STATUSES` に `accepted`（ADR-0015参照可）。実装PRは `risk: layout-data` label + 独立audit記録（`docs/assessment/pr-<PR番号>-<slug>.md`）+ 対象head上の`final-status`成功を要求される。
- `.github/workflows/ci.yml:117-147` — `surface_layout_write` filterに `ModelWriter.java`、`DirectEditContract.java`、`lawnchair/src/app/lawnchair/homeedit/**` が含まれる。**`ItemInstallQueue.java` / `AddWorkspaceItemsTask.java` / `PersistedItemArray.java` はfilterに含まれないため、per-path fail-closed規則により全source laneが起動する**（`tools/ci/compute_ci_gating.py`）。surface mappingの拡張は本PRでは行わない（lane追加審査はAGENTS.mdテスト規約の対象であり、既存laneで足りる）。
- `.github/workflows/ci.yml:360` — JVM gateは `app.lawnchair.homeedit.*` を実行する（新規homeedit JVM testは自動収録）。`:423` — instrumentation shared-writer laneのclass list（新規instrumentation classはここへ明示追加し、`tools/repo-contract/ci_portfolio_map.yml` の監査表を同じPRで更新する）。

## Design

### bridge位置の確定（spec AC-6、ADR-0015 Decision 9、446 plan候補1の確認）

**配置先の決定は、admission内の同一純粋計画関数のclosed resultとして1箇所で行う**。flush時のresolverは「どの書込み経路に流すか」のroutingだけを行い、配置自体は決めない:

| 段 | 場所 | 責務 |
|---|---|---|
| capture | `ItemInstallQueue.queueItem(String, UserHandle)`（自動追加overloadのみ） | resolver経由でpolicy選択+指定folder id+user+packageをcaptureし、`PendingInstallShortcutInfo`に載せてqueue XMLへ永続化（ADR-0015 Decision 10）。current policyの再読はこの時点だけ。**重複enqueue時は`addToQueue`の重複排除が動き追加も書込みもスキップされるため、最初に永続化したsnapshotが保持される（first enqueue wins。`ItemInstallQueue.java:113-120`。重複排除の意味論は変更しない）** |
| route | `ItemInstallQueue.flushQueueInBackground` | 永続化済みsnapshotを**読むだけ**。分類は純粋なsnapshot分類関数（JVM test可能）で行い、routingを決めて`Pair`のsecondへ載せる: `upstream` → 既定経路（stock。baselineと同じ）、`folder` → 新op経路（stage-2がclosed resultを決める）、欠損・decode不能（基底identityが読める）→ 新op経路（stage-2が`UpstreamDefault(SNAPSHOT_INVALID)`を返す）、snapshot部分のidentity不一致 → 新op経路（stage-2が`Reject(SNAPSHOT_INVALID)`を返し無変更）、基底entry自体がdecode不能 → 上流の既存の読み飛ばし（本契約の対象外） |
| 決定と書込み | 新`ModelWriter` op（admission内） | 同一純粋計画関数を現状態投影（`buildDirectEditSnapshot()`再利用）へ実行し、closed resultどおりにID採番→INSERT→model同期→`ModelVerifier`。**`UpstreamDefault`の既定配置は上流`WorkspaceItemSpaceFinder`をadmission内で呼び出して決める**（localなlistを渡し、新規screen id採番・list変更をadmission内に限る。fork側の走査複製を作らない）。記録と通知はadmitted結果のcallbackで行う |

この分離により、(i) `AddWorkspaceItemsTask`の変更は1分岐、(ii) snapshotのcapture（enqueue時）とread（flush時）がqueue層内で完結し、process死後の再flush一貫性が構造的に満たされ、(iii) `PackageUpdatedTask`への分岐追加が構造的に起こらない。

### 変更対象と変更内容

| File | 変更 | 内容 |
|---|---|---|
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationPlanner.kt` | 新設 | 純粋計画関数。入力は既存の純粋型`HomeEditSnapshot` + typedなpolicy snapshot + 対象identity。出力はclosed result（`FolderTarget(folderId, rank)` / `UpstreamDefault(reason)` / `Reject(reason)`。**座標を含まない**）。snapshot部分の欠損/破損/不一致をtypedに分類する純粋分類関数もここに置く（JVM test可能）。既定配置の座標計算は対象外 |
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationAdapter.kt` | 新設 | stage-2 validator adapter（`DirectEditContract`のdestination validatorを実装。`HomeEditSnapshotMapper`で投影しplannerを呼ぶ）。resolver/結果callbackのKotlin側実装（`FileLog`記録、one-shot通知stateへの反映、folder iconのUI refresh）を含むbridge class |
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationNotice.kt` | 新設 | one-shot通知state（prefs-backed。読み出し時に消費するstate machine。JVM test可能） |
| `lawnchair/src/app/lawnchair/homeedit/ui/` | 新設 | 設定用フォルダ選択dialog（Dock上のフォルダを除外し、profile注記を表示） |
| `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt` | 変更 | policy選択の`StringPref`（値: `upstream` または `folder:<id>`。既定`upstream`）+ one-shot通知stateのpref |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | 変更 | 3択ポリシー行を「ホームにアイコンを追加」の直下へ追加（ロック中は無効化）。「追加しない」⇔ `addIconToHome=false` の双方向整合 |
| `lawnchair/src/app/lawnchair/LawnchairProcessInitializer.kt`（または同等のprocess初期化点） | 変更 | resolverをsrc/契約のstatic注入点へ登録 |
| `lawnchair/res/values/strings.xml` + `values-ja/strings.xml` | 変更 | ポリシー行・dialog・通知の新規文字列 |
| `src/com/android/launcher3/model/DirectEditContract.java` | 変更 | destination型の追加（`DestinationValidator` / `DestinationDecision` / 理由コード定数）。Issue番号#497と理由を近傍に注記（AGENTS.md上流patch規約） |
| `src/com/android/launcher3/model/ItemInstallQueue.java` | 変更 | capture（自動追加overloadのみ）、queue XMLへのsnapshot永続化、flush時の読み出しとrouting（`Pair.second`経由の運搬）。Issue番号注記 |
| `src/com/android/launcher3/util/PersistedItemArray.java` | 変更 | entry attributeの任意拡張hook（既定動作は不変。旧format読み込み互換。既存parserは未知attributeを無視するため、旧版での読み込みでもentry skipは起こらない）。Issue番号注記 |
| `src/com/android/launcher3/model/AddWorkspaceItemsTask.java` | 変更 | 運搬された決定による1分岐（新op呼出し or 既定経路）。`shortcutExists`・promise検証の順序は不変。Issue番号注記 |
| `src/com/android/launcher3/model/ModelWriter.java` | 変更 | 新opの追加（admission内で検証→ID採番→INSERT→model同期→verifier→callback）。`UpstreamDefault`時は上流`WorkspaceItemSpaceFinder`をadmission内で呼び出し（local list渡し。新規screen id採番・list変更はadmission内に限る）。既存`addItemToDatabase`の構造は踏まない |
| `tests/unit/app/lawnchair/homeedit/` | 新設 | planner（closed result・境界・決定性・冪等性）、通知state、設定整合のJVM test |
| `tests/organizer-instrumentation/` | 新設 | 書込みshape（INSERT 1行・rank/lock不変）、fallback経路、snapshot再flush一貫性（process死込み）、欠損・破損、defer後の再計画のinstrumentation test |
| `docs/product/requirements.md`、`docs/engineering/editing-burden-benchmark.md`、`DESIGN.md`、`CONTEXT.md`、`tools/repo-contract/ci_portfolio_map.yml` | 変更 | spec AC-8/AC-9/AC-10どおり。ci_portfolio_mapはinstrumentation class list追加の監査表同期 |

### 契約と依存方向

- src/（`DirectEditContract`、`ItemInstallQueue`、`AddWorkspaceItemsTask`、`ModelWriter`、`PersistedItemArray`）は**契約型のみ**を参照し、`app.lawnchair`の実装クラスをimportしない。実装（純粋planner、validator adapter、resolver）はlawnchair側に置き、static注入点で登録する。#448の`DirectEditContract`↔`HomeEditStage2Validator`と同じ依存方向である。
- 純粋計画関数は`HomeEditSnapshot`（既存の純粋型）を使い、Android framework型をimportしない（AGENTS.md設計規約: platform型を計画moduleのinterfaceへ漏らさない）。
- organizerのplanner/application/locks/recovery protocolには依存しない（ADR-0015 Decision 12。spec Open question 6どおり、末尾rank追加によりlock状態の読み取りも不要）。

### テスト所有（ADR-0015要求テスト表とADR-0013要求テスト表の割付け。Phase 1 review指摘4）

決定意味論はJVM test（純粋関数。test DBを要しない）をcanonical ownerとし、process・lease・永続化の実境界を含む接続だけをinstrumentation（test DB使用）に割り付ける。instrumentationに割り付けるtestには「下位層では観測できないrisk」を1行で明記する。新規laneは作らず、既存のhomeedit JVM gate（`app.lawnchair.homeedit.*`）とorganizer shared-writer instrumentation laneに載せる。

| 要求（ADR-0015要求テスト表） | canonical surface | 内容 |
|---|---|---|
| admission後の再計画（defer後のstale検証） | JVM: stage-2 validator（現状態再実行でstale → `UpstreamDefault(reason)`へ再計画する決定意味論。`HomeEditStage2ValidatorTest`と同型）+ instrumentation shared-writer lane（ORGANIZER lease保持中のdefer→解放→再計画→既定書込みの実接続。**lease境界と実際の書込み経路は下位層で観測できないため**） | ADR-0013要求テスト表の「admission後の再検証」行と同じ責務分割。admission前無変更（`ItemInfo`変更・ID採番・bindItems callback・DB書込み・新規screen id採番なし）をinstrumentationで検証 |
| policy snapshotの再flush一貫性 | JVM: snapshot分類関数とdecode契約（first-wins、欠損→default route、不一致→Reject）+ instrumentation（queue永続化fileを含むpersist→再構築→flushの実接続。**実際のpersist/restart結合は下位層で観測できないため**） | snapshot=A → policy B → restart → flush → A。current policy再読なし |
| snapshot欠損・破損時のclosed result | JVM: 分類関数（欠損・decode不能→`UpstreamDefault(SNAPSHOT_INVALID)`、identity不一致→`Reject(SNAPSHOT_INVALID)`、基底entry decode不能→上流の既存drop）+ instrumentation（queue file実体を用いた破損注入の接続。**file I/O境界は下位層で観測できないため**） | current policyを再読しない単一のclosed result |
| 既定配置の上流意味論との等価性（Phase 1 review指摘1） | instrumentation（ModelWriter実経路で新opの`UpstreamDefault`書込み位置が`WorkspaceItemSpaceFinder`の走査（QSB時1ページ目除外・満杯時新規screen）と一致すること。**finder意味論はmodel状態とDB controllerに依存するためJVMで再現しない**） | 既定配置をfork側で複製しないことの固定 |
| coordinator排他・書込みshape（INSERT 1行・rank/lock不変） | instrumentation shared-writer lane（test DB使用） | ADR-0013要求テスト表の該当行 |
| process死 | instrumentation（既存のprocess-death smokeの慣行。**実process境界のため**） | 1 INSERTのatomic性（前か後のどちらか） |
| 純粋計画関数・通知state・設定整合 | JVM（`tests/unit/app/lawnchair/homeedit/`。fixture・境界・typed理由・決定性・冪等性） | AGENTS.mdテスト規約どおり |

テスト新設前に[test-audit skill](../../.agents/skills/test-audit/SKILL.md)を適用し、protected contract・credible regression・primary owner boundary・既存coverageとの重複・impact surface・CI分類を確定してPRへ記録する。新規instrumentation classは`.github/workflows/ci.yml`のclass listへ追加し、`tools/repo-contract/ci_portfolio_map.yml`の監査表を同じPRで更新する。

### spec 446 planからの引き継ぎ事項の決定

- bridge位置: 候補1（`ItemInstallQueue`のflush層でのrouting + `AddWorkspaceItemsTask`の1分岐 + 新op）で確定（本plan「bridge位置の確定」節）。
- policy snapshotの保持方法: queue XML entryへの追加attribute（`PersistedItemArray`の任意拡張hook）で確定。旧format読み込み時は`SNAPSHOT_INVALID`扱い（spec AC-4）。既存parserは未知attributeを無視するため、旧版downgrade時もentry skipは起こらない。
- `addToQueue`の重複排除（`PendingInstallShortcutInfo.equals`: user+itemType+intent）は不変であり、snapshotは`equals`の対象にしない。**重複enqueue時は最初に永続化したsnapshotが保持される（first enqueue wins。`mItems.contains`がtrueの場合、追加も`mStorage.write`も行われないため、last-winsは現行コードでは成立しない。Phase 1 review指摘2）**。last-winsが必要な場合はqueue意味論の変更となるため、本Issueでは採用しない。
- stage-1（flush時）はroutingのみで配置を決めない。snapshotの分類（`upstream` / `folder` / 欠損・decode不能 / identity不一致）は純粋関数とする。すべての配置決定はstage-2（admission内）のclosed resultであり、**既定配置の座標計算は純粋計画関数の対象外**である（上流`WorkspaceItemSpaceFinder`の意味論をadmission内で用いる。Phase 1 review指摘1）。これによりADR-0015 Decision 8の「admission後に同じ関数を現状態へ再実行」が自然に成立する（stage-2が最初かつ唯一の配置決定である）。

## Migration and recovery

- schema/rule migration: なし（書く行は上流と同じ標準構造。新列・新tableなし）。
- queue XML format拡張: 旧format（snapshot attributeなし）の読み込みは`SNAPSHOT_INVALID`フォールバックで動作する（spec AC-4）。
- failure中のrollback: 単一行INSERTでatomic。新opの失敗は無変更でtypedに通知する（握りつぶしなし）。
- release rollback/downgrade: 旧版に戻すとqueue XMLの追加attributeは既存parserにより無視される（entry skipは起こらない）。fallback先は上流既定であるためlayout損失はない。
- backup/restore compatibility: 契約変更なし。

## Verification

実行する検証と、PR本文への記録:

1. `./gradlew spotlessCheck`
2. `./gradlew assembleLawnWithQuickstepGithubDebug`
3. `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.homeedit.*'`（JVM gateと同一surface）
4. instrumentation（organizer shared-writer lane相当。CI上で実行し、run URLをPRへ記録。新規classは`ci.yml:423`のclass listへ追加）
5. `python3 tools/repo-contract/validate_repo_contract.py` と `python3 tools/repo-contract/test_validate_repo_contract.py`
6. `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`（NFR-010記録。AC-9）
7. `python3 tools/repo-contract/validate_writer_inventory.py`（PASS確認。AC-9）
8. エミュレータ: ベンチマーク§7準拠の実行記録（fixture seeding + session installによる配置実証。B1/B6会計）。実機確認はowner確認事項としてPRへ記録する（AC-1/AC-2/AC-11）

実施しない検証と理由: なし（上記がspec Test oracleの全ACを.coverする）。CI上の一時的failureはquality strategyの分類・証拠方針に従う。

## 高リスク要件（実装PR）

- `risk: layout-data` labelを付与する。`ModelWriter.java`が高リスクpath一覧内のため、独立audit（owner指示によりgeneral-purposeサブエージェントの独立session）と、監査対象head上のCI `final-status` 成功をmergeの条件とする。
- audit記録は `docs/assessment/pr-<PR番号>-new-app-destination-policy-impl.md` に、対象head SHA、参照したspec/ADRの受入条件（ADR-0013契約4/ADR-0015要求テスト表、spec 497 AC-1〜AC-11）、実行したtest表面、成功したCI runへのlinkを含めて作成する。
- Worker/Review/Owner/Merge operatorの責任記録は[Execution and approval contract](../../docs/project/github-workflow.md#execution-and-approval-contract)どおりPR本文のpacketへ残す。

## PR構成とclosing keyword

- 単一PR。base `main`。branch `issue-497-new-app-destination-policy-impl`。Phase 1（spec/plan）→ Phase 2（実装・test・文書）を同じPRで行う。
- PR作成前にmain断面を再確認し、更新があればrebaseする。
- 本PRは#497の全終了条件（spec受入、実機でのB1確認と会計記録、境界条件のtest確認、B6確定と会計記録、FR-008 implemented化、writer inventory・patch-surface記録）を満たす最終PRであるため、Phase 2完了時に `Closes #497` へ更新する（Phase 1中間は `Refs #497`）。

## Execution checklist

- [ ] Current behavior verified（上記Current evidence。2026-10-02実施済み、PRで再掲）
- [ ] テスト新設前のtest-audit適用（protected contract・credible regression・impact surface・CI分類の確定をPRへ記録）
- [ ] Phase 1: spec/plan review → clear
- [ ] Phase 2: 純粋planner + 契約型 + bridge + 新op + 設定UI + 通知の実装（最小の縦切り。interface経由のtestを先に追加）
- [ ] Phase 2: instrumentation test群（defer後の再計画、snapshot再flush一貫性、欠損・破損、書込みshape、排他）
- [ ] Verification完了とPR本文への記録（patch surface、writer inventory、CI run URL、エミュレータ記録）
- [ ] 文書更新（FR-008、ベンチマーク§6、DESIGN.md、CONTEXT.md、ci_portfolio_map.yml）
- [ ] Phase 2 review → clear
- [ ] main断面再確認・rebase、独立audit（別session）、merge operator check、merge

## Review / handoff packet（Phase 1提出時点）

- Issue and all comments: https://github.com/nunu1733/NunuLauncher/issues/497; retrieved at 2026-10-02; state=OPEN; labels=type: feature, status: needs-spec
- Scope type: feature（階層H実装）
- Accepted spec + commit: specs/497-new-app-destination-policy-impl/spec.md（本PR内でaccepted化を図る。commit SHAはPR本文へ記録）
- Bug oracle: N/A with reason（新機能実装であり、bug oracleは存在しない。ADR-0015要求テスト表とspec Test oracleが検証の正である）
- Plan + revision: specs/497-new-app-destination-policy-impl/plan.md（本書、Revision 2。Phase 1 review round 1対応）
- Base SHA: 3d8f4dcca5452fd609a1c1e2f279231ca8edc0e4
- Head SHA: PR本文へ記録
- Executed evidence: Current evidenceの`path:line`検証（2026-10-02）+ `validate_writer_inventory.py` PASS確認
- 次の1手: Phase 1 review（ChatGPT、結果をPRコメントへ投稿）→ clear後、Phase 2実装開始
