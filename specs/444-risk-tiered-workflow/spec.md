---
issue: "#444"
status: implemented
tier: L
requirements: []
updated: 2026-10-01
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
具体的には、正本に無条件の `plan.md` 要件（`docs/project/github-workflow.md`
Lifecycle §4、`AGENTS.md` 手順4）が残っており、軽量specだけの階層M手続と矛盾する。
また、プロセスの手順語彙（rebind、scope binding gate等の実装・契約語彙）が、
ドメイン用語の正本である `CONTEXT.md` に混在している。

## Outcome

変更が触れる経路のリスクで進め方が3階層（H/M/L）に分かる。階層Hの条件はM/Lに
常に優先し、現行の厳格さは緩めない。階層M（新しい書込み経路を持たないUX/機能）は
accepted軽量specだけで実装を開始でき、実機確認でowner確認を経る。階層L（文書・
テスト・高リスクpathに触れないrefactor）はPRのみで進む。判定はspec/plan/PR本文の
明示と既存の高リスクpath定義を土台に運用され、`CONTEXT.md` はドメイン用語に
再集中する。

## Scope

- `docs/project/github-workflow.md` への「Risk tiers（リスク階層）」節の追加。
  階層H/M/Lの定義、判定方法（既存の高リスクpath一覧と
  `tools/repo-contract/validate_high_risk_evidence.py` を正本として参照し、複製しない）、
  判定の優先順位（階層Hの条件はM/Lに常に優先する。階層LはH条件に該当しない
  文書・テスト・refactorに限る）、格上げ・下げの規則、階層Mのreview往復上限
  （原則2 round）と1 roundの定義を含む。既存の「高リスクPRへの独立エビデンス要求」節と
  「Fork label vocabulary」節は変更しない。
- 「Issue intake」節の必要項目への risk tier 追記と、「Start gate and approval
  lifecycle」節のfeature start gateへの階層別spec要件の追記。
- Lifecycle §4（Implementation plan）の無条件の `plan.md` 要件を階層別に改訂する
  （plan.mdの作成と維持は階層Hの要件とし、階層Mはaccepted軽量specのみで
  実装開始可と明示する）。
- `AGENTS.md` の「Issue駆動・仕様駆動の手順」の手順4（無条件の `plan.md` 作成）を
  階層別に改訂し、節末尾に階層判定の参照を追記する。
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
- patch-surface計測機構（`tools/repo-contract/measure_upstream_patch_surface.py`、
  `docs/assessment/upstream-patch-surface-baseline.{md,json}`）の変更。
  本Issueは既存の計測・記録経路を階層Mのoracleとして参照するだけである。
- 境界語3種（export session、対象scope凍結、中断・破棄・キャンセル）の移動。
  ドメイン語と実装語の境界上にあり、附属草案も保守者判断を求めているため、
  本Issueでは残置し、判断をAC-4の記録とIssue #444コメントへ残す。

## Domain language

- **リスク階層 (Risk tier)**: 変更が触れる経路のリスクに応じた進め方の区分（H/M/L）。
  定義・判定・格上げ規則の正本は `docs/project/github-workflow.md` の Risk tiers 節とする。
  階層Hの条件（新しい書込み経路、migration/recovery/backup契約、上流model/loader
  bridge、高リスクpath一覧への該当）は、階層M/Lの判定より常に優先する。
  プロセスの手順語彙であり、ドメイン用語の正本である `CONTEXT.md` には登録しない。
- **上流UI bridgeのpatch-surface oracle**: 階層Mで上流のUIだけに触れるbridgeを
  含む変更は、同じPRでcandidate HEADを対象とした計測
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  を実行し、その結果を **PR本文**（またはPRに添付したassessment記録）へreportする。
  `--target` / `--upstream` を省略するとbaseline JSONの記録値
  （`main_commit` / `upstream_commit`）を再計測するだけであるため、candidate計測には
  `--target HEAD` が必須である。upstream基準を変える場合（upstream sync等）は
  `--upstream <commit>` も明示する。`--enforce-baseline` は、counted patch file数・
  変更行数がaccepted baselineを超えた場合に失敗する（増加はmandatory review signal）。
  `docs/assessment/upstream-patch-surface-baseline.md`（Accepted、#110。NFR-010の
  計測記録）と同名の `.json` の更新は、 **新しいbaselineを採用する場合だけ** とし、
  採用手順は同文書に従う。通常の階層M PRではbaselineを更新しない。
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

### Scenario: 高リスクpath上のrefactor

Given 変更がbehavior-preservingなrefactorであっても、高リスクpath一覧の
ファイル（例: `ModelWriter.java`）に触れる
When 階層を判定する
Then 判定は階層Hである（階層Hの条件は階層Lより常に優先する。階層Lの
「refactor」は、高リスクpath一覧に触れない、かつ新しい書込み経路等のH条件を
作らない変更に限る）

### Scenario: 階層Mの判定

Given 変更が既存の適用・復旧経路の前段（入口、導線、選択、表示）だけに触れ、
新しいDB書込み・migration・recovery store・上流model/loader bridgeを作らない
When 作者が軽量specを書き、PR本文に判定を記載する
Then 「Risk tier: M」が明示され、accepted軽量specの取得だけで実装を開始できる
（plan.mdは要求されない）。実装PRは実機のスクリーンショット/録画によるowner確認を
含む。独立auditは要求されない。上流のUIだけに触れるbridgeの場合、同じPRで
patch-surfaceの計測結果を記録し、baseline文書の運用規則に従って正当性を示す

### Scenario: 階層の格上げ

Given 階層Mで進めていた変更の実装中に、新しい書込み経路が必要になると判明した
When 実装が書込みに進む前に判定の誤りが確認される
Then 階層をHへ上げて現行手続（spec + plan.md、高リスク対象なら独立エビデンス契約）
へ戻る。下げは、書込みがまだ発生していない段階でのみ、owner decision付きで可能である

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

- [x] AC-1: `docs/project/github-workflow.md` にRisk tiers節が追加されている。
  階層H/M/Lの定義、判定方法（高リスクpath一覧とvalidatorを参照し複製しない）、
  判定の優先順位（H条件はM/Lに常に優先、LはH条件に該当しない文書・テスト・
  refactorに限る）、格上げ・下げの規則、階層Mのreview往復上限と1 roundの定義を含む。
  「Issue intake」に risk tier が、「Start gate and approval lifecycle」のfeature
  start gateに階層別spec要件が追加され、Lifecycle §4（Implementation plan）の
  無条件の `plan.md` 要件が階層別に改訂されている（plan.md必須は階層H。
  階層Mはaccepted軽量specのみで実装開始可）。冒頭の `> Updated:` が更新されている。
  変更後のworkflow文書とAGENTS.mdに、無条件のplan.md要件が残っていないこと
  （`plan.md` への言及は階層条件付きであること）。既存の「高リスクPRへの独立
  エビデンス要求」節と「Fork label vocabulary」節は実質変更されていない。
  （Issue完了条件1の前半）
- [x] AC-2: `AGENTS.md` の「Issue駆動・仕様駆動の手順」の手順4が階層別に改訂され
  （`plan.md` 作成は階層Hの要件）、節末尾にリスク階層の参照（workflow文書の
  Risk tiers節へのlink、階層H/M/Lの手順の要約、階層M specには改善するベンチマーク
  課題と目標（編集負担ベンチマーク、NFR-014）を含める旨）が追記されている。
  「高リスクPRの独立エビデンス」節は実質変更されていない。（Issue完了条件1の後半）
- [x] AC-3: `specs/_template/spec-lite.md` が存在し、階層M用の軽量spec構造
  （frontmatterの `tier: M`、Problem、Benchmark（改善する課題と目標値。正本は
  `docs/engineering/editing-burden-benchmark.md` / NFR-014）、Outcome、
  Scope / Non-goals、Behavior scenarios（2〜4個。失敗時のzero-writeを1つ含む）、
  Verification、Accessibility and localization）を持つ。Verificationには
  (a) owner確認用のスクリーンショット/録画は **実機で取得** し、emulatorは
  補助証跡としてのみ可であること、(b) 上流UI bridgeを含む場合は
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  によるcandidate HEADの計測結果をPR本文へreportすること（baselineの
  `.md`/`.json` 更新は新しいbaselineを採用する場合だけ）、を含む。
  既存 `specs/_template/spec.md` / `plan.md` は変更されていない。（Issue完了条件2）
- [x] AC-4: `CONTEXT.md` から上記12語の定義本文が移動先正本へ移されている。
  `CONTEXT.md` 側には同一見出しの参照（正本へのlink）が残り、既存spec/Issueからの
  参照が切れない。境界語3種は残置され、その理由が本specに記録されている。
  （Issue完了条件3）
- [x] AC-5: `python3 tools/repo-contract/test_validate_high_risk_evidence.py`、
  `python3 tools/repo-contract/validate_repo_contract.py`、
  `python3 tools/repo-contract/test_validate_repo_contract.py`、
  `./gradlew spotlessCheck` が成功する（高リスクpath一覧の整合検証、
  Markdown内部link検証、docs gateを壊していない）。（Issue完了条件5）
- [x] AC-6: 階層Mの初回適用の確認項目（軽量spec作成 → 実装PR本文に Risk tier: M
  を明示 → 実機のスクリーンショット/録画によるowner確認 → review往復≤2 round →
  上流UI bridgeの場合は
  `python3 tools/repo-contract/measure_upstream_patch_surface.py --target HEAD --enforce-baseline`
  の計測結果をPR本文へreport）とpatch-surface oracleが
  本specに定義されている。（Issue完了条件4の整備部分。本PRで達成）
- [x] AC-7: 最初の階層M変更が軽量手順で実証される。実証:
  PR #486（[Issue #452](https://github.com/nunu1733/NunuLauncher/issues/452)、
  2026-10-01 merge）。実証記録:
  [Issue #444のAC-7記録](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5933818698)。
  実証PR本文への `Risk tier: M` 直接記載がなくtier宣言が参照spec冒頭に留まった逸脱は、
  [owner判断](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5934222863)
  によりこの1件に限り受理（waiver。次回以降の階層M PRではPR本文への直接記載が必須）。
  実証後に本specのstatusを `implemented` へ遷移する。（Issue完了条件4の実証部分）

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | diff review（節の存在と内容、Lifecycle §4の階層別改訂、既存2節の非変更、Updatedヘッダ）+ `docs/project/github-workflow.md`/`AGENTS.md` 全文の `plan.md` 言及が階層条件付きであることの確認 + AC-5のself-test |
| AC-2 | diff review（手順4の階層別改訂、追記位置、他節の非変更）+ 無条件plan要件の残存確認 |
| AC-3 | ファイル存在とdiff review（Verification内の実機必須/emulator補助の文言、patch-surface oracle参照、既存template2件の非変更） |
| AC-4 | diff review（12語の移動、参照の残置、境界語3種の残置）+ `validate_repo_contract.py` の内部link検証 |
| AC-5 | 実行commandと結果をPR本文へ記録 |
| AC-6 | 本specの確認項目とoracle定義の存在（本PR） |
| AC-7 | PR #486（Issue #452）と[Issue #444のAC-7記録](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5933818698)。tier宣言位置の逸脱は[owner判断](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5934222863)で受理 |

## Open questions

実装開始前に解消が必要な問いはない。以下は附属草案に対する適合差分と、
Phase 1 review（ChatGPT、round 1）で指摘された項目の解決記録である
（いずれも非blocking。reviewで覆せる）。

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
- **round 1 review（Changes requested、5件）への対応**:
  1. 階層Mでも無条件のplan.md要件が残る（High）→ Lifecycle §4とAGENTS手順4の
     階層別改訂をscopeへ追加し、AC-1/AC-2に「無条件plan要件の残存がないこと」の
     確認を追加（Finding 1）。
  2. H/Lの優先順位が曖昧（Medium）→ 判定規則に「H条件はM/Lに常に優先、LはH条件に
     該当しない文書・テスト・refactorに限る」を明示し、高リスクpath上のrefactorはHと
     なるscenarioを追加（Finding 2）。
  3. 実機/emulatorの契約割れ（Medium）→ owner確認用は実機必須、emulatorは補助証跡と
     して可、をspec-lite templateのVerificationとAC-3に固定（Finding 3）。
  4. AC-6のoracleが完了条件4を実証しない（Medium）→ AC-6（整備: 本PRで達成）と
     AC-7（実証: merge後の最初の階層M変更。本PRでは未達のまま残す）へ分離し、
     本specの `implemented` 遷移を実証後に行うことをplanへ記載（Finding 4）。
  5. patch-surface計測・記録のoracleが未定義（Medium）→ 正本を
     `tools/repo-contract/measure_upstream_patch_surface.py` と
     `docs/assessment/upstream-patch-surface-baseline.md`（Accepted、#110、NFR-010）
     に固定し、Risk tiers本文・spec-lite Verification・AC-6へ同一参照を置く
     （Finding 5）。
- **round 2 review（Changes requested、Finding 5残存・Finding 6新規）への対応**:
  - Finding 5（Medium・残存）: 省略時の `measure_upstream_patch_surface.py` は
    baseline JSONの記録値（`--target` 省略時 `main_commit`、`--upstream` 省略時
    `upstream_commit`）を再計測するだけでcandidate PRを測らない、という指摘は
    toolのCLI実装（`measure_upstream_patch_surface.py:536-564`）と一致する。
    oracleを `--target HEAD --enforce-baseline` のexact invocationへ固定し、
    記録先を「通常の階層M PRはPR本文へreport、baselineの `.md`/`.json` 更新は
    新baseline採用時だけ」へ分離した（Domain language、AC-3、AC-6）。
  - Finding 6（Low）: Test oracle AC-1行の `plan.md`/`AGENTS.md` は誤記で、
    `docs/project/github-workflow.md`/`AGENTS.md` が正。修正した。
- **Issue完了条件4の扱い**: 初回の階層M適用は本PRのmerge後の変更でのみ成立する
  （#443は既に現行手順でmerge済み — PR #467）。最終PRは `Refs #444` でmergeし
  Issueをopenのまま引き渡し、AC-7の実証時にIssue #444へ記録する。ownerが早期closeを
  望む場合は当該判断をIssue #444へ記録する。
  → 解決済み（2026-10-02）: AC-7はPR #486で実証され、
  [AC-7記録](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5933818698)と
  tier宣言逸脱の[owner判断](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5934222863)が
  Issue #444へ記録済み。

## Change history

- 2026-09-27: Draft created for #444. 出典: Issue #444本文と附属草案
  （refocus-drafts/project/github-workflow-tiering.md、再焦点化方針メモ §4.7 R-9）。
  Phase 1 review（ChatGPT）の対象。
- 2026-09-27: Phase 1 review round 1（Changes requested、5件）への対応として改訂。
  Lifecycle §4とAGENTS手順4の階層別改訂を追加、判定の優先順位を明示、
  実機/emulator契約を固定、AC-6/AC-7へ分離、patch-surface oracleを定義。
- 2026-09-27: Phase 1 review round 2（Changes requested、Finding 5残存・Finding 6）へ
  対応として改訂。patch-surface oracleを `--target HEAD --enforce-baseline` の
  exact invocationと記録先の分離（PR本文report / baseline更新は採用時のみ）へ修正、
  Test oracle AC-1行の誤記（`plan.md` → `docs/project/github-workflow.md`）を修正。
- 2026-09-27: Phase 1 review round 3で **Clear**（head
  `60626e660de17efa89c254a44d56bfb3c1eb5abe`）。
  [Issue #444コメント](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5852101118)。
  statusを `accepted` へ遷移。AC-7（初回階層M適用の実証）はmerge後の最初の
  階層M変更で確認し、その後に `implemented` へ遷移する。
- 2026-09-27: Phase 2 review round 1（Changes requested、3件）への対応として実装を修正。
  Start gate・AGENTS手順8の `plan revision` を階層条件付きへ変更（Finding 1）、
  各specのDomain language前書きを「移動対象語の個別宣言＋非対象語は引き続き
  `CONTEXT.md` が正本」へ修正（Finding 2）、Change historyのclear permalinkを
  正しいcomment IDへ修正（Finding 3）。
- 2026-10-01: AC-7達成 — Risk tiers導入（PR #468）後の最初の階層M変更が
  PR #486（Issue #452、2026-10-01 merge）で実証された。軽量spec（spec-lite形式・
  `tier: M`）、実機owner確認（Pixel 9a device evidence）、review往復≤2 round
  （Phase 1は2往復+Clear、実装は1往復+Clear）、patch-surface計測report
  （PR本文AC-8。growth detected → 同一commitでbaseline再採択 → `--verify` PASS）を
  確認。tier宣言がPR本文の直接記載ではなく参照spec冒頭に留まった旨を逸脱として
  [Issue #444のAC-7記録](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5933818698)
  へ明記。statusを `implemented` へ遷移し、Issue #444をcloseする。
- 2026-10-02: PR #496 review round 1（[ChatGPT review](https://github.com/nunu1733/NunuLauncher/pull/496#issuecomment-5934072817):
  Changes requested、3件）への対応。
  Finding 1（高）: AC-6手順の「実装PR本文への `Risk tier: M` 直接明示」に対する
  PR #486の逸脱を[owner判断](https://github.com/nunu1733/NunuLauncher/issues/444#issuecomment-5934222863)
  でこの1件に限り受理（waiver）し、AC-7本文・Test oracleへ反映。
  Finding 2（中）: AC-1〜AC-7のチェック状態を証跡（AC-1〜5はPR #468、AC-6は本PR、
  AC-7はPR #486+owner判断）へ同期し、Open questionsの条件4項目を受入後の状態へ更新。
  Finding 3（低）: `docs/product/requirements.md` のD-013/D-014 link provenanceを
  PR #480へ修正（本PRで同時実施）。
