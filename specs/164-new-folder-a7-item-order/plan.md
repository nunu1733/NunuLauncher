# Implementation Plan: New-folder plans reach A8 (canonical intended-state item order)

> Issue: [#164](https://github.com/nunu1733/NunuLauncher/issues/164)
> Spec: [spec.md](./spec.md)
> Status: **accepted** (2026-08-28, owner review passed with no blocking
> findings). Stage B implementation follows this plan; no production behavior
> change happened under the Stage A branch.
> Risk: `layout-data`
> Evidence baseline: Issue #164 reproduction at `92a490a2f8` (release) and
> `c68abcce62` (debug reproduction build), both on the device environment
> recorded in the issue.

## Current evidence

### Confirmed production paths (read at `c68abcce62`, 2026-08-28)

The defect chain the issue reports is confirmed in the source, step by step:

- `lawnchair/src/app/lawnchair/organizer/application/actions/OrganizationPlanMaterializer.kt`
  builds the intended items as planner-ordered existing items with new folders
  appended last (`val allItems = (originalItems.values + folderItems)`, line
  83) and hands the resulting `intendedState` to `ValidatedLayoutPlan`
  (lines 112–116). At this stage new folders are `ApplicationItemRef.PlannedFolder`
  refs; their persistent ids do not exist yet.
- `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt`
  `prepareApplyWriteSet` allocates persistent ids in intended-items order
  (`nextId = max rowId + 1`, lines 182, 206–212) and resolves
  `PlannedFolder → PersistentItem` while preserving the list order
  (`resolvePersistentReferences`, lines 225, 494–549). The materialized
  write set's `intendedState` therefore keeps the new folder last — here,
  carrying the device case's id `19`.
- `lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt`
  `capture` sorts canonical items by `ItemId` UTF-8 byte order (lines 95–103,
  the PR #160 canonical-order fix, which documents that "A7 verification
  compares LayoutState exactly"). `Item.compareTo` is
  `compareUtf8Bytes` (`lawnchair/src/app/lawnchair/organizer/planning/Identity.kt`
  lines 3–21), under which `"19" < "2"`.
- `lawnchair/src/app/lawnchair/organizer/application/protocol/ApplyProtocol.kt`
  A7 requires `db.layoutState == writeSet.intendedState &&
  db.manifest == writeSet.intendedManifest` (lines 333–341) and routes any
  mismatch to `automaticRecovery(..., ApplyFailure.VERIFICATION_FAILED, ...)`.
  `LayoutState` is a data class; `items` equality is order-sensitive, so the
  appended-last folder guarantees the mismatch. This matches the issue's
  diagnostic log (`stateEqual=false manifestEqual=true`, both sides at 18
  items, differing only in item order) and its ~630 ms failure timing (not the
  #150-era 10 s timeout shape).

Two surrounding facts bound the fix:

- Id allocation is already order-independent by design: the adapter comments
  that "a folder child can precede its planned folder in the canonical item
  order, so IDs must not depend on the order in which rowFor happens to be
  called" (lines 203–205). Reordering the resolved intended items therefore
  cannot change ids, rows, or manifest content.
- The A7 manifest side already coincides (`manifestEqual=true` on device;
  the intended manifest is `rows.sortedBy { it.rowId }`, line 238). Only the
  `LayoutState.items` list order diverges.

### Test-seam facts

- `tests/unit/app/lawnchair/organizer/application/adapter/FakeLayoutWriter.kt`
  bypasses the real `prepareApplyWriteSet` (it reuses `plan.intendedState`
  verbatim unless `materializedIntendedStateOverride` is set) and its
  `recaptureDb`/`captureOf` return the stored state without reordering.
  Worse, `applyWriteSet` stores `writeSet.intendedState` into `stateRef` and
  `recaptureDb` returns `captureOf(stateRef.get())` verbatim, so the A7
  comparison degenerates to an identity check: even with real preparation
  delegation, a pre-fix run would still pass A7. The device failure exists
  because the two sides are computed by different rules (writer-side planner
  order vs capture-side canonical order); the protocol oracle must reproduce
  exactly that asymmetry, which the current fake collapses. This is the
  review's blocking finding against the originally drafted AC-164-02.
- `tests/organizer-instrumentation/app/lawnchair/organizer/application/RealAdapterRowMatrixInstrumentationTest.kt`
  drives the real `RowManifestCodec.capture` against an in-memory
  `SQLiteDatabase.create(null)` with exact schema-33 row round-trips; it is
  the existing surface for asserting real capture-side mapping fidelity.
- `LauncherLayoutAdapter` requires `Context`, `ModelDbController`, and
  `LauncherModel` (lines 65–69), so it is not constructible in JVM unit tests.
  The existing precedent for JVM-testable write-set logic is
  `RecoveryWriteSetMaterializer` (`application/actions/`) with
  `RecoveryWriteSetTest`; `RealAdapterRowMatrixInstrumentationTest` is the
  instrumentation surface for the real adapter.
- `RetentionPolicy` (`application/lifecycle/RetentionPolicy.kt`, unit-covered
  by `RetentionPolicyTest`) implements the issue's secondary finding: up to
  three non-expired points, 24 h tombstone retention. With the A7 fix, three
  recovered runs in 24 h remain reachable only through genuine recoveries;
  the fourth attempt is rejected at A4 with
  `PRE_WRITE_REJECTED.RECOVERY_STORE_UNAVAILABLE`
  (`RecoveryStoreLifecycleTest` is the instrumentation seam for this
  interaction).

## Design

### Modules and interfaces

Public application and diagnostics contracts are unchanged:

```text
LayoutWriterPort.prepareApplyWriteSet(capture, plan) -> WriteSetPreparation
ApplyProtocol.apply(ValidatedLayoutPlan) -> ApplyResult
```

The internal contract of `prepareApplyWriteSet` is sharpened: the ready write
set's `intendedState.items` are always in canonical `ItemId` UTF-8 byte order
— the same order `RowManifestCodec.capture` produces — after persistent
reference resolution. Selected shape (per the owner review of 2026-08-28):

1. Introduce one internal canonical item-order authority (a small comparator
   or ordering function in the application module, e.g. under
   `application/canonical/`), used by both `RowManifestCodec.capture` and the
   resolved-state finalization. `RowManifestCodec`'s output must remain
   byte-identical; only the duplicated ordering knowledge is unified.
2. Extract the adapter's private `resolvePersistentReferences` extension and
   add the new canonical finalization into one small pure internal seam
   (resolution + finalization of the intended state). The adapter calls it
   after `normalizeMaterializedPages`; the JVM tests call the same seam. The
   finalization enforces fail-closed invariants: ordering is applied only
   when every item reference is resolved to a persistent `ItemId`; the
   `PlannedFolder`-stage list is never reordered; any unresolved reference
   rejects preparation (`InvalidPlan`) instead of producing a fallback order.
   Page ordering (`sortedBy { order }`), manifest row ordering
   (`rows.sortedBy { it.rowId }`), id allocation, action list, digests, and
   identity mapping are untouched.
3. No full `prepareApplyWriteSet` extraction in this change. The production
   fix is the minimal seam above; moving the whole preparation (id
   allocation, row derivation, manifest/context-resource encoding) out of the
   adapter is out of scope and requires an independently recorded
   justification (e.g. separating materialization from Android dependencies)
   — protocol-testability alone is not that justification.

The materializer is deliberately not reordered: `plan.intendedState` cannot
know final ids (`PlannedFolder` ordinals only), and ordering it by anything
other than the eventual persistent id would guess the adapter's allocation
authority. The single canonicalization point is the write-set boundary.

### Data flow

```text
A6 commit
  -> COMMITTED_UNVERIFIED
  -> correlated reload Completed (unchanged, #150/#160 barrier)
  -> independent DB recapture: capture-side items in canonical ItemId order
     (unchanged)
  -> exact comparison: writeSet.intendedState (NOW canonical ItemId order
     after reference resolution) == db.layoutState
  -> A8 VERIFIED
```

Pre-fix, the intended side kept the new folder last; post-fix both sides are
canonical by construction. Automatic recovery and explicit recovery flows are
untouched and keep their existing verification.

### Test oracles and path fidelity

The protocol oracle must reproduce the device asymmetry, so both sides of the
A7 comparison need independent fidelity:

- **Writer side** (real production logic): the fixture runs the real
  `OrganizationPlanMaterializer` on a default-workspace-shaped snapshot, the
  fake assigns fixture identity the way production does (maximum row id + 1,
  so the new folder's id byte-sorts mid-list), and the real extracted
  resolution/finalization seam materializes the write set's intended state.
  Only the identity assignment is fixture code; the ordering behavior under
  test is production code on both the pre-fix and post-fix heads.
- **Recapture side** (independent, production-equivalent capture semantics):
  `FakeLayoutWriter.applyWriteSet` persists row-equivalent state (rowId tied
  to the resolved persistent id, mirroring production where rowId == id), and
  `recaptureDb` rebuilds the `CapturedSnapshot` from those rows: manifest rows
  re-paired by rowId in enumeration order (so the manifest side keeps
  matching, as on device) and `LayoutState` items re-derived and ordered by a
  test-local implementation of the documented capture rule — `ItemId` UTF-8
  byte order, mirroring `RowManifestCodec` without reusing the writer-side
  authority. The recapture never echoes `writeSet.intendedState` verbatim.

Pre-fix, the intended list keeps the folder last while the recapture sorts it
mid-list: A7 fails exactly as on device (`manifestEqual=true`,
`stateEqual=false`) and the protocol returns `APPLY_RECOVERED`. Post-fix, the
finalized intended state is canonical and A7 passes. A supplementary
instrumentation roundtrip (in-memory SQLite + real `RowManifestCodec.capture`,
extending the `RealAdapterRowMatrixInstrumentationTest` pattern) asserts the
real capture output equals the materialized intended state for the same
fixture, covering the real capture-side row→item mapping rather than its
test-local mirror. A full real-adapter `ApplyProtocol` instrumentation harness
is not required: capture-side fidelity is covered by the real codec roundtrip,
writer-side ordering by the real pure seam, and true end-to-end behavior by
the AC-164-04 device evidence.

### Alternatives rejected

| Alternative | Reason rejected |
|---|---|
| Make the A7 `LayoutState` comparison order-insensitive for items | Weakens the accepted exact-verification contract (spec 13 / PR #160 canonical order), changes public `LayoutState` equality semantics or adds a special-case comparison, and could mask genuine ordering regressions (e.g. nondeterministic writes). The issue's constraint is that a false `APPLY_VERIFIED` stays impossible; loosening equality moves the opposite direction. |
| Recapture that echoes `writeSet.intendedState` (identity fake) or calls the writer-side authority to self-match | Collapses the two independent paths into one, so A7 passes pre-fix and the red→green oracle is vacuous (the review's blocking finding). |
| Full `prepareApplyWriteSet` extraction as a prerequisite of the fix | The fix needs only the small resolution/finalization seam; wholesale relocation of allocation/row/manifest logic widens the risk surface without strengthening the oracle. If later needed, it requires its own independent justification (e.g. Android-free materialization). |
| Sort new folders into canonical order inside `OrganizationPlanMaterializer` | Impossible as stated: the materializer only has `PlannedFolder(ordinal)` refs; persistent ids are allocated later in the adapter (`max rowId + 1`). Any materializer-side order would rely on guessing the allocator, duplicating its authority. |
| Reorder the capture to the materializer's planner order | Reverts the accepted PR #160 canonical-capture fix and couples the capture's determinism to planner internals. |
| Retry recapture until it matches, or delay/refresh before comparison | Masks divergence, adds nondeterministic latency, and violates the no-timing/retry shortcuts rule (AGENTS.md, spec 150 precedent). |
| Fix the tombstone lockout (retention/admission change) in this work | A recovery-store policy change needs its own spec/risk review (ADR-0003 ownership); the issue explicitly allows splitting it. With the A7 fix the lockout is reachable only via genuine recoveries. |

## Change set

Only implementation work after this spec is accepted should touch production
paths. This Stage A branch changes `spec.md` and `plan.md` only.

| Area | Intended change | Why here |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` | Finalize the resolved intended state through the small pure seam (resolution + canonical finalization) before building the write set. | The only point where planned refs are resolved to persistent ids and the write set is finalized. |
| `lawnchair/src/app/lawnchair/organizer/application/actions/` or `.../canonical/` (new small internal seam, name TBD) | The extracted `resolvePersistentReferences` plus canonical finalization (shared order authority, fail-closed unresolved-reference rejection). | Minimal JVM-testable seam for AC-164-01/02; no public contract change; no full preparation extraction. |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/RowManifestCodec.kt` | Use the shared canonical-order authority instead of its local `sortedBy { ItemId(...) }`; output byte-identical. | Single canonical-order authority (AC-164-03); prevents future divergence. |
| `lawnchair/src/app/lawnchair/organizer/application/protocol/MaterializedStateValidator.kt` | Compare the write set's intended state against the plan-resolved reference after applying the same shared canonical finalization. | The validator forbids every writer transformation beyond identity resolution; the accepted canonical reordering must be allowed through the same authority (not by trusting the writer), or every new-folder apply would be rejected `INVALID_PLAN`. |
| `tests/unit/app/lawnchair/organizer/application/` (new/existing unit tests) | Failing-path fixtures: a new folder whose allocated id byte-sorts mid-list (e.g. existing ids up to 18 → folder id 19, or ids 1–9 → folder id 10); canonical-order, determinism, and fail-closed unresolved-ref assertions; mismatch-injection recovery stays green. | The materializer/apply seam oracle required by the issue's acceptance. |
| `tests/unit/app/lawnchair/organizer/application/adapter/FakeLayoutWriter.kt` | Opt-in only (per the owner's acceptance note): a fixture-specific `productionEquivalentCapture` mode. Writer side: materialize via the real materializer and the real resolution/finalization seam with fixture identity (max row id + 1). Recapture side: rebuild state and manifest independently from persisted-row-equivalent rows with capture-side canonical semantics — never echoing the intended state and never using the writer-side authority. The shared fake's default echo semantics and synthetic-manifest behavior, which existing protocol tests depend on, remain exactly as before when the flag is unset. | Reproduces the device asymmetry so AC-164-02 is red pre-fix; both oracle paths keep production fidelity without changing existing tests' abstraction level. |
| `tests/organizer-instrumentation/app/lawnchair/organizer/application/RealAdapterRowMatrixInstrumentationTest.kt` (or a sibling) | Add a new-folder mid-list-id case: in-memory SQLite rows → real `RowManifestCodec.capture` output equals the materialized intended state. | Real capture-side mapping fidelity behind the JVM mirror. |
| `docs/assessment/pr-<PR>-new-folder-a7-item-order.md` | Independent high-risk audit record after implementation CI succeeds. | Required by `AGENTS.md` for `risk: layout-data`. |
| Follow-up lockout issue | Opened by the issue owner (or the Stage B PR) with an observable diagnostic code; linked from here and from #164. | AC-164-06; no tentative number per `AGENTS.md`. |

No change to `specs/13`, `specs/52`, `specs/150`, `specs/83`, `ADR-0003`,
database schema, recovery store, backup allowlist, planner, or diagnostics
schema.

## Migration and recovery

- **Database/recovery migration:** none. No schema, recovery format, journal,
  preference, or backup files change.
- **Runtime rollback:** reverting the source restores prior behavior with no
  data conversion. The reintroduced failure mode is the safe deterministic
  `APPLY_RECOVERED`, never a corrupt or lost layout (the issue's row-level
  evidence: pre/post rows byte-identical across recovery).
- **Apply rollback:** existing A7 failure → automatic recovery semantics are
  unchanged and must remain green under mismatch injection.
- **Backup/restore:** unchanged; the intended state is transient and never
  persisted outside the write set.

## Verification

| Acceptance criterion | Automated/manual evidence | Command or environment |
|---|---|---|
| AC-164-01 | Red→green JVM unit test through the real materializer + resolution/finalization seam with the mid-list new-folder fixture. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.application.*'` |
| AC-164-02 | Protocol seam test with the independent persisted-row-equivalent recapture: pre-fix `APPLY_RECOVERED`/`VERIFICATION_FAILED` recorded, post-fix `Applied`; mismatch-injection still recovers safely; real-capture roundtrip via the new-folder instrumentation case. | Same command, protocol test class (pre-fix failure recorded in the PR before the production change); instrumentation case on the API 36.1 device/AVD with the exact command recorded in PR evidence once verified. |
| AC-164-03 | Shared-authority unit tests + determinism/byte-identical assertions; full organizer unit suite green. | `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` |
| AC-164-04 | Pixel 9a / API 36 default-workspace debug and release runs reaching A8 with a new folder; redacted journal export; exact head SHA recorded. | `./gradlew assembleLawnWithQuickstepGithubDebug` / `...Release`; manual Settings flow per the issue; supported diagnostics export. |
| AC-164-05 | Explicit recovery preview/confirm after a verified apply; journal shows `RECOVERY_REQUESTED`/`RECOVERY_RESTORED` with matching `pointOriginRunId`. | Same device; supported Settings diagnostics export. |
| AC-164-06 | Follow-up issue link with observable diagnostic code recorded in this spec's history. | Issue link before #164 closes. |
| AC-164-07 | Formatting, build, repository-contract checks, CI merge gate, independent audit on the exact head. | `./gradlew spotlessCheck`; `./gradlew assembleLawnWithQuickstepGithubDebug`; `python3 tools/repo-contract/validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_repo_contract.py`; `python3 tools/repo-contract/test_validate_high_risk_evidence.py`; PR `CI / final-status`. |

Commands follow [building.md](../../docs/engineering/building.md) on a clean,
submodule-initialized checkout; commands not executed must not be reported as
passing. Device evidence records build type, device/OS, grid, tested commit,
journal phase sequence, and redacted invariant results only.

## Documentation updates

- [x] This spec and plan: `draft` → `accepted` after Stage A review (owner
  approval, including the tombstone-split decision and the chosen fix
  direction); → `implemented` after Stage B evidence.
- [ ] `CONTEXT.md`: no change expected (no new domain term).
- [ ] `DESIGN.md`: no change expected (no system-level seam change; the
  extraction stays inside the Layout Application module's internal
  structure). Stop and review if the shared ordering authority crosses a
  module boundary.
- [ ] ADR: none expected. Create one only if implementation turns the
  canonical-order authority or the write-set extraction into a
  cross-module/persistence decision.
- [ ] `AGENTS.md`: no change expected; no new verified command.
- [ ] Follow-up lockout issue: link recorded here and in #164 when opened.

## Execution checklist (Stage B, after acceptance)

- [x] Reproduce the divergence in a red unit test through the real
  materializer + resolution/finalization seam before touching production code.
- [x] Implement the minimal canonical finalization with the shared ordering
  authority and the fail-closed invariants (no `PlannedFolder`-stage
  reordering, no fallback order on unresolved refs).
- [x] Flip the protocol-seam test with the independent persisted-row-equivalent
  recapture, add the real-capture instrumentation roundtrip, and keep
  mismatch-injection recovery green.
- [x] Run the full organizer unit suite, formatting, and debug build.
- [x] Device evidence: default-workspace new-folder run to A8 (debug:
  physical Pixel 9a; release: API 36.1 AVD) and explicit recovery
  correlation on the Issue #164 environment
  (`docs/assessment/evidence/issue-164-device-verification.md`).
- [x] Open/link the tombstone-lockout follow-up issue (#166).
- [ ] Record PR evidence, pass `CI / final-status` on the exact head, and
  obtain the independent `risk: layout-data` audit.

## Stop conditions

Stop implementation and return to the owning Issue/spec before changing code
if:

1. The canonical ordering of the resolved intended state cannot be made
   deterministic without changing id allocation, row contents, manifest row
   order, or the action list.
2. The fix requires changing `LayoutState` equality, the A7 comparison, the
   planner contract, a public organizer API, the diagnostics schema, or any
   Launcher3 bridge file.
3. A test passes only with a delay, retry, weakened comparison, or an oracle
   whose two sides are not independent: a recapture that echoes
   `writeSet.intendedState`, a recapture that reuses the writer-side
   canonicalization authority, or a writer side that is a fake
   reimplementation of the ordering logic under test. Fixture identity
   assignment (id allocation) is acceptable; the ordering behavior must come
   from production code on the writer side and from production code or an
   independent implementation of the documented capture rule on the recapture
   side.
4. Device evidence shows a different root (e.g. manifest divergence, page
   normalization, profile/capability mismatch) — split that cause into its own
   issue instead of masking it here.
5. The tombstone lockout cannot be split cleanly (e.g. the A7 fix cannot be
   verified without changing retention) — resolve ownership with the recovery
   store owner first.
6. Automatic or explicit recovery ever loses a point, changes a locked
   placement, or reports success without exact verification — preserve
   evidence and return to the spec 13 recovery owner.

## High-risk merge gate

`risk: layout-data` applies. The Stage B PR must include `Closes #164`, map
evidence to AC-164-01…07, pass `CI / final-status` on the exact head SHA, and
then receive an independent audit record
(`docs/assessment/pr-<PR-number>-new-folder-a7-item-order.md`) authored by a
separate session/agent with the exact 40-character head SHA, CI run links,
referenced spec/ADR criteria, and test surfaces. Any source change after the
audit requires a new CI result and audit.

## Change history

- 2026-08-28: Stage A draft created for Issue #164. Records the confirmed
  defect chain (materializer append-last → adapter id allocation/resolution →
  capture canonical order → exact A7 comparison), the chosen fix direction
  (canonicalize the resolved write-set intended state with a shared ordering
  authority) with rejected alternatives, the JVM test seam via the
  `RecoveryWriteSetMaterializer`-pattern extraction, device/recovery evidence
  requirements, and the tombstone-lockout split decision.
- 2026-08-28: Revised after the owner's Spec/Plan review (Request changes).
  Blocking adopted: the previous protocol-oracle design could not turn red
  pre-fix because `FakeLayoutWriter` echoes the write set's intended state in
  its recapture; AC-164-02 now requires an independent persisted-row-equivalent
  recapture with production-equivalent capture semantics plus a real
  `RowManifestCodec.capture` instrumentation roundtrip. Recommended items
  adopted: the production change narrowed to the minimal pure seam (shared
  order authority + resolved-state finalization; no full preparation
  extraction without an independent justification), and the canonicalization
  preconditions made explicit fail-closed invariants. Stop conditions now
  require two-sided oracle independence.
- 2026-08-28: Accepted (no blocking findings) with one non-blocking
  implementation note, adopted into the change set: the production-equivalent
  recapture is contained as an opt-in mode of the shared `FakeLayoutWriter`
  (`productionEquivalentCapture`); the fake's default echo semantics and
  synthetic-manifest behavior stay unchanged so existing protocol tests keep
  their abstraction level. Stage B staged as a red commit (seam extraction
  with pre-fix behavior plus the oracle tests) followed by the fix commit
  (canonical finalization, adapter wiring, capture authority), recording both
  runs as PR evidence.
- 2026-08-28: Stage B implemented on this branch in two commits. The red
  commit (`a667b18e79`) extracted the resolution seam with pre-fix behavior
  and recorded the failing oracles in
  [issue-164-prefix-red-oracle.md](../../docs/assessment/evidence/issue-164-prefix-red-oracle.md);
  the fix commit landed `CanonicalItemOrder` (shared authority), the
  canonical finalization, and its adoption by `RowManifestCodec` and
  `MaterializedStateValidator` — the validator change is required in the same
  commit because its plan-order exact comparison would otherwise reject every
  new-folder write set with `INVALID_PLAN`. Local verification: new oracles
  red pre-fix and green post-fix; full unit suite 753 tests, 0 failures;
  `spotlessCheck`; `assembleLawnWithQuickstepGithubDebug`;
  repository-contract scripts; and the new instrumentation roundtrip green on
  the `nunu_qpr2_api36_1` API 36.1 AVD (5/5 in
  `RealAdapterRowMatrixInstrumentationTest`). Remaining before merge: device
  evidence (AC-164-04/05), the tombstone-lockout follow-up issue
  (AC-164-06), PR `CI / final-status` on the exact head, and the independent
  `risk: layout-data` audit (AC-164-07).
- 2026-08-28: Implementation review adopted (PR #165). AC-164-01/02 oracles
  rewritten to consume plans from the real `OrganizationPlanMaterializer`
  (`NewFolderPlanFixtures`: `OrganizationInput` + `PlanningResult` →
  `materialize` → `Ready.plan`; the protocol test no longer hand-assembles
  `ValidatedLayoutPlan`), with the fixture snapshot revision derived from the
  canonical source state so A2 passes. Added the AC-164-03 multi-folder +
  99→100 id-boundary determinism coverage. `CanonicalItemOrder.sortedResolved`
  now fails closed on unresolved nested references (placement parents,
  structure members), matching the spec invariant's full scope. Red
  re-demonstrated against pre-fix production with the rewritten oracles
  (5/8 failed, recorded in the evidence document); green again post-fix with
  755 unit tests / 0 failures.
- 2026-08-28: Device evidence recorded
  (`docs/assessment/evidence/issue-164-device-verification.md`). Debug on the
  physical Pixel 9a (Issue #164 environment, fresh default 17-row workspace):
  a manual run creating 1 new folder reached `APPLY_VERIFIED`/A8
  (`CHECKPOINTED` → `APPLY_COMMITTED` → `APPLY_VERIFIED`), the allocated
  folder id 19 byte-sorts mid-list (the issue's exact shape), and explicit
  recovery emitted `RECOVERY_REQUESTED`/`RECOVERY_RESTORED` with
  `pointOriginRunId`, restoring byte-identical pre-apply rows. Release on
  the API 36.1 AVD (the device's `app.lawnchair` is CI-signed and cannot be
  updated in place; the acceptance allows an API 36 environment): a
  new-folder plan produced via the product's S1 category-override surface
  reached A8 on the release build with the same recovery correlation and
  exact pre-apply restoration. AC-164-04/05 satisfied; AC-164-06 satisfied
  by follow-up Issue #166. Remaining before merge: PR `CI / final-status`
  on the exact head and the independent `risk: layout-data` audit.
