# Implementation Plan: Managed Grounded AI personalization

> Issue: #206
> Spec: [spec.md](./spec.md)
> Status: draft — 実装開始条件 (#204受入、D-011 privacy/threat model承認) を満たす前に実装しない。

## Current evidence

確認済みの現行状態 (2026-09-15に再検証、`origin/main` @ `397d3fd95764878366e7c9e5ce41ab65e6f3f9ca`)。実装開始時に再検証する。前回確認 (2026-09-13, `c5274b5d0d`) からの主差分は本節末尾に記載する。

- **AI/network provider codeはorganizerに存在しない。** `lawnchair/src/app/lawnchair/organizer/` 配下にAI・provider・network関連実装、`organizer/personalization/` packageは存在しない (2026-09-15に `397d3fd9` 上で再確認)。AI adapter・credential delivery・opt-in設定はすべて新規実装である。
- **app全体のnetwork前提**: `lawnchair/AndroidManifest.xml` はbaseline Lawnchair由来の `android.permission.INTERNET` を既に保持する。`gradle/libs.versions.toml` にretrofit 3.0.0 / okhttp 5.3.2 (bundle `retrofit`) が存在し、`lawnchair/src/app/lawnchair/ui/preferences/about/GithubService.kt`、`ui/preferences/data/liveinfo/LiveInformationService.kt`、`bugreport/KatbinService.kt` 等のpreferences系serviceがokhttp系networkingの先例である。よって新規permissionや新規network library追加は必須ではない (provider SDK追加与否はOpen decision)。
- **organizerのno-transport規律**: `tests/unit/app/lawnchair/organizer/diagnostics/integration/NoTransportContractTest.kt` (spec 67 AC-67-12) はdiagnostics module配下のnetwork/worker importを禁止する (存在を `397d3fd9` 上で再確認)。organizer-diagnostics.mdは「organizer diagnosticsは外部transportを持たずdefault off」(NFR-008) が正本。AI diagnostics field追加時はこの正本とtestの更新が必要。
- **planner seam**: `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` が唯一の外部planning seam `plan(OrganizationInput): PlanningResult` (spec 182 AC-4)。signature不変を `397d3fd9` 上で再確認。AI intentは直接ここへ入らず、#204契約のadapterを経る (draft時点で #204 の `IntentPlannerAdapter` 相当は未実装・未accept)。
- **provenance**: `organizer/rules/PolicyModels.kt` の `PolicySourceKind` (6値、`397d3fd9` 上で再確認済み) と `organizer/integration/CompositionModels.kt` / `OrganizationInputComposer.kt` が `InputProvenance` / policy input identityを所有。#182により `LAYOUT_STRATEGY_SELECTION` が第5 policy input (`InputProvenance.layoutStrategySelection`、code comment "Spec 182: fifth policy input")。#204 draftは第6input `PERSONALIZED_INTENT` を提案しているが未確定。
- **preview/confirm/apply**: `organizer/application/preview/` (spec 194 `inspectPlan`)、`organizer/ui/` (spec 195 confirmation、`ManualOrganizationRun.kt`、`OrganizationOperationLease.kt` 等)、spec 13 apply/recovery。いずれも実装済みで、AI pathはこれを複製しない。
- **run mode現況**: `organizer/planning/OrganizationInput.kt` の `RunMode` は `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` (#228) の3値。
- **#204/#205/#203**: いずれもOPEN。#204/#205/#203のdraft spec/plan はbranch (`origin/issue-204-spec-plan`, `origin/issue-205-spec-plan`, `origin/issue-203-spec-plan`) のみでorigin/main未取り込み。#204はre-review "Request changes" への対応revision `12f773ad61` (baseline `397d3fd9` 再anchor済み) が **owner再review待ち** であり、受入必須gate (Q1 planner投影、Q3 capability set、Q4 content limits/session TTL) も未解決である (durable export session / `ExportSessionStore` interface (pure側) とAndroid実装の `organizer/integration/` 配置、session TTL、`sourceContextDigest` / `exportId`、export単位のfresh random ref、per-item mobility projectionと `MOBILITY_CONTRADICTION`、coverage不変条件、`CONTEXT_STALE` 等を提案。いずれも本planの確定前提にしない)。#205もre-review対応revision `376dc35097` (baseline `397d3fd9` 再anchor済み。exchange framing所有、process recreation後のrun再構築semantics、disclosure順序契約を #205 側が所有)。#203はre-review対応revision `4faf660f0b` (signal実装はorigin/mainに存在しない)。
- **2026-09-13 (`f9afd8bfde`) → 2026-09-13 (`c5274b5d0d`) のmain差分と本planへの影響**: #283 (strategy picker選択表示: `ManualOrganizationPreferences.kt` へのpicker display追加)、#287 (grid-change unknown lock recovery: `LockAuthoring.kt` のfolder-child rank緩和、locks系instrumentation test追加)、#300/#308 (api36 UI laneのwindow focus前提gate、Compose focus修正: `ManualOrganizationPreferences.kt` のfocus/readiness制御)、#304 (CI emulator失敗evidence保持: workflow/test tools)。いずれもAI/transport/credential seamと無関係で、planning seam・`PolicySourceKind`・`NoTransportContractTest` は不変。manual run state machineとpreview/confirm surfaceの構造も不変 (#283/#308はfocus・readiness・picker表示の変更であり、本planのUI統合点の前提を変えない)。`LockAuthoring.kt` の変更はAI intentの契約対象外 (lock既存制約はplanner側が所有) である。
- **2026-09-13 (`c5274b5d0d`) → 2026-09-15 (`397d3fd9`) のmain差分と本planへの影響**: #298 (Nova restore reload thread affinityのspec/plan)、#299 (restore reload completion barrierとcapture不変条件の実装: `CaptureInvariant.kt` 新設、`OrganizationInputComposer.kt`/`LayoutApplicationModule.kt`/`DiagnosticsLogger.kt`/`LauncherModel.java`/`LoaderTask.java` 変更。`CaptureFailureObserver` のsignature拡張のみで `InputProvenance`/`PolicySourceKind`/composition入力は不変)、#315 (bounded CI failure evidence capture: workflow/tools変更、diagnostics正本への `invariant=` field追加)。いずれもAI/transport/credential seamと無関係で、`OrganizationPlanner.plan` signature、`PolicySourceKind` (6値)、`RunMode` (3値)、`NoTransportContractTest` を `397d3fd9` 上で再確認済み。organizer-diagnostics.mdの変更はcapture側例外行の限定例外拡張のみであり、managed AI diagnosticsの「正本更新を同じPRで行う」方針は不変。
- 推測 (未確認): provider APIのstructured output / grounding optionの現行仕様詳細、各providerのauth / credential delivery model (direct BYOKのproduction support可否、short-lived credential / OAuth / backend relayの要否)、grounding metadata (実際のtool call / search実行をresponseから確認できるか) の返却形式。実装時にprovider選定とともに調査し、調査記録をIssueへ残す。

## Design

### Modules and interfaces

新規package `lawnchair/src/app/lawnchair/organizer/personalization/managedai/` (#204実装が `organizer/personalization/` を使うdraftであるため、その下位に置く。#204受入時に実際の配置へ合わせる)。

```text
organizer/personalization/managedai/
├── AiProviderCapabilities.kt   # typed capability宣言 (STRUCTURED_OUTPUT, WEB_GROUNDING, ...)
├── ManagedAiRequestPolicy.kt   # timeout / size上限 / grounding許可 (immutable value)
├── ManagedAiGroundingProvenance.kt # groundingAvailable / groundingEnabled / groundingUsed (不変条件検証を型に持たせる。closed state model表現も可)
├── ManagedAiOutcome.kt         # typed sealed outcome: Success(intent表現, grounding provenance) | <Failure taxonomy全套>
├── ManagedAiQualityClass.kt    # ONE_SHOT / GROUNDED / GROUNDING_ENABLED_UNVERIFIED の導出 (provenanceから)
├── ManagedAiProviderAdapter.kt # fun interface: request(context表現, policy) -> ManagedAiOutcome
├── providers/                  # 初回provider adapter 1つ (Open decision 1で選定。delivery model評価を含む)
├── credentials/                # credential / auth delivery (Open decision 1で選定されたmodel) のinterface + 実装。direct BYOK選定時はkey storeが一形態 (機構はOpen decision 3)
└── ManagedAiSession.kt         # 1試行の実行単位 (cancel対応、並行発行禁止)
```

- **quality class導出の規律**: `GROUNDED` はadapter実装がprovider response内のtool call記録・grounding metadataから `groundingUsed=true` を確認できた場合のみ返す。actual useをresponseから確認できないprovider実装は `GROUNDING_ENABLED_UNVERIFIED` を返し、`GROUNDED` を返してはならない (contract testで強制)。provenance 3状態は許容不変条件 (`groundingUsed => groundingEnabled`、`groundingEnabled => groundingAvailable`) を検証し、違反は `UNEXPECTED_GROUNDING` のtyped failureとする (成功を禁止)。invalid combinationのtable-driven contract testで網羅する。内部表現はboolean 3個でもclosed state modelでもよいが、不変条件検証は必須である。

- **Seam原則**: 呼び出し側もtestも同じpublic seam (`ManagedAiProviderAdapter`) を使う。test double adapterで成功・全套失敗を擬似的に起こし、provider実体がなくてもUI・flow・diagnosticsを検証できる。
- **純粋側とAndroid側の分離**: capability model、request policy、grounding provenance、quality class導出、outcome、adapter interfaceはpure Kotlinとし、provider SDK・okhttp・Keystore依存は `providers/` と `credentials/` の実装側に閉じる。Provider SDK型をinterfaceへ漏らさない (spec「Provider abstraction」)。
- **UI**: `organizer/ui/` 配下にopt-in設定・credential管理 (UI構成は選定されたdelivery modelに従う)・送信内容確認 (grounding有効時はprovider-side検索query生成の明示を含む)・失敗表示・quality class表示を追加。既存settings経路のconventionに従う (D-012)。
- **diagnostics**: typed failure category、quality class・grounding provenance等のprivacy-safe metadataのみを既存run journalへ出力する。field追加はorganizer-diagnostics.mdの正本更新と同じPRで行う。API key・raw prompt/responseは対象外。

### Data flow

```text
user明示操作 (opt-in済み + credential存在)
  -> #204 ContextExportBuilder により context export生成 (spec #204受入形に従う)
  -> 送信内容確認UI (privacy disclosure) -> user承認
  -> ManagedAiSession: adapter.request(context, policy) [bounded 1 request, cancel可]
  -> ManagedAiOutcome
       Success: #204 IntentCodec + IntentValidator (fail-closed) -> accepted intent (digest付き)
                -> #204 planner adapter -> OrganizationPlanner.plan (既存seam)
                -> spec 194 preview -> spec 195 confirm -> spec 13 apply
       Failure: typed failure UI (zero-write)。deterministic継続の明示的選択肢のみ提示
```

- intent受領後はAI要素はなく、以降は全て既存pathである。
- 並行発行禁止: `ManagedAiSession`は同時に1試行のみ。cancel後の遅延結果は破棄する。

### Alternatives rejected

- **アプリ内agent framework / tool registry**: 複雑性・保守性・セキュリティで過剰 (Issue本文の必須設計)。採用しない。
- **provider SDKをdomainへ直接入れ**: SDK型がplanner/rulesへ流出し移行性を損なう。adapterの背後に隠す。
- **#205とtransportを共通化**: Issue本文が同一機能のtransport違いとして扱わないことを明示。#204 intent契約以降のみ共通。
- **AI失敗時の自動fallback (別providerへ再送、one-shotへdowngrade)**: silent semantic downgrade禁止に反する。typed failure + user選択のみ。
- **Jetpack Security Crypto (`EncryptedSharedPreferences`/`MasterKey`) をcredential保存の既定とする**: libraryがdeprecatedのため (出典: Android developer cryptography guidance、確認日2026-09-13)。Keystoreをroot of trustとした現行推奨mechanismの比較 (Open decision 3) に置き替える。
- **direct BYOKを全provider共通の標準production pathとする前提**: providerによってはclient-side長期keyの直接利用を推奨しない場合がある。delivery modelはprovider毎に評価し (spec「BYOK / credential management」、Open decision 1)、非対応providerでdirect BYOKを標準扱いしない。
- **provider直接座標生成**: #204契約が禁止。planner/allocatorが最終安全配置を所有する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/managedai/` (新規) | capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface、session | 本機能本体。pure interfaceと実装の分離 |
| `organizer/personalization/managedai/providers/` (新規) | 初回provider adapter 1つ | Open decision 1の選定結果 (delivery model評価を含む) |
| `organizer/personalization/managedai/credentials/` (新規) | 選定delivery modelのcredential / auth delivery (direct BYOK選定時はkey store。保存機構はOpen decision 3: Keystore root of trust + 現行推奨mechanism比較) | 選定delivery modelのcredential lifecycle (AC-5) |
| `organizer/ui/` + settings | opt-in、credential管理、送信内容確認 (grounding時はquery生成明示を含む)、失敗UI、quality class表示 | AC-3, 5, 6, 7のUI面 |
| `docs/engineering/organizer-diagnostics.md` | privacy-safe metadata field追加 (受入時に確定) | 正本更新 |
| `tests/unit/.../managedai/` (新規) | adapter contract test (test double)、全套failure、malformed/injection fixture、purity guard | AC-2, 7, 10 |
| `specs/206-.../spec.md`, `plan.md` | status更新、Open decisions解消の記録 | 正本管理 |

network/permission/dependency変更: 予定制約として、新規permission追加なし (既存 `INTERNET`)、provider SDK追加なしが第一候補 (okhttp直 + JSON)。いずれかを変更する場合はspec受入後、risk評価を伴う別判断である。

## Migration and recovery

- DB schema変更なし。credential deliveryのstate (direct BYOK選定時はkey store) は新規で、既定をbackup対象外とする (Open decision 3で確定)。
- managed AI未opt-in時の挙動は完全に従来どおり。featureはdefault off。
- rollback: 機能をdefault offへ戻す (設定) またはrevert。export/session/intentの耐久性・失効semanticsはaccepted #204 contractに従い、#206は独自の永続化契約を追加しない。AI失敗は常にzero-writeであり、失敗からの復旧は「何も起きていない」状態である。
- backup/restore: secret (key/token等) の取り扱いをOpen decision 3で確定するまで、backup対象外を維持する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (#204唯一契約) | adapterが #204 codec/validator以外のintent解釈を持たないことのcode review + contract test | unit test |
| AC-2 (bounded request) | adapter interfaceが1 request/response単位であることの契約test、多段orchestration API不存在check | unit test |
| AC-3 (capability typed) | capability modelのenum表現、grounding provenanceからのquality class導出 (`groundingUsed` 確認時のみ `GROUNDED`、確認不能時 `GROUNDING_ENABLED_UNVERIFIED`) のtest、不変条件違反combination (例: `groundingUsed=true` かつ `groundingEnabled=false`) が成功にならず `UNEXPECTED_GROUNDING` に分類されるtable-driven test、UI表示のUI test | unit + UI test |
| AC-4 (grounding/保守fallback) | grounding有効/無効のtest doubleでunresolved戻りを検証、grounding metadata不在時の `GROUNDING_ENABLED_UNVERIFIED` 経路を検証 | unit test |
| AC-5 (credential delivery lifecycle) | 選定delivery modelのlifecycle test (direct BYOK時: 入力/masked表示/削除)、log/diagnostics/bugreportにsecret (key/token) が出ないsecurity test | unit test + NoTransportContractTest族的scanner |
| AC-6 (opt-in/disclosure) | default off回帰test、送信確認UI test (TalkBack含む)。grounding有効時はprovider-side検索query生成・外部検索の明示文面を含むことの検証 | UI test |
| AC-7 (typed zero-write) | Failure taxonomy全套のtable-driven test。failure時に入力不変の検証 | unit test |
| AC-8 (immutable intent) | preview中に再requestされないことのtest、再実行で別identity | unit test |
| AC-9 (既存preview/apply再利用) | AI専用preview path不存在check、既存preview/apply testの無修正通過 | review + unit test |
| AC-10 (contract + security + device) | test double全套、malformed/injection fixture、実機representative evidence | unit test + 実機 |
| AC-11 (deterministic回帰) | 既存offline run suiteの無修正通過 | `./gradlew test` |
| AC-12 (threat model承認) | 承認済みthreat model文書 (BYOK residual risk、credential delivery model境界、provider生成検索query boundary、endpoint boundary、送信data分類を含む) | review |

- **高リスク分類**: network/privacy関連のため `risk: privacy` を付与。planner/provenance統合を含むPRは `risk: layout-data` 払いとし、high-risk gate (CI `final-status` + `docs/assessment/pr-<n>-*.md`) を満たす。

## Documentation updates

- [ ] spec status/history (受入時)
- [ ] `CONTEXT.md`: 「Managed Grounded AI path」「bounded provider request」「provider capability」「BYOK」「quality class」「grounding provenance」追加 (受入時。#204/#205と調整)
- [ ] `DESIGN.md`: §4へmanaged AI module行追加、§11 gate表更新 (受入時)
- [ ] `docs/product/requirements.md`: #204受入時のFR (draft提案FR-017) との関係、FR-014境界、D-011 status更新 (受入PR)
- [ ] `docs/engineering/organizer-diagnostics.md`: privacy-safe metadata許容 (最初の実装PR)
- [ ] ADR: BYOK保存機構とcustom endpoint判断は「変更が高コスト」「理由がコードから分からない」「実際の選択肢があった」を満たす可能性が高いため、受入時にADR要否を判断する

## Execution checklist / implementation order

1. (前提) #204受入、D-011 privacy/threat model承認、Open decisions 1〜10の解消 (少なくとも 1, 2, 3, 7, 8, 10)。
2. child A: pure契約 — capability model、request policy、grounding provenance、quality class導出、outcome、adapter interface + test double全套contract test (AC-2, 3, 7)。
3. child B: credential / auth delivery実装 (Open decision 1で選定されたmodelに従う。direct BYOK選定時はKeystore root of trust + 現行推奨mechanismのkey store。OAuth / short-lived credential選定時はtoken・session lifecycle、relay選定時はrelay認証lifecycle) + opt-in設定 + privacy disclosure UI (AC-5, 6)。
4. child C: 初回provider adapter (structured output)。malformed/injection security test (AC-1, 10一部)。
5. child D: grounding capability統合 + grounding provenance/quality class表示 + 保守fallback・`GROUNDING_ENABLED_UNVERIFIED` 経路 (AC-3, 4)。
6. child E: preview統合、実機evidence、diagnostics正本更新 (AC-8, 9, 10, 11)。
7. 2つ目のproviderは共通adapter contract妥当性確認後に別Issue。

## Dependencies / blockers

- **#204受入 (hard blocker)**: context/intent schema、validator、planner接続adapterが全て #204 由来。未accept (re-review "Request changes" への対応revision `12f773ad61` がbranch `origin/issue-204-spec-plan` に存在、baseline `397d3fd9` 再anchor済み、受入gate Q1/Q3/Q4未解決、**owner再review待ち**、main未取り込み)。
- **D-011 privacy/threat model承認 (hard blocker)**: 実装開始条件。threat modelにはcredential delivery model境界とprovider生成検索query boundaryを含める (AC-12)。
- **#203 (soft)**: usageSignals不在でも成立 (optional)。
- **provider API仕様調査** (Open decision 1/10の入力): structured output / grounding API仕様に加え、各providerのauth / credential delivery model (direct BYOKのproduction support可否、client-side key guidance整合) とgrounding metadata (実利用確認可否) の調査を実装前のresearchとして記録する。

## Risks

- network path追加によるNFR-008/D-011違反 — default off、明示opt-in、diagnostics正本更新を必須にする。
- provider SDK/型のdomain漏出 — purity guard testで `managedai/` 直下 (providers/以外) のSDK依存を禁止する。
- credential流出 — secret (key/token等) をlog/diagnostics/bugreportへ出さないscanner test、masked表示 (direct BYOK時)、residual risk文書化。
- **credential delivery model不整合** — 選定providerがdirect mobile BYOKを推奨しない場合にproduction pathとして受入してしまう。Open decision 1の評価基準 (provider公式guidance整合確認) を受入gateに含める。
- **delivery model選定と実装の乖離による不要なBYOK key store実装** — child BはOpen decision 1の選定modelに従って実装し (AC-5の分岐)、direct BYOK以外のmodel選定時には端末内key storeを実装前提にしない。
- **grounding provenanceのinvalid combination成功扱い** — 不変条件違反 (`groundingUsed => groundingEnabled`、`groundingEnabled => groundingAvailable` 違反) は `UNEXPECTED_GROUNDING` としてtyped zero-write failureに分類し、contract testで網羅する (AC-3)。同意なしの検索実行を成功としない。
- **provider生成検索queryによるprivacy境界** — grounding有効時、Launcherはquery内容を送信前に検証できない。送信確認での明示 (契約) とOpen decision 10 (provider要件 / tier追加制限) で境界を確定するまでgroundingを実装しない。
- **quality classの誤表示** — `groundingUsed` 確認なしに `GROUNDED` を表示・記録する実装をcontract testで禁止する。
- #204受入形と本planの想定差 — #204受入時にspec/planを改訂する (Re-entry rule参照)。
- AI失敗時の誤った自動retry/fallback — 並行発行禁止・retry禁止をsession契約で固定しtestする。

## Explicitly unverified areas

- #204契約の最終形 (field、tier、validator分類)。本planの `context表現`/`intent表現` は #204受入形への参照として扱う。#204 re-review対応revision `12f773ad61` の内容 (durable export session / `ExportSessionStore` interface (pure側) とAndroid実装の `organizer/integration/` 配置、session TTL、`sourceContextDigest` / `exportId`、export単位のfresh random ref、per-item mobility projectionと `MOBILITY_CONTRADICTION`、coverage不変条件、`CONTEXT_STALE` reject-on-change等) は **owner再review待ち** (受入gate Q1/Q3/Q4未解決) であり本planの確定前提にしない。受入時に改めて確認する。
- #203 signal snapshotの最終schema (未実装)。
- 初回providerのstructured output / grounding API仕様詳細 (未調査。provider選定researchで確認)。
- 各providerのauth / credential delivery model (direct BYOKのproduction support可否、short-lived credential / OAuth / backend relayの要否、provider公式client-side key guidance) (未調査。Open decision 1の評価入力)。
- grounding実行のactual useをprovider responseから確認できるか (tool call記録・grounding metadataの返却形式) (未調査。確認できないproviderでは `GROUNDING_ENABLED_UNVERIFIED` 扱いとなる)。
- credential保存機構の選定とbackup/restore互換性の詳細 (Open decision 3)。
- `organizer/personalization/` package名は #204 draftの想定であり、#204受入時の実配置に合わせる必要がある。
- AI intentと #228 `ScopeComposedOrganization` の `TargetSet.additions` (missing-app選択) の共存詳細 (intentが追加対象を生まないことの契約上の保証は #204 draft側の記述であり、受入時に確認)。
