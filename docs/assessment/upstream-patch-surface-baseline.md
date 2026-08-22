# Organizer upstream patch-surface measurement baseline

> Status: Accepted
> Issue: [#110](https://github.com/nunu1733/NunuLauncher/issues/110)
> Captured: 2026-08-23
> Upstream commit: `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Main commit: `4ec0eb3dc692eadf108c512df5de3cb1607cf1f5`

## Purpose

This record closes the quantitative evidence gap for **NFR-010**. The organizer
must keep its product logic behind deep project modules and confine changes in
Lawnchair/Launcher3 code to small, owned bridges. The architectural rule and the
upstream-sync requirement already exist in the [upstream strategy][1] and
[system design][2]; this assessment supplies one reproducible measurement and
one accepted comparison point without restating those documents.

> **Patch surface is a maintenance-risk inventory, not a quality score.** A
> decrease does not by itself prove a safer design, and a justified increase is
> not automatically a regression. Any increase is instead a mandatory review
> signal: its owning Issue, bridge responsibility, and alternative analysis must
> be recorded before a new accepted baseline is adopted.

## Metric and exclusions

The counted patch surface is the set of changed production `.java` and `.kt`
paths under `src/`, `lawnchair/src/`, and `quickstep/src/` between the fixed
upstream commit and a target commit. A path is counted when it existed in the
upstream commit, or when a newly added file outside a project-owned module is
explicitly assigned to a bridge-responsibility group. For every counted path,
the measurement reports Git's no-rename additions and deletions.

| Category | Treatment | Rationale |
|---|---|---|
| Existing Lawnchair/Launcher3 source path | Counted and assigned to one bridge group | It is a direct upstream-file patch subject to rebase/conflict cost. |
| New bridge file outside a project-owned prefix | Counted and assigned to one bridge group | The bridge is project-specific but extends an upstream-owned source area. |
| New `lawnchair/src/app/lawnchair/organizer/` or `migration/` source file | Reported separately; not counted | These are the deep project-owned modules intended by the design. [2] |
| Tests, documents, specs, assessments, workflows, repository tooling, generated files, and vendored files | Excluded | They do not constitute production upstream patch surface. |
| A changed production source path with no bridge owner | Measurement failure | An unowned bridge must be split to an owning Issue before it can be accepted. |

The machine-readable inventory is
[`upstream-patch-surface-baseline.json`](./upstream-patch-surface-baseline.json).
It is the only place that enumerates paths and expected totals; this assessment
explains the policy and links the existing architectural evidence.

## Accepted baseline

The offline measurement at the exact commits above found **42 counted
upstream/bridge files**, with **3,673 additions** and **977 deletions**. It also
reports **91 excluded project-owned source additions** with 15,898 additions,
which are deliberately visible but not folded into the metric.

| Bridge responsibility | Counted files | Additions | Deletions | Supporting evidence |
|---|---:|---:|---:|---|
| Deck retirement | 20 | 284 | 853 | [ADR-0006][3], [retirement assessment][4] |
| Organizer UI and lock authoring | 5 | 1,447 | 0 | [design seams][2] and accepted specs #38/#52/#99 |
| Model reload and transaction gates | 8 | 1,320 | 71 | [writer admission audit][5] and its executable writer inventory |
| Layout schema and recovery | 9 | 622 | 53 | [ADR-0003][6], [ADR-0004][7], and [writer admission audit][5] |
| **Total counted surface** | **42** | **3,673** | **977** | Machine-readable inventory |

The retained Deck evidence identifies the historical package-event and artifact
paths that were removed or constrained; the current bridge inventory therefore
links to that evidence rather than recreating an architectural audit. [3] [4]
The writer allowlist remains a different, complementary control: it detects
unowned `favorites`/Launcher database writers, while this metric tracks the
broader upstream modification boundary. [5]

## Reproduction and comparison

The commands use only the local Git object database and Python standard library.
They make no network request and do not modify the worktree.

```bash
# Reproduce this exact accepted measurement.
python3 tools/repo-contract/measure_upstream_patch_surface.py --verify

# Exercise the measurement's aggregation and ownership rules.
python3 tools/repo-contract/test_measure_upstream_patch_surface.py

# Compare an organizer branch or rebase candidate with a locally available
# upstream ancestor. A positive counted delta exits non-zero for review.
python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --upstream <candidate-upstream-commit> --target HEAD --enforce-baseline
```

The final command first rejects a candidate upstream commit that is not an
ancestor of the target. It then fails for a changed production path that lacks
a bridge owner, or when counted files, additions, or deletions grow beyond this
accepted baseline. A smaller or equal total passes the mechanical comparison,
but reviewers must still examine responsibility changes, deletions, and any
semantic safety impact.

## Rebase and organizer-PR review procedure

For an upstream sync, retain the accepted JSON inventory as the comparison
source, run the candidate command after the upstream commit is locally
available, and record the report in the sync PR. This operationalizes the
upstream strategy's requirement to record project patch-surface change at every
sync. [1] If a conflict requires a new or wider bridge, create or link an owning
Issue, update the accepted spec/plan, and add the exact path to one responsibility
group only after review.

For an organizer PR, run the same command against its target before merge. A
new project-owned module may appear in the separately reported total; it does
not need to be minimized for this metric. A change to an upstream/bridge path,
or any newly added source file outside the two project-owned prefixes, must be
classified in the JSON inventory. The measurement's failure is intentionally a
request for ownership review, not permission to refactor unrelated code merely
to reduce a number.

## NFR-010 disposition

This baseline gives the release-readiness matrix a single exact measurement,
complete bridge responsibility inventory, reproducible offline command, and
explicit review rule. No unexpectedly broad or unowned bridge was found at the
captured main commit; such a path would have caused the command to fail rather
than being silently absorbed.

## References

[1]: ../engineering/upstream-strategy.md "Lawnchair Upstream Strategy"
[2]: ../../DESIGN.md "NunuLauncher System Design"
[3]: ../adr/0006-retire-deck-runtime.md "ADR-0006: Retire the Deck runtime"
[4]: ./issue-56-deck-runtime-retirement.md "Issue #56 Deck runtime retirement assessment"
[5]: ./issue-60-executor-writer-admission-audit.md "Issue #60 writer admission audit"
[6]: ../adr/0003-organizer-recovery-point-storage.md "ADR-0003: Organizer recovery-point storage"
[7]: ../adr/0004-organizer-lock-persistence.md "ADR-0004: Organizer lock persistence"
