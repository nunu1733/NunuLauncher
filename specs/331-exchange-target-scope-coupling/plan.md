# Implementation Plan: Exchange対象scopeへの未配置アプリ候補の包含

> Issue: #331
> Spec: [spec.md](./spec.md)
> Status: implemented — [PR #333](https://github.com/nunu1733/NunuLauncher/pull/333) merge (commit `addb25d8181e`)。実装はChatGPT implementation review 2ラウンド (Changes requested → 解消 → Approve) を経て受理。独立監査: [docs/assessment/pr-333-exchange-target-scope-coupling.md](../../docs/assessment/pr-333-exchange-target-scope-coupling.md)。AC-7/AC-8の単一flow統合testとAC-11/AC-12のinstrumentation・manual evidenceは後続evidence PRの対象。

## Re-entry status

- 2026-09-16: baseline `4f555450bdf817a832b8827857b5af54f41913a8` (origin/main) 上で起草。#228 (PR #289 merge、implemented)、#204 (PR #322 merge、accepted・実装済み)、#205 (PR #325 merge、implemented) がすべてmainに存在することを確認済み。

## Current evidence

origin/main (`4f555450bd`) 時点の確認事実 (実装調査に基づく):

- **export生成の現状**: `organizer/integration/exchange/ExchangeInputAdapter.kt` `composeForExport` が `ProductionOrganizationInputComposer.composeFullOrganization()` 経由で `ExportInputs` を構築する (snapshot + full `TargetSet` + 解決済み分類 + user label + #203 signal)。`TargetSet.additions` / `runMode` はexchange系moduleのどこからも参照されない。`ContextExportBuilder.build` (`organizer/personalization/ContextExportBuilder.kt`) は `snapshot.items` のみを走査し、`APP_PAIR` / `SHORTCUT_LEGACY` / `Unknown` / `UnsupportedContainer` を `PreservedConstraints` 集計へ落とし、残りをplaced itemとしてexportする。
- **schema定数**: `ContextExportModels.kt` `ContextExportContract` に `SCHEMA_VERSION="personalization-context-v1"`、`INTENT_SCHEMA_VERSION="personalized-intent-v1"`、`MAX_EXPORT_ITEMS=512`、`MAX_EXPORT_BYTES=256KiB`、`MAX_INTENT_BYTES=128KiB`、`SESSION_TTL_MS=24h`。
- **session**: `ExportSession.itemRefs: Map<String, ItemId>` (placedのみ)。`AndroidExportSessionStore` (integration、`AtomicFile`、noBackupFilesDir、単一session)。
- **import検証**: `exchange/ExchangeImportPipeline.prepare/validate` → `IntentImportParser` (envelope 1 MiB → framing抽出) → `IntentCodec.decode` → session照会 → `SessionExportReconstructor.rebuild` (builderと同一の `toExportItemCore` をsession固定ref mapで再適用。label/usage非authority) → `IntentValidator.validate` (12 failure class、coverage不変条件、mobility矛盾検証)。
- **planner接続**: `IntentPlannerAdapter.project(validated)` が `session.itemRefs` (ref → `ItemId`) とexport itemsのroleから `PersonalizedIntentProjection.itemPreferences` を生成。`ManualOrganizationRun.runComposedPhase` が `composeFullOrganizationWithIntent` / `composeScopeComposedOrganizationWithIntent(operation.intent?.let(IntentPlannerAdapter::project))` を呼び、`OrganizationInput.intentPreferences` へ注入する。preference消費は `FullRunExecution` / `PlacementAllocator` に実装済み (`IntentPreferenceConsumptionTest` 等が存在)。
- **#228 run flow**: `ManualOrganizationRun.start` → `State.CandidateDetection` (`application.detectMissingAppCandidates()`) → `State.Selecting(runId, candidates)` → `confirmSelection(selection)` (ManualOrganizationRun.kt:396) で選択集合を確定し `runComposedPhase` へ。`DefaultOrganizationInputComposer.composeInternal(selection)` が選択を `CandidatePlanningIds.planningId` 付き `CandidateItem` の `additions` へ変換し (`OrganizationInputComposer.kt:256-265`)、`scopeComposedTargetsIdentity` でprovenance identityを計算し、stale選択は `StaleCandidateSelection` でfail-closed (同:245-255)。選択stateは `MissingAppSelectionState` (process-local、非永続)。
- **exchange導線のgate**: `ManualOrganizationPreferences.kt` (678-691行近傍) がexchange UIを `state is Idle || state is Cancelled` のときのみ提示する。import → `ExchangeFlowUi.import` → `run.start(trigger, intent)` (fresh run、`OrganizationOperationLease` gate、Busy時はtyped案内でzero-write)。
- **candidate分類**: `composeInternal` が選択候補をclassification evidence requestの対象に含める (#228 AC-13)。解決済み分類は `ExportInputs.resolvedCategories` としてexport生成へ渡せる形で既にcomposition resultに存在する。
- **既存test suite**: `tests/unit/app/lawnchair/organizer/personalization/` (44 exchange unit tests含む) + `integration/AndroidExportSessionStoreTest.kt`。

## Design

### Modules and interfaces

- **`organizer/personalization/` (契約v2拡張、既存module内のadditive変更)**:
  - `ContextExportModels.kt`: `SCHEMA_VERSION="personalization-context-v2"`、`INTENT_SCHEMA_VERSION="personalized-intent-v2"`、`ExportItemSubject` closed enum (`PLACED` / `CANDIDATE`)、`Mobility.CANDIDATE`、`ExportItem.subject` (全itemで必須)、candidate宛validator条件の拡張。
  - `ContextExportBuilder.kt`: `toExportItemCore` のcandidate partition拡張 — `inputs.targets.additions` を走査し、candidate item (kind `APP_OR_SHORTCUT`、`subject: CANDIDATE`、`mobility: CANDIDATE`、`category` は解決済み分類から、`pageAffinity`/`regionAffinity`/`groupSemantic` 省略、labelはtier制御、`usage` は #203 projection対象) をemitする。placed itemsとの全体順は決定的 (既存placed順 + candidateの検出順規則 (profile, label, component) 順)。`BuiltExport.session` のref map値をsealed型 (`Placed(ItemId)` / `Candidate(CandidateTarget.AppKey)`) へ拡張し、**export scopeのcandidate投影digest** (安定identity + availability + 解決済み分類のcanonical serializationに対するsha256、session-local・export文書に現れない) をsessionへ記録する。
  - `IntentValidator.kt`: candidate宛検証 — `CANDIDATE` refへの `preserve` → `MobilityContradiction`、`importance`/`desiredGroup`/`groupSemantic`/`pageAffinity`/`regionAffinity` は受理。coverage不変条件はexport refs全体 (placed + candidate) でそのまま成立。**`ScopeMismatch` (新13th class)** をfailure sealed familyへ追加。
  - `SourceContextIdentity.kt`: **無変更** (spec D-4)。
  - `IntentPlannerAdapter.kt`: candidate ref → candidate planning ID (`CandidatePlanningIds.planningId(CandidateTarget.AppKey)`) への解決を追加。既存placed ref → `ItemId` 解決は無変更。
- **`organizer/personalization/exchange/`**:
  - `ExchangeInputAdapter.kt`: **run内entry用の第2のcompose経路** `composeForExport(selection)` を追加 — 選択済み候補集合を受け取り、`ProductionOrganizationInputComposer.composeScopeComposedOrganization(selection)` と **同一のcanonical composition seam** から `ExportInputs` を得る (idle entryの `composeForExport()` は無変更)。両entryが同一adapter・同一projection述語を共有することをtype systemとcontract testで保証する。
  - `SessionExportReconstructor.kt`: session ref mapのcandidate entryを現在状態へ解決 (installed / launchable / AVAILABLE / snapshot未表現) し、candidate item core (固定projection) を再構築viewへ含める。解決不能candidateは `ScopeMismatch` (candidate無効化cause) へmapする。placed部分のparity契約は無変更。
  - `ExchangeImportPipeline.kt`: 失敗統合resultへ `ScopeMismatch` (cause detail付き) を追加 (17種 = v2 13 contract class + `FRAMING_*` 3種 + `INPUT_OVERSIZE`)。既存段階 (envelope → framing → decode → session → digest → reconstruct → validate) の順序は無変更。
  - `ScopeBindingGate.kt` (新規pure): spec D-2/D-4/D-5の判断部。入力: session記録のexport scope candidate集合とcandidate投影digest、run確定選択集合、composition時点のcandidate投影 (identity + availability + 解決済み分類)。判定: (1) 選択集合とexport candidate集合の **完全一致**、(2) 各candidateの解決可能性、(3) candidate投影digestの再計算照合。出力: `Pass` / `Mismatch(cause: 集合不一致[欠落/追加] | candidate無効化 | 投影不一致)`。run確定点 (confirm後のcomposition) とfresh run再構築pathの両方から呼ばれる単一の正本。
  - `ExchangePackageComposer.kt`: instruction部v2更新 — candidate itemsの説明 (「一部のアプリはまだhomeに配置されていないcandidateである。保持を指示せず、importance / grouping / page・region親和を提案してよい」) とv2 schema versionの明示。data部構造 (CONTEXT marker) は無変更。
- **`organizer/ui/` + `ManualOrganizationRun`**:
  - 選択surface (`MissingAppSelectionScreen` 近傍) にrun内exchange導線を追加: 「AI相談」明示開始 → freeze状態 (選択編集無効。status行でannounce) → 生成 (`ExchangeGenerationGate` 既存契約: activity session存在時はsession置換確認) → Pre-send Disclosure → transport (既存adapter流用) → import → **validated intentの当該runへの接続** (新typed run action。`State.Selecting` で1回のみ、単一active session宛のみ受理。fresh runを開始しない) → confirm → composition → `ScopeBindingGate` (完全一致 + candidate投影digest照合) → planning (intent投影込み)。
  - 中止: 未送信package取消は既存規則 (`ExportSessionStore.invalidate`) + freeze解除。
  - `ManualOrganizationRun` への変更は最小: `State.Selecting` でのintent attach actionと、`runComposedPhase` のscope-composed経路での `ScopeBindingGate` 評価 (違反時はcause detail付きtyped zero-write失敗で選択状態へ戻す)。既存state遷移・`OrganizationOperationLease`・preview/confirm/apply pathは無変更。
  - fresh run再構築path (idle import → `run.start(trigger, intent)`): 既存flowに、confirm後compositionでの `ScopeBindingGate` 評価を追加するのみ。再選択支援として選択surfaceがexport対象candidateの件数を案内表示する (自動選択はしない — #228 D-1維持)。idle宛intentでcandidateを選択して確定した場合は完全一致違反となり、run内entryでの再exportを案内する。
- **UI失敗表示**: 17種 (v2 13 contract class + `FRAMING_*` 3種 + `INPUT_OVERSIZE`) への拡張。strings は ja/en 両方。

### Data flow

```text
[run内entry]
MissingAppSelectionSurface (選択) → ユーザー明示「AI相談」
  → freeze (選択固定・UI状態、非永続)
  → ExchangeGenerationGate (既存) → composeForExport(selection) [read-only canonical composition]
      → ContextExportBuilder.build (v2: placed + candidate items、session記録にcandidate投影digest)
      → ExportSessionStore.save → ExchangePackageComposer (instruction v2)
      → Pre-send Disclosure → transport (既存)
  → [外部AI、app外]
  → import (同じsurface) → IntentImportParser → ExchangeImportPipeline (v2検証)
      → validated intentを当該runへ接続 (State.Selecting、attach 1回限定)
  → confirmSelection → composeInternal (既存canonical composition)
      → ScopeBindingGate (完全一致 + 解決可能性 + candidate投影digest照合)
          ├─ Pass → planning (intentPreferences: placed ItemId宛 + candidate planning ID宛)
          └─ Mismatch (集合不一致[欠落/追加] | candidate無効化 | 投影不一致)
                → typed失敗 `SCOPE_MISMATCH` (zero-write、選択状態へ戻す、再export案内)
[process death後]
idle import → run.start(intent) (既存fresh run) → 検出 → 再選択 (export対象件数の案内表示、自動選択なし)
  → confirm → ScopeBindingGate (完全一致。再現不能なら再export案内) → planning
[idle entry]
既存flow無変更 (scope = placedのみ、candidate集合空 → gate自明通過。
idle export後にcandidateを選んで確定した場合は完全一致違反 → 再export案内)
```

### Alternatives rejected

- **完全一致のscope binding (review Blocking 1対応で採用)**: 検討段階では包含 (⊇) 規則も検討したが、export後の候補追加を許すとAI未判断のcandidateが同一intent下でorganizeされ、本Issueの問題 (export scopeとrun scopeの不一致) を再現するため採用しない。process death後の再選択は件数案内表示で支援し、再現不能な場合は再export (安価) とする。
- **digest入力にcandidate投影を統合するoption**: placed側digest (`SourceContextIdentity`) にcandidateを追加すると、import時 (run無し・process death後) にdigest照合が成立しなくなるほか、v1互換のdigest定義変更を伴うため非採用 (spec D-4)。candidate投影digest (session-local) を別契約として新設し、役割を分離する。
- **`CandidateUnresolved` を独立failure classとするoption**: candidate無効化・集合不一致・投影不一致は救済action (再選択または再export) が共通するため、1つのclass (`SCOPE_MISMATCH`) + cause detailで十分。class分割は失敗表示面の増加のみを生むため採用しない (spec D-5)。
- **run-scoped固有のcandidate ID体系 (新namespace)**: sessionがref対応の唯一の保持者である以上、対応先種別の拡張で足りる。新ID体系は二重正本を生むため非採用。
- **v1 schemaのその場限りの拡張 (version bump無し)**: #204 accepted契約のimmutable semantic version規則に反するため非採用 (spec D-1)。
- **exchange UIをrun外に留め、選択のみ事前に行うoption**: 選択stateが非永続である以上、run外に選択を保持する正本が生まれ、単一scope正本の原則 (spec §1) に反する。run内entryの新設が最小変更である。
- **freeze無しで選択編集を許し続けるoption**: 「AI相談前にscope確定」の基本形が保証されず、generate時とconfirm時のscope差異が常態化する。中止すれば編集に戻れるため、freezeの摩擦はexchange利用者に限られる。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `organizer/personalization/` | v2 schema constants、`ExportItemSubject`、`Mobility.CANDIDATE`、builder candidate partition、session ref map sealed化、validator拡張 (`ScopeMismatch`)、`IntentPlannerAdapter` candidate解決 | #204契約v2の実装本体。privacy tier / coverage / mobilityの正本がこのpackage |
| `organizer/personalization/exchange/` | `composeForExport(selection)`、reconstructor candidate対応、pipeline失敗拡張、`ScopeBindingGate` (新規pure)、instruction v2 | scope couplingの純粋logic。framing・gateの所有は #205系exchange moduleの継続 |
| `organizer/integration/` | adapter接続 (run内entryのcomposition呼出し)、`AndroidExportSessionStore` のsealed ref map対応 | Android-free純粋部への漏出を防ぐintegration境界 |
| `organizer/ui/` + `ManualOrganizationRun` | 選択surfaceのrun内exchange導線、freeze UI、intent attach action、`ScopeBindingGate` 評価点、17種失敗表示 | flow ordering (spec §5) のUX本体 |
| `specs/204,205` Change history + `CONTEXT.md` + `DESIGN.md` | 契約拡張の記録、用語追加、gate表更新 | AGENTS.md正本規約 (同一PR群で更新) |
| test | unit (personalization/exchange/planner) + instrumentation (選択surface・統合) + contract/property (parity、privacy走査、決定性) | Test oracle (spec) |

既存の適用path (materialize / `ApplyAction.Insert` / availability再検証 / recovery) は **無変更** (#228契約の継続)。既存run mode・strategy意味論・planner保持判断も無変更。

## Migration and recovery

- DB schema変更なし。session storeのrecord formatはsealed ref map + candidate投影digestへの拡張 (app-private・backup対象外)。旧v1 session recordはschema識別によりimport不可 (TTL 24時間で自然失効。移行処理は不要 — 本spec受入から実装releaseまでの間にactivityなv1 sessionが存在し得ない単一ユーザー配布形態)。
- rollback: 機能導線を表示しない (既存 #205 と同様のfeature flag相当の導線制御を検討)。残余はsession fileのみ (TTL自然失効)。
- 運用中の回復: 選択変更による `SCOPE_MISMATCH` → 再選択または再export。candidate無効化 → 再検出からやり直し (#228 §6の継承)。配置構造変化 → `CONTEXT_STALE` 再export (既存)。freeze中のprocess death → fresh run再構築 (既存 #205 semantics + gate)。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | builder candidate partition unit test (含まれる/含まれない、zero-write) + 選択surface instrumentation test | unit + connected test |
| AC-2 | composition adapter contract test (両entryのscope内容同一性・選択変化伝播) + reconstructor parity test拡張 | unit test |
| AC-3 | privacy contract test (両tier走査: package/component/profile/planning ID不在、`subject`/`mobility` 値) | unit test |
| AC-4 | validator unit test (`UNKNOWN_REF` corpus: 未選択候補・新規install) | unit test |
| AC-5 | validator corpus (`MOBILITY_CONTRADICTION` / 受理field) + planner unit/property test (additions宛preference消費、既存保持判断・strategy意味論不変のproperty) | unit test |
| AC-6 | pipeline分類test (3段stale) + `ScopeBindingGate` unit test (完全一致Pass/欠落/追加/無効化/投影digest不一致・分類変化) + 失敗表示17種 UI test | unit + UI test |
| AC-7 | 空workspace統合test (export → import → plan → preview) + candidate分類変化test (空workspaceで投影digest不一致 → `SCOPE_MISMATCH`) | unit (planner/統合) + instrumentation |
| AC-8 | mixed workspace統合test (preference適用・`AddChange`/`MoveChange` 区別) | unit + instrumentation |
| AC-9 | 既存 #204/#205/#228 suite regression + idle entry gate適用test | unit test (全既存suite green) |
| AC-10 | v2 bump contract test (v1拒否、v2受入、content limits、生成時超過typed失敗) | unit test |
| AC-11 | freeze/中止/失効/process death模擬 → 再選択 → gate のrun flow統合test | instrumentation |
| AC-12 | a11y assertion + ja string解決test | unit/instrumentation + 手動 (TalkBack / large font) |

## Execution checklist

1. **現行挙動の再現test先行**: v1 export/import/coverage/mobilityの既存test緑を確認し、builder candidate非対応・idle-only導線の現行契約を固定する (regression oracle)。
2. **v2 schema拡張 (TDD)**: `ExportItemSubject` / `Mobility.CANDIDATE` / version constants → builder candidate partition (決定的順・label tier制御・usage projection・省略field) → session ref map sealed化。privacy contract test (AC-3) とv1拒否test (AC-10) を同時追加。
3. **validator拡張**: candidate宛条件表 (preserve拒否・受理field)、coverage (placed + candidate)、`ScopeMismatch` class追加。corpus test (AC-4/AC-5)。
4. **adapter/reconstructor**: `composeForExport(selection)` (canonical seam共有) + reconstructor candidate解決 + pipeline失敗拡張。parity test (AC-2)。
5. **`ScopeBindingGate`**: 純粋gate unit test (AC-6: 完全一致/欠落/追加/無効化/投影digest不一致) 先行。candidate投影digestのcanonical serialization (決定性・process跨ぎ安定性) のunit testを含む。
6. **`IntentPlannerAdapter` candidate解決** + planner消費確認 (既存 `FullRunExecution`/`PlacementAllocator` のpreference機構でadditions宛preferenceが到達することのunit/property test、AC-5)。
7. **run/UI統合**: 選択surface導線・freeze・attach action・gate評価点・17種失敗表示・strings (ja/en) (AC-11/AC-12)。
8. **統合test**: 空workspace (AC-7)、mixed (AC-8)、process death模擬、idle entry regression (AC-9)。CI class filterへの追加。
9. **正本更新**: specs/204, 205 Change history、`CONTEXT.md` 用語、`DESIGN.md` gate行を同じPRで更新。
10. **PR evidence記録**: 実行した検証コマンドと結果、未確認範囲をPR本文へ記録。
