# 16-dev rebase時のschema/migration/backup/restore互換とrollback契約（Issue #522）

> Status: proposed（review中。受理時にacceptedへ更新する）
> Research date: 2026-10-04
> Agent session: ZCode（GLM-5.3-flash）
> Issues: Epic [#516](https://github.com/nunu1733/NunuLauncher/issues/516) Phase 1、[#522](https://github.com/nunu1733/NunuLauncher/issues/522)
> 対象revision: fork main `a65a1d169c743ef28e7f07c4717b5efc08c9e1b1`（PR #523 merge後）、upstream baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`、候補upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`（ADR-0018採用SHA）
> 出典: [ADR-0018](../adr/0018-lawnchair-16-rebase.md) Decision 2/5、[Phase 0 assessment](./issue-516-16-rebase-phase0-research.md) §5.1/§6-1

## 1. 問いと方法

#522の4問い（upgrade方向、downgrade方向、backup/restore、grid migration分割）について、rebase着手前に互換性とrollback契約を確定する。方法はlocal Git object database解析（`git diff` / `git grep`、対象commit固定）とADR/specの正本参照。worktree変更なし。

## 2. Q1: upgrade方向（15 fork APK → 16 rebase APK）

### 2.1 Launcher DB schema

- `SCHEMA_VERSION` 32はbaselineと16-devで同一（[Phase 0 §6-1](./issue-516-16-rebase-phase0-research.md)）。
- **schema内容の変更なしを確認**（2026-10-04）: `git diff 505dbc40..43a21b43 -- src/com/android/launcher3/model/DatabaseHelper.java` はコメント修正と `mMaxItemId` 初期化の `compareAndSet` 化のみで、CREATE TABLE / ALTER / 列定義の変更は0件。`LauncherSettings.java` の差分はjavadoc再整形、`CONTAINER_PREDICTION`→`CONTAINER_ALL_APPS_PREDICTION` の **Java定数rename**（DB列ではない）、layout provider用のキー定数整理（`LAYOUT_DIGEST_KEY`→`LAYOUT_PROVIDER_KEY`/`BLOB_KEY_PREFIX`。runtime preference keyでありfavorites schemaではない）のみ。`LauncherProvider.java` の差分もquery where句の `Pair.create` 化等でschema/transaction構造の変更なし。
- fork固有の `ORGANIZER_LOCK_STATE` 列（ADR-0004、non-wiping ALTER TABLE + 失敗時rollback）はforkのDatabaseHelper patch（[bridge disposition: adapt](./issue-516-16-rebase-phase0-research.md) §5）として同一内容で再適用される。schema 32 + lock列の組み合わせはrebase前後で同一。
- organizer recovery storeは **version付きapp-private database**（[ADR-0003](../adr/0003-organizer-recovery-point-storage.md)）であり、Launcher DBのschema/upgrade経路から独立。rebaseによる影響なし。

**結論（upgrade契約）**: 既存install → rebase APK は schema 32連続（onUpgrade不発火）で、favorites/lock列/recovery storeはそのまま維持される。未知のpreference keyは§2.2のとおり無視される。

### 2.2 Lawnchair preferences

16-devは `PreferenceManager2.kt` を再構成した（up +228/−51。dagger singleton化、keyの追加・削除）。keyのrenameは確認されず（削除 `show_hidden_apps_in_search` / `enable_smart_hide` 等と追加のみ。2026-10-04確認）。DataStore形式のため、restore時に旧APKで書かれたkeyは読み飛ばされ、新keyは既定値になる。**layout-data riskなし**（workspace構成はfavorites DBが正であり、preference欠落は機能設定の初期化のみ）。

## 3. Q2: downgrade方向（16 rebase APK → 15 fork APK）

- schema 32が同一のため、downgradeでschema down-conversion（`DbDowngradeHelper` / `downgrade_schema.json`）は発火しない。`downgrade_schema.json` はbaselineと16-devで無変更（Phase 0 §5）。
- 16 rebase APKが書き得るfavorites行はschema 32の列集合に収まる（rebaseは構造変化への追従であり、列追加を行う契約は本assessmentでは確定していない。新列を要求する変更はPhase 2でADR-0004と同様の受入を要求する）。
- preferenceは§2.2の逆: 16側で追加されたkeyは旧APKで無視される。
- **契約**: rollback点と操作は [ADR-0018 Decision 5](../adr/0018-lawnchair-16-rebase.md)（`pre-lawnchair16-<date>` tag、cutover revert / 旧baselineからのrelease再発行）。16→15 APK downgradeの実証は **Phase 4の要求のまま**（[ADR-0018](../adr/0018-lawnchair-16-rebase.md): 実証またはowner decisionまでbaseline切替を完了とみなさない）。本assessmentは契約のみ確定し、実証は行わない。

## 4. Q3: backup/restore と Nova restore 2挙動の採否（ADR-0018 Decision 2の実行）

### 4.1 Lawnchair backup/restore

- `LawnchairBackup.kt` は16-devで無変更（[bridge cross-reference](./issue-516-16-rebase-phase0-research.md) §5: up-unchanged）。`NovaBackupConverter.kt` は16-devで進化（up +13/−42。`GridType.GRID_TYPE_ANY` 対応の追加と、**subgrid/smartspace処理の欠落**）。`RestoreNovaBackupScreen.kt` / `RestoreNovaBackupViewModel.kt` は16-devに存在する。
- `RestoreDbTask.java` は16-devで再構成（`restoreIfNeeded` → `createRestoreTask` 返却のConsumer化、one-grid flag対応。up +116/−111）。restore全体の流れ（backup DB restore → grid整合）は維持されており、forkのpatch（+88/−13）は新構造へ再適用する。
- organizer recovery artifact（ADR-0009/0011）はapp-private格納で、Launcher DB backup/restore（ZIP）経路から独立。old-backup restore後のrestart再入は [DESIGN.md §4.6](../../DESIGN.md) のstartup migration契約が所有し、rebaseで不変のfork所有契約である。

### 4.2 Nova restore 2挙動の採否決定

[Phase 0 §5.1 Group C](./issue-516-16-rebase-phase0-research.md) のとおり、baseline `505dbc40` に存在し16-dev `43a21b43` に同等が存在しない挙動:

| 挙動単位 | 内容 | 16-dev側の状態（2026-10-04確認） |
|---|---|---|
| `9b48473c` (i) | subgrid検出と警告UI | `novaSubgridRegex` 等が16-devに存在しない |
| `9b48473c` (ii) | `cellX/cellY/spanX/spanY` の `.toInt()` 切り捨て→`roundToInt()` 最近傍丸め | `NovaBackupConverter.kt:358-362` は16-devでも `.toInt()` のまま |
| `53a2092541` (i) | smartspace conflict toggle UI | 16-devに該当toggleなし |
| `53a2092541` (ii) | smartspace有効時の `rows +1` grid補償・desktop itemの `cellY +1` shift・span/grid境界のclamp・範囲外item skip | 16-devは `rows = info.rows` の補償なし、smartspace処理0件 |

**決定: 4挙動単位すべてを採用（adopt）し、Phase 2のNovaBackupConverter adaptationで16-dev `43a21b43` へportする。**

根拠: (a) これらは現baseline（=現行製品）が持つNova backup復元の品質契約であり、rebaseによる欠落は配置の切り捨て誤差（subgrid座標のtruncate）とAt a Glance重複（rows補償なし）という復元品質回帰になる。(b) upstream-strategyの「16側で同等以上に解決されたpatchは削除」の条件を満たす16-dev側実装が存在しない（Phase 0 §5.1で確認済み）。(c) 機能自体（Nova backup import）は16-devに存続するため、port先は安定している。

**保持条件（Phase 2実装への要求）**:
1. portは16-devの `GridType.GRID_TYPE_ANY` 対応と共存する形で行い、`writeGridToLawnchairPrefs` / `createRestoredDb` の新signatureへ統合する（15側commitの機械的な再適用ではなく、16-dev構造への再表現）。
2. 挙動単位 (ii)（座標丸め）と (ii')（rows補償・clamp/skip）には、fixture経由のrestore変換oracle（`.toInt()` との差分が観測できる境界値: 小数座標、subgrid表記、At a Glance有効grid）をPhase 2/3で追加する。
3. UI単位 (i)（警告/toggle）は既存のfork strings経路（organizer-ui groupと同じ `lawnchair/res/values*/strings.xml` 管理）で維持する。

本決定は [ADR-0018 Decision 2](../adr/0018-lawnchair-16-rebase.md) の「#522の成果物としてrebase着手前に確定」に対応するものであり、icon shadow submodule pin（Phase 2 plan判断）とは分離される。

## 5. Q4: grid migration分割への契約接続

16-devは `GridSizeMigrationUtil.java` を `GridSizeMigrationDBController.java`（TABLE_NAME/TMP_TABLE操作、controller層）と `GridSizeMigrationLogic.kt`（`migrateGrid` / `migrateWorkspace` / `placeItems` 等のlogic層）へ分割した。

- forkの `GridMigrationJournal` / `GridMigrationOperation` / `GridMigrationRuntime`（fork追加file、layout-schema group）は、現行 `GridSizeMigrationUtil.java` のpatch（fork +84/−30）を通じてmigration実行に接続している。Phase 2ではこの接続を **分割後のcontroller/logicの境界へ再表現する**。
- 契約の維持条件（[spec #118](../../specs/118-sqlite-migration-transaction-audit/spec.md)）: journal/operation/runtimeの記録はmigrationのDB transaction内のままとし、transaction ownershipをlogic層へ移して新たなtransaction境界を作らない。適用後の検証は [ADR-0016](../adr/0016-layout-application-test-surface.md) のcanonical surface（organizer shared-writer lane / upstream grid migration test）で行う。
- 16-dev自身のgrid migration test（`tests/multivalentTests/.../GridSizeMigrationTest.kt` 等）が新構造のupstream oracleとして存在するため、fork側の追加oracleはjournal/runtime契約の境界に絞る（既存testの無条件複製をしない。Epic Phase 3規定）。

## 6. Phase 2/3で必要なmigration / downgrade / backup-restore test一覧

| # | 表面 | 既存/新規 | 内容 |
|---|---|---|---|
| T1 | real APK upgrade evidence（fork 15 APK → rebase 16 APK） | 既存lane（startup migration integration test、[DESIGN.md §10](../../DESIGN.md)）の再実行 | schema 32連続・layout維持・lock列維持・recovery store維持 |
| T2 | downgrade smoke | 既存 `tools/deck-retirement-downgrade-smoke.sh` | 15→16→15のDB downgrade経路。16→15実APK evidenceはPhase 4（ADR-0018 Decision 5） |
| T3 | backup/restore smoke | 既存 `tools/deck-retirement-backup-restore-smoke.sh` + `tools/organizer-recovery-smoke.sh` | rebase APK上でのbackup作成→restore→recovery artifact生存 |
| T4 | Nova restore変換oracle | **新規**（§4.2保持条件2。fixture境界値ベース） | roundToInt丸め、rows+1/cellY shift、clamp/skip、subgrid警告表示 |
| T5 | grid migration契約 | 既存upstream `GridSizeMigrationTest.kt`（16-dev由来）+ forkのjournal/runtime契約testの再point | 分割構造上でのtransaction ownership（spec #118） |

## 7. 未確認範囲

- 本assessmentは契約確定（Git object解析と正本参照）であり、T1〜T5の実行はPhase 2/3で行う。
- `PreferenceManager2` の全key差分の列挙はしていない（§2.2は構造変化とkey追加/削除の確認まで。renameなしの確認は差分のkey文字列抽出による）。
- 16 rebase APKの実ビルド・実行での挙動（Behavior change含む）は #521 とPhase 2/3の対象。

## 8. Change history

- 2026-10-04: 初版。4問いの契約確定と、Nova restore 2 commit（4挙動単位）の採用決定（ADR-0018 Decision 2の実行）を記録。対象: main `a65a1d169c743ef28e7f07c4717b5efc08c9e1b1`、候補upstream `43a21b43d7cc7850ab54e14b1a57dc9646685f35`。
