# Quality Strategy

> Status: Accepted
> Updated: 2026-09-25 (Issue #456: test-audit skill による authoring/review/audit 手順を追加。Issue #458: organizer unit-test gate filter へ migration/preset 解決契約を追加。CI portfolio policy は Issue #422 を継続)

## Quality order

1. layoutを失わない。
2. lockと対象外itemを変えない。
3. 同じ入力で同じ結果になる。
4. 失敗理由を説明し、復旧できる。
5. その上で整理品質と速度を改善する。

## Test surfaces

### Organization Planning interface

呼び出し側とtestが同じ `plan` seamを使う。内部の分類順、sort、bin packing classを個別mockしない。

- example fixture tests: 人間が読めるinputとexpected plan/diagnostic。
- property tests: conservation、no overlap、bounds、lock、profile isolation。
- metamorphic tests: input順序を変えてもcanonical resultが同じ。
- idempotence tests: output layoutを再入力すると空差分。
- determinism tests: locale、timezone、thread schedulingに依存しない。
- convergence tests: incremental後とfull organizationの不要な振動がない。

The normal source-PR property gate remains the deterministic 64-case corpus
from `SyntheticFixtureGenerator.DEFAULT_SEED`. Issue #46 additionally runs a
separate scheduled/manual matrix of eight fixed seeds × 512 cases (4,096
generated cases total) in `.github/workflows/planner-stress.yml`. That matrix
is exploration evidence rather than a per-PR merge gate; failures still carry
the seed, count, and zero-based case needed for local reproduction through the
same `PlannerGeneratedPropertyTest` seam.

### Layout Application interface

test databaseをproduction DB adapterの代替として使い、interface経由で検証する。

- revision mismatchで書き込まない。
- recovery point作成失敗で書き込まない。
- N番目のwrite失敗で全rollbackする。
- process death相当後も復旧できる。
- folder/container/widget参照が適用後も有効。
- memory model reload後のsnapshotがplanと一致する。
- recoveryを複数回実行しても壊れない。

### Platform integration

- package add/update/remove/restore/unavailable。
- personal/work/private profileと同一package。
- 複数launcher activity、disabled/hidden app。
- orientation、grid変更、tablet/foldable profile。
- backup/restore、app upgrade、DB migration、downgrade behavior。
- launcherがforeground/backgroundのときのeventとUI notification。

### UI and accessibility

- empty diff、large diff、warning、unplaced item、failure、recovery。
- confirmationのcancel/retry、process recreation。
- TalkBack label/focus order、font scaling、contrast、touch target。
- translated stringでlayoutが崩れない。

## Minimum fixture corpus

| Fixture | Purpose |
|---|---|
| empty home | zero-item behavior |
| apps only | stable sorting and fill |
| mixed app/shortcut/widget | item coverage and span |
| nested folder contents | container integrity |
| locked corners and center | fragmented free space |
| full grid / no capacity | explicit rejection or overflow |
| multiple pages and Dock | page ordering and preserved Dock |
| personal + work same package | identity isolation |
| undefined categories | fallback and diagnostics |
| grid/profile change | rule portability and stale plan |
| existing Deck layout output | upstream compatibility/regression |

Fixtureにはprivateな実端末dataを含めず、synthetic identityを使う。

## Performance measurement

Big-Oだけを合格条件にしない。reference環境、workload matrix、phase別metric、
統計法、暫定budgetの正本は [performance-budgets.md](./performance-budgets.md)
(Issue #15) である。budget未決定・測定不能phaseも、計測値が存在するなら
PRに残し、regression比較可能にする。

## CI gates after source import

実際の上流commandを確認してAGENTSへ追加する。最低限のgateは次の通り。

- Markdown/YAML link and syntax check。
- format/lint。
- compile。
- upstream unit tests。
- planner contract/property tests。
- application DB/migration tests。
- debug APK build。
- risk label付きPRでのtargeted emulator test。

The source-import command names are now fixed by the checked-in workflow and
the [building guide](./building.md). Any new mandatory command requires a
successful clean-checkout or CI run before it is added here.

## Organizer unit-test CI gate

Issue #41 で organizer JVM test gateをCIに追加した。`.github/workflows/ci.yml` の `organizer-unit-tests` jobが、local開発で使うのと同一のtest surfaceをsource PRで実行する。第二のtest seamは作らない。

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
```

- この `--tests` filterは `app.lawnchair.organizer.planning.*`（contract/property test）と純粋な `application` JVM testの両方を含み、同package treeへ追加された新testは自動的にこのgateに加わる。
- jobは `final-status` 集約に接続されており、test失敗はmergeをblockする。docs/spec-only PRではpath filterによりskipされ、repository contract検証のみ走る。
- 実行結果の正本はGitHub Actionsの当該run URLとする（PR本文に記録する）。instrumentation test（Issue #14）とemulator実行はこのgateの対象外である。
- Issue #242 で `app.lawnchair.ui.preferences.navigation.*`（既存）に続き `app.lawnchair.bugreport.*` を同じjobのfilterへ追加した。bugreport packageのJVM test (`tests/unit/app/lawnchair/bugreport/`) もこのgateで実行される。
- Issue #458 で `app.lawnchair.migration.*`（Deck retirement artifact names 契約）と `app.lawnchair.DeviceProfileOverridesPresetResolutionTest`（#134 preset 解決契約）をfilterへ追加した。いずれも旧来 unrouted だった純 JVM class であり、wildcard 化は gate ownership を広げるため行わず明示追加とした。

## Organizer connected-test CI gate

Issue #422 により、CI portfolio は impact-based 起動へ移行した。各 instrumentation
lane は「変更が影響しうる impact surface に対応するとき」だけ PR で起動し、全 lane 実行
は main push・週次 scheduled sweep・`workflow_call`・`workflow_dispatch(full-portfolio)` が
担う。lane↔surface 対応の正本は [tools/repo-contract/ci_portfolio_map.yml](../../tools/repo-contract/ci_portfolio_map.yml)、
各 lane の契約・分類・実測費用・過去 failure 分類を含む監査表の正本は
[ci-test-portfolio.md](./ci-test-portfolio.md) である。以下は運用上の要点である。

- PR merge gate の中心条件は「開発によって変更された箇所、およびその変更によって影響
  されうる既存 contract が検証できていること」である。file path ではなく impact
  surface / production contract 単位で必要 test set を選ぶ。
- 起動判定は `tools/ci/compute_ci_gating.py` が持つ per-path fail-closed 規則による。
  diff に 1 件でも未 mapping の source file があれば全 source lane が起動する
  （mapped / unmapped 混在でも発火）。mapping の隙間が gate の静かな skip として
  現れないことを機械で保証する。
- `organizer-unit-tests`・`check-style`・`build-debug-apk` は Permanent gate として
  `permanent_run`（source || ci || full || smoke）で起動する。`validate-repo-contract`
  は docs-only を含む全 run で実行する。
- `.github/workflows/**` を変更する PR は `ci` filter により全量を自己実行する。
  workflow-only change がその変更対象の gate を skip したまま merge されることを
  許可しない。
- Issue #52/#53 由来の状態隔離（lane ごとの clean emulator、database-heavy fixture
  の非共有）は維持する。lane の改名（Issue 番号ベース → contract ベース）により job ID
  は変わったが、class filter・clean-state 要件・coverage ownership は変更していない。

## Intermittent failure の分類・証拠・retry 方針

Issue #422 で確立した方針。背景は #304 / #352 / #418（いずれも変更対象外の lane の
failure が merge evidence を阻害した実績）。

失敗は再実行の前に、次の分類のいずれかに割り当てる。

1. **product regression** — production code の欠陥。修正は対象契約の最も低い層で
   再現する regression test を伴う。
2. **deterministic test defect** — test 自体の論理 bug（毎回失敗する）。
3. **test synchronization / test harness defect** — 待ち合わせ・timeout・focus 等の
   test harness 側の欠陥。
4. **CI wrapper / artifact handling defect** — workflow・script・artifact 系の欠陥。
5. **emulator / runner / platform environment defect** — SystemUI ANR・boot 不調等の
   環境 signature。#418 が追跡中の代表例。
6. **unknown / investigation required** — 上記に確定できないもの。tracking Issue を
   分離する。

運用規則:

- 全 instrumentation lane が bounded failure-time evidence capture
  （#315 実装の `tools/ci/capture-emulator-failure-evidence.sh`、continue-on-error）
  を持ち、失敗時の証拠が rerun 前に artifact として残る。
- 「rerun で green になった」ことのみを分類なしの merge evidence として扱わない。
  rerun は分類の記録（Issue/PR コメントへの signature と分類の記載）を伴う。
- 既知 environment signature を merge gate から無条件に除外しない。除外する場合は
  対象 signature・根拠・検出条件を tracking Issue に記録し、portfolio 文書へ反映する。
- 一時的 failure = production 無関係、という前提を置かない。再現性の低さと原因分類は
  別問題である。

## 新規 test / CI lane 追加時の審査ルール

test の新規作成・変更・review・監査、または CI test routing の変更では、
[test-audit skill](../../.agents/skills/test-audit/SKILL.md) を実行手順として使う。
skill は本節と #422 の policy を置き換える正本ではなく、protected contract、credible
regression、primary owner boundary、重複、impact surface、CI 分類を変更前に確定するための
authoring/review gate である。矛盾時は本書、[ci-test-portfolio.md](./ci-test-portfolio.md)、
`ci_portfolio_map.yml`、`.github/workflows/ci.yml` を優先する。

新しい test を追加するときは、PR で次を記載する。

1. 既存 test / lane でその contract をカバーできない理由。
2. regression oracle は可能な限り最も低く・速く・決定的な層に置いたこと。instrumentation
   / emulator が必要な理由の明示（実 framework・実 process・実 storage に依存する等）。
3. 新 test がどの impact surface に属し、どの変更で起動するか。既存 lane への統合で
   済む場合は独立 lane を作らない。独立 lane が必要な場合は clean-state 要件を満たせ
   ない理由。
4. 既存 CI のどの lane と重複しないか。
5. 恒久 PR gate に昇格する場合、scheduled sweep では不足する理由。
6. `tools/repo-contract/ci_portfolio_map.yml` と
   [ci-test-portfolio.md](./ci-test-portfolio.md) の監査表を同じ PR で更新する
   （`validate_ci_portfolio.py` が map↔workflow の整合を強制する）。

Issue 完了時の一時的 diagnostic test を、そのまま恒久 PR gate に昇格させない。
obsolete / duplicated test の削除・降格も通常の保守として許容する。

## High-risk independent-evidence gate

Issue #43 で、`risk: layout-data` / `risk: migration` labelまたは高リスクpath変更を持つPRに対する独立エビデンスgateを追加した。`.github/workflows/high-risk-gate.yml` が `tools/repo-contract/validate_high_risk_evidence.py` を実行し、`docs/assessment/pr-<PR番号>-<slug>.md` のaudit記録（Head SHA、CI run link、spec/ADR criteria）をGitHub APIと照合する。第二のtest seamは作らず、このgateは #41 のCI runの実行結果そのものを証拠として検証する。適用条件、audit形式、運用の正本は [github-workflow.md](../project/github-workflow.md) とする。validatorのself-testは `validate-repo-contract` job内で毎回実行される。

```bash
python3 tools/repo-contract/test_validate_high_risk_evidence.py
```

## Repository contract gates

Issue #8 で repository contract validator を導入した。次のcommandはlocalで検証済みであり、CIではPyYAMLを追加して完全なYAML parseを行う。Markdown内部link、Issue form YAML、forkのIssue chooser設定（上流contact routeを含む）、required project filesを検証する。

```bash
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
```

CI（`.github/workflows/ci.yml` の `validate-repo-contract` job）は pinned PyYAMLをinstallして完全なYAML解析を行う。localでPyYAMLがない場合は構造smoke checkにfall backするため、YAMLの最終判定はCIを正本とする。validatorは上流由来の `wmshell`、`quickstep/src` 配下と自身の `tools/repo-contract/fixtures` を検証対象から除外する。

source pathは「既知moduleの列挙」ではなく、docs・Issue form・repository-contract tooling以外をdefaultでsourceとみなす。これにより新しい上流moduleを取り込んでもformat/build gateがfail-openしない。PRの変更検出にはread-onlyのpull-request permissionを使い、CI artifactは7日でexpireする。上流専用secretを使う通知・翻訳・release mutationはfork CIから実行しない。

## Release evidence

各release candidateに、基準upstream commit、schema version、rule version、test matrix結果、既知のlayout risk、recovery検証、upgrade/downgrade結果を保存する。release可否は機能数ではなく、P0/P1 safety defectがないことを優先する。
