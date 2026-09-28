# Implementation Plan: 全 instrumentation lane の live failure capture（残り 7 lane）

> Issue: #438
> Spec: [spec.md](./spec.md)
> Status: implemented（merge PR #459。final PR でのstatus遷移）

## Current evidence

- 7 lane の現配線（`ci.yml` @ `a9b9454b91`）: runner `script` で test command を実行し、
  `if: failure()` + `continue-on-error: true` の runner 外 capture step
  （`timeout --kill-after=30 300 bash tools/ci/capture-emulator-failure-evidence.sh emulator-5554 build/<name>-failure-time-evidence`）
  が teardown 後に走る。
- runner 実行仕様: `reactivecircus/android-emulator-runner@v2` は `parseScript` で行分解し
  各行を `sh -c` で実行、`@actions/exec` が非ゼロで reject → loop 中断 → action 失敗 →
  `killEmulator`（[src/main.ts](https://github.com/ReactiveCircus/android-emulator-runner/blob/v2/src/main.ts)、
  2026-09-25 確認）。したがって複数行 script の現 failure semantics は「最初の失敗行で
  停止・失敗、emulator kill 後に capture」。
- 参照実装: PR #437（merge済み）が manual-organization-ui（helper 経由）/ category-override /
  onboarding-proposal（直接 gradle）の 3 lane を live 化し、wrapper
  `tools/ci/run-emulator-command-with-failure-capture.sh`、lifecycle contract test
  `tools/ci/test_emulator_failure_capture_lifecycle.sh`、validator 強化（echo/substring 拒否）を
  導入。hosted run 36080811322 で全 10 lane 起動・成功を実証。
- 各 lane の command 列（変更対象は wrap のみで列自体は不変）:
  - shared-writer: `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest
    -Pandroid.testInstrumentationRunnerArguments.class=com.android.launcher3.organizer.ModelWriterTransactionReentryTest,…,HotseatRestoreAdmissionTest`
    （9 class、単一 command）
  - db-migration: 同 task、class 5 個（MigrationTransactionOwnershipTest … rollback32.Schema32RollbackBinaryTest、単一 command）
  - restore-capture: scenario class 4 本の connected run → assemble → adb install ×2 →
    `am instrument` Stage A → force-stop → `am instrument` Stage B（10 command、#299 手順書が正本）
  - production-input: connected run（#83 4 class）→ `installDebug` 2 task → force-stop ×2 →
    `am instrument | tee` writer stage → grep ×2 → force-stop ×2 → `am instrument | tee` reader
    stage → grep ×2（12 command、API 35）
  - reservation-recovery: 単一 command、7 class（ProductionPublicSeam…Issue265ManualEditRecovery）
  - exchange-import-ui: 単一 command、1 class（ExchangeImportSurfaceInstrumentationTest）
  - method-choice-journey: 単一 command、1 class（MethodChoiceConnectedJourneyInstrumentationTest）
- artifact path（全 lane 不変）: failure-time upload は
  `build/<name>-failure-time-evidence/**` → artifact `<name>-failure-time-emulator-evidence`
  （retention 14日）。`name` は shared-writer / db-migration / restore-capture /
  production-input / reservation-recovery / exchange-import-ui / method-choice-journey。

## Design

### Modules and interfaces

- 変更 module は CI orchestration のみ: `.github/workflows/ci.yml`、`tools/ci/` の helper 2本
  （新規）と lifecycle contract test、`tools/repo-contract/validate_ci_portfolio.py` とその
  self-test、`tools/repo-contract/ci_portfolio_map.yml`（header 注記）、
  `docs/engineering/ci-test-portfolio.md`。
- seam は既存 wrapper の CLI（`[serial] [output-dir] -- command…`）と
  `capture-emulator-failure-evidence.sh` の bounded capture 契約。両方とも #437 で
  hosted 検証済みであり、本 Issue では interface を変更しない。
- production 側の interface・seam には触れない（`risk: layout-data` 等の高リスク契約は
  非対象）。

### Data flow

runner 起動 →（runner script 1 行）wrapper が command（helper または gradle 直）を実行 →
非ゼロなら teardown 前 capture → 元 status を exit → `@actions/exec` が reject →
runner action 失敗 → `if: failure()` の upload step が reports（7日）と failure-time
evidence（14日）を upload。成功時は evidence directory が存在しないため
`if-no-files-found: warn` の failure-time upload は動かない（#437 の lifecycle test が
success 無 capture を検証済み）。

### Change set

| Area | Intended change | Why here |
|---|---|---|
| `.github/workflows/ci.yml`（7 lane） | runner script を wrapper 1 行へ置換（単一 command 5 lane は gradle 直、複数 stage 2 lane は helper 経由）。runner 外 capture step を削除。upload step（reports / failure-time）は path・名前不変のまま残す | capture を runner 所有の live emulator 内に移すため。step 削除は validator 契約（runner 外 capture 拒否）と整合 |
| `tools/ci/run-restore-capture-instrumentation.sh`（新規） | #299 手順の command 列 10 本を `set -euo pipefail` で保持（pipeline を含まないため pipefail は不活性） | emulator-runner は行単位 `sh -c` のため複数 stage を 1 command に束ねる必要がある。manual lane の helper 前例 |
| `tools/ci/run-production-input-instrumentation.sh`（新規） | #83 + restart writer/reader の command 列 12 本を同様に保持（tee / grep oracle 含む）。`set -eu` とし pipefail を意図的に外して既存の tee→grep 判定順序と status を保持 | 同上 + 既存 failure semantics の完全保持（review round 1 指摘 1） |
| `tools/ci/test_emulator_failure_capture_lifecycle.sh` | 対象 job を 3 lane → 10 lane へ拡張。helper 2本の実行可否 check を追加 | wiring と artifact path の決定的契約を全 lane へ広げる（AC-3） |
| `tools/repo-contract/validate_ci_portfolio.py` | rule 6 を「全 lane が live wrapper capture 必須・runner 外 capture step は禁止」へ引き上げ。docstring 更新 | 7 lane の再 drift（runner 外 capture への戻り）を repo-contract gate で機械阻止 |
| `tools/repo-contract/test_validate_ci_portfolio.py` | fixture の既定を live capture 化し、runner 外 capture への退行を検出する負例 test を追加 | validator 契約変更の自己検証 |
| `tools/repo-contract/ci_portfolio_map.yml` | header 注記に live capture 必須契約を追記（edge・lane 集合は不変） | validator が検査する契約の normative 記述を map 側にも残す |
| `docs/engineering/ci-test-portfolio.md` | failure-time capture 行・lifecycle self-test 行・#438 follow-up 注記を「全 10 lane live 化完了」へ更新 | #422 新規 test 規則の監査表更新（AC-3） |
| `specs/438-live-failure-capture-seven-lanes/` | 本 spec/plan | AC-1 |

### Alternatives rejected

- **各行を個別に wrapper で包む**: 複数 stage lane で失敗 stage 以降が runner により
  実行されなくなる点は同じだが、`sh -c` の各行は pipefail を持たず、wrapper の
  capture が「どの stage 失敗か」を1回しか観測できず、grep oracle の意図（tee 後に
  OK/INSTRUMENTATION_CODE を確認）も分断される。helper 1本に束ねる方が command 列と
  failure semantics を 1 箇所で記述できる。
- **validator を現状維持（runner 外 capture も許容）**: lifecycle test で drift は
  検出できるが、portfolio gate 側の契約が「どちらでも可」のままになり、将来の lane
  追加・手直しで runner 外 capture が再混入し得る。#438 完了後は live capture が唯一の
  正なので、validator で機械阻止する。

## Migration and recovery

- schema / rule migration なし。CI workflow のみ。
- rollback: PR を revert すれば 7 lane は現行の runner 外 capture に戻る（#437 と対称）。
  部分退行時は validator が runner 外 capture step を検出して repo-contract gate が
  失敗するため、退行が静かに混入しない。
- helper script は main へ入る前に `bash -n` と lifecycle test で検証し、実 emulator を
  要する動作は hosted run で検証する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | spec/plan commit SHA + PR review | PR diff |
| AC-2 | lifecycle test（10 lane wiring、status 保持、success 無 capture、device-gone、helper 実行可否） | `bash tools/ci/test_emulator_failure_capture_lifecycle.sh` |
| AC-3 | validator + self-test（負例含む） | `python3 tools/repo-contract/validate_ci_portfolio.py`、`python3 tools/repo-contract/test_validate_ci_portfolio.py` |
| AC-2/3 補助 | capture helper smoke | `bash tools/ci/test_capture_emulator_failure_evidence.sh` |
| 全体 | repo contract | `python3 tools/repo-contract/validate_repo_contract.py` |
| AC-4 | hosted run: ci.yml 変更 → `full=true`（`compute_ci_gating.py` 規則）で 10 lane 起動、permanent gate + `final-status` 成功 | GitHub Actions `pull_request` run（最終 head） |
| AC-5 | controlled failure run: restore-capture helper 末尾へ一時 probe（`exit 7`）を push → live evidence artifact を保存・分類 → revert | GitHub Actions run + Issue/PR コメント（rerun 前に記録） |
| 構文 | shell 構文 check | `bash -n`（wrapper、helper 2本、lifecycle test） |

## Documentation updates

- [x] spec status/history（accepted、根拠を記録）
- [ ] `docs/engineering/ci-test-portfolio.md`（AC-3、同じ PR）
- [ ] `tools/repo-contract/ci_portfolio_map.yml` header 注記（AC-3、同じ PR）
- [ ] CONTEXT.md / DESIGN.md / ADR: 該当なし（production 構造・ドメイン用語・変更困難な
      設計判断の追加なし。runner 行単位実行の確認は spec へ記録済み）

## Execution checklist

- [x] Current behavior confirmed（7 lane 配線・runner 行単位実行仕様・#437 前例）。
- [x] Lifecycle test を先に 10 lane へ拡張して失敗を確認（missing behavior の再現）。
- [x] 最小実装（ci.yml 7 lane + helper 2本）。
- [x] Validator 契約引き上げ + self-test。
- [x] Full relevant verification（上表の local command 群）。
- [x] Hosted CI evidence（controlled failure + live evidence 分類・保存）。
- [x] PR evidence / handoff packet / 残risk 記録。
- [x] Review round 1（ChatGPT, comment 5827562500）の指摘対応: production-input を
      `set -eu` へ（既存 failure semantics 保持）、validator/lifecycle test の runner 外
      capture 検出を token・参照単位へ強化、plan 件数・status 修正。
- [ ] 再 review + Owner final decision + merge。
