# Implementation Plan: Preserve folder-child representability across workspace moves

> Issue: #269
> Spec: [spec.md](./spec.md)
> Status: implementation-complete locally — connected and high-risk gate evidence pending
> Baseline: `main` at `b25f20ca7c31ad384fbe8f8b696e87118f8fbe8c`; upstream baseline `505dbc40e6154c05158b5d0271c45f6a885a411b`.

## Current evidence

- The accepted Issue #265 investigation was reproduced on API 36.1 with the
  real Settings orchestration, production adapters, durable recovery store,
  real `LauncherModel` reloads, and the baseline writer. Path A moves a folder
  child to the workspace and produces `spanX/spanY = NULL`; recovery preview
  then throws from canonical capture. Path B restores exactly and remains a
  control.
- `LauncherLayoutAdapter.rowFor` maps `PlacementState.FolderChild` to
  `screen = null`, `cell = null`, `span = null` (current lines 615–624). That
  is valid for the public folder-child semantic state; it is not the owner of
  the later folder-child → workspace transition.
- `RowManifestCodec.toCanonical` requires a non-null `rawSpan` for a desktop
  row (lines 257–262). This strictness is correct for desktop canonical state
  and remains unchanged by this plan.
- `ModelWriter.moveItemInDatabase` writes container, cell, rank, and screen,
  but not span (current lines 190–200). The targeted bridge will add `1×1`
  span persistence when the destination is the desktop and the item is a
  `WorkspaceItemInfo`, independently of its source container.
- `WorkspaceItemProcessor` restores application and deep-shortcut icon items
  with `spanX=1` and `spanY=1` (current lines 318–327); the writer transition
  must persist that same model representation.
- `PersistentRow` already accepts and serializes positive spans and the
  recovery codec already carries nullable raw span values. No data-format
  change is required.
- The working tree contains the unrelated user-owned untracked `.ux-review/`
  evidence directory. It is preserved and excluded from this implementation;
  the Issue #265 instrumentation harness is included in the change set because
  it is the regression oracle for this issue.

## Design

### Modules and interfaces

The public `LayoutWriterPort`, `OrganizationPlanner`, and application result
types remain unchanged. The implementation uses the existing production
writer seam:

| Module | Responsibility | Boundary |
|---|---|---|
| `LauncherLayoutAdapter.rowFor` | Preserve the existing lossless `FolderChild` representation (`NULL` cell/span) | No Android/SQLite type crosses the public application state; `PersistentRow` stays internal |
| `RowManifestCodec` | Continue lossless capture/serialization and strict desktop canonicalization | No capture-time span invention; no typed preview mapping here |
| baseline `ModelWriter.moveItemInDatabase` | Normalize icon span when an existing `WorkspaceItemInfo` enters the desktop | One targeted Launcher3 bridge; widget/folder items, unrelated writer methods, and `moveItemsInDatabase` remain unchanged |
| `LayoutApplicationModule` / existing protocols | Apply, recovery point, correlated reload, and A7 verification | No lifecycle or recovery-contract change |

The first implementation change should be a small, local condition in
`ModelWriter.moveItemInDatabase`: after the common position update, when the
destination is `Favorites.CONTAINER_DESKTOP` and the item is a
`WorkspaceItemInfo`, set `item.spanX`/`item.spanY` to `1` and include
`Favorites.SPANX`/`Favorites.SPANY` in the pending `ContentValues`. Do not
classify the source container or inspect its parent kind. Do not change
`LauncherLayoutAdapter.rowFor`, desktop capture, or other writer methods.

### Data flow

1. Capture reads the current rows losslessly. A folder child may still have a
   NULL raw span in the input because its public placement is parent/rank.
2. Planning and validation continue to operate on `FolderChild(parent, rank)`.
3. A5 writes the intended rows in the existing transaction. A folder child
   may still have nullable raw cell/span columns because its public semantic
   state is `FolderChild(parent, rank)`.
4. When an existing `WorkspaceItemInfo` enters the desktop through
   `moveItemInDatabase`, regardless of source container, set both the in-memory
   item and pending DB span to `1×1`. A folder → Hotseat move preserves the
   Hotseat semantic row; the subsequent Hotseat → desktop move triggers this
   destination rule.
5. The writer commits container/page/cell and the normalized span in the same
   existing write path, preserving its queueing and notification behavior.
6. A fresh capture and model reload both observe `Workspace(page, cell,
   1×1)`, and A7 compares DB/model/intended state as before.
7. Recovery still materializes the exact recorded pre-state. It never
   normalizes an old recovery record, so exact restore remains exact.

### Alternatives rejected

- **Retain arbitrary raw span during organizer materialization**: rejected.
  `WorkspaceItemProcessor` restores application/deep-shortcut icon items as
  `1×1`, so retaining a `2×2` raw span can leave the DB and model divergent
  after reload.
- **Change every writer move to write a default span**: rejected. The bridge
  is limited to `moveItemInDatabase` with a desktop destination and a
  `WorkspaceItemInfo`; widget/folder items, other writer methods, and batch
  folder-insertion behavior must not change.
- **Normalize only in organizer materialization**: rejected. It does not
  protect pre-existing NULL rows or rows restored exactly from an old recovery
  point when the next action is a manual writer move or a no-change apply.
- **Normalize only when a `WorkspaceItemInfo` enters the desktop**: selected.
  It covers direct and Hotseat-mediated legacy/post-recovery rows at the actual
  state change without source-container or parent-kind ambiguity, repair-on-
  restore, or a broad writer policy.
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
| `src/com/android/launcher3/model/ModelWriter.java` | In `moveItemInDatabase`, when the destination is desktop and the item is `WorkspaceItemInfo`, persist in-memory/DB `1×1` without source-container classification | Owns the actual desktop-entry transition and protects direct, Hotseat-mediated, old, NULL, and positive non-1×1 rows |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt` | Turn Path A into the regression oracle: assert real writer normalization for NULL and positive non-1×1 rows, including folder → Hotseat → desktop, reload DB/model convergence, strict `Restorable → Restored`, exact pre-run manifest, and a second organize pass before recovery; retain Path B control | Exercises the complete production flow requested by Issue #269 |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/Issue265ManualEditRecoveryInstrumentationTest.kt` focused writer cases | Add focused real-writer fixtures for NULL and positive non-1×1 folder-child spans, direct and Hotseat-mediated desktop entry, and an AppPair-container source value; the existing `RealAdapterRowMatrixInstrumentationTest` retains the desktop NULL-span strictness control | Keeps the destination transition seam covered without testing private implementation details directly |
| `tests/unit/app/lawnchair/organizer/application/...` | Add only pure helper/contract coverage if the final implementation extracts a platform-free helper | Unit coverage is optional and must not create a second production seam |

The implementation PR must not modify `RecoveryRecordCodec`, recovery DB DDL,
planner semantics, or UI/resources. The only Launcher3 bridge change is the
targeted `ModelWriter.java` transition rule described above.

## Migration and recovery

- No Launcher DB schema or recovery format migration.
- No backup/restore allowlist change. Recovery records continue to be excluded
  from Lawnchair ZIP and Android backup as specified by Spec 13.
- New `moveItemInDatabase` desktop transitions for `WorkspaceItemInfo` icons
  contain `1×1` spans, including folder → Hotseat → desktop paths; their
  recovery points record these exact post-transition rows.
- Existing recovery points remain readable and are restored byte-for-byte,
  including a NULL span on a folder-child row. They are not rewritten during
  startup reconciliation or explicit recovery.
- If A5/A7 observes any divergence, the existing stale/precondition or
  post-commit recovery result applies. The implementation must not add a
  repair write, retry loop, sleep, or whole-table replacement.
- A release downgrade requires no special handling because no persisted format
  changed. The behavior difference is in the new binary's workspace-transition
  writer behavior.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-269-01 | Real writer seam seeds NULL and positive non-1×1 folder-child rows, exercises direct and Hotseat-mediated desktop entry plus an AppPair-container source value, and asserts both DB columns and in-memory/model spans are `1×1` after reload | Focused organizer instrumentation test on API 36.1; no materializer helper or source-parent lookup is required |
| AC-269-02 | Path A runs organize → real `ModelWriter` move → recovery preview/confirm and has failing assertions for non-`Restorable`, non-`Restored`, and manifest mismatch | `./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.Issue265ManualEditRecoveryInstrumentationTest` on `nunu_qpr2_api36_1` |
| AC-269-03 | Path A performs a second organize after the manual move and before recovery, with failing assertions for unavailable capture, non-verified apply, and reload failure | Same API 36.1 instrumentation target; use latch/state assertions, not sleeps as the oracle |
| AC-269-04 | Path B exact restore/next-start assertions remain green; injected/external desktop NULL span remains rejected by strict canonical capture without a write | Same instrumentation suite plus focused row-matrix instrumentation test |
| Spec 13 revision | Spec 13 status/history and #269 cross-reference were updated after owner acceptance; no implementation started while draft | Markdown review and repo-contract validation |
| Layout-data gate | Implementation PR records the accepted spec/plan revision, exact base/head SHA, full relevant diff, successful CI `final-status`, and independent audit under `docs/assessment/pr-<number>-<slug>.md` | GitHub Actions + `docs/project/github-workflow.md` high-risk evidence contract |

Required verification surface also includes the existing repository commands
when implementation begins: `./gradlew spotlessCheck`, the relevant organizer
unit/instrumentation suites, and `./gradlew assembleLawnWithQuickstepGithubDebug`.
The exact connected-test invocation must be confirmed on a clean checkout or
CI before it becomes a new required command in `docs/engineering/building.md`.

## Documentation updates

- [x] `specs/269-folder-workspace-representability/spec.md` accepted by owner
- [x] `specs/13-safe-layout-application/spec.md` updated with the accepted
      normative revision and cross-reference
- [x] `specs/269-folder-workspace-representability/plan.md` status changed to
      implementation-ready after spec acceptance
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
- [x] Spec #269 and the Spec 13 revision are accepted.
- [x] Targeted destination-based `ModelWriter` bridge change completed; no
      broad writer or materialization change.
- [x] Direct folder, folder → Hotseat → desktop, and AppPair-source icon
      transitions all converge to DB/model `1×1`.
- [ ] Path A passes through `Restorable → Restored` with exact manifest
      equality, including the second organize pass.
- [ ] Path B control remains exact.
- [ ] Strict malformed desktop capture behavior remains fail-closed.
- [ ] `spotlessCheck`, relevant organizer tests, and debug build pass.
- [ ] High-risk CI and independent audit evidence are recorded before merge.
