---
issue: "#10"
status: accepted
requirements:
  - FR-001
  - FR-002
  - FR-003
  - FR-010
  - FR-011
  - FR-015
  - NFR-002
  - NFR-003
  - NFR-004
  - NFR-005
  - NFR-010
updated: 2026-08-27
---

# Pure organization planning interface

## Problem

NunuLauncher must compute a home-layout organization plan from a captured
snapshot without performing any database write, Android framework call, UI
mutation, or I/O. The existing Deck layout mixes classification, placement, and
DB writes inside one task and exposes no inspectable plan or diagnostic ([Deck
audit](../../docs/assessment/lawnchair-deck-audit.md) §6.1). Without a pure
planning seam the system cannot verify conservation, bounds, overlap, lock, and
profile invariants before applying a change, and tests cannot drive the same
code path that production uses.

## Outcome

An accepted `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult` seam that takes a
canonical snapshot, taxonomy contract, versioned rules, classification signals,
target membership, and run mode; returns a deterministic plan, category
decisions, rationale, warnings, unplaced items, and rejection reasons; exposes
no Android/DB/UI/I/O type or concrete layout algorithm; and lets production
callers and contract tests exercise the identical seam. This spec defines the
observable contract only.

## Scope

- The single public seam `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult`.
- Canonical shapes of captured snapshot, candidate additions, taxonomy contract,
  rules, signals, target membership, run mode, and output plan/diagnostics.
- Opaque identity handles; closed placement variants; typed identity shapes that
  make valid states easy and impossible combinations unrepresentable.
- Error semantics: **invalid**, **impossible**, **stale-independent**.
- Contract-test scenarios verifiable through the public seam alone.

## Non-goals

- Placement algorithm, tie-break, fill order, sorting, bin packing — driven by
  [layout strategy v1](../../docs/product/layout-strategy-v1.md).
- Layout Application, recovery, snapshot capture, signal computation
  (`DESIGN.md` §4.2–§4.4); rule file format (`DESIGN.md` §4.3).
- Lock persistence ([Issue
  #23](https://github.com/nunu1733/NunuLauncher/issues/23)); empty-folder
  deletion ([Issue #24](https://github.com/nunu1733/NunuLauncher/issues/24)).
- Explicit item deletion in v1; usage/frequency signals (FR-013).
- UI, confirmation, preview, accessibility of downstream surfaces; performance
  budgets ([Issue #15](https://github.com/nunu1733/NunuLauncher/issues/15)).
- End-to-end incremental feature delivery (owned by other issues).

## Delivery boundary

This document defines the observable contract beyond the first code slice. The
contract-definition delivery creates the canonical public types and the
`OrganizationPlanner` functional interface, but no concrete planner and no
`TODO`/throwing production placeholder. Representative tests construct the
public types and invoke a test adapter through the same `plan` method that the
future production implementation will satisfy.

The reusable black-box harness and the concrete planner are separate deliveries
tracked by GitHub Issues. The harness accepts an `OrganizationPlanner` and does
not mock internal planning helpers. The concrete implementation owns
canonicalization, V-01–V-22 evaluation, classification, preservation,
placement, and canonical result ordering. The validation rules and behavior
scenarios below are normative downstream conformance requirements; the
contract-definition delivery makes them constructible and type-safe but does
not execute them.

## Domain handles

Terms resolve to [CONTEXT.md](../../CONTEXT.md). Opaque handles below are
programming identifiers, not domain vocabulary:

| Handle | Public backing value | Carries no |
|---|---|---|
| `ItemId` | non-empty `String` | `ItemInfo`, DB `_id` |
| `ProfileId` | non-empty `String` | `UserHandle`, raw serial |
| `PageId` | non-empty `String` | persistent screen ID |
| `PageOrder` | canonical non-negative decimal `String`; `Int` convenience constructor | screen ID |
| `NewPageOrdinal` | non-negative `Int` | persistent screen ID |
| `FolderId` | non-empty `String` | `FolderInfo`, DB `_id` |
| `NewFolderOrdinal` | non-negative `Int` | persistent `_id` |
| `AppPairId` | non-empty `String` | `AppPairInfo`, DB `_id` |
| `SnapPositionToken` | non-empty `String` | encoded rank |
| `CategoryId` / `TaxonomyVersion` | non-empty `String` | display name |
| `KindCode` / `ContainerCode` | `Int` | typed meaning |
| `RevisionId` / `RuleVersion` | non-empty `String` | timestamp, file format |
| `ComponentKey` / `PackageName` / `ShortcutId` | non-empty `String` | Android `ComponentName` or package object |
| `AppWidgetId` | `Int` | `AppWidgetProviderInfo` |

Every text-backed opaque handle has a public constructor taking its backing `String`,
uses exact value equality, and performs no Unicode normalization, case folding,
or locale transform. Its canonical order is unsigned lexicographic comparison
of the exact UTF-8 bytes; when one byte sequence is a prefix, the shorter sorts
first. Integer codes use signed numeric order. `PageOrder` accepts a canonical
unsigned decimal string of arbitrary length or a non-negative `Int`, compares by
numeric value, and supports overflow-free addition of a non-negative `Int`.
Leading zeros, signs, whitespace, and empty text are rejected. Plan-local
ordinals use natural non-negative `Int` order and reject negative construction.
Captured (`PageId`, `FolderId`, `AppPairId`) and plan-created
(`NewPageOrdinal`, `NewFolderOrdinal`) identities are distinct public types.

## Contract shapes

Minimum observable structure; `plan.md` may add private fields. No `android.*`,
`com.android.launcher3.*`, `android.database.*`, `android.content.*`,
coroutine, flow, file handle, or DB cursor in the public surface.

### Input types

```text
OrganizationInput {
    snapshot: LayoutSnapshot
    rules:    RuleSemantics
    taxonomy: TaxonomyContract
    signals:  ClassificationSignals
    targets:  TargetSet
    runMode:  RunMode
}
RunMode = FullOrganization | IncrementalPlacement

LayoutSnapshot {
    revision: RevisionId
    device:   DeviceCapabilities
    pages:    [Page]
    items:    [CapturedItem]            // captured existing layout only
    reservedWorkspaceRegions: [ReservedWorkspaceRegion]
}
Page { id: PageId, order: PageOrder }   // order is unique within snapshot
ReservedWorkspaceRegion { page: PageRef, cell: GridCell, span: GridSpan }

DeviceCapabilities {
    columns: Int, rows: Int, hotseatSlots: Int
    folderMaxColumns: Int, folderMaxRows: Int
    orientation: Orientation             // PORTRAIT | LANDSCAPE | TWO_PANEL_*
}

`ReservedWorkspaceRegion` is a canonical, platform-owned occupancy constraint.
It is neither a `CapturedItem`, a target member, a candidate, nor a requested
mutation. Its page must occur exactly once in `pages`; its rectangle obeys the
same workspace bounds as an item; duplicate value-equal regions are rejected;
and two regions may overlap only when their pages differ. A region overlapping a
captured workspace item is invalid input. A rowless logical page is valid when
it is present in `pages` and is referenced only by a reservation. The planner
must preserve such a region as occupancy and never expose it as a disposition,
placement, action, or persistent item identity.

CapturedItem {
    id:           ItemId
    profile:      ProfileId
    kind:         ItemKind
    target:       TargetKey
    placement:    CapturedPlacement
    locked:       Bool
    availability: Availability
    folderId:     FolderId?              // present iff kind = FOLDER
    appPairId:    AppPairId?             // present iff kind = APP_PAIR
    members:      [ItemId]               // folder child ids; iff kind = FOLDER
    appPair:      AppPairMetadata?       // iff kind = APP_PAIR
}

ItemKind =
    | APPLICATION | DEEP_SHORTCUT | SHORTCUT_LEGACY
    | FOLDER | APPWIDGET | CUSTOM_APPWIDGET | APP_PAIR
    | Unknown(KindCode)                  // unrecognized persisted kind; opaque

Availability = AVAILABLE | DISABLED | QUIET | LOCKED_PRIVATE_SPACE | UNAVAILABLE

TargetKey =
    | AppKey(component: ComponentKey, profile: ProfileId) // APPLICATION
    | ShortcutKey(packageName: PackageName, shortcutId: ShortcutId,
                  profile: ProfileId)                       // DEEP_SHORTCUT
    | LegacyShortcutKey                         // SHORTCUT_LEGACY (no canonical target)
    | WidgetKey(provider: ComponentKey, appWidgetId: AppWidgetId,
                profile: ProfileId)                         // APPWIDGET / CUSTOM_APPWIDGET
    | FolderKey(folderId: FolderId)                         // FOLDER
    | AppPairKey(appPairId: AppPairId)                     // APP_PAIR
```

Each named `ItemKind` pairs with exactly one `TargetKey` variant; any other
pairing reaches V-11. A `TargetKey` embedding `profile` (`AppKey`,
`ShortcutKey`, `WidgetKey`) must equal the item's `ProfileId` (else V-12).
`Unknown(KindCode)` has no compatible target and is rejected as V-01.

**Folder/app-pair identity.** A captured `FOLDER` exposes `folderId: FolderId`;
`FolderRef(folderId)` in a child's placement resolves to it. A captured
`APP_PAIR` exposes `appPairId: AppPairId`; `AppPairRef(appPairId)` resolves to
it. `ItemId` is the instance identity for conservation; `FolderId`/`AppPairId`
is the container identity for placement resolution. Both are opaque, no platform
ID. A ref resolving to zero or more than one item reaches V-06.

`LegacyShortcutKey` is a unit variant: `ITEM_TYPE_SHORTCUT` is deprecated with
no canonical representation ([item preservation
policy](../../docs/product/item-preservation-policy.md) §3.4). This lets a valid
`SHORTCUT_LEGACY` exist in a snapshot and reach the preservation + warning
scenario (S-12) rather than being rejected.

Folder membership lives in `CapturedItem.members`; app-pair membership in
`AppPairMetadata.members`. No `parent` field — membership is expressed through
the [closed placement variant](#placement). Folder membership is bidirectional
and unique: every listed child has the matching `FolderMember` placement, every
`FolderMember` appears in exactly one folder's list, and allowed child kinds are
`APPLICATION`, `DEEP_SHORTCUT`, and `APP_PAIR`. The container graph is acyclic.

### Placement

Split into **captured** (input; plan-local refs excluded) and **target**
(output; plan-local refs allowed). Closed variants eliminate nullable
cell/rank combinations without semantics.

```text
// refs (value wrappers)
PageRef(PageId)        NewPageRef(NewPageOrdinal)
FolderRef(FolderId)    NewFolderRef(NewFolderOrdinal)
AppPairRef(AppPairId)

// named value types (no Kotlin Pair or unnamed union in the public surface)
GridCell { x: Int, y: Int }
GridSpan { width: Int, height: Int }
PageTargetRef = PageRef | NewPageRef
FolderTargetRef = FolderRef | NewFolderRef

CapturedPlacement =                        // input
    | Workspace { page: PageRef, cell: GridCell, span: GridSpan }
    | Dock { rank: Int }
    | FolderMember { folder: FolderRef, rank: Int }
    | AppPairMember { pair: AppPairRef }
    | UnsupportedContainer { code: ContainerCode }

PlacementTarget =                          // output
    | WorkspaceTarget
    | Dock { rank: Int }
    | FolderMember { folder: FolderTargetRef, rank: Int }
    | AppPairMember { pair: AppPairRef }

WorkspaceTarget {
    page: PageTargetRef
    cell: GridCell
    span: GridSpan
}
```

In Kotlin, `PageTargetRef` and `FolderTargetRef` are sealed interfaces;
`PageRef`/`NewPageRef` and `FolderRef`/`NewFolderRef` respectively are their
only implementations. `GridCell` and `GridSpan` are public value objects with
the named fields above.

`CapturedPlacement` never carries `NewPageRef`/`NewFolderRef` (captured state
cannot depend on an unmade plan). `PlacementTarget` never carries
`UnsupportedContainer` (rejected before a plan). `UnsupportedContainer` makes an
unsupported container constructible through the public seam (V-02). Validity:
Workspace `x,y ≥ 0; w,h > 0; x+w ≤ columns; y+h ≤ rows`; Dock `rank ∈ [0,
hotseatSlots-1]`; FolderMember `rank ≥ 0`. Violations reach V-04; absent
`PageRef` reaches V-03.

### AppPairMetadata and CandidateItem

```text
AppPairMetadata { members: [AppPairMember] }    // exactly two; complementary stages
AppPairMember {
    item:         ItemId              // must be APPLICATION or DEEP_SHORTCUT
    stage:        SplitStage          // TOP_OR_LEFT | BOTTOM_OR_RIGHT
    snapPosition: SnapPositionToken?  // persistent split snap; null = absent
}
SplitStage = TOP_OR_LEFT | BOTTOM_OR_RIGHT

CandidateItem {
    id:           ItemId              // must not collide with captured ItemId
    profile:      ProfileId
    kind:         CandidateKind       // APPLICATION | DEEP_SHORTCUT only
    target:       CandidateTarget
    availability: Availability
    span:         GridSpan
}
CandidateKind = APPLICATION | DEEP_SHORTCUT
CandidateTarget =
    | AppKey(component: ComponentKey, profile: ProfileId)
    | ShortcutKey(packageName: PackageName, shortcutId: ShortcutId,
                  profile: ProfileId)
```

`CandidateKind.APPLICATION` pairs only with `AppKey` and
`CandidateKind.DEEP_SHORTCUT` only with `ShortcutKey`; mismatch reaches V-11.
The target's embedded profile must equal `CandidateItem.profile` (V-12).

App-pair validity (all constructible, all reaching V-07 `MALFORMED_APP_PAIR`
with `ItemParam`): exactly two members; one `TOP_OR_LEFT` + one
`BOTTOM_OR_RIGHT`; the two members have distinct `ItemId` values; each member
kind is `APPLICATION`/`DEEP_SHORTCUT`; both
members carry present, value-equal `SnapPositionToken`; each `member.item` has
`placement = AppPairMember { pair: AppPairRef(this.appPairId) }` and the app
pair is the sole membership source.

`CandidateKind` is restricted to launchable kinds. `FOLDER`/`APP_PAIR`
candidates are not constructible — they lack required `members`/`appPair`
structure. A candidate carries no `locked`, no `members`, no `appPair`, no
`folderId`/`appPairId`, no captured `Placement`. The planning inventory is
`snapshot.items ∪ targets.additions`; conservation accounts for it once. An
oversized candidate (span exceeds grid) reaches `Impossible` V-21; a candidate
whose availability is not `AVAILABLE` reaches `Impossible` V-22 — disjoint from a captured
bounds defect (V-04, `Invalid`).

### Policy inputs

```text
RuleSemantics {
    version:                RuleVersion      // must be accepted by this planner
    folderPolicy:           FolderPolicy
    dockPolicy:             DockPolicy
    overflowPolicy:         OverflowPolicy
    fallbackCategoryPolicy: FallbackCategoryPolicy
    orderingPolicy:         OrderingPolicy
}
FolderPolicy { minGroupSize: Int, newFolderProfileScope: NewFolderProfileScope }

NewFolderProfileScope = SAME_PROFILE_ONLY
DockPolicy             = PRESERVE
OverflowPolicy         = ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE
FallbackCategoryPolicy = KEEP_AS_SINGLETON
OrderingPolicy         = CANONICAL_V1   // versioned identity linked to layout-strategy-v1;
                                         // fill/sort/tie-break mechanics hidden in planner

TaxonomyContract {
    version:           TaxonomyVersion
    allowedCategories: [CategoryId]      // no duplicates; canonical byte ordering
    fallbackCategory:  CategoryId        // must be member of allowedCategories
}
```

`CANONICAL_V1` is only a versioned policy identity — the seam contracts on
observable result properties, not mechanics ([layout strategy
v1](../../docs/product/layout-strategy-v1.md) §4). `RuleSemantics` and
`TaxonomyContract` have value equality and are canonicalized as part of input.
The canonical [taxonomy v1](../../docs/product/category-taxonomy-v1.md) has 34
categories (33 + `OTHER`); `fallbackCategory` is `OTHER`. `RuleVersion` does not
implicitly determine taxonomy — the binding is explicit through `TaxonomyContract`.

Each policy field is a single v1 variant, so an invalid policy cannot be
constructed. Reachable V-20 (`INVALID_RULES`) cases: unaccepted `version`,
`minGroupSize < 2`, duplicate `CategoryId` in `allowedCategories`, or
`fallbackCategory` not in `allowedCategories`.

**Dock resolution under `PRESERVE`:** a dock-scoped item marked `Movable` is
reported `Preserved { DOCK }` — the planner never moves it.

### Signals and targets

```text
ClassificationSignals { entries: [ClassificationSignal] }
ClassificationSignal { item: ItemId, source: SignalSource, candidate: CategoryId }
SignalSource = S1 | S2 | S3 | S4 | S5 | S6

TargetSet {
    existing:  [ExistingTargetMembership]
    additions: [CandidateItem]
}
ExistingTargetMembership { item: ItemId, role: ExistingRole }
ExistingRole = Movable | Preserved
```

`entries` is a list (not a map) so complete input is constructible verbatim.
Resolution priority: `S1 > S2 > S3 > S4 > S5 > S6` ([taxonomy
v1](../../docs/product/category-taxonomy-v1.md) §3.2); hidden inside the module.
No signal → `S6` → `fallbackCategory`. V-13: signal `item` outside inventory.
V-14: signal `candidate` not in `allowedCategories`. Same `item`+`source` ties
resolve by canonical `CategoryId` ordering (§3.3, §4.3); exact duplicates
canonicalize away.

`TargetSet` validation: V-15 through V-18. Profile isolation of planner folders
is output (INV-6), not input rejection.

### Output types

```text
PlanningResult {
    revision:    RevisionId            // echoed
    ruleVersion: RuleVersion           // echoed
    taxonomyVersion: TaxonomyVersion   // echoed
    outcome:     PlanningOutcome
}
PlanningOutcome = Planned | Rejected
Planned {
    placements: [PlannedPlacement]     // one per inventory item
    newPages:   [NewPage]
    newFolders: [NewFolder]
    categories: [CategoryDecision]
    warnings:   [Warning]
}
Rejected =
    | Invalid { reasons: [RejectionReason], warnings: [Warning] }
    | Impossible { unplaced: [UnplacedItem], warnings: [Warning] }

PlannedPlacement { item: ItemId, disposition: Disposition, target: PlacementTarget }
Disposition =
    | Moved { rationale: PlacementCode }
    | Preserved { reason: PreserveReason }
PlacementCode   = SINGLE_PLACEMENT | FOLDER_MEMBER | FOLDER_UNIT
PreserveReason  = LOCKED | UNAVAILABLE_TARGET | DOCK | WIDGET
                 | APP_PAIR | LEGACY_SHORTCUT | NON_TARGET | STRUCTURAL
                 | ALREADY_CANONICAL

NewPage { ordinal: NewPageOrdinal, order: PageOrder }
NewFolder {
    ordinal:            NewFolderOrdinal
    profile:            ProfileId         // single-profile
    workspacePlacement: WorkspaceTarget   // span (1,1)
    members:            [ItemId]
}
CategoryDecision { item: ItemId, category: CategoryId, decidedSignal: SignalSource, confidence: Confidence }
Confidence = EXPLICIT | RULE | FALLBACK     // S1-S2 / S3-S4 / S5-S6
```

In Kotlin, `PlanningOutcome` is a sealed interface; `Planned` and the sealed
`Rejected` hierarchy are its only implementations.

`categories` contains exactly one `CategoryDecision` for every captured or
candidate inventory item whose kind is `APPLICATION` or `DEEP_SHORTCUT`,
including locked, unavailable, preserved, and non-target captured items. It
contains no decision for folders, widgets, app pairs, or legacy shortcuts; it
has no duplicate `ItemId` and is ordered by canonical `ItemId`. Target
membership and disposition do not change classification eligibility.

`NewPage.order` > every captured `Page.order`; ordinals `0,1,2,…` ascending.
`NewFolder`: `profile` is single-profile (INV-6); members reference this
`NewFolderOrdinal`; not double-counted (each also appears as
`PlannedPlacement`); `workspacePlacement` is `Workspace` span `(1,1)`; acyclic
(INV-4). New-folder members are `APPLICATION` or `DEEP_SHORTCUT` items only.
`Moved` accepts only `PlacementCode`; `Preserved` only
`PreserveReason`.

**Legal planned targets.** These rules constrain observable results without
exposing placement mechanics:

| Inventory item | Legal `Moved.target` | Additional rule |
|---|---|---|
| captured `APPLICATION` / `DEEP_SHORTCUT` | `WorkspaceTarget` or `FolderMember` | workspace span equals captured span |
| candidate `APPLICATION` / `DEEP_SHORTCUT` | `WorkspaceTarget` or `FolderMember` | workspace span equals candidate span; never Dock/app-pair |
| captured `FOLDER` | `WorkspaceTarget` | span equals captured span; child membership/ranks unchanged |
| widget, app-pair parent/member, legacy shortcut | none | always `Preserved` |

Every `Preserved` target is the exact domain-value conversion of its
`CapturedPlacement`; preservation never changes container, cell, rank, span, or
occupied region. A result violating this table or changing a moved captured
item's span is not a valid `Planned` result.

### Diagnostics

`Warning`, `UnplacedItem`, `RejectionReason` are distinct types (AC-3). Stable
codes + opaque typed params; no localized prose, raw
package names, or profile serials.

```text
Warning         { code: WarningCode,     params: [DiagnosticParam] }
UnplacedItem    { item: ItemId, requiredSpan: GridSpan, reason: UnplacedReason }
RejectionReason { code: RejectionCode,    params: [DiagnosticParam] }

DiagnosticParam =
    | ItemParam(ItemId) | KindParam(KindCode) | ContainerCodeParam(ContainerCode)
    | SpanParam(GridSpan) | RankParam(Int)
    | DimensionParam(DeviceDimension, Int)
    | PageParam(PageId) | CategoryParam(CategoryId)

DeviceDimension = COLUMNS | ROWS | HOTSEAT_SLOTS | FOLDER_MAX_COLUMNS | FOLDER_MAX_ROWS

WarningCode     = LEGACY_SHORTCUT_REVIEW | FALLBACK_CATEGORY | UNAVAILABLE_PRESERVED
UnplacedReason  = EXCEEDS_GRID_DIMENSIONS | TARGET_UNAVAILABLE
RejectionCode   = UNKNOWN_ITEM_KIND | INVALID_CONTAINER | UNKNOWN_PAGE | BOUNDS_VIOLATION
    | OVERLAP | DANGLING_REFERENCE | MALFORMED_APP_PAIR | LOCKED_OUT_OF_BOUNDS
    | DUPLICATE_TARGET | MISSING_TARGET | INCOMPLETE_TARGET_PARTITION
    | ADDITIONS_UNDER_FULL_ORGANIZATION | INVALID_RULES | DUPLICATE_ITEM_ID
    | DUPLICATE_PAGE | INVALID_DIMENSIONS | KIND_TARGET_MISMATCH
    | TARGET_PROFILE_MISMATCH | UNKNOWN_SIGNAL_ITEM | UNKNOWN_CATEGORY
```

No generic string/token parameter. Each `DiagnosticParam` variant is used by at
least one [validation rule](#canonical-validation-rules).

## Public seam

```kotlin
fun interface OrganizationPlanner {
    fun plan(input: OrganizationInput): PlanningResult
}
```

The `plan` method is the only public operation of the Organization Planning
module (`DESIGN.md` §4.1). The interface permits the black-box harness to be
built before the production implementation;
both use identical public types and the same method. The production adapter is
referentially transparent and performs no I/O. Tests do not mock internal sort,
classification, or bin-packing helpers
([quality-strategy](../../docs/engineering/quality-strategy.md)).

## Invariant evidence

`DESIGN.md` §5 is the sole source for invariant definitions. This table maps
each invariant to observable contract evidence; it does not restate invariant
prose.

| DESIGN INV | Observable contract evidence |
|---|---|
| INV-1 Conservation | Every `ItemId` in `snapshot.items ∪ additions` → exactly one `PlannedPlacement.item`; `Moved`/`Preserved`; no `Deleted` in v1 |
| INV-2 No overlap | Pairwise-disjoint regions per container; unique dock ranks |
| INV-3 In bounds | Every `PlacementTarget` fits `DeviceCapabilities`; dock `rank ∈ [0, hotseatSlots-1]` |
| INV-4 Referential integrity | Every ref resolves to exactly one entity; acyclic |
| INV-5 Lock preservation | `locked=true` → `Preserved{LOCKED}`, target = captured, no overlap; captured OOB → V-19 |
| INV-6 Profile isolation | `ProfileId` never changes; `NewFolder` single-profile; mixed-profile existing containers preserved as-is |
| INV-7 Determinism | Value-equal input → value-equal result including list ordering and ordinals |
| INV-8 Idempotence | Applied-plan re-input with equivalent membership → empty effective change set |
| INV-9 Convergence | Conditional: empty diff only when pre-existing layout was canonical; otherwise conservation-safe |

## Canonical validation rules

Each rule defined once. Scenarios, ACs, and oracle refer to these IDs.

| ID | Trigger (constructible through public types) | Outcome | Code | Params |
|---|---|---|---|---|
| V-01 | `kind = Unknown(KindCode)` | `Invalid` | `UNKNOWN_ITEM_KIND` | `KindParam` |
| V-02 | captured `placement = UnsupportedContainer` | `Invalid` | `INVALID_CONTAINER` | `ContainerCodeParam` |
| V-03 | `PageRef` to absent `PageId` | `Invalid` | `UNKNOWN_PAGE` | `PageParam` |
| V-04 | captured cell/span or dock rank outside `DeviceCapabilities` | `Invalid` | `BOUNDS_VIOLATION` | `SpanParam` or `RankParam` |
| V-05 | captured regions overlap in a container | `Invalid` | `OVERLAP` | — |
| V-06 | container ref unresolved/non-unique; folder membership not bidirectional/unique, has a disallowed child kind, assigns one child to multiple containers, or forms a cycle | `Invalid` | `DANGLING_REFERENCE` | `ItemParam` |
| V-07 | malformed `AppPairMetadata` (count/distinct ids/stages/kind/snap/coherence) | `Invalid` | `MALFORMED_APP_PAIR` | `ItemParam` |
| V-08 | two `CapturedItem` share `ItemId` | `Invalid` | `DUPLICATE_ITEM_ID` | `ItemParam` |
| V-09 | two `Page` share `PageId`/`PageOrder` | `Invalid` | `DUPLICATE_PAGE` | `PageParam` |
| V-10 | non-positive span/device dimension | `Invalid` | `INVALID_DIMENSIONS` | `SpanParam` or `DimensionParam` |
| V-11 | captured `ItemKind`/`TargetKey` or candidate `CandidateKind`/`CandidateTarget` mismatch; or captured structure-field presence does not match `ItemKind` | `Invalid` | `KIND_TARGET_MISMATCH` | `ItemParam` |
| V-12 | captured or candidate target profile ≠ item `ProfileId` | `Invalid` | `TARGET_PROFILE_MISMATCH` | `ItemParam` |
| V-13 | signal `item` outside inventory | `Invalid` | `UNKNOWN_SIGNAL_ITEM` | `ItemParam` |
| V-14 | signal `candidate` not in `allowedCategories` | `Invalid` | `UNKNOWN_CATEGORY` | `CategoryParam` |
| V-15 | `ItemId` duplicated in `existing`/`additions` or candidate-captured collision | `Invalid` | `DUPLICATE_TARGET` | `ItemParam` |
| V-16 | `existing` references absent `ItemId` | `Invalid` | `MISSING_TARGET` | `ItemParam` |
| V-17 | captured `ItemId` absent from all `existing` | `Invalid` | `INCOMPLETE_TARGET_PARTITION` | — |
| V-18 | `additions` non-empty under `FullOrganization` | `Invalid` | `ADDITIONS_UNDER_FULL_ORGANIZATION` | — |
| V-19 | `locked=true` region does not fit `DeviceCapabilities` | `Invalid` | `LOCKED_OUT_OF_BOUNDS` | `SpanParam` |
| V-20 | unaccepted `RuleVersion`; `minGroupSize<2`; dup `CategoryId`; bad `fallbackCategory` | `Invalid` | `INVALID_RULES` | — |
| V-21 | `CandidateItem.span` exceeds `(columns, rows)` | `Impossible` | `EXCEEDS_GRID_DIMENSIONS` | — |
| V-22 | `CandidateItem.availability != AVAILABLE` | `Impossible` | `TARGET_UNAVAILABLE` | — |

## Preservation-reason precedence

When attributes intersect, exactly one reason is reported, highest first:

`LOCKED > UNAVAILABLE_TARGET > DOCK > WIDGET > APP_PAIR > LEGACY_SHORTCUT > NON_TARGET > STRUCTURAL > ALREADY_CANONICAL`

Locked+unavailable → `LOCKED`; locked+dock → `LOCKED`; unavailable+dock →
`UNAVAILABLE_TARGET`; widget outside target set → `WIDGET` (not `NON_TARGET`).

The predicates are: `LOCKED` for any locked captured item;
`UNAVAILABLE_TARGET` for any unlocked captured item whose availability is not
`AVAILABLE`; `DOCK` for an available unlocked Dock item; `WIDGET` for an
available unlocked widget; `APP_PAIR` for an app-pair parent and its members;
`LEGACY_SHORTCUT` for a legacy shortcut; `NON_TARGET` for an otherwise movable
item whose membership is `Preserved`; and `STRUCTURAL` for a folder member whose
parent is preserved as a unit. `ALREADY_CANONICAL` is the lowest-priority
reason for an otherwise movable item when its computed canonical target is
value-equal to its captured target. Higher predicates always win.

## Canonicalization and versioning

At entry to `plan`, input is canonicalized: pages by `PageOrder`; inventory by
`ItemId` byte ordering; signals by `ItemId`/`SignalSource`/`CategoryId`;
`TargetSet` by `ItemId`. Exact duplicate signals canonicalize to one. The
canonical form solely determines output (INV-7).

Output collections also have a canonical representation: `placements` and
`categories` by `ItemId`; `newPages` by `NewPageOrdinal`; `newFolders` by
`NewFolderOrdinal`; each new folder's members by `FolderMember.rank` then
`ItemId`; warnings and rejection reasons by `(code, canonical params)`;
unplaced items by `ItemId`. `DiagnosticParam` lists use their declared order.
No public result collection is order-insensitive.

`RuleSemantics.version`, `TaxonomyContract.version`, and
`LayoutSnapshot.revision` are echoed in `PlanningResult`. Staleness is detected outside the seam (caller compares echoed
`revision`). The planner is stale-independent.

## Error semantics

**Invalid** — structurally broken input. V-01–V-20; constructible through public
types; no plan produced. **Impossible** — valid input, unsatisfiable candidate.
V-21–V-22; lists every `UnplacedItem`. The two are disjoint: captured bounds
defect = `Invalid` (V-04); candidate that cannot fit = `Impossible` (V-21).
**Stale-independent** — no clock, delay, or live read; caller detects staleness
([item preservation policy](../../docs/product/item-preservation-policy.md)
§5.1, §5.4).

## Behavior scenarios

These are downstream conformance scenarios verified through
`OrganizationPlanner.plan(OrganizationInput) -> PlanningResult`. No internal
mocks. All are constructible from the Issue #10 public types.

### S-01: valid full organization produces a plan

**G** movable `APPLICATION` items, valid bounds, signals, no additions, v1 rules.
**W** `plan` under `FullOrganization`.
**T** `Planned`; INV-1–3 hold; `revision`/`ruleVersion`/`taxonomyVersion` echoed.
> FR-001, NFR-003; AC-1, AC-4.

### S-02: conservation over captured + candidate union

**G** snapshot mixing all supported kinds + two `CandidateItem` additions.
**W** `plan` under `IncrementalPlacement`.
**T** `PlannedPlacement` count = captured + candidates; each `Moved`/`Preserved`;
no `Deleted`.
> FR-002, NFR-002; AC-2.

### S-03: preserved occupancy and reason precedence

**G** widget `(2,2)`, dock item rank 0, app pair, locked-unavailable item, locked
dock item — all `Preserved`.
**W** `plan` runs.
**T** each `Preserved`, no overlap; locked-unavailable → `LOCKED`; locked-dock →
`LOCKED`.
> FR-003, NFR-002; AC-3, AC-9.

### S-04: locked placement and region immutable

**G** locked `APPLICATION` at `(3,4)` and locked `FOLDER` at `(0,0)` + locked
children.
**W** `plan` runs.
**T** both `Preserved{LOCKED}`, targets unchanged, no overlap with locked
regions (INV-5).
> FR-003, NFR-002; AC-9.

### S-05: profile isolation and same-package distinctness

**G** same `component` under two `ProfileId`; items sharing `CategoryId` but
different profiles.
**W** `plan` with v1 rules.
**T** distinct placements; never same `NewFolder`; `ProfileId` unchanged (INV-6).
> FR-002, NFR-002; AC-2.

### S-06: folder creation and fallback contrast

**G** (a) same-profile items resolving to same `CategoryId`; (b) items whose only
signal is `S6` → `OTHER`/`FALLBACK`.
**W** `plan` runs.
**T** (a) `NewFolder` created, members `Moved{FOLDER_MEMBER}`, profile correct;
(b) singletons, no `NewFolder`, `FALLBACK_CATEGORY` warning per item.
> FR-011, FR-015, NFR-003; AC-10, AC-11.

### S-07: user override respected

**G** item with `S1` signal → `TOOLS`.
**W** `plan` runs.
**T** `CategoryDecision.category = TOOLS`, `decidedSignal = S1`,
`confidence = EXPLICIT`; grouped only with same-profile same-category.
> FR-010, FR-011; AC-10.

### S-08: determinism, metamorphic, locale-independence

**G** same input; reordered input; different locale/timezone/thread.
**W** `plan` on each.
**T** all results value-equal including ordinals (INV-7).
> NFR-003, NFR-005; AC-4.

### S-09: idempotence

**G** `P = plan(input)` `Planned` under `FullOrganization`.
**W** re-`plan` on applied snapshot, equivalent membership.
**T** empty effective change set; otherwise movable unchanged items are
`Preserved{ALREADY_CANONICAL}`; `newPages`/`newFolders` empty (INV-8).
> NFR-004; AC-8.

### S-10: convergence (conditional and conservative)

**G** `IncrementalPlacement` plan `P` with candidate additions.
**W** `FullOrganization` over post-`P` state.
**T** if pre-existing layout was canonical → empty change set. If not →
deterministic, conservation-safe (INV-1, INV-6): no unexplained loss; a full run
may move noncanonical items including previously placed candidates.
> NFR-003; INV-9; AC-8.

### S-11: incremental targeting and overflow

**G** (a) `IncrementalPlacement` with two candidates + one `Movable`; (b) more
movable items than fit on captured pages.
**W** `plan` runs.
**T** (a) only additions + `Movable` may `Moved`; (b) `newPages` sufficient,
`NewPage.order` > all captured, ordinals ascending.
> FR-001, FR-002, NFR-002; AC-11, AC-12.

### S-12: legacy shortcut preserved and warned

**G** `SHORTCUT_LEGACY` with valid placement (`LegacyShortcutKey`).
**W** `plan` runs.
**T** `Planned`; `Preserved{LEGACY_SHORTCUT}`; `LEGACY_SHORTCUT_REVIEW` warning;
not moved/deleted/excluded.
> FR-015, NFR-002; AC-3.

### S-13: unavailable captured target preserved; unavailable candidate impossible

**G** (a) captured item whose availability is not `AVAILABLE`; (b)
`CandidateItem` whose availability is not `AVAILABLE`.
**W** `plan` runs.
**T** (a) `Preserved{UNAVAILABLE_TARGET}` + `UNAVAILABLE_PRESERVED` warning; (b)
`Impossible` with `TARGET_UNAVAILABLE`.
> FR-002, FR-015, NFR-002; AC-3, AC-7.

### S-14: invalid — unknown kind, unsupported container, structural defects

**G** `Unknown(KindCode)` (V-01); `UnsupportedContainer` (V-02); overlap (V-05);
bounds (V-04); duplicate `ItemId` (V-08); duplicate page (V-09); bad dimensions
(V-10); captured or candidate kind/target mismatch (V-11); captured or
candidate target-profile mismatch (V-12).
**W** `plan` runs.
**T** `Invalid` with matching typed code + param; no plan.
> NFR-002; AC-13, AC-15.

### S-15: invalid — malformed app pair

**G** `APP_PAIR` with V-07 defect (count/distinct ids/stages/kind/snap/coherence).
**W** `plan` runs.
**T** `Invalid`, `MALFORMED_APP_PAIR` + `ItemParam`.
> NFR-002; AC-16.

### S-16: invalid — locked OOB, malformed target set, bad rules/taxonomy

**G** locked OOB (V-19); `TargetSet` defects (V-15–V-18); bad rules/taxonomy
(V-20).
**W** `plan` runs.
**T** `Invalid` with matching code; no plan.
> FR-003, FR-011, NFR-003; AC-7, AC-14.

### S-17: invalid — bad signal/category; tie-break deterministic

**G** signal outside inventory (V-13) or category (V-14); same `item`+`source`
with different `candidate` + exact duplicate.
**W** `plan` runs (twice for tie-break).
**T** `Invalid` with `UNKNOWN_SIGNAL_ITEM`/`UNKNOWN_CATEGORY`; tie resolves by
canonical `CategoryId` ordering; duplicate canonicalizes away.
> FR-011, NFR-003; AC-17.

### S-18: impossible — oversized candidate

**G** `CandidateItem.span` exceeds `(columns, rows)` (V-21).
**W** `plan` runs.
**T** `Impossible`; `UnplacedItem` with `EXCEEDS_GRID_DIMENSIONS`; disjoint from
captured bounds defect (V-04 `Invalid`).
> FR-003, NFR-002; AC-7.

### S-19: warnings non-fatal and type-distinct

**G** valid snapshot with `SHORTCUT_LEGACY` + `S6`-resolved item.
**W** `plan` runs.
**T** `Planned`; `LEGACY_SHORTCUT_REVIEW` + `FALLBACK_CATEGORY` warnings; distinct
type from `UnplacedItem`/`RejectionReason`.
> FR-015; AC-3.

### S-20: stale-independent and no leakage

**G** valid snapshot; public type set.
**W** `plan` runs; surface inspected.
**T** deterministic, input-only; echoed `revision`; no DB/clock/network. No
`android.*`/DB/UI/I/O types. Same types as production.
> NFR-003, NFR-005, NFR-010; AC-1, AC-6.

## Data and state

- **Reads:** `OrganizationInput` only. **Writes:** none. **Retention:** nothing
  between calls.
- **Identity:** captured handles opaque; `CandidateItem.id` shares `ItemId`
  namespace; plan-local ordinals in disjoint namespaces.
- **Migration:** rule/taxonomy upstream (Rule Management). **Backup:** no
  interaction; restore remapping outside a run ([item preservation
  policy](../../docs/product/item-preservation-policy.md) §3.5).

## Permissions, privacy, and security

No permission added; no network/I/O. All data in caller memory (NFR-005).
Diagnostics carry stable codes + opaque params; no raw packages/serials/prose.
Exposure tracked by [Issue #16](https://github.com/nunu1733/NunuLauncher/issues/16).

## Accessibility and localization

No UI. Supports accessibility indirectly via structured diagnostics (FR-015),
deterministic ordering, and locale independence.

## Requirement mapping

| Req | Evidence | Scenarios |
|---|---|---|
| FR-001 | `plan` pure; plan + diagnostics, no side effect | S-01, S-11, S-20 |
| FR-002 | `TargetSet` partitions captured + candidates; conservation | S-02, S-03, S-05, S-11, S-13 |
| FR-003 | locked immutable; captured OOB `Invalid`; candidate `Impossible` | S-03, S-04, S-16, S-18 |
| FR-010 | user override as `S1`; resolved and exposed | S-07 |
| FR-011 | signals input; resolution hidden/deterministic; taxonomy decidable | S-06, S-07, S-08, S-16, S-17 |
| FR-015 | distinct types; stable codes + params; precedence | S-06, S-12, S-13, S-15, S-18, S-19 |
| NFR-002 | INV-1–6 contract evidence | all invalid/impossible + conservation |
| NFR-003 | determinism + canonicalization + constructible policy | S-01, S-06, S-08, S-09, S-10, S-17 |
| NFR-004 | idempotence; convergence conditional | S-09, S-10 |
| NFR-005 | pure, offline, no I/O | S-20 |
| NFR-010 | single seam, no platform types, deterministic ordinals | S-20 |

## Issue #10 acceptance criteria

These criteria accept the contract definition and public type surface. They do
not require the downstream concrete planner or reusable fixture/property
harness.

- [ ] **AC-1** No Android/DB/UI/I/O type in `plan`'s public surface. (crit 1;
  NFR-005, NFR-010)
- [ ] **AC-2** Shapes express conservation over captured ∪ candidate inventory,
  profile identity, locked occupancy. (crit 2; FR-002, FR-003, NFR-002)
- [ ] **AC-3** `PlannedPlacement`/`Disposition`, `Warning`, `UnplacedItem`,
  `RejectionReason` are distinct types, and the normative precedence is
  defined. Executable precedence resolution belongs to the concrete planner. (crit 3;
  FR-015)
- [ ] **AC-4** Canonicalization point and rule/taxonomy version plus revision echo explicitly
  defined. (crit 4; NFR-003)
- [ ] **AC-5** Representative contract tests invoke a test adapter through
  `OrganizationPlanner.plan`, the same interface and method that the future
  production adapter must satisfy; no internal planner helper is mocked. (crit
  5; NFR-010)
- [ ] **AC-6** No layout algorithm or DB apply in spec. (crit 6; non-goals)
- [ ] **AC-7** `Invalid`/`Impossible` are disjoint result types and each carries
  typed reasons for V-01–V-22. Rule execution belongs to the concrete planner. (FR-003,
  NFR-002)
- [ ] **AC-8** Idempotence and conditional-convergence semantics are defined
  using equivalent membership. Executable properties belong to the downstream
  harness and concrete planner. (NFR-003, NFR-004)
- [ ] **AC-9** Profile isolation and lock preservation are representable and
  mapped to INV-5/INV-6 evidence. Executable enforcement belongs to the concrete planner.
  (FR-003, NFR-002)
- [ ] **AC-10** The seam consumes raw signals and exposes `CategoryDecision`;
  `TaxonomyContract` makes `UNKNOWN_CATEGORY` decidable. Resolution behavior is
  specified here and implemented by the concrete planner. (FR-010, FR-011)
- [ ] **AC-11** `NewPage`/`NewFolder` expose disjoint plan-local ordinal types
  and their canonical ordering is defined. Allocation belongs to the concrete planner.
  (FR-002, NFR-003)
- [ ] **AC-12** `TargetSet` is constructible list model with V-15–V-18
  validation. (FR-002, NFR-002)
- [ ] **AC-13** `Unknown(KindCode)` and `UnsupportedContainer(ContainerCode)`
  constructible through public seam. (NFR-002)
- [ ] **AC-14** `RuleSemantics`/`TaxonomyContract` are canonical, comparable,
  and closed over v1 enums; V-20 is the defined `INVALID_RULES` mapping.
  (FR-011, NFR-003)
- [ ] **AC-15** Structural defects V-08–V-12 constructible, typed codes/params.
  (NFR-002)
- [ ] **AC-16** App-pair validity is constructible via `AppPairMetadata`; the
  platform-rank-independent `MALFORMED_APP_PAIR` mapping is defined. (NFR-002)
- [ ] **AC-17** Signal validity/tie-break contract is defined (V-13/V-14). Every supported
  item has legal identity shape (`LegacyShortcutKey`, `FolderKey`/`FolderId`,
  `AppPairKey`/`AppPairId`). `CandidateKind` restricted to
  `APPLICATION`|`DEEP_SHORTCUT`. (FR-011, NFR-003)

## Downstream conformance oracle

The reusable harness implements this oracle and every concrete planner must
pass it through the public `OrganizationPlanner.plan` seam ([quality-strategy §Organization
Planning interface](../../docs/engineering/quality-strategy.md)). This table is
not the Issue #10 implementation checklist.

| AC | Evidence |
|---|---|
| AC-1 | Static check: no public symbol references platform/DB/UI/I/O types |
| AC-2 | Fixture tests: captured ∪ candidate accounted, profiles distinct, locks preserved |
| AC-3 | Fixture tests: warnings/unplaced/reasons separate types; precedence one reason |
| AC-4 | Tests: echoed `revision`/`ruleVersion`/`taxonomyVersion` + canonical output ordering + reordering-invariance |
| AC-5 | Tests call only `plan(...)` with public builders; no mocking |
| AC-6 | Review: no algorithm/apply in spec |
| AC-7 | Fixture tests per V-rule returning typed `Rejected` |
| AC-8 | Determinism + idempotence + metamorphic + conditional-convergence property tests |
| AC-9 | Profile-isolation + lock-preservation property/fixture tests |
| AC-10 | Tests: raw signals + `TaxonomyContract` → resolved `CategoryDecision`; `UNKNOWN_CATEGORY` decidable |
| AC-11 | Tests: ordinals deterministic, namespaces disjoint |
| AC-12 | Tests: duplicate/missing/incomplete/mode-mismatch `TargetSet` |
| AC-13 | Tests: `Unknown(KindCode)` + `UnsupportedContainer` construct + reject |
| AC-14 | Tests: closed v1 policies; `INVALID_RULES` triggers; taxonomy defects |
| AC-15 | Tests: each structural defect V-08–V-12 |
| AC-16 | Tests: `AppPairMetadata` malformed cases |
| AC-17 | Tests: out-of-inventory/taxonomy signals; ties; identity shapes; candidate restriction |

Property tests (conservation, overlap, bounds, lock, profile, determinism,
idempotence, convergence) over generated inputs. Fixtures from [item
preservation policy](../../docs/product/item-preservation-policy.md) §6 (F-01–F-20)
and [layout strategy v1](../../docs/product/layout-strategy-v1.md) §10 (L-01–L-20).

## Issue #10 verification

Issue #10 uses a small representative suite, not the downstream harness:

- compile every public contract type without Android/DB/UI/I/O references;
- construct each closed input/output/diagnostic family, including malformed
  sentinel variants required by AC-13–AC-17;
- verify opaque-handle value equality and locale-independent canonical ordering;
- implement a test-only `OrganizationPlanner` adapter returning a supplied
  `PlanningResult`, invoke `plan`, and verify all top-level outcome variants can
  cross the seam;
- scan every production source file in the planning package and fail on any
  forbidden platform/DB/UI/I/O package prefix, including aliased imports,
  typealiases, and fully qualified references. This whole-package gate is
  intentionally stricter than public-surface-only leakage.

## Open questions

- **Frequency signals (FR-013):** v1 classification only; future extension must
  preserve determinism/offline.
- **Deletion disposition:** absent in v1; requires accepted rule + recovery +
  migration.
- **Empty-folder deletion:** v1 preserves; behavior and future
  explicit-deletion eligibility are owned by
  [spec 24](../24-empty-folder-policy/spec.md)
  ([Issue #24](https://github.com/nunu1733/NunuLauncher/issues/24)).
- **Lock persistence:** ADR-0004 owns storage; this planning seam remains
  behavior-only and consumes typed lock state.
- **Rule file format:** seam requires typed `RuleSemantics` + `TaxonomyContract`;
  format is Rule Management's concern.

## Change history

- 2026-08-15: Recorded the Issue #24 reference in open questions (owner:
  spec 24); no contract, type, or behavior change.
- 2026-08-10: Issue #33 makes `PageOrder` an unbounded canonical decimal
  domain value so a new page after any captured order remains constructible.
- 2026-08-10: Issue #31 adds the lowest-priority
  `ALREADY_CANONICAL` preservation reason so an idempotent full replan can
  represent an unchanged `ExistingRole.Movable` item without misusing
  `NON_TARGET`.
- 2026-08-10: Accepted after Codex Standards/Spec review. Defines the pure `plan` seam with closed
  placement variants (`CapturedPlacement`/`PlacementTarget`), typed identity
  (`FolderKey`/`FolderId`, `AppPairKey`/`AppPairId`, `LegacyShortcutKey`),
  explicit `TaxonomyContract`, restricted `CandidateKind`, closed `RuleSemantics`
  v1 enums, deterministic preservation-reason precedence, conservative
  convergence, and a single canonical validation table (V-01–V-22). Eliminates
  `ContainerInstance` and nullable all-purpose `Placement`. Maps DESIGN
  invariants via compact evidence table.
