# Implementation Plan: Reproducible multi-seed planner stress coverage

> Issue: [#46](https://github.com/nunu1733/NunuLauncher/issues/46)
> Spec: [spec.md](./spec.md)
> Status: implemented

## Change set

Only these paths are in scope:

| Path | Responsibility |
|---|---|
| `tests/unit/app/lawnchair/organizer/planning/PlannerGeneratedPropertyTest.kt` | Read the test-only count and generate/select the requested deterministic corpus while preserving default behavior. |
| `tests/unit/app/lawnchair/organizer/planning/harness/PlannerFixture.kt` | Carry an optional non-default corpus count in generated-case reproduction text without changing the default command. |
| `tests/unit/app/lawnchair/organizer/planning/harness/SyntheticFixtureGenerator.kt` | Attach the requested count to generated reproductions and validate case selection against seed/index/count. |
| `tests/unit/app/lawnchair/organizer/planning/harness/SyntheticFixtureGeneratorTest.kt` | Test default and extended reproduction/count behavior. |
| `build.gradle` | Make planner seed/count/case system properties part of Gradle Test task inputs and forward them to the Test JVM. |
| `.github/workflows/planner-stress.yml` | Run the fixed 8 × 512 matrix on schedule and via `workflow_dispatch`. |
| `docs/engineering/quality-strategy.md` | Document the difference between PR presubmit and scheduled stress evidence. |
| `docs/engineering/building.md` | Document the local count/seed/case commands and fixed stress matrix. |
| `specs/46-reproducible-planner-stress-coverage/spec.md` | Observable behavior and acceptance criteria. |
| `specs/46-reproducible-planner-stress-coverage/plan.md` | This implementation and verification plan. |

Do not modify `lawnchair/src/app/lawnchair/organizer/planning/`, public
planning types, `ci.yml`, Gradle dependencies, permissions, resources, DB/UI
code, or the Issue #11 oracle implementation.

## Implementation sequence

1. Add a positive test-only `planner.count` parser with default 64 and
   fail-closed handling for malformed/non-positive values.
2. Thread the requested count into the existing generated corpus selection.
   Keep `planner.seed` and `planner.case` semantics unchanged. Preserve the
   exact Issue #11 reproduction string when count is 64; append count only for
   non-default corpora so cases above index 63 are independently reproducible.
3. Update generator selection/reproduction tests to cover 64 and 512 cases,
   prefix/value determinism, selected high index, and invalid count input.
4. Register the three planner system properties as Gradle Test task inputs and
   forward present values to the Test JVM so local/remote caches cannot alias
   different corpora and the reproduction command is effective.
5. Add the dedicated workflow with eight string-valued seeds and count 512 in
   the matrix. Use the repository's JDK 21, submodules, Gradle wrapper, and
   existing planner test task. Keep the workflow out of `ci.yml` and
   `final-status`.
6. Add concise local-run and evidence-policy documentation, without copying
   planner behavior or harness rules from the existing specs.

## Workflow contract

The workflow's matrix is explicit and reviewable in YAML. It runs:

```text
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests '*PlannerGeneratedPropertyTest*' \
  -Dplanner.seed=<matrix seed> \
  -Dplanner.count=512
```

`fail-fast: false` allows every seed row to report independent failures. The
workflow is scheduled weekly and can be started manually. It has read-only
contents permission, does not upload or mutate artifacts, and is not required
for pull-request merge status.

## Verification

Run the focused generator/runner tests and the full planner property test with
the default corpus. Then run all eight matrix commands (or an equivalent
loop) to provide one complete 4,096-case result. Run a high-index single-case
reproduction with the count property and confirm the command is printed in the
failure/reproduction model. Validate the workflow YAML, repository contracts,
formatting, and diff whitespace.

Required commands:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.planning.harness.SyntheticFixtureGeneratorTest'
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*'
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*' -Dplanner.seed=1314213461 -Dplanner.count=512
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
git diff --check
```

Workflow syntax is checked with the repository's available YAML parser or
`actionlint` when installed. A deliberately failing selected-case invocation
is used only to verify diagnostics, then no failing test change remains in the
working tree.

## Handoff evidence (2026-08-14)

- Focused generator/runner tests passed after the review fixes, including the
  throwing-planner test that verifies reproduction output before rethrow.
- The complete local matrix passed: 8/8 seeds × 512 cases = 4,096 cases.
  Seeds were `1314213461`, `0`, `1`, `42`, `20260814`, `8675309`,
  `2147483647`, and `-2147483648`; measured wall time was 16 seconds with
  warm Gradle outputs on JDK 21.
- The high-index single-case command passed:

  ```bash
  ./gradlew --quiet testLawnWithQuickstepGithubDebugUnitTest \
    --tests '*PlannerGeneratedPropertyTest*' \
    -Dplanner.seed=1314213461 -Dplanner.case=511 -Dplanner.count=512
  ```
- The invalid boundary command with `-Dplanner.case=512` failed the test task
  as expected, confirming that the planner properties reach the selected Test
  task and that out-of-range cases are rejected.
- Workflow YAML validation, repository-contract validation and its self-tests,
  and `git diff --check` passed.
- The remote manual workflow completed successfully: 8/8 matrix jobs passed,
  covering all 4,096 cases, with a 4m43s workflow wall time. Evidence:
  [Planner stress run 31778797192](https://github.com/nunu1733/NunuLauncher/actions/runs/31778797192).

## Rollback and stop conditions

Rollback is a revert of the test-only runner/configuration, docs, workflow,
and this spec directory; no persisted data or production behavior changes.
Stop and report if the existing harness cannot reproduce a case using the
public seam, if a second oracle or dependency appears necessary, or if the
extended matrix requires changing planner behavior or the normal CI gate.
