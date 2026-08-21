---
issue: "#99"
status: proposed
requirements:
  - FR-010
  - FR-011
  - FR-015
  - NFR-003
  - NFR-005
  - NFR-008
  - NFR-009
  - NFR-011
  - NFR-012
updated: 2026-08-22
---

# User-authored category overrides for Organizer v1

> **Status:** Proposed Stage A specification. Production behavior is blocked until this specification and its companion `plan.md` are reviewed and accepted by Issue [#99][1].

## Problem and outcome

Organizer v1 already consumes a typed, local-only S1 category-override snapshot through `OrganizationInputComposer`, but the snapshot is explicitly read-side only. Consequently, the MVP promise that a user can override inferred app classification is not yet observable. The implementation must add a user-facing, profile-scoped authoring capability without creating a second taxonomy or a second route into `OrganizationPlanner`. [2] [3]

The completed capability lets a user select a launchable app in a supported current profile, view its current explicit-override state, assign or change one valid active-v1 `CategoryId`, or remove that assignment. A successful authoring operation changes only the Rule Management override source. It never invokes a planner, changes Launcher layout rows, creates a recovery point, starts organization, or changes an existing plan. The next eligible **fresh** manual or onboarding organization composition is the only point at which the changed S1 value may affect planner input. [2] [4]

## Scope and authority

| Concern | Authoritative owner and rule |
|---|---|
| Taxonomy selection authority | The immutable active `OrganizerPolicyBundle` with `TaxonomyVersion("v1")`; its declared category IDs, canonical order, fallback, and version are the sole source of selectable values. UI-local taxonomies and Flowerpot are prohibited. [2] |
| Localized category presentation | An exhaustive UI resource mapping, keyed by bundle-provided `CategoryId`, supplies localized label and description only. It is not taxonomy authority; a missing or extra mapping is a build/test failure. [2] |
| Profile identity derivation | The editor uses the same canonical `ProfileId(userCache.getSerialNumberForUser(user).toString())` derivation, with a non-negative serial check, as `LauncherLayoutAdapter`; no independent profile-key format is permitted. [10] |
| Override persistence | Rule Management owns `CategoryOverrideStore`; storage format is private implementation detail behind typed reader/writer contracts. [2] |
| Override consumption | `CategoryOverrideSnapshotSource` remains the only source read by `OrganizationInputComposer`. The composer remains the sole production path by which overrides reach the planner. [3] [4] |
| Organization preview and apply authority | The existing manual/onboarding run coordinator owns its active operation, preview, confirmation capability, and layout/recovery writes. The authoring flow has no access to those capabilities. [5] [6] |
| Diagnostics and user messaging | User-visible authoring state is local UI content. Organizer run diagnostics remain governed by the existing closed-field privacy contract and must not contain raw package, profile, or category identity. [7] |

The Stage B implementation may add a narrowly scoped persistence store in `app.lawnchair.organizer.rules`, an authoring coordinator at the existing organizer/UI boundary, and a preference destination in the existing Home Screen settings navigation. It may not add a planner public type, mutate `OrganizationInput`, construct `ClassificationSignals` in UI code, create a mutable policy bundle, or introduce a separate preferences-backed taxonomy.

## User-facing entry point and interaction model

The entry point is a new **“App category overrides”** row under the existing **Home Screen** preferences section, adjacent to the existing organization and placement-management destinations. Opening it shows a profile-aware list of supported, currently resolvable launchable app identities. Each row exposes the app label/icon and a text state that is independently understandable: **“Using automatic category”** or **“Override: <localized category name>.”** Color is supplemental only. The row must not disclose a raw package name or profile serial.

Selecting a row opens a category-selection screen or dialog owned by the same destination. The selector obtains its selectable `CategoryId` values and their canonical order exclusively from the validated active-v1 bundle. It renders those IDs through an exhaustive localized resource mapping that supplies display names and concise descriptions but neither adds nor removes valid categories. It exposes an explicit **“Use automatic category”** action when an override exists; that action means removal, not assignment to `OTHER`. Selecting `OTHER` remains a valid explicit S1 choice and is visibly distinct from automatic classification. The selector requires an explicit save/confirmation action for set or change, and reports a typed failure without falsely updating the displayed state.

| User action | Required result | Layout/run effect |
|---|---|---|
| Select a supported app/profile with no entry | Show automatic state and all active-v1 category choices. | None. |
| Set a category | Validate target identity and selected `CategoryId`, atomically publish the replacement snapshot, then display the selected explicit state. | No planner, layout, recovery, or run action. |
| Change a category | Atomically replace the key’s value and display the new explicit state. | No planner, layout, recovery, or run action. |
| Use automatic category | Atomically delete only the selected `(package, profile)` mapping and display automatic state. A future fresh composition falls through to S2–S6. [3] | No planner, layout, recovery, or run action. |
| Cancel/dismiss before save | Leave storage and displayed committed state unchanged. | None. |
| Attempt to save during an active organization operation | Reject with a localized busy/safe-to-edit-later state; preserve the editor selection for retry only after a fresh destination load. | The existing run and confirmed preview remain unchanged. |

The selector must not show or synthesize the inferred S2–S6 value as a stored assignment. The product wording may explain that removing an override restores automatic classification, but it must not promise a specific future inferred category because platform evidence or the active profile may change before the next fresh composition.

## Identity, availability, and profile isolation

The persistence key is exactly `CategoryOverrideKey(PackageName, ProfileId)`. The editor derives `ProfileId` with the same `UserCache` serial mapping and non-negative-serial validation used by canonical layout capture: `ProfileId(userCache.getSerialNumberForUser(user).toString())`. Stage B must extract/reuse a shared internal mapper or normatively invoke that exact derivation; it must not create a parallel profile-key scheme. The app label, icon, `PackageName`, and this canonical `ProfileId` are resolved together from one current platform/profile snapshot immediately before mutation. A package name alone is never a valid authoring target. Connected evidence must show that the editor key equals the key observed by a later #83 composition for the same profile. [3] [8] [10]

| Identity or availability condition | Authoring outcome | Persistence outcome |
|---|---|---|
| Current app/profile is launchable, visible to the authoring policy, and matches the selected key | Allow set/change/remove after category validation. | Publish one new complete snapshot. |
| Same package exists in another current profile | Treat it as a separate row and separate key. | No cross-profile read, edit, or fallback. |
| Selected profile becomes quiet, private-space locked, disabled, unavailable, removed, or cannot be resolved before mutation | Reject as `TargetUnavailable`; instruct the user to make the profile available and reopen the editor. | No write and no generation change. |
| Package is uninstalled, no longer launchable, or profile identity is recreated with a different `ProfileId` | Treat the originally selected key as unavailable. A recreated profile does not inherit prior entries. | No write; orphaned prior data is not applied to another profile. |
| Entry exists for an app/profile that is not in the current editor inventory | Retain it as an opaque, non-applicable stored record until a future explicit cleanup/migration policy is accepted. It is filtered out of the composition snapshot when the profile is not captured. [3] | No implicit deletion during listing or composition. |
| Stored key or value is malformed, duplicate, corrupt, or schema-unsupported | Fail closed as a typed persistence failure. | Do not repair, default, or publish a partial replacement. [2] [3] |

This policy preserves profile isolation and avoids unsafe identity transplantation. The v1 override store remains excluded from backup and restore. Installation restore, device transfer, downgrade, or profile recreation must not map entries by package name, display label, or serial-like value. [2]

## Category validation and precedence

A writable assignment is valid only if the requested `CategoryId` appears in the active bundle’s declared `TaxonomyVersion("v1")` category set. The validated bundle exclusively supplies ID membership, canonical ordering, fallback, and version. An exhaustive localized resource mapping keyed by those IDs exclusively supplies label/description presentation; it cannot be used to authorize a value or to create a fallback taxonomy. If a bundle ID lacks a presentation resource, or a presentation resource names a non-bundle ID, build/UI contract tests fail and the editor is not enabled. If the active bundle is unavailable, digest-invalid, unsupported, or not bound to taxonomy v1, the editor is unavailable and no write is attempted. [2] [4]

The v1 valid values are `ART`, `AUTO`, `BEAUTY`, `BOOKS`, `BUSINESS`, `COMICS`, `COMMUNICATION`, `DATING`, `EDUCATION`, `ENTERTAINMENT`, `EVENTS`, `FINANCE`, `FOOD`, `GAME`, `HEALTH`, `HOUSE`, `LIBRARIES`, `LIFESTYLE`, `MAPS`, `MEDICAL`, `MUSIC`, `NEWS`, `OTHER`, `PARENTING`, `PERSONALIZATION`, `PHOTOGRAPHY`, `PRODUCTIVITY`, `SHOPPING`, `SOCIAL`, `SPORTS`, `TOOLS`, `TRAVEL`, `VIDEO`, and `WEATHER`. `OTHER` is permitted as an explicit override; absence of an entry is the only representation of “automatic.” [2]

A successfully composed entry remains S1 and therefore has precedence over S2 Android category, S3/S4 (currently explicit empty bundle tables), S5 system/Google evidence, and S6 fallback. Removing the entry restores normal S2–S6 evaluation on a subsequent fresh composition; an unreadable source is never treated as an empty override set. [2] [3]

## Persistence, generation, durability, and compatibility

The existing v1 reader defines an app-private preferences representation with `schema`, `generation`, and newline-delimited `entries`; physical absence means the defined empty schema-v1 generation-0 snapshot. `SharedPreferences` is only a **legacy input representation**. Because a failed `SharedPreferences.Editor.commit()` can expose a failed value to same-process readers before durability is known, it is not an acceptable Stage B publication primitive. Stage B preserves the reader’s composer-visible behavior through an explicit compatibility reader; it does not change #83’s semantic reader contract. [3]

Stage B defines two deliberately distinct identities. The private **stored snapshot identity** is `schema version + generation + SHA-256(canonical complete entry set)` over every committed entry, including entries for profiles absent from a current capture. The existing **composer-visible snapshot identity** is exactly the `CategoryOverrideSnapshotSource.read(capturedProfiles)` result: it keeps schema/generation but computes its digest over the map filtered to `capturedProfiles`. They may therefore have the same generation and different digests; Stage B must not equate them or change #83’s filtered identity semantics implicitly.

The accepted publication design is one app-private **complete snapshot file** protected by the already-established AndroidX `AtomicFile` seam used by organizer recovery inspection. [12] The file contains the schema, generation, canonical complete entry set, and stored digest together. Stage B creates one process-local `CategoryOverrideAtomicAccess` boundary that owns the **same mutex for every access**: full-store reads, composer-visible reads, UI reads, migration reads, post-write verification reads, legacy-marker transitions, and writes. No caller, including `OrganizationInputComposer`, opens the AtomicFile base path through a raw `FileInputStream` or inspects a `.new` file directly.

Inside that mutex, every authoritative AtomicFile read uses `AtomicFile.openRead()` (or the same recovery-aware API behind a dedicated wrapper), decodes and validates exactly one complete final/base snapshot, then filters it for the caller’s captured profiles. The temporary `.new` file is never an independent source. Therefore an interrupted pre-finish write recovers through the AtomicFile base/temporary-file protocol to the prior complete snapshot; it is not interpreted as an arbitrary directory-shape error or a candidate generation. Concurrent compose-read, UI-read, migration-read, verification-read, and write operations cannot observe a mixed generation because they all cross this same access boundary.

The writer uses `(generation, stored snapshot digest)` as its optimistic conflict token, validates and derives the next complete map in memory, calls `startWrite()`, writes and fsyncs the complete encoded file, and calls `finishWrite()` only after the complete write succeeds. On a write or finish exception it calls `failWrite()` and returns `WriteFailed`. The standard compatible `CategoryOverrideSnapshotSource` reads only the final/base file through `CategoryOverrideAtomicAccess`; it never reads a mutable `SharedPreferences` map after atomic authority is established.

The writer then re-opens the final file through the same access boundary and full-store reader to validate schema, generation, complete assignment set, and stored digest. A post-finish corruption or validation failure is `VerificationFailed`; the standard reader must reject the final content as `Unreadable` and #83 must fail closed rather than consume it. Thus no failed generation becomes a `Ready` snapshot in the same process or after restart. `SharedPreferences.apply()` and `SharedPreferences.commit()` are prohibited as authoring publication paths.

Post-write success has two explicit identity surfaces. First, the full-store reader returns the committed stored identity. Second, the compatible `CategoryOverrideSnapshotSource` runs with the editor’s explicit current verification-profile set and returns a **verification-visible identity** for UI refresh only. That visible identity is not claimed to equal any future composer identity, because a later fresh capture can have a different profile set. The success result returns both identities separately.

| Publication outcome | Reader visibility and identity rule | Required caller behavior |
|---|---|---|
| Success | `finishWrite()` has completed, the final file passes full-store verification, and all reads see that one complete final snapshot through the access boundary. The returned verification-visible identity is a filtered reader result for the explicit verification profiles. | Refresh the selected row from the committed typed snapshot; do not compare it to a future composition digest. |
| No-op request (set to already selected value or remove absent mapping) | Preserve the existing final file, generation, and stored identity; do not call `startWrite()`. | Render current committed state. |
| `startWrite()`/write/`finishWrite()` failure or interruption before completion | `failWrite()` and recovery-aware `openRead()` preserve/select the prior final snapshot. The temporary file is not a reader input, so the failed generation is not consumable in the same process or after restart. | Return `WriteFailed`; show localized retry/safe-state guidance. |
| Post-finish full-store validation failure | Do not report success. The standard reader rejects the invalid final file as `Unreadable`; #83 returns typed non-write `NotReady` rather than consuming a failed generation. | Return `VerificationFailed`; no repair or automatic fallback. |
| Concurrent writer or conflict-token mismatch | Do not begin publication. Return typed `Conflict`. | Reload complete source and require explicit user choice; no automatic retry. |
| Reader detects corrupt, duplicate, unsupported, or newer final schema | Existing composer behavior remains typed fail-closed. The authoring UI is read-only unavailable and offers no destructive “repair” action. | Do not substitute a category or overwrite the final file. |

### Authority migration, rollback, and downgrade

Physical absence of both the atomic final file and the legacy source continues to mean the defined schema-v1 generation-0 state. The legacy `SharedPreferences` source is both the compatibility input and the **rollback barrier**. The existing old #83 reader returns `UnsupportedSchema` whenever its `schema` key is not `1`; Stage B reserves `schema = 2` as an incompatibility marker meaning “AtomicFile authority established.” Older binaries therefore fail closed without reading entries whenever the marker is durable. [3]

Authority migration is performed under `CategoryOverrideAtomicAccess` in this exact order. First, read and validate the legacy schema-v1 source without mutation; physical absence is its defined generation-0 value. Second, publish an equivalent complete AtomicFile snapshot and re-open/verify it, but do not yet accept any new-format mutation. Third, synchronously write and verify legacy `schema = 2`. Until this marker commit has succeeded and been re-read as durable, the new binary continues to regard the legacy source as authoritative and must not perform a user mutation. If marker commit returns false or throws, the access boundary enters typed `MigrationBarrierUncertain`: it admits neither an AtomicFile mutation nor a legacy override read in that process until a fresh durable reload resolves the marker; the UI reports no migrated/saved success. After restart, an absent durable marker restores the unchanged legacy authority, which remains semantically equivalent to the already-verified AtomicFile copy. Only after the marker is durable does the AtomicFile snapshot become authoritative and ordinary authoring mutations become eligible.

After marker success, every older #83 binary—both in the same process and after restart—reads `schema = 2` and returns typed `UnsupportedSchema`; it cannot silently consume stale legacy entries or physical absence. New binaries read only the AtomicFile authority. If a later AtomicFile authoring write fails, the prior atomic final snapshot remains authoritative, while the legacy marker continues to make old binaries fail closed. The protocol never rewrites the legacy entries after marker establishment and never restores a stale legacy snapshot during rollback.

Migration marker failure, AtomicFile write/verification failure before marker success, and any post-marker AtomicFile failure are typed non-success outcomes; none permits a false “migrated” or “saved” UI result. Tests must cover valid legacy migration, marker false/exception followed by same-process `MigrationBarrierUncertain` and restart recovery, one or more post-migration mutations, restart into an older #83 reader, and proof that it gets `UnsupportedSchema` rather than a stale/empty `Ready` snapshot. If this ordering or the recovery-aware AtomicFile access contract cannot be proven on target Android versions, implementation stops for a new accepted decision rather than weakening AC-6.

The legacy-to-AtomicFile authority transition above is the only v1 compatibility migration. It deliberately makes rollback/downgrade fail closed after authority establishment; normal Launcher operation and the active home layout remain unchanged. A future schema change must read and validate the active atomic snapshot, publish and verify a complete new snapshot through the same access boundary, establish an explicit incompatibility barrier before allowing semantics to diverge, and leave the prior authoritative state unchanged or safely unavailable on failure. It must never rewrite a newer source into an older format. [2]

## Active-run consistency policy

An active manual or onboarding organization operation owns a captured input and, once previewed, an in-memory confirmation capability. An override mutation during capture, planning, preview, materialization, apply, recovery inspection, or recovery would make the UI state difficult to explain and could silently change the policy meaning between a confirmed preview and a later fresh run. The authoring flow therefore uses a conservative **reject/busy** policy rather than invalidating or replanning the existing run. [5] [6]

The implementation must expose one internal process-local **organization-operation lease** from the existing run coordinator/module. The lease has one mutually exclusive admission domain for manual/onboarding `start()`, recovery-preview entry, recovery confirmation, and authoring mutation. An organizer/UI-layer **authoring coordinator**, rather than Rule Management, acquires the authoring lease before it invokes the Rule Management store mutation and releases it only after the typed mutation has committed or failed. Run admission holds its lease from start through its terminal transition. `beginRecoveryPreview()` acquires one recovery lease/token and retains it through preview cancellation or terminal recovery result; `confirmRecovery()` must **continue that same recovery lease/token** rather than acquire a second lease. If an incompatible lease is held, the attempted admission returns its typed busy/no-write result and publishes nothing. This rule protects both directions: an existing run/recovery rejects authoring, and an admitted authoring mutation rejects a new run, recovery preview, or recovery confirmation until it completes. Rule Management therefore has no dependency on UI/run state; this is a coordination seam, not a policy source and not a planner or layout-application contract change.

A newly committed override affects no already captured `OrganizationInput`, no displayed preview, and no pending or confirmed `LayoutPlan`. The next eligible run must capture and compose afresh through `OrganizationInputComposer`; its A/E1/B/E2 stable-read protocol either includes one consistent new override identity or returns its existing typed non-write readiness result. [2] [4]

## Privacy, diagnostics, accessibility, and localization

The override editor is local-only and must add no network transport, telemetry dependency, permission, or external export path. It must not write raw package names, profile identifiers, category IDs, entry contents, snapshot digests, or exception text to the organizer run journal, logcat, or diagnostic export. The existing run diagnostics contract expressly excludes non-run authoring actions; Stage B may expose only closed authoring result codes and aggregate counts if an accepted diagnostics extension becomes necessary. [7]

The authoring UI itself may show the selected app label/icon and localized category name because that is the user’s immediate local interaction, but it must not display raw package or profile identifiers. All controls need resource-backed localized text. Every actionable row and selector option must provide a descriptive TalkBack label that conveys app name, profile display name where product-safe, and automatic/explicit status. The visual state must include text and semantics rather than color alone, preserve a predictable top-to-bottom focus order, support keyboard/DPAD and switch activation, use accessible touch targets, restore focus after save/cancel/error, and remain usable at 200% system font scale without clipped category state or unreachable controls. [7] [9]

## Acceptance criteria

- [ ] **AC-1 — Stage A gate:** This `spec.md` and its companion `plan.md` are reviewed and accepted on Issue #99 before production behavior changes.
- [ ] **AC-2 — one entry point and one authority:** A Home Screen settings entry exposes profile-aware override authoring. The active validated v1 bundle exclusively supplies category membership/order/version/fallback, while exhaustive resource mappings supply localized presentation only.
- [ ] **AC-3 — explicit semantics:** Supported current app/profile identities can set, change, and remove an override. Removal deletes the mapping and causes a later fresh composition to use normal S2–S6 classification.
- [ ] **AC-4 — canonical profile safety:** The exact `(PackageName, ProfileId)` key is required, with `ProfileId` derived by the same `UserCache` serial mapping as canonical capture. Same-package profiles remain isolated; unavailable, deleted, recreated, unresolved, or non-launchable targets reject safely without cross-profile fallback.
- [ ] **AC-5 — taxonomy validity:** Unknown, stale, malformed, unsupported, or non-v1 `CategoryId` values are rejected. `OTHER` is valid as explicit S1; automatic is represented only by absent mapping.
- [ ] **AC-6 — atomic durable publication, recovery, and identities:** Successful writes publish one complete valid schema-v1 AtomicFile snapshot after full-store verification through the common access boundary. Stored, verification-visible, and composer-visible identities have the distinct semantics defined above. A pre-finish failed or interrupted generation resolves to the prior complete snapshot and is never reader-visible to #83 in the same process or after restart; a post-finish verification failure is rejected as typed unreadable/non-write; conflicting, corrupt, or unsupported cases never publish a partial assignment set or false success.
- [ ] **AC-6a — downgrade barrier:** Before accepting any post-migration mutation, the AtomicFile authority transition durably sets legacy `schema = 2`. An older #83 reader then returns `UnsupportedSchema` and cannot silently consume stale or empty legacy S1 data after rollback/downgrade.
- [ ] **AC-7 — read-contract compatibility:** `CategoryOverrideSnapshotSource` and `OrganizationInputComposer` retain their #83 filtered composer-visible identity semantics: physical first-run absence is empty generation 0; unreadable/newer/corrupt data fails closed; only the composer supplies S1 to the planner.
- [ ] **AC-8 — run and recovery isolation:** One atomic organization-operation lease rejects an authoring write during every active manual/onboarding/recovery operation and rejects new run/recovery admission during an authoring mutation. It cannot mutate an existing preview, confirmed plan, layout, recovery state, or writer authorization; a later fresh run consumes the committed snapshot through #83.
- [ ] **AC-9 — no layout mutation:** Override authoring never calls the planner, direct Launcher DB writers, the application apply seam, recovery point creation, or automatic organization.
- [ ] **AC-10 — privacy and accessible UI:** Default diagnostics/logcat/export contain no raw package/profile/category/override data. The UI meets TalkBack, keyboard/switch, non-color state, focus restoration, localization, touch-target, and 200% font-scale evidence requirements.
- [ ] **AC-11 — compatibility evidence:** Focused unit, integration, UI, and connected-device evidence covers set/change/remove, full-store versus filtered-visible identity semantics, recovery-aware read/write serialization and temporary-file recovery, profile-key equivalence with #83 capture, unavailable targets, bidirectional run/recovery lease exclusion, atomic failure behavior, corruption/newer-schema handling, legacy-to-AtomicFile migration, post-migration mutation followed by old-reader `UnsupportedSchema` downgrade behavior, fresh-run S1 consumption, backup exclusion, and repository/high-risk gates.

## Explicit non-goals

This Issue does not change the v1 taxonomy, planner algorithm or public contract, Flowerpot, S3/S4 tables, layout application/recovery authorization, manual/onboarding planning behavior, package-event incremental placement, rule import/export, usage signals, external classification, backup transfer of overrides, cloud synchronization, or a UI-local fallback classification source. It does not automatically delete historical entries for missing apps or profiles, because that is a separate retention/migration decision with potential identity and rollback implications.

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/99 "Issue #99 — User-authored category overrides"
[2]: ../../docs/adr/0007-authoritative-organization-policy-sources.md "ADR-0007 — Authoritative organization policy sources"
[3]: ../../lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideSnapshot.kt "Current CategoryOverrideSnapshot read contract"
[4]: ../83-production-organization-input-sources/spec.md "Issue #83 specification — Production OrganizationInput sources"
[5]: ../../lawnchair/src/app/lawnchair/organizer/ui/ManualOrganizationRun.kt "Manual/onboarding organization run state machine"
[6]: ../../specs/52-manual-full-organization-vertical-slice/spec.md "Issue #52 — Manual organization flow"
[7]: ../../docs/engineering/organizer-diagnostics.md "Organizer diagnostics and privacy contract"
[8]: ../../lawnchair/src/app/lawnchair/organizer/planning/Identity.kt "PackageName and ProfileId domain types"
[9]: ../../docs/engineering/quality-strategy.md "Quality strategy — UI and accessibility evidence"
[10]: ../../lawnchair/src/app/lawnchair/organizer/application/adapter/LauncherLayoutAdapter.kt "Canonical UserCache-to-ProfileId derivation"
[11]: ../../docs/product/category-taxonomy-v1.md "Issue #6 taxonomy research; authoring persistence superseded by ADR-0007 and Issue #99"
[12]: ../../lawnchair/src/app/lawnchair/organizer/application/store/RecoveryInspectionSnapshotPublisher.kt "Existing AndroidX AtomicFile publication boundary"
