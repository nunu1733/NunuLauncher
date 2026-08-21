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
| Taxonomy and allowed categories | The immutable active `OrganizerPolicyBundle` with `TaxonomyVersion("v1")`; its declared 34 IDs are the sole source of selectable values. UI-local lists and Flowerpot are prohibited. [2] |
| Override persistence | Rule Management owns `CategoryOverrideStore`; storage format is private implementation detail behind typed reader/writer contracts. [2] |
| Override consumption | `CategoryOverrideSnapshotSource` remains the only source read by `OrganizationInputComposer`. The composer remains the sole production path by which overrides reach the planner. [3] [4] |
| Organization preview and apply authority | The existing manual/onboarding run coordinator owns its active operation, preview, confirmation capability, and layout/recovery writes. The authoring flow has no access to those capabilities. [5] [6] |
| Diagnostics and user messaging | User-visible authoring state is local UI content. Organizer run diagnostics remain governed by the existing closed-field privacy contract and must not contain raw package, profile, or category identity. [7] |

The Stage B implementation may add a narrowly scoped persistence store in `app.lawnchair.organizer.rules`, an authoring coordinator at the existing organizer/UI boundary, and a preference destination in the existing Home Screen settings navigation. It may not add a planner public type, mutate `OrganizationInput`, construct `ClassificationSignals` in UI code, create a mutable policy bundle, or introduce a separate preferences-backed taxonomy.

## User-facing entry point and interaction model

The entry point is a new **“App category overrides”** row under the existing **Home Screen** preferences section, adjacent to the existing organization and placement-management destinations. Opening it shows a profile-aware list of supported, currently resolvable launchable app identities. Each row exposes the app label/icon and a text state that is independently understandable: **“Using automatic category”** or **“Override: <localized category name>.”** Color is supplemental only. The row must not disclose a raw package name or profile serial.

Selecting a row opens a category-selection screen or dialog owned by the same destination. The selector presents only the active-v1 taxonomy values in canonical bundle order with localized display names and concise descriptions. It exposes an explicit **“Use automatic category”** action when an override exists; that action means removal, not assignment to `OTHER`. Selecting `OTHER` remains a valid explicit S1 choice and is visibly distinct from automatic classification. The selector requires an explicit save/confirmation action for set or change, and reports a typed failure without falsely updating the displayed state.

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

The persistence key is exactly `CategoryOverrideKey(PackageName, ProfileId)`. The app label, icon, `PackageName`, and `ProfileId` used by the editor must be resolved together from a current platform/profile snapshot immediately before mutation. A package name alone is never a valid authoring target. [3] [8]

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

A writable assignment is valid only if the requested `CategoryId` appears in the active bundle’s declared `TaxonomyVersion("v1")` category set. Stage B must obtain that set from the same validated policy bundle source used by production composition, not from resource strings or an enum duplicated by the settings screen. If the active bundle is unavailable, digest-invalid, unsupported, or not bound to taxonomy v1, the editor is unavailable and no write is attempted. [2] [4]

The v1 valid values are `ART`, `AUTO`, `BEAUTY`, `BOOKS`, `BUSINESS`, `COMICS`, `COMMUNICATION`, `DATING`, `EDUCATION`, `ENTERTAINMENT`, `EVENTS`, `FINANCE`, `FOOD`, `GAME`, `HEALTH`, `HOUSE`, `LIBRARIES`, `LIFESTYLE`, `MAPS`, `MEDICAL`, `MUSIC`, `NEWS`, `OTHER`, `PARENTING`, `PERSONALIZATION`, `PHOTOGRAPHY`, `PRODUCTIVITY`, `SHOPPING`, `SOCIAL`, `SPORTS`, `TOOLS`, `TRAVEL`, `VIDEO`, and `WEATHER`. `OTHER` is permitted as an explicit override; absence of an entry is the only representation of “automatic.” [2]

A successfully composed entry remains S1 and therefore has precedence over S2 Android category, S3/S4 (currently explicit empty bundle tables), S5 system/Google evidence, and S6 fallback. Removing the entry restores normal S2–S6 evaluation on a subsequent fresh composition; an unreadable source is never treated as an empty override set. [2] [3]

## Persistence, generation, durability, and compatibility

The existing v1 reader defines an app-private preferences representation with `schema`, `generation`, and newline-delimited `entries`; physical absence means the defined empty schema-v1 generation-0 snapshot. Stage B must preserve this reader contract or evolve it only behind the same typed source with an accepted schema/migration decision. [3]

The authoring facade accepts a complete current snapshot plus a single operation (`Set`, `Change`, or `Remove`) and returns only typed outcomes. It validates before mutation and derives the next assignment map in memory. It then serializes entries in the reader’s canonical `(profile UTF-8 byte order, package UTF-8 byte order)` order, computes the same canonical SHA-256 digest basis, and atomically publishes **schema v1, generation + 1, and the full entry set** as one durable commit. The facade must re-read and validate the committed snapshot before reporting success; the returned identity must equal the persisted, reader-visible snapshot identity.

| Persistence outcome | Generation and visibility rule | Required caller behavior |
|---|---|---|
| Success | Exactly one new monotonically greater generation becomes visible with a complete, valid assignment set and matching digest. | Refresh the selected row from the committed typed snapshot. |
| No-op request (set to already selected value or remove absent mapping) | Do not write and do not publish a new generation. | Render current committed state. |
| Validation, target-resolution, I/O, serialization, commit, or post-write verification failure | Publish no new generation. The prior complete snapshot remains authoritative; where durability cannot be established, return typed `WriteFailed` rather than success. | Show localized retry/safe-state guidance; reload before retry. |
| Concurrent writer or generation mismatch | Do not overwrite a newer snapshot. Return typed `Conflict`; the UI reloads the current state and requires the user to explicitly choose again. | No automatic retry with stale selection. |
| Reader detects corrupt, duplicate, unsupported, or newer schema | Existing composer behavior remains typed fail-closed. The authoring UI is read-only unavailable and offers no destructive “repair” action. | Do not substitute a category or overwrite the store. |

The writer must not use `SharedPreferences.apply()` or any mechanism that can expose schema, generation, and entries independently. Its commit protocol must ensure that process death or write failure yields either the previous complete snapshot or the next complete snapshot, never a partially updated set. The implementation must document the exact Android storage primitive and demonstrate the invariant with failure-injection/process-recreation evidence before acceptance.

Schema v1 supports no in-place downgrade or backup migration. A future schema change must read and validate the old snapshot, convert it without changing the old source, atomically publish a new snapshot with a new generation and digest, and leave the old snapshot intact on failure. An older binary that cannot read the newer schema must fail closed for organizer composition and authoring rather than rewrite it. [2]

## Active-run consistency policy

An active manual or onboarding organization operation owns a captured input and, once previewed, an in-memory confirmation capability. An override mutation during capture, planning, preview, materialization, apply, recovery inspection, or recovery would make the UI state difficult to explain and could silently change the policy meaning between a confirmed preview and a later fresh run. The authoring flow therefore uses a conservative **reject/busy** policy rather than invalidating or replanning the existing run. [5] [6]

The implementation must expose a minimal internal `withNoActiveOrganizationOperation`-style guard from the existing run coordinator/module. An organizer/UI-layer **authoring coordinator**, rather than Rule Management, invokes the Rule Management store mutation inside that guard. The guard atomically rejects when a nonterminal operation is already active and prevents a new organization operation from being admitted until the typed mutation has committed or failed. If the guard reports busy, the coordinator returns `OrganizationRunActive` and publishes nothing. Rule Management therefore has no dependency on UI/run state; this is a coordination seam, not a policy source and not a planner or layout-application contract change.

A newly committed override affects no already captured `OrganizationInput`, no displayed preview, and no pending or confirmed `LayoutPlan`. The next eligible run must capture and compose afresh through `OrganizationInputComposer`; its A/E1/B/E2 stable-read protocol either includes one consistent new override identity or returns its existing typed non-write readiness result. [2] [4]

## Privacy, diagnostics, accessibility, and localization

The override editor is local-only and must add no network transport, telemetry dependency, permission, or external export path. It must not write raw package names, profile identifiers, category IDs, entry contents, snapshot digests, or exception text to the organizer run journal, logcat, or diagnostic export. The existing run diagnostics contract expressly excludes non-run authoring actions; Stage B may expose only closed authoring result codes and aggregate counts if an accepted diagnostics extension becomes necessary. [7]

The authoring UI itself may show the selected app label/icon and localized category name because that is the user’s immediate local interaction, but it must not display raw package or profile identifiers. All controls need resource-backed localized text. Every actionable row and selector option must provide a descriptive TalkBack label that conveys app name, profile display name where product-safe, and automatic/explicit status. The visual state must include text and semantics rather than color alone, preserve a predictable top-to-bottom focus order, support keyboard/DPAD and switch activation, use accessible touch targets, restore focus after save/cancel/error, and remain usable at 200% system font scale without clipped category state or unreachable controls. [7] [9]

## Acceptance criteria

- [ ] **AC-1 — Stage A gate:** This `spec.md` and its companion `plan.md` are reviewed and accepted on Issue #99 before production behavior changes.
- [ ] **AC-2 — one entry point and one authority:** A Home Screen settings entry exposes profile-aware override authoring, and selectable categories come exclusively from the active validated v1 policy bundle.
- [ ] **AC-3 — explicit semantics:** Supported current app/profile identities can set, change, and remove an override. Removal deletes the mapping and causes a later fresh composition to use normal S2–S6 classification.
- [ ] **AC-4 — profile safety:** The exact `(PackageName, ProfileId)` key is required; same-package profiles remain isolated; unavailable, deleted, recreated, unresolved, or non-launchable targets reject safely without cross-profile fallback.
- [ ] **AC-5 — taxonomy validity:** Unknown, stale, malformed, unsupported, or non-v1 `CategoryId` values are rejected. `OTHER` is valid as explicit S1; automatic is represented only by absent mapping.
- [ ] **AC-6 — atomic durable persistence:** Successful writes publish one complete valid schema-v1 snapshot with a single increased generation and matching digest. Failed, interrupted, conflicting, corrupt, or unsupported cases never publish a partial assignment set or false success.
- [ ] **AC-7 — read-contract compatibility:** `CategoryOverrideSnapshotSource` and `OrganizationInputComposer` retain their #83 semantics: physical first-run absence is empty generation 0; unreadable/newer/corrupt data fails closed; only the composer supplies S1 to the planner.
- [ ] **AC-8 — active-run isolation:** An authoring write during any active manual/onboarding operation is typed busy/no-write. It cannot mutate an existing preview, confirmed plan, layout, recovery state, or writer authorization; a later fresh run consumes the committed snapshot through #83.
- [ ] **AC-9 — no layout mutation:** Override authoring never calls the planner, direct Launcher DB writers, the application apply seam, recovery point creation, or automatic organization.
- [ ] **AC-10 — privacy and accessible UI:** Default diagnostics/logcat/export contain no raw package/profile/category/override data. The UI meets TalkBack, keyboard/switch, non-color state, focus restoration, localization, touch-target, and 200% font-scale evidence requirements.
- [ ] **AC-11 — compatibility evidence:** Focused unit, integration, UI, and connected-device evidence covers set/change/remove, profile isolation, unavailable targets, active-run rejection, atomic failure behavior, corruption/newer-schema handling, fresh-run S1 consumption, backup exclusion, upgrade/downgrade behavior, and repository/high-risk gates.

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
