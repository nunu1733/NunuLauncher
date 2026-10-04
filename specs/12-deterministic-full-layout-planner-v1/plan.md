# Implementation Plan: Deterministic full-layout planning v1

> Issue: #12
> Spec: [spec.md](./spec.md)
> Status: accepted

## Current evidence

- Baseline is main commit `2fe774b4f3`, which includes Issue #31's
  `PreserveReason.ALREADY_CANONICAL`/idempotence fix and Issue #33's unbounded
  `PageOrder` plus overflow-free addition.
- The accepted public types and only production seam are in
  `lawnchair/src/app/lawnchair/organizer/planning/`.
- The accepted black-box corpus, generator, materializer, and oracles are in
  `tests/unit/app/lawnchair/organizer/planning/harness/` and accept any
  `OrganizationPlanner` implementation.
- The Issue #5/#6 product documents remain proposed research inputs. The
  accepted Issue #12 spec, not those documents alone, fixes the v1 behavior.
- No concrete production planner, dependency, service registration, Android
  adapter, or DB apply path exists in scope.

## Change set

Only the following new files are allowed:

| File | Responsibility |
|---|---|
| `lawnchair/src/app/lawnchair/organizer/planning/DeterministicOrganizationPlanner.kt` | Implements the existing `OrganizationPlanner` seam and coordinates the internal phases. |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningValidation.kt` | Internal V-rule evaluation and typed rejection accumulation for P-01/P-11. |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningClassification.kt` | Internal signal collapse, priority, tie-breaking, decisions, and classification warnings for P-02. |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningPlacement.kt` | Internal preservation, folder partition, occupancy, workspace allocation, and disposition construction for P-03–P-08/P-11. |
| `lawnchair/src/app/lawnchair/organizer/planning/PlanningResultCanonicalization.kt` | Internal canonical result assembly and plan-local ordinal checks for P-09/P-12. |
| `tests/unit/app/lawnchair/organizer/planning/DeterministicOrganizationPlannerTest.kt` | Focused algorithm examples and boundary tests not already owned by the harness corpus. |
| `tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt` | Wires the concrete planner into the existing Issue #11 generator and contract harness, with reproducible case selection. |
| `tests/unit/app/lawnchair/organizer/planning/PlannerBenchmarkTest.kt` | Records the fixed, non-gating performance sample from the spec. |

All production helpers and values are `internal` or `private`. Do not add a
second public seam or test an internal phase directly. Do not modify the
accepted public contract, Issue #11 harness/corpus, Gradle files, dependency
catalogs, resources, manifests, Deck, Launcher3/AOSP code, DB, UI, or docs
outside this spec directory.

## Implementation shape

`DeterministicOrganizationPlanner.plan` remains the only entry point. It owns
one invocation's immutable intermediate state and calls package-internal
functions in this order:

1. canonical input preparation and P-01 validation;
2. P-02 category resolution;
3. P-03 preservation and P-08 run-mode selection;
4. P-04/P-05 folder grouping and safe partition;
5. P-06 occupancy and workspace allocation;
6. P-07/P-11 outcome construction;
7. P-09/P-10/P-12 canonical result assembly and contract checks.

There is no mutable state between calls. Internal functions consume and return
domain values or private immutable records; they do not introduce interfaces,
Android types, platform services, I/O, clocks, randomness, or default-locale
comparisons. Contract checks return typed outcomes and must not be implemented
as assertions whose behavior changes with JVM flags.

## Test-first implementation sequence

### 1. Wire the accepted harness

Add `PlannerGeneratedPropertyTest` first. Instantiate
`DeterministicOrganizationPlanner` through the `OrganizationPlanner` type and
pass it to the existing `PlannerContractHarness`. Use
`SyntheticFixtureGenerator`; do not copy its generator, oracle, materializer,
V fixtures, or S fixtures.

The test accepts:

- `planner.seed`, default `0x4E554E55`;
- `planner.case`, which selects exactly one zero-based case when present;
- otherwise cases `0..63` for the selected seed.

On failure, print the exact seed, case, and reproduction command shown in
Verification.

### 2. Implement rejection ordering

Write focused public-seam tests only for algorithm-specific ambiguity:
multiple independent defects, duplicate equivalent defects, Invalid suppressing
Impossible, and all affected subjects. Implement P-01/P-11 in
`PlanningValidation.kt`. The exhaustive V-rule examples remain owned by the
existing corpus and harness.

### 3. Implement category resolution

Add focused tests for source priority, same-source `CategoryId` tie-breaking,
exact duplicate collapse, no-signal fallback, S6 warning, and classification
of preserved members. Implement P-02 in `PlanningClassification.kt`. Do not
parse `TargetKey` or invent S5a/S5b values.

### 4. Implement preservation and run modes

Add focused tests for the complete preserve-reason precedence, exact target
retention, `ALREADY_CANONICAL`, warning coexistence, full versus incremental
target eligibility, and materialized replanning. Implement P-03/P-08 before
allocating any movable unit.

### 5. Implement folder formation

Add boundary tests for fallback singleton policy, group sizes immediately
below/at/above `minGroupSize`, capacity-sized chunks, redistributable and
non-redistributable suffixes, profile separation, deterministic ordinals, and
rank/member equality. Implement P-04/P-05 in `PlanningPlacement.kt` exactly;
do not join existing folders.

Compute `folderMaxColumns * folderMaxRows` in `Long`. Before list indexing or
chunk sizing, cap the effective capacity to the current group size, then
convert that bounded value to `Int`; do not allocate a buffer sized to the raw
capacity. Include a public-seam case with `65536 × 65536` folder dimensions to
prove that overflow does not turn an eligible group into singletons.

### 6. Implement workspace allocation

Add focused tests for span occupancy, preferred-page order, row-major cells,
empty captured pages, full captured pages, reuse of new pages, page ordinals,
and the exact L-13–L-17 coordinates/counts in the spec's example table.
Implement P-06/P-07/P-11 in `PlanningPlacement.kt`. Unlimited new-page
overflow means a full captured grid is not an Impossible result.

Represent page occupancy as sparse rectangles, never as an array sized by
`columns * rows`. To find the exact row-major target for one span, enumerate
candidate `y` values `0` plus occupied bottom edges in ascending order. At each
`y`, collect the horizontal intervals whose rectangles overlap the proposed
vertical span, sort/merge them, and choose the first fitting gap from `x=0`.
Use `Long` for coordinate-plus-span bounds arithmetic. New page order is the
greatest captured `PageOrder` plus the ordinal and one, using consecutive
overflow-free `PageOrder.plus(Int)` calls; with no captured page, it is the
decimal value of the ordinal. This search must match exhaustive row-major
results on small-grid tests while keeping memory independent of grid area.

### 7. Canonicalize results

Add focused tests for canonical collection order, stable plan-local ordinals,
permutation equivalence, locale/timezone/thread independence, repeated-call
determinism, full-run idempotence, and conditional incremental convergence.
Implement P-09/P-10/P-12 in
`PlanningResultCanonicalization.kt`. Compare complete `PlanningResult` values,
not selected projections.

Measure or statically account for the spec's complexity variables while
reviewing the implementation. Do not introduce recursive search or an
input-sized global cache. Include small-grid exhaustive-search equivalence and
very-large-dimension sparse-occupancy cases through the public seam.

### 8. Add the non-gating benchmark

`PlannerBenchmarkTest` uses the same seed and cases as the generated test. In
one JVM invocation it performs three complete warmup passes, then ten measured
passes. Record each measured full-corpus duration in nanoseconds and report the
median plus nearest-rank p95, along with JVM, available processors, max heap,
seed, case count, and git commit. Do not add a pass/fail threshold.

## Test ownership

| Evidence | Owner in this change |
|---|---|
| V-01–V-22 and S-scenario conformance | Existing `ExampleCorpus` + `PlannerContractHarness`, invoked unchanged by `PlannerGeneratedPropertyTest`. |
| Conservation, lock, profile, bounds, overlap, container integrity, determinism, metamorphic, and idempotence oracles | Existing Issue #11 harness, invoked through the production seam. |
| P-rule algorithm choices and exact source examples | Focused cases in `DeterministicOrganizationPlannerTest`. |
| Generated breadth and reproducibility | `PlannerGeneratedPropertyTest`, reusing the existing generator. |
| Performance sample | `PlannerBenchmarkTest`; observational only. |

Do not recreate a manual test per V/S identifier. A focused test may reuse an
existing corpus fixture when it needs to assert an Issue #12-specific result.

## Verification

Run with the repository's documented JDK 21 environment:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.planning.*'
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*'
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
git diff --check
```

Reproduce one generated case with:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*' -Dplanner.seed=<seed> -Dplanner.case=<index>
```

Run the performance sample separately so it does not gate the correctness
suite:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerBenchmarkTest*'
```

The PR records the exact commands/results, failing seed/case reproductions if
any, the benchmark output, implementation complexity, and `Closes #12`.

## Migration, rollback, and handoff

- Migration and persistent-data recovery: none; this change is pure and
  stateless.
- Rollback: revert the eight new files. No schema, manifest, permission,
  resource, dependency, or persisted layout is changed.
- Stage B stops after the allowed implementation, tests, and verification are
  complete. It does not begin Issue #13/#14 DB snapshot/apply work.
- If an accepted contract is not constructible during implementation, stop and
  report the exact input/result conflict instead of changing a public type,
  harness, or spec silently.
