# Implementation Plan: AI personalization用 Context / PersonalizedIntent exchange contract

> Issue: #204
> Spec: [spec.md](./spec.md)
> Status: draft
> Baseline: `origin/main` = `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda` (2026-09-13時点)。owner review (Request changes, 2026-09-13) 対応を含むrevision。過去の再入場検証履歴は「Current evidence」節を参照。

## Current evidence

確認済みの現行状態 (2026-09-13, `origin/main` @ `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda`)。実装開始時に再検証する。

- `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt` — `OrganizationInput(snapshot, rules, taxonomy, signals, targets, runMode)`。`RunMode` は `FullOrganization` / `IncrementalPlacement` / `ScopeComposedOrganization` (#228、`TargetSet.additions` が非空になり得る唯一のmode)。`CapturedItem` は `ItemKind` として `APPLICATION`/`DEEP_SHORTCUT`/`SHORTCUT_LEGACY`/`FOLDER`/`APPWIDGET`/`CUSTOM_APPWIDGET`/`APP_PAIR`/`Unknown` を持つ (#235でwidgetが第一級計画対象)。`LayoutSnapshot.reservedWorkspaceRegions` (`ReservedWorkspaceRegion`) はitem外のplatform占有領域constraint。`ItemId`/`ProfileId`/`CategoryId` 等は `planning/Identity.kt` のopaque value class。
- **intent addressable性の現行真実 (review対応で確認)**: `planning/PlanningPlacement.kt` の `determinePreservation` は `APP_PAIR`/`AppPairMember` を無条件に `PreserveReason.APP_PAIR`、`SHORTCUT_LEGACY` を無条件に `PreserveReason.LEGACY_SHORTCUT` で固定する (strategy escapeなし。widgetのみ `widgetPolicy` 持ちstrategy下で `relocateWidgets` により移動可)。`integration/FullTargetSetMaterializer.kt` も `APP_PAIR`/`SHORTCUT_LEGACY`/widget系をkind基準で `ExistingRole.Preserved` にする。Movableになり得るのは `APPLICATION`/`DEEP_SHORTCUT`/`FOLDER` (top-level workspace) のみ。このためV1契約のaddressable対象は `APP_OR_SHORTCUT` (APPLICATION/DEEP_SHORTCUT)/`FOLDER`/`WIDGET` の3 roleに固定した (spec「Contract 1」)。
- `lawnchair/src/app/lawnchair/organizer/planning/OrganizationPlanner.kt` — 唯一の外部planning seam `plan(OrganizationInput): PlanningResult` (spec 182 AC-4)。前回baselineから未変更。
- `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt` — #182内部strategy catalog。#235によりwidget placement role/stream/band (`PlanningPlacement.kt` / `PlacementAllocator.kt`、`PlacementCode.WIDGET_UNIT`) が追加済み。`PlanningResult` は `organizationStrategy: StrategyId` と #228 の `unplaced` を持つ。`PreserveReason` enumは `planning/PlanningResult.kt`。
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt` / `CompositionModels.kt` — `InputProvenance` を所有 (`PolicySourceKind`/`PolicyInputIdentity` は `rules/PolicyModels.kt`)。`layoutStrategySelection` は第5 policy input (code comment明記)。`PolicySourceKind` は現時点で6値 (`ORGANIZER_POLICY_BUNDLE`, `CATEGORY_OVERRIDE_SNAPSHOT`, `LAYOUT_STRATEGY_SELECTION`, `PLATFORM_CLASSIFICATION_EVIDENCE`, `MATERIALIZED_CLASSIFICATION_SIGNALS`, `MATERIALIZED_FULL_TARGET_SET`)。#228の `StaleCandidateSelection` 等、composition failure型も拡張済み。
- `lawnchair/src/app/lawnchair/organizer/rules/` — `BuiltInOrganizerPolicyBundleSource.kt` (ADR-0007のpolicy authority)、`LayoutStrategySelectionStore.kt`、`CategoryOverrideStore.kt` (schema version + generation + digest のatomic store族の先例。export session storeの実装様式の参照先)。ADR-0007へは #228 のscope-composed target identity拡張が追記済み (本契約と矛盾なし)。
- `lawnchair/src/app/lawnchair/organizer/application/preview/` — spec 194 plan preview (`inspectPlan`)。spec 195 confirmation UIは `organizer/ui/`。#271 durable status / #288 diagnostics export filename は本契約と直交するadditive変更。
- unit test置き場は `tests/unit/app/lawnchair/organizer/` (planning/integration/rules/ui 等)。既存guard: `planning/PurityGuardTest.kt`、property test基盤 `planning/PlannerGeneratedPropertyTest.kt`、widget系 `planning/WidgetPlacementStrategyTest.kt`、scope-composed系 `planning/ScopeComposedPlannerTest.kt` / `integration/ScopeComposedCompositionTest.kt`。実行commandはbuilding guideの `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`。
- #203 (usage signals) は OPEN で、`PersonalizationSignalSnapshot` / usage signal実装は現mainに存在しない (`specs/203-*` も未取り込み)。よって `usageSignals` はoptional fieldとし、不在でも契約が成立するよう設計する。
- #205/#206 (外部agent exchange / managed AI) は OPEN。consumer不在でも本契約 (codec + validator + adapter + session store) は単体でtest可能な純粋module + 境界1つで成立する。
- `docs/product/requirements.md` は FR-016 まで (FR-017は未割当)。owner review (2026-09-13) の通りFR-017を確定扱いにできる。
- 推測 (未確認): intent採用時の `InputProvenance` 第6 input追加が `CompositionModels.kt` のsealed構造 (`SourceUnavailable` 等の全網羅箇所) へ与える影響の詳細。実装child issueで確認する。

**再入場検証 (2026-09-13, 第2回)**: 前回snapshot baseline `f9afd8bfde121932c0c8ed965225d52a84d86ab4` から現baseline `c5274b5d0d1a4cd3a5cf55ef8dcadb84283a3cda` への差分を確認した。変更は #304 (API 36 window focus: CI evidence capture、UI lane系test)、#283 (strategy picker選択表示: strings/preferences UI)、#287 (grid変更時のunknown lock回収: `LockAuthoring.kt` のfolder child rank validation緩和とlocks系test)、#300/#308 (UI注入前提/Compose focus) であり、planning seam・`InputProvenance`・preview path・本契約の接続面への変更はない。#287の `LockAuthoring` 変更はlock review capacityの話であり、export契約のlock制約projectionの意味は不変。**owner review "Request changes" (2026-09-13, snapshot `65b9fc859d` 基準) を処理し**、P1×4 (process death耐性、missing ref fail-closed、export決定性の自己矛盾、redacted tierの弱いprivacy契約) / P2×2 (source context binding不在、APP_PAIR扱い未定義) を本planとspecの該当節へ反映した。コード確認により、旧specの「plannerはwidgetとapp pairを第一級計画対象」という記述が誤り (app pairは無条件preserved) であることを検証済み。

## Design

### Modules and interfaces

新規package `lawnchair/src/app/lawnchair/organizer/personalization/` (DESIGN.md §9の論理構成に従う追加。純粋Kotlin、Android型・DB・networkなし)。

```text
organizer/personalization/
├── ContextExportModels.kt    # PersonalizationContextExportV1 typed model (pure)
├── ContextExportBuilder.kt   # canonical inputs -> export (pure, tier-aware, ref決定的割当)
├── IntentModels.kt           # PersonalizedIntentV1 typed model (pure)
├── IntentCodec.kt            # JSON <-> typed model (closed schema, limits)
├── IntentValidator.kt        # strict validation -> typed failure (pure; coverage/session/stale検証含む)
├── IntentIdentity.kt         # content digest / schema identity / contextDigest計算
├── ExportSessionStore.kt     # export session (durable・期限付き) のseam interface + 実装
└── IntentPlannerAdapter.kt   # validated intent -> planner-internal semantic inputs (pure)
```

- **Seam原則**: 呼び出し側もtestも同じpublic seam (builder / codec+validator / adapter / session store) を使う。internal実装 (ref割当、digest計算、session書込み) を直接検証しない。
- **ContextExportBuilder** の入力は既存canonical入力のみ (`LayoutSnapshot` 相当のcaptured data、解決済み分類、lock状態。#203導入後はsignal snapshot)。tierに応じるfield除外 (自由文class一括制御) はbuilder内の単一点で行う。`ref` 割当はcanonical入力から決定的かつ一方向 (実行時順序・乱数非依存。ItemId逆算不可) とし、`contextDigest` はenvelope (`exportId`) を除く本文に対して計算する。
- **ExportSessionStore** はexport生成時にsession (`exportId`、ref↔`ItemId` map、tier、`contextDigest`、生成・失効時刻) をapp-private・backup対象外でdurableに保存する唯一の境界module。実装様式は `rules/CategoryOverrideStore.kt` 族 (schema version + atomic書込み) に従い、Launcher favorites DBとは独立である。V1はsingle-active-session (新規export生成が既存sessionを無効化) であり、失効・無効化されたsessionの読み出しは無効を返す。process death耐性はこのmoduleが担う (純粋moduleの外にある唯一の状態境界)。
- **IntentValidator** は spec の失敗class (`SCHEMA_MISMATCH`/`EXPORT_MISMATCH`/`SESSION_EXPIRED`/`CONTEXT_STALE`/`OVERSIZE`/`UNKNOWN_REF`/`DUPLICATE_REF`/`INCOMPLETE_COVERAGE`/`INVALID_ENUM`/`FORBIDDEN_CONTENT`/`CAPABILITY_UNSUPPORTED`) をtyped sealed resultで返す。fail-closed、zero-write。coverage不変条件 (`itemIntents` refs ∪ `unresolvedRefs` == 全addressable refs、互いに素) とsource binding (`contextDigest` 再計算照合) はvalidatorの検証項目である。
- **IntentPlannerAdapter** はvalidated intentをplanner内部のsemantic入力へ投影する (投影先はspec Open question 1。受入時に固定)。addressable対象は `APP_OR_SHORTCUT`/`FOLDER`/`WIDGET` の3 roleに限られ、widget親和の実効化は `widgetPolicy` 持ちstrategyを選択したrunでのみplanner側で行われる。
- **Provenance統合**: accept済みintentは `PolicySourceKind.PERSONALIZED_INTENT` として `InputProvenance` へ第6 policy inputを追加する (`LayoutStrategySelectionSnapshot` と同じgeneration/digest契約族)。intent未使用runはこのinputを持たない (既存runへの影響なし)。
- **Diagnostics**: `PlanningResult` echoへ intent identity (digest) を追加する。個人情報 (label、package、座標) はdiagnosticsへ出さない (organizer-diagnostics.mdの規則拡張を同PRで)。

### Data flow

```text
canonical inputs --(ContextExportBuilder, tier)--> PersonalizationContextExportV1 + export session (durable作成)
    --> [AI/agent: #205/#206またはtest double] --> PersonalizedIntentV1
    --(IntentCodec + IntentValidator; session照会・contextDigest照合)--> ValidatedPersonalizedIntent (digest付き) | typed failure
    --(IntentPlannerAdapter)--> planner semantic inputs
    --(OrganizationPlanner.plan: 既存唯一seam)--> PlanningResult (intent identity echo)
    --(spec 194/195/13: 既存preview/confirm/apply)--> 適用
```

- network・providerとの入出力は本moduleの外 (#205/#206)。codecはtransport非依存のcanonical JSON表現のみ定義する。
- regenerationは新規exportId/intent identity。既存previewの流用はspecどおり無効化する。同一canonical状態の再生成は `contextDigest` を保ち、`exportId` のみ新規である。

### Alternatives rejected

- **AIへ `OrganizationInput` を直接渡す**: lock/bounds安全性、privacy (raw ID/package)、schema移行性で不適切 (Issue本文が明示)。採用しない。
- **intentを `RuleSemantics` へ直書き**: intentはrun-scopedのdynamic inputでありimmutable bundle policyではない。ADR-0007のauthority model (bundle ≠ dynamic state) に反する。
- **#182 catalogへの「AI strategy」追加**: intentはsemantic preferenceであり組み込みstrategyとは別物。catalogはcuratedでなければならない (spec 182 Non-goals)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/` (新規) | Context/Intent model、builder、codec、validator、identity、adapter、ExportSessionStore | 契約本体。pure module + 唯一の状態境界 (session store) |
| `organizer/integration/CompositionModels.kt` | `PolicySourceKind.PERSONALIZED_INTENT` 追加、intent identityの`InputProvenance`参加 | determinism/provenance保証 |
| `organizer/integration/OrganizationInputComposer.kt` | accepted intentの読み取りとstable cutへの組入れ (intent利用runのみ) | spec 183系のconsistent-cut規律を維持 |
| `organizer/planning/PlanningResult.kt` | intent identity echo (optional) | diagnostics/previewでの追跡 |
| `docs/engineering/organizer-diagnostics.md` | intent identity/digest のallowed field追加 (session内容・自由文は引き続き不許可) | privacy規則の正本更新 |
| tests (下記Verification) | contract/property/security tests | AC対応 |
| `specs/204-.../spec.md`, `plan.md` | status更新、Open questions解消の記録 | 正本管理 |

実装はchild issueへ分割する (下記Execution order)。本Issueの実装縦切りは「pure codec/validator + session store + provenance統合」までとし、#205/#206接続を含まない (Issue本文のImplementation orderどおり)。ExportSessionStoreはLauncher favorites DBと独立なapp-private storageであり、`risk: layout-data` 判定の根拠 (home layout正本を触らない) を実装PRの説明に明記する。

## Migration and recovery

- 新規純粋module追加 + export session store (app-private・backup対象外・Launcher favorites DBと独立) のみ。Launcher DB schema変更なし → Launcher DB migrationは不要。
- session store自体はschema version付きの新規storageであり、version不一致・破損sessionはfail-closedで無効扱いする (session喪失の影響は「再exportが必要」のみで、既存layout・planning入力への影響はない)。
- intent未使用runは全て従来どおり (互換性: 既存testが無修正で通ることが回帰基準)。
- rollback: 実装のrevertで完了。session storeはrevertで不要になる一時recordのみを保持する。validation失敗時のzero-writeにより、失敗からの復旧も「何も起きていない」状態である。
- backup/restore: session storeはbackup対象外とし、backup/restore互換に影響しない (restore後は既存session無効 → 再export)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 (FR-017) | requirements.md更新 (受入PR) | review |
| AC-2 (versioned schema) | model contract test: schemaVersion固定、unknown version拒否 | unit test (`./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`) |
| AC-3 (provider非依存) | 同一intentをtest doubleと将来providerが同じseamで取り込める契約test | unit test |
| AC-4 (planner authority) | `FORBIDDEN_CONTENT` 検証test (座標指定/lock移動含むintentのreject) + purity guard拡張 | unit test |
| AC-5 (fail-closed zero-write) | 各typed failureのtable-driven test。malformed/oversize/duplicate/unknown/**coverage違反 (`INCOMPLETE_COVERAGE`)/session失効 (`SESSION_EXPIRED`)/source不一致 (`CONTEXT_STALE`)** | unit test |
| AC-6 (intent identity/determinism) | 同一accepted intent + 同一inputs → byte-equal plan。regenerate → 別identity。preview無効化 | unit + property test |
| AC-7 (prompt injection) | injection label fixtureを含むexport/intentのsecurity test群 | unit test |
| AC-8 (privacy tier) | tier別export field集合の契約test (package/profile/raw usage不混入の全field走査 + **自由文classのtier別走査: `EXTERNAL_REDACTED` にlabel/folder title/自由文・surrogate fieldが一切現れないこと**) | unit test |
| AC-9 (preview path再利用) | planが`PlanningResult`→`inspectPlan`以外の経路を取らないことのコードレビュー + 既存preview testの無修正通過 | review + unit test |
| AC-10 (test計画) | 本表 | 本plan |
| AC-11 (process death耐性) | export生成 → **store instanceを破棄・再生成してprocess deathを模擬** → 失効前intent取り込み成功。失効後は `SESSION_EXPIRED`。不明sessionは `EXPORT_MISMATCH` | unit test (ExportSessionStore seam経由) |
| AC-12 (source context binding) | export後に入力canonical状態を変化させ、`contextDigest` 再計算照合が `CONTEXT_STALE` でrejectすること。同一状態での再生成が `contextDigest` を保ち `exportId` のみ変わることの分離test | unit + property test |
| AC-13 (kind projection matrix) | `APP_OR_SHORTCUT`/`FOLDER`/`WIDGET` のみ `items` に現れること。`APP_PAIR`/`SHORTCUT_LEGACY` は `preservedConstraints` 集計のみ。`Unknown` 除外。これらへの `ref` 参照が `UNKNOWN_REF` になること | unit test |

追加観点:
- **Property test**: validatorは全fail classでtotal functionである (入力任意byte列に対しrejectまたはvalidのいずれか、例外・hangなし)。export builderは同一inputs→同一 `contextDigest`・同一 `ref` 集合、状態変化→digest変化。coverage不変条件のpartition性 (集合演算のproperty test)。既存のproperty基盤 (`tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt` 族) に整合させる。
- **Purity test**: personalization packageのAndroid依存・I/O依存ゼロ (`tests/unit/app/lawnchair/organizer/planning/PurityGuardTest.kt` 族に追加)。ExportSessionStoreはseam interface経由でのみ触り、実装はpurity対象から除外する。
- **Widget projection test**: widget itemを含むexport/intentでspan不変が保たれること (`FORBIDDEN_CONTENT` のspan指定reject、adapter投影後も `PlacementCode.WIDGET_UNIT` 系のstrategy宣言semanticsを弱めない) を #235 の `WidgetPlacementStrategyTest` と同水準のfixtureで検証する。widget親和intentが `widgetPolicy` なしstrategy下で移動を強制しないことの確認を含む。
- **高リスク分類**: 本契約自体はpure追加だが、provenance/planner統合PRは `risk: layout-data` 払いとし、high-risk gate (CI `final-status` + `docs/assessment/pr-<n>-*.md`) を満たす。

## Documentation updates

- [ ] spec status/history (受入時)
- [ ] `CONTEXT.md`: 「Personalization Context Export」「Personalized Intent」「export-scoped ID」追加 (受入時)
- [ ] `DESIGN.md`: §4へpersonalization module行追加、§11 gate表へ本契約の正本を行追加 (受入時)
- [ ] `docs/product/requirements.md`: FR-017追加、FR-014との境界備考、D-011言及 (受入PR)
- [ ] `docs/engineering/organizer-diagnostics.md`: intent identity許容 (最初の実装PR)
- [ ] ADR: なし (契約自体はspecで十分。将来provider接続 (#205) でprivacy/threat model判断時に検討)

## Execution checklist / implementation order

1. (前提) spec受入 (owner re-review待ち)。受入gate必須のOpen questionsを固定する: **Q1 (planner投影)、Q3 (capability set初期内容)、Q4 (content limits + session TTL数値)**。Q2 (FR-017) とQ5 (自由文/surrogate方針) は2026-09-13 revisionで解決済み。
2. child A: `personalization/` pure package — models + builder + codec + validator + identity、全contract/property/security test (AC-2,3,4,5,7,8,12,13)。ref決定割当とcontextDigest分離のproperty testを含む。
3. child B: provenance統合 (`PERSONALIZED_INTENT` 第6 input) + adapter + `PlanningResult` echo + composer stable cut 組入れ (AC-6,9)。`risk: layout-data` + high-risk gate。
4. child C: #203契約確定後、`usageSignals` projectionの追記とtest (spec Open question 6)。
5. #205/#206: provider接続 (本Issueの範囲外)。

ExportSessionStore (durable session) はchild Aに含める (AC-11)。storage実装は既存store族の様式に従い、backup対象外設定を含める。

## Dependencies / blockers

- **capability set (Q3) と content limits/session TTL (Q4) の確定がchild Aのblocker (受入時に固定)** — owner review (2026-09-13) により受入gateに追加された。
- planner投影 (Q1) の確定がchild Bのblocker。
- #203 の契約確定が `usageSignals` 詳細のblocker (V1ではoptionalにより実質blockでない)。
- #205/#206 は本契約のconsumerとして本Issue実装後 (Issue本文: 「Provider integrationより先に本contractをacceptedにする」)。

## Risks

- intent→planner投影が既存determinism/idempotence (INV-7/8) を壊す恐れ → child Bで既存harness/property suiteの無修正通過を必須化し、intentあり/なしのcross-run testを追加する。
- widget/span投影の漏れ: intentのpageAffinity等が #235 のwidget stream/band・span不変semanticsを迂回する形で実装される恐れ → `FORBIDDEN_CONTENT` のspan指定rejectと投影後planのwidget意味論testで固定する (Verification参照)。
- privacy tier判定の漏れ (将来field追加時に意図せず外部送信) → user作成自由文をclosed classとして宣言制御し、新field追加時にclass宣言を強制するtype設計。tier別field集合走査test (AC-8) で固定。
- **export session storeの漏えい**: ref↔内部`ItemId` mapは内部identityを含むため、app-private設定・backup対象外・diagnostics非出力をchild Aのtestで固定する (漏えい経路: backup、log、export)。session本文 (自由文) を保存しない設計の契約test。
- **`CONTEXT_STALE` の誤検知**: digest再計算が非決定的 (順序非安定等) だと、状態不変でもimportが失敗する → builder決定性property test (同一inputs→同一digest) と、状態不変でのimport成功testで固定する。
- export/intent本文の意図しない永続化 → session store以外の永続化経路が存在しないことをpurity/persistence testで固定。
- session失効と外部AI応答待ちのUX不整合 (長文生成中に失効) → `SESSION_EXPIRED` の再export導線は #205 のUI課題として記録 (本契約はfail-closedのみ定義)。

## Explicitly unverified areas

- #203 signal snapshotの最終schema (未実装・draft spec未取り込み)。`usageSignals` のfield詳細は仮枠。
- `ref` 決定的割当の具体的アルゴリズム (衝突回避・一方向性の実装詳細)。child Aで確定し、契約条件 (決定性・一方向・衝突なし) をproperty testで検証する。
- ExportSessionStoreの具体的storage実装 (既存store族のどの様式を採るか、backup除外の適用方法)。child Aで確定。
- `InputProvenance` sealed構造への第6 input追加影響の詳細 (child Bで確認)。
- #205/#206 UI・transport経路での実際の取り込み、session失効時の再export導線UX (範囲外)。
