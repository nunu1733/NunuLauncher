# Japanese UI Localization Reviewer workflow

> Status: implemented for Issue #161
>
> この文書は特定のモデル・ベンダー・APIを必要としない、NunuLauncher用の再利用可能な日本語UI LQA契約である。

## 1. 適用範囲

次のいずれかに該当する場合に使う。

- 日本語の user-visible Android resource を追加・変更する。
- 既存の日本語画面が直訳的、冗長、曖昧、または技術的すぎる。
- CTA、warning、failure、recovery、stale-state、accessibility text を変更する。
- Nunu 固有 resource をまとめて監査する。

package名、アプリ名、実ファイル名、IDなどの runtime data は、UIに埋め込む固定文言でない限り review unit にしない。

## 2. Source precedence

1. accepted feature spec、関連ADR、既存の安全意味論
2. `docs/localization/ja-style-guide.md`
3. `docs/localization/ja-glossary.tsv`
4. 同じ概念の互換なAOSP/Lawnchair日本語
5. default resource と実装の surrounding context
6. reviewerの文体上の好み

下位の自然さを理由に上位の挙動・安全意味を変更しない。

## 3. Review unit input

孤立した XML 値ではなく、次の構造を持つ context を用意する。分からない項目は空欄にせず `unknown` と記録する。

```text
resource_name:
default_text:
current_ja:
surface:
ui_role: title | action | menu | summary | dialog | warning | error | progress | result | toast | a11y | diagnostic
user_class: normal | safety-critical | accessibility | diagnostic
neighboring_copy:
placeholder_contract:
example_rendered_value:
behavior_or_spec_context:
aosp_or_lawnchair_reference:
screenshot_or_rendered_context:
```

surface、role、placeholder、または safety/behavior context が必要なのに不明な場合、意味を推測して修正しない。`PRODUCT_DECISION` または追加調査へ送る。

## 4. Required output

各 unit は次の4つから必ず1つだけ分類する。

- `OK`: 現在の日本語が文脈に適している。
- `REVISE`: 挙動の意味を変えずに日本語を修正する。
- `PRODUCT_DECISION`: よりよい表現が未決定のUX・情報階層・domain意味に依存する。
- `TECHNICAL_ONLY`: 診断・サポート目的で技術語が意図的に必要である。

`REVISE` には以下を付ける。

| Field | 内容 |
|---|---|
| proposed_ja | 採用候補の日本語 |
| severity | `low` / `medium` / `high` |
| reason | 言語・UI上の短い理由 |
| rule | style guide / glossaryの該当規則 |
| meaning_preserved | `yes` / `no` / `needs decision` |
| layout_risk | `none` / `shorter` / `longer` / `unknown` |

severityの目安は、primary CTAの曖昧さ、警告・復旧の意味、a11yの意味誤りを `high`、内部語彙の露出や大きな不自然さを `medium`、句読点など安全判断に影響しないものを `low` とする。

## 5. Review passes

### Pass A: semantic anchoring

画面の実際の action、利用者の判断、保存/変更の有無、復旧可能性、placeholderの意味をコードとaccepted spec/ADRで確認する。英語だけから製品意図を推測しない。

### Pass B: Japanese LQA

Accuracy、Fluency、Terminology、UI style/concision、Context fit、Safety preservation、resource correctnessを評価する。必要なら各軸を0–2点で診断採点するが、dispositionと理由が正式な判断である。

### Pass C: family consistency

画面タイトル・説明・CTA、progress・preview・confirm・result、warning・failure・recovery、lock state、automatic/manual category、diagnostics entry・export resultを一つの流れとして読む。個別には自然でも用語や意味が揺れる案は採用しない。

### Pass D: rendered fit

通常font scaleと代表的な拡大font scaleで、critical actionのclip、変な改行、説明の重複、a11yの読み上げを確認する。表示確認は言語レビューやbehavior testの代替ではない。

## 6. Initial full-pass independence

Issue #161の初回full passでは、全inventory unitをReviewer AとReviewer Bが同じsource/current/proposed/contextに対して独立に評価する。

- Aは分類と提案を作る。
- BはAの理由・hidden reasoningを受け取らず、current/source/proposalを独立に評価する。
- 各実行に `role`、`model_identifier`、`family_or_provider`、`session_or_context_id`、実施日を記録する。
- 異なるfamily/providerがpracticalでなかった場合は、その理由をevidenceに残す。
- low/mediumのA/B agreementは個別owner承認なしで確定できる。
- high severityは、A/B agreement、accepted spec/ADRに対するmeaning preservationの明示確認、product ambiguity不在の全てを満たす場合に確定できる。
- high-severity disagreement、meaning ambiguity、source defectはowner resolutionまたはsplit Issueなしにresourceを変更しない。

Reviewer Bの返却値は `AGREE`、`AGREE_WITH_EDIT`、`DISAGREE_KEEP_CURRENT`、`ESCALATE_PRODUCT_DECISION` のいずれかとし、差分理由を記録する。

## 7. Model bake-off

20–40件の固定contextを、CTA/title、onboarding/progress/status、failure/safety/recovery、settings/category/lock、a11y、diagnostic/technicalから構成する。同じ入力を候補へ与え、候補名を隠して出力を比較する。

- candidate自身の自己採点は禁止する。
- project ownerが唯一のblind adjudication authorityとして、各候補・各itemを7軸0–2点で採点する。
- per-item score、軸別合計、総得点、理論満点に対する割合、aggregate方法を記録する。
- Safety preservationまたはmeaning preservationの重大欠陥が1件でもあれば、その候補は平均点にかかわらずhard failureとする。
- 候補名と匿名出力の対応付けは採点後に行う。同点やrubricで決まらない場合はownerの裁定理由を残す。

Bake-offは現在の実行 evidenceであり、guide/workflowに特定モデル名を恒久的なmerge gateとして埋め込まない。

## 8. Resource application and stop conditions

`REVISE` は、meaning-preservedであることと、A/B agreementまたはowner resolutionがevidenceにあるものだけをresourceへ反映する。resource name、placeholder/plural/escaping、`translatable`、Kotlin側のlocale依存文構築を確認する。

次の場合は停止する。

- 文言変更がaction、navigation、情報階層、automatic/manual、domain concept自体を変更する。
- warning/failure/recoveryが安全制約・復旧可能性・成功保証を変更する。
- high severityでA/Bが不一致になる。
- required set、placeholder、plural、translatableの意味が分類できない。
- 上流翻訳、全locale管理、hosted service、新規Skill conventionが必要になる。

停止時は `PRODUCT_DECISION` または別Issueへ送り、推測で文言を決めない。

## 9. Evidence schema

closing evidenceは少なくとも次を保持する。

- exact base SHAとinventoryの再現方法
- resource、surface、role、current JA、A/B result、final disposition
- `REVISE`のproposal、severity、reason、rule、meaning preservation、layout risk
- Reviewer A/Bのrole、model、family/provider、session/context、例外理由
- 匿名bake-off input/output、per-item 7軸score、aggregate、hard-failure、owner adjudication
- 代表before/after、screenshot/UI dump、font scale、a11y確認
- resource validator、format、lint、test、buildの正確なcommandと結果
- 未解決・分離Issue
