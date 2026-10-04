---
issue: "#182"
status: accepted
requirements:
  - FR-001
  - FR-003
  - FR-006
  - FR-010
  - FR-015
  - FR-016
  - NFR-002
  - NFR-003
  - NFR-004
  - NFR-006
  - NFR-007
  - NFR-009
  - NFR-010
  - NFR-011
  - NFR-012
risk:
  - layout-data
  - migration
updated: 2026-09-05
---

# Selectable versioned layout-strategy catalog

> Status: accepted — Epic 正本spec。本specの受入により初期strategy集合・既定・selection契約・内部seamが確定した。source実装は planned child Issues (本文「Child issues」) へ分割し、最初の実装縦切りは child 2 (versioned strategy-selection contract) である。

## Problem

The organizer MVP has one built-in planning policy. `DeterministicOrganizationPlanner` classifies movable apps/deep shortcuts, forms same-profile/category folders, preserves locked/unsupported regions, and allocates units deterministically in top-left row-major order under `OrderingPolicy.CANONICAL_V1`. Users cannot choose an organization intent: when classification yields few useful groups, the result is perceived as little more than upper-left compaction. The safety/application machinery is valuable, but the produced layout needs meaningful, selectable alternatives without weakening planner or application safety contracts.

## Outcome

A curated, versioned, user-selectable catalog of built-in layout strategies behind the unchanged external seam `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult`:

- `CANONICAL_PAGE_COMPACT_V1` preserves the exact current `CANONICAL_V1` observable behavior as the compatibility baseline and regression oracle.
- `STABLE_PAGE_TIDY_V1` and `BOTTOM_FIRST_V1` ship as the first two new strategies, both computed exclusively from already-authorized local inputs.
- `GLOBAL_COMPACT_V1` and `CATEGORY_CONTIGUOUS_V1` are accepted catalog members implemented in later child issues after the baseline ships (their behavioral definitions are normative here; their delivery order is not).
- The selected strategy is a policy input: carried in `RuleSemantics`, provenance-bound through its own selection-snapshot identity in `InputProvenance` (the immutable bundle digest covers the runtime-supported catalog and default, never the user selection), echoed in the result and diagnostics, with fail-closed selection handling.
- The manual flow lets the user choose a supported strategy and previews the effective strategy identity and its consequences (move counts by scope, new folders/pages, preserved-by-strategy items, warnings) through the existing spec 194/195 preview seams.

`OrganizationPlanner.plan(OrganizationInput)` remains the sole external planning interface. Strategy implementations, grouping, unit ordering, page scope, and cell traversal are internal seams tested through the public interface.

## Candidate comparison and accepted initial set

The Issue #182 candidate table (canonical compact / preserve-and-tidy / category focused / alphabetical / reachability first / profile separated / balanced spacing / routine / user templates) was evaluated against: user value, required inputs, determinism/idempotence risk, compatibility/migration cost, and current-input feasibility. Alphabetical, usage-based, handedness-aware, and template/zone strategies require display labels, usage signals (FR-013), handedness preference, or versioned user-authored rules (FR-012/D-009) that `OrganizationInput` does not carry; they are not catalog candidates and this spec does not reinterpret them. `CATEGORY_CONTIGUOUS_V1` is the only accepted strategy that depends on a folder-policy decision; it is accepted here with explicit decisions (below) and scheduled after the baseline child issues.

Accepted spec-level catalog for strategy-catalog v1 (IDs are normative once this spec is accepted; each ID is an immutable semantic identity — renaming requires a new spec change):

| ID | Intent | Folder policy | Eligible movable units | Unit order | Page scope | Cell traversal |
|---|---|---|---|---|---|---|
| `CANONICAL_PAGE_COMPACT_V1` | compatibility baseline / rollback oracle | identical to current behavior | current movable set (existing folders, new folders, movable singletons incl. non-`1×1`) | current existing-folder → new-folder → singleton canonical order with current tie-breaks | captured/preferred page, then new pages | top-left row-major first-fit |
| `STABLE_PAGE_TIDY_V1` | close holes with least surprising movement | create no folders; never rewrite existing folder children | available, unlocked, top-level `APPLICATION`/`DEEP_SHORTCUT` with `1×1` span | per page: captured visual order `(cell.y, cell.x, ItemId)` | stay on captured page; never create or cross pages | earliest free row-major `1×1` cell on that page |
| `GLOBAL_COMPACT_V1` | minimize occupied early pages | canonical grouping/partition policy applied to eligible `1×1` candidates only | movable `1×1` singletons only; otherwise-movable non-`1×1` singletons and all existing folder units are `STRATEGY_PRESERVED` fixed units | global captured visual order `(PageOrder, PageId, cell.y, cell.x, ItemId)` over eligible units; new folders after them by `(preferred page key, NewFolderOrdinal)` | scan all captured pages by `PageOrder` (≈ `allocateCapturedThenNew`), then new pages | top-left row-major first-fit |
| `BOTTOM_FIRST_V1` | visibly different geometry from device dimensions only | identical to `CANONICAL_PAGE_COMPACT_V1` | current movable set | current canonical order | captured/preferred page, then new pages | bottom-first: candidate top-left `y` from `rows - span.height` down to `0`, `x` left-to-right |
| `CATEGORY_CONTIGUOUS_V1` | categories visible as contiguous icon groups | create no new folders; existing folders preserved as fixed units | available, unlocked, top-level `1×1` `APPLICATION`/`DEEP_SHORTCUT` | per page: `(profile, category with fallback last, canonical target key, ItemId)` | page-local; categories never pulled across page boundaries | top-left row-major first-fit |

**Spec-level catalog vs runtime-supported set.** This table is the spec-level accepted catalog. The active bundle additionally declares a **runtime-supported set**: only strategies whose planner implementation exists on that mainline. Every intermediate mainline (each independently mergeable child issue) declares exactly the implemented strategies, so a merge never creates a strategy that is bundle-supported but unplannable. Enabling a newly implemented strategy on a later child mainline is a policy content change and therefore publishes a **new bundle semantic version/generation and digest** per ADR-0007 §8 (e.g. `organization-policy-v2.1`); `rule-v2`, taxonomy/classification/target versions, and the selection-store schema do not change per enablement. The UI offers exactly the runtime-supported set. Every binary's bundle must satisfy the catalog coherence invariants: `runtimeSupported ⊆ implemented internal strategy IDs`, `default ∈ runtimeSupported`, and — enforced exactly — `runtimeSupported == runtime-enabled implemented strategy IDs`, so a strategy can never be offered by the bundle while unknown to the planner. This is a required contract test in the selection child issue and in every strategy child issue.

Default strategy: `CANONICAL_PAGE_COMPACT_V1`. It is the compatibility control for product evaluation and the rollback oracle.

Category-contiguous folder decisions (closing the Issue's open product point): this strategy creates no titled/category folders and does not imply any `NewFolder` contract change; expressing category value through better folders is separate work that must not be implied by this ID.

Compatibility requirement: `CANONICAL_PAGE_COMPACT_V1` produces a byte-equivalent canonical plan payload (placements, new pages, new folders, categories, warnings) to the current `CANONICAL_V1` implementation. The strategy identity echo is additional metadata and is excluded from the equivalence payload. The equivalence oracle is a **golden corpus pinned from a pre-#182 baseline commit**: the accepted `main` head immediately before the first #182 source change is recorded in the extraction child issue (currently `main@5fdab48082`; if `main` has advanced when implementation starts, the accepted pre-#182 head at that time is pinned instead) and its outputs over the accepted corpus (spec 11 harness corpus, generated property cases with seed `0x4E554E55L`, and the fixtures fixed in spec 12) are stored as golden files. Both the selection child issue (which introduces the registry/adapter dispatch) and the extraction child issue must pass the **same pinned golden corpus**, so a regression introduced in the selection child cannot be silently promoted into the oracle by a later capture.

Idempotence arguments per strategy:

- `CANONICAL_PAGE_COMPACT_V1`: unchanged current behavior; existing INV-8 evidence.
- `STABLE_PAGE_TIDY_V1`: after one run, captured visual order equals target traversal order on every page, so the next run moves nothing.
- `GLOBAL_COMPACT_V1`: all movable units are `1×1` singletons and every non-singleton movable (non-`1×1` singletons, existing folders) is fixed, so the strategy is a sorted assignment — units in captured visual order consume the sorted list of free cells (page order, then row-major), and newly formed folders take the remaining free cells in sorted order after them. After materialization the captured visual order of the units equals that same sorted order and the formed folders sit at the tail of it, so a replan reproduces the assignment exactly and moves nothing. (The earlier claim that heterogeneous spans are idempotent under positional order is withdrawn: with a non-`1×1` unit and a fragmented fixed obstacle, first-fit reorders the visual sequence across replans — see the normative fixture below — so cross-page movers are restricted to `1×1`, and non-`1×1`/folder movable units are `STRATEGY_PRESERVED`. A folder-compacting variant would be a new strategy ID requiring its own complete proof.)
- `BOTTOM_FIRST_V1`: deterministic unit order plus deterministic bottom-first candidate order yields the same targets on replan.
- `CATEGORY_CONTIGUOUS_V1`: page-local identity-based ordering is invariant under the placement (page membership never changes), so a replan reproduces the same order and targets.

Strategy-preserved rationale: when a strategy intentionally keeps otherwise movable items fixed (e.g. non-`1×1` movable items under `STABLE_PAGE_TIDY_V1`, existing folders under `CATEGORY_CONTIGUOUS_V1`), the planner reports a truthful dedicated preservation reason (`PreserveReason.STRATEGY_PRESERVED`), never `ALREADY_CANONICAL` and never `NON_TARGET`.

## Scope

- Versioned strategy selection contract: `RuleSemantics` carries the selected strategy identity; the policy bundle version bumps to `organization-policy-v2` and `rule-v2`; the bundle digest covers the runtime-supported catalog and default, and the user selection carries its own provenance identity; unsupported/corrupt/newer/removed selection fails closed with a typed non-write result.
- Internal planning seam: extract shared input materialization and the shared constraint-aware allocator; dispatch strategy-specific grouping/order/page-scope/cell-traversal from a curated internal strategy catalog (no combinational public toggles).
- `CANONICAL_PAGE_COMPACT_V1` compatibility extraction with byte-equivalence proof on the accepted corpus.
- `STABLE_PAGE_TIDY_V1` and `BOTTOM_FIRST_V1` implementations (first delivery). Each child mainline's bundle declares only its implemented strategies as runtime-supported.
- `GLOBAL_COMPACT_V1` and `CATEGORY_CONTIGUOUS_V1` behavioral definitions (this spec) and later implementation (child issue).
- Manual selection UI + preview integration: localized strategy name/intent description, only supported strategies offered, effective strategy identity in preview, strategy-specific consequence counts (cross-page moves, preserved-by-strategy), replan on strategy change.
- Planner-facing diagnostics echo of the effective strategy identity (see Diagnostics).
- Requirements/traceability updates (FR-016), `CONTEXT.md`/`DESIGN.md` updates, and the ADR recording the strategy-catalog decision.

## Non-goals

- Changing application/recovery safety invariants, apply/recovery contracts, or diagnostics privacy rules.
- Moving locked placements, preserved widgets/app pairs/legacy shortcuts, Dock items, or reserved regions under any strategy; every strategy sees them only as fixed occupancy constraints.
- Automatic unconfirmed full organization; strategy selection never bypasses confirmation.
- Usage/frequency permission (FR-013), network/LLM/external classification, arbitrary user scripts or imported rule execution.
- Launcher DB schema changes; page-record deletion policy (a global compaction does not itself delete empty page records).
- Lawnchair 16 migration.
- Label/alphabetical, handedness, profile-role naming, usage-ranked, or user-authored template strategies (each needs a new authoritative input and its own accepted decision; profile-role display projection is already excluded from spec 194 scope).
- A minimum-cost optimizer or general layout solver; `STABLE_PAGE_TIDY_V1` is deliberately a stable-compaction heuristic.
- Independently combinable public policy switches (folder policy × page policy × cell traversal × ordering); the catalog is curated.

## Domain language

`CONTEXT.md` gains (at acceptance):

**layout strategy (レイアウトストラテジー)**:
対象集合をどのような配置方針へ変換するかを決める、version付きの組み込み計画戦略。folder形成、対象unit、unit順序、page範囲、cell探索をcurated catalogの1メンバーとして固定し、selection identityがpolicy provenanceへ参加する。
_Avoid_: 並べ替え設定 (組合せ式toggleを想起させる)、Theme、OrderingPolicy (旧単一値の型名)

## Selection contract

### Versioned identity

`OrderingPolicy` (exactly one value `CANONICAL_V1`, asserted by `ContractShapeTest`) is replaced by a versioned strategy identity type in `RuleSemantics`:

```text
RuleSemantics {
    // ... existing v1 fields ...
    organizationStrategy: StrategyId    // non-empty opaque String,
                                        // e.g. "CANONICAL_PAGE_COMPACT_V1"
}
```

- **Identity policy:** `StrategyId` is an immutable semantic identity. `CANONICAL_PAGE_COMPACT_V1` means exactly the behavior this spec defines, forever. A behavior change is expressed by a new ID (e.g. a future `CANONICAL_PAGE_COMPACT_V2`), never by reinterpreting an existing ID. There is no separate `StrategyVersion` field: the `_V1` suffix is the version, so a strategy's name and its semantics can never drift apart. Renaming a shipped ID is forbidden; removing a catalog member means new bundles stop declaring it, and selections naming it become unsupported (fail-closed below).
- The planner accepts exactly the declared catalog members; an unknown, removed, or unsupported selection reaches the existing invalid-rules rejection family (`V-20 INVALID_RULES`), producing a typed `Rejected.Invalid`, never a silent fallback to another strategy. This is the direct-seam contract layer (see Failure layering below); the production composition path fails earlier with `NotReady`.
- `RuleSemantics` gains `organizationStrategy: StrategyId`; `orderingPolicy: OrderingPolicy` is removed. The public input model change is this spec's only planner public-shape change besides the new `PreserveReason.STRATEGY_PRESERVED` value; no other spec 10 type changes.
- This is a rule-schema change: bundle version `organization-policy-v2`, rule version `rule-v2` (v2 projections are otherwise identical to v1 with the strategy field added and default `CANONICAL_PAGE_COMPACT_V1`).

### Provenance and consistency

Provenance responsibilities are split so that every `InputProvenance` row keeps ADR-0007 §5's identity-content invariant (one identity never names different content):

- **Bundle identity (immutable policy artifact):** covers the runtime-supported strategy catalog, the default, and all v2 policy projections. It changes only when the shipped artifact changes (including enabling a newly implemented strategy on a child-issue mainline), never when a user changes their selection.
- **Selection identity (dynamic user state):** the `LayoutStrategySelectionSnapshot` contributes its own schema-version/generation/content-digest identity to `InputProvenance` as a fifth policy input, exactly like the override snapshot. `PolicySourceKind.LAYOUT_STRATEGY_SELECTION` is added.
- **Rules identity (materialized effective semantics):** the rules `PolicyInputIdentity` is the identity of the **effective `RuleSemantics`** that the planner actually receives — the bundle rules base with the selected strategy substituted. Its digest is computed exactly as `hash(bundleIdentity.semanticVersion || bundleIdentity.sha256 || selectionIdentity.sha256 || canonical(effectiveRuleSemantics))` over one unambiguous byte representation (length-prefixed segments or a labeled canonical string; raw concatenation is forbidden), and its version/generation is `rule-v2`. The bundle identity participates per ADR-0007 §5's requirement that individual rule identities include the bundle identity. Because the effective strategy is part of that content, the same bundle with a different valid selection yields a different `rulesIdentity`; the pure bundle projection identity never substitutes for it.
- Taxonomy, signals, and targets identities are unchanged; a composition is uniquely identified by `bundleIdentity + selectionIdentity + rulesIdentity + taxonomyIdentity + signalsIdentity + targetsIdentity + revision`, and no single identity is overloaded.
- `OrganizerPolicyBundle.canonicalRepresentation()` includes the declared catalog and default (not the user selection). ADR-0007's consistent-cut protocol is extended: the selection snapshot is read as part of the dynamic A/E1/B/E2 reads (identity re-read as B), so a selection change mid-read yields `NotReady(InconsistentPolicyRead)` after the single bounded retry.
- Stale-plan detection continues to compare the capture `RevisionId` (unchanged semantics); a strategy change alone does not make a captured input stale — it produces a different plan for the same snapshot under a different `rulesIdentity`.

### Rule Management and composer

- Rule Management owns both sides of the selection record: a read source (typed snapshot) and a write command. First-run absence is the defined default (`CANONICAL_PAGE_COMPACT_V1`); corruption, unsupported identity, or a read failure fails closed (`NotReady`), never silently selecting another strategy.
- **Write authority:** the UI picker never writes storage directly and never passes a selection value to the composer; it issues a Rule Management-owned validated write command. The write path:
  1. validates the requested `StrategyId` against the active bundle's runtime-supported catalog at write time (an unsupported request is rejected without touching storage);
  2. publishes the new selection snapshot atomically with a new monotonic generation and content digest (same contract family as the category override store);
  3. on write failure keeps the existing selection intact (the store is never left empty or half-written);
  4. on success, the caller starts a fresh compose/plan cycle (spec 52: a run never reuses a prior snapshot) — the published snapshot reaches planning only through the next composer read, never through an in-run substitution.
- The composer (`OrganizationInputComposer`) materializes `RuleSemantics` from the bundle with the selected strategy substituted after validating it against the bundle's runtime-supported set. The selection snapshot participates in the stable composition cut (read-after-validate, re-read as B, per Provenance above).
- Bundle v2 declares the runtime-supported strategy catalog and the default. A selection naming a strategy absent from the active bundle's runtime-supported set is `NotReady(UnsupportedVersion)`-equivalent (typed non-write), not a fallback.

### Downgrade and rollback

Rollback/downgrade safety has three distinct cases; conflating them is a specification error:

1. **Bundle-version mismatch inside one binary (defensive):** if the shipped bundle artifact and the binary's compatibility matrix disagree (a build/packaging defect, reachable only through fault injection), the existing unsupported-version path fails closed. Normal APK upgrades/downgrades cannot produce this case: the bundle is an application-owned artifact, so a binary always reads its own bundle, never a bundle left behind by another version.
2. **Store-aware APK downgrade:** the older binary reads **its own** (older) bundle; if the persisted selection names a strategy outside that older bundle's runtime-supported set, the selection-layer validation returns a typed `NotReady` — fail-closed, no silent fallback, layout unchanged. Re-upgrading revalidates the selection against the newer bundle and resumes it.
3. **Pre-store binary downgrade:** a binary from before the selection store existed has no detection code for it; it ignores the store and runs its own legacy `CANONICAL_V1` policy. It never reads, writes, or destroys the selection store, so a later re-upgrade rediscovers and revalidates the persisted selection.

A binary that understands the selection store but reads an unsupported newer store schema fails closed per ADR-0007 §8 and never rewrites the newer store. Launcher layout data is never rewritten by any of these cases; an already-applied layout from any strategy remains recoverable through the existing recovery contract (recovery points are strategy-agnostic).

## Internal planning seam

Inside the planning module, separate (all internal; callers and tests keep using `OrganizationPlanner.plan`):

1. shared input validation/canonicalization and classification (unchanged);
2. shared immutable constraints: locks, preserved items, reservations, spans, profiles, containers (extracted from `PlanningPlacement.place`);
3. strategy-specific grouping, eligible-unit selection, unit ordering, preferred page/region, and placement objective — a curated `StrategyDefinition` per catalog member;
4. one shared allocator accepting a deterministic page scope and cell traversal; a single occupancy/bounds implementation (extend the existing `Allocator` + `findRowMajorFirstFit` rather than adding a second one);
5. shared result canonicalization, self-validation, and explanation (unchanged).

A strategy produces ordered placement units/preferences for the shared allocator. Strategies never access Android types, Launcher DB rows, UI state, the application module, or recovery storage, and cannot bypass shared validation, allocator safety, result validation, or application/recovery.

`CANONICAL_V1` becomes the first built-in strategy (`CANONICAL_PAGE_COMPACT_V1`); its extraction must not change output (byte-equivalence on the accepted corpus is the acceptance gate for the extraction child issue).

### Determinism and complexity

- All strategies are pure functions of canonical input (P-09 semantics unchanged): no locale, clock, thread, or platform dependence; complete tie-breaks; canonical output ordering unchanged.
- `STABLE_PAGE_TIDY_V1` per-page stable compaction is bounded by the existing planner complexity model (sort + first-fit scan); it does not introduce search or backtracking.

## Observable strategy behavior

### STABLE_PAGE_TIDY_V1 — normative rules

- Movable set: available, unlocked, top-level `APPLICATION`/`DEEP_SHORTCUT` placements with `1×1` span. Everything else — all currently preserved items/reservations, existing folders, and non-`1×1` movable items — is a fixed constraint with `PreserveReason.STRATEGY_PRESERVED` for the non-`1×1` movable and existing-folder cases (genuinely preserved kinds keep their higher-precedence reasons per spec 10 precedence).
- No new folders are created; existing folder children are never rewritten.
- Per page, all eligible `1×1` units are first lifted against the page's fixed constraints (lift-then-place), then placed in captured visual order `(cell.y, cell.x, ItemId)` into the earliest free row-major `1×1` cell on that page. Because the captured layout is valid and non-overlapping, the number of eligible units never exceeds the free cells after lifting: every eligible unit is always placeable and no new page is ever needed.
- Units never leave their captured page; no page is created and no existing page is crossed.
- Representative fixture: apps at `(0,0), (2,0), (3,0)` with no fixed occupant at `(1,0)` become `(0,0), (1,0), (2,0)`; apps on another page do not move into the gap.
- A movable `1×1` item already at its stable position is `ALREADY_CANONICAL`; a movable item the strategy intentionally keeps fixed (non-`1×1`) is `STRATEGY_PRESERVED`.

### GLOBAL_COMPACT_V1 — normative rules

- Eligible movable units: `1×1` `APPLICATION`/`DEEP_SHORTCUT` singletons only. Otherwise-movable non-`1×1` singletons and all existing folder units are fixed units with `PreserveReason.STRATEGY_PRESERVED` (each stays on its captured page and is an occupancy constraint for the compacting units).
- Folder formation: the canonical grouping/partition policy (P-04/P-05: same-profile same-category grouping, capacity partition, ranks, `NewFolder` semantics) applied **to the eligible `1×1` candidates only** — a non-`1×1` app is never a folder candidate under this strategy, so no item is simultaneously asked to be `STRATEGY_PRESERVED` and absorbed into a new folder. New folders are placed after the eligible `1×1` units; on a replan they exist as captured folders with fixed members and therefore join the fixed set.
- Global unit order: eligible `1×1` units in captured visual order `(PageOrder, PageId, cell.y, cell.x, ItemId)`, then new folders by `(preferred page key, NewFolderOrdinal)`.
- Page scope: for each unit in global order, scan all captured pages by `PageOrder`, then already-created new pages, then create another page (the proven `allocateCapturedThenNew` semantics applied to the full-run stream).
- Cell order: top-left row-major first-fit.
- Idempotence counterexample fixture (required contract/property test — fragmented fixed occupancy + non-`1×1` units): 3×2 grid; page 1 has a fixed `1×1` at `(1,0)`, a movable `2×1` app at `(0,1)`, and a movable `1×1` app at `(2,1)`; page 2 has a movable `1×2` app at `(0,0)`. Under this strategy both the `2×1` and the `1×2` apps are `STRATEGY_PRESERVED` (non-`1×1` units never compact and never become folder candidates); only the `1×1` app compacts, moving from `(2,1)` into the earliest free page-1 cell `(0,0)`, and replanning the materialized result produces an empty diff. (If non-`1×1` units were compacted, run 1 would place the `2×1` at `(0,1)` and the `1×2` at `(2,0)`; the materialized visual order would become `1×2 → 2×1`; run 2 would move both — the documented INV-8 violation that motivates the `1×1` restriction.)
- The planner does not delete empty page records; preview must surface the cross-page move count (see Preview integration).

### BOTTOM_FIRST_V1 — normative rules

- Folder/unit/page policy identical to `CANONICAL_PAGE_COMPACT_V1`; only the cell traversal changes.
- Candidate top-left `y` runs from `rows - span.height` down to `0`; `x` runs left-to-right. Locks, widgets, app pairs, preserved items, and reserved regions remain occupied constraints.
- Naming constraint: user-facing copy describes this as "bottom first", never "thumb optimized" or "one-handed" (no handedness input exists).
- Device coverage: verified on portrait, landscape, tablet, and both two-panel orientations; no phone-only hard-coded row.

### CATEGORY_CONTIGUOUS_V1 — normative rules

- Movable set: available, unlocked, top-level `1×1` apps/deep shortcuts; existing folders are preserved fixed units; no new folders.
- Per page unit order: `(profile, category with fallback last, canonical target key, ItemId)`; page-local only — categories never pulled across page boundaries.
- Cell order: top-left row-major first-fit.
- Category decisions themselves (signals, fallback, warnings) are unchanged (P-02 applies to every strategy).

## Diagnostics

- `PlanningResult` echoes the effective strategy identity: `organizationStrategy` sits alongside `revision`/`ruleVersion`/`taxonomyVersion` (exact field shape fixed in plan.md; value-equality participates in canonical result comparison).
- `PreserveReason` gains `STRATEGY_PRESERVED`, placed in the precedence between `NON_TARGET` and `STRUCTURAL` (`LOCKED > UNAVAILABLE_TARGET > DOCK > WIDGET > APP_PAIR > LEGACY_SHORTCUT > NON_TARGET > STRATEGY_PRESERVED > STRUCTURAL > ALREADY_CANONICAL`); it applies only to otherwise-movable items a strategy intentionally fixed, and the precedence of all existing reasons is unchanged.
- The run journal keeps recording the accepted version identifiers; strategy identity is a policy identifier and is allowed in diagnostics under the existing "version identifiers" allowance ([organizer-diagnostics.md](../../docs/engineering/organizer-diagnostics.md) §Allowed). No new fields beyond the strategy identity are added; counts projections are unchanged.
- The diagnostics contract file gains the strategy-identity allowance in the same PR as the first strategy-shipping child issue.

## Preview integration

The manual flow inherits spec 194/195 contracts unchanged and adds:

- Strategy selection lives on the manual-run surface before planning: only strategies in the active bundle's runtime-supported set are offered, each with a localized name and short intent description. Changing the selection publishes through the Rule Management write command and then starts a new compose/plan cycle with a fresh capture (spec 52: a run never reuses a prior snapshot); staleness continues to be detected only by capture revision.
- The preview shows the effective strategy identity and strategy-specific consequences: moved count split by within-page vs cross-page moves, new folders/pages, preserved-by-strategy count (`STRATEGY_PRESERVED`), and warnings. This rides the existing `PreviewChange`/`PreviewCounts` projection (rationale/preserve reasons come from `Planned`, per spec 194's responsibility split) plus header-level presentation in #195's UI; no new projection kinds are required.
- `GLOBAL_COMPACT_V1` preview must make the cross-page move count visible because that change is materially more disruptive than page-local tidy.
- Confirmation, recovery-point creation, transactional apply, and post-apply verification are unchanged for every strategy.
- New selection UI inherits the Switch Access/TalkBack/font-scaling expectations of spec 52 (MFO-15) and spec 195.

## Failure layering

Invalid strategy selections are handled at two deliberate defense-in-depth layers:

- **Production composition path (authoritative):** an invalid persisted selection (unknown/removed ID, unsupported identity, corrupt or unreadable store) is caught by Rule Management/composer validation and returns a typed `NotReady` before the planner is invoked. The planner, writer, and recovery-point are never reached through this path.
- **Direct planner contract layer (defense-in-depth / test seam):** a caller that constructs an `OrganizationInput` with a catalog-external `StrategyId` directly (malformed test input, or a future non-composition caller) receives `Rejected.Invalid` via `V-20 INVALID_RULES`. The planner itself never falls back to another strategy.

Neither layer silently substitutes a strategy.

## Failure behavior

| Condition | Observable outcome |
|---|---|
| Persisted selection names an unknown/removed strategy (outside the active runtime-supported set) | Composer `NotReady` (typed non-write); planner never invoked (production layer). |
| Selection store schema newer than the binary supports | Composer `NotReady` (fail closed); organizer unavailable; layout unchanged; store never rewritten. |
| Persisted selection names a strategy outside the older bundle's runtime-supported set after an APK downgrade | Selection-layer `NotReady` (typed non-write); organizer unavailable until the user re-selects or re-upgrades; store never rewritten. |
| Shipped bundle artifact disagrees with the binary's compatibility matrix (packaging defect; unreachable in normal upgrade/downgrade) | Defensive fail-closed via the existing unsupported-version path. |
| Selection store corrupt/unreadable | Composer `NotReady`; no silent default. |
| Selection change between A and B reads of the cut | One bounded retry, then `NotReady(InconsistentPolicyRead)`. |
| Catalog-external `StrategyId` constructed directly through the planner seam | `V-20 INVALID_RULES` → typed `Rejected.Invalid` (defense-in-depth contract layer). |
| Strategy cannot place a unit its rules admit | Reuse of the existing typed impossibility semantics (spec 12 P-11: only V-21/V-22 make a candidate impossible; full-run planner invariants forbid silent drops). |
| Downgrade to a binary predating the selection store | Store ignored (never read/written/deleted); legacy policy runs; re-upgrade revalidates and reuses the selection. |

For `STABLE_PAGE_TIDY_V1`, per-page placeability is constructive (lift-then-place argument above); a placement failure of an admitted unit is therefore a planner invariant violation and fails loudly (internal error surfaced as a typed non-apply result), never a silent partial plan.

## Behavior scenarios

### Scenario: baseline strategy reproduces current output byte-for-byte

**Given** the accepted corpus (spec 11 harness corpus + generated cases + spec 12 fixed fixtures) and the planner outputs of the pinned pre-#182 baseline commit recorded as the oracle,
**When** `plan` runs with `CANONICAL_PAGE_COMPACT_V1` selected on the selection child issue and again on the extraction child issue,
**Then** every result is byte-equivalent to the oracle over the whole corpus on both child issues,
**And** the existing harness, property, determinism, idempotence, and purity tests pass without modification of their assertions.

### Scenario: page-local tidy closes holes without cross-page movement

**Given** a 4×5 page with apps at `(0,0), (2,0), (3,0)` and no fixed occupant at `(1,0)`, plus a second page with apps,
**When** a full run executes with `STABLE_PAGE_TIDY_V1`,
**Then** the page-1 apps occupy `(0,0), (1,0), (2,0)`, page-2 apps are unchanged or page-2-tidied but never moved to page 1,
**And** no `NewPage` is produced and existing folders' children are unchanged,
**And** replanning the materialized result yields an empty diff.

### Scenario: global compaction fills earlier pages

**Given** two captured pages where page 2 holds movable `1×1` singletons and page 1 has free cells,
**When** a full run executes with `GLOBAL_COMPACT_V1`,
**Then** page-2 units fill page-1 free cells in captured-visual global order before any new page is created,
**And** the preview's cross-page move count is greater than zero and equals the number of `Moved` placements whose source and destination pages differ,
**And** no captured page record is deleted,
**And** replanning the materialized result yields an empty diff.

### Scenario: global compaction preserves non-square movable units

**Given** the counterexample fixture (3×2 grid; page 1 has a fixed `1×1` at `(1,0)`, a movable `2×1` app at `(0,1)`, and a movable `1×1` app at `(2,1)`; page 2 has a movable `1×2` app at `(0,0)`),
**When** a full run executes with `GLOBAL_COMPACT_V1`,
**Then** the `2×1` and `1×2` apps are both `Preserved{STRATEGY_PRESERVED}` and only the `1×1` app compacts onto page 1,
**And** replanning the materialized result yields an empty diff (the documented heterogeneous-span INV-8 violation never occurs because non-`1×1` movable units neither compact nor become folder candidates).

### Scenario: bottom-first fills lower rows first

**Given** an otherwise empty 4×5 portrait page with ten movable `1×1` apps,
**When** a full run executes with `BOTTOM_FIRST_V1`,
**Then** apps occupy the bottom rows first: `(0,4)..(3,4)` then `(0,3)..(3,3)` then `(0,2)..(1,2)`,
**And** the same fixture on landscape 4×3, tablet 6×5, and both two-panel orientations fills the respective bottom rows first,
**And** a locked app at `(3,4)` (fixture L-17 variant) is preserved and the remaining apps fill lower free cells without overlapping it.

### Scenario: category-contiguous keeps categories visible per page

**Given** a page with `1×1` apps of categories GAME, TOOLS, and fallback-OTHER, plus one existing folder,
**When** a full run executes with `CATEGORY_CONTIGUOUS_V1`,
**Then** same-category apps occupy contiguous row-major cells ordered by `(profile, category with fallback last, target key, ItemId)`, the folder stays fixed with `STRATEGY_PRESERVED`, and no new folder is created,
**And** no app crosses a page boundary that its category ordering would otherwise suggest.

### Scenario: strategy-preserved items are truthful

**Given** a `STABLE_PAGE_TIDY_V1` run over a page containing a non-`1×1` movable app and an existing folder,
**When** the plan is produced,
**Then** both are `Preserved` with `STRATEGY_PRESERVED` (not `ALREADY_CANONICAL`, not `NON_TARGET`),
**And** they remain occupancy constraints for the movable `1×1` units.

### Scenario: selection participates in provenance and cut

**Given** a user-selected `BOTTOM_FIRST_V1` in the strategy-selection store,
**When** the composer composes a full-organization input,
**Then** `RuleSemantics.organizationStrategy` carries `BOTTOM_FIRST_V1`, the selection snapshot's generation/digest identity appears in `InputProvenance` alongside the four existing policy identities, the rules identity digest covers the effective `RuleSemantics` (so the same bundle with `CANONICAL_PAGE_COMPACT_V1` selected produces a different `rulesIdentity`), the bundle identity reflects the runtime-supported catalog (not the selection), and the plan result echoes `BOTTOM_FIRST_V1`,
**And** a selection change between the A and B reads yields one retry then `NotReady(InconsistentPolicyRead)`.

### Scenario: selection write is validated, atomic, and failure-preserving

**Given** the strategy picker visible on the manual-run surface and an active bundle whose runtime-supported set contains `BOTTOM_FIRST_V1` but not a removed `LEGACY_SORT_V0`,
**When** the user selects `BOTTOM_FIRST_V1`,
**Then** the picker issues a Rule Management write command that validates against the runtime-supported set, publishes the new selection atomically with a new generation/digest, and the coordinator then starts a fresh compose/plan cycle,
**And** a write attempt naming `LEGACY_SORT_V0` is rejected at write time without touching the store,
**And** a storage failure during publication leaves the previous selection intact and does not start a new run.

### Scenario: unsupported or corrupt selection fails closed

**Given** a persisted selection naming a removed strategy, a selection store with a newer schema than the binary supports, or a corrupt selection store,
**When** composition runs,
**Then** the composer returns a typed `NotReady`, the planner/writer/recovery-point are never invoked, and no fallback strategy is applied silently,
**And** a direct planner-seam caller constructing the same invalid `StrategyId` instead receives `Rejected.Invalid` via `V-20 INVALID_RULES`.

### Scenario: strategy change replans under the stable-cut discipline

**Given** a displayed preview computed for strategy S1,
**When** the user selects strategy S2 supported on this device,
**Then** the coordinator starts a new compose/plan cycle with a fresh capture (spec 52: no snapshot reuse); if the layout is unchanged the plan is recomputed under S2 and re-previewed with S2's identity and consequences,
**And** staleness continues to be detected only by capture revision.

### Scenario: cross-strategy isolation

**Given** identical capture, target membership, and application semantics,
**When** the same fixture runs under each catalog strategy,
**Then** only strategy-varying observables differ (targets, dispositions, cross-page counts); conservation, bounds, overlap, reference, lock, profile isolation, determinism, and idempotence hold for every strategy,
**And** every strategy passes the shared spec 11 harness and property suite through the public seam.

## Data and state

- The planner stays pure; strategies add no I/O, state, or platform dependence.
- New persistence: one local strategy-selection record owned by Rule Management, with a read source and a single validated write command (typed snapshot with schema version, monotonic generation, content digest — same contract family as the category override store; excluded from backup/restore in v1 like the override store, with the same defined first-run empty state).
- No Launcher DB schema change; recovery points, checkpoints, and the apply write-set are unchanged. Bundle `organization-policy-v2`/`rule-v2` is an application-owned immutable artifact change (no in-place migration; ADR-0007 §8). Enabling a newly implemented strategy publishes a new bundle semantic version/generation and digest (ADR-0007 §8); `rule-v2` and the selection-store schema are unchanged per enablement.
- Migration surface: selection-store schema v1 introduction (no migration from anything); a pre-store binary ignores the store; a store-aware binary reading a newer schema fails closed without rewriting.

## Permissions, privacy, and security

No new permission, network, or telemetry. The strategy selection is a local, app-private preference. Diagnostics expose only the strategy identity under the existing version-identifier allowance; no raw policy content, package, profile, or coordinate data is added to any diagnostic.

## Accessibility and localization

Strategy names and intent descriptions are localized strings (`values/` + `values-ja/`). Selection UI satisfies TalkBack (meaningful label/state/selection announcements), Switch Access/keyboard traversal, and 200% font scaling per spec 52/195 expectations. Preview consequence counts are announced as text, not color-only.

## Acceptance criteria

Product/specification:

- [ ] AC-1: The candidate comparison, accepted initial set, default, and per-strategy observable rules (including tie-breaks, idempotence argument, overflow, and failure behavior) are recorded in this spec and accepted.
- [ ] AC-2: A new requirement (FR-016) covers user selection and preview of a supported organization strategy; traceability is updated ([requirements.md](../../docs/product/requirements.md)).
- [ ] AC-3: Unsupported/corrupt/newer/removed selection plus downgrade/rollback behavior is specified and implemented fail-closed at the composition layer, with the V-20 defense-in-depth layer behind it.
- [ ] AC-3b: Selection writes go through the Rule Management validated write command (write-time catalog validation, atomic generation/digest publication, existing selection preserved on failure); the UI never writes storage directly.

Architecture/implementation:

- [ ] AC-4: `OrganizationPlanner.plan(OrganizationInput)` remains the sole external planning seam; strategy logic cannot bypass shared validation, constraints, allocator safety, result validation, or application/recovery (verified by the existing purity guard extended to strategy sources).
- [ ] AC-5: `CANONICAL_PAGE_COMPACT_V1` extraction is byte-equivalent against the golden corpus pinned from the pre-#182 baseline commit, verified on both the selection child issue (registry/adapter dispatch) and the extraction child issue.
- [ ] AC-6: Strategy identity participates in policy provenance — the bundle digest covers the runtime-supported catalog and default; the selection contributes its own generation/digest identity to `InputProvenance`; the rules identity is defined over the effective `RuleSemantics`, so the same bundle with different valid selections yields different `rulesIdentity` values (identity-content invariant holds per row); the result echoes the effective strategy.
- [ ] AC-7: Selection is local, versioned, validated, generation/digest-bearing, and migration/fail-closed tested.
- [ ] AC-8: UI identifies the effective strategy, offers only supported strategies, and previews strategy-specific consequences.

Verification:

- [ ] AC-9: Every catalog strategy passes the shared conservation, bounds, overlap, reference, lock, profile-isolation, determinism, and idempotence contract/property tests through the public seam.
- [ ] AC-9b: Bundle catalog coherence is contract-tested in the selection child issue and every strategy child issue: `runtimeSupported ⊆ implemented internal strategy IDs`, `default ∈ runtimeSupported`, and `runtimeSupported ==` the runtime-enabled implemented strategy IDs.
- [ ] AC-10: Fixtures cover empty/full homes, widgets/folders/app pairs, fragmented locks, pages, profiles, category fallback, grids, rotation, tablet, foldable/two-panel, and invalid selection.
- [ ] AC-11: Cross-strategy tests change only strategy while capture, target membership, and application safety semantics remain unchanged.
- [ ] AC-12: Planner performance is sampled across the strategy × item/page/device matrix using the spec 12 protocol (no budget assertion; Issue-owned budgets unaffected).
- [ ] AC-13: UI covers TalkBack, Switch Access, font scaling, recreation, stale plan, failure/recovery, and localization.
- [ ] AC-14: Physical-device before/preview/after/recovery evidence exists for every shipped strategy.
- [ ] AC-15: High-risk PRs carry successful `final-status` CI and independent audit records per the evidence gate.

## Child issues

Created after this spec is accepted; never one PR:

1. Research/decision confirmation: record the accepted set/default (closed by accepting this spec; no separate issue needed if acceptance is direct).
2. Feature: versioned strategy-selection contract — `RuleSemantics`/bundle v2 change, selection store (read source + validated write command), composer/provenance/migration/fail-closed handling, the effective-`RuleSemantics` rules identity, a minimal `CANONICAL_PAGE_COMPACT_V1` strategy registration (internal registry + adapter that dispatches to the existing placement body unchanged) so AC-9b catalog coherence is testable at this point, and a pass of the pinned pre-#182 golden corpus. The bundle's runtime-supported set declares only the strategies implemented at that point (initially `CANONICAL_PAGE_COMPACT_V1`); each later strategy child publishes a new bundle semantic version/generation with the expanded set (ADR-0007 §8) under unchanged `rule-v2`/selection-store schema.
3. Feature: extract `CANONICAL_PAGE_COMPACT_V1` behavior fully behind the internal seam (extraction/refactor of the placement body) with the compatibility corpus byte-equivalence proof; the registry it registers into exists since child 2.
4. Feature: `STABLE_PAGE_TIDY_V1`.
5. Feature: `BOTTOM_FIRST_V1` (may be delivered before or after child 4; both are first-delivery strategies).
6. Feature: `GLOBAL_COMPACT_V1`.
7. Feature: `CATEGORY_CONTIGUOUS_V1`.
8. Feature: selection and preview-explanation UI (strategy picker, identity display, cross-page/preserved-by-strategy consequence counts).
9. Maintenance/evidence: compatibility, accessibility, performance, physical-device matrix.
10. Independent high-risk audit work for final source-changing PRs.

## Open questions

None blocking acceptance. Deferred deliberately: catalog renaming policy (a rename is a new strategy identity, never a silent reinterpretation); `GLOBAL_COMPACT_V1`/`CATEGORY_CONTIGUOUS_V1` delivery order (child issues). The result echo field shape is fixed in plan.md.

## Change history

- 2026-09-04: Draft created for Issue #182 (Epic spec): accepted catalog, selection contract, internal seam, strategy rules, diagnostics/preview integration, child-issue split.
- 2026-09-04: Review revision (owner review on `bc023485`): separated the selection identity from the immutable bundle digest (bundle covers runtime-supported catalog/default; selection carries its own `InputProvenance` identity as a fifth policy input) — Blocking 1. Added the Rule Management write-command contract (write-time catalog validation, atomic generation/digest publication, failure preserves the existing selection, picker never writes storage or substitutes in-run) — Blocking 2. Split spec-level catalog from the bundle runtime-supported set so each child mainline declares only implemented strategies; enabling a strategy is a bundle content change without a schema bump — Blocking 3. Unified downgrade semantics (pre-store binaries ignore the store; store-aware binaries fail closed on newer schemas; re-upgrade revalidates) — Blocking 4. Removed the duplicate `StrategyVersion` field; `StrategyId` (with the `_V1` suffix) is the immutable semantic identity — Medium. Documented the two-layer failure policy (composition `NotReady` vs planner-seam `V-20`) — Medium.
- 2026-09-04: Re-review revision (owner re-review on `c666c435`): strategy enablement now publishes a new bundle semantic version/generation and digest per ADR-0007 §8 (e.g. `organization-policy-v2.1`), keeping `rule-v2` and the selection-store schema unchanged; the "same schema, digest-only" model is withdrawn — Blocking. Added the bundle catalog coherence contract test (`runtimeSupported ⊆ implemented IDs`, `default ∈ runtimeSupported`, exact equality with runtime-enabled implementations) in the selection and strategy child issues — Medium. Removed the resolved echo-shape item from open questions — Low.
- 2026-09-04: Second re-review revision (owner re-review on `110bba11`): the rules `PolicyInputIdentity` is redefined as the identity of the **effective** `RuleSemantics` (bundle rules base + selected strategy, digest bound to the bundle and selection identities), so the same bundle with a different valid selection yields a different `rulesIdentity` and ADR-0007 §5's identity-content invariant holds for every provenance row — Blocking. Child 2 now includes a minimal `CANONICAL_PAGE_COMPACT_V1` registration (internal registry + adapter dispatching to the unchanged placement body) so AC-9b is testable before child 3's behavior extraction — Medium. Downgrade/rollback rewritten as a three-case model (in-binary bundle-version mismatch defensive case; store-aware APK downgrade failing closed at the selection layer against the older bundle's set; pre-store binaries ignoring the store), and the "old binary reads an unknown bundle version" claim was withdrawn — Medium.
- 2026-09-04: Third re-review revision (owner re-review on `60af35c3`): `GLOBAL_COMPACT_V1` cross-page compaction is restricted to movable `1×1` singletons — otherwise-movable non-`1×1` singletons and all existing folder units are `STRATEGY_PRESERVED` — because the reviewer's heterogeneous-span counterexample (non-`1×1` unit plus fragmented fixed obstacle) reorders the visual sequence across replans and violates INV-8; the withdrawn claim and the required counterexample fixture/property coverage are documented — Blocking. The byte-equivalence oracle is now pinned from the accepted pre-#182 baseline commit (currently `main@5fdab48082`) and must pass on both the selection and extraction child issues, so a selection-child regression cannot be promoted into the oracle — Blocking. The effective-rules digest formula now names its inputs explicitly (`bundleIdentity.sha256 || selectionIdentity.sha256 || canonical(effectiveRuleSemantics)`) — Medium.
- 2026-09-04: Fourth re-review revision (owner re-review on `8903156e`): the `1×1` restriction is propagated consistently through `GLOBAL_COMPACT_V1` — folder formation is "canonical grouping/partition policy applied to the eligible `1×1` candidates only" (a non-`1×1` app is never a folder candidate, so no item is both `STRATEGY_PRESERVED` and a new-folder member), and the counterexample fixture/scenario gains a movable `1×1` app with both non-`1×1` apps `STRATEGY_PRESERVED` — Blocking. The effective-rules digest formula is unified on the plan's shape (including `bundleIdentity.semanticVersion`) with a required unambiguous byte representation (length-prefixed/labeled; raw concatenation forbidden) — Medium. The execution checklist's stale "golden capture at extraction-child start" step is replaced with the pinned-baseline/both-children procedure — Medium.
- 2026-09-05: Clarified the counterexample fixture phrase — the movable 1x1 app sits on page 1 at (2,1), so it compacts to (0,0) within page 1; 'moving from page 2' contradicted the fixture geometry (auditor Low finding during child-6 review). No behavioral change.
- 2026-09-04: Accepted by the Issue #182 owner after the fifth review round found no further issues (reviewed head `9b6eec73a9`). Implementation may begin within this specification and plan, split across the child issues; child 2 is the first implementation vertical. The spec-10/12 delta rows in this acceptance PR are the only planner public-shape changes authorized by this spec.

## References

- [Issue #182: Expand organizer layout strategies beyond CANONICAL_V1](https://github.com/nunu1733/NunuLauncher/issues/182)
- [Spec 10: pure organization planning interface](../10-pure-organization-planning/spec.md)
- [Spec 12: deterministic full-layout planner v1](../12-deterministic-full-layout-planner-v1/spec.md)
- [Spec 83: production OrganizationInput sources](../83-production-organization-input-sources/spec.md)
- [Spec 52: manual full-organization vertical slice](../52-manual-full-organization-vertical-slice/spec.md)
- [Spec 194: plan preview seam](../194-plan-preview-seam/spec.md)
- [Spec 195: confirmation change list](../195-organizer-confirmation-change-list/spec.md)
- [ADR-0007: authoritative organization policy sources](../../docs/adr/0007-authoritative-organization-policy-sources.md)
- [layout-strategy-v1 research](../../docs/product/layout-strategy-v1.md)
- [organizer diagnostics contract](../../docs/engineering/organizer-diagnostics.md)
- [AGENTS.md](../../AGENTS.md), [DESIGN.md](../../DESIGN.md), [CONTEXT.md](../../CONTEXT.md)
