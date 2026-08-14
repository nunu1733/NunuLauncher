# Empty-Folder Policy

> Status: Proposed (decision output of Issue #24)
> Related: [item-preservation-policy](item-preservation-policy.md) classification, [ADR-0004](../adr/0004-organizer-lock-persistence.md) lock storage, [spec 13](../../specs/13-safe-layout-application/spec.md) safe application
> Reviewed: 2026-08-15
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-002, FR-003, FR-004, FR-005, FR-006, NFR-001, NFR-002, NFR-009
> Decision gates: D-003 (target set), D-006 (lock semantics)
> Primary scope/dependency record: [Issue #24](https://github.com/nunu1733/NunuLauncher/issues/24)

## 1. Question and scope

This policy decides, for an organization run, whether a captured folder with
zero captured children is kept by default, which explicit user action may
delete it, and which cases reject. It keeps that decision consistent with
conservation accounting, [ADR-0004](../adr/0004-organizer-lock-persistence.md)
lock semantics, and the [spec 13](../../specs/13-safe-layout-application/spec.md)
recovery and transaction boundaries. It also fixes how the organizer treats the
baseline's own independent empty-folder mutation paths.

An **empty folder** is a captured `ITEM_TYPE_FOLDER` `favorites` row with a
valid DESKTOP or HOTSEAT placement whose captured child set (rows whose
`container` resolves to the folder) is exactly empty. Emptiness is evaluated
against the run's capture, never against the live database.

Non-goals: changing baseline loader cleanup; folder-internal layout and folder
formation (owned by [spec 12](../../specs/12-deterministic-full-layout-planner-v1/spec.md));
organizer DB apply or UI implementation; deleting non-empty folders
("dissolve"), which would need its own explicit-action decision.

## 2. Decision

### 2.1 Default: preserve, never auto-delete

An empty folder in the captured inventory is accounted as a **preserved** item:
it keeps its exact captured placement, acts as an occupied constraint that no
other item may use, is not a move target, and is never deleted by the run. The
run reports its presence as a count-level diagnostic and, in preview surfaces,
as an item-level "empty folder" entry.

No organizer run deletes an empty folder implicitly. A folder that becomes
empty for any reason outside an approved explicit deletion (§2.2) stays
preserved until a later capture classifies it again. This holds for every run
mode (manual full organization, onboarding proposal, incremental placement)
and every profile.

### 2.2 Explicit deletion: eligible only under closed conditions

The organizer may delete an empty folder only as an **explicit plan action**
that satisfies every condition below. Until a future accepted revision of the
apply contract adds the required `Delete` action family, no surface may offer
or perform such a deletion (spec 13 v1 has no delete; see §6).

| # | Condition |
|---|---|
| E1 | The folder is a captured, structurally valid `ITEM_TYPE_FOLDER` row in DESKTOP or HOTSEAT, in bounds for the captured device profile, with exactly zero captured children. |
| E2 | The folder row's `organizerLockState` is exactly `UNLOCKED`. A `LOCKED` folder is never eligible; `UNKNOWN` already fails the whole run closed under ADR-0004 before any plan. |
| E3 | The deletion exists only because the user opted in for that run (a dedicated non-default action or toggle). The planner never emits it by default, and one opt-in covers each folder only through an individually enumerated per-folder delete action in the plan. |
| E4 | Preview lists every proposed deletion item-level (folder title, page/cell or Dock rank, reason "empty folder"), and a **separate destructive confirmation** authorizes it; a generic full-organization confirmation is not sufficient ([organization-run-ux §4.1](organization-run-ux.md)). |
| E5 | Execution happens only inside the accepted apply contract with its full ordering: stale check, verified recovery point, one preconditioned atomic transaction, model reload, and independent post-apply verification. The folder row's expected state (including lock and zero children) is re-verified inside the transaction before the first mutation. |
| E6 | Conservation accounting counts each deleted folder exactly once as an explicit deletion (DESIGN §5.1). Diagnostics carry counts only, per the [organizer-diagnostics](../engineering/organizer-diagnostics.md) contract. |
| E7 | The same rules apply for all run modes and profiles; deletion targets only that folder row in its own profile and never any child, sibling, or other profile's row. |

### 2.3 Rejected alternatives and rationale

- **Auto-delete during organization** (mirroring baseline loader sanitation):
  rejected. It would be an unapproved implicit deletion that the exit criteria
  of Issue #24 forbid, cannot be explained per item to the user, destroys
  user-visible state (title, position) the user never asked to remove, and
  executes outside any preview/confirmation. It also conflicts with the
  quality order: never losing a layout outranks tidiness.
- **Preserve-only forever** (manual cleanup only): rejected as the permanent
  answer because the organizer could never complete its purpose for exactly
  this residue class, and manual baseline paths offer no preview, recovery
  point, or accounting. It **is** the v1 behavior, because the accepted apply
  contract has no delete action and an extension must first be accepted.
- **Selected: preserve by default, explicit confirmed deletion later.**
  Preserving costs one occupied cell until the user acts; deleting without
  consent costs user trust and violates conservation explainability. The
  asymmetry fixes the default, and the closed conditions in §2.2 bound the
  only path to deletion.

### 2.4 ADR judgment

No ADR is required. This decision selects observable product behavior only:
it changes no schema, storage, migration, or interface, and reversing it is a
normal policy-document change. The expensive part — extending the apply
contract with a delete action — is deliberately deferred and will carry its
own accepted spec revision when proposed (§6). This parallels how
[item-preservation-policy](item-preservation-policy.md) records classification
decisions, while storage decisions went to ADRs.

## 3. Baseline mutation paths are independent evidence, not reusable behavior

The baseline deletes or dissolves empty folders on four independent paths,
each outside any organizer transaction, recovery point, or accounting:

| Baseline path | Trigger and behavior | Evidence (baseline SHA) |
|---|---|---|
| Loader sanitation | Runs only when that load marked rows deleted; deletes every folder whose `_id` matches no `container`, in its own transaction; a `SQLException` is swallowed (logged, empty result). | `LoaderTask.java:292,681-693`, `ModelDbController.java:444-475` |
| Folder UI dissolve | A folder that reaches zero (or one) remaining item is dissolved on bind, close, or drag-out completion; with one item the item replaces the folder; with zero the folder row is simply deleted from the DB. | `Folder.java:594-599,957-990,1145-1155,1528-1544`, `LauncherDelegate.java:80-133` |
| Grid migration | A folder with zero children fails migration validity ("Folder is empty") and is removed as an invalid entry. | `GridSizeMigrationUtil.java:551-574,655-674` |
| Restore profile cleanup | Rows of profiles that could not be restored are deleted; a surviving parent may thereby become empty. | `RestoreDbTask.java:262-278` |

The organizer does not call, wait for, or extend these paths. Their effects are
external mutations relative to a run, classified exactly as
[item-preservation-policy §5.1/§5.3](item-preservation-policy.md) require:

- Effect **before** capture: the absent row is simply not part of that run's
  inventory; the run never claims it was preserved or deleted.
- Effect **after** capture: the revision no longer matches; the run is stale
  and rejects or recaptures before any organizer write. It must not relabel
  the external disappearance as a plan deletion, free the occupied space from
  it, or create a recovery point for the unapplicable plan.
- A failed baseline cleanup (for example the swallowed sanitation exception)
  leaves an empty folder that the organizer preserves and diagnoses; it never
  "finishes" baseline cleanup on its own.

Mutual exclusion between an organizer apply and concurrent baseline writers
(loader, model writer, sanitation) is the operational gate that
[spec 13](../../specs/13-safe-layout-application/spec.md) apply serialization
and its implementation plan own; this policy adds no second mechanism.

## 4. Behavior matrix

| # | Situation | Required behavior |
|---|---|---|
| M-01 | Empty folder captured, default run | Preserved at its exact placement as an occupied constraint; item-level preview entry and count-level diagnostic; no move, no deletion. |
| M-02 | Empty folder, user opted into explicit deletion | v1: not offered; nothing deleted. Future: per-folder delete actions in the plan only when §2.2 E1–E7 all hold. |
| M-03 | Empty folder with `LOCKED` state | Preserved; deletion not eligible; D-006 protects the folder placement, and removing the row would remove that placement. The user must unlock first (Issue #38 owns authoring). |
| M-04 | Empty folder with `UNKNOWN`/corrupt/missing lock state | Whole run fails closed before any plan or write per ADR-0004 (`LOCK_STATE_UNAVAILABLE`); not an empty-folder-specific outcome. |
| M-05 | Folder children disappear after capture (loader sanitation, package removal, restore) | Run is stale: reject or recapture with DB unchanged; never claim the now-empty folder as a plan deletion or free its space. |
| M-06 | Folder was emptied before capture (any cause, including failed sanitation or restore profile cleanup) | Captured as an empty folder; M-01 applies. The organizer does not investigate the cause or finish external cleanup. |
| M-07 | A plan would empty a non-empty folder | Not possible in v1: [spec 12](../../specs/12-deterministic-full-layout-planner-v1/spec.md) never removes members from an existing folder. Any future dissolve/ungroup action must satisfy §2.2 for the emptied parent and account it as an explicit deletion. |
| M-08 | Baseline UI dissolves a captured empty folder while a plan is pending | External mutation after capture: stale at confirmation/apply; recapture. |
| M-09 | Restore happens during a run | Stale; the run captures the restored state anew. Restores outside a run may leave empty parents, which M-06 covers. |
| M-10 | Grid migration runs | Baseline migration may remove empty folders as invalid entries; that is its own deletion, not an organizer one. Post-migration capture contains no such row. Migration during a run is stale; after migration, a new capture and revision are required. |
| M-11 | Any folder row field changes after capture (title, placement, lock) | Stale; deletion eligibility is re-evaluated against the new capture only. |
| M-12 | Empty folder in the Dock (HOTSEAT) | Same rules; the Dock rank is the placement; preserved rank/slot by default, eligible for explicit deletion under §2.2. |
| M-13 | App-pair parent with zero or malformed members | Not an empty-folder case; structural preflight rejection per [item-preservation-policy §5.2](item-preservation-policy.md); this policy grants no deletion. |
| M-14 | Recovery of a run that deleted empty folders (future) | Spec 13 recovery is row-accounted and revision-bound: restoring re-inserts the exact checkpoint folder rows as explicit inserts of the reviewed current state; conflicts with user changes since require a fresh recovery preview, never a blind restore. Runs without deletions restore nothing extra. |

## 5. Confirmation, accessibility, and recovery acceptance criteria

These flow to the [organization-run-ux](organization-run-ux.md) contract and
future apply-contract revision; they are acceptance criteria for any surface
that offers empty-folder deletion.

| AC | Criterion |
|---|---|
| EF-1 | Preview enumerates each proposed empty-folder deletion item-level (title, placement, reason) and lists preserved empty folders distinctly from deletions; an empty overall diff never renders confirm as a write action. |
| EF-2 | Deletion is authorized only by a separate destructive confirmation naming the folder count and the effect; generic full-organization confirmation is rejected as authorization. |
| EF-3 | TalkBack exposes name, role, state, and result for the deletion opt-in, each listed folder, and the confirmation; warnings are never color-only; every action is reachable and activatable by keyboard/switch access; 200% font scaling reflows without loss; no timeout auto-confirms or auto-cancels. |
| EF-4 | A verified recovery point exists before any deletion writes; restoring returns deleted folder rows exactly, and the recovery preview states what deletions will be reversed. |
| EF-5 | Deletion is one action inside the single atomic apply transaction: any failure rolls back the deletions together with the rest; post-apply verification proves each deleted folder absent and each preserved one present with unchanged placement. |
| EF-6 | Diagnostics record only counts (deleted/preserved empty-folder counts) through the [organizer-diagnostics](../engineering/organizer-diagnostics.md) contract; no titles, coordinates, or profile identifiers. |
| EF-7 | Staleness re-evaluates eligibility: a folder that changed since capture is never deleted by the earlier plan; the run recaptures and the user re-confirms. |

## 6. Required conditions handed to downstream sources of truth

- **[item-preservation-policy](item-preservation-policy.md) (#3):** §3.2 and
  fixtures F-10a/F-10b now reference this policy; the classification table is
  unchanged (`ITEM_TYPE_FOLDER` remains move/preserve; empty is preserve).
- **[organization-run-ux](organization-run-ux.md) (#4):** §4.1's
  empty-folder placeholder now points here; UI implementation issues must
  satisfy §5 EF-1–EF-3.
- **[spec 13](../../specs/13-safe-layout-application/spec.md) (#13):** v1 is
  unchanged — no delete action. A future revision must add a typed `Delete`
  action carrying the exact expected folder-row precondition (including
  `UNLOCKED` lock state and zero children, re-verified inside the transaction),
  rollback semantics, recovery insert accounting, post-apply absence
  verification, and a count-only diagnostic field, all before any
  implementation. Until that revision is accepted, no deletion is implemented.
- **Planner specs [10](../../specs/10-pure-organization-planning/spec.md)/[12](../../specs/12-deterministic-full-layout-planner-v1/spec.md):**
  v1 preserves empty folders (already stated); any deletion enters only as
  explicit per-folder plan actions under §2.2 through an accepted spec change.
- **[organizer-diagnostics](../engineering/organizer-diagnostics.md) (#16):**
  the future extension adds a count-only delete summary field (for example
  `deleteActionCount`) through that contract's process; no item-level data.
- **Issue #38 (lock authoring):** no change; locked and `UNKNOWN` empty
  folders follow ADR-0004 and M-03/M-04.

## 7. Fixtures

Synthetic identities only; extend the F-10 series of
[item-preservation-policy](item-preservation-policy.md).

| # | Fixture | Expected result |
|---|---|---|
| F-24a | Captured empty folder, default run | M-01: preserved at its cell; diagnostic count; no write beyond the confirmed plan. |
| F-24b | Explicit deletion requested for an `UNLOCKED` empty folder | v1: not offered; future: enumerated delete action appears only with E1–E7 satisfied. |
| F-24c | Deletion requested for a `LOCKED` empty folder | M-03: preserved; not eligible; no plan action emitted. |
| F-24d | Folder children vanish after capture | M-05: stale; recapture; no organizer write. |
| F-24e | Capture already contains an empty folder of restore/sanitation origin | M-06/M-01: preserved; cause is not diagnosed as an organizer action. |
| F-24f | Grid migration removed an empty folder before capture | M-10: absent from inventory; nothing accounted. |
| F-24g | Empty folder in the Dock | M-12: rank preserved by default; explicit-deletion rules identical. |
| F-24h | Future deletion run, then explicit recovery | M-14/EF-4: folder rows re-inserted exactly; changed current state forces a fresh recovery preview. |

## 8. Evidence

All source references are fixed to baseline commit
`505dbc40e6154c05158b5d0271c45f6a885a411b` (also
[§3](#3-baseline-mutation-paths-are-independent-evidence-not-reusable-behavior)):

- [LoaderTask.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/LoaderTask.java)
- [ModelDbController.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/ModelDbController.java)
- [Folder.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/folder/Folder.java)
- [LauncherDelegate.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/folder/LauncherDelegate.java)
- [GridSizeMigrationUtil.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/GridSizeMigrationUtil.java)
- [RestoreDbTask.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/provider/RestoreDbTask.java)

## Change history

- 2026-08-15: Decided preserve-by-default with closed explicit-deletion
  eligibility (E1–E7), baseline mutation-path separation, and the behavior
  matrix; recorded the no-ADR judgment. Research output of Issue #24; no
  production behavior change.
