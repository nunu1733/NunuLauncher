---
issue: "#161"
status: accepted
requirements:
  - AC-161-01
  - AC-161-02
  - AC-161-03
  - AC-161-04
  - AC-161-05
  - AC-161-06
  - AC-161-07
  - AC-161-08
  - AC-161-09
  - AC-161-10
updated: 2026-08-28
---

# Nunu 固有 UI の日本語コピー LQA と再利用可能なレビュー手順

> **Stage A gate:** 本 spec と同伴の [plan.md](./plan.md) は、Issue [#161][1] の Stage B を拘束する提案契約である。Issue 上で両文書が承認されるまで、Stage B は日本語リソース、オーガナイザーの振る舞い、または既存の安全保証を変更してはならない。実装中の発見が本契約と矛盾する場合は、実装内で代替方針を選ばず、Issue で判断を更新する。

## Problem

Issue #123 は Nunu 固有オーガナイザー文字列を Android resource に収束させ、日本語 resource の被覆、placeholder の整合、疑似ロケール、および代表画面の表示証拠を確立した。しかし、構造的に正しい翻訳であっても、通常の日本語 Android UI として自然で簡潔か、技術・ドメイン用語を不必要に露出していないか、操作結果が明確かは別の品質特性である。[2] [3]

現状では、この言語品質を一回限りのモデル出力や個人の書き換えに依存せず、再現可能な基準、文脈化したレビュー単位、独立した第二レビュー、および実装後の画面確認で扱う契約がない。そのため、今後の Nunu 固有 UI でも用語揺れ、直訳、曖昧な CTA、または安全文言の意味縮退を発見・処理する一貫した手順が不足している。

## Outcome

Stage B 完了時、NunuLauncher は通常利用者向け・安全関連・設定/ヘルプ・アクセシビリティ・診断の各 surface を区別する日本語 UI コピー規約、必要な範囲に限定した用語集、および人間とコーディングエージェントが実行できるモデル非依存の LQA workflow を持つ。現行の active / user-visible / translatable な Nunu 固有日本語 resource は、その workflow により文脈付きで棚卸し・分類され、採用した修正は resource 契約と既存のオーガナイザー安全意味論を維持したまま検証される。[1] [2]

## Scope

この Issue は、次の順序で実施する言語品質の maintenance 作業を対象とする。Stage A の成果物は本 spec と plan だけであり、Stage B が以下の実装・証拠化を行う。

| 区分 | Stage B の対象 | 正本となる成果物 |
|---|---|---|
| コピー規約 | reader、surface class、CTA、文体、句読点、外来語、内部用語、安全意味論、a11y、Android resource 互換性を定義する。 | `docs/localization/ja-style-guide.md` |
| 用語一貫性 | 繰り返し現れる Nunu/domain 用語について、推奨語、避ける語、surface 例外、根拠を記録する。文脈依存の語を機械置換表にしない。 | `docs/localization/ja-glossary.tsv` |
| 再利用可能なレビュー | 文脈化した review unit、優先順位、4 disposition、severity、第二レビュー、エスカレーション、出力表を定義する。 | `docs/localization/ja-review-workflow.md` |
| 評価と監査 | 固定評価集合による reviewer bake-off、全対象 resource の inventory / review table、Reviewer A/B の独立実行記録、reviewer 間の判断と解決、変更前後の例を残す。 | `docs/assessment/evidence/issue-161-ja-lqa.md` |
| リソース修正と表示検証 | 承認済み `REVISE` のみを `lawnchair/res/values-ja/strings.xml` と `res/values-ja/strings.xml` に反映し、既存の被覆・placeholder oracle と代表画面を再確認する。 | resource 差分と Issue #161 の evidence |

対象 resource は、Lawnchair baseline `505dbc40e6154c05158b5d0271c45f6a885a411b` 以降に Nunu が追加し、現在も active / user-visible / translatable であるオーガナイザー UI resource とする。対象集合は固定件数では定義せず、Stage B の開始時に accepted Stage A commit を基準として再構成する。#123 が確立した定義、すなわち `required = active Nunu organizer default resource where user-visible && translatable != false` と、各 resource root の日本語 name 集合・placeholder 契約の比較を継続する。[2] [3]

通常利用者向けの優先順位は、primary action / title、onboarding と manual organization、confirmation / failure / recovery、category override と lock、accessibility、diagnostics / support の順とする。診断・支援 surface は正確さのため技術語を保持できるが、その判断を `TECHNICAL_ONLY` として記録する。[1]

**初回 full pass に限り**、inventory の全 review unit を Reviewer A と Reviewer B が同じ style guide、glossary、context に対して独立に評価する。low / medium severity で両者が合意した `OK`、`REVISE`、または `TECHNICAL_ONLY` は owner の resource ごとの個別承認なしに確定できる。一方、high severity の不一致または behavior / safety meaning の曖昧さは、resource を変更する前に owner resolution または split Issue を必要とする。将来の通常運用に、この初回 full-pass の全件二重レビューを恒久 requirement として拡張しない。[1]

## Non-goals

この作業は全上流 Lawnchair/AOSP 翻訳の文体統一、他 locale への翻訳管理、Crowdin や runtime 機械翻訳の導入、永続的な有料翻訳/LLM API の merge gate 化を含まない。また、オーガナイザーの planning、application、recovery、navigation、safety policy、情報階層を変更しない。よりよい日本語にするために製品意味・操作・保証を変更する必要がある場合は、`PRODUCT_DECISION` として別 Issue に分離する。[1] [2]

## Domain language

本 Issue は新しい product/domain 用語を導入しない。既存の「ホームレイアウト」「レイアウト snapshot」「レイアウト plan」「整理 run」「ロック配置」「recovery point」等は [CONTEXT.md][4] を正本とし、用語集は UI 表現の一貫性を補助するのみで domain 定義を置き換えない。

## Behavior scenarios

### Scenario: 文脈付きの通常利用者向けコピーをレビューする

Given reviewer は resource name、default text、current Japanese、surface、UI role、周辺コピー、placeholder の意味と表示例を持つ

When reviewer が primary normal-user UI を style guide と glossary に照らして評価する

Then 各 review unit は `OK`、`REVISE`、`PRODUCT_DECISION`、`TECHNICAL_ONLY` のいずれか 1 つに分類され、初回 full pass では Reviewer A と Reviewer B の独立した結果がともに記録される

And low / medium severity の合意は owner の個別承認なしに確定でき、`REVISE` は提案日本語、根拠、適用規則、意味/安全意味論が不変であること、layout 拡張リスクを記録する。[1]

### Scenario: 高リスクの安全・復旧文言に曖昧さまたは不一致がある

Given primary CTA、warning、failure、recovery、破壊的または不可逆な結果を説明する review unit がある

When Reviewer A と Reviewer B が意味保存、保証、ユーザー判断への影響について相違する、または accepted behavior から意図を導けない

Then 当該 unit は自動採用されず `PRODUCT_DECISION` として Issue / project owner へエスカレーションされる

And Stage B は、既存 spec が保証しない自動復旧、成功保証、または新しい安全制約を文言で約束しない。[1] [5]

### Scenario: 診断 surface の技術語が意図的に必要である

Given diagnostics / support surface で technical precision が利用目的に必要である

When reviewer が通常利用者 UI の平易な語への置換を検討する

Then 正確さを損なう置換を行わず `TECHNICAL_ONLY` と理由を review table に記録する

And その判断は通常利用者 surface へ技術用語を横展開する根拠にはならない。[1]

### Scenario: 採用済みの resource 文言を反映する

Given `REVISE` の提案が二重レビューおよび必要な人間判断を通過している

When Stage B が日本語 resource を変更する

Then string name、placeholder 数・型・位置指定、plural、escaping、`translatable="false"` の意味論は維持される

And `required ⊆ values-ja names` と placeholder 一致の既存 oracle を満たし、変更はオーガナイザーの実行状態・layout data・recovery protocol を変更しない。[2] [3]

### Scenario: 表示と読み上げで修正を確認する

Given 採用済み `REVISE` が primary、warning、failure、recovery、a11y のいずれかを変更している

When 日本語 locale の代表画面を通常 font scale と代表的な拡大 font scale で確認する

Then critical label / action に clipping または操作を妨げる不自然な wrapping がない

And a11y 専用文字列は視覚的な短縮語ではなく自然な読み上げとして意味を保つ。`en-XA` は構造的な localization regression を検知する補助証拠として維持する。[2] [3]

## Data and state

アプリの runtime state、Launcher DB、`favorites`、recovery database、preference key、diagnostics journal に変更はない。Stage B で追加する永続成果物は repository 内の style guide、glossary、workflow、review/bake-off evidence、および承認済み日本語 XML resource に限る。layout data を書き込む変更がないため、ホームレイアウトの適用・transaction・rollback protocol は対象外であり、release rollback は当該 PR の revert により行う。

review table は resource ごとに surface / role、current Japanese、disposition、proposed Japanese、severity、reason、rule、meaning preserved、layout risk を保持する。スクリーンショット、UI dump、rendered value などの補助証拠は必要な review unit に紐付けるが、モデルの hidden reasoning や認証情報、個人データを commit してはならない。[1] [3]

## Permissions, privacy, and security

**None.** 新規 permission、アプリからの network access、telemetry、runtime 翻訳 service、または有料 LLM API dependency は追加しない。reviewer model の識別子と実施日を bake-off の実行証拠として記録できるが、特定 vendor / model を恒久的な repository requirement や runtime dependency にしない。[1]

## Accessibility and localization

通常利用者 UI は簡潔で操作結果が予測できる表現を用いる一方、warning / recovery では判断に必要な意味を削除しない。accessibility text は画面上の短い label を機械的に再利用せず、日本語で読み上げたときに自然な文として評価する。日本語変更後も、#123 で利用した Japanese / `en-XA` / font-scale の確認経路と、resource name / placeholder の構造検証を維持する。[2] [3]

## Acceptance criteria

- [x] **AC-161-01 — Stage A gate:** 本 spec と同伴の `plan.md` が Issue #161 で承認され、Stage B の対象、非対象、停止条件、検証経路が矛盾なく固定されている。
- [x] **AC-161-02 — 日本語 UI コピー規約:** `docs/localization/ja-style-guide.md` が normal UI、confirmation / warning / recovery、settings / help、a11y、diagnostics の 5 surface class と、CTA、文体、句読点、外来語、内部用語、安全意味論、Android resource 互換性を定義している。
- [x] **AC-161-03 — 用語集:** `docs/localization/ja-glossary.tsv` が、整合性を要する recurring Nunu/domain terms について preferred term、avoid、surface-specific exception、rationale/reference を必要な範囲で記録し、機械的な全語置換を要求していない。
- [x] **AC-161-04 — 再利用可能 workflow:** `docs/localization/ja-review-workflow.md` が、contextualized review unit、優先順位、4 disposition、severity、必須出力、Reviewer A / B の独立モード、高リスク escalation を規定している。孤立した XML 値だけで結論を出すことを許可しない。
- [x] **AC-161-05 — Bake-off:** 20〜40 件の固定・文脈付き評価集合を用い、Accuracy、Fluency、Terminology、UI style / concision、Context fit、Safety preservation、resource correctness を評価した model bake-off が evidence に記録されている。candidate 自身による自己採点は禁止し、candidate 名を伏せた同一 context / output を project owner が blind adjudication する。evidence は candidate ごとの per-item score、aggregate 方法、最終 adjudication、選定理由を含む。Safety または meaning preservation の重大欠陥を 1 件でも出した candidate は aggregate にかかわらず不採用とする。恒久 workflow はモデル非依存である。
- [x] **AC-161-06 — 完全な LQA inventory と初回二重レビュー:** Stage B 開始時点の active / user-visible / translatable Nunu 固有 resource が、`lawnchair/res/values-ja` と root `res/values-ja` の両方を含めて inventory 化され、各 unit に 4 disposition のいずれかが記録されている。初回 full pass では**全 unit**に Reviewer A result と Reviewer B result を記録し、各 reviewer の role、model identifier、family または provider、execution session または independent-context identifier を evidence に残す。異なる family / provider を利用できなかった場合は理由も記録する。
- [x] **AC-161-07 — 合意規則と判断分離:** 初回 full pass の low / medium severity item は Reviewer A / B の合意により確定できる。primary CTA、warning、failure、recovery、破壊的/不可逆な意味、a11y、および用語 policy の high severity item で Reviewer A / B が実質的に不一致であるか、意味が曖昧な場合は、採用前に project owner が解決するか、別 Issue へ分離されている。Reviewer A / B の合意だけでは、未解決の product decision を解決したことにはならない。
- [x] **AC-161-08 — resource と behavior の保存:** 採用済み `REVISE` だけが実装され、placeholder / plural / escaping / `translatable` 契約が保持される。オーガナイザーの planning、application、recovery、layout data、安全保証、navigation の diff を混在させない。
- [x] **AC-161-09 — rendered localization evidence:** 主要な変更 surface を Japanese locale の通常 font scale と代表的な拡大 font scale で確認し、critical clipping / wrapping がない。a11y 意味を確認し、`en-XA` と #123 の被覆 oracle を構造 regression の検証として継続する。
- [ ] **AC-161-10 — closing evidence:** exact base SHA、resource inventory / review table、style guide・glossary・workflow path、bake-off 実施日、candidate ごとの per-item / aggregate score と final adjudication、Reviewer A / B ごとの role・model identifier・family/provider・execution session または independent-context identifier、異なる family/provider を使えなかった理由、代表的な変更前後、reviewer の不一致と解決、resource validation、実行 command と結果、未解決/分離 Issue が closing evidence に記録されている。

## Test oracle

| AC | Evidence |
|---|---|
| AC-161-01 | Issue #161 上の承認記録、および `spec.md` / `plan.md` の status・change history。 |
| AC-161-02 | style guide の section review。5 surface class と safety/resource rules を checklist で照合する。 |
| AC-161-03 | glossary TSV schema review。各列と representative term の根拠を確認する。 |
| AC-161-04 | workflow に従い fixed evaluation set の各 unit を 4 disposition と required output で処理できることを dry-run で確認する。 |
| AC-161-05 | evidence 内の固定 evaluation set、candidate 名を伏せた output、project owner の blind adjudication、candidate identifier、実施日、per-item score、aggregate 方法、hard-failure 判定、最終選定根拠。 |
| AC-161-06 | `required` 集合、resource root、surface / role mapping、1 resource 1 disposition、全 unit の Reviewer A / B result、各 reviewer の role / model / family/provider / session-or-context identifier / 例外理由を持つ review table。 |
| AC-161-07 | low / medium row の A/B agreement と、high severity disagreement / ambiguity の owner resolution または split Issue link。 |
| AC-161-08 | 日本語 resource diff review、name-set 被覆と placeholder 比較、organizer behavior source path 非変更の diff review。 |
| AC-161-09 | 日本語代表画面の capture / UI dump、font-scale 確認、a11y evidence、`en-XA` 確認、既存 Gradle / CI 結果。 |
| AC-161-10 | `docs/assessment/evidence/issue-161-ja-lqa.md` の closing checklist と PR / CI link。 |

## Open questions

Stage A を阻害する未決定事項はない。Stage B で使う reviewer pair は、固定評価集合の bake-off 後に実行証拠として記録する。bake-off は candidate 名を伏せて project owner が adjudicate し、candidate の自己採点を認めない。利用可能な model family / provider の組合せは時点依存であり、style guide または workflow の恒久要件にはしないが、初回 full-pass の evidence には各 reviewer の role、model identifier、family/provider、session または独立 context identifier を残す。既存 tree に repository-local Skill directory の規約は確認できないため、portable な正本は `docs/localization/ja-review-workflow.md` とする。将来、repository-wide skill convention が受諾された場合は、別途その convention に従って workflow の配置を検討する。[1]

## Change history

- 2026-08-28: Issue #161 の Stage A 向け `proposed` 仕様を作成。Issue 本文・提案済み reviewer design、#123 の implemented spec / plan / visual evidence、`main` `c68abcce628de9d01efaf29280193defe4aff540`、および repository workflow を入力として、モデル非依存の文脈付き LQA contract と Stage B の停止条件を固定した。
- 2026-08-28: Issue #161 の Stage A review（P1 × 2、P2 × 1）に対応。初回 full pass の全 inventory unit に独立 Reviewer A/B result を要求し、low/medium agreement と high-severity disagreement/ambiguity の扱いを分離した。bake-off には candidate 自己採点禁止、blind project-owner adjudication、per-item/aggregate 記録、safety/meaning-preservation hard failure を追加し、closing evidence に reviewer role/model/family-or-provider/session-or-context identity と例外理由を追加した。
- 2026-08-28: リポジトリ所有者の承認を受領し、Stage A gate を `accepted` に更新。Stage B の実装を開始する。
- 2026-08-28: Stage B の style guide、glossary、portable workflow、resource validator、全 223 unit の A/B review、24 unit の blind bake-off、承認済み日本語 resource 修正、AVD 表示検証を完了した。AC-161-01〜09 をローカル証拠で確認し、AC-161-10 は pushed PR head の CI `final-status` 待ちとして残す。
- 2026-08-28: 実装レビューの P1/P2 指摘に対応し、最終 disposition と実変更を 82 件で一致させ、24 件の固定 bake-off context、Japanese 側 `translatable` 契約検証、lock 状態/action の再レビューを追加した。

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/161 "Issue #161: Establish Japanese UI copy LQA and reusable localization review workflow"
[2]: https://github.com/nunu1733/NunuLauncher/blob/main/specs/123-organizer-ui-convergence/spec.md "Issue #123 specification"
[3]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/assessment/evidence/issue-123-ui-mapping.md "Issue #123 UI mapping and visual evidence"
[4]: https://github.com/nunu1733/NunuLauncher/blob/main/CONTEXT.md "NunuLauncher domain language"
[5]: https://github.com/nunu1733/NunuLauncher/blob/main/docs/adr/0003-organizer-recovery-point-storage.md "ADR-0003: recovery point storage"
