# Implementation Plan: Preserve folder-child representability across workspace moves

> Issue: #269
> Spec: [spec.md](./spec.md)
> Status: draft — implementation is blocked until the Spec 13 revision is accepted
> Baseline: `main` at `b25f20ca7c31ad384fbe8f8b696e87118f8fbe8c`; upstream baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`.

## Current evidence

- The accepted Issue #265 investigation was reproduced on API 36.1 with the
  real Settings orchestration, production adapters, durable recovery store,
  real `LauncherModel` reloads, and the baseline writer. Path A moves a folder
  child to the workspace and produces `spanX/spanY = NULL`; recovery preview
  then throws from canonical capture. Path B restores exactly and remains a
  control.
- `LauncherLayoutAdapter.rowFor` currently maps `PlacementState.FolderChild`
  to `screen = null`, `cell = null`, `span = null` (lines 615–624).
- `RowManifestCodec.toCanonical` requires a non-null `rawSpan` for a desktop
  row (lines 257–262). This strictness is correct for desktop canonical state
  and remains unchanged by this plan.
- `ModelWriter.moveItemInDatabase` writes container, cell, rank, and screen,
  but not span (lines 190–200). Its placement-only behavior is shared by
  normal Launcher paths and is not changed by this plan.
- `PersistentRow` already accepts and serializes positive spans and the
  recovery codec already carries nullable raw span values. No data-format
  change is required.
- The working tree contains unrelated user-owned untracked paths:
  `.ux-review/` and the Issue #265 instrumentation harness. They must be
  preserved and reviewed as part of the implementation PR rather than
  discarded by this work.

## Design

### Modules and interfaces

The public `LayoutWriterPort`, `OrganizationPlanner`, and application result
types remain unchanged. The implementation uses the existing production
adapter seam:

| Module | Responsibility | Boundary |
|---|---|---|
| `LauncherLayoutAdapter.rowFor` | Materialize a `FolderChild` into a lossless `PersistentRow` with retained-or-default positive span | No Android/SQLite type crosses the public application state; `PersistentRow` stays internal |
| `RowManifestCodec` | Continue lossless capture/serialization and strict desktop canonicalization | No capture-time span invention; no typed preview mapping here |
| baseline `ModelWriter` | Continue placement-only move behavior | No broad Launcher3 writer policy change |
| `LayoutApplicationModule` / existing protocols | Apply, recovery point, correlated reload, and A7 verification | No lifecycle or recovery-contract change |

The first implementation change should be a small private helper at the
materialization boundary, for example `folderChildSpan(base)`, that returns
`base?.rawSpan ?: GridSpan(1, 1)`. It must be used only in the
`PlacementState.FolderChild` branch. It must not be used for desktop capture,
`AppPairChild`, `Dock`, or `UnsupportedContainer` branches.

### Data flow

1. Capture reads the current rows losslessly. A folder child may still have a
   NULL raw span in the input because its public placement is parent/rank.
2. Planning and validation continue to operate on `FolderChild(parent, rank)`.
3. During normal apply materialization, an existing positive raw span is
   copied; a missing raw span becomes `1×1`. The public intended state remains
   unchanged, while the intended persistence manifest contains a positive
   span for every materialized folder child.
4. A5 writes the intended rows in the existing transaction. The recovery
   point stores the exact pre-state and positive-span intended post-state.
5. The platform writer later moves a child by changing container/page/cell;
   its placement-only update leaves the stored positive span intact.
6. A fresh capture maps the moved row to `Workspace(page, cell, span)`, and
   A7 compares DB/model/intended state as before.
7. Recovery still materializes the exact recorded pre-state. It never
   normalizes an old recovery record, so exact restore remains exact.

### Alternatives rejected

- **Change `ModelWriter.moveItemInDatabase` to write a default span**: rejected
  for this Issue. It is a shared Launcher3 bridge and would require deciding
  how every item type, widget, and move call site supplies span data. The
  organizer-created invalid row can be prevented at its owning seam with a
  smaller and more reviewable change.
- **Accept NULL span as canonical desktop `1×1` during capture**: rejected.
  It makes capture diverge from the lossless write/recovery manifest, hides a
  malformed/external row, and weakens the exact `Workspace` contract. Typed
  reporting of this exceptional input is #270.
- **Rewrite all folder-child cell/span columns during recovery**: rejected.
  Recovery must restore the exact pre-apply manifest; normalization would
  violate Spec 13's byte-equivalent recovery promise and would require a
  migration/compatibility decision.
- **Add a new public nullable span variant**: rejected. Folder-child semantic
  placement does not need raw workspace geometry and the public application
  seam must remain closed and platform-free.

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `specs/13-safe-layout-application/spec.md` | After acceptance, add the Spec 13 normative folder-child raw-span rule and link Issue #269 in change history/downstream gates | Spec 13 owns the canonical/application contract; this Issue supplies the accepted revision |
| `specs/269-folder-workspace-representability/spec.md` | Keep the observable outcome, selected seam, exactness, scenarios, and ACs | Issue #269 is the source of the follow-up behavior and status gate |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` | In the `FolderChild` materializer, retain `base.rawSpan` when present, otherwise emit `GridSpan(1, 1)`; add a focused Issue #269 comment | The invalid row is created here; no public seam or baseline writer expansion is needed |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt` | Turn Path A into the regression oracle: assert positive post-apply child span, real writer move, `Restorable → Restored`, exact pre-run manifest, and a second organize pass; retain Path B control | Exercises the complete production flow requested by Issue #269 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/RealAdapterRowMatrixInstrumentationTest.kt` or a focused sibling | Add a production adapter contract fixture for an existing NULL-span folder child and deterministic `1×1` intended materialization; add a desktop NULL-span strictness control | Keeps the owning seam covered without testing private implementation details directly |
| `tests/unit/app/lawnchair/organizer/application/...` | Add only pure helper/contract coverage if the final implementation extracts a platform-free helper | Unit coverage is optional and must not create a second production seam |

The implementation PR must not modify `RecoveryRecordCodec`, recovery DB DDL,
`ModelWriter.java`, planner semantics, or UI/resources unless an accepted
spec change proves that the selected seam cannot satisfy the ACs.

## Migration and recovery

- No Launcher DB schema or recovery format migration.
- No backup/restore allowlist change. Recovery records continue to be excluded
  from Lawnchair ZIP and Android backup as specified by Spec 13.
- New organizer apply write sets contain positive spans for folder children;
  their recovery points record these exact intended rows.
- Existing recovery points remain readable and are restored byte-for-byte,
  including a NULL span on a folder-child row. They are not rewritten during
  startup reconciliation or explicit recovery.
- If A5/A7 observes any divergence, the existing stale/precondition or
  post-commit recovery result applies. The implementation must not add a
  repair write, retry loop, sleep, or whole-table replacement.
- A release downgrade requires no special handling because no persisted format
  changed. The behavior difference is in the new binary's apply materializer.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-269-01 | Adapter/application seam asserts `FolderChild` intended rows use retained positive span or `1×1`; DB read-back confirms both `SPANX` and `SPANY` are non-NULL and positive before the real move | Focused organizer instrumentation test on API 36.1; existing organizer unit suite for any extracted pure helper |
| AC-269-02 | Path A runs organize → real `ModelWriter` move → recovery preview/confirm and asserts `Restorable`, `Restored`, and exact manifest equality | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.Issue265ManualEditRecoveryInstrumentationTest` on `nunu_qpr2_api36_1` |
| AC-269-03 | Path A performs a second organize after the manual move and asserts no `InputUnavailable`, no capture exception, and verified outcome; repeated post-move capture/materialization is deterministic | Same API 36.1 instrumentation target; use latch/state assertions, not sleeps as the oracle |
| AC-269-04 | Path B exact restore/next-start assertions remain green; injected/external desktop NULL span remains rejected by strict canonical capture without a write | Same instrumentation suite plus focused row-matrix instrumentation test |
| Spec 13 revision | Spec 13 status/history and #269 cross-reference are updated only after owner acceptance; no implementation starts while draft | Markdown review and repo-contract validation |
| Layout-data gate | Implementation PR records the accepted spec/plan revision, exact base/head SHA, full relevant diff, successful CI `final-status`, and independent audit under `docs/assessment/pr-<number>-<slug>.md` | GitHub Actions + `docs/project/github-workflow.md` high-risk evidence contract |

Required verification surface also includes the existing repository commands
when implementation begins: `./gradlew spotlessCheck`, the relevant organizer
unit/instrumentation suites, and `./gradlew assembleLawnWithQuickstepGithubDebug`.
The exact connected-test invocation must be confirmed on a clean checkout or
CI before it becomes a new required command in `docs/engineering/building.md`.

## Documentation updates

- [ ] `specs/269-folder-workspace-representability/spec.md` accepted by owner
- [ ] `specs/13-safe-layout-application/spec.md` updated with the accepted
      normative revision and cross-reference
- [ ] `specs/269-folder-workspace-representability/plan.md` status changed to
      implementation-ready only after spec acceptance
- [ ] `CONTEXT.md` — no change expected
- [ ] `DESIGN.md` — no structural seam change expected; update only if review
      finds the adapter responsibility description materially changed
- [ ] ADR — not required; the responsibility choice is local to the existing
      accepted Spec 13 boundary and introduces no new durable format or
      high-cost system-wide decision
- [ ] `docs/assessment/pr-<number>-<slug>.md` on the implementation PR, because
      the changed adapter path is `risk: layout-data`

## Execution checklist

- [ ] Current behavior reproduced from the accepted #265 evidence.
- [ ] Focused regression test fails before the implementation change.
- [ ] Spec #269 and the Spec 13 revision are accepted.
- [ ] Minimal materialization change completed; no `ModelWriter` bridge change.
- [ ] Path A passes through `Restorable → Restored` with exact manifest
      equality, including the second organize pass.
- [ ] Path B control remains exact.
- [ ] Strict malformed desktop capture behavior remains fail-closed.
- [ ] `spotlessCheck`, relevant organizer tests, and debug build pass.
- [ ] High-risk CI and independent audit evidence are recorded before merge.
