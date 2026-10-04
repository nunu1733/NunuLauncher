---
issue: "#336"
status: accepted
spec: ./spec.md
updated: 2026-09-17
---

# Implementation plan — User-defined categories as first-class Organizer taxonomy

> **Stage gate:** This plan and its companion `spec.md` were accepted on Issue [#336][1] (ChatGPT re-review 2 **Approved** at head `0cff39b7a1`, no blocking/required findings — [verdict][7]). The baseline is `main` at merge commit `3170c57e32fd6ec40cc592dd10e8535b2999d4b7` (PR #339 / Issue #329; the merge touches only the exchange/personalization area and no file in this plan's change surface). Any implementation discovery that contradicts an accepted decision is a stop condition, not permission for an implicit alternative.

## Current evidence

| Fact | Source |
|---|---|
| `OrganizationInput.taxonomy: TaxonomyContract` is the planner's only category surface; signals carry `candidate: CategoryId` | `organizer/planning/OrganizationInput.kt` |
| Bundle validation pins taxonomy v1 (34 IDs), `OTHER` fallback, and the bundle digest over taxonomy content | `organizer/rules/PolicyModels.kt` (`OrganizerPolicyBundle.canonicalRepresentation/validate`) |
| Planner rejects out-of-taxonomy signal candidates (`UNKNOWN_CATEGORY`) and non-distinct category sets | `organizer/planning/PlanningValidation.kt` (`checkUnknownCategory`, `checkInvalidRules`) |
| Classification resolves S1→S6 precedence over taxonomy members; S6 falls back to `OTHER` | `organizer/planning/PlanningClassification.kt` |
| Folder formation groups by `(profile, CategoryId)`, skips the fallback group; contiguous ordering compares `(profile, fallbackFlag, category, …)` | `organizer/planning/FolderFormation.kt`, `organizer/planning/FullRunExecution.kt` (`categoryContiguousOrder`) |
| `FolderNaming.FromCategory(CategoryId)` is plan-canonical; production resolver maps via the exhaustive built-in presentation table with generic fallback | `organizer/planning/PlanningResult.kt`, `organizer/ui/GeneratedFolderTitles.kt` |
| `OrganizationPlanMaterializer.materialize(input, result, sourceState, titleResolver, …)` validates `result.taxonomyVersion == input.taxonomy.version` and receives the title resolver **per call**; `LayoutApplicationModule`/`PlanPreviewProtocol` hold the constructor-injected resolver | `organizer/application/actions/OrganizationPlanMaterializer.kt`, `organizer/application/protocol/*.kt` |
| Pre-336 override codec turns any non-`schema=1` header into a decode failure, surfaced as typed `Unreadable` / composer `OVERRIDE_UNREADABLE` — this, not `UnsupportedSchema`, is what an old binary observably does today | `organizer/rules/CategoryOverrideStore.kt` (`CategoryOverrideFullStoreCodec.decode`, `readAtomicStoredIfPresentLocked`) |
| #331 export carries each candidate's resolved category as a string; the scope-binding gate digests `identity + availability + resolved category` | `organizer/personalization/ContextExportBuilder.kt`, `organizer/personalization/exchange/ScopeBindingGate.kt`, `SessionExportReconstructor.kt` |
| `InputProvenance` and the closed `InputCompositionCode` vocabulary live in the integration models file | `organizer/integration/CompositionModels.kt` |
| Override AtomicFile store: schema=1, `pkg\|profile\|category` entries, one mutex access boundary, optimistic `(generation, digest)`, legacy marker migration | `organizer/rules/CategoryOverrideStore.kt` |
| Composer dynamic cut = `sha256(bundle ‖ overrides ‖ evidence ‖ strategy selection)`, double-read with max 2 attempts; override membership check `OVERRIDE_CATEGORY_INVALID` against the built-in set | `organizer/integration/OrganizationInputComposer.kt` (`dynamicCutIdentity`, `materializeSignals`) |
| Authoring coordinator acquires `OrganizationOperationLease.tryAcquire(AUTHORING)`; one global admission domain shared with run/recovery | `organizer/ui/CategoryOverrideAuthoring.kt`, `organizer/ui/OrganizationOperationLease.kt` |
| Diagnostics are a closed vocabulary; new readiness codes require the same-PR doc update | `docs/engineering/organizer-diagnostics.md` |
| Provenance precedent for an optional/empty dynamic source sentinel: `PERSONALIZED_INTENT` no-intent identity | `organizer/rules/PolicyModels.kt` (`PolicySourceKind`), #204 spec |

## Design decisions

| Decision | Rule |
|---|---|
| Identity types | Add `CategoryIdentity` (`BuiltIn`/`UserDefined`), `UserCategoryId` (canonical UUID v4 string), and `UserDefinedCategory(id, displayName)` in the planning domain. Rename-invariant canonical order: built-in byte order, then user-defined ID byte order. Display names: trim + NFC normalize, 1–50 code points, no `\|`/line breaks, **unique within the catalog** (typed invalid/duplicate-name failures); the store normalizes before validation and persists the normalized form. |
| Planner surface | `OrganizationInput.taxonomy: TaxonomyContract` **stays** the built-in contract (application-side `taxonomyVersion` semantics unchanged); a new `OrganizationInput.catalog: ActiveCategoryCatalog` carries combined membership and the user-defined entries. Planner validation rejects `catalog.builtIn != input.taxonomy`. All planner consumers (validation, classification, folder formation, ordering, canonicalization) read the catalog. |
| Authority split | The built-in taxonomy stays the bundle's immutable projection (ADR-0007); user-defined entries live in a new Rule Management-owned `UserDefinedCategoryStore`. Bundle version/digest unchanged. |
| Catalog store | App-private AtomicFile snapshot (`schema=1`, `generation`, `digest`, `id\|displayName` entries sorted by ID) behind one mutex access boundary with recovery-aware reads, `startWrite/finishWrite/failWrite`, post-write verification, typed results, backup exclusion. Physical absence = defined empty generation-0. Capacity bound: 64 entries. |
| Signals | `ClassificationSignal.candidate` becomes `CategoryIdentity`. Only S1 may carry a user-defined candidate; planner validation rejects non-S1 user-defined candidates as a typed failure. |
| Override store v2 | Identity-typed entry encoding (`b\|…` / `u\|…`), schema 2, read-validate-publish migration from schema 1 (one generation bump, fail-closed on failure), membership validated against the active catalog at write time. #99 identity surfaces, legacy marker, lease, and no-layout-mutation guarantees unchanged. Downgrade expectation: pre-336 binaries observably return their existing `Unreadable`/`OVERRIDE_UNREADABLE` fail-closed outcome on schema-2 data; `UnsupportedSchema` is claimed only for a current binary reading a newer schema. |
| Folder title binding | Custom titles resolve from **the same `OrganizationInput`'s catalog snapshot**, never a fresh store read: `LayoutApplicationModule`/`PlanPreviewProtocol` combine the injected `FolderTitleResolver` presentation with a total lookup over `input.catalog` at the `materialize` call; `FolderTitleResolver`'s contract and the materializer's `taxonomyVersion` check stay unchanged; preview and apply consume the same creation-time title. |
| Delete protocol | Overrides-first two-store protocol under one lease admission (see spec); commit only after both publications verify; no silent remap. Step-2 failure leaves assignments durably removed + empty category present, rendered truthfully and completed by retry. Not a cross-store transaction. |
| Provenance/cut | New `PolicySourceKind` row, mandatory with an empty-catalog sentinel identity; catalog identity joins the dynamic cut (read + re-read, two attempts). New closed `InputCompositionCode`s and the `CompositionModels.kt` provenance wiring; `docs/engineering/organizer-diagnostics.md` updated in the same PR. |
| Exchange projection | Two separated layers: (a) **export presentation** — user-defined classifications are absent-category fields in `ExportItem`/`groupSemantic` (no raw ID, no display name); (b) **session-local freshness** — `CandidateScopeIdentity.canonicalRow()` and the `SourceContextIdentity`-owned placed-item projections digest the resolved `CategoryIdentity` (kind + stable ID), so raw IDs touch only the one-way digest, A→B reassignment and assigned-category deletion are detected, and create/rename/unused-delete keep sessions valid. #204 intents keep built-in-only addressing. Privacy tests verify both: no raw ID/name in export/session fields, and internal digest sensitivity to user-defined identity changes. |

## Change surface

| Path | Change | Boundary preserved |
|---|---|---|
| `organizer/planning/Identity.kt` (+ small new file for `CategoryIdentity`/`UserDefinedCategory`) | Add `UserCategoryId`, `CategoryIdentity`, `UserDefinedCategory`, canonical ordering helpers | Pure planning domain; no platform types |
| `organizer/planning/OrganizationInput.kt` | Add `catalog: ActiveCategoryCatalog` (built-in embedded + user-defined entries + combined membership); identity-typed signal candidate; `taxonomy` field retained as the built-in contract | Single planner-facing catalog surface; materializer `taxonomyVersion` semantics unchanged |
| `organizer/planning/PlanningValidation.kt` | Membership against the catalog; catalog/built-in consistency invariant; new typed rejection for non-S1 user-defined candidates | Fail-closed defense-in-depth unchanged |
| `organizer/planning/PlanningClassification.kt`, `FolderFormation.kt`, `FullRunExecution.kt`, `PlanningPlacement.kt` | Identity-typed decisions, grouping keys, contiguous ordering | Determinism/idempotence restated over mixed catalogs |
| `organizer/planning/PlanningResult.kt`, `PlanningResultCanonicalization.kt` | `CategoryDecision` identity-typed; new `FolderNaming` variant; canonical bytes carry IDs | Rename does not change canonical plan bytes |
| `organizer/rules/PolicyModels.kt` | Add `PolicySourceKind.USER_DEFINED_CATEGORY_CATALOG` | Existing kinds/identities unchanged |
| `organizer/rules/UserDefinedCategoryStore.kt` (new) | Codec, atomic access boundary, typed read/mutation results, ID minting, name normalization+validation (trim/NFC/length/uniqueness), capacity bound | #99 access-boundary pattern; no SharedPreferences |
| `organizer/rules/CategoryOverrideStore.kt` | Schema-2 identity-typed entries + schema-1→2 migration; catalog-aware validation | Filtered composer-visible semantics and legacy marker unchanged; old-binary `Unreadable` behavior untouched |
| `organizer/integration/CompositionModels.kt` | Provenance-kind wiring + new closed readiness codes | Closed-vocabulary 1:1 mapping |
| `organizer/integration/OrganizationInputComposer.kt` | Catalog read in the cut; provenance row + sentinel; catalog-membership checks; new typed readiness codes | Bounded two-attempt protocol; zero-write `NotReady` |
| `organizer/application/protocol/LayoutApplicationModule.kt`, `PlanPreviewProtocol.kt` | Bind the per-composition user-title lookup (from `input.catalog`) to the injected `FolderTitleResolver` presentation at the `materialize` call | `FolderTitleResolver` contract and materializer validation unchanged; no store access from application |
| `organizer/application/public/FolderTitleResolver.kt` | Contract unchanged (verify total-lookup/no-raw-ID/blank fail-closed) | #201 creation-time title semantics preserved |
| `organizer/personalization/ContextExportBuilder.kt`, `ContextExportModels.kt` | Export presentation: user-defined classifications serialize as absent category fields (no raw ID/name anywhere in the document) | #331 export document shape otherwise unchanged; built-in exports byte-stable |
| `organizer/personalization/CandidateScopeIdentity.kt`, `SourceContextIdentity.kt`, `personalization/exchange/ScopeBindingGate.kt`, `SessionExportReconstructor.kt` | Freshness identity: canonical rows digest the resolved `CategoryIdentity` (kind + stable ID) instead of a redacted category; gate and session reconstruction consume the same identity | #331 typed mismatch semantics unchanged; raw IDs appear only inside one-way digests, never as fields |
| `organizer/ui/CategoryOverrideAuthoring.kt`, `CategoryOverrideAuthoringCoordinator` | Lease-covered catalog authoring (create/rename/delete incl. overrides-first delete) and catalog-sourced selector options; truthful partial-delete rendering | No direct store/DB/planner access from UI |
| `organizer/ui/GeneratedFolderTitles.kt` | Combinator accepting a user-title lookup so a per-composition resolver can be built | Total lookup, generic fallback, no raw IDs |
| `ui/preferences/destinations/CustomCategoryPreferences.kt` (new), `CategoryOverridePreferences.kt`, `PreferenceRoutes.kt`, `PreferenceNavigation.kt`, `HomeScreenPreferences.kt`, `strings.xml` | Management UI + "Custom" marker in the assignment selector + localized strings | Existing settings/navigation conventions; a11y bar per spec |
| `res/xml/backupscheme.xml` | Verify/add exclusion for the catalog store file | Local-only policy |
| `docs/engineering/organizer-diagnostics.md` | New closed readiness codes | 1:1 code/enum mapping retained |
| `docs/product/requirements.md`, `CONTEXT.md`, `DESIGN.md` | FR-010 wording extension; new domain terms; gate-5 source pointer | Same-PR truth updates |
| `tests/unit/app/lawnchair/organizer/**`, `tests/organizer-instrumentation/**` | Fixtures and evidence per verification plan | Same seams as production |

No Launcher DB schema, `favorites` mutation, layout-application/recovery **writer** change, permission, network, or background work is planned. The plan does make small, behavior-preserving changes inside `organizer/application/**` (the title-resolution binding in the two protocol files and the unchanged-contract `FolderTitleResolver` verification), so the resulting PR is expected to trip the repository's high-risk path insurance list: **CI `final-status` with all source jobs green on the final head plus an independent `docs/assessment/pr-<PR>-<slug>.md` audit record are required before merge**, regardless of labels.

## Implementation sequence

1. **Planning identity + catalog contract (with failing tests first).** Add the identity types and the catalog field (with the built-in consistency invariant); extend validation/classification/folder formation/ordering/canonicalization; prove built-in-only equivalence and mixed-catalog determinism/idempotence/property coverage at the `plan` seam.
2. **Catalog store.** Codec round-trip, access-boundary serialization, typed reads (empty/unreadable/unsupported), create/rename/delete mutations with generation/digest/conflict/no-op semantics, ID minting, name normalization+validation (trim/NFC/length/uniqueness), capacity bound, backup exclusion check.
3. **Override store schema 2.** Identity-typed codec + schema-1→2 read-validate-publish migration (fail-closed), catalog-aware membership validation, dangling-user-reference composer fail-closed test, old-binary observable-outcome evidence.
4. **Composer integration.** Provenance row + sentinel in `CompositionModels.kt`, cut participation, new typed codes, diagnostics doc update; run-change isolation through the lease.
5. **Title binding + exchange projection.** Per-composition resolver binding in `LayoutApplicationModule`/`PlanPreviewProtocol`; export presentation redaction plus identity-preserving session freshness digests across `ContextExportBuilder`/`CandidateScopeIdentity`/`SourceContextIdentity`/`ScopeBindingGate`/`SessionExportReconstructor` with parity tests.
6. **Authoring coordinator + UI.** Lease-covered create/rename/delete (overrides-first), selector extension with the Custom marker, management destination, strings, a11y semantics.
7. **End-to-end evidence.** Fresh-run consumption of user-defined assignments, folder titles from the bound snapshot, delete flow incl. truthful partial state, downgrade/corruption/conflict paths, connected/device evidence.

## Failure and compatibility matrix (new surfaces)

| Condition | Result | Store/composition effect |
|---|---|---|
| Create with invalid name / duplicate name / catalog full | Typed `InvalidName` / `DuplicateName` / capacity failure | No write |
| Rename to invalid or colliding name / conflicting concurrent edit | Typed failure / `Conflict` | No write; reload + explicit retry |
| Delete with assignments | Overrides removed atomically, then catalog published | Removed apps resume S2–S6 on next fresh run; no remap |
| Delete step 2 fails after step 1 | Typed failure; UI reloads and shows assignments removed + empty category still present; retry completes the delete | Durable partial state is benign and truthful; composition stays well-defined throughout |
| Override targeting a deleted/unknown user-defined ID (external corruption only) | Composer typed `NotReady` (zero-write) | Fail-closed; no remap; unreachable via the product delete protocol |
| Catalog unreadable/digest-invalid/duplicate/newer schema | Editor unavailable; composer typed `NotReady` | No repair, no default, no partial catalog |
| Schema-1→2 migration failure | Typed non-success; schema-1 stays authoritative | No mutation admitted |
| Edit during active run/recovery/authoring | Typed busy | Existing run/preview/recovery unchanged |
| Pre-336 binary on schema-2 override snapshot | Existing old-binary fail-closed outcome: `Unreadable` / composer `OVERRIDE_UNREADABLE`; never stale/empty S1, never a write | Catalog store ignored; home layout unaffected |
| User-defined-classified candidate in exchange export | Export field shows no category (no ID, no name); session freshness digest keeps the stable `CategoryIdentity` | Create/rename/unused-delete keep sessions valid; reassignment or assigned-category delete fails through the existing typed scope-mismatch path |

## Verification plan

| Surface | Required proof |
|---|---|
| Planning unit tests | Identity ordering/equality; catalog membership + built-in consistency validation; non-S1 user-defined rejection; folder formation + contiguous ordering over mixed catalogs; rename-invariance of canonical plan bytes; built-in-only byte-equivalence fixture; determinism/idempotence/property corpus extension |
| Store unit tests | Codec round-trip; generation/digest/conflict/no-op; recovery-aware reads; `failWrite`/verification-failure injection; capacity bound; name normalization (NFC/trim/length) + uniqueness validation; ID minting determinism of format |
| Override-store tests | Schema-1→2 migration success/failure; identity-typed entries; catalog-membership validation; #99 semantics preserved (identities, legacy marker, lease); pre-336 codec behavior on schema-2 header = `Unreadable` (observable downgrade outcome, unchanged code) |
| Composer integration tests | Provenance row + sentinel; cut inclusion + instability; catalog unreadable/unsupported fail-closed; dangling reference fail-closed; fresh-run S1 consumption for user-defined categories |
| Title-binding tests | Preview and apply resolve user-defined folder titles from the same `OrganizationInput`'s catalog snapshot (incl. a fixture where the store has changed since composition); generic fallback for unknown IDs; built-in resolution unchanged |
| Exchange parity tests | `ContextExportBuilder` emits no category for user-defined classifications and identical bytes for built-in-only runs; no `UserCategoryId`/display name in export documents or session record fields; `CandidateScopeIdentity` digest changes on A→B reassignment and on assigned-category delete (falls to built-in) but not on create/rename/unused-category delete; gate/session reconstruction consume the identity-preserving digest |
| UI tests | Management flows; Custom marker; typed states incl. duplicate-name and partial-delete rendering; TalkBack/focus/keyboard/switch/200% font; no raw IDs |
| Connected instrumentation | Create/rename/delete/assign/remove on device; folder title resolution; backup exclusion; downgrade path |
| Repository gates | `python3 tools/repo-contract/validate_repo_contract.py`, `python3 tools/repo-contract/test_validate_repo_contract.py`, `./gradlew spotlessCheck`, `./gradlew testLawnWithQuickstepGithubDebugUnitTest --tests 'app.lawnchair.organizer.*'`, debug APK build, PR CI `final-status`; PR touches `organizer/application/**` → high-risk path: independent audit record + green CI on final head required |

## Migration, rollback, and release

No Launcher DB migration. App-private evolutions: override store schema 1→2 (read-validate-publish, fail-closed) and the new catalog store. Rollback is binary rollback only: old binaries ignore the catalog store and observably fail closed on schema-2 overrides through their existing decode path (`Unreadable`/`OVERRIDE_UNREADABLE`) without reading entries; neither store is rewritten downward; backup/restore never maps or restores either store. Release evidence records both store schemas, migration results, and downgrade behavior (expected observable outcome on the pre-336 binary, not a retrofitted typed result).

## Stop conditions

Stop and return to the owning contract if: the planner cannot keep byte-identical built-in-only plans with the catalog surface; rename-invariant ordering cannot be maintained without locale dependence; the catalog identity cannot join the cut without weakening the bounded protocol; the overrides-first delete protocol cannot keep every intermediate state fail-closed or benign; schema-2 migration cannot preserve #99's identity semantics; or UI authoring would require direct layout/recovery/store access. Each case needs a new decision on Issue #336 (and an ADR where the trade-off is durable) before implementation resumes.

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/336 "Issue #336 — User-defined categories as first-class taxonomy"
[2]: ./spec.md "Spec 336"
[3]: ../../docs/adr/0007-authoritative-organization-policy-sources.md "ADR-0007"
[4]: ../../specs/99-user-authored-category-overrides/spec.md "Issue #99 specification"
[5]: ../../docs/engineering/organizer-diagnostics.md "Organizer diagnostics contract"
[6]: ../../docs/engineering/quality-strategy.md "Quality strategy"
[7]: https://github.com/nunu1733/NunuLauncher/issues/336#issuecomment-5701844245 "Phase1 re-review 2 — approved"
