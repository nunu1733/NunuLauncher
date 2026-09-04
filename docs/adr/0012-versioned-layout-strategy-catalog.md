---
status: proposed
---

# Versioned built-in layout-strategy catalog with fail-closed local selection

## Context

Issue #182 asks for selectable layout strategies beyond the single built-in
`OrderingPolicy.CANONICAL_V1`. The current planning module exposes one external
seam (`OrganizationPlanner.plan`) and carries the strategy as a single-value
`OrderingPolicy` enum inside `RuleSemantics`, which participates in the
immutable built-in `OrganizerPolicyBundle` digest ([ADR-0007]). Strategies such
as page-local tidy, cross-page compaction, bottom-first traversal, and
category-contiguous grouping change folder formation, eligible units, page
scope, and cell traversal — not only ordering — so the `OrderingPolicy` name
and shape are too narrow, and user selection introduces a new dynamic policy
input where ADR-0007 currently has none.

Three decisions are expensive to reverse and not obvious from code:

1. a curated catalog of versioned built-in strategies versus combinational
   public policy toggles;
2. a versioned strategy identity in the rule schema (`rule-v2`) with the
   catalog declared by the active bundle, versus overloading
   `OrderingPolicy` or adding unversioned flags;
3. a typed, generation/digest-bearing local selection store that fails closed,
   versus a plain user preference with silent fallback to a default strategy.

## Decision

1. **Curated catalog, not toggles.** Strategy behavior is a closed set of
   built-in `StrategyDefinition`s (grouping, eligible units, unit order, page
   scope, cell traversal) behind the unchanged `OrganizationPlanner.plan`
   seam. Independent public switches for folder policy × page policy × cell
   traversal × ordering are rejected: they would enlarge the interface and
   multiply property-test states without user value.
2. **Versioned identity in the rule schema.** `RuleSemantics` replaces
   `orderingPolicy` with a versioned strategy identity (`id` + `version`). The
   active bundle (`organization-policy-v2`, `rule-v2`) declares exactly the
   supported strategy set and the default (`CANONICAL_PAGE_COMPACT_V1`); the
   identity participates in the bundle digest, result echo, and diagnostics.
   `CANONICAL_PAGE_COMPACT_V1` is the byte-equivalent extraction of the
   current `CANONICAL_V1` behavior and the compatibility oracle.
3. **Fail-closed local selection.** User selection is read by the composer
   through a Rule Management-owned local store with schema version, monotonic
   generation, and content digest (the contract family of the category
   override store). Missing selection means the bundle default; unknown,
   removed, corrupt, or newer selections are typed non-write failures
   (`NotReady`) — never a silent selection of another strategy. The selection
   joins ADR-0007's consistent-cut protocol as a dynamic re-read.
4. **Idempotence-constrained strategy definitions.** Strategies that move
   units across pages order units by captured visual (positional) order, which
   is stable under the transformation; identity-tie-break ordering is accepted
   only for strategies that never change page membership. A catalog member
   that cannot state a complete tie-break and idempotence argument is not
   accepted.

The accepted strategy-level behavior (catalog members, their rules, default,
and delivery split) is normative in
[spec 182](../../specs/182-layout-strategy-catalog/spec.md); this ADR owns the
shape of the decision, not the per-strategy rules.

## Consequences

- The rule schema version bumps to `rule-v2` and the bundle to
  `organization-policy-v2`; per ADR-0007 §8 the older bundle is never migrated
  in place, and a downgrade cannot rewrite a newer selection store.
- The composer gains one more dynamic read in the stable cut (selection
  A/B re-read), and one more `PolicySourceKind`.
- Strategy definitions stay inside the planning module and are tested through
  the public seam; no strategy plugin surface is published.
- Adding a strategy later means a new catalog member in a new bundle version
  with its own versioned identity — never a silent reinterpretation of an
  existing ID.

## Alternatives considered

- **Extend `OrderingPolicy` with more enum values.** Rejected: the name and
  shape mislead (strategies change more than ordering) and unversioned enum
  values cannot express removal/re-interpretation policy.
- **Combinational public toggles.** Rejected above; a curated catalog keeps
  the interface deep and the test surface bounded.
- **Plain `SharedPreferences` selection with silent fallback.** Rejected: a
  missing/corrupt selection would silently pick a different destructive
  layout; ADR-0007's fail-closed discipline exists precisely to prevent that.
- **Fallback to the default strategy when a selection is unsupported.**
  Rejected for the same reason: fallback is an unconfirmed layout decision;
  the user must re-select explicitly.
- **Include `CATEGORY_CONTIGUOUS_V1` in the first delivery.** Deferred: it is
  behaviorally accepted in spec 182 but ships after the baseline/tidy/
  bottom-first issues so the catalog grows in independently reviewable steps.

[ADR-0007]: 0007-authoritative-organization-policy-sources.md
