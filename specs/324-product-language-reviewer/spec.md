---
issue: "#324"
status: implemented
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
  - AC-324-15
updated: 2026-10-15
---

# Product Language Reviewer subagent による Nunu 固有 UI 文言の再監査

> **Status: implemented**（2026-10-15 受領: PR #602 review round 2 Approve — [comment 6193306647][29]。実装: PR #603。実装時の残要件 — AC-324-04 の owner blind 採点、fresh-session での Gemini challenger 実行可否確認（plan D2 の adapter discovery 検証を兼ねる）、AC-324-13 の実機 owner 確認 — は PR #603 と Issue に明示済みで、完了をもって本 Issue を close する。実装中の発見が本契約と矛盾する場合は、実装内で代替を選ばず Issue で判断を更新する。

## Risk tier（[github-workflow.md](../../docs/project/github-workflow.md) Risk tiers 準拠）

**Risk tier: M（resource 反映 PR）+ L（それ以外の PR）。** 判定理由: accepted `REVISE` の resource 反映は user-visible な表示変更であるが、Launcher DB への書込み経路・schema migration・recovery store・上流 model/loader bridge を一切作らず変更しない（strings.xml のみ）。高リスクpath一覧にも当たらない。よって反映 PR は階層M、Skill / adapter / workflow 追記 / bake-off・re-audit evidence の各 PR は階層L（docs / tooling）である。階層H に相当する構成要素はない。

階層Mの手続き上の位置づけ: 本 spec は spec-lite を超える全文 spec であり、これをもって階層M の accepted spec 要件を満たす（軽量化は上限ではなく下限である）。階層M の evidence 規則（owner 確認は実機のスクリーンショット / 録画、emulator は補助証跡のみ）は AC-324-13 に反映する。NFR-014 の編集負担 benchmark は純粋な文言修正には該当課題がないため適用せず、その理由（copy 修正であり操作数・書込み契約を変えない）を PR へ記録する。

## Problem

NunuLauncher の開発は Coding/Review Agent を中心に進んでおり、仕様遵守・機能的正しさ・安全性・テストは強く検証されている。一方で「人間が画面で読む文言として自然か、簡潔か、内部実装の都合が漏れていないか」という editorial quality は、同じ評価軸では十分に担保できない。技術的に正しいが硬い・長い文言、domain 語の UI 露出、実装処理名を説明する CTA、仕様説明調の warning/recovery copy、developer-oriented な default English、画面・フロー全体での用語揺れが残り得る。

[#161][2] で日本語 UI LQA の style guide / glossary / 再利用可能な workflow が整備され、初回監査（223 unit、2026-08-28時点）まで完了している。しかし正本は `docs/localization/` の review contract であり、**言語表現を専任で見る subagent を通常の開発フローに組み込み、実装 worker / functional reviewer とは異なる model family に継続的に担当させる構造**にはなっていない。また #161 以降、strategy selection / preview、missing-app selection、restore 確認、re-entry hint、backup preview、外部エージェント交換（[#205][5]: entry / privacy / 送信前確認 / session 置換確認 / generate / transport / import と typed failure 分類、[#331][6]: scoped entry / 選択freeze / `SCOPE_MISMATCH`、[#329][7] + [#332][8]: clipboard / file import の入力 UI、認識形式表示、raw detail、normalization 失敗 copy、[#330][9] の意味変更に追従した `exchange_failure_incomplete_coverage` の文言改訂、[#327][11]: interview-first の capability 説明・会話 flow 案内・framing hint の文言改訂と `exchange_import_accepted` の削除、[#328][12]: 取り込み成功状態・破棄確認・summary plurals・strategy busy / frozen 案内、[#337][13]: カテゴリ参照失敗 copy と privacy / disclosure copy のカテゴリ言及改訂）、custom category の authoring / assignment（[#336][10]: 作成・rename・削除・削除の部分状態・capacity / conflict 等 28 entry + plurals 2件、root `res` 両言語）、さらに [#361][14] の TO-BE UX decision に基づく migration series（[#366][16] organizer hub、#367 materials 再配置、#368 strategy picker 再配置、#369 run 表示統合、#370 onboarding hub 接続、#371 usage access JIT 要求、[#372][17] AI相談 request flow、#373 import 表示の手段別失敗投影、#374 durable imported intent、#375 scope remedy rebind、#376 durable status recovery entry、#377 post-migration cleanup、[#398][18] 下部優先領域、#417 scope-first method choice。#362 の migration 管理は完了し closed）と、[#448][19] の per-item edit actions（`homeedit_menu_*` / `homeedit_dialog_*` / `homeedit_error_*` 等 20 entry、両言語、新規 Nunu 固有 `homeedit_` prefix、[#447][20] の edit surface decision と ADR-0013/0014/0015 に基づく）、[#450][23] の edit undo（`homeedit_undo_label` と undo 失敗分類 `homeedit_undo_error_*` 10 entry）、[#451][24] の duplicate surplus item 警告（`manual_organization_*duplicate_launch_target*` 4 entry）、[#508][25] の visual before/after preview と per-item exclusion・replanning（`manual_organization_diagram_*` / `manual_organization_exclud*` / `manual_organization_replanning*` / `manual_organization_unplaced_candidates_heading` 19 entry。いずれも両言語）等の新しい user-visible surface が大量に追加されており、初回監査時に存在しなかった文言が蓄積している（Nunu 固有 prefix 5 種の string/plurals は、post-cutover main `905afbd5b2` の 2026-10-15 時点で `lawnchair/res` に 538 entry（default）/ 535 entry（ja）、root `res` に 86/86。前回 2026-09-28 snapshot からの増分は +33 で、default/ja の差 3 は既知の訳出欠落 3 件。ADR-0018 Phase 4 cutover（2026-10-13、Epic [#516][26]）で main baseline は Lawnchair 16 `43a21b43d7` へ切替わったが、fork commit の replay で既存 Nunu 文言の値は一切変化していない（`c5a7840b88` ↔ `905afbd5b2` で value 変更 0・削除 0）ため、re-audit の対象集合は現行 main の tree からそのまま再構成できる）。この間の copy 追加は専任の language review を経ておらず、実際に #161 が checked-in した resource oracle（`tools/localization/verify_nunu_ja_resources.py`）は、監査完了後の PR で追加された strings に対して 2026-10-15 時点で 17 件の finding（#161 監査後の PR 由来の `manual_organization_*` 13 件の placeholder/plural 契約 — #376 由来の `manual_organization_recovery_remaining_hours` を含む、[#336][10] 由来の日本語 resource 未提供 1 件、同 only-other plural 契約 2 件、および ADR-0018 cutover で upstream 16-dev から持ち込まれた `res/values` の `work_profile_scheduler_intent`（`translatable="false"` の空 resource）に対する empty-resource finding 1 件 — oracle の empty-resource check は Nunu 固有集合ではなく file 全体に適用されるため、rebase 由来の upstream string が新規に引っかかる）を出し、oracle と self-test が現行 main で失敗している（oracle は CI に組み込まれていないため回帰は検知されず、`exchange_` / `homeedit_` prefix の strings は required set 対象外のため、[#443][21] が default のみに追加した `exchange_ai_consultation_toggle_label` / `exchange_ai_consultation_toggle_description` の日本語未提供は oracle 上に現れていない。詳細は plan current evidence）。さらに、#161 以降の copy の大部分は LLM agent が起草しており、技術的に正しくても生成文体特有の違和感（いわゆる **AI 感 / AI register**）— 日本語では定型の前置き・言い切らない文末・名詞化された冗長な述語（「〜することができます」）・硬い漢語優先の直訳調・語尾と文長の単調さ、英語では過剰な対比構文（"not just X but Y"）や特定高頻度語（delve / showcase / seamless 等）の密集 — が画面に残る恐れがある。#161 資産は naturalness を評価軸として持つが、AI register を明示的な検出軸として持たない。本 spec は Web 調査（[issue-324-ai-register-research.md][27]、2026-10-15）に基づく AI-register checklist を review contract に追加し、誤検出（人間も常用する表現）への対策を含めて運用する（AC-324-15）。[#199][3] の Vision UX review は見た目・第一印象・affordance を独立評価するが、product copy の編集・言語監修を主責務としない。

## Outcome

NunuLauncher に **Product Language Reviewer / UI Copy Editor** の専任 subagent を導入する。reviewer は coding worker でも functional reviewer でも vision observer でもなく、product language / microcopy / terminology / fluency を専任で評価する。#161 の資産を正本として再利用し、(1) 専任 reviewer の shared review contract が repository に存在し現行の主要 coding runtime から呼び出せ、(2) 専任 model が固定 evaluation set で選定され、(3) 現行の active な Nunu 固有 user-visible UI copy 全体が contextual に再監査され、(4) accepted revisions が resource 契約と安全意味論を維持したまま反映され、(5) 今後の user-visible copy 変更で専任 reviewer を利用する trigger / skip rule が repository process に記録され、(6) AI register（AI 感）の検出軸が根拠付き checklist として review contract に含まれ、bake-off と full pass の評価軸として使用される。

## Scope

| 区分 | 対象 | 正本となる成果物 |
|---|---|---|
| shared review contract | reviewer の rubric、input schema、output schema、責務・優先順位契約、AI register（AI 感）検出の evidence-backed checklist を #161 正本への参照として定義する。 | `.agents/skills/product-language-review/`（SKILL.md + references。`ai-register-tells.md` を含む） |
| runtime adapter | 現行 runtime から専任 subagent として呼び出す thin adapter。実際の support を確認して必要なものだけを作る。 | `.codex/agents/` 配下の adapter、および ZCode の呼出し方法の記録 |
| model 選定 | 固定 evaluation set（20〜40件）による dedicated model の bake-off と採用/不採用の evidence。AI-register 軸を含む。 | `docs/assessment/evidence/` 配下の実施記録 |
| full re-audit | 現行の active / user-visible / translatable な Nunu 固有 UI copy の inventory 化と全 unit の disposition。 | re-audit evidence（inventory + review table） |
| resource 反映 | accepted `REVISE` の resource 反映と structural / rendered 検証。 | resource 差分と検証 evidence |
| 継続運用 | 今後の user-visible copy change に対する trigger / skip rule の repository process への記録。 | `docs/localization/ja-review-workflow.md` への追記（「ongoing review pass」節） |

対象 copy は日本語を主対象とするが、Nunu 固有の default English 自体が developer-oriented / awkward で、日本語側だけ直すと source semantics と乖離する場合は default copy も監査・修正対象とする。上流 Lawnchair/AOSP 由来の一般文言を好みだけで全面 rewrite しない。Nunu-specific または Nunu flow と直接接続する surface を優先する。

再監査の対象集合は固定件数で定義せず、full pass 開始時に base SHA を記録した上で、#123/#161 の定義（`required = active な Nunu 固有 default resource where user-visible && translatable != false`、両 resource root `lawnchair/res` と root `res`）を踏襲して再構成する。#123/#161 の required set は resource name prefix（`organizer_`、`manual_organization_`、`organization_onboarding_`）で Nunu 固有集合を構成しており、#205 で追加された `exchange_` prefix と [#448][19] で追加された `homeedit_` prefix（baseline `505dbc40e6154` 時点で 0 件の新規 Nunu 固有 prefix）は現行 oracle の対象外である。再構成時は post-#161 の Nunu 固有 prefix（少なくとも `exchange_` と `homeedit_`）を required set と #161 oracle の被覆の両方に含める。

## Non-goals

- repository docs 全体の文章校正、spec / plan / PR 文体の全面統一。
- feature behavior、navigation、information architecture、planner/application contract の変更。
- 上流 Lawnchair/AOSP strings の全面 rewrite。
- runtime machine translation、および hosted LLM API をアプリ本体の依存にすること。
- Product Language Reviewer への code review / functional correctness review の兼務。
- #199 Vision UX review の置き換え（両者は別 surface であり相互代替しない）。
- 特定 model / vendor を恒久的な merge gate に固定すること。初版を CI 上の hard gate にすること。

## Domain language

新しい product/domain 用語は導入しない。**Product Language Reviewer**、**review unit**、**disposition**（`OK` / `REVISE` / `PRODUCT_DECISION` / `TECHNICAL_ONLY`）、**AI register（AI 感）** は、本契約が所有する quality-process 用語であり、[CONTEXT.md][4] の domain 語彙（ホームレイアウト、レイアウト plan、整理 run、recovery point、外部エージェント交換、交換パッケージ、送信前確認等）を置き換えない。AI register の検出 checklist（`references/ai-register-tells.md`）は本契約が所有する評価の入力であり、#161 正本（style guide / glossary）と矛盾する場合には #161 正本が優先する。checklist の tell は統計的な editing trigger であって機械的な禁止語リストではなく、誤検出の反例（人間も常用する表現）を checklist 内に明記する。

再監査は #161 監査後に追加された surface（外部エージェント交換、import 入力 UI、custom category authoring、interview-first の capability 説明、取り込み成功状態、organizer hub / 材料 / AI相談の依頼、per-item edit actions を含む）も対象にするが、その user-facing 用語の一部は `docs/localization/ja-glossary.tsv` にまだ登録されていない（2026-10-15 時点で glossary は 22 term のまま（`docs/localization/` は #161 完成 commit `92a490a2f8` 以降未変更）であり、exchange 系・ユーザー定義カテゴリ系・interview-first 系・hub / 材料 / 依頼系・edit undo 系の用語を含まない）。一方で CONTEXT.md は exchange 系 domain concept（持ち帰りIntent取り込み、インポート正規化、交換フレーミング、取り込み成功状態、取り込み破棄、判断なし項目、整理方針確認等）、custom category 系 domain concept（カテゴリidentity、ユーザー定義カテゴリ、アクティブカテゴリカタログ等）、TO-BE migration 系 domain concept（Organizer hub、材料、AI相談の依頼、手段別失敗投影、原因別remedy、rebind、選択復元初期値、下部優先領域等）、配置先ポリシー（ADR-0015）の日本語名を定義している。ただし #444 の docs 再編で実装・契約語彙の定義は各所有 spec と DESIGN.md へ移動しており、CONTEXT.md は domain 用語の正本のままである。reviewer は UI 文言の用語を CONTEXT.md の domain 語彙との整合で評価し、既存 glossary entry・CONTEXT.md のいずれにも対応づけできない user-facing 用語は、glossary 追加提案（terminology decision）として扱う。accepted terminology の追加・変更は `ja-glossary.tsv`（#161 正本）に記録し、Skill や adapter へ複製しない。

## Authority and responsibility boundary

Reviewer が判断するもの: naturalness / fluency、brevity without loss of meaning、CTA の予測可能性、user-facing terminology、implementation/domain language の露出、隣接 copy との重複・冗長さ、flow 全体の tone / terminology consistency、accessibility text の読み上げ自然さ、default English と日本語双方の product-language quality、AI register（AI 感）の有無（evidence-backed checklist に基づく。tell の密度と反復を見て、単独の出現で機械的に判断しない）。

Reviewer が決めないもの: feature behavior、safety / recovery semantics、planner/application contract、navigation / information architecture、未決定の product terminology decision。より自然な表現が product behavior の意味変更を必要とする場合、reviewer は rewrite せず `PRODUCT_DECISION` として owner resolution または split Issue へ送る。

優先順位（`ja-review-workflow.md` §2 の source precedence を拡張して固定する）:

1. accepted behavior / spec / ADR / safety semantics
2. `docs/localization/ja-style-guide.md` / `ja-glossary.tsv` / accepted terminology
3. 専任 Product Language Reviewer の linguistic / editorial judgment
4. 実装 worker / general code reviewer の stylistic preference

tier 1 には accepted な product decision 文書も含まれる。`docs/product/organizer-to-be-ux.md`（[#361][14]、accepted）が Organizer の TO-BE 情報設計・遷移・data timing の長期正本である。TO-BE の UI 反映を所有していた [#362][15] は完了して closed しており、migration series（#366〜#377、#398、#417）は main に取り込まれた。したがって現行 main の surface は post-migration surface であり、re-audit は migration を待つ必要なく base SHA 時点の現行 surface をそのまま対象にする。edit actions 関連の accepted decision（ADR-0013 直接編集の書込み契約、ADR-0014 edit surface、ADR-0015 新規app配置先ポリシー）も tier 1 の behavior context として読む。[#442][22] の最終結論C が計画した Android 16/17 upstream 同期 rebase は ADR-0018（[Epic #516][26]）として実行され、Phase 4 cutover（2026-10-13）で main baseline は Lawnchair 16 `43a21b43d7` に切替わった。現行 main は post-cutover surface であり、full pass の base SHA は現行 main から直接選ぶ。以降に upstream 同期 rebase が再度入った場合は base SHA の取り直しと差分の再 review で対応する（Re-entry rule と同様の規則）。reviewer は TO-BE decision（用語・語彙の統一方針を含む）を behavior context として読み、それと矛盾する提案をしない。

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

Then string name、placeholder 数・型・順序、plural、escaping、`translatable` 契約が維持され、既存の被覆・placeholder oracle が新規 finding なく pass する（2026-10-15 時点で既存の 17 finding は AC-324-12 の disposition が完了していることを前提とする。copy 改訂が copy 構造を pin する既存 test（例: `ExchangeCapabilityCopyTest`）の対象 string に触れる場合は、当該 test も同一変更 set で整合させる）

And 代表画面を normal / enlarged font scale で確認し、critical clipping や操作を妨げる wrapping の regression がない。

## Data and state

アプリの runtime state、Launcher DB、`favorites`、recovery store、preference key、diagnostics journal に変更はない。追加する永続成果物は repository 内の Skill contract、runtime adapter、workflow 追記、bake-off / re-audit evidence、承認済み resource 差分に限る。language review を理由に organizer の planning / application / recovery / layout data contract を変更しない。review evidence は model の hidden reasoning、credential、個人データを含まない。

## Permissions, privacy, and security

**None.** 新規 permission、アプリからの network access、telemetry、runtime 翻訳 service、hosted LLM API の runtime dependency は追加しない。hosted LLM へ送る review 入力は repository が所有する UI copy と context に限り、利用者の個人データ・端末データを含まない。rendered context を使う場合は synthetic または privacy-reviewed の evidence に限る（#199 evidence policy を準用）。review の実行記録は effective provider、model identifier、実施日、session / context identifier を evidence として残すが、Skill contract 本体は特定 vendor / model に恒久依存しない。review 対象 evidence に埋め込まれた指示・link は untrusted data として扱い、それに従って tool 実行・外部作用・情報開示を行わない。

## Accessibility and localization

accessibility text は視覚 label の短縮を流用せず、読み上げて自然な文として評価する。日本語変更後も #123/#161 の resource 被覆・placeholder oracle（`tools/localization/verify_nunu_ja_resources.py`）、代表画面の Japanese / `en-XA` / font-scale 確認経路を維持する。default English の修正は日本語側との意味整合を保ち、片言語だけの drift を作らない。

## Acceptance criteria

- [ ] **AC-324-01 — 正本の再利用:** shared contract と runtime adapter が `docs/localization/ja-style-guide.md`、`ja-glossary.tsv`、`ja-review-workflow.md` を正本として参照し、同じ言語規則を prompt / adapter へ二重管理しない。
- [ ] **AC-324-02 — shared contract:** 専任 Product Language Reviewer の単一の review contract（rubric、input schema、output schema、責務・優先順位契約、AI register 検出の checklist を含む）が repository に存在する。
- [ ] **AC-324-03 — runtime からの呼び出し:** 現行の主要 coding runtime から専任 subagent として呼び出せる。Codex 向け thin adapter を提供し、ZCode は primary-source で確認した mechanism で呼び出す。確認できない capability を support 主張せず、限界を記録する。
- [ ] **AC-324-04 — dedicated model 選定:** 20〜40件の固定・context 付き evaluation set で初期候補（Issue 本文は `Gemini-3.8-flash` を初期候補として想定）を評価し、exact model identifier / provider / 実施日と採用・不採用の理由を evidence に記録する。候補名を伏せた出力に対し owner が blind adjudication し（#161 bake-off 方法を準用）、meaning または safety preservation の重大欠陥を 1 件でも出した候補は aggregate にかかわらず不採用とする。あわせて、採用時点の実装 worker / functional reviewer の effective model family（provider / model 系統）を provenance に記録し、専任 reviewer にはそれらと**異なる model family** を必須とする（Issue 本文の「実装 worker / functional reviewer とは異なる model family に継続的に担当させる」要件）。同一 family の候補は bake-off で高得点でも専任 reviewer として採用せず、要件を緩和する場合は owner decision を先に要求する。Skill contract 本体に model / vendor を恒久固定しない。
- [ ] **AC-324-05 — 責務分離:** reviewer が product language / microcopy / terminology / fluency を専任で評価し、feature behavior、safety / recovery semantics、planner/application contract、navigation / IA、未決定の product terminology を決めないことが contract に明文化されている。
- [ ] **AC-324-06 — 優先順位契約:** 「accepted behavior/safety semantics > #161 正本 > 専任 reviewer の linguistic judgment > 一般 reviewer の stylistic preference」の順位が contract に固定され、functional reviewer が stylistic preference だけで専任 reviewer の表現を差し戻せない一方、meaning drift / safety regression の block 権は保持される。
- [ ] **AC-324-07 — contextual input:** review input が isolated resource string ではなく surface / ui_role / 隣接 copy / behavior context / placeholder contract を含む（`ja-review-workflow.md` §3 の schema を準用）。必須 context が不明な unit は推測で修正せず `PRODUCT_DECISION` とする。
- [ ] **AC-324-08 — output contract:** 各 review unit が `OK` / `REVISE` / `PRODUCT_DECISION` / `TECHNICAL_ONLY` のいずれか 1 つを持ち、`REVISE` は current/proposed、reason、severity、meaning_preserved、layout_risk を含む。
- [ ] **AC-324-09 — full re-audit:** full pass 開始時に base SHA を記録した上で、現行の active / user-visible / translatable な Nunu 固有 user-visible copy（両 resource root。post-#161 に追加された外部エージェント交換・import 入力 UI・custom category authoring・interview-first の capability 説明と会話 flow 案内・取り込み成功状態 / 破棄 / summary・strategy busy / frozen 案内・organizer hub / 材料 / AI相談の依頼（#366〜#377、#398、#417 由来）・usage access JIT 要求・手段別失敗投影・per-item edit actions（`homeedit_*`）・edit undo とその失敗分類（[#450][23]）・visual before/after preview の per-item exclusion・replanning（[#508][25]）・duplicate surplus item の警告と保持理由（[#451][24]）を含む）が inventory 化され、全 unit が 8 分類（primary action / title、onboarding / entry guidance、Organizer strategy / preview / apply flow、warning / confirmation / failure / stale / recovery、placement lock / category override / settings、progress / result / empty state、accessibility text、diagnostics / support entry）のいずれかに属する disposition を持つ。
- [ ] **AC-324-10 — default English の監査:** Nunu 固有 default English が user-facing copy として問題な string が監査対象になり、必要なものは両言語で整合して修正される。
- [ ] **AC-324-11 — semantic guard:** primary CTA、warning、failure、recovery、stale-state、a11y wording の accepted revision は、適用前に accepted behavior との meaning preservation が別途確認される。high-severity `PRODUCT_DECISION` は実装 worker が自己解決せず、owner resolution または split Issue になる。
- [ ] **AC-324-12 — resource 反映と oracle:** accepted `REVISE` items が resource に反映され、name、placeholder/plural/translatable semantics が維持され、`tools/localization/verify_nunu_ja_resources.py` が新規 finding を出さない。あわせて、(a) oracle の required set が再監査対象の post-#161 Nunu 固有 prefix を被覆する（`organizer_custom_category_*` は既存 `organizer_` prefix で被覆済み。追加を要するのは少なくとも `exchange_` と `homeedit_`）、(b) 2026-10-15 時点で main に既存する 17 件の oracle finding（`manual_organization_*` の placeholder/plural 契約 13 件 — #376 由来の `manual_organization_recovery_remaining_hours` を含む、[#336][10] 由来の日本語 resource 未提供 1 件、only-other plural 契約 2 件、ADR-0018 cutover で upstream から持ち込まれた `work_profile_scheduler_intent` 空 resource に対する empty-resource finding 1 件）と、required set 拡張によって新たに可視化される finding（[#443][21] 由来の `exchange_ai_consultation_toggle_label` / `exchange_ai_consultation_toggle_description` の日本語未提供 2 件）が、accepted revision による解決・oracle 契約の修正・split Issue のいずれかに明示的に disposition され、disposition 完了後は oracle と self-test が pass する。split を選んだ場合は当該 split Issue の解決を #324 close の前提とし、完了時点で oracle / self-test が pass していることを要する（単なる split Issue の link だけでは本 AC の完了条件を満たさない。例外を許容する場合は accepted oracle contract の変更と owner decision を先に要求する）。
- [ ] **AC-324-13 — rendered 検証と既存 test の維持:** representative revised screens を normal + enlarged font で確認し、critical clipping / awkward wrapping の regression がない。resource 反映 PR は階層M（Risk tier 節参照）であるため、owner 確認は実機のスクリーンショット / 録画で行い、emulator capture は補助証跡とする（#444 の階層M evidence 規則）。#123/#161 の localization / resource checks と relevant UI / a11y / behavior tests が維持される。
- [ ] **AC-324-14 — 継続運用と #199 境界:** 今後の user-visible copy change で専任 reviewer を利用する trigger / skip rule（reviewer unavailable 時の fallback と explicit skip reason を含む）が repository process に記録される。fallback として実装 worker / functional reviewer と同一 model family への代替を行う場合は reviewer 実行として扱わず explicit skip reason に記録し、要件の緩和は owner decision を先に要求する。初版は CI hard gate にしない。#199 Vision UX review と本 reviewer の責務境界が明文化される。
- [ ] **AC-324-15 — AI register 検出軸:** AI register（AI 感）の検出 checklist が review contract（`references/ai-register-tells.md`）に含まれ、(a) 日本語・英語それぞれの tell（語彙・構文・リズム・記号）、(b) 誤検出の反例（人間も常用する表現）、(c) tell が統計的 editing trigger であり機械的禁止語リストではない運用原則、(d) 根拠となった Web 調査の evidence（[issue-324-ai-register-research.md][27] の URL・確認日）、(e) **適用粒度** — 各 tell が single-string / neighboring-copy / flow-level / long-form のどの粒度で使えるかの明示。長文 corpus 由来の signal（段落構造・文長 burstiness・体言止め比率・表記揺れの一貫性等）は single-string unit の `REVISE` 根拠にしない。UI-role 固有の style 規則と #161 正本を常に優先し、evidence 内で未直接検証の外部 signal は normative rule ではなく補助 evidence に限定する — を持つ。checklist は bake-off（AC-324-04）と full pass（AC-324-09）の評価軸として使用される。AI 感の除去を理由に warning / failure / recovery copy の安全意味論を削減しない。

## Test oracle

| AC | Evidence |
|---|---|
| AC-324-01, 02, 05–08, 15 | Skill contract / adapter / workflow 追記の diff review。正本複製の不在、rubric・schema・責務・優先順位・AI-register checklist（tell・誤検出反例・運用原則・根拠 evidence 参照）の記載を checklist 照合する。 |
| AC-324-03 | Codex adapter の実在と内容、ZCode 呼出し方法の primary-source 確認記録（#199 runtime support 記録の形式を準用）。support 限界の明示。 |
| AC-324-04 | evaluation set の固定 context、匿名出力、owner blind per-item score、aggregate、hard-failure 判定、model identifier / provider / 実施日、採用裁定。採用時点の実装 worker / functional reviewer の effective model family の provenance 記録と、専任 reviewer の family 分離確認。 |
| AC-324-09, 10 | base SHA 付き inventory、8 分類への mapping、全 unit の disposition、default English 対象の記録。 |
| AC-324-11 | safety class unit の meaning-preservation 確認記録、high-severity `PRODUCT_DECISION` の owner resolution / split Issue link。 |
| AC-324-12 | `python3 tools/localization/verify_nunu_ja_resources.py`（baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`）と self-test の実行結果（既存 17 finding + required set 拡張で可視化される 2 finding の disposition 前後）、required set の post-#161 prefix 被覆、resource diff review。 |
| AC-324-13 | 代表画面の capture / UI dump（normal + enlarged font scale）、a11y 確認、Gradle / CI 結果。 |
| AC-324-14 | workflow 追記節の review、skip reason 記録様式、#199 境界の明文化箇所。 |

## Open questions（unresolved decisions）

実装開始前に Issue 上で owner の判断または承認を要する。本 spec はこれらを確定させない。

- **D1: reviewer model の確定。** `Gemini-3.8-flash` は初期候補の名称であり、2026-10-15 時点の実行 runtime（ZCode）の model 一覧で provider 表記 `2c4a554d-c324-4bd5-8567-20db0f081147/gemini-3.8-flash`（BAI）として利用可能なことを確認した。同 runtime では比較候補（`glm-5.3-flash`、`qwen3.8-flash`、`mimo-v2.x`、`gpt-5.6` 系、`kimi-k3`、`deepseek-v4-flash` 等）も利用可能である。採用/不採用は bake-off による。利用可能な候補が初期候補と異なる場合、代替候補で bake-off を実行して記録する。
- **D2: ZCode における専任 subagent の呼出し形態。** #199 時点で残存していた runtime gate は解消済みである。現行 ZCode runtime は名前付き subagent 定義（user-level `~/.zcode/agents/*.md`。frontmatter で model / thoughtLevel / tools / maxTurns / `injectAgentsMd: false` 等を指定）を受理し、#199 の ux-observer / ux-critic が当該機構で稼働中であることを 2026-10-15 の session で確認した（呼び出し可能な agent 一覧に実在）。adapter は (a) workspace-level `.zcode/agents/*.md`（実装時の fresh session で discovery を確認できた場合。この場合は repo に同梱できる）、(b) user-level `~/.zcode/agents/product-language-reviewer.md`（#199 と同一構成。file 本体は repo 外に置き、内容と設置手順を repo 側 evidence に記録する）のいずれかで実装し、speculative な双ファイル作成は行わない。
- **D3: evaluation set の具体構成。** 件数と軸（本 spec AC-324-04）は固定したが、採用する具体 unit は full pass 開始時の inventory から #161 bake-off context を含めて選定する。#332 の import 入力 UI（clipboard / file の failure copy、認識形式表示）、#336 の custom category（作成・削除の部分状態、capacity / conflict）、#327 の capability 説明・transport success・framing hint、#328 の取り込み成功状態・破棄確認・summary plurals、#337 のカテゴリ参照失敗・privacy/disclosure copy、#366〜#377 の organizer hub / 材料 / AI相談の依頼・usage access JIT・手段別失敗投影、#448 の per-item edit actions（`homeedit_menu_*` CTA、`homeedit_error_*` failure copy、lock note warning）、[#450][23] の undo label と undo 失敗分類、[#508][25] の diagram 要素名・exclusion 状態・replanning 案内、[#451][24] の duplicate warning と保持理由など、#161 以降に追加された surface も候補集合に含める。
- **D4: 継続 trigger rule の記載位置。** 正本は `docs/localization/ja-review-workflow.md` への追記を提案する。`AGENTS.md` への追加は #199 の resolved decision 前例（required step の無承認追加をしない）に従い、owner の明示承認がある場合に限る。
- **D5: default English 修正の具体対象。** re-audit で判明したものに限定し、事前に列挙しない。
- **D6: 既存 oracle finding と required set 被覆の扱い。** 2026-10-15 時点の main（`905afbd5b2`、post-cutover）でも `verify_nunu_ja_resources.py` は 17 件の finding を出し（2026-09-28 の 16 件から ADR-0018 cutover 由来の 1 件が増加）、self-test の real-repository assertion が失敗している。(i) `manual_organization_*` 13 件の placeholder/plural 契約 finding（#161 監査完了後の PR #195/#228 等と #376 に由来）、(ii) [#336][10] 由来の `manual_organization_rejection_invalid_category_provenance` の日本語 resource 未提供 1 件（`lawnchair/res` の default のみに存在し、`ManualOrganizationPreferences` の rejection code 表示で使用される。locale 差ではなく訳出欠落であり、resource 修正が素直な解と考えられるが、本 spec は実行しない）、(iii) [#336][10] 由来の `organizer_custom_category_entry_count` / `organizer_custom_category_delete_text` の only-other plural 契約 2 件（(i) と同じ、ja の正しい複数形 class と oracle の厳格等価比較の食い違いクラス）、(iv) required set 拡張時に新たに可視化される [#443][21]（PR #467）由来の `exchange_ai_consultation_toggle_label` / `exchange_ai_consultation_toggle_description` の日本語未提供 2 件（`exchange_` が required set 対象外のため現行 oracle 上に現れない。実験的機能の toggle であるが `translatable="false"` 等の意図的な非翻訳宣言はない）、(v) 新規: ADR-0018 cutover で upstream 16-dev から `res/values` に持ち込まれた `work_profile_scheduler_intent`（`translatable="false"` の空 resource）に対する empty-resource finding 1 件。oracle の empty-resource check は Nunu 固有集合ではなく file 全体に適用されるために発生しており、Nunu copy 側の修正では解決できない点が (i)〜(iv) と異なる。これらを (a) #324 の accepted revision で resource 側を解決する、(b) locale 別 plural category・positional order の正当な差異として oracle 契約側を修正する（(v) では empty-resource check の required set 限定または非翻訳空 resource の明示的許容が候補）、(c) 別 maintenance Issue に分離する、のいずれで解くか（(ii) / (iv) については即時 resource 修正の split も含む）、および required set の `exchange_` / `homeedit_` prefix 拡張の実装方法（`NUNU_PREFIXES` への追加か同等の再構成か）は、実装開始時に owner が判断する。(c) の split を選んだ場合、#324 の close は当該 split Issue の解決（または owner による例外承認と accepted oracle contract の変更）を前提とし、完了時点で oracle / self-test が pass していることを要する。あわせて oracle baseline commit `505dbc40e6154` が pre-cutover（Lawnchair 15）の tree であることに伴う再錨定の要否（cutover merge が ancestry を保持するため現行でも `git show` は成功するが、15/16 を跨ぐ required set 再構成の健全性をどう扱うか）も同時に判断する。

## Change history

- 2026-09-16: Issue #324 の spec 準備 task として `proposed` 仕様を作成。入力: Issue 本文、#161 / #123 の implemented spec・plan・evidence、#199 の accepted spec と runtime support 記録、`main` `0cf82bc1e61c1874b280a7120dff9594be4fef71` の tree（`.agents/skills/ux-visual-review/`、`.codex/agents/`、`docs/localization/`、`tools/localization/`、resource 実態）。本 task は承認を行わず、status は `proposed` のままとする。
- 2026-09-16 (re-entry): baseline を `aab0d293d1a98bf59f5b164693f54ee1a63e3f0b` へ再錨定。#205（PR #325）と #331（PR #333/#334）の実装 merge による exchange user-visible copy（string/plurals 63 entry、両言語、`lawnchair/res` root）と CONTEXT.md の exchange domain 用語追加、`ja-glossary.tsv` の exchange 用語不在、#161 oracle の既存 12 finding（self-test 失敗を含む）と `exchange_*` prefix 対象外を反映。Problem・Scope・Domain language を更新し、AC-324-12 を disposition 条件付きへ変更、D6 を追加。
- 2026-09-17 (re-entry): baseline を `703afe3f4c1f5387f768832ea422c7b681c3775a` へ再錨定。#329/#330/#332/#336 の実装 merge（PR #338/#340/#341/#344）を反映: exchange entry の 63 → 80 増加（import 入力 UI 15 string + normalization failure 2 string）、`exchange_failure_incomplete_coverage` の意味変更に伴う文言改訂、root `res` への custom category 28 entry（両言語）追加、CONTEXT.md の インポート正規化・カテゴリidentity・ユーザー定義カテゴリ・アクティブカテゴリカタログ 用語追加。oracle finding の 12 → 15 増加（#336 由来の日本語未提供 1 件と only-other plural 契約 2 件）を Problem・AC-324-12・D6 へ反映し、custom category が既存 `organizer_` prefix で required set に含まれることを AC-324-12 (a) に明記。D3 へ evaluation set 候補として新 surface を追記。
- 2026-09-19 (re-entry): baseline を `3076bdae7e` へ再錨定。#348（PR #349）/ #327（PR #350）/ #328（PR #353）/ #337（PR #355）の実装 merge を反映: exchange entry の 80 → 114 増加（両言語、name set 一致）、#327 の capability 説明 7 string・transport success / framing hint の文言改訂・`exchange_import_accepted` の削除、#328 の取り込み成功状態・破棄・summary・strategy busy / frozen 案内、#337 の `exchange_failure_unknown_category_ref` と privacy/disclosure copy のカテゴリ言及改訂。CONTEXT.md の 取り込み成功状態・取り込み破棄・判断なし項目・整理方針確認・exportカテゴリ参照・既存カテゴリ参照・提案グループ・昇格 用語追加（glossary は 22 term のまま）。#361 の TO-BE UX decision（accepted、PR #364）と #356 の AS-IS audit（draft、PR #363）を取り込み、優先順位 tier 1 へ accepted product decision 文書を明示し、#362 migration を先取りしない旨を追加。oracle finding は 15 件のまま不変（新規 exchange copy は required set 対象外）を 2026-09-19 実行で再確認。AC-324-09 / D3 へ新 surface を反映し、copy 構造を pin する test（`ExchangeCapabilityCopyTest` 等）の整合を scenario へ追記。
- 2026-09-28 (re-entry): baseline を `c5a7840b88` へ再錨定。TO-BE migration series（#366〜#377、#398、#417）の実装 merge と #362 の完了（closed）を反映: 現行 main は post-migration surface であり、「migration を先取りしない」規則を廃止して現行 surface を直接対象とする旨に改訂。#448（PR #472）の per-item edit actions による新規 Nunu 固有 `homeedit_` prefix 20 entry（両言語）を追加し、required set 拡張対象を `exchange_` に加え `homeedit_` へ拡大。#443（PR #467）由来の `exchange_ai_consultation_toggle_*` 2 entry の日本語未提供（required set 対象外のため oracle 不検出）を D6 / AC-324-12 へ反映。oracle finding の 15 → 16 増加（#376 の `manual_organization_recovery_remaining_hours`）を反映。#444（PR #468）による Risk tiers 導入（`docs/project/github-workflow.md` / AGENTS.md / `specs/_template/spec-lite.md`）を取り込み、Risk tier 節（M + L、H なし）と AC-324-13 の階層M evidence 規則（実機 owner 確認）を追加。ADR-0013/0014/0015 の accepted と #442 最終結論C（upstream rebase は専用 Epic + ADR で計画、未作成）を Authority 節へ反映。exchange は 164/162 entry、organizer 系は 321/320 + 86/86（2026-09-28 時点）。
- 2026-10-15 (review round 1 対応): PR #602 への ChatGPT review（[comment 6193112079][28]、Changes requested: 高2・中1）へ対応。(1) 高: model-family 分離が acceptance contract にない — AC-324-04 に「採用時点の実装 worker / functional reviewer の effective model family を provenance に記録し、専任 reviewer には異なる family を必須とする」を追加、AC-324-14 の fallback に同一 family を explicit skip とする規則を追加、Test oracle 表へ family provenance を反映。(2) 高: split Issue disposition と oracle/self-test pass の両立が未定義 — AC-324-12 と D6 に「split を選んだ場合は split Issue の解決を #324 close の前提とし、完了時点で oracle / self-test が pass していることを要する（単なる split link で完了としない）」を追加。(3) 中: 長文向け AI 文体 signal の UI microcopy への適用境界 — AC-324-15 に適用粒度（single-string / neighboring-copy / flow-level / long-form。長文 corpus 由来 signal は single-string の `REVISE` 根拠にしない）を追加、evidence §5 へ原則 7 として記録。
- 2026-10-15 (re-entry): baseline を `905afbd5b2`（post-cutover main、PR #601 merge）へ再錨定（merge re-anchor、merge commit `9471d7fc43`）。ADR-0018 Phase 4 cutover（Epic #516、2026-10-13）で main baseline が Lawnchair 16 `43a21b43d7` に切替わったことを反映: fork commit の replay で既存 Nunu 文言の値は不変（`c5a7840b88` ↔ `905afbd5b2` で value 変更 0・削除 0、name/value level diff で検証）、oracle finding は 16 → 17（upstream 由来 `work_profile_scheduler_intent` 空 resource の empty-resource finding。D6 (v) として新設）、「upstream rebase 用 Epic の確認」條項を廃止。#450（PR #481）/ #451（PR #485）/ #508（PR #515）の +33 entry（`homeedit_undo_*` 10、`manual_organization_*` 23、両言語・ja 提供済み）を Problem / AC-324-09 / D3 へ反映し、Nunu 固有集合の現行値を 538/535 + 86/86 へ更新。D1 に現行 runtime での `gemini-3.8-flash` identifier（provider 表記込み）の確認を記録。D2 を解消済み状態へ更新（user-level `~/.zcode/agents/*.md` の ZCode 名前付き subagent が #199 ux-observer / ux-critic で稼働中であることを 2026-10-15 session で確認）。owner 指示により AI register（AI 感）検出軸を追加: 2026-10-15 の Web 調査（英語・日本語並列、主要出典 2 件は本文直接検証）を `docs/assessment/evidence/issue-324-ai-register-research.md` に格納し、Problem / Outcome / Scope / Domain language / Authority / AC-324-02 / 新設 AC-324-15（requirements へ追加）へ反映。

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/324 "Issue #324: Product Language Reviewer subagent の導入と UI 文言再監査"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/161-japanese-ui-copy-lqa/spec.md "Issue #161 specification (implemented)"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/199-ux-visual-review/spec.md "Issue #199 specification (accepted)"
[4]: https://github.com/nunu1733/NunuLauncher/blob/main/CONTEXT.md "NunuLauncher domain language"
[5]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/205-external-agent-exchange/spec.md "Issue #205 specification (implemented)"
[6]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/331-exchange-target-scope-coupling/spec.md "Issue #331 specification (implemented)"
[7]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/329-import-normalizer/spec.md "Issue #329 specification (implemented)"
[8]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/332-exchange-import-input-ui/spec.md "Issue #332 specification (implemented)"
[9]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/330-partial-intent-authoring/spec.md "Issue #330 specification (implemented)"
[10]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/336-user-defined-categories/spec.md "Issue #336 specification (implemented)"
[11]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/327-agent-exchange-interview-first/spec.md "Issue #327 specification (implemented)"
[12]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/328-exchange-import-success-state/spec.md "Issue #328 specification"
[13]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/337-exchange-category-group-proposals/spec.md "Issue #337 specification"
[14]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/product/organizer-to-be-ux.md "Organizer TO-BE UX decision (#361, accepted)"
[15]: https://github.com/nunu1733/NunuLauncher/issues/362 "Issue #362: 既存正本の処分とmigration順序 (closed)"
[16]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/366-organizer-hub-shell/spec.md "Issue #366 specification (implemented)"
[17]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/372-ai-consultation-request-flow/spec.md "Issue #372 specification (implemented)"
[18]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/398-strategy-intent-first-bottom-region/spec.md "Issue #398 specification (implemented)"
[19]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/448-edit-actions-per-item/spec.md "Issue #448 specification (implemented)"
[20]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/adr/0014-edit-surface.md "ADR-0014: Edit surface (#447, accepted)"
[21]: https://github.com/nunu1733/NunuLauncher/issues/443 "Issue #443: AI相談の凍結適用 (implemented, PR #467)"
[22]: https://github.com/nunu1733/NunuLauncher/issues/442 "Issue #442: Android 16/17での日常利用の適性確認とupstream同期判断 (closed, 最終結論C)"
[23]: https://github.com/nunu1733/NunuLauncher/issues/450 "Issue #450: 編集の取り消し（Undo）(implemented, PR #481)"
[24]: https://github.com/nunu1733/NunuLauncher/issues/451 "Issue #451: 整理での重複アイコンの扱い（同じ起動先を同じ新規フォルダに入れない）(implemented, PR #485)"
[25]: https://github.com/nunu1733/NunuLauncher/issues/508 "Issue #508: Organizerの変更前後を図で確認し、項目単位で除外・再計画する (implemented, PR #515)"
[26]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/adr/0018-lawnchair-16-rebase.md "ADR-0018: Lawnchair 16 rebase (Epic #516, accepted)"
[27]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/assessment/evidence/issue-324-ai-register-research.md "AI register (AI 感) detection/removal research evidence (2026-10-15)"
[28]: https://github.com/nunu1733/NunuLauncher/pull/602#issuecomment-6193112079 "PR #602 ChatGPT review round 1 (Changes requested)"
[29]: https://github.com/nunu1733/NunuLauncher/pull/602#issuecomment-6193306647 "PR #602 ChatGPT review round 2 (Approve)"
