---
issue: "#99"
status: proposed
spec: ./spec.md
updated: 2026-08-22
---

# Implementation plan — User-authored category overrides for Organizer v1

> **Stage A gate:** This plan is proposed. Stage B production work begins only after Issue [#99][1] records acceptance of both this plan and `spec.md`. This document plans a minimal vertical slice that preserves the accepted #83 composition seam and does not authorize planner, layout-application, or recovery-contract changes. [2] [3]

## Baseline and design decisions

The implementation baseline is `main` after Issue #83 / PR #88. `CategoryOverrideSnapshotSource` currently establishes the app-private schema-v1 snapshot shape, defined empty generation-0 first-run state, profile-filtered reader result, and fail-closed outcomes. Its returned digest is computed over assignments visible to the supplied `capturedProfiles`, not the complete physical store. It has no writer. Stage B adds a private full-store codec/writer beside that reader under Rule Management while keeping the existing composer-visible reader result and `OrganizationInputComposer` consumption unchanged. [2] [4]

The approved Stage A decisions are summarized below. Any implementation discovery that contradicts a decision is a stop condition rather than permission to introduce an implicit alternative.

| Decision | Stage B implementation rule | Rationale |
|---|---|---|
| Authoring surface | Add one Home Screen preferences destination, **App category overrides**, with a profile-aware app list and category editor. | It reuses the existing settings/navigation convention and keeps authoring outside an organization-run preview. [5] |
| Taxonomy authority | Obtain selectable `CategoryId` membership/order/version/fallback only from the active validated `OrganizerPolicyBundle` taxonomy projection. | Prevents a UI-local taxonomy and preserves #83’s single policy authority. [3] |
| Taxonomy presentation | Render those validated IDs through an exhaustive localized resource mapping; labels/descriptions are presentation, never authority. | Missing or extra resource mappings fail build/UI contract tests instead of modifying taxonomy. |
| Mutation boundary | Add a typed Rule Management persistence-and-validation store and an organizer/UI-layer authoring coordinator; UI passes a resolved `(PackageName, ProfileId)` plus a selected category or removal command. | Keeps platform/UI and run-coordination details out of the policy source, enforces taxonomy validity defensively at the storage boundary, and keeps storage format private. [3] [4] |
| Identity model | Keep private stored identity separate from #83 composer-visible filtered identity; use stored `(generation, digest)` for conflict and return stored plus explicit verification-visible identity. | Orphan retention and captured-profile filtering must not be conflated. [4] |
| Commit and access model | Route every AtomicFile and legacy-marker read/write through one process-local `CategoryOverrideAtomicAccess` mutex. Publish one complete immutable snapshot through recovery-aware `AtomicFile.openRead()` / base-temporary-file semantics, then full-store verify; never publish through mutable `SharedPreferences`. | Ensures recovery-aware reads cannot observe a temporary or mixed generation in-process or after restart. [4] |
| Organization operation | Use one lease for run start, recovery-preview/confirmation, and authoring mutation; require a later fresh run after authoring. | A confirmed preview or recovery operation must never race authoring. [6] |
| Profile key | Derive the editor `ProfileId` by the same `UserCache` serial-to-string mapping as `LauncherLayoutAdapter`. | Prevents a second profile-key scheme and proves eventual #83 key equivalence. [9] |
| Backup and compatibility | Keep the v1 store excluded from backup/restore. Migrate valid legacy preferences to AtomicFile before authoring, then durably set legacy `schema = 2` as a rollback barrier before accepting a new-format mutation. | Old #83 readers fail closed as `UnsupportedSchema` rather than silently consuming stale or empty legacy data after downgrade. [3] |
| Diagnostics | Do not emit authoring actions into the organizer run journal; forbid raw identity/category data in logs/export. | Existing diagnostics permit only closed run information and summary counts. [7] |

## Planned change surface

| Path | Planned Stage B change | Boundary preserved |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideSnapshot.kt` and a focused `CategoryOverrideAtomicAccess` implementation | Retain filtered `CategoryOverrideSnapshotSource` behavior while adding one mutex-owned recovery-aware AtomicFile access boundary, a compatibility reader, a private full-store codec, legacy-marker migration, and typed writer results carrying stored and verification-visible identities. | Composer retains `Ready`/`Unreadable`/`UnsupportedSchema` semantics while never observing a temporary or mixed generation. |
| `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` and/or `PolicyModels.kt` | Expose a narrow validated taxonomy-ID projection for authoring; no mutable bundle or display metadata in the bundle. | `OrganizerPolicyBundle` remains immutable and Rule Management remains the sole taxonomy authority. [3] |
| `lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt` plus a shared internal profile mapper | Extract/reuse the exact `UserCache` serial-to-`ProfileId` mapping for editor identity resolution. | Editor and #83 capture use the identical profile key. [9] |
| `lawnchair/src/app/lawnchair/organizer/integration/ProductionOrganizationInputComposer.kt` | Reuse the same private override-store configuration for reader and writer wiring only if required by construction; do not change composition logic, filtered identity, or planner input types. | `OrganizationInputComposer` remains the only planner-facing override path. [2] |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | Add one internal process-local organization-operation lease spanning `start()`, recovery-preview/confirmation admission, and authoring coordinator mutation. | Rule Management does not depend on UI/run state; recovery cannot race authoring. [6] |
| `docs/product/category-taxonomy-v1.md` | Mark the old proposed override persistence/diagnostics/backup wording as superseded by ADR-0007 and Issue #99. | Prevents research-era metadata/backup rules from being treated as the current persistence contract. [3] |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | Add a serializable Home Screen route for override management. | Retain existing preference navigation and do not serialize run or write authorization. [5] |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | Bind the route to the new destination. | No direct DB or planner call from navigation. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Add the Home Screen entry row. | Existing Settings destination pattern. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/CategoryOverridePreferences.kt` (new) | Implement list, selection editor, typed result rendering, accessibility semantics, and focus restoration. | UI uses the authoring facade; it cannot access policy storage, composer internals, planner, layout DB, or recovery. |
| `res/values/strings.xml` and translated resource locations as applicable | Add localized labels, descriptions, state text, errors, and accessibility strings. | No hard-coded taxonomy or raw identity strings. |
| `res/xml/backupscheme.xml` | Verify exclusion of both the legacy override preferences file and the new AtomicFile snapshot path; add an explicit exclusion only if required by the existing scheme. | v1 local-only/no-restore policy stays intact through the authority migration. [3] |
| `tests/unit/.../organizer/rules/*` and `tests/unit/.../organizer/ui/*` | Add deterministic store, taxonomy, conflict, active-run, and privacy-shape tests. | Test the same public/internal seams used by production. |
| `tests/organizer-instrumentation/...` | Add device/profile and preference/UI integration evidence. | Validate no cross-profile fallback and no indirect layout mutation. |

No new database table, Launcher `favorites` mutation, application/recovery source file, network dependency, permission, background service, or backup payload is planned.

## Implementation sequence

### 1. Freeze the accepted contracts and create test fixtures

Before source changes, update this Stage A documentation to `accepted` only after review. In the same Stage B branch, create synthetic fixtures for two current profiles sharing a package name, a retained orphan profile, unavailable/removed profile contexts, all active-v1 categories, invalid IDs, first-run empty state, corrupt/duplicate final snapshot, unsupported schema, leftover temporary file, interrupted temporary-file write, `finishWrite()` failure, post-finish full-store verification failure, process death before/after AtomicFile finish, compose-read versus authoring-write contention, verification-read versus writer completion, legacy marker failure, rollback to old reader after one or more new-format mutations, competing writes, and blocked authoring/run/recovery admissions. Fixture package/profile identifiers must be synthetic and must never enter diagnostics fixtures. [7]

The tests must assert typed results and the correct identity surface, not the private preference string alone. For success, assert the complete stored map, schema `1`, exactly one increased generation, stored digest, durable AtomicFile final snapshot, and explicit filtered verification-visible digest. Include a fixture where an orphaned entry makes stored and composer-visible digests differ at the same generation. For every pre-finish write/finish failure, assert the old final snapshot remains the only same-process and restarted #83 reader-visible value; the temporary generation remains unreachable. For a post-finish validation failure, assert the standard reader returns typed unreadable/non-write rather than consuming it. For a successful legacy authority migration, assert the durable legacy `schema = 2` barrier precedes every new-format mutation; after rollback, the old reader must return `UnsupportedSchema`, never stale/empty `Ready`. For rejected operations, assert unchanged stored identity and complete map.

### 2. Define the writer contract inside Rule Management

Introduce a narrow persistence-and-validation contract such as `CategoryOverrideStore.mutate(request, expectedStoredIdentity): CategoryOverrideWriteResult`. The exact names may follow repository style, but the capabilities are fixed: `Set`, `Remove`, and explicit typed results for success, no-op, invalid category, taxonomy unavailable/unsupported, store unreadable/unsupported, conflict, write failure, and verification failure. The store obtains/validates the active taxonomy through Rule Management before every mutation, even when the UI has already validated the choice. The organizer/UI-layer authoring coordinator owns `TargetUnavailable` and organization-operation lease admission before invoking the store.

The contract accepts only validated domain values and never accepts a display label, raw serialized row, inferred category, planner input, or Android UI object. `expectedStoredIdentity` is the complete physical-source `(generation, digest)` token, not a #83 filtered identity. A success returns both the committed stored identity and the verification-visible identity for an explicit current profile set; it never claims that the latter will equal a future composer identity. The UI cannot infer success from a requested value; it uses the returned typed result and reloads state after conflict/failure.

### 3. Preserve canonical snapshot encoding and publish atomically

Extract the current parser and canonical renderer into a private full-store codec used by `CategoryOverrideAtomicAccess`. The decoder continues to reject blank/malformed components, duplicate `(package, profile)` keys, negative generations, incompatible schema, and runtime storage faults. Its digest covers the complete immutable snapshot entry set, sorted by profile and then package according to existing canonical UTF-8 ordering. Every full-store, composer-visible, UI, migration, and verification read enters the same access mutex and uses `AtomicFile.openRead()` / an equivalent recovery-aware wrapper; no raw final-file stream or `.new` inspection is permitted. The compatible public reader then applies the existing profile-filtered map/digest semantics. [4]

Under that same access mutex and an admitted organization-operation lease, the writer reads/validates the final complete snapshot, compares `expectedStoredIdentity`, checks taxonomy binding, materializes a new full map, and increments generation exactly once only for a state-changing request. It uses the existing organizer-recovery-style AndroidX `AtomicFile` boundary: `startWrite()`, complete encoded write plus fsync, then `finishWrite()`; a write/finish exception invokes `failWrite()`. `SharedPreferences.apply()` and `SharedPreferences.commit()` are prohibited as authoring publication paths because a failed write may otherwise leak into same-process reader state. Before success, the writer re-opens the final snapshot through the same access boundary and verifies its digest; a post-finish validation error makes the standard reader typed unreadable and #83 non-write. Only after full-store success does the compatible standard reader run with explicit verification profiles to obtain the separate verification-visible identity. The contract treats a leftover `.new` / pre-finish interruption as AtomicFile recovery to the prior complete snapshot, not as an independent source or arbitrary directory failure. If the established AtomicFile protocol cannot prove this recovery and no-mixed-generation behavior on target Android versions, stop Stage B and create a Decision/ADR rather than weakening AC-6.

### 3a. Migrate authority and establish the downgrade barrier

Before any ordinary authoring mutation, `CategoryOverrideAtomicAccess` reads and validates the legacy schema-v1 source without modifying it; physical absence is its defined generation-0 state. It writes and verifies the equivalent complete AtomicFile snapshot, then synchronously commits and verifies legacy `schema = 2`. Until that marker is durable, AtomicFile content is only an equivalent compatibility copy and no user mutation may be accepted. If the marker fails, the source remains semantically legacy and no new-format semantic change has occurred. Once it succeeds, new binaries read only AtomicFile and old #83 readers see `schema = 2` and return `UnsupportedSchema` before reading entries. The migration tests must cover a later AtomicFile mutation followed by an old-reader restart and prove fail-closed behavior.

### 4. Bind category selection to the validated immutable bundle

Add a read-only authoring-facing taxonomy projection from the existing active built-in bundle source. Validate its bundle identity, supported taxonomy version, fallback binding, and category-ID set before enabling the editor. The UI obtains only this projection for selectable ID membership/order. It does not use a separate enum, Flowerpot content, package rules, Android application categories, or hand-maintained resource list as taxonomy authority. [3]

An exhaustive resource-backed `CategoryId`→localized-label/description mapping is a separate presentation layer. Contract tests must prove it covers every active bundle ID and no non-bundle ID. A missing or extra mapping is a build/UI test failure, not a reason to hide, substitute, or authorize a category. `OTHER` is offered as an explicit option, while “Use automatic category” dispatches `Remove` and never `Set(OTHER)`.

### 5. Resolve identity and availability at the UI boundary

Implement a platform adapter that enumerates only supported current launchable app/profile identities for the editor and returns a typed availability result. It must resolve app label/icon and the exact domain `PackageName` plus `ProfileId(userCache.getSerialNumberForUser(user).toString())` with the same non-negative-serial check as `LauncherLayoutAdapter`, preferably through one shared internal mapper. Same-package entries from distinct profiles remain distinct rows. The UI must use a product-safe localized profile label and not expose serials.

Immediately before dispatching a mutation, resolve the selected app/profile again with that same mapper. If it has been removed, disabled, quiet, locked private-space, made unavailable, or otherwise cannot be safely represented, return `TargetUnavailable`; do not write an orphan, guessed, or cross-profile key. Existing source entries outside the current inventory remain untouched and are not silently garbage-collected. Connected evidence must compare the editor key with the profile key seen in a subsequent #83 composition.

### 6. Coordinate with manual/onboarding run state

Extend the existing process-local manual run module with one internal organization-operation lease owned by the **organizer/UI-layer authoring coordinator**. `start()`, `beginRecoveryPreview()`, `confirmRecovery()`, and authoring mutation all use the same mutual-exclusion domain. A run lease remains held through its terminal transition. `beginRecoveryPreview()` obtains one recovery lease/token and holds it through preview cancellation or terminal recovery result; `confirmRecovery()` continues that already-held recovery token rather than acquiring a second lease. An authoring lease remains held until the store returns committed or failed. This prevents both a new run and a recovery entry from racing an admitted authoring mutation, not merely `start()`.

On `OrganizationRunActive`, the coordinator leaves stored identity unchanged, retains the existing manual/onboarding/recovery state and pending plan unchanged, and shows an accessible localized busy state. It does not cancel, invalidate, replan, or retrofit the active run. Tests must prove both directions: every existing run/recovery state rejects authoring, and a blocked-mutation fixture rejects start, recovery preview, and recovery confirmation until the mutation releases the lease. Once the operation terminates, the user reopens or reloads the authoring destination and starts a fresh organization run if they want its new override to be considered. [6]

### 7. Build the settings UI with accessible, truthful states

Add the entry and new destination through the existing `PreferenceRoute` and navigation graph. The list must distinguish automatic from explicit state in text and semantics; it may use a visual indicator only as a supplementary cue. The editor must provide a labelled category selector, an explicit automatic/removal action, save/cancel controls, a progress/busy state, and a non-destructive typed-error presentation.

Unit/UI tests must cover TalkBack names and roles, initial/return focus after save/cancel/error, keyboard/DPAD and switch activation, enabled/disabled semantics, touch-target sizing, 200% font scale, long localized category labels, and no reliance on color. The view state survives ordinary configuration recreation without serializing a pending persistence capability; after recreation it refreshes storage and revalidates availability.

### 8. Verify end-to-end fresh-run consumption and non-mutation

Through the existing #83 composer integration seam, prove that a fresh composition after a successful set contains the matching S1 assignment and associated new snapshot identity, and that the planner gives it S1 precedence over S2–S6. Prove that removal causes a subsequent fresh composition to omit S1 and resume normal inference/fallback. The evidence must use current/captured profile fixtures and must demonstrate that an unavailable profile is not treated as empty, valid, or cross-profile data. [2] [4]

In parallel, test that set/change/remove invoke neither `OrganizationPlanner`, layout application/materialization, Launcher DB mutation, recovery-point creation, nor automatic/manual/onboarding run start. Test an active preview and an applying/recovery operation: authoring must return busy, preserve the preexisting snapshot generation, and leave the preview/plan result unchanged.

## Failure and compatibility matrix

| Condition | Authoring result | Snapshot / organizer effect |
|---|---|---|
| Valid set or change | `Committed(identity)` | One full schema-v1 snapshot with a greater generation; later fresh #83 composition may consume it. |
| Valid removal | `Committed(identity)` or no-op when absent | Mapping absent after commit; later fresh composition resumes S2–S6. |
| Same requested explicit value or absent removal | `NoChange(identity)` | No persistence commit and no generation change. |
| Invalid/unknown category or missing taxonomy label | `InvalidCategory` / `TaxonomyUnavailable` | No write; no UI fallback taxonomy. |
| App/profile no longer resolvable or available | `TargetUnavailable` | No write; no cross-profile or package-only fallback. |
| Manual/onboarding run active | `OrganizationRunActive` | No write; current capture/preview/plan/application state remains authoritative. |
| Current source unreadable/corrupt/duplicate | `StoreUnreadable` | No repair or overwrite; composer remains fail-closed. |
| Current source has unsupported/newer schema | `UnsupportedSchema` | No downgrade/rewrite; composer remains fail-closed. |
| Commit/interruption/verification failure | `WriteFailed` / `VerificationFailed` | Previous complete snapshot remains authoritative; never return success without reader-visible validation. |
| Competing mutation/generation change | `Conflict` | Do not overwrite; reload and require explicit user choice. |
| Backup, restore, downgrade, or recreated profile | Not applicable for automatic migration | Store remains excluded; no remap/rewrite; unsupported schema fails closed. |

## Verification plan and evidence

| Evidence surface | Required proof |
|---|---|
| Rule-store unit tests | Full-store parser/renderer round-trip; all 34 valid IDs; unknown category rejection; set/change/remove/no-op; canonical complete-map stored digest; monotonically increasing generation; duplicate/corrupt/unsupported final snapshot; `startWrite()`/write/`finishWrite()` failure injection with `failWrite()`; post-finish full-store verification failure; expected-stored-identity conflict; legacy-marker ordering/failure; no partial publication. |
| Atomic access and composer integration tests | All compose/UI/migration/verification reads use one recovery-aware access boundary; leftover `.new`, pre-finish failure, and process restart recover to the prior complete snapshot; compose-read versus authoring-write and verification-read versus writer completion show no mixed generation; same-profile S1 precedence; removal falls through to S2–S6; retained orphan proves stored versus filtered composer-visible digest distinction; post-finish invalid content returns typed `NotReady`; profile isolation and unavailable-profile fail-closed behavior. |
| Rollback/downgrade tests | Migrate valid legacy data, perform one or more AtomicFile authoring mutations, start the old #83 reader in-process and after restart, and assert `UnsupportedSchema` rather than stale/empty `Ready`; marker failure must block mutation and preserve semantic legacy authority. |
| Run-coordination tests | Active capture/planning/preview/materialization/apply/recovery states reject authoring with no generation change; an admitted blocked authoring mutation rejects start, recovery preview, and recovery confirmation; `confirmRecovery()` continues its preview lease without self-busy/deadlock; no existing preview or confirmed plan mutation; fresh later run sees committed state. |
| UI tests | Entry/list/editor navigation; automatic vs explicit `OTHER`; typed result presentation; exact-bundle-ID to exhaustive presentation-resource mapping; TalkBack/semantics; focus restoration; keyboard/DPAD/switch; non-color state; 200% font scale; long translations; cancellation. |
| Connected instrumentation | Personal/work same-package fixture; editor key equals #83 capture/composer `ProfileId`; profile removal/unavailable path; app removal; full-store plus filtered-reader post-write validation; fresh manual and onboarding composition; no direct layout/recovery writes. |
| Compatibility tests | First-run generation-0 behavior; exclusion of legacy and AtomicFile override paths from backup; physical-absence and valid-legacy upgrade; durable `schema = 2` barrier before post-migration mutation; old-reader downgrade `UnsupportedSchema` without stale/empty S1; marker-failure no-success behavior; process recreation after interrupted/committed mutation. |
| Repository gates | `python3 tools/repo-contract/validate_repo_contract.py`, `python3 tools/repo-contract/test_validate_repo_contract.py`, `./gradlew spotlessCheck`, focused organizer JVM tests, required UI/instrumentation tests, debug APK build, and PR CI `final-status`. [8] |
| Independent high-risk evidence | After CI passes on the final PR head, a separate session creates `docs/assessment/pr-<PR>-user-category-overrides.md` with head SHA, accepted AC mapping, executed test surfaces, and CI run URL. The PR must satisfy `high-risk-gate` before merge. [8] |

## Migration, rollback, and release plan

Stage B makes no Launcher DB migration and no layout/recovery migration. It evolves the app-private category-override source from legacy SharedPreferences authority to AtomicFile authority under the explicit compatibility protocol in §3a. Before release, test upgrade from physical absence and from valid legacy schema-v1 entries; verify the AtomicFile snapshot; verify durable legacy `schema = 2`; perform at least one new-format mutation; then run the old #83 reader against the same data. The old reader must leave data unchanged and return its existing typed `UnsupportedSchema` outcome before it reads stale entries. Also test marker failure: no user mutation is admitted and no migrated/saved success is reported. [3] [4]

Rollback is application-binary rollback only. It must not edit the AtomicFile source, synthesize a prior generation, clear the legacy marker, restore from backup, or modify the home layout. After a successful authority transition, rollback deliberately leaves Organizer override composition unavailable on the older binary rather than silently reverting to a stale or empty legacy S1 source. A future format change requires the same explicit authority/rollback barrier decision before any writer ships.

## Stop conditions and handoff

Stop and return to the owning contract if recovery-aware AtomicFile access cannot serialize all read/write surfaces or prove pre-finish recovery to the prior complete state; if stored and #83 filtered-visible identities cannot remain distinct without changing the #83 contract; if legacy `schema = 2` cannot durably block an old reader before new-format mutation; if one lease cannot exclude both run and recovery admission during mutation; if a shared writer requires changing the planner public contract; if active taxonomy cannot be exposed without a second policy authority; if the editor cannot reuse canonical `ProfileId` derivation; if v1 backup behavior would need to change; or if UI authoring requires direct layout/recovery access. Each case requires a separate decision Issue and, where the architectural trade-off is durable, an ADR.

The Stage B handoff must include the accepted spec/plan baseline, implementation SHA/PR, authoring-result and failure matrix, source schema/generation/digest evidence, active-run rejection evidence, profile/accessibility/device evidence, backup/upgrade/downgrade evidence, exact local and CI commands, final-status URL, and independent high-risk audit. It must state explicitly that FR-010 is satisfied end to end only when a fresh composition consumes an authored S1 value through `OrganizationInputComposer`.

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/99 "Issue #99 — User-authored category overrides"
[2]: ../83-production-organization-input-sources/spec.md "Issue #83 specification — Production OrganizationInput sources"
[3]: ../../docs/adr/0007-authoritative-organization-policy-sources.md "ADR-0007 — Authoritative organization policy sources"
[4]: ../../lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideSnapshot.kt "Current CategoryOverrideSnapshot read contract"
[5]: ../../lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt "Preference routing conventions"
[6]: ../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt "Manual/onboarding run state machine"
[7]: ../../docs/engineering/organizer-diagnostics.md "Organizer diagnostics and privacy contract"
[8]: ../../AGENTS.md "Repository workflow, verification, and high-risk evidence"
[9]: ../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt "Canonical UserCache-to-ProfileId derivation"
[10]: ../../docs/product/category-taxonomy-v1.md "Issue #6 taxonomy research; authoring persistence superseded by ADR-0007 and Issue #99"
