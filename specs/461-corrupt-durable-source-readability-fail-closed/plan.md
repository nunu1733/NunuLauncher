# Implementation Plan: Fail closed when a durable grid-migration recovery source is unreadable

> Issue: [#461](https://github.com/nunu1733/NunuLauncher/issues/461)
> Spec: [spec.md](./spec.md)
> Status: draft
> Updated: 2026-10-01

Risk tier: H（Launcher DB / layout-data 安全 path。spec.md + plan.md の両方正本）。

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

### 制御流上の推論（in-vivo 観測まではしていないが制御流と整合）

- corrupt source の writable open（`openJournalSource` :777-791 →
  `refreshMaxItemIdFromCommittedRows` → `getWritableDatabase`）は既定
  `DatabaseErrorHandler` により corrupt file を削除・空 DB として再作成し、
  `restoreSourceAuthority`（:743-764）が完了して `Reconciliation.FAILED` で戻る経路が
  非例外で通る唯一の path。結果として corrupt source 削除 → 空 source publish → source
  preferences 書換 → journal/BACKUP 削除という逆状態になる。AC-59-06 違反（layout data 損失）。
- 修正はこの writable open を一切起こさないことが本質であるため、再作成機構の in-vivo
  実測は不要（validation が open の前に失敗するため、機構の詳細に修正の正しさが依存しない）。

### 再現 command

```bash
./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.model.GridMigrationFailureTest
```

pixel_6 / API 36.1 AVD を起動した状態で実行する（CI と同一の gradle task / runner
argument 形式。既存記録: `docs/assessment/pr-114-model-writer-reentry.md`、
`docs/assessment/pr-183-recovery-tombstone-admission.md`、CI db-migration lane は
`.github/workflows/ci.yml:488` で同一 task を使用）。

## Design

### Modules and interfaces

- seam は 1 点のみ: `ModelDbController.validatedJournalSourceFile(GridMigrationJournal.Entry,
  DatabaseHelper)`（package-private、既存の identity/path/existence gate）。
  existence check の後、source file を素ファイル読取（`FileInputStream` または
  `RandomAccessFile`）で先頭 16 バイトを読み、`SQLite format 3\000` と一致することを要求する。
  `IOException`・16 バイト未満も検証失敗扱いとし、既存の検証失敗と同じ
  `IllegalStateException` を throw する。method 近傍の Issue #59 comment に Issue #461 を
  追記する。
- これで全 4 呼び出し口が fail closed になる。`DatabaseHelper` /
  `NoLocaleSQLiteHelper` / `DatabaseErrorHandler` は変更しない（既定 error handler の
  self-heal 挙動は active admission 等の他経路で意味を持つため、journal source 検証 seam の
  外へ変更を漏らさない）。
- 新規 interface・adapter・module は作らない。production 実体が既に存在する seam への
  最小追加である。

### Data flow

- 正常系: journal.sourceDatabaseName → `validatedJournalSourceFile`（identity / path /
  existence / readability）→ `openJournalSource` または `publishFreshSource`。
  読取は raw file の先頭 16 バイトのみで、書込みは発生しない。
- 失敗系（RESTORE_PENDING + corrupt source、primary 回帰）:
  `reconcileActiveDatabaseJournal` :561 が `openJournalSource` の前に throw →
  `migrateGridIfNeeded` の catch（:386-387）→ `failClosedActiveRecovery`（active helper
  close、`mOpenHelper = null`）→ `GridMigrationRecoveryPendingException` を伝播。
  journal は RESTORE_PENDING のまま、corrupt file は削除も再作成もされない。
- FINALIZED reconciliation failure 経路（:699-720）でも同一 seam を通るため、corrupt
  source は `validateFinalizedJournalSource` :796 で失敗し、target helper quarantine +
  recovery-pending となる（既存 missing-source test と同型の挙動）。

### Alternatives rejected

- read-only `SQLiteDatabase.openDatabase` probe: 既定 `DatabaseErrorHandler.onCorruption()`
  が検出対象の corrupt file を削除し得るため不採用（証拠保全と fail-closed の要件に反する）。
  byte-level の identity 確認に SQLite 機構を開くことも過剰。spec Non-goals に記録済み。
- `DatabaseHelper` / `NoLocaleSQLiteHelper` / `DatabaseErrorHandler` 側への実装:
  これらは migration 以外の全 DB open（active admission・通常 load 等）に共用され、
  変更が他経路の self-heal 挙動を変える。契約 owner は journal source 検証 seam である。
- open 後の空 source 検出（例: `refreshMaxItemIdFromCommittedRows` 後の行数 sanity check）:
  検出時点で corrupt file の削除・再作成が既に起きており、証拠保全に失敗する。遅すぎる。
- 新規 test の追加: 本契約の fail-closed 振る舞いは既存
  `restorePendingJournalWithCorruptSourceFailsClosedAndQuarantinesActiveHelper` が
  controller 境界の owner oracle として既に所有している（parent 決定: 新規 test は作らない）。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `src/com/android/launcher3/model/ModelDbController.java` | `validatedJournalSourceFile` に SQLite magic header（先頭 16 バイト = `SQLite format 3\000`、`IOException`・長さ不足も失敗）の読取可能性検証を追加。comment に Issue #461 を残す | 4 つの durable-recovery 呼び出し口が共有する唯一の検証 seam。他型を変更せず全経路を fail closed にできる |
| `.github/workflows/ci.yml` | db-migration lane の runner class list（:488）へ `com.android.launcher3.model.GridMigrationFailureTest` を追加し、lane comment（:482-486 の "GridMigrationFailureTest stays unrouted behind #461"）を更新 | routing の正 carrier は lane の明示 class filter。surface_db_schema path filter は既に `tests/organizer-instrumentation/com/android/launcher3/model/**`（:164）を含み test-only 変更の self-trigger は成立済みで、surface 定義変更は不要 |
| `docs/engineering/ci-test-portfolio.md` | db-migration lane 行（:56）、"Routing 分離" disposition（:135-138）、model/** over-trigger 注記（:146-149）を routing 完了の記録へ更新 | 監査表の正本。map の human-readable mirror |
| `tools/repo-contract/ci_portfolio_map.yml` | lane↔surface edge は不変（db-migration lane は既に surface_db_schema を所有）。同 PR 更新規則（AGENTS.md / test-audit skill）に従い、class list への追加（Issue #461）を comment に記録 | edge 正本。edge 変更がないことを明示しつつ同じ PR で更新する |
| `tests/organizer-instrumentation/com/android/launcher3/model/GridMigrationFailureTest.java` | 変更なし（byte-identical）。既存 corrupt-source test をそのまま oracle として使用 | parent 決定: 新規 test は作らず、既存 test が本契約の owner 境界 |

注: parent 指示の「`GridMigrationFailureTest.java`（routing 追加）」は routing の結果として
当該 class が lane で実行されることを意味する。routing の物理的変更先は `.github/workflows/ci.yml`
の class list であり、test file 本体は変更しない。

## Migration and recovery

- schema/rule migration: なし。DB schema 変更なし、書込み経路追加なし。
- failure 中の rollback: 該当なし（validation は読取のみで、open 前に失敗するため
  永続状態を残さない）。
- release rollback/downgrade: revert commit のみで無依存（他変更・永続状態に依存しない
  単一 seam の gate 追加）。
- backup/restore compatibility: 影響なし。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-461-01（red→green） | 修正前: 既存 test が `fail("An unreadable durable-recovery source must fail closed")` で赤。修正後: 同 test が緑（例外伝播、`publishedHelper()` null、journal RESTORE_PENDING）。corrupt SOURCE_DB のバイト列が残ることを同一実行の証跡（file 存在確認）で記録 | pixel_6 API 36.1 AVD 上で `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.model.GridMigrationFailureTest` |
| AC-461-02 | `GridMigrationSuccessTest` + `GridMigrationFailureTest` の focused 実行が全 green で、既存 test の結果が不変であることを PR へ記録 | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.model.GridMigrationSuccessTest,com.android.launcher3.model.GridMigrationFailureTest` |
| AC-461-03 | 非 SQLite dim は AC-461-01 の oracle test が所有。truncated / 0-byte dim は同一 `IllegalStateException` branch へ合流することを実装 inspection と focused validation 証跡で確認（恒久 test は追加しない。spec Non-goals） | AC-461-01 と同一実行 + code review |
| AC-461-04 | PR CI run で `organizer-instrumentation-db-migration-tests` が class list に `GridMigrationFailureTest` を含んだ状態で green。portfolio 文書と map を同 PR で更新 | GitHub Actions run（PR head）。`ModelDbController.java` は surface_layout_write に mapping されるため shared-writer 等 layout_write fan-out lane も同一 run で実行・green であること |
| Style / build | `./gradlew spotlessCheck`、`./gradlew assembleLawnWithQuickstepGithubDebug` | JDK 21 / SDK Platform 36.1 / Build Tools 36.1.0（building guide 正本） |

含めるべき観点: contract（controller 境界・interface 経由。既存 oracle test）、failure
injection（corrupt file fixture）、integration（routing 後の CI db-migration lane で実
SQLite / 実 platform 上の回帰を恒常検証）。unit/property 層は本契約の owner 境界ではない
（既存 test が controller entry を要求する。spec 59 Test oracle の原則を継承）。

## Documentation updates

- [x] spec status/history（本 spec。承認時に status を更新）
- [ ] CONTEXT.md — 不要（domain language 追加なし）
- [ ] DESIGN.md — 不要（system structure 変更なし。既存 seam への gate 追加のみ）
- [ ] ADR — 不要（既存契約 AC-59-06 の施行であり、新規の変更困難な判断ではない。
      read-only probe 不採用の理由は spec Non-goals と本 plan に記録済み）
- [ ] AGENTS.md — 不要（検証済み command の必須追加なし。focused run は既存 gradle task の
      組合せであり、恒久必須化は行わない）

## Execution checklist

- [ ] Current behavior reproduced（修正前の focused run で赤を AVD 上で確認）。
- [ ] Existing oracle test fails for the missing behavior（test 変更なしで赤→緑を確認）。
- [ ] Minimal implementation completed（`validatedJournalSourceFile` のみ。4 呼び出し口で
      fail closed を確認）。
- [ ] Migration/recovery verified（該当なし: schema 変更・書込み経路追加なし）。
- [ ] Full relevant verification completed（focused GridMigration 2 class、spotlessCheck、
      assemble、routing 後の CI db-migration lane、layout_write fan-out lane）。
- [ ] PR evidence and remaining risks recorded（red→green 証跡、corrupt file 保全確認、
      CI run link、非対象範囲）。
