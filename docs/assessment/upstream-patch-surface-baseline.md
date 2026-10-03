# Organizer upstream patch-surface measurement baseline

> Status: Accepted
> Issue: [#110](https://github.com/nunu1733/NunuLauncher/issues/110)
> Captured: 2026-08-23; recaptured 2026-09-29 at main `29476b10a0`（Issue #449 実装merge後。新bridge group `homeedit-edit-surface`（#449、ADR-0014案B。適用経路の削除passと `inspectCapture` seamを含む）、既存fork platform変更の整理（`fork-platform-preexisting`）、.agents/.codex agent設定のnon-production exclusion、`res/values-ja/strings.xml` のorganizer group帰属、DirectEditContract/InvariantDeviceProfile/HotseatRestoreHelper のmodel-reload group帰属を追加）; recaptured 2026-09-29 at branch `issue-450-spec-plan` `3bde871229`（Issue #450 実装。新bridge group `homeedit-edit-undo`（#450、ADR-0013契約5。世代付きundo record・純粋undo planner・availability verifier・undo executor/snackbar・recovery text mapper。逆操作は既収録のmodel-reload group `ModelWriter.java`/`DirectEditContract.java` への追加）。round 13 reviewで新src/ path `Folder.java` をhomeedit-edit-undo groupへ追加登録（+9/−1。bind時single-child cleanupの抑制ガード）。Acquisition history は末尾）; recaptured 2026-10-03 at branch `issue-507-duplicate-removal` `4e770fb196`（Issue #507実装 + #497分の遡及登録。`EditSurfaceDuplicateGroups.kt` をhomeedit-edit-surface groupへ追加、#497（PR #498）でmerge済みながらbridge帰属が欠けていた10 pathを新bridge group `new-app-destination` として遡及登録。`expected_measurement` を同headで再採択。Acquisition history は末尾）
> Upstream commit: `505dbc40e6154c05158b5d0271c45f6a885a411b`
> Main commit: `79c1a7db6f1909c248f3bd22365ee9a240357ce1`

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

The measurement begins with **every path** in `git diff --no-renames` between the
fixed upstream commit and a target commit. It then applies the classification
rules recorded in the machine-readable inventory. An exact bridge-group
assignment takes precedence over every exclusion, so an organizer-specific
localized resource remains visible. Known non-production changes to
production-capable paths — the root Gradle build files and pure upstream/Crowdin
translation churn under localized `values-*` directories — are excluded only as
**content pins**, honored while both sides of the patch still match their
recorded upstream and target Git blobs. An organizer PR that edits such a file,
or an upstream rebase that changes its upstream side, therefore fails closed for
ownership review instead of being silently absorbed. Remaining explicit
non-production exclusions are structural only — documentation, specification,
workflow, repository-tooling, test, generated, and vendored trees. A base
resource, schema, manifest, image asset, YAML configuration, localized string
change, or other non-excluded path is counted when it existed upstream, or when
a newly added file outside a project-owned module is explicitly assigned to one
bridge-responsibility group. For every counted text path, the measurement
reports Git's no-rename additions and deletions.

| Category | Treatment | Rationale |
|---|---|---|
| Existing Lawnchair/Launcher3 production path, including base resources and schemas | Counted and assigned to one bridge group | It is a direct upstream-file patch subject to rebase/conflict cost. |
| New bridge file outside a project-owned prefix | Counted and assigned to one bridge group | The bridge is project-specific but extends an upstream-owned source area. |
| New `lawnchair/src/app/lawnchair/organizer/` or `migration/` source file | Reported separately; not counted | These are the deep project-owned modules intended by the design. [2] |
| Structurally non-production trees (documents, specs, assessments, workflows, repository tooling, tests, generated/vendor churn) | Explicitly excluded and reported separately | They are not production upstream patch surface; the base `values/strings.xml` remains counted, while an organizer-specific localized path can be listed in its bridge group. |
| Known non-production change to a production-capable path (root `build.gradle`, `gradle/libs.versions.toml`) and known pure translation churn under localized `values-*` directories | Excluded only while both sides match the recorded upstream and target Git blobs (content pin) | Pattern-based exclusion would hide future production patches: a rebase that changes the upstream side, or a new organizer-specific translation in any locale. Any divergence fails the measurement until an owning Issue reviews and re-records it. |
| Explicitly excluded binary path | Excluded as one file with zero line counts | Git reports binary numstat as `-/-`; classification must still reach the exclusion rule. |
| Binary path that would otherwise be counted | Measurement failure | The metric has no portable line count for a counted binary; it must be explicitly excluded or reviewed as a different metric. |
| A changed non-excluded path with no bridge owner | Measurement failure | An unowned bridge must be split to an owning Issue before it can be accepted. |

The machine-readable inventory is
[`upstream-patch-surface-baseline.json`](./upstream-patch-surface-baseline.json).
It is the only place that enumerates paths and expected totals; this assessment
explains the policy and links the existing architectural evidence.

## Accepted baseline

The offline measurement at the exact commits above found **47 counted
upstream/bridge files**, with **3,993 additions** and **1,017 deletions**. It also
reports **91 excluded project-owned source additions** with 15,898 additions and
**309 explicitly excluded non-production paths** with 57,611 additions and 685
deletions. Both excluded categories are deliberately visible but are not folded
into the metric.

| Bridge responsibility | Counted files | Additions | Deletions | Supporting evidence |
|---|---:|---:|---:|---|
| Deck retirement | 20 | 288 | 853 | [ADR-0006][3], [retirement assessment][4] |
| Organizer UI and lock authoring, including base and organizer-specific localized resources | 8 | 1,679 | 9 | [design seams][2] and accepted specs #38/#52/#99 |
| Model reload and transaction gates | 8 | 1,349 | 71 | [writer admission audit][5] and its executable writer inventory |
| Layout schema and recovery, including downgrade schema and transaction ownership | 11 | 677 | 84 | [ADR-0003][6], [ADR-0004][7], [writer admission audit][5], [spec #118 audit][8] |
| **Total counted surface** | **47** | **3,993** | **1,017** | Machine-readable inventory |

This capture is a re-baseline at the current main head before the accepted
record is consumed by #100. Its growth over the earlier `4ec0eb3dc6` capture
(46 files, +3,912 / -987) is fully attributable to already-reviewed main merges:
the bounded `MODEL_WRITER` re-entry fix (#114), the minified preference
navigation fix (#121), and the deterministic SQLite migration transaction
ownership fix (#118/#122). The last change adds
`src/com/android/launcher3/model/DbDowngradeHelper.java` to the layout schema
and recovery group because the downgrade now executes inside its production
caller's transaction; no unowned bridge was introduced.

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
ancestor of the target. It then fails for a changed non-excluded path that lacks
a bridge owner, for a content-pinned file whose upstream or target bytes no
longer match the recorded pair at the measured commits, or when counted files,
additions, or deletions grow beyond this accepted baseline. `--verify`
additionally requires the exact recorded target, path-set digest, per-group
path-set digest and metrics, project-owned totals, and explicit-exclusion
totals; equal grand totals cannot mask path or responsibility substitution. A
smaller or equal candidate total passes the mechanical comparison, but reviewers
must still examine responsibility changes, deletions, and any semantic safety
impact.

After a candidate comparison is accepted — for example an upstream sync — the
content of the already-reviewed pinned paths can be re-recorded mechanically:

```bash
python3 tools/repo-contract/measure_upstream_patch_surface.py \
  --upstream <accepted-upstream-commit> --target <accepted-target-commit> --refresh-pins
```

This prints the `pinned_content_exclusions` fragment with fresh upstream/target
blob pairs for exactly the reviewed path set; it never adds or removes pins.
Adding, removing, or bridge-assigning a pinned path remains a manual, reviewed
inventory edit backed by an owning Issue.

Binary changes are retained until classification. A binary path matching an
explicit exclusion is reported as one excluded file with zero additions and
deletions because Git does not provide line counts; a binary path in a bridge
group fails instead of being silently counted.

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

- 2026-10-04: Recapture at branch `issue-508-visual-preview-item-exclusion` head `8af117b6fc` — Issue #508 implementation (confirmation before/after diagrams + per-item exclusion/replan). `ui/diagram/HomeDiagramParts.kt` (pure read-only diagram parts extracted from `EditSurfaceScreen.kt`: page surface, cell placement, item visuals; new project file, no selection/session/icon logic) joins `homeedit-edit-surface`, consumed by both the edit surface and the organizer confirmation diagrams; `EditSurfaceScreen.kt` shrinks by the extraction (−79/+25 net inside the group). All organizer-side changes stay under the `organizer/` project-owned prefix (coordinator, application preview, planning derivation, UI, tests). `expected_measurement` re-adopted at head `8af117b6fc` — owning Issue #508, accepted spec `specs/508-visual-preview-item-exclusion/spec.md` (tier H, Phase 1 re-review round 4 Clear; Phase 2 review rounds tracked on PR #515).

## References

[1]: ../engineering/upstream-strategy.md "Lawnchair Upstream Strategy"
[2]: ../../DESIGN.md "NunuLauncher System Design"
[3]: ../adr/0006-retire-deck-runtime.md "ADR-0006: Retire the Deck runtime"
[4]: ./issue-56-deck-runtime-retirement.md "Issue #56 Deck runtime retirement assessment"
[5]: ./issue-60-executor-writer-admission-audit.md "Issue #60 writer admission audit"
[6]: ../adr/0003-organizer-recovery-point-storage.md "ADR-0003: Organizer recovery-point storage"
[7]: ../adr/0004-organizer-lock-persistence.md "ADR-0004: Organizer lock persistence"
[8]: ../../specs/118-sqlite-migration-transaction-audit/spec.md "Spec #118 SQLite migration transaction audit"

## Acquisition history

- 2026-08-23: Initial capture at upstream `505dbc40e6` / main `79c1a7db6f` (Issue #110).
- 2026-09-29: Recapture at main `29476b10a0` — Issue #449 implementation merge. New bridge group `homeedit-edit-surface` (PR #476); `fork-platform-preexisting` recorded for measurement completeness; agent-config exclusions; resource attribution fixes.
- 2026-09-29: Recapture at branch `issue-450-spec-plan` head `fa21c753d4` (later refreshed at the review-resolution heads; the group composition is unchanged — test-only additions stay under the project-owned test tree) — Issue #450 implementation. New bridge group `homeedit-edit-undo` (7 files, +863 lines: generation-bound undo record, pure undo planner + availability verifier, undo executor/snackbar, recovery text mapper). The inverse operations are additions to the already-counted `model-reload-and-transaction-gates` files (`ModelWriter.java`, `DirectEditContract.java`), so no new src/ path enters the surface. Baseline delta: files +6, additions +1387, deletions +0 — owning Issue #450, accepted spec `specs/450-edit-undo/spec.md` (review round 3 Clear), responsibility recorded in the bridge group above; the alternative (reusing the existing direct-edit operations instead of minimal inverse additions) was rejected in the spec/plan Alternatives because undo needs explicit-placement restores, payload-carrying remove, and a two-row transaction that the #448 operations cannot express (ADR-0013 contract 4 "同経路に追加する最小の操作").
- 2026-09-30: Recapture at branch `issue-450-spec-plan` head `99dfff015e` — adoption of review round 13 Finding 2. `src/com/android/launcher3/folder/Folder.java` (+9/−1) is registered into `homeedit-edit-undo`. The round-12 resolution added a bind-path guard whose purpose is undo-window protection: upstream `Folder#bind` runs a single-child cleanup (`replaceFolderWithFinalItem`) that would flatten a just-created folder inside the undo snackbar's window, so the guard suppresses that cleanup for a folder carrying the direct-edit OPTIONS bit, now persisted in the existing options column (round 13) instead of the round-12 transient flag. A src/ bridge was unavoidable here: the cleanup executes inside upstream `Folder#bind` before any fork-side seam is reached, so deferring the check to fork code could only react after the flatten had already happened. This supersedes the earlier "no new src/ path enters the surface" note above. The round-12 transient `FolderInfo.java` field was fully reverted (the persisted OPTIONS bit replaces it), so that path no longer differs from upstream and is not registered. Baseline delta vs the previous accepted baseline (rounds 10–13 of #450): files +1, additions +127, deletions +1, of which `Folder.java` is the only new counted path (+9/−1); `ModelWriter.java`/`DirectEditContract.java` grew +19/−0 inside `model-reload-and-transaction-gates` — owning Issue #450, ADR-0013, responsibility recorded in the bridge group above.
- 2026-09-30: Recapture at branch `issue-450-spec-plan` head `3bde871229` — round 14 centralized the auto-collapse policy in `Folder#shouldAutoCollapseToIcon` (bind/closeComplete/onRemove share the marker check), growing `Folder.java` to +26/−3 vs upstream; `expected_measurement` re-adopted at head `3bde871229`; no new path.
- 2026-10-03: Recapture at branch `issue-507-duplicate-removal` head `4e770fb196` — Issue #507 implementation (duplicate-group confirmation in the edit surface) plus the retroactive registration of the Issue #497 paths. `EditSurfaceDuplicateGroups.kt` (pure projection + last-one guard, new project file) joins `homeedit-edit-surface`; the #451 grouping rule is generalized in place at `organizer/planning/DuplicateLaunchTargets.kt` (behavior-invariant delegation, still under the `organizer/` project-owned prefix). The ten paths merged with PR #498 (ADR-0015 destination policy: the AppDestination fork module, its preference/settings wiring, `LawnchairProcessInitializer`, `PreferenceManager`, and the minimal upstream writer surface `AddWorkspaceItemsTask` / `ItemInstallQueue` / `PersistedItemArray` under ADR-0013 contract 4) had no bridge assignment in this inventory — a pre-existing gap since the #497 merge that this measurement surfaced; they are registered retroactively as the new bridge group `new-app-destination`. `expected_measurement` is re-adopted at the same head. The #507-owned growth inside `homeedit-edit-surface` is +1 file (`EditSurfaceDuplicateGroups.kt`) and the touched-file deltas of `EditSurfaceScreen.kt` / `HomeEditSurfaceActivity.kt` / strings; all other group deltas since the previous accepted anchor belong to the already-reviewed #452/#497 merges — owning Issue #507, accepted spec `specs/507-duplicate-removal-proposal/spec.md` (tier M, review Clear).
