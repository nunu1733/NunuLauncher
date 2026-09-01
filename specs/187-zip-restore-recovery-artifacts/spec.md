---
issue: "#187"
status: draft
requirements: []
updated: 2026-09-01
---

# ZIP backup restoreがorganizer recovery artifactsを矛盾なくresetする

[Issue #187](https://github.com/nunu1733/NunuLauncher/issues/187) は
[Issue #153](https://github.com/nunu1733/NunuLauncher/issues/153) のfocused fix issueである
（diagnosis evidence: [assessment](../../docs/assessment/issue-153-zip-restore-notready-root-cause.md)）。

## Problem

Lawnchair ZIP backup restoreは `LawnchairBackup.restore()` 内で
`getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile?.deleteRecursively()` により `databases/`
全体を削除する。これにはorganizer recovery DB（`databases/organizer_recovery.db`）が巻き込まれる一方、
restore前にstartup reconciliationが成功していたprocessがpublishしたinspection snapshot
（`no_backup/recovery-inspection/`）は残存する。以後の全processで
`RecoveryStartupStorageClassifier` が `SuspiciousAbsence` を返し、`startupAvailability()` は
`READ_FAILED`、`RestartReconciler.reconcileAll` はSQLite open以前のavailability分岐で失敗して
DBを再作成できず、`ReadinessGate` が恒久に `FAILED` となる。結果、全manual全体整理が
`NotReady(RECONCILIATION_FAILED)`（journal: `INPUT_NOT_READY / INPUT_READINESS.RECONCILIATION_FAILED`）
で永続的に失敗し、app data消去またはsnapshotの手動削除以外に回復経路がない
（emulator実証: heads `50ddb86148` / `667e8915f2` で再現、snapshot削除で即解消 ×2）。

## Outcome

ZIP backup restoreの後、organizer recovery storeのartifact状態が**矛盾のない単一状態**
（DBとinspection snapshotが同時に存在するか、同時に不在＝Pristine）に保たれる。restore後の
新規processでstartup reconciliationが成功し、manual全体整理がpreviewへ到達する。
#153で確定した再現手順が解消する。classifierのfail-closed分類規則は変更しない。

## Decision(本specの中心判断)

diagnosisの3候補に加えて比較のため1候補を追加し、**候補1（restoreと同一区間でのstale
inspection snapshot削除）**を採用する。判断の正本は [ADR-0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md)
（本specの受入条件として作成）とする。

1. **採用: colocated cleanup。** `databases/` wipeと同一のlease区間・同一条件で
   `no_backup/recovery-inspection/` を削除し、restore後のartifact状態をPristineへ揃える。
   - 現行のrestoreは既にrecovery DB（＝全recovery record）を巻き添えで削除している。snapshotだけが
     取り残されている状態は、restoreが本来意図する「launcher状態の全置換」と矛盾する残留物であり、
     それを消すだけである。
   - classifier（Issue #89のfail-closed設計）は一切変更しない。SuspiciousAbsenceのfail-closed性は、
     今後も genuinely suspicious な状態（部分削除、corrupt DB、未知file）に対して維持される。
   - 適用対象はsnapshot directory全体（`recovery-inspection.v1` およびAtomicFileの一時file）。
2. **却下: classifier/reconciliation側のhealing rule。** 「DB不在+snapshot存在」をpruneして
   Pristineへ復帰する規則は、Issue #89が意図したstartup時のfail-closed inventory検査を
   producer側の欠陥のために弱める。healingが安全なのは本case（restoreがDBを消した）に限られ、
   その原因はproducer側で閉じるべきである。
3. **却下: recovery DBの保存（`databases/` wipeからのorganizer file除外）。** restore後の
   workspaceはbackup由来の別stateであり、旧workspaceをtargetにしたrecovery recordは
   request時のfail-closed precondition（STALE_REVISION / ContextMismatch）で実質NotRestorable
   である（[ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md)、#171 Stage D）。
   保存する価値が現れない上に、restoreのfile操作にorganizer固有の除外規則を追加する変更が大きい。
   「restoreがorganizer recovery recordを破棄する」という現行のde-facto挙動をADR-0011に明示的に
   記録し、将来recovery-across-restoreが製品要件になった場合のproduct decision候補として残す。

## Scope

- `LawnchairBackup.restore()` の `databases/` wipeと同一区間（BACKUP_RESTORE lease内）に、
  organizer側の小さなcleanup hook（例: organizer store層の
  `RecoveryStartupArtifacts.clearInspectionSnapshot(context)` 相当）を追加し、
  `no_backup/recovery-inspection/` を削除する。hookは `RecoveryInspectionSnapshotReader` の
  定数名の重複を作らず、organizer module経由で呼ぶ。
- cleanupは `databases/` wipeと**常に同じ条件で**走る（colocation）。wallpaper-only restoreで
  `databases/` wipeが走る現行挙動が変わらない限り、cleanupもこれに従う（既存挙動の変更は
  本specのnon-goal）。
- restore完了後の次processでstartup reconciliationが成功することのregression test
  （owning seam level）と、エミュレータでの#153再現手順の解消記録。
- [ADR-0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md) の新規作成
  （採用・却下候補、「restoreがorganizer recovery recordを破棄する」ことの明示）。

## Non-goals

- `RecoveryStartupStorageClassifier` / `RestartReconciler` / `ReadinessGate` の分類規則・
  fail-closed挙動の変更。
- restore時のrecovery record保全（候補3）。将来のproduct decisionとする。
- `LawnchairBackup.restore()` の既存挙動変更（wallpaper-only時の `databases/` wipeを含む）、
  backup format・allowlistの変更、#58 BACKUP_RESTORE lease契約の変更。
- diagnostics契約・journal・#172 surfaceの変更。
- Nova restore経路（#168/#185所有）、onboarding/incremental flowの変更。
- #153で分離記録した `historical discrepancy / unresolved`（元episodeの自然解消理由）の解消。
  本issueはreproduced persistent defectのみを対象とする。

## Domain language

- **recovery artifact矛盾状態**: recovery DBとinspection snapshotの存在/不在が一致しない状態
  （本件: DB不在+snapshot存在）。classifierが `SuspiciousAbsence` として検出する状態。
  `CONTEXT.md` への追加は不要（既存のstartup classifier概念の派生語）。

## Behavior scenarios

### Scenario: #153の再現手順が解消する

**Given** reconciled startup（organizer recovery DB作成済み+inspection snapshot publish済み）の後に
ZIP backup restoreを実行した。
**When** restore後の新規processでmanual全体整理のstartを実行する。
**Then** startup reconciliationが成功し（gate READY）、composeはpreview（または既存契約のtyped結果）
へ到達する。
**And** restore直後のon-disk状態はPristine相当（recovery DB不在、snapshot directory不在）であり、
最初のstartup reconciliationで両者が再作成される。

### Scenario: cleanupはdatabases/ wipeと同一条件で走る

**Given** ZIP restoreが `INCLUDE_LAYOUT_AND_SETTINGS` を含むcontentsで実行される。
**When** restoreの `databases/` wipeが実行される。
**Then** 同一lease区間でinspection snapshot directoryも削除され、artifact矛盾状態が生じない。
**And** cleanupの失敗（file削除不可等）がrestoreを失敗させない。ただしcleanup自体はbest-effortの
冪等削除であり、失敗時も既存のclassifier fail-closedが後段の安全を担保する（現行と同様）。

### Scenario: genuinely suspicious状態は引き続きfail-closed

**Given** 部分削除・corrupt DB・ `-wal` 残留など、DB不在以外の疑わしいartifact状態が存在する。
**When** startup reconciliationが実行される。
**Then** `RecoveryStartupStorageClassifier` の現行分類（`SuspiciousAbsence` / `ZeroLengthMain` /
`InvalidMain` / `UnreadableInventory` → `READ_FAILED`）は不変であり、変更されていないことが
既存unit test群の無変更通過で検証される。

### Scenario: restoreがrecovery recordを破棄することの明示

**Given** 非finalまたはfinalのrecovery recordが存在する状態でZIP backup restoreが実行される。
**When** restoreが完了する。
**Then** organizer recovery recordは現行どおり破棄される（DB巻き込み）。この挙動は
[ADR-0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md) に明示的に記録され、
undocumentedな巻き添え削除ではなくなる。
**And** restore後のsnapshot/DB状態は矛盾しないため、#171で観測された種類のstore poisoning
（読めない残留rowによる恒久失敗）は本経路では発生しない。

### Scenario: restore失敗中のrollback

**Given** restore中にprocess死やIO失敗が発生する。
**When** 次のprocessのstartup reconciliationが実行される。
**Then** artifact状態がDB存在+snapshot存在なら現行どおりreconcileが進み、DB不在+snapshot不在なら
Pristineとして成功する。
**And** 本fixは新しい中間状態（snapshot不在+DB存在等の未定義組合せ）を生まない
（削除順序: snapshot先に削除してから既存の `databases/` wipe。これにより中間状態は
「DB存在+snapshot不在」または「両方不在」に限定される）。

## Data and state

- 永続化の追加はnone。削除対象は `no_backup/recovery-inspection/` のみで、organizer diagnostics
  journal（`files/organizer_diagnostics/`、#105でbackup除外済み）には触れない。
- recovery recordの破棄は現行de-facto挙動の明示化であり、追加のデータ損失を生まない
  （ADR-0011に記録）。
- layout DB、backup format、recovery format v2（chunked manifest、ADR-0009）、revision/digest計算
  への影響はnone。schema/rule migrationは不要。source rollbackで元に戻る。

## Permissions, privacy, and security

**None。** permission・network・telemetryの追加なし。file操作はapp-private directory内のみ。

## Accessibility and localization

- 新しいuser-facing文字列・UIは追加しない。

## Acceptance criteria

- [ ] **AC-1 — 決定の記録:** 採用候補（colocated cleanup）と却下候補（classifier healing、
  recovery DB保存）の評価が [ADR-0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md)
  に記録され、「restoreがorganizer recovery recordを破棄する」de-facto挙動の明示化を含む。
  本specのDecision節と矛盾しない。
- [ ] **AC-2 — トリガpinの解消:** #153で追加した
  `RecoveryStartupStorageClassifierTest.zipRestoreLeavesRecoveryDbDeletedWithPublishedSnapshotSuspiciousAbsence`
  はclassifier自体のcharacterizationとして不変のまま維持され、新たに**restore artifact状態遷移**
  （reconciled startup → restore相当のcleanup → 両artifact不在=Pristine）を検証するtestが
  追加される。cleanup hookはorganizer moduleの純粋file操作としてunit test可能な形にする。
- [ ] **AC-3 — classifier不変:** `RecoveryStartupStorageClassifierTest` の既存test群（genuinely
  suspicious状態の分類）が無変更で通過し、classifier・reconciler・gateのコードdiffがない。
- [ ] **AC-4 — #153再現手順の解消:** accepted assessment記録の再現手順（reconciled startup →
  ZIP restore → retry）をemulator `nunu_qpr2_api36_1` で実行し、restore後のprocessで
  reconciliation成功とpreview到達を確認する。結果を `docs/assessment/issue-187-zip-restore-recovery-artifacts.md`
  に記録する。
- [ ] **AC-5 — 非干渉:** restore失敗中の状態（中間状態）が未定義組合せを生まないこと
  （削除順序のtest、または同値のunit test）。journal・recovery format・backup formatへの
  非影響を既存test群の無変更通過で確認する。
- [ ] **AC-6 —  通常検証:** `spotlessCheck`、unit test全件、`assembleLawnWithQuickstepGithubDebug`
  が成功する。PRに検証結果を記録する。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | ADR-0011本体 |
| AC-2 | 新規unit test（artifact状態遷移: reconciled → cleanup → Pristine）+ 既存trigger pinの無変更通過 |
| AC-3 | `RecoveryStartupStorageClassifierTest` 既存test群の無変更通過、`git diff` でclassifier/reconciler/gate非変更の確認 |
| AC-4 | エミュレータでの再現手順実行記録（`docs/assessment/issue-187-zip-restore-recovery-artifacts.md`） |
| AC-5 | unit test（削除順序/中間状態）または同値の確認記録 |
| AC-6 | `./gradlew spotlessCheck` `./gradlew testLawnWithQuickstepGithubDebugUnitTest` `./gradlew assembleLawnWithQuickstepGithubDebug` |

## Open questions

なし。採用判断（候補1）の前提事実は次のとおり確認済みである:

- `LawnchairBackup.restore()` の `databases/` wipeは現行でrecovery DBを含む全DBを削除しており、
  recovery recordは既に破棄されている（de-facto）。snapshotのみが `no_backup/` に残留する。
- classifierの `SuspiciousAbsence` はDB不在+inventory非空の分類であり、両者不在のPristineは成功経路
  （H0 baseline、因果実験後のH3/H4で実証済み）。
- #153で分離したhistorical discrepancyは本issueの対象外であり、reproduced persistent defectのみを
  fixする。

## Change history

- 2026-09-01: #153 diagnosis（assessment `issue-153-zip-restore-notready-root-cause.md`、
  investigation review承認済み）に基づきdraftを作成。#187本文の3候補に保存候補を加えた4案を評価し、
  classifier不変のcolocated cleanupを採用案として起草。
