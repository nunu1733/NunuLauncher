# Implementation Plan: Pure organization planning contract

> Issue: #10
> Spec: [spec.md](./spec.md)
> Status: draft

## Current evidence

- The accepted spec is the sole source for observable contract shapes and
  semantics. This plan references its type, validation, scenario, and AC IDs;
  it does not reproduce them.
- [DESIGN.md](../../DESIGN.md) §4.1 fixes the interface as
  `OrganizationPlanner.plan(OrganizationInput) -> PlanningResult`. Issue #10
  delivers that functional interface and its types; Issue #12 supplies the
  concrete implementation.
- [ADR-0002](../../docs/adr/0002-replace-deck-layout.md) accepts replacement of
  Deck with the organizer modules, based on the completed
  [Deck audit](../../docs/assessment/lawnchair-deck-audit.md). This slice adds no
  Deck hook, classification mechanism, placement mechanism, or DB write.
- The logical source layout in DESIGN.md §9 permits
  `lawnchair/src/app/lawnchair/organizer/planning/`. The `lawn` source set in
  [build.gradle](../../build.gradle) already compiles `lawnchair/src`, and
  Spotless already covers Kotlin files there.
- The repository has no JVM unit-test dependency or host-test source root.
  `src/` is the main source root, so conventional `src/test/**` would leak test
  classes into the application. Host tests therefore use the disjoint
  `tests/unit/` root configured explicitly as `sourceSets.test`. AndroidX JUnit
  in the catalog is an instrumentation dependency and is not suitable here.

## Design

### Module and interface

Create one package, `app.lawnchair.organizer.planning`, in the existing Android
application module. Keep the initial contract in a few cohesive files rather
than creating one package per type family:

```text
lawnchair/src/app/lawnchair/organizer/planning/
├── Identity.kt
├── OrganizationInput.kt
├── PlanningResult.kt
└── OrganizationPlanner.kt
```

`OrganizationPlanner.kt` contains exactly one public operation:

```kotlin
fun interface OrganizationPlanner {
    fun plan(input: OrganizationInput): PlanningResult
}
```

There is no top-level concrete `plan` function in Issue #10, no default
implementation, and no `TODO`, exception, or placeholder production result.
Issue #11 accepts an `OrganizationPlanner` to build the black-box harness;
Issue #12 adds the production adapter. Both therefore call the identical seam.

All public contract declarations match the accepted spec exactly. Helpers used
only to implement value equality or canonical byte comparison remain internal.
No Android, Launcher3, DB, UI, coroutine/flow, file, network, clock, random, or
other I/O type appears in a public declaration.

### Behavior intentionally absent

Issue #10 does not implement:

- input canonicalization or version echo;
- V-01–V-22 evaluation;
- signal resolution or preservation-reason selection;
- placement, allocation, overflow, or canonical result production;
- DB application, integration hooks, UI, migration, or recovery.

The public types make those outcomes and malformed sentinel inputs
constructible. The accepted spec defines their semantics. Issues #11 and #12
own executable conformance, as recorded in the spec's Delivery boundary
section.

### Planning-package dependency gate

The package lives on an Android classpath, so compilation alone cannot prove
purity. `PurityGuardTest` reads every production `.kt` file under the planning
package and rejects forbidden package prefixes anywhere in source text, not
only in import statements. This catches direct imports, aliased imports,
typealiases, and fully qualified references and applies to future files without
a manually maintained class list. Production contract sources must not contain
those prefixes even in comments or string literals.

This deliberately stricter static architecture gate does not pretend to
exercise planner behavior. `OrganizationPlannerSeamTest` separately calls
`plan` through a test-only adapter.

### Alternatives rejected

- A top-level function with `TODO("#12")` was rejected because valid input
  would crash and violate the declared result contract.
- A concrete no-op or `Unsupported` result was rejected because the accepted
  result model has no such outcome and adding one would invent behavior.
- A separate Kotlin/JVM Gradle module was rejected for this slice because
  DESIGN.md §9 and ADR-0002 establish the in-tree organizer package and a new
  module would expand build structure. The whole-package source gate verifies a
  stricter form of AC-1 without creating another module.

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/planning/*.kt` | Canonical handles, input/result/diagnostic types, and `OrganizationPlanner` functional interface | Realizes DESIGN.md §4.1/§9 without an algorithm |
| `tests/unit/app/lawnchair/organizer/planning/ContractShapeTest.kt` | Representative construction, equality, ordering, and type-separation tests | Verifies the Issue #10 domain contract only |
| `tests/unit/app/lawnchair/organizer/planning/OrganizationPlannerSeamTest.kt` | Test adapter invoked through `OrganizationPlanner.plan` for each top-level result family | Proves production and tests share the same seam without mocking internals |
| `tests/unit/app/lawnchair/organizer/planning/PurityGuardTest.kt` | Whole-package forbidden-prefix scan | Automated AC-1 architecture gate |
| `gradle/libs.versions.toml`, `build.gradle` | Add exact JUnit 4 host-test dependency, configure `sourceSets.test`, and include `tests/unit/**/*.kt` in Spotless | Keeps test classes and JUnit out of production while formatting the new tests |
| [DESIGN.md](../../DESIGN.md) | Record the exact functional interface and separation between contract and concrete implementation | DESIGN is the interface/seam source of truth |

No other production package, Deck source, Launcher3/AOSP source, database,
resource, manifest, permission, or dependency changes are in scope.

### Exact build change

Add one version-catalog entry and consume that alias:

```toml
[versions]
junit4 = "4.13.2"

[libraries]
junit4 = { module = "junit:junit", version.ref = "junit4" }
```

```groovy
testImplementation libs.junit4
```

Configure the disjoint host-test source root inside the existing Android
`sourceSets` block:

```groovy
test {
    java.srcDirs = ['tests/unit']
}
```

Do not add `compileOnly` or any other production configuration for JUnit.

Extend the existing Spotless Kotlin target without changing its formatter or
rules:

```groovy
target("lawnchair/src/**/*.kt", "tests/unit/**/*.kt")
```

JUnit 4.13.2 is required only for host-side contract tests. It adds no runtime
application dependency, permission, processor, plugin, or network behavior.
No property-test or assertion library is added; Issue #11 owns any property
library comparison.

## Implementation sequence

1. Add the exact JUnit catalog alias and `testImplementation` wiring.
2. Add failing contract-shape and seam tests using only names from the accepted
   spec and the exact `OrganizationPlanner` interface from DESIGN.md.
3. Implement opaque handles and canonical byte comparison, then the closed
   input/result/diagnostic types until the contract-shape tests compile and pass.
4. Add `OrganizationPlanner` as a functional interface. In the seam test,
   create a test-only adapter that returns a supplied `PlanningResult`, call
   `adapter.plan(input)`, and assert each `Planned`/`Invalid`/`Impossible`
   top-level family crosses the seam unchanged.
5. Add the whole-package purity test. It enumerates every `.kt` file under the
   production planning path and checks all forbidden package prefixes.
6. Run the full verification set. Do not begin canonicalization, validation,
   classification, placement, or downstream harness work when tests pass.

### Required representative construction cases

`ContractShapeTest` remains a type/constructor suite, not a validator or the
downstream fixture corpus. It must nevertheless construct:

- captured workspace, Dock, folder-member, app-pair-member, and unsupported
  placement variants;
- captured application, folder with membership, and app pair with two distinct
  members/stages/snap metadata;
- both candidate kinds with their matching candidate target variants;
- every captured `TargetKey` family, plus constructible kind/target and profile
  mismatches without evaluating them;
- `RuleSemantics`, `TaxonomyContract`, `ClassificationSignals`, and `TargetSet`,
  including duplicate/missing list shapes that a future validator can reject;
- `Planned` with workspace/folder targets, `NewPage`, `NewFolder`, categories,
  and warnings; `Invalid` and `Impossible` with typed diagnostics;
- malformed app-pair, unknown signal/category, closed policy enum, and
  candidate-kind restriction shapes required by AC-14–AC-17.

Assertions prove construction, field/type separation, closed enum membership,
and seam transport only. They do not assert V-rule execution or planning
behavior.

## Migration and recovery

- Schema/rule/data migration: none. The slice owns no persisted state.
- Failure rollback: revert the contract commit; there is no runtime path or
  user layout to recover.
- Backup/restore compatibility: unchanged. Opaque identities are contract
  values only and do not serialize platform IDs in this Issue.

## Verification

| Issue #10 acceptance | Evidence | Command |
|---|---|---|
| AC-1 | Whole-package forbidden-prefix test, stricter than public-surface-only leakage | `./gradlew testLawnWithQuickstepGithubDebugUnitTest` |
| AC-2 | Representative construction of captured/candidate inventory, profile identity, and locked placement | same |
| AC-3 | Compile-time distinct result/diagnostic families and documented precedence shape | same |
| AC-4 | Comparable canonical handles and constructible revision/rule/taxonomy fields on input/result | same |
| AC-5 | Test adapter called only through `OrganizationPlanner.plan`; no internal helper exists to mock | same |
| AC-6 | Diff review confirms no concrete planner, algorithm, DB apply, or integration hook | review + `git diff --check` |
| AC-7–AC-17 | Representative construction and type separation for the spec-referenced contract shapes; executable rules deferred per Delivery boundary | unit-test task + review |
| repository contract | Required docs, links, and forms remain valid | `python3 tools/repo-contract/validate_repo_contract.py` and `python3 tools/repo-contract/test_validate_repo_contract.py` |
| formatting | Production/test Kotlin formatting and whitespace | `./gradlew spotlessCheck` and `git diff --check` |
| application compile | Contract sources compile in the target variant | `./gradlew assembleLawnWithQuickstepGithubDebug` |

The unit-test task is new to this repository. Record its successful invocation
in the PR. Do not promote it to a repository-wide mandatory command or edit the
building guide until it has also succeeded on a clean checkout or CI, per
AGENTS.md.

## Documentation updates

- [ ] `spec.md`: keep `accepted` during implementation; set `implemented` only
      after the Issue #10 contract is merged.
- [ ] `DESIGN.md`: include the exact `OrganizationPlanner` interface and the
      separation between contract and concrete implementation; do not duplicate
      shapes, scenarios, or Issue ownership.
- [ ] `CONTEXT.md`: no change; no domain term is added or redefined.
- [ ] ADR: none. The functional interface is a reversible seam clarification,
      and the in-tree package follows existing DESIGN guidance.

## Stop conditions

Stop implementation and return to Codex review if any of these occurs:

- a public type or result variant must differ from the accepted spec;
- a valid-input concrete result, canonicalizer, validator, classifier, or
  placement implementation appears necessary to make Issue #10 tests pass;
- an Android/Launcher3/DB/UI type is needed in the public surface;
- another dependency or Gradle plugin appears necessary;
- the exact target unit-test task does not exist or cannot compile the
  `lawnchair/src` planning package.

## Execution checklist

- [ ] Read Issue #10 and comments with `--repo nunu1733/NunuLauncher` and
      confirm the accepted spec/DESIGN fixed point.
- [ ] Add only the exact JUnit host-test dependency.
- [ ] Add contract tests first, then the four cohesive production files.
- [ ] Keep `OrganizationPlanner` abstract and production code free of
      `TODO`/throwing/no-op adapters.
- [ ] Pass unit tests, purity gate, repo-contract tests, Spotless, diff check,
      and target application compile.
- [ ] Confirm the diff contains no #11 harness or #12 implementation work.
- [ ] Record commands, public symbols, and downstream handoff for #11/#12 in
      the PR and Issue comment.
