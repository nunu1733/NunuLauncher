# Implementation Plan: impact-based CI portfolio

> Issue: #422
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

### 現行 portfolio の実測（2026-09-23 時点）

source 変更を含む成功 run [35864884049](https://github.com/nunu1733/NunuLauncher/actions/runs/35864884049)
（PR #420、head `13:07` 開始）の job 別実行時間。source または `.github/workflows/**` 変更で
9 本の emulator lane が全起動し、合計約 96 runner 分 / wall 約 10.7 分を消費する。

| Job | 実行時間 | 起動条件（現行） |
|---|---:|---|
| changes | 0.4分 | 常時 |
| validate-repo-contract | 0.9分 | 常時 |
| check-style | 1.1分 | source または ci |
| build-debug-apk | 5.3分 | source または ci |
| organizer-unit-tests | 5.8分 | source または ci |
| organizer-instrumentation-shared-writer-tests | 8.9分 | source または ci |
| organizer-instrumentation-db-migration-tests | 9.1分 | source または ci |
| organizer-instrumentation-issue299-tests | 9.5分 | source または ci |
| organizer-instrumentation-api35-tests | 9.3分 | source または ci |
| organizer-instrumentation-issue52-tests | 8.7分 | source または ci |
| organizer-instrumentation-issue155-tests | 9.4分 | source または ci |
| organizer-instrumentation-issue99-tests | 8.1分 | source または ci |
| organizer-instrumentation-issue332-tests | 10.2分 | source または ci |
| organizer-instrumentation-issue53-tests | 9.7分 | source または ci |

### 障害・慣行の実績

- #352: JVM test 1 件修正の PR (#416) が、無関係な instrumentation lane の intermittent
  failure で merge evidence を塞がれた。exit criteria に「3 連続 full-workflow green」を
  要求した実例。
- #418: head `16688d1b5d` の 3 attempt で、変更対象外の issue52 / issue99 lane が
  SystemUI ANR・Compose timeout・focus 系で失敗（run 35828114497）。tracking 継続中。
- failure-time evidence capture（#315 実装済み、bounded・continue-on-error）は
  `ci.yml:482` / `ci.yml:649` の issue52 / issue53 lane のみに装備。
- `planner-stress.yml` は週次 scheduled / manual の exploration matrix であり、PR gate
  でない分類として既に適合している。

### 機械的結合（変更してはならない固定点）

- `tools/repo-contract/validate_high_risk_evidence.py:456-460` が job ID
  `final-status`・`organizer-unit-tests`・`check-style`・`build-debug-apk` を文字列参照
  する。これらの job ID は改名しない。
- branch protection の required checks は `final-status` と `high-risk-evidence`
  （[github-workflow.md](../../docs/project/github-workflow.md) §main branch protection）。
- `changes` job の paths-filter は `predicate-quantifier: some-with-excludes` を使う
  （v4 依存の明示的設定。踏襲する）。

推測で決めていないこと: dev branch push における paths-filter の base 解析の実挙動は
新規 branch 初 push の `before` が空になる場合を含め、実装時に demo run で確認する。

## Design

### Modules and interfaces

変更対象は CI orchestration（`ci.yml`）と docs、repo-contract validator のみ。

#### 1. `changes` job: impact surface 出力への拡張

paths-filter に次の filter を追加する（`source` / `ci` は現行維持）。path list は実装時
監査で確定させるが、初期案は次の通り。**全 surface filter に `some-with-excludes` は使わ
ず default 動作**（いずれかの path 一致で true）とし、 excludes は使わない。

```yaml
surface_layout_write:
  - 'src/com/android/launcher3/model/LayoutWriteCoordinator.java'
  - 'src/com/android/launcher3/model/ModelWriter.java'
  - 'src/com/android/launcher3/model/ModelDbController.java'
  - 'lawnchair/src/app/lawnchair/organizer/application/**'
  - 'tests/organizer-instrumentation/com/android/launcher3/organizer/**'  # lane 所有 test の自己起動（実装時に対象 class まで絞る）
surface_db_schema:
  - 'src/com/android/launcher3/provider/**'
  - 'src/com/android/launcher3/model/DatabaseHelper.java'
  - 'src/com/android/launcher3/model/GridSizeMigrationUtil.java'
  - 'lawnchair/src/app/lawnchair/migration/**'
  - <db-migration lane の test class 群>
surface_backup_restore:
  - 'lawnchair/src/app/lawnchair/backup/**'
  - 'src/com/android/launcher3/LauncherBackupAgent.java'
  - <restore capture lane の test class 群>
surface_production_input:
  - 'lawnchair/src/app/lawnchair/organizer/integration/**'
  - <API 35 lane の test class 群、および production input composer が読む
     loader cursor 周りの実 path（監査で確定）>
surface_organizer_ui:
  - 'lawnchair/src/app/lawnchair/organizer/ui/**'
  - 'lawnchair/src/app/lawnchair/ui/**'
  - 'lawnchair/src/app/lawnchair/organizer/personalization/**'
  - 'lawnchair/res/**'
  - <UI 4 lane の test class 群>
surface_jvm:                     # Permanent gate (organizer-unit-tests) が所有する領域
  - 'lawnchair/src/app/lawnchair/organizer/planning/**'
  - 'lawnchair/src/app/lawnchair/organizer/rules/**'
  - 'lawnchair/src/app/lawnchair/organizer/diagnostics/**'
  - 'lawnchair/src/app/lawnchair/organizer/locks/**'
  - 'tests/unit/**'
```

test source の変更が自分の lane を起動するよう、各 surface filter はその lane が実行する
test class の path を含む。test のみの変更（production 無変更）でも当該 lane が自己検証
される。`surface_jvm` は instrumentation lane を持たず Permanent gate（`source` 変更時に
常に起動する `organizer-unit-tests`）が所有する領域を示す。後述の未 mapping 判定で
「JVM test / planner のみの変更が保守 default を発火させない」ために必要である。

**per-path fail-closed（AC-422-04 の中核）**: paths-filter に workflow level で
`list-files: json` を設定し、`source_files` と各 `surface_*_files` を JSON array として
取得する。集合差分の計算は安全な経路でのみ行う。

- `*_files` output は GitHub expression を `run:` へ直接埋め込まず、`env:` 経由で
  （または一時 file 経由で）集約 script へ渡す。shell `eval` と式の直接 interpolation は
  禁止する（[dorny/paths-filter の警告](https://github.com/dorny/paths-filter#notes)に
  従う。`*_files` は PR 由来の filename を含むため信頼できない入力として扱う）。
- 集約は Python（PyYAML 同梱環境で追加依存なし）で JSON array を parse し集合差分を
  取る。quote・空白・shell metacharacter を含む filename で判定が壊れないことを
  self-test で検証する。

```text
unmapped_files = source_files - (∪ surface_*_files)
smoke = event == workflow_dispatch && inputs.full-portfolio == false
instrumentation_enabled = !smoke
full  = !smoke && ( (ci == true)
    || (unmapped_files が 1 件でも存在する)      # mapped/unmapped 混在でも発火
    || event == schedule
    || event == workflow_call
    || (event == push && ref == refs/heads/main)
    || (event == workflow_dispatch && inputs.full-portfolio == true) )

permanent_run = source || ci || full || smoke
```

PR 単位の surface boolean（`source && 全 surface false`）は使わない。mapped path と
未 mapping path の混在 PR が保守 default を回避することを防ぐためである。

`smoke`（`workflow_dispatch(full-portfolio=false)`）では paths-filter の出力を
downstream の起動判断に一切使わない。Permanent gate の全起動と instrumentation の全
skip が event 条件のみから決定的に決まる。paths-filter は非 PR event（dispatch 等）で
直近 commit を変更集合と扱いうるため、dispatch 時の mapping 出力は無視する。

各 job の `if` は次を参照する。global 制御 flag（`permanent_run` /
`instrumentation_enabled` / `full`）と lane 個別の surface 判定を分離し、「通常 PR は
own surface の lane のみ、`full=true` のときだけ全 lane、`smoke=true` では surface 出力に
かかわらず全 instrumentation skip」を式として成立させる。

- Permanent gate（`organizer-unit-tests` / `check-style` / `build-debug-apk`）:
  `needs.changes.outputs.permanent_run == 'true'`（= `source || ci || full || smoke`）
- instrumentation lane:
  `needs.changes.outputs.instrumentation_enabled == 'true' && (needs.changes.outputs.full == 'true' || <own surface flag> == 'true' || ...)`
  （global な「1 surface でも true なら全 lane」という条件は持たせない）

#### 2. lane の conditional 化と改名

各 instrumentation lane の `if` を次の形にする（`needs.changes.outputs` 経由）。

```yaml
if: >-
  needs.changes.outputs.instrumentation_enabled == 'true' &&
  (needs.changes.outputs.full == 'true' ||
   needs.changes.outputs.surface_layout_write == 'true')
```

job ID と surface 対応（v1）。**`organizer-unit-tests` / `check-style` / `build-debug-apk` /
`final-status` は改名しない**。

| 現行 job ID | 新 job ID | 起動する surface |
|---|---|---|
| organizer-instrumentation-shared-writer-tests | （改名なし。既に contract-based） | surface_layout_write |
| organizer-instrumentation-db-migration-tests | （改名なし。既に contract-based） | surface_db_schema |
| organizer-instrumentation-issue299-tests | organizer-instrumentation-restore-capture-tests | surface_backup_restore |
| organizer-instrumentation-api35-tests | organizer-instrumentation-production-input-tests | surface_production_input、surface_layout_write（NestedTransactionTest を含むため） |
| organizer-instrumentation-issue52-tests | organizer-instrumentation-manual-organization-ui-tests | surface_organizer_ui |
| organizer-instrumentation-issue155-tests | organizer-instrumentation-reservation-recovery-tests | surface_layout_write（apply/recovery contract） |
| organizer-instrumentation-issue99-tests | organizer-instrumentation-category-override-tests | surface_organizer_ui |
| organizer-instrumentation-issue332-tests | organizer-instrumentation-exchange-import-ui-tests | surface_organizer_ui |
| organizer-instrumentation-issue53-tests | organizer-instrumentation-onboarding-proposal-tests | surface_organizer_ui |

fan-out（同一 surface が複数 lane を起動する）はこの表で定義され、edge（lane→surface
対応）の normative 正本は後述の `ci_portfolio_map.yml` である（次節 Design 5）。本表は
`ci-test-portfolio.md` に説明つきで mirror する。`surface_organizer_ui` が UI 4 lane を同時
起動するのは v1 の意図的な group 化である（spec Non-goals の通り画面単位分割は後続）。

#### 3. trigger と全量 sweep

```yaml
on:
  push:
    branches: [main, '*-dev']
  pull_request:
  schedule:
    - cron: '30 19 * * 0'   # planner-stress (18:30 UTC 日曜) と時間をずらす
  workflow_dispatch:
    inputs:
      full-portfolio:
        description: 'Run the full lane portfolio regardless of changed paths'
        type: boolean
        default: true
  workflow_call:
```

- `workflow_call` は現行から維持する（repository 内に直接 caller はないが、既存 workflow
  interface の削除には当たらない。将来の release 評価 workflow 等のために保持し、呼び出
  時は `full = true` で全 portfolio を実行する。廃止する場合は別 Issue で caller 有無の
  監査と契約変更を行う）。
- scheduled / dispatch / workflow_call は workflow 全体が起動し、`changes` の `full` 計算
  により Permanent gate を含む全 lane が実行される。paths-filter は非 PR event で base を
  取れない場合に備え、`full` の event 条件が優先するため出力に依存しない。
- `workflow_dispatch` の `full-portfolio=false` は repository contract + Permanent gate
  のみ（instrumentation 全 skip）の決定的な smoke 実行を意味する。paths-filter の出力に
  依存せず event 条件のみで Permanent gate 全起動・instrumentation 全 skip が決定する
  （Design 1 の `smoke` 定義）。
- main push も `full = true` とし、merge 毎に全 portfolio の regression sweep を行う。
- concurrency group は現行（ref 単位・cancel-in-progress）。scheduled run が main push で
  cancel されても push 側が全量を実行するため許容する。

#### 4. failure-time evidence capture の全 lane 装備

issue52 / issue53 lane と同一パターン（`continue-on-error: true` +
`timeout --kill-after=30 300`）の capture step と artifact upload step を残り 7 lane に追加
する。helper 本体（#315 実装）は変更しない。

#### 5. mapping の機械正本と repo-contract validator

lane→surface 対応の機械正本として `tools/repo-contract/ci_portfolio_map.yml` を置く
（AC-422-03）。**edge の normative 正本はこの map file が唯一である**。
`ci-test-portfolio.md` は contract の説明・分類・実測費用・過去 failure・審査記録を持つ
human-readable な mirror であり、edge の正本ではない（docs 側の表と map file が食い違う
場合は map file が正である）。

```yaml
# tools/repo-contract/ci_portfolio_map.yml
lanes:
  organizer-instrumentation-shared-writer-tests:
    surfaces: [surface_layout_write]
  organizer-instrumentation-db-migration-tests:
    surfaces: [surface_db_schema]
  organizer-instrumentation-restore-capture-tests:
    surfaces: [surface_backup_restore]
  organizer-instrumentation-production-input-tests:
    surfaces: [surface_production_input, surface_layout_write]
  organizer-instrumentation-manual-organization-ui-tests:
    surfaces: [surface_organizer_ui]
  organizer-instrumentation-reservation-recovery-tests:
    surfaces: [surface_layout_write]
  organizer-instrumentation-category-override-tests:
    surfaces: [surface_organizer_ui]
  organizer-instrumentation-exchange-import-ui-tests:
    surfaces: [surface_organizer_ui]
  organizer-instrumentation-onboarding-proposal-tests:
    surfaces: [surface_organizer_ui]
permanent_gates: [organizer-unit-tests, check-style, build-debug-apk]
permanent_only_surfaces: [surface_jvm]   # lane を持たず Permanent gate が所有
```

`tools/repo-contract/validate_ci_portfolio.py`（self-test: `test_validate_ci_portfolio.py`、
既存 validator と同じ構成で `validate-repo-contract` job に step 追加）が PyYAML で
`ci.yml` と map file を parse し、次を検証する。

1. map file の lane 集合 == `ci.yml` の `organizer-instrumentation-*` job 集合。
2. 各 lane の `surfaces` 集合 == その job の `if` 条件内の `surface_*` flag 参照集合
   （**edge 完全一致比較**。docs↔workflow の mapping drift を検出する）。global 制御
   flag（`full` / `instrumentation_enabled` / `permanent_run` 等の `surface_*` 以外の
   参照）は比較対象から除外する。
3. `ci.yml` の `changes` job に定義された全 `surface_*` output が、map file 上でいずれか
   の lane または `permanent_only_surfaces` に紐づく（未使用 surface / 幽霊 surface 検出）。
4. `final-status` の `needs` == {changes, validate-repo-contract, build-debug-apk,
   check-style, organizer-unit-tests} ∪ 全 instrumentation lane（exact set 比較。Permanent
   gate の集約漏れも検出する）。
5. `organizer-unit-tests` / `check-style` / `build-debug-apk` / `final-status` job が存在
   する（high-risk validator 結合の固定点）。
6. 全 instrumentation lane が capture step（`capture-emulator-failure-evidence.sh` 参照）
   を持つ。
7. `docs/engineering/ci-test-portfolio.md` が全 lane ID と全 surface 名を含む
   （docs 同期の存在検査。各 cell の記述内容は review が所有する）。

失敗時は即 exit 1。

#### 6. docs 再編

- `ci-test-portfolio.md`: Issue #96 時代の内容を前提とした現状記述を、本 Issue の監査表
  （contract・監査情報の正本、かつ map file の human-readable mirror）へ再編
  mapping 正本へ再編（status: Implemented → 更新日付と本 Issue への参照を更新）。全 job の
  監査表（AC-422-01 の項目）、分類、mapping/fan-out 表、failure 分類の実績、代表 run の
  実測時間を記録する。
- `quality-strategy.md`: CI gates section を新構成へ更新。intermittent failure の分類
  category・証拠保持・retry 方針、および「新規 test / CI lane 追加時の審査ルール」section
  を追加。
- `github-workflow.md`: Execution and approval contract に risk 比例 evidence 選択原則
  （「full workflow N 連続 green」を一律要求しない）を追加。
- `AGENTS.md`: テスト規約に CI portfolio 追加審査の要点を追記し、正本は
  `quality-strategy.md` / `ci-test-portfolio.md` への参照とする。
- 過去の `docs/assessment/*.md` に残る旧 lane 名は歴史記録のため書き換えない。

### Data flow

```text
push/PR/schedule/dispatch/workflow_call
  → changes job（paths-filter list-files(json) + 安全な集約 script で unmapped/smoke/full 計算）
  → permanent gate（validate-repo-contract / style / build / unit）: permanent_run（source||ci||full||smoke）
  → conditional lane: instrumentation_enabled（!smoke）&&（full || own surface）
  → final-status: needs = permanent + repo-contract + 全 lane、skip は成功扱い（現行 grep 逻辑変更なし）
```

### Alternatives rejected

- **fail-open mapping（未 mapping → 最小 gate）**: 新規上流 module 取り込みが静かな
  coverage skip になる。Issue の「path mapping だけで安全性を判断せず」に反するため却下。
- **scheduled 用に別 workflow file で lane 定義を複製**: 定義が drift するため却下。
  単一 `ci.yml` の event 条件で実現する。
- **test class 単位の ownership registry（外部 YAML）**: test class 単位の粒度は
  `ci.yml` の class filter と二重管理になり、`--tests` 引数との整合検証が複雑化するため
  却下。v1 は lane↔surface 単位の `ci_portfolio_map.yml`（`changes` job 内の path filter
  と対になる）で十分。
- **dorny/paths-filter の `some-with-excludes` を surface filter へ適用**: surface は
  excludes を持たないため不要。`source` filter のみ現行設定を踏襲する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `.github/workflows/ci.yml` | surface filter 追加、list-files(json) による per-path unmapped 計算、`smoke`/`full`/`permanent_run`/`lanes_run` 計算、lane `if` 条件、Permanent gate の full/smoke 起動化、job 改名、schedule/dispatch/workflow_call trigger、capture step 全 lane 装備 | 本 Issue の実装本体 |
| `tools/repo-contract/ci_portfolio_map.yml` | lane→surface / permanent gate / permanent-only surface の機械正本 | AC-422-03, 09 の比較基準 |
| `docs/engineering/ci-test-portfolio.md` | 監査・分類・failure 実績の正本へ再編し、map file と同じ edge の human-readable mirror 表を含む | AC-422-01, 02, 06 の正本（edge は map file が正本） |
| `docs/engineering/quality-strategy.md` | CI gates section 更新、failure 分類・retry 方針、test/CI 追加審査ルール | AC-422-07, 08 |
| `docs/project/github-workflow.md` | risk 比例 evidence 選択原則 | AC-422-08 |
| `AGENTS.md` | テスト規約へ CI portfolio 審査の要点追記 | AC-422-08（運用の可視性） |
| `tools/repo-contract/validate_ci_portfolio.py` | map file↔ci.yml edge 完全一致・`final-status` needs exact set・固定点検証 | AC-422-09 |
| `tools/repo-contract/test_validate_ci_portfolio.py` | validator self-test | AC-422-09 |
| `specs/422-impact-based-ci-portfolio/spec.md` | status 更新（accepted → implemented） | 状態管理 |

## Migration and recovery

- product data・schema への影響なし（CI / docs / tooling のみ）。
- rollback: `ci.yml` の当該 commit を revert するのみで旧構成（全 lane 常時起動）へ戻る。
  validator も同 commit に含めるため、revert で同時に消える。
- branch protection・required checks（`final-status` / `high-risk-evidence`）は job ID 不変
  のため影響なし。high-risk validator の期待する job 名も不変。
- scheduled trigger の誤爆は workflow disable で即停止できる。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-422-01, 02 | 監査表の review + 表↔workflow 整合は AC-422-09 validator | `python3 tools/repo-contract/validate_ci_portfolio.py` |
| AC-422-03, 04 | 実装 PR の CI run（`ci` filter で全 job 自己実行）。代表 surface demo は `*-dev` branch への push で起動/非起動を実証: (a) docs-only (b) planner-only (c) organizer-ui のみ (d) 未 mapping path のみ (e) mapped+未 mapping の混在（fail-closed 発火）。各 run の started/skipped job 一覧を PR に記録 | GitHub Actions（push event on `422-*-dev` demo branches） |
| AC-422-05 | `ci.yml` trigger 定義 + `workflow_dispatch`（`full-portfolio=true`）による全量経路の実行 run + `workflow_dispatch`（`full-portfolio=false`）smoke run で Permanent gate 全起動・instrumentation 全 skip を直接確認。merge 後の main push run と初回 scheduled run は後続 evidence として Issue comment へ記録する（scheduled は週次のため初回が翌週以降になる点は分離して扱う） | GitHub Actions |
| AC-422-06 | rename 後 run の job 一覧、`grep -rn "organizer-instrumentation-issue" docs/ AGENTS.md` が canonical docs で空、実装 PR 上の high-risk-gate green | gh run view / grep |
| AC-422-07 | capture step 定義の review + validator 検査 6.、`quality-strategy.md` 方針の review | `python3 tools/repo-contract/test_validate_ci_portfolio.py` |
| AC-422-08 | 3 文書の該当 section review | — |
| AC-422-09 | `validate-repo-contract` job 内での self-test 実行（CI run log） | CI |

dev branch demo の注意: 新規 branch 初 push で paths-filter の base が意図通り取れない
場合は、demo を各 branch の 2 commit 目（main を base にした追加 commit）で実施する。
それでも不安定なら draft PR で同一検証を行う。demo branch は検証後に削除する。

## Documentation updates

- [ ] spec status/history（draft → accepted → implemented）
- [ ] CONTEXT.md（変更なし: domain 用語追加なし）
- [ ] DESIGN.md（変更なし: system 構造変更なし）
- [ ] ADR（作成しない: workflow 変更は spec/plan + portfolio 正本で足りる。恒久かつ高コスト
      な判断になった場合は後続で昇格）
- [ ] AGENTS.md（CI portfolio 審査の要点）
- [ ] `quality-strategy.md` / `github-workflow.md` / `ci-test-portfolio.md`（本文の通り）

## Execution checklist

- [ ] 現行 lane 全てについて監査表を作成（contract・起動条件・実測時間・過去 failure 分類）。
- [ ] surface filter の path list を実装確認（実在 path・test class の所在）。
- [ ] `ci.yml` 変更（surface・list-files(json) による安全な per-path unmapped 計算・
      smoke/full・rename・schedule・workflow_call 維持・Permanent gate の full/smoke 起動化・
      capture）。
- [ ] map file + validator + self-test 追加、`validate-repo-contract` job へ組込み
      （filename 安全性の test を含む）。
- [ ] docs 再編（portfolio / quality-strategy / github-workflow / AGENTS）。
- [ ] 実装 PR の CI 全量 green を確認。
- [ ] dev branch demo（docs-only / planner-only / ui-only / unmapped / 混在）で起動・skip 実証。
- [ ] `workflow_dispatch`（full-portfolio=true）run で全量経路を、
      `workflow_dispatch`（full-portfolio=false）smoke run で Permanent 全起動・
      instrumentation 全 skip を実証。
- [ ] merge 後: main push run と初回 scheduled run を Issue へ記録（後続 evidence）し、
      残課題を分離。
