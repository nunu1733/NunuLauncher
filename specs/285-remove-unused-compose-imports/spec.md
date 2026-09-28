---
issue: "#285"
status: draft
tier: H
requirements: []
updated: 2026-09-28
---

# RestoreBackupScreen の未使用 Compose import が残存しない（振る舞いは一切変化しない）

> Risk tier: H — 変更対象file `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` は高リスクpath一覧（[docs/project/github-workflow.md](../../docs/project/github-workflow.md) 「高リスクPRへの独立エビデンス要求」節。正本は同節と `tools/repo-contract/validate_high_risk_evidence.py` の `HIGH_RISK_PATH_PREFIXES` の `lawnchair/src/app/lawnchair/backup/`）に当たる。現行workflow（#444、2026-09-27適用）は「高リスクpath上のbehavior-preservingなrefactorも階層Hである」と定めており、階層L（文書・テスト・**高リスクpathに触れない**refactor）の条件を満たさない。したがって本cleanupは階層Hの手順（accepted spec + plan.md、Execution and approval contract、高リスクpathであることによる独立エビデンス契約）で進める。2026-09-13のIssueコメントのmaintenance（spec/plan不要）判定は #444 適用前の旧workflow基準であり、本spec起草時に現行基準へ更新した。
> 出典: PR #278（Issue #233、refs #273）の監査記録 [docs/assessment/pr-278-backup-page-summary.md](../../docs/assessment/pr-278-backup-page-summary.md) finding 8（re-audit note 5）。Issue #285。

## Problem

PR #278 の実装過程で、中間commit `e3286421c3` がbounded-scroll body（`Box(heightIn + verticalScroll)` 内の `Column`）を導入し、後の `7e1e2e0d1e` が全体scrollable化（`PreferenceLayout` への `scrollState` 渡し）でそれを置き換えた際、使わなくなった2つのimportが `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` に残った:

- `androidx.compose.foundation.layout.Column`（L13）
- `androidx.compose.foundation.verticalScroll`（L20）

repoのspotless/ktlint構成は未使用importを検出しない（監査記録 re-audit note 5 が `./gradlew spotlessKotlinCheck --rerun-tasks` の強制再実行で確認済み）ため、CIは緑のまま通る。動作影響はないが、backup/restore画面の実装に死んだ依存宣言が残り続け、将来の整理・監査の対象を読む者に誤解を与える。

## Outcome

`RestoreBackupScreen.kt` のimportリストが実際の使用と一致する。画面の見た目、scroll挙動、restore処理、生成物の意味は一切変化しない。削除は2行・1 fileに限られ、使用中の隣接import（`ColumnScope`、`rememberScrollState`）は影響を受けない。

## Scope

- `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` から次の2行を削除する:
  - `import androidx.compose.foundation.layout.Column`
  - `import androidx.compose.foundation.verticalScroll`

## Non-goals

- 他の未使用import・他fileのcleanup（本issueは上記2行のみ。Issue本文の非対象「他の cleanup や機能変更」どおり）。
- spotless/ktlint設定へ未使用import検出ruleを追加する変更（別issueのmaintenance課題であり、本issueでは行わない）。
- 画面構造・文字列・testの変更。formatのみの再整列。

## Domain language

追加・変更する用語なし。

## Behavior scenarios

### Scenario: restore確認画面は削除前と同一に動作する

Given 有効なbackup fileを開いてrestore確認画面が表示されている
When 画面を閲覧・scrollし、内容選択のtoggleを操作してrestoreを実行する
Then 削除前と同じ表示・scroll・restore結果が得られる（本変更は観測可能な振る舞いを1つも変えない）
And `app.lawnchair.backup.*` の既存unit testがすべて成功する

### Scenario: 使用中の隣接importは保持される

Given 削除対象2行の隣に、名前が部分一致する使用中のimportがある（`ColumnScope`、`rememberScrollState`）
When 2行の削除diffを適用する
Then `androidx.compose.foundation.layout.ColumnScope`（`fun ColumnScope.RestoreBackupOptions` で使用）と `androidx.compose.foundation.rememberScrollState`（`rememberScrollState()` で使用）はそのまま残る
And compileが成功する

### Scenario: 誤って使用中のimportを削除した場合

Given 実装者が誤って使用中のimport（例: `ColumnScope`）も削除した
When buildを実行する
Then compile errorとなりmergeできない（静的に検出される。silentな振る舞い変化の経路ではない）

## Data and state

- 読むdata / 永続化するdata: なし。import宣言の削除のみで、実行時のdata access・永続化・状態は一切変わらない。
- migration / backup / restore / rollbackへの影響: なし（観測可能な振る舞いの変化がないため）。対象fileはbackup/restore UI配下であるが、変更はその契約に触れない。

## Permissions, privacy, and security

None。permission・外部送信・sensitive dataへの影響なし。

## Accessibility and localization

None。UI構造・文言・focus順は変化しないため、accessibility・localizationへの影響なし。

## Acceptance criteria

- [ ] AC-1: `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` から `androidx.compose.foundation.layout.Column` と `androidx.compose.foundation.verticalScroll` の2 import行が削除され、変更後のdiffはこの2行削除のみ（1 file）である。
- [ ] AC-2: 使用中の `androidx.compose.foundation.layout.ColumnScope`（L14）と `androidx.compose.foundation.rememberScrollState`（L19）は削除されていない（単語境界での使用確認: `fun ColumnScope.RestoreBackupOptions(`、`rememberScrollState()`）。
- [ ] AC-3: PR上で `./gradlew spotlessCheck` と `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` が成功している。
- [ ] AC-4: 削除が振る舞い非変化であることを、PRに単語境界検索（`\bColumn\b`、`\bverticalScroll\b` の使用箇所が0件であること）の実行記録として残す。spotless/ktlintは未使用importを検出しないため、CI緑だけでは本cleanupの検証にならない（監査記録 re-audit note 5 と同じ根拠）。
- [ ] AC-5: 変更対象pathが高リスクpath（`lawnchair/src/app/lawnchair/backup/`）であるため、PRは独立エビデンス要件（検証対象commit上のCI merge gate `final-status` 成功 + `docs/assessment/pr-<PR番号>-<slug>.md` の独立audit記録）を満たしてmergeされる（`high-risk-gate` workflowが機械検証する）。

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | PR diffの目視確認（2行削除・1 file）。`git diff --stat` が1 file / 2 deletions / 0 insertions |
| AC-2 | PR diffで当該2行が残っていることの目視確認 + 削除後のword境界grep（`ColumnScope`、`rememberScrollState` の使用が残る） |
| AC-3 | CI上の `./gradlew spotlessCheck` と `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` の実行結果 |
| AC-4 | PR本文に記録したgrep実行結果（使用箇所0件の証跡） |
| AC-5 | GitHub Actions `CI / final-status` の成功run（head SHA一致）と `docs/assessment/pr-<PR番号>-<slug>.md` |

## Open questions

- なし（未使用import検出ruleの追加はNon-goalsに分離済み。必要な場合は別issueで起票する）。

## Change history

- 2026-09-28: Draft created for #285. Risk tier判定を旧基準（2026-09-13コメントのmaintenance）から現行基準（#444適用後の階層H）へ更新した起草として記録。
