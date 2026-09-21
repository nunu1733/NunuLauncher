---
issue: "#373"
status: draft
requirements: [FR-017]
risk: []
updated: 2026-09-21
---

# T-17取り込み入力の契約維持とT-18取り込み結果の再構成（手段別失敗投影、D-11/D-12/D-13）

> 契約の根拠: accepted TO-BE decision
> [docs/product/organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)
> （D-11, D-12, D-13, §5.1 T-17/T-18, §5.3, §8.3 stale remedy対応表, §9 語彙規約, §10 用語）。
> 処分の正本: accepted disposition
> [docs/product/organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)
> （§2.1 #328「#373（表示）→#374」・#337「#373（配置のみ）」・#332「Continue（表記更新）#373」、
> §3.12 spec 205分段改訂のうち本件分（AC-5表示面）、§3.14/§3.17、§4.1 supersession map
> 「spec 205 AC-5のtyped失敗直接説明 → 手段別再投影＋typedは補助（#373）」、
> §5 更新順序 #7「specs 205(AC-5) / 332表記 → D-11・T-17/T-18 → #373」、§7.2 (c)、§8）。
> 本specは[Issue #373][1]の成果物である。statusが `draft` の間はimplementation-readyではない。
> 前提: [Issue #372][2]のT-15/T-16再構成（PR #393でmerge済み）が本specの失敗面
> 「依頼を作り直す」remedyの到達先（T-15依頼作成面）である（Issue本文 `Depends on`）。
> baselineのSHAはChange historyのre-entry entryを正とする。

## Problem

現行（baseline `dcaecf6913`。#372 merge後のorigin/main。Change historyのre-entry entry参照。
spec 205/328/329/332/337 contract core/348実装済み）の
External Agent Exchange取り込み結果面（`ExchangeImportOutcomeScreen`、
`lawnchair/src/app/lawnchair/organizer/ui/exchange/ExchangeFlowUi.kt`）は、import失敗を
**typed失敗20種の固有文言**で1行表示する（envelope 4種 `INPUT_OVERSIZE` / `FRAMING_MISSING` /
`FRAMING_AMBIGUOUS` / `FRAMING_EMPTY`、normalization 2種、contract 14種
（#204 12種 + `SCOPE_MISMATCH` + `UNKNOWN_CATEGORY_REF`）。`exchangeFailureText` →
`exchangeContractFailureText` の網羅 `when`）。これは次のAS-IS findingを抱える:

- **F-04/監査 D-9（state・失敗語彙の爆発）**: 20種のtyped語彙（`CONTEXT_STALE` 等の契約内部語に
  対応する専門的な説明）がprimary表示に並び、ユーザーが次に取るべき行動（再コピーするのか、
  再依頼するのか、諦めるのか）が文言から読み取りにくい。
- **F-07（サブシステム感）**: 失敗語彙の専門性が「AI相談は独立サブシステム」という誤解を強める。
- accepted TO-BE D-11は、typed失敗分類をそのまま列挙せず
  **「もう一度取り込む / 貼り直す / 依頼を作り直す / 中断する / 診断を開く」の手段別**へ再投影し、
  typed原因を補助情報（詳細展開・診断）に格下げすることを決定した。
- accepted TO-BE D-12（§8.3）は、取り込み時stale（digest不一致）の表示を
  T-18「依頼の内容が古くなりました」+ remedy「依頼を作り直す」へ固定する。現行の
  `exchange_failure_context_stale`（「依頼作成後にホーム画面が変化しました。新しい依頼文を
  作成してください。」）は意味は整合するが、手段別語彙（依頼を作り直す）のprimary copyとして
  未整備である。

一方、T-18の**成功面**（取り込み済み・未適用状態）はspec 328（accepted・implemented）として
実装済みであり、件数サマリ・提案グループ件数・未適用表示・CTA・破棄（「破棄して閉じる」+
Back確認dialog）が存在する。本Issueは成功面の表示構造をT-18として確定し、契約を
spec 328 accepted範囲（AC-2/AC-4。pending intentはprocess-localのまま）で維持する。
durable化・status card統合は#374、CTA語彙のT-18語彙への統一（「この提案で続ける」）も
#374のspec 328 rev.2が所有する。

## Outcome

取り込み結果面（T-18失敗面）のprimary表示が**手段別5種の語彙**で構成される。各typed失敗は
20種のtyped語彙を列挙する代わりに、1つのprimary remedy（もう一度取り込む / 貼り直す /
依頼を作り直す）に対応するprimary copyと操作を持ち、typed原因（分類名・typed固有の説明・
認識framing/version/entry数・raw text）は**折りたたまれた詳細展開**にのみ現れる。失敗面には
面レベルの手段として「中断する」（zero-writeでflowを閉じる。依頼は生存・確認不要）と
「診断を開く」（既存の診断面への遷移。現在のattemptのtyped原因をprocess-local・非永続で
受け渡し、診断面に補助行として表示する。journal記録・永続化は行わない）が常設される。`CONTEXT_STALE`の
primary copyは「依頼の内容が古くなりました」+「依頼を作り直す」である（D-12行）。

T-17取り込み入力面（clipboard/file-first＋手動paste折りたたみ、1 MiB gate、bounded editor、
parse-first表示）の契約・次元・上限はspec 332 accepted範囲で**一切変更しない**。取り込み
成功面（T-18成功面）はspec 328 accepted範囲（件数サマリ＋提案グループ件数＋未適用である旨＋
CTA＋破棄。CTA処理中の不受理・Back interception含む）で維持する。表示のみの変更であり、
persistent state・DB書込経路・同意gate構造・validator/normalizer契約は不変である。

## Scope

- **T-18失敗面の手段別再投影（D-11）**: import失敗のprimary表示を、typed失敗20種の固有文言の
  直接提示から、**手段別のprimary copy + primary操作**へ再構成する。primary操作の語彙は
  「もう一度取り込む / 貼り直す / 依頼を作り直す」（class別に1つ。下記mapping表）であり、
  面レベルの常設手段として「中断する」「診断を開く」を加えた5種が失敗面の語彙全体を構成する。
  typed分類名・typed固有の説明・認識情報（framing/version/entry数）・raw textは
  **詳細展開（折りたたみ、既存のraw detail pattern）**にのみ表示する。
- **primary mapping表（本specが所有する20種→手段の対応）**: `ExchangeImportFailure` 全20種
  （envelope 4 + normalization 2 + contract 14）の各々について、primary remedy category
  （貼り直す / もう一度取り込む / 依頼を作り直す）とprimary copyを固定する（Behavior scenarios
  のmapping表）。既存typed文言の**説明部分**は詳細展開のtyped原因説明として継承する。
- **D-12 stale行の表示**: `CONTEXT_STALE` のprimary copyを「依頼の内容が古くなりました」+
  remedy「依頼を作り直す」へ固定する（TO-BE §8.3の取り込み行）。
- **面レベル手段の新設**: 失敗面に「中断する」（flowを閉じる。zero-write・依頼（session）は
  生存・確認不要 — 失う作業が存在しないため。D-13 §9のzero-write中止規約）と
  「診断を開く」（既存の診断routeへの遷移。遷移時に現在のattemptのtyped失敗を
  process-local・非永続で診断面へ受け渡し、診断面は現在の取り込み失敗のtyped分類名と
  typed原因説明（契約文言のみ。ユーザーデータ無し）を補助行として表示する。
  exchange失敗eventのdiagnostics journal記録は本Issueでも追加しない）を置く。
  これによりD-11の補助情報経路「詳細展開・診断」の両方が現在の失敗に対して成立する。
- **T-18成功面の表示構造確定（spec 328維持）**: 成功面（`ExchangeImportSuccess`）の構成要素
  （件数サマリ（認識件数・判断なし合算・内訳4種・提案グループ件数を含むgroup breakdown・
  全体方針行）・未適用表示・CTA・破棄「破棄して閉じる」・Back確認dialog・CTA処理中の
  破棄/Back不受理）はspec 328 accepted契約どおり維持する。本Issueはこれに**表示面の追加変更を
  しない**（回帰として固定するのみ）。
- **T-17入力契約の不変回帰**: clipboard/file/manual pasteの取得UI・契約・次元
  （`maxLines = 8` + `heightIn(max = 200.dp)`）・1 MiB envelope gate・parse-first表示・
  source別typed失敗（clipboard空/非text、file読取失敗）のin-place表示はspec 332 accepted契約
  どおり維持する（回帰として固定するのみ）。
- **D-13語彙の統一確認（本Issueの新規・改訂copyに限定）**: 本Issueが新設・改訂する失敗面の語彙
  （「中断する」= zero-write中止・確認不要、primary操作3種、詳細展開）がD-13 §9規約に従うことを
  検証する。既存copyでD-13と衝突する語彙がT-17/T-18の範囲内にあれば統一する。
  **成功面の取り込み破棄の確認契約（「破棄して閉じる」明示ボタン=追加確認なし・system Back=
  確認dialog 1回。spec 328 D-2確定）は本Issueでは現行どおり維持する**。これはD-13 §9の
  「取り込み済み提案の破棄=必須確認」に対する既知の未整合であり（CONTEXT.md 取り込み破棄の
  定義が明示する）、その解消（語彙・確認契約・CTA語彙を含むspec 328改訂）は
  **#374（spec 328 rev.2）が所有する**（disposition §3.14のownership境界）。本Issueはこの
  未整合を解消せず、ACでその部分のD-13準拠を主張しない。
- **spec改訂（実装PRで実施。disposition §5 更新順序 #7）**:
  - **spec 205**: AC-5の表示部分を「typed失敗種別がuserに説明される」から
    「手段別primary再投影 + typed原因は補助情報（詳細展開）」へ改訂する（D-11）。
    validator失敗分類・zero-write・framing/envelope契約本体は不変。実装主体の記述を
    #373の成果物へ更新する。
  - **spec 332**: 配置表記をTO-BE T-17へ対応付け（V-33 → T-17）、失敗・成功の**結果表示**の
    正本がspec 205/328と本spec（#373）側である旨を境界節へ明記する。AC・Decisions・
    実寸（D-4）は不変。
- **Localization**: 新規・改訂のuser-visible文字列はAndroid resource由来でEN（`values/`）と
  ja（`values-ja/`、正本）の双方に供給する（spec 123契約）。copyはTO-BE §10語彙
  （取り込み/依頼/提案/破棄/中断）とD-13語彙規約に従い、契約内部語（digest、revision、
  session等）をprimary面に出さない。

## Non-goals

- 取り込み済み提案（pending intent）のdurable化・hub status cardへの依頼/提案表示・
  spec 328 rev.2（freeze再設計・語彙更新・CTA語彙のT-18語彙統一を含む）: **#374**。
  pending intentは本Issueでもprocess-localのままである（spec 205/328の非永続契約を変えない）。
- 成功面CTA文言の「この提案で続ける」への語彙統一: **#374（spec 328 rev.2）**。
  本Issueではspec 328 D-3確定文言（idle「この提案で整理を進める」/ run-in
  「選択に戻って整理を確定する」）を**維持**する。TO-BE §5.1 T-18の主CTA欄
  「この提案で続ける」は面の主CTA概念の呼称であり、実装文言の変更指示ではない。
- `SCOPE_MISMATCH` のremedy原因別明文化（SET_MISMATCH=選択修正で継続可 /
  PROJECTION_MISMATCH=再依頼のみ）と選択面・run失敗面での表示再構成: **#375**（表示面の
  構造は#369が所有）。本Issueは選択面の `scopeRejection` 行と `ScopeMismatchFailed` stateの
  表示（`exchangeContractFailureText` の残り2 call site）を変更しない。
- `SCOPE_MISMATCH` / `UNKNOWN_CATEGORY_REF` を含むvalidator失敗分類・#329 normalizerの
  typed失敗・envelope上限（1 MiB）・framing規則の変更（spec 204/205/329契約は不変）。
- #337 AC-9（promotion UX: `カテゴリとして保存` の実接続）とAC-10（既存built-in /
  永続user-defined / run-scoped proposalの3種区別UI完全化）: **#337**の残件である。
  本Issueの成功面は現行のgroup breakdown表示（既存カテゴリ合算行 + 提案グループ行）を維持し、
  3種区別の完全化は#337の実装PRで本specのT-18構造に整合して行う（owner記録の方針）。
- validator/normalizer契約（spec 204/329）の変更（Issue本文Non-goals）。
- exchange失敗eventのdiagnostics journalへの記録追加（organizer-diagnostics.md契約の変更を
  伴うため本Issueでは行わない。Contract notes 4）。
- T-15/T-16の再構成・idle entry rowの撤去（#372）、hub/status cardの構成（#366/#374）、
  run面の表示統合（#369）。
- run state machine・typed outcome・persistent store・permission・外部送信経路の変更。

## Domain language

`CONTEXT.md` への追加用語（#365はmerge済み（PR #379）のため正本改訂の所有者は不存在であり、
**本Issueが所有する**。実装PRで `CONTEXT.md` へ反映する）。CONTEXT.mdへの登録は下記の
**手段別失敗投影** 1項目とし、primary remedy・面レベル手段はその定義内の概念として
含める（用語の爆発を避ける）。

**手段別失敗投影 (Failure Remedy Projection)**:
import失敗のtyped分類を、ユーザーの次の行動（もう一度取り込む / 貼り直す / 依頼を作り直す /
中断する / 診断を開く）の語彙へ再投影した表示モデル（TO-BE D-11）。各typed失敗に1つの
primary remedy（class別のprimary copyと操作）を対応させ、typed失敗によらず常設される
面レベル手段（中断する・診断を開く）を併置する。primary面は手段別語彙のみを出し、
typed原因は詳細展開と診断に格下げされる。
_Avoid_: typed失敗一覧（20種の列挙そのものはprimary面に現れない）、エラーコード表示
（分類名は補助情報に限る）

以下の2語は本specの表示model内部の構成概念であり（手段別失敗投影の定義に含まれる）、
CONTEXT.mdへは独立項目として登録しない。

**primary remedy (手段別primary)**:
1つのtyped失敗に対して失敗面のprimary面に現れる、単一のremedy categoryとそのcopy・操作。
本specのmapping表が20種全typedの対応を固定する。
_Avoid_: 複数remedyの併記（primaryは1つ。補助案内は詳細展開へ）

**面レベル手段 (face-level means)**:
typed失敗の種別によらず失敗面に常設される手段（中断する・診断を開く）。どのtyped失敗に
対しても同一の意味を持つ。
_Avoid_: class別のsecondary案内との混同（class別の補助情報は詳細展開に格下げされる）

## Behavior scenarios

### Scenario: T-18失敗面のprimaryは手段別語彙であり、typed原因は詳細展開のみである（D-11）

Given importがいずれかのtyped失敗（envelope 4種・normalization 2種・contract 14種のいずれか）で
zero-write拒否され、取り込み結果面（T-18失敗面）が表示されている、
When 失敗面のprimary面を観察する、
Then primary面はmapping表（次scenario）に対応する**1つの手段別primary copy**と、
そのremedy categoryに対応する**primary操作**（もう一度取り込む / 貼り直す → 入力面への復帰、
依頼を作り直す → 依頼作成導線への復帰）を持ち、
And primary面にはtyped失敗の種別を識別させる契約内部語（`CONTEXT_STALE` 等のtyped分類名）と
20種のtyped固有文言を**列挙する表示が存在しない**、
And typed原因（typed分類名。typed固有の説明。認識framing / version / entry数）とraw textは
**折りたたまれた詳細展開**（既存のraw detail pattern。default閉・bounded・内部scroll）に
のみ表示され、詳細展開を開いてはじめて読める、
And 失敗面には面レベル手段として「中断する」と「診断を開く」が常設される（後述scenario）、
And 失敗のsettleは既存のattempt anchor契約（spec 328 AC-1）どおりであり、
本Issueの変更で遅延settleの扱いが変わらない。

### Scenario: primary mapping表（20種 → 手段別primary）

Given `ExchangeImportFailure` の20種のtyped失敗、
When 失敗面が表示される、
Then primary copy（ja正本。enは同意味）とprimary操作が次のmapping表どおりである:

| typed失敗 | primary remedy | primary copy（ja draft。文言確定は実装PR・非blocking） |
|---|---|---|
| `Envelope.InputOversize` | もう一度取り込む | 回答が大きすぎます。前後の文章を減らして、もう一度取り込んでください。 |
| `Envelope.FramingMissing` | 貼り直す | 回答の中に取り込める整理案がありません。AIアプリで最終案を送り直してもらい、貼り直してください。 |
| `Envelope.FramingAmbiguous` | 貼り直す | 回答に複数の整理案が含まれています。最終案を1つだけ送り直してもらい、貼り直してください。 |
| `Envelope.FramingEmpty` | 貼り直す | 整理案が空でした。AIアプリで最終案を送り直してもらい、貼り直してください。 |
| `Normalization.AmbiguousBlocks` | 貼り直す | 回答に複数のコードブロックがあります。整理案のJSONだけをコピーして貼り直してください。 |
| `Normalization.UnrecognizedFormat` | 貼り直す | 取り込める形式がありません。AIアプリで最終案を送り直してもらい、貼り直してください。 |
| `Contract.SchemaMismatch` | 貼り直す | 回答を整理案として読み取れませんでした。AIアプリで送り直してもらい、貼り直してください。 |
| `Contract.ExportMismatch` | 依頼を作り直す | この回答は現在の依頼宛ではありません。依頼を作り直してください。 |
| `Contract.SessionExpired` | 依頼を作り直す | 依頼の有効期限が切れています。依頼を作り直してください。 |
| `Contract.ContextStale` | 依頼を作り直す | 依頼の内容が古くなりました。依頼を作り直してください。（D-12行） |
| `Contract.Oversize` | 貼り直す | 整理案本体が大きすぎます。より小さい整理案を依頼して、貼り直してください。 |
| `Contract.UnknownRef` | 貼り直す | 整理案が依頼に存在しない項目を参照しています。AIアプリで送り直してもらい、貼り直してください。 |
| `Contract.DuplicateRef` | 貼り直す | 整理案に重複した項目があります。AIアプリで送り直してもらい、貼り直してください。 |
| `Contract.IncompleteCoverage` | 貼り直す | 整理案の項目指定に重複があります。AIアプリで送り直してもらい、貼り直してください。 |
| `Contract.InvalidEnum` | 貼り直す | 整理案に未知の値が含まれています。AIアプリで送り直してもらい、貼り直してください。 |
| `Contract.ForbiddenContent` | 貼り直す | 整理案に禁止された指示が含まれています。AIアプリで送り直してもらい、貼り直してください。 |
| `Contract.MobilityContradiction` | 貼り直す | 整理案が動かせない項目を動かそうとしています。AIアプリで送り直してもらい、貼り直してください。 |
| `Contract.CapabilityUnsupported` | 貼り直す | 整理案が未対応の機能を使用しています。AIアプリで送り直してもらい、貼り直してください。（V1到達不能のreserved class） |
| `Contract.ScopeMismatch` | 依頼を作り直す | （網羅性のためのmapping。T-18失敗面では発生しない — run接続時gate由来。選択面・run失敗面での表示は#369/#375の所有） |
| `Contract.UnknownCategoryRef` | 依頼を作り直す | 回答が参照したカテゴリが現在利用できません。依頼を作り直してください。 |

And 「もう一度取り込む」と「貼り直す」のprimary操作はいずれも入力面（T-17）への復帰である
（現行の再取り込み挙動と同一。raw textの破棄・retention boundaryもspec 332 AC-7のまま）。
差異はcopyの意味であり、貼り直し前にAIアプリで回答を送り直す/コピーし直すことを案内する、
And 「依頼を作り直す」のprimary操作は依頼作成導線への復帰である（idle entry: T-15依頼作成面
へ — #372適用後の `openFlow()` 起動先。run-in entry: 既存のscope凍結生成導線と同一の復帰）。
操作は既存の生成flow起動seam（`openFlow()` 相当）を不変で使い、sessionの置換確認（spec 205
AC-13）は依頼作成時の既存gateで効く、
And primary操作のいずれもsession・layout DB・validator入力へのwriteを行わない（zero-write）。

### Scenario: D-12 stale行 — `CONTEXT_STALE`は「依頼の内容が古くなりました」

Given 依頼作成後にホーム・分類・lock・availabilityが変化し、取り込み時のdigest照合が
`CONTEXT_STALE`で拒否した、
When T-18失敗面が表示される、
Then primary copyは上表のとおり「依頼の内容が古くなりました。依頼を作り直してください。」であり、
primary操作は「依頼を作り直す」である（TO-BE §8.3 stale remedy対応表の取り込み行）、
And 詳細展開にはtyped原因の説明（依頼作成後にホームが変化した旨。既存
`exchange_failure_context_stale` の説明部分を継承）が現れる、
And 部分適用・rebaseの導線は存在しない（remedyは常に「現在の状態を正として取り直す」こと。
D-12「古い提案の部分適用は存在しない」の回帰）。

### Scenario: 中断する（面レベル手段・zero-write・確認不要）

Given T-18失敗面が表示されている（typed失敗の種別は任意）、
When 「中断する」を選ぶ、
Then exchange flowが閉じられ、画面はhost面へ戻る。import失敗はzero-writeであり、
失う作業が存在しないため確認dialogは出ない（D-13 §9のzero-write中止規約）,
And 依頼（export session）は失効せず、有効期限内であれば同じ回答の再取り込みが可能である、
And pending state（import attempt）は既存の `close()` 契約どおり破棄される
（成功状態・成功面の破棄semanticsはspec 328のまま。本scenarioは**失敗面**の閉じ方のみを規定する）。

### Scenario: 診断を開く（面レベル手段・既存導線・現在の失敗のtyped原因を非永続で受け渡す）

Given T-18失敗面が表示されている、
When 「診断を開く」を選ぶ、
Then 既存の診断route（host面が既に使う `onOpenDiagnostics` 相当の遷移先と同一の診断面）へ
遷移し、診断面には **現在のattemptの取り込み失敗** のtyped分類名（`CONTEXT_STALE` 等の
closed set。TO-BE §10が補助情報位置として認める診断）とtyped原因説明（詳細展開と同一の
契約文言）が補助行として表示される（D-11の補助情報経路「詳細展開・診断」の両方が
現在の失敗に対して成立する）,
And 受け渡しは **process-local・非永続** である（遷移時の引数受け渡しのみ。diagnostics
journal・永続store・logcatへの書込みは発生しない。exchange失敗eventのjournal記録追加は
本Issueのscope外 — Contract notes 4）。診断面の本体（説明文・journal export等の既存構成）は
変化せず、補助行は引数が渡された遷移でのみ現れる（他入口からの診断面は現行のままである）,
And 診断面へ渡すのはtyped分類名とtyped原因説明のみであり、raw import text・export-scoped
`ref`・app label・folder title等のユーザーデータは渡さない,
And 診断面から戻った場合、失敗面のraw text保持（spec 332 AC-7のretention boundary）は
画面stateとして維持されることを要求しない（process内の画面復帰挙動は現行のhost面挙動に従い、
raw textが破棄されていても再取り込みが回復pathである）。

### Scenario: T-17入力契約の不変回帰（spec 332）

Given 本Issueの実装PRが適用されている、
When 取り込み入力面（T-17）を操作する、
Then clipboard読込・file読込・手動paste折りたたみ（bounded editor `maxLines = 8` +
`heightIn(max = 200.dp)`）・1 MiB envelope gate（全経路同一・全量処理前fail）・parse-first表示
（認識framing/version/entry数・raw折りたたみ）・source別typed失敗のin-place表示
（clipboard空/非text、file読取失敗）がspec 332 accepted契約どおり機能する
（spec 332 AC-1〜AC-4/AC-10対応testのgreenで検証される）、
And source別typed失敗の表示はT-18失敗面へ**移動しない**（in-place維持。既存copyは
既に「コピーし直し→再読込→別source」の行動案内構造であり、D-13/手段別語彙との衝突が
あれば語彙のみ統一する）、
And 取り込みtextのprocess memory外書き出し禁止・明示操作以外のclipboard access禁止の
regressionは維持される。

### Scenario: T-18成功面の表示構造はspec 328 accepted契約どおりである（回帰）

Given importがvalidationを通過した、
When 取り込み成功面（T-18成功面）が表示される、
Then 件数サマリ（認識件数・判断なし合算・内訳4種・group breakdown（既存カテゴリ行 +
提案グループ行）・run-inでは候補数）・planner-effectiveな全体方針行・未適用である旨・
CTA（idle「この提案で整理を進める」/ run-in「選択に戻って整理を確定する」）・
破棄「破棄して閉じる」がspec 328 AC-2/AC-4/D-2/D-3契約どおり表示される（本Issueで
表示面の追加変更をしない）、
And CTA押下時のsingle-flight・CTA処理中（`continuing`）の破棄/Back不受理・system Backの
確認dialog・hosting画面level interception（spec 328 AC-3/AC-5/AC-7）は現行契約どおりであり、
本Issueの失敗面再構成と独立してgreenである、
And 提案グループ件数（`proposedGroupCount`）の表示が維持され、#337 AC-10の3種区別UIは
本面の表示構造内で後続実装できる（本Issueでは合算表示のまま。#337残件）。

### Scenario: 破棄/中断/キャンセル語彙の統一（D-13。本Issueの新規・改訂copyに限定）

Given 本Issueが新設・改訂するT-17/T-18失敗面の操作label（「中断する」・primary操作3種・
詳細展開）と、本Issueが変更しない現行labelを区別して観察する、
Then 本Issueの新規・改訂copyはD-13 §9規約に従う: 失敗面の「中断する」はzero-write中止であり
確認不要で、失う作業がある中断（run flowの中断確認）と区別される。typed原因の詳細展開の
開閉は確認dialogを伴わない,
And 不可逆な破棄（取り込み破棄）は「破棄」ラベルであるが、その確認契約
（明示ボタン経由=追加確認なし・system Back経由=確認dialog 1回）はspec 328 D-2確定の
回帰として現行どおり維持する。**この確認契約はD-13 §9の「取り込み済み提案の破棄=必須確認」に
未整合であることが既知であり（CONTEXT.md 取り込み破棄の定義）、本specはこの部分を
D-13準拠として主張しない**。解消（語彙・確認契約・CTA語彙の統一を含むspec 328改訂）は
#374（spec 328 rev.2）が所有する（disposition §3.14のownership境界）,
And 確認不要の中止（入力面のキャンセル等、現行 `exchange_cancel` 系）は「キャンセル」の
ままであり、「破棄」「中断」と混用しない,
And 本Issueの範囲（失敗面の新規・改訂copy）でD-13 §9に衝突するlabelが発見された場合は
T-17/T-18内で統一し、成功面の破棄確認契約と範囲外（T-15/T-16）の語彙は#374の所有である。

### Scenario: 既存失敗経路・競合機構の回帰

Given 本Issueの実装PRが作成されている、
When spec 205/328/329/332/348対応の既存test（`ExchangeImportPipelineTest` /
`ImportNormalizerTest` / `ExchangeFlowStateHolderTest` /
`ExchangeImportSurfaceInstrumentationTest` / `ExchangeImportSuccessInstrumentationTest` /
`Issue348AiFacingContractSyncTest` 等）を実行する、
Then 失敗表示の表示model変更対象箇所以外は無編集または表示面移設のみの更新でgreenであり、
pipeline・validator・normalizer・envelope gate・attempt anchor・success state・freeze機構は
無変更である、
And 選択面の `scopeRejection` 行と `ScopeMismatchFailed` stateの表示
（`exchangeContractFailureText` の残り2 call site）は本Issue適用前と同一である
（#369/#375の所有。`exchangeContractFailureText` 関数自体はこれらのcall site向けに残る —
T-18失敗面は新しい手段別projectionを通す。Contract notes 5）。

### Scenario: accessibility・環境

Given TalkBack / Switch Access / large font環境、
When T-18失敗面を操作する、
Then primary copyは到着時にlive regionで通知され、primary操作・中断する・診断を開く・
詳細展開の開閉がTalkBackで識別可能（name/role/state）であり、Switch Access / keyboardのみで
「失敗の把握 → primary操作の実行（または中断/診断）」まで完結できる、
And 詳細展開はexpand/collapse stateをTalkBackへ伝え、展開前にtyped原因textが読み順へ
大量投入されない（D-11の補助情報化のa11y上の利点を保持）、
And 200% font scaleでprimary操作が画面内に到達可能であり、typed原因textが主要操作を
押し流さない（既存bounded patternの継承）、
And primary remedyと詳細展開の区別は色のみに依存しない。

### Scenario: process death / stale state

Given T-18失敗面の表示中または詳細展開中にprocessが破棄される、
Then 失敗はzero-writeであるため、消失するのは表示state（raw textのephemeral保持を含む）のみであり、
layout DB・export sessionへの影響はない（現行契約の回帰）、
And 再起動後の回復は再取り込みである（依頼が有効期限内の場合。`SESSION_EXPIRED` の場合は
手段別primary「依頼を作り直す」が案内する）、
And 依頼TTL失効後の失敗面表示は「依頼を作り直す」をprimaryとし、期限切れ依頼宛の
再取り込みが失敗し続ける導線にはならない。

## Failure behavior

| Condition | Observable outcome |
|---|---|
| mapping表にないtyped失敗の到達（将来の分類追加漏れ） | 投影は **fail-closed** である: 未知のtyped失敗は既定の手段別primary（もう一度取り込む）+ typed原因の詳細展開で表示され、crash・silent失敗しない。網羅 `when` の追加はcompilerが要求する（Kotlin exhaustive when） |
| 「依頼を作り直す」操作時に依頼作成が利用不能な状態（run-in entryの選択凍結解除後等） | 操作は既存の生成flow起動seamのgateに従う（現行のentry操作と同一挙動）。typedな不受理が既存status語彙で表示され、失敗面の表示は壊れない |
| 失敗面表示中の再import | 既存のattempt anchor契約（spec 328 AC-1）どおり。失敗settle後の新importは新しいattemptであり、失敗面は置き換わる（遅延settleのdrop規則は不変） |
| source失敗（clipboard/file）とpipeline失敗の区別 | source失敗はT-17入力面のin-place表示のまま（spec 332 AC-6回帰）。pipeline失敗のみがT-18失敗面へsettleする（現行どおり）。両者が混在して1面に20種が並ぶ状態は存在しない |

## Stale state / concurrency

- 失敗面の表示対象は「そのattemptのsettle結果」であり、attempt anchor（spec 328）により
  遅延settle・古いattemptの結果が表示を置き換えない（現行契約の維持。本Issueは
  anchor機構に触れない）。
- 「依頼を作り直す」で開く生成flowの置換確認（spec 205 AC-13）は、active依頼の実効stateを
  gate時に再読取する（現行 `activeSession()` 契約）。失敗面表示中のTTL失効とのraceは
  生成側gateが防ぐ。
- 診断を開くは画面遷移であり、import attempt・pending stateの整合に影響しない
  （`close()` 系のstate変更を行わない。失敗後のattemptはすでに終端している）。

## Data and state

- 読むdata: `ExchangeImportFailure`（pipeline結果。既存のtyped enum。分類・field追加なし）、
  `RecognizedImportInfo`（認識framing/version/entry数。詳細展開へ表示）、raw import text
  （詳細展開。spec 332 AC-7のretention boundaryのまま）、active sessionの存在
  （「依頼を作り直す」操作の既存gate）。本specは新規の読取seamを設けない。
- 書くdata: **新規の永続化・preference・diagnostics event・DB書込はない**。書込みは既存の
  session保存/invalidate（spec 204/205契約）のみ。import失敗自体はzero-writeである。
  「診断を開く」のtyped原因受け渡しは遷移引数（process-localのnavigation state）であり、
  diagnostics journal・永続storeへは書き込まない。
- Identity: 変更なし。`exportId`、ref↔`ItemId` map、`sourceContextDigest`、typed失敗の
  分類identityは不変である。
- Migration / backup / restore / rollback: persistent state変更なし。schema変更なし。
  Launcher layout DB / `favorites` への接触なし（ホームレイアウト安全規約の適用対象外）。
  PR revertで旧typed文言primary表示へ戻る。downgrade時の残留物なし。
- 表示model: 失敗面の表示modelへの変換は **純粋なprojection関数** として切り出し、
  typed失敗 → primary remedy category + primary copy resource + 詳細展開内容の対応を
  table-drivenに固定する（UI層の分岐散步を防ぐ。spec 332 AC-5「UI側parseなし」規約と
  同型の規約）。出力modelはtyped原因text（詳細展開用）を含むが、clipboard/file内容の
  process外出力経路を作らない。

## Permissions, privacy, and security

- 追加permission・network通信・外部送信経路なし。
- primary面に表示するのは手段別copyのみであり、app label・folder title・export-scoped `ref`・
  AI自由文（`rationale`）・`confidence` は失敗面のいずれの層（primary・詳細展開）にも
  表示しない（spec 328 summary規約と同一のprivacy境界。詳細展開に現れるtyped固有文言は
  契約文言であり、ユーザーデータを含まない）。
- raw textの詳細展開はspec 332 AC-7のretention boundary（結果surface表示中のみの
  process memory上のephemeral保持・1箇所・遷移で破棄・process外書き出し禁止）のまま。
- clipboard監視・自動読み取り・自動送信の経路は存在しない（spec 332回帰）。
- 診断を開くは既存routeへの遷移であり、新規のdiagnostics記録を追加しない
  （organizer-diagnostics.mdの個人情報規約の維持）。診断面へ受け渡すのは現在のattemptの
  typed分類名（closed set）とtyped原因説明（契約文言）のみであり、raw import text・
  export-scoped `ref`・app label・folder title等のユーザーデータは渡さない。受け渡しは
  process-local（遷移引数）であり、diagnostics journal・永続store・logcatへは書き込まない。

## Accessibility and localization

- organization-run-ux §6のaccessibility受入基準を適用する: TalkBackのname/role/state、
  live region（primary copyの到着通知）、expand/collapse state（詳細展開）、focus restoration、
  200% reflow、non-color-only、traversal順、timeout auto-confirm/cancelの不在。
- 詳細展開の補助情報化により、TalkBack読み順のprimary部分が20種のtyped文言で占有されない
  ことを構造で保証する（primary面のnode数・読み上げ順のassertionをinstrumentationに含める）。
- 新規・改訂stringは `values/` + `values-ja/`（正本）の双方へ供給する（spec 123契約）。
  手段別copyの5語彙（もう一度取り込む/貼り直す/依頼を作り直す/中断する/診断を開く）は
  ja正本をTO-BE D-11/§9/§10の語彙と一致させ、enは同意味とする。複合文はformat resourceで
  構成する。本Issueの表示model変更により未使用になるstringは実装PRのreference grepで
  確定し、双方のresourceから削除する。

## Acceptance criteria

- [ ] **IM-AC-01**: T-18失敗面のprimary面が手段別語彙（mapping表どおりのprimary copy +
  primary操作）で構成され、20種のtyped固有文言・typed分類名がprimary面に列挙されないことが
  testされる。typed原因（分類名・typed固有説明・認識framing/version/entry数・raw text）が
  詳細展開（default閉・bounded）にのみ現れることがtestされる。mappingは20種全typedを
  table-drivenに固定し、未知のtyped失敗はfail-closedで既定remedyに落ちることがtestされる。
  （Issue受入1。D-11）
- [ ] **IM-AC-02**: `CONTEXT_STALE` のprimary copyが「依頼の内容が古くなりました」+
  primary操作「依頼を作り直す」であることがtestされる（D-12行）。rebase・部分適用の
  導線が存在しないことが回帰で確認される。
- [ ] **IM-AC-03**: 「依頼を作り直す」primary操作が依頼作成導線（idle: T-15依頼作成面。
  run-in: scope凍結生成導線と同一の復帰）へ到達し、操作がzero-writeで
  session置換確認は依頼作成時の既存gate（spec 205 AC-13）で効くことがtestされる。
  「もう一度取り込む」「貼り直す」primary操作がT-17入力面へ復帰し、復帰時のraw text破棄
  （retention boundary）が現行どおりであることがtestされる。
- [ ] **IM-AC-04**: 失敗面に「中断する」（zero-write close・確認不要・依頼生存）と
  「診断を開く」が常設されることがtestされる。「診断を開く」は既存診断routeへの遷移であり、
  診断面に現在のattemptのtyped分類名・typed原因説明が補助行として表示されること
  （process-local・非永続の受け渡し。raw text・ユーザーデータは渡さない。他入口からの
  診断面では補助行が出ない）と、diagnostics journalへの書込みが発生しないことが
  test/reviewされる。（D-11の5語彙の残り2種。D-11の補助情報経路「詳細展開・診断」を
  現在の失敗に対して両方成立させる）
- [ ] **IM-AC-05**: T-17入力契約の回帰: spec 332 AC-1〜AC-4/AC-10対応test
  （clipboard/file/paste・1 MiB gate・bounded editor・parse-first表示・source別typed失敗の
  in-place表示）が無編集または表示面非依存の更新のみでgreenである。（Issue受入2）
- [ ] **IM-AC-06**: T-18成功面の回帰: spec 328 AC-2/AC-4対応test（未適用表示・件数summary
  （認識件数・判断なし合算・内訳4種・提案グループ件数を含むgroup breakdown・全体方針行））
  およびAC-3/AC-5/AC-7対応test（CTA single-flight・CTA処理中の破棄/Back不受理・
  system Back interception・破棄「破棄して閉じる」+ Back確認dialog）がgreenである。
  成功面に本Issueによる表示変更がないことがdiff reviewで確認される。（Issue受入3）
- [ ] **IM-AC-07**: 本Issueの新規・改訂copy（失敗面の「中断する」・primary操作・詳細展開）の
  破棄/中断/キャンセル語彙がD-13 §9規約に従うことがtest/reviewされる。成功面の取り込み破棄の
  確認契約はspec 328 D-2確定の回帰として維持され、それがD-13 §9に対する既知の未整合であり
  #374（spec 328 rev.2）が解消を所有すること（本specがこの部分のD-13準拠を主張しないこと）
  がPR本文に記録される。（Issue受入4）
- [ ] **IM-AC-08**: 旧oracle（typed文言をprimary表示として固定していた20種失敗表示oracle・
  spec 348 content oracleの対象string集合）の更新と、旧oracleがobsoleteである理由
  （D-11再投影による表示model変更。no-AI-repair-loop境界と「診断」forbidden markerの
  区別を含む）のPR記録が行われる。（Issue受入5。disposition §1「旧UXを固定するtest oracleは
  削除ではなく『旧oracleがなぜobsoleteか』をPRへ記録」）
- [ ] **IM-AC-09**: T-18失敗面がorganization-run-ux §6のaccessibility受入基準を満たすこと
  （primary/詳細展開のTalkBack構造・live region・200% reflow・non-color-only・
  Switch Access完結）がinstrumentation/manual evidenceで確認される。
- [ ] **IM-AC-10**: spec 205（AC-5表示面の手段別再投影への改訂）とspec 332（T-17配置表記・
  結果表示の正本境界の明記）の改訂が同じ実装PRで行われていること。validator/normalizer/
  envelope/framing契約本体に触れていないことがdiff reviewで確認される。（Issue受入6。G-9）

## Test oracle

| AC | Evidence |
|---|---|
| IM-AC-01 | unit: 手段別projection純粋関数のtable-driven test（20種全typed → primary remedy category + primary copy resource + 詳細展開内容。ja/en resource解決を含む。未知typed → 既定remedyのfail-closed oracle）+ holder unit test（失敗settle → 失敗面state）。instrumentation: 失敗面のprimary面にtyped固有文言testTag/stringが存在しないことの否定的観測 + 詳細展開default閉 + 展開時のtyped原因表示 |
| IM-AC-02 | unit: `CONTEXT_STALE` fixture（structural digest不一致。既存pipeline testのfixtureを再利用）→ primary copy/操作のassertion。instrumentation: 失敗面表示のcopy確認 |
| IM-AC-03 | holder unit test: 依頼を作り直す操作 → `openFlow()` 相当seam呼出・session不変・zero-write。再取り込み操作 → `openImport()` 呼出・raw text破棄。run-in entryのscope凍結復帰の回帰。instrumentation: 導線の到達 |
| IM-AC-04 | instrumentation: 「中断する」→ flow close・確認dialog不在・status経由で依頼生存の確認（再取り込み成立）。「診断を開く」→ route遷移・診断面の補助行に現在のattemptのtyped分類名・typed原因説明が表示されること（遷移元attempt由来）・他入口からの診断面では補助行が出ないこと・raw text等ユーザーデータの受け渡し不在・diagnostics journal書込み経路不在のreview/unit |
| IM-AC-05 | spec 332対応既存test（`ExchangeImportSurfaceInstrumentationTest`、`ExchangeFlowStateHolderTest` のsource失敗系、`ExchangeImportPipelineTest` envelope系、`ImportNormalizerTest`）のgreen + CI lane（`organizer-instrumentation-issue332-tests`）のgreen |
| IM-AC-06 | spec 328対応既存test（`ExchangeImportSuccessInstrumentationTest`、`ExchangeFlowStateHolderTest` のsuccess/arbiter/anchor系）のgreen + 成功面diff review |
| IM-AC-07 | strings走査（新規・改訂labelの語彙確認）+ review（D-13 §9規約との照合）。ja/en name集合・placeholder一致の機械確認 |
| IM-AC-08 | 旧oracle更新のdiff（手段別oracleへの置換）+ PR本文へのobsolete理由記録（D-11再投影・TO-BE/disposition参照。spec 348 no-AI-repair-loop境界の維持説明を含む） |
| IM-AC-09 | Compose semantics assertion（primary/詳細展開の構造・live region・expand/collapse state）+ 200% font scale test + light/dark × ja/default screenshot evidence |
| IM-AC-10 | specs 205/332のdiff review（改訂箇所が本spec Scope節と一致・契約本体不変） |

共通gate: `./gradlew spotlessCheck`、`./gradlew testLawnWithQuickstepGithubDebugUnitTest
--tests 'app.lawnchair.organizer.*'`、exchange系instrumentation lane
（`ExchangeImportSurfaceInstrumentationTest`、`ExchangeImportSuccessInstrumentationTest`）、
CI `final-status` green。本Issueは表示のみの変更であり（persistent state・DB書込経路・
同意gate構造に触れない）、`risk: layout-data`/`risk: migration` を付けない。

## Contract notes（owner reviewで確認すべき解釈）

1. **「診断を開く」は面レベルの常設手段であり、どのtyped失敗のprimaryにもならない（V1）**。
   20種のtyped失敗はすべて入力・契約起因であり、ユーザーが解決可能なremedy（もう一度取り込む /
   貼り直す / 依頼を作り直す）を持つため。D-11の5語彙は「失敗面の語彙全体」として解した。
   特定class（例: 未知失敗のfallback）を「診断を開く」primaryにする解釈がowner reviewで
   選ばれた場合は、mapping表とIM-AC-01/04を修正する。
2. **「貼り直す」と「もう一度取り込む」の境界**: 「貼り直す」= AIアプリ側で回答を送り直してもらう
   /コピーし直すことを案内する語彙（回答の内容が原因のtyped失敗）、「もう一度取り込む」=
   同一・別sourceでの再取り込みを案内する語彙（入力の形態・サイズが原因のtyped失敗）。
   両操作ともT-17入力面への復帰である（navigation上の差異はない）。
3. **成功面CTA文言**: Issue本文の「この提案で続ける」はTO-BE §5.1 T-18の主CTA概念の呼称であり、
   本Issueではspec 328 D-3確定文言を維持する。T-18語彙への統一は#374（spec 328 rev.2）が
   所有する（disposition §3.14のconflict 3・§4.1）。owner reviewで本Issue内での統一が
   選ばれた場合は本specを修正する。
4. **diagnostics journal記録は追加しない。typed原因は非永続の受け渡しで診断面へ現れる
   （review指摘対応で確定）**: D-11の「typed原因は補助情報（詳細展開・診断）とする」を、
   本Issueでは (1) 失敗面の詳細展開 と (2) 「診断を開く」遷移時の診断面への
   process-local・非永続なtyped原因受け渡し（分類名+契約文言の説明。ユーザーデータ無し）で
   成立させる。exchange失敗eventのdiagnostics journal記録追加は行わない —
   organizer-diagnostics.mdのjournal契約はorganization run / recoveryのみを記録対象とし、
   この境界を変えるには契約改訂とprivacy reviewを要する（本Issueでは行わない）。
   診断面への表示はjournalを経由しないuser-facing reason層の構成である
   （organizer-diagnostics.md §2の「UIはresult typeから直接組み立てる」分離規約に従う）。
5. **`exchangeContractFailureText` の残り2 call site**: 選択面 `scopeRejection` 行と
   `ScopeMismatchFailed` stateはrun flowの表示であり、#369（T-13統合・Non-goalsで
   exchange表示を現行のまま維持）と#375（SCOPE_MISMATCH remedy原因別明文化）が所有する。
   本IssueはT-18失敗面のみを新しい手段別projectionへ通し、関数自体は残す。関数の置換・統合は
   #375以降のcleanup（#377）で評価する。
6. **source失敗（clipboard/file）のin-place維持**: spec 332 AC-6のin-place typed表示は
   行動案内構造が既に手段別と整合するため、T-18失敗面へ移動しない。移動する解釈が
   owner reviewで選ばれた場合は、入力面のstate保持（spec 332のeditor state維持）との
   整合を含めて本specを修正する。

## Dependencies

- **#372（merged。PR #393）**: Issue本文 `Depends on`。失敗面「依頼を作り直す」の到達先
  （T-15依頼作成面）とT-17入力面への到達経路（T-15面の取り込み導線）は#372の実装が所有する。
  **実装着手の前提（disposition §8 依存graph `#372 → #373`）は#372のmergeで満たされる**。
  実装PRはmerge後のhosting・到達経路の実装と整合させる（plan.md Current evidenceの
  re-entry確認対象）。
- **#365（merged。PR #379）**: `CONTEXT.md` の正本改訂（Organizer hub・材料・依頼・
  取り込み済み提案・D-13語彙規約等）はmerge済み。本specの新規用語（手段別失敗投影）の
  `CONTEXT.md` 追加は**本Issue（実装PR）が所有する**（Domain language節）。
- **#369（merged。PR #387）**: T-07〜T-13のrun面統合はmerge済み。本specはexchange導線の
  表示のみを変え、#369がexchange失敗の手段別再投影を#373へ委譲したNon-goals境界は
  実装済み契約として維持される。
- **spec改訂の所有**: spec 205 AC-5表示面とspec 332表記は本Issueの実装PRが改訂する
  （disposition §5 更新順序 #7）。
- **前提（実装済み・accepted）**: specs 204（accepted・implemented）/205（implemented）/
  327（implemented）/**328（accepted・implemented）**/329/330/331/**332（implemented）**/
  **337（accepted・contract core implemented、AC-9/AC-10残件）**/348（implemented）の現行契約、
  spec 123（strings契約）、organization-run-ux §6（accessibility受入基準）、
  organizer-to-be-ux.md @ main `dcaecf6913`（accepted、PR #364）、
  organizer-disposition-migration.md @ main `dcaecf6913`（accepted、PR #378）。
- **後続**: #374（durable pending intent・spec 328 rev.2・status card・CTA語彙統一。#373後）、
  #375（SCOPE_MISMATCH原因別remedy・選択復元初期値）、#337残件（AC-9 promotion UX・
  AC-10 3種区別UI。#373のT-18表示構造と整合して着手 — owner記録）、#377（cleanup）。

## Open questions

実装開始前に解消が必須な問いはない。Contract notes 1〜6のうち、4はreview指摘対応で
確定した（diagnostics journal記録追加なし・非永続のtyped原因受け渡し）。残る1・2・3・5・6の
解釈確認をowner reviewが所有し、確認前は本specは `draft` のままである。非blocking事項:

1. 手段別primary copyの最終文言（ja正本・en。D-11/§9/§10語彙に沿うことのみ拘束）は
   実装PRで確定する。
2. 詳細展開の操作形式（展開row/リンク等）と、primary操作・面レベル手段・詳細展開の面上配置
   （縦順）は200% reflowとTalkBack読み順を満たす範囲で実装PRのreviewで確定する
   （spec 123収束対象）。
3. 既存typed文言string（`exchange_failure_*` 20種）の再配置（詳細展開用への統合・分割・
   存続）の最終形は実装PRで確定する。削除するstringはreference grep 0件を条件に
   双方のresourceから削除する。
4. `exchange_import_retry_hint`（「AIに最終JSONを```jsonコードブロック1つで再送してもらい、
   再度取り込んでください。」）の存続要否 — 手段別copyへの統合で冗長になる場合の処理は
   実装PRで確定する（spec 348 content oracleの対象stringであるため、更新時はIM-AC-08の
   obsolete記録とセットで行う）。

## Change history

- 2026-09-21: Phase1 re-entry revision 2（[review Changes requested](https://github.com/nunu1733/NunuLauncher/issues/373#issuecomment-5740056108)の指摘1〜3対応）。
  baselineを `dcaecf6913f3c139aa2406b6b7a090f72d914ddf`（origin/main。PR #393（#372実装）
  merge + PR #394（docs-only）後）へ更新し、#372/#369/#371/#368/#365 merge後のhosting面・
  `ExchangeFlowUi.kt` 構造・strings・test実体をre-verifyした（plan.md Current evidence。
  失敗面構造と `exchange_failure_*` 20種は #372 によって無変更であることを確認）。
  **(1) D-13語彙scopeの限定（指摘1・中）**: 成功面の取り込み破棄確認契約（spec 328 D-2確定=
  明示ボタン追加確認なし・Back確認dialog）はD-13 §9「取り込み済み提案の破棄=必須確認」に
  対する既知の未整合であり、その解消は#374（spec 328 rev.2）が所有する旨を明記し、
  D-13統一scenarioとIM-AC-07を本Issueの新規・改訂copy（失敗面）に限定した
  （成功面の現行契約自体は回帰として維持する）。
  **(2) 「診断を開く」の到達先契約化（指摘2・中）**: D-11の補助情報経路「診断」を、
  現在のattemptのtyped分類名・typed原因説明（契約文言のみ。ユーザーデータ無し）を
  process-local・非永続で診断面へ受け渡すprojectionで成立させた。journal記録追加は
  行わない（organizer-diagnostics.md §2の分離規約に従うuser-facing reason構成）。
  scenario・IM-AC-04・test oracle・privacy節・Data and state・Contract note 4を更新。
  **(3) #365 merge後のDependencies更新（指摘3・低）**: #365（PR #379）/#369（PR #387）を
  merge済みとして整理し、`CONTEXT.md` 用語（手段別失敗投影1項目。primary remedy・
  面レベル手段は定義内に含める）の追加を本Issue（実装PR）が所有すると明記した。
- 2026-09-19: Draft created for #373（spec/plan整備task）。accepted TO-BE契約
  （organizer-to-be-ux.md @ main `a2b6aba318`、PR #364）、accepted処分文書
  （organizer-disposition-migration.md @ main `a2b6aba318`、PR #378、§2.1/§3.12/§3.14/
  §3.17/§4.1/§5/§7.2/§8）、#372 spec draft（`6ba84fc4`）、#369 spec draft（`3c39ceb2f8`）、
  現行実装調査（`ExchangeFlowUi.kt`（`ExchangeImportOutcome`/`ExchangeImportSuccess`/
  `exchangeFailureText`/`exchangeContractFailureText`/`exchangeStatusTextResource`/
  `ExchangeFlowStateHolder`）、`ExchangeImportPipeline.kt`（失敗分類20種）/
  `ExchangeImportSummary.kt`、`ManualOrganizationPreferences.kt`（hosting・`scopeRejection`・
  `ScopeMismatchFailed`・`onOpenDiagnostics`）、ja/en strings、exchange系unit/instrumentation
  test群、Issue #328/#332/#205/#337/#348 spec、Issue #337コメント（AC-9/AC-10残件の
  #373整合方針）を入力に作成。

## References

- [Issue #373][1]
- [organizer-to-be-ux.md](../../docs/product/organizer-to-be-ux.md)（accepted。D-11/D-12/D-13、§5.1 T-17/T-18、§5.3、§8.3、§9、§10）
- [organizer-disposition-migration.md](../../docs/product/organizer-disposition-migration.md)（accepted。§2.1/§3.12/§3.14/§3.17/§4.1/§5 更新順序 #7/§7.2 (c)/§8）
- [Spec 205: External Agent Exchange](../205-external-agent-exchange/spec.md)（implemented。framing/envelope/失敗分類の所有。本IssueがAC-5表示面を改訂）
- [Spec 328: import success state](../328-exchange-import-success-state/spec.md)（accepted・implemented。T-18成功面・破棄語彙・CTA処理中不受理の所有。rev.2は#374）
- [Spec 332: exchange import input UI](../332-exchange-import-input-ui/spec.md)（implemented。T-17入力面の所有。本Issueが配置表記を更新）
- [Spec 329: Import Normalizer](../329-import-normalizer/spec.md)（implemented。normalization失敗2種の所有）
- [Spec 331: exchange target scope coupling](../331-exchange-target-scope-coupling/spec.md)（implemented。run-in entry・`SCOPE_MISMATCH` gate）
- [Spec 337: AI category/group proposal](../337-exchange-category-group-proposals/spec.md)（accepted・contract core implemented。AC-9/AC-10残件は#337）
- [Spec 348: exchange AI-facing contract sync](../348-exchange-ai-facing-contract/spec.md)（implemented。no-AI-repair-loop境界の所有）
- [Spec 204: AI personalization context/intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md)（accepted・implemented。validator失敗分類の所有）
- [Spec 123: organizer UI convergence](../123-organizer-ui-convergence/spec.md)（ja/en strings契約）
- [organization-run-ux.md](../../docs/product/organization-run-ux.md)（§6 accessibility受入基準）
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)（raw text記録禁止の正本）
- [CONTEXT.md](../../CONTEXT.md)（取り込み成功状態・取り込み破棄・持ち帰りIntent取り込み等の既存用語）, [DESIGN.md](../../DESIGN.md)（gate 13）, [AGENTS.md](../../AGENTS.md)
- 先行spec draft: [Issue #372][2]（T-15/T-16・依頼語彙）、[Issue #369][3]（run面統合・T-13）

[1]: https://github.com/nunu1733/NunuLauncher/issues/373
[2]: https://github.com/nunu1733/NunuLauncher/issues/372
[3]: https://github.com/nunu1733/NunuLauncher/issues/369
