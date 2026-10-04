# Quality Strategy

> Status: Proposed
> Updated: 2026-08-14 (high-risk independent-evidence gate added, Issue #43; organizer unit-test CI gate added, Issue #41)

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

compile以降のcommand名はsource導入後に確定する。

## Organizer unit-test CI gate

Issue #41 で organizer JVM test gateをCIに追加した。`.github/workflows/ci.yml` の `organizer-unit-tests` jobが、local開発で使うのと同一のtest surfaceをsource PRで実行する。第二のtest seamは作らない。

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
```

- この `--tests` filterは `app.lawnchair.organizer.planning.*`（contract/property test）と純粋な `application` JVM testの両方を含み、同package treeへ追加された新testは自動的にこのgateに加わる。
- jobは `final-status` 集約に接続されており、test失敗はmergeをblockする。docs/spec-only PRではpath filterによりskipされ、repository contract検証のみ走る。
- 実行結果の正本はGitHub Actionsの当該run URLとする（PR本文に記録する）。instrumentation test（Issue #14）とemulator実行はこのgateの対象外である。

## Organizer connected-test CI gate

Issue #83、#52、#53 の production/Launcher-host evidence は `.github/workflows/ci.yml` の独立した focused instrumentation job が source-changing PR で実行する。これらは `final-status` の required evidence であり、JVM gate の代替ではない。API 35 の #83 evidence と API 36 の #52/#53 evidence の責務、baseline、状態隔離、変更候補の判断は [ci-test-portfolio.md](./ci-test-portfolio.md) を正本とする。

shared-writer seam の coordinator/transaction 回帰、Issue #119 の実Launcher modelを使うreload supersession回帰、Issue #120 のrestore helper lifecycle/file-set replacementは、独立した API 36 instrumentation job `organizer-instrumentation-shared-writer-tests` のrequired evidenceである。coordinator/transaction/restore caseはisolated fixture DBを使い、reload caseは実Launcher modelを使う。job全体をUI laneのapp stateとは別のclean emulatorで実行する。lane 責務の正本は [ci-test-portfolio.md](./ci-test-portfolio.md) である。

Issue #52 と #53 は同じ API 36 emulator または target application state を共有しない。独立 job による clean emulator を各 suite に与え、source-changing PR の critical path から直列 emulator provisioning を除く。database-heavy fixture state、class-filtered Gradle invocation、coverage ownership は統合しない。

`.github/workflows/**` を変更する PR は、source-path filter が false でも同じ build、JVM、connected-test jobs を実行する。workflow-only change がその変更対象の gate を skip したまま merge されることを許可しない。

## High-risk independent-evidence gate

Issue #43 で、`risk: layout-data` / `risk: migration` labelまたは高リスクpath変更を持つPRに対する独立エビデンスgateを追加した。`.github/workflows/high-risk-gate.yml` が `tools/repo-contract/validate_high_risk_evidence.py` を実行し、`docs/assessment/pr-<PR番号>-<slug>.md` のaudit記録（Head SHA、CI run link、spec/ADR criteria）をGitHub APIと照合する。第二のtest seamは作らず、このgateは #41 のCI runの実行結果そのものを証拠として検証する。適用条件、audit形式、運用の正本は [github-workflow.md](../project/github-workflow.md) とする。validatorのself-testは `validate-repo-contract` job内で毎回実行される。

```bash
python3 tools/repo-contract/test_validate_high_risk_evidence.py
```

## Repository contract gates

Issue #8 で repository contract validator を導入した。次のcommandはlocalで検証済みであり、CIではPyYAMLを追加して完全なYAML parseを行う。Markdown内部link、Issue form YAML、required project filesを検証する。

```bash
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
```

CI（`.github/workflows/ci.yml` の `validate-repo-contract` job）は pinned PyYAMLをinstallして完全なYAML解析を行う。localでPyYAMLがない場合は構造smoke checkにfall backするため、YAMLの最終判定はCIを正本とする。validatorは上流由来の `wmshell`、`quickstep/src` 配下と自身の `tools/repo-contract/fixtures` を検証対象から除外する。

source pathは「既知moduleの列挙」ではなく、docs・Issue form・repository-contract tooling以外をdefaultでsourceとみなす。これにより新しい上流moduleを取り込んでもformat/build gateがfail-openしない。PRの変更検出にはread-onlyのpull-request permissionを使い、CI artifactは7日でexpireする。上流専用secretを使う通知・翻訳・release mutationはfork CIから実行しない。

## Release evidence

各release candidateに、基準upstream commit、schema version、rule version、test matrix結果、既知のlayout risk、recovery検証、upgrade/downgrade結果を保存する。release可否は機能数ではなく、P0/P1 safety defectがないことを優先する。
