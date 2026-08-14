# Item Preservation Policy

> Status: Proposed (research output of Issue #3)
> Related: [empty-folder policy](empty-folder-policy.md) (Issue #24 decision), [ADR-0004](../adr/0004-organizer-lock-persistence.md) lock storage
> Reviewed: 2026-08-09
> Baseline: Lawnchair `v15.0.0-beta3.0` / commit `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Requirements: FR-002, FR-003, FR-008, NFR-002, NFR-007, NFR-012
> Decision gates: D-003 (target set), D-006 (lock semantics)
> Depends on: [Issue #2 Deck audit](../assessment/lawnchair-deck-audit.md)
> Primary scope/dependency record: [Issue #3](https://github.com/nunu1733/NunuLauncher/issues/3)

## 1. Question and scope

This policy classifies every baseline item type as **move**, **preserve**,
**transform**, or **reject**, and defines observable safety behavior for existing
placements, identity, and occupied space during an organization run. It is a
product research proposal: D-003 and D-006 still need their decision gates.

No captured row may be ignored. For every row in a successful run's capture, the
outcome is exactly one of: moved, preserved, explicitly deleted by the approved
organization transaction, or a rejected run with the organizer making no
database change. An outside loader, restore, or migration deletion is not an
organizer explicit deletion and must never be relabelled as one.

New drawer additions are out of scope except for the D-003 boundary.

## 2. Baseline inventory and four-way classification

`LauncherSettings.Favorites` defines the following values at baseline commit
`505dbc40e`. `favorites` stores placement rows; folder and app-pair members are
rows whose `container` points to the parent `_id`.

The classification in this table applies to the hypothetical case in which the
value is present in `favorites`. Thus the metrics/in-memory constants are also
covered: their normal in-memory instances are outside home-layout scope, but a
persisted instance is rejected rather than ignored.

| itemType | Value | Normal representation | Classification if in `favorites` | Default behavior / reason |
|---|---:|---|---|---|
| `ITEM_TYPE_NON_ACTIONABLE` | -1 | `PackageItemInfo` | **reject** | In-memory/widget-tray package header, not a home-layout row; reject with DB unchanged (§5.2). |
| `ITEM_TYPE_APPLICATION` | 0 | `WorkspaceItemInfo` | **move** | Desktop app icons are the default full-organization target. Dock and folder exceptions are in §3. |
| `ITEM_TYPE_SHORTCUT` | 1 | raw legacy row; no canonical `WorkspaceItemProcessor` representation | **preserve** | Legacy persisted shortcut; preserve as an opaque occupancy constraint when structurally valid (§3.4). |
| `ITEM_TYPE_FOLDER` | 2 | `FolderInfo` | **move** | Move its top-level placement as one unit; retain its children (§3.2). |
| `ITEM_TYPE_APPWIDGET` | 4 | `LauncherAppWidgetInfo` | **preserve** | User placement and external bind state (§3.3). |
| `ITEM_TYPE_CUSTOM_APPWIDGET` | 5 | `LauncherAppWidgetInfo` | **preserve** | User placement, including launcher-provided widgets (§3.3). |
| `ITEM_TYPE_DEEP_SHORTCUT` | 6 | `WorkspaceItemInfo` | **move** | Desktop deep shortcuts move by shortcut identity, subject to availability override (§3.5). |
| `ITEM_TYPE_TASK` | 7 | recents/metrics | **reject** | Not a Favorites row by baseline contract; reject with DB unchanged (§5.2). |
| `ITEM_TYPE_QSB` | 8 | search/metrics | **reject** | Not a Favorites row by baseline contract; normal in-memory QSB remains outside scope. |
| `ITEM_TYPE_SEARCH_ACTION` | 9 | `SearchActionItemInfo` | **reject** | Not a Favorites row by baseline contract; normal in-memory action remains outside scope. |
| `ITEM_TYPE_APP_PAIR` | 10 | `AppPairInfo` | **preserve** | Preserve as one unit, including its two children and split encoding (§3.3). |
| `ITEM_TYPE_PRIVATE_SPACE_INSTALL_APP_BUTTON` | 11 | `PrivateSpaceInstallAppButtonInfo` | **reject** | Not a Favorites row by baseline contract; normal in-memory control remains outside scope. |
| Any other value | — | unknown | **reject** | Unknown persisted type is a preflight failure, never an omission (§5.2). |

There is deliberately no transform in this proposal. A future transform needs a
separate accepted rule, recovery behavior, and migration validation before it
can replace any preserve or reject result.

Evidence: `LauncherSettings.java:96-157`, especially its statement that values
7–9 and 11 are metrics-only and not used in Favorites DB.

### 2.1 Containers and occupied space

Only the following captured `favorites` containers are organization placement
containers. This classification concerns a persisted row; it does not assert
that a normal in-memory feature occupant is stored in `favorites`.

| Container | Value | Classification for a captured `favorites` row |
|---|---:|---|
| `CONTAINER_DESKTOP` | -100 | valid: workspace page plus 2D cell/span. |
| `CONTAINER_HOTSEAT` | -101 | valid: Dock; `screen` is one-dimensional rank, not a page. |
| positive folder `_id` | positive | valid only when it resolves to a captured FOLDER row; child `rank` orders folder content. |
| positive app-pair `_id` | positive | valid only when it resolves to a captured APP_PAIR row; member `rank` encodes split position/snap ratio. |
| `CONTAINER_PREDICTION` | -102 | reject. |
| `CONTAINER_HOTSEAT_PREDICTION` | -103 | reject. |
| `CONTAINER_ALL_APPS` | -104 | reject. |
| `CONTAINER_WIDGETS_TRAY` | -105 | reject. |
| `CONTAINER_SHORTCUTS` | -107 | reject. |
| `CONTAINER_SETTINGS` | -108 | reject. |
| `CONTAINER_TASKSWITCHER` | -109 | reject. |
| `CONTAINER_PRIVATESPACE` | -110 | reject. |
| `CONTAINER_WIDGETS_PREDICTION` | -111 | reject. |
| `CONTAINER_BOTTOM_WIDGETS_TRAY` | -112 | reject. |
| `CONTAINER_PIN_WIDGETS` | -113 | reject. |
| `CONTAINER_WALLPAPERS` | -114 | reject. |
| `EXTENDED_CONTAINERS` | -200 | reject. |
| `CONTAINER_UNKNOWN` | -1 | reject. |
| any other zero, negative, or positive value | — | reject; a positive value is not valid merely because it is positive. |

The capture checks bounds and overlap against the captured device profile. A
preserved placement is an occupied constraint: other items may not be placed
over it. Folder child references and app-pair membership are also preserved as
references, not independent workspace occupancy.

Evidence: `LauncherSettings.java:186-209`, `LoaderCursor.checkItemPlacement`
(`LoaderCursor.java:524-596`), `FolderGridOrganizer.updateRankAndPos`, and
`AppPairsController.encodeRank`.

### 2.2 Page inventory

Baseline schema 32 does not have a current `workspaceScreens` table: upgrade
case 27 reads that legacy table to remap desktop screen IDs and then drops it.
The capture instead includes or derives the active ordered page/screen set from
captured DESKTOP `screen` values together with current model/device-profile
state, and revision-checks that set with the row inventory. A DESKTOP row whose
screen cannot be represented in that captured page set rejects the run with DB
unchanged. Page-order or device-profile changes after capture make the run
stale and require recapture.

Evidence: `DatabaseHelper.java:226-252`.

## 3. Normal preservation rules

### 3.1 Target set and D-003

The D-003 proposal is: a default full organization considers only items already
captured on the home layout. It does not add all drawer apps. Drawer-wide or
category-subset addition is an explicit incremental mode; its trigger and
confirmation are owned by [Issue #4](https://github.com/nunu1733/NunuLauncher/issues/4).

An existing item outside the move target remains a preserved occupancy
constraint. This is consistent with the Deck audit recommendation not to make
Deck-style all-app insertion the default.

### 3.2 Apps, folders, and Dock

- A structurally valid desktop app icon normally **moves**. A Dock app is
  **preserved** by default; its rank may change only in an explicit Dock action.
- A top-level folder normally **moves** as one workspace unit. Its child set and
  child placement are **preserved** unless a later accepted folder-layout policy
  says otherwise. `Folder.willAcceptItemType` permits APPLICATION,
  DEEP_SHORTCUT, and APP_PAIR children only.
- An empty folder is **preserved** by default. Explicit-deletion eligibility,
  the empty-folder behavior matrix, and baseline mutation-path separation are
  owned by the [empty-folder policy](empty-folder-policy.md). This policy
  authorizes no deletion beyond that policy's explicit, confirmed plan
  actions.
- A locked folder preserves its own cell and all child placements. The exact
  lock behavior is in §4; default preservation is not itself a user lock.

### 3.3 Widgets and app pairs

- System and custom widgets are **preserved**. Their span, occupied region,
  provider/bind state, and placement stay unchanged unless a future explicit
  widget action has accepted validation and recovery behavior.
- An app pair is **preserved** as one unit only when it is structurally valid:
  exactly two captured child rows reference the app-pair parent; each is an
  APPLICATION or DEEP_SHORTCUT row (the persisted types capable of becoming
  `WorkspaceItemInfo`) with a structurally valid or opaque-preservable target.
  Decoding the two `rank` values must yield complementary TOP_OR_LEFT and
  BOTTOM_OR_RIGHT stage positions, and the same snap position in both ranks;
  that snap position must be accepted by baseline
  `SplitScreenConstants.isPersistentSnapPosition`. An unavailable but otherwise
  structurally valid member remains preserved under §3.5; it does not split or
  delete the pair.
- Widgets and app pairs inside an invalid container, a pair without exactly two
  valid members, or a folder with a disallowed child are structural failures and
  therefore reject the run (§5.2), rather than relying on baseline cleanup.

Evidence: `AppPairInfo.kt:28-60` (`WorkspaceItemInfo` contents and guarded
`add`), `AppPairsController.java:192-206, 486-510` (accepted snap positions and
rank encode/decode), and
`wmshell/src/com/android/wm/shell/common/split/SplitScreenConstants.java:51-95` (complementary positions and
`isPersistentSnapPosition`).

### 3.4 Legacy shortcut

`ITEM_TYPE_SHORTCUT` is deprecated. Baseline upgrade v25→v26 can convert valid
primary-profile shortcuts to APPLICATION, but a database already at schema 32
does not rerun case 25. Also, baseline `WorkspaceItemProcessor.processItem`
does not dispatch SHORTCUT, so it does not make a canonical loader item or mark
that row deleted.

A captured legacy shortcut with valid placement/container is therefore
**preserved as an opaque occupancy constraint**: its stored row and placement
are not moved, transformed, deleted, or silently excluded. The diagnostic says
that a legacy shortcut is present and needs manual review. A legacy shortcut in
an invalid container or invalid occupancy instead rejects the whole run under
§5.2. This keeps the policy table and F-13 consistent without claiming that
loader omission is an organizer deletion.

Evidence: `DatabaseHelper.java:226-228, 374-409` and
`WorkspaceItemProcessor.kt:94-111`.

### 3.5 Profile and target availability

An instance identity is an opaque organizer ID backed by the captured
`favorites._id`; it keeps every captured row distinct. Profile identity is the
captured `profileId` user serial, resolved through `UserManagerState`/`UserCache`
without changing the serial. Target keys are `component + profile serial` for an
application, `ShortcutKey(package/shortcut-id) + profile serial` for a deep
shortcut, and `provider + appWidgetId + profile serial` for a widget. Folders
and app pairs are container instances keyed by their captured row ID.

Duplicate targets remain distinct instances. An organization run never changes
a row's profile identity or combines items across profiles. A mixed-profile
folder or app pair preserves each child's own profile identity: it is not
coalesced, reassigned, or rejected merely because its children differ in
profile. Rejection is limited to an invalid/unresolved parent reference or the
type/membership constraints in §5.2; this policy cites no baseline rule banning
mixed-profile collections.

Disabled, quiet, locked-private-space, temporarily unavailable, or unavailable
target/profile cases override normal move behavior to **preserve** when their
placement and references are structurally valid. The row remains an occupancy
constraint and is diagnosed; it is not reassigned to another profile, dropped,
or split from its folder/app pair. This applies consistently to folder children,
app-pair members, and widgets with pending/not-ready provider state.

If a captured row cannot be represented canonically because its target or
profile is unavailable, it may be preserved opaquely only when its raw
placement, container references, and occupied space are structurally valid.
Otherwise the run rejects with DB unchanged. The next run may reassess after the
target/profile becomes available.

Restore profile-id remapping occurs outside an organization run. A run captures
the resulting identity as it exists at capture; it does not use restore or
loader cleanup to delete a captured row.

Evidence: `LauncherSettings.java:303`, `LoaderCursor.java:171-177`,
`UserManagerState.java:41-56`, `ItemInfo.java:286-294`,
`WorkspaceItemInfo.java:230-234`, and `AppPairInfo.kt:28-31`.

## 4. D-006: locked placement behavior

This proposal treats a lock as a placement constraint, not merely an item flag.
[ADR-0004](../adr/0004-organizer-lock-persistence.md) owns its persistence and
migration decision. If lock truth is unknown or unreadable, organization fails
closed until the user reviews it; absence is never interpreted as unlocked.

| Case | Required behavior |
|---|---|
| Folder | Preserve the folder cell and every child placement. |
| Dock item | Preserve its captured Dock rank and occupied slot. |
| Widget | Preserve cell, span, and full occupied region. |
| App pair | Preserve its placement and both members' split encoding. |
| Coordinates/span fit the captured device profile | Preserve unchanged; no other item may use that region. |
| Coordinates/span do not fit the captured device profile | Reject before a plan or write; organizer DB remains unchanged. |
| Device profile changes after capture | Treat the capture as stale and require a new capture; do not reinterpret, move, or delete the locked placement. |

Grid migration remains a separate operation. If it changes the layout, a later
organization run must capture and validate its resulting state anew; this policy
does not promise to preserve a lock through a grid migration it did not perform.

## 5. Capture boundary and failure behavior

### 5.1 Capture boundary

An organization run begins only after it has captured one fixed inventory of all
`favorites` rows, their container/placement fields, profile identities, active
ordered page/screen set, device profile, and a revision identifying that state.
The organizer neither invokes nor adopts baseline loader, restore, or
grid-migration cleanup during this run.

The run evaluates the captured inventory in three ordered checks:

1. **Inventory check:** enumerate every captured row and its references.
2. **Representability check:** classify each row as a normal policy item or a
   structurally valid opaque preserved constraint.
3. **Plan eligibility check:** reject any unknown or structurally invalid row;
   otherwise account for every row as move, preserve, or an approved explicit
   deletion.

If any row disappears or changes after capture, including through loader
sanitation, restore, or another user operation, the revision/inventory no
longer matches. The run is stale and is rejected or recaptured before any
organizer write. It must not claim that the external disappearance was a plan
deletion, free the corresponding occupied space, or create a recovery point for
an unapplicable plan.

### 5.2 Preflight rejection cases

Each case below rejects the entire run before organizer writes. Diagnostics name
the row identity, item type, container, and reason; the organizer leaves the DB
unchanged.

| Captured condition | Result |
|---|---|
| Unknown persisted type, including metrics/in-memory type unexpectedly in `favorites` | reject; do not ignore or coerce it. |
| Any persisted container other than DESKTOP, HOTSEAT, or a valid positive folder/app-pair parent reference | reject; do not treat in-memory containers as layout rows. |
| DESKTOP screen absent from the captured page/screen set; invalid workspace/Dock bounds, overlap, or Dock rank duplication | reject; do not depend on loader overlap cleanup. |
| Missing/invalid parent reference; folder child type not accepted by baseline; invalid widget container | reject. |
| App pair lacking exactly two captured APPLICATION/DEEP_SHORTCUT children, an opaque-preservable target, complementary decoded TOP_OR_LEFT/BOTTOM_OR_RIGHT positions, or one shared persistent snap position | reject. |
| Locked placement does not fit the captured device profile | reject (§4). |
| A row cannot be represented canonically and is not eligible for opaque preservation | reject. |

### 5.3 Baseline cleanup is independent evidence, not plan behavior

At the baseline, `WorkspaceItemProcessor` can call `LoaderCursor.markDeleted`
for a deleted profile, malformed/missing app target, unavailable non-restoring
package, unpinned deep shortcut, invalid widget placement/span, or specific
widget restore failures. `LoaderCursor.commitDeleted` later deletes those marked
rows. On the invariant database-load path, `LoaderTask` can delete empty folders
after loader deletions, call `deleteBadAppPairs` (which checks member count),
and call `deleteUnparentedApps` (which removes rows whose positive parent is
missing). Grid migration has its own independent data path.

Those operations are evidence for risks to capture, not authorization for an
organization plan. In particular, they occur outside the organizer transaction
and recovery point. If any occurs after run capture, §5.1 requires stale/reject
behavior. If it occurred before capture, the absent row is not part of that
run's inventory. A future safe-application specification must establish the
operational gate that prevents overlap with such cleanup.

Evidence: `WorkspaceItemProcessor.kt:94-111, 139-275, 436-505`,
`LoaderCursor.java:421-441, 503-517`, `LoaderTask.java:648-680`, and
`ModelDbController.java:390-476` at the baseline commit.

### 5.4 Capacity, stale state, and availability

- Insufficient capacity for all movable items is a rejected run; it cannot
  displace preserved/opaque/locked occupancy. F-11b is intentionally only a
  candidate fixture until [Issue #5](https://github.com/nunu1733/NunuLauncher/issues/5)
  decides overflow behavior.
- Any snapshot revision or captured device-profile change is stale; recapture is
  required before confirmation or write.
- A deep shortcut becoming unpinned after capture, or a package/profile/widget
  changing availability after capture, also makes the relevant captured state
  stale. No automatic deletion is authorized.

## 6. Representative fixtures

Fixtures use synthetic identities and no user data.

| # | Fixture | Expected result |
|---|---|---|
| F-01 | desktop applications | movable items receive a deterministic, in-bounds layout. |
| F-02 | app plus deep shortcut | identities remain distinct and both are accounted for. |
| F-03 | folder with children | folder moves as one unit; children retain their placement. |
| F-04 | app pair in folder | pair is not split; its members and split encoding are retained. |
| F-05/F-06 | system/custom widget | placement and occupancy remain unchanged. |
| F-07 | Dock items | default Dock ranks remain unchanged. |
| F-08 | same package across profiles, plus duplicate rows within one profile | cross-profile component/package values produce distinct target keys and instances because profile serial is part of the key; same-profile duplicate rows may share a target key but remain distinct instances. |
| F-09 | unavailable private profile | item is preserved and diagnosed; this is not a placement-lock fixture. |
| F-10a | empty folder | preserved at its captured placement with a count diagnostic; no move, no deletion ([empty-folder policy](empty-folder-policy.md) M-01). |
| F-10b | explicit empty-folder deletion request | v1: not offered; future eligibility requires the [empty-folder policy](empty-folder-policy.md) E1–E7 conditions including an accepted apply-contract delete revision. |
| F-11a | full grid without overflow | reject with capacity diagnostic and no write. |
| F-11b | full grid with candidate overflow | fixture only; no overflow result is implied before Issue #5/D-007. |
| F-12 | unknown persisted type | preflight reject, diagnostic includes type and row identity, DB unchanged. |
| F-13 | residual legacy shortcut | opaque-preserve its valid stored occupancy and diagnose it; never silently omit it. |
| F-14 | app pair with disabled/unavailable member | preserve pair structure and diagnose availability. |
| F-15 | profile/grid changed after capture | stale; no organizer write, then recapture. |
| F-16 | existing Deck output | valid captured rows are classified by this policy without default drawer insertion. |
| F-17 | prediction/tray/unknown/extended container row | preflight reject with DB unchanged; normal in-memory occupants are not asserted to be rows. |
| F-18 | multiple desktop pages | captured ordered page set represents every DESKTOP screen; absent screen rejects. Page-order or device-profile change after capture is stale and requires recapture. |
| F-19a | locked folder | folder cell and every child placement remain unchanged. |
| F-19b | locked Dock item | captured Dock rank and slot remain unchanged. |
| F-19c | locked widget | cell, span, and complete occupied region remain unchanged. |
| F-19d | locked app pair | placement and both members' split encoding remain unchanged. |
| F-19e | locked out-of-bounds placement | preflight reject with DB unchanged. |
| F-19f | locked placement after grid/device-profile change | stale; no organizer write, then recapture. |
| F-20 | malformed app-pair member or rank | reject with DB unchanged unless exactly two APPLICATION/DEEP_SHORTCUT members have opaque-preservable targets, complementary decoded stage positions, and one shared persistent snap position. |

These fixtures verify conservation, no overlap, bounds, referential integrity,
lock preservation, profile isolation, and determinism through the eventual
public behavior. F-11b and F-12 are retained as explicit safety boundaries.

## 7. Evidence

All source references are fixed to baseline commit
`505dbc40e6154c05158b5d0271c45f6a885a411b`.

- [LauncherSettings.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/LauncherSettings.java)
- [WorkspaceItemProcessor.kt](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/WorkspaceItemProcessor.kt)
- [LoaderCursor.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/LoaderCursor.java)
- [LoaderTask.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/LoaderTask.java)
- [ModelDbController.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/ModelDbController.java)
- [DatabaseHelper.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/DatabaseHelper.java)
- [GridSizeMigrationUtil.java](https://github.com/LawnchairLauncher/lawnchair/blob/505dbc40e6154c05158b5d0271c45f6a885a411b/src/com/android/launcher3/model/GridSizeMigrationUtil.java)
