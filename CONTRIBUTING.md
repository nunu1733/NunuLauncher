# Contributing

NunuLauncherの変更は、human/AI Agentを問わずGitHub Issueから開始します。

1. 適切なIssue formで問題・成果・非対象・終了条件を記載する。
2. 機能変更ではIssue番号を使って `specs/<issue>-<slug>/spec.md` を作り、承認を得る。
3. `status: ready` 後にbranch `issue-<number>-<slug>` で作業する。
4. code、test、migration、必要な文書を同じPull Requestで更新する。
5. PR templateに受入条件ごとのevidenceと実行commandを記載する。Issueの終了条件を満たす最終PRは `Closes #<issue>`、spec/plan/research/調査・証跡などの中間PRは `Refs #<issue>` とし、closing keywordを本文でレビューする。

詳細なsource of truth、label、Ready/Done条件は [GitHub workflow](./docs/project/github-workflow.md) を参照してください。AI Agentは作業前に [AGENTS.md](./AGENTS.md) を必ず読みます。

## GitHub操作先

このリポジトリのIssue/PRはfork `nunu1733/NunuLauncher`で管理します。checkoutの既定repositoryを信用せず、操作前に次を実行してください。

```bash
gh repo view -R nunu1733/NunuLauncher --json nameWithOwner,defaultBranchRef
```

以降の `gh issue`、`gh pr`、API操作でも対象forkを明示し、PRのbaseは `main` とします。Lawnchair上流への不具合・提案報告は、Issue chooserの[上流報告リンク](https://github.com/LawnchairLauncher/lawnchair/issues/new/choose)から上流へ直接送ります。上流報告をNunuLauncherのIssueとして起票し直すことはしません。

Issue chooserのfork用フォームは、Bug、Feature、Documentation or maintenance、Research or decision、Upstream integrationです。forkに存在しない上流用labelや上流のnightly/FAQ案内を持つフォームは使用しません。

## Checkout and build

submoduleを含めてcheckoutします。

```bash
git clone --recursive https://github.com/nunu1733/NunuLauncher.git
cd NunuLauncher
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
```

JDK/SDK要件、環境変数、検証済みversionは [building guide](./docs/engineering/building.md) を参照してください。submoduleが欠けている場合は `git submodule update --init --recursive` を実行します。

## Source placement and upstream conventions

- project固有codeは原則 `lawnchair/src/app/lawnchair` 配下に置く。
- `src/com/android/launcher3` はLauncher3由来であり、変更を最小のbridgeに限定する。
- commit messageはLawnchairのConventional Commits形式 `type(scope): subject` を継承する。
- NunuLauncherのPRは `main` をbaseにする。Lawnchair上流へ送る変更は、上流の最新方針を別途確認する。
- upstream copyright、license、source headerを削除しない。

上流固有の開発情報は [Lawnchair contributing guidelines](https://github.com/LawnchairLauncher/lawnchair/blob/15-beta/CONTRIBUTING.md) も参照してください。
