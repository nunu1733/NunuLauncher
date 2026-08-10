---
issue: "#12"
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
  - NFR-006
  - NFR-010
updated: 2026-08-10
source:
  - ../10-pure-organization-planning/spec.md
  - ../11-planner-fixture-property-harness/spec.md
  - ../../docs/product/item-preservation-policy.md
  - ../../docs/product/layout-strategy-v1.md
  - ../../docs/product/category-taxonomy-v1.md
  - ../../DESIGN.md
---

# Deterministic full-layout planning v1

## Problem

Issue #10 provides the pure `OrganizationPlanner.plan` contract and Issue #11
provides its black-box conformance harness, but no concrete planner produces a
layout. The Issue #5 and #6 research documents also predate the accepted public
types, so their proposed rules must be closed here without inventing platform
data that `OrganizationInput` does not carry.

## Outcome

One pure planner returns a complete, deterministic `PlanningResult` for both
run modes. Validation, category resolution, preservation, folder formation,
workspace allocation, overflow, and explanations remain behind the existing
`OrganizationPlanner.plan` interface. No Android, DB, UI, I/O, network, clock,
random, or mutable global state is used.

## Scope

- Execute the accepted V-01–V-22 contract and Issue #11 harness.
- Resolve S1–S6 signals supplied in `ClassificationSignals`.
- Preserve constrained targets and allocate movable workspace units.
- Form same-profile folders and allocate plan-local pages/folders.
- Produce canonical dispositions, category decisions, warnings, and rejections.
- Verify determinism, idempotence, conditional convergence, and a repeatable
  performance sample.

## Non-goals

- Changing the Issue #10 public contract or Issue #11 harness interface/corpus.
- Reading package metadata or evaluating Android, Google, system, intent, or
  package-name rules. Adapters supply those observations as S1–S6 signals.
- DB apply, stale detection, transaction, recovery, UI, package events, rule
  persistence, or persistent ID assignment.
- Dock reorganization, deletion, drawer-wide addition, usage/frequency signals,
  or external classification.
- Lock persistence (#23), empty-folder deletion (#24), performance budgets
  (#15), or privacy policy (#16).

## Authority and adopted refinements

The accepted Issue #10 types and semantics take precedence over the proposed
Issue #5/#6 research shapes. This spec adopts their device-derived regions,
row-major search, category priority, profile isolation, preserved Dock, and
unlimited page overflow with these necessary refinements:

- `SignalSource` is exactly `S1` through `S6`; S5a/S5b are adapter-side evidence
  represented as `S5`, not planner-visible variants.
- Opaque `ComponentKey` is not parsed as a package name. Planner ordering uses
  typed `ProfileId`, `CategoryId`, and `ItemId` values only.
- `FallbackCategoryPolicy.KEEP_AS_SINGLETON` applies whenever the resolved
  category equals `taxonomy.fallbackCategory`, regardless of which signal
  selected it.
- Existing top-level singleton items keep their captured page in a full run;
  row-major canonicalization occurs within that page. Category grouping may
  replace members from several pages with one new folder on the earliest
  contributing page.
- New pages have no numeric count cap. A fitting, available candidate therefore
  cannot become impossible merely because captured pages are full.

## Observable rules

The rules below are the Issue #12 behavior delta. Issue #10 remains the sole
definition of public shapes, canonical input/output ordering, V-01–V-22,
diagnostic types, and preservation precedence. `DESIGN.md` §5 remains the sole
definition of system invariants.

### P-01: rejection before planning

Canonicalize as specified by Issue #10, then evaluate all V-01–V-20 predicates.
Collect one `RejectionReason` per affected subject and defect, de-duplicate
value-equal reasons, and return `Rejected.Invalid` in this total order if the
collection is non-empty: `RejectionCode` declaration order, then a
lexicographic comparison of `params`. Parameter variant order is `ItemParam`,
`KindParam`, `ContainerCodeParam`, `SpanParam`, `RankParam`, `DimensionParam`,
`PageParam`, `CategoryParam`; values use their accepted canonical comparison,
`GridSpan` uses `(width,height)`, `DimensionParam` uses
`(DeviceDimension declaration order,value)`, and a shorter equal-prefix list
sorts first. V-21/V-22 are not evaluated in that case.

Only after V-01–V-20 pass, evaluate V-21/V-22 for every candidate. Return one
`UnplacedItem` per candidate/reason pair, ordered by `ItemId` then
`UnplacedReason`, when any candidate fails. `Rejected.Invalid.warnings` and
`Rejected.Impossible.warnings` are empty. Rejected outcomes contain no partial
plan and no input is repaired.

### P-02: category decisions

Every captured or candidate `APPLICATION`/`DEEP_SHORTCUT` receives exactly one
decision, including preserved items and folder/app-pair members.

1. Exact duplicate signals collapse.
2. For one `(ItemId, SignalSource)`, the smallest `CategoryId` wins.
3. The first present source in `S1 > S2 > S3 > S4 > S5 > S6` wins.
4. With no signal, use `taxonomy.fallbackCategory`, `S6`, and `FALLBACK`.
5. Confidence is `EXPLICIT` for S1/S2, `RULE` for S3/S4, and `FALLBACK` for
   S5/S6.

The planner never derives S2–S5 from `TargetKey`. An S6 decision emits exactly
`Warning(FALLBACK_CATEGORY, [ItemParam(item)])`.

### P-03: preservation and unchanged results

Apply the Issue #10 precedence exactly. Every higher-priority preservation
predicate keeps the captured target exactly. Additional observable rules are:

- An app-pair parent and both members retain their app-pair references.
- An existing folder's children retain membership and rank. A child without a
  higher predicate is `STRUCTURAL`.
- `ExistingRole.Preserved` is `NON_TARGET` unless a higher predicate applies.
- An otherwise movable item whose selected target equals its captured target is
  `ALREADY_CANONICAL`; this is the lowest-priority reason from Issue #31.
- A legacy shortcut emits exactly
  `Warning(LEGACY_SHORTCUT_REVIEW, [ItemParam(item)])`.
- Any unavailable captured item emits `UNAVAILABLE_PRESERVED`, even when
  `LOCKED` wins its disposition reason; its exact value is
  `Warning(UNAVAILABLE_PRESERVED, [ItemParam(item)])`.

Warnings are one value per affected item and use Issue #10 canonical ordering.

### P-04: eligible folder members

In `FullOrganization`, eligible members are top-level, available, unlocked
`APPLICATION` or `DEEP_SHORTCUT` items with `ExistingRole.Movable`. In
`IncrementalPlacement`, only available candidate additions are eligible;
captured items are not drawn into a new folder.

Group by `(ProfileId, resolved CategoryId)`. A group stays singleton when its
category equals `taxonomy.fallbackCategory`, its size is below
`folderPolicy.minGroupSize`, or folder capacity is below that minimum. No item
joins an existing folder in v1. Existing folders only move as intact workspace
units.

Group order is `(ProfileId, CategoryId)` and member order is `ItemId`, using the
typed canonical comparisons. Cross-profile folders are forbidden.

### P-05: folder capacity and partition

Capacity is the mathematical product
`folderMaxColumns * folderMaxRows`, without fixed-width overflow. For each
ordered eligible group:

1. Take complete capacity-sized chunks.
2. If the remainder is at least `minGroupSize`, it is another folder.
3. If `0 < remainder < minGroupSize`, move the minimum required suffix from
   the preceding chunk only when both resulting chunks remain within
   `[minGroupSize, capacity]`.
4. Otherwise the remainder stays as ordered singleton items.

No folder exceeds capacity and no folder has fewer than `minGroupSize` members.
Folders and singleton residues retain the original member order. Folder
ordinals are assigned in group/chunk order from zero. Member rank is the index
in that ordered chunk; `NewFolder.members` uses the same rank order. Every
member targets that `NewFolderOrdinal` with `Moved(FOLDER_MEMBER)`. A new folder
has span `(1,1)` and the group's profile.

### P-06: workspace units and ordering

A workspace unit is an intact movable existing folder, a new folder, or a
movable singleton. Its span is the captured span, candidate span, or `(1,1)`
for a new folder.

For a full run, the preferred page is the captured page for an existing unit
and the lowest `(PageOrder, PageId)` among contributors for a new folder. For
an incremental run, every captured item remains at its captured target and only
candidate/new-folder units require allocation.

For a full run, process preferred-page groups by `(PageOrder, PageId)`. Within
each group, allocate in this order:

1. existing folder units by `ItemId`;
2. new folders by `NewFolderOrdinal`;
3. singleton units by `(ProfileId, CategoryId, ItemId)`.

Preserved workspace regions occupy their captured cells before allocation.
Search cells row-major (`y`, then `x`) and mark the complete rectangular span.
If a full-run unit cannot fit its preferred captured page, or an incremental
unit cannot fit any captured page in `PageOrder` order, allocate it on new
pages. New pages are reused until full before another is created.

For an incremental run, the global allocation stream is new folders by
`NewFolderOrdinal`, then remaining candidate singletons by
`(ProfileId, CategoryId, ItemId)`. Each unit scans every captured page by
`(PageOrder, PageId)`, then already-created new pages by `NewPageOrdinal`,
before creating another page. Thus the earlier unit deterministically receives
the last captured vacancy.

Existing empty pages remain and are eligible. Existing page identity/order is
unchanged. New pages have ordinals `0..n-1` and orders immediately following
the greatest captured order; with no captured page, order starts at zero.

### P-07: dispositions

- A changed existing singleton or candidate workspace target is
  `Moved(SINGLE_PLACEMENT)`.
- A changed existing folder workspace target is `Moved(FOLDER_UNIT)`.
- A new-folder member is `Moved(FOLDER_MEMBER)`.
- Otherwise use the exact P-03 preservation reason, including
  `ALREADY_CANONICAL` for an otherwise movable unchanged item.

Every inventory item has exactly one placement. Existing folder/app-pair
members never receive a newly selected rank or container.

### P-08: run modes

`FullOrganization` rejects additions via V-18 and recomputes all movable
top-level targets under P-02–P-07. `IncrementalPlacement` preserves every
captured target, even when its membership is `Movable`; it classifies all
inventory but allocates only additions. A rejected incremental run returns no
partial placements for otherwise placeable additions.

### P-09: determinism and totality

Result values are independent of locale, timezone, calling thread, and every
enumeration declared non-semantic by Issue #10/#11. Production code uses no
clock, random source, environment/default locale, global cache, I/O, or
platform type. `plan` does not intentionally throw for an input constructible
through the public values; it returns the typed outcome defined by the accepted
contract.

### P-10: idempotence and convergence

After an Issue #11 materialization of a planned full result, a second full run
returns every target unchanged, uses the exact preservation reason (including
`ALREADY_CANONICAL` where applicable), and returns no `Moved`, `newPages`, or
`newFolders`.

After an incremental result, a full run is empty only when the pre-existing
layout was already full-canonical. Otherwise it may move noncanonical items,
but remains deterministic and preserves identity, profile, and membership
constraints.

### P-11: capacity outcomes

Because v1 may add pages without a count cap, every available unit whose span
fits an empty device page is placeable. `Impossible` is reachable only through
accepted V-21/V-22: an oversized candidate or an unavailable candidate. A full
captured grid alone produces a planned result; it does not imply rejection.

### P-12: output explanations

`categories`, dispositions, warnings, and rejections use only accepted typed
values. The planner exposes no localized prose or package/profile raw data in a
diagnostic. Result collections and plan-local ordinals follow Issue #10
canonical ordering; `NewFolder.members` follows its unique member ranks.

## Representative examples

The following table closes the previously proposed examples after applying
P-01–P-12.

| Evidence | Required observable result |
|---|---|
| Issue #11 `empty-home` | Planned; no placements, new pages, new folders, categories, or warnings. The captured empty page remains input state, not a `NewPage`. |
| `apps-only` | `app.calc` and `app.game` resolve S3/GAMES/RULE and form new folder 0 with ranks in `ItemId` order; `app.tools` resolves S1/TOOLS/EXPLICIT and is a singleton. |
| `mixed-app-shortcut-widget` | Exact preservation map from the corpus; S6 shortcut is OTHER/FALLBACK; legacy and fallback warnings present. |
| `folder-container-integrity` | Parent is allocated as one captured-span folder unit; child remains rank 0 in the same `FolderRef`. |
| `locked-fragmented-space` | Every captured target unchanged with `LOCKED`; no allocated region overlaps it. |
| `full-grid-no-capacity` | All 16 fallback-category apps remain singletons on the captured 4×4 page and no page is added. Canonical UTF-8 `ItemId` order is `0,1,10,11,12,13,14,15,2,3,4,5,6,7,8,9`; those items occupy row-major cells `(0,0)` through `(3,3)` in that order, so the numeric-suffix input layout is reordered in place. |
| `multiple-pages-and-dock` | Each fallback singleton remains on its captured page when canonical; Dock rank 0 is `DOCK`; no cross-page move. |
| `same-package-personal-work` | Independent fallback decisions and placements; never one folder because profile differs and fallback stays singleton. |
| `undefined-category` | OTHER/S6/FALLBACK, `FALLBACK_CATEGORY`, zero new folders. |
| `device-profile-variation` | Landscape 6x8 bounds are used; the canonical `(0,0)` target is `ALREADY_CANONICAL`. |
| `deck-output-compatibility` | Existing folder remains intact as one unit; its child rank/ref is unchanged; fallback singleton is not inserted into it. |

Issue #5 L-01–L-09 and L-11–L-20 are adopted with P-01–P-12. L-03 means
existing fallback singletons do not cross captured pages. L-10 remains a caller
stale-check example and causes no special planner outcome. The exact cell/count
examples L-13–L-17 are fixed below; each listed app sequence is ascending
`ItemId`, and all ungrouped apps resolve to the fallback category. L-18 is
enforced by the fallback singleton policy, L-19 by profile grouping, and L-20
by V-21.

| Evidence | Exact adopted result |
|---|---|
| L-13, portrait 4×5 | Preserve widget spans `(0,0,2×2)` and `(0,2,4×1)`, locked `(3,4)`, and Dock ranks 0/1. Place ten singletons at `(2,0),(3,0),(2,1),(3,1),(0,3),(1,3),(2,3),(3,3),(0,4),(1,4)` on the captured page; no new page. |
| L-14, landscape 4×3 | Preserve widget `(0,0,2×2)`, locked `(3,2)`, and Dock ranks 0/1. Place the first six singletons at `(2,0),(3,0),(2,1),(3,1),(0,2),(1,2)` on the captured page. Put the remaining four at `(0,0),(1,0),(2,0),(3,0)` on `NewPageOrdinal(0)`. |
| L-15, tablet 6×5 | Preserve Dock ranks 0–3 and place 20 singletons in the first 20 row-major cells of the captured page; ten cells remain free and no page is added. |
| L-16, folders | Three members of the first `(ProfileId, CategoryId)` group form folder 0, five members of the second form folder 1, and seven fallback items remain singletons. Place folders at `(0,0),(1,0)` and the singletons at `(2,0),(3,0),(0,1),(1,1),(2,1),(3,1),(0,2)` on the captured 4×5 page. |
| L-17, widget + lock | Preserve widget `(0,0,3×2)`, locked `(4,4)`, and Dock ranks 0–2. Place eight singletons at `(3,0),(4,0),(3,1),(4,1),(0,2),(1,2),(2,2),(3,2)` on the captured 5×5 page. |

Issue #6 C-01–C-16 are planner examples only after adapter observations become
accepted `ClassificationSignal` values. The planner-visible results are:

| Evidence | Planner input/result |
|---|---|
| C-01–C-04 | One S2 candidate respectively yields GAME, SOCIAL, MUSIC, or VIDEO with `EXPLICIT`. |
| C-05 | No supplied signal yields fallback/S6/`FALLBACK` plus `FALLBACK_CATEGORY`. |
| C-06 | S1/TOOLS wins with `EXPLICIT`. |
| C-07 | Adapter-supplied S5/TOOLS yields S5/TOOLS/`FALLBACK`; the Google observation is not planner input. |
| C-08 | Adapter-supplied S5/OTHER yields S5/OTHER/`FALLBACK`; the system-app observation is not planner input. |
| C-09 | Two item identities in different profiles receive independent decisions; neither profile changes. |
| C-10 | S3/GAME and S3/TOOLS for one item resolve to S3/GAME/`RULE`, the smaller `CategoryId`. |
| C-11 | With the former S1 entry absent, the highest remaining supplied S2–S5 entry wins, or S6 fallback applies. |
| C-12 | S1/SOCIAL beats S2/GAME and yields `EXPLICIT`. |
| C-13 | S2/MAPS yields `EXPLICIT`. |
| C-14 | No supplied signal yields fallback/S6/`FALLBACK` plus its warning. |
| C-15 | Every application/deep shortcut has exactly one decision in `allowedCategories`. |
| C-16 | The adapter omits an unaccepted S2 observation; the next valid S3–S5 entry wins, or S6 fallback applies. Supplying an unknown `CategoryId` instead is V-14 and is not skipped. |

For V-01–V-22 and S-01–S-20, the exact inputs and required typed observations
are the accepted Issue #10 definitions and Issue #11 corpus/coverage rows. This
planner must make every existing harness check pass; it does not create a
second copy of those fixtures.

## Complexity model

Let `n` be total captured plus candidate items, `s` signal entries, and `p` the
total captured plus newly produced pages. Canonical sorting/grouping and
rectangle validation are bounded by `O((n + s + p) log(n + s + p) + n²)`.
Sparse row-major first-fit is bounded by `O(p * n³ log n)`, giving the total
worst-case time bound; folder partition is linear after grouping. Indexed
items, signals, pages, and sparse occupied rectangles use `O(n + s + p)`
space, independent of `columns * rows`. No dense grid-sized allocation,
recursive search, exponential backtracking, or input-sized global cache is
permitted.

## Performance sample protocol

Use the Issue #11 generator with seed `0x4E554E55L`, cases `0..63`, and one new
stateless planner per corpus pass. On one test worker:

1. run three complete warm-up passes and discard them;
2. run ten measured complete passes using `System.nanoTime()` in test code only;
3. record each pass duration plus median and nearest-rank p95 in nanoseconds;
4. record JVM version, available processors, max heap, seed, case range, and
   the current commit in the PR.

No threshold or pass/fail assertion is allowed; Issue #15 owns budgets. Fixture
results are still verified during measured passes so the sample cannot time a
no-op adapter.

## Data, privacy, and migration

The planner reads only `OrganizationInput`, returns a new value, and retains no
state. It adds no persistence, permission, resource, telemetry, or personal
data flow. There is no migration or recovery action in this Issue.

## Acceptance criteria

- [ ] AC-1: A concrete planner implements only the existing
  `OrganizationPlanner.plan` interface and P-01–P-12.
- [ ] AC-2: The complete Issue #11 corpus, validation coverage, generated
  properties, determinism, permutation, idempotence, lock, profile, bounds,
  overlap, conservation, and container checks pass through that interface.
- [ ] AC-3: Signal priority/tie/confidence/fallback and representative C cases
  match P-02; no package/platform heuristic is evaluated in the planner.
- [ ] AC-4: Folder grouping, capacity partition, ranks, cells, page overflow,
  and representative L cases match P-04–P-07/P-11.
- [ ] AC-5: Full materialization replans with exact unchanged targets,
  preservation reasons, and zero moved/new-page/new-folder values.
- [ ] AC-6: Results are value-equal across same inputs, declared permutations,
  locale, timezone, and calling thread.
- [ ] AC-7: Invalid and impossible inputs return the complete typed P-01 result
  without a partial plan or thrown exception.
- [ ] AC-8: Production planning code adds no Android/DB/UI/I/O/network/clock/
  random/global-state dependency and changes no public Issue #10 type.
- [ ] AC-9: The fixed performance protocol records measurements without a
  budget assertion.
- [ ] AC-10: Targeted tests, Spotless, debug assembly, repository-contract
  checks, and `git diff --check` pass.

## Test oracle

| AC | Evidence |
|---|---|
| AC-1, AC-8 | Static path/surface review plus Issue #10 purity guard |
| AC-2, AC-5 | Existing Issue #11 harness and concrete generated-property runner |
| AC-3 | Public-seam priority, tie, fallback, and C-mapping tests |
| AC-4 | Public-seam partition/boundary and source-exact L tests |
| AC-6 | Same-value, permutation, locale/timezone, and executor-thread tests |
| AC-7 | Existing V corpus plus multi-defect and no-throw tests |
| AC-9 | Benchmark output using the fixed seed/pass protocol |
| AC-10 | Commands in the accepted implementation plan |

## Open questions

None.

## Change history

- 2026-08-10: Accepted after Codex Standards/Spec review on main
  `2fe774b4f3`; no open questions remain.
- 2026-08-10: Rewritten after Codex review and Issue #31. Removes duplicated
  architecture/contract tables; closes fallback, signal, folder partition,
  overflow, canonical unchanged, fixture, and benchmark behavior.
