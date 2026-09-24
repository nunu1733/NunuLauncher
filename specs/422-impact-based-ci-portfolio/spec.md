---
issue: "#422"
status: draft
requirements:
  - AC-422-01
  - AC-422-02
  - AC-422-03
  - AC-422-04
  - AC-422-05
  - AC-422-06
  - AC-422-07
  - AC-422-08
  - AC-422-09
updated: 2026-09-24
---

# CI test portfolio を impact surface 起動の merge gate と main/scheduled regression sweep へ再編成する

## Problem

現行 `.github/workflows/ci.yml` は、source または CI 設定の変更に対して 9 本の API 35/36
emulator instrumentation lane を無条件で全起動する。個々の lane 追加は過去の Issue で得た
regression evidence として妥当でも、portfolio 全体では次の観測可能な問題が生じている。

- 変更箇所と直接関係しない instrumentation failure が merge を阻害する（#352: 単一 JVM
  test 修正 PR が無関係な instrumentation failure で merge evidence を塞がれた。#418:
  変更対象外の lane で SystemUI ANR / Compose timeout / focus 系 intermittent failure）。
- lane 名が Issue 番号ベース（`organizer-instrumentation-issue52-tests` 等）で、現在どの
  production contract を守っている lane かが名前から把握できない。
- Issue 完了時の evidence 慣行として「full workflow N 連続 green」が機械的に要求され、
  対象変更と無関係な CI 環境 failure の解消まで merge evidence に組み込まれてきた
  （#352 / #418 の exit criteria が実例）。
- 新規 test / CI lane 追加時に「既存 lane でカバーできないか」「より低く速い層で代替でき
  ないか」「どの変更で起動するか」を審査する恒久ルールが canonical docs に存在しない。
  追加は Issue ごとに単調に積み上がる一方、削除・降格は行われてこなかった。
- failure-time evidence capture（#315）は issue52 / issue53 lane にしか装備されておらず、
  他 lane の失敗は原因分類に必要な証拠を残せないまま rerun で上書きされうる。

## Outcome

PR の merge gate は「常に必要な permanent gate（repo contract / style / build / JVM unit・
contract test）」と「変更が影響しうる impact surface に対応する instrumentation lane」だけを
起動する。全 lane 実行は main push と scheduled sweep が担い、conditional 化によって PR で
常時走らなくなった coverage が失われない。各 lane と CI 補助処理は contract / 分類 /
起動条件 / fan-out を `docs/engineering/ci-test-portfolio.md` の監査表として持ち、mapping と
workflow の整合は repo-contract validator が機械検証する。intermittent failure は文書化された
分類・証拠・retry 規約に従って扱われ、acceptance evidence は「full workflow N 連続 green」
ではなく変更 risk と対象 surface に対応して選択される。今後の test / CI 追加は canonical
docs に組み込まれた審査ルールを通る。

## Scope

- `ci.yml` の `changes` job を impact surface flag の出力へ拡張し、全 instrumentation lane
  を conditional gate 化する（起動条件の変更であり、test 本体の削除・統合は行わない）。
- main push・scheduled・`workflow_dispatch` による全 portfolio 実行（regression sweep）を
  同一 workflow 内に定義する。
- Issue 番号由来 lane job ID の contract-based naming への改名と、canonical docs の参照更新。
- 全 instrumentation lane への bounded failure-time evidence capture step の拡張（#315 の
  bounded helper を踏襲、continue-on-error）。
- `docs/engineering/ci-test-portfolio.md` を現行 9 lane 構成の監査・mapping 正本として再編
  する（全 job の contract、起動条件、fan-out、分類、過去 failure 分類、実行費用を記録）。
- intermittent failure の分類・証拠・retry 方針と、新規 test / CI lane 追加時の審査ルール、
  risk 比例 evidence 選択原則の canonical docs（`quality-strategy.md`、
  `github-workflow.md`、`AGENTS.md`）への反映。
- lane↔surface mapping と `ci.yml` の整合、および validator 結合 job ID の不変を検証する
  repo-contract validator と self-test の追加。mapping の機械正本は専用の machine-readable
  な map file とし、docs の人間向け監査表（`ci-test-portfolio.md`）との同期も検証する。
- `planner-stress.yml` と `high-risk-gate.yml` は監査対象に含める。planner-stress は既に
  scheduled / manual の exploration evidence という分類に適合するため構造変更しない。
  high-risk-gate の job ID 結合（`organizer-unit-tests` / `check-style` / `build-debug-apk` /
  `final-status`）は維持する。
- 現行 `workflow_call` trigger は維持する（廃止しない）。呼び出し時は全 portfolio を実行
  する。現在の repository 内に直接 caller はないが、将来の release 評価 workflow 等の
  interface として保持する。

## Non-goals

- coverage の削減。PR で起動しなくなった lane は main / scheduled で継続実行する。
  instrumentation test の一律削除・allow-failure 化・unit test 化は行わない。
- test 本体の lane 間移動・統合・削除の実施。監査で Retire / replace 候補と判断したものは
  分類と根拠を記録し、実施は後続 Issue に分離する。
- auto-retry 機構や既知 environment signature の無視リストの新設。retry は分類と証拠の
  規約に従う運用にとどめる。
- production code の変更。
- UI 画面単位などの細粒度 surface 分割の最適化。v1 は contract group 単位とし、必要と
  判断された場合は後続 Issue で精密化する。
- `final-status` / branch protection / high-risk gate の必須 check 構成の変更。

## Domain language

（CI 運用の用語は `docs/engineering/ci-test-portfolio.md` を正本とする。`CONTEXT.md` に
追加する domain 用語はない。）

## Behavior scenarios

### Scenario: docs-only PR

Given `docs/**`、`specs/**`、`*.md` 等の documentation のみの変更
When CI が起動する
Then `validate-repo-contract` 系のみが実行される
And いずれの emulator instrumentation lane も起動しない

### Scenario: planner (pure domain) のみの変更

Given `lawnchair/src/app/lawnchair/organizer/planning/**` 等の純粋な計画module のみの変更
When CI が起動する
Then `check-style`、`build-debug-apk`、`organizer-unit-tests`（permanent gate）が実行される
And planner の contract は JVM corpus / property test が所有するため、emulator lane は起動しない
And main / scheduled sweep では引き続き全 lane が当該変更を通過検証する

### Scenario: organizer UI の変更

Given `lawnchair/src/app/lawnchair/organizer/ui/**` 等の UI surface の変更
When CI が起動する
Then permanent gate に加え、UI contract group に mapping された instrumentation lane
（manual organization UI、onboarding proposal、category override、exchange import UI）が起動する
And UI と無関係な lane（shared-writer、DB migration、restore capture、API 35 production
input）は起動しない

### Scenario: shared contract の変更（fan-out）

Given `lawnchair/src/app/lawnchair/organizer/application/**`、`src/com/android/launcher3/model/LayoutWriteCoordinator.java` 等、複数 lane が所有する shared contract の変更
When CI が起動する
Then `ci-test-portfolio.md` の mapping が定める fan-out 先 lane がすべて起動する
And fan-out 先は mapping 表で機械検証可能な形で定義されている

### Scenario: 未 mapping の source path を含む変更（保守 default）

Given 変更 source file のうち 1 件でも、いずれの impact surface filter にも一致しない
path（新規上流 module、`src/` 直下、`quickstep/`、build script 等）
When CI が起動する
Then 全 source gate と全 instrumentation lane が起動する（fail-closed）
And mapping の隙間が gate の静かな skip として現れない

判定は PR 単位の surface boolean ではなく変更 file 集合に対して行う。mapped path と
未 mapping path が同じ変更に混在する場合も、未 mapping file が 1 件でもあれば全 lane
を起動する。

### Scenario: CI workflow 自体の変更

Given `.github/workflows/**` の変更を含む PR
When CI が起動する
Then 変更対象 gate を含む全 source job が当該 PR 上で自己実行される（現行規則の維持）

### Scenario: main push / scheduled / workflow_call による全量実行

Given main への merge push、scheduled trigger、`workflow_call`、または
`workflow_dispatch`（`full-portfolio` default）
When CI が起動する
Then 変更 path にかかわらず全 portfolio（Permanent gate を含む全 lane）が実行される
And PR で conditional 化した coverage が main / scheduled で継続検証される

`workflow_dispatch` の `full-portfolio=false` は repository contract + Permanent gate
のみ（instrumentation 全 skip）の決定的な高速 smoke 実行を意味する。smoke 実行では
paths-filter の mapping 出力を downstream の起動判断に一切使わず、Permanent gate の全起
動と instrumentation lane の全 skip が event 条件のみから決定する。`workflow_dispatch`
以外の event では smoke 状態は発生しない。

### Scenario: required lane の intermittent failure

Given PR の impact mapping に含まれる lane が失敗した
When failure が処理される
Then その lane の bounded failure-time evidence capture が自動実行され、証拠が artifact として残る
And 失敗は文書化された分類（product regression / deterministic test defect / test
synchronization defect / CI wrapper・artifact defect / emulator・runner environment defect /
unknown）のいずれかに分類されてから再実行される
And「rerun で green になった」ことのみを分類なしの merge evidence として扱わない

### Scenario: mapping 漏れの検出

Given conditional 化によって特定の PR 群で起動しなくなった lane において regression が発生した
When main push または scheduled sweep が全 lane を実行する
Then 当該 regression が main 上で検出され、tracking Issue へ記録される
And conditional 化が coverage の喪失として無検出にならない

## Data and state

- 恒続 data は持たない。impact surface 定義（path filter）の正本は `ci.yml` の `changes`
  job、lane↔surface 対応と fan-out の唯一の normative 正本は
  `tools/repo-contract/ci_portfolio_map.yml` とする。`docs/engineering/ci-test-portfolio.md`
  は contract の説明・監査情報（分類、実測費用、過去 failure、審査記録）を持つ
  human-readable な mirror であり、edge の正本ではない。repo-contract validator が
  map file と workflow の整合（edge 完全一致）を検証する。
- migration、backup/restore、product data への影響はない（CI orchestration と docs のみ）。

## Permissions, privacy, and security

None。job 毎の least-privilege permissions は現行維持、新規 permission・secret・外部通信は
追加しない。scheduled trigger も `contents: read` のみである。

## Accessibility and localization

None。UI を変更しないため（CI / docs のみの変更）。

## Acceptance criteria

- [ ] AC-422-01: `ci.yml` の全 job、`planner-stress.yml`、`high-risk-gate.yml`、および
      evidence capture / artifact upload / `final-status` 集約等の CI 補助処理の各々につい
      て、守る production contract、低層 test では不足する理由、必要な impact surface、
      fan-out、重複、独立実行要件、過去 failure の分類、PR gate 必要性、実行費用を
      `ci-test-portfolio.md` の監査表に記録する。
- [ ] AC-422-02: 全 lane が Permanent / Conditional / Scheduled / Diagnostic / Retire に分類
      され、「なぜ PR gate か / そうでないか」が説明可能である。source 変更というだけで
      全 instrumentation lane を無条件起動する構成を撤廃する（未 mapping 保守 default と
      CI 変更時の自己実行は維持する）。
- [ ] AC-422-03: `changes` job が impact surface flag を出力し、各 conditional lane が対応
      surface の変更時に起動する。shared contract 変更時の fan-out 先が mapping に定義され
      る。lane→surface の対応の機械正本は machine-readable な map file とし、人間向けの監査
      表は `ci-test-portfolio.md` に置く。
- [ ] AC-422-04: 変更 source file 集合のうち 1 件でもいずれの surface にも一致しない
      path があれば、全 source gate と全 instrumentation lane を起動する（per-path
      fail-closed。mapped / unmapped 混在の変更でも発火する）。`.github/workflows/**`
      変更 PR は全 source job を自己実行する。
- [ ] AC-422-05: main push、scheduled trigger、`workflow_call`、および
      `workflow_dispatch`（`full-portfolio` default）は、変更 path によらず Permanent
      gate を含む全 portfolio を実行する。`workflow_dispatch(full-portfolio=false)` は
      明示的な smoke 状態として Permanent gate 全起動・instrumentation 全 skip を event
      条件のみから決定的に実現し、paths 判定に依存しない。acceptance oracle は schedule
      trigger の定義、`workflow_dispatch` による全量経路の実行、および smoke 実行で
      Permanent gate が全起動し instrumentation が全 skip されることである。merge 後の
      実際の scheduled run は後続の追跡 evidence として Issue へ記録する。
- [ ] AC-422-06: Issue 番号由来の lane job ID を contract-based ID に改名し、canonical
      docs の参照を更新する。validator と結合する job ID（`organizer-unit-tests` /
      `check-style` / `build-debug-apk` / `final-status`）は改名せず、high-risk gate は
      引き続き green になる。`final-status` は「当該 PR に必要と判定された gate が完了し
      たこと」を集約する（skip は成功扱い、失敗のみ fail）という意味を docs に明記する。
- [ ] AC-422-07: 全 instrumentation lane が bounded な failure-time evidence capture step
      （continue-on-error）を持ち、intermittent failure の分類 category・証拠保持・retry
      方針を `quality-strategy.md` に正本化する。
- [ ] AC-422-08: 「full workflow N 連続 green」を一律要求しない risk 比例の evidence 選択
      原則と、新規 test / CI lane 追加時の審査ルール（既存 coverage 確認、低層への配置、
      起動条件、重複、scheduled で不足する理由）を `github-workflow.md` /
      `quality-strategy.md` / `AGENTS.md` に反映する。
- [ ] AC-422-09: 次を機械検証する repo-contract validator（self-test 付き）を
      `validate-repo-contract` job に追加する: (a) map file の lane 集合と `ci.yml` の
      instrumentation lane 集合の一致、(b) 各 lane の surface 集合が workflow の `if`
      条件の参照と完全一致、(c) `ci.yml` に定義された全 surface が map file で lane または
      Permanent gate に紐づく、(d) `final-status` の `needs` が Permanent gate と全 lane
      の必須集合と完全一致、(e) validator 結合 job ID（`organizer-unit-tests` /
      `check-style` / `build-debug-apk` / `final-status`）の存在、(f) 全 instrumentation
      lane の capture step 装備、(g) `ci-test-portfolio.md` が全 lane と全 surface を含む。

## Test oracle

| AC | Evidence |
|---|---|
| AC-422-01, 02 | `ci-test-portfolio.md` の監査表（review）と AC-422-09 の validator による表↔workflow 整合検証 |
| AC-422-03, 04 | 実装 PR の CI run（`ci` filter による全 job 自己実行）+ dev branch 上の代表 surface demo run（docs-only / planner-only / UI-only / 未 mapping / mapped+未mapping 混在）における起動・skip の確認、run link を PR に記録 |
| AC-422-05 | `ci.yml` trigger 定義 + `workflow_dispatch`（`full-portfolio=true`）による全量経路の実行 run + `workflow_dispatch`（`full-portfolio=false`）smoke run で Permanent 全起動・instrumentation 全 skip の確認。merge 後の main push run と初回 scheduled run は後続 evidence として Issue へ記録する |
| AC-422-06 | rename 後の CI run 上の job 一覧、canonical docs の参照更新（grep）、実装 PR 上の high-risk-gate 結果 |
| AC-422-07 | `ci.yml` の capture step 定義 + `quality-strategy.md` の方針（review）。capture 動作自体は #315 の self-test が既に検証する bounded helper の再利用 |
| AC-422-08 | `github-workflow.md` / `quality-strategy.md` / `AGENTS.md` の該当 section（review） |
| AC-422-09 | `validate-repo-contract` job 内での validator 本体・self-test 実行（CI run log） |

## Open questions

（`accepted` 時点で解消済みとする）

- scheduled sweep の頻度: weekly とする。main への merge push が毎回全 portfolio を実行す
  るため無活動期間の補完として weekly で十分であり、runner 費用との均衡をとる。daily 化
  は mapping 漏れ検出の遅延が問題視された時に再判断する（非blocking）。
- surface flag の細粒度: v1 は contract group 単位（UI 4 lane を同時に起動する `organizer-ui`
  group を含む）。画面単位分割は運用データを踏まえた後続判断とする（非blocking）。

## Change history

- 2026-09-24: Draft created for #422.
- 2026-09-24: PR #424 review (Changes requested) 対応: per-path fail-closed
  （mapped/unmapped 混在検出）へ契約変更、Permanent gate の全量実行時強制起動と
  `full-portfolio=false` の意味定義、mapping の機械正本（map file）と edge 完全一致
  検証、`workflow_call` 維持、AC-422-05 oracle を dispatch 実証ベースへ変更。
- 2026-09-24: PR #424 re-review (Changes requested) 対応: `full-portfolio=false` を
  paths 判定に依存しない明示的な smoke 状態として再定義（Permanent 全起動・
  instrumentation 全 skip を event 条件のみで決定）、lane↔surface edge の normative
  正本を `ci_portfolio_map.yml` に一意化し `ci-test-portfolio.md` を human-readable
  mirror に明確化、`list-files: json` + 安全な集約経路（shell interpolation 禁止）を
  plan に固定。
