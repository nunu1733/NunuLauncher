# High-risk audit: PR #186 QSB-row item interop — preserved projection + overlap acceptance predicate (Issue #185)

> Status: accepted with conditions
> Audit date: 2026-08-31
> Verdict: **ACCEPT WITH CONDITIONS**（初回監査 head `8f649e1e4c` での REJECT 指摘 F1/F2/F3 は新 head で対処済み。下記「再監査の条件」参照）

- Auditor: 独立ZCode agent session（実装を行ったagent/sessionとは別作業の読み取り監査 + 検証commandの独立再実行。初回監査と同一sessionによる新 head の再監査。コードは未変更、本記録の更新のみ）
- PR: https://github.com/nunu1733/NunuLauncher/pull/186
- Head SHA: 8683542052914c5304eab50e8f17a40b1880c9b3
- Head SHA検証: 上記は `git rev-parse HEAD`（branch `issue-185-implementation`、working tree clean）と一致し、PR #186のheadと同一であることを監査sessionで確認した。初回監査対象 `8f649e1e4c` の子孫であり、両者の間の非docs変更は修正コミット `8683542052` 1本のみ（`git log --oneline 8f649e1e4c..HEAD`）
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33440703365
- CI run補足: 上記はPR #186のpull_request merge-gate run（head `8683542052`、organizer-unit-tests / check-style / validate-repo-contract / build-debug-apk / instrumentation lanes実行）。監査完了時点でのこのrunの `final-status` はFAILURE（instrumentation 2 laneのflaky疑い失敗。Executed test surface 節に記録）。**このrun（または同一headの再実行run）の `final-status` green を再確認するまでmergeしないこと**。また本記録は head `8683542052` を監査しており、記録自体はdocs-only commitとしてpushされ、push後に `high-risk-evidence` が再実行される（validatorは「監査head以降の変更がdocs-only」であることを確認する設計）
- Criteria: specs/185-qsb-row-item-interop/spec.md AC-1 AC-2 AC-3 AC-4 AC-5 AC-6 AC-7 AC-8 AC-9、docs/adr/0010-qsb-row-item-overlap-interop.md ADR-0010、docs/adr/0008-qsb-reservation-context-and-recovery-compatibility.md ADR-0008、specs/13-safe-layout-application/spec.md FR-004 FR-005（`PreWriteRejection` closed集合・A5）、docs/engineering/organizer-diagnostics.md §5/§7

## Scope

監査対象diffは `git diff main...8683542052`（36 files、+1777/−45、commits `1ec7452f2a`→`58f9fa8f2f`→`8f649e1e4c`→`8683542052`）。初回監査（head `8f649e1e4c`、32 files）でAC-1〜AC-7の適合を確認済み。増分（`git diff 8f649e1e4c..8683542052`）は以下を独立レビューした:

- `LauncherLayoutAdapter.applyWriteSet` のA5 gate入力を `before.manifest.rows`（現在状態）→ `writeSet.intendedManifest.rows`（intended state。通常applyではintended manifest、recoveryでは `recovery.targetManifest`）へ変更（F1修正、`LauncherLayoutAdapter.kt:314-327`）
- 新規 `OverlapAcceptanceGateSeamInstrumentationTest`（apply/recovery のadapter seam test、F2）
- 新規 `LoaderCursorOverlapAcceptanceContractTest`（`LoaderCursor.checkItemPlacement` ↔ organizer predicate の等価contract test、F3）
- `ProductionPublicSeamInstrumentationTest` のimport 2行追加（未使用。軽微指摘M4参照）
- 本監査記録ファイル（初回REJECT版が `8683542052` に含まれてコミット済み。本更新はdocs-only commitでpushされる）

runtime書き込み経路とmigration対象: F1修正はtransaction内・write前のprecondition評価対象の差し替えのみで、transaction構造・commit経路・recovery lifecycleは不変。schema/format migrationはnone（recovery v2・journal schemaVersion 1・revision/digest計算不変）。予約listの取得元 `before.layoutState.reservedWorkspaceRegions` は現在=target（`RecoveryWriteSetMaterializer` のcontext一致検証 / ADR-0008のsource/intended不変検証）であり、intended rowsとの評価組合せは整合する。

## Criteria check

初回監査で適合確認した AC-1〜AC-7（ADR記録、capture fail-closedの局所性、分類precedence 2 site、plan時guard限定免除、composer受容gate+production wiring、closed集合17値、en/ja copy、#172手順の実機解消記録）は、増分diffがそれらの層に触れていないため維持される（unit 800 tests再実行で無回帰確認）。以下は再監査で状態が変化した項目。

**AC-8（受容policyのstalenessとrecovery安全）— 適合（条件付き、下記C2/C3）。** F1修正によりgateはintended stateを評価する: `overlapAcceptanceHolds(writeSet.intendedManifest.rows, before.layoutState.reservedWorkspaceRegions, overlapToleranceSource.isOverlapTolerated())`（`LauncherLayoutAdapter.kt:314-327`）。recovery write setの `intendedManifest` は `recovery.targetManifest`（同285行）であり、automaticRecovery（`ApplyProtocol.kt:431`）/ user-initiated recovery（`RecoveryProtocol.kt:153`）の両経路が同一のA5段で守られる。通常applyでは初回監査で示した現在=intended同値性がそのまま成立するため挙動不変。F2として実adapter seam testが追加された: (a) intended stateが予約重複を導入するplanを受容無効policyでapply → `Rejected(OVERLAP_POLICY_REJECTED)` + favorites無書込み検証、(b) loader削除済み重複行をrecovery targetが含むwrite set → `PreconditionFailed(OVERLAP_POLICY_REJECTED)` + 行が復活しないことのDB照会検証。実装側は修正前コードで(b)が `Committed`（復活）になることを実機で再現済み（treatmentの反証確認）。残存するtest品質上の条件はC2（test (b)のambient pref依存）とC3（CI lane未組み込み）。

**AC-9（acceptance一致保証）— 適合（条件付き、下記C3）。** ADR-0010 §4が必須とする等価contract testが追加された（`LoaderCursorOverlapAcceptanceContractTest`）。実 `LoaderCursor.checkItemPlacement` をsandbox（`MatrixCursor` + protected seamを開くsubclass）で呼び、QSB行重複形状 `(2,0)` について policy true/false 両値で「loader受容 = policy値」「organizer predicate = loader判定」をassertする。`checkItemPlacement` はscreen単位のoccupied mapをメソッド内で遅延構築しQSB行をmarkするため（`LoaderCursor.java:571-581`）、sandbox呼び出しでも実際のQSB分岐を通過しており、判定は本物のloader ruleに基づく。drift検知機構としては成立するが、当該classはどのCI laneのclass filterにも含まれずCIで実行されない（C3）。

## Executed test surface

監査sessionで実際に実行したcommandと結果（head `8683542052`、JDK 21 / Gradle 9.3.0）:

- `git rev-parse HEAD` → `8683542052914c5304eab50e8f17a40b1880c9b3`（PR headと一致）。`git status --porcelain` → 空。`git merge-base --is-ancestor 8f649e1e4c HEAD` → 成功。
- `./gradlew spotlessCheck` → **BUILD SUCCESSFUL**
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` → **BUILD SUCCESSFUL**。XML集計（`build/test-results/testLawnWithQuickstepGithubDebugUnitTest/*.xml`、67 suites）: **800 tests / 0 failures / 0 errors / 0 skipped**（再実行時刻を確認済み: results mtimeが本監査実行時刻直後）
- `./gradlew assembleLawnWithQuickstepGithubDebug` → **BUILD SUCCESSFUL**
- `./gradlew assembleLawnWithQuickstepGithubDebugAndroidTest` → **BUILD SUCCESSFUL**（新規instrumentation test 2 classのcompile確認）
- `python3 tools/repo-contract/validate_high_risk_evidence.py --repo 'nunu1733/NunuLauncher' --pr-number 186 --head-sha '8683542052914c5304eab50e8f17a40b1880c9b3'`（本記録更新後）→ 形式・criteria・lineage checkは本記録起因の失敗なし。残る失敗はCI evidence（下記 `final-status` FAILURE）のみであり、これは条件C1として記録

新規instrumentation testの実行結果（実装agentがemulator実行・報告済み: seam test + contract test 3 tests OK。本監査では実機再実行せず、compile成功までを独立確認）。

CI（PR #186、head `8683542052`、監査時点 2026-08-31 21:30 UTC頃完了）:

- merge-gate run https://github.com/nunu1733/NunuLauncher/actions/runs/33440703365: `changes` / `validate-repo-contract` / `check-style` / `organizer-unit-tests` / `build-debug-apk` / instrumentation（shared-writer、db-migration、issue52、issue53、issue155）= **SUCCESS**。`organizer-instrumentation-issue99-tests` = **FAILURE**（`CategoryOverridePreferencesInstrumentationTest > appRowMeetsMinimumFortyEightDpTouchTarget` の `ComposeTimeoutException`。初回監査headでは同一classの別testが同じtimeoutで失敗しrerunでpassしており、本PRと無関係のUI testのflaky継続と整合）。`organizer-instrumentation-api35-tests` = **FAILURE**（`ProductionOrganizationInputInstrumentationTest > productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites` の `ExecutionException: AssertionError`。当該testは本PRで追加されたtestではなく、F1修正の適用対象 `applyWriteSet` を経由しないcomposer/authoring並行性の既存test。同一laneは同日main run `33380149381`・初回監査head `33434045588` でSUCCESSであり、失敗testも前回と異なるためflaky疑い。ただし**再実行で再現した場合は本PR回帰として調査必須**）。よって **`final-status` = FAILURE**（https://github.com/nunu1733/NunuLauncher/actions/runs/33440703365/job/99650747486 ）。AGENTSの「検証対象commit上でCI merge gate (final-status) が実際に成功していること」は現時点で未達（条件C1）。
- high-risk gate run https://github.com/nunu1733/NunuLauncher/actions/runs/33440703377: `high-risk-evidence` = **FAILURE**、理由は「changes after the audited Head SHA are not docs-only; re-audit against the new head」（tree内の記録が初回headを指していたため。本更新のdocs-only commit push + 再実行で解消される手順どおりの状態）。

## Findings

再監査の結論: 初回REJECTのBlocking指摘F1/F2/F3はいずれも新 head `8683542052` で対処を確認した。判定は **ACCEPT WITH CONDITIONS**。以下に対処確認と、merge前に充足すべき条件（C1〜C4）と軽微指摘を記載する。

### 初回指摘の対処確認

- **F1（解消）**: gate入力が `writeSet.intendedManifest.rows` となり、recovery targetを含むintended stateがtransaction内・write前に現在の受容policyで再評価される。recovery両経路（automatic/user-initiated）が同一段を通ることをコードで確認。実装側の実機反証（修正前 `Committed` 復活→修正後 `PreconditionFailed(OVERLAP_POLICY_REJECTED)`）+ seam test (b) のDB照会（復活なし）で裏付け。
- **F2（解消・条件C2/C3付き）**: apply/recoveryのadapter seam testが実装され、`OVERLAP_POLICY_REJECTED` と無書込み/無復活をassert。ただしtest (b)はfixture行の初回reload生存を実機のambientな `allowWidgetOverlap`（既定falseではloaderが先読み削除してtestが失敗する）に依存しており、C2の確定化を条件にACCEPT。
- **F3（解消・条件C3付き）**: ADR-0010 §4必須の等価contract testが実loader ruleの実際の分岐を通過する形で存在し、policy両値で一致を検証。drift検知として機能するにはCI実行が必要（C3）。

### 再監査の条件（merge前に充足・記録すること）

- **C1**: 監査head `8683542052`（または同一内容の再実行）でCI `final-status` がSUCCESSであることを確認する。issue99/api35の失敗はflaky疑い（前回と別test、同一laneのmainでの成功履歴、変更領域との非関連）だが、再実行でapi35の `productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites` が再現した場合は本PR回帰として再監査相当の調査を要求する。
- **C2**: `OverlapAcceptanceGateSeamInstrumentationTest.recoveryGateRejectsRestoringRowTheLoaderDeletedWhenPolicyIntolerant` の環境依存を除去する。具体的にはfixture setupで `prefs.allowWidgetOverlap.setBlocking(true)`（finallyで復元）を明示し、fresh emulator（既定false）でも初回reloadでfixture行が削除されずrecoveryシナリオが成立することを保証する。現状のコードはambient stateに依存し、CI laneに組み込んだ時点で失敗する可能性が高い。
- **C3**: 新規instrumentation 2 class（`OverlapAcceptanceGateSeamInstrumentationTest`、`LoaderCursorOverlapAcceptanceContractTest`）をいずれかのCI laneのclass filterに追加する（現状 `grep OverlapAcceptanceGateSeam\|LoaderCursorOverlapAcceptance .github/workflows/ci.yml` → 不一致。AC-8/AC-9のtest証拠とLoaderCursor drift検知がCIで一度も実行されない状態を解消すること。C2の確定化が前提）。
- **C4**: 本記録の更新をdocs-only commitとしてpushし、そのheadで `high-risk-evidence` がPASSすることを確認する（本記録が監査するのは `8683542052` であり、docs-only commit以降の非docs変更は再監査対象となる）。

### 軽微（非blocking）

- **M4**: `ProductionPublicSeamInstrumentationTest.kt` に未使用import 2本（`RowManifestCodec`、`assertFalse`）が増分コミットで混入。spotless/CI check-styleは通過しているが、C2/C3の作業時に除去推奨。
- 初回監査の軽微指摘（composer/adapterのtolerance default `{ true }`、RESERVED_REGION precedenceのLOCKED表示差、新code固有retry testの非追加）は変更なし。いずれも非blockingとして維持。

### 判定根拠

ACCEPT WITH CONDITIONS: 初回REJECTの中心であったlayout安全契約の欠陥（F1）は指摘どおりの最小修正で解消され、実機反証とseam testで確認できた。AC-8/AC-9のtest証拠も実質的なものが揃った。一方、AGENTSが高リスクPRのmerge要件として同一commitでの `final-status` 成功を要求しており（C1）、F2/F3のtestがCIで実行されない・片方が環境依存という状態（C2/C3）は、これらを「マージ前に潰すべき条件」として残すのが相当と判断。docs-only commit手順（C4）はgate設計どおり。
