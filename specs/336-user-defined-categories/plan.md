---
issue: "#336"
status: draft
spec: ./spec.md
updated: 2026-09-17
---

# Implementation plan — User-defined categories as first-class Organizer taxonomy

> **Stage gate:** This plan and its companion `spec.md` are implemented only after acceptance on Issue [#336][1]. The baseline is `main` at merge commit `3170c57e32fd6ec40cc592dd10e8535b2999d4b7` (PR #339 / Issue #329; the merge touches only the exchange/personalization area and no file in this plan's change surface). Any implementation discovery that contradicts an accepted decision is a stop condition, not permission for an implicit alternative.

## Current evidence

| Fact | Source |
|---|---|
| `OrganizationInput.taxonomy: TaxonomyContract` is the planner's only category surface; signals carry `candidate: CategoryId` | `organizer/planning/OrganizationInput.kt` |
| Bundle validation pins taxonomy v1 (34 IDs), `OTHER` fallback, and the bundle digest over taxonomy content | `organizer/rules/PolicyModels.kt` (`OrganizerPolicyBundle.canonicalRepresentation/validate`) |
| Planner rejects out-of-taxonomy signal candidates (`UNKNOWN_CATEGORY`) and non-distinct category sets | `organizer/planning/PlanningValidation.kt` (`checkUnknownCategory`, `checkInvalidRules`) |
| Classification resolves S1→S6 precedence over taxonomy members; S6 falls back to `OTHER` | `organizer/planning/PlanningClassification.kt` |
| Folder formation groups by `(profile, CategoryId)`, skips the fallback group; contiguous ordering compares `(profile, fallbackFlag, category, …)` | `organizer/planning/FolderFormation.kt`, `organizer/planning/FullRunExecution.kt` (`categoryContiguousOrder`) |
| `FolderNaming.FromCategory(CategoryId)` is plan-canonical; production resolver maps via the exhaustive built-in presentation table with generic fallback | `organizer/planning/PlanningResult.kt`, `organizer/ui/GeneratedFolderTitles.kt` |
| Override AtomicFile store: schema=1, `pkg\|profile\|category` entries, one mutex access boundary, optimistic `(generation, digest)`, legacy marker migration | `organizer/rules/CategoryOverrideStore.kt` |
| Composer dynamic cut = `sha256(bundle ‖ overrides ‖ evidence ‖ strategy selection)`, double-read with max 2 attempts; override membership check `OVERRIDE_CATEGORY_INVALID` against the built-in set | `organizer/integration/OrganizationInputComposer.kt` (`dynamicCutIdentity`, `materializeSignals`) |
| Authoring coordinator acquires `OrganizationOperationLease.tryAcquire(AUTHORING)`; one global admission domain shared with run/recovery | `organizer/ui/CategoryOverrideAuthoring.kt`, `organizer/ui/OrganizationOperationLease.kt` |
| Diagnostics are a closed vocabulary; new readiness codes require the same-PR doc update | `docs/engineering/organizer-diagnostics.md` |
| Provenance precedent for an optional/empty dynamic source sentinel: `PERSONALIZED_INTENT` no-intent identity | `organizer/rules/PolicyModels.kt` (`PolicySourceKind`), #204 spec |

## Design decisions

| Decision | Rule |
|---|---|
| Identity types | Add `CategoryIdentity` (`BuiltIn`/`UserDefined`), `UserCategoryId` (canonical UUID v4 string), and `UserDefinedCategory(id, displayName)` in the planning domain. Rename-invariant canonical order: built-in byte order, then user-defined ID byte order. |
| Planner surface | `OrganizationInput` carries one catalog surface (built-in `TaxonomyContract` + user-defined entries + combined membership accessors) instead of the bare `TaxonomyContract`; all planner consumers (validation, classification, folder formation, ordering, canonicalization) switch to it once. |
| Authority split | The built-in taxonomy stays the bundle's immutable projection (ADR-0007); user-defined entries live in a new Rule Management-owned `UserDefinedCategoryStore`. Bundle version/digest unchanged. |
| Catalog store | App-private AtomicFile snapshot (`schema=1`, `generation`, `digest`, `id|displayName` entries sorted by ID) behind one mutex access boundary with recovery-aware reads, `startWrite/finishWrite/failWrite`, post-write verification, typed results, backup exclusion. Physical absence = defined empty generation-0. |
| Catalog capacity | Bounded (max 64 entries) so the planner input stays bounded; overflow is a typed create failure. |
| Signals | `ClassificationSignal.candidate` becomes `CategoryIdentity`. Only S1 may carry a user-defined candidate; planner validation rejects non-S1 user-defined candidates as a typed failure. |
| Override store v2 | Identity-typed entry encoding (`b\|…` / `u\|…`), schema 2, read-validate-publish migration from schema 1 (one generation bump, fail-closed on failure), membership validated against the active catalog at write time. #99 identity surfaces, legacy marker, lease, and no-layout-mutation guarantees unchanged. |
| Delete protocol | Overrides-first two-store protocol under one lease admission (see spec); commit only after both publications verify; no silent remap; retryable failure states. |
| Provenance/cut | New `PolicySourceKind` row, mandatory with an empty-catalog sentinel identity; catalog identity joins the dynamic cut (read + re-read, two attempts). New closed `InputCompositionCode`s; `docs/engineering/organizer-diagnostics.md` updated in the same PR. |
| Folder naming | New `FolderNaming` variant carrying the `UserCategoryId`; materializer resolves the display name once from the current catalog with the generic-fallback total lookup; canonical plan bytes carry IDs only. |

## Change surface

| Path | Change | Boundary preserved |
|---|---|---|
| `organizer/planning/Identity.kt` (+ small new file for `CategoryIdentity`/`UserDefinedCategory`) | Add `UserCategoryId`, `CategoryIdentity`, `UserDefinedCategory`, canonical ordering helpers | Pure planning domain; no platform types |
| `organizer/planning/OrganizationInput.kt` | Replace `taxonomy: TaxonomyContract` with the catalog surface; identity-typed signal candidate | Single planner-facing catalog; `TaxonomyContract` remains bundle-owned |
| `organizer/planning/PlanningValidation.kt` | Membership against the catalog; new typed rejection for non-S1 user-defined candidates | Fail-closed defense-in-depth unchanged |
| `organizer/planning/PlanningClassification.kt`, `FolderFormation.kt`, `FullRunExecution.kt`, `PlanningPlacement.kt` | Identity-typed decisions, grouping keys, contiguous ordering | Determinism/idempotence restated over mixed catalogs |
| `organizer/planning/PlanningResult.kt`, `PlanningResultCanonicalization.kt` | `CategoryDecision` identity-typed; new `FolderNaming` variant; canonical bytes carry IDs | Rename does not change canonical plan bytes |
| `organizer/rules/PolicyModels.kt` | Add `PolicySourceKind.USER_DEFINED_CATEGORY_CATALOG` | Existing kinds/identities unchanged |
| `organizer/rules/UserDefinedCategoryStore.kt` (new) | Codec, atomic access boundary, typed read/mutation results, ID minting, name validation, capacity bound | #99 access-boundary pattern; no SharedPreferences |
| `organizer/rules/CategoryOverrideStore.kt` | Schema-2 identity-typed entries + schema-1→2 migration; catalog-aware validation | Filtered composer-visible semantics and legacy marker unchanged |
| `organizer/integration/OrganizationInputComposer.kt` | Catalog read in the cut; provenance row + sentinel; catalog-membership checks; new typed readiness codes | Bounded two-attempt protocol; zero-write `NotReady` |
| `organizer/ui/CategoryOverrideAuthoring.kt`, `CategoryOverrideAuthoringCoordinator` | Lease-covered catalog authoring (create/rename/delete incl. overrides-first delete) and catalog-sourced selector options | No direct store/DB/planner access from UI |
| `organizer/ui/GeneratedFolderTitles.kt` | Resolve the user-defined naming variant from the catalog | Total lookup, generic fallback, no raw IDs |
| `ui/preferences/destinations/CustomCategoryPreferences.kt` (new), `CategoryOverridePreferences.kt`, `PreferenceRoutes.kt`, `PreferenceNavigation.kt`, `HomeScreenPreferences.kt`, `strings.xml` | Management UI + "Custom" marker in the assignment selector + localized strings | Existing settings/navigation conventions; a11y bar per spec |
| `res/xml/backupscheme.xml` | Verify/add exclusion for the catalog store file | Local-only policy |
| `docs/engineering/organizer-diagnostics.md` | New closed readiness codes | 1:1 code/enum mapping retained |
| `docs/product/requirements.md`, `CONTEXT.md`, `DESIGN.md` | FR-010 wording extension; new domain terms; gate-5 source pointer | Same-PR truth updates |
| `tests/unit/app/lawnchair/organizer/**`, `tests/organizer-instrumentation/**` | Fixtures and evidence per verification plan | Same seams as production |

No Launcher DB schema, `favorites` mutation, `organizer/application/**` writer/recovery change, permission, network, or background work is planned.

## Implementation sequence

1. **Planning identity + catalog contract (with failing tests first).** Add the identity types and the catalog surface; extend validation/classification/folder formation/ordering/canonicalization; prove built-in-only equivalence and mixed-catalog determinism/idempotence/property coverage at the `plan` seam.
2. **Catalog store.** Codec round-trip, access-boundary serialization, typed reads (empty/unreadable/unsupported), create/rename/delete mutations with generation/digest/conflict/no-op semantics, ID minting, name validation, capacity bound, backup exclusion check.
3. **Override store schema 2.** Identity-typed codec + schema-1→2 read-validate-publish migration (fail-closed), catalog-aware membership validation, dangling-user-reference composer fail-closed test.
4. **Composer integration.** Provenance row + sentinel, cut participation, new typed codes, diagnostics doc update; run-change isolation through the lease.
5. **Authoring coordinator + UI.** Lease-covered create/rename/delete (overrides-first), selector extension with the Custom marker, management destination, strings, a11y semantics.
6. **End-to-end evidence.** Fresh-run consumption of user-defined assignments, folder titles, delete flow, downgrade/corruption/conflict paths, connected/device evidence.

## Failure and compatibility matrix (new surfaces)

| Condition | Result | Store/composition effect |
|---|---|---|
| Create with invalid/duplicate-capacity name or catalog full | Typed failure | No write |
| Rename to invalid name / conflicting concurrent edit | Typed failure / `Conflict` | No write; reload + explicit retry |
| Delete with assignments | Overrides removed atomically, then catalog published | Removed apps resume S2–S6 on next fresh run; no remap |
| Delete step 2 fails after step 1 | Typed failure; empty category remains; retry deletes it | Composition stays well-defined throughout |
| Override targeting a deleted/unknown user-defined ID (external corruption) | Composer typed `NotReady` (zero-write) | Fail-closed; no remap |
| Catalog unreadable/digest-invalid/duplicate/newer schema | Editor unavailable; composer typed `NotReady` | No repair, no default, no partial catalog |
| Schema-1→2 migration failure | Typed non-success; schema-1 stays authoritative | No mutation admitted |
| Edit during active run/recovery/authoring | Typed busy | Existing run/preview/recovery unchanged |
| Older binary after schema-2 migration | `UnsupportedSchema` fail-closed | Home layout unaffected |

## Verification plan

| Surface | Required proof |
|---|---|
| Planning unit tests | Identity ordering/equality; catalog membership validation; non-S1 user-defined rejection; folder formation + contiguous ordering over mixed catalogs; rename-invariance of canonical plan bytes; built-in-only byte-equivalence fixture; determinism/idempotence/property corpus extension |
| Store unit tests | Codec round-trip; generation/digest/conflict/no-op; recovery-aware reads; `failWrite`/verification-failure injection; capacity bound; name validation; ID minting determinism of format |
| Override-store tests | Schema-1→2 migration success/failure; identity-typed entries; catalog-membership validation; #99 semantics preserved (identities, legacy marker, lease) |
| Composer integration tests | Provenance row + sentinel; cut inclusion + instability; catalog unreadable/unsupported fail-closed; dangling reference fail-closed; fresh-run S1 consumption for user-defined categories |
| UI tests | Management flows; Custom marker; typed states; TalkBack/focus/keyboard/switch/200% font; no raw IDs |
| Connected instrumentation | Create/rename/delete/assign/remove on device; folder title resolution; backup exclusion; downgrade path |
| Repository gates | `python3 tools/repo-contract/validate_repo_contract.py`, `python3 tools/repo-contract/test_validate_repo_contract.py`, `./gradlew spotlessCheck`, `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`, debug APK build, PR CI `final-status` |

## Migration, rollback, and release

No Launcher DB migration. App-private evolutions: override store schema 1→2 (read-validate-publish, fail-closed) and the new catalog store. Rollback is binary rollback only: old binaries ignore the catalog store and fail closed on schema-2 overrides without reading entries; neither store is rewritten downward; backup/restore never maps or restores either store. Release evidence records both store schemas, migration results, and downgrade behavior.

## Stop conditions

Stop and return to the owning contract if: the planner cannot keep byte-identical built-in-only plans with the catalog surface; rename-invariant ordering cannot be maintained without locale dependence; the catalog identity cannot join the cut without weakening the bounded protocol; the overrides-first delete protocol cannot keep every intermediate state fail-closed or benign; schema-2 migration cannot preserve #99's identity semantics; or UI authoring would require direct layout/recovery/store access. Each case needs a new decision on Issue #336 (and an ADR where the trade-off is durable) before implementation resumes.

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/336 "Issue #336 — User-defined categories as first-class taxonomy"
[2]: ./spec.md "Spec 336"
[3]: ../../docs/adr/0007-authoritative-organization-policy-sources.md "ADR-0007"
[4]: ../../specs/99-user-authored-category-overrides/spec.md "Issue #99 specification"
[5]: ../../docs/engineering/organizer-diagnostics.md "Organizer diagnostics contract"
[6]: ../../docs/engineering/quality-strategy.md "Quality strategy"
