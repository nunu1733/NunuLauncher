# High-risk audit: PR #188 ZIP backup restore resets organizer recovery artifacts consistently (Issue #187, ADR-0011)

> Status: accepted with conditions
> Audit date: 2026-09-01
> Verdict: **ACCEPT WITH CONDITIONS**（初回監査 head `6110e8d3af` はREJECT: finding 1 — `RunMutex.release()` が `notifyAll()` せず、production経路のholder解放後も `withExclusive` のdrain待機が起床しないliveness欠陥。fix commit `f4d007b71e` で解消し、第2回監査（delta再監査）で **ACCEPT WITH CONDITIONS**。実装reviewのAC-2(b)(c)要求（orchestration自体のhard stop/ordering pin）に対応する commit `dd0863d7d4` を第3回監査（delta `f4d007b71e..dd0863d7d4`）で確認し、判定不変。残る条件は本記録のHead SHA/CI run更新（docs-only commit）と `high-risk-evidence` 再実行PASS（AC-6）のみ。C4充足時点でmerge可能）

- Auditor: 独立ZCode agent session（実装を行ったagent/sessionとは別作業の読み取り監査 + 検証commandの独立再実行。第1回監査 `6110e8d3af` 全件審査、第2回監査はfix delta `6110e8d3af..f4d007b71e`、第3回監査はreview対応 delta `f4d007b71e..dd0863d7d4` の増分確認）
- PR: https://github.com/nunu1733/NunuLauncher/pull/188
- Head SHA: 5f171d734c05f6e35e866a29b74de92e99c02edc
- Head SHA検証: accepted planのbranch dependency手順に従い、#153 branch（PR #190、head `20cb868c0a`）を先にmainへmerge（main `1e7781f221`）した後、本PRをnew mainへrebaseした。監査済みcommit `dd0863d7d4`（第3回監査で認証）のreplayは **内容完全一致**（`git diff dd0863d7d4 5f171d734c` 空diff、lawnchair/tests配下0行差分）であり、認証はreplay後の `5f171d734c` を同一内容として引き継ぐ。本記録のHead SHA/CI run更新はdocs-only commitとしてpushされ、push後の `high-risk-evidence` 再実行でmerge-gate evidenceが検証される
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33525093066
- CI run補足: 上記はrebase後head `bec7b65e53` のpull_request merge-gate runで、監査実行時点で **completed / success、`final-status` job pass**（`organizer-unit-tests`、`check-style`、`build-debug-apk`、全instrumentation lane、`validate-repo-contract` 含む。第2回監査対象 `f4d007b71e` のrun 33507950197、第3回監査対象 `dd0863d7d4` のrun 33512899868 もcompleted/success/final-status passでlineageが連続する）
- Criteria: specs/187-zip-restore-recovery-artifacts/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7、docs/adr/0011-zip-restore-organizer-recovery-artifacts.md ADR-0011

## Scope

監査対象diffは `git diff main...f4d007b71e`（branch `docs/issue-187-spec-plan`。stacked on `docs/issue-153-spec-plan` — #153のspec/plan/assessment docs + classifier trigger pin testを含む。PR本文に記載のとおり、#153 branchを先にmainへmergeした上で本実装をmain basedのaudit対象headとする運用をplanに明記）。production codeの変更は4 file:

- `Ports.kt`（`RunMutex`）: `withExclusive`（blocking排他取得・drain。同一monitorで `tryAcquire`/`release` と直列）と `release()` の `notifyAll()` 追加
- `LayoutApplicationModule.kt`: `runWithRecoveryOperationsSuspendedForRestore`（serialization wrapper。契約範囲: recovery mutation/reconciliation排他。capture/composeはmutex非経由で対象外とKDoc明記）+ `mutexForTest()`（test-only accessor）
- `organizer/application/store/RecoveryStartupArtifacts.kt`（新規）: 検証つきinspection snapshot削除（`no_backup/recovery-inspection/`。削除失敗・残存entryでfalse）
- `backup/LawnchairBackup.kt`: restore内で cleanup+検証 → `prepareForRawFileRestore` → `databases/` wipe をmodule mutex排他区間で実行。検証失敗時は状態変更なしでrestore中断（hard stop）

classifier/reconciler/gate/diagnostics model/`NovaBackupConverter` は非変更（AC-3、Nova経路はspec non-goal #168/#185）。

## Criteria check

- **AC-1 — 適合。** ADR-0011（accepted）: 採用候補（検証つきcolocated cleanup+hard stop+serialization）、recovery epoch boundary（pre-restore recovery pointsの意図的invalidate、recovery record破棄の明示）、却下候補4案（healing/DB保存/hybrid/時間窓依存）、中間状態定義表。spec Decision節と一致。
- **AC-2 — 適合（第2回監査で解消、第3回監査でorchestration pin追加確認）。** (a) reconciled→cleanup→両者不在=Pristine分類、(b) failure injection（削除失敗・残存entry→false→hard stop、recovery DB不削除・poison非生成）、(c) 削除順序（snapshot先・検証後wipe）を `RecoveryStartupArtifactsTest` が固定。(d) serialization契約のinterleaving test（排他区間中の `reconcileAtStart` 競合が即時returnしgate READY不変）+ **lock-order drain test**（in-flight holderはproduction `tryAcquire`/`release` pathを使用。release()のnotifyAll修正なしに通過しないregression pin）。#153 trigger pin `zipRestoreLeavesRecoveryDbDeletedWithPublishedSnapshotSuspiciousAbsence` は無変更でgreen（characterization維持）。
  - **第3回監査の追加pin（実装reviewのchanges requestedに対応）**: restore orchestration自体を `LawnchairBackup.runRestoreCriticalSection(...)` seamへ切出し、`LawnchairBackupRestoreCriticalSectionTest` で (b) cleanup=false → `IllegalStateException`、ops==`[cleanup]` のみでquiesce/wipe未到達・recovery DB file生存、(c) cleanup=true → ops==`[cleanup, quiesce, wipe]` の順序とwipeが成功pathでのみDBを削除することを決定論的に固定。非blocking minor: test 3の `sectionRan` assertionは自明真（in-section性は構造的に担保）— polish候補として記録。
- **AC-3 — 適合。** classifier/reconciler/gateのdiffなし。既存classifier test群（5 tests、trigger pin含む）無変更通過。
- **AC-4 — 適合。** エミュレータ実測（`docs/assessment/issue-187-zip-restore-recovery-artifacts.md`、最終head `f4d007b` buildで再実行）: reconciled startup → ZIP restore → 後続processで `RUN_STARTED → CAPTURED → PLANNED → PREVIEWED`、`INPUT_NOT_READY` なし、post-restore artifacts両不在→reconcileで再作成。
- **AC-5 — 適合。** 「DB存在+snapshot不在」の `Existing` 分類test（poisonでないことの固定）+ 既存store publish path通過 + エミュレータ実測でsnapshot再生成（gate READY）を確認。
- **AC-6 — 適合（条件: 本記録のdocs-only pushと `high-risk-evidence` PASS）。** exact-head run 33507950197 がcompleted/success・`final-status` pass。`spotlessCheck` / unit全件（825 tests, 0 failures — 監査sessionの独立再実行） / `assembleLawnWithQuickstepGithubDebug` が成功。独立audit記録は本書であり、docs-only commitとしてpushされる。
- **AC-7 — 適合。** diffはdiagnostics model/`RunEvent`/closed codeに触れない。新codeはdiagnostics出力なし（純粋file操作+synchronization。例外messageはlayout内容を含まない固定文字列）。non-write fixture（`OrganizationInputComposerTest` / `ManualOrganizationRunTest` INPUT_NOT_READY系）とnegative-redaction fixtureがfull suiteで無変更通過。AC-4実測でもjournalは通常phaseのみ。

Lock graph（deadlock不存在、第1回監査PASSを第2回監査で再確認）: module mutex holderのcoordinator取得は非blocking `tryAcquire(ORGANIZER)`（`LauncherLayoutAdapter.tryAcquireLease`、`LockStateDbAdapter`）またはcapability token reentry（`newTransaction(organizerToken)` → `tryAcquireOrganizerLease`、非blocking・不一致時throw）に限定。`acquireBlockingQuietly` call site（`LawnchairBackup`/`NovaBackupConverter` BACKUP_RESTORE、`RestoreDbTask` RESTORE reentry、`GridSizeMigrationUtil`/`ModelDbController` GRID_MIGRATION、`getCoordinatorLease` MODEL_WRITER fallback）はすべてmodule mutex非保持経路。「module mutex → coordinator blocking wait」の逆順は形成されない。`quiesceForRestore` はmodel lockのみ。全snapshot publisher経路はmodule mutex下（call site auditは [issue-187 assessment](issue-187-zip-restore-recovery-artifacts.md) に記録）。

## Executed test surface

監査sessionによる独立再実行（head `6110e8d3af`、delta `f4d007b71e` で限定re-run）:

- `./gradlew spotlessCheck` → BUILD SUCCESSFUL
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest` → BUILD SUCCESSFUL in 24s、**825 tests / 0 failures / 0 errors**（RunMutexRestoreSuspensionTest 3/3、RecoveryStartupArtifactsTest 5/5、RecoveryStartupStorageClassifierTest 5/5 を含む。delta headでは限定test `--tests ...RunMutexRestoreSuspensionTest` を再実行 → BUILD SUCCESSFUL in 27s、3/3）
- `./gradlew assembleLawnWithQuickstepGithubDebug` → BUILD SUCCESSFUL（APK stamp `15.Dev.(6110e8d)` / delta head `15.Dev.(f4d007b)`）
- CI run 33502986767（`6110e8d3af`）・33507950197（`f4d007b71e`）・33512899868（`dd0863d7d4`）とも completed/success、`final-status` pass
- エミュレータ（`nunu_qpr2_api36_1`）: fix前は恒久 `INPUT_NOT_READY / RECONCILIATION_FAILED`（#153 assessment）。fix後（`f4d007b` build）はrestore後processでpreview到達、journalに `INPUT_NOT_READY` なし（delta `dd0863d7d4` はtest追加のみでproduction挙動は同一のため、実測はf4d007b buildのままで妥当）

## Findings

1. ~~**Blocking — `RunMutex.release()` がnotifyせずdrainが永久待機**~~（第1回監査head `6110e8d3af` でREJECT）→ fix `f4d007b71e`（`release()` に `notifyAll()`、drain testをproduction holder path化）で解消、第2回監査でregression pinの有効性を含め確認済み。
1b. ~~**Blocking — AC-2(b)(c)のorchestration pin不在**~~（実装review、head `eb45d62a19` でChanges requested）→ `dd0863d7d4`（seam切出し+test）で解消、第3回監査でdelta確認済み。
2. ~~Non-blocking — AC-6 gate red（audit記録不在）~~ → 本記録のdocs-only pushと `high-risk-evidence` 再実行で解消（条件C4）。
3. ~~Non-blocking — AC-4 evidenceのbuild stamp（`65ce6d6` = docs commit時点のAPK）~~ → 最終head `f4d007b` buildでの再実行により解消（本記録AC-4節）。
4. **Non-blocking — hard stop時の部分削除**: `deleteRecursively` が部分的に成功した後に失敗した場合、snapshot directoryが部分削除のままrestore中断となる（「launcher状態がrestore開始前と同一」は厳密にはsnapshot dirが縮小している）。ただしDB は保持されるため状態は `Existing` → 次reconcileでsnapshot再生成され、poisonには至らない。実害なし、記録として残す。
5. **Non-blocking — Nova restore経路は未処置**: `NovaBackupConverter` は同種のquiesce/wipeを含むがorganizer snapshot cleanupを持たない。specのnon-goal（#168/#185所有）であり、将来の追跡先として記録。
6. **Non-blocking — trigger pin testの帰属**: `RecoveryStartupStorageClassifierTest` は#153 branch由来で本PRの差分ではなくbyte-identical。AC-2は「trigger pinを不変で維持」を要求しており矛盾しない。
