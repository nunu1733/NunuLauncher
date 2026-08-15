---
status: accepted
---

# Require a coverage-backed app-private presence store for fresh-install proposals

## Decision

Incremental placement may produce a fresh-install proposal only when a persisted,
completed profile inventory proves that the package was absent before the install
provenance event and a continuity barrier remains valid until that event.

The presence store is a separate app-private file owned by the organizer integration
module. It stores only schema version, profile identity, and package membership. It is
not part of the Launcher layout DB, recovery DB, Lawnchair ZIP backup, or Android full
backup. The store is append/update-only for observed package membership; package
remove/unavailable events do not erase membership. A profile removal erases that
profile's membership.

A missing, corrupt, unreadable, unknown-version, or continuity-invalid store is not
replaced with an empty valid store for eligibility decisions. It invalidates the
coverage barrier and returns `PRESENCE_MEMORY_FAILED`; no incremental proposal or
layout mutation is allowed until a complete inventory successfully rebuilds the
barrier. An empty store is therefore a valid result only after a successful complete
inventory, not after recovery from an error.

## Context

Issue #54 compared the baseline package callbacks, PackageInstaller session evidence,
launchable activity resolution, install timestamps, and model state. The comparison
found that callback names, `SessionInfo` reason, and install timestamps cannot by
themselves distinguish a fresh install from an uninstall/reinstall that the launcher
never observed. Treating an ever-seen miss as proof of prior absence would violate the
fail-closed contract in Issue #4 and permit an unobserved reinstall to produce a
proposal.

The selected provenance event is the successful, non-replacing
`ACTION_SESSION_COMMITTED` broadcast delivered to the default home. The session
contains the package, installing user, install reason, and unarchival state. It does
not provide the install history needed to prove prior absence, so the organizer needs
its own coverage-backed memory.

This choice meets the ADR threshold: changing the store boundary or allowing a
missing store to become empty changes safety, privacy, migration, and backup behavior.

## Alternatives rejected

| Alternative | Why rejected |
|---|---|
| No persistence; infer from `onPackageAdded` | The callback also covers restore/setup/policy installs and cannot distinguish an unobserved reinstall. |
| Ever-seen set without inventory coverage | A missing entry is not evidence that the package was absent before the event. It permits the P1 false positive this ADR forbids. |
| Store presence in the Launcher layout DB | The layout DB is included by baseline backup allowlists; package inventory would violate the required backup/privacy boundary and couple provenance corruption to layout state. |
| Store presence in the recovery DB | Recovery DB ownership is limited to recovery records by ADR-0003; provenance loss or corruption must not affect recovery. |
| Clear corrupt/unknown data and continue | An empty replacement falsely represents “known absent” and can classify an existing package as fresh. |
| Time-based eviction or arbitrary FIFO cap | Deleting old membership recreates the reinstall false-positive path. Retention is profile-lifecycle based, not time based. |

## Consequences

- A first install that occurs while the launcher has no valid inventory coverage is a
  safe false negative: it gets no incremental proposal until a later supported event
  can be assessed. It is never treated as a fresh install merely because the store is
  empty or missing.
- Store corruption is isolated from layout and recovery. The integration module must
  expose a typed failure to the classifier and must not silently repair eligibility.
- The file has an independent schema version. Future unknown versions are read-only
  for the running app and invalidate coverage; migration is a #55 implementation-plan
  concern and must rebuild/verify the complete inventory before eligibility resumes.
- Package names and profile identity are sensitive local inventory data. They remain
  app-private, are excluded from diagnostics/export/telemetry, and are not copied into
  recovery records.
- The exact file encoding, atomic write mechanism, and test adapter are implementation
  details of #55 `plan.md`; they must preserve this ownership and failure contract.

## Verification obligations

The #55 spec and plan must prove:

- complete inventory creates a valid coverage barrier and records absent/present
  package membership per profile;
- process death, reboot, listener gaps, profile availability changes, store repair,
  and unknown schema invalidate the barrier;
- corrupt/unknown-version/read failures return `PRESENCE_MEMORY_FAILED` and do not
  produce a proposal;
- package removal preserves historical membership, while profile removal erases it;
- both Lawnchair ZIP and Android full backup exclude the store;
- no package/profile/layout identity enters organizer diagnostics or export.
