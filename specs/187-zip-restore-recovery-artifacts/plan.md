# Implementation Plan: ZIP backup restore時のorganizer recovery artifacts整合

> Issue: #187
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

確認済みの事実（現行main `667e8915f2` 時点）:

- **欠陥の実証**: #153 assessment（[issue-153-zip-restore-notready-root-cause.md](../../docs/assessment/issue-153-zip-restore-notready-root-cause.md)）
  のとおり。restoreが `databases/` 全体を削除（recovery DB巻き込み）しつつ
  `no_backup/recovery-inspection/` を残留させ、以後の全processでclassifier `SuspiciousAbsence`
  → `READ_FAILED` → gate FAILEDが恒久化する。因果実験（snapshot削除→即解消）を2 headで実施済み。
- **restore経路の構造**（[LawnchairBackup.kt:79-93](../../lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt)）:
  `LayoutWriteCoordinator` のBACKUP_RESTORE lease内で
  `RestoreDbTask.prepareForRawFileRestore(context)`（organizer有無でno-opする#58の
  nullable-app pattern、[RestoreDbTask.java:249-266](../../src/com/android/launcher3/provider/RestoreDbTask.java)）
  → `databases/` `deleteRecursively()` → grid prefs書込み → zip読込 → `performRestore` /
  `reloadAfterRestore`。cleanup hookの追加位置は `deleteRecursively()` と同区間。
- **snapshot pathの正本**（[RecoveryInspectionSnapshotPublisher.kt:44-54](../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshotPublisher.kt)）:
  `noBackupFilesDir/recovery-inspection/` + `recovery-inspection.v1`。定数は
  `RecoveryInspectionSnapshotReader.DIRECTORY_NAME` / `FINAL_FILE_NAME`（companion const）。
  backup側でこの名前を複製しない——organizer module側のcleanup hook経由で参照する。
- **classifierの現行分類**（[RecoveryStartupStorageClassifier.kt](../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStartupStorageClassifier.kt)）:
  DB不在+snapshot非空 → `SuspiciousAbsence`。両者不在 → `Pristine`。既存unit test
  （`RecoveryStartupStorageClassifierTest`、#153でtrigger pin追加済み）が分類を固定している。
  本fixではclassifierを変更しない。
- **ADR-0003**（[0003-organizer-recovery-point-storage.md](../../docs/adr/0003-organizer-recovery-point-storage.md)）:
  recovery storeはZIP backup/Android backupから除外されるprivate store。restoreとの
 相互作用の規定はなく、本specがADR-0011で初めて明示する。
- 前回ADRはADR-0010（#185）まで。新規はADR-0011。

## Design

### Modules and interfaces

| Module | 変更 |
|---|---|
| `organizer/application/store/` 新規小helper（例: `RecoveryStartupArtifacts.kt`） | `clearInspectionSnapshot(context)` 相当の純粋file操作（`noBackupFilesDir/recovery-inspection/` の冪等削除）。`RecoveryInspectionSnapshotReader` の定数を参照し、名前の複製を作らない。unit test可能な形（`File` 引数の内部関数 + context wrapper） |
| `backup/LawnchairBackup.kt` | `databases/` wipeの直前に `clearInspectionSnapshot` 呼出しを追加（colocation: 同一lease区間・同一条件）。#58のnullable-app patternにならうorganizer不在時のno-op考慮は不要（helper自身がfile操作のみ） |
| `docs/adr/0011-zip-restore-organizer-recovery-artifacts.md` | 新規ADR（採用判断、recovery record破棄の明示、#171 Stage D / #89との整合） |
| `tests/unit/.../RecoveryStartupStorageClassifierTest.kt`（または隣接file） | artifact状態遷移test: reconciled startup相当（DB+snapshot存在）→ cleanup → 両者不在=Pristine分類。削除順序（snapshot先→databases/後）の担保を表現するtest |
| `docs/assessment/issue-187-zip-restore-recovery-artifacts.md` | エミュレータでの#153再現手順の解消記録（AC-4） |

- **削除順序**: snapshot directory → 既存の `databases/` wipe。これによりrestore中の任意時点で
  snapshot不在+DB存在（= 現行でも生じ得る正常な一時組合せ。次のpublishまで
  `readInspectionProjection` がfence経由で保守）または両者不在に限定され、
  「DB不在+snapshot存在」という未定義組合せを新たに作らない。
- backup moduleからorganizer内部定数を直接参照しない（layering）。hook関数をorganizer store層の
  public-within-app surfaceとして置き、backupはそれを呼ぶだけにする。

### Data flow

```text
restore(selectedContents)
  acquire BACKUP_RESTORE lease
    prepareForRawFileRestore (model quiesce)
    organizer: clearInspectionSnapshot(noBackupFilesDir/recovery-inspection)   ← 追加
    databases/ deleteRecursively (既存; recovery DB含む)
    grid prefs write / zip read / performRestore / reloadAfterRestore
  lease release → process再起動
    startup reconcile: Pristine → DB作成+snapshot publish → gate READY → compose成功
```

### Alternatives rejected

specのDecision節どおり。実装観点の補足:

- **classifier healing**: 実装差分は小さいが、#89のfail-closed inventory検査をproducer欠陥の
  ために弱めるため不採用。
- **recovery DB保存（wipeからの除外）**: `deleteRecursively` に除外規則を入れる変更が大きく、
  保存してもrecordはrequest時preconditionでNotRestorable。将来のproduct decisionとしてADR-0011
  のalternativesに記録。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/store/RecoveryStartupArtifacts.kt` | cleanup hook（新規、純粋file操作） | 定数の正本がorganizer側にあるため |
| `lawnchair/src/app/lawnchair/backup/LawnchairBackup.kt` | hook呼出し追加（1行相当） | databases/ wipeとのcolocationの正本位置 |
| `tests/unit/app/lawnchair/organizer/application/store/RecoveryStartupArtifactsTest.kt` | 状態遷移test（新規） | hookの挙動固定（AC-2/AC-5） |
| `docs/adr/0011-zip-restore-organizer-recovery-artifacts.md` | ADR（新規） | AC-1 |
| `docs/assessment/issue-187-zip-restore-recovery-artifacts.md` | 実測記録（新規） | AC-4 |

## Migration and recovery

- schema/rule migration: none。
- failure中のrollback: cleanupは冪等削除のみ。restore自体のrollback性（#58契約）は不変。
- release rollback/downgrade: source rollbackで元に戻る。データ変換なし。
- backup/restore compatibility: backup format・allowlist変更なし。organizer recovery storeは
  引き続きbackup対象外（ADR-0003）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | ADR-0011 | — |
| AC-2 | 新規unit test（reconciled→cleanup→Pristine）+ #153 trigger pinの無変更通過 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest` |
| AC-3 | classifier/reconciler/gateの非変更確認（`git diff`）+ 既存classifier test群の無変更通過 | 同上 |
| AC-4 | エミュレータ `nunu_qpr2_api36_1` で#153手順（reconciled startup → ZIP restore → retry）を実行し、restore後processでpreview到達を確認 → assessment記録 | 手動（エミュレータ） |
| AC-5 | 削除順序・中間状態のunit test | 同unit test |
| AC-6 | `./gradlew spotlessCheck`、unit全件、`./gradlew assembleLawnWithQuickstepGithubDebug` | CI/手動 |

リスク評価: `LawnchairBackup` はrestore系の重要pathだが、変更はorganizer hook呼出し1行相当で
ある。recovery recordの破棄は現行de-facto挙動の明示化であり、挙動変化はsnapshot削除のみ。
`risk: layout-data` 該当性はPRで明示判断する（DB書込み変更はないため非該当の見込み）。

## Documentation updates

- [ ] spec status/history（承認時に `accepted`、完了時に `implemented`）
- [ ] CONTEXT.md — 不要（新domain語なし）
- [ ] DESIGN.md — 不要（§7のpersistence記述に「restoreがrecovery storeをresetする」旨の
      追記が必要かは実装時に判断。ADR-0011へ委譲する場合は不要）
- [x] ADR-0011（AC-1）
- [ ] AGENTS.md — 検証command追加なし

## Execution checklist

- [ ] Spec review・承認（statusを `accepted` へ更新）。
- [ ] `RecoveryStartupArtifacts` helper + unit test（AC-2/AC-5）を実装。
- [ ] `LawnchairBackup.restore()` への呼出し追加（AC-2、colocation）。
- [ ] ADR-0011作成（AC-1）。
- [ ] 既存classifier test群の無変更通過・`spotlessCheck`・unit全件・debug build（AC-3/AC-6）。
- [ ] エミュレータでの#153手順解消を実測しassessmentへ記録（AC-4）。
- [ ] PR本文へ検証結果を記録。#153 AC-6のfix側検証としてnon-write/redaction維持を確認し、
      #153 close判断につなげる。
