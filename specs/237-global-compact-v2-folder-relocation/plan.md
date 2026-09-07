# Implementation Plan: Compact existing 1×1 folders across pages (GLOBAL_COMPACT_V2)

> Issue: #237
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- `LayoutStrategyRegistry.GLOBAL_COMPACT_V1` (`lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt`): `eligibleUnitFilter` は 1×1 `APPLICATION`/`DEEP_SHORTCUT` のみ。既存 `FOLDER` unit は eligible 外。
- `FullRunExecution.executeGlobalCompact` (同 `FullRunExecution.kt`): filter 外の movable item を `STRATEGY_PRESERVED` で元位置 `markOccupied`。既存 folder は 1×1 でも cross-page に参加しない。2026-09-06 実機観察 (Issue #237) と一致。
- `PlanningPlacement.place`: FullOrganization では naturally preserved のみ初期 occupancy に入れ、movable (既存 1×1 folder を含む) は lift される。よって eligible filter に `FOLDER` を加えるだけで folder は mover stream に参加できる。
- `FolderFormation.formFolderGroups` / `partitionMembers`: per-(profile, category) で独立処理。group 化から漏れた残余は (a) minGroupSize 未満 / (b) fallback category / (c) capacity < minGroupSize のいずれか → formation は replan 安定 (spec 冪等性証明 第3段の根拠)。
- `Allocator.allocateCapturedThenNew` (`PlacementAllocator.kt`): captured page を `PageOrder` 順に first-fit、満杯なら new page。1×1 mover の自身の cell は必ず空き cell に含まれるため singleton stream は overflow せず、new folder は member 縮約 (≥2 member → 1 unit) で cell 需要が減るため overflow も起きない (防御 branch のみ)。
- 再現: `GlobalCompactStrategyTest.existingFoldersNeverCompactCrossPage` が V1 挙動 (folder 固定) を normative として固定。

## Design

### Modules and interfaces

外部 seam `OrganizationPlanner.plan(OrganizationInput)` は不変。変更はすべて planning module 内部と bundle/UI 媒体層:

- `LayoutStrategyRegistry`: `GLOBAL_COMPACT_V2` の `StrategyId` と `StrategyDefinition` を追加。`eligibleUnitFilter` に 1×1 top-level `FOLDER` を加える。`placeFullRun` は新 executor `FullRunExecution::executeGlobalCompactV2` へ接続 (executor 分岐は宣言 field でなく identity dispatch を避けるため、`UnitOrdering.CAPTURED_VISUAL_GLOBAL` を V1 と共有しつつ `StrategyDefinition` の `placeFullRun` 参照で区別する)。
- `FullRunExecution.executeGlobalCompactV2`: V1 executor と同型。差分は (1) folder formation candidate を `kind != FOLDER` に限定、(2) moved workspace unit の code を kind で選択 (`FOLDER` → `PlacementCode.FOLDER_UNIT`、それ以外 → `SINGLE_PLACEMENT`)、(3) V1 executor は無変更。
- `PolicyModels.OrganizerPolicyBundle.POLICY_BUNDLE_VERSION`: `organization-policy-v2.4` → `organization-policy-v2.5`。
- `BuiltInOrganizerPolicyBundleSource`: `runtimeSupported` へ `GLOBAL_COMPACT_V2` 追加 (V1 は残す)。digest は canonical 内容から自動再計算。
- `ManualOrganizationPreferences` + `lawnchair/res/values{,-ja}/strings.xml`: V2 の name/description 追加、V1 の description を「既存 folder は移動しない」と正直に更新。
- 適用/recovery/writer/provenance/selection store は無変更 (folder 移動は既存 workspace write 経路、selection store schema v1 のまま)。

### Data flow

capture → composer (bundle v2.5 を読み `RuleSemantics.organizationStrategy` へ反映) → `plan` → `executeGlobalCompactV2`:

1. fixed (naturally preserved + non-1×1 movable) を元位置 `markOccupied`。
2. formation candidate = eligible のうち `APPLICATION`/`DEEP_SHORTCUT` のみ。`formFolderGroups` で new folder 群を形成。
3. workspace units = eligible − new-folder members (既存 1×1 folder を含む) を global captured visual order で `allocateCapturedThenNew`。
4. new folder を singleton stream の後ろに `(preferred page key, NewFolderOrdinal)` 順で配置。
5. folder member は既存どおり `Preserved{STRUCTURAL}` (planner は workspace item として扱わない)。

### Alternatives rejected

- **V1 の無言変更 (eligibility 差し替え)**: ADR-0012 identity policy 違反。spec 182 が新 ID を要求済み。
- **V1 廃止 + V2 置換**: V1 選択の fail-closed (再選択強制) が発生する。spec は共存を採用したため不採用。
- **V2 を `UnitOrdering` 新 enum 値で分岐**: 順序付けは V1 と同一のため、ordering family を複製すると seam が太る。`placeFullRun` 参照の違いだけで十分。
- **形成済み folder を次回以降 fixed 化する永続 provenance**: 永続 state 追加の設計負荷に対し、formation replan 安定性により不動点が成立するため不採用 (spec 証明参照)。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/planning/LayoutStrategyRegistry.kt` | `GLOBAL_COMPACT_V2` 登録 | catalog はここだけ |
| `lawnchair/src/app/lawnchair/organizer/planning/FullRunExecution.kt` | `executeGlobalCompactV2` 追加 (V1 無変更) | strategy semantics は executor 内部 |
| `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt` | bundle version v2.5 | ADR-0007 §8 |
| `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` | runtimeSupported 追加 | bundle 正本 |
| `lawnchair/res/values{,-ja}/strings.xml` | V2 copy 追加・V1 copy 更新 | UI 正本 |
| `lawnchair/src/.../ManualOrganizationPreferences.kt` | V2 mapping | picker 表示 |
| `tests/unit/.../GlobalCompactStrategyTest.kt` | V2 fixture 追加 | AC-2〜AC-6 |
| `tests/unit/.../BuiltInOrganizerPolicyBundleSourceTest.kt` | v2.5 期待値 | bundle 契約 |
| `tests/organizer-instrumentation/.../StrategyPickerInstrumentationTest.kt` | V2 行追加 | AC-9 |
| `CONTEXT.md` / `docs/product/requirements.md` | domain language / traceability | spec 承認時更新 |

## Migration and recovery

- `organization-policy-v2.5` は application-owned artifact 変更。各 binary は自身の bundle を読むため in-place migration なし (ADR-0007 §8)。
- selection store schema は v1 のまま。persisted `GLOBAL_COMPACT_V1` selection は V1 が catalog に残るため引き続き有効。
- downgrade: v2.5 binary で V2 を選択 → v2.4 binary では selection-layer `NotReady` (fail-closed、既存 path)。re-upgrade で再検証。
- layout data: planner/writer の safety invariants は無変更。recovery point は strategy 非依存。apply → recapture → replan 冪等性は spec 証明 + property suite で担保。

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-2 | `GlobalCompactStrategyTest` V2: 既存 1×1 folder が前方 page 空き cell へ `Moved{FOLDER_UNIT}` | `:tests` unit test (JVM) |
| AC-3 | V2 mixed-movers fixture + `CrossStrategyCorpusTest` (V2 が registry 経由で自動対象) | 同上 |
| AC-4 | folder identity / member / profile isolation assertion (`STRUCTURAL` 不変) | 同上 |
| AC-5 | formation → apply → recapture → replan → 空 diff、形成済み folder は `Preserved{ALREADY_CANONICAL}` | 同上 |
| AC-6 | preview projection: `FOLDER_UNIT` wording 経由で folder 移動行が表示 | 既存 preview unit/instrumentation test |
| AC-7 | V1 既存 test 群 (`GlobalCompactStrategyTest` V1 case) 無修正 pass | 同上 |
| AC-8 | `BuiltInOrganizerPolicyBundleSourceTest`: v2.5 + coherence equality | 同上 |
| AC-9 | `StrategyPickerInstrumentationTest` に V2 行 | organizer-instrumentation (実機/エミュレータ) |
| AC-10 | 実機 3-page fixture で before/preview/after evidence | 手動 (Issue/PR に記録) |
| AC-11 | downgrade `NotReady` | 既存 selection test + plan 契約 |
| AC-12 | `final-status` CI + 独立 audit | GitHub Actions / `docs/assessment/` |

含めるべき観点: unit/contract (planner seam)、property (`CrossStrategyCorpusTest` の determinism/idempotence)、bundle 契約、UI/instrumentation (picker/preview)、failure injection (`AllocationFault.FAIL_ALLOCATION` は既存 suite が担保)。

## Documentation updates

- [x] spec status/history (accepted + correction 記録済み)
- [ ] CONTEXT.md (strategy-fixed unit 追加)
- [ ] DESIGN.md — 影響なし (catalog member 追加は DESIGN の記述粒度以下)
- [ ] ADR — 不要 (versioning 判断は spec 内で記録、ADR-0012 の適用例)
- [ ] requirements.md — FR-016 status に spec 237 を追記

## Execution checklist

- [ ] Current behavior reproduced. (既存 V1 test が緑であることで担保)
- [ ] Tests fail for the missing behavior. (V2 fixture を先に追加し、登録前は fail することを確認)
- [ ] Minimal implementation completed.
- [ ] Migration/recovery verified. (bundle version test + selection 継続性)
- [ ] Full relevant verification completed.
- [ ] PR evidence and remaining risks recorded.
