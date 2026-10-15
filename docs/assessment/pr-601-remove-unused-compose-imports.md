# High-risk audit: PR #601 Remove unused Compose imports in RestoreBackupScreen

> Status: accepted
> Audit date: 2026-10-15

- Auditor: 独立監査session（PR #601の実装Worker sessionとは別の監査専用session。solo保守における独立sessionによる再実行・再確認）
- PR: https://github.com/nunu1733/NunuLauncher/pull/601
- Head SHA: 62f64e42e8cc71a39dd8a7fa184ac26e1c8f7d7c
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/38721718683
- Criteria: specs/285-remove-unused-compose-imports/spec.md AC-1, AC-2, AC-3, AC-4, AC-5

## Scope

実装sessionとは別の独立sessionとして、検証対象commit `62f64e42e8cc71a39dd8a7fa184ac26e1c8f7d7c`（PR #601 head、branch `issue-285-implementation`）をdetached HEADでcheckoutし、base `754674e2ac15f8df88ef09f4a1d4b52946f089ec`（main）とのdiffを目視したうえで、spec `specs/285-remove-unused-compose-imports/spec.md`（accepted @ `830df4e770727874c780ab3d3ee8e862a7852d4f`）とplanの検証手順を自ら再実行した。対象diffは `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt` からの2 import行（`androidx.compose.foundation.layout.Column`、`androidx.compose.foundation.verticalScroll`）の削除のみで、1 file / 2 deletions / 0 insertionsである。当該fileは高リスクpath `lawnchair/src/app/lawnchair/backup/**`（`tools/repo-contract/validate_high_risk_evidence.py` の `HIGH_RISK_PATH_PREFIXES`）配下のため、独立エビデンス契約の対象である。変更はimport宣言の削除のみで、runtime書込み経路・migration・recovery対象に触れる変更はdiff上に存在しない。

## Criteria check

- **AC-1（2行削除のみ・1 file）: PASS。** `git diff --stat 754674e2ac..62f64e42e8` が `lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt | 2 --` / `1 file changed, 2 deletions(-)` を返した。diff本体を目視し、削除行が `import androidx.compose.foundation.layout.Column` と `import androidx.compose.foundation.verticalScroll` の2行のみ、追加 Changes 0件であることを確認した。PR上のhead SHAは `gh pr view 601` で `headRefOid = 62f64e42e8...` と検証対象commit一致を確認し、`gh api repos/nunu1733/NunuLauncher/commits/62f64e42e8...` で当該commitが本PRのcommit（`chore(285): remove unused Compose imports in RestoreBackupScreen`）であることを確認した。
- **AC-2（使用中の隣接importの保持、symbol/occurrenceベース）: PASS。** 削除後fileに対する `grep -c 'ColumnScope'` = 2件（import行 L13 + `fun ColumnScope.RestoreBackupOptions(` L116）、`grep -c 'rememberScrollState'` = 2件（import行 L18 + `val scrollState = rememberScrollState()` L84）。行番号は契約にせずoccurrence数で判定した。
- **AC-3（spotlessCheck + backup unit tests成功）: PASS。** 2026-10-15T10:40Z 頃（JST作業時刻）に再実行。`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew spotlessCheck` → `BUILD SUCCESSFUL in 44s`。`JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'` → `BUILD SUCCESSFUL in 7m 7s`（554 actionable tasks: 17 executed, 537 up-to-date）。双方とも自らの実行であり、実装PR本文の報告を参照していない。
- **AC-4（単語境界検索の使用箇所0件証跡）: PASS。** 削除後fileに対する `grep -c -E '\bColumn\b'` = 0件（exit 1、一致なし）、`grep -c -E '\bverticalScroll\b'` = 0件（exit 1、一致なし）。使用中のoccurrenceが失われていないことをAC-2のgrepと併せて確認した。
- **AC-5（独立エビデンス要件）: CI側はPASS / audit記録のpush待ち。** CI merge gate: GitHub Actions run 38721718683（`.github/workflows/ci.yml`、event `pull_request`、head_branch `issue-285-implementation`、head_sha `62f64e42e8...`（検証対象SHA一致）、PR #601 associated）が 2026-10-15T10:53Z 頃 `conclusion: success / status: completed` で完了をGitHub APIで直接確認した。job内訳: `final-status: success`、`organizer-unit-tests: success`、`check-style: success`、`build-debug-apk: success`、`validate-repo-contract: success`、`organizer-instrumentation-restore-capture-tests: success`（同runの他instrumentation jobは8件 skippedのため未実行）。audit記録の有無の機械検証（`high-risk-gate` run 38721718486の `high-risk-evidence` job）は本記録file作成時点で `FAIL: no docs/assessment/pr-601-<slug>.md audit record for this PR`（exit code 1）である。これは本audit記録がまだpushされていないことによる期待通りのfailであり、本記録がこのpathにcommit/pushされれば機械検証要件を満たす状態になる。push後の再評価結果は本auditの対象外（監査時点の事実のみ記載）。

## Executed test surface

監査sessionで自ら実行し、そのままコマンドと結果を記載する（実行日 2026-10-15、repo `nunu1733/NunuLauncher`、working treeは検証対象commit上のdetached HEAD）。

```
$ gh repo view nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef
{"defaultBranchRef":{"name":"main"},"nameWithOwner":"nunu1733/NunuLauncher"}

$ gh pr view 601 -R nunu1733/NunuLauncher --json headRefOid,baseRefOid,headRefName
{"baseRefOid":"754674e2ac15f8df88ef09f4a1d4b52946f089ec","headRefName":"issue-285-implementation","headRefOid":"62f64e42e8cc71a39dd8a7fa184ac26e1c8f7d7c",...}

$ git fetch origin && git checkout 62f64e42e8cc71a39dd8a7fa184ac26e1c8f7d7c
# detached HEAD on audited commit

$ git diff --stat 754674e2ac..62f64e42e8
lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt | 2 --
1 file changed, 2 deletions(-)

$ git diff 754674e2ac..62f64e42e8
# 2 removed lines only:
# -import androidx.compose.foundation.layout.Column
# -import androidx.compose.foundation.verticalScroll

$ grep -c -E '\bColumn\b' lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt
0  (exit code 1, no match)

$ grep -c -E '\bverticalScroll\b' lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt
0  (exit code 1, no match)

$ grep -c 'ColumnScope' lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt
2
$ grep -c 'rememberScrollState' lawnchair/src/app/lawnchair/backup/ui/RestoreBackupScreen.kt
2

$ JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew spotlessCheck
BUILD SUCCESSFUL in 44s
4 actionable tasks: 4 up-to-date

$ JAVA_HOME=/opt/homebrew/opt/openjdk@21 ANDROID_HOME=$HOME/Library/Android/sdk ./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.backup.*'
BUILD SUCCESSFUL in 7m 7s
554 actionable tasks: 17 executed, 537 up-to-date

$ gh api repos/nunu1733/NunuLauncher/actions/runs/38721718683
{"conclusion":"success","event":"pull_request","head_branch":"issue-285-implementation","head_sha":"62f64e42e8cc71a39dd8a7fa184ac26e1c8f7d7c","path":".github/workflows/ci.yml","pull_requests":[601],"status":"completed"}

$ gh api repos/nunu1733/NunuLauncher/actions/runs/38721718683/jobs?per_page=100
build-debug-apk: success / changes: success / check-style: success / final-status: success
organizer-instrumentation-restore-capture-tests: success / organizer-unit-tests: success
validate-repo-contract: success / (8 instrumentation jobs: skipped)
```

## Findings

- 本audit時点でspecのAC-1〜AC-4は自らの再実行で全て確認できた。AC-5のCI側（`final-status` 成功・head SHA一致・`pull_request` run・PR #601 associated・source job実行済み）もGitHub APIで直接確認できた。
- **残課題（監査時点の事実）**: `high-risk-gate` workflowの `high-risk-evidence` jobは「audit記録file不在」でFAILしている。本記録fileをPRへのdocs-only commitとしてpushすれば、`non_docs_files_after` 条件（audit後のdocs-only変更許容）を満たすため機械検証は通る見込みであるが、push後の再評価は本auditの対象外であり、事実としてのみ報告する。
- 変更は振る舞い非変化の2行削除であり、runtime書込み経路・migration・recoveryへの影響はdiff上存在しない。使用中import誤削除に対するguardとしてのcompile検証（静的検出）も、`spotlessCheck` とunit testのbuild successにより通過している。
- spotless/ktlintは未使用import検出をしないためCI緑は本cleanupの主要検証にならず（specの根拠どおり）、grep証跡とdiff目視が第一の検証として機能していることを本auditで再実行により確認した。

## 未確認範囲

- なし（本audit時点のspec AC-1〜AC-5に対応する検証項目は全て自ら再実行で確認した。AC-5のaudit記録push後の `high-risk-evidence` jobの再評価結果のみ、監査が行われた時点では確定していない）。
