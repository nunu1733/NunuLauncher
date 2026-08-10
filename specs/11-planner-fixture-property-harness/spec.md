---
issue: "#11"
status: accepted
requirements:
  - NFR-002
  - NFR-003
  - NFR-004
  - NFR-005
  - NFR-010
updated: 2026-08-10
source:
  - https://github.com/nunu1733/NunuLauncher/issues/11
  - ../10-pure-organization-planning/spec.md
  - ../../docs/engineering/quality-strategy.md
---

# Planner fixture and property-test harness

## Problem

Issue #10 defines the pure `OrganizationPlanner` contract. Downstream planner
work needs one reusable black-box harness for synthetic fixtures, invariant
checks, deterministic generation, and reproducible diagnostics instead of
reimplementing those concerns in each test.

## Outcome

A test-only harness accepts an `OrganizationPlanner`, evaluates a typed fixture
through the production `plan` seam, and returns an ordered report of observable
contract violations. It computes no layout and requires no production planner.

## Scope

- One callable operation: `PlannerContractHarness.verify(PlannerFixture)`.
- Typed fixtures, expectations, checks, violations, reports, and reproduction data.
- An in-memory corpus containing the 11 required examples and constructible
  V-01–V-22 cases from the accepted Issue #10 spec.
- Deterministic, prefix-stable generation of 64 valid synthetic cases by default.
- Black-box checks for expectations, INV-1–INV-8 evidence, same-input
  determinism, and declared input permutations.
- Deterministic failure ordering and an exact reproduction command.

## Non-goals

- No concrete planner, classifier, canonicalizer, V-rule evaluator, occupancy
  engine, placement/folder/bin-packing algorithm, or exact-cell oracle.
- No database application, Android/Launcher3/Deck integration, UI, resources,
  permissions, migration, benchmark, emulator, network, or filesystem corpus.
- No second planner interface, internal planner mock, general assertion DSL,
  serializer, property-test dependency, or change to Issue #10 contract types.
- This Issue does not execute generated cases against a fabricated planner.
  A concrete planner's test supplies the adapter in Issue #12 and calls the same
  `verify` operation.

## Harness interface

```kotlin
class PlannerContractHarness(
    private val planner: OrganizationPlanner,
) {
    fun verify(fixture: PlannerFixture): VerificationReport
}
```

The harness calls only `OrganizationPlanner.plan`. Planner exceptions propagate
unchanged; the report describes violations in returned `PlanningResult` values.

## Fixture and report model

- `FixtureId`: a non-empty synthetic identifier with value equality.
- `PlannerFixture`: `id`, canonical `OrganizationInput`, typed
  `FixtureExpectation`, enabled `ContractCheck` set, and optional `Reproduction`.
- `FixtureExpectation`: the closed shape below. Every `required*` collection is
  subset matching; nullable counts are unconstrained when null and exact when set.
- `ContractCheck`: the closed enum `EXPECTATION`, `CONSERVATION`, `BOUNDS`,
  `NO_OVERLAP`, `CONTAINER_INTEGRITY`, `LOCK_PRESERVATION`,
  `PROFILE_ISOLATION`, `DETERMINISM`, `INPUT_PERMUTATION`, `IDEMPOTENCE`.
- `ContractViolation`: fixture ID, check, optional reproduction, and a readable
  diagnostic.
- `VerificationReport`: ordered violations and derived `isSuccess`.
- `Reproduction`: seed and zero-based generated-case index.

```kotlin
internal data class FixtureExpectation(
    val outcome: ExpectedOutcome,
    val requiredWarningCodes: Set<WarningCode> = emptySet(),
)

internal sealed interface ExpectedOutcome {
    data class Planned(
        val requiredPreservations: Map<ItemId, PreserveReason> = emptyMap(),
        val requiredCategories: Set<CategoryDecision> = emptySet(),
        val expectedNewPageCount: Int? = null,
        val expectedNewFolderCount: Int? = null,
    ) : ExpectedOutcome

    data class Invalid(
        val requiredCodes: Set<RejectionCode>,
        val requiredDetails: Set<RejectionReason> = emptySet(),
    ) : ExpectedOutcome

    data class Impossible(
        val requiredReasons: Set<UnplacedReason>,
        val requiredItems: Set<UnplacedItem> = emptySet(),
    ) : ExpectedOutcome
}
```

Counts and reproduction indices must be non-negative. `Invalid.requiredCodes`
and `Impossible.requiredReasons` must be non-empty. Required codes/reasons use
typed set containment. Optional full-value details use data-class equality only
where Issue #10 fixes the affected value and params unambiguously; every detail's
code/reason must also appear in its required code/reason set. Warnings are matched
by `WarningCode`, because Issue #10 does not define canonical warning params. The
expectation never fixes an otherwise unspecified cell, page ordinal, or folder ordinal.

Revision, rule-version, and taxonomy-version echo are part of `EXPECTATION`.
`isSuccess` is true exactly when `violations` is empty.

### Deterministic violation order

The observable order is fixture ID UTF-8 byte order, then `ContractCheck`
declaration order, then the affected subject. Subject order is `ItemId`,
`PageId`, `FolderId`, `AppPairId`, plan-local page ordinal, plan-local folder
ordinal, then no-subject; values use their canonical ordering. Multiple findings
for the same subject use diagnostic text as the final tie-break. The diagnostic
text is not parsed to obtain the subject.

## Deterministic generation

The module's internal generator behaves as:

```kotlin
SyntheticFixtureGenerator.generate(
    seed: Long = 0x4E554E55L,
    count: Int = 64,
): List<PlannerFixture>
```

- All randomness comes from one `java.util.Random(seed)`.
- `count` must be positive. Output has exactly `count` fixtures, indexed from 0.
- Generation is prefix-stable: `generate(seed, n)` equals the first `n` values
  of `generate(seed, m)` whenever `0 < n <= m`.
- Fixture IDs use `fixture.generated.<index>` and revisions use
  `fixture.revision.<index>`, so distinct indices select distinct value inputs.
- Inputs are valid by constrained construction and span 3–8 columns/rows; every
  orientation; zero through multiple pages; every captured kind; Dock items;
  personal/work profiles sharing a component; locked/unavailable items; full
  and incremental modes; and fitting candidate additions.
- Identifiers use reserved `fixture.`, `profile.`, `component.`, and `category.`
  prefixes. No installed package, raw user serial, database ID, or device data is read.
- Generated fixtures enable structural safety, determinism, and permutation
  checks. Their expectations do not prescribe exact placement choices.

A future concrete-planner JUnit test may select one generated index from
`planner.seed` and `planner.case`. This module self-tests generation and
selection without supplying a fake planner.

## Required example corpus

The example corpus contains exactly these stable IDs:

1. `empty-home`
2. `apps-only`
3. `mixed-app-shortcut-widget`
4. `folder-container-integrity`
5. `locked-fragmented-space`
6. `full-grid-no-capacity`
7. `multiple-pages-and-dock`
8. `same-package-personal-work`
9. `undefined-category`
10. `device-profile-variation`
11. `deck-output-compatibility`

The Deck-shaped example is synthetic: top-level folders with ranked application
members plus a singleton. It contains no Deck type, title match, Flowerpot rule,
database row, real package, runtime hook, or approval of Deck behavior.

A separate malformed corpus makes every V-01–V-20 `Invalid` shape and every
V-21–V-22 `Impossible` shape constructible. Variant shapes explicitly required
by the Issue #10 validation table remain distinct cases. Expected codes and
parameters are the typed values specified by that table.

## Coverage contract

Coverage keys are typed validation/scenario IDs, not arbitrary strings. Each key
maps to one or more typed evidence entries containing fixture IDs and enabled
checks. The completeness test requires every V-01–V-22 and S-01–S-20 key,
rejects unknown/duplicate keys, and requires referenced fixture IDs to exist.

Two mappings need explicit multi-evidence:

- S-06 maps a groupable same-profile example to folder creation and separately
  maps `undefined-category` to singleton fallback plus `FALLBACK_CATEGORY`;
  the fallback example must not require a new folder.
- S-17 maps malformed V-13/V-14 inputs and a valid generated same-source tie/
  exact-duplicate case whose expectation uses canonical `CategoryId` ordering.

S-08 maps generated `DETERMINISM` and `INPUT_PERMUTATION` evidence. Its
locale/timezone/thread execution, and S-20's production purity inspection, are
recorded as downstream Issue #12 evidence because Issue #11 has no concrete
planner. Downstream evidence never replaces the required local fixture/check
entry for a scenario key.

## Oracle behavior

The accepted Issue #10 spec is normative for V-01–V-22 and INV-1–INV-9. The
harness observes returned values as follows:

- `EXPECTATION` checks the outcome family, echo fields, and only the typed
  observations requested by the fixture.
- `CONSERVATION`, `BOUNDS`, `NO_OVERLAP`, `CONTAINER_INTEGRITY`,
  `LOCK_PRESERVATION`, and `PROFILE_ISOLATION` check INV-1–INV-6. Profile checks
  infer an item's profile from the input inventory. Every `NewFolder` is
  single-profile. A member newly moved into a captured folder must match that
  folder parent's profile; pre-existing mixed-profile folder membership is
  valid only when its membership and member targets remain unchanged.
- `DETERMINISM` calls `plan` twice with the same value input and compares the
  complete results by value.
- `INPUT_PERMUTATION` independently rotates only lists declared
  non-semantic by Issue #10 canonicalization: pages, captured items, signals,
  existing memberships, additions, taxonomy categories, and folder/app-pair
  member enumeration. Rank values and other semantic values are unchanged.
- `IDEMPOTENCE` runs only after a `FullOrganization` result is `Planned`. It
  materializes that result, replans it, and requires no effective target change,
  exactly one placement for every materialized item, no unknown item, no
  `Moved` dispositions, and empty `newPages`/`newFolders`. An otherwise
  movable top-level item whose target is unchanged must be
  `Preserved(ALREADY_CANONICAL)`; higher preservation predicates retain their
  existing reasons.
- `Invalid` checks only `EXPECTATION`; `Impossible` checks its typed expectation.
  Planned-layout safety checks never run on either rejected family.

### Idempotence materialization

The materialized snapshot retains every original captured identity, metadata,
and `ExistingRole`; full runs have no additions by V-18. It maps plan-local page
and folder ordinals to deterministic reserved IDs, appends materialized pages
with the output orders, and converts each returned target to the corresponding
captured placement without choosing a target.

Each `NewFolder` becomes one synthetic captured folder item with deterministic
folder/item IDs, the returned workspace placement and members, and
`ExistingRole.Preserved`. Member `NewFolderRef` values become its `FolderRef`.
Original members retain their original target roles. The new snapshot uses a
deterministic synthetic revision and otherwise keeps rules, taxonomy, signals,
and full run mode. This structural conversion is the only new inventory needed
to represent an applied folder result.

## Reproduction text

Generated failures render exactly:

```text
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests '*PlannerGeneratedPropertyTest*' -Dplanner.seed=<seed> -Dplanner.case=<index>
```

`PlannerGeneratedPropertyTest` is supplied with the concrete planner by Issue
#12; Issue #11 verifies the command formatting and deterministic case selection.
Seed and index are rendered as signed base-10 values using `Long.toString()` and
`Int.toString()`.

## Behavior scenarios

### Conforming result

Given a canonical fixture and a scripted adapter returning a conforming result,
when `verify` runs, then the report is successful and has no violations.

### Independently broken result

Given the same fixture/result pair with exactly one observable mutation for an
enabled check, when `verify` runs, then the report contains that check's
violation. Each check has an independent negative self-test.

### Permutation and sequence detection

Given complete-input-keyed scripts for the original and permuted inputs, the
harness accepts value-equal results and rejects a permutation-sensitive result.
Given a result sequence for one value input, it rejects unequal consecutive
results as `DETERMINISM`.

### Rejected result separation

Given an `Invalid` or `Impossible` expectation, when the adapter returns that
family, then typed expectation checks run and planned-layout checks do not.

## Data and state

All values are synthetic and in memory. `verify` retains no state after the
call and performs no persistent write or I/O.

## Permissions, privacy, and security

None. The harness adds no permission and reads no external or personal data.

## Accessibility and localization

No UI. Diagnostic text is test output and is deterministic English developer
text; product localization is unaffected.

## Acceptance criteria

- [ ] AC-1: the harness accepts only `OrganizationPlanner` and exposes only `verify`.
- [ ] AC-2: all fixtures and identities are synthetic; the required 11 IDs exist.
- [ ] AC-3: V-01–V-22 and S-01–S-20 coverage is typed and machine-checked.
- [ ] AC-4: expectation and each structural safety check have independent positive/negative evidence.
- [ ] AC-5: determinism and declared permutations are checked through `plan`.
- [ ] AC-6: full-run materialization verifies idempotence without choosing placement or changing original membership roles.
- [ ] AC-7: seed/index generation is deterministic and failure text is reproducible.
- [ ] AC-8: rejected outcomes are separated from planned-layout checks.
- [ ] AC-9: no internal planner helper, concrete planner behavior, new dependency, production type, or platform source is added.
- [ ] AC-10: unit tests, Spotless, debug assembly, repository-contract checks, and `git diff --check` pass.

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | Compile review and seam self-test |
| AC-2–AC-3 | Corpus/generator completeness self-tests |
| AC-4 | Scripted result mutations through `verify` |
| AC-5 | Complete-input script sequence and permutation self-tests |
| AC-6 | Materialized full-run replan self-test |
| AC-7 | Prefix/selection/reproduction self-tests |
| AC-8 | Invalid and Impossible family self-tests |
| AC-9 | Path/dependency review and same-seam tests |
| AC-10 | Commands named in the implementation plan |

## Open questions

None.

## Change history

- 2026-08-10: Issue #31 makes the idempotence oracle require unchanged targets
  and the typed `ALREADY_CANONICAL` reason for otherwise movable top-level
  items.
- 2026-08-10: Accepted after Codex review. Separates the future concrete-planner
  runner from this harness, makes coverage and violation ordering implementable,
  and fixes profile, determinism, fallback, and idempotence semantics.
