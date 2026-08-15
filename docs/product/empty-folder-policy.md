# Empty-Folder Policy

> Status: Accepted (decision record for Issue #24; accepted through PR #69 review)
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

The decision: an empty folder is **preserved by default**, and no organizer
run deletes one implicitly. The observable preserve behavior — placement,
occupancy, diagnostics, and its uniformity across run modes and profiles —
is owned by [spec 24](../../specs/24-empty-folder-policy/spec.md)
(M-01, M-06) and is not restated here.

### 2.2 Explicit deletion: a gated future capability

The decision: deletion is possible only as an **explicit plan action** under
the closed gate owned by
[spec 24](../../specs/24-empty-folder-policy/spec.md) (E1–E7). The accepted
apply contract ([spec 13](../../specs/13-safe-layout-application/spec.md))
has no delete action, so v1 offers nothing and the gate becomes
implementable only after an accepted spec 13 revision.

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

The decision: the organizer does not call, wait for, or extend these paths,
and never finishes a failed baseline cleanup on its own. Their effects are
external mutations relative to a run; the absent-vs-stale classification is
owned by [spec 24](../../specs/24-empty-folder-policy/spec.md) (M-05–M-10)
on top of [item-preservation-policy §5.1/§5.3](item-preservation-policy.md).

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
- 2026-08-15: Second review revision: removed the remaining restatement of
  spec 24 behavior from §§2–3; the decision record now states only the
  decisions and their rationale.
- 2026-08-15: Accepted — reviews passed with no open findings; status set
  to `Accepted` for the PR #69 merge.
