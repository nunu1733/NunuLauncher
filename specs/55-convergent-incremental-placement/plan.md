# Implementation Plan: Convergent incremental placement for a genuinely new launchable app

> Issue: #55
> Spec: [spec.md](./spec.md)
> Status: draft
> Dependency decision: [Issue #54](https://github.com/nunu1733/NunuLauncher/issues/54)
> Storage ADR: [ADR-0005](../../docs/adr/0005-fresh-install-presence-evidence.md)

## Current evidence

- `ModelLauncherCallbacks` maps package callbacks to model operations; callback names
  do not carry install reason or prior absence.
- `SessionCommitReceiver` is an exported manifest receiver already present in the
  baseline and receives targeted successful-install session broadcasts for the default
  home. It currently feeds the promise-icon queue.
- `InstallSessionHelper` validates trusted sessions, USER reason, app metadata, and
  uninstalled state; `SessionInfo` exposes package/profile/reason/unarchival.
- `ItemInstallQueue` chooses the first launchable activity. This behavior is not safe
  for organizer eligibility; the new classifier must require exactly one.
- The baseline Deck package-event hook is at `PackageUpdatedTask.java:456-472` and is
  owned by Issue #57 for retirement. #55 must not create a second live organizer hook.
- AOSP evidence is fixed in `docs/engineering/package-provenance.md` §3; no mutable
  branch URL is used as the research source of truth.

## Design

### Modules and interfaces

The implementation belongs in `lawnchair/src/app/lawnchair/organizer/integration/`.
The domain seam must remain platform-free:

```text
PackageEventProvenance.classify(
    event: PackageEvent,
    sessionEvidence: SessionEvidence?,
    packageName: PackageName,
    profile: ProfileId
) -> ProvenanceClassification
```

`SessionEvidence` contains only package/profile, install reason, unarchival state,
and one-shot delivery generation. Android `SessionInfo`, raw Intent, session ID,
installer, component, and layout identities remain behind the adapter.

The positive bridge is the existing `SessionCommitReceiver`; add a typed sink without
adding another manifest receiver or a duplicate organizer `LauncherApps.Callback`.
`ModelLauncherCallbacks` remains a negative-signal/inventory synchronization source.

### Data flow

1. Complete profile inventory reads current launchable package membership and commits a
   valid coverage barrier before a session event can be eligible.
2. `SessionCommitReceiver` validates and sends one typed session evidence value to the
   classifier; the evidence token cannot be reused across classification/capture.
3. Classifier checks session/package/profile/reason/unarchival, coverage-backed prior
   absence, target resolution (exactly one), and evidence lifecycle.
4. Missing, corrupt, unknown-version, incomplete, or stale state returns typed
   `Ambiguous`; it never becomes an empty valid store and never starts a proposal.
5. `FreshInstall` enters #52 proposal/preview/confirmation. The existing planner
   receives `IncrementalPlacement`; Issue #13 applies/recoveries the result.
6. On process death, reboot, listener/profile lifecycle change, or store recovery,
   invalidate the coverage barrier and require a complete inventory before eligibility.

### Alternatives rejected

- Callback-name inference: cannot distinguish restore/setup/policy/reinstall.
- Second manifest receiver or duplicate LauncherApps callback: violates the single
  live package-event owner and creates ordering ambiguity with Deck/model callbacks.
- Empty-on-corruption repair: makes unknown prior presence look like proven absence.
- Layout/recovery DB storage: rejected by ADR-0005 backup and ownership boundaries.
- Time/FIFO eviction: can erase historical membership and re-enable reinstall false
  positives.

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/.../organizer/integration/` | Domain value types, classifier, platform adapter, typed SessionCommitReceiver bridge | Keeps Android/session details behind one seam |
| app-private provenance store path | Versioned atomic file, profile/package membership, coverage state | ADR-0005 owner; not Launcher/recovery DB |
| `tests/unit/app/lawnchair/organizer/integration/` | Classifier/store contract, lifecycle, corruption, and negative privacy tests | Same public seam as production |
| `tests/organizer-instrumentation/...` | Backup exclusion, manifest bridge, profile/session integration | Requires Android/system behavior |
| #52 UI integration path | Proposal/preview/confirmation only | No auto-apply or direct DB writes |
| #57 boundary | Confirm Deck hook is retired before enabling #55 path | Prevents two live organizer owners |

No planner/application public type changes, permission changes, network, or layout DB
migration are planned.

## Migration and recovery

- Presence store schema is independent and starts at version 1. Unknown future versions
  are read-only/ineligible; do not clear and continue.
- A compatible migration writes a new temporary file, validates schema and membership,
  then atomically replaces the old file. Any failure leaves the old store invalid for
  eligibility and returns `PRESENCE_MEMORY_FAILED` until a complete inventory rebuild.
- A complete inventory rebuild is the only operation that can re-establish the coverage
  barrier after corruption, unknown version, process/listener gap, profile lifecycle
  change, or app restart where continuity cannot be proven.
- The store is excluded from Lawnchair ZIP and Android full backup. Recovery DB and
  layout DB transactions are unchanged.
- Rollback removes the integration module/store code; it does not mutate layout or
  recovery data. A failed classification path has no persistent layout side effect.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1 | classifier contract fixtures for complete inventory + USER session + one target | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.integration.*'` |
| AC-2 | event matrix and no-proposal/no-mutation assertions | same targeted JVM tests |
| AC-3 | corrupt/unknown-version/continuity-gap/empty-store negative fixtures | targeted JVM tests |
| AC-4 | profile/remove lifecycle and ZIP/full-backup exclusion | organizer instrumentation suite |
| AC-5 | bridge shape, manifest/source inventory, no duplicate callback | repository contract + targeted integration test |
| AC-6 | 0/1/N target and pre-confirmation recapture tests | targeted JVM/instrumentation tests |
| AC-7 | process generation/reboot/profile lifecycle fixtures | instrumentation and recovery scenario |
| AC-8 | #52 UI, planner IncrementalPlacement, Issue #13 application contract | existing organizer unit/instrumentation commands |
| AC-9 | diagnostics/export/recovery/backup negative corpus | diagnostics and instrumentation tests |
| AC-10 | personal/work/private and incremental-to-full convergence | representative integration fixtures |

Before implementation, add the exact new test path to the repository's organizer
unit-test gate. Build/spotless commands follow `AGENTS.md` and `docs/engineering/building.md`.

## Documentation updates

- [ ] spec status/history after acceptance
- [ ] DESIGN.md module/interface and data ownership references
- [ ] ADR-0005 accepted and linked
- [ ] #55 Issue updated with spec/plan links and dependency status
- [ ] diagnostics/privacy references updated if fields change
- [ ] PR includes exact command results and high-risk evidence if applicable

## Execution checklist

- [ ] #52 and #57 are closed and accepted outputs are on `main`.
- [ ] Spec accepted; no open product questions.
- [ ] Store and bridge tests fail before implementation.
- [ ] Classifier/store implementation completed behind the typed seam.
- [ ] Migration/backup/corruption recovery verified.
- [ ] Planner/application/UI integration verified without direct package-event writes.
- [ ] PR evidence and remaining false-negative cases recorded.
