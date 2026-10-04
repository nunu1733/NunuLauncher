# High-risk audit: PR #92 recovery preview seam

> Status: accepted
> Audit date: 2026-08-20

- Auditor: ChatGPT GPT-5.6 Sol, independent audit session; this session did not implement or review-fix the PR #92 code changes.
- PR: https://github.com/nunu1733/NunuLauncher/pull/92
- Head SHA: cbe4b0a94309dffeeb589c91aa79c852c1a85e15
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32335308235
- Criteria: specs/84-recovery-preview-seam/spec.md FR-004; specs/84-recovery-preview-seam/spec.md FR-005; specs/84-recovery-preview-seam/spec.md FR-006; specs/84-recovery-preview-seam/spec.md NFR-001; specs/84-recovery-preview-seam/spec.md NFR-002; specs/84-recovery-preview-seam/spec.md NFR-007; specs/84-recovery-preview-seam/spec.md NFR-011; specs/13-safe-layout-application/spec.md FR-005; specs/13-safe-layout-application/spec.md NFR-001; specs/13-safe-layout-application/spec.md NFR-002; docs/adr/0003-organizer-recovery-point-storage.md ADR-0003

## Scope

The audit fixed the implementation baseline to PR #92 code head `cbe4b0a94309dffeeb589c91aa79c852c1a85e15`. The audit-record commit following that head is docs-only and does not alter the audited code.

PR #92 itself changes only `tests/unit/app/lawnchair/organizer/application/protocol/RecoveryPreviewProtocolTest.kt`, adding the missing regression for the trusted authoritative incompatibility projection. The Issue #84 production seam is already present in the PR base after the merged Issue #89 integration, so this independent audit did not limit itself to the 15-line test delta. It re-examined the complete #84 behavior on the PR #92 baseline, including:

- `RecoveryPreviewProtocol` inspection ordering, closed classification, retention-age evaluation, writer/run serialization, current-state capture, and no-mutation behavior;
- `LayoutApplicationModule.inspectRecovery()` and the opaque, one-shot confirmation registry that binds `RecoveryPointId` to the freshly captured expected current revision;
- confirmation delegation into the existing application-level recovery behavior, including readiness re-check, `RecoveryRequest` construction, existing recovery diagnostics, and current-revision revalidation;
- public recovery-preview values and the absence of manifest, payload, digest, row, or raw revision exposure;
- the Issue #89 inspection projection/fence path in the production `RecoveryStore`, including the distinct trusted incompatibility outcome and fail-closed unknown/dirty states;
- startup reconciliation handling of an incompatible recovery store and reachability of the closed `INCOMPATIBLE_VERSION` preview result through the public application entry;
- the existing Issue #13 recovery contract and ADR-0003 storage/write boundary used after explicit confirmation;
- PR-associated GitHub Actions results on the exact audited implementation head.

No production source, Launcher database schema, recovery database schema, migration, or layout write path is changed by PR #92.

## Criteria check

- **Issue #84 FR-004 — application-owned read-only inspection:** accepted. Preview inspection is owned by `LayoutApplicationModule`, uses the typed inspection projection rather than exposing `RecoveryStorePort` or persisted recovery data, and acquires the ordinary run/writer serialization required before authoritative current-state capture. The tested inspection paths do not enter checkpoint, lifecycle, retention-cleanup, recovery, layout-write, reload, or diagnostic mutation behavior.
- **Issue #84 FR-005 — revision-bound opaque confirmation:** accepted. A restorable preview returns an opaque capability only. `LayoutApplicationModule` keeps the point ID and captured expected revision in an identity-bound internal registry, consumes the capability once, creates the existing `RecoveryRequest` locally, and delegates to the same application-level recovery behavior as public recovery. Forged or reused capabilities cannot enter recovery, and readiness/current revision are rechecked after preview.
- **Issue #84 FR-006 — closed fail-closed classification:** accepted. Missing, expired, corrupt, incompatible, already-restored, unresolved, lock-state-unavailable, store-unavailable, writer-busy, and concurrent outcomes remain typed and closed. PR #92 specifically covers the #89 trusted authoritative incompatibility projection and proves it maps to `NotRestorable(INCOMPATIBLE_VERSION)` before current-layout capture and without mutation.
- **Issue #84 NFR-001 — inspection no-write boundary:** accepted. Protocol and module tests assert zero recovery-store/lifecycle/layout/reload mutation for inspection. The production projection read remains the accepted #89 fenced SQLite-free snapshot path. PR #92 changes no production storage code, so the API 26/API 35 physical no-write evidence accepted by the independent PR #90 audit remains applicable to this unchanged dependency.
- **Issue #84 NFR-002 — uncertainty fails closed:** accepted. Unavailable/unknown/dirty inspection state does not fall back to authoritative SQLite inspection, and trusted incompatibility remains distinguishable from generic unavailability. No recovery authorization is inferred from a preview result alone.
- **Issue #84 NFR-007 — supported storage/platform boundary:** accepted. The preview continues to depend on the #89 app-private snapshot/fence mechanism already independently verified on the required API 26/API 35 physical matrix. This PR introduces no new filesystem, backup, database-open, or Android-version behavior.
- **Issue #84 NFR-011 — independent verification:** accepted for code head `cbe4b0a94309dffeeb589c91aa79c852c1a85e15`. This independent session inspected the PR diff, production seam, dependent storage boundary, tests, accepted specs/ADR, and the PR-associated merge-gate run. `organizer-unit-tests`, `check-style`, `build-debug-apk`, repository-contract checks, and `final-status` all completed successfully for the audited head.
- **Issue #13 FR-005 / NFR-001 / NFR-002 — recovery remains revision-bound and fail-safe:** accepted. Preview confirmation does not create a second mutation protocol. It materializes the existing `RecoveryRequest(pointId, expectedCurrentRevision)` only inside the application boundary; stale revision or recovery precondition failures remain no-write outcomes, and the existing explicit recovery transaction is reused unchanged.
- **ADR-0003 — separate private recovery store and explicit recovery write-set:** accepted. Inspection does not copy or open the Launcher database as a backup mechanism, and confirmation delegates to the existing recovery path backed by the separate private recovery database and explicit preconditioned layout write-set. PR #92 changes none of this storage architecture.

## Executed test surface

The PR-associated GitHub Actions merge-gate run for the audited head was inspected directly:

```bash
./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'
./gradlew spotlessCheck
./gradlew assembleLawnWithQuickstepGithubDebug
```

All three completed with `BUILD SUCCESSFUL`. The organizer suite includes the PR #92 regression `trustedAuthoritativeIncompatibilityReturnsClosedRejectionWithoutCaptureOrMutation()` together with the existing #84 preview, confirmation, and revision-race tests. The workflow's `validate-repo-contract` and `final-status` jobs also completed successfully in run `32335308235`.

The #89 physical no-write oracle is dependency evidence rather than a PR #92 generic-CI claim. The accepted independent audit in `docs/assessment/pr-90-inspection-safe-recovery-store-read.md` records `RecoveryStoreInspectionInstrumentationTest` as 12/12 passing on both API 26 and API 35, plus deterministic snapshot-publication failure coverage on both required API levels. Because PR #92 changes only the protocol regression test and leaves that production boundary unchanged, this audit reuses that accepted evidence rather than misrepresenting it as newly executed by PR #92 CI.

## Findings

**Blocking findings: none.** The audited implementation head is accepted for the Issue #84 recovery-preview seam and the PR #92 incompatibility regression.

No independent evidence was found of preview inspection entering the recovery mutation protocol, opening the authoritative recovery SQLite store through the production inspection path, exposing raw recovery inputs, trusting unknown/dirty projection state, allowing forged or reused confirmation capabilities, skipping current-revision revalidation after confirmation, or collapsing trusted authoritative incompatibility into a generic unavailable result.

**Non-blocking process note:** the production Issue #84 implementation reached the current baseline as part of the merged PR #90 / Issue #89 integration; PR #92 itself is test-only. This audit therefore re-examined the complete existing #84 production path on PR #92's baseline instead of treating the 15-line regression-test diff as the full implementation. PR #90's accepted high-risk audit remains the independent physical no-write evidence for the unchanged #89 storage dependency.

If any non-`docs/` source change is pushed after `cbe4b0a94309dffeeb589c91aa79c852c1a85e15`, this audit must be repeated against the new code head. The audit-record commit itself is docs-only and therefore does not invalidate the recorded implementation head under the repository high-risk evidence rules.
