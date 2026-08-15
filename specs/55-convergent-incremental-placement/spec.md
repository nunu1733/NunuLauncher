---
issue: "#55"
status: proposed
requirements:
  - FR-008
  - FR-009
  - FR-015
  - NFR-001
  - NFR-002
  - NFR-003
  - NFR-005
  - NFR-007
  - NFR-008
  - NFR-009
  - NFR-011
updated: 2026-08-15
---

# Convergent incremental placement for a genuinely new launchable app

## Problem

A package callback does not prove that a package is a genuinely new install for one
profile. Update/replacing, restore, device setup, enterprise policy install,
availability return, uninstall/reinstall, and ambiguous rediscovery can all produce
package activity. Incremental placement must not guess from a callback name or mutate
layout when prior absence, fresh-install provenance, or launchable-target uniqueness
is not proven.

## Outcome

For one package/profile, the integration module returns a typed classification. Only
`FreshInstall` may enter the existing incremental proposal flow. It requires a
coverage-backed prior-absence record, a successful non-replacing
`ACTION_SESSION_COMMITTED` `SessionInfo` with `INSTALL_REASON_USER` and no
unarchival, and exactly one current launchable target. Any missing, stale,
contradictory, corrupt, or unknown evidence returns `Ambiguous`; no proposal or
layout mutation is allowed. The resulting proposal continues through preview,
explicit confirmation, the existing planner `IncrementalPlacement` seam, and the
transactional application/recovery seam. V1 has no auto-apply path.

## Scope

- Package/session evidence capture and typed classification for one package/profile.
- Prior-absence inventory and continuity-barrier validation.
- Unique launchable-target resolution and recapture/stale handling.
- Routing only eligible results into the existing proposal, planner, application,
  recovery, and diagnostic seams.
- Privacy-safe local persistence and backup exclusion required by
  [ADR-0005](../../docs/adr/0005-fresh-install-presence-evidence.md).

## Non-goals

- A new planner algorithm or planner/application public contract.
- Automatic placement or bypass of preview/confirmation/recovery.
- Deck retirement itself (Issue #57), usage signals, external classification,
  telemetry/network, rule import/export, or generic analytics.
- Treating package callback names, absent ever-seen entries, or an empty/repaired
  store as proof of prior absence.

## Domain language

- **Provenance classification**: typed decision for one package/profile event:
  `FreshInstall`, `NotNew`, or `Ambiguous`.
- **Prior-absence evidence**: a completed persisted profile inventory that records
  the package absent, with a valid continuity barrier through the session event.
- **Coverage barrier**: the validity interval from a completed inventory until an
  event observer/profile/store lifecycle gap invalidates the inventory.
- **Session evidence**: a platform-free value containing package/profile,
  `INSTALL_REASON_*`, unarchival state, and one-shot delivery generation. It does
  not contain session ID, raw Intent, installer, component, or layout identity.

## Behavior scenarios

### Scenario: genuinely new user install

Given a valid completed inventory for profile P that records package X absent
And the coverage barrier remains valid through the event
And a successful non-replacing `SESSION_COMMITTED` for X/P has reason `USER` and
    `isUnarchival == false`
And `getActivityList(X, P)` returns exactly one enabled MAIN/LAUNCHER activity
When the classifier evaluates the event
Then it returns `FreshInstall` with the one target
And the UI may show an incremental proposal
And preview and explicit confirmation remain required
And no layout write occurs during classification

### Scenario: update or replacing event

Given an existing package X/P
When `onPackageChanged` or a replacing event is observed
Then classification is `NotNew(UPDATE)`
And no incremental proposal or layout mutation occurs

### Scenario: restore, setup, policy, or unarchival

Given a session event whose reason is `DEVICE_RESTORE`, `DEVICE_SETUP`, or `POLICY`
Or `SessionInfo.isUnarchival()` is true
When the classifier evaluates the event
Then classification is `NotNew`
And no incremental proposal or layout mutation occurs

### Scenario: observed reinstall

Given a valid prior inventory or membership record for X/P
When X/P is removed and later receives a USER session commit
Then classification is `NotNew(REINSTALL)`
And package removal does not erase historical membership
And no incremental proposal or layout mutation occurs

### Scenario: prior absence is not proven

Given no completed inventory for P before the event
Or the inventory-to-event continuity barrier is invalid
When a USER session commit for X/P is received
Then classification is `Ambiguous(PRESENCE_MEMORY_FAILED)` or `Ambiguous(EVIDENCE_STALE)`
And no incremental proposal or layout mutation occurs
And manual organization remains available

### Scenario: store corruption or unknown schema

Given the presence store is unreadable, corrupt, or newer than the running app
When classification is requested
Then the store is not treated as an empty valid store
And classification is `Ambiguous(PRESENCE_MEMORY_FAILED)`
And no proposal or layout mutation occurs
And a complete inventory is required before eligibility resumes

### Scenario: missing session provenance

Given a package callback or inventory change without a matching successful
    non-replacing session evidence
When classification is requested
Then classification is `Ambiguous(SESSION_UNAVAILABLE)`
And no incremental proposal or layout mutation occurs

### Scenario: ambiguous launchable target

Given valid provenance and prior absence
When `getActivityList(X, P)` returns zero, two or more, or an inaccessible result
Then classification is `Ambiguous(TARGET_NOT_LAUNCHABLE)`,
    `Ambiguous(TARGET_NOT_UNIQUE)`, or `Ambiguous(TARGET_RESOLUTION_FAILED)`
And no proposal or layout mutation occurs

### Scenario: evidence becomes stale

Given a candidate FreshInstall
When process generation changes, the one-shot evidence token is reused, or the
    package/profile/target changes between proposal and capture
Then classification is `Ambiguous(EVIDENCE_STALE)`
And the flow recaptures/reassesses rather than replaying the old plan

### Scenario: work/private profile isolation

Given the same package exists in personal and work/private profiles
When an event is evaluated
Then profile identity is preserved end-to-end
And inaccessible, quiet, locked, or hidden profiles fail closed
And one profile's presence evidence cannot authorize another profile

### Scenario: process death, reboot, and lifecycle gaps

Given a process death, reboot, listener gap, profile availability change, or store
    recovery occurs
When the process resumes
Then the coverage barrier is invalid until a complete inventory succeeds
And no package is proposed from an unverified empty/missing store

### Scenario: successful downstream run

Given `FreshInstall` was classified
When the user reviews and confirms the incremental proposal
Then planning uses the existing `OrganizationPlanner` with `IncrementalPlacement`
And application/recovery uses the existing Issue #13 seam
And the result preserves profile identity, occupied constraints, and convergence
And no automatic apply route exists

## Data and state

- Launcher/package callbacks and the manifest `SessionCommitReceiver` are evidence
  inputs; callback names alone are not eligibility evidence.
- The presence store is app-private, separate from Launcher and recovery DBs, and
  owns only schema version, profile identity, and package membership. Its ownership,
  lifecycle, corruption, migration, and backup boundary are defined by ADR-0005.
- A complete inventory creates the coverage barrier. Unknown/corrupt/newer store,
  process/listener/profile lifecycle gap, or incomplete inventory invalidates it.
- Package remove/unavailable does not erase membership. Profile deletion erases that
  profile's membership. No time/FIFO eviction is allowed.
- The store is excluded from Lawnchair ZIP and Android full backup. It is not copied
  into diagnostics, export, recovery records, or layout snapshots.
- A one-shot session evidence value is consumed by one classification/capture chain;
  raw Android objects do not cross the domain seam.

## Permissions, privacy, and security

No new permission, network, telemetry, or external transport. Package names and
profile identity are sensitive local inventory data: app-private only, never in
organizer diagnostics/export. Session ID, component, installer, raw Intent, layout
coordinates, and rule contents are never persisted by this feature.

## Accessibility and localization

No new UI is specified here. The downstream proposal/preview/confirmation/recovery
surface must inherit Issue #4/#52 accessibility requirements and expose ambiguous,
not-new, and stale outcomes without relying on color.

## Acceptance criteria

- [ ] AC-1: Only the complete prior-absence + valid session provenance + unique target
      conjunction returns `FreshInstall`.
- [ ] AC-2: Every update/replacing/restore/setup/policy/reinstall/availability/remove/
      unavailable/suspend/unarchival path returns `NotNew` or `Ambiguous` and performs
      no proposal or layout mutation.
- [ ] AC-3: Missing session, missing prior-absence coverage, continuity gap, profile
      access failure, target 0/2+/query failure, stale, contradictory, corrupt, and
      unknown-version evidence all fail closed; an empty repaired store is never used
      as absence evidence.
- [ ] AC-4: Presence membership is profile-isolated, survives package remove, is
      erased only by profile deletion/app-data removal, and is excluded from both
      backup surfaces.
- [ ] AC-5: SessionCommitReceiver is the single positive provenance bridge; no second
      manifest receiver or organizer-specific duplicate LauncherApps callback is added.
- [ ] AC-6: Target resolution accepts exactly one current launchable activity and
      recaptures if package/profile/target changes before confirmation.
- [ ] AC-7: Process death/reboot/profile lifecycle invalidates coverage and requires
      a complete inventory before a new proposal.
- [ ] AC-8: Eligible proposals use existing planner/application/recovery seams,
      preview plus explicit confirmation, and no auto-apply route.
- [ ] AC-9: Privacy tests demonstrate no package/component/profile/session/layout/
      rule identity in diagnostics, export, recovery records, or backup artifacts.
- [ ] AC-10: Personal/work/private profile isolation and convergence with later full
      organization are demonstrated by representative integration tests.

## Test oracle

| AC | Evidence |
|---|---|
| AC-1 | JVM classifier contract tests with complete inventory/session/target fixtures |
| AC-2 | event classification matrix tests and no-mutation assertions |
| AC-3 | corrupted/unknown-version/continuity-gap/empty-store negative fixtures |
| AC-4 | store lifecycle tests plus ZIP/full-backup exclusion instrumentation tests |
| AC-5 | bridge shape test and manifest/source inventory check |
| AC-6 | target 0/1/N and recapture tests |
| AC-7 | process-generation, reboot simulation, profile lifecycle tests |
| AC-8 | #52 UI integration, planner seam, Issue #13 application contract tests |
| AC-9 | negative diagnostic/export/recovery/backup corpus |
| AC-10 | personal/work/private and incremental-to-full convergence integration tests |

## Open questions

None for the behavior contract. Implementation path, exact file encoding, and test
placement are specified in [plan.md](./plan.md) and must not weaken the accepted
fail-closed rules.

## Change history

- 2026-08-15: Proposed spec created from Issue #54 research and #55 requirements.
- 2026-08-15: Review correction: prior absence requires a valid coverage barrier;
  corrupt/unknown stores fail closed instead of becoming empty evidence.
