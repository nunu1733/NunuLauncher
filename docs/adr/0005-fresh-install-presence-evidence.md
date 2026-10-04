---
status: accepted
---

# Do not enable incremental fresh-install eligibility without authoritative history

## Decision

The baseline does not enable an incremental fresh-install proposal path.

A package/profile event may be classified as a candidate only if a future accepted
product decision provides an authoritative install-history source that proves the
package was absent before the event. A current inventory, an ever-seen set,
`SessionInfo.getInstallReason() == INSTALL_REASON_USER`, `firstInstallTime`, or a
unique current launchable activity is not sufficient to prove that absence.

Until that source and its event-correlation protocol are approved and implemented,
all package-event incremental placement remains disabled. Update, restore, reinstall,
availability, ambiguous, missing, stale, corrupt, and contradictory paths produce no
proposal. Manual organization remains available.

No presence store, classifier interface, SessionCommitReceiver bridge, schema/migration,
or callback/session atomic-consume protocol is selected by this ADR. Those choices are
blocked on the product decision and on the dependency conditions in Issues #52 and #57.

## Context

Issue #54 compared the baseline package callbacks, PackageInstaller session evidence,
launchable activity resolution, install timestamps, and model state. The baseline
provides evidence about the current install event but no authoritative history for
installs that happened before the launcher observed them.

Counterexample:

1. X is installed while the launcher has no usable history coverage.
2. X is uninstalled before the launcher observes it.
3. A first inventory is created and records X absent.
4. X is reinstalled by a USER session and has one current launchable target.

Every currently available candidate signal now looks like a fresh install, although
X is a reinstall. Therefore a current inventory cannot be treated as prior-absence
proof. A fail-closed implementation must reject this case rather than expose a
proposal requiring the user to detect the mistake.

The two existing event inputs also have no accepted atomic protocol. `ModelLauncherCallbacks`
updates model state while `SessionCommitReceiver` receives session provenance. Ordering,
correlation, generation ownership, atomic membership/provenance consumption, replay,
crash recovery, and durable-write failure behavior are not defined by the baseline.
Combining them before those decisions are accepted could either consume a new install
as already present or reuse stale absence after a failed write.

This ADR meets the threshold because enabling or disabling eligibility changes safety,
privacy, public seam, persistence, and migration behavior, and multiple alternatives
were considered.

## Alternatives considered

| Alternative | Decision |
|---|---|
| Infer fresh install from `onPackageAdded` | Rejected: restore/setup/policy installs and reinstall can produce the same callback. |
| Use `SESSION_COMMITTED` + USER reason | Rejected as sufficient proof: it describes the current successful non-replacing install, not prior install history. |
| Use current inventory/ever-seen absence | Rejected: the counterexample above creates a false prior-absence claim. |
| Use install timestamps | Rejected: reinstall can receive fresh-looking timestamps and the normal launcher seam is not authoritative across profiles. |
| Add an app-private presence store now | Rejected as sufficient: without a trusted initial history/coverage origin, the store cannot solve pre-store reinstall. It may be reconsidered only with an authoritative source. |
| Combine callbacks and session receiver now | Rejected until ordering, correlation, generation, atomic consume/update, crash, replay, and write-failure rules are accepted. |
| Treat false positives as confirmation-UI risk | Rejected: this contradicts Issue #4's fail-closed contract and reinstall exclusion. |
| Manual organization only for current baseline | Selected interim behavior: safe false negatives, no package-event layout mutation. |

## Consequences

- #55 cannot begin its implementation/specification stage while #52 and #57 remain
  open and while the authoritative-history product decision is unresolved.
- A future product decision must define the source boundary, retention/privacy,
  profile isolation, event correlation, generation ownership, atomic consume/update,
  crash/restart replay, and failure injection before creating a spec or plan.
- No new permission, storage file, manifest receiver, public classifier, or package-event
  hook is added by this ADR.
- The research result is intentionally a blocker, not an implementation shortcut.

## Verification obligations for the future decision

Before incremental eligibility can be enabled, its accepted spec must demonstrate:

- a trusted history origin covering installs before launcher observation;
- prior absence per package/profile, including the counterexample above;
- deterministic correlation between session provenance and package callbacks;
- one generation owner and atomic consume/update semantics;
- crash/restart/replay and durable-write failure behavior that never reuses stale absence;
- profile isolation, privacy retention, backup behavior, and no raw identity diagnostics;
- no proposal for missing, stale, contradictory, corrupt, or unavailable evidence.
