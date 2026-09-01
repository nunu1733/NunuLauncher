# ADR-0011: ZIP backup restore時のorganizer recovery artifacts扱い(recovery epoch boundary)

Status: accepted (spec/plan approved alongside; call-site audit to be re-verified in the implementation PR assessment)

Date: 2026-09-01

Related: [Issue #187](https://github.com/nunu1733/NunuLauncher/issues/187) (focused fix of
[Issue #153](https://github.com/nunu1733/NunuLauncher/issues/153)),
[ADR-0003](0003-organizer-recovery-point-storage.md) (recovery storage),
[ADR-0009](0009-chunk-recovery-manifests.md) (recovery format v2),
Issue #89 (inspection snapshot fail-closed invariants), #171 Stage D, #58 (BACKUP_RESTORE lease)

## Context

`LawnchairBackup.restore()` はBACKUP_RESTORE lease内で
`getDatabasePath(LAUNCHER_DB_FILE_NAME).parentFile?.deleteRecursively()` により `databases/`
全体を削除する。この巻き添えでorganizer recovery DB（全recovery recordを含む）が削除される一方、
`no_backup/recovery-inspection/`（startup reconciliationがpublishするinspection snapshot）は
残存する。結果、restore後の全processで `RecoveryStartupStorageClassifier` が `SuspiciousAbsence`
→ `READ_FAILED` を返し、startup reconciliationはSQLite open以前に失敗してgateが恒久にFAILEDと
なり、全manual全体整理が `NotReady(RECONCILIATION_FAILED)` で不能になる
（[assessment](../assessment/issue-153-zip-restore-notready-root-cause.md)、因果実験×2で実証）。

判断を必要とする論点は3つ:

1. 残留snapshotを誰が消すか（producer側のrestoreか、classifier側のhealingか）。
2. restoreがrecovery recordを破棄することをどう位置づけるか（undocumentedな巻き添えか、
   意図的なepoch境界か）。
3. restore中にprocess死した場合の中間状態（snapshot不在+DB存在 等）をどう扱うか。

## Decision

1. **Colocated cleanup（検証つき・hard stop・serializationつき）**: `databases/` wipeと同一lease区間で、かつ
   **`prepareForRawFileRestore`（model quiesce）より前に**、organizer側のcleanup hookが
   `no_backup/recovery-inspection/` を削除し、**directoryが不在または空になったことを検証する**。
   検証に失敗した場合（削除不可等）、**何も変更せずrestoreを失敗させる**（quiesceにも
   `databases/` wipeにも進まない）。これにより「DB不在+snapshot存在」のpoison状態をcleanup
   失敗から再生する経路を型として断つ。cleanupはbest-effortではなく検証付きであり、失敗時の
   復旧はユーザーの再試行（または手動除去）に委ねられる。
2. **Serialization契約（republication raceの遮断）**: cleanup検証成功から `databases/` wipe完了までの
   区間を、**organizer application moduleのoperation mutex（reconciliation/apply/recoverと同一の
   `RunMutex`）の排他保持下で実行する**。これにより当該区間ではinspection snapshotの全publisher
   （`rebuildInspectionSnapshot` を呼ぶ全経路はmodule mutex下のoperationである）が介入できない。
   - 進入前にin-flight operationを完了待ちするため、直前にpublishされたsnapshotはcleanupが
     必ず消す。
   - 保持中の `reconcileAtStart` はmutex `tryAcquire` 失敗で即時returnし、**gate状態を変更しない**
     （失敗は既存の競合扱い。`ReadinessGate` へのFAILED書込みはない）。
   - **デッドロック不存在の根拠（lock graph・call site audit）**: `LayoutWriteCoordinator` には
     blocking取得（`acquireBlocking` / `acquireBlockingQuietly`）が実在するため、「coordinator全体が
     nonblocking」は根拠にならない。根拠は**module mutexを保持するproduction経路がcoordinatorの
     blocking取得を行わない**ことにある:
     - ApplyProtocol / RecoveryProtocol / RestartReconciler のcoordinator取得は、writer seam
       （`LauncherLayoutAdapter.tryAcquireLease` → `tryAcquire(ORGANIZER)`、非blocking、競合時は
       typed failure）と、既取得capability tokenによるreentry
       （`ModelDbController.newTransaction(organizerToken)` → `tryAcquireOrganizerLease`、非blocking、
       不一致時はthrow）に限定される。capture（`capture()`）は `controller.db` の直接読取で
       coordinatorを取得しない。locks系（`LockStateDbAdapter`）も非blocking `tryAcquire(ORGANIZER)`。
     - `acquireBlockingQuietly` の実在call siteは、LawnchairBackup / NovaBackupConverter
       （BACKUP_RESTORE。module mutex取得前に取得）、RestoreDbTask（RESTORE。restore-family lease
       保持下のreentry）、GridSizeMigrationUtil と ModelDbController（GRID_MIGRATION）および
       `getCoordinatorLease` のMODEL_WRITER fallback（launcher側mutation経路。organizer操作は
       `newTransaction(organizerToken)` を使用し到達しない）である。いずれもmodule mutexを保持した
       状態では実行されない。したがって「module mutex → coordinator blocking wait」の逆順は
       形成されず、循環待ちは存在しない。
     - 実装時に全call siteの再調査（grep audit）をassessmentへ記録し、lock-order drain test
       （AC-2(d)）で機械的に担保する。
   - 保持区間は短い（cleanup file操作・quiesce・`deleteRecursively`）。zip読込等の長いIOは
     区間外である。
   - 契約範囲の明確化: module mutexはrecovery mutation/reconciliationを直列化する。manual composeの
     capture（`writer.captureCurrent()` 経路）はmodule mutexを通らず、restore区間中も読取として
     動作し得る。captureは読取専用かつsnapshot publisherではないため、本契約の目的
     （republication遮断）には影響しない。
3. **Recovery epoch boundary**: ZIP backup restoreをorganizer recoveryの**epoch境界**として扱い、
   restoreはpre-restore recovery pointsを**互換性の如何にかかわらず意図的にinvalidate（破棄）する**。
   これは新たなデータ損失ではない——現行実装は既に `databases/` wipeでrecovery DBを削除している
   （de-factoの巻き添え）——が、本決定により「restoreがorganizer recoveryをresetする」ことが
   文書化された意図的挙動となる。根拠:
   - restoreはlauncher状態（layout DB、prefs）をbackup由来の別stateへ wholesale に置換する。
     pre-restore pointsは置換対象workspaceをtargetにしており、restore後の意味ある復元対象ではない。
   - 仮にZIPが同一の有効workspace状態を復元した場合でも（revision/contextが一致し得るケース）、
     epoch boundaryとしてのinvalidationは偶然の一致に依存しない明示的な判断であり、
     「部分的に復旧できるかも知れないstore」を残さないことで状態空間を単純に保つ。
   - #171 Stage D（「authoritative external restore alone is not a reason to pre-invalidate a
     VERIFIED point」）は**Nova等の外部restoreがlauncher状態を置換しない**ケースの判断である。
     Lawnchair ZIP restoreはlauncher状態そのものを置換するため、前提が異なり矛盾しない。
4. **Classifier不変**: `RecoveryStartupStorageClassifier` / `RestartReconciler` / `ReadinessGate`
   の分類規則・fail-closed挙動は変更しない。本欠陥はproducer（restore）側のartifact管理の欠陥
   であり、検出器（classifier）の規則は正しく機能していた。

### 中間状態の定義

削除順序は「snapshot（検証つき）→ `databases/` wipe」であり、当該区間はserialization契約
（Decision 2: module mutex排他）によりsnapshot publisherの介入から閉じられている。restore中の
任意時点の状態は次のいずれかに限定され、いずれも定義済み安全状態である:

| 状態 | 意味 | 次processの挙動 |
|---|---|---|
| DB存在 + snapshot存在 | cleanup前、またはpublish後の正常状態 | `Existing` → reconcile継続、必要ならsnapshot再publish |
| DB存在 + snapshot不在 | cleanup後・wipe前の中間状態（**定義済み安全**） | `Existing` → reconcile継続、`rebuildInspectionSnapshot` がsnapshotを再生成 → READY |
| DB不在 + snapshot不在 | restore完了後（Pristine） | reconcile成功、両者を再作成 → READY |
| DB不在 + snapshot存在 | **poison状態**。cleanup検証のhard stop、削除順序、およびDecision 2のserialization契約により本経路では生じない | classifier fail-closed（現行のまま） |

## Alternatives considered

- **Classifier/reconciliation側のhealing rule**（「DB不在+snapshot存在」をpruneしてPristineへ）:
  実装差分は小さいが、Issue #89のfail-closed inventory検査をproducer欠陥のために永続的に弱める。
  healingが正しいのは本caseだけであり、原因はproducer側で閉じるべき。不採用。
- **Recovery DB保存（`databases/` wipeからのorganizer file除外）**: 保存したrecordは旧workspaceを
  targetにするが、「常にNotRestorable」ではない（ZIPが同一有効workspaceを復元すれば一致し得る）。
  それでも採用しない理由は、epoch boundaryとしての意図的invalidationの方が偶然の一致に依存せず、
  restore後のstore状態空間（常にPristine起点）が単純になるため。recovery-across-restoreが将来
  製品要件になった場合は、この決定を置換するproduct decisionとして再検討する。
- **Hybrid（cleanup+healing併用）**: cleanup単独でpoison状態の発生を断てるため、healingの追加は
  fail-closedの弱めにしかならない。不採用。
- **Cleanup後のwipeを同一mutex区間にせず、時間窓だけ頼る**（「通常はstartup reconciliationが
  先に終わる」）: 排他ではなく実行順の期待に過ぎず、process死・IO遅延・loader遅延でrepublication
  がwipeと入れ替わりpoison状態を再生し得る。本決定のserialization契約で排除した。不採用。

## Consequences

- restore後の最初のstartup reconciliationは必ずPristine経路で成功し、DBとsnapshotを再作成する。
  #153の恒久NotReadyは解消される。
- restoreによってpre-restore recovery pointsは失われる。ユーザーがrestore前に作成したrecovery
  pointでrestore後のlayoutを復元することはできない（restore対象のlayoutがそのrecovery pointの
  pre-stateではないため、意味ある復元対象でもない）。このトレードオフはSettings上の
  backup/restore説明とADR-0003の位置づけ（recovery storeはexport backup対象外のprivate store）
  と整合する。
- cleanup検証失敗時はrestoreが失敗する（新挙動）。失敗時のlauncher状態はrestore開始前と同一
  （quiesce前_abort）であり、再試行可能である。
- classifierのfail-closed分類は将来にわたり、genuinely suspiciousな状態（部分削除、corrupt DB、
  未知file、DB不在+snapshot存在のpoison）に対して変更なく機能する。
