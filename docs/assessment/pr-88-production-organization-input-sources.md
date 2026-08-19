# High-risk audit: PR #88 feat(issue-83): compose production organization input

> Status: accepted
> Audit date: 2026-08-19

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/88
- Head SHA: 34e09cfb99bae19d6d229d4b5f5c88244636694c
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32256520745
- Criteria: specs/83-production-organization-input-sources/spec.md (AC-1, AC-2, AC-3, AC-4, AC-5, AC-6, AC-7, AC-8); docs/adr/0007-authoritative-organization-policy-sources.md (ADR-0007)

## Scope

This audit covers the full diff from merge-base `main` (branch `issue-83-stage-a-input-sources`) to head `34e09cfb99bae19d6d229d4b5f5c88244636694c` (17 files, 2583 insertions, 6 deletions). A prior independent audit at head `be54f68d8d` passed but was superseded after code-review fixes. Commits `6f81f92871` (style: format readiness branches), `11cb9fbf66` (fix: verify complete policy bundle digest), `29ee8a751f` (test: add production input instrumentation), and `34e09cfb99` (docs: record instrumentation evidence) were added after the superseded audit. All changes are additive to new modules under `lawnchair/src/app/lawnchair/organizer/rules/`, `lawnchair/src/app/lawnchair/organizer/integration/`, `tests/unit/app/lawnchair/organizer/`, and `tests/organizer-instrumentation/`. No existing production files were modified. The CI workflow `.github/workflows/ci.yml` gained a new `organizer-instrumentation-tests` job and the `final-status` gate now depends on it.

The review-fix commits specifically addressed:
- **Digest fix (11cb9fbf66):** `PolicyBundleIdentity` digest now covers the complete authoritative bundle content via `OrganizerPolicyBundle.canonicalRepresentation()` and `canonicalDigest()`. The canonical representation includes every field of rules (folder, dock, overflow, fallback, ordering), taxonomy (version, all 34 categories, fallback), classification (version, android mapping, package rules, intent rules, google/system categories), and targets (version). The bundle is built with a provisional identity, then the real digest is computed from the complete self-referential content and set via `provisional.copy(identity = ...)`. `validate()` checks that `identity.sha256 == canonicalDigest()` and returns `Corrupt` on mismatch. Two new tests verify that changing rule semantics or classification semantics under the original identity produces a different digest and is rejected as `Corrupt`.
- **Typed readiness (6f81f92871):** `InputReadinessReason` variants carry typed payloads: `SourceUnavailable(source)`, `SourceUnreadable(source)`, `UnsupportedVersion(source, actual)`, `IncompatiblePolicyBundle(rules, taxonomy, signals, targets, policyBundle)`, `InconsistentPolicyRead(expected, observed)`, `ContradictorySource(source)`, `InvalidCanonicalCapture(category)`. `UnsupportedSchema` from `CategoryOverrideSnapshotSource` maps to `InputReadinessReason.UnsupportedVersion(CATEGORY_OVERRIDE_SNAPSHOT, null)`, distinct from `SourceUnreadable(CATEGORY_OVERRIDE_SNAPSHOT)` for corruption/I/O.
- **Instrumentation test (29ee8a751f):** `ProductionOrganizationInputInstrumentationTest` exercises the production seam through `ProductionOrganizationInputComposer`, `LauncherLayoutAdapter`, and `AndroidClassificationSignalSnapshotSource`. It covers canonical capture-to-planner mapping, availability/lock preservation, `UNKNOWN` lock fail-closed, unrepresentable capture fail-closed, and same-package cross-profile evidence rejection. The test restores original DB rows in `tearDown`; it does not write layout or recovery data.

Production files reviewed in detail at this head:
- `lawnchair/src/app/lawnchair/organizer/rules/PolicyModels.kt` — `PolicyInputIdentity`, `PolicyBundleIdentity`, `ClassificationPolicy`, `FullOrganizationTargetPolicy`, `OrganizerPolicyBundle` with `canonicalRepresentation()`, `canonicalDigest()`, `validate()`, `BundleReadResult`, `v1RuleSemantics()`, `sha256Canonical()`.
- `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` — Singleton v1 authority with 34 categories, Android category mapping (int 0-7), `googleCategory=TOOLS`, `systemCategory=OTHER`, explicit empty S3/S4, self-referential digest via provisional construction.
- `lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideSnapshot.kt` — `CategoryOverrideSnapshotSource` read-only interface, `SharedPreferencesCategoryOverrideSnapshotSource` (schema-v1, generation-0 empty default, `UnsupportedSchema` for non-v1, `Unreadable` for corruption/I/O/duplicate, no migration writer).
- `lawnchair/src/app/lawnchair/organizer/integration/CompositionModels.kt` — `InputProvenance`, `OrganizationInputComposition` (Ready/NotReady), `InputReadinessReason` (7 typed variants), `CompositionDiagnostic` (opaque), `ClassificationEvidenceRequest`, `PlatformClassificationEvidence`, `PlatformEvidenceReadResult`, `ClassificationSignalSnapshotSource`, `MaterializedTargetSet`, `MaterializedSignals`.
- `lawnchair/src/app/lawnchair/organizer/integration/OrganizationInputComposer.kt` — `DefaultOrganizationInputComposer` with full capture-to-planner-input pipeline, A/E1/B/E2 dynamic-read consistency protocol (max 2 attempts), canonical capture mapping, bundle validation, S1>S2>S5 classification, full target materialization, provenance attachment.
- `lawnchair/src/app/lawnchair/organizer/integration/ProductionOrganizationInputComposer.kt` — Production entry point wiring `LayoutWriterPort`, `BuiltInOrganizerPolicyBundleSource`, `SharedPreferencesCategoryOverrideSnapshotSource`, `AndroidClassificationSignalSnapshotSource`. No UI, Flowerpot, or planner policy construction.
- `lawnchair/src/app/lawnchair/organizer/integration/AndroidClassificationSignalSnapshotSource.kt` — Android-only S2/S5 evidence adapter using `PackageManager` and `UserCache`. Read failure returns `Unreadable` (not absence). Profile isolation via `createContextAsUser`.
- `lawnchair/src/app/lawnchair/organizer/integration/FullTargetSetMaterializer.kt` — Exactly-once partition: locked/unavailable/Dock/structural/widget/legacy to Preserved; available unlocked workspace APPLICATION/DEEP_SHORTCUT/FOLDER to Movable; unknown/invalid to Invalid (NotReady); explicit empty additions. SHA-256 membership digest.

No changes to: `OrganizationInput`, `OrganizationPlanner`, `DeterministicOrganizationPlanner`, `LauncherLayoutAdapter` write paths, recovery storage, Flowerpot, UI code, or Launcher DB schema.

## Criteria check

- **AC-1 — authoritative ownership and identity (PASS)**

The four planner inputs each have exactly one production owner. `RuleSemantics` and `TaxonomyContract` are direct immutable projections from `BuiltInOrganizerPolicyBundleSource` (the sole `OrganizerPolicyBundle` authority). `ClassificationSignals` is materialized from the bundle's classification policy, the `CategoryOverrideSnapshotSource`, and `AndroidClassificationSignalSnapshotSource` (integration is adapter, not second policy owner). `TargetSet` is materialized from the bundle's target policy via `FullTargetSetMaterializer`. Every identity carries `source` (PolicySourceKind), `versionOrGeneration`, and `sha256` (validated against `[0-9a-f]{64}` regex). `InputProvenance` records all four identities plus the shared `PolicyBundleIdentity`. `BuiltInOrganizerPolicyBundleSourceTest` confirms the bundle is the sole `organization-policy-v1` authority with explicit empty S3/S4 tables and null `validate()` result. The bundle digest covers the complete authoritative content (rules, taxonomy, classification including category mapping, target policy).

- **AC-2 — consistent policy cut (PASS)**

`DefaultOrganizationInputComposer.composeFullOrganization()` implements the A/E1/B/E2 read-after-validate-retry protocol from ADR-0007 section 6. The bundle is immutable, so its identity is read once and validated. The dynamic portion (override snapshot + platform evidence) uses `repeat(MAX_DYNAMIC_ATTEMPTS)` (value 2). Each attempt reads override snapshot A, platform evidence E1, override snapshot B, platform evidence E2. Stability is verified by `dynamicCutIdentity()` which computes a `PolicyBundleIdentity` from `(bundle.identity.sha256, overrides.sha256, evidence.sha256)`. If `firstCut != secondCut`, the attempt is discarded and retried. A second unstable attempt returns `NotReady(InconsistentPolicyRead(expected, observed))`. The `OrganizationInputComposerTest.dynamicCutMismatchRetriesOnceThenSucceedsOrReturnsBothTypedCuts` verifies retry-success and retry-rejection with typed expected/observed cuts.

- **AC-3 — one canonical capture (PASS)**

`LayoutWriterCanonicalCaptureSource` wraps the existing `LayoutWriterPort.captureCurrent()` — no second snapshot, no direct SQLite access. `mapLayout`/`mapItem` projects `LayoutState` to `LayoutSnapshot` + `CapturedItem` list using only planner-public types. `mapItem` maps `CanonicalItemKind`, `PlacementState`, `ItemAvailability`, `OrganizerLockState`, `DeviceCapabilities` through the existing application-capture seam. `ProductionOrganizationInputInstrumentationTest.productionComposerMapsCanonicalCaptureAndPreservesPageDeviceProfileAvailabilityAndLock` confirms the production mapping. No UI DB access, no second planner, no second snapshot source.

- **AC-4 — complete conservation input (PASS)**

`FullTargetSetMaterializer` enforces: duplicate item IDs to `Invalid` (NotReady); every captured item appears exactly once in `TargetSet.existing`; `additions` is explicit `emptyList()`. Precedence table matches ADR-0007 section 4: locked to `Preserved`, availability != AVAILABLE to `Preserved`, Dock to `Preserved`, FolderMember to `Preserved`, AppPairMember to `Preserved`, `ItemKind.Unknown` to `Invalid`, `UnsupportedContainer` to `Invalid`, `APPWIDGET`/`CUSTOM_APPWIDGET`/`APP_PAIR`/`SHORTCUT_LEGACY` to `Preserved`, Workspace `APPLICATION`/`DEEP_SHORTCUT`/`FOLDER` to `Movable`, else `Preserved`. `FullTargetSetMaterializerTest` verifies locked/unavailable/Dock items are Preserved, structural/widget/app-pair/legacy items are Preserved, workspace folder/shortcut are Movable, and unknown kind/unsupported container reject.

- **AC-5 — fail closed (PASS)**

All failure paths produce typed `NotReady` results. Verified by code inspection: capture invalid returns `NotReady(InvalidCanonicalCapture, "capture-invalid")`; unknown lock returns `NotReady(InvalidCanonicalCapture, "capture-unknown-lock")`; unrepresentable layout returns `NotReady(InvalidCanonicalCapture, "capture-unrepresentable")`; bundle missing returns `NotReady(SourceUnavailable, "bundle-missing")`; bundle corrupt returns `NotReady(SourceUnreadable, "bundle-corrupt")`; bundle unsupported version returns `NotReady(UnsupportedVersion, "bundle-unsupported")`; `validate()` failure returns `NotReady(IncompatiblePolicyBundle, "bundle-invalid")`; override unreadable returns `NotReady(SourceUnreadable, "override-unreadable")`; override unsupported schema returns `NotReady(UnsupportedVersion, "override-unsupported-schema")`; evidence unreadable returns `NotReady(SourceUnreadable, "evidence-unreadable")`; override category outside taxonomy returns `NotReady(ContradictorySource, "override-category-invalid")`; signal contradiction returns `NotReady(ContradictorySource, "signal-contradiction")`; target partition invalid returns `NotReady(ContradictorySource, "target-partition")`; dynamic cut unstable after retry returns `NotReady(InconsistentPolicyRead, "dynamic-cut-unstable")`. No path calls `OrganizationPlanner`, creates a recovery point, calls the application writer, modifies the Launcher DB, or mutates the override store. `OrganizationInputComposerTest.bundleAndDynamicSourceFailuresRemainTypedWithoutDiagnosticParsing` verifies typed failure payloads for bundle source, unsupported bundle identity, override unsupported schema, platform evidence source, and contradictory source. `ProductionOrganizationInputInstrumentationTest.unknownLockFailsClosedBeforeAnyWriterSideEffect` and `unrepresentableCaptureFailsClosedBeforeAnyWriterSideEffect` confirm no-write behavior with `captureOnlyWriter` tracking that only `captureCurrent` was invoked.

- **AC-6 — profile and lock safety (PASS)**

`mapItem` preserves `profile` from `CanonicalItemState`. `ProfileAvailability.UNAVAILABLE` maps to `Availability.UNAVAILABLE`; other item availability values map directly from `ItemAvailability` (DISABLED, QUIET, LOCKED_PRIVATE_SPACE, UNAVAILABLE). `OrganizerLockState.UNKNOWN` causes `mapItem` to return null, which fails composition with `InvalidCanonicalCapture`. Lock is never silently converted to `unlocked`. `mapItem` returns null for `CanonicalItemKind.Unknown`, `PlacementState.UnsupportedContainer`, non-persistent refs, and missing profile. `evidenceRequest` only builds requests for `Availability.AVAILABLE` items. `CategoryOverrideSnapshotSource.read()` filters to `capturedProfiles`. `AndroidClassificationSignalSnapshotSource` uses `createContextAsUser(user)` for profile-isolated package reads. `ProductionOrganizationInputInstrumentationTest` confirms QUIET/LOCKED_PRIVATE_SPACE/DISABLED/UNAVAILABLE items are Preserved, `UNKNOWN` lock fails closed, and same-package cross-profile evidence is rejected.

- **AC-7 — deterministic composition (PASS)**

`sha256Canonical` produces deterministic SHA-256 digests. `PolicyInputIdentity` and `PolicyBundleIdentity` constructors validate SHA-256 format (64 lowercase hex chars). `OrganizerPolicyBundle.validate()` checks `identity.sha256 == canonicalDigest()` and returns `Corrupt` on mismatch. Materialized signals and targets sort items deterministically before canonicalization. `BuiltInOrganizerPolicyBundleSourceTest` confirms that changing rule semantics or classification semantics under the original identity produces a different digest and is rejected. `OrganizationInputComposerTest.unknownLockFailsClosedAndValueEquivalentInputsComposeEqually` verifies that two compositions from the same state produce value-equal `OrganizationInput` and `InputProvenance`. If content changes while retaining the same version, either the SHA-256 differs (identity mismatch) or the dynamic consistency check detects the change (retry/reject).

- **AC-8 — evidence (PASS — see Executed test surface below)**

All required verification commands executed successfully on the audited head. CI merge gate (`final-status`) passed including `organizer-instrumentation-tests`. This independent audit record satisfies the high-risk evidence requirement.

## Executed test surface

All commands executed on the audited head `34e09cfb99bae19d6d229d4b5f5c88244636694c` with clean working tree on macOS arm64 (JDK 21, Android SDK Platform 36.1, Build Tools 36.1.0).

| Command | Result |
|---|---|
| `./gradlew testLawnWithQuickstepGithubDebugUnitTest` | BUILD SUCCESSFUL in 40s (386 tasks, 17 executed) |
| `./gradlew spotlessCheck` | BUILD SUCCESSFUL in 1s (5 tasks, 2 executed) |
| `./gradlew assembleLawnWithQuickstepGithubDebug` | BUILD SUCCESSFUL in 7s (445 tasks, 5 executed) |
| `python3 tools/repo-contract/validate_repo_contract.py` | repository contract OK |
| `python3 tools/repo-contract/test_validate_repo_contract.py` | OK (11 tests passed) |

CI run https://github.com/nunu1733/NunuLauncher/actions/runs/32256520745 (merge gate, pull_request on branch `issue-83-stage-a-input-sources`):

| Job | Status |
|---|---|
| `changes` | pass |
| `validate-repo-contract` | pass |
| `check-style` | pass |
| `organizer-unit-tests` | pass |
| `organizer-instrumentation-tests` | pass (API 35 Google APIs x86_64 emulator) |
| `build-debug-apk` | pass |
| `final-status` | pass |

The `high-risk-evidence` check at https://github.com/nunu1733/NunuLauncher/actions/runs/32256520618 currently fails because the existing audit record names the superseded head `be54f68d8d`. This updated record corrects the head SHA to `34e09cfb99bae19d6d229d4b5f5c88244636694c` and references the current merge-gate CI run.

## Findings

- **F1 (PASS) — Digest covers complete bundle content.** Commit `11cb9fbf66` fixes the prior finding: `PolicyBundleIdentity` digest is now computed from `OrganizerPolicyBundle.canonicalRepresentation()` which covers every field of every identity-bearing policy projection (rules folder/dock/overflow/fallback/ordering, taxonomy version/categories/fallback, classification version/android-mapping/package-rules/intent-rules/google/system, target version). The bundle is constructed with a provisional identity, then the real digest is computed from the complete self-referential content. `validate()` checks `identity.sha256 == canonicalDigest()` and returns `Corrupt` on mismatch. Two new tests confirm that changing rule semantics or classification semantics under the original identity produces a different digest and is rejected. The canonicalization uses sorted entries for maps and joinToString for lists, ensuring deterministic ordering. The format includes field-name prefixes (e.g., `rule.folder.minGroupSize=`) which prevents structural ambiguity. No canonicalization weaknesses were identified: field order is deterministic, all authoritative fields are included, and no fields are omitted.

- **F2 (PASS) — Typed readiness payloads.** `InputReadinessReason` variants carry typed payloads directly: `UnsupportedVersion` carries `source` and `actual` identity, `InconsistentPolicyRead` carries `expected` and `observed` bundle identities, `IncompatiblePolicyBundle` carries all four input identities plus the bundle identity. `UnsupportedSchema` from `CategoryOverrideSnapshotSource` maps to `InputReadinessReason.UnsupportedVersion(CATEGORY_OVERRIDE_SNAPSHOT, null)`, distinct from `SourceUnreadable(CATEGORY_OVERRIDE_SNAPSHOT)` for corruption/I/O. The `OrganizationInputComposerTest.bundleAndDynamicSourceFailuresRemainTypedWithoutDiagnosticParsing` test verifies that callers can distinguish these states without parsing `CompositionDiagnostic` codes.

- **F3 (PASS) — Instrumentation test exercises production seam without writing layout/recovery data.** `ProductionOrganizationInputInstrumentationTest` exercises through `ProductionOrganizationInputComposer`, `LauncherLayoutAdapter`, and `AndroidClassificationSignalSnapshotSource`. The test inserts a fixture row, composes, and restores original rows in `tearDown` via DB transaction. The `captureOnlyWriter` proxy in fail-closed tests tracks writer method invocations, confirming only `captureCurrent` is called and no write/recovery mutation occurs. CI job `organizer-instrumentation-tests` passed on API 35 Google APIs x86_64 emulator.

- **F4 (PASS) — No implicit defaults.** Every source-unavailable path was traced: bundle missing/corrupt/unsupported, override unreadable/unsupported, evidence unreadable, target partition invalid, capture invalid/unrepresentable, dynamic cut unstable. None substitutes an implicit default rule, taxonomy, target set, or empty classification. All return `NotReady` before planner invocation.

- **F5 (PASS) — No DB write, recovery write, or UI integration.** The entire change set is read-only with respect to layout data, recovery storage, and the override store. No `LauncherLayoutAdapter` write path, `LayoutWriterPort` write, recovery point, or UI composable was added or modified. `SharedPreferencesCategoryOverrideSnapshotSource` is read-only; it has no `put`, `edit`, `apply`, or `commit` calls. `ProductionOrganizationInputComposer` delegates to `DefaultOrganizationInputComposer` and provides only canonical capture, bundle, override, and platform evidence sources — no UI preference, Flowerpot, or planner policy construction.

- **F6 (PASS) — No planner public type modified.** `git diff bc60ee52e2..HEAD --stat -- '*/organizer/planning/*'` produces no output. `OrganizationInput`, `OrganizationPlanner`, `RuleSemantics`, `TaxonomyContract`, `ClassificationSignals`, `TargetSet`, and all other planner types are consumed as-is.

- **F7 (PASS) — S1 override read-side is schema-v1 fail-closed with no migration writer.** `SharedPreferencesCategoryOverrideSnapshotSource`: schema version is `SCHEMA_V1 = 1`; physical absence returns `Ready(emptySnapshot())` with generation 0; schema != 1 returns `UnsupportedSchema`; generation < 0 or entries null returns `Unreadable`; duplicate keys return `Unreadable`; `RuntimeException` returns `Unreadable`. No `SharedPreferences.Editor` usage, no `apply()`/`commit()`, no migration writer.

## Audit verdict

**PASS.** The review-remediation commits (`6f81f92871`, `11cb9fbf66`, `29ee8a751f`, `34e09cfb99`) resolve all prior findings. The digest fix covers the complete authoritative bundle content with deterministic canonicalization. Typed readiness payloads carry source/identity/cut data directly. The instrumentation test exercises the production seam on emulator without writing layout/recovery data. All fail-closed/no-write/precedence properties from the prior audit are preserved at this head. Local verification (unit tests, spotlessCheck, assemble, repo-contract) and CI merge gate (final-status, organizer-instrumentation-tests) are green. The high-risk evidence gate is expected to pass once this updated record is committed to the PR branch.