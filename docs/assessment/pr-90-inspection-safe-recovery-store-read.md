# High-risk audit: PR #90 inspection-safe recovery-store read

> Status: accepted
> Audit date: 2026-08-20

- Auditor: ChatGPT GPT-5.6 Sol, independent audit session; this session did not implement or review-fix the PR #90 code changes.
- PR: https://github.com/nunu1733/NunuLauncher/pull/90
- Head SHA: 182ae32efbe4b8e651fe16a3268c457be7bd6519
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32330693166
- Criteria: specs/89-inspection-safe-recovery-store-read/spec.md FR-004; specs/89-inspection-safe-recovery-store-read/spec.md FR-005; specs/89-inspection-safe-recovery-store-read/spec.md FR-006; specs/89-inspection-safe-recovery-store-read/spec.md NFR-001; specs/89-inspection-safe-recovery-store-read/spec.md NFR-002; specs/89-inspection-safe-recovery-store-read/spec.md NFR-007; specs/89-inspection-safe-recovery-store-read/spec.md NFR-011

## Scope

The audit fixed the implementation baseline to PR #90 code head `182ae32efbe4b8e651fe16a3268c457be7bd6519` and treated the subsequent audit-record commit as a docs-only delta, as permitted by the high-risk evidence workflow.

The changed high-risk surface was inspected independently from the implementation session. The audit covered:

- the recovery preview/application boundary in `LayoutApplicationModule.kt`, `RecoveryPreviewProtocol.kt`, `RecoveryProtocol.kt`, and `Ports.kt`;
- mutex ordering and startup reconciliation authority, including `RunMutexPort`, the concrete `RunMutex`, identity-bound `RecoveryStoreReconciliationSession`, and `RestartReconciler.kt`;
- the durable SQLite-free inspection snapshot path in `InspectionSnapshotFence.kt`, `RecoveryInspectionSnapshot.kt`, `RecoveryInspectionSnapshotCodec.kt`, `RecoveryInspectionSnapshotPublisher.kt`, `RecoveryInspectionSnapshotReader.kt`, and `RecoveryStore.kt`;
- startup fail-closed classification in `RecoveryStartupStorageClassifier.kt` before a live recovery SQLite open;
- the no-cleanup/no-mutation inspection behavior for final snapshot, `.new`, `.bak`, unexpected entries, malformed/truncated envelopes, and unavailable fence states;
- deterministic publication-failure coverage and the API 26/API 35 physical no-write oracle recorded in the Stage B handoff and PR evidence;
- the PR-associated GitHub Actions merge gate on the exact audited implementation head.

No new public recovery mutation contract, Launcher database schema migration, or raw Launcher layout write path was introduced by this audit. The risk under review is the recovery-store/layout-data boundary and whether inspection can observe metadata without opening or mutating the authoritative WAL-backed recovery SQLite store.

## Criteria check

- **FR-004 — SQLite-free inspection read:** accepted. The inspection seam reads only the typed projection from the private final snapshot and does not use the live recovery SQLite store for preview inspection. The reader uses a bounded file read and fails closed on missing, malformed, oversized, unreadable, or untrusted input.
- **FR-005 — snapshot/fence trust and stale-success prevention:** accepted. Successful classification requires a valid in-process fence generation matching the final snapshot envelope. Writer entry invalidates the fence before authoritative work; publication or read-back uncertainty leaves it unavailable. An older final snapshot cannot become a success source while the fence is dirty or unknown.
- **FR-006 — startup/reconciliation authority:** accepted. Startup classifies storage before SQLite open, and only the composition root owning the concrete `RunMutex` can issue an identity-bound reconciliation lease/session. Closed, released, reused, or foreign-mutex capabilities are rejected before privileged store work.
- **NFR-001 — physical no-write inspection boundary:** accepted. The implementation inventories the authoritative DB, WAL/SHM sidecars, final snapshot, `.new`, `.bak`, and unexpected entries without inspection-time cleanup. The recorded physical oracle passed all 12 cases on API 26 and API 35, including sidecars-present/absent cases and closed-store publication behavior.
- **NFR-002 — fail closed on uncertainty/failure:** accepted. Invalid main-store headers, residual artifacts, companion files, malformed/truncated snapshot data, publication failure, and dirty/unknown fence states map to unavailable/incompatible outcomes rather than fallback SQLite inspection or repair.
- **NFR-007 — supported Android/storage boundary:** accepted. Publication is constrained to the app-private no-backup directory and the pinned AndroidX `AtomicFile` base/`.new` protocol. API 26 and API 35 are covered by the required physical matrix; the recorded API 36 execution is supplementary only.
- **NFR-011 — independent verification/high-risk gate evidence:** accepted for the audited code head. This independent session re-ran the PR-associated CI workflow and verified `final-status` success on attempt 2 for the exact implementation head. The audit record is the remaining repository evidence required for the high-risk gate; only this docs-only record is added after the audited head.

The mutex-first ordering was checked specifically: preview attempts acquire the shared ordinary mutex before using the snapshot classification path, while writer/reconciliation paths hold the concrete mutex for their authoritative transition. This prevents a concurrent writer from leaving a prior snapshot observable as a successful preview.

## Executed test surface

This independent audit session triggered a re-run of the PR-associated CI run `32330693166` for the audited implementation head and verified attempt 2 completed successfully. The re-run executed the source jobs rather than relying on a docs-only run. Relevant commands confirmed from the workflow/logs were:

```bash
./gradlew spotlessCheck
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew assembleLawnWithQuickstepGithubDebug
python3 tools/repo-contract/validate_repo_contract.py
python3 tools/repo-contract/test_validate_repo_contract.py
python3 tools/repo-contract/test_validate_high_risk_evidence.py
python3 tools/repo-contract/validate_writer_inventory.py
python3 tools/repo-contract/validate_diagnostics_contract.py
python3 tools/repo-contract/test_validate_diagnostics_contract.py
```

The independently re-run GitHub Actions attempt completed with `organizer-unit-tests`, `check-style`, `build-debug-apk`, `validate-repo-contract`, and `final-status` all successful. The unit-test job log reported `BUILD SUCCESSFUL` for the organizer suite.

The #89-specific Android physical oracle is not the generic instrumentation source job in `ci.yml`; it is separate acceptance evidence recorded on the audited branch. The audit verified the recorded commands and results rather than falsely treating them as part of `CI / final-status`:

```bash
ANDROID_SERIAL=emulator-5556 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest
ANDROID_SERIAL=emulator-5558 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.RecoveryStoreInspectionInstrumentationTest
ANDROID_SERIAL=emulator-5556 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotPublicationInstrumentationTest
ANDROID_SERIAL=emulator-5558 ./gradlew connectedLawnWithQuickstepGithubDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.lawnchair.organizer.application.store.RecoveryInspectionSnapshotPublicationInstrumentationTest
```

The Stage B handoff records `RecoveryStoreInspectionInstrumentationTest` as 12/12 passing on both API 26 (`arm64-v8a`) and API 35 (`arm64-v8a`), and the deterministic publication-failure class as 2/2 passing on both required API levels.

## Findings

**Blocking findings: none.** The audited implementation head is accepted for the Issue #89 high-risk boundary.

No independent evidence was found of an inspection path that opens the live recovery SQLite store, creates or cleans sidecars/companions, silently trusts a stale final snapshot, exposes reconciliation privilege to ordinary protocols, or heals a dirty/unknown fence through an ordinary mutation path.

Residual risk is limited to the filesystem/Android runtime behaviors explicitly bounded by the accepted spec. Those behaviors are covered by the required API 26/API 35 physical oracle and fail closed outside the supported single-process, app-private storage assumptions. The audit does not treat the supplementary API 36 result as part of the required support matrix.

If any non-`docs/` source change is pushed after `182ae32efbe4b8e651fe16a3268c457be7bd6519`, this audit must be repeated against the new code head. The audit-record commit itself is docs-only and therefore does not invalidate the recorded implementation head under the repository high-risk gate rules.
