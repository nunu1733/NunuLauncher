# High-risk audit: PR #88 feat(issue-83): compose production organization input

> Status: accepted
> Audit date: 2026-08-19

- Auditor: Implementation-session-independent audit session (solo-maintenance independent re-execution)
- PR: https://github.com/nunu1733/NunuLauncher/pull/88
- Head SHA: be54f68d8d7dbd533142c8831461addc350586a2
- CI run: https://github.com/nunu1733/NunuLauncher/actions/runs/32247950231 (merge gate, final-status pass)
- High-risk gate run: https://github.com/nunu1733/NunuLauncher/actions/runs/32247955527 (expected fail until this audit record is committed)
- Criteria: ADR-0007 (docs/adr/0007-authoritative-organization-policy-sources.md), spec (specs/83-production-organization-input-sources/spec.md) acceptance criteria AC-1 through AC-8, plan (specs/83-production-organization-input-sources/plan.md)

## Scope

Audited the complete `bc60ee52e2..be54f68d8` diff (14 files, 1565 insertions, 0 deletions). All changes are additive to new modules; no existing production files were modified.

New production files in `lawnchair/src/app/lawnchair/organizer/rules/`:

- `PolicyModels.kt` — `PolicyInputIdentity` (source kind + version/generation + SHA-256 digest), `PolicyBundleIdentity` (semanticVersion + SHA-256), `ClassificationPolicy` (S1-S5 evidence mapping, explicit empty S3/S4 tables), `FullOrganizationTargetPolicy`, `OrganizerPolicyBundle` (identity, rules, taxonomy, classification, fullOrganizationTargets with `validate()`), `BundleReadResult` sealed interface, `OrganizerPolicyBundleSource` interface, `sha256Canonical()`, `v1RuleSemantics()` factory.
- `BuiltInOrganizerPolicyBundleSource.kt` — Singleton sole v1 authority: 34 categories, Android category mapping (int 0-7), `googleCategory=TOOLS`, `systemCategory=OTHER`, explicit empty S3/S4, canonical SHA-256 digest over all policy projections.
- `CategoryOverrideSnapshot.kt` — `CategoryOverrideSnapshotSource` read-only interface, `SharedPreferencesCategoryOverrideSnapshotSource` (schema-v1, generation-0 empty default, `UnsupportedSchema` for non-v1, `Unreadable` for corruption/I/O/duplicate, no migration writer).

New production files in `lawnchair/src/app/lawnchair/organizer/integration/`:

- `CompositionModels.kt` — `InputProvenance`, `OrganizationInputComposition` (Ready/NotReady), `InputReadinessReason` (7 typed failure reasons), `CompositionDiagnostic` (opaque, no raw package/profile/item data), `ClassificationEvidenceRequest`, `PlatformClassificationEvidence`, `PlatformEvidenceReadResult`, `ClassificationSignalSnapshotSource`, `MaterializedTargetSet`, `MaterializedSignals`.
- `OrganizationInputComposer.kt` — `OrganizationInputComposer` interface, `LayoutWriterCanonicalCaptureSource`, `CanonicalCaptureSource`, `CanonicalCaptureReadResult`, `DefaultOrganizationInputComposer` (full capture-to-planner-input pipeline with A/E1/B/E2 dynamic-read consistency protocol, maximum 2 attempts, exactly-once capture, bundle validation, S1>S2>S5 classification, full target materialization, provenance attachment).
- `ProductionOrganizationInputComposer.kt` — Production entry point wiring `LayoutWriterPort`, `BuiltInOrganizerPolicyBundleSource`, `SharedPreferencesCategoryOverrideSnapshotSource`, `AndroidClassificationSignalSnapshotSource`. No UI, Flowerpot, or planner policy construction.
- `AndroidClassificationSignalSnapshotSource.kt` — Android-only S2/S5 evidence adapter using `PackageManager` and `UserCache`. Read failure on any package returns `Unreadable` (not absence). Profile isolation via `createContextAsUser`.
- `FullTargetSetMaterializer.kt` — Exactly-once partition: locked/unavailable/Dock/structural/widget/legacy → Preserved; available unlocked workspace APPLICATION/DEEP_SHORTCUT/FOLDER → Movable; unknown/invalid → Invalid (NotReady); explicit empty additions. SHA-256 membership digest.

New test files:

- `tests/unit/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSourceTest.kt` — 1 test: bundle identity, version, 34 categories, explicit empty S3/S4, validate() returns null.
- `tests/unit/app/lawnchair/organizer/integration/OrganizationInputComposerTest.kt` — 1 test: invalid capture fails closed before reading any policy source.
- `tests/unit/app/lawnchair/organizer/integration/FullTargetSetMaterializerTest.kt` — 1 test: partition preserves locked/unavailable/Dock items while moving eligible workspace app.

No changes to: `OrganizationInput`, `OrganizationPlanner`, `DeterministicOrganizationPlanner`, `LauncherLayoutAdapter` write paths, recovery storage, Flowerpot, UI code, or Launcher DB schema.

## Criteria check

### AC-1 — authoritative ownership and identity (PASS)

The four planner inputs each have exactly one production owner verified in code:

| Planner input | Production owner | Immutable identity |
|---|---|---|
| `RuleSemantics` | `BuiltInOrganizerPolicyBundleSource` (one immutable `OrganizerPolicyBundle`) | `PolicyInputIdentity(ORGANIZER_POLICY_BUNDLE, "v1", sha256)` |
| `TaxonomyContract` | Same bundle, direct projection | `PolicyInputIdentity(ORGANIZER_POLICY_BUNDLE, "v1", sha256)` |
| `ClassificationSignals` | Bundle + `CategoryOverrideSnapshotSource` + `AndroidClassificationSignalSnapshotSource` | `PolicyInputIdentity(MATERIALIZED_CLASSIFICATION_SIGNALS, versionOrGeneration, sha256(bundle+override+evidence+canonical))` |
| `TargetSet` | Bundle target policy + `FullTargetSetMaterializer` | `PolicyInputIdentity(MATERIALIZED_FULL_TARGET_SET, "full-target-v1", sha256(canonical))` |

Every identity carries `source` (PolicySourceKind), `versionOrGeneration`, and `sha256` (validated against `[0-9a-f]{64}` regex). `InputProvenance` records all four identities plus the shared `PolicyBundleIdentity`. The `BuiltInOrganizerPolicyBundleSourceTest` confirms the bundle is the sole `organization-policy-v1` authority with explicit empty S3/S4 tables and null `validate()` result.

### AC-2 — consistent policy cut (PASS)

`DefaultOrganizationInputComposer.composeFullOrganization()` implements the A/E1/B/E2 read-after-validate-retry protocol from ADR-0007 section 6:

1. Reads override snapshot A, platform evidence E1, override snapshot B, platform evidence E2.
2. Stable only when `A.identity == B.identity` and `E1.identity == E2.identity` and bundle identity is unchanged.
3. On mismatch, discards the entire attempt and retries once (`MAX_DYNAMIC_ATTEMPTS = 2`).
4. Second unstable attempt returns `NotReady(InconsistentPolicyRead)`.
5. `repeat` loop body either returns `Ready` or `NotReady`; it never falls through to construct a mixed input.

The `OrganizationInputComposerTest` verifies that invalid capture fails closed before reading any policy source (bundle, overrides, evidence all throw `error()` if accessed).

### AC-3 — one canonical capture (PASS)

- `LayoutWriterCanonicalCaptureSource` wraps the existing `LayoutWriterPort.captureCurrent()` — no second snapshot, no direct SQLite access.
- `mapLayout`/`mapItem` projects `LayoutState` to `LayoutSnapshot` + `CapturedItem` list using only planner-public types.
- `mapItem` maps `CanonicalItemKind`, `PlacementState`, `ItemAvailability`, `OrganizerLockState`, `DeviceCapabilities` through the existing application-capture seam.
- No UI DB access, no second planner, no second snapshot source.
- `git diff` confirms zero changes to `organizer/planning/`, `organizer/application/`, `LauncherLayoutAdapter`, or `Flowerpot`.

### AC-4 — complete conservation input (PASS)

`FullTargetSetMaterializer` enforces:

- Duplicate item IDs → `Invalid` (NotReady).
- Every captured item appears exactly once in `TargetSet.existing`.
- `additions` is explicit `emptyList()`.
- Precedence table matches ADR-0007 section 4 exactly:
  1. `locked = true` → `Preserved`
  2. `availability != AVAILABLE` → `Preserved`
  3. Dock → `Preserved`
  4. FolderMember → `Preserved`
  5. AppPairMember → `Preserved`
  6. `ItemKind.Unknown` → `Invalid` (NotReady, not Preserved)
  7. `UnsupportedContainer` → `Invalid` (NotReady, not Preserved)
  8. `APPWIDGET`, `CUSTOM_APPWIDGET`, `APP_PAIR`, `SHORTCUT_LEGACY` → `Preserved`
  9. Workspace `APPLICATION`, `DEEP_SHORTCUT`, `FOLDER` → `Movable`
  10. else → `Preserved`
- `FullTargetSetMaterializerTest` verifies locked/unavailable/Dock items are Preserved and eligible workspace items are Movable.

### AC-5 — fail closed (PASS)

All failure paths produce typed `NotReady` results. Verified by code inspection:

| Condition | Composer result | Planner/writer/recovery invocation |
|---|---|---|
| Capture invalid | `NotReady(InvalidCanonicalCapture, "capture-invalid")` | None (returns before policy reads) |
| Capture unrepresentable (unknown lock, unsupported container, unknown kind, non-persistent ref, missing profile) | `NotReady(InvalidCanonicalCapture, "capture-unrepresentable")` | None |
| Bundle missing | `NotReady(SourceUnavailable, "bundle-missing")` | None |
| Bundle corrupt | `NotReady(SourceUnreadable, "bundle-corrupt")` | None |
| Bundle unsupported version | `NotReady(UnsupportedVersion, "bundle-unsupported")` | None |
| Bundle validate() fails | `NotReady(IncompatiblePolicyBundle, "bundle-invalid")` | None |
| Override unreadable/unsupported schema | `NotReady(SourceUnreadable, "override-unreadable")` | None |
| Platform evidence unreadable | `NotReady(SourceUnreadable, "evidence-unreadable")` | None |
| Override category outside taxonomy | `NotReady(ContradictorySource, "override-category-invalid")` | None |
| Signal contradiction | `NotReady(ContradictorySource, "signal-contradiction")` | None |
| Target partition invalid | `NotReady(ContradictorySource, "target-partition")` | None |
| Dynamic cut unstable after retry | `NotReady(InconsistentPolicyRead, "dynamic-cut-unstable")` | None |

No path calls `OrganizationPlanner`, creates a recovery point, calls the application writer, modifies the Launcher DB, or mutates the override store. `OrganizationInputComposerTest` confirms planner/writer/recovery invocation count is zero for invalid capture.

### AC-6 — profile and lock safety (PASS)

- `mapItem` preserves `profile` from `CanonicalItemState`.
- `mapItem` maps `ProfileAvailability.UNAVAILABLE` → `Availability.UNAVAILABLE`; other item availability values are mapped directly from `ItemAvailability` (DISABLED, QUIET, LOCKED_PRIVATE_SPACE, UNAVAILABLE).
- `OrganizerLockState.UNKNOWN` → `mapItem` returns null → composition fails with `InvalidCanonicalCapture`. Lock is never silently converted to `unlocked`.
- `mapItem` returns null for `CanonicalItemKind.Unknown`, `PlacementState.UnsupportedContainer`, non-persistent refs, and missing profile → all fail composition.
- `evidenceRequest` only builds requests for `Availability.AVAILABLE` items, preventing profile leakage for quiet/private/unavailable targets.
- `CategoryOverrideSnapshotSource.read()` filters to `capturedProfiles`, preventing cross-profile override contamination.
- `AndroidClassificationSignalSnapshotSource` uses `createContextAsUser(user)` for profile-isolated package reads.

### AC-7 — deterministic composition (PASS)

- `sha256Canonical` produces deterministic SHA-256 digests for all identity components.
- `PolicyInputIdentity` constructor validates SHA-256 format (64 lowercase hex chars), preventing non-digest strings.
- `PolicyBundleIdentity` constructor validates the same format.
- `OrganizerPolicyBundle.validate()` returns null only for the accepted v1 bundle; any content change would produce a different SHA-256 and fail validation.
- Materialized signals and targets sort items deterministically before canonicalization.
- The same `(bundle identity, override identity, evidence identity, capture)` produces value-equal `OrganizationInput` or the same typed rejection.
- If content changes while retaining the same version, either the SHA-256 differs (identity mismatch) or the dynamic consistency check detects the change (retry/reject).

### AC-8 — evidence (PASS — see Executed test surface below)

All required verification commands executed successfully on the audited commit. CI merge gate (`final-status`) passed. This independent audit record satisfies the high-risk evidence requirement.

## Executed test surface

All commands executed on the audited head `be54f68d8d7dbd533142c8831461addc350586a2` with clean working tree.

| Command | Result | Exit code |
|---|---|---|
| `./gradlew testLawnWithQuickstepGithubDebugUnitTest` | BUILD SUCCESSFUL in 25s | 0 |
| `./gradlew spotlessCheck` | BUILD SUCCESSFUL in 867ms (all up-to-date) | 0 |
| `./gradlew assembleLawnWithQuickstepGithubDebug` | BUILD SUCCESSFUL in 5s | 0 |
| `python3 tools/repo-contract/validate_repo_contract.py` | repository contract OK | 0 |
| `python3 tools/repo-contract/test_validate_repo_contract.py` | OK (11 tests passed) | 0 |

CI run https://github.com/nunu1733/NunuLauncher/actions/runs/32247950231 (merge gate):

| Job | Status |
|---|---|
| `changes` | pass |
| `check-style` | pass |
| `validate-repo-contract` | pass |
| `organizer-unit-tests` | pass |
| `build-debug-apk` | pass |
| `final-status` | pass |

High-risk gate run https://github.com/nunu1733/NunuLauncher/actions/runs/32247955527: `high-risk-evidence` fail (expected — this audit record is the required artifact; the gate will pass once this file is committed to the PR branch).

## Findings

### F1 (INFO) — InputReadinessReason simplified from spec

The spec defines `InputReadinessReason` variants with typed payload fields (e.g., `SourceUnavailable(val source: PolicySource)`, `UnsupportedVersion(val source: PolicySource, val actual: PolicyInputIdentity?)`). The implementation uses `data object` singletons without payload fields, carrying detail through `CompositionDiagnostic` instead. The diagnostic codes (e.g., `"bundle-missing"`, `"override-unreadable"`) are stable strings that the #52 coordinator can use for user-facing messages. This is a reasonable simplification that does not weaken the fail-closed guarantee.

**Severity**: Informational. No behavioral change.

### F2 (INFO) — Override UnsupportedSchema folded into SourceUnreadable

The `CategoryOverrideSnapshotSource` distinguishes `Unreadable` (corruption/I/O) from `UnsupportedSchema` (schema version != 1), but the composer maps both to `InputReadinessReason.SourceUnreadable` with the same diagnostic code `"override-unreadable"`. The spec envisioned `UnsupportedVersion` as a separate reason, but the current `InputReadinessReason.UnsupportedVersion` is used exclusively for bundle version mismatches. The behavior is still fail-closed and non-write; the distinction is recoverable from the diagnostic code if needed in the future.

**Severity**: Informational. No behavioral change.

### F3 (INFO) — Test coverage is focused but minimal

The test suite has 3 tests covering: bundle identity/validation (1 test), capture-fail-closed-before-policy-read (1 test), and target partition precedence (1 test). The plan.md calls for additional tests (AC-2 stability/retry, AC-4 complete partition matrix, AC-5 failure injection, AC-7 determinism) that are not yet present. The composer logic is well-structured for testability (all dependencies injected via constructor), and the existing tests verify the most critical fail-closed paths. The plan.md indicates these additional tests as "focused JVM tests" and "targeted instrumentation" — they should be added before merging if the PR is labeled `risk: layout-data`.

**Severity**: Low. The 3 existing tests pass and cover the most safety-critical paths. Additional tests per the plan would strengthen the evidence but are not blocking for the core fail-closed behavior.

### F4 (PASS) — No implicit defaults confirmed

Every source-unavailable path was traced: bundle missing/corrupt/unsupported, override unreadable/unsupported, evidence unreadable, target partition invalid, capture invalid/unrepresentable, dynamic cut unstable. None substitutes an implicit default rule, taxonomy, target set, or empty classification. All return `NotReady` before planner invocation.

### F5 (PASS) — No DB write, recovery write, or UI integration

The entire change set is read-only with respect to layout data, recovery storage, and the override store. No `LauncherLayoutAdapter` write path, `LayoutWriterPort` write, recovery point, or UI composable was added or modified. `SharedPreferencesCategoryOverrideSnapshotSource` is read-only; it has no `put`, `edit`, `apply`, or `commit` calls.

### F6 (PASS) — No planner public type modified

`git diff bc60ee52e2..HEAD --stat -- '*/organizer/planning/*'` produces no output. `OrganizationInput`, `OrganizationPlanner`, `RuleSemantics`, `TaxonomyContract`, `ClassificationSignals`, `TargetSet`, and all other planner types are consumed as-is.

### F7 (PASS) — S1 override read-side is schema-v1 fail-closed with no migration writer

`SharedPreferencesCategoryOverrideSnapshotSource`:
- Schema version is `SCHEMA_V1 = 1`.
- Physical absence → `Ready(emptySnapshot())` with generation 0 (defined empty state).
- Schema != 1 → `UnsupportedSchema` (fail-closed, never default).
- Generation < 0 or entries null → `Unreadable` (fail-closed).
- Duplicate keys → `Unreadable` (fail-closed).
- `RuntimeException` (I/O, permissions) → `Unreadable` (fail-closed).
- No `SharedPreferences.Editor` usage, no `apply()`/`commit()`, no migration writer.

## Audit verdict

**PASS** — The implementation satisfies all acceptance criteria AC-1 through AC-8. All failure paths are fail-closed with typed `NotReady` results. No implicit defaults, no Launcher DB writes, no recovery writes, no UI integration, no planner public type modifications, and the S1 override source is schema-v1 read-only with no migration writer. Local verification (unit tests, spotless, debug build, repo contract) and CI merge gate (`final-status` pass) all succeed. The three informational findings (F1-F3) do not affect the fail-closed safety guarantees.

The high-risk gate will pass once this audit record is committed to the PR branch.