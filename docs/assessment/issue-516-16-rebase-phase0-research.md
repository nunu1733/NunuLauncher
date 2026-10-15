# Lawnchair 16 rebase Phase 0 — 候補baseline固定・差分分類・patch-surface計測（Issue #516 / #519）

> Status: accepted（2026-10-04。PR #523 review round 3でblocking findingなしを確認（[review](https://github.com/nunu1733/NunuLauncher/pull/523#issuecomment-5975761227)）。[ADR-0018](../adr/0018-lawnchair-16-rebase.md)のaccepted遷移とともに受入。受入は本PR #523のmergeで完了する）
> Research date: 2026-10-04（revision 2: 同日のreview round 1指摘対応。Change history参照）
> Agent session: ZCode（GLM-5.3-flash）
> Issues: Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516)、Phase 0子Issue [#519](https://github.com/nunu1733/NunuLauncher/issues/519)
> 対象revision: fork main `0b4db97a9aa8853fba9824ae148aadb4ec42e32b`、upstream baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`、候補upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`

## 1. 問いと方法

Epic #516 Phase 0の問いは、「Lawnchair 16系（`16-dev`）のどのcommitをrebase候補として固定し、現baseline（`505dbc40`）からの差分とfork patch surfaceをどう管理するか」である。本書は観測と分類のみを行い、採用判断は[ADR-0018](../adr/0018-lawnchair-16-rebase.md)に委ねる。

解析は取得済みのlocal Git object databaseとrepository内toolingのみによる（worktree変更なし）。候補commitの取得・固定確認のみnetwork操作（`git fetch` / `git ls-remote`、読み取りのみ）を行った:

- `git fetch upstream 16-dev` による候補commitの取得
- `git diff --no-renames --numstat 505dbc40..43a21b43` による差分分類（区分規則は§3）
- `python3 tools/repo-contract/measure_upstream_patch_surface.py --verify` による受入済みbaselineの再現
- [upstream-patch-surface-baseline.json](./upstream-patch-surface-baseline.json) のbridge path × 16-dev上の状態照合によるdisposition分析（§5）

## 2. 候補upstream commitの固定

確認日: 2026-10-04。方法: `git ls-remote upstream refs/heads/16-dev`（読み取りのみ）とlocal `git fetch`。

- `16-dev` head: `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（2026-10-02T06:12:03+05:00 commit "fix: keyboard closing when clearing app search (#7353)"）。Epic #516起票時（2026-10-04）の観測値と同一である。**本SHAは観測値であり、採用はADR-0018のacceptance時点で確定する。**
- `git fetch upstream 16-dev` でlocal object databaseへ取得済み（`git cat-file -e 43a21b43^{commit}` 成功）。
- baseline `505dbc40` とのmerge-base: `b011d84ca9ce3e381e3e7601116e99a9ac170ec3`。**両者はdivergeしている**: merge-base以後、16-dev側7,377 commits、baseline（15系）側33 commits。
- `v16*` tagは存在しない（`git ls-remote upstream 'refs/tags/v16*'` は空。2026-10-04確認）。
- 16-devの統合履歴はmerge主体（merge-base以後のmerge commit 3,236件。`Merge tag 'android-16.0.0_r3'`、25Q3 cherrypick等のAOSP統合と `Merge remote-tracking branch 'origin/15-dev' into 16-dev` を含む）。

## 3. 差分分類（7区分）

対象区間: `505dbc40..43a21b43`（baseline→16-dev候補の全upstream差分。rebase時にforkが吸収する範囲と一致する）。

全規模: `--no-renames` で **5,166 files, +532,729 / −185,654**（rename検知ありでは 4,999 files, +510,077 / −163,002）。数値はrename検知なしの`--numstat`基準である。

| 区分 | files | 追加 / 削除 | 代表的な内容 |
|---|---:|---:|---|
| 1. Launcher3 model / schema / event | 57 | +3,864 / −4,045 | `src/com/android/launcher3/model/`、`LauncherModel.java`→`LauncherModel.kt`（Kotlin化）、`LauncherProvider.java`、`LauncherSettings.java`、`protos/` |
| 2. DB migration / downgrade / backup / restore | 33 | +4,192 / −3,107 | `DatabaseHelper`、`RestoreDbTask`、`LauncherBackupAgent.java`、grid migration系、`lawnchair/src/app/lawnchair/backup/`、**`SCHEMA_VERSION` は32のまま不変** |
| 3. Quickstep / SystemUI compat | 2,904 | +320,337 / −70,655 | `quickstep/`、`systemUI/`、`compatLib/`（`compatLibVBaklava`=API 36新設）、**vendored `wmshell/` 1,639 files +198,101行**、`go/` |
| 4. Workspace / drag / folder / widget / hotseat | 85 | +5,895 / −2,662 | `dragndrop/`、`folder/`、`widget/`、`hotseat/`、`Workspace.java`、`CellLayout.java` 等 |
| 5. build / Gradle / SDK / module構成 | 620 | +60,627 / −20,391 | AGP 9.0.1→9.4.1、Kotlin 2.3.0→2.4.20、新module（`flags`、`wmshell`、`dagger`、`concurrent`、`modules:widgetpicker`、`baseline-profile`、`androidx-lib`、`checks`、composeはinclude済みだが`//include ':compose'`と無効化）、新submodule `platform_frameworks_libs_systemui`（branch 16-dev）、`src_no_quickstep/` variant分割、`tests/` |
| 6. permission / manifest / targetSdk | 37 | +966 / −92 | `AndroidManifest*.xml` 37件。build.gradleのtargetSdk 35→37は区分5に計上 |
| 7. Nunu固有bridge / fork-owned module | （§5で分類） | — | 本区分はfork側のinventoryであり、§4/§5で扱う |
| （補助）その他Launcher3 product code | 287 | +21,660 / −13,861 | `allapps/`、`search/` 等の区分4名目外のLauncher3 src |
| （補助）upstream Lawnchair app code | 280 | +14,621 / −5,276 | `lawnchair/src/app/lawnchair/` のupstream側変更 |
| （補助）upstream resources / metadata / docs | 858 | +19,645 / −7,852 | `fastlane/` 398件、`res/`、`lawnchair/res/`、README等 |
| （補助）生成物 | 5 | +80,922 / −57,713 | `baseline-prof.txt` 生成物2件、`google_fonts.json` 等 |

分類規則（regex、first match優先）の要点: migration/backup/manifest語を含むpathは区分1より区分2/6を優先、`wmshell/`は内容がSystemUI/WindowManager Shell（recents/quickstep compat基盤）であるため区分3へ計上（新moduleという構成事実は区分5にも記載）、区分7はfork側のみを対象とする。生成物5件は補助区分へ手動帰属した。

## 4. patch-surface計測

実行コマンドと結果（2026-10-04）:

```bash
python3 tools/repo-contract/measure_upstream_patch_surface.py --verify
# -> Counted upstream/bridge surface: 105 file(s), +23314 / -1098 lines
#    PASS: measurement completed with complete bridge ownership.
#    （受入済みbaselineのanchor: upstream 505dbc40 / main 8af117b6fc7ce074f9ac32df416e74d697953e8d。
#     8af117b6fc はIssue #508 recapture headであり、現main 0b4db97a9a のancestorである）

python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --upstream 505dbc40e6154c05158b5d0271c45f6a885a411b --target HEAD --enforce-baseline
# -> Counted upstream/bridge surface: 105 file(s), +23314 / -1098 lines（delta 0）
#    PASS（現HEADでも受入済みinventoryを正確に再現。anchor後のmain側差分はdocs/spec-only
#    （#507 device evidence、#508 closing docs、#509 specを含むmerge群）で計測対象surfaceの差分なし）

python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --upstream 43a21b43d7cc7850ab54e14b1a57dc9646685f35 --target HEAD --enforce-baseline
# -> FAIL: the upstream commit must be an ancestor of the target; ...（期待どおりの拒否）

python3 tools/repo-contract/test_measure_upstream_patch_surface.py
# -> Ran 22 tests ... OK
```

- `--verify` のPASSにより、**現行bridge inventory（105 path、9 group、anchor main `8af117b6fc`）が再適用候補の全リストとして確定した**。anchorと現main `0b4db97a9a` の間に計測差分はない（上記の通りdelta 0）。これがPhase 2での disposition 対象集合である。なお [upstream-patch-surface-baseline.md](./upstream-patch-surface-baseline.md) 冒頭の「47 counted files, +3,993/−1,017」は2026-08-23の初回capture値であり、#449〜#508のrecaptureで更新された現行の `expected_measurement`（105 files, +23,314/−1,098）とは異なる。Phase 2以後の比較はJSONの現行値を使う。
- `measure_upstream_patch_surface.py` は「upstreamがtargetのancestor」を前提とする。16-dev候補は現mainのancestorではないため、**正式なsurface再計測（`--upstream <採用SHA> --target <rebase後head> --enforce-baseline`）はrebase完了後（Phase 2）に可能になる**。rebase前の本Phaseでは、§5のbridge path × 16-dev状態照合が代替の競合面記録である。
- 受入済みbaselineの数値（47 files +3,993/−1,017）を「新baselineとの比較」へ流用してはならない（Epic Phase 4の規定どおり、新upstream ancestor基準での再採択が必要）。

## 5. bridge disposition分析（区分7: Nunu固有bridge / fork-owned）

[upstream-patch-surface-baseline.json](./upstream-patch-surface-baseline.json) のbridge group全105 path（anchor main `8af117b6fc`、§4のとおり現HEADでも同一）を、16-dev候補上の状態と照合した。方法: pathごとに (a) baselineに存在したか、(b) 16-devに存在するか、(c) `git diff --no-renames --numstat 505dbc40..43a21b43 -- <path>`（upstream側変更量）、(d) `git diff --no-renames --numstat 505dbc40..HEAD -- <path>`（fork側変更量）を記録。

**内訳: 105 = fork追加file 49 + upstream無変更（再適用が自明）8 + upstream変更あり（競合面）45 + upstreamで削除（移動/分割の追従が必要）3。**

下表のdispositionの定義: **keep** = patch競合なしで再適用可能（依存先upstream APIの変化に対するcompile追従はrebase作業の範囲）/**adapt** = upstream側の変更・構造変化により再表現が必要/**drop** = 16-dev側が目的を代替しており再適用不要。group内で混在する場合はpath単位で区切る。

| bridge group | paths | fork追加 | up無変更 | up変更 | up削除 | disposition（path単位の内訳） |
|---|---:|---:|---:|---:|---:|---|
| deck-retirement | 20 | 0 | 1 | 19 | 0 | **keep 1**（`LawnchairBackup.kt` 無変更）/ **adapt 19**（`PreferencesDashboard.kt` up +131/−164、`PreferenceManager2.kt` up +228/−51、`LauncherPrefs.kt` up +121/−106、`PackageUpdatedTask.java` up +111/−100 等を含む。deck runtimeは16-devに存続し、退役契約（ADR-0006）の再適用が必要） |
| organizer-ui-and-lock-authoring | 13 | 8 | 1 | 4 | 0 | **keep 9**（fork追加8 + `lawnchair/res/values-ja/strings.xml` 無変更）/ **adapt 4**（`lawnchair/res/values/strings.xml` up +215/−66 × fork +801のorganizer strings再統合、`res/values/strings.xml` up +74/−8、`res/values-ja/strings.xml` up +35/−4、`BaseLauncherBinder.java` up −440の大規模改変へのfork +19再表現） |
| model-reload-and-transaction-gates | 11 | 3 | 1 | 6 | 1 | **keep 4**（fork追加3: `OrganizerModelReloadAdapter.java`・`LayoutWriteCoordinator.java`・`DirectEditContract.java` は競合なし再追加 + `HotseatRestoreHelper.java` 無変更。ただし接続先の再表現はadapt側で実施）/ **adapt 7**（`LauncherModel.java`→`LauncherModel.kt` 化への接続再表現、`ModelWriter` up +147/−63 × fork +923、`ModelDbController` up +235/−193 × fork +753、`LoaderTask` up +403/−418、`LauncherProvider` up +141/−53、`MainThreadInitializedObject` up −128（onPostInit削除）、`InvariantDeviceProfile` up +1,677/−1,288） |
| layout-schema-and-recovery | 11 | 4 | 2 | 3 | 2 | **keep 6**（fork追加4: `FavoritesTableDigest.java`・`GridMigrationJournal.java`・`GridMigrationOperation.java`・`GridMigrationRuntime.java` + 無変更2: `downgrade_schema.json`・`DbDowngradeHelper.java`。分割後構造への接続はadapt側で実施）/ **adapt 5**（`GridSizeMigrationUtil.java`→`GridSizeMigrationDBController.java` + `GridSizeMigrationLogic.kt` 分割への追従、`LauncherDbUtils.java`→`.kt` 化、`LauncherSettings.java` up +66/−100、`DatabaseHelper.java` up +48/−59、`RestoreDbTask.java` up +116/−111） |
| homeedit-edit-surface | 22 | 20 | 0 | 2 | 0 | **keep 20**（fork追加のみ）/ **adapt 2**（`LauncherOptionsPopup.kt` up +61/−14 × fork +96、`lawnchair/AndroidManifest.xml` up +45/−0 × fork +9） |
| homeedit-edit-undo | 8 | 7 | 0 | 1 | 0 | **keep 7**（fork追加のみ）/ **adapt 1**（`Folder.java` up +426/−161 へOPTIONS bit guard（fork +26/−3）を再適用） |
| organizer-home-entry | 2 | 1 | 0 | 1 | 0 | **keep 1**（`ic_organize_home.xml`）/ **adapt 1**（`LauncherPopupPreference.kt` up +1/−4 × fork +1） |
| fork-platform-preexisting | 8 | 1 | 2 | 5 | 0 | **keep 3**（fork追加1: `BackupPageSummaryReader.kt` + 無変更2: `RestoreBackupViewModel.kt`・`LawnchairBugReporter.kt`）/ **adapt 5**（`build.gradle` up +111/−64 × fork +53、`settings.gradle` up +28/−5（module再編）× fork +4、`config.xml` up +19/−3、`DeviceProfileOverrides.kt` up +98/−19 × fork +75、`RestoreBackupScreen.kt` up +2/−0 × fork +67） |
| new-app-destination | 10 | 5 | 1 | 4 | 0 | **keep 6**（fork追加5 + `PersistedItemArray.java` 無変更）/ **adapt 4**（`AddWorkspaceItemsTask` up +25/−43 × fork +64、`ItemInstallQueue` up +45/−23 × fork +81、`PreferenceManager.kt` up +135/−20、`LawnchairProcessInitializer.kt` up +2/−3） |

**合計: keep 57 / adapt 48 / drop 0。** fork追加・upstream無変更pathをkeepとする根拠は「patch自体に競合がない」ことである。ただし model-reload のfork追加3 pathと layout-schema のfork追加4 pathは、接続先のupstream構造変化（`LauncherModel.kt` 化、grid migration分割）をadapt側pathで再表現する前提のkeepであり、接続の再表現はadapt側のPhase 2作業に含まれる。

**drop該当なし（Phase 0としての結論）**: §5.1の33 commit棚卸しと上表の照合で、16-devがfork patchの目的を代替したpathは確認されなかった（deck退役・direct-edit write契約・destination policy・grid migration journal等の代替実装は存在しない）。Phase 2のconflict解消で新事実（upstream代替の発見）が生じた場合は、本assessmentとADR-0018の改訂によりdispositionを変更する（黙示的な変更はしない）。

### 5.1 baseline側のみに存在する33 commitsの棚卸し

merge-base `b011d84c` から baseline `505dbc40` 側だけに存在する33 commits（16-devはancestorでない）について、**不運搬による挙動損失の有無**をcommit単位で棚卸しした。方法: `git log --reverse ${16-dev}..${505dbc40}` で列挙し、各commitの変更内容から識別tokenを抽出して16-dev上の同等実装を `git grep`（対象commit固定）で照合した。submoduleの判断はGitHub compare API（`repos/LawnchairLauncher/platform_frameworks_libs_systemui/compare/...`。確認日 2026-10-04）による。

| 分類 | commits | 判断と証拠 |
|---|---|---|
| A. build / CI / version / docs / 翻訳 / fonts metadata（15件） | `eeb66fb129`（baseline-profile cfg）、`90c2b35333`（AGP 9.0.1）、`cf23430a6c`+`310cd77a1f`（Google Fonts json）、`857b8c71e2`（16-kb page size）、`fc1104a9a6`（buildTools 36.1.0）、`f05391baae`+`640b4b8321`（上流CI workflow）、`cd8add7a5f`+`70be89746a`（version bump）、`ddfe62c05d`+`c7a23a529a`（Crowdin翻訳）、`972a825132`（version表示名）、`ae80519238`+`4dff675620`（changelog） | **不運搬で損失なし**。upstream release engineering差分であり、16-devは AGP 9.4.1 / buildTools 37.0.0 等の現行版を持つ。上流CI workflowはfork対象外 |
| B. 16-devに同等実装を確認（15件） | `582d9fc660`（web suggestions timeout→16-dev `WebSuggestionProvider.kt` に同実装）、`0b32a513cc`（BAL hardening→`StartActivityParams.java` の `allowBGLaunch`）、`ec71d5990c`（GestureNavContract toggle→strings/Compatibility確認）、`8d04c149b6`（PagedView feed無限scroll→`mCachedEnableFeed` による進化実装）、`54835b3315`（BGLaunch at-least→`Utilities.java`）、`46abccbe38`（preview省メモリ→`MAX_PREVIEW_SIZE_PX`）、`892cdc9ad3`（exif orientation→`exifinterface` 依存）、`e67ccde64c`（FGS dataSync→manifest）、`1ba03af948`（bugreport ClipData→`BugReport.kt` `newRawUri`）、`c65f34ab28`（URI flags削除→`SearchTargetFactory.kt` 進化後）、`787ddd0e57`（workflow artifact名→`release_update.yml`）、`f973986d21`（Nova backup import→strings/`NovaBackupConverter.kt`）、`5a152708cc`（Nova row/column mapping→同 converter）、`00a58d0203`（Nova smart folders→`FOLDER%3A-` marker）、`505dbc40e6`（app drawer fonts→`FontCache.kt` `preloadFont`） | **不運搬で損失なし**（16-devで同等以上の実装に置換済み） |
| C. 16-devに同等なし（契約確定をrebase前に）（3件） | `9b48473c`（Nova復元のsubgrid対応。**挙動は2単位**: (i) subgrid検出と警告UI、(ii) `cellX/cellY/spanX/spanY` の `.toInt()` 切り捨て→`roundToInt()` 最近傍丸めへのrestore座標変換。16-dev `NovaBackupConverter.kt:358-362` は依然 `.toInt()` であることを確認）、`53a2092541`（Nova復元のsmartspace対応。**挙動は2単位**: (i) smartspace conflict toggle UI、(ii) smartspace有効時の `rows +1` grid補償・desktop itemの `cellY +1` shift・span/grid境界のclamp・範囲外item skip。16-devにはsmartspace処理がなく `rows = info.rows` の補償なしであることを確認）、`aae5fbbc96`（icon shadow preference修正。submodule bump `6a11ef76`） | **現baselineが持つ挙動で16-devが欠落している。**(1)(2)の採否・保持条件は **#522（Phase 1）の成果物としてrebase着手前に確定し、Phase 2は確定結果を実装するのみ**とする（UI単体だけportしてrestoreデータ変換契約を落とすことを防ぐため、挙動単位で記録）。(3)はbackup/restore契約と別の問題であり、Phase 2のplanで「16-dev pin `7d9e92bd` へ追従しicon shadow修正を再適用する」か「修正済みpin `6a11ef76` を維持する」かを決める（現mainは `6a11ef76` をpin。16-dev pinはcompare APIでbehind_by 1、欠落1 commitが当該修正であることを確認） |

結論: 33件のうち **30件は不運搬で挙動損失なし**、**3件（Group C）はrebase着手前〜Phase 2でのport/pin判断対象**として引き継ぐ（2件のNova restore契約は#522のPhase 1成果物で確定、icon shadow pinはPhase 2 plan判断）。ADR-0018 Decision 2はこの結論に基づく。

### 要対応の3点（upstream側構造変化によりfork patchの再表現が必要）

1. **`LauncherModel.java` → `LauncherModel.kt`**（Kotlin化、内容継続）。model-reload groupのfork bridge（fork +322/−3。`OrganizerModelReloadAdapter` 接続等）はKotlin版への再適用になる。
2. **`GridSizeMigrationUtil.java` の分割**（`GridSizeMigrationDBController.java` + `GridSizeMigrationLogic.kt`）。layout-schema groupのgrid migration契約（`GridMigrationJournal` / `GridMigrationOperation` / `GridMigrationRuntime`、fork追加file）の接続先を分割後の構造へ合わせる。
3. **`MainThreadInitializedObject` の `Overrides`/`onPostInit` 機構の上流削除**（up −128。Lawnchair由来のmodification除去）。forkの3行patch（Issue #14: 初期化値publish後の `onPostInit` 呼び出し順序）は、16-dev上に `onPostInit` が存在しなくなるためhookを再表現する必要がある。`ResourceBasedOverride` 自体は16-devに残存している。

## 6. 主要な発見

1. **upstream同士のDB schemaは不変**: `SCHEMA_VERSION` 32は旧upstream baselineと候補で同一、`res/raw/downgrade_schema.json` 無変更（`git diff` で確認）。現行forkはADR-0004のlock列を持つschema33であり、この32不変をfork APKの互換性保証へ使わない。fork33維持・rollback条件は [#522 assessment](./issue-522-rebase-data-compatibility.md) で確定した。一方でgrid migration utilの分割・Kotlin化という構造変化がある（§5）。
2. **Quickstep/compat**: 16-devはAPI 36（Baklava）用 `QuickstepCompatFactoryVBaklava` と `QUICKSTEP_MIN_SDK=35 / MAX_SDK=36` を実装済み（`build.gradle:147-148`）。**API 37（Android 17）のcompat factoryは存在せず、`quickstepMaxSdk=36` のまま**。quickstep advertised support rangeはbaselineの29..35→35..36へ変化しており、API 29〜34端末での実挙動・サポート影響の確定は #520 の対象である（本Phaseでは影響を断定しない）。`QUICKSTEP_MAX_SDK` の単純な定数引き上げを先に採用しない（Epic Non-goals）。
3. **targetSdk 37**: 16-devは `targetSdk = 37`、compileSdk 37（minor 2）、buildTools 37.0.0、minSdk 26。Android 16/17 behavior changesの分離はPhase 1子Issueへ委ねる。
4. **build再編の規模**: AGP 9.0.1→9.4.1、Kotlin 2.3.0→2.4.20。vendored `wmshell/`（1,639 files +198k行、Gradle module `:wmshell`）、`flags`、`dagger`、`concurrent`、`modules:widgetpicker` 等の新moduleと、新submodule `platform_frameworks_libs_systemui`（branch 16-dev）の取込みが必要。toolchain詳細の正本更新（building guide）はPhase 4である。
5. **fork patchの所在**: 再適用対象は105 path（49 fork追加file + 56 upstream file patch/追従）。fork commitはbaseline後に1,799件（merge commitを含む）。replay単位の扱い（merge commitの保持有無）はPhase 2 planで決定する。
6. **16-devはmerge主体の統合履歴**（merge 3,236件）であり、upstream自体は15→16をmerge/再構築で進めている。forkはEpic指定のとおり監査性のためreplay型rebaseを採用する（ADR-0018 Alternatives参照）。

## 7. Phase 1への引き継ぎ

- **API 37 Quickstep/recents（#520）**: §6-2の事実（`compatLibVBaklava` まで、`quickstepMaxSdk=36`、advertised range 29..35→35..36）を入力として、引き上げ評価・compat前提・system overviewとの境界を確定する。
- **targetSdk 37 / behavior changes（#521）**: §6-3を入力に、upstream変更で自動的に解けるものとNunu固有対応の分離を行う。
- **schema/migration/backup/restore互換（#522）**: §6-1と§5の構造変化（grid migration分割、`RestoreDbTask` up +116/−111、`DatabaseHelper` up +48/−59、`NovaBackupConverter.kt` up +13/−42）に加え、§5.1 Group CのNova restore系port候補（subgrid警告、smartspace toggle）を入力に、既存layout/recovery契約との互換性とrollbackをrebase前に確定する。

## 8. 未確認範囲とリスク

- **build可否は未検証**: 本書はdiff/object database解析のみであり、AGP 9.4.1 / compileSdk 37 でのbuild（JDK 21継続可否を含む）はPhase 2の最初のgateである。
- **数値は `--no-renames` 基準**: 分類とbridge照合の数値はrename検知なし。rename-aware総量（4,999 files +510,077/−163,002）は参考値として併記した。
- **候補SHAの先取り禁止**: `43a21b43` は2026-10-04の観測値。Phase 2着手までに16-devが前進した場合の扱いはADR-0018（再anchor手続き）に記載する。
- **dispositionの変更管理**: 本書§5の `keep / adapt / drop` はPhase 0時点の確定判定である。Phase 2のconflict解消で16-dev側代替の発見等により判定を変える場合は、本書とADR-0018の改訂として記録する（黙示的な変更はしない）。up/fork行数は競合面の規模指標であり、作業量の確約ではない。
- **runtime観測なし**: recents動作、behavior changeの実挙動、wmshell取込み後のbuild時間等は未観測である。

## 9. Prior art

- git公式docs `git-rebase`（https://git-scm.com/docs/git-rebase 。確認日 2026-10-04）: `--onto` によるcommit replayの機構的根拠。fork commitのreplay型rebase（upstream ancestry維持）に採用。
- 上流Lawnchair 16-devの統合履歴（local object database `b011d84c..43a21b43`、merge commit 3,236件。確認日 2026-10-04）: upstreamはmerge主体でAOSP tag/15-devを統合している。forkは監査性（bridgeごとのconflict attribution、patch所在の追跡）を優先し、merge方式を採用しない（不採用の理由。ADR-0018 Alternativesに展開）。

## 10. Change history

- 2026-10-04: 初版。候補固定（§2）、7区分分類（§3）、surface計測（§4）、disposition分析（§5）、発見（§6）を記録。対象: main `0b4db97a9aa8853fba9824ae148aadb4ec42e32b`、候補upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`。
- 2026-10-04（revision 2）: PR #523 review round 1（[ChatGPT review](https://github.com/nunu1733/NunuLauncher/pull/523#issuecomment-5975549572)）指摘対応: (1) §4の `--verify` 記録を実測（105 files +23,314/−1,098、anchor `8af117b6fc`）へ修正し、初回capture値（47/+3,993/−1,017）との混在を解消、(2) §5.1にbaseline側33 commitsの棚卸しを追加（30件不運搬可、3件port/pin判断）、(3) §5のdispositionを `keep / adapt` の単一結論（path内訳つき）へ変更しdrop判定の根拠を明記、(4) §6-2のquickstepMinSdk影響記述を「advertised range変化・実影響は#520で確定」へ修正、(5) §1のnetwork記述と§7のIssue番号を修正。
- 2026-10-04（revision 3）: PR #523 review round 2（[ChatGPT review](https://github.com/nunu1733/NunuLauncher/pull/523#issuecomment-5975684996)）指摘対応: (1) §5.1 Group CのNova restore 2件を「UI＋restoreデータ変換契約」の挙動単位へ展開（`9b48473c`: subgrid警告＋`roundToInt()`座標変換〔16-devは `.toInt()` のまま〕、`53a2092541`: toggle＋rows+1補償・cellY shift・clamp/skip〔16-devに同等なし〕。いずれも16-dev候補で確認済み）し、採否確定を#522（Phase 1、rebase前）へ移管、(2) §5のdispositionを全groupでkeep/adapt subsetの統一粒度に変更（合計keep 57 / adapt 48 / drop 0）、(3) §4のanchor後差分説明を「docs/spec-only（#507/#508/#509を含むmerge群）でsurface差分なし」へ修正。
- 2026-10-04（revision 4）: **acceptedへ遷移**。PR #523 review round 3（[review](https://github.com/nunu1733/NunuLauncher/pull/523#issuecomment-5975761227)）でblocking findingなしを確認し、ADR-0018のaccepted遷移とともに本書を受入。受入は本PR #523のmergeで完了する。

- 2026-10-04（#522追記）: §6-1のschema32不変はupstream baseline/候補間の事実と明記。fork33の維持・rollback判断は#522 assessmentとADR-0018改訂へ接続（Phase0の固定SHA/計測値は変更しない）。
