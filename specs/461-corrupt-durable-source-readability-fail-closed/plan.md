# Implementation Plan: Fail closed when a durable grid-migration recovery source is unreadable

> Issue: [#461](https://github.com/nunu1733/NunuLauncher/issues/461)
> Spec: [spec.md](./spec.md)
> Status: accepted
> Updated: 2026-10-01

Risk tier: H（Launcher DB / layout-data 安全 path。spec.md + plan.md の両方正本）。
Review 5924983374 の修正要件（非破壊完全性検証への拡張・保全 assert・accepted 化）を反映済み。

## Current evidence

### 確認済みの事実

- `GridMigrationFailureTest.restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper`
  （`tests/organizer-instrumentation/com/android/launcher3/model/GridMigrationFailureTest.java:337-354`）
  が、RESTORE_PENDING journal + corrupt SOURCE_DB の fixture で
  `fail("An unreadable durable-recovery source must fail closed")` に到達して決定的に失敗する。
  Issue #458 focused validation（
  [specs/458-semantic-test-audit/audit.md §8.6](../../specs/458-semantic-test-audit/audit.md)、
  2026-09-25、実行 revision `b242891f`、V3 で 1/21）で pixel_6 / pixel_7_pro（arm64
  API 36.1）の両 AVD が同一失敗（AVD 非依存）。
- fixture: `GridMigrationTestSupport.corruptDatabase` は SOURCE_DB を削除した後、
  `"issue 59 corrupt database"`（24 バイトの非 SQLite 内容）を書き込む。existence check は
  通るが読取可能性は検証されない。
- `validatedJournalSourceFile`（`src/com/android/launcher3/model/ModelDbController.java:816`）
  は identity（source name と source preferences の db file 一致）・path（target と同
  directory・target と非同一）・existence（`isFile()`）のみを検証し、読取可能性は検証しない。
  4 呼び出し口: `reconcileActiveDatabaseJournal` :561、`compensateAndRestore` :595、
  `restoreSourceAuthority` :746、`validateFinalizedJournalSource` :796。
- f8bddc7944（"fix: fail closed on unresolved recovery and validate durable sources"）の
  commit message は「recovery can no longer manufacture or publish an empty source
  database」を主張するが、validation は identity/path/existence に止まる。missing ケースに
  は効くが corrupt ケースには効かない。
- test は f8bddc7944 と同一 commit で追加され、`openJournalSource` /
  `refreshMaxItemIdFromCommittedRows` は当時と byte 等価のため、追加当初から赤の可能性が
  高い（unrouted で気付かれていなかった。audit §8.6 の分析）。
- repository 内先例: `GridMigrationJournal.read(Context, String)`
  （`src/com/android/launcher3/model/GridMigrationJournal.java:48-52`）が既に
  `SQLiteDatabase.openDatabase(path, null, OPEN_READONLY)` + try-with-resources close を
  使用する。本修正の probe は同一形状に custom `DatabaseErrorHandler` を加えたものであり、
  新しい open 方式を導入しない。
- favorites DB に WAL 有効化なし（`DatabaseHelper` / `ModelDbController` に
  `setWriteAheadLoggingEnabled` / `ENABLE_WRITE_AHEAD_LOGGING` なし）→ 既定 rollback
  journal mode（delete 系）。read-only probe が sidecar（`-journal`/`-wal`/`-shm`）を
  生成しない前提と整合する。

### 制御流上の推論と review で確定した failure class

- corrupt source の writable open（`openJournalSource` :777-791 →
  `refreshMaxItemIdFromCommittedRows` → `getWritableDatabase`）は既定
  `DatabaseErrorHandler` により corrupt file を削除・空 DB として再作成し、
  `restoreSourceAuthority`（:743-764）が完了して `Reconciliation.FAILED` で戻る経路が
  非例外で通る唯一の path。結果として corrupt source 削除 → 空 source publish → source
  preferences 書換 → journal/BACKUP 削除という逆状態になる。AC-59-06 違反（layout data 損失）。
- review 5924983374 で確定: header-magic check 単独では failure class を固定できない。
  有効な SQLite header を持ち body（page 領域）が破損した file は magic check を通過し、
  writable open で同様に空 DB として再作成され得る。よって readability は
  「valid header + 非破壊 read-only open + `PRAGMA quick_check` == `ok`」で定義する
  （spec accepted へ反映済み）。

### 再現 command

```bash
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.model.GridMigrationFailureTest
```

AVD `issue142_api36`（起動済み emulator-5554）で実行する（CI と同一の gradle task /
runner argument 形式。既存記録: `docs/assessment/pr-114-model-writer-reentry.md`、
`docs/assessment/pr-183-recovery-tombstone-admission.md`、CI db-migration lane は
`.github/workflows/ci.yml:488` で同一 task を使用）。

## Design

### Modules and interfaces

- seam は 1 点のみ: `ModelDbController.validatedJournalSourceFile(GridMigrationJournal.Entry,
  DatabaseHelper)`（package-private、既存の identity/path/existence gate）。
  検証を次の 3 段階へ拡張する（いずれの失敗も既存と同じ `IllegalStateException`）:
  1. 既存の identity / path / existence 検証（変更なし）。
  2. raw magic header check: `FileInputStream` または `RandomAccessFile` で先頭
     16 バイトを読み、`SQLite format 3\000` と一致することを要求。`IOException`・
     16 バイト未満も検証失敗。早すぎる open を防ぐ粗い判定として先に置く。
  3. 非破壊完全性検証:
     `SQLiteDatabase.openDatabase(sourceFile.getPath(), null, SQLiteDatabase.OPEN_READONLY,
     errorHandler)` で開き（`CREATE_IF_NECESSARY` を付けない）、`PRAGMA quick_check` を
     `SQLiteStatement.simpleQueryForString` で実行し、結果が `ok` であることを要求する。
     open / query 中の `SQLiteException`・`SQLiteDatabaseCorruptException`、および `ok`
     以外の結果は検証失敗。database は `finally` で必ず `close()` する。
- custom `DatabaseErrorHandler` は `ModelDbController` 内の **private static class** と
  して追加する（新規 public 型は作らない）。`onCorruption` は file を削除・再作成せず、
  corrupt を呼び出し側へ例外として伝播させる（証拠保全）。この handler は probe 専用で
  登録先は probe のみであり、既定 handler や他 open path の挙動は一切変更しない。
  method 近傍の Issue #59 comment に Issue #461 と検証内容（header + quick_check、
  非破壊）を追記する。
- これで全 4 呼び出し口が fail closed になる。`DatabaseHelper` /
  `NoLocaleSQLiteHelper` / 既定 `DatabaseErrorHandler` は変更しない（既定 error handler の
  self-heal 挙動は active admission 等の他経路で意味を持つため、journal source 検証 seam の
  外へ変更を漏らさない）。
- 新規 public interface・adapter・module は作らない。production 実体が既に存在する seam への
  最小追加である。

### Sidecar / WAL の注意点

- favorites DB は既定 rollback journal mode（delete 系、WAL 無効、Current evidence 参照）
  であり、`OPEN_READONLY` 単独の open は journal file を作成・変化させない。republish 後の
  sidecar 件（`-wal`/`-shm` が絡む journal mode の問題）は本契約の対象外である。
- probe は検証対象 file を一切書き換えない（header 読取は read-only、SQLite open は
  `OPEN_READONLY`、`quick_check` は非破壊診断）。`close()` を必ず行い、probe 由来の
  永続状態を残さない。

### Data flow

- 正常系: journal.sourceDatabaseName → `validatedJournalSourceFile`（identity / path /
  existence / magic / read-only quick_check）→ `openJournalSource` または
  `publishFreshSource`。
- 失敗系（RESTORE_PENDING + corrupt source、primary 回帰）:
  `reconcileActiveDatabaseJournal` :561 が `openJournalSource` の前に throw →
  `migrateGridIfNeeded` の catch（:386-387）→ `failClosedActiveRecovery`（active helper
  close、`mOpenHelper = null`）→ `GridMigrationRecoveryPendingException` を伝播。
  journal は RESTORE_PENDING のまま、corrupt file は削除も再作成も変更もされない
  （byte-identical）。
- FINALIZED reconciliation failure 経路（:699-720）でも同一 seam を通るため、corrupt
  source は `validateFinalizedJournalSource` :796 で失敗し、target helper quarantine +
  recovery-pending となる（既存 missing-source test と同型の挙動）。

### Alternatives rejected

- header-only magic check（初版案、review 5924983374 で撤回）: valid header + corrupt
  body を healthy と誤判定し、failure class を固定できない。非破壊完全性検証（quick_check）
  へ拡張して採用。
- 既定 `DatabaseErrorHandler` での read-only probe: `onCorruption()` が検出対象の
  corrupt file を削除し得るため不採用（証拠保全と fail-closed の要件に反する）。この
  不採用理由は controller-private の non-deleting handler により解消され、custom handler
  付き probe として採用した。既定 handler を使う probe は引き続き不採用。
- `DatabaseHelper` / `NoLocaleSQLiteHelper` / `DatabaseErrorHandler`（既定）側への実装:
  これらは migration 以外の全 DB open（active admission・通常 load 等）に共用され、
  変更が他経路の self-heal 挙動を変える。契約 owner は journal source 検証 seam である。
- open 後の空 source 検出（例: `refreshMaxItemIdFromCommittedRows` 後の行数 sanity check）:
  検出時点で corrupt file の削除・再作成が既に起きており、証拠保全に失敗する。遅すぎる。
- `integrity_check` / `foreign_key_check` への置換: `quick_check` がこの gate の
  cost/coverage point（O(N)・非破壊・UNIQUE と index 対照を除く）。spec Non-goals に記録。
- 新規 test class / lane の追加: 本契約の fail-closed 振る舞いは既存 class が owner 境界
  （controller entry）であるため、既存 class 内の method 追加と既存 Support への fixture
  追加で足りる（parent 決定 + review 5924983374）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `src/com/android/launcher3/model/ModelDbController.java` | `validatedJournalSourceFile` に (a) magic header check、(b) controller-private non-deleting `DatabaseErrorHandler` 付き `OPEN_READONLY` open + `PRAGMA quick_check` == `ok`（`finally` で close、`CREATE_IF_NECESSARY` なし）を追加。private static class として handler を追加。comment に Issue #461 を残す | 4 つの durable-recovery 呼び出し口が共有する唯一の検証 seam。他型を変更せず全経路を fail closed にできる |
| `tests/organizer-instrumentation/com/android/launcher3/model/GridMigrationTestSupport.java` | valid-header + body-corrupt fixture helper を 1 個追加（最小構成）: 正規 SQLite DB の file bytes を保持したまま先頭 16 バイトを維持し後続（page 領域）を書換する。raw bytes の取り出し/比較 helper を含む | 既存 Support の fixture 集約に沿う。新規 test file・新規 lane は作らない |
| `tests/organizer-instrumentation/com/android/launcher3/model/GridMigrationFailureTest.java` | body-corrupt 用の新 test メソッドを 1 本追加: fixture 適用 → attempt 前の raw bytes capture → `tryMigrateDB` が `RuntimeException` を投げること・`publishedHelper()` null・journal RESTORE_PENDING 不変・raw bytes が byte-identical であることを同一メソッド内で assert。既存 oracle メソッド（`restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper` 他）は byte-identical で不変 | 保全 assert を既存 oracle へ足さず、body-corrupt 用新メソッド内に fail-closed assert と保全 assert を両方入れる形（review 5924983374 実行順の許容形）。契約 owner 境界は既存 class の controller entry のまま |
| `.github/workflows/ci.yml` | db-migration lane の runner class list（:488）へ `com.android.launcher3.model.GridMigrationFailureTest` を追加し、lane comment（:482-486 の "GridMigrationFailureTest stays unrouted behind #461"）を更新 | routing の正 carrier は lane の明示 class filter（新メソッドを含む class 全体が実行対象）。surface_db_schema path filter は既に `tests/organizer-instrumentation/com/android/launcher3/model/**`（:164）を含み test-only 変更の self-trigger は成立済みで、surface 定義変更は不要 |
| `docs/engineering/ci-test-portfolio.md` | db-migration lane 行（:56）、"Routing 分離" disposition（:135-138）、model/** over-trigger 注記（:146-149）を routing 完了の記録へ更新 | 監査表の正本。map の human-readable mirror |
| `tools/repo-contract/ci_portfolio_map.yml` | lane↔surface edge は不変（db-migration lane は既に surface_db_schema を所有）。同 PR 更新規則（AGENTS.md / test-audit skill）に従い、class list への追加（Issue #461）を comment に記録 | edge 正本。edge 変更がないことを明示しつつ同じ PR で更新する |

注: parent 指示の「`GridMigrationFailureTest.java`（routing 追加）」は routing の結果として
当該 class が lane で実行されることを意味する。routing の物理的変更先は `.github/workflows/ci.yml`
の class list であり、surface 定義は変更しない。

## Migration and recovery

- schema/rule migration: なし。DB schema 変更なし、書込み経路追加なし。
- failure 中の rollback: 該当なし（validation は読取のみで、open 前に失敗するため
  永続状態を残さない。probe 自体も非破壊で close まで行う）。
- release rollback/downgrade: revert commit のみで無依存（他変更・永続状態に依存しない
  単一 seam の gate 追加）。
- backup/restore compatibility: 影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-461-01（non-SQLite dim、既存 oracle 不変） | 既存 test が green（本 review 前の §8.6 red 証跡は既に記録済み）。test file の既存メソッドが不変であることを diff で確認 | AVD `issue142_api36`（emulator-5554）で `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.model.GridMigrationFailureTest` |
| AC-461-02（valid-header + body-corrupt、red→green） | 実装順: 新 fixture + 新メソッド追加直後に focused 実行で **赤**（現行 production は fail closed せず、保全 assert も不成立）を記録 → production 修正後に **緑**（例外・`publishedHelper()` null・journal RESTORE_PENDING・raw bytes byte-identical）。fixture が quick_check で non-ok になることを同一実行で確認 | 同上（同 AVD）。red/green 各 1 回の focused 実行を PR 証跡に記録 |
| AC-461-03（4 dim 合流・非破壊） | non-SQLite dim は AC-461-01 oracle、body-corrupt dim は AC-461-02 が所有。truncated / 0-byte dim と probe 失敗意味論（`SQLiteException`/`SQLiteDatabaseCorruptException`・non-ok・`finally` close・`CREATE_IF_NECESSARY` なし）が同一 `IllegalStateException` branch へ合流することを実装 inspection と focused validation 証跡で確認（恒久 test は追加しない。spec Non-goals） | AC-461-01/02 と同一実行 + code review |
| AC-461-04（既存結果不変） | `GridMigrationSuccessTest` + `GridMigrationFailureTest` の focused 実行が全 green で、既存 test の結果が不変であることを PR へ記録 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.model.GridMigrationSuccessTest,com.android.launcher3.model.GridMigrationFailureTest`（同 AVD） |
| AC-461-05（routing） | PR CI run で `organizer-instrumentation-db-migration-tests` が class list に `GridMigrationFailureTest` を含んだ状態で green。portfolio 文書と map を同 PR で更新 | GitHub Actions run（PR head）。`ModelDbController.java` は surface_layout_write に mapping されるため shared-writer 等 layout_write fan-out lane も同一 run で実行・green であること |
| Style / build | `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug` | JDK 21 / SDK Platform 36.1 / Build Tools 36.1.0（building guide 正本） |

実行順（メイン確定）: spec/plan 更新（本 revision）→ 実装修正
（test 先行: 新 fixture + 新メソッド → focused で赤 → production 修正 → focused で緑）→
`spotlessCheck` → AVD（issue142_api36 / emulator-5554）で `GridMigrationFailureTest`
単独 → 成功確認 → FailureTest+SuccessTest focused → routing + 文書更新を含む PR。

含めるべき観点: contract（controller 境界・interface 経由。既存 oracle + 新 body-corrupt
test）、failure injection（non-SQLite fixture と valid-header + body-corrupt fixture）、
integration（routing 後の CI db-migration lane で実 SQLite / 実 platform 上の回帰を恒常
検証）。unit/property 層は本契約の owner 境界ではない（既存 test が controller entry を
要求する。spec 59 Test oracle の原則を継承）。

## Documentation updates

- [x] spec status/history（本 revision で accepted + review 対応を記録）
- [ ] CONTEXT.md — 不要（domain language 追加なし）
- [ ] DESIGN.md — 不要（system structure 変更なし。既存 seam への gate 追加のみ）
- [ ] ADR — 不要（既存契約 AC-59-06 の施行であり、新規の変更困難な判断ではない。
      readability 定義・quick_check 採用・既定 handler probe 不採用の理由は spec
      Non-goals / Prior art と本 plan に記録済み）
- [ ] AGENTS.md — 不要（検証済み command の必須追加なし。focused run は既存 gradle task の
      組合せであり、恒久必須化は行わない）

## Execution checklist

- [ ] Current behavior reproduced（§8.6 red 証跡 + 新 body-corrupt test の focused 赤を AVD 上で確認）。
- [ ] Tests fail for the missing behavior（test 先行: 新メソッドが production 修正前に赤）。
- [ ] Minimal implementation completed（`validatedJournalSourceFile` + controller-private
      handler のみ。4 呼び出し口で fail closed を確認）。
- [ ] Probe の非破壊性を確認（検証前後で source file と sidecar が byte-identical）。
- [ ] Migration/recovery verified（該当なし: schema 変更・書込み経路追加なし）。
- [ ] Full relevant verification completed（`GridMigrationFailureTest` 単独 → focused
      2 class、spotlessCheck、assemble、routing 後の CI db-migration lane、layout_write
      fan-out lane）。
- [ ] PR evidence and remaining risks recorded（red→green 証跡、byte-identical 保全確認、
      CI run link、非対象範囲）。
