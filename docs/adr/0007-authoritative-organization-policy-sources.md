---
status: accepted
---

# Use one immutable policy bundle as the authority for OrganizationInput policy sources

## Context

Issue #83 must compose a fresh canonical layout capture into the accepted
`OrganizationInput` contract without allowing UI or coordinator code to invent
rules, taxonomy, classification signals, or target membership. The planner
already consumes `RuleSemantics`, `TaxonomyContract`, `ClassificationSignals`,
and `TargetSet`, but it deliberately does not own production I/O or policy
persistence.

`DESIGN.md` assigns versioned rule loading, validation, migration, and export to
Rule Management while leaving the file syntax and category-override ownership to
later decisions. The Issue #5/#6 research documents are useful inputs, but their
status is `Proposed`; legacy Flowerpot is also not a typed, versioned organizer
source. Reading four independently mutable sources would additionally allow a
mixed policy cut when an update occurs between reads.

This ADR resolves Issue #86 only. It does not authorize Issue #83 production
implementation before that Issue's spec is accepted and its canonical
`plan.md` is reviewed.

## Decision

### 1. Authority model

For manual `FullOrganization` v1, Rule Management owns one immutable built-in
`OrganizerPolicyBundle` as the policy authority. The bundle is shipped with the
application and exposed to integration through a typed read-only source under
`app.lawnchair.organizer.rules`. It is not Flowerpot, not a UI preference, and
not a mutable downloaded/imported file.

The four planner inputs have exactly these production owners/sources:

| Planner input | Production owner/source | Materialization rule |
|---|---|---|
| `RuleSemantics` | Rule Management, `OrganizerPolicyBundle.rules` | Direct immutable projection of the active bundle. |
| `TaxonomyContract` | Rule Management, `OrganizerPolicyBundle.taxonomy` | Direct immutable projection of the same active bundle. |
| `ClassificationSignals` | Rule Management policy plus one integration-owned `ClassificationSignalSnapshotSource` | Materialized from the canonical capture, the same bundle's classification policy, one profile-scoped override snapshot, and stable platform evidence. Integration is an adapter, not a second policy owner. |
| full-organization `TargetSet` | Rule Management, `OrganizerPolicyBundle.fullOrganizationTargets`, materialized by the #83 composition boundary | Complete partition derived only from the canonical capture and the same bundle's target policy. |

The #83 composer may expose narrower internal ports, but there must be only one
production authority for each row above. Test doubles may implement the same
ports; UI/coordinator code may not construct policy values directly.

### 2. Built-in bundle v1 content

The active bundle for the current planner has bundle semantic version
`organization-policy-v1` and supports only the following public planner
versions:

- `RuleVersion("v1")`;
- `TaxonomyVersion("v1")`;
- classification policy version `classification-v1`;
- full-organization target policy version `full-target-v1`.

The `RuleSemantics` projection is exactly:

- `FolderPolicy(minGroupSize = 2, SAME_PROFILE_ONLY)`;
- `DockPolicy.PRESERVE`;
- `OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE`;
- `FallbackCategoryPolicy.KEEP_AS_SINGLETON`;
- `OrderingPolicy.CANONICAL_V1`.

The taxonomy projection contains exactly these 34 category IDs, in canonical
byte order when exposed to the planner:

`ART`, `AUTO`, `BEAUTY`, `BOOKS`, `BUSINESS`, `COMICS`, `COMMUNICATION`,
`DATING`, `EDUCATION`, `ENTERTAINMENT`, `EVENTS`, `FINANCE`, `FOOD`, `GAME`,
`HEALTH`, `HOUSE`, `LIBRARIES`, `LIFESTYLE`, `MAPS`, `MEDICAL`, `MUSIC`,
`NEWS`, `OTHER`, `PARENTING`, `PERSONALIZATION`, `PHOTOGRAPHY`,
`PRODUCTIVITY`, `SHOPPING`, `SOCIAL`, `SPORTS`, `TOOLS`, `TRAVEL`, `VIDEO`,
`WEATHER`.

`OTHER` is the sole fallback category. A rule/taxonomy binding is valid only for
this declared pair; neither version is inferred from the other.

The bundle also owns the classification priority `S1 > S2 > S3 > S4 > S5 >
S6` and these adapter semantics:

- **S1 user override:** profile-scoped `(package, profile) -> CategoryId` data
  comes only from the Rule Management `CategoryOverrideStore` described below.
- **S2 Android category:** a successfully read, supported
  `ApplicationInfo.category` maps through the taxonomy-v1 mapping. Undefined or
  unmapped values mean "no S2 observation"; an I/O/platform read failure is not
  converted to absence.
- **S3 package rule:** the built-in v1 package-rule table is deliberately and
  explicitly empty. This is bundle content, not a missing-source fallback.
  Adding package rules requires a new bundle identity; Flowerpot is not read.
- **S4 intent rule:** the built-in v1 intent-rule table is deliberately and
  explicitly empty. A future non-empty table must declare the exact queried
  intents and mappings in a new bundle identity.
- **S5 Google/system evidence:** Google-package evidence maps to `TOOLS` before
  system-app evidence maps to `OTHER`, matching the accepted planner-side S5
  shape. If a target/profile is explicitly unavailable, lack of S5 evidence is
  valid absence; an unexpected read error for an otherwise readable target is a
  source failure.
- **S6:** no production adapter manufactures an S6 entry merely to cover a read
  failure. When no higher signal is present, the planner's accepted fallback
  behavior resolves S6 to `taxonomy.fallbackCategory`.

An empty S1/S3/S4 observation set is therefore valid only when it is the value of
its authoritative source. "Could not read the source" is never represented as an
empty list.

### 3. Category override source

S1 overrides are owned by Rule Management in an app-private, local-only
`CategoryOverrideStore`. Its public/internal contract is a typed snapshot, not a
specific JSON/XML/SQLite format; the storage format remains a Rule Management
implementation detail.

The v1 snapshot contract is:

- key: package identity plus captured `ProfileId`;
- value: one taxonomy-v1 `CategoryId`;
- identity: schema version, monotonically changing generation, and content
  digest;
- first-run state: a defined empty generation-0 snapshot;
- physical absence before the first write is the representation of that defined
  empty state, not a generic "missing source" rule;
- corruption, permission/I/O failure, unsupported schema, or contradictory
  duplicate values are read failures and fail closed;
- diagnostics never include package names, profile identifiers, or raw override
  content.

Issue #83 only needs the read side. Any user-facing override authoring UI/write
operation is separate work and must use Rule Management rather than mutate this
source from the composer.

The store is excluded from device/cloud backup in v1. Profile identities are
capture-local and must not be transplanted to another device by generic app
restore. A restored installation therefore starts from the defined empty
snapshot unless a future accepted migration/restore decision introduces a safe
identity-remapping protocol.

### 4. Full-organization TargetSet policy

`FullOrganization` has no candidate additions. `TargetSet.additions` is always
an explicitly authored empty list in `full-target-v1`; this is not a missing
source fallback.

Every item in the canonical capture appears exactly once in
`TargetSet.existing`. The source applies these rules in precedence order:

1. Any `locked = true` item is `Preserved`.
2. Any item whose `Availability` is not `AVAILABLE` (`DISABLED`, `QUIET`,
   `LOCKED_PRIVATE_SPACE`, or `UNAVAILABLE`) is `Preserved`.
3. Any Dock item is `Preserved`.
4. Folder members and app-pair members are `Preserved` structural members.
5. `APPWIDGET`, `CUSTOM_APPWIDGET`, `APP_PAIR`, and `SHORTCUT_LEGACY` are
   `Preserved`.
6. An available, unlocked, top-level workspace `APPLICATION`, `DEEP_SHORTCUT`,
   or `FOLDER` is `Movable`.
7. Any otherwise valid captured item not admitted by the move policy is
   `Preserved`, never omitted.

Unknown item kinds, unsupported containers, dangling/contradictory references,
invalid placement structure, unknown lock truth, or a profile/context that
cannot be represented canonically are not converted to `Preserved`. They make
composition `NotReady` before planner invocation. Existing mixed-profile
folders/app pairs are not rewritten: their captured profile identities and
members remain intact; a valid top-level folder may still move as one unit while
its children remain structural/preserved.

This policy makes target membership a complete partition and prevents
out-of-scope rows, quiet/private-space items, widgets, legacy shortcuts, Dock
items, or unavailable targets from disappearing merely because they are not
move candidates.

### 5. Immutable identities and provenance

Every emitted input has an immutable identity with at least:

- a closed source kind;
- semantic version or generation;
- SHA-256 digest of a canonical byte representation.

At minimum, #83 records identities for rules, taxonomy, signals, and targets in
`InputProvenance`. The identity rule is strict: the same `(source kind,
version/generation,digest)` may never name different content, and content may
never be replaced in place while retaining the same identity.

The built-in bundle itself has a `PolicyBundleIdentity` containing its semantic
version and SHA-256 digest over the canonical representations of the rule,
taxonomy, classification-policy, and target-policy projections. Individual rule
and taxonomy identities include the bundle identity.

Materialized signals additionally identify the override snapshot generation and
content digest plus the canonical digest of the platform evidence/result used to
produce `ClassificationSignals`. Materialized targets include the target-policy
version and canonical digest of the complete membership result. The canonical
layout `RevisionId` remains a separate provenance field; it is not overloaded as
a policy version.

Diagnostics may record source kind, version/generation, result code, and opaque
content digest. They must not record raw policy content, package/component
identity, profile identity, layout coordinates, or override values.

### 6. Consistent policy cut and read-after-validate retry

The built-in bundle is immutable for the lifetime of the installed binary, so
rules, taxonomy, and the policy portions of signal/target materialization cannot
change between reads. Dynamic override/platform evidence still can change while
classification signals are being constructed, so #83 uses the following
bounded read-after-validate protocol:

1. Capture the canonical layout once through the accepted application capture
   boundary; retain its `RevisionId` and profile/availability state.
2. Read and validate the active built-in `PolicyBundleIdentity`.
3. Read override snapshot **A**.
4. Read all platform classification evidence needed for the captured eligible
   app/shortcut identities, canonicalize it, and compute evidence digest **E1**.
5. Re-read the override snapshot as **B** and re-read the same platform evidence
   as **E2**.
6. The cut is stable only when `A.identity == B.identity` and `E1 == E2` and the
   built-in bundle identity is unchanged. Only then materialize all four inputs
   and their provenance.
7. If the cut changed, discard every value from that attempt and retry the whole
   dynamic read once. Two complete attempts are the maximum for one compose
   call.
8. If the second attempt is still unstable, return typed
   `NotReady(InconsistentPolicyRead)` and do not invoke the planner.

A future source may replace this protocol with an atomic snapshot/fence token,
but only through another accepted decision that provides equivalent or stronger
consistency. Four independent reads followed by version-string comparison are
not sufficient.

The same value-equal canonical capture plus the same four policy input identities
must produce a value-equal `OrganizationInput`. If that cannot be established,
composition returns a value-equal typed non-write result instead.

### 7. Failure and compatibility semantics

The composer fails closed before planner invocation for any of these conditions:

- built-in bundle missing/corrupt or its declared digest does not verify;
- unsupported rule/taxonomy/classification/target version;
- invalid rule/taxonomy binding;
- override store unreadable/corrupt/unsupported;
- unexpected platform evidence read failure;
- contradictory signal evidence or category outside the accepted taxonomy;
- inconsistent dynamic read after the bounded retry;
- incomplete or duplicate target partition;
- canonical capture/profile/lock/context that cannot be represented safely.

A semantically valid "no observation" is not a failure: undefined Android
category, no override, an explicit no-match in the S3/S4 tables, or evidence
that is intentionally unavailable for an already-unavailable target may simply
leave that signal absent and allow the planner's S6 fallback.

`NotReady` is non-write. It must not call `OrganizationPlanner`, create a
recovery point, call the application writer, modify the Launcher DB, or mutate
the override store. UI/coordinator code receives only the typed readiness result
and cannot replace it with a local default.

### 8. Upgrade, migration, downgrade, backup, and rollback

Built-in policy bundles are immutable application artifacts. They have no
in-place migration. A changed rule/taxonomy/classification/target policy is
published under a new semantic version/generation and new digest. The installed
binary declares one active bundle; if that bundle is unsupported by its
composer/planner compatibility matrix, organization is unavailable rather than
silently selecting an older bundled policy.

The v1 override store uses schema version 1. A future schema migration must:

1. read the old snapshot without modifying it;
2. validate and convert into a new snapshot;
3. publish the new snapshot atomically with a new generation/digest;
4. leave the old snapshot intact if conversion or publication fails.

A failed migration makes the source unavailable for organization and leaves the
home layout unchanged. A downgrade never rewrites a newer override store into an
older schema. If the older binary cannot read the newer schema, its organizer
fails closed; normal Launcher operation and the active home layout remain
unmodified.

Built-in bundles are restored by installing the corresponding application binary,
not from app backup. The override store is excluded from backup/restore in v1 as
described above. Therefore application rollback/downgrade changes only which
binary-owned immutable bundle is active; it is never a reason to restore or
rewrite Launcher layout data.

## Alternatives considered

### Let UI/coordinator construct defaults

Rejected. It would create a second policy owner, violate Issue #52/#83 dependency
direction, and make missing-source behavior indistinguishable from a valid empty
policy.

### Adopt Flowerpot as the S3 authority

Rejected. Flowerpot does not provide the organizer taxonomy/version/profile,
immutable identity, failure, migration, or consistency contract. Its content may
inform a future explicitly versioned rule bundle, but it is not read by v1.

### Persist four separately mutable policy files

Rejected for v1. It creates avoidable migration and mixed-snapshot risk before
rule import/export is a product requirement. A single built-in immutable bundle
is sufficient for the manual MVP and keeps future mutable/imported rule support
behind Rule Management.

### Compare only version strings after four reads

Rejected. A version may be accidentally reused for changed content and an update
can occur during the read. Immutable content digests plus a shared bundle
identity and dynamic read validation are required.

### Add provenance to the planner public model

Rejected as unnecessary. The planner remains a pure consumer of the accepted
`OrganizationInput`; provenance/readiness is owned by the #83 composition
boundary.

### Treat unreadable signal sources as empty observations

Rejected. That would make a platform/storage failure select S6 and could change
classification and placement while appearing successful. Only semantically valid
absence may fall through.

## Consequences

Issue #83 can now close its Stage A open questions without inventing production
policy in integration code. Its accepted spec must reference this ADR, carry all
four immutable input identities plus the bundle/cut identity in provenance,
implement the bounded consistency protocol, and expose typed non-write failures.

The manual MVP intentionally has no Flowerpot-backed S3 rules, no S4 intent rules,
no drawer-wide additions, and no cloud-restored category overrides. Those are
explicit v1 policy choices rather than accidental defaults. Adding any of them
requires changing the authoritative bundle or persistence contract under a new
immutable identity and, where the architecture trade-off changes, a follow-up
accepted decision.

Issue #83 remains the implementation owner for the production composer/adapters.
Issue #52 remains blocked until #83 has an accepted spec/plan and its production
output is implemented on `main`.
