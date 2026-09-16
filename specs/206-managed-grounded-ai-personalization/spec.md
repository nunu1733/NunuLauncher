---
issue: "#206"
status: draft
requirements:
  - FR-014 # 境界定義のみ。本件はFR-014の拡大解釈ではない
  - FR-017 # 本件はFR-017 (AI personalization intent契約) の内部managed AI consumerである
  - NFR-008
  - NFR-005
  - NFR-011
  - D-011
risk:
  - privacy
  - network
  - layout-data
updated: 2026-09-16
---

# Managed Grounded AI: アプリ内完結型Organizer personalization

> Status: draft — 本specの必須依存だった **#204 (Context / PersonalizedIntent exchange contract) は2026-09-15にaccepted** となり (6th review Approve、snapshot `52b9097c` 基準)、PR #322 経由で `origin/main` に実装込みでmerge済みである ([accepted spec 204](../../specs/204-ai-personalization-context-intent-contract/spec.md))。兄弟issue **#205 (External Agent Exchange) も2026-09-16にaccepted** となり、PR #325 (merge `3df9c7af`) で実装が `origin/main` (`4f555450bd`、本revisionのanalysis baseline) に取り込まれた ([spec 205](../../specs/205-external-agent-exchange/spec.md)、status: implemented、Issue #205 closed)。これに伴い、本specは #204 のschema詳細を暫定扱いから確定契約への参照へ更新した (契約核の変更はない)。D-011 (external LLM): 「privacy/threat modelとoffline behavior承認後まで導入しない」が requirements.md のdecision gateであり、2026-09-16のrequirements.md更新で「#205のexternal exchangeはnetwork/provider APIを含まないuser-mediated text交換でありgate外 (privacy/threat modelはspec 205が定義しreview承認済み)、**in-app provider API接続 (#206) は引き続き本gate内**」と明記された。本機能の実装開始は (1) ~~#204受入~~ (**達成済み** 2026-09-15)、(2) privacy/threat model承認、(3) offline behavior (local deterministic Organizerがnetwork/AIなしで利用可能なまま) の確認を満たすまで禁止される (Issue本文も同じ)。

## Problem

Organizer personalizationをアプリ内で完結させる場合、NunuLauncher自身にagent framework / autonomous loop / browser automationを内包するのは複雑性・保守性・セキュリティの面で過剰である。一方、単純なone-shot LLM classificationは未知アプリや意味が曖昧なアプリについて不正確な推測を行い、外部ChatGPT/Gemini等のagentic workflowより品質が明確に劣る可能性がある。

現在の主要AI provider APIは、アプリ側からはboundedな1 requestとして呼びつつ、provider側のbuilt-in web grounding / search / tool capabilityで必要な調査を行い、structured resultを返す構成が可能である。

## Outcome

NunuLauncher内から #204契約の `PersonalizationContextExportV1` をAI providerへ送信し、provider capabilityに応じた reasoning / web grounding を利用した `PersonalizedIntentV1` を取得できる **Managed Grounded AI** pathを提供する。NunuLauncherから見た実行単位はbounded request/responseであり、agent orchestration frameworkをアプリ内へ導入しない。取得したintentは #204 のstrict validation (`IntentValidator`、fail-closed・zero-write) を通り、既存 #182 planner → #194/#195 preview → confirm → apply 経路のみで消費される。

## Scope

- Provider非依存のcapability model (`STRUCTURED_OUTPUT`, `WEB_GROUNDING`, `CITATIONS`, `REASONING` 等) のtyped表現と、要件capabilityの宣言方法。
- `ManagedAiProviderAdapter` contract: request serialization、structured-output/schema binding、optional grounding enablement、response extraction、typed failure mapping、token/request size limit handling。
- 選定されたcredential / auth delivery modelのlifecycle (direct BYOK選定時: key入力、保存、表示 (masking)、再入力、削除。OAuth / short-lived credential選定時: token・sessionの取得・更新・失効。backend relay選定時: relay認証lifecycle)、backup/restore可否、全secret (key/token等) のlog/diagnostics/crash reportへの流出禁止。direct BYOKを全provider共通の標準pathとは仮定しない。
- External transmissionの明示的opt-in、送信内容確認、privacy disclosure。
- Typed failure outcomeの全套 (下記「Failure taxonomy」) と、AI失敗時のzero-write保証・silent fallback禁止。
- grounding provenance (`groundingAvailable`/`groundingEnabled`/`groundingUsed` の許容不変条件とfail-closed検証を含む) に基づくquality class (`ONE_SHOT` / `GROUNDED` / `GROUNDING_ENABLED_UNVERIFIED`) の表現と、grounding非対応時の保守的取り扱い。
- 生成intentのimmutable固定とpreview中の暗黙再実行禁止。
- 上記のaccessibilityとdiagnostics (privacy-safe metadataのみ)。

## Non-goals

- NunuLauncher内general-purpose agent framework、autonomous multi-turn agent loop、browser controller、general-purpose tool registry、planner/replanner agent graph、long-running background research agent。provider内部が1 request処理中に複数回search/reasoningすることは許容するが、その内部stepをLauncherがorchestrateしない。
- #205 External Agent Exchange (clipboard/share/fileによる外部agentとの受け渡し)。#205とは同一機能のtransport違いとして扱わない。共通化は #204 intent契約以降のみ。
- AIによる無確認layout apply。確認・適用は #194/#195/#13の既存経路を再利用する。
- providerのchain-of-thought取得・保存・表示。
- local deterministic strategiesの廃止・挙動変更 (network/AIなしで利用可能なまま維持)。
- userへのAI側からのmulti-turn clarification loop。
- 2つ目以降のprovider adapter (共通adapter contractの妥当性確認後に別Issueで追加)。
- #204 schemaの独自派生版・再定義。#204が唯一の正本。

## Domain language

`CONTEXT.md` 追加用語案 (受入時に反映)。#204/#203の契約用語 (パーソナライゼーション文脈書き出し、パーソナライゼーション意図等) は既にCONTEXT.mdへ反映済みであり、本specの用語案はそれと整合させる。

**Managed Grounded AI path (マネージドグラウンデッドAI経路)**:
NunuLauncherが #204契約のcontextをAI providerへbounded requestとして送信し、structured intentを受け取るアプリ内完結のpersonalization経路。agent実行部はprovider側にあり、Launcherはagentではない。
_Avoid_: AI連携 (#205外部exchangeとの混同)、agent機能

**bounded provider request (有界プロバイダリクエスト)**:
1回のprovider API呼び出しとして表現される実行単位。timeout、request/response size上限、tool/grounding許可がrequest policyとして宣言され、Launcher側の多段orchestrationを含まない。
_Avoid_: agent session、background research

**provider capability (プロバイダ能力)**:
adapterが宣言する、provider/function単位の機能旗 (structured output、web grounding等)。provider名やmodel名ではなくcapabilityがcontractの主語。
_Avoid_: provider名による機能判定

**BYOK (Bring Your Own Key)**:
userが自身のprovider API keyを指定してmanaged AIを利用するcredential model。keyのcustodyは端末内にあり、完全秘匿は保証されない (residual riskは文書化)。

**quality class (品質クラス)**:
managed AI実行の実効能力区分。`ONE_SHOT` (provided context + model知識のみ)、`GROUNDED` (provider response内の根拠により実際のweb grounding利用が **確認できた** 実行)、`GROUNDING_ENABLED_UNVERIFIED` (groundingを有効化して要求したが、provider responseから実際の利用を確認できなかった実行)。capability差・actual use差を隠蔽しない。
_Avoid_: capability宣言からのGROUNDED表示、実利用確認なしのGROUNDED記録

**grounding provenance (グラウンディング由来情報)**:
1実行におけるgrounding利用の3状態。`groundingAvailable` (adapterが宣言したcapability)、`groundingEnabled` (request policyがgroundingを有効化した)、`groundingUsed` (provider responseのtool call記録・grounding metadata等により実際の利用を確認できた)。quality classはこれらから導出され、`groundingUsed` の確認なしに `GROUNDED` にはならない。許容不変条件は `groundingUsed => groundingEnabled` かつ `groundingEnabled => groundingAvailable` であり、これに違反するprovider responseは成功として受け入れない (typed zero-write failure)。3状態の内部表現 (boolean 3個 / closed state model) は実装構成の選択肢であり、不変条件の維持は表現に依らず契約である。
_Avoid_: availableとusedの混同、不変条件違反combinationの成功扱い

## Capability model

Provider名ではなくcapabilityを中心に設計する。

```text
AiProviderCapabilities
  STRUCTURED_OUTPUT   // 必須。#204 の `personalized-intent-v1` schemaへのbindingに必要
  WEB_GROUNDING       // optional。quality-enhancing
  CITATIONS           // optional
  REASONING           // provider-specific / optional
```

- `STRUCTURED_OUTPUT` 相当を最低要件とする案を本draftの既定とする (比較: 「WEB_GROUNDINGも必須にする」案はprovider選択肢を狭め、`ONE_SHOT`限定modeの意義を失わせるため不採用。受入時に再確認する)。
- capabilityはadapterがtypedに宣言する。実行時のquality classはcapability宣言から直接導出しない。`groundingAvailable` (capability宣言) と `groundingEnabled` (request policy) と `groundingUsed` (provider response内のtool call記録・grounding metadata等による実利用確認) を区別し、**`groundingUsed` をprovider responseで確認できた場合のみ `GROUNDED` と表示・記録する**。actual useを確認できないproviderでは `GROUNDING_ENABLED_UNVERIFIED` として扱い、`GROUNDED` としては表示・記録しない (表示形の詳細はOpen decision 6/7)。provenance 3状態は許容不変条件 (`groundingUsed => groundingEnabled`、`groundingEnabled => groundingAvailable`) で検証され、違反responseは成功とせず `UNEXPECTED_GROUNDING` のtyped zero-write failureに分類する (「Failure taxonomy」)。
- `WEB_GROUNDING` 非対応providerでは、未知アプリを推測で断定せず #204契約の `unresolvedRefs` (coverage partitionのunresolved側) で返せるschema/prompt policyを持つ (「Failure taxonomy」の `GROUNDING_UNSUPPORTED` は「grounding要求が実行できない」ことの検出用)。

### Grounding behavior policy

`WEB_GROUNDING` 利用時の既定policy (受入時に確定)。

- contextだけで高信頼に判断できないappのみ検索対象とする方向でprompt policyを設計する。ただしこれはrisk低減策であり、保証ではない (下記のprivacy boundary参照)。
- app用途を確認するための検索は許容する。
- returned web contentはuntrusted evidenceであり、planner commandとして実行しない。final resultは常に #204 のpersonalized intentである。

### Grounding search query privacy boundary

grounding有効時、provider/modelがrequest受領後に自ら検索queryを生成する場合がある。この場合、Launcherは送信前にquery内容を検証できず、**prompt policyによって「queryにtier外の情報が入らない」ことを保証する表現はしない**。groundingはprovider APIへの一次送信とは別のprivacy/threat boundaryとして扱う。

- 送信確認UI / privacy disclosureは、「providerが送信内容から検索queryを生成し、外部検索処理を行い得る」ことを明示する (契約として固定)。
- 検索query生成を制御・監査可能なproviderのみをgrounding対象とするか、grounding実行時により厳しいexport tier制限を課すかは受入時に確定する (Open decision 10)。
- provider側のquery処理・外部検索先のデータ取扱いをNunuLauncherは保証しない。当該差はthreat model (AC-12) に含める。

## Provider abstraction

domain contractをOpenAI/Gemini固有型へ依存させない。adapterの責務:

- provider request serialization (context export表現 → provider request)
- structured-output / schema binding
- optional grounding enablement
- response extraction (provider response → #204 intent表現)
- provider error → typed app-level failure mapping (下記taxonomy)
- token / request size limit handling

Provider SDK objectをplanner/rules/application domainへ流出させない。adapterは #204契約表現とtyped outcomeの間に置かれ、production/testが同じseamを使う。

## Intended flow

```text
PersonalizationContextExportV1 (#204。ContextExportBuilder + PrivacyTier選択。実装済み)
        ↓
ManagedAiProviderAdapter (bounded request policy: timeout, size上限, capability宣言)
        ↓
provider request
   - structured output schema (personalized-intent-v1)
   - optional web grounding/search
        ↓
PersonalizedIntentV1 (#204)
        ↓
IntentCodec.decode + IntentValidator.validate (#204実装済み。fail-closed, zero-write,
   ExportSessionStoreのsession照会と structural digest再計算を含む)
        ↓
IntentPlannerAdapter.project → OrganizationInput.intentPreferences (第7 policy input)
        ↓
#182 planner / allocator (OrganizationPlanner.plan。既存唯一のplanning seam)
        ↓
#194/#195 preview → confirm → #13 apply/recovery
```

## Failure taxonomy

AI実行の全失敗をtyped outcomeとして定義する。いずれも **zero-write** (layout・selection・planning入力を一切変更しない) であり、diagnosticsへはcategoryのみを記録する。

| Failure | 条件 | Userへの表示 |
|---|---|---|
| `NO_CREDENTIAL` | 選定delivery modelのcredential (direct BYOK時はAPI key) が未設定または失効 | credential設定への導線と「local deterministicで続行」の選択肢 |
| `AUTH_REJECTED` | providerがcredentialを拒否 | 再入力導線。自動retryしない |
| `NETWORK_UNAVAILABLE` | 端末がnetworkに到達できない | offline説明とdeterministic継続選択肢 |
| `RATE_LIMITED` | provider rate limit / quota | 待機または別タイミングでの再実行導線 |
| `PROVIDER_UNAVAILABLE` | provider側障害 | 再試行導線 |
| `GROUNDING_UNSUPPORTED` | grounding要求がproviderで実行できない | quality class低下の明示的な告知または実行中止 (受入時に固定) |
| `UNEXPECTED_GROUNDING` | grounding provenanceの不変条件違反 (例: grounding未有効化のresponseで `groundingUsed` が主張される) | 失敗告知。同意なしの検索実行を成功として扱わない |
| `MALFORMED_OUTPUT` | responseがstructured outputとして解析不能 | 失敗告知。raw responseは保存しない |
| `SCHEMA_MISMATCH` | responseがstructured outputとして解析不能、または #204 validator/codecがrejectした場合の代表class。intent側validation分類は #204契約が所有する (`IntentValidationFailure`: `SCHEMA_MISMATCH` / `EXPORT_MISMATCH` / `SESSION_EXPIRED` / `CONTEXT_STALE` / `OVERSIZE` / `UNKNOWN_REF` / `DUPLICATE_REF` / `INCOMPLETE_COVERAGE` / `INVALID_ENUM` / `FORBIDDEN_CONTENT` / `MOBILITY_CONTRADICTION` / `CAPABILITY_UNSUPPORTED` (V1ではreserved)。本specは新設しない) | 検証失敗として告知 |
| `TIMEOUT` / `CANCELLED` | bounded request policyのtimeout超過、user cancel | 中止告知。部分結果を採らない |
| `RESPONSE_TOO_LARGE` | responseがsize上限を超過 | 失敗告知 |

- AI失敗を理由にlayoutを変更しない。
- 自動で別providerへ送信しない。userに知らせずpure one-shotへdowngradeしない (silent semantic downgrade禁止)。
- 必要ならdeterministic strategyへ戻る選択肢をUIで提示するが、意味の異なるAI resultとしてのsilent fallbackはしない。

## Privacy / security

### External transmission

- 内部UIで完結していてもprovider APIへの送信はexternal processingである。明示的opt-in (default off) を必須とする (NFR-008)。
- 送信前に、送信内容 (tier相当のfield集合) と「provider側で処理される」旨のprivacy disclosureを表示する。providerごとのデータ取扱い差をNunuLauncherは保証しないことを明記する。
- groundingを有効にする場合は、送信確認に「providerが送信内容から検索queryを生成し、外部検索処理を行い得る」ことの明示を含める (「Grounding search query privacy boundary」節)。
- 送信内容は #204契約のexport表現 (`ContextExportBuilder` + `PrivacyTier`) に最小化され、package/profile/raw usage等は #204のprivacy tierが許可する範囲を超えない。#204受入specのtier表は内部engine (#206) の想定consumerを `LOCAL_FULL` としている。いずれのtierを用いる場合も、送信確認UIは適用tierの内容 (含まれる/除外される情報のclass) をuserが送信前に確認できる形で表示する。
- local deterministic Organizerはnetwork/AIなしで利用可能なまま維持する (NFR-005、D-011 offline behavior)。

### BYOK / credential management

受入時に以下を確定する (Open decisions参照)。ただし以下は契約レベルで固定する。

- **credential delivery model を provider-neutral な標準と仮定しない。** 「端末内credential storeに長期API keyを保存し、appからprovider APIを直接呼ぶ」direct BYOK構成を、全providerに共通の標準production pathとしては前提にしない。credential delivery model (direct BYOK、short-lived credential、OAuth、backend relay等) はprovider compatibilityとthreat boundaryの一部であり、provider選定基準 (Open decision 1) に含める。
- 選定providerがdirect mobile BYOKをproduction-supportedとしていない場合、direct BYOKをそのproviderの標準production pathとして扱わない。provider公式のclient-side key guidanceとの整合を確認し、整合しない構成はproduction pathとせず、代替 (非対応なら該provider不採用) をOpen decision 1の評価に含める。
- **credential lifecycle契約はdelivery model毎に分岐する。** direct BYOK: key入力 / 保存 (masked表示) / 再入力 / 削除。OAuth / short-lived credential: token・sessionの取得、更新、失効。backend relay: relay認証とアカウント接続のlifecycle。delivery modelはOpen decision 1の選定結果であり、direct BYOK以外が選定された場合、端末内BYOK key store自体が不要になり得る (その場合にkey storeを実装前提にしない)。
- 選定されたdelivery modelのsecret (key / token等) のlogcat / diagnostics / crash report / bugreportへの出力を禁止する。この禁止はdelivery modelに依らない。
- direct BYOK選定時、key表示はmaskingを既定とし、平文再表示を既定にしない。key削除をいつでも可能にし、credential不在 (`NO_CREDENTIAL`) のmanaged AI起動を禁止する。
- 端末内BYOK keyを「絶対に秘匿できる」とは扱わない。root / debug / device compromiseを含むresidual riskをuser向け文書とthreat modelに文書化する。
- provider endpoint / custom endpointを許す場合のthreat boundary (MITM、なりすましendpoint、key exfiltration経路) をthreat modelで評価する。custom endpoint許可自体がOpen decisionである。

### Prompt injection / untrusted content

- app label、folder title、provider response、web検索結果本文はすべてdataでありinstructionではない (#204の「Prompt injection / untrusted labels」脅威モデルを継承)。
- grounding結果を含む応答も #204 validator (`IntentCodec.decode` + `IntentValidator.validate`) のallow-list (export `ref` 集合) /closed enum検証を通らなければ採用されない。web contentがplanner commandとして実行される経路は存在しない。

## Reproducibility / provenance

- AI call自体の再現性は保証しない。
- 生成されたintentは #204契約のcontent-addressed immutable inputとして固定し (実装: `IntentIdentity`、content digest + schemaVersion)、preview/confirmation中に同じrequestを暗黙再実行しない。再実行は明示的なuser操作による新規試行であり、新しいintent identityとなる (#204のregeneration semanticsに従う)。planner側のprovenance参加は #204受入により確定済みである (`PolicySourceKind.PERSONALIZED_INTENT`、第7 policy input、no-intent時はsentinel identity)。
- provider/model/capability identityをprovenanceへどこまで含めるかは受入時に確定する (Open decision 5。対象はdiagnostics/UI側のprivacy-safe metadataであり、planner側のpolicy input identityは #204で確定済み)。quality classの記録はgrounding provenance (`groundingAvailable` / `groundingEnabled` / `groundingUsed`) を含み、`groundingUsed` の確認なしに `GROUNDED` を記録しない。ただしsecret/API keyやraw prompt/responseをdefault diagnosticsへ保存しない。diagnosticsに記録してよいのはprivacy-safe metadata (quality class・grounding provenance、provider family、typed failure category、request成否、latency bucket等。具体集合は受入時にorganizer-diagnostics.mdへ反映) に限る。

## UI / accessibility

- opt-in、送信内容確認、credential管理、失敗表示、quality class表示をTalkBack / font scaling / keyboard・switch accessで利用可能にする (NFR-009系)。
- 失敗UIはtyped failure categoryに対応した説明と、deterministic継続の明示的選択肢を持つ。
- raw chain-of-thoughtを扱わない。必要ならquality class (grounding provenance含む) / provider / model family等のprivacy-safe metadataのみ表示する。`GROUNDING_ENABLED_UNVERIFIED` を `GROUNDED` と表示しない。

## Stale state / concurrency

- bounded request実行中にlayoutが変化した場合、戻ってきたintentは受けたexportに対する #204の検証 (`IntentValidator.validate` の structural digest再計算照合) を通るため、構造的状態 (layout・lock・availability・分類・placement) が変化していれば `CONTEXT_STALE` でrejectされる。#203 signal snapshotの変化はstale判定に入らない (#204受入契約)。session失効時は `SESSION_EXPIRED`、古い/別export宛は `EXPORT_MISMATCH` (V1はsingle-active-session、TTL 24時間)。stale検出の詳細は #204受入契約に従う。
- 同一run内で複数のAI requestを並行発行しない。実行中のrequestはuserがcancelでき (`TIMEOUT`/`CANCELLED`)、cancel後の結果到着は破棄する。
- process death / restartがrequest途中で発生した場合、in-flight requestは失われる。再実行はuserの明示操作であり、layoutは一切変更されていないため復旧不要である。export/sessionの耐久性・失効semanticsは #204受入契約に従う (durable・期限付き `ExportSessionStore`、app-private・backup対象外。本specでは独自の永続化を追加しない)。

## Unsupported cases

- providerが `STRUCTURED_OUTPUT` 相当を満たせない場合はadapterとして登録しない。
- work profile / private profileの分離は既存planner制約が所有する。intentはprofileを跨ぐ指示を出せない (#204契約)。
- grounding結果で未知appの断定ができない場合、unresolvedとして返す。推測による断定をしない。
- intentの対象範囲は既存run modeに限定される (現行 `RunMode` は `FullOrganization` / `IncrementalPlacement` / #228由来の `ScopeComposedOrganization` の3値)。intentが新規run modeを導入しないこと、target追加 (`TargetSet.additions`) を生まないことは #204受入契約で確定済みである (spec 204「Planner接続」: additionsは #228 のuser明示選択のみ)。export対象は `APP_OR_SHORTCUT` / `FOLDER` / `WIDGET` のみで、`APP_PAIR` / `SHORTCUT_LEGACY` / Unknownはconstraint-only投影 (#204受入契約)。

## Dependencies

| 依存先 | 関係 | 状態 (2026-09-16時点) |
|---|---|---|
| #204 Context / PersonalizedIntent contract | 唯一のAI output contract。adapterの入出力・validationはこれに従う | **accepted (2026-09-15) + 実装済み** ([accepted spec 204](../../specs/204-ai-personalization-context-intent-contract/spec.md)、PR #322 merge済み、`origin/main` = `4f555450bd` 上に存在)。schema/validator/codec/session store/planner接続は `organizer/personalization/` + `organizer/integration/` に存在 |
| D-011 (external LLM gate) | privacy/threat modelとoffline behaviorの承認が必要 | 未承認。requirements.md D-011行 (2026-09-16更新) は、#205 external exchangeを「network/provider APIを含まないuser-mediated text交換 (clipboard/share/file) であり、privacy/threat modelはspec 205が定義しreviewで承認済み」としてgate外に整理した上で、**「in-app provider API接続 (#206) は引き続き本gate内」** と明記。実装開始のhard blocker (残存) |
| #182 (spec 182, implemented) | planner seam。intentは #204経由 (`OrganizationInput.intentPreferences`) でこのseamに入る | 実装済み。intent preference消費は全planner executorに接続済み (PR #322) |
| #194/#195/#13 (implemented/accepted) | preview / confirmation / apply / recoveryの再利用 | 実装済み |
| #203 usage signals | usage signalがあればcontext exportの `usageSignals` projectionへ含まれる (optional)。signal snapshotとsettings surfaceは実装済み | **実装済み** (PR #321 merge済み、spec 203 accepted)。projection定義は #204受入spec「usage signal projection (V1)」。不在でも本機能は成立する |
| #205 (CLOSED) | 兄弟issue。transport共通化はしない (exchange: clipboard/share/fileによるuser-mediated text交換でnetwork/provider APIなし / 本件: in-app provider API接続)。exchange framing (応答text内のmarker/抽出規則) の所有は #205 側 (#206のmanaged pathはprovider structured outputを利用しframingを持たない) | **accepted (2026-09-16) + 実装済み** ([spec 205](../../specs/205-external-agent-exchange/spec.md) status: implemented、PR #325 merge `3df9c7af`、docs PR #326、`origin/main` = `4f555450bd` 取り込み済み、Issue #205 closed)。実装は `organizer/personalization/exchange/` (pure) + `organizer/integration/exchange/` + `organizer/ui/exchange/`。organizer配下にnetwork実装は存在しないままである |

## Compatibility / migration

- 本機能はnetwork使用の新規追加であるため、AGENTS.mdによりspecとrisk評価を前提とする (本spec + 別途threat model)。app全体としてはbaseline Lawnchair由来の `INTERNET` permissionが既に存在するが、organizer系機能のexternal transmissionは引き続きdefault offである (NFR-008)。
- BYOK credential保存の新設 (保存機構はOpen decision)。backup/restore対象とするかは受入時に決定し、決定までの既定はbackup対象外とする。
- intent未使用・managed AI未opt-inのrunは、全て従来どおり (既存testが無修正で通ることが回帰基準)。
- DB schema変更なし。provider追加はadapter追加のみで行い、domain契約変更を伴わない。

## Acceptance criteria

- [ ] AC-1: #204 schema/validatorを唯一のAI output contractとして利用する (独自schema派生なし)。
- [ ] AC-2: NunuLauncherにgeneral-purpose agent loopを導入せず、bounded provider requestで完結する (実行単位が1 request/responseであることのcontract test)。
- [ ] AC-3: provider capability (`STRUCTURED_OUTPUT`, `WEB_GROUNDING` 等) がtypedに表現され、quality classがgrounding provenance (`groundingAvailable`/`groundingEnabled`/`groundingUsed`) から導出される。`groundingUsed` を確認できた実行のみ `GROUNDED` と表示・記録され、確認できない場合は `GROUNDING_ENABLED_UNVERIFIED` として扱われる。provenance不変条件違反 (例: `groundingUsed=true` かつ `groundingEnabled=false`) は成功とされず `UNEXPECTED_GROUNDING` のtyped zero-write failureに分類されること (invalid combinationのcontract testを含む)。
- [ ] AC-4: grounding対応時に未知app調査を利用でき、非対応時の保守的fallback (unresolved) が定義・実装される。
- [ ] AC-5: 選定されたcredential delivery modelのlifecycleが実装・検証される (direct BYOK選定時: key入力/保存/masked表示/再入力/削除。OAuth・short-lived credential選定時: token/session lifecycle。relay選定時: relay認証lifecycle)。secret (key/token等) のlog/diagnostics/crash reportへの流出禁止はdelivery model非依存で満たされる。
- [ ] AC-6: external transmissionの明示的opt-inとprivacy disclosure (送信内容確認。grounding有効時はprovider-side検索query生成・外部検索処理の明示を含む) がある。
- [ ] AC-7: Failure taxonomy全套がtyped zero-writeとして実装され、AI失敗時にlayout変更・silent cross-provider送信・silent semantic downgradeがない。
- [ ] AC-8: 生成intentがimmutable identityとして固定され、preview中に暗黙再生成・暗黙再requestがない。
- [ ] AC-9: #194/#195 preview + explicit confirmation + #13 recovery safetyを維持する (AI専用preview/apply pathを作らない)。
- [ ] AC-10: provider adapter contract tests (test doubleによる成功・全套失敗) + malformed response/security tests (prompt injection fixture含む) + representative device evidenceがある。
- [ ] AC-11: local deterministic Organizerがnetwork/AIなしで従来どおり動作する回帰証拠がある。
- [ ] AC-12: threat model文書 (BYOK residual risk、credential delivery model境界、provider生成検索queryのprivacy boundary、endpoint boundary、送信data分類) が承認済みである。

## Open decisions (未決定 — 受入前に解決が必要)

1. **初回provider選択**: OpenAI / Gemini / その他の比較と選定基準 (capability、structured output精度、grounding品質、cost、privacyに加え、**provider-supported auth / credential delivery model** (direct BYOK / short-lived credential / OAuth / backend relay)、**direct BYOKがそのproviderでproduction-supportedか**、short-lived credential等の代替が必須か、provider公式のclient-side key guidanceとの整合)。
2. **`ONE_SHOT` の正式サポート可否**: 正式quality classとするか、grounding非対応時はunknown保守的限定modeとするか。
3. **credential保存機構 (direct BYOK選定時)**: Android Keystoreをroot of trustとした上での現行推奨encrypted storage (Tink等を含む) の比較選定、backup/restore対象可否。direct BYOK以外のdelivery modelが選定された場合、本decisionは適用しない。Jetpack Security Crypto library (`androidx.security:security-crypto`、`EncryptedSharedPreferences`/`MasterKey`を提供) はdeprecatedであるため第一候補としない (出典: [Android developer cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)、確認日2026-09-13)。
4. **custom endpoint許可**: 許可する場合のthreat boundaryとvalidation。
5. **provenance詳細**: diagnostics/UI側のprivacy-safe provenance metadata (provider family、model family、capability/quality class、grounding provenance) をどこまで載せるか (#205と整合)。planner側のpolicy input identityは #204受入により確定済み (`PolicySourceKind.PERSONALIZED_INTENT` + intent content digest)。
6. **diagnostics field集合**: 許容privacy-safe metadata (quality class・grounding provenance含む) の確定とorganizer-diagnostics.mdへの反映。
7. **GROUNDING_UNSUPPORTED時の挙動と `GROUNDING_ENABLED_UNVERIFIED` の扱い**: 実行中止か、明示告知の上ONE_SHOT継続か。`GROUNDING_ENABLED_UNVERIFIED` をuser表示するかdiagnostics限定にするか。
8. **request policy数値**: timeout、request/response size上限、retry禁止の具体値。
9. **検索対象判定**: 「contextだけで高信頼に判断できないapp」の判定規則の具体化。
10. **grounding対象providerの要件**: 検索query生成を制御・監査可能なproviderのみに限定するか、grounding実行時により厳しいexport tier制限を課すか (送信確認でのprovider-side query生成の明示は契約として固定済み。「Grounding search query privacy boundary」節参照)。

## Change history

- 2026-09-10: Draft created for Issue #206. Managed Grounded AI path: capability model, provider adapter contract, failure taxonomy, BYOK/privacy constraints, quality class, immutable intent handling. #204 marked as unsettled hard dependency; D-011 gate recorded.
- 2026-09-13: Re-entry re-anchor to baseline `f9afd8bfde` (2026-09-13時点 `origin/main`)。前回baseline `6b6bf8dd` 以降のmain差分 (#228/#235/#265/#271/#288/#292、requirements FR-016 status更新、ADR-0007への#228追記、CONTEXT/DESIGN/organizer-diagnostics更新) を検証し、本specの契約は変更不要と判断。#204は未acceptのまま (branch側で同baselineへ再anchor)、D-011は未承認のまま。run mode現況 (#228 `ScopeComposedOrganization`) をUnsupported casesへ追記。
- 2026-09-13 (review対応): owner review "Request changes" への対応。Re-anchor to baseline `c5274b5d0d`。P1: credential delivery model (direct BYOK / short-lived / OAuth / backend relay) をprovider選定基準へ追加し、direct BYOKをprovider中立な標準production pathと仮定しない境界を契約化。P1: `groundingAvailable`/`groundingEnabled`/`groundingUsed` を分離し、`GROUNDED` を実利用確認時のみの表示・記録へ変更 (`GROUNDING_ENABLED_UNVERIFIED` 新設)。P1/P2: grounding検索queryのprivacy boundaryを節として新設し、provider-side query生成の送信確認での明示を契約化 (prompt policyによるquery内容の保証表現を削除)。P2: credential保存機構候補からdeprecatedなJetpack Security Crypto (`EncryptedSharedPreferences`/`MasterKey`) を外し、Keystore root of trust + Tink等の比較へ変更。#204 draft状態をreview対応revision `324e6182ae` (再review待ち) へ更新し、#204側で取下げられたprocess-local前提への依存を削除。Open decisions 1/3/6/7更新、10新設。AC-3/6/12更新。
- 2026-09-15 (re-review対応): owner re-review "Request changes" への対応。Re-anchor to baseline `397d3fd9` (merge `b6bd4e1c21`; 前回baseline `c5274b5d0d` 以降のmain差分 #298/#299/#315 はAI/transport/credential seamと無関係、planner seam・`PolicySourceKind`・`RunMode`・`NoTransportContractTest` は不変を `397d3fd9` 上で再確認)。P1: credential lifecycle契約をdelivery model毎に分岐 (direct BYOK: key lifecycle / OAuth・short-lived credential: token・session lifecycle / backend relay: relay認証lifecycle) し、AC-5を「選定delivery modelのlifecycle」形式へ変更。secret (key/token) 流出禁止をdelivery model非依存の契約へ分離し、`NO_CREDENTIAL` をdelivery model中立の条件へ変更。P1: grounding provenanceの許容不変条件 (`groundingUsed => groundingEnabled`、`groundingEnabled => groundingAvailable`) を契約化し、違反responseをtyped zero-write failure `UNEXPECTED_GROUNDING` へ分類 (invalid combinationのcontract testをAC-3へ追加)。boolean 3個 / closed state modelの内部表現を実装構成の選択肢として明記。P2 (plan側): plan残存のprocess-local前提を「耐久性・失効semanticsはaccepted #204契約に従い #206は独自の永続化契約を追加しない」へ修正。#204依存参照をre-review対応revision `12f773ad61` (baseline `397d3fd9` 再anchor済み、受入gate Q1/Q3/Q4未解決、再review待ち) へ更新、#205参照を `376dc35097` へ更新。Open decision 3をdirect BYOK選定時のdecisionとして明確化。実装開始blocker (#204未accept、D-011未承認) は維持。
- 2026-09-16 (re-entry再anchor): 前回baseline `397d3fd9` → 現在 `origin/main` `0cf82bc1e6` の差分を検査。主要変化は (1) **#204 受入 + 実装** (PR #322、2026-09-15 accepted、FR-017をrequirements.mdへ登録、ADR-0007 §9に #203 optional input追記、CONTEXT.md/DESIGN.mdへ用語・module行追加)、(2) **#203 実装** (PR #321、signal snapshot + settings surface)、(3) #298実装 (PR #319/#320、thread affinity。AI/transport/credential seamと無関係)。#204受入に伴い、本specの依存状態と暫定表現を確定契約への参照へ更新した (header、Dependencies表、Intended flow、Failure taxonomyの `SCHEMA_MISMATCH` 行、Privacy tier参照、provenance、Stale state、Unsupported cases、Open decision 5、References)。**契約核 (capability model、adapter contract、failure taxonomyの #206固有行、grounding provenance不変条件、credential delivery model境界、privacy boundary、Open decisions 1〜10 の枠組み) に変更はない。** #204の契約blockerは解消したが、D-011承認は実装開始のhard blockerとして残る。`PolicySourceKind` は #203/#204により8値へ拡張 (`PERSONALIZATION_SIGNAL_SNAPSHOT`、`PERSONALIZED_INTENT`)、`OrganizationInput` に `intentPreferences` field追加を確認済み。
- 2026-09-16 (第2回re-entry再anchor): 前回baseline `0cf82bc1e6` → 現在 `origin/main` `4f555450bd` の差分を検査。主要変化は **#205 (External Agent Exchange) の受入 + 実装** (spec 205 accepted 2026-09-16、実装PR #325 merge `3df9c7af`、docs PR #326でspec/planをimplementedへ更新、Issue #205 closed)。`organizer/personalization/exchange/` (pure) / `organizer/integration/exchange/` / `organizer/ui/exchange/` が追加され、`ContextExportBuilder` は #205の `SessionExportReconstructor` との内部共通化 (`toExportItemCore`) を得たが (外部seam不変)、organizer配下にnetwork/provider API実装は存在しないままである (`4f555450bd` 上で再確認)。requirements.mdはD-011行へ「#205 exchangeはuser-mediated text交換としてgate外、in-app provider API接続 (#206) は引き続きgate内」を明記し、FR-017 statusへspec 205を受入追記した。CONTEXT.mdへ #205用語追加、DESIGN.mdへgate 13追加。本revisionではDependencies表 (#205行を実装済みへ、D-011行を最新文言へ) とbaseline参照を更新した。**契約核に変更なし。** D-011承認が実装開始のhard blockerとして残存。`OrganizationPlanner.plan` signature、`RunMode` (3値)、`PolicySourceKind` (8値)、`PurityGuardTest` (`organizer/personalization` 配下をsubdirectory含め走査)、`NoTransportContractTest` は `4f555450bd` 上で再確認済み。

## References

- [Issue #206](https://github.com/nunu1733/NunuLauncher/issues/206)
- [Spec 204: AI personalization Context / Intent exchange contract](../204-ai-personalization-context-intent-contract/spec.md) (**accepted** 2026-09-15、実装済み — PR #322)
- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205) (兄弟issue、closed。transport共通化なし。exchange framing所有は #205)
- [Spec 205: external agent exchange](../205-external-agent-exchange/spec.md) (accepted 2026-09-16、implemented — PR #325)
- [Spec 203: usage implicit preference signals](../203-usage-implicit-preference-signals/spec.md) (implemented — PR #321)
- [Android developer cryptography guidance](https://developer.android.com/privacy-and-security/cryptography) (Jetpack Security Crypto deprecated。確認日2026-09-13)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 13: safe layout application](../13-safe-layout-application/spec.md)
- [requirements.md](../../docs/product/requirements.md) (FR-014, FR-017, NFR-005, NFR-008, NFR-011, D-011)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
