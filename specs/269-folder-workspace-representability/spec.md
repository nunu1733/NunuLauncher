---
issue: "#269"
status: draft
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
child to the workspace through the platform's ordinary writer leaves a valid,
deterministically capturable desktop placement. The flow

> organize → move an app from an organizer-created folder to the workspace →
> organize again or restore

remains available on every supported grid. Apply and recovery continue to use
Spec 13's exact row-accounted, revision-bound, transactionally verified
contract; no success is inferred from a successful write alone.

This document is the Issue #269 revision proposal for
[Spec 13](../13-safe-layout-application/spec.md). Implementation is blocked
until this revision is accepted.

## Scope

- Revise Spec 13's canonical/application boundary for raw span handling of
  `FolderChild` rows.
- In the organizer materialization path, preserve a valid captured raw span
  for an existing folder child and use deterministic `1×1` span data when the
  source folder-child row has no span. A folder-child row emitted by organizer
  apply therefore never has `NULL` `SPANX`/`SPANY`.
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
  chosen owner seam is organizer materialization, not a platform writer
  policy.
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

### D1: Owner seam — organizer materialization retains a valid raw span

The chosen option is to retain deterministic span data across folder-child
normalization. `rowFor(FolderChild)` remains the only place that projects the
public folder-child placement into a `PersistentRow` for a normal apply:

- when `base.rawSpan` is present, the intended row carries that exact positive
  `(spanX, spanY)` pair;
- when `base.rawSpan` is absent, the intended row carries `GridSpan(1, 1)`;
- `screen`, `cellX`, and `cellY` keep the existing folder-child normalization
  (`NULL`), because the public folder-child placement has no workspace cell;
- a newly materialized folder child also receives `GridSpan(1, 1)`.

The fallback is applied at write-set materialization, before the recovery
manifest is built. It is not a capture-time repair and it is not a nullable
wildcard. The `PersistentRow` constructor's positive-span invariant remains
the guard for values that reach `values()`.

This makes every organizer-produced folder-child row safe for a later
placement-only writer move: the writer changes the container and workspace
coordinates while the valid span remains in the row. It also repairs rows
left by an earlier organizer apply the next time a normal apply materializes
them, without changing their public semantic placement.

### D2: Canonical/public representation stays closed

The public `PlacementState.Workspace` contract continues to require an exact
`GridSpan`; `FolderChild` does not gain nullable or raw database fields. The
lossless persistence manifest continues to record nullable schema columns so
recovery can round-trip legacy or unsupported rows exactly. The distinction is
intentional:

- valid organizer-generated folder-child write intent has a positive raw span;
- a captured desktop row must have a positive cell and span;
- a legacy/external desktop row with a `NULL` span is not reinterpreted as
  `1×1` by capture and is not silently accepted as a different state.

Thus write and capture describe the same row deterministically. The strict
desktop capture check remains in this Issue; mapping its exception to a
localized typed preview result remains Issue #270.

### D3: No Launcher3 writer bridge change

`ModelWriter.moveItemInDatabase` is a placement-only writer used by many
Launcher paths. Changing it to infer or overwrite spans would widen the
Launcher3 bridge and could alter widget or other item moves. The organizer
already owns the row materialization that created the invalid combination, so
the smallest responsibility boundary is to ensure its output supplies the
platform writer with valid span data. Existing writer behavior then remains
the compatibility contract: a move that does not write span columns preserves
the valid stored value.

### D4: Recovery and exactness

No recovery format or Launcher schema migration is needed. New checkpoints
capture the materialized `1×1`/retained span in their intended manifest, and
A7 recapture must equal that manifest. Existing recovery records remain
lossless and are not rewritten just because this policy changes. Exact restore
continues to restore the recorded pre-state; the new invariant applies to
rows emitted by a subsequent organizer materialization. No whole-table delete,
delay, or process restart is introduced.

## Behavior scenarios

### Scenario: Organizer-created folder child is moved to the workspace

Given a valid layout contains a folder and the organizer applies a plan that
keeps or creates one of its children,

When the user moves that child to a free workspace cell through the ordinary
Launcher writer,

Then the resulting desktop row has the writer-provided page/cell and a
positive span equal to the span emitted by the organizer apply,

And a fresh canonical capture succeeds and includes the child as an exact
`PlacementState.Workspace` item.

### Scenario: Existing folder child has no raw span before a later apply

Given a folder-child row is accepted by canonical capture because its parent
and rank are valid but its raw span is `NULL`,

When a subsequent organizer apply materializes that child,

Then the intended and committed row uses `SPANX=1` and `SPANY=1`,

And the public canonical state remains `FolderChild(parent, rank)` with no
invented workspace placement.

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
  values of newly materialized intended rows, not the manifest schema.
- No `favorites` schema version, recovery format version, backup allowlist, or
  migration is added.
- Before A5, the materialized intended manifest must contain the same positive
  folder-child spans that will be committed. After reload, A7 must recapture
  the same raw rows and canonical state. A mismatch is the existing verified
  apply failure/recovery path.
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

- [ ] AC-269-01: After organizer apply, every materialized `FolderChild` row
  has a positive deterministic span: the captured positive span is retained,
  otherwise `1×1` is written. A folder child never becomes a `NULL`-span
  desktop row solely because the ordinary writer later changes its container.
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
| AC-269-01 | Production application/instrumentation seam asserts intended and committed `PersistentRow.spanX/spanY`, plus a pre-existing NULL folder-child fixture receives `1×1` on the next apply. |
| AC-269-02 | `Issue265ManualEditRecoveryInstrumentationTest.pathA_manualEditBeforeRestore` on the API 36.1 emulator, with raw-row, `Restorable → Restored`, and exact manifest assertions. |
| AC-269-03 | The same real production harness performs a second organize pass after the manual move; a focused adapter contract test repeats materialization and compares the intended state/manifest deterministically. |
| AC-269-04 | `pathB_controlWithoutManualEdit` remains green with exact restore; a focused capture fixture proves a NULL-span desktop row is not accepted as a 1×1 row. Typed Settings presentation is explicitly deferred to #270. |

## Open questions

No product choice remains in this draft: the selected responsibility boundary
is organizer materialization with a retained-or-`1×1` raw span. Owner
acceptance of this Spec 13 revision is the implementation gate.

## Change history

- 2026-09-10: Draft created for #269 from the accepted #265 two-path
  reproduction at repository `main` `b25f20ca7c31ad384fbe8f8b696e87118f8fbe8c`.
  Selected organizer materialization as the owner seam; retained the strict
  desktop capture boundary and separated typed preview failure to #270.
