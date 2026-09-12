# Implementation Plan: Managed Grounded AI personalization

> Issue: #206
> Spec: [spec.md](./spec.md)
> Status: draft — 実装開始条件 (#204受入、D-011 privacy/threat model承認) を満たす前に実装しない。

## Current evidence

確認済みの現行状態 (2026-09-13に再検証、`origin/main` @ `f9afd8bfde121932c0c8ed965225d52a84d86ab4`)。実装開始時に再検証する。前回確認 (2026-09-10, `6b6bf8dd`) からの主差分は本節末尾に記載する。

- **AI/network provider codeはorganizerに存在しない。** `lawnchair/src/app/lawnchair/organizer/` 配下にAI・provider・network関連実装、`organizer/personalization/` packageは存在しない。AI adapter・credential store・opt-in設定はすべて新規実装である。
- **app全体のnetwork前提**: `lawnchair/AndroidManifest.xml` はbaseline Lawnchair由来の `android.permission.INTERNET` を既に保持する。`gradle/libs.versions.toml` にretrofit/okhttp (bundle `retrofit`) が存在し、`lawnchair/src/app/lawnchair/ui/preferences/about/GithubService.kt`、`ui/preferences/data/liveinfo/LiveInformationService.kt`、`bugreport/KatbinService.kt` 等のpreferences系serviceがokhttp系networkingの先例である。よって新規permissionや新規network library追加は必須ではない (provider SDK追加与否はOpen decision)。
- **organizerのno-transport規律**: `tests/unit/app/lawnchair/organizer/diagnostics/integration/NoTransportContractTest.kt` (spec 67 AC-67-12) はdiagnostics module配下のnetwork/worker importを禁止する。organizer-diagnostics.mdは「organizer diagnosticsは外部transportを持たずdefault off」(NFR-008) が正本。AI diagnostics field追加時はこの正本とtestの更新が必要。
- **planner seam**: `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` が唯一の外部planning seam `plan(OrganizationInput): PlanningResult` (spec 182 AC-4)。前回確認からsignature不変。AI intentは直接ここへ入らず、#204契約のadapterを経る (draft時点で #204 の `IntentPlannerAdapter` 相当は未実装・未accept)。
- **provenance**: `organizer/rules/PolicyModels.kt` の `PolicySourceKind` (6値) と `organizer/integration/CompositionModels.kt` / `OrganizationInputComposer.kt` が `InputProvenance` / policy input identityを所有。#182により `LAYOUT_STRATEGY_SELECTION` が第5 policy input (`InputProvenance.layoutStrategySelection`、code comment "Spec 182: fifth policy input")。#204 draftは第6input `PERSONALIZED_INTENT` を提案しているが未確定。
- **preview/confirm/apply**: `organizer/application/preview/` (spec 194 `inspectPlan`)、`organizer/ui/` (spec 195 confirmation、`ManualOrganizationRun.kt`、`OrganizationOperationLease.kt` 等)、spec 13 apply/recovery。いずれも実装済みで、AI pathはこれを複製しない。
- **#204/#205/#203**: いずれもOPEN。#204/#205のdraft spec/plan はbranch (`origin/issue-204-spec-plan`, `origin/issue-205-spec-plan`) のみでorigin/main未取り込み。#204 draftは2026-09-13にbaseline `f9afd8bfde` へ再anchor済み (契約の核は不変、#235/#228反映の投影詳細を更新)。#203のsignal実装もorigin/mainに存在しない。
- **2026-09-10 → 2026-09-13のmain差分と本planへの影響**: #228 (missing-app選択: `RunMode.ScopeComposedOrganization` 追加、`CandidateResolution`/`CandidatePlanningIds`、`MissingAppSelectionScreen.kt`、`InputReadinessReason.StaleCandidateSelection`)、#235 (widget移動strategy `STABLE_PAGE_TIDY_V2`/`BOTTOM_FIRST_V2`、`POLICY_BUNDLE_VERSION` v2.6)、#271 (durable status projection)、#288 (diagnostics export filename規則)、#292 (orientation row安定化)、requirements FR-016 implemented更新、ADR-0007へ#228追記。いずれもAI/transport/credentialに関係せず、本planの変更set・flow・seam前提は不変。AI intentがtarget追加を生まない限り #228 のselection入力と干渉しない点は #204受入時に確認する。
- 推測 (未確認): provider APIのstructured output / grounding optionの現行仕様詳細。実装時にprovider選定とともに調査し、調査記録をIssueへ残す。

## Design

### Modules and interfaces

新規package `lawnchair/src/app/lawnchair/organizer/personalization/managedai/` (#204実装が `organizer/personalization/` を使うdraftであるため、その下位に置く。#204受入時に実際の配置へ合わせる)。

```text
organizer/personalization/managedai/
├── AiProviderCapabilities.kt   # typed capability宣言 (STRUCTURED_OUTPUT, WEB_GROUNDING, ...)
├── ManagedAiRequestPolicy.kt   # timeout / size上限 / grounding許可 (immutable value)
├── ManagedAiOutcome.kt         # typed sealed outcome: Success(intent表現) | <Failure taxonomy全套>
├── ManagedAiProviderAdapter.kt # fun interface: request(context表現, policy) -> ManagedAiOutcome
├── providers/                  # 初回provider adapter 1つ (Open decision 1で選定)
├── credentials/                # BYOK store interface + Android実装 (機構はOpen decision 3)
└── ManagedAiSession.kt         # 1試行の実行単位 (cancel対応、並行発行禁止)
```

- **Seam原則**: 呼び出し側もtestも同じpublic seam (`ManagedAiProviderAdapter`) を使う。test double adapterで成功・全套失敗を擬似的に起こし、provider実体がなくてもUI・flow・diagnosticsを検証できる。
- **純粋側とAndroid側の分離**: capability model、request policy、outcome、adapter interfaceはpure Kotlinとし、provider SDK・okhttp・Keystore依存は `providers/` と `credentials/` の実装側に閉じる。Provider SDK型をinterfaceへ漏らさない (spec「Provider abstraction」)。
- **UI**: `organizer/ui/` 配下にopt-in設定・credential管理・送信内容確認・失敗表示を追加。既存settings経路のconventionに従う (D-012)。
- **diagnostics**: typed failure category、quality class等のprivacy-safe metadataのみを既存run journalへ出力する。field追加はorganizer-diagnostics.mdの正本更新と同じPRで行う。API key・raw prompt/responseは対象外。

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
- **provider直接座標生成**: #204契約が禁止。planner/allocatorが最終安全配置を所有する。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/managedai/` (新規) | capability model、request policy、outcome、adapter interface、session | 本機能本体。pure interfaceと実装の分離 |
| `organizer/personalization/managedai/providers/` (新規) | 初回provider adapter 1つ | Open decision 1の選定結果 |
| `organizer/personalization/managedai/credentials/` (新規) | BYOK store (保存機構はOpen decision 3) | credential lifecycle (AC-5) |
| `organizer/ui/` + settings | opt-in、credential管理、送信内容確認、失敗UI、quality class表示 | AC-3, 5, 6, 7のUI面 |
| `docs/engineering/organizer-diagnostics.md` | privacy-safe metadata field追加 (受入時に確定) | 正本更新 |
| `tests/unit/.../managedai/` (新規) | adapter contract test (test double)、全套failure、malformed/injection fixture、purity guard | AC-2, 7, 10 |
| `specs/206-.../spec.md`, `plan.md` | status更新、Open decisions解消の記録 | 正本管理 |

network/permission/dependency変更: 予定制約として、新規permission追加なし (既存 `INTERNET`)、provider SDK追加なしが第一候補 (okhttp直 + JSON)。いずれかを変更する場合はspec受入後、risk評価を伴う別判断である。

## Migration and recovery

- DB schema変更なし。credential storeは新規で、既定をbackup対象外とする (Open decision 3で確定)。
- managed AI未opt-in時の挙動は完全に従来どおり。featureはdefault off。
- rollback: 機能をdefault offへ戻す (設定) またはrevert。未confirm intentはprocess-localであり (#204契約)、保持stateの復旧は不要。AI失敗は常にzero-writeであり、失敗からの復旧は「何も起きていない」状態である。
- backup/restore: credentialの取り扱いをOpen decision 3で確定するまで、backup対象外を維持する。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (#204唯一契約) | adapterが #204 codec/validator以外のintent解釈を持たないことのcode review + contract test | unit test |
| AC-2 (bounded request) | adapter interfaceが1 request/response単位であることの契約test、多段orchestration API不存在check | unit test |
| AC-3 (capability typed) | capability modelのenum表現とquality class導出のtest、UI表示のUI test | unit + UI test |
| AC-4 (grounding/保守fallback) | grounding有効/無効のtest doubleでunresolved戻りを検証 | unit test |
| AC-5 (BYOK lifecycle) | storeの入力/masked表示/削除test、log/diagnostics/bugreportにkeyが出ないsecurity test | unit test + NoTransportContractTest族的scanner |
| AC-6 (opt-in/disclosure) | default off回帰test、送信確認UI test (TalkBack含む) | UI test |
| AC-7 (typed zero-write) | Failure taxonomy全套のtable-driven test。failure時に入力不変の検証 | unit test |
| AC-8 (immutable intent) | preview中に再requestされないことのtest、再実行で別identity | unit test |
| AC-9 (既存preview/apply再利用) | AI専用preview path不存在check、既存preview/apply testの無修正通過 | review + unit test |
| AC-10 (contract + security + device) | test double全套、malformed/injection fixture、実機representative evidence | unit test + 実機 |
| AC-11 (deterministic回帰) | 既存offline run suiteの無修正通過 | `./gradlew test` |
| AC-12 (threat model承認) | 承認済みthreat model文書 | review |

- **高リスク分類**: network/privacy関連のため `risk: privacy` を付与。planner/provenance統合を含むPRは `risk: layout-data` 払いとし、high-risk gate (CI `final-status` + `docs/assessment/pr-<n>-*.md`) を満たす。

## Documentation updates

- [ ] spec status/history (受入時)
- [ ] `CONTEXT.md`: 「Managed Grounded AI path」「bounded provider request」「provider capability」「BYOK」「quality class」追加 (受入時。#204/#205と調整)
- [ ] `DESIGN.md`: §4へmanaged AI module行追加、§11 gate表更新 (受入時)
- [ ] `docs/product/requirements.md`: #204受入時のFR (draft提案FR-017) との関係、FR-014境界、D-011 status更新 (受入PR)
- [ ] `docs/engineering/organizer-diagnostics.md`: privacy-safe metadata許容 (最初の実装PR)
- [ ] ADR: BYOK保存機構とcustom endpoint判断は「変更が高コスト」「理由がコードから分からない」「実際の選択肢があった」を満たす可能性が高いため、受入時にADR要否を判断する

## Execution checklist / implementation order

1. (前提) #204受入、D-011 privacy/threat model承認、Open decisions 1〜9の解消 (少なくとも 1, 2, 3, 7, 8)。
2. child A: pure契約 — capability model、request policy、outcome、adapter interface + test double全套contract test (AC-2, 3, 7)。
3. child B: credential store + opt-in設定 + privacy disclosure UI (AC-5, 6)。
4. child C: 初回provider adapter (structured output)。malformed/injection security test (AC-1, 10一部)。
5. child D: grounding capability統合 + quality class表示 + 保守fallback (AC-3, 4)。
6. child E: preview統合、実機evidence、diagnostics正本更新 (AC-8, 9, 10, 11)。
7. 2つ目のproviderは共通adapter contract妥当性確認後に別Issue。

## Dependencies / blockers

- **#204受入 (hard blocker)**: context/intent schema、validator、planner接続adapterが全て #204 由来。未accept (2026-09-13にbranch側でbaseline `f9afd8bfde` へ再anchor、statusはdraftのまま)。
- **D-011 privacy/threat model承認 (hard blocker)**: 実装開始条件。
- **#203 (soft)**: usageSignals不在でも成立 (optional)。
- **provider API仕様調査** (Open decision 1の入力): 実装前のresearchとして記録する。

## Risks

- network path追加によるNFR-008/D-011違反 — default off、明示opt-in、diagnostics正本更新を必須にする。
- provider SDK/型のdomain漏出 — purity guard testで `managedai/` 直下 (providers/以外) のSDK依存を禁止する。
- credential流出 — keyをlog/diagnostics/bugreportへ出さないscanner test、masked表示、residual risk文書化。
- #204受入形と本planの想定差 — #204受入時にspec/planを改訂する (Re-entry rule参照)。
- AI失敗時の誤った自動retry/fallback — 並行発行禁止・retry禁止をsession契約で固定しtestする。

## Explicitly unverified areas

- #204契約の最終形 (field、tier、validator分類)。本planの `context表現`/`intent表現` は #204受入形への参照として扱う。#204 draftの2026-09-13再anchor内容 (semantic placement role投影、widget span/reservation系 `FORBIDDEN_CONTENT` 拡張等) は本planの前提にしない。受入時に改めて確認する。
- #203 signal snapshotの最終schema (未実装)。
- 初回providerのstructured output / grounding API仕様詳細 (未調査。provider選定researchで確認)。
- credential保存機構の選定とbackup/restore互換性の詳細 (Open decision 3)。
- `organizer/personalization/` package名は #204 draftの想定であり、#204受入時の実配置に合わせる必要がある。
- AI intentと #228 `ScopeComposedOrganization` の `TargetSet.additions` (missing-app選択) の共存詳細 (intentが追加対象を生まないことの契約上の保証は #204 draft側の記述であり、受入時に確認)。
