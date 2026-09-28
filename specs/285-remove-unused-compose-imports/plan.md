# Implementation Plan: RestoreBackupScreen の未使用 Compose import 削除（振る舞い非変化のcleanup）

> Issue: #285
> Spec: [spec.md](./spec.md)
> Status: draft
> Risk tier: H — 変更path `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` が高リスクpath一覧（`tools/repo-contract/validate_high_risk_evidence.py` の `HIGH_RISK_PATH_PREFIXES` に含まれる `lawnchair/src/app/lawnchair/backup/`。docs/project/github-workflow.md「高リスクPRへの独立エビデンス要求」節と同じ正本）に当たるため。現行workflow（#444、2026-09-27適用）の判定規則「判定の第一基準は変更pathである。高リスクpath一覧に当たる場合は階層H」「高リスクpath上のbehavior-preservingなrefactorも階層Hである」により、本issueのような2行の未使用import削除も階層Hとして扱う。手順: accepted spec + plan.md、Execution and approval contract、および高リスクpathであることによる独立エビデンス契約（CI `final-status` 成功 + `docs/assessment/pr-<PR番号>-<slug>.md` 独立audit。`high-risk-gate` workflowが機械検証）。

## Current evidence

本planの `path:line` 根拠は2026-09-28に `origin/main` `c5a7840b880ed4c436b67170930ca87d4ef7f148` 上で検証した（worktree `issue-285-spec-plan`）。前回snapshot（2026-09-13、baseline `c5274b5d0d`）以降に当該fileへ触れたcommitはない（`git log c5274b5d0d..origin/main -- <file>` が空）。

**対象fileの現状（`lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt`、297行）**

- L13 `import androidx.compose.foundation.layout.Column` — 単語境界検索 `\bColumn\b` の一致はこのimport行のみ（使用箇所0件）。画面本体は `PreferenceLayout`（L89）配下で構成され、`Column` composableの呼び出しはない。
- L20 `import androidx.compose.foundation.verticalScroll` — 単語境界検索 `\bverticalScroll\b` の一致はこのimport行のみ（使用箇所0件）。scrollは L86 `val scrollState = rememberScrollState()` を L93 で `PreferenceLayout`（L89呼び出し）の `scrollState` 引数へ渡すことで実現される。
- L14 `import androidx.compose.foundation.layout.ColumnScope` — **使用中**（L118 `fun ColumnScope.RestoreBackupOptions(`）。削除しない。
- L19 `import androidx.compose.foundation.rememberScrollState` — **使用中**（L86 `rememberScrollState()`）。削除しない。

**由来（監査記録 [docs/assessment/pr-278-backup-page-summary.md](../../docs/assessment/pr-278-backup-page-summary.md) re-audit note 4/5・finding 8）**

- 中間commit `e3286421c3`（#233 codex re-review対応）がbounded-scroll body（`Box(heightIn(max = 240.dp) + verticalScroll)` 内 `Column`）を導入。
- `7e1e2e0d1e` が全体scrollable化（`scrollState` をportraitでも `PreferenceLayout` へ渡す）で上記を置換し、body側のusageは消えたが2 importが残留。
- 監査は「cosmetic residue only, zero behavior impact, suitable for a trivial follow-up cleanup」と判定し、この追い越しcleanupとしてIssue #285が起票された。

**検証環境の制約**

- spotlessは `compatLib/**/src/**/*.java`（googleJavaFormat + removeUnusedImports）と `lawnchair/src/**/*.kt`、`tests/unit/**/*.kt`（ktlint 1.8.0 + compose rules）のみを対象とし、markdownは対象外。ktlint構成は未使用importを検出しない（監査記録 re-audit note 5 が `spotlessKotlinCheck --rerun-tasks` 強制再実行で確認済み）。よってCI緑は本cleanupの検証にならず、目視diffとgrep証跡が第一の検証になる（spec AC-4）。
- Kotlin compileは使用中importの誤削除を静的に検出する（spec scenario 3）。

## Design

### Modules and interfaces

- 変更は `RestoreBackupScreen.kt` 1 fileのimport宣言2行削除のみ。module境界・interface・seamの変更はない。呼び出し側（`restoreBackupGraph()`、`RestoreBackupScreen()`、`ColumnScope.RestoreBackupOptions()`、`restoreBackupOpener()` の各宣言シグネチャ）は変わらない。

### Data flow

変更なし。実行時のdata access・状態遷移・副作用は一切触れない。

### Alternatives rejected

- **spotless/ktlintへ未使用import検出を追加して同じPRで潰す**: 変更scopeがIssue本文の非対象（他のcleanup）へ膨らむ。lint運用は別issueのmaintenance課題とする。
- **残留を放置する**: 監査finding 8が追い越しcleanupとして記録済みであり、放置は記録と矛盾する。
- **階層L（maintenance経路、spec/planなし）で進める**: 2026-09-13のIssueコメントは当時の正しい判定だが、#444（2026-09-27適用）が「高リスクpath上のbehavior-preservingなrefactorも階層H」を明文化したため、現行では変更pathが第一基準に反する。文書の現行版へ従う。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` | L13 `import androidx.compose.foundation.layout.Column` と L20 `import androidx.compose.foundation.verticalScroll` の2行を削除（追加変更なし） | 7e1e2e0d1e での置換後に使用が0件となった残留（finding 8）。隣接する L14 `ColumnScope` / L19 `rememberScrollState` は使用中のため保持 |

## Migration and recovery

- schema/rule migration: なし。
- failure中のrollback: 該当なし（compile成功する限り振る舞い変化なし。compile失敗はmerge前に静的に止まる）。
- release rollback/downgrade: 通常のrevertで戻る。生成物への影響なし。
- backup/restore compatibility: なし（backup/restoreの契約・data・経路に触れない）。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | diffの目視確認（2行削除・1 file・insertion 0） | `git diff --stat`、PR diff |
| AC-2 | 使用中importの残存確認 | 削除後fileに対する `grep -n 'ColumnScope'`（L14+L118）、`grep -n 'rememberScrollState'`（L19+L86） |
| AC-3 | style gate + 既存unit test | `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'`（JDK 21 / Android SDK 36.1。docs/engineering/building.md） |
| AC-4 | 使用箇所0件のgrep証跡 | `grep -n -E '\bColumn\b'` / `grep -n -E '\bverticalScroll\b'` がimport行のみに一致した実行記録をPR本文へ |
| AC-5 | 独立エビデンス契約 | CI `CI / final-status` 成功run（head SHA一致）+ `docs/assessment/pr-<PR番号>-<slug>.md`（`high-risk-gate` workflowが機械検証） |

含める観点: unit/contract（AC-3。本変更はtest追加対象なし——振る舞い非変化であり、失敗を再現するtestが書けない性質のcleanupのため、grep証跡とdiffが代替証拠。PRにその理由を記載する）、failure injection（不要。compileが静的検出）、DB/integration・UI/accessibility・performance（対象外。観測可能な振る舞いの変化なし）。

## Documentation updates

- [ ] spec status/history（実装PRで `implemented` へ遷移させる）
- [ ] CONTEXT.md — 変更なし（domain language 追加なし）
- [ ] DESIGN.md — 変更なし（system structure 変更なし）
- [ ] ADR — 作成しない（「変更が高コスト」「理由がコードから分からない」「実際の選択肢があった」の3条件を満たさない）
- [ ] AGENTS.md — 変更なし（workflow/verified command 変更なし）

## Execution checklist

- [ ] Current state reproduced（L13/L20 の使用箇所0件をgrepで再確認）。
- [ ] Tests fail for the missing behavior — 適用外（振る舞い非変化のcleanup。AC-4のgrep証跡とdiffが代替証拠であり、PRに理由を記載する）。
- [ ] Minimal implementation completed（2行削除のみ）。
- [ ] Migration/recovery verified — 適用外（migrationなし）。
- [ ] Full relevant verification completed（AC-3/AC-5）。
- [ ] PR evidence and remaining risks recorded（AC-4証跡、未確認範囲なしを明記）。
