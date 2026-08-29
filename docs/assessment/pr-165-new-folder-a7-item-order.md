# High-risk audit: PR #165 new-folder A7 item order

> Status: accepted; no blocking findings
> Audit date: 2026-08-29

- Auditor: ZCode independent audit session (separate from the implementation session); the auditor made no production or test source changes — the only artifact produced by this session is this audit record
- PR: https://github.com/nunu1733/NunuLauncher/pull/165
- Head SHA: ac4ae8be19bb95c2885154e89e302ff95c6cf119
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/33227480393 (verified via the GitHub API as a completed, successful `pull_request` run of `.github/workflows/ci.yml` associated with PR #165, on the exact head SHA and head branch `docs/issue-164-new-folder-a7-item-order`; `final-status` passed with `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`, and all organizer-instrumentation lanes executed and green — none skipped)
- Criteria: docs/adr/0003-organizer-recovery-point-storage.md ADR-0003

The machine-checked citation line carries ADR-0003 because the accepted spec's `AC-164-01`-style identifiers are not parseable by the gate's requirement-ID format; the acceptance criteria AC-164-01 through AC-164-07 of the accepted spec are instead checked per criterion in "Criteria check" below. This matches the accepted PR-160 record format. The spec's frontmatter status is `accepted` (verified 2026-08-29), and each AC identifier used below is defined in that spec.

## Scope

Independently reviewed the complete `main...ac4ae8be19bb95c2885154e89e302ff95c6cf119` diff commit by commit (`git log main..HEAD --name-status`, `git diff main...HEAD`). Seven commits: two spec/plan docs commits, the pre-fix red test commit (`a667b18e79`), the fix commit (`cd9de8879e`), the real-materializer oracle rewrite (`aed928dfc6`), and two docs-only evidence commits (`344eabe73a`, `ac4ae8be19`, the branch head).

Production changes are exactly the five described files, all inside `app.lawnchair.organizer.application.*`:

- `canonical/CanonicalItemOrder.kt` (new, `internal`): single authority for the canonical `ItemId` UTF-8 byte order; fails closed on unresolved references, including nested placement parents and structure members.
- `actions/IntendedStateResolution.kt` (new, `internal`): the reference resolution moved out of `LauncherLayoutAdapter` unchanged, plus `finalizeCanonicalOrder` (returns `null` → callers return `InvalidPlan` / fail closed; ordering applied only at the fully resolved boundary).
- `adapter/LauncherLayoutAdapter.kt`: the private `resolvePersistentReferences` body moved verbatim into `IntendedStateResolution.resolve`, and the write-set preparation now calls `resolveAndFinalize` with `InvalidPlan` on `null`.
- `adapter/RowManifestCodec.kt`: `capture` now sorts canonical items through `CanonicalItemOrder` instead of a private `rows.sortedBy { ItemId(...) }` — the same ordering, now shared, with an `error()` fail-closed on unresolved references (impossible for DB-row-derived items, whose references come from persisted row ids).
- `protocol/MaterializedStateValidator.kt`: the validator canonicalizes its own independently resolved reference (`finalizeCanonicalOrder(resolved)`) and compares it to `writeSet.intendedState`; it does not trust the writer's ordering, and returns `false` when the writer's state diverges in any way other than the page normalization already permitted.

Verified absent from the diff: any change to `src/com/android/launcher3/` (empty diff), `ApplyProtocol` (the A7 exact comparison and stage semantics are untouched), the recovery store / `RetentionPolicy` / recovery-point format, diagnostics schema, permissions, transports, backup/restore, database schema or migration, and public (`public.*`) contract shapes. `IntendedStateResolution` and `CanonicalItemOrder` are `internal`, so no public API surface changed. All other changed files are tests (`tests/unit/**`, `tests/organizer-instrumentation/**`) or docs (`docs/`, `specs/`). The last production-changing commit is `aed928dfc6`; the two commits after it are docs-only, so production code at the audited head equals the device-verified `344eabe73a` production code (the device evidence documents this equivalence).

The red→green claim was audited from the code and the committed evidence (`docs/assessment/evidence/issue-164-prefix-red-oracle.md`): the oracles build plans through the real `OrganizationPlanMaterializer` (`NewFolderPlanFixtures`, no hand-assembled materializer output), the protocol test's opt-in `productionEquivalentCapture` mode rebuilds the A7 recapture independently from persisted-row-equivalent state in capture-side order without echoing the write set's intended state and without calling the writer-side `CanonicalItemOrder`/`IntendedStateResolution` seam, the fail-closed invariants (top-level and nested unresolved references) are asserted, and the genuine-drift oracle still fails closed with `VERIFICATION_FAILED` and safe recovery. The pre-fix red outputs recorded in the evidence document are consistent with the oracle code as reviewed; no oracle weakness (fixture echo, shared ordering helper, weakened assertion) was found.

Device evidence (`docs/assessment/evidence/issue-164-device-verification.md` and `docs/assessment/evidence/issue164-device/` journals) is internally consistent: debug run `1052815c…` and release export run `4ca4787f…` on the physical Pixel 9a, and the supplementary AVD run `7ebae699…`, each show `CHECKPOINTED (A4) → APPLY_COMMITTED (A6) → APPLY_VERIFIED (A8)` with `newFolderCount: 1`, and each explicit recovery shows `RECOVERY_REQUESTED` / `RECOVERY_RESTORED` carrying the same `pointId` and a `pointOriginRunId` equal to the apply run. Counts (17 captured / 5 moved / 12 preserved / 1 new folder; 17 → 18 rows) are consistent across the document and journals. All committed identifiers are opaque hashes; no titles, package names, coordinates, or raw rows are present in the committed evidence. ADR-0003 guarantees are untouched (separate recovery DB, checkpoint-then-apply ordering, no raw DB copy).

## Criteria check

Checked against the accepted spec (`specs/164-new-folder-a7-item-order/spec.md`, frontmatter `status: accepted`) and ADR-0003 (`docs/adr/0003-organizer-recovery-point-storage.md`, `status: accepted`):

- **AC-164-01 — met.** `IntendedStateCanonicalOrderTest` drives the real materializer plan through `IntendedStateResolution.resolveAndFinalize`: the fixture asserts the materializer appends the new folder last, then requires the resolved items in canonical `ItemId` byte order (`[1, 10, 2, …]`, folder id 10 byte-sorting mid-list) — the device divergence shape. Fail-closed tests assert `null` (no fallback order) for unresolved top-level and nested (placement parent) references. The A7 comparison itself is unchanged (`ApplyProtocol` untouched by the diff).
- **AC-164-02 — met.** `NewFolderCanonicalOrderProtocolTest` runs `ApplyProtocol` end to end with the fake writer in the opt-in `productionEquivalentCapture` mode: writer side through the real production resolution seam with fixture identity allocation (max row id + 1), recapture side independently rebuilt from persisted-row-equivalent rows with capture-side canonical ordering. The recapture never echoes `writeSet.intendedState` and never calls the writer-side canonicalization authority (verified in code). The genuine-drift oracle still yields `Recovered` with `VERIFICATION_FAILED`, pre-apply state restored, and both reloads completing — no verification weakening. Reinforced by `RealAdapterRowMatrixInstrumentationTest.newFolderWriteSetIntendedStateMatchesRealCanonicalCapture` (real `RowManifestCodec.capture` vs resolved intended state), green in the exact-head CI instrumentation lanes.
- **AC-164-03 — met.** `CanonicalItemOrder` is the single ordering authority, consumed by `RowManifestCodec.capture`, `IntendedStateResolution.finalizeCanonicalOrder`, and (through the same seam) `MaterializedStateValidator`. Determinism is asserted (`repeatedPreparationOfMultiFolderPlanIsDeterministic`), multi-folder and id-boundary cases are covered (`multiFolderWithBoundaryIds`, ids 100/101 across the 99→100 boundary), and the capture-side ordering is semantically identical to the pre-fix `rows.sortedBy { ItemId(...) }` (same comparator, byte-identical output on unchanged workspaces).
- **AC-164-04 — met.** `docs/assessment/evidence/issue-164-device-verification.md` records the physical Pixel 9a debug and release runs at production-identical code to the audited head, both reaching `APPLY_VERIFIED`/A8 with `newFolderCount: 1` on the fresh default 4×5 workspace (17-row baseline, folder row `_id=19` byte-sorting mid-list), with committed journals matching the document.
- **AC-164-05 — met.** The same evidence records explicit recovery for both Pixel 9a runs: `RECOVERY_REQUESTED` / `RECOVERY_RESTORED` with identical `pointId` and `pointOriginRunId` matching the verified apply run; the debug run additionally documents post-recovery row equality with the pre-apply rows (17/17).
- **AC-164-06 — met.** Follow-up issue nunu1733/NunuLauncher#166 is open and owns the 24-hour tombstone lockout with an observable diagnostic code; this diff changes no retention or recovery-store behavior (verified in the diff).
- **AC-164-07 — met.** Scope check passed (see Scope); `CI / final-status` is green on the exact head SHA (`gh api` verified run 33227480393: `event=pull_request`, `head_sha=ac4ae8be…`, associated with PR #165, `conclusion=success`, all source jobs executed), and this independent audit record completes the gate.
- **ADR-0003 — no violation found.** The diff does not touch the recovery store, retention, or recovery-point format; checkpoint-then-apply ordering and separate-DB guarantees are preserved; the device evidence used only existing redacted diagnostics surfaces.

## Executed test surface

Independent local execution on macOS arm64 (Darwin 25.5.0), Gradle 9.3.0 daemon, against the clean checkout at `ac4ae8be19bb95c2885154e89e302ff95c6cf119` (`git status` clean before writing this record):

- `git log main..HEAD --name-status` / `git diff main...HEAD` — reviewed commit by commit (see Scope).
- `./gradlew spotlessCheck` — `BUILD SUCCESSFUL`.
- `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'` — first invocation completed with all tasks up-to-date (no re-execution, warm daemon), so it was re-run forced: `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*' --rerun` — `BUILD SUCCESSFUL`, test task executed, JUnit XML results regenerated: 739 organizer tests, 0 failures, 0 errors, 0 skipped.
- `./gradlew assembleLawnWithQuickstepGithubDebug` — `BUILD SUCCESSFUL` (tasks up-to-date against the current sources; the debug APK for the audited head exists at `build/outputs/apk/lawnWithQuickstepGithub/debug/Lawnchair.15.Dev.(ac4ae8b).github.debug.apk` and Gradle's up-to-date check confirms it corresponds to these exact inputs).
- `python3 tools/repo-contract/validate_repo_contract.py` — `repository contract OK`.
- `python3 tools/repo-contract/test_validate_repo_contract.py` — 11 tests, `OK`.
- `python3 tools/repo-contract/test_validate_high_risk_evidence.py` — 47 tests, `OK`.
- `gh run view 33227480393` and `gh api repos/nunu1733/NunuLauncher/actions/runs/33227480393` (+ `/jobs`) — run metadata and job conclusions verified as claimed (see the CI run field above).

Not executed locally in this session and why: the organizer instrumentation suites (`connectedLawnWithQuickstepGithubDebugAndroidTest`) and the on-device runs — no emulator or physical device is attached to this audit session. These claims rest on the exact-head CI run 33227480393 (all organizer-instrumentation lanes executed and green) and the committed, internally consistent device evidence documents; the `--rerun`-forced unit re-execution above is this session's independently executed test surface.

## Findings

No blocking findings. The merge may proceed once the repository gate (`high-risk-gate` workflow) accepts this record on the current head.

### Non-blocking: PR-body evidence table cites superseded run IDs

The PR body's AC-164-05 row cites debug runId `2d15347a…` and release runId `7ebae699…` from the first verification pass. The re-verification commit `ac4ae8be19` (docs-only) superseded these with the physical-device runs documented in `docs/assessment/evidence/issue-164-device-verification.md` (debug `1052815c…`, release `4ca4787f…`; `7ebae699…` survives only as the supplementary AVD run). The spec change history, evidence document, and committed journals are mutually consistent and authoritative; the stale prose lives only in the PR body, which is not the repository's source of truth. No correction to the PR body is required for this gate.

### Non-blocking: gate requirement-ID format does not parse this spec's AC identifiers

`tools/repo-contract/validate_high_risk_evidence.py` extracts only `FR-<n>` / `NFR-<n>` / `AC-<n>` (non-hyphenated) / `ADR-<nnnn>` tokens from `Criteria:` lines; the accepted spec's `AC-164-01`-style identifiers cannot be machine-cited there, and citing the spec path with no parseable ID would fail the gate. This record therefore carries ADR-0003 on the machine-checked line and verifies AC-164-01…07 in "Criteria check", the same accepted format as the merged PR-160 record. This is a gate-format limitation, not an evidence gap.

### Non-blocking: production-strengthening change inside a test-labelled commit

Commit `aed928dfc6` (message starts `test(organizer):`) also extends `CanonicalItemOrder` to fail closed on unresolved nested references. Reviewed as behavior-strengthening (it implements the spec's fail-closed invariant scope, cannot weaken verification, and is covered by the CI run on the final head); noted because commit labels do not perfectly partition production/test changes.
