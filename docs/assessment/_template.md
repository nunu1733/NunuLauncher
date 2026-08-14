# High-risk audit: PR #<PR number> <short title>

> Status: accepted
> Audit date: YYYY-MM-DD

- Auditor: <実装を行っていない作業主体。solo保守では独立sessionを明記する>
- PR: https://github.com/nunu1733/NunuLauncher/pull/<PR number>
- Head SHA: <auditが対象とする40桁commit>
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/<run id>
- Criteria: <specs/<n>-<slug>/spec.md または docs/adr/*.md への参照と要件ID（例: FR-1、NFR-2、ADR-0003）>

## Scope

対象diffと、確認したruntime書き込み経路・migration対象を列挙する。

## Criteria check

Criteriaに挙げた受入条件ごとの確認結果を記載する。

## Executed test surface

正確なcommandと結果を記載する。「test通過」だけの記載はしない。

## Findings

確認された問題、残課題、後続Issueへの分離を記載する。
