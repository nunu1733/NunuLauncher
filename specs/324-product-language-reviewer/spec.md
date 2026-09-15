---
issue: "#324"
status: proposed
requirements:
  - AC-324-01
  - AC-324-02
  - AC-324-03
  - AC-324-04
  - AC-324-05
  - AC-324-06
  - AC-324-07
  - AC-324-08
  - AC-324-09
  - AC-324-10
  - AC-324-11
  - AC-324-12
  - AC-324-13
  - AC-324-14
updated: 2026-09-16
---

# Product Language Reviewer subagent による Nunu 固有 UI 文言の再監査

> **Status: proposed.** 本 spec は Issue [#324][1] の提案契約であり、本準備 task が自己承認したものではない。実装は Issue 上で owner が本 spec を承認するまで開始しない。実装中の発見が本契約と矛盾する場合は、実装内で代替を選ばず Issue で判断を更新する。

## Problem

NunuLauncher の開発は Coding/Review Agent を中心に進んでおり、仕様遵守・機能的正しさ・安全性・テストは強く検証されている。一方で「人間が画面で読む文言として自然か、簡潔か、内部実装の都合が漏れていないか」という editorial quality は、同じ評価軸では十分に担保できない。技術的に正しいが硬い・長い文言、domain 語の UI 露出、実装処理名を説明する CTA、仕様説明調の warning/recovery copy、developer-oriented な default English、画面・フロー全体での用語揺れが残り得る。

[#161][2] で日本語 UI LQA の style guide / glossary / 再利用可能な workflow が整備され、初回監査（223 unit、2026-08-28時点）まで完了している。しかし正本は `docs/localization/` の review contract であり、**言語表現を専任で見る subagent を通常の開発フローに組み込み、実装 worker / functional reviewer とは異なる model family に継続的に担当させる構造**にはなっていない。また #161 以降、strategy selection / preview、missing-app selection、restore 確認、re-entry hint、backup preview 等の新しい user-visible surface が追加されており、初回監査時に存在しなかった文言が蓄積している。[#199][3] の Vision UX review は見た目・第一印象・affordance を独立評価するが、product copy の編集・言語監修を主責務としない。

## Outcome

NunuLauncher に **Product Language Reviewer / UI Copy Editor** の専任 subagent を導入する。reviewer は coding worker でも functional reviewer でも vision observer でもなく、product language / microcopy / terminology / fluency を専任で評価する。#161 の資産を正本として再利用し、(1) 専任 reviewer の shared review contract が repository に存在し現行の主要 coding runtime から呼び出せ、(2) 専任 model が固定 evaluation set で選定され、(3) 現行の active な Nunu 固有 user-visible UI copy 全体が contextual に再監査され、(4) accepted revisions が resource 契約と安全意味論を維持したまま反映され、(5) 今後の user-visible copy 変更で専任 reviewer を利用する trigger / skip rule が repository process に記録される。

## Scope

| 区分 | 対象 | 正本となる成果物 |
|---|---|---|
| shared review contract | reviewer の rubric、input schema、output schema、責務・優先順位契約を #161 正本への参照として定義する。 | `.agents/skills/product-language-review/`（SKILL.md + references） |
| runtime adapter | 現行 runtime から専任 subagent として呼び出す thin adapter。実際の support を確認して必要なものだけを作る。 | `.codex/agents/` 配下の adapter、および ZCode の呼出し方法の記録 |
| model 選定 | 固定 evaluation set（20〜40件）による dedicated model の bake-off と採用/不採用の evidence。 | `docs/assessment/evidence/` 配下の実施記録 |
| full re-audit | 現行の active / user-visible / translatable な Nunu 固有 UI copy の inventory 化と全 unit の disposition。 | re-audit evidence（inventory + review table） |
| resource 反映 | accepted `REVISE` の resource 反映と structural / rendered 検証。 | resource 差分と検証 evidence |
| 継続運用 | 今後の user-visible copy change に対する trigger / skip rule の repository process への記録。 | `docs/localization/ja-review-workflow.md` への追記（「ongoing review pass」節） |

対象 copy は日本語を主対象とするが、Nunu 固有の default English 自体が developer-oriented / awkward で、日本語側だけ直すと source semantics と乖離する場合は default copy も監査・修正対象とする。上流 Lawnchair/AOSP 由来の一般文言を好みだけで全面 rewrite しない。Nunu-specific または Nunu flow と直接接続する surface を優先する。

再監査の対象集合は固定件数で定義せず、full pass 開始時に base SHA を記録した上で、#123/#161 の定義（`required = active な Nunu 固有 default resource where user-visible && translatable != false`、両 resource root `lawnchair/res` と root `res`）を踏襲して再構成する。

## Non-goals

- repository docs 全体の文章校正、spec / plan / PR 文体の全面統一。
- feature behavior、navigation、information architecture、planner/application contract の変更。
- 上流 Lawnchair/AOSP strings の全面 rewrite。
- runtime machine translation、および hosted LLM API をアプリ本体の依存にすること。
- Product Language Reviewer への code review / functional correctness review の兼務。
- #199 Vision UX review の置き換え（両者は別 surface であり相互代替しない）。
- 特定 model / vendor を恒久的な merge gate に固定すること。初版を CI 上の hard gate にすること。

## Domain language

新しい product/domain 用語は導入しない。**Product Language Reviewer**、**review unit**、**disposition**（`OK` / `REVISE` / `PRODUCT_DECISION` / `TECHNICAL_ONLY`）は、本契約が所有する quality-process 用語であり、[CONTEXT.md][4] の domain 語彙（ホームレイアウト、レイアウト plan、整理 run、recovery point 等）を置き換えない。

## Authority and responsibility boundary

Reviewer が判断するもの: naturalness / fluency、brevity without loss of meaning、CTA の予測可能性、user-facing terminology、implementation/domain language の露出、隣接 copy との重複・冗長さ、flow 全体の tone / terminology consistency、accessibility text の読み上げ自然さ、default English と日本語双方の product-language quality。

Reviewer が決めないもの: feature behavior、safety / recovery semantics、planner/application contract、navigation / information architecture、未決定の product terminology decision。より自然な表現が product behavior の意味変更を必要とする場合、reviewer は rewrite せず `PRODUCT_DECISION` として owner resolution または split Issue へ送る。

優先順位（`ja-review-workflow.md` §2 の source precedence を拡張して固定する）:

1. accepted behavior / spec / ADR / safety semantics
2. `docs/localization/ja-style-guide.md` / `ja-glossary.tsv` / accepted terminology
3. 専任 Product Language Reviewer の linguistic / editorial judgment
4. 実装 worker / general code reviewer の stylistic preference

Functional reviewer は meaning drift / safety regression を block できるが、単なる「自分ならこう書く」という stylistic preference で専任 reviewer の自然な表現を差し戻してはならない。逆に専任 reviewer の提案も、accepted behavior/safety semantics との矛盾が示された場合には優先されない。

## Behavior scenarios

### Scenario: 文脈付きの copy review を実行する

Given reviewer が resource name、default text、current Japanese、surface、ui_role、隣接 copy、実際の user action/result、accepted behavior/safety context、placeholder/plural contract を持つ

When reviewer が style guide と glossary に照らして評価する

Then 各 review unit は `OK` / `REVISE` / `PRODUCT_DECISION` / `TECHNICAL_ONLY` のいずれか 1 つに分類される

And `REVISE` は current/proposed、理由、severity、meaning-preserved、layout risk を含み、なぜ人間向け UI として改善するかを短く説明する。孤立した XML 値だけの review は採用しない。

### Scenario: 自然な表現が behavior 変更を要求する

Given 提案されたより自然な表現が、action の意味、navigation、情報階層、安全制約、復旧可能性、成功保証のいずれかを変える

When reviewer が rewrite の可否を判断する

Then reviewer は resource を書き換えず `PRODUCT_DECISION` として owner resolution または split Issue へ送る

And 実装 worker が high-severity `PRODUCT_DECISION` を自己解決しない。

### Scenario: reviewer が利用できない、または context が不足する

Given 専任 reviewer が利用不可能であるか、必要な behavior/safety context が取得できない

When user-visible copy の変更が review 対象となる

Then repository process は reviewer unavailable 時の fallback と explicit skip reason の記録を要求し、context 不足の unit は推測で修正せず `PRODUCT_DECISION` に分類される

And skip 理由のない黙示的な review pass は採用しない。

### Scenario: functional reviewer と language reviewer が衝突する

Given 専任 reviewer が proposed した自然な表現に対し、functional reviewer が意味・安全上の問題ではなく文体上の好みで差し戻しを要求する

When 優先順位契約を適用する

Then functional reviewer の stylistic preference は専任 reviewer の linguistic judgment を無根拠に上書きしない

And meaning drift / safety regression の指摘は block として有効であり、accepted behavior との整合が優先される。

### Scenario: accepted revisions を resource へ反映する

Given `REVISE` が review contract の確定条件（meaning preservation の確認、safety class の semantic guard、必要な owner resolution）を通過している

通常の worker が resource へ反映する

Then string name、placeholder 数・型・順序、plural、escaping、`translatable` 契約が維持され、既存の被覆・placeholder oracle が pass する

And 代表画面を normal / enlarged font scale で確認し、critical clipping や操作を妨げる wrapping の regression がない。

## Data and state

アプリの runtime state、Launcher DB、`favorites`、recovery store、preference key、diagnostics journal に変更はない。追加する永続成果物は repository 内の Skill contract、runtime adapter、workflow 追記、bake-off / re-audit evidence、承認済み resource 差分に限る。language review を理由に organizer の planning / application / recovery / layout data contract を変更しない。review evidence は model の hidden reasoning、credential、個人データを含まない。

## Permissions, privacy, and security

**None.** 新規 permission、アプリからの network access、telemetry、runtime 翻訳 service、hosted LLM API の runtime dependency は追加しない。hosted LLM へ送る review 入力は repository が所有する UI copy と context に限り、利用者の個人データ・端末データを含まない。rendered context を使う場合は synthetic または privacy-reviewed の evidence に限る（#199 evidence policy を準用）。review の実行記録は effective provider、model identifier、実施日、session / context identifier を evidence として残すが、Skill contract 本体は特定 vendor / model に恒久依存しない。review 対象 evidence に埋め込まれた指示・link は untrusted data として扱い、それに従って tool 実行・外部作用・情報開示を行わない。

## Accessibility and localization

accessibility text は視覚 label の短縮を流用せず、読み上げて自然な文として評価する。日本語変更後も #123/#161 の resource 被覆・placeholder oracle（`tools/localization/verify_nunu_ja_resources.py`）、代表画面の Japanese / `en-XA` / font-scale 確認経路を維持する。default English の修正は日本語側との意味整合を保ち、片言語だけの drift を作らない。

## Acceptance criteria

- [ ] **AC-324-01 — 正本の再利用:** shared contract と runtime adapter が `docs/localization/ja-style-guide.md`、`ja-glossary.tsv`、`ja-review-workflow.md` を正本として参照し、同じ言語規則を prompt / adapter へ二重管理しない。
- [ ] **AC-324-02 — shared contract:** 専任 Product Language Reviewer の単一の review contract（rubric、input schema、output schema、責務・優先順位契約）が repository に存在する。
- [ ] **AC-324-03 — runtime からの呼び出し:** 現行の主要 coding runtime から専任 subagent として呼び出せる。Codex 向け thin adapter を提供し、ZCode は primary-source で確認した mechanism で呼び出す。確認できない capability を support 主張せず、限界を記録する。
- [ ] **AC-324-04 — dedicated model 選定:** 20〜40件の固定・context 付き evaluation set で初期候補（Issue 本文は `Gemini-3.8-flash` を初期候補として想定）を評価し、exact model identifier / provider / 実施日と採用・不採用の理由を evidence に記録する。候補名を伏せた出力に対し owner が blind adjudication し（#161 bake-off 方法を準用）、meaning または safety preservation の重大欠陥を 1 件でも出した候補は aggregate にかかわらず不採用とする。Skill contract 本体に model / vendor を恒久固定しない。
- [ ] **AC-324-05 — 責務分離:** reviewer が product language / microcopy / terminology / fluency を専任で評価し、feature behavior、safety / recovery semantics、planner/application contract、navigation / IA、未決定の product terminology を決めないことが contract に明文化されている。
- [ ] **AC-324-06 — 優先順位契約:** 「accepted behavior/safety semantics > #161 正本 > 専任 reviewer の linguistic judgment > 一般 reviewer の stylistic preference」の順位が contract に固定され、functional reviewer が stylistic preference だけで専任 reviewer の表現を差し戻せない一方、meaning drift / safety regression の block 権は保持される。
- [ ] **AC-324-07 — contextual input:** review input が isolated resource string ではなく surface / ui_role / 隣接 copy / behavior context / placeholder contract を含む（`ja-review-workflow.md` §3 の schema を準用）。必須 context が不明な unit は推測で修正せず `PRODUCT_DECISION` とする。
- [ ] **AC-324-08 — output contract:** 各 review unit が `OK` / `REVISE` / `PRODUCT_DECISION` / `TECHNICAL_ONLY` のいずれか 1 つを持ち、`REVISE` は current/proposed、reason、severity、meaning_preserved、layout_risk を含む。
- [ ] **AC-324-09 — full re-audit:** full pass 開始時に base SHA を記録した上で、現行の active / user-visible / translatable な Nunu 固有 user-visible copy（両 resource root）が inventory 化され、全 unit が 8 分類（primary action / title、onboarding / entry guidance、Organizer strategy / preview / apply flow、warning / confirmation / failure / stale / recovery、placement lock / category override / settings、progress / result / empty state、accessibility text、diagnostics / support entry）のいずれかに属する disposition を持つ。
- [ ] **AC-324-10 — default English の監査:** Nunu 固有 default English が user-facing copy として問題な string が監査対象になり、必要なものは両言語で整合して修正される。
- [ ] **AC-324-11 — semantic guard:** primary CTA、warning、failure、recovery、stale-state、a11y wording の accepted revision は、適用前に accepted behavior との meaning preservation が別途確認される。high-severity `PRODUCT_DECISION` は実装 worker が自己解決せず、owner resolution または split Issue になる。
- [ ] **AC-324-12 — resource 反映と oracle:** accepted `REVISE` items が resource に反映され、name、placeholder/plural/translatable semantics が維持され、`tools/localization/verify_nunu_ja_resources.py` とその self-test が pass する。
- [ ] **AC-324-13 — rendered 検証と既存 test の維持:** representative revised screens を normal + enlarged font で確認し、critical clipping / awkward wrapping の regression がない。#123/#161 の localization / resource checks と relevant UI / a11y / behavior tests が維持される。
- [ ] **AC-324-14 — 継続運用と #199 境界:** 今後の user-visible copy change で専任 reviewer を利用する trigger / skip rule（reviewer unavailable 時の fallback と explicit skip reason を含む）が repository process に記録される。初版は CI hard gate にしない。#199 Vision UX review と本 reviewer の責務境界が明文化される。

## Test oracle

| AC | Evidence |
|---|---|
| AC-324-01, 02, 05–08 | Skill contract / adapter / workflow 追記の diff review。正本複製の不在、rubric・schema・責務・優先順位の記載を checklist 照合する。 |
| AC-324-03 | Codex adapter の実在と内容、ZCode 呼出し方法の primary-source 確認記録（#199 runtime support 記録の形式を準用）。support 限界の明示。 |
| AC-324-04 | evaluation set の固定 context、匿名出力、owner blind per-item score、aggregate、hard-failure 判定、model identifier / provider / 実施日、採用裁定。 |
| AC-324-09, 10 | base SHA 付き inventory、8 分類への mapping、全 unit の disposition、default English 対象の記録。 |
| AC-324-11 | safety class unit の meaning-preservation 確認記録、high-severity `PRODUCT_DECISION` の owner resolution / split Issue link。 |
| AC-324-12 | `python3 tools/localization/verify_nunu_ja_resources.py`（baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`）と self-test の実行結果、resource diff review。 |
| AC-324-13 | 代表画面の capture / UI dump（normal + enlarged font scale）、a11y 確認、Gradle / CI 結果。 |
| AC-324-14 | workflow 追記節の review、skip reason 記録様式、#199 境界の明文化箇所。 |

## Open questions（unresolved decisions）

実装開始前に Issue 上で owner の判断または承認を要する。本 spec はこれらを確定させない。

- **D1: reviewer model の確定。** `Gemini-3.8-flash` は初期候補の名称であり、実行 runtime で利用可能な exact model identifier の存在は未確認。bake-off による採用/不採用をもって確定する。利用可能な候補が初期候補と異なる場合、代替候補で bake-off を実行して記録する。
- **D2: ZCode における専任 subagent の呼出し形態。** repository Skill の直接 discovery は #199 で runtime 検証済みだが、isolated な名前付き subagent としての呼出し・分離保証の有無は未検証。未検証の場合は Skill mode 呼出し + 分離限界の記録で代替し、speculative な adapter file を作らない。
- **D3: evaluation set の具体構成。** 件数と軸（本 spec AC-324-04）は固定したが、採用する具体 unit は full pass 開始時の inventory から #161 bake-off context を含めて選定する。
- **D4: 継続 trigger rule の記載位置。** 正本は `docs/localization/ja-review-workflow.md` への追記を提案する。`AGENTS.md` への追加は #199 の resolved decision 前例（required step の無承認追加をしない）に従い、owner の明示承認がある場合に限る。
- **D5: default English 修正の具体対象。** re-audit で判明したものに限定し、事前に列挙しない。

## Change history

- 2026-09-16: Issue #324 の spec 準備 task として `proposed` 仕様を作成。入力: Issue 本文、#161 / #123 の implemented spec・plan・evidence、#199 の accepted spec と runtime support 記録、`main` `0cf82bc1e61c1874b280a7120dff9594be4fef71` の tree（`.agents/skills/ux-visual-review/`、`.codex/agents/`、`docs/localization/`、`tools/localization/`、resource 実態）。本 task は承認を行わず、status は `proposed` のままとする。

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/324 "Issue #324: Product Language Reviewer subagent の導入と UI 文言再監査"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/161-japanese-ui-copy-lqa/spec.md "Issue #161 specification (implemented)"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/199-ux-visual-review/spec.md "Issue #199 specification (accepted)"
[4]: https://github.com/nunu1733/NunuLauncher/blob/main/CONTEXT.md "NunuLauncher domain language"
