---
issue: "#110"
status: accepted
requirements:
  - NFR-010
updated: 2026-08-23
---

# Reproducible organizer upstream patch-surface baseline

## Problem

The repository records bridge paths in individual implementation plans and
audits, but it has no single definition, quantitative baseline, or offline
comparison command for the organizer's changes to Lawnchair/Launcher3-owned
source. Consequently, a rebase or organizer pull request cannot demonstrate
whether its upstream patch surface changed without rebuilding the inventory by
hand.

## Outcome

Maintainers can reproduce one accepted patch-surface baseline from the local Git
object database, inspect every counted path by bridge responsibility, and obtain
a non-zero review signal when a candidate rebase or organizer change grows the
counted upstream/bridge surface. New project-owned organizer and migration
modules are visible separately and do not distort this upstream maintenance
metric. The measurement complements, rather than replaces, the existing
writer-admission scan. [1]

## Scope

- Define the counted production paths, including base resources, schemas,
  manifests, and changed-line measurement, plus explicit exclusions.
- Record an exact upstream commit, exact main commit, expected totals, bridge
  responsibility groups, and ownership evidence in one machine-readable file.
- Provide a standard-library-only, no-network command that reproduces the
  accepted baseline and compares local rebase or organizer candidates.
- Fail the check for an unowned changed upstream/bridge path, an invalid
  comparison ancestor, or a requested growth review.
- Link the current upstream strategy, system seams, Deck-retirement evidence,
  and writer audit without duplicating their architectural content. [1] [2]

## Non-goals

- Refactor or reduce production modules merely to lower a count.
- Change upstream sync policy, high-risk writer policy, feature behavior,
  migration behavior, or release behavior.
- Treat tests, generated/vendor churn, documentation, workflows, or repository
  tooling as production upstream patch surface.
- Evaluate semantic safety, code quality, or merge-conflict difficulty from a
  numeric result alone.

## Domain language

No domain-language change. **Upstream patch surface** is a repository-maintenance
term whose precise metric is defined by the accepted assessment, not a new
organizer product concept.

## Behavior scenarios

### Scenario: Reproduce the accepted baseline

Given the local Git object database contains the recorded upstream and main
commits, and the checked-in inventory has expected totals

When a maintainer runs the measurement with `--verify`

Then it reports the same counted files, additions, deletions, excluded
project-owned additions, and bridge groups as the accepted inventory

And it exits successfully only when all baseline deltas are zero.

### Scenario: Compare a rebase or organizer candidate

Given a local candidate upstream commit is an ancestor of the candidate target
commit

When a maintainer runs the measurement with `--enforce-baseline`

Then the tool reports the three counted deltas against the accepted baseline

And it exits non-zero when counted files, additions, or deletions grow, so the
change receives Issue-owned review instead of an automatic quality verdict.

### Scenario: Encounter an unowned production bridge

Given a changed non-excluded repository path, including a base resource or
schema, is neither a project-owned addition nor listed in exactly one bridge
group

When the measurement runs

Then it exits non-zero and names the path requiring ownership

And it does not silently include the path in an aggregate total.

### Scenario: Preserve an organizer-specific localized bridge

Given a localized `values-*` path matches the broad translation-churn exclusion
but is explicitly assigned to a bridge responsibility

When the measurement runs

Then the bridge assignment takes precedence and the path is counted with that
responsibility rather than hidden by the broad exclusion.

### Scenario: Encounter a binary change

Given Git reports a changed path with binary `-/-` numstat values

When the path is classified

Then an explicitly excluded binary is reported as one excluded file with zero
line counts

And a binary path that would be counted fails with an actionable error.

### Scenario: Compare an unrelated history

Given the proposed upstream commit is not an ancestor of the target

When the measurement runs

Then it exits non-zero before reporting a baseline comparison

And it directs the maintainer to select or fetch the correct candidate upstream
commit rather than counting unrelated history.

## Data and state

The baseline is versioned repository documentation. It stores public source
paths, commit identities, ownership groups, and Git-derived totals only; it
stores no layout data, user data, credentials, or runtime application state.
There is no database migration, backup/restore effect, or production-state
rollback associated with this work.

## Permissions, privacy, and security

None. The command invokes read-only local Git operations and standard-library
Python; it makes no network request and introduces no Android permission,
telemetry, or external transport.

## Accessibility and localization

None. This is a developer-facing local command with stable plain-text output and
no user-facing Android UI.

## Acceptance criteria

- [x] AC-1: The metric, all-path classification, line measurement, and explicit
  exclusions are documented in an accepted assessment and machine-readable
  inventory.
- [x] AC-2: An exact upstream commit and exact main commit have a baseline that
  lists every counted path, including base resources and schemas, in exactly one
  bridge responsibility group and reports totals.
- [x] AC-2a: Exact reproduction verifies the recorded target, counted path-set,
  each group path-set and totals, project-owned totals, and explicit-exclusion
  totals; matching grand totals alone is insufficient.
- [x] AC-3: A standard-library-only command reproduces the exact baseline from
  local Git data without network access.
- [x] AC-4: Candidate comparison detects invalid ancestry, unowned bridges, and
  counted growth without treating the metric as a blind quality score.
- [x] AC-5: The record links existing upstream strategy, DESIGN seams,
  Deck-retirement evidence, and writer-admission audit rather than duplicating
  their architecture.
- [x] AC-6: No unexpectedly broad or unowned bridge exists at the captured
  baseline; future occurrences fail the command and must be split to an owner.

## Test oracle

| AC | Evidence |
|---|---|
| AC-1, AC-2 | `docs/assessment/upstream-patch-surface-baseline.md` and `upstream-patch-surface-baseline.json` |
| AC-3 | `python3 tools/repo-contract/measure_upstream_patch_surface.py --verify`, also required by `validate-repo-contract` CI |
| AC-4 | `python3 tools/repo-contract/test_measure_upstream_patch_surface.py` plus the tool's `--enforce-baseline` path; tests cover unowned path, invalid ancestry, and exit semantics |
| AC-5 | Assessment references to [upstream strategy][1], [DESIGN][2], Deck retirement, and writer audit |
| AC-6 | Successful `--verify` result with complete bridge ownership |

## Open questions

None. A future major Lawnchair upgrade may adopt a new upstream commit and
accepted baseline through its dedicated Epic/ADR process; it must not silently
overwrite this record. [1]

## Change history

- 2026-08-23: Accepted for #110 with the initial reproducible baseline.
- 2026-08-23: Review correction makes bridge ownership take precedence over
  broad localized-resource exclusions and defers binary handling until
  classification.

## References

[1]: ../../docs/engineering/upstream-strategy.md "Lawnchair Upstream Strategy"
[2]: ../../DESIGN.md "NunuLauncher System Design"
