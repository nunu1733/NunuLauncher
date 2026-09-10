---
issue: "#269"
status: accepted
requirements:
  - AC-269-01
  - AC-269-02
  - AC-269-03
  - AC-269-04
risk:
  - layout-data
updated: 2026-09-10
---

# Organizer-applied folder children remain representable after moving to the workspace

## Problem

[Issue #269](https://github.com/nunu1733/NunuLauncher/issues/269) records a
confirmed failure boundary from the two-path reproduction in [Issue #265](https://github.com/nunu1733/NunuLauncher/issues/265).
Organizer apply currently materializes a `FolderChild` as a `favorites` row
with `SPANX` and `SPANY` set to `NULL`. The normal Launcher
`ModelWriter.moveItemInDatabase` path later changes the row's container and
workspace coordinates but does not write either span column. The result is a
desktop row with a `NULL` span, which the canonical capture cannot construct as
`PlacementState.Workspace` and which therefore makes organize/recovery
unavailable.

This is a product-generated row shape, not only malformed external data:

1. `LauncherLayoutAdapter.rowFor(FolderChild)` currently sets `cell` and
   `span` to `null` ([source](../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt)).
2. `RowManifestCodec.toCanonical` requires a desktop row's cell and span
   ([source](../../lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt)).
3. The baseline move writer updates container, screen, cell, and rank only
   ([source](../../src/com/android/launcher3/model/ModelWriter.java)).

The accepted investigation also establishes that the recovery lifecycle is
not the cause: the control path restores exactly and the manual-edit path
leaves the recovery point `VERIFIED` without mutating the layout when preview
capture fails. The primary fix must preserve representability; a typed capture
failure is defense-in-depth and belongs to [Issue #270](https://github.com/nunu1733/NunuLauncher/issues/270).

## Outcome

After an organizer apply has created or retained a folder child, moving that
icon item into the workspace through the platform's ordinary writer persists
the same valid span that Launcher restores. This applies whether the item
moves directly from the folder or first passes through the Hotseat, and to
rows created by an older organizer version or restored exactly from an older
recovery point. The flow

> organize → move an app from an organizer-created folder to the workspace →
> organize again or restore

remains available on every supported grid. Apply and recovery continue to use
Spec 13's exact row-accounted, revision-bound, transactionally verified
contract; no success is inferred from a successful write alone.

This document is the accepted Issue #269 revision for
[Spec 13](../13-safe-layout-application/spec.md). Its implementation and
verification are tracked in [plan.md](./plan.md).

## Scope

- Revise Spec 13's canonical/application boundary for raw span handling of
  `FolderChild` rows.
- At every existing-item transition into the workspace, make the ordinary
  Launcher writer persist deterministic `1×1` span data for
  `WorkspaceItemInfo` icon items. This destination-based guarantee covers
  direct folder moves and folder → Hotseat → workspace moves, as well as
  existing NULL-span and positive non-1×1 rows, without requiring a no-op
  organizer apply or a repair-on-restore write.
- Keep `FolderChild(parent, rank)` as the public semantic placement. Raw
  `favorites` columns remain internal to `PersistenceManifest` and do not
  leak into the planner/application public seam.
- Verify the production adapter and real baseline writer interaction through
  the existing application/instrumentation seams, including a second organize
  pass after the manual move.
- Preserve exact recovery manifest equality and the unchanged control path.

## Non-goals

- Catching and mapping canonical capture exceptions into the recovery-preview
  typed result; that is Issue #270.
- Persisting Settings status projection across process restart; that is Issue
  #271.
- Explaining the original `INPUT_READINESS / RECONCILIATION_FAILED` journal
  route from Issue #265.
- Changing planner strategy semantics, target membership, folder policy,
  recovery retention, recovery format, Launcher DB schema, or the exact
  precondition/rollback protocol.
- Broadly changing `ModelWriter` or every Launcher writer call site. The
  chosen seam is one targeted destination-based branch in
  `moveItemInDatabase`; widget/folder items, unrelated writer methods, and
  batch folder insertion remain unchanged.
- Making canonical capture accept a `NULL` span for a desktop row or silently
  inventing a span during capture. External/legacy malformed desktop rows
  remain fail-closed; typed presentation of that failure is the separate
  follow-up.

## Domain language

No new domain term is required. `FolderChild`, `workspace placement`,
`canonical capture`, and `recovery point` use the definitions already owned by
`CONTEXT.md` and Spec 13. `raw span` is an internal persistence description,
not a user-facing concept.

## Design decisions

### D1: Owner seam — the destination writer persists Launcher-compatible 1×1

The chosen option is to persist a valid span whenever an existing
`WorkspaceItemInfo` icon item enters the desktop through
`ModelWriter.moveItemInDatabase`. The bridge is destination-based: it does not
infer the source container or require a parent-kind lookup. It sets the
in-memory item span and the pending DB write to `1×1` for every such desktop
transition.

This is intentionally normalization rather than arbitrary raw-span retention.
`WorkspaceItemProcessor` restores application and deep-shortcut icon items as
`spanX=1` and `spanY=1`; persisting any other positive raw span would make the
DB leg and model leg diverge after reload. A NULL or positive non-1×1 folder
child therefore converges through the same rule, whether it moves directly
to the desktop or first moves through the Hotseat. An AppPair member that is
represented as a `WorkspaceItemInfo` follows the same destination rule; no
ambiguous source-container classification is needed.

The change is made at the transition, not during capture, planner evaluation,
or recovery. It protects rows produced by old organizer versions and rows
restored exactly from old recovery points while preserving Spec 13's exact
recovery semantics.

### D2: Canonical/public representation stays closed

The public `PlacementState.Workspace` contract continues to require an exact
`GridSpan`; `FolderChild` does not gain nullable or raw database fields. The
lossless persistence manifest continues to record nullable schema columns so
recovery can round-trip legacy or unsupported rows exactly. The distinction is
intentional:

- a folder-child → workspace writer transition has a positive raw span;
- a captured desktop row must have a positive cell and span;
- a legacy/external desktop row with a `NULL` span is not reinterpreted as
  `1×1` by capture and is not silently accepted as a different state.

Thus write and capture describe the same row deterministically. The strict
desktop capture check remains in this Issue; mapping its exception to a
localized typed preview result remains Issue #270.

### D3: Minimal Launcher3 writer bridge

The bridge is limited to `moveItemInDatabase` calls whose destination is
`Favorites.CONTAINER_DESKTOP` and whose item is a `WorkspaceItemInfo`. It sets
the in-memory `spanX`/`spanY` and pending `Favorites.SPANX`/`SPANY` values to
`1` before notification and enqueueing. It does not classify the source as a
folder, Hotseat, or AppPair, so the Hotseat intermediate transition cannot
lose the invariant and no `container >= 0` false-positive rule is introduced.
Widgets, folders, unrelated writer methods, and `moveItemsInDatabase` remain
unchanged; queueing, notifications, and transaction behavior otherwise stay
on the existing path.

### D4: Recovery and exactness

No recovery format or Launcher schema migration is needed. New checkpoints
capture the post-transition `1×1` span in their intended manifest, and A7
recapture must equal that manifest. Existing recovery records remain lossless
and are not rewritten just because this policy changes. Exact restore
continues to restore the recorded pre-state; the new invariant applies when a
subsequent writer transition moves an icon child to the desktop. No whole-table
delete, delay, or process restart is introduced.

## Behavior scenarios

### Scenario: Organizer-created folder child moves directly to the workspace

Given a valid layout contains a folder and the organizer applies a plan that
keeps or creates one of its children,

When the user moves that child to a free workspace cell through the ordinary
Launcher writer,

Then the resulting desktop row has the writer-provided page/cell and
`SPANX=1`, `SPANY=1`,

And a fresh canonical capture succeeds and includes the child as an exact
`PlacementState.Workspace` item.

### Scenario: Existing NULL folder child moves directly to the workspace

Given a folder-child row is accepted by canonical capture because its parent
and rank are valid but its raw span is `NULL`,

When the user moves that child to the workspace through the ordinary writer,

Then the committed desktop row uses `SPANX=1` and `SPANY=1`,

And a fresh capture succeeds with the matching Launcher model span.

### Scenario: NULL folder child passes through the Hotseat

Given an organizer-created or legacy folder-child icon has NULL raw span,

When the user moves it from the folder to a Hotseat slot and then from the
Hotseat to a workspace cell through ordinary `moveItemInDatabase` calls,

Then the Hotseat row may retain its semantic NULL span, but the desktop
transition persists `SPANX=1` and `SPANY=1`,

And a reload and fresh canonical capture succeed with the matching Launcher
model span.

### Scenario: Source container does not alter desktop-entry normalization

Given an existing `WorkspaceItemInfo` icon is in an AppPair or another
non-folder container and has a stale positive span such as `2×2`,

When it enters a workspace cell through `moveItemInDatabase`,

Then the desktop row and in-memory item use `SPANX=1` and `SPANY=1`,

And the result does not depend on a source-container or parent-kind lookup.

### Scenario: Existing positive non-1×1 folder child is moved to the workspace

Given a legacy folder-child row has a positive raw span such as `2×2`,

When the user moves that child to the workspace through the ordinary writer,

Then the committed desktop row is normalized to `SPANX=1` and `SPANY=1`,

And a reload produces the same span in the DB and Launcher model rather than
retaining the arbitrary raw span.

### Scenario: Manual edit followed by restore

Given the Issue #265 Path A fixture has completed an organizer apply,

When one folder child is moved to the workspace through a real `ItemInfo` and
`ModelWriter.moveItemInDatabase` call,

Then recovery preview returns `Restorable` rather than throwing or becoming
unavailable,

And confirmation returns `Restored` only after exact DB/model verification,

And the restored manifest equals the manifest captured immediately before the
organize run.

### Scenario: Second organize pass is deterministic

Given the manual move in the previous scenario has completed,

When the user starts a second organize run before any recovery operation,

Then capture, planning, apply, correlated reload, and A7 verification all
remain available,

And a repeated pass over the resulting layout does not reintroduce a `NULL`
desktop span or produce a non-deterministic raw span.

### Scenario: Control path remains unchanged

Given the same fixture without the manual move,

When the user previews and confirms recovery,

Then the result remains `Restored`, the pre-organize manifest is restored
exactly, and the next organizer start remains the existing preview/ready
outcome.

### Scenario: Malformed desktop row stays fail-closed

Given an external or legacy desktop row has a `NULL` span,

When canonical capture encounters it,

Then capture does not reinterpret the row as `1×1` and no apply/recovery
mutation is authorized,

And the user-facing typed failure mapping is covered by Issue #270 rather than
this Issue.

## Data and state

- The public planner/application state is unchanged. `FolderChild` continues
  to carry only parent identity and rank; workspace items continue to carry an
  exact positive `GridSpan`.
- `PersistenceManifest` continues to record the raw `SPANX`/`SPANY` values and
  the recovery record codec remains byte-compatible. The change affects the
  values written by the targeted workspace transition, not the manifest
  schema.
- No `favorites` schema version, recovery format version, backup allowlist, or
  migration is added.
- Every existing `WorkspaceItemInfo` moved into the desktop through
  `moveItemInDatabase` must have the same `1×1` span in its pending write and
  in-memory item, regardless of source container. After that move, a fresh
  capture and any subsequent A7 must recapture the same raw rows and canonical
  state. A mismatch is the existing verified apply failure/recovery path.
- The pre-apply recovery point remains the exact source state. Existing NULL
  spans in folder-child rows may be restored exactly; they are not silently
  rewritten by recovery.
- All layout safety invariants in `AGENTS.md` and Spec 13 remain in force:
  same revision, complete item accounting, unchanged locks, valid bounds and
  references, recovery point before mutation, atomic transaction, and
  post-apply re-read verification.

## Permissions, privacy, and security

None. The change reads and writes the existing local Launcher DB fields only;
it adds no permission, network path, telemetry, export, or new sensitive data.

## Accessibility and localization

None. No user-visible surface or localized resource changes. If #270 exposes
the separate malformed-capture result, that issue owns its accessibility and
localization contract.

## Acceptance criteria

- [ ] AC-269-01: At every existing-item transition into the desktop through
  `moveItemInDatabase`, a `WorkspaceItemInfo` icon is persisted with
  `SPANX=1` and `SPANY=1`, regardless of source container. This includes NULL
  and positive non-1×1 folder-child rows and the folder → Hotseat → workspace
  path; after reload, DB and Launcher model spans converge.
- [ ] AC-269-02: The Issue #265 Path A production harness reaches
  `RecoveryPreviewResult.Restorable` and `RecoveryResult.Restored` after the
  manual move, and the restored manifest equals the manifest captured before
  the relevant organize run.
- [ ] AC-269-03: The manual-move path supports a second organize pass through
  capture, plan/apply, reload, and verification without reintroducing the
  failure; the result is deterministic across repeated runs on the same
  snapshot.
- [ ] AC-269-04: The no-manual-edit control path remains exact and unchanged,
  and malformed/external desktop `NULL` spans remain fail-closed rather than
  being silently interpreted as `1×1`.

## Test oracle

| AC | Evidence |
|---|---|
| AC-269-01 | Focused real-writer instrumentation asserts NULL and positive non-1×1 folder-child fixtures, including a Hotseat intermediate move, are committed as `1×1` on desktop entry and converge after reload. |
| AC-269-02 | `Issue265ManualEditRecoveryInstrumentationTest.pathA_manualEditBeforeRestore` on the API 36.1 emulator, with raw-row, `Restorable → Restored`, and exact manifest assertions. |
| AC-269-03 | The same real production harness performs a second organize pass after the manual move; a focused writer contract test repeats the transition and compares DB/model spans deterministically. |
| AC-269-04 | `pathB_controlWithoutManualEdit` remains green with exact restore; a focused capture fixture proves a NULL-span desktop row is not accepted as a 1×1 row. Typed Settings presentation is explicitly deferred to #270. |

## Open questions

No product choice remains in this draft: the selected responsibility boundary
is the targeted destination-based desktop-entry writer bridge, with
`WorkspaceItemInfo` icon items normalized to `1×1` regardless of source.
Owner acceptance of this Spec 13 revision is the implementation gate.

## Change history

- 2026-09-10: Owner accepted the destination-based desktop-entry writer
  bridge revision. Implementation may proceed under the accepted Spec 13
  contract; verification and Spec 13 cross-reference remain implementation
  deliverables.
- 2026-09-10: Draft created for #269 from the accepted #265 two-path
  reproduction at repository `main` `b25f20ca7c31ad384fbe8f8b696e87118f8fbe8c`.
  Selected the targeted writer transition as the owner seam; retained the
  strict desktop capture boundary and separated typed preview failure to #270.
