---
issue: "#206"
status: draft
requirements:
  - FR-014 # 境界定義のみ。本件はFR-014の拡大解釈ではない (#204 draft の提案FR-017参照)
  - NFR-008
  - NFR-005
  - NFR-011
  - D-011
risk:
  - privacy
  - network
  - layout-data
updated: 2026-09-13
---

# Managed Grounded AI: アプリ内完結型Organizer personalization

> Status: draft — 本specは **#204 (Context / PersonalizedIntent exchange contract) の受入を必須依存** とする。2026-09-13再確認時点で #204 の契約は未acceptであり、owner review "Request changes" への対応revision (2026-09-13、commit `324e6182ae`) がbaseline `c5274b5d0d` に再anchorされた上でbranch `origin/issue-204-spec-plan` に存在するのみで、**owner再review待ち** かつ `origin/main` 未取り込みである。よって本specは #204 のschema詳細 (field名、tier名、failure分類、`PersonalizedIntentV1` の具体形) を **確定事実として扱わない**。#204受入時に本specの依存参照と用語をaccepted契約へ合わせて改訂する。
>
> さらに D-011 (external LLM): 「privacy/threat modelとoffline behavior承認後まで導入しない」が requirements.md のdecision gateであり、本機能の実装開始は (1) #204受入、(2) privacy/threat model承認、(3) offline behavior (local deterministic Organizerがnetwork/AIなしで利用可能なまま) の確認を満たすまで禁止される (Issue本文も同じ)。

## Problem

Organizer personalizationをアプリ内で完結させる場合、NunuLauncher自身にagent framework / autonomous loop / browser automationを内包するのは複雑性・保守性・セキュリティの面で過剰である。一方、単純なone-shot LLM classificationは未知アプリや意味が曖昧なアプリについて不正確な推測を行い、外部ChatGPT/Gemini等のagentic workflowより品質が明確に劣る可能性がある。

現在の主要AI provider APIは、アプリ側からはboundedな1 requestとして呼びつつ、provider側のbuilt-in web grounding / search / tool capabilityで必要な調査を行い、structured resultを返す構成が可能である。

## Outcome

NunuLauncher内から #204契約のpersonalization contextをAI providerへ送信し、provider capabilityに応じた reasoning / web grounding を利用したpersonalized intentを取得できる **Managed Grounded AI** pathを提供する。NunuLauncherから見た実行単位はbounded request/responseであり、agent orchestration frameworkをアプリ内へ導入しない。取得したintentは #204 のstrict validationを通り、既存 #182 planner → #194/#195 preview → confirm → apply 経路のみで消費される。

## Scope

- Provider非依存のcapability model (`STRUCTURED_OUTPUT`, `WEB_GROUNDING`, `CITATIONS`, `REASONING` 等) のtyped表現と、要件capabilityの宣言方法。
- `ManagedAiProviderAdapter` contract: request serialization、structured-output/schema binding、optional grounding enablement、response extraction、typed failure mapping、token/request size limit handling。
- BYOK (user自有のprovider API key) credential lifecycle: 入力、保存、表示 (masking)、再入力、削除、backup/restore可否、log/diagnostics/crash reportへの流出禁止。
- External transmissionの明示的opt-in、送信内容確認、privacy disclosure。
- Typed failure outcomeの全套 (下記「Failure taxonomy」) と、AI失敗時のzero-write保証・silent fallback禁止。
- grounding provenance (`groundingAvailable`/`groundingEnabled`/`groundingUsed`) に基づくquality class (`ONE_SHOT` / `GROUNDED` / `GROUNDING_ENABLED_UNVERIFIED`) の表現と、grounding非対応時の保守的取り扱い。
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

`CONTEXT.md` 追加用語案 (受入時に反映。#204/#205の用語案と合わせて調整)。

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
1実行におけるgrounding利用の3状態。`groundingAvailable` (adapterが宣言したcapability)、`groundingEnabled` (request policyがgroundingを有効化した)、`groundingUsed` (provider responseのtool call記録・grounding metadata等により実際の利用を確認できた)。quality classはこれらから導出され、`groundingUsed` の確認なしに `GROUNDED` にはならない。
_Avoid_: availableとusedの混同

## Capability model

Provider名ではなくcapabilityを中心に設計する。

```text
AiProviderCapabilities
  STRUCTURED_OUTPUT   // 必須。#204 intent schemaへのbindingに必要
  WEB_GROUNDING       // optional。quality-enhancing
  CITATIONS           // optional
  REASONING           // provider-specific / optional
```

- `STRUCTURED_OUTPUT` 相当を最低要件とする案を本draftの既定とする (比較: 「WEB_GROUNDINGも必須にする」案はprovider選択肢を狭め、`ONE_SHOT`限定modeの意義を失わせるため不採用。受入時に再確認する)。
- capabilityはadapterがtypedに宣言する。実行時のquality classはcapability宣言から直接導出しない。`groundingAvailable` (capability宣言) と `groundingEnabled` (request policy) と `groundingUsed` (provider response内のtool call記録・grounding metadata等による実利用確認) を区別し、**`groundingUsed` をprovider responseで確認できた場合のみ `GROUNDED` と表示・記録する**。actual useを確認できないproviderでは `GROUNDING_ENABLED_UNVERIFIED` として扱い、`GROUNDED` としては表示・記録しない (表示形の詳細はOpen decision 6/7)。
- `WEB_GROUNDING` 非対応providerでは、未知アプリを推測で断定せず #204契約のunresolved/unknown表現で返せるschema/prompt policyを持つ (「Failure taxonomy」の `GROUNDING_UNSUPPORTED` は「grounding要求が実行できない」ことの検出用)。

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
#204 PersonalizationContextExportV1 (tier含む。schema詳細は#204受入時に確定)
        ↓
ManagedAiProviderAdapter (bounded request policy: timeout, size上限, capability宣言)
        ↓
provider request
   - structured output schema (#204 intent schema)
   - optional web grounding/search
        ↓
PersonalizedIntentV1 (#204)
        ↓
#204 strict validation (fail-closed, zero-write)
        ↓
#182 planner / allocator (既存唯一のplanning seam)
        ↓
#194/#195 preview → confirm → #13 apply/recovery
```

## Failure taxonomy

AI実行の全失敗をtyped outcomeとして定義する。いずれも **zero-write** (layout・selection・planning入力を一切変更しない) であり、diagnosticsへはcategoryのみを記録する。

| Failure | 条件 | Userへの表示 |
|---|---|---|
| `NO_CREDENTIAL` | BYOK keyが未設定 | key設定への導線と「local deterministicで続行」の選択肢 |
| `AUTH_REJECTED` | providerがcredentialを拒否 | 再入力導線。自動retryしない |
| `NETWORK_UNAVAILABLE` | 端末がnetworkに到達できない | offline説明とdeterministic継続選択肢 |
| `RATE_LIMITED` | provider rate limit / quota | 待機または別タイミングでの再実行導線 |
| `PROVIDER_UNAVAILABLE` | provider側障害 | 再試行導線 |
| `GROUNDING_UNSUPPORTED` | grounding要求がproviderで実行できない | quality class低下の明示的な告知または実行中止 (受入時に固定) |
| `MALFORMED_OUTPUT` | responseがstructured outputとして解析不能 | 失敗告知。raw responseは保存しない |
| `SCHEMA_MISMATCH` | #204 validatorがreject。intent側validationの分類 (coverage不変条件違反、context競合等) は #204契約が所有し、本specは新設しない | 検証失敗として告知 |
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
- 送信内容は #204契約のexport表現に最小化され、package/profile/raw usage等は #204のprivacy tierが許可する範囲を超えない。
- local deterministic Organizerはnetwork/AIなしで利用可能なまま維持する (NFR-005、D-011 offline behavior)。

### BYOK / credential management

受入時に以下を確定する (Open decisions参照)。ただし以下は契約レベルで固定する。

- **credential delivery model を provider-neutral な標準と仮定しない。** 「端末内credential storeに長期API keyを保存し、appからprovider APIを直接呼ぶ」direct BYOK構成を、全providerに共通の標準production pathとしては前提にしない。credential delivery model (direct BYOK、short-lived credential、OAuth、backend relay等) はprovider compatibilityとthreat boundaryの一部であり、provider選定基準 (Open decision 1) に含める。
- 選定providerがdirect mobile BYOKをproduction-supportedとしていない場合、direct BYOKをそのproviderの標準production pathとして扱わない。provider公式のclient-side key guidanceとの整合を確認し、整合しない構成はproduction pathとせず、代替 (非対応なら該provider不採用) をOpen decision 1の評価に含める。
- keyのlogcat / diagnostics / crash report / bugreportへの出力を禁止する。
- key表示はmaskingを既定とし、平文再表示を既定にしない。
- key削除をいつでも可能にし、削除後にmanaged AIを起動できない (`NO_CREDENTIAL`)。
- 端末内BYOK keyを「絶対に秘匿できる」とは扱わない。root / debug / device compromiseを含むresidual riskをuser向け文書とthreat modelに文書化する。
- provider endpoint / custom endpointを許す場合のthreat boundary (MITM、なりすましendpoint、key exfiltration経路) をthreat modelで評価する。custom endpoint許可自体がOpen decisionである。

### Prompt injection / untrusted content

- app label、folder title、provider response、web検索結果本文はすべてdataでありinstructionではない (#204の脅威モデルを継承)。
- grounding結果を含む応答も #204 validatorのallow-list/closed enum検証を通らなければ採用されない。web contentがplanner commandとして実行される経路は存在しない。

## Reproducibility / provenance

- AI call自体の再現性は保証しない。
- 生成されたintentは #204契約のcontent-addressed immutable inputとして固定し、preview/confirmation中に同じrequestを暗黙再実行しない。再実行は明示的なuser操作による新規試行であり、新しいintent identityとなる (#204のregeneration semanticsに従う)。
- provider/model/capability identityをprovenanceへどこまで含めるかは受入時に確定する。quality classの記録はgrounding provenance (`groundingAvailable` / `groundingEnabled` / `groundingUsed`) を含み、`groundingUsed` の確認なしに `GROUNDED` を記録しない。ただしsecret/API keyやraw prompt/responseをdefault diagnosticsへ保存しない。diagnosticsに記録してよいのはprivacy-safe metadata (quality class・grounding provenance、provider family、typed failure category、request成否、latency bucket等。具体集合は受入時にorganizer-diagnostics.mdへ反映) に限る。

## UI / accessibility

- opt-in、送信内容確認、credential管理、失敗表示、quality class表示をTalkBack / font scaling / keyboard・switch accessで利用可能にする (NFR-009系)。
- 失敗UIはtyped failure categoryに対応した説明と、deterministic継続の明示的選択肢を持つ。
- raw chain-of-thoughtを扱わない。必要ならquality class (grounding provenance含む) / provider / model family等のprivacy-safe metadataのみ表示する。`GROUNDING_ENABLED_UNVERIFIED` を `GROUNDED` と表示しない。

## Stale state / concurrency

- bounded request実行中にlayoutが変化した場合、戻ってきたintentは受け付けた #204 exportに対する検証 (#204のexport一致検証) を通るため、古いexport宛のintentはrejectされる。stale検出の詳細は #204契約に従う。
- 同一run内で複数のAI requestを並行発行しない。実行中のrequestはuserがcancelでき (`TIMEOUT`/`CANCELLED`)、cancel後の結果到着は破棄する。
- process death / restartがrequest途中で発生した場合、in-flight requestは失われる。再実行はuserの明示操作であり、layoutは一切変更されていないため復旧不要である。export/sessionの耐久性・失効semanticsは #204契約の受入形に従う (本specでは独自の永続化を前提にしない)。

## Unsupported cases

- providerが `STRUCTURED_OUTPUT` 相当を満たせない場合はadapterとして登録しない。
- work profile / private profileの分離は既存planner制約が所有する。intentはprofileを跨ぐ指示を出せない (#204契約)。
- grounding結果で未知appの断定ができない場合、unresolvedとして返す。推測による断定をしない。
- intentの対象範囲は既存run modeに限定される (main側の現行 `RunMode` は `FullOrganization` / `IncrementalPlacement` / #228由来の `ScopeComposedOrganization`)。intentが新規run modeを導入しないこと、target追加 (missing-app候補) を生まないことは #204契約側の制約として扱い、受入時に合致を確認する。

## Dependencies

| 依存先 | 関係 | 状態 (2026-09-13時点) |
|---|---|---|
| #204 Context / PersonalizedIntent contract | 唯一のAI output contract。adapterの入出力・validationはこれに従う | **未accept** (owner review "Request changes" への対応revision `324e6182ae` がbranch `origin/issue-204-spec-plan` のみ。baseline `c5274b5d0d` 再anchor済み、**owner再review待ち**、`origin/main` 未取り込み)。実装開始のhard blocker |
| D-011 (external LLM gate) | privacy/threat modelとoffline behaviorの承認が必要 | 未承認 (requirements.md変更なし)。実装開始のhard blocker |
| #182 (spec 182, implemented) | planner seam。intentは #204経由でこのseamに入る | 実装済み |
| #194/#195/#13 (implemented/accepted) | preview / confirmation / apply / recoveryの再利用 | 実装済み |
| #203 usage signals (OPEN) | usage signalがあればcontextへ含まれる (optional) | 未実装 (spec draftはbranchのみ)。不在でも本機能は成立する |
| #205 (OPEN) | 兄弟issue。transport共通化はしない。exchange framing (応答text内のmarker/抽出規則) の所有は #205 側に確定 (#206のmanaged pathはprovider structured outputを利用しframingを持たない) | draft review対応revision `02f7f905cd` がbranch `origin/issue-205-spec-plan` のみ。`origin/main` 未取り込み |

## Compatibility / migration

- 本機能はnetwork使用の新規追加であるため、AGENTS.mdによりspecとrisk評価を前提とする (本spec + 別途threat model)。app全体としてはbaseline Lawnchair由来の `INTERNET` permissionが既に存在するが、organizer系機能のexternal transmissionは引き続きdefault offである (NFR-008)。
- BYOK credential保存の新設 (保存機構はOpen decision)。backup/restore対象とするかは受入時に決定し、決定までの既定はbackup対象外とする。
- intent未使用・managed AI未opt-inのrunは、全て従来どおり (既存testが無修正で通ることが回帰基準)。
- DB schema変更なし。provider追加はadapter追加のみで行い、domain契約変更を伴わない。

## Acceptance criteria

- [ ] AC-1: #204 schema/validatorを唯一のAI output contractとして利用する (独自schema派生なし)。
- [ ] AC-2: NunuLauncherにgeneral-purpose agent loopを導入せず、bounded provider requestで完結する (実行単位が1 request/responseであることのcontract test)。
- [ ] AC-3: provider capability (`STRUCTURED_OUTPUT`, `WEB_GROUNDING` 等) がtypedに表現され、quality classがgrounding provenance (`groundingAvailable`/`groundingEnabled`/`groundingUsed`) から導出される。`groundingUsed` を確認できた実行のみ `GROUNDED` と表示・記録され、確認できない場合は `GROUNDING_ENABLED_UNVERIFIED` として扱われる。
- [ ] AC-4: grounding対応時に未知app調査を利用でき、非対応時の保守的fallback (unresolved) が定義・実装される。
- [ ] AC-5: BYOK credential lifecycle (入力/保存/masked表示/再入力/削除) とlog/diagnostics/crash reportへの流出禁止が実装・検証される。
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
3. **credential保存機構**: Android Keystoreをroot of trustとした上での現行推奨encrypted storage (Tink等を含む) の比較選定、backup/restore対象可否。Jetpack Security Crypto library (`androidx.security:security-crypto`、`EncryptedSharedPreferences`/`MasterKey`を提供) はdeprecatedであるため第一候補としない (出典: [Android developer cryptography guidance](https://developer.android.com/privacy-and-security/cryptography)、確認日2026-09-13)。
4. **custom endpoint許可**: 許可する場合のthreat boundaryとvalidation。
5. **provenance詳細**: provider/model/capability identityをどこまで `InputProvenance` 系へ載せるか (#204受入後、#205と整合)。
6. **diagnostics field集合**: 許容privacy-safe metadata (quality class・grounding provenance含む) の確定とorganizer-diagnostics.mdへの反映。
7. **GROUNDING_UNSUPPORTED時の挙動と `GROUNDING_ENABLED_UNVERIFIED` の扱い**: 実行中止か、明示告知の上ONE_SHOT継続か。`GROUNDING_ENABLED_UNVERIFIED` をuser表示するかdiagnostics限定にするか。
8. **request policy数値**: timeout、request/response size上限、retry禁止の具体値。
9. **検索対象判定**: 「contextだけで高信頼に判断できないapp」の判定規則の具体化。
10. **grounding対象providerの要件**: 検索query生成を制御・監査可能なproviderのみに限定するか、grounding実行時により厳しいexport tier制限を課すか (送信確認でのprovider-side query生成の明示は契約として固定済み。「Grounding search query privacy boundary」節参照)。

## Change history

- 2026-09-10: Draft created for Issue #206. Managed Grounded AI path: capability model, provider adapter contract, failure taxonomy, BYOK/privacy constraints, quality class, immutable intent handling. #204 marked as unsettled hard dependency; D-011 gate recorded.
- 2026-09-13: Re-entry re-anchor to baseline `f9afd8bfde` (2026-09-13時点 `origin/main`)。前回baseline `6b6bf8dd` 以降のmain差分 (#228/#235/#265/#271/#288/#292、requirements FR-016 status更新、ADR-0007への#228追記、CONTEXT/DESIGN/organizer-diagnostics更新) を検証し、本specの契約は変更不要と判断。#204は未acceptのまま (branch側で同baselineへ再anchor)、D-011は未承認のまま。run mode現況 (#228 `ScopeComposedOrganization`) をUnsupported casesへ追記。
- 2026-09-13 (review対応): owner review "Request changes" への対応。Re-anchor to baseline `c5274b5d0d`。P1: credential delivery model (direct BYOK / short-lived / OAuth / backend relay) をprovider選定基準へ追加し、direct BYOKをprovider中立な標準production pathと仮定しない境界を契約化。P1: `groundingAvailable`/`groundingEnabled`/`groundingUsed` を分離し、`GROUNDED` を実利用確認時のみの表示・記録へ変更 (`GROUNDING_ENABLED_UNVERIFIED` 新設)。P1/P2: grounding検索queryのprivacy boundaryを節として新設し、provider-side query生成の送信確認での明示を契約化 (prompt policyによるquery内容の保証表現を削除)。P2: credential保存機構候補からdeprecatedなJetpack Security Crypto (`EncryptedSharedPreferences`/`MasterKey`) を外し、Keystore root of trust + Tink等の比較へ変更。#204 draft状態をreview対応revision `324e6182ae` (再review待ち) へ更新し、#204側で取下げられたprocess-local前提への依存を削除。Open decisions 1/3/6/7更新、10新設。AC-3/6/12更新。

## References

- [Issue #206](https://github.com/nunu1733/NunuLauncher/issues/206)
- [Issue #204](https://github.com/nunu1733/NunuLauncher/issues/204) (未accept。review対応revision は branch `origin/issue-204-spec-plan` commit `324e6182ae` の `specs/204-ai-personalization-context-intent-contract/`。owner再review待ち)
- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205) (兄弟issue。transport共通化なし。exchange framing所有は #205)
- [Android developer cryptography guidance](https://developer.android.com/privacy-and-security/cryptography) (Jetpack Security Crypto deprecated。確認日2026-09-13)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 13: safe layout application](../13-safe-layout-application/spec.md)
- [requirements.md](../../docs/product/requirements.md) (FR-014, NFR-005, NFR-008, NFR-011, D-011)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
