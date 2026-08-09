---
issue: "#<number>"
status: draft
requirements: []
updated: YYYY-MM-DD
---

# <Observable outcome>

## Problem

誰が、どの状況で、何に困っているかを書く。solutionのclass名ではなく、観測可能な問題を記述する。

## Outcome

このIssueが完了したときに可能になることを1段落で書く。

## Scope

- 含む振る舞い。

## Non-goals

- 意図的に含めない振る舞い。

## Domain language

追加・変更する用語があれば記載し、承認時に `CONTEXT.md` へ反映する。実装語だけなら空にする。

## Behavior scenarios

### Scenario: <name>

Given <initial state>
When <event/action>
Then <observable result>
And <invariant/diagnostic>

### Scenario: <failure or edge case>

Given ...
When ...
Then no persistent change is made
And the user/diagnostic receives ...

## Data and state

- 読むdataと正本。
- 永続化するdata、identity、retention。
- migration、backup/restore、rollbackへの影響。
- layoutを扱う場合は対象集合と全item typeの扱い。

## Permissions, privacy, and security

- 追加permission、外部送信、sensitive data、opt-in/out。
- 該当しない場合は `None` と理由を書く。

## Accessibility and localization

- focus、label、error/confirmation、font scaling、translation上の要件。

## Acceptance criteria

- [ ] AC-1: 観測可能かつtest可能な条件。
- [ ] AC-2: failure/rollback条件。
- [ ] AC-3: 文書・diagnostic条件。

## Test oracle

各ACをどのtest surfaceまたは手動evidenceで確認するかを書く。

| AC | Evidence |
|---|---|
| AC-1 | |
| AC-2 | |
| AC-3 | |

## Open questions

- 実装開始前に解消する問い。`accepted` 時点では空にするか、非blockingである理由を書く。

## Change history

- YYYY-MM-DD: Draft created for #<number>.
