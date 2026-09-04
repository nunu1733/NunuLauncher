# Implementation Plan: Selectable versioned layout-strategy catalog

> Issue: #182
> Spec: [spec.md](./spec.md)
> Status: draft
> ADR: [0012-versioned-layout-strategy-catalog](../../docs/adr/0012-versioned-layout-strategy-catalog.md) (proposed; flips to accepted together with this spec)

## Current evidence

確認済みの現行振る舞い (2026-09-04, `main` @ `5fdab48082`)。実装開始時に再検証する。

- `lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt:189` — `OrderingPolicy` は単一値 `CANONICAL_V1`。`tests/unit/app/lawnchair/organizer/planning/ContractShapeTest.kt:602` が `entries.size == 1` を主張する。
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt` — `placeFullRun` が folder形成 (`formFolderGroups`)、unit materialization、page grouping、allocationを1関数に結合。内部 `Allocator` (`allocatePreferred` = preferred page→新規page、`allocateCapturedThenNew` = 全captured page走査→新規page) と `findRowMajorFirstFit` (左上row-major) を同ファイルprivateで保持。
- `lawnchair/src/app/lawnchair/organizer/planning/PlanningResult.kt` — `PreserveReason` はclosed enum (`RESERVED_REGION` 含む、Issue #185/ADR-0010)。`PlanningResult` のechoは `revision`/`ruleVersion`/`taxonomyVersion`。
- `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt` — bundle v1 (`organization-policy-v1`/`rule-v1`)、`canonicalRepresentation()` が `;rule.ordering=CANONICAL_V1` を含みdigestに参加。`validate()` はversion束縛とS3/S4空tableを検査。
- `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` — immutable built-in bundleの唯一のpolicy authority (ADR-0007)。
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt:153` — `bundleSource.readActive()` → 非Readyは `NotReady`。A/E1/B/E2のread-after-validate retry (spec 83) を実装済み。`CompositionModels.kt` が `InputProvenance`/`PolicyInputIdentity` を所有。
- `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt:137` — `State.Preview(summary, details)` (spec 194)。strategy選択UIの着地点。
- 推測 (未確認): selection storeの物理format。CategoryOverrideStoreと同family (schema version + generation + digest、atomic access) を前提にするが、実装Issueでstorage formatを確定する。Rule Managementはformatをimplementation detailとして所有する (ADR-0007 §3 先例)。

## Design

### Modules and interfaces

**planning public surface (spec 10 delta、受入PRで spec 10/12 へ反映):**

```kotlin
@Suppress("ktlint:standard:class-naming")
sealed interface StrategyId { /* 非空String backing、opaque */ }
// 実際は spec 10 のhandle規約に従う value class/型エイリアス。
// v2 catalog: "CANONICAL_PAGE_COMPACT_V1", "STABLE_PAGE_TIDY_V1",
//             "GLOBAL_COMPACT_V1", "BOTTOM_FIRST_V1", "CATEGORY_CONTIGUOUS_V1"

data class OrganizationStrategy(
    val id: StrategyId,
    val version: StrategyVersion,      // 非空String、"v1"
)

data class RuleSemantics(
    val version: RuleVersion,                 // "v2"
    val folderPolicy: FolderPolicy,
    val dockPolicy: DockPolicy,
    val overflowPolicy: OverflowPolicy,
    val fallbackCategoryPolicy: FallbackCategoryPolicy,
    val organizationStrategy: OrganizationStrategy,   // orderingPolicy を置換
)

data class PlanningResult(
    val revision: RevisionId,
    val ruleVersion: RuleVersion,
    val taxonomyVersion: TaxonomyVersion,
    val organizationStrategy: OrganizationStrategy,   // echo
    val outcome: PlanningOutcome,
)
// PreserveReason へ STRATEGY_PRESERVED を追加
// (precedence: ... > NON_TARGET > STRATEGY_PRESERVED > STRUCTURAL > ALREADY_CANONICAL)
```

- V-20 を拡張: 受諾済み `RuleVersion` でもstrategy id/versionがcatalog外なら `INVALID_RULES`。
- `ContractShapeTest` の `OrderingPolicy.entries.size == 1` 主張は `OrganizationStrategy` のclosed catalog主張へ置換する。

**rules (Rule Management):**

- `OrganizerPolicyBundle` v2: `POLICY_BUNDLE_VERSION = "organization-policy-v2"`, `RULE_VERSION = RuleVersion("v2")`。v2は `LayoutStrategyCatalog(supported: List<OrganizationStrategy>, default: OrganizationStrategy)` をbundle contentに追加し、`canonicalRepresentation()` に `;strategy.catalog=...;strategy.default=...` を加える。taxonomy/classification/target versionは不変。
- `interface LayoutStrategySelectionSource` (Rule Management配下): typed snapshot (`selection: OrganizationStrategy?`, `schemaVersion`, `generation`, `digest`)。読み取り失敗・unsupported schema・digest不一致はfail-closed値を返す。CategoryOverrideStoreと同familyのatomic access testを持つ。

**integration (composer):**

- `PolicySourceKind.LAYOUT_STRATEGY_SELECTION` を追加。composerはcut内でselection snapshotをA/Bの2回読み、`A.identity == B.identity` を検証する。bundle catalogに対して検証し、unsupported/removed/corruptは既存の `NotReady(SourceUnreadable | UnsupportedVersion | ContradictorySource)` familyへ写像する。`RuleSemantics` 投影時にbundle defaultまたは選択値を `organizationStrategy` へ載せる。

**planning internal seam:**

```kotlin
internal sealed interface CellTraversal { TOP_LEFT_ROW_MAJOR; BOTTOM_UP_ROW_MAJOR }
internal sealed interface PageScope { PREFERRED_THEN_NEW; CAPTURED_THEN_NEW }
internal data class StrategyDefinition(
    val identity: OrganizationStrategy,
    val createsFolders: Boolean,          // STABLE_PAGE_TIDY / CATEGORY_CONTIGUOUS は false
    val eligibleUnitFilter: (CapturedItem, /* span */ GridSpan) -> Boolean,
    val unitOrder: UnitOrder,             // CANONICAL_TIE_BREAK | CAPTURED_VISUAL (page-local | global)
    val pageScope: PageScope,
    val cellTraversal: CellTraversal,
)
```

- `PlanningPlacement.placeFullRun` を「shared materialization (constraints/unit化/preservation)」と「strategy dispatch」へ分割する。`Allocator` は `findRowMajorFirstFit` にcell traversal引数を追加するのみで、occupancy/bounds実装は1つを保つ。
- `STABLE_PAGE_TIDY_V1`: pageごとにeligible `1×1` unitをliftし、visual order `(cell.y, cell.x, ItemId)` でrow-major first-fitへ再配置。既存folderと非`1×1` movableは固定constraintとして `STRATEGY_PRESERVED`。
- `GLOBAL_COMPACT_V1`: 全unitをglobal visual order `(PageOrder, PageId, y, x, ItemId)` で `allocateCapturedThenNew` へ通す。新規folderは `(preferred page key, NewFolderOrdinal)` 順でcaptured-position unitの後に配置。既存のpage削除policyには触れない。
- `BOTTOM_FIRST_V1`: canonical順序のまま、allocatorのtraversalをbottom-upへ切替 (`rows - span.height` から `0` へ降順)。
- `CATEGORY_CONTIGUOUS_V1`: page-local、`(profile, category(fallback last), target key, ItemId)` 順。既存folder固定。
- 新戦略は純粋関数として `DeterministicOrganizationPlanner` 経由でのみ到達可能。strategy実装がvalidation/allocator safety/result validationをbypassする経路を追加しない (`PurityGuardTest` をstrategy sourceにも適用)。

**byte-equivalence oracle:**

- 受入時点 (extraction子Issue開始時のhead) で、spec 11 harness corpus + `PlannerGeneratedPropertyTest` 64-case (seed `0x4E554E55L`) + spec 12 固定fixture (L-13〜L-17等) の `PlanningResult` から `organizationStrategy` echoを除いたcanonical payload (placements/newPages/newFolders/categories/warnings) をgolden fileとしてpinする。
- extraction後、`CANONICAL_PAGE_COMPACT_V1` 選択で同一golden fileが通ることをtestで証明する。echoを除くのは、追加されたstrategy echoがmetadataでありlayout観測ではないため (spec §Compatibility requirement)。

### Data flow

compose (bundle v2 + selection store + capture、A/E1/B/E2 cut拡張) → `OrganizationInput` (`RuleSemantics.organizationStrategy`) → `plan` (strategy dispatch → shared allocator) → `PlanningResult` (echo) → spec 194 preview (rationale/preserve reasonは `Planned` 由来のまま、`STRATEGY_PRESERVED` が `PreservedChange.reason` へ流れる) → spec 13 apply/recovery (無変更)。

strategy選択変更はcompose時点で確定する (stable cut)。run途中の選択変更は次のcompose cycleで効く。preview中の選択変更はspec 52通り新compose/plan cycle (fresh capture) を開始する。

### Alternatives rejected

- `OrderingPolicy` enum拡張 — 名前と形が実際のsemantics (folder/page/cell) より狭い。ADR-0012。
- 組合せ式public toggle — interfaceとproperty-test状態が爆発する。ADR-0012。
- selection未対応時にdefaultへsilent fallback — 無確認のlayout決定になるため禁止。ADR-0012。
- `GLOBAL_COMPACT_V1` のtie-break順序 (Issue案) — cross-page移動後の再計画で順序が変わりINV-8違反。visual orderへ修正 (spec冪等性引数参照)。
- 最小cost optimizer — v1では不要。`STABLE_PAGE_TIDY_V1` の安定compactionで十分。

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `planning/OrganizationInput.kt` | `OrganizationStrategy`/`StrategyId`/`StrategyVersion` 追加、`RuleSemantics.organizationStrategy` 化 | policy identityの唯一の公開inputs場所 |
| `planning/PlanningResult.kt` | echo field、`PreserveReason.STRATEGY_PRESERVED` | result自己記述性とtruthful rationale |
| `planning/PlanningPlacement.kt` | shared materialization抽出、strategy dispatch、allocator traversal/page scope引数 | strategy固有処理の唯一の実装場所 |
| `planning/PlanningValidation.kt` | V-20拡張 (catalog外strategy) | invalid rules familyの一元化 |
| `rules/PolicyModels.kt` | bundle v2、strategy catalog/digest、validation | policy authorityはRule Management (ADR-0007) |
| `rules/` 新規 selection store | schema/generation/digest付き読み取りsource | selectionの唯一のowner |
| `integration/OrganizationInputComposer.kt` + `CompositionModels.kt` | cut拡張、catalog検証、`PolicySourceKind` 追加 | planner入力の唯一の合成点 (spec 83) |
| `ui/ManualOrganizationRun.kt` + preferences UI | strategy picker、Summary のstrategy identity、replan | selection UIの着地点 (spec 52/194/195継承) |
| `organizer-diagnostics.md` | strategy identityのversion identifier allowance明文化 | diagnostics契約の正本 |
| spec 10/12, `CONTEXT.md`, `DESIGN.md` | 受入PRでdelta反映 | 正本を先に直す規約 |

## Migration and recovery

- bundle v2/`rule-v2` はapplication所有のimmutable artifact。in-place migrationなし (ADR-0007 §8)。旧bundleからの移行はbinary更新のみ。
- selection store: schema version 1、generation、digest。first-run不在 = 定義済みdefault状態。corrupt/unsupported/newerはfail-closedで `NotReady`。backup/restore対象外 (override storeと同じv1判断)。
- downgrade: selection storeを知らない旧binaryは自前のv1 policyで動作し、storeを読み書きしない。新schemaを読むはずのbinaryがunsupported schemaに当たった場合はfail-closedで書き換えない。
- layout dataへのmigration影響なし: strategy適用結果のlayoutは既存recovery point契約 (spec 13) で復旧可能。strategyはrecovery点に無関係。

## Verification

Epic全体の受入条件 (spec AC-1〜AC-15) を子Issueへ割り当てる。各子Issueは自spec/planを持ち、この表の該当行を満たす。

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1/2 (spec受入, FR-016) | このspec/plan + requirements.md更新のreview | — |
| AC-3/AC-7 (selection fail-closed) | selection store unit test (corrupt/newer/removed/generation)、composer `NotReady` mapping test、downgrade/fail-closed test | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-4 (単一seam/purity) | `PurityGuardTest` 拡張 (strategy sourceも対象)、`OrganizationPlannerSeamTest` | 同上 |
| AC-5 (byte-equivalence) | golden oracle corpus test (harness + property 64-case + spec 12 fixtures) | 同上 (`*PlannerContractHarnessTest*`, `*PlannerGeneratedPropertyTest*`) |
| AC-6 (provenance/echo) | bundle digest test、result echo test、diagnostics projection test | 同上 |
| AC-8/AC-13 (UI) | strategy picker/previewのCompose test、TalkBack/font-scale/switch test、recreation/stale test | `connectedLawnWithQuickstepGithubDebugAndroidTest` (spec 52パターン) |
| AC-9/AC-11 (共有invariants/戦略分離) | harness + property testを全strategyで実行するcross-strategy runner | 同上 unit test |
| AC-10 (fixture網羅) | strategy別fixture (spec scenario: 空home/full home/widget/folder/app pair/lock/page/profile/category fallback/rotation/tablet/two-panel/invalid selection) | 同上 |
| AC-12 (性能) | spec 12性能sample protocolをstrategy×item/page/device matrixで実施 (budget assertionなし) | `*PlannerBenchmarkTest*` 拡張 |
| AC-14 (実機) | strategy毎のbefore/preview/after/recovery evidenceをPRへ記録 | 物理端末 (building guide環境) |
| AC-15 (高リスクgate) | `final-status` CI + `docs/assessment/pr-<n>-*.md` | `.github/workflows/high-risk-gate.yml` |

共通command (AGENTS.md検証済み):

```bash
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew assembleLawnWithQuickstepGithubDebug
```

含めるべき観点: unit/contract (各strategyのtie-break・境界・overflow)、property (共有invariants)、failure injection (selection store corruption、cut不一致)、UI/accessibility (picker・preview・a11y)、performance (strategy matrix)、migration (bundle v2・selection downgrade)。

## Documentation updates

- [ ] spec status/history (本spec受入時: `accepted`、spec 10/12へdelta反映とchange history追記)
- [ ] `CONTEXT.md` (layout strategy 用語)
- [ ] `DESIGN.md` §4.1 (strategy seamの言及) — 必要最小限
- [x] ADR-0012 (本PRでproposed、受入時accepted)
- [ ] `docs/product/requirements.md` (FR-016)
- [ ] `docs/engineering/organizer-diagnostics.md` (strategy identity allowance、最初のstrategy shipping子Issueで)

## Execution checklist

- [ ] Current behavior reproduced (golden oracle captureをextraction子Issue冒頭で実施)。
- [ ] Tests fail for the missing behavior (各子Issueで先にtestを追加)。
- [ ] Minimal implementation completed (子Issue単位の縦切り)。
- [ ] Migration/recovery verified (selection store fail-closed、bundle v2 downgrade)。
- [ ] Full relevant verification completed (上表)。
- [ ] PR evidence and remaining risks recorded (高リスクPRは独立audit)。
