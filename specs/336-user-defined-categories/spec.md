---
issue: "#336"
status: implemented
requirements:
  - FR-010
  - FR-011
  - NFR-003
  - NFR-005
  - NFR-008
  - NFR-009
  - NFR-012
updated: 2026-09-17
---

# User-defined categories as first-class Organizer taxonomy

> **Status:** Implemented (merged to `main` as PR #341, merge commit `45711f53dd40b5cc67013f4a4193d2c6d5b0dcc1`; Issue #336 closed). Originally accepted on Issue [#336][1] after ChatGPT review (initial review [changes requested][r1], addressed at `8024653378`; re-review [changes requested][r2] on the exchange projection, addressed at `0cff39b7a1`; re-review 2 **Approved** with no blocking/required findings — [verdict][r3]). This specification and its companion `plan.md` are the binding contract for Phase2 implementation. Production behavior must remain within the stated scope and stop conditions.

## Problem and outcome

The Organizer category model treats the built-in `TaxonomyContract.allowedCategories` closed set as the only category identity. Classification signals, the #99 user-authored override, and category-oriented strategies all operate inside that closed taxonomy. A user who thinks in their own organizing concepts ("AI tools", "commute", "morning routine") cannot express them; forcing those concepts into the nearest Play-Store-like built-in category, or misusing an unused one as a dummy, separates the user's intent from the classification identity that plans and folder grouping expose.

This Issue adds **user-defined categories as first-class category identity**: a user can create, rename, and delete their own categories, and the Organizer's classification, override, folder formation, and category-contiguous ordering treat built-in and user-defined identities uniformly under the existing safety contracts. A user-defined category is identified by a **stable local opaque ID**, not by its display name, so renaming never breaks assignments or grouping identity.

The active category catalog presented to the planner is, conceptually, `built-in categories + user-defined categories`. The built-in taxonomy remains the immutable bundle-owned authority; user-defined categories are a separate Rule Management-owned, dynamic, content-addressed policy source. The two identities are never conflated.

## Identity model

- `CategoryIdentity` is a closed planning-domain type: `BuiltIn(CategoryId)` or `UserDefined(UserCategoryId)`.
- `UserCategoryId` is a stable, local, opaque ID minted by Rule Management (canonical lowercase UUID v4 form; never derived from the display name, never shown in user UI, never derived from platform or AI output in this Issue).
- `UserDefinedCategory` carries exactly `id: UserCategoryId` and `displayName`. The display name is presentation only: canonical equality and ordering use the ID alone. The display name is a single localized line that is **not** an identity, validated as: trimmed, Unicode NFC-normalized, 1–50 code points long, with no field separators (`|`) and no line breaks. Display names are **unique within the user-defined catalog** under exactly this normalization; a create or rename that would collide is a typed duplicate-name failure with no write.
- Canonical ordering is total and rename-invariant: built-in categories in their existing UTF-8 byte order first, then user-defined categories in UTF-8 byte order of their stable IDs. Inserting or renaming a user-defined category never reorders other identities' relative order.
- Built-in categories remain immutable: renaming or deleting a built-in category is impossible, and `OTHER` remains the sole fallback category. A user-defined category may share a display name with a built-in category's localized label; the assignment UI's text marker distinguishes them. Identity collision between the namespaces is impossible by construction.

## Active category catalog

The planner receives one combined membership surface — a new `ActiveCategoryCatalog` input field on `OrganizationInput` — that exposes:

- the allowed `CategoryIdentity` set: the active built-in v1 taxonomy's 34 IDs plus the current user-defined entries;
- the unchanged built-in fallback (`OTHER`);
- the built-in `TaxonomyContract` itself with its bundle identity, and the user-defined entries as a separate content-addressed projection.

`OrganizationInput.taxonomy` itself remains the immutable built-in `TaxonomyContract`; its existing application-side role (`ValidatedLayoutPlan.taxonomyVersion` and the materializer's plan/input version check) is unchanged and continues to refer to the built-in taxonomy version only. The catalog embeds the same built-in contract, and planner validation rejects an input whose catalog's built-in projection differs from `taxonomy` (one consistency invariant, fail-closed).

Rules:

- The built-in portion is a direct immutable projection of the active `OrganizerPolicyBundle` (ADR-0007). Its membership, order, fallback, and digest semantics do not change, and `POLICY_BUNDLE_VERSION`/`TaxonomyVersion("v1")` do not change. User-defined categories never alter the bundle identity.
- The user-defined portion comes from a new Rule Management-owned store defined below. It is a production policy source: "could not read the source" is never represented as an empty catalog.
- An empty user-defined portion is the defined first-run state and is represented by a canonical sentinel identity in provenance (mirroring the no-intent sentinel of #204), so built-in-only runs keep today's plan behavior byte for byte.
- Membership validation everywhere (planner defense-in-depth, composer, override store) is against the active catalog, not the built-in set alone.

## Persistence: `UserDefinedCategoryStore`

Rule Management owns one app-private, local-only AtomicFile snapshot store under `organizer/rules`, following the #99 `CategoryOverrideAtomicAccess` pattern:

- File content: `schema=1`, monotonic `generation`, `digest=SHA-256(canonical complete entry set)`, and entries `id|displayName` canonically sorted by ID byte order. One process-local access boundary owns **the same mutex for every read and write**; reads use recovery-aware `AtomicFile.openRead()`; the temporary `.new` file is never an input.
- Physical absence of the file is the defined schema-1 generation-0 empty catalog, not a missing source.
- The writer validates, derives the next complete entry set, increments the generation exactly once per state-changing mutation, publishes via `startWrite()`/fsync/`finishWrite()`, re-opens and verifies the final file through the same boundary before reporting success, and calls `failWrite()` on any interruption. `(generation, digest)` is the optimistic conflict token; a mismatch is a typed `Conflict` with no overwrite.
- Typed read outcomes are at least `Ready`, `Unreadable` (corrupt, duplicate ID, malformed name, digest mismatch), and `UnsupportedSchema` (newer schema). All are fail-closed: no repair, no default, no partial catalog. The store is excluded from backup/restore (`backupscheme.xml` verified); a restored installation starts from the defined empty catalog; downgrade to an older binary leaves the store untouched and the old binary simply does not read it.
- Mutations: `Create` (mints a fresh `UserCategoryId`, requires a valid display name per the Identity-model rules — trim, NFC, 1–50 code points, uniqueness within the catalog — with typed invalid-name/duplicate-name failures and a typed failure when the bounded catalog capacity of 64 entries is exceeded), `Rename` (same validation; ID and order unchanged), `Delete` (see Delete semantics). The store applies the normalization before validation and persists the normalized form, so codec validation and UI tests see one canonical rule. No-op requests preserve file, generation, and identity.

## Classification semantics

- The catalog is classification-neutral by itself: **creating, renaming, or deleting a user-defined category never assigns or re-classifies any app**. No automatic inference (S2–S6) may target a user-defined category. This is enforced structurally: S2 evidence mapping, the empty S3/S4 bundle tables, and S5 evidence are built-in-`CategoryId`-typed and cannot carry a user-defined identity; the planner additionally rejects, as a typed validation failure, any signal whose source is not S1 but whose candidate is user-defined.
- A user-defined category receives members only through the existing S1 override path (explicit user assignment). This Issue does not add an AI-confirmed assignment path; AI-personalization workflows that might reference or propose user-defined categories remain a separate Issue and never create categories.
- S1 precedence over S2–S6 and the meaning of removal (fall through to normal S2–S6 on the next fresh composition) are unchanged from #99.

## Override integration (#99 extension)

- The override store's value type becomes the identity-typed `CategoryIdentity`; the physical AtomicFile snapshot advances to **schema 2** with a kind-discriminated entry encoding (`b|<CategoryId>` / `u|<UserCategoryId>`).
- Migration: before the first schema-2 mutation, the store reads and validates the schema-1 snapshot without modifying it, publishes an equivalent schema-2 snapshot (same semantics, one generation bump) through the same access boundary, and verifies it. A failed migration is a typed non-success; the prior schema-1 content stays authoritative and no user mutation is admitted. Existing built-in-valued overrides keep their exact semantics.
- A set/change is valid only when the target identity exists in the current active catalog: built-in membership as today, or an existing user-defined ID. Assigning to an unknown, deleted, or malformed user-defined ID is a typed invalid-target failure with no write.
- The composer's override-membership check (`OVERRIDE_CATEGORY_INVALID`) becomes catalog membership. An override referencing a user-defined ID that is absent from the catalog is contradictory evidence and fails closed as a typed `NotReady` (zero-write). The product path cannot produce this state — Delete removes assignments explicitly (below) — so its only source is external corruption, which must fail closed rather than silently remap.
- #99's stored identity vs. verification-visible vs. composer-visible identity semantics, the legacy-SharedPreferences rollback barrier (`schema = 2` marker), lease-based active-run rejection, and the no-layout-mutation guarantee all carry over unchanged. The stored identity now covers identity-typed entries.

## Delete semantics

Deleting a user-defined category is one user operation with an explicit, two-store, overrides-first protocol:

1. Under the authoring lease, the coordinator first removes every override assignment that references the category (explicit `Remove` per key, one atomic override-store publication).
2. It then publishes the catalog snapshot without the category.
3. The operation is committed only when both publications verified; otherwise the user gets a typed failure and the UI reloads and renders the truthful committed state. The protocol is deliberate about its one partial state: if step 2 fails after step 1 succeeded, the assignment removals are **already durable** and the category remains as an empty entry. That state is well-defined and benign; the UI must not present it as an undone operation, and a retry completes the delete of the now-empty category. The protocol is per-store atomic, **not a cross-store transaction**: "atomic persistence" in AC-2 is a single-store guarantee. A catalog from which the category is removed while assignments still reference it is **not reachable through this protocol** and remains solely the external-corruption fail-closed state described in Override integration.

Deleted assignments are never silently remapped to the fallback category or to any other category; the affected apps return to normal S2–S6 automatic classification on the next fresh composition, and the UI states this outcome before confirmation. Tombstoning is rejected (see Alternatives).

## Composition provenance, cut, and stale semantics

- A new closed `PolicySourceKind` (user-defined category catalog) joins `InputProvenance` as a mandatory row carrying `(schema, generation, digest)`; the empty catalog carries the defined sentinel identity. Diagnostics may record only source kind, version/generation, result codes, and opaque digests — never IDs, display names, or entry contents.
- The catalog identity joins the mandatory dynamic cut: it is read once before platform evidence and re-read for the stability comparison, subject to the existing bounded two-attempt protocol; an unstable cut is the existing typed `NotReady(DYNAMIC_CUT_UNSTABLE)`. New typed read-failure codes (catalog unreadable / unsupported schema) join the closed `InputCompositionCode` vocabulary, and [organizer-diagnostics.md][7] is updated in the same change.
- A committed catalog change between runs never reinterprets an existing preview: category authoring (create/rename/delete) and override mutations are admissions in the existing single organization-operation lease domain, so an active manual/onboarding/recovery operation rejects them and vice versa (reject/busy, no invalidation, no replan-in-place). After the operation terminates, the next fresh composition re-reads the catalog through the cut; the plan then reflects the new catalog with full provenance.
- The #331 scope-binding gate re-derives each candidate's resolved classification at run time. This Issue fixes that projection explicitly (next section) so that user-defined catalog state cannot silently reinterpret an active session.

### Exchange export and #331 binding projection

User-defined category identity never enters the external exchange surface: neither the raw `UserCategoryId` nor a display name may appear in an export document, a session record field, or an intent payload. Two distinct layers keep that promise, and collapsing them is explicitly rejected:

- **Export presentation surface**: a candidate whose resolved classification is a user-defined category is exported with **no category** (the existing absent-category projection) in `ExportItem.category` / `groupSemantic` — never an ID or a name. Built-in classifications export exactly as today.
- **Session-local freshness identity (internal)**: the #331 freshness identity keeps digesting the resolved `CategoryIdentity` itself — the `CandidateScopeIdentity` canonical row (and the placed-item freshness projections owned by `SourceContextIdentity`) use the identity's kind discriminator + stable ID as canonical digest input. A raw ID may flow only into the one-way session digest; it is never persisted as an export/session *field*. Redacting the export field to "absent" therefore does **not** collapse the freshness identity: a reassignment from user-defined category A to user-defined category B (or across the built-in/user-defined boundary) is detected exactly as a built-in resolved-category change is today, through the existing typed scope-mismatch/stale path.

The resulting stale semantics are exact:

- **Never stale**: creating a category, renaming one (stable ID unchanged), or deleting an **unassigned** category changes no candidate's/item's resolved `CategoryIdentity` and cannot invalidate an active exchange session.
- **Typed mismatch/stale as today**: reassigning a candidate or item across any categories, or deleting an **assigned** category (whose protocol removes its overrides, letting classification fall through to S2–S6 built-in resolution), changes resolved identities and fails through the existing typed scope-mismatch path with its specified replan/remedy.

#204 `PersonalizedIntentProjection.groupSemantic` keeps addressing built-in categories only (built-in `CategoryId`-typed); intents cannot reference, assign, or create user-defined categories.

## Planner and strategy integration

- Folder formation groups by `(profile, CategoryIdentity)` with the same capacity partition, minimum group size, fallback-skip, and profile-isolation rules; user-defined groups participate identically, ordered by the canonical identity order.
- Category-contiguous ordering uses the same canonical identity order (fallback last), so user-defined categories are first-class inputs to `CATEGORY_CONTIGUOUS_V1` and any category-consuming strategy under the unchanged safety contracts.
- New folders formed under a user-defined category carry a new `FolderNaming` variant carrying the stable ID. Title resolution is bound to **the same `OrganizationInput`'s catalog snapshot** — never a fresh store read: the callers of plan materialization combine the injected built-in `FolderTitleResolver` presentation with a total lookup over that snapshot's display names, resolving exactly once per planned folder at creation time, with the same generic-fallback policy and no raw ID exposure. The `FolderTitleResolver` contract (non-blank localized title, total lookup, fail-closed on blank) is unchanged; preview and apply consume the same creation-time title from the same snapshot. Plan canonical representation carries the identity (stable ID), never the display name: **a rename does not change the canonical plan bytes**; the new name appears only in titles of folders generated by later runs.
- Determinism (same canonical input + same catalog content ⇒ byte-equal canonical plan), idempotence, convergence, and the existing planner invariants are restated and tested over mixed catalogs; a catalog consisting of built-in categories only reproduces today's plans exactly.
- The runtime active catalog and the built-in taxonomy identity remain distinct: the bundle's taxonomy projection and digest are untouched, and no code may treat the union as bundle content.

## User-facing authoring UX

At least the following, localized and reachable from the existing Home Screen settings area adjacent to the #99 override editor:

- **Create**: name entry with validation feedback; success shows the new category in the catalog list and in the assignment selector.
- **Rename**: name edit; the operation is presented as renaming the same category; committed state keeps assignments and grouping.
- **Delete**: confirmation that states how many apps are currently assigned and that those apps return to automatic classification; deletion never offers a "move to another category" silent remap.
- **Assignment** (in the existing override editor): built-in and user-defined categories appear in canonical order, and user-defined entries are distinguishable from built-in ones by text and semantics (e.g. a localized "Custom" marker), never by color alone. Catalog-internal uniqueness of display names (Identity model) guarantees that every custom entry in the selector is presented under a unique visible name.
- **Create/rename validation**: name rules (trim, NFC, 1–50 code points, uniqueness within the user-defined catalog) are enforced with typed, localized feedback before any write.
- Raw category IDs, serials, and taxonomy internal vocabulary are never shown. Failure, busy, and conflict states are typed and localized, with truthful committed-state rendering after reload (in particular, a partially completed delete renders as "category remains, assignments removed", not as an undone operation).

Accessibility bar (carried from #99 AC-10): TalkBack labels/roles, focus restoration after save/cancel/error, keyboard/DPAD and switch activation, non-color state, touch targets, 200% font scale, and long localized names without clipped controls.

## Compatibility

- Built-in-only runs (empty catalog, no user-defined overrides) produce byte-identical canonical plans to the current implementation. Provenance representation gains one constant sentinel row and a cut-input change; this is a documented provenance format evolution, not a plan behavior change, and existing assertions are updated deliberately.
- Downgrade: an older binary never reads the catalog store (it stays untouched on disk). A pre-336 binary reading the schema-2 override snapshot observes its **existing fail-closed decode outcome** — on the current mainline, typed `Unreadable` and composer `OVERRIDE_UNREADABLE` — never stale or empty S1 data, and never a write. `UnsupportedSchema` remains the contract of a *current* binary encountering a schema newer than it supports; no implementation may retrofit new typed outcomes onto old binaries. Normal Launcher operation and the home layout remain unmodified in every failure case.
- No Launcher DB schema, favorites content, layout application, or recovery contract changes. The store adds no permission, network, or background work.

## Acceptance criteria

- [ ] **AC-1** — User-defined categories have a stable local opaque ID and a display name; identity, canonical ordering, and equality are display-name-independent and rename-invariant. Display names are trim+NFC-normalized, 1–50 code points, and unique within the user-defined catalog (typed duplicate/invalid-name failures, no writes).
- [ ] **AC-2** — Create/rename/delete follow the defined lifecycle with atomic (per-store), recovery-aware, generation+digest persistence, typed success/no-op/conflict/failure outcomes, and full-store verification before success; the two-store delete is explicitly not a cross-store transaction and its partial state is truthful.
- [ ] **AC-3** — Renaming a category keeps every existing assignment, grouping identity, and canonical plan content stable; only subsequently generated titles reflect the new name.
- [ ] **AC-4** — The active catalog safely represents built-in + user-defined identities with disjoint ID namespaces, total canonical order, unchanged built-in fallback, and unchanged bundle taxonomy identity; a catalog/built-in divergence in one input is rejected fail-closed.
- [ ] **AC-5** — The #99 override editor can assign, change, and remove user-defined categories with unchanged S1 precedence and removal semantics; invalid targets fail typed without writes.
- [ ] **AC-6** — The planner and category-consuming strategies treat user-defined categories as first-class (grouping, contiguous ordering, folder naming) under unchanged safety invariants, and folder titles resolve from the same composition's catalog snapshot (never a fresh store read), with preview and apply consuming the same creation-time title.
- [ ] **AC-7** — Creating a category alone never re-classifies any app; no S2–S6 source can target a user-defined category (structurally and via planner validation).
- [ ] **AC-8** — Delete removes assignments explicitly and never silently remaps them; the step-2-failure state (assignments durably removed, empty category remains) is rendered truthfully and completed by retry; dangling assignments are reachable only via external corruption and fail closed.
- [ ] **AC-9** — Catalog generation/digest joins composition provenance and the dynamic cut; empty catalog uses the defined sentinel identity.
- [ ] **AC-10** — Corrupt, duplicate, malformed, digest-invalid, or newer-schema catalog data fails closed (typed, zero-write); concurrent edits surface typed `Conflict`; edits during an active run/recovery/authoring operation are rejected by the lease.
- [ ] **AC-11** — Built-in-only runs keep deterministic, byte-identical plans; mixed-catalog determinism/idempotence/property coverage exists; backup exclusion is verified; downgrade to a pre-336 binary observes its existing fail-closed override outcome (`Unreadable`/`OVERRIDE_UNREADABLE` on the current mainline) without stale or empty S1 consumption and without layout changes.
- [ ] **AC-12** — Focused unit, composer-integration, UI, and connected/device evidence covers create/rename/delete/assign/remove, migration, cut instability, corruption, conflict, title binding, and no-layout-mutation.
- [ ] **AC-13** — TalkBack, keyboard/DPAD, Switch Access, non-color state, focus restoration, and 200% font scale are verified for authoring and assignment flows; no raw IDs in any user UI.
- [ ] **AC-14** — Privacy: diagnostics/journal/export never contain user-defined IDs, display names, or entry contents; exchange exports project user-defined classifications as absent categories while the internal session freshness identity still digests the stable `CategoryIdentity` (detecting reassignment/deletion-driven changes); the store is excluded from backup.

## Explicit non-goals

- AI-driven category creation, suggestion, or auto-confirmed assignment (separate Issue; AI output never persists a category in this Issue).
- Retiring or replacing the Play-Store-derived built-in taxonomy; changing built-in category membership, order, fallback, or enabling built-in rename/delete.
- Using display names as identity.
- External exchange of user-defined categories (exporting their identity/name, or letting #204 intents address them) — a future Issue may propose an explicit, privacy-reviewed projection.
- Arbitrary script/rule execution; rule import/export (FR-012); cloud sync or backup transfer of the catalog.
- Automatic layout application triggered by category edits; category edits never start a run or mutate a plan/preview/recovery state.
- An AI-confirmed assignment path or intent-driven assignment to user-defined categories (#204 intents keep addressing built-in categories only in this Issue).

## Alternatives considered

- **Tombstoned deletion** (keep a hidden marker so old previews/sessions can resolve the deleted ID) — rejected: it adds a second lifecycle state, keeps dead identity in the catalog surface, and invites silent remapping of resolved values; the #331 gate already specifies typed mismatch + replan for changed scope/classification projections, and this Issue's export projection keeps sessions stable without tombstones.
- **Require unassignment before delete** (two separate user operations) — rejected as strictly worse UX than explicit removal inside one operation; the chosen overrides-first protocol provides the same safety with one operation.
- **Allow duplicate display names with a disambiguator** — rejected: opaque IDs are not user-usable disambiguators, and synthetic counters/indexes leak catalog internals into UI and accessibility names; uniqueness under fixed normalization achieves unambiguous presentation with less machinery.
- **Reuse a dummy built-in category** — the problem statement itself; rejected.
- **Fold user-defined entries into the bundle taxonomy or a mutable bundle** — rejected: bundle identity is binary-immutable (ADR-0007); per-user content must not change bundle digest or version.
- **Derive the stable ID from the display name (slug)** — rejected: rename would break identity or require ID-freezing exceptions; equality/order must be rename-invariant.
- **Resolve custom folder titles from a fresh store read at materialization** — rejected: it breaks the composition-cut contract (the resolved catalog could differ from the one the plan was built from) and would couple the application layer to Rule Management storage.

## References

[1]: https://github.com/nunu1733/NunuLauncher/issues/336 "Issue #336 — User-defined categories as first-class taxonomy"
[r1]: https://github.com/nunu1733/NunuLauncher/issues/336#issuecomment-5701419152 "Phase1 review — changes requested"
[r2]: https://github.com/nunu1733/NunuLauncher/issues/336#issuecomment-5701716689 "Phase1 re-review — changes requested (exchange projection)"
[r3]: https://github.com/nunu1733/NunuLauncher/issues/336#issuecomment-5701844245 "Phase1 re-review 2 — approved"
[2]: ../../docs/adr/0007-authoritative-organization-policy-sources.md "ADR-0007 — Authoritative organization policy sources"
[3]: ../../specs/99-user-authored-category-overrides/spec.md "Issue #99 specification — User-authored category overrides"
[4]: ../../specs/83-production-organization-input-sources/spec.md "Issue #83 specification — Production OrganizationInput sources"
[5]: ../../lawnchair/src/app/lawnchair/organizer/rules/CategoryOverrideStore.kt "AtomicFile override store and access boundary"
[6]: ../../lawnchair/src/app/lawnchair/organizer/planning/OrganizationInput.kt "Planner input contract (taxonomy, signals)"
[7]: ../../docs/engineering/organizer-diagnostics.md "Organizer diagnostics and privacy contract"
[8]: ../../specs/331-exchange-target-scope-coupling/spec.md "Issue #331 — Scope binding gate and resolved classification projection"
[9]: ../../docs/engineering/quality-strategy.md "Quality strategy — test surfaces and accessibility evidence"
[10]: ../../lawnchair/src/app/lawnchair/organizer/ui/GeneratedFolderTitles.kt "Production FolderTitleResolver"
