---
issue: "#<number>"
status: draft
tier: M
requirements: []
updated: YYYY-MM-DD
---

# <Observable outcome>

階層Mの軽量specである（[Risk tiers](../../docs/project/github-workflow.md#risk-tiersリスク階層)）。
Launcher DB書込み、schema migration、recovery store、上流model/loader bridgeを
含む変更にはこの形式を使わない（通常の `spec.md` + `plan.md` を使う）。

## Problem

誰が、どの状況で、何に困っているか。1〜3文。

## Benchmark

改善する編集負担ベンチマークの課題（B1〜B7。
[editing-burden-benchmark.md](../../docs/engineering/editing-burden-benchmark.md) で定義）
と目標値。階層Mのfeature specは、課題と目標値がなければ受け入れない（NFR-014）。
純粋な導線・文言修正で該当課題がない場合は、その理由を書く。

## Prior art

設計に影響した外部の類似実装・best practiceを、対象・URL・確認日・採用/不採用理由を
1行1事例で記載する。`なし（調査済み）` は実際に調査して有用例がなかった場合のみ、
`省略（理由）` は正本の省略条件に当たる場合のみ使う。適用対象・省略条件の正本は
[GitHub workflowのExternal reference scan](../../docs/project/github-workflow.md#external-reference-scan設計時の外部参照調査)。

## Outcome

この変更の後に可能になることを1段落で。

## Scope

- 含む振る舞い。

## Non-goals

- 意図的に含めない振る舞い。

## Behavior scenarios

### Scenario: <name>

Given <initial state>
When <event/action>
Then <observable result>

### Scenario: <failure or edge case>

Given ...
When ...
Then no persistent change is made
And the user sees <message/state>

（2〜4個を目安にする。失敗時のzero-writeを1つは含める。）

## Verification

- owner確認用のスクリーンショット/録画は **実機で取得** する
  （emulatorは補助証跡としてのみ可）。確認した受入条件との対応を書く。
- 実行したunit test / instrumentation（あれば）。
- 書込み経路を追加しないことの確認（diffで触れるpathの列挙）。
- 上流のUIだけに触れるbridge（popupやメニューの項目の追加など。model、loader、
  DBに触れないもの）を含む場合は、
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  によるcandidate HEADの計測結果を **PR本文** へreportする
  （`docs/assessment/upstream-patch-surface-baseline.md` / `.json` の更新は
  新しいbaselineを採用する場合だけ）。

## Accessibility and localization

- focus、label、TalkBack、font scalingの確認結果。該当しない場合は理由。

## Change history

- YYYY-MM-DD: Draft created for #<number>.
