# Implementation Plan: reproducible organizer upstream patch-surface baseline

> Issue: [#110](https://github.com/nunu1733/NunuLauncher/issues/110)
> Spec: [spec.md](./spec.md)
> Status: implemented

## Current evidence

The accepted upstream strategy already requires project logic to remain in
`app.lawnchair` modules, asks that Lawnchair/Launcher3 changes be minimal
bridges, and requires sync PRs to record patch-surface change. [1] `DESIGN.md`
locates the organizer's deep modules and platform adapters. [2] The existing
writer-admission audit supplies an offline, standard-library-only allowlist
precedent for source-boundary checks, but it deliberately inventories only
`favorites`/database writers. [3]

The Issue #110 baseline is captured at upstream
`505dbc40e6154c05158b5d0271c45f6a885a411b` and main
`4ec0eb3dc692eadf108c512df5de3cb1607cf1f5`. The captured metric reports 45
counted upstream/bridge files, 3,908 additions, and 987 deletions; 91
project-owned additions and 300 explicitly excluded non-production paths are
reported separately. The exact path sets, group ownership, and totals are
recorded in the machine-readable baseline file.

## Design

### Modules and interfaces

| Area | Interface / responsibility | Boundary kept out of the tool |
|---|---|---|
| `measure_upstream_patch_surface.py` | Read local Git diff and classify every changed path through explicit exclusions, project ownership, and bridge responsibility | No Android build, network request, worktree mutation, or product-runtime behavior |
| `upstream-patch-surface-baseline.json` | Own the exact commits, path groups, exclusions, and expected numeric totals | No duplicated architectural rationale or release verdict |
| `upstream-patch-surface-baseline.md` | Explain the metric, comparison policy, and links to evidence | No duplicated Deck/writer/design audit |
| Tool regression test | Fix aggregation, ownership, and growth semantics | No second production measurement seam |

The tool begins with every changed repository path. It removes only explicit
non-production exclusions, then counts a path that existed at the selected
upstream commit, or a new file outside a project-owned prefix that is explicitly
assigned to one bridge group. Base resources, schemas, manifests, and other
non-excluded files therefore cannot be skipped by a source-extension allowlist.
The tool separately reports additions under the project-owned organizer and
migration prefixes. Every other changed path requires exactly one bridge
responsibility. This avoids hiding a widened bridge while preserving the design's
intended deep modules. [2]

### Data flow

The tool resolves the local upstream and target commits, verifies that upstream
is an ancestor of target, and reads `git diff --no-renames` name-status and
numstat output for every changed path. It classifies paths using the checked-in
inventory, aggregates counts by responsibility, prints the report, and compares
totals to the accepted baseline. Invalid ancestry or unowned paths fail
immediately; `--verify` fails for any exact baseline difference, including path
set, group assignment, project-owned totals, and explicit-exclusion totals; and
`--enforce-baseline` fails for positive counted growth.

### Alternatives rejected

| Alternative | Decision | Reason |
|---|---|---|
| Count all repository diff lines | Rejected | Documentation, tests, tooling, and generated/vendor churn would obscure production upstream maintenance risk. |
| Count organizer additions with upstream patches | Rejected | The project is designed to hold product behavior in these deep modules; their volume is relevant but not an upstream patch. [2] |
| Use only a prose table | Rejected | It cannot reliably reject a newly unowned bridge or reproduce the same totals. |
| Treat a smaller count as an automatic pass | Rejected | The upstream strategy requires intent review; changed-line volume alone cannot assess safety. [1] |

## Change set

| Area | Intended change | Why here |
|---|---|---|
| `tools/repo-contract/measure_upstream_patch_surface.py` | Add offline measurement, ownership validation, and candidate comparison | Repository-contract tooling already hosts deterministic source-boundary checks. [3] |
| `tools/repo-contract/test_measure_upstream_patch_surface.py` | Add regression tests for resource/schema classification, exact baseline, ownership, ancestry, and exit semantics | Protect the metric from silent classification drift. |
| `docs/assessment/upstream-patch-surface-baseline.json` | Add exact baseline commits, path groups, exclusions, and expected totals | One machine-readable source of truth for the numerical inventory. |
| `docs/assessment/upstream-patch-surface-baseline.md` | Add accepted methodology, baseline table, and review procedure | NFR-010 evidence and maintainer instructions. |
| `docs/engineering/upstream-strategy.md` | Link the accepted measurement from sync policy | Preserve the existing policy as the architectural source of truth. |
| `docs/product/mvp-release-readiness.md` | Consume the #110 evidence for NFR-010 and remove it from outstanding blockers | Keep the release matrix synchronized with accepted evidence. |
| `specs/110-upstream-patch-surface-baseline/` | Add accepted behavior specification and this plan | Meet Issue/spec-driven workflow requirements. |

## Migration and recovery

No Android schema, migration, recovery point, backup, restore, permission, or
release rollback behavior changes. The files are repository documentation and
local analysis tooling. A faulty candidate comparison affects only its exit
status; maintainers can return to the accepted target command, and a future
baseline update remains a reviewed documentation/tooling change.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-1, AC-2 | JSON syntax plus exact accepted assessment table | `python3 -m json.tool docs/assessment/upstream-patch-surface-baseline.json` and document review |
| AC-3, AC-6 | Exact local baseline regeneration with complete ownership | `python3 tools/repo-contract/measure_upstream_patch_surface.py --verify` |
| AC-4 | Regression tests of aggregation, ownership, and growth rule | `python3 tools/repo-contract/test_measure_upstream_patch_surface.py` |
| Existing repository contract | Link/syntax and project-file validation | `python3 tools/repo-contract/validate_repo_contract.py` |
| Repository-contract CI integration | Tool test and exact baseline regeneration run in `validate-repo-contract` | `.github/workflows/ci.yml` |
| Existing repository contract self-test | Validator regression suite | `python3 tools/repo-contract/test_validate_repo_contract.py` |
| Whitespace | No malformed patch whitespace | `git diff --check` |

## Documentation updates

- [x] spec status/history
- [ ] CONTEXT.md (no domain-language change)
- [ ] DESIGN.md (no module/system-structure change)
- [ ] ADR (no high-cost design decision beyond accepted upstream policy)
- [ ] AGENTS.md (the command is an Issue #110 evidence command, not a universal build prerequisite)
- [x] Upstream strategy
- [x] MVP release-readiness matrix

## Execution checklist

- [x] Current baseline and adjacent evidence inspected.
- [x] Missing behavior reproduced as a distributed/non-quantitative inventory gap.
- [x] Minimal offline measurement and baseline inventory completed.
- [x] No product migration or recovery surface changed.
- [ ] Full relevant verification completed.
- [ ] Issue/PR evidence and remaining risks recorded.

## References

[1]: ../../docs/engineering/upstream-strategy.md "Lawnchair Upstream Strategy"
[2]: ../../DESIGN.md "NunuLauncher System Design"
[3]: ../../docs/assessment/issue-60-executor-writer-admission-audit.md "Issue #60 writer admission audit"
