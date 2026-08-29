# Assessment: Issue #171 — Organizer after external workspace restore (A4 checkpoint / recovery semantics)

Status: `implemented` (investigation complete; focused fix split to a separate Bug Issue)
Date: 2026-08-29 (rev 2 — device root cause added; rev 1's "not reproducible post-#169" conclusion retracted)
Investigation issue: https://github.com/nunu1733/NunuLauncher/issues/171
Related: #167 / #168 / PR #169, #164, #166, follow-up #172, fix issue #174

Raw evidence (not committed, per issue non-goals): `/tmp/nova171/` — emulator
and Pixel screenshots, full logcat per restore pass, pulled
`launcher_*.db`/`organizer_recovery.db` copies, organizer diagnostics
journals (device export + debug journal). Only hashes, counts, opaque IDs,
lifecycle states, and schema-level exception messages are quoted here.

## Rev 2 verdict

The reported A4 `CHECKPOINT_CREATE_FAILED` **is real on current `main` and
was reproduced and root-caused on the physical device**. Rev 1's conclusion
("not reproducible post-#169") was drawn from fresh-install emulator runs
whose workspace is small enough to hide the failure; it is **retracted**.
What stands from rev 1 is the emulator evidence set, which now serves as the
small-workspace control that isolates the load-bearing variable.

**Root cause (statement/phase level, instrumented device reproduction):**

1. `RecoveryStore.checkpoint()` encodes the capture manifest twice into the
   recovery record row (`pre_manifest` + `intended_manifest`, identical at
   CREATING). On the device the post-Nova-restore workspace is a 125-row
   import whose manifest is ~1.12 MB per blob → the single record row is
   ~2.25 MB.
2. The CREATING insert **commits successfully** (the row is durable).
3. The protocol then closes and reopens the helper and reads the row back
   (`readRecord` → `SELECT * … WHERE point_id = ?`). The row exceeds the
   2 MB SQLite `CursorWindow` → `android.database.sqlite.SQLiteBlobTooBigException:
   Row too big to fit into CursorWindow requiredPos=0, totalRows=1`.
4. The exception is a `SQLException`, collapsed by
   `RecoveryStore.checkpoint()` into `CheckpointResult.CreateFailed` →
   `ApplyProtocol` maps it to `PRE_WRITE_REJECTED.CHECKPOINT_CREATE_FAILED`
   at A4. No Launcher layout mutation occurs (pre-write rejection, as
   reported).

**Store poisoning (follow-on, also confirmed by instrumentation):**

5. Because the CREATING row is committed and unreadable, the process's
   inspection fence finishes `OUTCOME_UNCERTAIN` → DIRTY; every subsequent
   apply in that process is rejected at A4 with
   `PRE_WRITE_REJECTED.RECOVERY_STORE_UNAVAILABLE` (`beginMutation
   fence=DIRTY_OR_UNKNOWN`) — matching the reported second symptom.
6. After a designed restart, reconciliation's
   `SELECT * FROM recovery_points ORDER BY created_at_ms ASC` throws the same
   `SQLiteBlobTooBigException` (`requiredPos=1, totalRows=2`): the whole
   non-final record list is unbuildable, so **even the small, healthy
   VERIFIED record is not reconciled**, and the huge CREATING row cannot be
   advanced, pruned, or tombstoned by any protocol path (every path reads
   the row first). The store stays poisoned until the row is removed
   out-of-band or the format changes.

This is a checkpoint/store capacity defect independent of Nova semantics:
any workspace whose capture manifest exceeds ~1 MB per blob (real-device
row counts with icon-bearing rows) crosses the 2 MB `CursorWindow`.
Nova-restore-after-organizer is simply the reported path that reaches it.

## Environments

| | Device (authoritative repro) | Emulator (control) |
|---|---|---|
| Hardware | Pixel 9a (`56231JEBF08674`), Android 17 / API 37 | AVD `nunu_qpr2_api36_1`, Android 16 / API 36 |
| Builds | release `app.lawnchair` `15.Dev.(#18)` (versionCode 1500020300, APK sha256 `32ab0cd1…b5a`, installed 2026-08-29 21:40 — user-installed); debug `app.lawnchair.debug` `15.Dev.(afb7618)` + checkpoint instrumentation for the reproduction | debug `15.Dev.(afb7618)` (clean, then + instrumentation for one capture-reason run) |
| Default grid / workspace | `launcher_5_4_4.db` (4 cols × 5 rows), 17 default rows | `launcher_5_4_4.db` (5×4×4), 15 default rows |
| Nova backup | `2026-08-28_15-49.novabackup` (md5 `af49202e…fc45`) | identical file |
| Post-restore workspace | 6×5×5 grid `launcher_6_5_5.db`, **125-row import, 3 pages** (real apps + icons) | 6×5×5 grid, **12–13 rows** after loader sanitation (most backup apps not installed) |
| Recovery record size | pre+intended manifest = **2,247,054 bytes** (~1.12 MB each) | 211,560–233,569 bytes total |

`main` @ `afb7618144`; production code identical to PR #169 head. The
checkpoint instrumentation (phase + exception class/message + opaque point ID
only, per the issue's Stage C contract) lived on the investigation branch
`invest/issue-171-checkpoint-cause`, was used only for the device
reproduction, and is not part of PR #173 or `main`. The device was restored
after the reproduction: instrumented debug build uninstalled, default home
returned to Nova; the user-installed release build and its (poisoned)
app state were left untouched.

## Device evidence chain

### Release-build diagnostics export (user's run, `Download/organizer_diagnostics.jsonl`, 28 events)

```text
run ef50d3a4… MANUAL_FULL  RUN_STARTED → CAPTURED → PLANNED → PREVIEWED
  → USER_CONFIRMED → CHECKPOINTED A4 (point 7ec1c2bf…) → APPLY_COMMITTED A6
  → APPLY_VERIFIED A8 {preserve:12, update:5, insert:1}
RESTART_RECONCILED prior=VERIFIED classification=NEITHER_RECOGNIZED result=VERIFIED
run 5c06efb5… ONBOARDING_PROPOSAL … USER_CONFIRMED
  → CHECKPOINT_REJECTED A4 {family: PRE_WRITE_REJECTED, code: CHECKPOINT_CREATE_FAILED}
run ec5aa47e… MANUAL_FULL … USER_CONFIRMED
  → APPLY_REJECTED A4 {code: RECOVERY_STORE_UNAVAILABLE}   (+12 s, same process)
run 1865bc9e… MANUAL_FULL … USER_CONFIRMED
  → APPLY_REJECTED A4 {code: RECOVERY_STORE_UNAVAILABLE}
```

This is exactly the reported scenario: one Nova restore completes normally
(applyGridInfo trace, import authoritative), the first post-restore Organizer
apply (the onboarding proposal) dies at A4 `CHECKPOINT_CREATE_FAILED` before
any mutation, and the follow-ups degrade to `RECOVERY_STORE_UNAVAILABLE`.

### Instrumented debug reproduction (same device, same sequence)

Fresh debug install → manual organize (A4→A6→A8 verified, point `05ccd92f…`,
record 5,193+4,830 bytes) → one Nova restore (one-pass authoritative, grid
5_4_4→6_5_5) → restart → onboarding proposal → confirm → apply:

```text
checkpoint=64a4e6db… phase=DB_OPEN ok
checkpoint=64a4e6db… phase=RETENTION_ALLOWED evictCount=0
checkpoint=64a4e6db… phase=CREATING_INSERT_COMMITTED
checkpoint=64a4e6db… phase=EXC sql class=android.database.sqlite.SQLiteBlobTooBigException
                     msg=Row too big to fit into CursorWindow requiredPos=0, totalRows=1
```

Store read-back after the failure (`run-as`, debug build):
`64a4e6db…` lifecycle `CREATING`(0), `length(pre_manifest)=length(intended_manifest)=1,123,527`;
`05ccd92f…` lifecycle `VERIFIED`(4), 5,193 bytes; 0 tombstones; `user_version` 2.

Second apply in the same process (manual): rejected before any new
instrumented checkpoint line — fence already DIRTY (consistent with the
release-build `RECOVERY_STORE_UNAVAILABLE` follow-ups).

Designed restart (Lawnchair再起動) with the poisoned store:

```text
E/SQLiteQuery: exception: Row too big to fit into CursorWindow requiredPos=1, totalRows=2;
               query: SELECT * FROM recovery_points ORDER BY created_at_ms ASC
```

No `RESTART_RECONCILED` event was emitted for either record (the list query
aborts before any record is processed). The CREATING row and the VERIFIED row
are both still present, unchanged.

## Emulator control set (rev 1 evidence, retained)

All fresh-install emulator sequences passed — and their recovery records are
5–9× below the window limit, which is now the explanation rather than a
mystery:

- One Nova restore → authoritative import (13 rows, no default load, 0).
- Organizer from clean store: A4→A6→A8 `APPLY_VERIFIED` (point `20875cd7…`).
- Restart reconciliation after external restore: `VERIFIED + NEITHER_RECOGNIZED
  → VERIFIED` (observed for three distinct points across the session).
- Apply with 1–2 stale VERIFIED points present: A4→A6→A8 `APPLY_VERIFIED`
  (points `e900ce24…`, `071d30bc…`); 0 tombstones at all times → #166
  capacity lockout ruled out; `RetentionPolicy.planCreate` source analysis
  shows stale VERIFIED records cannot force `Unavailable` below the cap.
- Known boundary: the manual organizer fails legitimately when opened before
  the launcher model has loaded; the input-unavailable surface additionally
  exhibited a one-off unexplained failure (diagnostics gap → #172).

## Device vs emulator differentiator

The only load-bearing difference found is **recovery-record size vs the 2 MB
CursorWindow**, driven by captured manifest scale (row count × icon-bearing
rows): device ~2.25 MB (fails) vs emulator ~0.21 MB (passes). Retention
state, tombstone counts, store version/sidecars, prior-point shape
(VERIFIED + NEITHER), and the restore flow itself are equivalent. The bug is
therefore not Nova-specific and not retention-related; the Nova restore is
the trigger that makes the workspace (and thus the next checkpoint's
pre-state manifest) large on a real device.

## Stage D — semantic decision (unchanged, and independent of this defect)

**Keep current behavior: a `VERIFIED` recovery point remains stored and
restorable after an authoritative external workspace replacement; no
invalidation and no acknowledgement gate.** `VERIFIED → SilentAdvance` in
restart reconciliation is accepted (observed `NEITHER_RECOGNIZED` on both
platforms). Grounding: recovery restores a validated snapshot through an
explicit, preconditioned, fail-closed write-set (ADR-0003; #155/ADR-0008
context rejection), so a stale point is safe; external replacement is an
explicit user action; invalidation would require a new external-restore seam
across all replacement mechanisms for no demonstrated harm; retention bounds
staleness. Per the PR review, this decision is recorded separately from the
checkpoint defect and is not a prerequisite for the fix.

## Focused owning issue (split per Stage E)

- **#174 (Bug)**: `RecoveryStore.checkpoint()` creates records whose committed
  row can exceed the 2 MB `CursorWindow` on readback — deterministic A4
  `CHECKPOINT_CREATE_FAILED` at real-device workspace scale, plus store
  poisoning (unreadable CREATING row blocks reconciliation of the entire
  store, including healthy records). Owns the fix and the regression seam.
- **#172 (existing)**: input-unavailable readiness reason is not journaled or
  logged (the fence-DIRTY follow-on in this scenario was only visible through
  instrumentation; production diagnostics showed `RECOVERY_STORE_UNAVAILABLE`
  with no cause).

## Acceptance-criteria mapping (Issue #171, rev 2)

- Post-#169 prerequisite proven (one restore → authoritative workspace):
  **yes** — both platforms.
- Original sequence re-run from clean recovery-store state with exact build/
  environment evidence: **yes** — device release export + instrumented device
  debug reproduction.
- C1-C4 (documented smaller equivalent set) establishing the necessary
  condition: **yes** — the necessary condition is **workspace scale
  (manifest size), not prior VERIFIED points, not external replacement as
  such, not Nova specifically**; the emulator control isolates it.
- Repeatable reproduction: **yes** — device sequence reproduced end-to-end
  with instrumentation; emulator fix-verification is expected to run at the
  application-contract level (test DB with an oversized record), per the fix
  issue.
- Recovery-store state before the failing apply captured sufficiently to
  rule #166 in/out: **yes** — 0 tombstones, ≤2 non-final records on both
  platforms.
- Exact `RecoveryStore.checkpoint()` failure phase and concrete exception:
  **yes** — CREATING insert commits; close/reopen readback throws
  `SQLiteBlobTooBigException` (`SELECT *` row > 2 MB CursorWindow); collapsed
  to `CreateFailed`. `creation_failed` is no longer the only surface.
- Relationship between the external workspace and the old VERIFIED point
  classified and correlated with restart reconciliation: **yes** —
  `NEITHER_RECOGNIZED → VERIFIED` (both platforms), plus the poisoning
  behavior above.
- Conscious recovery-point semantic decision recorded: **yes** — Stage D.
- Implementation work split to focused owning Issue(s); no silent semantic or
  fail-closed change: **yes** — #174 (fix) and #172 (diagnostics); no
  production change in this investigation (net diff empty on the PR branch;
  instrumentation isolated to the investigation branch and removed from the
  device).
- Fix verification runs primarily on emulator: **to be satisfied by #174** —
  the defect is reproducible at the contract-test level (oversized record in
  a test DB) without device hardware.

## Non-goal compliance

- #168's one-restore fix was not reopened; the one-restore prerequisite held
  on the device as well.
- No #166 admission/tombstone policy was implemented.
- Exact apply verification, recovery-store durability, and fail-closed
  behavior were not weakened; PR #173 remains docs-only.
- No public diagnostics schema was added.
- The pre-#169 physical observation is now **corroborated** (not explained
  away); rev 1's attribution of the failure to the pre-#169 two-pass restore
  environment is retracted.
