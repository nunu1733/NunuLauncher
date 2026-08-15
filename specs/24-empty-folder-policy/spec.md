---
issue: "#24"
status: proposed
requirements:
  - FR-002
  - FR-003
  - FR-004
  - FR-005
  - FR-006
  - NFR-001
  - NFR-002
  - NFR-009
updated: 2026-08-15
---

# Empty-folder preservation and deletion contract

## Problem

Issue #24 must decide what an organization run does with a captured folder
that has zero captured children: keep it by default, allow deletion only
through which explicit user action, and which cases reject. The decision,
its rationale, and the baseline mutation-path evidence are recorded in
[empty-folder policy](../../docs/product/empty-folder-policy.md); this spec
owns the observable behavior, the reject branch, the scenario matrix, and
the acceptance criteria. It supersedes the issue's initial "no separate
spec" intake judgment, which predated the review finding that these
conditions are normative and belong in a spec.

## Outcome

Every captured `ITEM_TYPE_FOLDER` row receives exactly one disposition:

- **preserve** — the default in v1 and the ongoing default;
- **explicit delete** — possible only through the closed future gate below;
- **reject** — the whole run fails closed with no database change.

v1 implements only preserve and reject. The accepted apply contract
([spec 13](../13-safe-layout-application/spec.md)) has no delete action, so
no surface may offer or perform an empty-folder deletion until that contract
is revised and this spec's gate is accepted.

## Scope

- Classification of captured folder rows with zero captured children,
  including malformed and dangling cases.
- Default preserve behavior across run modes (manual full, onboarding,
  incremental) and profiles.
- Stale/external-mutation classification of the baseline empty-folder
  mutation paths.
- The closed eligibility gate for a future explicit deletion capability and
  the confirmation/accessibility/recovery criteria for any surface offering
  it.

## Non-goals

- Baseline loader cleanup changes; folder formation and folder-internal
  layout ([spec 12](../12-deterministic-full-layout-planner-v1/spec.md));
  organizer DB apply/UI implementation; deleting non-empty folders
  ("dissolve"), which needs its own explicit-action decision; diagnostics
  field encoding, owned by
  [organizer-diagnostics](../../docs/engineering/organizer-diagnostics.md).

## Definitions

- **Empty folder**: a captured `ITEM_TYPE_FOLDER` row whose captured child
  set is exactly empty and whose captured container is valid DESKTOP or
  HOTSEAT with an in-bounds, non-overlapping placement in the captured
  device profile.
- **Malformed folder row**: a captured FOLDER row failing those structural
  checks (invalid container, out-of-bounds, or overlapping placement),
  regardless of child count.
- **Dangling reference**: a captured child row whose `container` targets a
  folder id not present in the captured inventory.

## Scenario matrix

| # | Scenario | Required observable result |
|---|---|---|
| M-01 | Empty folder captured, default run | Preserved at its exact captured placement as an occupied constraint; item-level preview entry and count-level diagnostic; no move, no deletion. |
| M-02 | Empty folder, user opted into explicit deletion | v1: not offered; nothing deleted. Future: per-folder delete actions in the plan only when the explicit-deletion gate E1–E7 all hold. |
| M-03 | Empty folder with `LOCKED` lock state | Preserved; deletion not eligible; removing the row would remove a locked placement. Unlock authoring is owned by [Issue #38](https://github.com/nunu1733/NunuLauncher/issues/38). |
| M-04 | Empty folder with `UNKNOWN`/corrupt/missing lock state | Whole run fails closed before any plan or write per [ADR-0004](../../docs/adr/0004-organizer-lock-persistence.md); apply returns spec 13 `LOCK_STATE_UNAVAILABLE`. Not an empty-folder-specific outcome. |
| M-05 | Folder children disappear after capture (loader sanitation, package removal, restore) | Run is stale: reject or recapture with DB unchanged; never claim the now-empty folder as a plan deletion or free its space. |
| M-06 | Folder was emptied before capture (any cause) | Captured as an empty folder; M-01 applies. The run does not investigate the cause or finish external cleanup. |
| M-07 | A plan would empty a non-empty folder | Not possible in v1: [spec 12](../12-deterministic-full-layout-planner-v1/spec.md) never removes members from an existing folder. Any future dissolve action must pass the explicit-deletion gate for the emptied parent. |
| M-08 | Baseline UI dissolves a captured empty folder while a plan is pending | External mutation after capture: stale at confirmation/apply; recapture. |
| M-09 | Restore happens during a run | Stale; the run captures the restored state anew. Restores outside a run may leave empty parents, which M-06 covers. |
| M-10 | Grid migration runs | Baseline migration may remove empty folders as invalid entries; that is its own deletion, not an organizer one. Post-migration capture contains no such row. Migration during a run is stale. |
| M-11 | Any folder row field changes after capture (title, placement, lock) | Stale; deletion eligibility is re-evaluated against the new capture only. |
| M-12 | Empty folder in the Dock (HOTSEAT) | Same rules; the Dock rank is the placement; preserved rank/slot by default, eligible under the gate identically. |
| M-13 | App-pair parent with zero or malformed members | Not an empty-folder case; structural preflight rejection per [item-preservation-policy §5.2](../../docs/product/item-preservation-policy.md). |
| M-14 | Recovery of a run that deleted empty folders (future) | Spec 13 recovery is row-accounted and revision-bound: restoring re-inserts the exact checkpoint folder rows; conflicts with later user changes require a fresh recovery preview, never a blind restore. |
| M-15 | Malformed folder row | Whole-run preflight reject before any plan or write; DB unchanged. Not an empty folder; never deletable. Rejection surfaces through the existing typed results: spec 10 `Invalid` with the matching `RejectionReason` (V-02 `INVALID_CONTAINER` + `ContainerCodeParam`, V-04 `BOUNDS_VIOLATION` + span/rank param, V-05 `OVERLAP`), or spec 13 `INVALID_PLAN` / `EXACT_PRECONDITION_FAILED` at the apply boundary. Per the [organizer-diagnostics §2 layering](../../docs/engineering/organizer-diagnostics.md), the v1 user-facing reject explanation is **layout-level**: defect category, affected-item count where the result provides one, nothing-changed, retry/exit — V-02/V-04/V-05 carry no item correlation and multiple rows can share the same defect, so no surface may claim to name the affected folder from these results; the explanation never reads the journal. The journal records only the typed error category and counts per its §7. Owning contracts: capture boundary and preflight rules of [item-preservation-policy §5.2](../../docs/product/item-preservation-policy.md), spec 10 V-rules, spec 13 preconditions. **Downstream gate:** naming the affected folder in a malformed-folder reject surface requires a spec 10 revision that adds item-correlated reject detail to V-02/V-04/V-05 (or an equivalent typed field), accepted before implementation. |
| M-16 | Dangling reference (child row targets a non-captured folder id) | Whole-run preflight reject with DB unchanged, surfaced as spec 10 V-06 `DANGLING_REFERENCE` with `ItemParam`; never treated as an empty folder or as deletable residue. Because V-06 carries `ItemParam`, the user-facing explanation may identify the affected item through that param; the journal stays category/count-only per the same layering. Owning contract: [item-preservation-policy §5.2](../../docs/product/item-preservation-policy.md). |

Baseline evidence for the external paths referenced by M-05–M-10 is fixed at
baseline commit `505dbc40e6154c05158b5d0271c45f6a885a411b` in
[empty-folder policy §3](../../docs/product/empty-folder-policy.md).

## Explicit deletion gate (future capability)

An organizer plan may delete an empty folder only when every condition
holds. Until an accepted revision of spec 13 adds the required typed
`Delete` action, this gate is not implementable and no surface may offer it.

| # | Condition |
|---|---|
| E1 | The folder is a captured, structurally valid `ITEM_TYPE_FOLDER` row in DESKTOP or HOTSEAT, in bounds for the captured device profile, with exactly zero captured children. |
| E2 | The folder row's `organizerLockState` is exactly `UNLOCKED`. A `LOCKED` folder is never eligible; `UNKNOWN` already fails the whole run closed under ADR-0004. |
| E3 | The deletion exists only because the user opted in for that run (a dedicated non-default action or toggle). The planner never emits it by default; one opt-in covers each folder only through individually enumerated per-folder delete actions. |
| E4 | Preview lists every proposed deletion item-level (folder title, page/cell or Dock rank, reason "empty folder"), and a separate destructive confirmation authorizes it; a generic full-organization confirmation is not sufficient ([organization-run-ux §4.1](../../docs/product/organization-run-ux.md)). |
| E5 | Execution happens only inside the accepted apply contract with its full ordering: stale check, verified recovery point, one preconditioned atomic transaction, model reload, and independent post-apply verification, with the folder row's expected state (including lock and zero children) re-verified inside the transaction before the first mutation. |
| E6 | Conservation accounting counts each deleted folder exactly once as an explicit deletion (DESIGN §5.1). Diagnostics carry counts only; field encoding is owned by the organizer-diagnostics contract, never item-level data. |
| E7 | The same rules apply for all run modes and profiles; deletion targets only that folder row in its own profile. |

## Acceptance criteria

| AC | Acceptance criterion | Evidence surface |
|---|---|---|
| EF-1 | Preview enumerates each proposed empty-folder deletion item-level (title, placement, reason) and lists preserved empty folders distinctly from deletions; an empty overall diff never renders confirm as a write action. | Preview UI tests per organization-run-ux §8. |
| EF-2 | Deletion is authorized only by a separate destructive confirmation naming the folder count and the effect; generic full-organization confirmation is rejected as authorization. | Confirmation UI tests. |
| EF-3 | TalkBack exposes name, role, state, and result for the deletion opt-in, each listed folder, and the confirmation; warnings are never color-only; every action is reachable and activatable by keyboard/switch access; 200% font scaling reflows without loss; no timeout auto-confirms or auto-cancels. | Accessibility tests per organization-run-ux §6. |
| EF-4 | A verified recovery point exists before any deletion writes; restoring returns deleted folder rows exactly, and the recovery preview states which deletions will be reversed. | Spec 13 recovery protocol tests (row-accounted restore). |
| EF-5 | Deletion is one action inside the single atomic apply transaction: any failure rolls back the deletions together with the rest; post-apply verification proves each deleted folder absent and each preserved one present with unchanged placement. | Spec 13 apply/verification tests. |
| EF-6 | Diagnostics record counts only through the organizer-diagnostics contract; no titles, coordinates, or profile identifiers. | Diagnostics contract tests. |
| EF-7 | Staleness re-evaluates eligibility: a folder that changed since capture is never deleted by the earlier plan; the run recaptures and the user re-confirms. | Stale/revision tests. |
| EF-8 | In v1, each preserved empty folder has an accessible preview entry: name/role/state announced (e.g., empty folder, its placement, kept unchanged), state not conveyed by color alone, reachable and operable with keyboard/switch access at 200% font scaling. | Preview accessibility tests. |
| EF-9 | Stale and reject outcomes restore focus deterministically and announce the outcome and next action (recapture, retry, or safe exit) once, per organization-run-ux §6. | Stale/reject accessibility tests. |

## Downstream requirements

- **[spec 13](../13-safe-layout-application/spec.md) (apply):** v1 is
  unchanged — no delete action. A future revision adds a typed `Delete`
  action with an exact expected folder-row precondition (including
  `UNLOCKED` lock state and zero children, re-verified inside the
  transaction), rollback semantics, recovery insert accounting, and
  post-apply absence verification. The revision exposes typed
  action/result information; the diagnostic field that summarizes it is
  added by the organizer-diagnostics contract's own process, not by
  spec 13.
- **Planner specs [10](../10-pure-organization-planning/spec.md) and
  [12](../12-deterministic-full-layout-planner-v1/spec.md):** v1 preserves;
  any deletion enters only as explicit per-folder plan actions under E1–E7
  through an accepted spec change. Per M-15, an item-correlated
  reject-detail revision of spec 10 (V-02/V-04/V-05 or an equivalent typed
  field) is the downstream gate for naming the affected folder in a
  malformed-folder reject surface; v1 explanations for those rejections are
  layout-level.
- **[organization-run-ux](../../docs/product/organization-run-ux.md):**
  §4.1 destructive effects and §6 accessibility apply to every
  empty-folder surface; EF-1–EF-3 and EF-8–EF-9 constrain it.
- **[organizer-diagnostics](../../docs/engineering/organizer-diagnostics.md):**
  owns any count-only summary field; this spec requires counts only.
- **[Issue #38](https://github.com/nunu1733/NunuLauncher/issues/38):** owns
  unlock authoring, the user path that can later make a locked empty folder
  eligible.

## Open questions

None. Diagnostic field naming/encoding is owned by organizer-diagnostics
and intentionally not decided here.

## Change history

- 2026-08-15: Drafted after review of the PR #69 decision record: moved
  observable behavior, the malformed/dangling reject branch (M-15/M-16),
  the scenario matrix, the explicit-deletion gate, and acceptance criteria
  (including v1 preserved-state and stale/reject accessibility) into this
  spec; the product document keeps decision rationale and baseline
  evidence. Status `proposed` until the PR #69 review/acceptance gate
  passes.
- 2026-08-15: Second review revision: M-15/M-16 now separate the
  user-facing reason (assembled from existing spec 10 typed
  `RejectionReason`/`DiagnosticParam` results) from the developer journal
  (typed category and counts only, per organizer-diagnostics §2/§7), and
  name a spec 10 revision as the downstream gate should richer typed reject
  detail ever be needed.
- 2026-08-15: Third review revision: the M-15 user-facing explanation is
  weakened to layout-level because V-02/V-04/V-05 carry no item
  correlation; naming the affected folder is gated on a spec 10 revision
  adding item-correlated reject detail. M-16 keeps item correlation
  through V-06 `ItemParam`.
