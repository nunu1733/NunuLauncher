# High-risk audit: PR #410 reconciliation decision table統合（path context入力の単一純分類table）と余剰整理（Issue #377）

> Status: accepted
> Audit date: 2026-09-22

- Auditor: 独立監査session（ZCode orchestrator配下のgeneral-purpose subagent）。本PRの実装（branch `issue-377-implementation` のcommit群 `c53a57e99b`〜`5ce4ced79a`）は別sessionが行っており、監査主体は実装・修正・revertに一切関与していない（読み取り + test実行 + 本記録の作成のみ。AGENTS.md「高リスクPRの独立エビデンス」のsolo保守における独立session要件）。実装側の主張（commit message）は位置確認にのみ参照し、本記録の判断は監査sessionが自ら実読したdiff・test本文・spec/plan本文と、自ら実行した`git` / `gh` / `./gradlew`検証の結果のみによる。
- PR: https://github.com/nunu1733/NunuLauncher/pull/410 （base `main`、head `issue-377-implementation`、state OPEN、labels無し — `gh pr view 410` で機械確認）
- Head SHA: 5ce4ced79a4d4705afb4def4ceed1201b610477c
  - Head SHA検証: `git rev-parse HEAD` == PR `headRefOid` == CI run `headSha` を機械確認済み（`git status` はclean、working tree差分なし。監査中の生成物commitは行っていない）
  - 対象diffの正: 監査対象範囲は `f9c95272d4..5ce4ced79a`（`f9c95272d4` = main @ PR #409 merge。3 files +609/−8 のcharacterization-only commit `c53a57e99b`、2 files +360/−670相当の統合commit `b8e189541e`、docs 1 commit `5ce4ced79a` の3 commit）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/35736426750 （`pull_request` event・workflow `CI`・headSha `5ce4ced79a…` 一致、conclusion=**success**。source job 5種〔`changes` / `build-debug-apk` / `validate-repo-contract` / `check-style` / `organizer-unit-tests`〕、instrumentation lane 9本すべてsuccess、merge gate `final-status` もsuccess — `gh run view 35736426750 --json jobs` でjob 15個のconclusionを機械確認）
- Criteria: specs/377-post-migration-cleanup/spec.md AC-1〜AC-4（accepted 2026-09-22）＋ specs/13-safe-layout-application/spec.md §"Transaction outcome classification" / §"Restart reconciliation"（path contextの受入済み正本）＋ specs/377-post-migration-cleanup/plan.md Current evidenceの裁定済みmatrix（規則(a)/(b)）とInventory result
- high-risk分類の根拠: labels無しでも変更path `lawnchair/src/app/lawnchair/organizer/application/`（`lifecycle/` `protocol/` `store/`）がhigh-risk path backstopに該当するため監査記録を要求される（plan.md「高リスク独立エビデンス」の適用条件2どおり）

## Scope

対象diffは `git diff --stat f9c95272d4..5ce4ced79a` で **22 files、+970 / −677**。履歴は2段階に分離されていることを監査sessionが機械確認した:

1. **`f9c95272d4..c53a57e99b`（characterization-only commit）**: `git diff --stat` の結果は **specs/377-post-migration-cleanup/plan.md、tests/unit/.../adapter/FakeLayoutWriter.kt、tests/unit/.../protocol/ReconciliationDecisionMatrixTest.kt の3 filesのみ**。production実装には一切触れていない（spec AC-1(2)の「統合前にcharacterization testが存在」の前提をcommit分離で機械証明）。`FakeLayoutWriter.classificationDigestOverride` はtest adapterへのcharacterization用hook（productionコード変更なし、diff実読で確認）。
2. **`c53a57e99b..5ce4ced79a`（統合commit + docs commit）**: production 6 file・test 11 file・res 2 fileの統合と、plan.md Inventory resultの重複記述除去。

監査sessionが全文実読した主なproduction変更:

| File | 変更 |
|---|---|
| `application/protocol/ReconciliationDecisionTable.kt`（新設156行） | 単一純分類table。`ReconciliationPathContext`（RESTART / IN_FLIGHT_APPLY / IN_FLIGHT_RECOVERY）を明示入力に持ち、`decideRestart(lifecycle, authoritative)` / `decideInFlightApply(authoritative)` / `decideInFlightRecovery(authoritative)` の3関数が同一row空間上のpath context別viewを提供。各cellは `(nextLifecycle, pruneRecord, Surface)` を返す。副作用なし（KDocで明示: no store writes, no lease, no reload, no result construction） |
| `application/protocol/RestartReconciler.kt` | `reconcileWithLease`（旧nested when）を `decideRestart` 消費へ委譲。副作用（advance/prune/quarantine・reload・検証・recovery resume）とtyped結果組立（runId/pointId込み）はprotocol層に保持。`ReconciliationPublicResult`変換helperを同fileから削除（protocol層の新fileへ移動）。format gate定数を `RecoveryRecordCodec.RECORD_FORMAT_VERSION` へ変更 |
| `application/protocol/ApplyProtocol.kt` | `classifyApplyOutcome` の分類分岐を `decideInFlightApply(...).surface` 消費へ委譲（`ROLLED_BACK_APPLY` / `CONTINUE_COMMITTED_APPLY` 等でdispatch。store副作用・typed結果組立は同層保持） |
| `application/protocol/RecoveryProtocol.kt` | inline分類（`authoritative != PRE_STATE && != RECOVERY_TARGET`）を `decideInFlightRecovery(authoritative)` 消費へ委譲（`RESTORE_NOT_COMMITTED` surfaceでtyped failure組立）。format gate定数をcodec参照へ変更 |
| `application/protocol/ReconciliationPublicResult.kt`（新設27行） | `ReconciliationPublicResult` sealed interface + 変換helperをlifecycle packageからprotocol層へ移動（内容は同一。diff実読で確認） |
| `application/lifecycle/LifecycleReconciler.kt` | 削除（360行。production到達不能な純分類重複・実装A） |
| `application/store/RecoveryRecordCodec.kt` | `RECORD_FORMAT_VERSION`（値2）のKDoc参照を `LifecycleReconciler` から「restart/in-flight protocol classification gates」へ更新（定数値・意味は不変） |
| `application/protocol/RecoveryPreviewProtocol.kt` | format gate定数を `LifecycleReconciler.SUPPORTED_FORMAT` → `RecoveryRecordCodec.RECORD_FORMAT_VERSION` へ変更（3箇所目のproduction参照統一） |
| `lawnchair/res/values{,-ja}/strings.xml` | `manual_organization_summary` をen/ja対で削除（AC-3） |

test変更: `ReconciliationDecisionMatrixTest.kt`（新設584行・18 test。統合commitでの変更はimport 1行〔`lifecycle.ReconciliationPublicResult` → `protocol.ReconciliationPublicResult`〕のみで、assert内容は1行も変更なし — `git diff c53a57e99b..5ce4ced79a -- <file>` で機械確認。これが統合前後同一結果の主証拠の構造的根拠）、`ReconciliationDecisionTableContractTest.kt`（新設107行・3 testの補助oracle）、`LifecycleReconcilerTest.kt`削除（222行）。

**runtime書き込み経路の確認**: 本diffのdurable書込経路は変更なし。`ReconciliationDecisionTable`は純分類でありstore書込を含まない（全文実読）。副作用（`advance` / `pruneUnused` / `markIncompatible` / quarantine / reload / verification / recovery resume）は全て既存protocol層に保持され、呼び出し順序・failure時挙動はdiff実読の範囲で旧実装と同一（`RestartReconciler`のSILENT/ROLLED_BACK_RESTART/UNRESOLVED_RESTART/UNRESOLVED_KEEP/CONTINUE_COMMITTED_APPLY/COMPLETE_RESTORE各分岐の副作用列を実読照合）。schema・migration変更なし。favorites / layout DBへの書込経路は本diffに含まれない。ホームレイアウトを扱う安全規約の適用面では、本変更はreconciliation分類の内部統合であり、DB適用・ロック配置・座標計算に触れない。

## Criteria check

test名は監査sessionがtest本文を実読し、assert内容と受入条件の対応を確認したもの。unit結果は監査session自身の実行（下記「Executed test surface」）による。

- **AC-1(1) matrix突合と差異裁定の記録: PASS。** plan.md Current evidenceに`(path × lifecycle × AuthoritativeClass)`全行の突合matrixと5件の差異裁定が記録済み（accepted spec添付）。実装はこの裁定を`ReconciliationDecisionTable`のコードコメント・commit message経由で反映: 規則(b)のpath context差（裁定3: APPLYING×PRE_STATE は RESTART=`silentPrune` / IN_FLIGHT_APPLY=`rolledBackInFlight`。裁定2: RESTORING×REVIEWED_CURRENT は RESTART=`unresolvedRestart`→recovery再開 / IN_FLIGHT_RECOVERY=`restoreNotCommitted`）がtable入力としてモデル化されていることを実読確認。READY×非PRE_STATE（裁定1）は`UNRESOLVED_KEEP`（lifecycle READY維持・record保持・`COMMIT_OUTCOME_UNKNOWN`直接表面化・recovery path不進入）として実装され、裁定どおり。規則(a)分離行（format不整合gate）はtableに含めず、`RestartReconciler.reconcileOne`のformat gate（#409実装の`markIncompatible` → INCOMPATIBLE遷移）に留まることを実読確認（bug挙動をcharacterization固定していない）。
- **AC-1(2) characterization testの統合前存在: PASS（機械証明）。** `git diff --stat f9c95272d4..c53a57e99b` がproduction変更0で`ReconciliationDecisionMatrixTest.kt`新設のみを含むことを確認。commit messageに統合前green確認の記録あり。test本文実読: restart path 13本（CREATING 2・READY 2〔READY×非PRE_STATEは`ApplyFailure.COMMIT_OUTCOME_UNKNOWN` direct assert + `storedLifecycleOf(pointId) == READY` のrecord保持assert込み〕・APPLYING 3・COMMITTED_UNVERIFIED 3・RESTORING 2・VERIFIED 1）+ in-flight apply 3本（NEITHER行はwrite失敗 + `classificationDigestOverride`で実class到達、`reloadCount == 0` discriminatorでdirect automatic recovery配線を識別）+ in-flight recovery 2本。生存production seam（`RestartReconciler` / 実`ApplyProtocol` / 実`RecoveryProtocol`）経由で固定しており、spec Scope §1-a-4の要求どおり。
- **AC-1(3) 統合後の同一結果と既存test修正なし通過: PASS。** 統合commitにおける`ReconciliationDecisionMatrixTest.kt`への変更がimport追従1行のみであることをdiffで機械確認（oracleのassertは無修正）。監査sessionの独立実行で18 test全green（下記）。既存契約test: `RestartReconcilerTest`（20 test）、`RecoveryProtocolTest`（42 test）、`ApplyProtocolTest`（65 test）を監査sessionが実行し全green。test修正のうち実挙動assertに触れるものは無く、import/定数参照の機械的追従（`RecoveryPreviewProtocolTest`のimport、entry testのimport等）のみであることをdiff実読で確認。
- **AC-2 削除test oracleのobsolete理由記録: PASS。** 削除対象は`LifecycleReconcilerTest`（222行・19 test）1件。obsolete理由が統合commit messageに明記されている: 固定していたのはproduction経路から呼ばれない`LifecycleReconciler.reconcile/classify*`の行であり、生存実装との行差異はplan Current evidenceで規則(b)または到達不能driftと裁定済み、置換oracleは`ReconciliationDecisionMatrixTest`（生存seam経由の全裁定行固定）。production到達不能性（production参照が`SUPPORTED_FORMAT`定数のみ）はplan Current evidenceの記録と一致し、監査sessionも削除後のgrepで`LifecycleReconciler`への残存参照がKDocの履歴言及1箇所のみであることを確認（コード参照0件）。
- **AC-3 未使用organizer系string/resourceの削除: PASS。** 削除は`manual_organization_summary`1件（en/ja対で同時削除 — diff実読）。plan.md Inventory resultに計上方法（organizer-family 471項目の定義）・参照scan範囲（code: `lawnchair/src` + launcher3側 + tests。非code: res内XML・manifest）・未使用判定根拠（#370が設定側直行row廃止時に参照消失、spec 370がresource cleanupを#377所有と明示）が記録済み。監査sessionの独立grepで`manual_organization_summary`の残存参照0件を確認。`spotlessCheck`成功（下記）。build成功はCI run `build-debug-apk` job successで確認（監査sessionでの`assembleLawnWithQuickstepGithubDebug`再実行は省略せず下記に記載する方針だったが、本監査ではCI success + spotlessCheck + 単体test compile成功を証拠とし、full assembleはCIに委ねた — この範囲をFindingsに明記）。
- **AC-4 評価対象の実施/見送り記録: PASS。** plan.md Inventory resultに記録済み: (1) `RecoveryPreviewSummary` seam簡素化=見送り（spec 84 acceptedが`Restorable.summary`を公開契約として明示、内部簡素化の利益が契約変更リスクを下回る。語彙サイズ1の解消はspec 84改訂を要する別Issueで行うべきと起案方針まで記録）、(2) export usage重複=見送り（spec 204が両者を意図的に別物として定義、解消はschema v5・spec 204改訂が前提で別起票）、(3) freeze残骸=残骸なし（#374/#375/#376後の個別無効化2箇所はspec 328競合affordanceの現行契約として生存）、(4) 旧UX test oracle=対応済み・削除対象0件（各移行PRのobsolete理由記録を入力に照合）、(5) organizer系strings=削除1件。`Trigger.INCREMENTAL_PROPOSAL` / `LOCAL_FULL`契約値の維持もplan記録どおり本diffで削除されていないことを確認（該当file無変更）。
- **spec 13 §"Transaction outcome classification"との整合: PASS。** `decideInFlightApply`: PRE_STATE→`ROLLED_BACK_APPLY`（prune込み。table cellは`nextLifecycle=READY, pruneRecord=true`、副作用はApplyProtocol層が実行）、INTENDED_POST_STATE→`CONTINUE_COMMITTED_APPLY`、その他→`ATTEMPT_RECOVERY`。specの3行表（not committed+prune / continue / attempt recovery）と1対1で一致。RecoveryFailedのtruthful `AuthoritativeState`等のtyped結果組立はApplyProtocol層に保持（実読）。
- **spec 13 §"Restart reconciliation"との整合: PASS。** `decideRestart`: CREATING×PRE_STATE=validate→READY+prune（spec row 1）、CREATING×その他=CORRUPT quarantine（spec row 2）、PRE_STATE系=prune/RESTORED（spec row 3）、INTENDED_POST_STATE=reload+verify+VERIFIED化（spec row 4、`finishCommittedApply`）、RESTORING×RECOVERY_TARGET/PRE_STATE=RESTORED化（spec row 5）、RESTORING×REVIEWED_CURRENT=recovery resume（spec row 6の"either resume the persisted recovery intent or surface an interrupted recovery"のresume側）、その他=recovery再試行・失敗時record保持+unresolved（spec row 7）。restart pathがcaller不在のため静かに掃除する文脈差は`SILENT`系surfaceとして保持。
- **plan.md Inventory result（AC-4評価記録の正本）: 確認済み。** 統合commit + docs commit（`5ce4ced79a`）でInventory resultが実装形状（単一純分類table + 3 seam委譲 + 副作用のprotocol層保持 + characterization history 2段階）と一致する内容に更新されていることを実読。ADR不要求の判断（統合先選択が3条件を満たさない）も記録済みで、監査sessionも同一判断（生存実装が正本・到達不能実装の削除は自明な統合先）。

## Executed test surface

監査sessionが実行した検証（checkout `/Users/nunu/Documents/work_idle/i_Nunulawncher`、HEAD `5ce4ced79a4d4705afb4def4ceed1201b610477c`、`git status` → clean）:

- `git log --oneline -5` → `5ce4ced79a` / `b8e189541e` / `c53a57e99b` / `f9c95272d4` / `6cef6d12dc`（対象3 commitの位置確認）
- `git diff --stat f9c95272d4..5ce4ced79a` → 22 files / +970 / −677
- `git diff --stat f9c95272d4..c53a57e99b` → **3 files（plan.md / FakeLayoutWriter.kt / ReconciliationDecisionMatrixTest.kt）/ +609 / −8**（characterization-only commitの機械証明。production実装0変更）
- `git diff --stat c53a57e99b..5ce4ced79a` → 20 files / +362 / −670（統合commit群）
- `git rev-parse HEAD` → `5ce4ced79a4d4705afb4def4ceed1201b610477c`
- `git diff <各file>` → production 7 file・test主要3 file・res 2 file・plan.mdの全文実読（Scope表・Criteria check）
- `grep -rn "manual_organization_summary\|LifecycleReconciler" lawnchair/src tests --include='*.kt' --include='*.xml'` → `manual_organization_summary`参照0件、`LifecycleReconciler`は`ReconciliationPublicResult.kt`のKDoc履歴言及1箇所のみ（コード参照0件）
- `grep -c "@Test"` → `ReconciliationDecisionMatrixTest.kt` 18件 / `ReconciliationDecisionTableContractTest.kt` 3件
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.protocol.ReconciliationDecisionMatrixTest' --tests 'app.lawnchair.organizer.application.protocol.ReconciliationDecisionTableContractTest' --tests 'app.lawnchair.organizer.application.protocol.RestartReconcilerTest' --tests 'app.lawnchair.organizer.application.protocol.RecoveryProtocolTest' --tests 'app.lawnchair.organizer.application.protocol.ApplyProtocolTest'` → **BUILD SUCCESSFUL（exit 0、49s）**。結果XML（`build/test-results/testLawnWithQuickstepGithubDebugUnitTest/`）から: `ReconciliationDecisionMatrixTest` **18 tests / failures=0 / errors=0 / skipped=0**、`ReconciliationDecisionTableContractTest` **3 / 0 / 0 / 0**、`RestartReconcilerTest` **20 / 0 / 0 / 0**、`RecoveryProtocolTest` **42 / 0 / 0 / 0**、`ApplyProtocolTest` **65 / 0 / 0 / 0**。合計148 test全green
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL（exit 0）**
- `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nunu1733/NunuLauncher` / `main`（AGENTS.mdのGitHub操作先確認。local ghは`-R` shorthand非対応のため位置引数で実行）
- `gh pr view 410 --json state,baseRefName,headRefName,headRefOid,labels` → OPEN / base `main` / head `issue-377-implementation` / headRefOid `5ce4ced79a…` 一致 / labels無し
- `gh run view 35736426750 --json status,conclusion,headSha,event,workflowName` → completed / **success** / headSha一致 / `pull_request` event / workflow `CI`
- `gh run view 35736426750 --json jobs --jq '.jobs[] | [.name, .conclusion]'` → 全15 job success（source 5種〔`changes` / `build-debug-apk` / `validate-repo-contract` / `check-style` / `organizer-unit-tests`〕+ instrumentation 9 lane + merge gate `final-status`）
- `gh issue view 377 --json state,title` → OPEN / `[Maintenance]: TO-BE移行で余剰となった実装・文言・test oracleを整理する`
- `specs/377-post-migration-cleanup/spec.md` / `plan.md` / `specs/13-safe-layout-application/spec.md` §"Recovery record and lifecycle"・"Transaction outcome classification"・"Restart reconciliation" / `docs/assessment/_template.md` / `docs/assessment/pr-409-*.md`（記録形式の先例）の全文実読

監査sessionで未実行の検証: `./gradlew assembleLawnWithQuickstepGithubDebug`のローカル再実行（CI run `build-debug-apk` job successを代替証拠とした）、instrumentation laneのローカル再実行（CI 9 lane全successを代替証拠とした）。いずれも同一head SHA上のCI成功が機械確認済みであるため監査証拠として充足と判断するが、範囲を明記しておく。

## Findings

監査sessionが確認した範囲で**merge阻害となる問題は存在しない**。AC-1〜AC-4はすべてspec/planの受入条件どおりに充足されており、統合は「裁定済みmatrixの生存path挙動を不変に保った単一table抽出」として実装されている（characterization testのassert無修正維持 + 既存契約test 148 test全green + CI final-status greenの3点で裏付け）。

補足事項（いずれもmerge阻害ではない）:

1. **`ReconciliationDecisionTableContractTest`は補助oracleとして位置づけが適切。** table直接assert（3 test）は純分類tableのcell値を固定するが、生存pathの観測可能挙動の主証拠はproduction seam経由の`ReconciliationDecisionMatrixTest`（18 test）であり、spec Scope §1-a-4（生存seam経由を要求）との整合を保っている。plan.md Inventory resultもこの2層構造を記録済み。
2. **AC-3のfull build証拠はCI依存。** 監査sessionは`assembleLawnWithQuickstepGithubDebug`をローカル再実行していない（上記のとおりCI `build-debug-apk` successを代替証拠とした）。spec AC-3のevidence定義は「検証済みcommand（building guide）でのbuild成功」であり、実装側PR記録とCI runがこれを満たすため充足だが、監査記録としての実行範囲を明示した。
3. **後続Issueの起案は記録済みで本PRの対象外。** `RecoveryPreviewSummary`語彙の解消（spec 84改訂を要する別Issue）とexport usage重複の解消（spec 204 schema v5改訂を要する別Issue）はplan.md Inventory resultに「本Issueでは起案しない／需要が生じた時点で起票」と記録されており、AC-4の要求（起案記録）とは整合する。起案自体の要否はIssue #377の終了条件判断に属すため、監査sessionはこれを未決定事項として扱わない（spec Open questionsの定義どおり本specの成果は評価記録まで）。
