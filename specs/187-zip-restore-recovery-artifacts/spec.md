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

diagnosis assessmentで確定した欠陥に対し、#187本文の候補（cleanup / classifier healing /
hybrid）にrecovery DB保存案を加えた**計4案**を評価し、**候補1（restoreと同一lease区間での
colocated cleanup。検証つき・失敗時は `databases/` wipe前のhard stop）**を採用する。併せて、
**ZIP backup restoreをrecovery epoch boundaryとして扱い、pre-restore recovery pointsを
互換性の如何にかかわらず意図的にinvalidateする**というデータ安全上の判断を下す。
判断の正本は [ADR-0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md)
（本spec/planと同時にdraftし、review後にacceptedへ）とする。

1. **採用: colocated cleanup（検証つき・hard stop・serializationつき）。** cleanup（`no_backup/recovery-inspection/`
   の削除）とその**検証（directoryが不在または空になったこと）**を、
   `prepareForRawFileRestore`（model quiesce）より前に実施する。検証に失敗した場合（削除不可等）、
   **launcher状態を一切変更せずrestoreを失敗させる**（quiesce・`databases/` wipeへ進まない）。
   cleanupをbest-effortにすると、削除失敗+DB wipe成功が本欠陥のpoison状態
   （DB不在+snapshot存在 → `SuspiciousAbsence` → gate FAILED恒久化）をそのまま再生成するため、
   失敗時の進行を許さない。hookは `RecoveryInspectionSnapshotReader` の定数名の重複を作らず、
   organizer module経由で呼ぶ。
   加えて、**cleanup検証成功から `databases/` wipe完了までの区間を、organizer application
   moduleのoperation mutex（reconciliation/apply/recoverと同一の `RunMutex`）の排他保持下で
   実行する**（serialization契約）。`rebuildInspectionSnapshot` を呼ぶ全publisher経路は
   module mutex下のoperationであるため、当該区間へsnapshot publicationは割り込めない。
   進入前にin-flight operationを完了待ちし（直前のpublishはcleanupが必ず消す）、保持中の
   `reconcileAtStart` はmutex `tryAcquire` 失敗で即時returnし**gate状態を変更しない**
   （既存の競合扱い）。**デッドロック不存在の根拠はlock graph audit**である: module mutexを
   保持するproduction経路（apply/recovery/reconciler）のcoordinator取得は非blocking
   `tryAcquire`（`LauncherLayoutAdapter.tryAcquireLease`、`LockStateDbAdapter`）または既取得
   capability tokenのreentry（`ModelDbController.newTransaction(organizerToken)`、非blocking）に
   限定され、`acquireBlockingQuietly` のcall site（BACKUP_RESTORE/RESTORE/GRID_MIGRATION/
   MODEL_WRITER fallback）はいずれもmodule mutexを保持しない経路でのみ実行される。
   実装時に全call siteの再調査をassessmentへ記録し、lock-order drain test（AC-2(d)）で
   機械的に担保する。
2. **却下: classifier/reconciliation側のhealing rule。** 「DB不在+snapshot存在」をpruneして
   Pristineへ復帰する規則は、Issue #89が意図したstartup時のfail-closed inventory検査を
   producer側の欠陥のために永続的に弱める。healingが正しいのは本caseだけであり、原因は
   producer側で閉じるべきである。
3. **却下: recovery DB保存（`databases/` wipeからのorganizer file除外）。** 保存したrecordは
   旧workspaceをtargetにするが、「常にNotRestorable」であるとは断定できない（ZIPが同一の有効
   workspace状態を復元した場合、revision/contextが一致し得る）。それでも採用しない理由は、
   epoch boundaryとしての意図的なinvalidationの方が偶然の一致に依存せず、restore後のstore状態
   （常にPristine起点）を単純に保てるためである。recovery-across-restoreが将来製品要件になった
   場合は、本決定を置換するproduct decisionとして再検討する。#171 Stage D（外部restoreでは
   VERIFIED pointを事前無効化しない）は、launcher状態を置換しない外部restoreが前提であり、
   launcher状態そのものを置換するLawnchair ZIP restoreとは前提が異なるため矛盾しない。
4. **却下: hybrid（cleanup+healing併用）。** 検証つきcleanup単独でpoison状態の発生を断てるため、
   healingの追加は#89 fail-closedの弱めにしかならない。

## Scope

- `LawnchairBackup.restore()` の `databases/` wipeと同一lease区間（BACKUP_RESTORE lease内）、かつ
  **`prepareForRawFileRestore`（model quiesce）より前**に、organizer側の小さなcleanup hook
  （例: organizer store層の `RecoveryStartupArtifacts` 相当）で
  `no_backup/recovery-inspection/` を削除し、**directoryが不在または空になったことを検証する**。
  検証失敗時は何も変更せずrestoreを失敗させる（hard stop）。hookは `RecoveryInspectionSnapshotReader`
  の定数名の重複を作らず、organizer module経由で呼ぶ。
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

### Scenario: cleanupと検証はquiesce前・同一lease区間で走る

**Given** ZIP restoreが `INCLUDE_LAYOUT_AND_SETTINGS` を含むcontentsで実行される。
**When** restoreがBACKUP_RESTORE leaseを獲得する。
**Then** `prepareForRawFileRestore`（model quiesce）より前にinspection snapshot directoryが
削除され、**directoryが不在または空になったことが検証される**。
**And** 検証成功後にのみ既存の `databases/` wipeへ進むため、「DB不在+snapshot存在」のpoison状態は
本経路から生じない。

### Scenario: cleanup検証に失敗した場合はrestoreを中断しpoison状態を作らない

**Given** recovery DBが存在する状態でZIP restoreが実行され、snapshot directoryの削除が
IO失敗等で完了しない。
**When** cleanupの検証（不在または空の確認）が失敗する。
**Then** restoreはquiesce・`databases/` wipeへ進まずに失敗する。launcher状態はrestore開始前と
同一である。
**And** recovery DBは削除されず、DB不在+snapshot存在のpoison状態は作られない
（failure-injection testで固定する）。
**And** ユーザーにはrestore失敗が既存のrestore error surfaceで示され、再試行できる。

### Scenario: snapshot不在+DB存在は定義済み安全中間状態である

**Given** cleanup完了後・`databases/` wipe前の任意時点でprocess死が発生した
（snapshot不在+recovery DB存在の中間状態）。
**When** 次のprocessのstartup reconciliationが実行される。
**Then** classifierはDB存在を `Existing` と分類し（snapshot不在は分類に影響しない）、
reconcileが継続して `rebuildInspectionSnapshot` によりsnapshotが再生成され、gateはREADYへ到達する。
**And** 中間状態はpoison（`SuspiciousAbsence`）に進まない（unit testで分類と順序を固定する）。

### Scenario: cleanup検証後〜DB wipe完了までsnapshot publicationは介入できない

**Given** cleanup検証が成功し、serialization契約によりmodule operation mutexがrestore側で
排他保持されている。
**When** 同時にstartup reconciliation（またはapply/recover等のpublisher経路）が実行される。
**Then** 当該operationはmutex `tryAcquire` 失敗で即時に既存の競合結果を返し、snapshotの
republicationは発生しない。gate状態は変化しない（FAILEDに落ちない）。
**And** `databases/` wipe完了までの間、DB不在+snapshot存在のpoison状態へ至るinterleavingが
存在しない（deterministic interleaving testで固定する）。
**And** mutex解放後は既存の競合経路が通常どおり動作する。

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
**And** cleanup検証失敗の中断時はlauncher状態がrestore開始前と同一であるため、次のrestore試行は
通常どおり実行できる。
**And** 本fixが生む中間状態は「DB存在+snapshot不在」（cleanup後・wipe前）のみであり、これは
**定義済み安全中間状態**である（上のscenarioのとおり、次processで `Existing` としてreconcileし
snapshotを再生成できる。「未定義組合せ」ではない）。
**And** poison状態（DB不在+snapshot存在）はcleanup検証のhard stopと削除順序
（snapshot先、検証後wipe）により本経路から生じない。

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

- [ ] **AC-1 — 決定の記録:** 採用候補（検証つきcolocated cleanup + recovery epoch boundary）と
  却下候補（classifier healing、recovery DB保存、hybrid）の評価が
  [ADR-0011](../../docs/adr/0011-zip-restore-organizer-recovery-artifacts.md) に記録され、
  「ZIP restoreがpre-restore recovery pointsを意図的にinvalidateする（epoch boundary）」判断の
  明示化を含む。本specのDecision節と矛盾しない。ADR-0011は本spec/planと同時にdraftされ、
  review後にacceptedへ更新する。
- [ ] **AC-2 — cleanup hookの検証つき挙動とfailure injection:** cleanup hookは
  （a）reconciled startup相当（DB+snapshot存在）→ cleanup → 両artifact不在=Pristine分類の
  状態遷移、（b）**snapshot削除失敗の注入 → 検証失敗 → hard stop（recovery DB不削除、
  poison状態「DB不在+snapshot存在」を生成しない）**、（c）削除順序（snapshot先・検証後wipe）を
  unit testで固定する。#153で追加したtrigger pin
  （`RecoveryStartupStorageClassifierTest.zipRestoreLeavesRecoveryDbDeletedWithPublishedSnapshotSuspiciousAbsence`）
  はclassifier自体のcharacterizationとして不変のまま維持される。
  加えて（d）**serialization契約のdeterministic interleaving testとlock-order drain test**: 排他区間
  （cleanup検証後〜DB wipe完了相当）の保持中にsnapshot publication経路（reconciliation/mutation）
  を実行してもmutex待ちで即時競合returnしgate状態が変化せず、区間内にrepublicationが割り込めない
  こと、および解放後に通常動作へ戻ることをowning seam（`RunMutex` / `LayoutApplicationModule`）で
  検証する。さらに**in-flight module operationを保持した状態からの排他取得が有限時間でdrainする**
  こと（lock-order test。module mutex → coordinator blocking waitの逆順が形成されないことの
  機械的担保）を検証する。全publisher経路がmodule mutex下であること、および`acquireBlocking*`
  call siteがmodule mutex保持下で実行されないことを呼出site調査で確認し、assessmentに記録する。
- [ ] **AC-3 — classifier不変:** `RecoveryStartupStorageClassifierTest` の既存test群（genuinely
  suspicious状態の分類）が無変更で通過し、classifier・reconciler・gateのコードdiffがない。
- [ ] **AC-4 — #153再現手順の解消:** accepted assessment記録の再現手順（reconciled startup →
  ZIP restore → retry）をemulator `nunu_qpr2_api36_1` で実行し、restore後のprocessで
  reconciliation成功とpreview到達を確認する。結果を `docs/assessment/issue-187-zip-restore-recovery-artifacts.md`
  に記録する。
- [ ] **AC-5 — 中間状態の定義化:** 「snapshot不在+DB存在」が `Existing` 分類として次processで
  reconcileを継続し、`rebuildInspectionSnapshot` によりsnapshotが再生成されてgate READYへ到達する
  ことがtest（unit分類test+既存store publish pathの通過、必要ならinstrumentation）で検証される。
  journal・recovery format・backup formatへの非影響を既存test群の無変更通過で確認する。
- [ ] **AC-6 — high-risk merge gate:** 本実装の変更path（`lawnchair/src/app/lawnchair/backup/**`、
  `organizer/application/**`）はrepository workflow上のhigh-risk pathであるため、labelの有無に
  かかわらず実装PRは次のmerge gateを満たす: **対象head SHA上の `CI / final-status` 成功、
  独立audit記録 `docs/assessment/pr-<PR番号>-<slug>.md`、`high-risk-gate` workflow pass**。
  加えて `spotlessCheck`、unit test全件、`assembleLawnWithQuickstepGithubDebug` が成功し、
  PRに検証結果を記録する。
- [ ] **AC-7 — #153 AC-6の検証（non-write/redaction/schema不変）:** fix側のtest/evidenceにより
  次を証明する: (a) `NotReady` 経路がplanner呼出し・recovery point作成・layout mutationを
  行わないこと（既存composer/run non-write fixtureの通過）。(b) journal/export/logcatの既存
  redaction契約が変わらないこと（既存diagnostics negative-redaction fixtureの通過、
  §7 non-containment）。(c) diagnostics schema・closed code集合の意味論が変わらないこと
  （`ModelValidationTest` 等のclosed集合検証の通過、本実装diffがdiagnostics modelへ触れないこと）。
  このACの完了が [Issue #153](https://github.com/nunu1733/NunuLauncher/issues/153) AC-6の
  close条件である。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | ADR-0011本体（epoch boundary判断とrecovery record破棄の明示化を含む） |
| AC-2 | 新規unit test: (a) reconciled → cleanup → Pristine分類、(b) failure injection（snapshot削除失敗 → hard stop・recovery DB不削除・poison非生成）、(c) 削除順序、(d) deterministic interleaving test（排他区間中のpublication介入不可・gate不変・解放後復帰）+ lock-order drain test（in-flight op保持からの排他取得が有限drain）。既存trigger pinの無変更通過 |
| AC-3 | `RecoveryStartupStorageClassifierTest` 既存test群の無変更通過、`git diff` でclassifier/reconciler/gate非変更の確認 |
| AC-4 | エミュレータでの再現手順実行記録（`docs/assessment/issue-187-zip-restore-recovery-artifacts.md`） |
| AC-5 | unit分類test（`Existing` + snapshot不在）+ 既存store publish path（`rebuildInspectionSnapshot`）の通過。gapが残る場合はinstrumentation test |
| AC-6 | 対象headの `CI / final-status` 成功、`docs/assessment/pr-<PR番号>-<slug>.md` 独立audit、`high-risk-gate` workflow pass、`./gradlew spotlessCheck` `./gradlew testLawnWithQuickstepGithubDebugUnitTest` `./gradlew assembleLawnWithQuickstepGithubDebug` |
| AC-7 | 既存non-write fixture（`OrganizationInputComposerTest` / `ManualOrganizationRunTest` のINPUT_NOT_READY系）+ diagnostics negative-redaction fixture（journal/export non-containment系）+ `ModelValidationTest` 等closed集合検証の通過。本実装diffがdiagnostics modelに触れないことの確認 |

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
- 2026-09-01: Spec/Plan review対応。(1) [P1] cleanupをbest-effortから**検証つきhard stop**へ変更:
  検証（directory不在/空の確認）をmodel quiesceより前に実施し、失敗時は一切変更せずrestoreを
  失敗させる（failure-injection testで「recovery DB不削除・poison非生成」を固定）。(2) [P1]
  「snapshot不在+DB存在」を**定義済み安全中間状態**として定義し（次processでExisting→reconcile→
  snapshot再生成→READY）、rollback scenarioとAC-5のoracleへ反映。(3) [P1] 実装pathがhigh-risk
  であるため、AC-6を **exact-head `CI / final-status` + 独立audit `pr-<n>-<slug>.md` +
  `high-risk-gate` pass** を要求するmerge gateへ変更。(4) [P1] #153 AC-6の検証（non-write /
  redaction / diagnostics schema・closed code不変）を専用AC-7として追加し、test oracleに
  該当fixture群を明記。(5) [P1] **ADR-0011を本spec/planと同時にdraft**（recovery epoch boundary
  としての意図的invalidation判断、中間状態定義、alternativesを含む）。候補3の却下理由から
  「常にNotRestorable」の断定を除去。(6) [P2] #153→#187のbranch/PR依存をplanへ明記。
  (7) Minor: 候補列挙を「計4案」に明示、Documentation updatesのADR-0011チェックは実物作成済みに
  基づく形へ修正。
- 2026-09-01: Spec/Plan re-review対応。残留P1（cleanup検証後〜DB wipe完了間のsnapshot
  republication race）を解消: **serialization契約**（当該区間をorganizer moduleのoperation mutex
  `RunMutex` の排他保持下で実行。全publisher経路はmodule mutex下であることを前提契約とし、
  in-flight完了待ち・保持中のreconcileはtryAcquire失敗でgate不変）をDecision 1・新scenario・
  AC-2(d)に追加。ADR-0011にDecision 2としてserialization契約を追記し、時間窓依存の代替案を
  不採用として記録。
- 2026-09-01: Re-review（2回目）対応。残留P1（deadlock不存在の根拠が実コードと不一致）を修正:
  `LayoutWriteCoordinator` には `acquireBlocking*` が実在するため「coordinator全体がnonblocking」
  という誤った前提をspec/plan/ADR-0011から削除し、**lock graph call site audit**による正しい根拠へ
  置換（module mutex保持経路のcoordinator取得は非blocking `tryAcquire` またはcapability token
  reentryに限定。`acquireBlockingQuietly` の実在call siteはすべてmodule mutex非保持経路）。
  AC-2(d)にlock-order drain testを追加し、assessmentでのcall site再調査を必須化。
  Non-blocking note対応: wrapper API名を `runWithRecoveryOperationsSuspendedForRestore` とし、
  契約範囲（recovery mutation/reconciliation排他。capture/composeはmodule mutex非経由で対象外、
  読取専用かつpublisherではない）を明記。
