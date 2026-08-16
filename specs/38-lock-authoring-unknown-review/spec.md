---
issue: "#38"
status: accepted
requirements:
  - FR-003
  - FR-004
  - NFR-001
  - NFR-002
  - NFR-007
updated: 2026-08-16
---

# Lock authoring and unknown-state review

## Problem

ADR-0004 gave every `favorites` row a tri-state `organizerLockState` column and
Issue #14 made capture, apply, recovery, migration, and restore carry it
fail-closed. Nothing lets a user express lock intent yet. Consequences today:

- A user cannot mark a placement as a Locked Placement, so organization rules
  can never see a `LOCKED` row authored by intent.
- After a 32→33 upgrade, a downgrade/re-upgrade cycle, a grid migration, or an
  old-backup restore, every pre-existing row is `UNKNOWN`. Organization fails
  closed with `LOCK_STATE_UNAVAILABLE` and the user has no screen to resolve
  it, so the organizer is permanently unavailable on that layout.
- Parent/child lock semantics (folder, app pair) are defined by ADR-0004 but
  are invisible to users; a lock that also fixes folder children or both app
  pair members would mutate more than the user expects if shown as a plain
  toggle.

## Outcome

The user can, from the launcher UI, lock and unlock the placement of an item
after seeing what the change protects, and can review every `UNKNOWN` row —
one item at a time or as one reviewed batch — so that lock truth becomes
explicit user intent. All lock writes go through the same Launcher DB writer
lease, revision, and transaction boundary supplied by Issue #14, change only
the `organizerLockState` column of existing rows, and leave the layout
unchanged on any rejection. States, effects, and failures are readable by
screen readers and localized; no state is communicated by color alone.

## Scope

- A platform-free lock authoring domain module (`app.lawnchair.organizer.locks`)
  that decides, from a canonical capture, whether a requested lock change may
  proceed and what it affects.
- Effective-lock effect computation and explanation for folder parent/child
  and app-pair parent/member rows, Dock rows, widget rows, and plain rows
  (ADR-0004 "Identity and effective-lock rules").
- `UNKNOWN` review: single-item review and one-transaction batch review, both
  requiring explicit user intent before `UNKNOWN` becomes `LOCKED` or
  `UNLOCKED`.
- A production writer that serializes through `LayoutWriteCoordinator`
  (organizer owner kind), re-reads the revision and the exact per-row
  precondition inside the DB transaction, updates only the
  `organizerLockState` column, and commits atomically.
- Launcher UI surfaces:
  - a long-press popup entry on shortcut-capable workspace/hotseat icons
    (application and deep shortcut rows) that opens a state-aware
    confirmation dialog with the effect explanation;
  - a lock management/review screen in Lawnchair preferences that lists every
    captured row of every kind — folder, folder child, Dock, widget, app pair,
    app-pair member — grouped with profile context, supports lock/unlock with
    the same confirmation, and hosts the `UNKNOWN` review flow including batch
    review.
- Localized, accessible state/effect/error/result messaging.

## Non-goals

- Planner algorithm or contract changes; the planner continues to treat
  `LOCKED`/`UNLOCKED` as captured.
- Alternative lock storage or any schema change (ADR-0004 owns the column).
- Generic organization confirmation UI (Issue #52) and any apply/recover
  protocol change (Issue #14 owns those surfaces).
- Grid migration algorithm changes; migration continues to mark rows
  `UNKNOWN`.
- A taskbar-mode popup entry (upstream `TaskbarPopupController` quickstep
  surface). With the taskbar visible, Dock rows are authored from the
  management screen instead.
- Proactive notification/trigger UX telling the user that review is pending;
  the entry points are the popup dialog state and the management screen.
- New permissions, network access, telemetry, or dependencies.

## Domain language

Uses **Locked Placement** (CONTEXT.md) as defined. No new domain terms;
"review" means a user decision that resolves a row's `UNKNOWN` state to
`LOCKED` or `UNLOCKED`, not a planner re-evaluation.

## Behavior scenarios

### Scenario: Lock a plain item from the popup

Given a captured `UNLOCKED` application row on the desktop
When the user opens the item popup and chooses "Placement lock", then confirms
"Lock" in the dialog that shows the current state and the protected region
Then the row's `organizerLockState` becomes `LOCKED` inside one DB transaction
And no other column of that row, and no other row, changes
And the capture revision changes and the dialog reports success.

### Scenario: Lock a folder parent explains child effects before mutation

Given a folder parent with children captured `UNLOCKED`
When the user requests locking the folder parent
Then before any write the UI explains that the folder's cell and every child's
container/rank become protected
And only after explicit confirmation does the parent row (not the child rows)
change to `LOCKED`; child rows keep their own stored states.

### Scenario: Unlock a folder child under a locked parent explains precedence

Given a folder child whose parent row is `LOCKED`
When the user requests unlocking the child
Then the explanation states that the parent lock keeps protecting the child's
placement, so the unlock has no effect on organization until the parent is
unlocked
And the write still records the child's own state as `UNLOCKED` only after
confirmation.

### Scenario: App-pair parent and member explanations

Given an app pair with parent `UNLOCKED` and both members `UNLOCKED`
When the user requests locking the parent
Then the explanation states that the pair's placement, membership, and split
encoding are protected, covering both members.
When instead a member is locked
Then the explanation states that the member's own lock protects its
membership/split placement, and that locking the parent would protect both
members regardless of their own states.

### Scenario: Dock and widget explanations

Given a Dock row (hotseat) or a widget row on the desktop
When the user requests locking it from the management screen
Then the explanation names the protected extent: Dock rank/slot for the Dock
row; cell, span, and occupied region for the widget.

### Scenario: Single-item UNKNOWN review requires intent

Given a row captured `UNKNOWN` (for example after a 32→33 upgrade)
When a lock change to `LOCKED` or `UNLOCKED` is requested without confirmed
user intent evidence
Then the domain command rejects and no write occurs.
When the UI collects an explicit choice ("Keep locked" or "Mark unlocked")
and resubmits with intent
Then the row changes to the chosen state in one transaction and the capture no
longer lists it as `UNKNOWN`.

### Scenario: Batch UNKNOWN review is one reviewed transaction

Given several rows captured `UNKNOWN`
When the user chooses a batch review resolving a concrete, displayed list of
rows to one state and confirms
Then exactly the listed rows change to that state in one DB transaction
And any row that no longer matches its captured exact precondition — including
rows deleted or mutated in the meantime — rejects the whole batch with no
partial write.

### Scenario: Stale item identity rejects without mutation

Given a requested item whose row was deleted (or recreated with a new id)
between capture and the in-transaction reread
When the lock write executes
Then the transaction makes no change, the caller receives a typed stale
rejection, and the UI shows a localized stale message advising a retry.

### Scenario: Unavailable profile rejects without mutation

Given a requested item whose profile is captured `UNAVAILABLE` (quiet/locked)
or a request naming a profile/item absent from the capture
When a lock change is requested
Then the command rejects before any write with a typed unavailable/stale
rejection and the layout is unchanged.

### Scenario: Unsupported or out-of-profile rows reject

Given a row in an unsupported/non-actionable container or a kind the capture
classifies as not actionable, or a placement that does not fit the captured
device capabilities
When a lock change is requested
Then the command rejects without mutation (a lock value never makes such a row
actionable; D-006), with a localized unsupported message.

### Scenario: Corrupt encoding is reviewable as UNKNOWN

Given a row whose stored value is outside the closed encoding
When the capture is taken
Then the row is surfaced as `UNKNOWN` in the management screen and review
resolves it exactly like any `UNKNOWN` row; it is never coerced silently.

### Scenario: Writer contention reports busy

Given another writer holds the coordinator lease
When a lock write is requested
Then the request returns a typed busy result without acquiring the DB or
writing, and the UI shows a localized busy message.

### Scenario: Transaction failure rolls back

Given a DB failure during the lock transaction
Then the transaction rolls back, stored lock states and layout are unchanged,
and the failure is surfaced as a localized error message.

### Scenario: Organization confirmation never changes lock state

Given any apply of a validated plan (Issue #14 surface)
Then per-row stored lock states are preserved by the plan/apply contract and
no part of the lock authoring flow participates in apply; the only production
paths that write `organizerLockState` are this module's reviewed writes and
the Issue #14 migration/marking paths.

## Data and state

- Reads: the authoritative Launcher layout DB through the same canonical
  capture codec used by Issue #14 (`RowManifestCodec`), including profile
  inventory/availability and device capabilities. No second lock store, no
  cache with independent truth; the UI re-captures on demand.
- Writes: `UPDATE favorites SET organizerLockState = ? WHERE _id = ?` for the
  targeted existing rows only, inside one `ModelDbController` transaction,
  guarded by the `LayoutWriteCoordinator` organizer lease and an in-transaction
  revision plus exact-precondition reread. No insert/delete path is added; item
  deletion continues to delete lock state with the row.
- Backup/restore, upgrade, downgrade, and grid migration behavior are owned by
  ADR-0004/Issue #14 and unchanged.
- No new persisted data, retention, or migration is introduced by this spec.

## Permissions, privacy, and security

None. No new permission, no network, no diagnostics sink; lock authoring is a
local UI/domain operation over existing data.

## Accessibility and localization

- Lock state is always rendered as text (e.g., "Locked", "Unlocked", "Needs
  review"), never color alone; state controls expose semantics/content
  descriptions.
- Effect explanations, confirmations, failures, and results are localized
  string resources; the default locale ships in this change and translations
  follow the existing Crowdin flow.
- Confirmations are reachable and readable with TalkBack; touch targets reuse
  standard Lawnchair preference/popup/dialog components; no time-dependent
  dismissal.

## Acceptance criteria

- [ ] AC-1: A user-confirmed lock/unlock changes only `organizerLockState` of
  the targeted existing rows, in one transaction, and the new revision is
  observable on recapture.
- [ ] AC-2: A row captured `UNKNOWN` never becomes `LOCKED`/`UNLOCKED` without
  confirmed user intent evidence in the request; with intent it resolves, for
  a single row or for a confirmed batch that is atomic and exact-precondition
  checked.
- [ ] AC-3: Before any mutation, the UI explains the effective-lock effects for
  folder parent/child and app-pair parent/member precedence, Dock rank/slot,
  and widget cell/span/region, per ADR-0004.
- [ ] AC-4: Lock writes use the shared `LayoutWriteCoordinator` lease, an
  in-transaction revision reread, and exact per-row preconditions; a stale or
  changed identity rejects with no mutation; concurrent lease holders receive
  a busy result without mutation.
- [ ] AC-5: Requests naming a missing/stale item or profile, an unavailable
  profile, an unsupported container/kind, or an out-of-profile placement reject
  without mutation.
- [ ] AC-6: The apply/organization-confirmation path preserves lock states and
  contains no lock-authoring control; lock state changes come only from this
  module's reviewed writes and the ADR-0004 marking paths.
- [ ] AC-7: State, effects, errors, and results are localized and non-color-only,
  with screen-reader-readable semantics, verified by UI tests over the real
  composable/screen.
- [ ] AC-8: Focused JVM tests cover the decision matrix (accept/reject/batch/
  precedence/profile/staleness) and focused instrumentation tests cover real-DB
  lock/unlock/unknown-review/stale/rollback/busy across folder, Dock, widget,
  app pair, folder child, and profiles.

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | JVM `LockAuthoringModule` protocol tests (fake writer asserts single-column write set + revision change) and real-DB instrumentation round-trip tests. |
| AC-2 | JVM decision tests (intent required; batch atomicity; exact precondition) + instrumentation UNKNOWN review E2E. |
| AC-3 | JVM `EffectiveLockEffects` fixture tests per kind/precedence + Compose UI tests asserting the explanation text renders for each effect class. |
| AC-4 | Instrumentation tests against `ModelDbController`/`LayoutWriteCoordinator` (stale revision rejection, rollback, lease contention) + JVM protocol test for busy. |
| AC-5 | JVM decision matrix tests (missing item, unavailable profile, unsupported kind/container, out-of-bounds placement). |
| AC-6 | Existing planner/application lock-preservation tests (Issue #12/#14 surfaces) cited in PR; `PublicSeam`/module-shape JVM test that the locks module exposes no apply/plan API. |
| AC-7 | Compose UI semantics tests on the management/review screen and confirmation dialog using localized resources. |
| AC-8 | `app.lawnchair.organizer.locks.*` JVM tests in the organizer unit-test CI gate + organizer-instrumentation suite on an API 36.1 emulator. |

## Open questions

None blocking. The taskbar popup boundary and the settings-only entry for
review pending state are recorded as explicit scope decisions above; revisit
in the organizer UX work (Issue #52/#53) if a proactive surface is wanted.

## Change history

- 2026-08-16: Draft created for #38; accepted with the repository workflow
  (solo maintainer delegation) before implementation.
