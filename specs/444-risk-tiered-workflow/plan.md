# Implementation Plan: リスク階層に応じた進め方の軽量化

> Issue: #444
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- `docs/project/github-workflow.md`（363行、2026-09-27時点）の構造:
  Principle → Work item types → Repository target and upstream boundary →
  Lifecycle（Issue intake / Specification / Ready判定 / **Implementation plan** /
  Execution and approval contract の各節…）→ 高リスクPRへの独立エビデンス要求
  （`### 適用条件` に高リスクpath一覧）→ Fork label vocabulary → Branch and commit
  convention → Agent handoff → Parallel work。冒頭に `> Updated:` を持つ。
- **無条件のplan.md要件（本改訂で階層別に改訂する箇所）**:
  - Lifecycle §4（Implementation plan）: 「同じspec directoryの `plan.md` に、
    現在codeの根拠、変更module、interface/seam、migration、rollback、testを記載する。」
  - `AGENTS.md`「Issue駆動・仕様駆動の手順」手順4: 「spec承認後に `plan.md` を作り、
    変更するmodule、seam、migration、rollback、検証を記載する。」
  - Start gate（`### Start gate and approval lifecycle`）のfeature bullet:
    「`status: accepted` のspecと、そのaccepted内容を含むcommit SHA、plan revision。」
  - このままだと階層M（軽量spec + 実装PR + 実機確認）と矛盾するため、
    plan.md要件を階層Hに限定する改訂を上記3箇所へ入れる。
- `tools/repo-contract/test_validate_high_risk_evidence.py` の `DocConsistencyTests` は、
  `github-workflow.md` の `### 適用条件` 節内のbacktick path token
  （`lawnchair/src/` または `src/` で始まるもの）を抽出し、validator定数
  （`HIGH_RISK_PATH_PREFIXES` / `HIGH_RISK_PATH_FILES`）とset-equalityを検証する。
  → 新節は (a) `### 適用条件` 見出しを複製しない、(b) 高リスクpathに該当する
  backtick tokenを書かない、の2つの制約を守れば壊れない。
- `tools/repo-contract/validate_repo_contract.py` はMarkdown内部linkを検証する。
  本変更で追加する相対linkはすべて実在pathへ解決する必要がある。
- **patch-surface計測の既存機構（階層Mの上流UI bridge時のoracleとして参照する）**:
  - 計測tool: `tools/repo-contract/measure_upstream_patch_surface.py`
    （`--verify` / `--enforce-baseline` / `--refresh-pins`。self-test:
    `test_measure_upstream_patch_surface.py`）。
  - 記録の正本: `docs/assessment/upstream-patch-surface-baseline.md`
    （Status: Accepted、Issue #110、NFR-010の計測記録）と同名の `.json`。
  - 運用規則（同文書）: patch surfaceはmaintenance-risk inventoryであり、
    増加はmandatory review signal。新しいbaselineの採用は同文書の手順に従い、
    owning Issue・bridge責務・代替案分析の記録を要求する。
- `CONTEXT.md`（257行、2026-09-27時点）: 附属草案§4の行番号（241行時点）からずれている。
  移動対象12語の現行位置は語彙名で特定する（organizer durable status 103–105、
  export-scoped ID 135–137、取り込み成功状態 159–161、取り込み破棄 163–165、
  手段別失敗投影 167–169、原因別remedy 171–173、rebind 175–177、選択復元初期値 179–181、
  インポート正規化 187–189、交換セッション置換確認 195–197、scope binding gate 207–209、
  scope-bound依頼破棄 239–241。残置3種: export session 139–141、
  中断・破棄・キャンセル 227–229、対象scope凍結 231–233）。
- 移動先の現状（確認済み）:
  - spec 375（implemented）: Domain language節に「原因別remedy」「rebind」
    「選択復元初期値」の定義が既に存在する。
  - spec 328（accepted）: Domain language節に「取り込み成功状態」「取り込み破棄
    (revision 2)」の定義が既に存在する。
  - spec 373（implemented）: Domain language節に「手段別失敗投影」の定義が存在する。
  - spec 204（accepted）: Domain language節に「export-scoped ID」の定義が存在する。
  - spec 205（implemented）: Domain language節に「交換セッション置換確認」の定義が
    存在する。
  - spec 331（implemented）: Domain language節に「scope binding gate」の定義が
    存在する。
  - spec 417（implemented）: Domain language節に「scope-bound依頼破棄」の定義が
    存在する。
  - spec 329（implemented）: Domain language節が存在しないため、節を新設して
    「インポート正規化」の定義を統合する。
  - `DESIGN.md` §4.2: `durableOrganizerStatus`（spec 271）への本文言及はあるが、
    「organizer durable status」の定義ブロックは存在しないため、小節を追加する。
  - 各specのDomain language前書きは「`CONTEXT.md` への追加用語案 (受入時に反映)」の
    形であり、正本の反転後に当該1行を更新する。spec 374は「用語の正本は `CONTEXT.md`
    （#365改訂済み）である」と記載するため、当該1行を更新する。
- Issue #444 の完了条件4（階層Mの初回適用）について: #443（AI相談の凍結）は
  現行手続でmerge済み（PR #467）。軽量手順の初回適用は、本変更後の最初の階層M変更
  （例: #452「ホーム画面から整理を開始」。workspace optionsへの追加はメモ§4.7で
  階層M確定の上流UI bridge候補）となる。spec.mdのAC-7は本PRでは未達のまま残し、
  実証後に本specを `implemented` へ遷移する。
- 実機/emulatorの契約: Issue #444本文とメモ§4.7の階層M定義は「実機のスクリーン
  ショットまたは録画のowner確認」。附属草案のtemplateは「実機（またはemulator）」と
  していた。本改訂ではowner確認用を **実機必須**、emulatorを補助証跡に限定して
  統一する（spec.md AC-3、spec-lite templateのVerification）。
- 編集負担ベンチマークの正本は `docs/engineering/editing-burden-benchmark.md`
  （B1〜B7、NFR-014。#441で受入）。
- `refocus-drafts/` はrepository未収録（untracked）。repository内の文書から
  出典を参照する場合は正本pathのみを使う。

## Design

### Modules and interfaces

- 変更はrepository内の文書のみ。コードmodule、interface、seamの変更なし。
- validator・self-test・CI workflow・高リスクpath一覧・patch-surface計測機構は
  変更しない（階層判定とoracleは既存の定義・記録を参照するだけ）。
- 階層判定の運用面: spec/plan冒頭（maintenanceはIssue本文）と実装PR本文の明示で行い、
  Reviewが確認する。機械検証は新設しない。

### Data flow

振る舞いの変更なし。語彙定義の参照方向が「specが用語案を出し受入時に `CONTEXT.md`
へ反映する」から「実装語彙はspec/`DESIGN.md` が正本、`CONTEXT.md` は参照を保持する」
へ変わる。

### Alternatives rejected

- **階層をlabelで運用**: label語彙の変更はNon-goal（メモ§12 B5で確定）。
  spec/Issue/PR本文の明示を採用する。
- **階層定義を別ファイル（`docs/project/github-workflow-tiering.md`）へ新設**:
  附属草案は既存workflow文書への節追加を指示している。正本の分散を避ける。
- **階層判定の機械検証（frontmatter `tier:` のvalidator等）**: 本Issueの非対象
  （附属草案で確定）。frontmatterは判定の明示のみに使う。
- **`CONTEXT.md` 語彙の一斉全移動**: 境界語3種（export session、対象scope凍結、
  中断・破棄・キャンセル）は保守者判断事項（附属草案の未解決事項）のため残置し、
  判断記録を分離する。残り12語のみ実施する。
- **ADR-0013への参照をworkflow文書へ記載**: ADR-0013は `docs/adr/` 未収録
  （refocus-drafts/adr/ に草案のみ）。参照不能な判断への言及を避け、
  「Launcher DBへの新しい書込み経路を作る」という階層H条件で同一の判定が得られる。
  ADR-0013受入時（#445）に当該ADR側へ階層Hの扱いを記録する。
- **無条件のplan.md要件を残したまま階層節だけ足す**（round 1 review Finding 1で
  指摘）: 階層Mの軽量化が正本上で成立しなくなるため採用しない。Lifecycle §4・
  Start gate・AGENTS手順4を階層別に改訂する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| docs/project/github-workflow.md | 「Work item types」節の直後に「Risk tiers（リスク階層）」節を追加（階層H/M/Lの定義、判定、優先順位（H条件はM/Lに常に優先、LはH条件に該当しない文書・テスト・refactorに限る）、格上げ・下げ、review往復2 roundと1 roundの定義、上流UI bridge時のpatch-surface oracle参照）。「Issue intake」の必要項目にrisk tierを1行追加。「Start gate and approval lifecycle」のfeatureに「階層Mは軽量specのacceptedで足りる。階層Hは現行どおりspec + plan.md」を追加。Lifecycle §4（Implementation plan）を階層別に改訂（plan.mdの作成は階層Hの要件。階層Mはaccepted軽量specのみで実装開始可）。冒頭 `> Updated:` に#444と日付を追記 | 階層定義と判定の正本（AC-1） |
| AGENTS.md | 手順4を階層別に改訂（「spec承認後に `plan.md` を作り…」→ plan.md作成は階層Hの要件、階層Mはaccepted軽量specのみで実装開始可）。節末尾に階層参照の段落を追加（workflow文書のRisk tiersへのlink、階層H/M/Lの手順の要約、階層M specへのベンチマーク課題と目標の要求） | agent作業手順の正本（AC-2） |
| specs/_template/spec-lite.md | 新設。階層M用軽量spec（frontmatterに `tier: M`、Problem、Benchmark、Outcome、Scope / Non-goals、Behavior scenarios（2〜4個。失敗時のzero-writeを1つ含む）、Verification、Accessibility and localization）。Verificationに (a) owner確認用スクリーンショット/録画は実機必須（emulatorは補助証跡として可）、(b) 上流UI bridge時は `python3 tools/repo-contract/measure_upstream_patch_surface.py` の計測結果の記録と `docs/assessment/upstream-patch-surface-baseline.md` の運用規則に従った差分の正当性提示、を含む。B1〜B7・NFR-014は `docs/engineering/editing-burden-benchmark.md` への相対link（`../../docs/...`） | 階層M手続の実体（AC-3） |
| CONTEXT.md | 12語の定義本文を移動先正本へ統合し、当該見出しを1〜2行の参照（正本link、#444で移動の注記）へ置換。境界語3種は残置 | 語彙整理（AC-4） |
| DESIGN.md | §4.2に「Durable status vocabulary」小節を新設し「organizer durable status」の定義を統合 | 移動先正本（AC-4） |
| specs/328 / 373 / 375 / 204 / 205 / 331 / 417 の各spec.md | 該当用語の定義を統合（内容差がある場合は契約内容を保持して統合）し、Domain language前書きの正本表示を更新 | 移動先正本（AC-4） |
| specs/329/spec.md | Domain language節を新設し「インポート正規化」の定義を統合 | 移動先正本（AC-4） |
| specs/444-risk-tiered-workflow/{spec.md,plan.md} | 本spec/plan | 追跡 |

## Migration and recovery

- 文書変更のみのためschema/rule migration、backup/restore互換への影響なし。
  rollbackはgit revertで戻る。
- 参照切れは `validate_repo_contract.py`（Markdown内部link検証）で検出する。
  `CONTEXT.md` 側は同一見出し + 正本linkを保持するため、既存文書からの参照
  （anchorを含む）は切れない。
- 失敗中のrollback: 語彙移動は語ごとに独立した編集であり、部分的に戻しても
  不整合は残らない（正本と参照の組が語単位で閉じている）。

## Verification

```bash
python3 tools/repo-contract/test_validate_high_risk_evidence.py
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
./gradlew spotlessCheck
```

- docs-only差分のため、CIでは `organizer-unit-tests` 等のsource jobはpath filterで
  skipし、`validate-repo-contract` が実行される（quality-strategy.mdのとおり）。
- **無条件plan要件の残存確認**（AC-1/AC-2）: 変更後の `docs/project/github-workflow.md`
  と `AGENTS.md` について `grep -n "plan.md" ...` を行い、`plan.md` への言及が
  すべて階層条件付き（H必須 / Mは不要と明示）であることをdiff reviewで確認する。
  無条件の「plan.mdを作る」要件が残っていれば本ACは失敗とする。
- 語彙移動は語ごとに「移動後の正本本文」と「CONTEXT.mdの参照」の対応を
  PR本文のチェックリストで確認する。

## 実装上の判断

- **新節の挿入位置**: 「Work item types」節の直後（附属草案「『Work item types』節の後、
  『Lifecycle』節の前」。現在は「Repository target and upstream boundary」節が両者の間に
  あるため、Risk tiers節は「Repository target…」の前に置く）。
- **DocConsistencyTests制約**: 新節に `### 適用条件` 見出しを複製せず、
  `lawnchair/src/`・`src/` で始まるbacktick path tokenを書かない。高リスクpath一覧への
  参照は節anchor（`#高リスクprへの独立エビデンス要求`）とvalidator名で行う。
- **判定の優先順位**（round 1 review Finding 2対応）: 階層Hの条件はM/Lに常に優先する。
  階層Lは「高リスクpath一覧に触れず、新しい書込み経路等のH条件を作らない」文書・
  テスト・refactorに限る。高リスクpath上のbehavior-preserving refactorは階層Hとして
  扱う（spec.mdのscenarioに追加済み）。
- **実機/emulatorの統一**（round 1 review Finding 3対応）: owner確認用の
  スクリーンショット/録画は実機必須。emulatorは補助証跡としてのみ可
  （spec-lite templateのVerificationとAC-3に同一文言を置く）。
- **AC-6/AC-7の分離とspec遷移**（round 1 review Finding 4対応）: AC-6（整備:
  確認項目とoracleの定義）は本PRで達成。AC-7（実証: 最初の階層M適用）は本PRでは
  未達のまま残し、最終PRは `Refs #444` でmergeしてIssueをopenのまま引き渡す。
  本specのstatus遷移（`implemented`）はAC-7の実証後に行う。
- **patch-surface oracle**（round 1 review Finding 5対応）: 計測は
  `python3 tools/repo-contract/measure_upstream_patch_surface.py`、記録の正本は
  `docs/assessment/upstream-patch-surface-baseline.md` / `.json`
  （Accepted、#110、NFR-010）。増加はmandatory review signalであり、
  baselineの採用は同文書の手順に従う。この参照をRisk tiers本文・
  spec-lite Verification・AC-6の確認項目へ同一の内容で置く。
- **1 roundの数え方**（附属草案の未解決事項）: 「1 roundは1回のreview recommendationと、
  それに対する対応の組。条件解除のためのevidence追加とその再確認は同じround内の対応と
  して数える」を提案として採用する（spec.mdのOpen questions参照。owner判断で変更可）。
- **語彙移動の統合規則**: 定義本文は移動先正本へ統合する。両者に内容差がある場合は
  契約内容を失わないよう統合する（新しい方の改訂を採用し、失われる契約文があれば
  移動先へ取り込む）。`CONTEXT.md` 側は見出し + 1〜2行の位置づけ + 正本linkを残し、
  `_Avoid_` 行は移動先へ統合する。
- **移動先の修正**（附属草案の移動先原案からの差分。根拠はspec.md Open questions）:
  取り込み破棄 → spec 328、手段別失敗投影 → spec 373、scope binding gate → spec 331。

## Change history

- 2026-09-27: Draft created for #444（spec.mdと同時にPhase 1 reviewの対象）。
- 2026-09-27: Phase 1 review round 1（Changes requested、5件）への対応として改訂。
  Lifecycle §4・Start gate・AGENTS手順4の階層別改訂をChange setへ追加、
  判定の優先順位を明示、実機/emulator契約を統一、AC-6/AC-7へ分離と
  spec遷移時期の記載、patch-surface oracleの定義を追加。
