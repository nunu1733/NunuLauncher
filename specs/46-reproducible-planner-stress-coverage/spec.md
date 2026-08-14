# Reproducible multi-seed planner stress coverage

> Issue: [#46](https://github.com/nunu1733/NunuLauncher/issues/46)
> Status: implemented
> Updated: 2026-08-14

## Purpose

Keep the normal planner property run bounded and reproducible while adding a
separate, deterministic stress surface that explores more generated inputs
than the default single-seed 64-case presubmit corpus.

The existing Issue #11 harness and Issue #12 concrete planner remain the only
test seam and oracle. This change configures the existing generated-property
runner; it does not change planner behavior or the public planning contract.

## Sources of truth and constraints

- Issue #46 owns the outcome, scope, and acceptance criteria.
- [Issue #11 harness spec](../11-planner-fixture-property-harness/spec.md)
  owns fixture, reproduction, and oracle semantics.
- [Issue #12 planner spec](../12-deterministic-full-layout-planner-v1/spec.md)
  owns planner behavior.
- [Quality strategy](../../docs/engineering/quality-strategy.md) owns the
  repository's test-surface and CI evidence policy.

If this spec conflicts with those sources, implementation stops and the
appropriate source is corrected first.

## Observable behavior

### Default presubmit run

With no planner system properties, `PlannerGeneratedPropertyTest` continues to
run exactly 64 generated cases using `SyntheticFixtureGenerator.DEFAULT_SEED`.
The existing example and validation corpus tests continue to run in the same
JUnit class.

`-Dplanner.seed=<seed>` continues to select the deterministic generator seed.
`-Dplanner.case=<index>` continues to select exactly one zero-based generated
case from that seed's corpus. A selected case uses the same generated corpus
count that was requested for the invocation.

### Test-only corpus count

`-Dplanner.count=<positive-int>` is read only by the test runner and defaults
to 64. It controls the generated corpus size for the selected seed. It is not
read by production planner code, the public planning types, or the fixture
generator's explicit `(seed, count)` API.

When a failure occurs in a corpus larger than the default, its diagnostic
includes a complete command containing `planner.seed`, `planner.case`, and the
non-default `planner.count`, so the exact generated input can be rerun. The
default 64-case reproduction string remains byte-for-byte compatible with the
Issue #11 command.

The Gradle `Test` task declares `planner.seed`, `planner.case`, and
`planner.count` as inputs and forwards present values to the Test JVM, so local
and remote test output caches cannot silently reuse a result from another
generated corpus and the existing reproduction command reaches the runner.

### Extended matrix

A dedicated workflow runs the following fixed matrix. Each row executes 512
generated cases; the complete matrix therefore executes 4,096 cases.

| Seed | Cases |
|---:|---:|
| `1314213461` | 512 |
| `0` | 512 |
| `1` | 512 |
| `42` | 512 |
| `20260814` | 512 |
| `8675309` | 512 |
| `2147483647` | 512 |
| `-2147483648` | 512 |

The workflow is available through both a weekly schedule and
`workflow_dispatch`. It is not a per-pull-request gate and is not added to the
existing `final-status` aggregate.

## Determinism and failure behavior

- The same seed and count produce value-equal generated fixtures.
- The same seed, count, and selected case reproduce the same fixture and
  planner result.
- Generated identities remain synthetic and use the existing generator.
- Every planner call continues through `OrganizationPlanner.plan` and every
  assertion continues through `PlannerContractHarness.verify`.
- A failing matrix job exits non-zero and prints the seed, case index, and
  exact rerun command for each generated failure.
- Invalid or non-positive `planner.count` values fail the test configuration
  rather than silently selecting another corpus.

## Non-goals

- No planner algorithm, result type, public contract, or production source
  change.
- No property-testing dependency.
- No second oracle, harness, generated identity scheme, or test seam.
- No replacement of representative fixtures with randomized tests.
- No change to the normal CI `ci.yml` workflow or its presubmit gate.
- No database, UI, permission, network, or Android behavior change.

## Acceptance criteria

- [x] The default run remains exactly 64 deterministic cases with the existing
  default seed and exact default seed/case reproduction form.
- [x] A test-only count property can run a requested positive corpus size and
  selects a zero-based case from that same corpus.
- [x] The extended workflow runs the documented 8 × 512 matrix (4,096 cases)
  on a fixed schedule and through manual dispatch.
- [x] Extended failures include enough seed/count/case data for exact local
  reproduction.
- [x] Existing harness/oracle and `OrganizationPlanner.plan` seam are reused
  without mocking planner internals or adding dependencies.
- [x] Gradle test caching distinguishes different planner seed/count/case
  configurations.
- [x] No production, Android, DB, UI, permission, or public-contract files are
  changed.
- [x] Targeted tests, workflow/YAML validation, repository-contract checks,
  and `git diff --check` pass.

## Verification evidence to record

The implementation handoff records the complete matrix command/result, a
single-case reproduction command, workflow validation, repository-contract
validation, and `git diff --check`. Runtime is evidence only; the extended
matrix is not given a pass/fail performance budget by this Issue.
