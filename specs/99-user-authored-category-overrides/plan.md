---
issue: "#99"
status: proposed
spec: ./spec.md
updated: 2026-08-22
---

# Implementation plan — User-authored category overrides for Organizer v1

> **Stage A gate:** This plan is proposed. Stage B production work begins only after Issue [#99][1] records acceptance of both this plan and `spec.md`. This document plans a minimal vertical slice that preserves the accepted #83 composition seam and does not authorize planner, layout-application, or recovery-contract changes. [2] [3]

## Baseline and design decisions

The implementation baseline is `main` after Issue #83 / PR #88. `CategoryOverrideSnapshotSource` currently establishes the app-private schema-v1 snapshot shape, defined empty generation-0 first-run state, profile-filtered read result, generation identity, and fail-closed read outcomes. It has no writer. The Stage B writer will be added beside that reader under Rule Management, while `OrganizationInputComposer` continues to consume only the existing reader result. [2] [4]

The approved Stage A decisions are summarized below. Any implementation discovery that contradicts a decision is a stop condition rather than permission to introduce an implicit alternative.

| Decision | Stage B implementation rule | Rationale |
|---|---|---|
| Authoring surface | Add one Home Screen preferences destination, **App category overrides**, with a profile-aware app list and category editor. | It reuses the existing settings/navigation convention and keeps authoring outside an organization-run preview. [5] |
| Policy authority | Obtain selectable categories from the active validated `OrganizerPolicyBundle` taxonomy projection only. | Prevents a UI-local taxonomy and preserves #83’s single policy authority. [3] |
| Mutation boundary | Add a typed Rule Management persistence-and-validation store and an organizer/UI-layer authoring coordinator; UI passes a resolved `(PackageName, ProfileId)` plus a selected category or removal command. | Keeps platform/UI and run-coordination details out of the policy source, enforces taxonomy validity defensively at the storage boundary, and keeps storage format private. [3] [4] |
| Commit model | Build next full snapshot in memory, serialize canonical complete entries, commit schema/generation/entries atomically, then read-after-write validate. | Preserves digest/generation identity and prevents partial source publication. [4] |
| Active run | Reject authoring when manual or onboarding run has any active nonterminal operation; require a later fresh run. | A confirmed preview must never silently acquire a changed S1 meaning. [6] |
| Backup and migration | Keep the v1 store excluded from backup/restore. Do not add cleanup, remapping, or downgrade writer behavior. | `ProfileId` is capture-local; remapping would require a separate accepted decision. [3] |
| Diagnostics | Do not emit authoring actions into the organizer run journal; forbid raw identity/category data in logs/export. | Existing diagnostics permit only closed run information and summary counts. [7] |

## Planned change surface

| Path | Planned Stage B change | Boundary preserved |
|---|---|---|
| `lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideSnapshot.kt` | Refactor the current preferences reader into a shared private codec/validator; retain `CategoryOverrideSnapshotSource` read behavior and add typed writer-facing store/facade/result contracts. | The composer keeps its existing read-only source and typed `Ready`/`Unreadable`/`UnsupportedSchema` semantics. |
| `lawnchair/src/app/lawnchair/organizer/rules/BuiltInOrganizerPolicyBundleSource.kt` and/or `PolicyModels.kt` | Expose a narrow validated active-taxonomy projection for authoring; no mutable bundle or duplicated category list. | `OrganizerPolicyBundle` remains immutable and Rule Management remains the sole policy owner. [3] |
| `lawnchair/src/app/lawnchair/organizer/integration/ProductionOrganizationInputComposer.kt` | Reuse the same private override-store instance/configuration for reader and writer wiring only if required by construction; do not change composition logic or planner input types. | `OrganizationInputComposer` remains the only planner-facing override path. [2] |
| `lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt` | Add only an internal process-local `withNoActiveOrganizationOperation`-style admission guard for the organizer/UI-layer authoring coordinator. | Rule Management does not depend on UI/run state; no change to plan, confirmation, application, recovery, or writer authority. [6] |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceRoutes.kt` | Add a serializable Home Screen route for override management. | Retain existing preference navigation and do not serialize run or write authorization. [5] |
| `lawnchair/src/app/lawnchair/ui/preferences/navigation/PreferenceNavigation.kt` | Bind the route to the new destination. | No direct DB or planner call from navigation. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/HomeScreenPreferences.kt` | Add the Home Screen entry row. | Existing Settings destination pattern. |
| `lawnchair/src/app/lawnchair/ui/preferences/destinations/CategoryOverridePreferences.kt` (new) | Implement list, selection editor, typed result rendering, accessibility semantics, and focus restoration. | UI uses the authoring facade; it cannot access policy storage, composer internals, planner, layout DB, or recovery. |
| `res/values/strings.xml` and translated resource locations as applicable | Add localized labels, descriptions, state text, errors, and accessibility strings. | No hard-coded taxonomy or raw identity strings. |
| `res/xml/backupscheme.xml` | Verification-only review; no include entry for the override preferences file. Update only if current behavior would include the file unexpectedly, in which case stop for an explicit migration/backup decision. | v1 local-only/no-restore policy stays intact. [3] |
| `tests/unit/.../organizer/rules/*` and `tests/unit/.../organizer/ui/*` | Add deterministic store, taxonomy, conflict, active-run, and privacy-shape tests. | Test the same public/internal seams used by production. |
| `tests/organizer-instrumentation/...` | Add device/profile and preference/UI integration evidence. | Validate no cross-profile fallback and no indirect layout mutation. |

No new database table, Launcher `favorites` mutation, application/recovery source file, network dependency, permission, background service, or backup payload is planned.

## Implementation sequence

### 1. Freeze the accepted contracts and create test fixtures

Before source changes, update this Stage A documentation to `accepted` only after review. In the same Stage B branch, create synthetic fixtures for two current profiles sharing a package name, unavailable/removed profile contexts, all active-v1 categories, invalid IDs, first-run empty state, corrupt/duplicate rows, unsupported schema, interrupted write, post-commit verification failure, and competing writes. Fixture package/profile identifiers must be synthetic and must never enter diagnostics fixtures. [7]

The tests must assert the observable typed result and identity, not the private preference string alone. For successful commits, assert canonical reader output, schema `1`, exactly one increased generation, full assignment-set conservation, and the expected digest. For rejected operations, assert unchanged reader-visible identity and assignment map.

### 2. Define the writer contract inside Rule Management

Introduce a narrow persistence-and-validation contract such as `CategoryOverrideStore.mutate(request): CategoryOverrideWriteResult`. The exact names may follow repository style, but the capabilities are fixed: `Set`, `Remove`, and explicit typed results for success, no-op, invalid category, taxonomy unavailable/unsupported, store unreadable/unsupported, conflict, write failure, and verification failure. The store obtains/validates the active taxonomy through Rule Management before every mutation, even when the UI has already validated the choice. The organizer/UI-layer authoring coordinator owns `TargetUnavailable` and active-run admission outcomes before invoking the store.

The contract accepts only validated domain values and never accepts a display label, raw serialized row, inferred category, planner input, or Android UI object. It must return the committed snapshot identity on success and the current stable identity only where it is safe and already readable. The UI cannot infer success from a requested value; it uses the returned typed result and reloads state after conflict/failure.

### 3. Preserve canonical snapshot encoding and publish atomically

Extract the current parser and canonical renderer into one private codec used by both reader and writer. The decoder continues to reject blank/malformed components, duplicate `(package, profile)` keys, negative generations, incompatible schema, and runtime storage faults. The renderer sorts by profile and then package according to the existing canonical UTF-8 ordering and writes exactly the format consumed by the current reader. [4]

The writer reads and validates a complete source snapshot, checks the active run admission and taxonomy binding, materializes a new full map, and increments generation exactly once only for a state-changing request. It then commits all source fields atomically using an Android storage primitive whose failure/visibility semantics are documented in code and tests. It must not call asynchronous `apply()`. After the commit it reads through the standard snapshot reader and confirms schema, complete assignments, generation, and digest before returning success. If the platform primitive cannot meet the all-or-nothing property for the existing three-key representation, stop Stage B and create a Decision/ADR rather than silently weakening AC-6.

### 4. Bind category selection to the validated immutable bundle

Add a read-only authoring-facing taxonomy projection from the existing active built-in bundle source. Validate its bundle identity, supported taxonomy version, fallback binding, category set, and display metadata before enabling the editor. The UI obtains only this projection. It does not use a separate enum, Flowerpot content, package rules, Android application categories, or hand-maintained resource list as authority. [3]

Category localization maps the validated IDs through resource-backed labels owned by the authoring UI. A missing localized label is a build/test failure, not a reason to hide or substitute a category. `OTHER` is offered as an explicit option, while “Use automatic category” dispatches `Remove` and never `Set(OTHER)`.

### 5. Resolve identity and availability at the UI boundary

Implement a platform adapter that enumerates only supported current launchable app/profile identities for the editor and returns a typed availability result. It must resolve app label/icon and the exact domain `PackageName` plus current `ProfileId` in one current snapshot. Same-package entries from distinct profiles remain distinct rows. The UI must use a product-safe localized profile label and not expose serials.

Immediately before dispatching a mutation, resolve the selected app/profile again. If it has been removed, disabled, quiet, locked private-space, made unavailable, or otherwise cannot be safely represented, return `TargetUnavailable`; do not write an orphan, guessed, or cross-profile key. Existing source entries outside the current inventory remain untouched and are not silently garbage-collected.

### 6. Coordinate with manual/onboarding run state

Extend the existing process-local manual run module with the smallest internal `withNoActiveOrganizationOperation`-style guard needed by the **organizer/UI-layer authoring coordinator**. The guard rejects while any operation is capturing, planning, previewing, materializing, applying, inspecting recovery, or recovering. When idle, it holds admission against new organization operations until the coordinator’s typed store mutation has committed or failed. This avoids a time-of-check/time-of-use gap without making Rule Management depend on UI/run state.

On `OrganizationRunActive`, the coordinator leaves source identity unchanged, retains the existing manual/onboarding state and pending plan unchanged, and shows an accessible localized busy state. It does not cancel, invalidate, replan, or retrofit the active run. Once the operation terminates, the user reopens or reloads the authoring destination and starts a fresh organization run if they want its new override to be considered. [6]

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
| Rule-store unit tests | Parser/renderer round-trip; all 34 valid IDs; unknown category rejection; set/change/remove/no-op; canonical ordering/digest; monotonically increasing generation; duplicate/corrupt/unsupported failure; commit/verification failure injection; conflict; no partial publication. |
| Composer integration tests | Same-profile S1 precedence; removal falls through to S2–S6; source identity changes only after committed write; reader failure remains `NotReady`; profile isolation and unavailable-profile fail-closed behavior. |
| Run-coordination tests | Active capture/planning/preview/materialization/apply/recovery states reject authoring with no generation change; no existing preview or confirmed plan mutation; fresh later run sees committed state. |
| UI tests | Entry/list/editor navigation; automatic vs explicit `OTHER`; typed result presentation; TalkBack/semantics; focus restoration; keyboard/DPAD/switch; non-color state; 200% font scale; long translations; cancellation. |
| Connected instrumentation | Personal/work same-package fixture; profile removal/unavailable path; app removal; atomic post-write reader validation; fresh manual and onboarding composition; no direct layout/recovery writes. |
| Compatibility tests | First-run generation-0 behavior; backup allowlist exclusion; upgrade/current-schema reopening; newer-schema downgrade failure without rewrite; process recreation after interrupted/committed mutation. |
| Repository gates | `python3 tools/repo-contract/validate_repo_contract.py`, `python3 tools/repo-contract/test_validate_repo_contract.py`, `./gradlew spotlessCheck`, focused organizer JVM tests, required UI/instrumentation tests, debug APK build, and PR CI `final-status`. [8] |
| Independent high-risk evidence | After CI passes on the final PR head, a separate session creates `docs/assessment/pr-<PR>-user-category-overrides.md` with head SHA, accepted AC mapping, executed test surfaces, and CI run URL. The PR must satisfy `high-risk-gate` before merge. [8] |

## Migration, rollback, and release plan

Stage B makes no Launcher DB migration and no layout/recovery migration. It evolves only the app-private category-override source under its existing schema-v1 contract. Before release, test an upgrade from physical absence and from valid schema-v1 entries, then test an older binary encountering a deliberately newer schema fixture. The older binary must leave data unchanged and the organizer must return the existing fail-closed typed outcome. [3] [4]

Rollback is application-binary rollback only. It must not edit the override store, synthesize a prior generation, restore from backup, or modify the home layout. If the release contains only compatible schema-v1 authoring, rollback leaves the last valid snapshot in place. If a future release needs a non-v1 format, that work is outside this plan and requires an accepted migration/compatibility decision before any writer ships.

## Stop conditions and handoff

Stop and return to the owning contract if atomic publication cannot be proven with the selected storage primitive; if a shared writer requires changing the planner public contract; if mutation cannot be excluded while an existing confirmed plan is active; if active taxonomy cannot be exposed without a second policy authority; if profile identity needs remapping; if v1 backup behavior would need to change; or if UI authoring requires direct layout/recovery access. Each case requires a separate decision Issue and, where the architectural trade-off is durable, an ADR.

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
