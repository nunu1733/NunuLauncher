# Candidate ownership inventory（G3計測記録）

> Status: **candidate（未採択）** — plan §7 G3の計測記録。accepted baselineの正式な再採択はPhase 4に残す（本candidateを自動採択しない）。
> 計測対象: upstream（anchor）`43a21b43d7cc7850ab54e14b1a57dc9646685f35` → target（HEAD）`7f46ab6466075ebf5a39f954cf3376d2968e6353`（branch `issue-532-phase2-restart`）
> 計測日: 2026-10-05 / 作成: G3（candidate ownership inventory計測）
> 正本: [plan.md §7 G3行](./plan.md) / 計測script `tools/repo-contract/measure_upstream_patch_surface.py` / candidate JSON [ownership-inventory-candidate.json](./ownership-inventory-candidate.json)

## 1. 合格判定

**G3合格条件「全non-excluded production差分がowner分類され、増分・移動がreview済み」: 満たす（判定: PASS）。**

- 計測scriptがfail-closedで全changed pathの分類を強制し、candidate inventoryを `--baseline-file` として `PASS: measurement completed with complete bridge ownership.`（exit 0）を確認した。未分類pathは **0件**。
- 旧inventoryの `counted_files == 105` の一致はplan §7どおり合格条件から外している（adapt後のpath移動でfail-closedするため）。実測countedは388（内訳 §3）。
- 増分・移動のreview: §7の旧→新移動表（105 path全件の所在）、§8の帰属表（全570 production pathの内容帰属と由来単位）、§6のpin divergence review（59 pin全件）を根拠とする。
- 本判定はcandidate inventoryの分類完全性の判定であり、accepted baselineの再採択（Phase 4で最終headを再計測）を意味しない。

実行command:

```bash
python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --baseline-file specs/516-16-rebase-phase2/ownership-inventory-candidate.json \
  --upstream 43a21b43d7cc7850ab54e14b1a57dc9646685f35 \
  --target 7f46ab6466075ebf5a39f954cf3376d2968e6353
# => PASS: measurement completed with complete bridge ownership. (exit 0)
```

## 2. 計測方法

- `--baseline-file` にcandidate JSON（本spec dir同梱）を渡す。JSONは旧 `docs/assessment/upstream-patch-surface-baseline.json` と同一schemaで、`upstream_commit`/`main_commit` を新ペアに置き換え、bridge_groupsを旧owner→新pathの写像で再構成したもの。`expected_measurement` は `--print-expected-measurement` で生成した実測値。
- 分類precedenceはscript本体どおり（bridge group明示割当て > content pin > 明示的exclusion > project-owned addition > エラー）。本candidateでは **content pinを1件も保持しない**（§6）。
- 内容帰属の判定方法: 各pathについて `git rev-parse <rev>:<path>` のblob比較で
  - `fork_content_kept`: HEAD blob == 旧main `8af117b6fc7c` blob（fork main内容の持ち越し。anchor側16-dev進化は持越しによりrevertされた状態）
  - `adapted_or_changed`: HEAD blobがanchorとも旧mainとも相違（S1〜S3/G2の再表現・修復）
  - `new_file_post_main`: 旧mainに存在しないpath（anchorで16-devが追加したfileへの変更、または修復commitで生成）
  - `deleted_at_head`: HEADで削除
  - を機械判定し、由来単位は `git log --first-parent 43a21b43..HEAD -- <path>` の最古接触commit（`PR #N` / `Direct:` / S1〜S3/G2/fix/wip）で帰属付けた。

## 3. 計測結果（path数・分類内訳）

全changed path 1,832件 = production差分 570件（explicit exclusion適用後）+ 非production除外 1,262件。production 570件の内訳:

| 分類 | path数 | 行数 |
|---|---:|---|
| counted（bridge group割当て） | **388** | +35,581 / −20,376 |
| project-owned addition（`organizer/` `migration/` prefix の新規file。旧179→新178） | 178 | +37,322 / −0 |
| explicit exclusion（.github/docs/specs/tests/tools/.md等 + Inter TTF 4件） | 1,266 | +225,673 / −471 |

counted 388のgroup別内訳:

| group | files | additions | deletions | 旧inventory |
|---|---:|---:|---:|---|
| deck-retirement | 19 | +1,375 | −1,678 | 20（LauncherPrefs.ktが消失→19） |
| organizer-ui-and-lock-authoring | 13 | +6,292 | −37 | 13（同一path集合・同一paths_sha256） |
| model-reload-and-transaction-gates | 10 | +3,387 | −257 | 11（LauncherModel.java→.kt移行、src MainThreadInitializedObject/IDP消失、LauncherAppState.kt追加） |
| layout-schema-and-recovery | 11 | +891 | −187 | 11（GridSizeMigrationUtil分割→shell再接続、LauncherDbUtils.java→.kt移行） |
| homeedit-edit-surface | 22 | +9,557 | −60 | 22（同一path集合・同一paths_sha256） |
| homeedit-edit-undo | 8 | +933 | −4 | 8（同一path集合・同一paths_sha256） |
| organizer-home-entry | 2 | +36 | −2 | 2（同一path集合・同一paths_sha256） |
| new-app-destination | 10 | +970 | −142 | 10（同一path集合・同一paths_sha256） |
| fork-platform-preexisting | 9 | +639 | −50 | 8（diverged pinのlibs.versions.tomlを可視化のため追加） |
| **anchor-integration-repairs（新設）** | 62 | +3,361 | −1,611 | —（旧inventoryにowner無しのS1〜S3/G2修復増分） |
| **replay-carried-fork-surface（新設）** | 165 | +3,287 | −4,793 | —（旧inventoryにowner無しのreplay/WIP持越し増分） |
| **replay-carried-localization（新設）** | 57 | +4,853 | −11,555 | —（旧pin 57件のdiverged分。pin廃止してcounted化） |

## 4. 旧→新path移動の主要マッピング（plan §3「要対応の3点」を含む）

| 旧path（旧group） | 新path / 状況 | 形態 |
|---|---|---|
| `src/com/android/launcher3/LauncherModel.java`（model-reload） | `src/com/android/launcher3/LauncherModel.kt`（M。16-devで.java→.kt化済みのanchor fileへfork契約を移植。S2 `51f0b6e339`） | **改名adapt** |
| `src/com/android/launcher3/model/GridSizeMigrationUtil.java`（layout-schema） | 同名pathが **A（新規）**。中身はS3a `20249df0e9` が新規作成したtransaction shell（journal契約駆動のみ）。配置算法本体はanchor分割先 `GridSizeMigrationDBController.java` / `GridSizeMigrationLogic.kt`（両entry `tryMigrateDB`/`attemptMigrateDb` 接続済み。anchor側で無変更のため差分surface外） | **分割adapt + shell新設** |
| `src/com/android/launcher3/util/MainThreadInitializedObject.java`（model-reload） | **消失**（HEAD==anchor blob）。16-dev上流がonPostInitを削除したためfork patch不要化。代替hookは `LauncherAppState.kt`（M, +37行のみ。init末尾で `LawnchairApp.onLauncherAppStateCreated(this)`、component構築中の再入なし、preview/sandbox非発火。S2c `abe589ee07`）と `LawnchairApp.kt`（旧deck-retirement member、adapt済み）に実装。lawnchair側同名facade（`lawnchair/.../util/MainThreadInitializedObject.java`）はsandbox分岐除去の収束編集のみ（anchor-integration-repairsへ分類） | **onPostInit代替hook** |
| `src/com/android/launcher3/provider/LauncherDbUtils.java`（layout-schema） | `src/com/android/launcher3/provider/LauncherDbUtils.kt`（M。16-devでKotlin化済みのanchor fileへ移植。S3a） | **改名adapt** |
| `src/com/android/launcher3/InvariantDeviceProfile.java`（model-reload。PR169 Nova restore authoritative） | **消失**（HEAD==anchor blob）。anchor 16-devのIDP/`DeviceGridState` APIを正とし、Nova復帰契約は `NovaBackupConverter.kt`（旧deck-retirement member、S3a `b18a98af48` でanchor API適合）側へ再表現 | **消失→代替実装** |
| `src/com/android/launcher3/LauncherPrefs.kt`（deck-retirement） | **消失**（HEAD==anchor blob）。旧fork差分（deck prefs key除去）がanchor 16-devでは不要と判断された状態。Phase 4再採択時に妥当性を再確認（§9懸念） | **消失** |
| dagger化: 旧 ForkBridgeModule / 旧model provider | `quickstep/dagger/.../LauncherAppComponent.java`（M、16-dev新設のDI graphへfork service binding追加）、`lawnchair/src/app/lawnchair/dagger/ForkServiceModule.kt`（A、S1新設。非model bindingの分離）、`lawnchair/.../preferences/PreferenceManagerModule.kt`（A）、`src/.../util/DaggerSingletonObject.java`（M）。旧 `ForkBridgeModule.kt` はS1 `792b792522` で削除 | **dagger再配線** |
| preview統合 | `src/com/android/launcher3/preview/PreviewContext.kt`（M、anchor preview moduleへの収束。S1/S3）、`lawnchair/.../ui/preferences/components/LauncherPreview.kt`（M、S3）。旧 `graphics/LauncherPreviewRenderer` はS3 `9636d7c0f9` でstale削除 | **preview統合adapt** |
| stale削除 | `quickstep/src/com/android/quickstep/SystemUiProxy.java`、`quickstep/.../logging/StatsLogCompatManager.java` を **D**（16-devの `.kt` 化に伴う重複javaの削除。WIP持越し→fix `4198c0a6e7` 修復→S3 stale削除。HEADはanchor `.kt` を使用） | **stale java削除** |

残り92件の旧counted pathは同一pathで存続（内訳は§7の移動表）。うち `organizer-ui-and-lock-authoring` / `homeedit-edit-surface` / `homeedit-edit-undo` / `organizer-home-entry` / `new-app-destination` の5 groupはpath集合が旧baselineと完全一致（`paths_sha256` 同一）。

## 5. 新増分のowner分類（旧inventoryにownerが無い284 path）

旧105 counted + 旧project-owned 179 + 旧pin 59 のいずれにも該当しない新増分は、内容帰属で2 group + localization 1 groupに分類した（いずれもcandidate新設group）:

- **anchor-integration-repairs（62）**: S1〜S3/G2のanchor適応・修復commitのみが触れたpath。DI再配線（LauncherAppComponent、ForkServiceModule、PreferenceManagerModule、DaggerSingletonObject、ContextTracker）、anchor API収束編集（Workspace、FolderIcon、Utilities、FeatureFlags、DeviceProfile、lawnchair MainThreadInitializedObject）、icon shape契約の持越し（`graphics/IconShape.java`、`res/xml/folder_shapes.xml`、`res/values/attrs.xml`、`ThemedIconDrawable.kt`）、`quickstep/res/drawable/bg_overview_clear_all_button.xml`、wmshell空blob marker修復（`wmshell/multivalentTestsForDevice(+less)`。fix `4198c0a6e7`）、stale java削除2件。
- **replay-carried-fork-surface（165）+ Inter TTF 4件**: replay/WIP wholesale restoreによりfork main内容が持ち越されたpath（icons、search、smartspace、gestures、theme、preferences UI、data/、font/、wallpaper、backup UI、util、views、allapps、qsb、`res/` drawable/font、`LauncherClient.java` 等）。**全pathでHEAD blob == 旧main blobを機械検証済み**。anchor側16-dev進化は持越しによりrevert状態であり、その再適用要否の判定はPhase 4再採択の対象（ADR-0018 Decision 9 + S3のanchor API収束済み。G1 build green）。
- **replay-carried-localization（57）**: `lawnchair/res/values-*/strings.xml` の翻訳。旧accepted baselineはcontent pinで除外していたが、**新ペアでは59 pin全件がdiverge**したためpinを廃止しcounted化して可視化。diff shape reviewを実施し、全変更行が `<string>` / `<plurals>` / `<item>` / `<xliff:g>` のstring resource要素のみ（非翻訳要素の変更 0行）を確認。

## 6. pinned content exclusionのdivergence review

旧59 pin（`build.gradle`、`gradle/libs.versions.toml`、localized 57件）は新ペアで **全件diverge**（anchor側blobが記録済み旧upstream blobと不一致。build 2件はtarget側も不一致）。scriptのfail-closed設計に従い、機械的な再pin（`--refresh-pins`）は行わず:

- `build.gradle` / `settings.gradle` → `fork-platform-preexisting` にcounted（anchor build土台+fork追加の統合を可視化。unit: PR #29/#464、S1 `5c20ccc810`）
- `gradle/libs.versions.toml` → 同groupへ追加counted（旧pin理由「organizer tooling/test消費のみ」はanchor土台統合後に再確認が必要なため可視化）
- localized 57件 → `replay-carried-localization` にcounted（§8.12のshape review記録済み）

Phase 4で正式再採択する際に、(a) このままcounted化するか、(b) 受入後に `--refresh-pins` でcontent pairを更新してpinに戻すかを判定する。

## 7. 旧105 counted path → 新path 移動表（全105件）

旧accepted baseline（`505dbc40..8af117b6fc7c`）のbridge group別counted path 105件の、新差分（anchor `43a21b43` → HEAD `7f46ab6466`）での所在。unit列はreplay-log §2の単位番号に対応するreplay PR / S stage。

| 旧group | 旧path | 新path | status | 内容帰属 | 由来単位 | 備考 |
|---|---|---|---|---|---|---|
| deck-retirement | `lawnchair/src/app/lawnchair/LawnchairApp.kt` | `lawnchair/src/app/lawnchair/LawnchairApp.kt` | M | adapted_or_changed | PR405 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` | `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` | M | adapted_or_changed | PR472 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | M | fork_content_kept | PR188 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | M | adapted_or_changed | PR314 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt` | `lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt` | D | deleted_at_head | PR79 | deleted (fork退役の再適用) |
| deck-retirement | `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | D | deleted_at_head | PR79 | deleted (fork退役の再適用) |
| deck-retirement | `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | M | adapted_or_changed | PR467 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/components/GestureHandlerPreference.kt` | `lawnchair/src/app/lawnchair/ui/preferences/components/GestureHandlerPreference.kt` | M | fork_content_kept | PR79 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt` | D | deleted_at_head | PR79 | deleted (fork退役の再適用) |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/destinations/DebugMenuPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/DebugMenuPreferences.kt` | M | fork_content_kept | PR82 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt` | M | fork_content_kept | PR467 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | M | adapted_or_changed | PR498 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/destinations/PreferencesDashboard.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/PreferencesDashboard.kt` | M | fork_content_kept | PR79 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | M | adapted_or_changed | PR403 | same path |
| deck-retirement | `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | M | adapted_or_changed | PR403 | same path |
| deck-retirement | `src/com/android/launcher3/DeleteDropTarget.java` | `src/com/android/launcher3/DeleteDropTarget.java` | M | adapted_or_changed | PR79 | same path |
| deck-retirement | `src/com/android/launcher3/LauncherPrefs.kt` | `—` | vanished | anchor content (fork差分不要) | — | 消失: HEAD==anchor blob（旧fork差分が新anchorでは不要/代替） |
| deck-retirement | `src/com/android/launcher3/dragndrop/DragController.java` | `src/com/android/launcher3/dragndrop/DragController.java` | M | fork_content_kept | PR79 | same path |
| deck-retirement | `src/com/android/launcher3/model/PackageUpdatedTask.java` | `src/com/android/launcher3/model/PackageUpdatedTask.java` | M | adapted_or_changed | PR79 | same path |
| deck-retirement | `src/com/android/launcher3/util/OnboardingPrefs.kt` | `src/com/android/launcher3/util/OnboardingPrefs.kt` | M | adapted_or_changed | PR95 | same path |
| organizer-ui-and-lock-authoring | `lawnchair/res/values/strings.xml` | `lawnchair/res/values/strings.xml` | M | adapted_or_changed | PR515 | same path |
| organizer-ui-and-lock-authoring | `lawnchair/res/values-ja/strings.xml` | `lawnchair/res/values-ja/strings.xml` | M | fork_content_kept | PR515 | same path |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt` | `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt` | A | fork_content_kept | PR73 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/CategoryOverridePreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/CategoryOverridePreferences.kt` | A | fork_content_kept | PR341 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | A | fork_content_kept | PR515 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt` | A | fork_content_kept | PR264 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `res/values/strings.xml` | `res/values/strings.xml` | M | adapted_or_changed | PR341 | same path |
| organizer-ui-and-lock-authoring | `src/com/android/launcher3/model/BaseLauncherBinder.java` | `src/com/android/launcher3/model/BaseLauncherBinder.java` | M | adapted_or_changed | PR160 | same path |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/CustomCategoryPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/CustomCategoryPreferences.kt` | A | fork_content_kept | PR341 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerDiagnosticsPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerDiagnosticsPreferences.kt` | A | fork_content_kept | PR396 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerStrategyPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerStrategyPreferences.kt` | A | fork_content_kept | PR493 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerUsageMaterialRows.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerUsageMaterialRows.kt` | A | fork_content_kept | PR391 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-ui-and-lock-authoring | `res/values-ja/strings.xml` | `res/values-ja/strings.xml` | M | fork_content_kept | PR341 | same path |
| model-reload-and-transaction-gates | `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java` | `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java` | A | fork_content_kept | PR403 | same path (A: anchorに無し=fork追加の再適用) |
| model-reload-and-transaction-gates | `src/com/android/launcher3/LauncherModel.java` | `src/com/android/launcher3/LauncherModel.kt` | M | new_file_post_main | S3a | rename (.java→anchor .kt) |
| model-reload-and-transaction-gates | `src/com/android/launcher3/LauncherProvider.java` | `src/com/android/launcher3/LauncherProvider.java` | M | adapted_or_changed | PR47 | same path |
| model-reload-and-transaction-gates | `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | A | fork_content_kept | PR169 | same path (A: anchorに無し=fork追加の再適用) |
| model-reload-and-transaction-gates | `src/com/android/launcher3/model/LoaderTask.java` | `src/com/android/launcher3/model/LoaderTask.java` | M | adapted_or_changed | PR319 | same path |
| model-reload-and-transaction-gates | `src/com/android/launcher3/model/ModelDbController.java` | `src/com/android/launcher3/model/ModelDbController.java` | M | adapted_or_changed | PR491 | same path |
| model-reload-and-transaction-gates | `src/com/android/launcher3/model/ModelWriter.java` | `src/com/android/launcher3/model/ModelWriter.java` | M | adapted_or_changed | PR498 | same path |
| model-reload-and-transaction-gates | `src/com/android/launcher3/util/MainThreadInitializedObject.java` | `—` | vanished | anchor content (fork差分不要) | — | 消失: HEAD==anchor blob（旧fork差分が新anchorでは不要/代替） |
| model-reload-and-transaction-gates | `src/com/android/launcher3/model/DirectEditContract.java` | `src/com/android/launcher3/model/DirectEditContract.java` | A | fork_content_kept | PR498 | same path (A: anchorに無し=fork追加の再適用) |
| model-reload-and-transaction-gates | `src/com/android/launcher3/InvariantDeviceProfile.java` | `—` | vanished | anchor content (fork差分不要) | — | 消失: HEAD==anchor blob（旧fork差分が新anchorでは不要/代替） |
| model-reload-and-transaction-gates | `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java` | `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java` | M | fork_content_kept | PR157 | same path |
| layout-schema-and-recovery | `res/raw/downgrade_schema.json` | `res/raw/downgrade_schema.json` | M | fork_content_kept | PR47 | same path |
| layout-schema-and-recovery | `src/com/android/launcher3/LauncherSettings.java` | `src/com/android/launcher3/LauncherSettings.java` | M | adapted_or_changed | PR47 | same path |
| layout-schema-and-recovery | `src/com/android/launcher3/model/DatabaseHelper.java` | `src/com/android/launcher3/model/DatabaseHelper.java` | M | fork_content_kept | PR122 | same path |
| layout-schema-and-recovery | `src/com/android/launcher3/model/DbDowngradeHelper.java` | `src/com/android/launcher3/model/DbDowngradeHelper.java` | M | fork_content_kept | PR122 | same path |
| layout-schema-and-recovery | `src/com/android/launcher3/model/FavoritesTableDigest.java` | `src/com/android/launcher3/model/FavoritesTableDigest.java` | A | fork_content_kept | PR75 | same path (A: anchorに無し=fork追加の再適用) |
| layout-schema-and-recovery | `src/com/android/launcher3/model/GridMigrationJournal.java` | `src/com/android/launcher3/model/GridMigrationJournal.java` | A | adapted_or_changed | PR75 | same path (A: anchorに無し=fork追加の再適用) |
| layout-schema-and-recovery | `src/com/android/launcher3/model/GridMigrationOperation.java` | `src/com/android/launcher3/model/GridMigrationOperation.java` | A | adapted_or_changed | PR75 | same path (A: anchorに無し=fork追加の再適用) |
| layout-schema-and-recovery | `src/com/android/launcher3/model/GridMigrationRuntime.java` | `src/com/android/launcher3/model/GridMigrationRuntime.java` | A | adapted_or_changed | PR75 | same path (A: anchorに無し=fork追加の再適用) |
| layout-schema-and-recovery | `src/com/android/launcher3/model/GridSizeMigrationUtil.java` | `src/com/android/launcher3/model/GridSizeMigrationUtil.java` | A | adapted_or_changed | PR75 | same path (A: anchorに無し=fork追加の再適用) |
| layout-schema-and-recovery | `src/com/android/launcher3/provider/LauncherDbUtils.java` | `src/com/android/launcher3/provider/LauncherDbUtils.kt` | M | new_file_post_main | S3a | rename (.java→anchor .kt) |
| layout-schema-and-recovery | `src/com/android/launcher3/provider/RestoreDbTask.java` | `src/com/android/launcher3/provider/RestoreDbTask.java` | M | adapted_or_changed | PR95 | same path |
| homeedit-edit-surface | `lawnchair/AndroidManifest.xml` | `lawnchair/AndroidManifest.xml` | M | adapted_or_changed | PR476 | same path |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceDuplicateGroups.kt` | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceDuplicateGroups.kt` | A | fork_content_kept | PR513 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/EditSurfacePlanBuilder.kt` | `lawnchair/src/app/lawnchair/homeedit/EditSurfacePlanBuilder.kt` | A | fork_content_kept | PR476 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceProjection.kt` | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceProjection.kt` | A | fork_content_kept | PR476 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceSessionPlanner.kt` | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceSessionPlanner.kt` | A | fork_content_kept | PR476 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceState.kt` | `lawnchair/src/app/lawnchair/homeedit/EditSurfaceState.kt` | A | fork_content_kept | PR476 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/HomeEditAdapter.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditAdapter.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/HomeEditExecutor.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditExecutor.kt` | A | adapted_or_changed | PR498 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/HomeEditModel.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditModel.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/HomeEditPlanner.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditPlanner.kt` | A | fork_content_kept | PR476 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/HomeEditSurfaceAccess.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditSurfaceAccess.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/ui/EditActionsShortcuts.kt` | `lawnchair/src/app/lawnchair/homeedit/ui/EditActionsShortcuts.kt` | A | fork_content_kept | PR472 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/ui/EditSurfaceScreen.kt` | `lawnchair/src/app/lawnchair/homeedit/ui/EditSurfaceScreen.kt` | A | fork_content_kept | PR515 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt` | `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt` | A | adapted_or_changed | PR513 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` | `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt` | `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt` | A | fork_content_kept | PR485 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/organizer/ui/GeneratedFolderTitles.kt` | `lawnchair/src/app/lawnchair/organizer/ui/GeneratedFolderTitles.kt` | A | fork_content_kept | PR476 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | A | fork_content_kept | PR515 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/ui/diagram/HomeDiagramParts.kt` | `lawnchair/src/app/lawnchair/ui/diagram/HomeDiagramParts.kt` | A | fork_content_kept | PR515 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` | `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` | M | adapted_or_changed | PR486 | same path |
| homeedit-edit-surface | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` | A | fork_content_kept | PR494 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoAvailability.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoAvailability.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoExecutor.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoExecutor.kt` | A | adapted_or_changed | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoPlanner.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoPlanner.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoRecord.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoRecord.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoRecoveryText.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoRecoveryText.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoVerifier.kt` | `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoVerifier.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditUndoSnackbar.kt` | `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditUndoSnackbar.kt` | A | fork_content_kept | PR481 | same path (A: anchorに無し=fork追加の再適用) |
| homeedit-edit-undo | `src/com/android/launcher3/folder/Folder.java` | `src/com/android/launcher3/folder/Folder.java` | M | adapted_or_changed | PR481 | same path |
| organizer-home-entry | `lawnchair/res/drawable/ic_organize_home.xml` | `lawnchair/res/drawable/ic_organize_home.xml` | A | fork_content_kept | PR486 | same path (A: anchorに無し=fork追加の再適用) |
| organizer-home-entry | `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt` | `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt` | M | fork_content_kept | PR486 | same path |
| fork-platform-preexisting | `build.gradle` | `build.gradle` | M | adapted_or_changed | PR481 | same path |
| fork-platform-preexisting | `settings.gradle` | `settings.gradle` | M | adapted_or_changed | PR464 | same path |
| fork-platform-preexisting | `lawnchair/res/values/config.xml` | `lawnchair/res/values/config.xml` | M | adapted_or_changed | PR467 | same path |
| fork-platform-preexisting | `lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt` | `lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt` | M | adapted_or_changed | PR169 | same path |
| fork-platform-preexisting | `lawnchair/src/app/lawnchair/backup/BackupPageSummaryReader.kt` | `lawnchair/src/app/lawnchair/backup/BackupPageSummaryReader.kt` | A | fork_content_kept | PR278 | same path (A: anchorに無し=fork追加の再適用) |
| fork-platform-preexisting | `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` | `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` | M | fork_content_kept | PR278 | same path |
| fork-platform-preexisting | `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt` | `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt` | M | fork_content_kept | PR278 | same path |
| fork-platform-preexisting | `lawnchair/src/app/lawnchair/bugreport/LawnchairBugReporter.kt` | `lawnchair/src/app/lawnchair/bugreport/LawnchairBugReporter.kt` | M | fork_content_kept | PR261 | same path |
| new-app-destination | `lawnchair/src/app/lawnchair/LawnchairProcessInitializer.kt` | `lawnchair/src/app/lawnchair/LawnchairProcessInitializer.kt` | M | adapted_or_changed | PR498 | same path |
| new-app-destination | `lawnchair/src/app/lawnchair/homeedit/AppDestinationAdapter.kt` | `lawnchair/src/app/lawnchair/homeedit/AppDestinationAdapter.kt` | A | fork_content_kept | PR498 | same path (A: anchorに無し=fork追加の再適用) |
| new-app-destination | `lawnchair/src/app/lawnchair/homeedit/AppDestinationNotice.kt` | `lawnchair/src/app/lawnchair/homeedit/AppDestinationNotice.kt` | A | fork_content_kept | PR498 | same path (A: anchorに無し=fork追加の再適用) |
| new-app-destination | `lawnchair/src/app/lawnchair/homeedit/AppDestinationPlanner.kt` | `lawnchair/src/app/lawnchair/homeedit/AppDestinationPlanner.kt` | A | fork_content_kept | PR498 | same path (A: anchorに無し=fork追加の再適用) |
| new-app-destination | `lawnchair/src/app/lawnchair/homeedit/AppDestinationSettingsText.kt` | `lawnchair/src/app/lawnchair/homeedit/AppDestinationSettingsText.kt` | A | fork_content_kept | PR498 | same path (A: anchorに無し=fork追加の再適用) |
| new-app-destination | `lawnchair/src/app/lawnchair/homeedit/ui/AppDestinationPreference.kt` | `lawnchair/src/app/lawnchair/homeedit/ui/AppDestinationPreference.kt` | A | fork_content_kept | PR498 | same path (A: anchorに無し=fork追加の再適用) |
| new-app-destination | `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt` | `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt` | M | adapted_or_changed | PR498 | same path |
| new-app-destination | `src/com/android/launcher3/model/AddWorkspaceItemsTask.java` | `src/com/android/launcher3/model/AddWorkspaceItemsTask.java` | M | adapted_or_changed | PR498 | same path |
| new-app-destination | `src/com/android/launcher3/model/ItemInstallQueue.java` | `src/com/android/launcher3/model/ItemInstallQueue.java` | M | adapted_or_changed | PR498 | same path |
| new-app-destination | `src/com/android/launcher3/util/PersistedItemArray.java` | `src/com/android/launcher3/util/PersistedItemArray.java` | M | fork_content_kept | PR498 | same path |

## 8. path別owner/attribution（全570 production path）

本節の全表で、内容帰属は§2のblob比較による機械判定、由来単位は `git log --first-parent 43a21b43..HEAD -- <path>` の最古接触単位（replay-log §2の単位対応）。

#### 8.1 deck-retirement（19 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/src/app/lawnchair/LawnchairApp.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR405 |
| `lawnchair/src/app/lawnchair/LawnchairLauncher.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR472 |
| `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR188 |
| `lawnchair/src/app/lawnchair/backup/NovaBackupConverter.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR314 |
| `lawnchair/src/app/lawnchair/deck/AddFoldersWithItemsTask.kt` | D | 削除 | PR79 |
| `lawnchair/src/app/lawnchair/deck/LawndeckManager.kt` | D | 削除 | PR79 |
| `lawnchair/src/app/lawnchair/preferences2/PreferenceManager2.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR467 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/GestureHandlerPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/HomeLayoutPreferences.kt` | D | 削除 | PR79 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/DebugMenuPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ExperimentalFeaturesPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR467 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR498 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/PreferencesDashboard.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR403 |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR403 |
| `src/com/android/launcher3/DeleteDropTarget.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR79 |
| `src/com/android/launcher3/dragndrop/DragController.java` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `src/com/android/launcher3/model/PackageUpdatedTask.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR79 |
| `src/com/android/launcher3/util/OnboardingPrefs.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR95 |

#### 8.2 organizer-ui-and-lock-authoring（13 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/res/values-ja/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/res/values/strings.xml` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR515 |
| `lawnchair/src/app/lawnchair/ui/popup/OrganizerLockShortcut.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR73 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/CategoryOverridePreferences.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/CustomCategoryPreferences.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/ManualOrganizationPreferences.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerDiagnosticsPreferences.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR396 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerStrategyPreferences.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR493 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerUsageMaterialRows.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR391 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/PlacementLockPreferences.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR264 |
| `res/values-ja/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `res/values/strings.xml` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR341 |
| `src/com/android/launcher3/model/BaseLauncherBinder.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR160 |

#### 8.3 model-reload-and-transaction-gates（10 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/src/com/android/launcher3/OrganizerModelReloadAdapter.java` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR403 |
| `quickstep/src/com/android/launcher3/hybridhotseat/HotseatRestoreHelper.java` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR157 |
| `src/com/android/launcher3/LauncherAppState.kt` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S2c |
| `src/com/android/launcher3/LauncherModel.kt` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S3a |
| `src/com/android/launcher3/LauncherProvider.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR47 |
| `src/com/android/launcher3/model/DirectEditContract.java` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR498 |
| `src/com/android/launcher3/model/LayoutWriteCoordinator.java` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR169 |
| `src/com/android/launcher3/model/LoaderTask.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR319 |
| `src/com/android/launcher3/model/ModelDbController.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR491 |
| `src/com/android/launcher3/model/ModelWriter.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR498 |

#### 8.4 layout-schema-and-recovery（11 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `res/raw/downgrade_schema.json` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR47 |
| `src/com/android/launcher3/LauncherSettings.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR47 |
| `src/com/android/launcher3/model/DatabaseHelper.java` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR122 |
| `src/com/android/launcher3/model/DbDowngradeHelper.java` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR122 |
| `src/com/android/launcher3/model/FavoritesTableDigest.java` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR75 |
| `src/com/android/launcher3/model/GridMigrationJournal.java` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR75 |
| `src/com/android/launcher3/model/GridMigrationOperation.java` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR75 |
| `src/com/android/launcher3/model/GridMigrationRuntime.java` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR75 |
| `src/com/android/launcher3/model/GridSizeMigrationUtil.java` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR75 |
| `src/com/android/launcher3/provider/LauncherDbUtils.kt` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S3a |
| `src/com/android/launcher3/provider/RestoreDbTask.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR95 |

#### 8.5 homeedit-edit-surface（22 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/AndroidManifest.xml` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR476 |
| `lawnchair/src/app/lawnchair/homeedit/EditSurfaceDuplicateGroups.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR513 |
| `lawnchair/src/app/lawnchair/homeedit/EditSurfacePlanBuilder.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR476 |
| `lawnchair/src/app/lawnchair/homeedit/EditSurfaceProjection.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR476 |
| `lawnchair/src/app/lawnchair/homeedit/EditSurfaceSessionPlanner.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR476 |
| `lawnchair/src/app/lawnchair/homeedit/EditSurfaceState.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR476 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditAdapter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditExecutor.kt` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR498 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditModel.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditPlanner.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR476 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditSurfaceAccess.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/ui/EditActionsShortcuts.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR472 |
| `lawnchair/src/app/lawnchair/homeedit/ui/EditSurfaceScreen.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditSurfaceActivity.kt` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR513 |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/LayoutApplicationModule.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR485 |
| `lawnchair/src/app/lawnchair/organizer/ui/GeneratedFolderTitles.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR476 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/ui/diagram/HomeDiagramParts.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/ui/popup/LauncherOptionsPopup.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR486 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/OrganizerHubPreferences.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR494 |

#### 8.6 homeedit-edit-undo（8 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoAvailability.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoExecutor.kt` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoPlanner.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoRecord.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoRecoveryText.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/HomeEditUndoVerifier.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/homeedit/ui/HomeEditUndoSnackbar.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `src/com/android/launcher3/folder/Folder.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR481 |

#### 8.7 organizer-home-entry（2 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/res/drawable/ic_organize_home.xml` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR486 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/LauncherPopupPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR486 |

#### 8.8 fork-platform-preexisting（9 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `build.gradle` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR481 |
| `gradle/libs.versions.toml` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR73 |
| `lawnchair/res/values/config.xml` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR467 |
| `lawnchair/src/app/lawnchair/DeviceProfileOverrides.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR169 |
| `lawnchair/src/app/lawnchair/backup/BackupPageSummaryReader.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR278 |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR278 |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupViewModel.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR278 |
| `lawnchair/src/app/lawnchair/bugreport/LawnchairBugReporter.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR261 |
| `settings.gradle` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR464 |

#### 8.9 new-app-destination（10 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/src/app/lawnchair/LawnchairProcessInitializer.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR498 |
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationAdapter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR498 |
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationNotice.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR498 |
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationPlanner.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR498 |
| `lawnchair/src/app/lawnchair/homeedit/AppDestinationSettingsText.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR498 |
| `lawnchair/src/app/lawnchair/homeedit/ui/AppDestinationPreference.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR498 |
| `lawnchair/src/app/lawnchair/preferences/PreferenceManager.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR498 |
| `src/com/android/launcher3/model/AddWorkspaceItemsTask.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR498 |
| `src/com/android/launcher3/model/ItemInstallQueue.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | PR498 |
| `src/com/android/launcher3/util/PersistedItemArray.java` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR498 |

#### 8.10 anchor-integration-repairs（62 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/src/app/lawnchair/HeadlessWidgetsManager.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S2c |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultIcon.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/dagger/ForkServiceModule.kt` | A | 新規（anchor追加fileへの変更 / 修復commitで生成） | S3 |
| `lawnchair/src/app/lawnchair/data/AppDatabase.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/data/folder/service/FolderService.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S2c |
| `lawnchair/src/app/lawnchair/data/iconoverride/IconOverrideRepository.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/gestures/ui/CreateActionsScreen.kt` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | wip |
| `lawnchair/src/app/lawnchair/homeedit/WorkspaceViewLookup.kt` | A | 新規（anchor追加fileへの変更 / 修復commitで生成） | fix |
| `lawnchair/src/app/lawnchair/icons/IconPackProvider.kt` | A | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/icons/LawnchairIconProvider.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/icons/ThemedIconDrawable.kt` | A | 新規（anchor追加fileへの変更 / 修復commitで生成） | S3 |
| `lawnchair/src/app/lawnchair/icons/shape/IconShapeManager.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/icons/shape/PathShapeDelegate.kt` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S3 |
| `lawnchair/src/app/lawnchair/nexuslauncher/OverlayCallbackImpl.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/nexuslauncher/SmartSpaceHostView.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/nexuslauncher/ThemedSmartSpaceHostView.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/ModelProjectionCodec.kt` | A | adapt（anchor構造へ再表現、旧mainとも相違） | PR191 |
| `lawnchair/src/app/lawnchair/overview/TaskOverlayFactoryImpl.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/preferences/PreferenceManagerModule.kt` | A | 新規（anchor追加fileへの変更 / 修復commitで生成） | S3 |
| `lawnchair/src/app/lawnchair/preferences2/IdpPreference.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/preferences2/ReloadHelper.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/qsb/QsbIconUtil.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/smartspace/DoubleShadowTextView.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/smartspace/model/SmartspaceScores.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/smartspace/model/SmartspaceTarget.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/theme/color/tokens/ColorTokens.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/theme/drawable/DrawableTokens.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/popup/LawnchairShortcut.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/popup/WallpaperCarouselView.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/PreferenceActivity.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/PreferenceViewModel.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/LauncherPreview.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/TwoTargetSwitchPreference.kt` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/PreferenceTemplate.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/PositionalReorderer.kt` | A | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/ReorderablePreference.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/ReorderablePreferenceDefaults.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SelectAppsForDrawerFolder.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SelectIconPreference.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/util/AppCategorizationUtils.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/util/AppsList.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/util/Compatibility.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S2c |
| `lawnchair/src/app/lawnchair/util/LawnchairUtils.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/util/MainThreadInitializedObject.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S1 |
| `lawnchair/src/app/lawnchair/util/PackageManagerExtensions.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/util/RecentHelper.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `lawnchair/src/app/lawnchair/views/LawnchairFloatingSurfaceView.kt` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `quickstep/dagger/com/android/launcher3/dagger/LauncherAppComponent.java` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S1 |
| `quickstep/src/com/android/quickstep/SystemUiProxy.java` | D | 削除 | fix |
| `quickstep/src/com/android/quickstep/logging/StatsLogCompatManager.java` | D | 削除 | fix |
| `res/values/attrs.xml` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `src/com/android/launcher3/DeviceProfile.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | wip |
| `src/com/android/launcher3/Utilities.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `src/com/android/launcher3/Workspace.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S2c |
| `src/com/android/launcher3/config/FeatureFlags.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `src/com/android/launcher3/folder/FolderIcon.java` | M | adapt（anchor構造へ再表現、旧mainとも相違） | S2c |
| `src/com/android/launcher3/graphics/IconShape.java` | A | adapt（anchor構造へ再表現、旧mainとも相違） | S3 |
| `src/com/android/launcher3/preview/PreviewContext.kt` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S1 |
| `src/com/android/launcher3/util/ContextTracker.java` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S2c |
| `src/com/android/launcher3/util/DaggerSingletonObject.java` | M | 新規（anchor追加fileへの変更 / 修復commitで生成） | S2c |
| `wmshell/multivalentTestsForDevice` | M | adapt（anchor構造へ再表現、旧mainとも相違） | fix |
| `wmshell/multivalentTestsForDeviceless` | M | adapt（anchor構造へ再表現、旧mainとも相違） | fix |

#### 8.11 replay-carried-fork-surface（165 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/res/drawable/ic_app_drawer.xml` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/res/drawable/search_input_fg.xml` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | S3 |
| `lawnchair/res/layout/search_container_all_apps.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | S3 |
| `lawnchair/res/layout/search_container_hotseat.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | S3 |
| `lawnchair/src/app/lawnchair/AccentColorExtractor.java` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/BlankActivity.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/NotificationManager.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/SearchBarStateHandler.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/AllAppsSearchInput.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/FallbackSearchInputView.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/LawnchairAlphabeticalAppsList.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/views/SearchContainerView.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/views/SearchItemBackground.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultIconRow.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultRightLeftIcon.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/allapps/views/SearchResultText.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/backup/ui/CreateBackupScreen.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/backup/ui/RestoreNovaBackupScreen.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/bugreport/UploaderService.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/data/folder/FolderEntity.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/data/folder/model/FolderViewModel.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/data/folder/service/FolderDao.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/data/iconoverride/IconOverride.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/data/wallpaper/model/WallpaperViewModel.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/data/wallpaper/service/WallpaperService.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/font/FontCache.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/font/FontManager.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/font/googlefonts/GoogleFontsListing.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/gestures/GestureController.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/gestures/VerticalSwipeTouchController.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/gestures/handlers/OpenAppDrawerGestureHandler.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/gestures/handlers/OpenAppSearchGestureHandler.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/gestures/handlers/OpenNotificationsHandler.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/CustomIconPack.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/IconEntry.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/IconEntryWithDrawable.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/IconPack.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/IconPickerCategory.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/IconPickerItem.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/SystemIconPack.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/icons/shape/IconShape.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/override/CustomizeDialog.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/preferences/BasePreferenceManager.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/preferences/PreferenceAdapter.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/preferences2/PreferenceUtils.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/qsb/AssistantIconView.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/qsb/LawnQsbLayout.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/qsb/providers/AppSearch.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/qsb/providers/QsbSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/qsb/providers/Startpage.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/LawnchairSearchAdapterProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/adapter/SearchAdapterItem.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/adapter/SearchTargetCompat.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/adapter/SearchTargetFactory.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/LawnchairAppSearchAlgorithm.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/LawnchairLocalSearchAlgorithm.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/LawnchairSearchAlgorithm.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/data/ContactInfo.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/SectionBuilder.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/ContactsSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/FileSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/SettingsSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/apps/AppSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/BuiltInWebSearchProviders.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/CustomWebSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/WebSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/search/algorithms/engine/provider/web/WebSuggestionProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/smartspace/IcuDateTextView.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/smartspace/PageIndicator.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/smartspace/SmartspaceViewContainer.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/smartspace/SmartspacerView.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/smartspace/model/SmartspaceCalendar.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/smartspace/provider/BatteryStatusProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/smartspace/provider/SmartspaceProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/theme/ThemeProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/theme/color/tokens/ColorStateListTokens.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/Preferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/About.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/AboutModels.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/AboutViewModel.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/ChangesDialog.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/ContributorRow.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/GithubService.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/LawnchairLink.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/NightlyBuildsRepository.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/about/UpdateSection.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/AnnouncementPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/AppItem.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/FontPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/GridOverridesPreview.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/NavigationActionPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/NotificationDotsPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/OverlayHandlerPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/PermissionDialog.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/QuickActionsPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/SuggestionsPreference.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/WallpaperPreview.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorContrastWarning.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorPreferenceModelList.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorSelectionPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/ColorSlider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/pickers/CustomColorPicker.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/pickers/PresetsList.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/colorpreference/pickers/SwatchGrid.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/ClickablePreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/ListPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/MainSwitchPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/PreferenceCategory.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/SliderPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/SwitchPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/TextPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/controls/WarningPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/ClickableIcon.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/DividerColumn.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/LazyColumnPreferenceGroup.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/LoadingScreen.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/PreferenceGroup.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/TopBar.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/layout/TwoTabPreferenceLayout.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/reorderable/ReorderHapticFeedback.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/DockSearchPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/DrawerSearchPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/FileSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/SearchProviderPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/components/search/WebSearchProvider.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/LiveInformationManager.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/LiveInformationRequest.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/LiveInformationService.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/data/liveinfo/SyncLiveInformation.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/AppDrawerFoldersPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/AppDrawerPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/CustomIconShapePreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/DockPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/FeatureFlagsPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/FolderPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/FontSelectionPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/GeneralPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/GesturePreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HiddenAppsPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenGridPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/IconPackPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/IconPickerPreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/IconShapePreference.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/PickAppForGesture.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/QuickstepPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SearchPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SearchProviderPreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/SmartspacePreferences.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/theme/Shape.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/theme/Theme.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/theme/Type.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/util/LazyGridLayout.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/util/NavigationResult.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/util/ProvideBottomSheetHandler.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/ui/util/preview/PreferenceGroupPreviewContainer.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/util/DrawableUtils.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/util/FileAccessManager.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/util/FlowUtils.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/util/ImageViewWrapper.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/views/FullScreenOverlayView.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/app/lawnchair/wallpaper/WallpaperManagerCompat.kt` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `lawnchair/src/com/google/android/libraries/launcherclient/LauncherClient.java` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | wip |
| `quickstep/res/drawable/bg_overview_clear_all_button.xml` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | S3 |
| `res/xml/folder_shapes.xml` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | S3 |

#### 8.12 replay-carried-localization（57 path）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/res/values-af-rZA/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-am-rET/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ar-rSA/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-az-rAZ/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-b+sr+Latn/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-bn-rBD/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-bs-rBA/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ca-rES/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-cs-rCZ/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-da-rDK/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-de-rDE/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-el-rGR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-en-rCA/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-es-rES/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-et-rEE/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-fa-rIR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-fi-rFI/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-fil-rPH/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-fr-rFR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-gl-rES/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-hi-rIN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-hr-rHR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-hu-rHU/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-in-rID/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-it-rIT/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-iw-rIL/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ja-rJP/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ka-rGE/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-kmr-rTR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ko-rKR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-lt-rLT/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ml-rIN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-mr-rIN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-nl-rNL/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-no-rNO/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-pl-rPL/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-pt-rBR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-pt-rPT/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ro-rRO/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ru-rRU/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-sk-rSK/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-sl-rSI/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-sq-rAL/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-sr/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-sv-rSE/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-sw-rKE/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ta-rIN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-te-rIN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-th-rTH/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-tr-rTR/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-uk-rUA/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ur-rIN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-ur-rPK/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-uz-rUZ/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-vi-rVN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-zh-rCN/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/res/values-zh-rTW/strings.xml` | M | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |

#### 8.13 explicit exclusion: Inter TTF 4件（binary・exact-path exclusion）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/res/font/inter_bold.ttf` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み）（script仕様によりbinaryはcounted不可→exact-path exclusion。owner付帯は本行で記録） | S3 |
| `lawnchair/res/font/inter_medium.ttf` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み）（script仕様によりbinaryはcounted不可→exact-path exclusion。owner付帯は本行で記録） | S3 |
| `lawnchair/res/font/inter_regular.ttf` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み）（script仕様によりbinaryはcounted不可→exact-path exclusion。owner付帯は本行で記録） | S3 |
| `lawnchair/res/font/inter_semi_bold.ttf` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み）（script仕様によりbinaryはcounted不可→exact-path exclusion。owner付帯は本行で記録） | S3 |

#### 8.14 project-owned addition（178 path。`organizer/` `migration/` prefixの新規file。counted計上外）

| path | status | 内容帰属 | 由来単位 |
|---|---|---|---|
| `lawnchair/src/app/lawnchair/migration/DeckRetirementMigration.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR79 |
| `lawnchair/src/app/lawnchair/organizer/PreferenceWorkspaceOverlapToleranceSource.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR186 |
| `lawnchair/src/app/lawnchair/organizer/application/actions/ActionMaterializer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR47 |
| `lawnchair/src/app/lawnchair/organizer/application/actions/IntendedStateResolution.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR165 |
| `lawnchair/src/app/lawnchair/organizer/application/actions/OrganizationPlanMaterializer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR289 |
| `lawnchair/src/app/lawnchair/organizer/application/actions/RecoveryWriteSet.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR158 |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/CanonicalProfileId.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | direct |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/ContextResourceCodec.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR158 |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR314 |
| `lawnchair/src/app/lawnchair/organizer/application/canonical/CanonicalItemOrder.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR165 |
| `lawnchair/src/app/lawnchair/organizer/application/canonical/CanonicalMarshalling.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR158 |
| `lawnchair/src/app/lawnchair/organizer/application/canonical/Digest.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR47 |
| `lawnchair/src/app/lawnchair/organizer/application/canonical/PersistenceManifest.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR158 |
| `lawnchair/src/app/lawnchair/organizer/application/lifecycle/LifecycleState.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR409 |
| `lawnchair/src/app/lawnchair/organizer/application/lifecycle/OrganizerDurableStatusDeriver.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR276 |
| `lawnchair/src/app/lawnchair/organizer/application/lifecycle/RestorableRecoveryPointSelector.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR403 |
| `lawnchair/src/app/lawnchair/organizer/application/lifecycle/RetentionPolicy.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR183 |
| `lawnchair/src/app/lawnchair/organizer/application/preview/PlanDiagramProjector.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/application/preview/PlanPreviewProjector.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR481 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/CandidateResolution.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR289 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/CaptureInvariant.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR314 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/MaterializedStateValidator.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR165 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/ModelProjection.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR180 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/PlanPreviewProtocol.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/Ports.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR409 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/ReadinessGate.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR276 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/ReconciliationDecisionTable.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR410 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/ReconciliationPublicResult.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR410 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocol.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR410 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RecoveryProtocol.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR410 |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/RestartReconciler.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR410 |
| `lawnchair/src/app/lawnchair/organizer/application/public/FolderTitleResolver.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/application/public/LayoutState.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR158 |
| `lawnchair/src/app/lawnchair/organizer/application/public/OrganizerDurableStatus.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR276 |
| `lawnchair/src/app/lawnchair/organizer/application/public/PlanPreview.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/application/public/RecoveryPreview.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR273 |
| `lawnchair/src/app/lawnchair/organizer/application/public/RecoveryRequest.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR47 |
| `lawnchair/src/app/lawnchair/organizer/application/public/RestorableRecoveryEntry.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR403 |
| `lawnchair/src/app/lawnchair/organizer/application/public/Results.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR289 |
| `lawnchair/src/app/lawnchair/organizer/application/public/ValidatedLayoutPlan.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR47 |
| `lawnchair/src/app/lawnchair/organizer/application/revision/RevisionCalculator.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR47 |
| `lawnchair/src/app/lawnchair/organizer/application/store/InspectionSnapshotFence.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR90 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbHelper.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR193 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbSchema.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR175 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryDbVersionGate.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR175 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshot.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR90 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshotCodec.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR175 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshotPublisher.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR90 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshotReader.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR90 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryManifestChunks.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR175 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryRecordCodec.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR410 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStartupArtifacts.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR188 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStartupStorageClassifier.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR90 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR409 |
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStoreFaultPort.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR47 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/DiagnosticsPort.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/export/DiagnosticsExportFilename.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR290 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/export/ExportUi.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR290 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/export/ExportWriter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR290 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/journal/JournalSequence.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/journal/JournalStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR107 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/journal/RetentionPolicy.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/journal/RunEventSerializer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/logger/DiagnosticsLogger.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR314 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/ApplyStage.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/ApplySummary.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/CorrelationId.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/ErrorEntry.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR184 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/PhaseCode.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR184 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/PlanSummary.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/RunEvent.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR412 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/model/Trigger.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/projection/ApplyProjection.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR183 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/projection/InputReadinessProjection.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR184 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/projection/PlanningProjection.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR289 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/projection/ReconciliationProjection.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/diagnostics/projection/RecoveryProjection.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR82 |
| `lawnchair/src/app/lawnchair/organizer/integration/AndroidCandidatePorts.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR289 |
| `lawnchair/src/app/lawnchair/organizer/integration/AndroidClassificationSignalSnapshotSource.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR88 |
| `lawnchair/src/app/lawnchair/organizer/integration/AndroidExportSessionStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR432 |
| `lawnchair/src/app/lawnchair/organizer/integration/AndroidPendingImportedIntentStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/integration/AndroidPersonalizationSignalSnapshotSource.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/integration/AndroidSystemUsageSignalReader.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/integration/CompositionModels.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/integration/FullTargetSetMaterializer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR186 |
| `lawnchair/src/app/lawnchair/organizer/integration/LauncherOriginLaunchRecorder.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/integration/LauncherOriginSignalReader.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/integration/MissingAppCandidateSource.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR289 |
| `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/integration/ProductionOrganizationInputComposer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/integration/UsageAccess.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ClipboardImportTransport.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR344 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowController.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR432 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeFlowModule.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeInputAdapter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeSessionStoreModule.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR325 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/ExchangeTransports.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR325 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/PendingImportStartupReconcile.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/integration/exchange/PendingImportedIntentModule.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/locks/EffectiveLocks.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR264 |
| `lawnchair/src/app/lawnchair/organizer/locks/LockAuthoring.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR306 |
| `lawnchair/src/app/lawnchair/organizer/locks/LockAuthoringModule.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR73 |
| `lawnchair/src/app/lawnchair/organizer/locks/LockPorts.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR73 |
| `lawnchair/src/app/lawnchair/organizer/locks/LockReview.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR73 |
| `lawnchair/src/app/lawnchair/organizer/locks/OrganizerLocks.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR73 |
| `lawnchair/src/app/lawnchair/organizer/locks/adapter/LockStateDbAdapter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR73 |
| `lawnchair/src/app/lawnchair/organizer/personalization/CandidateScopeIdentity.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/personalization/ContextExportBuilder.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/ContextExportCodec.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/ContextExportModels.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR432 |
| `lawnchair/src/app/lawnchair/organizer/personalization/ExportSessionStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR432 |
| `lawnchair/src/app/lawnchair/organizer/personalization/IntentCodec.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/IntentCompletion.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR335 |
| `lawnchair/src/app/lawnchair/organizer/personalization/IntentIdentity.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/IntentModels.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR399 |
| `lawnchair/src/app/lawnchair/organizer/personalization/IntentPlannerAdapter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/IntentValidator.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/IntentWireContract.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/PendingImportedIntentStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/personalization/PersonalizationBuckets.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/personalization/PersonalizationSignalModels.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/personalization/PersonalizationSignalSnapshot.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/personalization/PersonalizationSignalSnapshotSource.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/personalization/RandomIdAllocator.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR322 |
| `lawnchair/src/app/lawnchair/organizer/personalization/SourceContextIdentity.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/SystemUsageAggregator.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR321 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeContract.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR325 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeGenerationGate.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR325 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeImportPipeline.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeImportSummary.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangeMutationGate.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ExchangePackageComposer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ImportNormalizer.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR339 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/IntentImportParser.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR325 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/PendingIntentReconcile.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/RebindIntentRebuilder.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/ScopeBindingGate.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR405 |
| `lawnchair/src/app/lawnchair/organizer/personalization/exchange/SessionExportReconstructor.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/planning/CandidatePlanningIds.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR289 |
| `lawnchair/src/app/lawnchair/organizer/planning/CategoryIdentity.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/planning/DeterministicOrganizationPlanner.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/planning/DuplicateLaunchTargets.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR513 |
| `lawnchair/src/app/lawnchair/organizer/planning/FolderFormation.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR355 |
| `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR485 |
| `lawnchair/src/app/lawnchair/organizer/planning/Identity.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR207 |
| `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR412 |
| `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR29 |
| `lawnchair/src/app/lawnchair/organizer/planning/PlacementAllocator.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR412 |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningClassification.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR485 |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningResultCanonicalization.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR322 |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningValidation.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/planning/ProposalExclusionDerivation.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/planning/ProposalExclusionKey.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/planning/ReservationOverlapAcceptance.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR186 |
| `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR485 |
| `lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideSnapshot.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/rules/LayoutStrategySelectionStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR207 |
| `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR485 |
| `lawnchair/src/app/lawnchair/organizer/rules/UserDefinedCategoryStore.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/ui/CategoryOverrideAuthoring.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/ui/CategoryOverridePresentation.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR202 |
| `lawnchair/src/app/lawnchair/organizer/ui/LockMessages.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR73 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationFace.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRunStateBus.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR504 |
| `lawnchair/src/app/lawnchair/organizer/ui/MissingAppSelectionScreen.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR432 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOnboardingProposal.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR389 |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationOperationLease.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | direct |
| `lawnchair/src/app/lawnchair/organizer/ui/OrganizationPreviewContent.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR515 |
| `lawnchair/src/app/lawnchair/organizer/ui/RunPublicationThread.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR504 |
| `lawnchair/src/app/lawnchair/organizer/ui/StrategyWriteArbiter.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR384 |
| `lawnchair/src/app/lawnchair/organizer/ui/UsageAccessJitRequest.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR504 |
| `lawnchair/src/app/lawnchair/organizer/ui/UserDefinedCategoryAuthoring.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR341 |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR432 |
| `lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeImportFailureDisplay.kt` | A | fork内容持越し（HEAD blob == 旧main blob 検証済み） | PR396 |


## 9. 懸念（Phase 4正式再採択に向けた注意）

1. **16-dev進化のrevert状態（最大のreview対象）**: `replay-carried-fork-surface`（165+4）と `replay-carried-localization`（57）は、HEAD内容が旧main（v15ベース+fork）と完全一致であることを根拠に分類した。anchor 16-devがこれらのfileに進めた進化（検索、smartspace、icons、preferences UI、翻訳更新 等）は **持越しによりrevertされている**。compile整合はG1で確認済みだが、16-dev新機能の文字列欠損（localized削除 −11,555行を含む）や上流修正の取り込み要否は、Phase 4でfile単位の再適用判断（keep維持 / 16-dev側再merge）として明示的にreviewすること。[wip-adoption-table.md](./wip-adoption-table.md)のS0初版ラベルを入力に使える。
2. **消失3件の妥当性再確認**: `LauncherPrefs.kt`（deck prefs key編集が不要になった根拠）、`InvariantDeviceProfile.java`（Nova restore authoritative契約の代替実装が `NovaBackupConverter.kt` 側で契約を満たすこと。T8 oracleで担保）、`src MainThreadInitializedObject.java`（onPostInit代替hookがIssue #14要件・specのstartup契約を満たすこと）は、Phase 4再採択前に各契約ownerの確認を1回ずつ要求する。
3. **pin再採択方針**: 本candidateはpin 0件。Phase 4で (a) counted維持か (b) `--refresh-pins` によるpin復活かを、57件の翻訳state扱い（16-dev Crowdin更新のre-merge要否）と一体で決める。pin復活時は本doc §5–6のreview記録を入力にする。
4. **project-owned prefixの再確認**: `organizer/` `migration/` prefixの新規178件をproject-owned additionとして計上した（旧179とほぼ同水準）。prefix自体は旧baselineから変更していない。`ModelProjectionCodec.kt` 等のS1〜S3新設fileがprefix配下に入るため、Phase 4で「project-ownedとして足切りするpathの妥当性」（特に `organizer/application/adapter/` 配下のanchor接続code）を再確認する。
5. **candidate JSONの位置**: 本candidateは `specs/516-16-rebase-phase2/ownership-inventory-candidate.json` に同梱した。Phase 4の正式再採択では、最終headを `--upstream/--target` に与えて再計測し、`docs/assessment/upstream-patch-surface-baseline.json` を正式後継として更新する（本candidateの自動採択はしない）。
6. **G3再実行条件**: 以後にproduction sourceへ変更（Phase 3/4の修復・re-merge）が入った場合、本candidateでのPASSは無効になる。その際は本docの計測数値を付け替え、増分のowner割当てを追加する。

## 10. 未分類path

**なし。** scriptのfail-closed分類（bridge group割当て / explicit exclusion / project-owned addition以外はMeasurementError）が全pathについて通過した。570 production path = counted 388 + project-owned 178、+ Inter TTF 4件をexact-pathのbinary exclusion（script仕様: binaryはcountedにできず、exclusionのみ可能。owner付帯情報は§5に記録）として処理し、合計570件で整合。

---

