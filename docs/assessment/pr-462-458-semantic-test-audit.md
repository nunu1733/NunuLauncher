# High-risk-audit-equivalent record: PR #462 semantic test portfolio re-audit Phase 2 (Issue #458)

> Status: accepted
> Audit date: 2026-09-25

- Auditor: independent general-purpose subagent session (ZCode); implemented by a separate session; solo-maintenance independence noted
- Verdict: **GO** (merge 可)
- PR: https://github.com/nunu1733/NunuLauncher/pull/462
- Head SHA audited: `cf57419e5aa8dd6c23021403ea89ca6511fa7adb` (branch `issue-458-test-portfolio-semantic-audit`, base `main` @ `7508bbf0d5`)
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/36132840490 (attempt 1 + failed-job rerun, attempt 2, conclusion success)
- Criteria: specs/458-semantic-test-audit/spec.md (AC-458-P1-01..08, AC-458-P2-01..08)
- Flake classification reference: PR comment [issuecomment-5832340708](https://github.com/nunu1733/NunuLauncher/issues/458#issuecomment-5832340708)（rerun 前記録、#422 taxonomy category 5 疑い / #418 family signature、capture artifact 付き）

## Scope

Pre-check: `gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef` → `nameWithOwner: nunu1733/NunuLauncher`, default branch `main`. Local checkout clean at the audited head.

Diff (`git diff main...HEAD --stat`), 14 files, +2098/−59:
- `.github/workflows/ci.yml` (+47/−8): db-migration lane class list +4 (`GridMigrationSuccessTest`, `LauncherPrefsCommitTest`, `DeckRetirementMigrationInstrumentationTest`, `RestoreProfileRemapTest`; `GridMigrationFailureTest` は含まず #461 所有)、reservation-recovery lane class list +7 (store 系 4 class + `Issue265GateFailedRouteInstrumentationTest` + `PageCaptureInstrumentationTest` + `LockAuthoringInstrumentationTest`)、category-override lane +`OrganizerLockScreenTest`、manual-organization-ui helper +`OrganizerDiagnosticsExportTimestampInstrumentationTest`、restore-capture helper 末尾に `NovaRestoreGridApplicationTest` の独立 connected invocation 追加（#299 の既存 stage 順序は diff 上未変更）、`organizer-unit-tests` `--tests` に `app.lawnchair.migration.*` と `app.lawnchair.DeviceProfileOverridesPresetResolutionTest` 追加、surface filter 追加（`surface_layout_write` に locks glob、`surface_organizer_ui` に locks + diagnostics/export glob、`surface_db_schema` に `LauncherPrefsCommitTest.java` / `RestoreProfileRemapTest.java` 明示指定）。
- `tools/ci/run-manual-organization-ui-instrumentation.sh` (+1 class)、`tools/ci/run-restore-capture-instrumentation.sh` (+6, 末尾追記のみ)。
- test fixture 修復 3 件 + Move boundary 1 件（下記 criteria check）。
- docs: `ci-test-portfolio.md` (+87)、`quality-strategy.md` (+8)。
- specs: `spec.md` / `plan.md` / `audit.md`（§1–§10）。
- **`tools/repo-contract/ci_portfolio_map.yml` は diff なし（edge 集合不変）** — plan.md の通り。

Production code / DB schema / workflow 構造の変更はなし。class filter と test path filter の追加のみで、rollback は class list からの除去。削除した instrumentation class は `BackupExclusionTest.java` 1 file のみで同一 assertion の JVM test が所有。

## Criteria check

Phase 1（audit.md の記録監査。本監査では §構成と対応関係を検証）:

- **AC-458-P1-01: PASS.** `audit.md` §2 に CI-routed instrumentation class 全件の protected contract と primary owner boundary の記録あり。
- **AC-458-P1-02: PASS.** `audit.md` §1.1（routed 全量）/ §1.2（unrouted 全量）/ §1.3（JVM filter coverage）/ §1.4（map↔workflow 整合）で全 class の識別を記録。
- **AC-458-P1-03: PASS.** `audit.md` §3（Group R Route / §3.2 Group D Diagnostic-local-only / §3.3 Remove 提案）で unrouted class 全件に明示 disposition。
- **AC-458-P1-04: PASS.** `audit.md` §5（journey、subset 関係、共有 fixture、lane grouping）で surface_organizer_ui lane 間 overlap を contract level で監査。
- **AC-458-P1-05: PASS.** `audit.md` §6 で既知 flaky を #422 taxonomy で分類。demotion/removal は行っていない。
- **AC-458-P1-06: PASS.** `audit.md` §7（7.1 non-test caller なし / 7.2 dual-use seam / 7.3 test-support）で seam インベントリ。
- **AC-458-P1-07: PASS.** `audit.md` §8 の bounded list は Route 14 + JVM Route 2 + Move boundary 1 + fixture 修復 3 + routing 分離 1（writerBusy）。count/runtime 削減だけを目的とした変更なし（Remove 0）。
- **AC-458-P1-08: PASS.** §8.6 focused-audit evidence（V1–V7、4 件の決定失敗の検出・分類・修復）を記録。

Phase 2（本監査で diff と CI から直接再検証）:

- **AC-458-P2-01: PASS.** 実装内容を diff で確認:
  - fixture 修復 3 件: `Issue265GateFailedRouteInstrumentationTest.kt`（新規 track。#417 scope-first flow 整合 + #371 granted fast path 前提。3 route tests `route1b`/`route2`/`route3` の gate assertion は intact、`awaitGate` の `assertEquals(target, module.readinessGate.state)` を含む）、`PageCaptureInstrumentationTest.kt`（#155/ADR-0008 契約に基づく `expectedPages(...)` expectation builder 導入。page ordering・空 page 排除・決定性の assertion 構造は intact — 3 test の `assertEquals(expectedPages(...), pageIds)` と page-3-absent `assertTrue` は残存）、`DeckRetirementMigrationInstrumentationTest.kt`（第 1 test 冒頭に `ensureActiveDbExists(context)` 追加のみ）。
  - writerBusy 分離: `Issue265GateFailedRouteInstrumentationTest` から report-only 観測を sibling class `Issue265WriterBusyObservationTest.kt` へ分離。`grep -rn "WriterBusy" .github/workflows/ci.yml tools/ci/*.sh` → 0 件。**どの CI class list にも含まれない**ことを確認（file 内 comment も明示）。
  - Move boundary (R-15): `tests/organizer-instrumentation/com/android/launcher3/organizer/BackupExclusionTest.java` 削除、`tests/unit/app/lawnchair/organizer/application/store/RecoveryDbBackupExclusionTest.kt` 新規（package `app.lawnchair.organizer.application.store`、同一 assertion `assertFalse(LauncherFiles.ALL_FILES.contains(RecoveryDbSchema.FILE_NAME))`）。JVM gate filter `app.lawnchair.organizer.*` がこれを自動拾う。
  - bounded list 改訂（R-10 の untracked harness の track 化）は owner が [issuecomment-5832044121](https://github.com/nunu1733/NunuLauncher/issues/458#issuecomment-5832044121) で明示追認済み。
- **AC-458-P2-02: PASS.** 削除は 1 class のみで、同一 assertion の JVM test が `organizer-unit-tests`（permanent gate）に移管済み。他に削除なし → 無 coverage 化なし。
- **AC-458-P2-03: PASS.** `ci-test-portfolio.md` と `ci.yml`（+helper script 2 件）を同じ PR で更新。`ci_portfolio_map.yml` は edge 不変のため意図的に無変更（validator で機械証跡）。
- **AC-458-P2-04: PASS.** 実 Gradle class filter を CI job log から確認（下記実行証跡）。path mapping だけを証拠にしていない。
- **AC-458-P2-05: PASS.** 本監査で local 再実行（下記）。CI の `validate-repo-contract` job も success。
- **AC-458-P2-06: PASS.** Run 36132840490 (attempt 2) completed/success、`gh pr checks 462` 全 17 check pass。対象 lane が新 class を含む実 filter で実行され、実測 runtime は docs 記載の見積と整合（db-migration 8m46s / reservation-recovery 11m50s / category-override 10m58s / manual-organization-ui 18m9s / restore-capture 10m26s、timeout headroom 内）。
- **AC-458-P2-07: PASS.** `audit.md` §9 に residual risk / follow-up 表（#461 起票済みの grid migration fail-closed 疑義を含む）。
- **AC-458-P2-08: PASS.** `audit.md` §8.6 に focused local validation（V1–V7、Phase 2 適用前 baseline）と §10 に V13–V16/V14c の結果・所要時間・分類を記録。

## 実行証跡（CI、GitHub API による直接確認）

Run 36132840490（pull_request、head_sha `cf57419e5a...`、conclusion success、attempt 2）:

- `gh pr checks 462` → 17 check 全 pass（`final-status` 含む、`high-risk-evidence` も pass）。
- **db-migration job (108070682453)**: log に実行 command として新 4 class を含む filter を確認（`GridMigrationSuccessTest`, `LauncherPrefsCommitTest`, `DeckRetirementMigrationInstrumentationTest`, `RestoreProfileRemapTest`）。`Starting 20 tests on emulator-5554` / `Finished 20 tests` / `BUILD SUCCESSFUL in 6m 25s`。
- **reservation-recovery job (108070682138)**: filter に新 7 class 全件を確認（`RecoveryStoreInspection` / `RecoveryInspectionSnapshotPublication` / `OrganizerDurableStatus` / `RecoveryStoreChunkedManifest` / `Issue265GateFailedRoute` / `PageCapture` / `LockAuthoring`）。`Starting 88 tests` / `Finished 88 tests` / `BUILD SUCCESSFUL in 9m 32s`。
- **category-override job (108070680213, attempt 2)**: filter に `OrganizerLockScreenTest` を確認。`Starting 26 tests` / `Finished 27 tests`（1 件は evidence capture の `Assume` による SKIPPED、log で `OrganizerLockScreenTest > capturesLockDialogTargetEvidence ... SKIPPED` を確認）。
- **organizer-unit-tests job (108070724090)**: 実行 command に `--tests 'app.lawnchair.migration.*' --tests 'app.lawnchair.DeviceProfileOverridesPresetResolutionTest'` を確認。`Task :testLawnWithQuickstepGithubDebugUnitTest` / `BUILD SUCCESSFUL`。成功時は per-test 行が出ないため個別 class 名は log に現れないが、Gradle は `--tests` pattern が 1 件も match しないと失敗するため、filter 語彙の実在・実行は証明される。class レベルの XML 確認は audit.md §10 の local 実行（165 class / 1833 tests / 0 failures）が補完。
- **manual-organization-ui (108070715656) / restore-capture (108070709754)**: ともに pass。helper script の diff で filter 追加と末尾独立 invocation を確認済み。

## Flake classification（attempt 1 → rerun）

- attempt 1 の failed job: `organizer-instrumentation-category-override-tests` (108064003654, conclusion failure) と `final-status` のみ。
- job log 直接確認: `12:13:38 CustomCategoryPreferencesInstrumentationTest > renameKeepsTheEntryPresentedAsTheSameCategory FAILED — androidx.compose.ui.test.ComposeTimeoutException: Condition still not satisfied after 5000 ms`。失敗時刻は本 PR 追加の `OrganizerLockScreenTest`（class list 末尾、12:14:09 頃実行）より前で、co-occupant 影響経路なし。同 class は本 PR で未変更・CI green 実績あり。
- 分類は **rerun 前**に [issuecomment-5832340708](https://github.com/nunu1733/NunuLauncher/issues/458#issuecomment-5832340708) で記録（category 5 疑い、#418 family signature）。capture artifact `category-override-failure-time-emulator-evidence`（id 10862013228、234,727 bytes、未 expired）が run 上に存在することを API で確認。
- rerun（attempt 2）で当該 lane green、run 全体 success。分類記録が rerun に先行していることを確認（quality-strategy の evidence-before-rerun 規則の遵守）。

## Local validator 再実行（本監査 session、head `cf57419e5a`）

- `/tmp/venv458/bin/python tools/repo-contract/validate_ci_portfolio.py` → `CI portfolio validation OK`（exit 0）
- `/tmp/venv458/bin/python tools/repo-contract/validate_repo_contract.py` → `repository contract OK`（exit 0）
- `/tmp/venv458/bin/python tools/repo-contract/test_validate_ci_portfolio.py` → `Ran 21 tests ... OK`
- `/tmp/venv458/bin/python tools/repo-contract/test_validate_repo_contract.py` → `Ran 13 tests ... OK`（fixture invalid-case 22 finding は self-test の期待動作）
- 未再実行: emulator instrumentation 本体（CI run が oracle）、`spotlessCheck` / JVM gate の local 再実行（audit.md §10 の記録と CI `check-style` / `organizer-unit-tests` success で代替）。

## Docs 整合 spot-check

- `ci-test-portfolio.md` db-migration lane 行（L91）: 「+4 class（grid-migration success path、commit-aware prefs、Deck retirement migration 冪等性、restore profile remap lock 保持）」— ci.yml 実 filter の 4 class と一致。
- `ci-test-portfolio.md` reservation-recovery lane 行（L95）: 「+7 class（recovery store inspection/publication/durable-status/chunked-manifest、#265 gate-FAILED routes、page capture、実 DB lock authoring）」— ci.yml 実 filter の 7 class と一致。
- `quality-strategy.md`: gate command block を ci.yml の新 filter と一致させ、「workflow を正とする」規則を明記。#458 追加の 2 filter の記述も一致。
- `ci_portfolio_map.yml`: 無変更。validator の edge 集合 check が PASS（map↔workflow 語彙の双方向一致は map 側の広い glob で吸収される設計）。

## Findings / residual risks

- **Merge blocker なし。** 全機械検証を本監査 session で独立再実行し、audit.md §10 / PR 本文の主張と一致した。
- Residual（非 blocking）:
  1. `organizer-unit-tests` の CI log には成功時 per-test 行がなく、新 JVM class の個別実行は CI 上では filter 語彙 + Gradle の no-match fail 動作による間接証拠。class レベルの直接証拠は audit.md §10 の local XML 確認（1833 tests / 0 failures）による。将来、unit gate も test report 集計を出すと監査が容易になる（follow-up 候補、新規 lane/報告義務ではない）。
  2. `PageCaptureInstrumentationTest` が `FeatureFlags.topQsbOnFirstScreenEnabled`（deprecated warning を CI log で確認）に依存する。契約入力として正しいが、flag 廃止時は本 test の expectation builder の更新が必要（#155 契約に従う範囲）。
  3. attempt 1 の category-override 失敗は category 5 疑いの暫定分類であり、#418 tracking issue が確定分類を所有する。本 PR で新たに発生したものではない（既知 family）。
  4. `Issue265WriterBusyObservationTest` は意図的に CI 非routing の diagnostic である。class が lane list から漏れていることと「diagnostic として非routing を選択したこと」を混同しないよう、portfolio doc 側の記録（§ untracked harness / writerBusy 分離）が正本であり続ける必要がある。
  5. audit.md §9 の残項目（GridChangeUnknownLockRecovery full loop、DeckRetirementProcessIsolation、surface_organizer_ui 画面単位分割、API 35 根拠等）は本 PR の範囲外の既知 follow-up であり、#461 を含め tracking issue 側で継続。
