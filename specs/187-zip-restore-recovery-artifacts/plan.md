# Implementation Plan: ZIP backup restore時のorganizer recovery artifacts整合

> Issue: #187
> Spec: [spec.md](./spec.md)
> Status: draft（review rev 2対応済み。ADR-0011同時draft済み。承認待ち）

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
| `organizer/application/store/` 新規小helper（例: `RecoveryStartupArtifacts.kt`） | `clearInspectionSnapshot(context): Boolean` 相当の純粋file操作。`noBackupFilesDir/recovery-inspection/` を削除し、**directoryが不在または空になったことを検証して真偽を返す**（検証付き）。`RecoveryInspectionSnapshotReader` の定数を参照し、名前の複製を作らない。`File` 引数の内部関数に分離しunit test可能な形にする |
| `backup/LawnchairBackup.kt` | lease獲得直後・**`prepareForRawFileRestore`（quiesce）より前**にhookを呼び、`false` なら**例外でrestoreを中断する**（hard stop。quiesce・`databases/` wipeへ進まないため、中断時のlauncher状態はrestore開始前と同一） |
| `docs/adr/0011-zip-restore-organizer-recovery-artifacts.md` | 新規ADR（epoch boundary判断、recovery record破棄の明示、中間状態の定義、alternatives）。**本spec/planと同一commitでdraft** |
| `tests/unit/.../store/RecoveryStartupArtifactsTest.kt`（新規） | (a) reconciled相当（DB+snapshot存在）→ cleanup → 両者不在、(b) **failure injection**（snapshot file削除不可を注入 → hook検証失敗 → DB不削除・poison非生成）、(c) 削除順序・分類（`Existing` + snapshot不在 → 次reconcileで再生成可能） |
| `docs/assessment/issue-187-zip-restore-recovery-artifacts.md` | エミュレータでの#153再現手順の解消記録（AC-4） |

- **削除順序と中間状態**: snapshot（検証つき）→ 既存の `databases/` wipe。生じ得る中間状態は
  「DB存在+snapshot不在」のみで、これは**定義済み安全状態**である（classifierは `Existing` と
  分類し、次のstartup reconcileが `rebuildInspectionSnapshot` でsnapshotを再生成 → READY）。
  poison状態（DB不在+snapshot存在）はhard stopと順序により本経路から生じない。
- backup moduleからorganizer内部定数を直接参照しない（layering）。hook関数をorganizer store層の
  public-within-app surfaceとして置き、backupはそれを呼ぶだけにする。

### Data flow

```text
restore(selectedContents)
  acquire BACKUP_RESTORE lease
    organizer: clearInspectionSnapshot + 検証（noBackupFilesDir/recovery-inspection）  ← 追加
      ├─ 検証成功 → 続行
      └─ 検証失敗 → 例外でrestore中断（hard stop。quiesce/wipeへ進まない。状態変更なし）
    prepareForRawFileRestore (model quiesce)
    databases/ deleteRecursively (既存; recovery DB含む)
    grid prefs write / zip read / performRestore / reloadAfterRestore
  lease release → process再起動
    startup reconcile: Pristine → DB作成+snapshot publish → gate READY → compose成功
  （中間状態: cleanup後・wipe前にprocess死 → DB存在+snapshot不在 = 定義済み安全。
    次processは Existing → reconcile → rebuildInspectionSnapshot でsnapshot再生成 → READY）
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
| AC-1 | ADR-0011（epoch boundary判断・recovery record破棄の明示・中間状態定義を含む。本branchに同時commit済み） | — |
| AC-2 | 新規unit test: (a) reconciled→cleanup→Pristine、(b) failure injection（snapshot削除失敗→検証失敗→hard stop・DB不削除・poison非生成）、(c) 削除順序。#153 trigger pinの無変更通過 | `./gradlew testLawnWithQuickstepGithubDebugUnitTest` |
| AC-3 | classifier/reconciler/gateの非変更確認（`git diff`）+ 既存classifier test群の無変更通過 | 同上 |
| AC-4 | エミュレータ `nunu_qpr2_api36_1` で#153手順（reconciled startup → ZIP restore → retry）を実行し、restore後processでpreview到達を確認 → assessment記録 | 手動（エミュレータ） |
| AC-5 | unit分類test（`Existing` + snapshot不在→再生成可能）+ 既存store publish path（`rebuildInspectionSnapshot`）の通過。gapが残る場合はinstrumentation test | 同unit test |
| AC-6 | **high-risk merge gate**: 対象head SHA上の `CI / final-status` 成功、独立audit `docs/assessment/pr-<PR番号>-<slug>.md`、`high-risk-gate` workflow pass。加えて `./gradlew spotlessCheck`、unit全件、`./gradlew assembleLawnWithQuickstepGithubDebug` | CI + 手動 |
| AC-7 | 既存non-write fixture（`OrganizationInputComposerTest` / `ManualOrganizationRunTest` INPUT_NOT_READY系）+ diagnostics negative-redaction fixture（journal/export non-containment系）+ `ModelValidationTest` 等closed集合検証の通過、本実装diffのdiagnostics model非接触確認 | 同unit test |

リスク評価: `LawnchairBackup`（`backup/**`）と `organizer/application/**` は **repository workflow上の
high-risk path**である。labelの有無にかかわらず、実装PRは独立性要件（exact-head CI final-status +
independent audit + `high-risk-gate` workflow）を満たすものとして扱う（AC-6）。DB書込み変更はないが、
restore系の安全pathに触れるためlabel判断はPRで明示する。

### Branch / PR dependency（#153との関係）

- 本branch `docs/issue-187-spec-plan` はmainではなく **#153調査branch `docs/issue-153-spec-plan`
  head `13c54837e7` をparentとするstacked branch** であり、#153のassessment・trigger pin
  （`RecoveryStartupStorageClassifierTest`）を前提とする。
- 承認後の実装は次の順で行う: (1) #153 branch（docs + trigger pin test、低リスク）を先にmainへ
  merge、(2) 本実装branchをmainへrebaseし、**実装PR（main based）のhead SHA** をhigh-risk gate
  （AC-6: exact-head CI + independent audit）の対象とする。stacked PRのまま出さない。

## Documentation updates

- [ ] spec status/history（承認時に `accepted`、完了時に `implemented`）
- [ ] CONTEXT.md — 不要（新domain語なし）
- [ ] DESIGN.md — 不要（§7のpersistence記述に「restoreがrecovery storeをresetする」旨の
      追記が必要かは実装時に判断。ADR-0011へ委譲する場合は不要）
- [x] ADR-0011（AC-1。本spec/planと同一commitでdraft作成済み。review後に `accepted` へ更新）
- [ ] AGENTS.md — 検証command追加なし

## Execution checklist

- [ ] Spec review・承認（statusを `accepted` へ更新。ADR-0011のreview確認を含む）。
- [ ] `RecoveryStartupArtifacts` helper（検証つきcleanup）+ unit test
      （AC-2: 状態遷移/failure injection/削除順序、AC-5: `Existing`中間状態分類）を実装。
- [ ] `LawnchairBackup.restore()` への呼出し追加（quiesce前hard stop）。
- [ ] 既存classifier test群の無変更通過・`spotlessCheck`・unit全件・debug build（AC-3/AC-6/AC-7）。
- [ ] エミュレータでの#153手順解消を実測しassessmentへ記録（AC-4）。
- [ ] #153 branchを先にmainへmergeし、本実装をmain basedで出す（planのBranch dependency）。
- [ ] 実装PRでexact-head `CI / final-status` + 独立audit `docs/assessment/pr-<n>-<slug>.md` +
      `high-risk-gate` pass（AC-6）。
- [ ] PR本文へ検証結果を記録。AC-7（non-write/redaction/schema不変）の確認をもって
      #153 AC-6を充足し、#153 close判断につなげる。
