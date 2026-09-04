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
   `orderingPolicy` with the strategy identity `StrategyId` (e.g.
   `CANONICAL_PAGE_COMPACT_V1`). A strategy ID is an immutable semantic
   identity: the `_V1` suffix is the version, so there is no separate version
   field that could drift from the name. A behavior change is a new ID;
   renaming a shipped ID is forbidden. The active bundle
   (`organization-policy-v2`, `rule-v2`) declares the **runtime-supported**
   strategy set and the default (`CANONICAL_PAGE_COMPACT_V1`) inside its
   immutable digest; the runtime-supported set contains only implemented
   strategies, and enabling one on a later child-issue mainline publishes a
   **new bundle semantic version/generation and digest** per ADR-0007 §8
   (e.g. `organization-policy-v2.1`), with `rule-v2` and the selection-store
   schema unchanged. The user selection never enters the bundle digest.
   `CANONICAL_PAGE_COMPACT_V1` is the byte-equivalent extraction of the
   current `CANONICAL_V1` behavior and the compatibility oracle.
3. **Fail-closed local selection with a single write authority.** User
   selection is owned by Rule Management through a local store with schema
   version, monotonic generation, and content digest (the contract family of
   the category override store), exposing one read source and one validated
   write command. Missing selection means the bundle default; unknown,
   removed, corrupt, or newer-schema selections are typed non-write failures
   (`NotReady`) — never a silent selection of another strategy. The write
   command validates against the active bundle's runtime-supported set at
   write time, publishes atomically, and preserves the previous selection on
   failure; the UI picker issues this command and never writes storage or
   substitutes a strategy into an in-flight run. The selection joins
   ADR-0007's consistent-cut protocol as a dynamic re-read and contributes its
   own identity to `InputProvenance` (a fifth policy input), so the mutable
   user state never enters the immutable bundle digest.
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
  in place. A binary from before the selection store ignores it entirely
  (legacy policy keeps running; the store is never read, written, or
  destroyed, so a re-upgrade rediscovers the selection); a store-aware binary
  that reads a newer schema fails closed without rewriting it.
- The composer gains one more dynamic read in the stable cut (selection
  A/B re-read), one more `PolicySourceKind`, and one more `InputProvenance`
  identity row; a composition is identified by the bundle identity together
  with the selection identity, never by either alone.
- Strategy definitions stay inside the planning module and are tested through
  the public seam; no strategy plugin surface is published.
- Enabling a newly implemented strategy publishes a new bundle semantic
  version/generation and digest under the same rule schema (`rule-v2`),
  per ADR-0007 §8; a binary that does not know the new bundle version fails
  closed through its existing unsupported-version path. A semantic change to
  strategy rules is a new strategy ID, and a schema change is a new bundle
  major version — never a silent reinterpretation of an existing ID.
- The runtime-supported catalog must be coherent with the planner:
  `runtimeSupported ⊆ implemented internal strategy IDs`,
  `default ∈ runtimeSupported`, and exactly equal to the runtime-enabled
  implementations. This coherence is a required contract test in the
  selection and strategy child issues, so a strategy can never be offered by
  the bundle while unknown to the planner.

## Alternatives considered

- **Extend `OrderingPolicy` with more enum values.** Rejected: the name and
  shape mislead (strategies change more than ordering) and unversioned enum
  values cannot express removal/re-interpretation policy.
- **Separate `StrategyVersion` field next to a versioned ID.** Rejected: with
  IDs like `CANONICAL_PAGE_COMPACT_V1` plus a `v1` version field, future
  changes could be expressed two conflicting ways (`..._V1/v2` versus
  `..._V2/v1`). Making the `_V1` suffix itself the immutable identity leaves
  one path: a behavior change is a new ID.
- **Fold the user selection into the bundle identity.** Rejected: the bundle
  is an immutable application artifact; a per-user, per-change digest would
  break ADR-0007's identity discipline and re-derive the bundle on every
  selection change. The selection therefore carries its own provenance
  identity.
- **Combinational public toggles.** Rejected above; a curated catalog keeps
  the interface deep and the test surface bounded.
- **Plain `SharedPreferences` selection with silent fallback.** Rejected: a
  missing/corrupt selection would silently pick a different destructive
  layout; ADR-0007's fail-closed discipline exists precisely to prevent that.
- **Fallback to the default strategy when a selection is unsupported.**
  Rejected for the same reason: fallback is an unconfirmed layout decision;
  the user must re-select explicitly.
- **Keep the same bundle version and only update the digest when a strategy
  is enabled.** Rejected: ADR-0007 §8 publishes any policy content change
  under a new semantic version/generation plus a new digest, and
  `PolicyBundleIdentity` is a semantic identity, not a storage schema name. A
  capability change with a frozen version would break that discipline; each
  enablement is therefore a new bundle semantic version
  (e.g. `organization-policy-v2.1`) with `rule-v2` and the selection-store
  schema unchanged.
- **Declare all five spec-level strategies runtime-supported in the first
  bundle.** Rejected: intermediate mainlines would expose bundle-supported
  strategies with no planner implementation. Each mainline declares only its
  implemented strategies; enabling a strategy publishes a new bundle
  semantic version per ADR-0007 §8.

[ADR-0007]: 0007-authoritative-organization-policy-sources.md
