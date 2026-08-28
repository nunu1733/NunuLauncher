# Issue #164 pre-fix red-oracle evidence

> Recorded: 2026-08-28
> Branch: `docs/issue-164-new-folder-a7-item-order`
> State: the test-first commit before the canonical-finalization fix. The
> production seam (`IntendedStateResolution.resolveAndFinalize`) reproduces
> the pre-fix behavior verbatim (planned order preserved, new folder kept
> last), so the accepted oracles run red exactly as the device does.

## Review re-verification (real materializer fixtures, 2026-08-28)

After the implementation review, the oracles were rewritten to consume plans
from the **real `OrganizationPlanMaterializer`** (`NewFolderPlanFixtures`:
`OrganizationInput` + `PlanningResult` → `materialize(...)` → `Ready.plan`);
no hand-assembled materializer output remains. Reverting only the four
production files to the pre-fix state (`IntendedStateResolution`,
`RowManifestCodec`, `MaterializedStateValidator` from `a667b18e79`, and
absent `CanonicalItemOrder`) against the rewritten oracles:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.application.actions.IntendedStateCanonicalOrderTest' \
  --tests 'app.lawnchair.organizer.application.protocol.NewFolderCanonicalOrderProtocolTest'
# 8 tests completed, 5 failed — the intended pre-fix failures
```

- `singleFolderPlanFromRealMaterializerIsCanonicalAfterResolution`:
  `expected:<[1, 10, 2, …]> but was:<[1, 2, …, 10]>` (folder appended last)
- `multiFolderPlanWithBoundaryIdsMixesIntoCanonicalOrder`:
  `expected:<[1, 100, 101, 19, 9, 91, 99]> but was:<[1, 9, 19, 91, 99, 100, 101]>`
- `finalizationFailsClosedOnUnresolvedTopLevelReference` / `…NestedReference`:
  `expected null, but was:<LayoutState…>` (no fail-closed yet)
- `newFolderPlanReachesAppliedAfterCanonicalFinalization`:
  `Expected Applied (A8), got Recovered(…, failure=VERIFICATION_FAILED)`

Green at the fix commit: the same 8 oracles pass, and the full unit suite is
755 tests / 0 failures.

## Original red run (initial oracles at `a667b18e79`)

### Command

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest \
  --tests 'app.lawnchair.organizer.application.actions.IntendedStateCanonicalOrderTest' \
  --tests 'app.lawnchair.organizer.application.protocol.NewFolderCanonicalOrderProtocolTest'
```

Result: `6 tests completed, 3 failed` — the three intended pre-fix failures.

## Failing oracles (intended red)

### AC-164-01 — seam-level canonical order

```text
IntendedStateCanonicalOrderTest > resolvedIntendedStateIsInCanonicalItemIdByteOrder FAILED
java.lang.AssertionError: Intended items must be in canonical ItemId byte order
(folder id 10 between 1 and 2) expected:<[1, 10, 2, 3, 4, 5, 6, 7, 8, 9]>
but was:<[1, 2, 3, 4, 5, 6, 7, 8, 9, 10]>
```

The resolved intended state keeps the new folder (allocated id 10) appended
last, while the canonical recapture order is `[1, 10, 2, …, 9]` — the exact
divergence from the Issue #164 device log (`ITEM[10] recap=…ItemId(value=19)`
vs `want=…ItemId(value=2)`).

### AC-164-01 — fail-closed invariant

```text
IntendedStateCanonicalOrderTest >
finalizationFailsClosedOnUnresolvedReferenceInsteadOfInventingAnOrder FAILED
java.lang.AssertionError: Unresolved references must fail closed, not produce
a fallback order expected null, but was:<LayoutState(…)>
```

The pre-fix seam has no finalization, so an unresolved reference passes
through instead of failing closed; the fix implements the rejection.

### AC-164-02 — protocol outcome

```text
NewFolderCanonicalOrderProtocolTest >
newFolderPlanReachesAppliedAfterCanonicalFinalization FAILED
java.lang.AssertionError: Expected Applied (A8), got
Recovered(runId=…, pointId=…, failure=VERIFICATION_FAILED)
```

With the opt-in `productionEquivalentCapture` mode (writer side via the real
production resolution seam; recapture side independently rebuilt from
persisted-row-equivalent state in capture-side canonical order), the run
fails A7 exactly as the device journal records:
`APPLY_COMMITTED (A6)` → `APPLY_RECOVERED (A7, err=APPLY_FAILURE.VERIFICATION_FAILED)`,
with the manifest side equal and only the item order divergent.

## Passing oracles (unchanged by the fix, must stay green)

- `resolutionIsDeterministicAcrossRepeatedPreparation` — determinism holds
  before and after the fix.
- `folderIdentityResolvesAcrossRefPlacementTargetKeyAndStructure` — planned
  folder identity resolution (ref, target key, placement parent, structure
  members) is unchanged; only presentation order changes.
- `genuineDbDriftAfterCommitStillFailsClosedAndRecovers` — a genuine
  persisted-row mutation still fails A7 and recovers safely (`Recovered`,
  `VERIFICATION_FAILED`, pre-apply state restored, 2 reloads). This is the
  no-weakening oracle; it is green pre-fix and must remain green post-fix.

## Instrumentation roundtrip (AC-164-02 reinforcement)

`RealAdapterRowMatrixInstrumentationTest.newFolderWriteSetIntendedStateMatchesRealCanonicalCapture`
asserts the real `RowManifestCodec.capture` output equals the resolved
intended state for the same rows; it fails against the pre-fix seam (folder
appended last) and passes after canonical finalization.
