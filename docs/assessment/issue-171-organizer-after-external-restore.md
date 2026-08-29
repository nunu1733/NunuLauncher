# Assessment: Issue #171 — Organizer after external workspace restore (A4 checkpoint / recovery semantics)

Status: `implemented` (investigation complete; focused follow-up split to a separate issue)
Date: 2026-08-29
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/171
Related: #167 / #168 / PR #169, #164, #166

Raw evidence (not committed, per issue non-goals): `/tmp/nova171/` — emulator
screenshots, full logcat for each restore pass, pulled `launcher_6_5_5.db`,
`organizer_recovery.db`, and the full organizer diagnostics journal. Only
hashes, counts, opaque IDs, and lifecycle states are quoted here.

## Verdict

1. **The reported A4 `CHECKPOINT_CREATE_FAILED` does not reproduce on post-#169 `main`.**
   The exact reported scenario — Organizer recovery points `VERIFIED` in the
   store, workspace externally replaced by one Nova restore, restart
   reconciliation classifying the old points against the new workspace as
   `NEITHER`, then a new Organizer apply — was executed on post-#169 `main`
   twice in the "stale point present" shape and both applies passed
   A4 → A6 → A8 (`APPLY_VERIFIED`) with new points created cleanly. The
   pre-#169 physical observation must be treated as secondary to the pre-#169
   two-pass restore environment (#167/#168), not as an independent
   checkpoint/store defect.
2. **`VERIFIED + NEITHER` restart reconciliation is `SilentAdvance`; the point
   stays `VERIFIED` and restorable.** Observed three times on-device
   (`RESTART_RECONCILED priorLifecycle=VERIFIED classification=NEITHER_RECOGNIZED
   resultingLifecycle=VERIFIED` for three distinct points). This matches the
   source fact in the issue (`RestartReconciler` line 242) and is retained as
   the accepted semantics (decision below).
3. **No retention/collision mechanism exists by which stale `VERIFIED` points
   could block checkpoint creation.** Source analysis of
   `RetentionPolicy.planCreate` (records ≤ cap 3; VERIFIED is not active/READY,
   so it is "resolved" capacity and is evictable oldest-first) and random
   point IDs with 4 retries (`ApplyProtocol.MAX_POINT_ID_ATTEMPTS`) leave no
   path from "one or two stale VERIFIED records" to
   `PointIdCollision` / `CreateFailed` / `StoreUnavailable`.
4. **New follow-up candidate (not the A4 defect):** the manual-organization
   input-unavailable surface produces **no diagnostics** — the readiness
   reason is dropped silently (UI state only, never journaled or logged), and
   one post-restore process exhibited repeated capture unavailability that
   could not be explained after the fact. Tracked in the follow-up issue.

## Environments

| | Value |
|---|---|
| Build | `main` @ `afb7618144` (post-#169 merge; production code identical to PR #169 head `1c64b5f4`), `assembleLawnWithQuickstepGithubDebug`, APK sha256 `26e35f54…9cc` |
| Emulator | AVD `nunu_qpr2_api36_1` (Pixel 6-class, Google APIs), Android 16 / API 36, `emulator-5554` |
| Package | `app.lawnchair.debug` (`15.Dev.(afb7618)`) |
| Nova backup | same file as #167/#168 (`backup.novabackup`, md5 `af49202e1b2b2638056481975ef03c45`; identical to the Pixel's `2026-08-28_15-49.novabackup`) |
| Default grid | `launcher_5_4_4.db`, 15 default rows |
| Backup grid | 6 rows × 5 cols × 5 hotseat → target `launcher_6_5_5.db` |
| Physical device | Pixel 9a (`56231JEBF08674`, API 37) available but not needed — the emulator reproduction satisfied the issue's "fix verification runs primarily on emulator" goal |

A temporary diagnosis-only log line (reason code only, no PII) was added to
`ManualOrganizationRun` for one run and removed before this investigation
closed; the working tree at the time of writing is exactly `main` @
`afb7618144` (verified by `git diff` empty + `spotlessCheck` green).

## Stage A — post-#169 prerequisite (one Nova restore → authoritative)

Fresh install of the `afb7618` debug build (fresh install, default workspace
15 rows in `launcher_5_4_4.db`), then one Nova restore:

- `IDP recompute: dbFile launcher_5_4_4.db -> launcher_6_5_5.db` then
  `applyGridInfo: dbFile launcher_6_5_5.db -> launcher_6_5_5.db (grid 6x5 h5)`
  — the #169 authoritative-application trace.
- `migrateGridIfNeeded: no grid migration needed`; **no** default-workspace
  load after the self-restart (`AutoInstalls` / default-layout lines: 0).
- Active DB `launcher_6_5_5.db` holds the converted import (13 rows after
  loader sanitation; the full 128-row staged import is reduced at load
  because most backup packages are not installed on the emulator — the same
  converter/loader semantics recorded in #167's secondary findings).
- Visible workspace = imported layout. Recovery store fresh (0 points,
  0 tombstones, `user_version` 2).

**Prerequisite proven.**

Note: `LoaderTask`'s `loadWorkspace: loading default favorites` line is an
**unconditional** log (`LoaderTask.java:482` in this tree) and is NOT a
default-workspace-load signature. An initial reading of this session's logs
as "the import was lost and defaults were reloaded" was wrong and was
retracted after source check; the DB contents never regressed to a default
workspace at any point in the sequences below.

## C-matrix outcomes (documented smaller equivalent set)

| Case | Prior VERIFIED point | External restore | Outcome on post-#169 `main` |
|---|---|---|---|
| C1 equivalent | no | Nova (grid change 5_4_4→6_5_5) | Organizer run from clean store: `CHECKPOINTED(A4) → APPLY_COMMITTED(A6) → APPLY_VERIFIED(A8)`, point `20875cd7…` VERIFIED |
| C3 (first, contaminated) | yes (1 point) | Nova (restore over existing 6_5_5, workspace then drifted via session's own force-stops) | Organizer apply: `CHECKPOINTED(A4) → APPLY_COMMITTED(A6) → APPLY_VERIFIED(A8)`, new point `e900ce24…` VERIFIED alongside the stale one (2 points, 0 tombstones) |
| C3 (clean) | yes (2 stale points) | Nova (restore over existing 6_5_5, no session interventions) | Restart reconciliation: both stale points `VERIFIED + NEITHER_RECOGNIZED → VERIFIED`; Organizer apply in the post-restore process: `CHECKPOINTED(A4) → APPLY_COMMITTED(A6) → APPLY_VERIFIED(A8)`, new point `071d30bc…` VERIFIED (3 points, 0 tombstones) |
| C2 | yes | none | Not run verbatim; subsumed: C3 exercised the stricter condition (prior point **and** external replacement) and passed; source analysis shows a prior VERIFIED point alone reduces free capacity from 3→2, still `Allowed` in `planCreate` |
| C4 | yes | ordinary Lawnchair restore | Not run: its purpose was to localize the trigger of the original A4 failure. The failure no longer occurs, so no trigger exists to localize (issue: "Only add another dimension if evidence requires it") |

**Answer to the C-matrix question:** on post-#169 `main`, `CHECKPOINT_CREATE_FAILED`
has **no** necessary condition among {prior VERIFIED point, external
replacement, Nova specifically} — none of them blocks A4.

## Stage C — recovery-store evidence around the failing-apply attempts

Before every apply attempt above, the store state was:

- `recovery_points`: only `VERIFIED` records (1 → 2 across the session);
  `lifecycle` canonical int 4 = VERIFIED; `created_at_ms` consistent.
- `recovery_tombstones`: **0 rows at all times** — the #166 three-RESTORED-
  tombstones admission lockout is definitively ruled out (nothing to evict,
  no capacity pressure: max 2 non-final records vs cap 3).
- `user_version` 2; main + sidecar files present; every `availability()`
  probe READY (all applies passed A2 and reached A4).
- Checkpoint phase reached and passed: the store accepted insert → close/
  reopen readback → CREATING→READY advance → READY readback → projection
  publication, i.e. every phase `RecoveryStore.checkpoint()` can fail in was
  exercised successfully.

Journal (`organizer_diagnostics.journal`, 40 events by end of session) key
entries, all with opaque IDs only:

```text
run f44c6d89… CHECKPOINTED A4 → APPLY_COMMITTED A6 → APPLY_VERIFIED A8
  {preserve:4, update:9, insert:2}   (C1-equivalent, clean store)
RESTART_RECONCILED subject=f44c6d89… prior=VERIFIED classification=NEITHER_RECOGNIZED result=VERIFIED
run 4533b587… CHECKPOINTED A4 → APPLY_COMMITTED A6 → APPLY_VERIFIED A8
  {preserve:4, update:8, insert:2}   (C3 first, stale point present)
RESTART_RECONCILED subject=4533b587… prior=VERIFIED classification=NEITHER_RECOGNIZED result=VERIFIED
run 435dd4d6… CAPTURED → PLANNED captured=12 moved=8 preserved=4 → PREVIEWED → USER_CANCELLED
run 7fb26eda… CAPTURED → PLANNED captured=12 moved=8 preserved=4 → PREVIEWED → USER_CONFIRMED
  CHECKPOINTED A4 → APPLY_COMMITTED A6 → APPLY_VERIFIED A8
  {preserve:4, update:8, insert:2}   (C3 clean, post-restore process)
```

## The external workspace vs the old VERIFIED points

- The second Nova restore re-imports deterministically: the workspace tuple
  set (`_id, itemType, container, screen, cellX, cellY`) after restore was
  **byte-identical** to the post-first-restore import (13 rows, same `_id`s).
- Nevertheless, restart reconciliation classified the stale VERIFIED points
  as `NEITHER_RECOGNIZED` — the current workspace digest matches neither the
  points' `preDigest` nor their `intendedDigest`. The classification digest
  covers more than the favorites tuple set (manifest context resources,
  page normalization, etc.), so tuple equality does not imply digest
  equality. This is worth knowing when reasoning about "the restore put the
  workspace back to the point's pre-state": **it does not, by digest**.
- Reconciliation outcome is `SilentAdvance` in every observation; points
  remain `VERIFIED`, restorable via the existing recovery surface, and
  evictable by retention (24 h age, count cap 3).

## Stage D — semantic decision: VERIFIED points after authoritative external replacement

**Decision: keep current behavior — a `VERIFIED` recovery point remains
stored and restorable after an authoritative external workspace replacement;
no invalidation and no acknowledgement gate is introduced.** Restart
reconciliation keeps `VERIFIED → SilentAdvance` unchanged.

Grounding (against ADR-0003 / current recovery guarantees):

1. **Safety does not depend on the point being current.** Recovery restores a
   validated snapshot through an explicit, preconditioned write-set in one
   Launcher DB transaction, with context-mismatch rejection before `RESTORING`
   (ADR-0003 ordering; #155/ADR-0008 context). Restoring a stale pre-state is
   therefore safe regardless of what the external restore installed — the
   guarantees the point carries are transactional, not semantic.
2. **External replacement is an explicit user action**, and the recovery
   surface is equally explicit and user-initiated; a user who restores a
   stale "previous layout" gets exactly that verified layout. No silent
   behavior change is introduced by keeping the point.
3. **An invalidation contract would need a new external-restore seam.** There
   is no existing notification path from {Nova converter, Lawnchair ZIP
   restore, Launcher3 restore, grid migration, raw-file restore} to the
   recovery store. Creating one couples every replacement mechanism to the
   organizer for a scenario with no demonstrated user harm, and would weaken
   recoverability (invariant 11 in `DESIGN.md`) in the window where the
   external restore went wrong — precisely when the old point is most
   valuable.
4. **Retention already bounds staleness** (24 h age, ≤3 points, VERIFIED
   evictable oldest-first at creation time).

The alternative "retire/invalidate on authoritative external restore" was
rejected for the seam-coupling reason above; "keep stored but ineligible
until acknowledged" was rejected because it adds a user-visible gating state
with no safety benefit (the restore path is already fail-closed) and no
evidence of user confusion in the reproduced scenario. If product evidence
for user surprise emerges, that is a product decision to be taken spec-first,
not a defect in the current protocol.

## Follow-up issue (split from this investigation)

- **Organizer input-unavailability is undiagnosable in production**: when
  `composeFullOrganization()` returns `NotReady(reason)`, the reason is placed
  in UI state only — never journaled, never logged. During this session the
  manual organizer showed "The current layout or required organization
  information is unavailable" in a post-restore process (twice, with the
  model loaded) and the cause could not be established afterwards; a later
  capture attempt in an equivalent state succeeded, so the episode is
  one-off and unexplained. This session also confirmed the same surface
  fails legitimately when the launcher model has not loaded (capture ran
  before any `LoaderTask` completed). The follow-up owns: (a) surfacing the
  readiness reason through the existing diagnostics contract (reason code
  only), (b) reproducing/understanding the one-off post-restore capture
  unavailability. Link: https://github.com/nunu1733/NunuLauncher/issues/172

Secondary observations (recorded, not owned here):

- Import content variance across restore passes: the backup's At a Glance
  placeholder row (itemType 6, no intent/provider) was present after
  grid-change restores but dropped by the loader after a restore over the
  same grid; logcat shows `App widget provider info is null. PackageName=
  app.lawnchair.debug appWidgetId-262/-263` around those loads. Converter/
  loader widget semantics adjacent to #168's scope; worth a look when #168's
  Open item A is revisited.

## Acceptance-criteria mapping (Issue #171)

- Post-#169 prerequisite proven (one restore → authoritative workspace):
  **yes** — Stage A section.
- Original sequence re-run from clean recovery-store state with exact build/
  environment evidence: **yes** — Environments + C1-equivalent.
- C1-C4 (documented smaller equivalent set) establishing the necessary
  condition: **yes** — table above; answer "neither" (no condition blocks A4).
- Repeatable emulator reproduction, or non-reproducibility with a defensible
  boundary: **yes, non-reproducible** — the scenario was executed twice in
  the stale-point shape on post-#169 `main` and passed; source analysis shows
  no mechanism; the pre-#169 observation is attributed to the pre-#169
  two-pass environment.
- Recovery-store state before the failing apply captured sufficiently to
  rule #166 in/out: **yes** — 0 tombstones at all times, ≤2 records vs cap 3.
- Exact `RecoveryStore.checkpoint()` failure phase/cause if A4 still fails:
  **N/A** — A4 no longer fails; every checkpoint phase passed.
- Relationship between external workspace and old VERIFIED point classified
  and correlated with restart reconciliation: **yes** — `NEITHER_RECOGNIZED`
  (despite tuple-identical re-import), `SilentAdvance`, point retained.
- Conscious recovery-point semantic decision recorded: **yes** — Stage D.
- Implementation work split to focused owning Issue(s); no silent semantic or
  fail-closed change: **yes** — follow-up #172 (diagnostics/observability);
  no production semantics changed (the one temporary log line was removed).
- Fix verification runs primarily on emulator: **yes** — all evidence above
  is emulator-only; the physical Pixel was not needed.

## Non-goal compliance

- #168's one-restore fix was not reopened; the prerequisite was verified, not
  re-fixed.
- No #166 admission/tombstone policy was implemented.
- Exact apply verification, recovery-store durability, and fail-closed
  behavior were not weakened; no production code changed in this
  investigation (net diff against `main` is empty).
- No public diagnostics schema was added.
- `VERIFIED + NEITHER` was not treated as valid-or-invalid before the
  semantic review; the decision above is grounded in ADR-0003.
- The pre-#169 physical observation was not treated as proof of a post-#169
  root cause.
