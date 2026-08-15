# Empty-Folder Policy

> Status: Proposed (decision record for Issue #24; acceptance tracked in PR #69 review)
> Behavior contract: [spec 24](../../specs/24-empty-folder-policy/spec.md)
> Related: [item-preservation-policy](item-preservation-policy.md) classification, [ADR-0004](../adr/0004-organizer-lock-persistence.md) lock storage, [spec 13](../../specs/13-safe-layout-application/spec.md) safe application
> Reviewed: 2026-08-15
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-002, FR-003, FR-004, FR-005, FR-006, NFR-001, NFR-002, NFR-009
> Decision gates: D-003 (target set), D-006 (lock semantics)
> Primary scope/dependency record: [Issue #24](https://github.com/nunu1733/NunuLauncher/issues/24)

## 1. Question and record scope

This document records the Issue #24 product decision — whether a captured
folder with zero captured children is kept by default and which explicit
user action may delete it — together with its rationale, rejected
alternatives, the no-ADR judgment, and the baseline mutation-path evidence.
The observable behavior, the reject branch, the scenario matrix, and the
acceptance criteria are owned by
[spec 24](../../specs/24-empty-folder-policy/spec.md); they are not
restated here.

## 2. Decision

### 2.1 Default: preserve, never auto-delete

An empty folder in the captured inventory is accounted as a **preserved**
item: it keeps its exact captured placement, acts as an occupied constraint,
is not a move target, and is never deleted by the run. This holds for every
run mode and profile. No organizer run deletes an empty folder implicitly;
a folder emptied for any reason outside an approved explicit deletion stays
preserved until a later capture classifies it again.

### 2.2 Explicit deletion: a gated future capability

The organizer may delete an empty folder only as an explicit plan action
under the closed conditions of the
[explicit-deletion gate](../../specs/24-empty-folder-policy/spec.md)
(E1–E7: valid captured empty folder, `UNLOCKED` lock state, per-run user
opt-in, per-folder enumerated actions, item-level preview with separate
destructive confirmation, the full spec 13 checkpoint/transaction/recovery
ordering, conservation accounting, count-only diagnostics). The accepted
apply contract has no delete action, so v1 offers nothing and the gate
becomes implementable only after an accepted spec 13 revision. Until then
no surface may offer or perform such a deletion.

### 2.3 Rejected alternatives and rationale

- **Auto-delete during organization** (mirroring baseline loader
  sanitation): rejected. It would be an unapproved implicit deletion that
  Issue #24's exit criteria forbid, cannot be explained per item to the
  user, destroys user-visible state the user never asked to remove, and
  executes outside any preview/confirmation. It also conflicts with the
  quality order: never losing a layout outranks tidiness.
- **Preserve-only forever** (manual cleanup only): rejected as the permanent
  answer because the organizer could never complete its purpose for exactly
  this residue class, and manual baseline paths offer no preview, recovery
  point, or accounting. It **is** the v1 behavior, because the accepted
  apply contract has no delete action and the extension must first be
  accepted.
- **Selected: preserve by default, explicit confirmed deletion later.**
  Preserving costs one occupied cell until the user acts; deleting without
  consent costs user trust and violates conservation explainability. The
  asymmetry fixes the default, and the closed gate bounds the only path to
  deletion.

### 2.4 ADR judgment

No ADR is required. This decision selects observable product behavior only:
it changes no schema, storage, migration, or interface, and reversing it is
a normal policy/spec change. The expensive part — extending the apply
contract with a delete action — is deliberately deferred and will carry its
own accepted spec revision when proposed. This parallels how
[item-preservation-policy](item-preservation-policy.md) records
classification decisions, while storage decisions went to ADRs.

## 3. Baseline mutation paths are independent evidence, not reusable behavior

The baseline deletes or dissolves empty folders on four independent paths,
each outside any organizer transaction, recovery point, or accounting.
Line references are verified against baseline commit
`505dbc40e6154c05158b5d0271c45f6a885a411b` (the current branch has diverged
in three of these files):

| Baseline path | Trigger and behavior | Evidence (baseline SHA) |
|---|---|---|
| Loader sanitation | Runs only when that load marked rows deleted; deletes every folder whose `_id` matches no `container`, in its own transaction; a `SQLException` is swallowed (logged, empty result). | `LoaderTask.java:259,648-660`, `ModelDbController.java:385-416` |
| Folder UI dissolve | A folder that reaches zero (or one) remaining item is dissolved on bind, close, drag-out completion, or item removal; with one item the item replaces the folder; with zero the folder row is simply deleted from the DB. | `Folder.java:594-599,957-990,1145-1156,1342,1528-1544`, `LauncherDelegate.java:80-137` |
| Grid migration | A folder with zero children fails migration validity ("Folder is empty") and is removed as an invalid entry. | `GridSizeMigrationUtil.java:507-510,527-532,611-614,631-640` |
| Restore profile cleanup | Rows of profiles that could not be restored are deleted; a surviving parent may thereby become empty. | `RestoreDbTask.java:262-278` |

The organizer does not call, wait for, or extend these paths. Their effects
are external mutations relative to a run, classified per
[item-preservation-policy §5.1/§5.3](item-preservation-policy.md) and
spec 24 M-05–M-10:

- Effect **before** capture: the absent row is simply not part of that
  run's inventory; the run never claims it was preserved or deleted.
- Effect **after** capture: the revision no longer matches; the run is
  stale and rejects or recaptures before any organizer write. It must not
  relabel the external disappearance as a plan deletion, free the occupied
  space from it, or create a recovery point for the unapplicable plan.
- A failed baseline cleanup (for example the swallowed sanitation
  exception) leaves an empty folder that the organizer preserves and
  diagnoses; it never "finishes" baseline cleanup on its own.

Mutual exclusion between an organizer apply and concurrent baseline writers
(loader, model writer, sanitation) is the operational gate that
[spec 13](../../specs/13-safe-layout-application/spec.md) apply
serialization and its implementation plan own; this decision adds no second
mechanism.

## Change history

- 2026-08-15: Decided preserve-by-default with a gated explicit-deletion
  capability, recorded the rejected alternatives and the no-ADR judgment,
  and fixed the baseline mutation-path evidence. Review of PR #69 moved
  the observable behavior, reject branch, gate conditions, and acceptance
  criteria into [spec 24](../../specs/24-empty-folder-policy/spec.md);
  this document remains the decision rationale and evidence record.
