---
issue: "#164"
status: draft
requirements:
  - CANONICAL-WRITESET-ORDER
  - NEW-FOLDER-A8-DEVICE
  - RECOVERY-CORRELATION-PRESERVED
  - NO-VERIFICATION-WEAKENING
  - TOMBSTONE-LOCKOUT-SPLIT
risk:
  - layout-data
updated: 2026-08-28
---

# New-folder plans reach A8: canonical intended-state item order

The GitHub [Issue #164](https://github.com/nunu1733/NunuLauncher/issues/164)
body is the authority for the reproduction history, device environment, journal
evidence, and diagnostic logs. This draft specification defines only the
observable correction and evidence needed to close that issue; it does not copy
a second product contract into the repository.

## Problem

On a physical device (Pixel 9a, Android 16, 4x5 default workspace), every
confirmed manual full-organization run that creates a new folder reaches
`APPLY_COMMITTED` (A6) and then deterministically fails the in-process A7
verification with `APPLY_FAILURE.VERIFICATION_FAILED`, ending in the accepted
terminal outcome `APPLY_RECOVERED`. Automatic recovery itself succeeds — the
layout always returns to the correct pre-apply state — but no run can reach
`APPLY_VERIFIED` (A8), so the flagship manual organization flow is unusable on
real workspaces.

The issue's device evidence and temporary diagnostic logging narrow the cause
to an item-order divergence between the two sides of the A7 exact comparison:
the materializer appends the new folder at the end of the intended items, while
the DB recapture orders canonical items by `ItemId` UTF-8 byte order, and the
A7 check compares `LayoutState` exactly. A newly allocated folder id such as
`19` byte-sorts before existing ids such as `2`, so any plan that creates a new
folder whose id does not also sort last fails A7. The PR #160 device evidence
passed only because those runs did not create a new folder.

## Outcome

A manual full-organization run that creates a new folder reaches
`APPLY_VERIFIED` (A8) on the Issue #164 default 4x5 workspace, with the A7
exact verification and all safe-layout invariants unchanged. The materialized
apply write set presents its intended state in the same canonical `ItemId`
byte order the DB recapture produces, so the two sides of the A7 comparison
coincide by construction rather than by the planner's item order happening to
match. Explicit recovery after a verified apply keeps `RECOVERY_REQUESTED` /
`RECOVERY_RESTORED` with `pointOriginRunId`.

## Scope

- Make the materialized apply write set's intended state — after persistent
  reference resolution, when the new folder's real id is known — present its
  items in the canonical `ItemId` UTF-8 byte order that
  `RowManifestCodec.capture` already produces.
- Keep one internal authority for the canonical item order, shared by the
  capture and the write-set preparation, so the two sides cannot diverge again.
- Add a failing-path regression test at the materializer/apply seam for a plan
  whose new folder id byte-sorts mid-list, plus coverage for the protocol
  outcome (pre-fix `APPLY_RECOVERED`, post-fix `Applied`) and for the
  no-weakening property that a genuine DB mismatch still fails A7.
- Record device evidence per the issue acceptance: a new-folder run reaching
  A8 on the default 4x5 workspace, and explicit recovery correlation.
- Split the 24-hour recovery-point tombstone lockout interaction (three
  `RESTORED` tombstones block the fourth attempt with
  `PRE_WRITE_REJECTED.RECOVERY_STORE_UNAVAILABLE`) into a follow-up issue with
  an observable diagnostic code; this work changes no retention policy.

## Non-goals

- Any change to the A7 exact comparison semantics, `LayoutState` equality,
  digest/classification logic, or verification strictness. A false
  `APPLY_VERIFIED` must remain impossible.
- Any change to the planner, category assignment, placement computation, or
  the planning-harness contracts. The planner keeps emitting its canonical
  order for existing items.
- Any change to the recovery store, recovery points, retention policy
  (`RetentionPolicy`), tombstone lifetime, point admission limits, or
  ADR-0003 guarantees. The lockout interaction is split, not fixed here.
- Any diagnostics schema, journal field, permission, transport, or export
  change. The existing journal already exposes the terminal phase/code.
- Any change to `favorites` write behavior, `PersistenceManifest` row
  ordering, page ordering, or the row-level write transaction. The issue's
  row-level evidence shows the write is deterministic and byte-identical
  across runs; only the in-memory item list order diverges.
- Launcher3 bridge changes. The defect and its fix live entirely inside the
  organizer application module.
- The `AppWidgetManager: App widget provider info is null` logcat line observed
  during reproduction. It has no `favorites` involvement and is not part of
  this failure.

## Domain language

No new product/domain term is introduced. **Canonical item order** is the
existing internal rule (capture items sorted by `ItemId` UTF-8 byte order,
introduced by the PR #160 canonical-capture fix); this spec only requires that
the materialized intended state use the same rule. No `CONTEXT.md` change is
required.

## Behavior scenarios

### Scenario: a new folder whose id byte-sorts mid-list verifies

Given a captured default workspace where a confirmed plan creates one new
folder, and the writer allocates the folder a persistent id (maximum row id + 1)
whose UTF-8 byte order falls strictly inside the existing item ids (for
example `19` between `1…18` and `2…9`)
When A6 commits and A7 independently recaptures the DB
Then the materialized write set's intended items and the recaptured canonical
items are in the same canonical `ItemId` byte order
And the exact A7 comparison succeeds and the run reaches `APPLY_VERIFIED` (A8)
And the recovery point advances to `VERIFIED` and the journal emits
`APPLY_VERIFIED`.

### Scenario: the pre-fix divergence is reproduced by a failing test

Given a test fixture at the materializer/apply write-set seam whose plan
creates a new folder with an allocated id that byte-sorts mid-list
When the write set is prepared and compared against a canonical recapture of
the same rows
Then the test fails before the fix (intended items keep the new folder last,
the canonical recapture sorts it mid-list) and after the fix both sides are in
canonical order
And the same fixture drives the protocol outcome: pre-fix
`APPLY_RECOVERED` via `APPLY_FAILURE.VERIFICATION_FAILED`, post-fix `Applied`.

### Scenario: multiple new folders and boundary ids stay canonical

Given a plan that creates several new folders (including allocations at id
boundaries such as `9→10` or `99→100`) and moves existing items into them
When the write set is prepared
Then all intended items, including every new folder and its resolved children
references, appear in one total canonical `ItemId` byte order
And preparing the same plan twice yields byte-identical intended states
(determinism invariant).

### Scenario: genuine mismatch still fails closed

Given a verified-order write set and an injected genuine DB divergence at A7
When the protocol recaptures
Then the exact comparison still fails, the journal still reports
`APPLY_FAILURE.VERIFICATION_FAILED` at stage A7, and automatic recovery
restores the pre-apply state exactly as today
And no scenario exists in which a wrong or reordered DB state reports
`APPLY_VERIFIED`.

### Scenario: explicit recovery after a verified new-folder apply

Given a verified apply that created a new folder and its recovery point
When the user opens the revision-bound recovery preview and confirms it
Then the journal emits `RECOVERY_REQUESTED` and, after exact restore
verification, `RECOVERY_RESTORED`, both carrying the point identity and its
`pointOriginRunId`.

### Scenario: tombstone lockout is split, not silently accepted

Given three recovery points tombstoned `RESTORED` within 24 hours make the
next apply attempt fail at A4 with
`PRE_WRITE_REJECTED.RECOVERY_STORE_UNAVAILABLE`
When this spec is accepted
Then a follow-up issue owns the lockout behavior with an observable diagnostic
code and a consciously chosen admission/retention decision
And this work changes no retention or recovery-store behavior.

## Data and state

- The Launcher `favorites` database remains the current-layout authority; the
  recovery database remains the separate private store selected by
  [ADR-0003](../../docs/adr/0003-organizer-recovery-point-storage.md).
- No database schema, recovery format, journal schema, backup allowlist, or
  preference change. No migration is required. A source rollback restores the
  prior behavior without a data conversion step; the failure it reintroduces
  is the safe `APPLY_RECOVERED` terminal, never a corrupt layout.
- The intended state remains a transient in-memory artifact of the apply write
  set; no new persistent identity or payload is introduced. The canonical
  order is presentation order of the same item set, not a data change: row
  contents, row ids, manifest rows, page order, and folder member ranks are
  unchanged.
- All item types keep their existing treatment; the reordering covers
  applications, shortcuts, folders, widgets, app pairs, and folder children
  uniformly as `CanonicalItemState` entries.
- The safe-layout invariants of [spec 13](../13-safe-layout-application/spec.md)
  (revision match, row accounting, lock/profile preservation, bounds,
  referential integrity, transaction atomicity, post-reload re-verification)
  are preserved and re-verified by the existing protocol checks.

## Permissions, privacy, and security

None. No permission, network, telemetry, or export destination is added.
Device evidence uses the existing redacted journal route with closed
phases/codes and opaque IDs; raw rows, titles, package names, coordinates, and
recovery payloads remain excluded by the diagnostics contract.

## Accessibility and localization

No new UI, string, or focus behavior. The existing success surface becomes
reachable on the default workspace only because the verified result now
occurs; existing confirmation, failure, and recovery accessibility behavior
is unchanged.

## Acceptance criteria

- [ ] **AC-164-01 — Failing-path regression at the materializer/apply seam:**
  A deterministic test drives the real materializer and the real apply write-set
  preparation with a plan whose new folder's allocated persistent id byte-sorts
  strictly mid-list. Pre-fix it fails because the intended items keep the new
  folder last while a canonical recapture of the same rows sorts it mid-list;
  post-fix both sides are in canonical `ItemId` byte order. The A7 comparison
  itself is unchanged.
- [ ] **AC-164-02 — Protocol outcome flips only by the order fix:** The same
  fixture drives `ApplyProtocol` end to end: pre-fix
  `APPLY_RECOVERED`/`APPLY_FAILURE.VERIFICATION_FAILED` at stage A7; post-fix
  `Applied`/A8; and an injected genuine DB mismatch still yields the typed
  failure with safe automatic recovery (no weakening, no false success).
- [ ] **AC-164-03 — Canonical order is single-sourced and deterministic:**
  Capture and write-set preparation use one internal canonical-order
  authority; repeated preparation of the same plan (including multi-folder and
  id-boundary cases) yields byte-identical intended states; existing capture
  behavior is byte-identical to before on unchanged workspaces.
- [ ] **AC-164-04 — Default-workspace device evidence:** On the Issue #164
  environment (Pixel 9a, Android 16, 4x5 default workspace), a manual run that
  creates a new folder reaches `APPLY_VERIFIED`/A8 in debug and release
  evidence, with redacted journal export and before/after row-accounting
  invariants intact.
- [ ] **AC-164-05 — Explicit recovery correlation preserved:** Device evidence
  includes recovery preview/confirmation after a verified new-folder apply,
  with journal `RECOVERY_REQUESTED` and `RECOVERY_RESTORED` carrying the same
  point identity and non-null matching `pointOriginRunId`.
- [ ] **AC-164-06 — Tombstone lockout split:** A follow-up issue owns the
  24-hour tombstone lockout after three recovered runs, with an observable
  diagnostic code, created before this issue closes; this work changes no
  retention or recovery-store behavior.
- [ ] **AC-164-07 — Scope and high-risk evidence:** No public contract,
  diagnostics schema, permission, transport, database/recovery migration, or
  planner change; the implementation PR passes the `risk: layout-data`
  independent audit gate with `CI / final-status` on the exact head SHA.

## Test oracle

| AC | Evidence |
|---|---|
| AC-164-01 | JVM unit test at the extracted/real write-set preparation seam with a new-folder fixture whose allocated id byte-sorts mid-list; red before the fix, green after. |
| AC-164-02 | `ApplyProtocolTest` (or equivalent protocol seam) using the real preparation path: pre-fix recovered outcome, post-fix applied outcome, plus mismatch-injection recovery cases. |
| AC-164-03 | Unit tests for the shared canonical-order authority across capture and preparation; determinism/byte-identical assertions for repeated preparation, multi-folder, and id-boundary fixtures; existing organizer unit suite stays green. |
| AC-164-04 | Pixel 9a / API 36 default-workspace debug and release runs; supported Settings diagnostics export showing `CHECKPOINTED → APPLY_COMMITTED → APPLY_VERIFIED`; recorded exact head SHA. |
| AC-164-05 | Same device evidence: explicit recovery preview/confirm with `RECOVERY_REQUESTED`/`RECOVERY_RESTORED` and matching `pointOriginRunId`. |
| AC-164-06 | Linked follow-up issue with the observable diagnostic code named; recorded in this spec's history when opened. |
| AC-164-07 | `spotlessCheck`, organizer unit suites, debug build, repository-contract checks, PR `CI / final-status`, and an independent audit record `docs/assessment/pr-<PR>-<slug>.md`. |

## Open questions

- The exact internal shape of the shared canonical-order authority (a small
  comparator/object in the application module versus a method reused by
  `RowManifestCodec`) and whether the pure write-set preparation is extracted
  from `LauncherLayoutAdapter` for JVM testability are implementation choices
  the plan bounds; both must keep capture output byte-identical and public
  shapes unchanged.
- The follow-up lockout issue is opened upon (or before) Stage B merge; its
  number is recorded then. Per `AGENTS.md` no tentative issue number is
  assigned in this directory.

## Change history

- 2026-08-28: Draft created for Issue #164 as Stage A, from the issue body's
  reproduction and root-cause evidence, the confirmed code paths
  (`OrganizationPlanMaterializer`, `LauncherLayoutAdapter` write-set
  preparation, `RowManifestCodec.capture`, `ApplyProtocol` A7), and accepted
  specs 13/52/150 with ADR-0003.
