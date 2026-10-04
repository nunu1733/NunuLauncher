# Implementation Plan: Planner fixture and property-test harness

> Issue: #11
> Spec: [spec.md](./spec.md)
> Status: accepted

## Current evidence

- The accepted spec is the sole source for observable harness behavior. This
  plan references its ACs and Issue #10 IDs instead of redefining them.
- `OrganizationPlanner` and all fixture/result types compile from
  `lawnchair/src/app/lawnchair/organizer/planning/`.
- `tests/unit` is already the host-test source root and JUnit 4 is already scoped
  to tests. Spotless already includes `tests/unit/**/*.kt`.
- The target harness directory does not yet exist. No build or dependency change
  is needed.

## Design

### Module and interface

Create one in-process test module in package
`app.lawnchair.organizer.planning.harness`. All declarations are Kotlin
`internal`; the only non-private operation callers learn is
`PlannerContractHarness.verify`. Generator, corpus, oracle, materializer,
coverage records, and scripted adapters are implementation details or self-test
support, not additional seams.

```text
tests/unit/app/lawnchair/organizer/planning/harness/
├── PlannerContractHarness.kt
├── PlannerFixture.kt
├── Oracle.kt
├── PostPlanMaterializer.kt
├── SyntheticFixtureGenerator.kt
├── ExampleCorpus.kt
├── PlannerContractHarnessTest.kt
└── SyntheticFixtureGeneratorTest.kt
```

| File | Responsibility |
|---|---|
| `PlannerContractHarness.kt` | `verify`, checks/report/reproduction types, dispatch, deterministic conversion/sort of internal findings |
| `PlannerFixture.kt` | Fixture/expectation values and typed V/S coverage keys/evidence |
| `Oracle.kt` | Pure expectation and INV check implementations; internal subject-keyed findings |
| `PostPlanMaterializer.kt` | Exact result-to-snapshot conversion required by spec AC-6 |
| `SyntheticFixtureGenerator.kt` | Constrained builders and prefix-stable `java.util.Random` generation |
| `ExampleCorpus.kt` | Required 11 examples, malformed cases, and V/S evidence maps |
| `PlannerContractHarnessTest.kt` | Complete-input scripted adapters and all harness self-tests |
| `SyntheticFixtureGeneratorTest.kt` | Generator ranges, prefix/selection determinism, corpus/coverage completeness |

Do not create `PlannerGeneratedPropertyTest.kt` in Issue #11. A runnable class
with that name requires the concrete Issue #12 adapter. Issue #12 will call the
same internal generator and public-in-module `verify` operation; it adds no
harness interface.

### Internal finding and ordering

`Oracle.kt` returns `OracleFinding`, not `String`:

```kotlin
internal data class OracleFinding(
    val check: ContractCheck,
    val subject: FindingSubject,
    val message: String,
)

internal sealed interface FindingSubject {
    data class Item(val id: ItemId) : FindingSubject
    data class Page(val id: PageId) : FindingSubject
    data class Folder(val id: FolderId) : FindingSubject
    data class AppPair(val id: AppPairId) : FindingSubject
    data class NewPage(val ordinal: NewPageOrdinal) : FindingSubject
    data class NewFolder(val ordinal: NewFolderOrdinal) : FindingSubject
    data object None : FindingSubject
}
```

`verify` sorts findings by the spec tuple, then converts them to the fixed
`ContractViolation` shape. Never derive order by parsing `message`.

### Complete-input scripted adapter

The self-test adapter is keyed by complete `OrganizationInput` values and stores
one or more results per key:

```kotlin
private class ScriptedPlanner(
    scripts: Map<OrganizationInput, List<PlanningResult>>,
) : OrganizationPlanner
```

It copies scripts into per-key queues. A one-result script reuses that value for
every call. A multi-result script returns entries in order and reuses the last
entry after exhaustion. Missing keys fail the self-test immediately. This
supports a deterministic adapter, a same-input unequal sequence for the
`DETERMINISM` negative, and independently keyed original/permuted inputs without
implementing placement.

### Coverage representation

Use validated internal value types `ValidationRuleId` (`V-01`…`V-22`) and
`ScenarioId` (`S-01`…`S-20`). Evidence is a sealed value:

```kotlin
internal sealed interface CoverageEvidence {
    data class Fixture(
        val fixture: FixtureId,
        val checks: Set<ContractCheck>,
    ) : CoverageEvidence

    data class Generated(val checks: Set<ContractCheck>) : CoverageEvidence
    data class Downstream(val issue: Int, val clause: String) : CoverageEvidence
}

internal data class CoverageRow<K>(
    val id: K,
    val evidence: List<CoverageEvidence>,
)
```

Store coverage as `List<CoverageRow<ValidationRuleId>>` and
`List<CoverageRow<ScenarioId>>`, where each row has one key and a non-empty
evidence list. The completeness test validates duplicates before constructing
lookup maps, then rejects missing/unknown keys, missing fixture references,
empty check sets, and a scenario represented only by `Downstream` evidence.

Scenario mapping is fixed as follows; all rule meanings remain references to
the accepted Issue #10 spec:

| Scenario | Local fixture/generated evidence | Additional downstream evidence |
|---|---|---|
| S-01 | `apps-only`: EXPECTATION, CONSERVATION, BOUNDS | — |
| S-02 | `mixed-app-shortcut-widget` plus generated incremental: CONSERVATION | — |
| S-03 | `mixed-app-shortcut-widget`: EXPECTATION, LOCK_PRESERVATION, NO_OVERLAP | — |
| S-04 | `locked-fragmented-space`: LOCK_PRESERVATION, NO_OVERLAP | — |
| S-05 | `same-package-personal-work`: PROFILE_ISOLATION | — |
| S-06 | `apps-only`: folder expectation; `undefined-category`: singleton fallback expectation | — |
| S-07 | `apps-only`: S1 category expectation | — |
| S-08 | generated: DETERMINISM, INPUT_PERMUTATION | Issue #12: locale/timezone/thread executions |
| S-09 | generated full: IDEMPOTENCE | — |
| S-10 | generated incremental/full: CONSERVATION, PROFILE_ISOLATION | Issue #12: noncanonical convergence behavior |
| S-11 | generated incremental overflow: EXPECTATION, CONSERVATION | — |
| S-12 | `mixed-app-shortcut-widget`: legacy expectation | — |
| S-13 | generated unavailable captured/candidate: EXPECTATION | — |
| S-14 | V-01/V-02/V-04/V-05/V-08–V-12 malformed fixtures: EXPECTATION | — |
| S-15 | V-07 variant fixtures: EXPECTATION | — |
| S-16 | V-15–V-20 fixtures: EXPECTATION | — |
| S-17 | V-13/V-14 fixtures plus generated valid tie/dedup: EXPECTATION, DETERMINISM | — |
| S-18 | V-21 fixture: EXPECTATION | — |
| S-19 | `mixed-app-shortcut-widget` plus `undefined-category`: EXPECTATION | — |
| S-20 | `empty-home`: EXPECTATION, DETERMINISM | Issue #12: purity/locale inspection |

For validation coverage, create one primary fixture for every V ID and the
following additional cases. Each bullet is a distinct fixture; no fixture relies
on an unrelated defect to reach its expected code.

- V-04: negative workspace x; negative y; right overflow; bottom overflow;
  negative Dock rank; Dock rank equal to `hotseatSlots`.
- V-06: absent `FolderRef`; duplicate `FolderId`; absent `AppPairRef`; duplicate
  `AppPairId`; parent lists a child whose placement does not point back; child
  placement points to a parent that does not list it; duplicate child in one
  member list; disallowed child kind; one child listed by two containers; folder
  cycle. Shapes sharing the V-06 code remain separate even if one also
  demonstrates another V-06 clause.
- V-07: wrong member count; duplicate member ID; duplicate split stage; invalid
  member kind; missing snap; unequal snaps; member placement/pair incoherence.
- V-09: duplicate `PageId`; duplicate `PageOrder`.
- V-10: non-positive captured span width; captured span height; candidate span
  width; candidate span height; columns; rows; hotseat slots; folder max columns;
  folder max rows.
- V-11: captured kind/target mismatch; candidate kind/target mismatch; folder
  missing `folderId`; non-folder with `folderId`; non-folder with non-empty
  `members`; app-pair missing `appPairId`; non-app-pair with `appPairId`;
  app-pair missing metadata; non-app-pair with app-pair metadata.
- V-12: captured target-profile mismatch; candidate target-profile mismatch.
- V-15: duplicate existing membership; duplicate addition ID;
  captured/addition ID collision.
- V-20: unaccepted rule version; minimum group size below two; duplicate allowed
  category; fallback category absent from allowed categories.

V-01–V-03, V-05, V-08, V-13–V-19, and V-21–V-22 use their one primary fixture
from the accepted validation-table condition.

### Generator implementation

Construct one case at a time from a fresh child seed obtained from the single
top-level `Random(seed)`. Include the zero-based case index in `FixtureId` and
`RevisionId`. Do not reserve a variable number of random calls based on the
requested count; this preserves prefixes. Builders choose only valid enum/type
combinations, positive dimensions/spans, unique IDs/orders/ranks, coherent
folder/app-pair references, valid target partitions, accepted rules/taxonomy,
known signals, and fitting candidates. Generator tests inspect these constructed
properties; they do not implement a reusable V-rule evaluator.

System-property selection is not read by the generator. Issue #11 tests a local
selection helper with explicit `(seed, caseIndex)` inputs. The future Issue #12
JUnit wrapper owns reading/forwarding `planner.seed` and `planner.case` and is the
target named by `Reproduction.toString()`.

### Permutation implementation

Build variants from the original input, changing one enumeration at a time. For
each top-level non-semantic list with at least two entries (`pages`, `items`,
signals, existing memberships, additions, allowed categories), create one
left-rotation-by-one variant. For each captured folder or app pair whose member
enumeration has at least two entries, create one variant rotating only that
container's member list. Skip size-zero/one no-ops, de-duplicate value-equal
variants, and never change rank, order, stage, identity, or any other semantic
value. Compare every variant result with the original result by full value equality.

### Oracle and materializer implementation

Implement the spec checks in `Oracle.kt` as pure functions returning typed
findings. `EXPECTATION` owns echo checking. Profile isolation resolves profile
from the input inventory. It enforces same-profile membership for `NewFolder`
and items newly moved into a captured folder. A captured mixed-profile folder is
valid only when its original membership and member targets are unchanged. The
oracle never looks for a nonexistent profile field on `PlannedPlacement`.

Expectation matching compares warning/rejection/unplaced codes as typed sets.
Optional `requiredDetails`/`requiredItems` use full data-class equality only for
Issue #10 rows with an unambiguous affected value and params. Required category
decisions use full value equality; each required preservation matches that
item's `Disposition.Preserved.reason`; and each non-null page/folder count equals
the returned count. Outcome-family or echo mismatch produces `EXPECTATION` and
prevents outcome-inapplicable checks from running.

`PostPlanMaterializer` follows spec AC-6 exactly:

1. Build maps for each returned new page/folder ordinal and reject unresolved or duplicate ordinals as findings before materialization.
2. Append mapped pages with returned `PageOrder`.
3. Convert every returned placement target to a captured placement, replacing only plan-local refs through those maps.
4. Preserve each original captured item's identity, kind, target, lock, availability, structure metadata, and original `ExistingRole`.
5. Materialize each `NewFolder` as one deterministic captured folder item at the returned workspace target with `ExistingRole.Preserved`; map its member targets to the new `FolderRef`.
6. Use a deterministic synthetic revision; retain rules, taxonomy, signals, and `FullOrganization`; additions remain empty as required by V-18.

The materializer never selects a cell, rank, page, folder, category, disposition,
or preservation reason.

## Change set

Only the eight files listed in the module tree may be added. Existing production,
build, version-catalog, resource, manifest, Deck, Launcher3/AOSP, spec, and design
files are not changed during implementation.

## Implementation sequence

1. Add fixture/report/coverage types and failing shape/order/reproduction tests.
2. Add `verify` dispatch plus EXPECTATION and rejected-family separation tests.
3. Add each structural oracle test-first, using one independent result mutation.
4. Add sequence/permutation scripts and their positive/negative tests.
5. Add materializer tests for existing refs, new pages, new folders, role retention,
   and the idempotence negative; then implement the materializer.
6. Add the 11 examples, validation variants, typed evidence maps, and completeness tests.
7. Add explicit-seed generator tests, then constrained generation and case selection.
8. Run all verification and stop for Codex review.

## Required self-tests

- Happy path and EXPECTATION mismatch, including echo.
- One independent negative each for CONSERVATION, BOUNDS, NO_OVERLAP,
  CONTAINER_INTEGRITY, LOCK_PRESERVATION, and PROFILE_ISOLATION; the profile
  mutation places different-profile members in one `NewFolder`.
- Same-input equal result and queued unequal-result DETERMINISM cases.
- Original/permutation equal result and one permutation-sensitive result.
- Invalid and Impossible expectations prove planned safety checks are skipped.
- Existing-page/new-page and existing-folder/new-folder materialization; original
  roles unchanged; synthetic folder role preserved; replan movement detected.
- Violation subject ordering and message tie-break; exact reproduction string.
- Same seed/count equality, prefix stability, different indexed inputs, explicit
  case selection, dimension/orientation/kind/profile/mode coverage.
- Exactly 11 unique example IDs; every V/S key; every referenced fixture/check;
  every listed validation variant.
- A call counter proves the harness crosses only `OrganizationPlanner.plan`.

## Migration and recovery

None. The change is test-only and keeps no data.

## Verification

| Spec AC | Evidence | Command |
|---|---|---|
| AC-1, AC-4–AC-9 | Harness self-tests and path/dependency review | Unit-test task below |
| AC-2–AC-3, AC-7 | Corpus/generator tests | Unit-test task below |
| AC-10 | Full command set | Commands below |

Set JDK 21 and Android SDK 36.1 as documented in
`docs/engineering/building.md`, then run:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
git diff --check
```

Review must also confirm that `git status --short` lists only the accepted spec/
plan and the eight allowed harness files, with no dependency or production change.

## Documentation updates

- No `CONTEXT.md`, `DESIGN.md`, ADR, `AGENTS.md`, or build-guide change is expected.
- The spec is already accepted. Mark it `implemented` only when Issue #11 is
  accepted on main.

## Stop conditions

Stop without improvising if an Issue #10 type cannot express a fixture/oracle,
materialization would require choosing a placement or changing a production
apply decision, a dependency/build change appears necessary, any production or
platform file would need editing, or the Issue #10 public contract would change.

## Execution checklist

- [ ] Add only the eight allowed harness files.
- [ ] Follow the test-first sequence above.
- [ ] Run the complete verification set.
- [ ] Confirm the final path/dependency scope.
- [ ] Stop for Codex review; do not commit, push, open a PR, or mutate GitHub.
