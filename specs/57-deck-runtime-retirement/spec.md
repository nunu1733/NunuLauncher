---
issue: "#57"
status: accepted
requirements: [NFR-001, NFR-007, NFR-010, NFR-012]
updated: 2026-08-15
---

# Retire Deck while preserving the current home layout

## Problem

Deck remains a live layout system with historical preference state, raw artifacts,
UI choices, and behavior that changes deletion and package placement. Removing it
must not replace the user's current home layout or silently change unrelated
preferences.

## Outcome

After retirement, the currently active home layout remains current for upgrades
from enabled, disabled, inconsistent, or interrupted Deck states. Deck is no
longer available as runtime behavior or visible UI, and historical state is made
inert without restoring a prior Deck snapshot.

## Scope

- Preserve the active Launcher database during retirement.
- Normalize the two historical Deck preference tombstones to `false` atomically
  before historical-file cleanup.
- Preserve the current swipe-up gesture and add-icon-to-home values.
- Remove Deck runtime behavior, UI, drag/delete behavior, package placement, and
  Deck labels.
- Keep normal package handling, App Drawer access, gesture choices, and baseline
  persisted-item delete and accessibility behavior.
- Retain the synthetic Deck-output fixture as ingestion evidence only.

## Non-goals

- Replacing Deck with an organizer package hook.
- Recovering unknown pre-Deck gesture or add-icon preference values.
- Restoring `bk` or `lawndeck` artifacts.
- Promising cross-grid downgrade safety.
- Changing permissions, privacy behavior, or Lawnchair backup format.

## Domain language

No domain-language change.

## Behavior scenarios

### Scenario: upgrade from any Deck preference state

Given an enabled, disabled, inconsistent, or partially persisted Deck state
When the new version initializes successfully
Then the active Launcher database remains the current layout
And both Deck tombstones become `false` together without changing the current
swipe-up gesture or add-icon-to-home values.

### Scenario: cleanup after normalization

Given both Deck tombstones have been normalized successfully
When historical raw artifacts are considered for cleanup
Then only exact names derived from the installed version's finite recognized
Launcher grid-database basenames are recognized
And no recognized artifact is restored or used to replace the active database.

### Scenario: normalization or cleanup interruption

Given normalization or a recognized-file delete fails or is interrupted
When the application starts again
Then the active Launcher database is unchanged
And the failed operation is retryable without activating Deck behavior.

### Scenario: old Lawnchair backup restore

Given an old Lawnchair backup restores a database and old Deck preferences
When the next application startup completes
Then the restored database is the current layout
And the tombstones normalize without restoring Deck artifacts or changing the
current gesture and add-icon values.

### Scenario: baseline behavior after retirement

Given the retired application is running
When a package is added, the user opens App Drawer settings, chooses a gesture,
or deletes a persisted item with drag or accessibility
Then the baseline behavior remains available
And no Deck-specific package placement, UI, delete rule, or accessibility rule
applies.

### Scenario: downgrade boundaries

Given the new version is removed
When rollback happens before cleanup
Then old-binary behavior is best effort.

Given cleanup has completed
When a user downgrades
Then restoring retired Deck behavior or snapshots is not promised.

Given an old binary or old backup is used before the new version initializes
When the old state is loaded
Then this is an unsupported boundary with no retirement safety promise.

## Deck upgrade behavior matrix

| Upgrade state | Active layout/database outcome | Deck tombstones | Current swipe-up/add-icon values | Recognized raw-file handling | Retry/failure outcome |
|---|---|---|---|---|---|
| Deck disabled | The active layout remains authoritative and unchanged. | Both are `false` together after successful initialization. | Both remain unchanged. | Historical files are never restored; cleanup is considered only after normalization. | A failed operation leaves the active layout unchanged and is retryable. |
| Deck enabled | The currently active Deck-produced layout remains authoritative and unchanged. | Both are `false` together after successful initialization. | Both remain unchanged; no pre-Deck value is inferred. | Historical files are never restored; cleanup is considered only after normalization. | A failed operation leaves the active layout unchanged and is retryable. |
| Inconsistent preference/artifact state | The active layout remains authoritative and unchanged. | Both are `false` together after successful initialization. | Both remain unchanged. | Only recognized historical files are inert cleanup candidates; no artifact selects a restore path. | A failed operation leaves the active layout unchanged and is retryable. |
| Interrupted normalization or cleanup | The active layout remains authoritative and unchanged. | They are both `false` only after successful normalization; no partial state selects restoration. | Both remain unchanged. | Cleanup does not begin before normalization; an interrupted delete leaves the exact artifact inert. | The next initialization retries the incomplete operation. |
| Old backup restored | The restored database becomes the current active layout. | Both are `false` together after the next successful initialization. | Both use the restored current values without retirement changes. | Historical files are never restored; cleanup is considered only after normalization. | A failed operation leaves the restored active layout unchanged and is retryable. |

## Backup, restore, upgrade, downgrade, rollback, and failure matrix

| Situation | Layout authority/result | Preferences | Raw artifacts | Outcome/support classification |
|---|---|---|---|---|
| Normal upgrade | The active layout remains current. | Both tombstones become `false` together; unrelated values remain unchanged. | No restoration; cleanup only follows successful normalization. | Supported upgrade behavior. |
| Backup created after retirement | The active layout is the backup layout source. | Current preference store is included without Deck reactivation. | Historical Deck artifacts are not backup inputs. | Supported backup behavior. |
| Old Lawnchair backup restore | The restored database becomes current. | Restored values become current, then both tombstones normalize together without unrelated changes. | No historical Deck artifact is restored. | Supported restore behavior. |
| Normalization failure | The active layout remains current and unchanged. | No successful paired normalization is claimed. | No cleanup occurs. | Retryable failure. |
| Cleanup failure | The active layout remains current and unchanged. | Successfully normalized tombstones remain false. | Only the failed exact artifact remains inert for retry. | Retryable failure. |
| Rollback before cleanup | The current layout remains the observed authority. | Old-binary behavior is best effort. | Historical artifacts may still exist. | Best effort only. |
| Downgrade after cleanup | The current layout remains the observed authority. | No restored Deck preference behavior is promised. | Retired artifacts and runtime are not promised to return. | Active layout intact; no Deck restoration promise. |
| Old binary or old backup before new-version initialization | No retirement state can be established before the new version runs. | No normalization guarantee applies. | No restoration or cleanup guarantee applies. | Unsupported boundary recorded without a false pass. |
| Cross-grid downgrade | No cross-grid authority or preservation claim is made. | No cross-grid preference guarantee is made. | No cross-grid artifact guarantee is made. | Excluded pending Issue #59. |

## Data and state

- The active Launcher database is the current layout authority.
- `enable_lawn_deck` and `show_deck_layout` are compatibility tombstones that
  persist as `false` after successful initialization.
- Recognized raw historical files derive only from the installed version's finite
  recognized Launcher grid-database basenames.
  They are never restoration inputs.
- Lawnchair backups continue to include the active database and preference stores.
  A restored old backup supplies the next current database.
- No replacement package hook exists.

## Permissions, privacy, and security

None. This change adds no permission, external transfer, or sensitive-data
collection behavior.

## Accessibility and localization

- Persisted-item delete and its accessibility action retain baseline behavior.
- Deck-only labels are absent from all localized resources.
- Removing Deck-only UI must not remove App Drawer or ordinary gesture choices,
  their labels, focus order, or accessibility access.

## Acceptance criteria

- [ ] AC-001: Enabled, disabled, inconsistent, and interrupted upgrade states
  preserve the active Launcher database and never invoke Deck enable, disable, or
  `bk` or `lawndeck` restoration.
- [ ] AC-002: Both tombstones become `false` atomically before any cleanup, while
  current `swipeUpGesture` and `addIconToHome` values remain unchanged.
- [ ] AC-003: Normalization and delete failures are retryable, never change the
  active database, and perform no cleanup before successful normalization.
- [ ] AC-004: Only exact historical names derived from the installed version's
  finite recognized Launcher grid-database basenames are recognized; no recognized
  file is restored.
- [ ] AC-005: Deck runtime, UI, drag/delete behavior, package placement, and
  localized labels are absent, while normal package, App Drawer, gesture, delete,
  and accessibility behavior remain.
- [ ] AC-006: An old Lawnchair backup makes its restored database current; the next
  startup normalizes tombstones without a Deck restore.
- [ ] AC-007: The Deck-output fixture remains ingestion evidence only, and no
  replacement package hook is introduced.
- [ ] AC-008: Rollback before cleanup is evidenced as best effort; cleanup-complete
  downgrade is evidenced without a restoration promise; old-binary or old-backup
  use before new-version initialization is recorded as unsupported.

## Test oracle

| AC | Evidence |
|---|---|
| AC-001 | Upgrade-state tests cover every row of the Deck upgrade behavior matrix. |
| AC-002 | Preference-store tests observe the matrix's paired normalization and unchanged unrelated values. |
| AC-003 | Failure injection and restart tests prove the matrix's no-cleanup-before-normalization and retry behavior. |
| AC-004 | Exact-name tests cover active and inactive grid names, journals, unknown files, and no restoration path. |
| AC-005 | Source, UI, resource, package, drag/delete, and accessibility regression tests prove removal and baseline behavior. |
| AC-006 | Backup/restore integration evidence covers the old-backup-restored matrix row. |
| AC-007 | Fixture registration test and package-path regression prove the stated scope. |
| AC-008 | Release and emulator evidence covers every downgrade, rollback, and support classification in the lifecycle matrix. |

## Open questions

None within this accepted behavior contract.

## Change history

- 2026-08-15: Draft created for #57 from Issue #56 assessment and ADR-0006.
- 2026-08-15: Accepted after Issue #56 migration decision and source inventory review.
