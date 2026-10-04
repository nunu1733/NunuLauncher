---
status: proposed
---

# 16-dev rebaseのデータ互換性・rollback条件（Issue #522）

> Status: Proposed（研究成果。APK互換性の実証やproduction rebaseの完了ではない）
> 確認日: 2026-10-04
> 対応: [#522](https://github.com/nunu1733/NunuLauncher/issues/522)、Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase 1
> Risk tier: L（assessment・ADR・Phase 0の比較範囲注記のみ。source、schema、test、CIは変更しない）。researchなのでspec/planはN/A。Phase 2の移植・Phase 3の実行をEpic #516が所有する。

## 1. 結論と固定した比較範囲

**upstreamの32→32と、forkの33→33を区別する。** 現行15系forkは既にADR-0004のlock列を持つschema **33**。16系forkでも同じ20列・schema33、32→33 upgrade、33→32 recipe、#118のtransaction ownershipを維持する。未改変16-devのschema32を採用することは、現在のforkデータの互換移行にならない。schema番号だけの一致をrollback保証にしない。

Novaのbaseline側2 commitは**両方採用・保持**する。警告/toggleと座標・grid変換を一組で移植する（§4）。grid migrationの分割先へ#59のjournal/source authorityを移し、restoreの#58/#187、recoveryのADR-0003/0009/0011とDeck退役ADR-0006を保持する。これらの製品・永続化契約を変更する必要はない。ADR-0018 Decision 2/5を本判断と比較範囲へ更新し、新しいデータ形式・個別specは起票しない。形式や失敗意味論を変える実装が必要になった場合は、rebase中の便宜で決めず既存spec/ADRを改訂する。

| 記号 | 固定revision / 意味 |
|---|---|
| B | upstream旧baseline `505dbc40e6154c05158b5d0271c45f6a885a411b` |
| U | ADR-0018の採用候補 `43a21b43d7cc7850ab54e14b1a57dc9646685f35` |
| F | 調査時点のfork main `80e10bb866d01eb26f156df3c80fc4471c43f708`（PR #529 merge） |
| R | **将来のcutover merge直前**の15系fork main headをtag化したrollback点。FやBに固定するものではない。RのAPK/データ契約を切替前に再照合する |

全コード引用はFまたはU固定。B→Uのschema/json差分がないことを`git diff B U -- res/raw/downgrade_schema.json`と各`SCHEMA_VERSION`で確認した。F→Uはその差分とfork契約の再適用要否を調べたもので、実行済み移植ではない。

## 2. 問い1/2: upgrade・downgrade

### Launcher DB・lock state

| 経路 | 確定した扱い / 限界 |
|---|---|
| F15 fork → 16 fork → R15 fork、同じDB形状 | 最終forkはschema33を維持。SQLiteOpenHelperのversion callbackは33→33では呼ばれない。20列の型/defaultとデータを保ち、LOCKED(2)/UNLOCKED(1)/UNKNOWN(0)を保持する。IDP・grid選択・profile remap等の別処理は走り得るため、version不変だけではrow保持を証明しない |
| schema32資産 → fork33 | `organizerLockState INTEGER NOT NULL DEFAULT 1`を追加し、既存rowをUNKNOWN(0)にする。fresh rowはUNLOCKED(1)。callback外側transactionで列追加と既存row更新を一体にし、失敗を伝播、wipeせずschema32とrowを保持する（ADR-0004） |
| fork33 → 歴史的schema32 binary | `downgrade_to_32`のrename/create/19列の明示copy/dropでlock列を除去。rowは成功時保持、再upgradeはUNKNOWN。これはRへのrollbackとは別経路。古いbinaryのnested transaction失敗はfileを33のまま残し得る。全旧APKへのlossless downgradeを保証しない |
| grid変更・inactive DB正規化 | schema32 sourceはupgradeでUNKNOWNになる。既存schema33 sourceのlockは保持し、migration成功後のtargetは全row UNKNOWN。inactive32をschema依存copy前に正規化する。失敗時のsource authorityは§5 |
| Android restore sanitize | 消滅profileのrow削除と生存profileのserial remapは既存契約の例外。生存rowのlock値を保ち、profile削除をschema migrationによる損失と混同しない |

根拠: C1/C2/C3/C7、[ADR-0004](../adr/0004-organizer-lock-persistence.md)、[spec #118](../../specs/118-sqlite-migration-transaction-audit/spec.md)。favoritesのupstream19列はB/Uで連続しているが、forkの20列目をdropしてはいけない。同じversionで違う必須列を持つ形式も作らない。将来新列を採用するなら別schema versionとmigration/downgrade契約が必要。

### Lawnchair preferences

Compose/DaggerへのAPI移行とon-disk移行を分ける。C4/C5で、SharedPreferencesの名前、DataStoreの`preferences`名（`files/datastore/preferences.preferences_pb`）、legacy→DataStoreのkey mapはB/F/Uで同一。`PreferenceManager.CURRENT_VERSION=2`も同じ。legacy migrationは既存destinationを優先しBoolean/Float/Int/Long/String/StringSetをコピーする。逆移行を行うforkコードはなく、legacyキーが必ず永久に残るとも保証しない。DataStoreは15 forkも利用するため、Compose化それ自体を新しいDB変換と扱わない。

| 差分 / root | port・rollbackの条件 |
|---|---|
| grid: 同じ型/keyの`pref_workspaceColumns/Rows`・`pref_hotseatColumns`、`LauncherPrefs.DB_FILE` | 保存済み値・DB選択の整合を保持。Uのdevice別defaultは未保存キーの実効gridを変え得る。明示保存済みfixtureと未保存fixtureを分け、必要なgrid migrationを#59経路へ通す。旧APKへ戻した後もprefsと実際のDB/gridの一致を照合する |
| `prefs_wrapAdaptive` false→true、`pref_drawerOpacity` 1→0.5、`pref_coloredBackgroundLightness` 0.9→1 | 同じ型の保存値をdefaultで上書きしない。未保存時の新defaultは挙動差として記録する。defaultsの差を実データ消去と呼ばない |
| fork固有キー（例: `pref_new_app_destination`、`organizer_personalization_recording`、`exchange_ai_consultation_enabled`） | Uにconsumerがなくてもforkのconsumer/type/keyを保持する。三例は全キー棚卸しの代替ではない。Phase 2で最終F/Rとの差分を全key・file・type単位で再照合する |
| `enable_lawn_deck` / `show_deck_layout` | Uのruntimeを復活させず、Fのprivate tombstone・paired false正規化とstartup/旧backup cleanupを再適用する（ADR-0006）。旧Deck有効APKへの戻しはRへの戻しと区別する |
| LauncherPrefs: UのGRID_TYPE、NO_DB_FILES_RESTORED、WORKSPACE_SCREEN_ORDER、landscape keys追加、SHOULD_SHOW_SMARTSPACE削除、PROMISE_ICON_IDS backedUp→nonRestorable | 追加キーだけでDB schema bump不要。旧consumer不在/新default・複数fileのroutingがrollbackで不整合を作らないことを確認する。必要なfork smartspace consumerは移植する。Android backup policy変更とZIPのraw XML保存を混同しない |
| UのcachedPreferences/Dagger・listener API変更 | #59の同期commit成功判定＋readback、#168のtyped setters/batchEdit→IDP main-thread反映→DeviceGridState同期commit（最後のdurability barrier）を再接続する。非同期cacheとXML raw swapを跨ぐcold-process確認が必要 |

判断は「形式の連続性あり、fork再適用と実行証拠が必要」。新キーを追加しただけで全設定の可逆性を主張せず、rename/type変更、削除/正規化、backup policy、cache/listenerの作用をPhase 2で実測する。lossy変換が見つかればRが読める復旧方法と残存リスクをOwnerへ戻す。

### organizer recovery store

C8でLauncher DBと別の`organizer_recovery.db`はphysical schema **3**、logical record format **2**。同形状の16 fork/Rならそのまま保持する。schema2→3のchunk migration、checksumとmanifest/revision/contextの検証、newer/unreadableのread-only probe・fail-closed、snapshot publication/分類とretentionを継続する（ADR-0003/0009）。別DB間のatomic commitを主張しない。

APK更新だけでrecovery DBや`no_backup/recovery-inspection/`を清掃しない。保持されたpointでもgrid/device profile/profile inventory等のcontextやrevisionが変わればNotRestorableになり得る。pointの存在と安全にrestoreできることは別。Nova外部restoreではVERIFIED pointを一律事前無効化せず、要求時の照合を使う（[#171 Stage D](./issue-171-organizer-after-external-restore.md)）。schema2時代のbinaryはschema3を拒否し、Launcher layoutへ触れない。これは現在のschema3を持つRとは別のdowngrade境界。

### rollbackの実行条件

1. ADR-0018どおりcutover直前Rを`pre-lawnchair16-<date>`へtag固定し、source SHA、APK SHA-256、package/signing certificate、versionCode、build variant、OS buildを記録する。旧mainへのforce-resetはしない。
2. R15→最終16→R15→16の**同一app dataを保持したAPK入替**をPhase 2/3で行う。uninstall、`pm clear`、connected testの終了時wipeを挟んだ別fixtureをdowngrade証拠にしない。debugの許可された`adb install -r -d`等とreleaseの署名/version制約を区別し、旧sourceから再発行するreleaseのinstall条件も記録する。
3. 毎回cold start後、active/inactive DBのversion/列/default/row、lock、prefsのkey/type/DB_FILE/grid、recovery3/format2のmanifest/checksum/stateを独立読取で比較する。通常操作・grid変更・ZIP/Nova restore後を別ケースにする。ZIP前のpointsは復元しない（§3）。SQLite backup/WALを一部だけコピーした比較は不可。
4. **Gitのrevertだけではデータは戻らない。** 同形状でも16での配置変更・設定書込みを消さない。Rで正常に開けることを確認する。破壊的ZIP復元のrollbackは旧checkpointではなく、その形式と条件に対応した事前backupからの明示restoreである。
5. 実証未完で切替る例外はADR-0018のPhase 4 Owner decision（代替検証と残存リスクを明記）が必要。本研究の承認はその例外承認ではない。Rが進んだ場合はFとのデータ契約差分を再評価し、固定U刷新もADR改訂を要する。

## 3. 問い3: ZIP・Nova資産とrecovery生存性

| 資産/操作 | 形式・互換判断と保持条件 |
|---|---|
| Lawnchair ZIP v1 | C6: metadata（version、timestamp、contents、gridState、preview size、dark text）、`launcher.db`、`com.android.launcher3.prefs.xml`、`preferences`、`preferences.preferences_pb`、preview/wallpaperの条件付きentry名とprotoはB/F/Uで同じ。DBはactiveだけ、restore先は`restored.db`。未知entryはreaderが無視する。形式の連続性を確認したが、破損/欠落entryを含む任意ZIPのreadabilityやatomic rollbackは保証しない |
| schema32 / schema33 ZIP | 古い32資産はhelperの32→33でUNKNOWN、33資産はlock列ごと保存・sanitizeの許容変更を除き値を保持。inactive grid DB・recovery DB・inspection snapshotはZIPに含まれない。ZIP往復でrecovery pointsを持ち運べると説明しない |
| ZIP restoreのepoch | C6: BACKUP_RESTORE lease → module RunMutex内でsnapshot cleanup/absence確認 → quiesce/helper close → databases directory wipe。cleanup失敗ならquiesce/wipe前でhard stop。以後prefs/file write→fresh helper→sanitize→相関reloadまでleaseを保持する。pre-ZIP pointsは無効化される（ADR-0011/spec #187）。wipe後のcopy失敗を全体rollback可能と偽らない |
| selectionの既存挙動 | F/Uとも選択maskはrestore handlerだけを制限し、databases wipeは**wallpaper-onlyを含め全restoreで無条件**。exportも存在するDB/prefsをmaskにかかわらず保存する。これは既存挙動の観測であり今回の修正対象ではない。wallpaper-onlyを「layout/recoveryに影響しない」とする試験期待値を置かない。改善する場合は別specでepoch/選択契約を更新する |
| Nova資産 | C9: Nova ZIPの`nova.db`と設定を既存parserで読み、現行のsubgrid/smartspace変換を保持（§4）。raw Nova DBをLauncher DBへ同形と扱わない。converted/staged DBをschema33へ開き、layout rowsのlockは新規/default値・既存migration規則どおり。#168/#299のactive DB/grid反映・capture repairを保持 |
| Nova後のrecovery | ZIPのdirectory wipe/epoch cleanupをNovaへ横展開しない。#171のVERIFIED retention＋request-time revision/context照合を維持。既存pointがstaleならfail-closed、無理にchecksum/contextを更新しない。Novaからのrecovery artifact importはない |
| Android full backup/restore | recovery DB・sidecars・inspection等のbackup除外を維持（ADR-0003）。ZIP raw XMLとAndroid backedUp/nonRestorable item routingは異なる。restoreのprofile/widget remapとfresh helperが必要で、APK upgradeのみのケースに代用しない |

ZIP/Novaの成功時互換性と、ZIPの既存破壊的失敗意味論を分離する。restore後にrecovery DBだけ不在・古いsnapshotだけ残るSuspiciousAbsenceを作らないことが#187のoracle。snapshot除去失敗時、DBを保持したExisting状態と部分snapshot削除を許す既存契約を保つ。

## 4. Nova baseline 2 commitの採否（rebase前の決定）

| baseline commit / 挙動単位 | 決定・port条件・検証oracle |
|---|---|
| `9b48473c7e` / subgrid検出・警告 | **保持**。desktop_gridのsubgrid marker検出と`isSubgrid`をViewModel→警告UIへ接続。fractional layoutの再現限界をユーザーに知らせる。parserだけ/文言だけの移植で完了しない |
| 同commit / 座標変換 | **保持**。cellX/cellY/spanX/spanYは`roundToInt()`、span最低1。Uの`.toInt()`へ戻さない。例えば正の1.5→2を期待値として固定し、0.49/0.5/0.51と1.49/1.5/1.51、四フィールドを独立assertする。expectedをproduction変換から生成しない |
| `53a2092541` / smartspace conflict toggle | **保持**。復元画面のenableSmartspace状態とrestoreが読む値を同じ設定へ接続し、ON/OFF両経路を検証する。警告/toggleは16-devで欠落しているため明示再適用 |
| 同commit / grid・item変換 | **保持**。ONでrows+1、desktop itemのcellY+1、spanをgrid境界内へclamp、desktop範囲外・hotseat許容量外のitemをskip。OFFは余分な行/shiftなし。C9のcontainer別処理を移植しfolder子へ一律shiftしない。skipは既存converterの明示import規則でありorganizer applyのconservation違反を許すものではない |

C9でB/Fの挙動とUの欠落を照合済み。上記は既存利用者のrestore結果を保持する選択であり、互換性を下げる代替への製品判断は不要。最終16のQSB/IDP予約領域と変換後gridの整合はT8で検証する。icon shadow submodule pinはADR-0018どおりPhase 2 planの別判断。

## 5. 問い4: grid splitへの接続とtransaction owner

Uの`GridSizeMigrationLogic`は名前に反して純粋な計算だけではなく、table copy、SQLiteTransaction、prefs writeを持つ。`ModelDbController.attemptMigrateDb`→Logicと`tryMigrateDB`→DBControllerの**二つの経路**がある（C10）。二つのtransactionが常にnestedという意味ではない。どちらもcopyがtarget transactionの前、finallyにdestination prefs/完了callbackがあり、controllerは先にtarget helperを公開しsourceを閉じる。これをforkへそのまま移さない。

| owner / bridge | Phase 2で維持する境界 |
|---|---|
| ModelDbController | GRID_MIGRATION leaseをtarget準備前からfinalization/補償まで保持。source helperを権威として保持し、targetはcommit閉鎖後に公開。Uの両entryと新callerを監査し、#59を迂回するresetLauncherDb/createEmptyDB fallbackを入れない |
| migration DB操作（分割先DBController/Logic） | backup snapshot＋canonical digest＋TARGET_OLD journal、copy/placement、target全row UNKNOWN、favorites_tmp cleanup、MIGRATED_PENDING_FINALIZATIONを**単一のtarget transaction**に入れる。pure placementを再利用しても副作用と成功判定をこのownerへ接続する。prefsや成功callbackをfinallyから実行しない |
| GridMigrationJournal / Operation / Runtime | 同じsource/target identity・backup digest・durable phaseを維持。既存Runtimeで実production operationに失敗注入し、test専用別経路/global hookを増やさない。commit後はtarget公開→source close→同期prefs commit/readback→FINALIZEDの順。失敗はRESTORE_PENDING/FAILEDと補償・fresh entry再試行、未解決はrecovery pendingとしてfail-closed |
| startup / cleanup | finalized journalはtarget/prefs/UNKNOWN/no tmp/digest検証後にだけbackupと削除。source identity/file/header/quick_checkを検証し、missing/corrupt sourceの代わりに空DBを作らない。active open・candidate target open・migration entryに同じreconcileを接続する |
| DatabaseHelper callbacks / DbDowngradeHelper | #118: SQLiteOpenHelperがouter owner、callback helperで子transactionを再導入しない。standalone recipe callerだけが明示scopeを持つ。createEmptyDBのstandalone scopeとfallbackのfirst-child条件を保持。lock upgrade失敗はwipeしない |
| LauncherDbUtils Kotlin化 / restore hooks | try-with-resources→useを含めcommit/end/throw semanticsを照合。#58のgetDb前lease/reentry、helper close→fresh open→sanitize→reload、#59のtransaction-close failure hooksを同じownerへ接続。削除されたonPostInit名を復元するだけではなく、startup normalizationの実callsiteへ接続する |

C1/C3/C7/C10と[spec #59](../../specs/59-preserve-source-grid-migration-failure/spec.md)、[spec #58](../../specs/58-serialize-runtime-restores/spec.md)、spec #118が正本。#118の古いupgrade/downgrade failure fallbackは、#59のgrid failureでwipeしない契約と別。成功時だけをテストしてjournal/補償を削除しない。

## 6. Phase 2テスト一覧・Phase 3実証への引継ぎ

これは**実行計画**。このPRではmigration test/APK build/端末実行を行わない。既存testも最終16上で再実行するまで合格としない。test-auditに従い、まず既存ownerを再利用・拡張し、実DB/framework/processでしか見えない差だけ上位で確認する。新しい恒久lane、test専用production seamは要求しない。

| ID / contract・credible regression | 再利用 / 追加・拡張するoracle | owner / 分類・実行面 |
|---|---|---|
| T1 schema33 / Uの32・19列をそのまま採用 | **再利用** DatabaseHelperSchema33Test、DowngradeSchema33Test、InactiveGridDbNormalizationTest。fresh33/default1、32→33 UNKNOWN、失敗rollback、33→32→33 row保持、inactive32とsource33 lock | surface_db_schema、既存db-migration API36 Conditional。実SQLiteOpenHelperが必要 |
| T2 transaction owner / Kotlin化でnested failed-childを再導入 | **再利用** MigrationTransactionOwnershipTest、Schema32RollbackBinaryTest。legacy upgrade・downgrade部分失敗のwipe commitと旧binary failureの33残存を区別。NestedTransactionTest（API35）は既存別platform oracle | db-migration API36 + production-input API35 Conditional。historical32 fixtureはRの代替ではない |
| T3 split grid / copyやprefsがtx外・source早期close | **再利用・両entryへ拡張** GridMigrationSuccessTest/FailureTest。fast/general、target既存/新規、各operation前/後throw、commit-close、sourceclose、commit=false、digest破損、missing source、unknown prefs、process restart各phase。成功target UNKNOWN/source保持、失敗source権威・未解決metadata保持を独立読取 | surface_db_schema/layout_write、既存db-migration Conditional。tryMigrateDBとattemptMigrateDbのcoverageを明示、片方だけgreen不可 |
| T4 restore lifecycle / lease前getDb・helper open中file swap | **再利用** RestoreLeaseSerializationTest（lease/reopen・sanitize失敗）、RestoreProfileRemapTest（直接profile remapと手動delete時のlock）、ModelWriterTransactionReentryTest（writer reentry）。**追加・拡張** 通常のRestoreDbTask.performRestore成功入口でprofile inventory→生存serial remap/消滅profile削除、widget ID/provider restoration、surviving lock保持を実DBでassert。既存三classだけではこの成功経路を証明しない | layout_write/backup_restore、shared-writer・db-migrationの既存filterを再利用。追加成功oracleは既存db-migration owner/laneへの拡張候補、現時点未実装・未routing（採用時filter/portfolio/map更新）。上流RestoreDbTaskTestは参考/on-demand、dir所属だけでCI実行済みとしない |
| T5 ZIP artifact / snapshotだけ残存・cleanup失敗後wipe | **再利用** LawnchairBackupRestoreCriticalSectionTest、RunMutexRestoreSuspensionTest、RecoveryStartupStorageClassifierTest/ArtifactsTest、RecoveryDbBackupExclusionTest。**追加** real ZIP32/33 fixtureを通常restore経路で復元しrow/lock/prefs・epochとcold-start READY/Pristineを確認。wallpaper-onlyも既存破壊的契約を観測。partial copy失敗は成功扱いしない | unitは既存organizer-unit Permanent。実ZIP/fs/helper差はbackup_restoreの既存API36 laneへ拡張候補（採用時filter追加とportfolio/map更新必須）。runtime testは現時点未実装・未routing |
| T6 recovery3/format2 / replayでcodec/gateやchunksを欠落 | **再利用** RecoveryRecordCodecTest、RecoveryManifestChunksTest、RecoveryStoreLifecycleTest、RecoveryStoreChunkedManifestInstrumentationTest、inspection/publication tests。schema2→3 checksum不変、oversize/chunk欠損・未来version、active lifecycleの保存、request-time stale/NotRestorable | unit Permanent＋既存reservation-recovery Conditional。APK version間の互換性はT9の独立process oracleで補う |
| T7 prefs / removed consumer・new defaults/cacheでDB選択が逆戻り | **再利用** LauncherPrefsCommitTest、NovaRestoreGridApplicationTest、DeckRetirementMigrationInstrumentationTest。**追加・拡張** legacy XML+既存DataStore conflict/missing key/type、gridキー保存有無、全fork key、tombstone、typedsetter/listener/cacheとfresh startのkey/type/activeDB readback。未知キーは未消去を比較 | db_schema/backup_restoreの既存lane候補。key変換は最低層、cache/IDP/framework差は既存instrumentation ownerを拡張。新coverageのfilter/分類はPhase2実装PRで記録 |
| T8 Nova 2 commits / warningだけ戻しデータ変換を欠落 | **再利用** NovaRestoreGridApplicationTestと#299のControl/WidgetWindow/UnknownProvider/NoCallbacks/CrossProcess A/B。**追加** converter通常入口にfractional四フィールド、smartspace ON/OFF、desktop/hotseat/folder、clamp/skip fixture。手書き期待値のconverted DB/gridとwarning/toggleをassert。API37のQSB予約/capture/reload結果も確認 | backup_restore既存restore-capture API36 Conditional（class毎独立起動、A/B間force-stopを維持）。converterを最低忠実境界で拡張、UIは状態接続の差だけ。新Nova oracleは未実装・未routing |
| T9 F/R15↔16実APK / schema等しくてもprefsやcontext非互換 | **追加の互換実証** §2 rollback手順でR→16→R→16、同一data/cold process、通常・grid migration・ZIP32/33・Nova後を分離。DB/prefs/recoveryを独立比較、samegrid lock保持とchangedgrid UNKNOWN、point存在/復旧可否を別assert | Phase2/3 on-demand release-compatibility evidence。既存connected invocationだけではAPK/data保持を証明できない。Permanent laneを追加せず、API36 emulator＋API37 Pixel9a実機、Phase4 Owner closure |

Fの実際のCI invocationを照合した: `.github/workflows/ci.yml:494`はT1/T2/T3とLauncherPrefsCommit/DeckMigration/RestoreProfileを明示class filterに持つ。`:759`はT6のrecovery/chunks/inspectionを持つ。restore-captureは`tools/ci/run-restore-capture-instrumentation.sh:15-36`が#299の独立invocationとNovaRestoreGridApplicationを実行する。unit filterはbackup/store/protocolを含む。GridChangeUnknownLockRecoveryInstrumentationTest等、表にないtestや上流testはdirectoryだけでroutedと推定しない。

Phase 2のplanにはT1〜T9のowner、最終source SHA/command/filter/fixture、追加oracleのprotected contractと既存overlap、CI分類を記録する。既存実行面のfilter変更時はportfolio/mapを同じPRで更新する。Phase 3は最終merged treeとR、OS/API/build/signature、実結果と未実証を記録する。#521のV7（API37 model/recovery/SAF）とも接続し、API36だけをAPI37合格に代用しない。

## 7. 固定コードの証拠と外部参照

以下のpath/lineは調査時点固定revisionのもの。Fは本assessmentのsource SHAではなく比較対象。行範囲は根拠の所在を示す。

| ID | source根拠 |
|---|---|
| C1 | [F DatabaseHelper](https://github.com/nunu1733/NunuLauncher/blob/80e10bb866d01eb26f156df3c80fc4471c43f708/src/com/android/launcher3/model/DatabaseHelper.java#L75): 75,166,286–336。U同path:77,170–179,290–299。F33/U32、lock upgrade、fallback tx |
| C2 | F `src/com/android/launcher3/LauncherSettings.java:371–413`、`res/raw/downgrade_schema.json:5–10`。U LauncherSettings:325–363、json:version32。19+lock列と明示33→32 recipe |
| C3 | F `src/com/android/launcher3/model/DbDowngradeHelper.java:55–77` vs U:56–72。callback-owned vs内側SQLiteTransaction。`LauncherDbUtils`のJava→Kotlin化はcommit/close監査対象 |
| C4 | F `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt:53–59,148–162`、`BasePreferenceManager.kt:60–66`。U PreferenceManager:77–115,216–239。fileはLauncherFiles/UtilitiesのSharedPreferences routing |
| C5 | F `lawnchair/src/app/lawnchair/preferences2/SharedPreferencesMigration.kt:34–91`（B/U差分なし）、`PreferenceManager2.kt:99–103,275–297,829–864`。U PreferenceManager2:112–119,797–805,878–915,975–982。F `src/com/android/launcher3/LauncherPrefs.kt:270–300` / U:243–313。型/key/name、tombstone、cache/new items |
| C6 | [F LawnchairBackup](https://github.com/nunu1733/NunuLauncher/blob/80e10bb866d01eb26f156df3c80fc4471c43f708/lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt#L63):63–129,133–170,188–239,245–260。U:60–103,107–140,149–174,181–209。`lawnchair/protos/lawnchair.proto:8–28`はB/U差分なし |
| C7 | F `src/com/android/launcher3/provider/RestoreDbTask.java:205–288`、`ModelDbController.java:127–155`。U RestoreDbTask:210–226。lease前getDb・freshhelper・sanitize/reload。rename失敗の握りつぶしを含め最終treeの失敗経路を再監査 |
| C8 | F `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbSchema.kt:15–26`、`RecoveryRecordCodec.kt:15–34`、`RecoveryDbVersionGate.kt:28–75`。physical3/logical2、read-only version gate。recovery bridgeはUにないfork所有 |
| C9 | [F NovaBackupConverter](https://github.com/nunu1733/NunuLauncher/blob/80e10bb866d01eb26f156df3c80fc4471c43f708/lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt#L176):176–193,375–390,416–446,567–585。U同path:153–155,358–362。F/U `lawnchair/src/app/lawnchair/backup/ui/RestoreNovaBackupScreen.kt:103–166`のsubgrid warning/smartspace switch有無もdiff確認 |
| C10 | [U ModelDbController](https://github.com/LawnchairLauncher/lawnchair/blob/43a21b43d7cc7850ab54e14b1a57dc9646685f35/src/com/android/launcher3/model/ModelDbController.java#L356):356–469、U `GridSizeMigrationDBController.java:123–195`、`GridSizeMigrationLogic.kt:49–155`。F ModelDbController:418–420,462–505,574–617,749–780,820–860、`GridSizeMigrationUtil.java:158–193`、`GridMigrationJournal.java:14–38,77–117`、`GridMigrationRuntime.java:6–29`、`GridMigrationOperation.java:3–17` |

**External reference scan: 事例の記載**（確認日2026-10-04。外部patternより既存spec/ADRを優先し、source copyは行わない）。

| 対象 / URL | 採用・不採用の理由 |
|---|---|
| AOSP Android16 [SQLiteOpenHelper.java:410–443](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/database/sqlite/SQLiteOpenHelper.java#L410) | version相違時のouter transactionでcallback→setVersion→commit/end。33→33ではmigration callbackなし、#118のframework所有を再確認。これだけではアプリの別処理/rollback保証にならない |
| AOSP Android16 [SQLiteSession.java:435–463](https://github.com/aosp-mirror/platform_frameworks_base/blob/android-16.0.0_r1/core/java/android/database/sqlite/SQLiteSession.java#L435) | failed childがancestorのmChildFailedを立てる。例外をcatchしただけではouter成功に戻らないため、Uのnested scopeを移植しない |
| Lawnchair固定U（C1/C3/C6/C9/C10） | ZIP v1の継続とplacementを再利用する。schema32、grid early-publication/finally prefs、restore lease不在、Nova変換欠落はfork契約へ適合させて採用する |

## 8. 終了条件と未実証

| #522終了条件 | 成果 |
|---|---|
| 問い1〜4の証拠付き回答 | §2（upgrade/downgrade/prefs/recovery）、§3（ZIP/Nova）、§5（split/transaction）、C1〜C10 |
| Phase 2テスト一覧と再利用/追加の区別 | T1〜T9。APK入替の独立oracleと既存CI routingの限界も明示 |
| 契約変更要否とADR/spec処置 | 永続化/失敗契約は維持。Nova二件を保持、fork33とrollback Rの境界をADR-0018 Decision 2/5へ反映。新形式/spec不要 |

今回の検証は固定コード・既存spec/ADR/test/CIの読取とrepository文書検証のみ。最終16のbuild、APK入替、ZIP/Nova runtime、API36/37実機、release install可否は未実証。T4/T5/T7/T8の追加oracleは計画段階であり、現在のtestがそれらを既に守るとは主張しない。Epic #516 Phase 2/3/4の担当が実装・実証・cutover decisionを引き継ぐ。

## Change history

- 2026-10-04: 起草。#522はresearch完了が終了条件で、production移植/runtime証明はEpic #516の後続Phaseが所有する。F/B/U固定で問い1〜4、Nova二件採用、Phase2 T1〜T9を記録し、ADR-0018とPhase0比較範囲注記を同期した。

- 2026-10-04（review revision）: PR #530初回独立reviewのP2を反映。T4の既存testが守るlease/直接remap範囲と、正常sanitize/profile inventory/widget ID・provider remapの追加・拡張oracleを区別し、未実装/未routingと明記。C7のreload引用範囲とZIP条件付きentryの表現も精密化した。
