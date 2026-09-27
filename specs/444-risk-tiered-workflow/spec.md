---
issue: "#444"
status: draft
tier: L
requirements: []
updated: 2026-09-27
---

# リスク階層に応じた進め方の選択が正本文書上で機能する

> Risk tier: L（maintenance/docs-only。product behaviorを変えず、高リスクpath一覧に触れない。
> 出典: [Issue #444](https://github.com/nunu1733/NunuLauncher/issues/444) 本体と附属草案
> （再焦点化方針メモ §4.7 R-9、`refocus-drafts/project/github-workflow-tiering.md`）。）

## Problem

現行のworkflowは、すべてのfeatureを「accepted spec + plan + Worker/Review/Owner/Merge
operatorの分離 +（高リスクなら）独立audit + high-risk gate」で扱う。この厳格さは
Launcher DB書込み・migration・recoveryには正しく機能しているが、新しい書込み経路を
持たないUX/機能（popupへのshortcut追加、方法選択の省略、導線変更など）にも同じ手順を
適用すると、spec・review往復・独立auditのコストが変更の実リスクと釣り合わない。
また、プロセスの手順語彙（rebind、scope binding gate等の実装・契約語彙）が、
ドメイン用語の正本である `CONTEXT.md` に混在している。

## Outcome

変更が触れる経路のリスクで進め方が3階層（H/M/L）に分かれる。階層Hの厳格さは
現行どおり緩めず、階層M（新しい書込み経路を持たないUX/機能）は軽量spec + 実機確認で
進められ、階層L（文書・テスト・refactor）はPRのみで進む。判定はspec/plan/PR本文の
明示と既存の高リスクpath定義を土台に運用され、`CONTEXT.md` はドメイン用語に再集中する。

## Scope

- `docs/project/github-workflow.md` への「Risk tiers（リスク階層）」節の追加。
  階層H/M/Lの定義、判定方法（既存の高リスクpath一覧と
  `tools/repo-contract/validate_high_risk_evidence.py` を正本として参照し、複製しない）、
  格上げ・下げの規則、階層Mのreview往復上限（原則2 round）と1 roundの定義を含む。
  既存の「高リスクPRへの独立エビデンス要求」節と「Fork label vocabulary」節は
  変更しない。
- 「Issue intake」節の必要項目への risk tier 追記と、「Start gate and approval
  lifecycle」節のfeature start gateへの階層別spec要件の追記。
- `AGENTS.md` の「Issue駆動・仕様駆動の手順」節末尾への階層判定の参照追記。
- `specs/_template/spec-lite.md`（階層M用軽量spec template）の追加。
  既存 `specs/_template/spec.md` / `plan.md` は変更しない。
- `CONTEXT.md` のうち、実装・契約語彙に分類できる12語の定義本文を、所有する
  specの Domain language または `DESIGN.md` へ移動し、`CONTEXT.md` 側には
  同一見出しの参照（正本へのlink）を残す。

## Non-goals

- 階層Hの厳格さの緩和（メモ §6）。
- `high-risk-gate` workflow・`tools/repo-contract/validate_high_risk_evidence.py`・
  高リスクpath一覧の変更（判定は既存定義を参照するだけ）。
- label語彙の変更（階層はlabelではなくspec/Issue/PR本文の明示で運用する。
  メモ §12 B5で確定済み）。
- 過去Issue・specの遡及的な階層付け替え。
- 階層判定の機械検証の新設（frontmatterの `tier:` は判定の明示であり、
  機械検証しない。附属草案で確定済み）。
- 境界語3種（export session、対象scope凍結、中断・破棄・キャンセル）の移動。
  ドメイン語と実装語の境界上にあり、附属草案も保守者判断を求めているため、
  本Issueでは残置し、判断をAC-4の記録とIssue #444コメントへ残す。

## Domain language

- **リスク階層 (Risk tier)**: 変更が触れる経路のリスクに応じた進め方の区分（H/M/L）。
  定義・判定・格上げ規則の正本は `docs/project/github-workflow.md` の Risk tiers 節とする。
  プロセスの手順語彙であり、ドメイン用語の正本である `CONTEXT.md` には登録しない。
- 既存語彙の正本移動（12語）。移動先の現状はplan.mdのCurrent evidenceで確認済み:

| 語彙 | 移動先（移動後の正本） | 移動先に既存の定義 |
|---|---|---|
| organizer durable status (永続整理状態) | `DESIGN.md` §4.2（定義ブロックを新設） | 本文言及のみ |
| export-scoped ID (Export Item Reference) | [spec 204](../../specs/204-ai-personalization-context-intent-contract/spec.md) Domain language | あり |
| 取り込み成功状態 (Import Success State) | [spec 328](../../specs/328-exchange-import-success-state/spec.md) Domain language | あり |
| 取り込み破棄 (Import Discard) | [spec 328](../../specs/328-exchange-import-success-state/spec.md) Domain language | あり（rev.2） |
| 手段別失敗投影 (Failure Remedy Projection) | [spec 373](../../specs/373-import-display-reprojection/spec.md) Domain language | あり |
| 原因別remedy (Cause-Specific Remedy) | [spec 375](../../specs/375-scope-remedy-rebind/spec.md) Domain language | あり |
| rebind (process死後再開 / fresh run rebind) | [spec 375](../../specs/375-scope-remedy-rebind/spec.md) Domain language | あり |
| 選択復元初期値 (Selection Restore Initial Values) | [spec 375](../../specs/375-scope-remedy-rebind/spec.md) Domain language | あり |
| インポート正規化 (Import Normalizer) | [spec 329](../../specs/329-import-normalizer/spec.md)（Domain language節を新設） | なし（本文言及のみ） |
| 交換セッション置換確認 (Session Replacement Confirmation) | [spec 205](../../specs/205-external-agent-exchange/spec.md) Domain language | あり |
| scope binding gate (scope束縛検証) | [spec 331](../../specs/331-exchange-target-scope-coupling/spec.md) Domain language | あり |
| scope-bound依頼破棄 (scope-bound request discard) | [spec 417](../../specs/417-scope-first-method-choice/spec.md) Domain language | あり |

移動の規則: 定義本文は移動先正本へ統合する（両者に内容差がある場合は契約内容を
失わないよう統合し、新しい方を採用する）。`CONTEXT.md` 側には見出しと1〜2行の
位置づけ、正本へのlinkを残し、既存の参照・anchorを切らない。移動先specの
「`CONTEXT.md` への追加用語案 (受入時に反映)」等の正本位置の記述は、移動後の
所有関係を示す文へ更新する（spec 374等の「正本は `CONTEXT.md`」の記述行を含む）。

## Behavior scenarios

### Scenario: 階層Hの判定

Given 変更がLauncher DBへの新しい書込み経路を作る、または高リスクpath一覧に当たる
When 作者がspec/planを書き、PR本文に判定を記載する
Then 冒頭に「Risk tier: H」と理由が明示され、現行手続（accepted spec + plan、
Execution and approval contract、高リスク対象なら独立エビデンス契約）が適用される

### Scenario: 階層Mの判定

Given 変更が既存の適用・復旧経路の前段（入口、導線、選択、表示）だけに触れ、
新しいDB書込み・migration・recovery store・上流model/loader bridgeを作らない
When 作者が軽量specを書き、PR本文に判定を記載する
Then 「Risk tier: M」が明示され、軽量spec + 実装PR + 実機確認で進められる。
独立auditは要求されない。上流のUIだけに触れるbridgeの場合、同じPRで
patch-surfaceの計測・記録が更新される

### Scenario: 階層の格上げ

Given 階層Mで進めていた変更の実装中に、新しい書込み経路が必要になると判明した
When 実装が書込みに進む前に判定の誤りが確認される
Then 階層をHへ上げて現行手続へ戻る。下げは、書込みがまだ発生していない段階でのみ、
owner decision付きで可能である

### Scenario: 階層Mのreview往復の上限

Given 階層Mのspec reviewで2 roundを超えて論点が残った
When ownerが残る論点を列挙して判断する
Then 先へ進めるか、scopeを縮小するか、階層Hへ格上げするかのいずれかが決まる
（1 roundは「1回のreview recommendationと、それに対する対応の組」であり、
条件解除のためのevidence追加とその再確認は同じround内の対応として数える）

### Scenario: 高リスクpath一覧の整合の維持

Given workflow文書へRisk tiers節が追加された
When `tools/repo-contract/` のself-testを実行する
Then `github-workflow.md` の `### 適用条件` 節のpath一覧とvalidator定数の
set-equality検証（DocConsistencyTests）が引き続き成功する
（新節はpath一覧を複製せず、`### 適用条件` 見出しを複製しない）

## Data and state

- 永続化するdata、migration、backup/restoreへの影響は `None`（文書変更のみ）。
- 正本の置き場所の変更: 上記12語の定義の正本が `CONTEXT.md` から各所有spec /
  `DESIGN.md` へ移る。`CONTEXT.md` は参照を保持し、既存spec/Issueからの参照を切らない。

## Permissions, privacy, and security

- `None`（外部送信・permission・sensitive dataの変更なし。進め方の文書変更である）。

## Accessibility and localization

- 該当しない（UI変更なし）。文書の日本語表記は既存の正本文書に揃える
  （「階層H/M/L」、リスク階層）。

## Acceptance criteria

- [ ] AC-1: `docs/project/github-workflow.md` にRisk tiers節が追加されている。
  階層H/M/Lの定義、判定方法（高リスクpath一覧とvalidatorを参照し複製しない）、
  格上げ・下げの規則、階層Mのreview往復上限と1 roundの定義を含む。
  「Issue intake」に risk tier が、「Start gate and approval lifecycle」のfeature
  start gateに階層別spec要件が追加され、冒頭の `> Updated:` が更新されている。
  既存の「高リスクPRへの独立エビデンス要求」節と「Fork label vocabulary」節は
  実質変更されていない。（Issue完了条件1の前半）
- [ ] AC-2: `AGENTS.md` の「Issue駆動・仕様駆動の手順」節末尾に、リスク階層の
  参照（workflow文書のRisk tiers節へのlink、階層H/M/Lの手順の要約、階層M specには
  改善するベンチマーク課題と目標（編集負担ベンチマーク、NFR-014）を含める旨）が
  追記されている。「高リスクPRの独立エビデンス」節は実質変更されていない。
  （Issue完了条件1の後半）
- [ ] AC-3: `specs/_template/spec-lite.md` が存在し、階層M用の軽量spec構造
  （frontmatterの `tier: M`、Problem、Benchmark（改善する課題と目標値。正本は
  `docs/engineering/editing-burden-benchmark.md` / NFR-014）、Outcome、
  Scope / Non-goals、Behavior scenarios（2〜4個。失敗時のzero-writeを1つ含む）、
  Verification、Accessibility and localization）を持つ。
  既存 `specs/_template/spec.md` / `plan.md` は変更されていない。（Issue完了条件2）
- [ ] AC-4: `CONTEXT.md` から上記12語の定義本文が移動先正本へ移されている。
  `CONTEXT.md` 側には同一見出しの参照（正本へのlink）が残り、既存spec/Issueからの
  参照が切れない。境界語3種は残置され、その理由が本specに記録されている。
  （Issue完了条件3）
- [ ] AC-5: `python3 tools/repo-contract/test_validate_high_risk_evidence.py`、
  `python3 tools/repo-contract/validate_repo_contract.py`、
  `python3 tools/repo-contract/test_validate_repo_contract.py`、
  `./gradlew spotlessCheck` が成功する（高リスクpath一覧の整合検証、
  Markdown内部link検証、docs gateを壊していない）。（Issue完了条件5）
- [ ] AC-6: 階層Mの初回適用の確認項目が本specに定義されている（軽量spec作成 →
  実装PR本文に Risk tier: M を明示 → 実機のスクリーンショット/録画のowner確認 →
  review往復≤2 round → 上流UI bridgeの場合はpatch-surface記録の更新）。
  初回適用の実記録は、本変更後の最初の階層M変更（例:
  [Issue #452](https://github.com/nunu1733/NunuLauncher/issues/452)）で行い、
  [Issue #444](https://github.com/nunu1733/NunuLauncher/issues/444) へ記録する。
  （Issue完了条件4。初回適用は本PRのmerge後の変更で成立するため、本PRはこれを
  達成主張せず `Refs #444` でmergeし、Issueをopenのまま引き渡す）

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | diff review（節の存在と内容、既存2節の非変更、Updatedヘッダ）+ AC-5のself-test |
| AC-2 | diff review（追記位置、他節の非変更） |
| AC-3 | ファイル存在とdiff review（既存template2件の非変更） |
| AC-4 | diff review（12語の移動、参照の残置、境界語3種の残置）+ `validate_repo_contract.py` の内部link検証 |
| AC-5 | 実行commandと結果をPR本文へ記録 |
| AC-6 | 本specの確認項目の存在（本PR）。初回適用の記録はmerge後のIssue #444コメントで確認 |

## Open questions

実装開始前に解消が必要な問いはない。以下は附属草案に対する適合差分と、
保守者判断が必要だった項目の記録である（いずれも非blocking。reviewで覆せる）。

- **附属草案からの適合差分**:
  (1) 草案の階層節本文に含まれる「直接編集（ADR-0013）とその最初の実装は階層Hである
  （メモ§4.7）」の文は、`docs/adr/` にADR-0013が未収録（refocus-drafts/adr/ に草案のみ）
  のためrepository内の文書からは参照できない。階層Hの定義
  （「Launcher DBへの新しい書込み経路を作る」）が同一の判定をもたらすため本文から省略し、
  ADR-0013受入時（#445）に当該ADR側へ階層Hの扱いを記録する。
  (2) 草案中の「メモ§4.7」等のrefocus-drafts参照とRF-IDは、repository外の出典を指すため、
  repository内の記述（正本path、編集負担ベンチマークは
  `docs/engineering/editing-burden-benchmark.md`）へ置き換える。
  (3) 附属草案§4の語彙表の行番号はCONTEXT.md 241行時点の目安であり、現行（257行）では
  ずれるため、語彙名で対象を特定する。
  (4) 移動先の原案に対する修正: 取り込み破棄は spec 328（rev.2定義の所有者。草案原案の
  spec 374は参照側）、手段別失敗投影は spec 373（#373 spec。草案原案のspec 375は
  SCOPE_MISMATCH系のremedy契約のみを所有）、scope binding gateは spec 331
  （`DESIGN.md` gate 13の所有記述と一致）。
- **1 roundの数え方（Issue #444の未解決事項）**: 「条件解除のためのevidence追加とその
  再確認は同じround内の対応として数える」提案を採用した。1 roundの定義
  （recommendation + 対応の組）の自然な読みであり、条件付き承認だけで往復予算を
  消費しない。ownerが異なる粒度を望む場合はworkflow文書の当該1文を修正する。
- **階層判定のPR本文への明示**: 附属草案の階層節本文は「判定はspec（階層M）または
  plan（階層H）の冒頭に明示」までを含むが、Issue #444 のScope 2は「判定はspec/plan
  （またはmaintenance Issue）と実装PRの本文に明示し、Reviewが確認する」を要求する。
  後者を満たす文を判定節へ含める。
- **Issue完了条件4の扱い**: 初回の階層M適用は本PRのmerge後の変更でのみ成立する
  （#443は既に現行手順でmerge済み — PR #467）。本specはこれをAC-6として
  「確認項目の定義 + 初回適用時のIssue #444への記録」へ具体化し、最終PRは
  `Refs #444` でmergeしてIssueをopenのまま引き渡す。ownerが早期closeを望む場合は
  当該判断をIssue #444へ記録する。

## Change history

- 2026-09-27: Draft created for #444. 出典: Issue #444本文と附属草案
  （refocus-drafts/project/github-workflow-tiering.md、再焦点化方針メモ §4.7 R-9）。
  Phase 1 review（ChatGPT）の対象。
