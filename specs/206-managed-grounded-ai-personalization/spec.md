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

> Status: draft — 本specは **#204 (Context / PersonalizedIntent exchange contract) の受入を必須依存** とする。2026-09-13再確認時点で #204 の契約は未acceptであり、draft snapshot (2026-09-13にbaseline `f9afd8bfde` へ再anchor) がbranch `origin/issue-204-spec-plan` に存在するのみで `origin/main` に取り込まれていない。よって本specは #204 のschema詳細 (field名、tier名、failure分類、`PersonalizedIntentV1` の具体形) を **確定事実として扱わない**。#204受入時に本specの依存参照と用語をaccepted契約へ合わせて改訂する。
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
- `ONE_SHOT` / `GROUNDED` quality classの表現と、grounding非対応時の保守的取り扱い。
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
managed AI実行の実効能力区分。`ONE_SHOT` (provided context + model知識のみ) と `GROUNDED` (provider-side web search/grounding利用可)。capability差を隠蔽しない。

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
- capabilityはadapterがtypedに宣言し、実行時にeffective capability class (`ONE_SHOT` / `GROUNDED`) としてuser表示・diagnosticsへ出力する。
- `WEB_GROUNDING` 非対応providerでは、未知アプリを推測で断定せず #204契約のunresolved/unknown表現で返せるschema/prompt policyを持つ (「Failure taxonomy」の `GROUNDING_UNSUPPORTED` は「grounding要求が実行できない」ことの検出用)。

### Grounding behavior policy

`WEB_GROUNDING` 利用時の既定policy (受入時に確定)。

- contextだけで高信頼に判断できないappのみ検索対象とする方向でprompt policyを設計する。
- app用途を確認するための検索は許容する。
- returned web contentはuntrusted evidenceであり、planner commandとして実行しない。final resultは常に #204 のpersonalized intentである。
- 検索内容・search queryに、export tierが許可しない情報 (package名、profile、raw usage等 #204のprivacy tier参照) を含めない。

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
| `SCHEMA_MISMATCH` | #204 validatorがreject (詳細分類は#204契約に従う) | 検証失敗として告知 |
| `TIMEOUT` / `CANCELLED` | bounded request policyのtimeout超過、user cancel | 中止告知。部分結果を採らない |
| `RESPONSE_TOO_LARGE` | responseがsize上限を超過 | 失敗告知 |

- AI失敗を理由にlayoutを変更しない。
- 自動で別providerへ送信しない。userに知らせずpure one-shotへdowngradeしない (silent semantic downgrade禁止)。
- 必要ならdeterministic strategyへ戻る選択肢をUIで提示するが、意味の異なるAI resultとしてのsilent fallbackはしない。

## Privacy / security

### External transmission

- 内部UIで完結していてもprovider APIへの送信はexternal processingである。明示的opt-in (default off) を必須とする (NFR-008)。
- 送信前に、送信内容 (tier相当のfield集合) と「provider側で処理される」旨のprivacy disclosureを表示する。providerごとのデータ取扱い差をNunuLauncherは保証しないことを明記する。
- 送信内容は #204契約のexport表現に最小化され、package/profile/raw usage等は #204のprivacy tierが許可する範囲を超えない。
- local deterministic Organizerはnetwork/AIなしで利用可能なまま維持する (NFR-005、D-011 offline behavior)。

### BYOK / credential management

受入時に以下を確定する (Open decisions参照)。ただし以下は契約レベルで固定する。

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
- provider/model/capability identityをprovenanceへどこまで含めるかは受入時に確定する。ただしsecret/API keyやraw prompt/responseをdefault diagnosticsへ保存しない。diagnosticsに記録してよいのはprivacy-safe metadata (effective capability class、provider family、typed failure category、request成否、latency bucket等。具体集合は受入時にorganizer-diagnostics.mdへ反映) に限る。

## UI / accessibility

- opt-in、送信内容確認、credential管理、失敗表示、quality class表示をTalkBack / font scaling / keyboard・switch accessで利用可能にする (NFR-009系)。
- 失敗UIはtyped failure categoryに対応した説明と、deterministic継続の明示的選択肢を持つ。
- raw chain-of-thoughtを扱わない。必要ならeffective capability class / provider / model family等のprivacy-safe metadataのみ表示する。

## Stale state / concurrency

- bounded request実行中にlayoutが変化した場合、戻ってきたintentは受け付けた #204 exportに対する検証 (#204のexport一致検証) を通るため、古いexport宛のintentはrejectされる。stale検出の詳細は #204契約に従う。
- 同一run内で複数のAI requestを並行発行しない。実行中のrequestはuserがcancelでき (`TIMEOUT`/`CANCELLED`)、cancel後の結果到着は破棄する。
- process death / restart後も、未confirmのintentは #204契約のprocess-local扱いに従い (draft時点の想定)、復旧を約束しない。layoutは一切変更されていないため復旧不要である。

## Unsupported cases

- providerが `STRUCTURED_OUTPUT` 相当を満たせない場合はadapterとして登録しない。
- work profile / private profileの分離は既存planner制約が所有する。intentはprofileを跨ぐ指示を出せない (#204契約)。
- grounding結果で未知appの断定ができない場合、unresolvedとして返す。推測による断定をしない。
- intentの対象範囲は既存run modeに限定される (main側の現行 `RunMode` は `FullOrganization` / `IncrementalPlacement` / #228由来の `ScopeComposedOrganization`)。intentが新規run modeを導入しないこと、target追加 (missing-app候補) を生まないことは #204契約側の制約として扱い、受入時に合致を確認する。

## Dependencies

| 依存先 | 関係 | 状態 (2026-09-13時点) |
|---|---|---|
| #204 Context / PersonalizedIntent contract | 唯一のAI output contract。adapterの入出力・validationはこれに従う | **未accept** (draft snapshotがbranch `origin/issue-204-spec-plan` のみ。2026-09-13にbaseline `f9afd8bfde` へ再anchor済み)。実装開始のhard blocker |
| D-011 (external LLM gate) | privacy/threat modelとoffline behaviorの承認が必要 | 未承認 (requirements.md変更なし)。実装開始のhard blocker |
| #182 (spec 182, implemented) | planner seam。intentは #204経由でこのseamに入る | 実装済み |
| #194/#195/#13 (implemented/accepted) | preview / confirmation / apply / recoveryの再利用 | 実装済み |
| #203 usage signals (OPEN) | usage signalがあればcontextへ含まれる (optional) | 未実装 (spec draftはbranchのみ)。不在でも本機能は成立する |
| #205 (OPEN) | 兄弟issue。transport共通化はしない | draft specがbranchのみ |

## Compatibility / migration

- 本機能はnetwork使用の新規追加であるため、AGENTS.mdによりspecとrisk評価を前提とする (本spec + 別途threat model)。app全体としてはbaseline Lawnchair由来の `INTERNET` permissionが既に存在するが、organizer系機能のexternal transmissionは引き続きdefault offである (NFR-008)。
- BYOK credential保存の新設 (保存機構はOpen decision)。backup/restore対象とするかは受入時に決定し、決定までの既定はbackup対象外とする。
- intent未使用・managed AI未opt-inのrunは、全て従来どおり (既存testが無修正で通ることが回帰基準)。
- DB schema変更なし。provider追加はadapter追加のみで行い、domain契約変更を伴わない。

## Acceptance criteria

- [ ] AC-1: #204 schema/validatorを唯一のAI output contractとして利用する (独自schema派生なし)。
- [ ] AC-2: NunuLauncherにgeneral-purpose agent loopを導入せず、bounded provider requestで完結する (実行単位が1 request/responseであることのcontract test)。
- [ ] AC-3: provider capability (`STRUCTURED_OUTPUT`, `WEB_GROUNDING` 等) がtypedに表現され、effective quality class (`ONE_SHOT`/`GROUNDED`) がuser表示・diagnosticsへ出力される。
- [ ] AC-4: grounding対応時に未知app調査を利用でき、非対応時の保守的fallback (unresolved) が定義・実装される。
- [ ] AC-5: BYOK credential lifecycle (入力/保存/masked表示/再入力/削除) とlog/diagnostics/crash reportへの流出禁止が実装・検証される。
- [ ] AC-6: external transmissionの明示的opt-inとprivacy disclosure (送信内容確認) がある。
- [ ] AC-7: Failure taxonomy全套がtyped zero-writeとして実装され、AI失敗時にlayout変更・silent cross-provider送信・silent semantic downgradeがない。
- [ ] AC-8: 生成intentがimmutable identityとして固定され、preview中に暗黙再生成・暗黙再requestがない。
- [ ] AC-9: #194/#195 preview + explicit confirmation + #13 recovery safetyを維持する (AI専用preview/apply pathを作らない)。
- [ ] AC-10: provider adapter contract tests (test doubleによる成功・全套失敗) + malformed response/security tests (prompt injection fixture含む) + representative device evidenceがある。
- [ ] AC-11: local deterministic Organizerがnetwork/AIなしで従来どおり動作する回帰証拠がある。
- [ ] AC-12: threat model文書 (BYOK residual risk、endpoint boundary、送信data分類) が承認済みである。

## Open decisions (未決定 — 受入前に解決が必要)

1. **初回provider選択**: OpenAI / Gemini / その他の比較と選定基準 (capability、structured output精度、grounding品質、cost、privacy)。
2. **`ONE_SHOT` の正式サポート可否**: 正式quality classとするか、grounding非対応時はunknown保守的限定modeとするか。
3. **credential保存機構**: Android Keystore + EncryptedSharedPreferences等の選定、backup/restore対象可否。
4. **custom endpoint許可**: 許可する場合のthreat boundaryとvalidation。
5. **provenance詳細**: provider/model/capability identityをどこまで `InputProvenance` 系へ載せるか (#204受入後、#205と整合)。
6. **diagnostics field集合**: 許容privacy-safe metadataの確定とorganizer-diagnostics.mdへの反映。
7. **GROUNDING_UNSUPPORTED時の挙動**: 実行中止か、明示告知の上ONE_SHOT継続か。
8. **request policy数値**: timeout、request/response size上限、retry禁止の具体値。
9. **検索対象判定**: 「contextだけで高信頼に判断できないapp」の判定規則の具体化。

## Change history

- 2026-09-10: Draft created for Issue #206. Managed Grounded AI path: capability model, provider adapter contract, failure taxonomy, BYOK/privacy constraints, quality class, immutable intent handling. #204 marked as unsettled hard dependency; D-011 gate recorded.
- 2026-09-13: Re-entry re-anchor to baseline `f9afd8bfde` (2026-09-13時点 `origin/main`)。前回baseline `6b6bf8dd` 以降のmain差分 (#228/#235/#265/#271/#288/#292、requirements FR-016 status更新、ADR-0007への#228追記、CONTEXT/DESIGN/organizer-diagnostics更新) を検証し、本specの契約は変更不要と判断。#204は未acceptのまま (branch側で同baselineへ再anchor)、D-011は未承認のまま。run mode現況 (#228 `ScopeComposedOrganization`) をUnsupported casesへ追記。

## References

- [Issue #206](https://github.com/nunu1733/NunuLauncher/issues/206)
- [Issue #204](https://github.com/nunu1733/NunuLauncher/issues/204) (未accept。draft spec は branch `origin/issue-204-spec-plan` の `specs/204-ai-personalization-context-intent-contract/`)
- [Issue #205](https://github.com/nunu1733/NunuLauncher/issues/205) (兄弟issue。transport共通化なし)
- [Spec 182: layout strategy catalog](../182-layout-strategy-catalog/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [Spec 13: safe layout application](../13-safe-layout-application/spec.md)
- [requirements.md](../../docs/product/requirements.md) (FR-014, NFR-005, NFR-008, NFR-011, D-011)
- [organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md)
- [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
