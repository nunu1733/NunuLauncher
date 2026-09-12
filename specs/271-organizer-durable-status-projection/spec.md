---
issue: "#271"
status: implemented
requirements: [DS-AC-01, DS-AC-02, DS-AC-03, DS-AC-04, DS-AC-05, DS-AC-06, DS-AC-07, DS-AC-08, DS-AC-09, DS-AC-10]
risk: []
updated: 2026-09-10
---

# Re-opened organizer Settings presents the durable organizer status instead of a bare Idle

## Problem

The organizer status a re-opened Settings presents is derived solely from the
process-local `ManualOrganizationRun` state machine (`appliedPoint`,
`lastVerifiedApply`, `pendingRecovery`; nothing is persisted). After any process
death, the surface shows `Idle` — indistinguishable from "never organized" —
even while a durable, restorable recovery point exists in the recovery store.
The #265 owner review requires that reopening after process death must not
present `Idle` while durable state is still `VERIFIED` and restorable.

## Outcome

A re-opened organizer Settings presents a minimal, privacy-safe durable status
derived from the recovery store's persisted records, distinguishing at least:

- **never organized** — no durable record,
- **organized and restorable** — a `VERIFIED` point within retention,
- **unresolved** — an in-flight or unresolved recovery record,
- **restored or expired** — the saved point was restored or its retention lapsed.

The projection is owned by the application module (the owner of the recovery
store); the Settings surface only reads the projected closed status. The
projection carries no record payloads, revisions, digests, item identities, or
identifiers — only what the existing surface already shows as vocabulary.

### Design decision: derived projection, no second persistence

The projection is **derived at read time** from the recovery store's durable
records and tombstones; this spec deliberately does not introduce a second
persisted copy of the organizer status. The recovery store is already the
durable, retention-managed source of truth (spec 13), and a derived projection
cannot outlive the record it describes by construction — a second copy would
duplicate authority (AGENTS.md 正本の分担) and require its own
retention-alignment invalidation. "Persistence" of the projection is therefore
inherited from the recovery store, and the "projection cannot outlive the
record" requirement is structural, not enforced by cleanup code.

## Scope

- A closed-vocabulary durable status type and a pure derivation over bounded
  record metadata (lifecycle, timestamps, checksum validity) and tombstone
  metadata (reason, expiry), aligned with spec 13 retention semantics.
- A read-only projection seam owned by the application module, exposed to the
  organizer Settings surface through the existing `ManualOrganizationRun`
  application seam, fail-closed when the store is unavailable or startup
  reconciliation has not completed.
- The Settings render mapping for the durable status while no run operation is
  active (`Idle` / `Cancelled` surfaces), including localized strings (EN, ja).
- Restart tests: apply → process death → reopen shows restorable; restore →
  reopen shows restored/expired; unresolved record → reopen shows the
  unresolved surface; retention-aligned invalidation.

## Non-goals

- Changing recovery records, lifecycle, retention, or the recovery DB schema
  (spec 13 stays the contract); the projection is derived and non-authoritative.
- Enabling the restore preview/confirm flow from a cold process: the durable
  status is informational in this issue. Wiring "Restore layout" to a persisted
  point after process death requires constructing a `RecoveryRequest` outside
  the apply session and is a separate, future spec (tracked as a follow-up).
- General organizer history/analytics; item-level detail beyond what the
  current surface already shows.
- New diagnostics fields or journal events for projection reads.
- Changing the existing process-local run states, their transitions, or the
  restore entry point while a run is active in this process.

## Domain language

- **Durable organizer status**: the closed-vocabulary projection of the
  recovery store's record/tombstone state, computed by the application module
  and read by the Settings surface. Candidate addition to `CONTEXT.md`.
- **Restorable window**: the spec 13 retention lifetime of a `VERIFIED` record
  (24h from record creation) during which the durable status may present
  "organized and restorable".

## Behavior scenarios

### Scenario: reopen after a verified apply shows restorable

Given the recovery store durably holds a record in `VERIFIED` lifecycle whose
retention has not elapsed, and the process has restarted (store and module
freshly constructed, startup reconciliation completed successfully)
When the user opens organizer Settings and no run operation is active
Then the surface presents the durable status "organized and restorable" in
addition to the normal start entry, instead of a bare `Idle`
And the presented status contains no record identifier, revision, digest,
manifest payload, item identity, or timestamp

### Scenario: reopen after restore shows restored or expired

Given a restore completed successfully — the recovery store durably holds
either a record row in `RESTORED` (or `EXPIRED`) lifecycle, or, after retention
eviction, only a tombstone with reason `ALREADY_RESTORED` (or `EXPIRED`) within
its tombstone retention — and the process has restarted
When the user opens organizer Settings and no run operation is active
Then the surface presents the durable status "restored or expired"
And it does not present "organized and restorable"

### Scenario: expired restorable window is not presented as restorable

Given a `VERIFIED` record whose creation time plus the spec 13 retention
lifetime has passed
When the durable status is derived (before or after lazy retention eviction)
Then the status is not "organized and restorable"
And after retention eviction the status derives from the `EXPIRED` tombstone as
"restored or expired"

### Scenario: unresolved record shows the unresolved surface

Given startup reconciliation completed successfully (readiness `READY`) and the
recovery store durably holds a record that presents an unresolved recovery: a
row in `CORRUPT` or `INCOMPATIBLE` lifecycle, or a `VERIFIED` row whose payload
checksum is invalid
When the user opens organizer Settings and no run operation is active
Then the surface presents the durable status "unresolved" with the existing
safe-support guidance (open diagnostics), reusing the existing unresolved
wording pattern
And it does not present "organized and restorable"

And (derivation rule, unit-tested): a row in a non-final lifecycle — `CREATING`,
`READY`, `APPLYING`, `COMMITTED_UNVERIFIED`, `RESTORING` — also derives
"unresolved", as does a retained `CORRUPT` or `INCOMPATIBLE_VERSION` tombstone.
Non-final rows normally cannot coexist with a completed reconciliation (the
readiness gate fail-closes to unavailable instead), so the surface-level
assertion targets the reachable rows above.

### Scenario: never organized presents no durable status row

Given the recovery store holds no record rows and no retained tombstones, or
only tombstones that never represented an applied result (`PRUNED_UNUSED` /
`QUARANTINED`)
When the user opens organizer Settings and no run operation is active
Then the surface presents no durable status row and is unchanged from today

### Scenario: fail-closed when durable truth cannot be read

Given the recovery store is unavailable (`INCOMPATIBLE_VERSION`,
`READ_FAILED`), or startup reconciliation has not completed (`FAILED`,
`RECONCILING`, `IDLE`), or the module's run mutex is contended by a writer, or
the inspection snapshot for the current generation is unreadable
When the Settings surface requests the durable status
Then the projection returns a fail-closed unavailable result and the surface
presents no durable status row and no invented state, announcing an explicit
checking/loading row instead while the result is not yet known — the row
persists while the gate is `IDLE`/`RECONCILING` and disappears once the gate
reaches a terminal state (`READY`/`FAILED`)
And no write, lifecycle transition, retention cleanup, or journal event is
performed by the read

### Scenario: reconciliation completes while the surface stays open

Given a durable-status read failed closed because startup reconciliation was
still pending (`IDLE`/`RECONCILING`) when the Settings surface entered `Idle`
When startup reconciliation reaches a terminal state (`READY` or `FAILED`)
without the user navigating away and no run operation becomes active
Then the surface re-reads the durable status and presents it per the scenarios
above, on the same surface, without depending on read timing
And a first read that already succeeded is not re-presented differently by a
later gate state

### Scenario: retention-aligned invalidation — no projection outliving its record

Given a durable status of "organized and restorable" was derivable from a
`VERIFIED` record
When the underlying record is gone from the recovery store (evicted and its
tombstone purged, or pruned as unused)
Then deriving the durable status again never presents "organized and
restorable"

### Scenario: organizer surface as the first screen of a fresh process

Given the exported settings surface opens the organizer in a process where no
Launcher activity has resumed yet
When the organizer Settings entry initializes
Then the application module exists (its `LauncherAppState` composition is
ensured) and the shared, idempotent startup reconciliation trigger runs
And because no Launcher is bound to start the model load, the trigger drives
the model load itself (a no-callback loader, a no-op once a Launcher has
bound), so reconciliation completes on its own and the same Settings surface
presents the derived durable status without the user ever opening the
Launcher
And the load fails closed only on a genuine failure or timeout; until then
the checking row persists and no invented status is presented

### Scenario: active process-local run state keeps precedence

Given a run operation is active or presenting in this process (`Capturing` …
`RecoveryResultState`)
When the Settings surface renders
Then the existing process-local presentation is unchanged and no durable status
row is rendered alongside it
And once the surface returns to `Idle` or `Cancelled`, the durable status is
read again and rendered per the scenarios above

## Data and state

- **Reads**: the recovery store's persisted record rows (bounded metadata only:
  `lifecycle`, `created_at_ms`, `updated_at_ms`, checksum validity) and
  tombstones (`reason`, `expires_at_ms`). The read path reuses the module-owned
  inspection snapshot seam (#89), including its concurrency contract: the
  module acquires its run mutex non-blocking before reading (writer contention
  fail-closes to unavailable), and the read itself never opens SQLite, probes
  versions, writes, mutates lifecycle, or purges tombstones. The snapshot is
  republished by startup reconciliation (`reconcileAtStart` →
  `rebuildInspectionSnapshot`), so a re-opened process reads a fresh,
  generation-valid snapshot after reconciliation completes.
- **Writes**: none. The projection is derived, non-authoritative, and stateless.
- **Identity/retention**: the projection type carries no identifiers. The
  "restorable" window follows spec 13 retention (24h from record creation);
  tombstone visibility follows the tombstone retention written by the store.
  Because the projection is derived per read, it cannot outlive the record it
  describes.
- **Migration/backup/restore**: none. No schema change, no new persisted data,
  no backup allowlist change. ZIP restore already wipes the recovery DB and
  inspection snapshot (ADR-0011), after which the projection fail-closes to
  "never organized" semantics.
- **Rollback**: removing the seam and the Settings render mapping restores
  today's behavior exactly; no durable state is touched.

## Permissions, privacy, and security

- None. No new permissions, no external transmission, no new persisted data.
- The projection vocabulary is closed and payload-free: no record payloads,
  revisions, digests, item identities, or identifiers beyond what the existing
  surface already shows. The projection type is not added to any backup
  allowlist and lives in the application module, not in UI code.

## Accessibility and localization

- The durable status renders as a text row announced by the screen reader,
  placed before the start entry on the `Idle`/`Cancelled` surfaces, following
  the existing `SummaryText` / live-region patterns of this surface; focus
  behavior of the existing start entry is unchanged. The status is re-read each
  time the surface transitions into `Idle` or `Cancelled` (e.g., after
  cancelling a run in place), not only on first composition.
- All new user-visible strings are localized in EN (`values/`) and ja
  (`values-ja/`); no string embeds counts, identifiers, or timestamps, so no
  plural handling is required.

## Acceptance criteria

- [ ] DS-AC-01: Process-death restart test — apply → close/reopen → reopened
      Settings projection presents "organized and restorable" for a `VERIFIED`
      record within retention.
- [ ] DS-AC-02: Process-death restart test — restore (and expiration) →
      close/reopen → projection presents "restored or expired" both for the
      fresh `RESTORED`/`EXPIRED` record row (pre-eviction) and for the
      `ALREADY_RESTORED` / `EXPIRED` tombstone (post-eviction), and never
      "organized and restorable".
- [ ] DS-AC-03: Process-death restart test — an unresolved record after
      completed startup reconciliation (a `CORRUPT` row, an `INCOMPATIBLE`
      row, or a `VERIFIED` row with an invalid payload checksum) → projection
      presents "unresolved"; the derivations for non-final lifecycle rows and
      retained `CORRUPT`/`INCOMPATIBLE_VERSION` tombstones are covered by unit
      tests on the deriver.
- [ ] DS-AC-04: A `VERIFIED` record past its retention window (boundary tested
      at exactly `createdAt + 24h`) never derives "organized and restorable",
      before and after lazy eviction; expired/restored tombstones outside
      tombstone retention never derive "restored or expired"; no records and
      only `PRUNED_UNUSED`/`QUARANTINED` tombstones derive "never organized";
      retained `CORRUPT`/`INCOMPATIBLE_VERSION` tombstones derive "unresolved".
- [ ] DS-AC-05: The projection never presents "organized and restorable" while
      the underlying record is gone (retention-aligned invalidation covered by
      a test); fail-closed unavailable (store unavailable, reconciliation not
      completed, run-mutex contention, unreadable snapshot) renders no durable
      status row and performs no writes or journal events.
- [ ] DS-AC-09: A fail-closed read taken during pending startup reconciliation
      recovers on the same Settings surface when the readiness gate reaches a
      terminal state (no navigation, no timing dependence — the surface re-reads
      on observable gate transitions); while no result is known the surface
      presents an explicit checking row, visually distinct from "never
      organized". Covered by a UI instrumentation test and a same-module
      before/after reconciliation instrumentation test.
- [ ] DS-AC-10: Opening the organizer surface as the first screen of a fresh
      process initializes the application module's composition and runs the
      shared idempotent startup reconciliation trigger; with no bound Launcher
      the trigger drives the model load itself (minimal no-callback loader
      bridge, a no-op once a Launcher binds), so staying on the Settings
      surface alone reaches a terminal gate and — when a restorable point
      exists — presents the restorable status without opening the Launcher.
      No crash, no invented status, no permanently stale surface; fail-closed
      only on genuine load failure/timeout. Evidenced by a cold-process
      emulator flow that never opens the Launcher.
- [ ] DS-AC-06: No new diagnostics fields outside the closed vocabulary; the
      projection emits no journal events; the projection type leaks no record
      payload, revision, digest, or item identity (enforced by the type's shape).
- [ ] DS-AC-07: The Settings render mapping presents the durable status row
      only while no run operation is active (`Idle`/`Cancelled`), with the
      unresolved status reusing the existing safe-support guidance pattern;
      covered by a UI instrumentation test including the fail-closed render.
- [ ] DS-AC-08: `CONTEXT.md` (domain term), `DESIGN.md` (seam ownership), and
      this spec's status are updated in the same PR.

## Test oracle

| AC | Evidence |
|---|---|
| DS-AC-01 | Instrumentation test: recovery store + projection seam across close/reopen (process-death surrogate, existing restart-equivalent pattern) asserting the restorable projection |
| DS-AC-02 | Instrumentation test: restore → retention → close/reopen asserting both the fresh `RESTORED` row projection and, after eviction, the `ALREADY_RESTORED`/`EXPIRED` tombstone projection |
| DS-AC-03 | Instrumentation test: `CORRUPT` row and checksum-invalid `VERIFIED` row after a reconciliation-equivalent snapshot rebuild → unresolved projection; unit tests cover non-final rows and `CORRUPT`/`INCOMPATIBLE_VERSION` tombstones |
| DS-AC-04 | Unit tests: pure derivation fixtures and retention/tombstone boundary edges (±1 ms at `createdAt + 24h`, tombstone expiry edge), priority ordering between statuses |
| DS-AC-05 | Unit test (invalidation on derived inputs) + instrumentation test (record gone → re-derivation; fail-closed paths) |
| DS-AC-06 | Type-shape review (projection type carries no payload fields) + existing diagnostics contract tests unchanged; no `RunEvent` emission in the new path (code review + grep) |
| DS-AC-07 | Instrumentation UI test on the organizer Settings surface: durable row present/absent per status incl. fail-closed and active-run precedence |
| DS-AC-08 | PR diff contains `CONTEXT.md`, `DESIGN.md`, and spec/plan status updates |
| DS-AC-09 | UI instrumentation test: blocked first read announces the checking row, then a readiness-gate transition re-reads and presents the status on the same surface; instrumentation test asserting one module instance reports `UNAVAILABLE` before and the derived status after `reconcileAtStart()`; unit test that the gate's observable state flow mirrors every transition |
| DS-AC-10 | Emulator evidence: force-stop → cold-start the exported settings surface directly into the organizer screen → wait WITHOUT opening the Launcher → model load + reconciliation complete on their own → restorable status row renders on the same surface; recorded in the PR/audit |

## Open questions

- None blocking. The follow-up for wiring the restore action to a persisted
  point from a cold process is intentionally deferred (Non-goals) and should be
  tracked as its own issue if pursued.

## Change history

- 2026-09-10: Draft created for #271.
- 2026-09-10: Accepted after Phase-1 review; extended with DS-AC-09/DS-AC-10 (readiness-driven re-read, explicit loading row, cold-process settings entry) from the PR #276 owner review.
- 2026-09-10: Re-review round: DS-AC-10 strengthened — the cold settings entry drives the model load itself (no-callback loader bridge) so the same surface reaches the derived status without opening the Launcher; the checking row persists while the gate is pending.
