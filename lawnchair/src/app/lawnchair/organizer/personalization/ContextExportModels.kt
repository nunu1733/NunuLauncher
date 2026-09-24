package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ItemId

/**
 * Issue #204: the versioned AI-personalization context export contract
 * (spec 204). Everything in this file is a pure typed model: no Android types,
 * no I/O. The export document never carries the structural source-context
 * digest, internal `ItemId`s, DB row ids, package names, or raw usage times.
 */
object ContextExportContract {
    /**
     * Issue #337 (v4, spec 337 D-1/D-3): every category exposure became an
     * export-scoped ref into the envelope `categories` projection, the intent
     * `groupSemantic` split into exactly-one-of `categoryRef` / `proposalLabel`,
     * and the proposal label adopts the #336 category-name domain. Bumped
     * together with [INTENT_SCHEMA_VERSION] (spec 204 immutable semantic
     * version rule; #330's [INTENT_SCHEMA_VERSION] bump precedent).
     */
    const val SCHEMA_VERSION = "personalization-context-v4"

    /** V1 fixed capability set: the export always advertises all six. */
    val FIXED_CAPABILITIES: Set<IntentCapability> = setOf(
        IntentCapability.IMPORTANCE,
        IntentCapability.GROUPING,
        IntentCapability.PAGE_AFFINITY,
        IntentCapability.REGION_AFFINITY,
        IntentCapability.PRESERVE,
        IntentCapability.GLOBAL_PREFERENCE,
    )

    /** Issue #337 (v4): see [SCHEMA_VERSION]. */
    const val INTENT_SCHEMA_VERSION = "personalized-intent-v4"

    // Content limits (spec 204 "content limits (V1)"). Overshoot is OVERSIZE.
    const val MAX_EXPORT_ITEMS = 512
    const val MAX_EXPORT_BYTES = 256 * 1024
    const val MAX_INTENT_ENTRIES = 512
    const val MAX_INTENT_UNRESOLVED = 512
    const val MAX_INTENT_BYTES = 128 * 1024
    const val MAX_FREE_TEXT_CHARS = 200
    const val MAX_RATIONALE_CHARS = 500

    /**
     * Issue #348: the intent `confidence` numeric constraint, named once so
     * the model check, the codec check, and the AI-facing wire descriptor
     * render the same bounds instead of duplicating `0..100` literals.
     */
    const val CONFIDENCE_MIN = 0
    const val CONFIDENCE_MAX = 100

    /** V1 export session TTL (spec 204 生成規則): 24 hours. */
    const val SESSION_TTL_MS: Long = 24L * 60L * 60L * 1000L
}

/** Privacy tier chosen once per export (spec 204 "privacy tier"). */
enum class PrivacyTier {
    LOCAL_FULL,
    EXTERNAL_REDACTED,
    EXTERNAL_WITH_LABELS,
}

/**
 * User-authored free-text class (app label, folder title, user-defined
 * category display name, and any other user-entered text). Every export field
 * that carries such text must belong to this class so tier control stays
 * single-point.
 */
enum class FreeTextClass {
    APP_LABEL,

    /**
     * Issue #337 (v4): the display name of a user-defined category in the
     * envelope `categories` projection (`ExportCategory.displayName`). The
     * built-in taxonomy id is NOT free text and never belongs to this class.
     */
    USER_CATEGORY_NAME,
}

/**
 * Issue #337 (v4, spec 337 D-1/D-2): the user-authored display name carrier of
 * an advertised user-defined category. The free-text class is part of the model
 * so the single-point tier control can be audited per field (spec 204
 * "FreeTextClass" discipline); the built-in taxonomy id is NOT free text and
 * never uses this carrier.
 */
data class ExportCategoryName(
    val freeTextClass: FreeTextClass,
    val value: String,
) {
    init {
        require(freeTextClass == FreeTextClass.USER_CATEGORY_NAME)
        require(value.isNotEmpty())
        require(value.length <= ContextExportContract.MAX_FREE_TEXT_CHARS)
    }
}

/**
 * Issue #337 (v4, spec 337 D-1): the kind of one advertised category entry.
 * The value itself carries no personal data.
 */
enum class CategoryRefKind {
    BUILT_IN,
    USER_DEFINED,
}

/**
 * Issue #337 (v4, spec 337 D-1/D-2): one advertised category of the export's
 * `categories` projection — the only way an intent can reference an existing
 * (built-in or user-defined) category. The [ref] is an export-scoped random
 * identifier from the same allocator seam and entropy contract as the item
 * refs; the ref → `CategoryIdentity` mapping lives in the export session only,
 * so neither the stable `UserCategoryId` nor the display name is an identity.
 *
 * - [taxonomyId]: the immutable built-in taxonomy value; present iff
 *   [kind] is [CategoryRefKind.BUILT_IN]. It is a taxonomy enum spelling, not
 *   user-authored free text, so every privacy tier carries it.
 * - [displayName]: the user-authored free-text class carrier
 *   ([FreeTextClass.USER_CATEGORY_NAME]); present iff [kind] is
 *   [CategoryRefKind.USER_DEFINED] and the export's privacy tier admits that
 *   class (`EXTERNAL_REDACTED` never carries it).
 */
data class ExportCategory(
    val ref: String,
    val kind: CategoryRefKind,
    val taxonomyId: String? = null,
    val displayName: ExportCategoryName? = null,
) {
    init {
        require(ref.isNotEmpty())
        when (kind) {
            CategoryRefKind.BUILT_IN -> {
                require(taxonomyId != null && taxonomyId.isNotEmpty())
                require(displayName == null)
            }

            CategoryRefKind.USER_DEFINED -> require(taxonomyId == null)
        }
    }
}

/**
 * Semantic placement roles the intent may address (spec 235 role family).
 * `APP_PAIR` / `SHORTCUT_LEGACY` / `Unknown` kinds never become export items;
 * they are aggregated as [PreservedConstraints] counts only.
 */
enum class ExportItemRole {
    APP_OR_SHORTCUT,
    FOLDER,
    WIDGET,
}

/** Per-item mobility projection (spec 204 "per-item mobility projection"). */
enum class Mobility {
    MOVABLE,
    CONDITIONAL,
    FIXED,

    /**
     * Issue #331 (v2): a not-yet-placed missing-app candidate (spec 331
     * "subject identity for not-yet-placed apps"). The subject has no current
     * workspace placement: it is neither movable (nothing to move) nor fixed
     * (nothing to preserve) — it is a creation candidate. Carries no
     * `fixReason` (the `mobility == FIXED ⇔ fixReason != null` invariant
     * still holds).
     */
    CANDIDATE,
}

/**
 * Issue #331 (v2): whether an export item is backed by an existing workspace
 * placement ([PLACED]) or by a user-selected not-yet-placed missing-app
 * candidate ([CANDIDATE]). The enum value itself carries no personal data;
 * raw identities stay in the export session only.
 */
enum class ExportItemSubject {
    PLACED,
    CANDIDATE,
}

/**
 * Underlying fixed cause determined at export time. Deliberately NOT a copy of
 * the run-time `PreserveReason`: e.g. a folder member receives the run-time
 * `NON_TARGET` in a full-target composition, while its export-time underlying
 * cause stays `FOLDER_MEMBER`.
 */
enum class FixReason {
    RESERVED_REGION,
    LOCKED,
    UNAVAILABLE,
    DOCK,
    APP_PAIR_MEMBER,
    FOLDER_MEMBER,
}

/** Coarse vertical workspace band (region affinity abstraction level). */
enum class ExportRegionKind {
    TOP,
    MIDDLE,
    BOTTOM,
}

/**
 * #203 bucket projection for one export item (spec 204 "usage signal
 * projection (V1)"). Values are closed bucket ordinals; absent fields are
 * omitted. Raw milliseconds, timestamps, package names never appear.
 */
data class UsageProjection(
    val foreground30d: Int? = null,
    val foreground7d: Int? = null,
    val recency: Int? = null,
    val activeDays: Int? = null,
    val launcherCount: Int? = null,
    val launcherRecency: Int? = null,
) {
    init {
        foreground30d?.let { require(it in 0..4) }
        foreground7d?.let { require(it in 0..4) }
        recency?.let { require(it in 0..3) }
        activeDays?.let { require(it in 0..4) }
        launcherCount?.let { require(it in 0..4) }
        launcherRecency?.let { require(it in 0..3) }
    }
}

data class UsageSignalEntry(
    val ref: String,
    val usage: UsageProjection,
)

/** Optional envelope-level `usageSignals` section (tier-controlled). */
data class UsageSignalsSection(
    val entries: List<UsageSignalEntry>,
)

/** Minimal device/grid projection. No platform types. */
data class ExportGridContext(
    val columns: Int,
    val rows: Int,
    val pageCount: Int,
) {
    init {
        require(columns > 0)
        require(rows > 0)
        require(pageCount >= 0)
    }
}

/**
 * Minimal aggregate of non-addressable preserved subjects. No item identity,
 * no `ref`: the AI cannot reference these through the intent.
 */
data class PreservedConstraints(
    val reservedRegions: List<ReservedRegionProjection>,
    val preservedCounts: Map<PreservedReason, Int>,
)

/** Closed count classes for non-addressable preserved subjects. */
enum class PreservedReason {
    APP_PAIR,
    LEGACY_SHORTCUT,
    UNSUPPORTED_CONTAINER,
    UNKNOWN_KIND,
}

data class ReservedRegionProjection(
    val pageOrdinal: Int,
    val cellX: Int,
    val cellY: Int,
    val spanWidth: Int,
    val spanHeight: Int,
)

/**
 * V1 fixed capability advertisement. The export builder always emits the full
 * set; subsets are a future schema version (CAPABILITY_UNSUPPORTED stays
 * reserved/unreachable in V1).
 */
data class ExportCapabilities(
    val intentSchemaVersion: String,
    val functions: Set<IntentCapability>,
)

/** The one optional user-authored free-text field of an export item. */
data class ExportItemLabel(
    val freeTextClass: FreeTextClass,
    val value: String,
) {
    init {
        require(freeTextClass == FreeTextClass.APP_LABEL)
        require(value.isNotEmpty())
        require(value.length <= ContextExportContract.MAX_FREE_TEXT_CHARS)
    }
}

data class ExportPageAffinity(
    /** Zero-based workspace page ordinal (capture order). */
    val pageOrdinal: Int,
) {
    init {
        require(pageOrdinal >= 0)
    }
}

/** One addressable export item (`ref` holder). */
data class ExportItem(
    val ref: String,
    val role: ExportItemRole,
    /**
     * Issue #337 (v4): resolved project category (override included) as an
     * advertised [ExportCategory] ref, or null. Never a raw built-in value, a
     * `UserCategoryId`, or a display name (spec 337 D-1/D-3).
     */
    val categoryRef: String?,
    /**
     * Existing folder semantic as an advertised category ref. Folder titles
     * are user-authored free text and never projected.
     */
    val folderCategoryRef: String?,
    val label: ExportItemLabel?,
    val pageAffinity: ExportPageAffinity?,
    val regionAffinity: ExportRegionKind?,
    val mobility: Mobility,
    /** Present iff [Mobility.FIXED]. */
    val fixReason: FixReason?,
    val usage: UsageProjection?,
    /** Issue #331 (v2): placement-backed or candidate-backed subject. */
    val subject: ExportItemSubject = ExportItemSubject.PLACED,
) {
    init {
        require(ref.isNotEmpty())
        require((mobility == Mobility.FIXED) == (fixReason != null))
        if (mobility == Mobility.FIXED) require(fixReason != null)
        if (mobility == Mobility.CONDITIONAL) {
            require(role == ExportItemRole.WIDGET)
        }
        // Issue #331 invariants: a candidate subject has no current placement,
        // so it projects no current-position fields and is never FIXED.
        if (subject == ExportItemSubject.CANDIDATE) {
            require(mobility == Mobility.CANDIDATE)
            require(role == ExportItemRole.APP_OR_SHORTCUT)
            require(pageAffinity == null && regionAffinity == null && folderCategoryRef == null)
        }
        if (mobility == Mobility.CANDIDATE) require(subject == ExportItemSubject.CANDIDATE)
        if (categoryRef != null) require(categoryRef.isNotEmpty())
        if (folderCategoryRef != null) require(folderCategoryRef.isNotEmpty())
    }
}

/** The complete `PersonalizationContextExportV1` envelope. */
data class PersonalizationContextExportV1(
    val schemaVersion: String = ContextExportContract.SCHEMA_VERSION,
    val exportId: String,
    val tier: PrivacyTier,
    val grid: ExportGridContext,
    val items: List<ExportItem>,
    /**
     * Issue #337 (v4): the active category catalog projection (built-in
     * members plus every user-defined entry) in canonical identity order. The
     * only category namespace an intent may reference.
     */
    val categories: List<ExportCategory>,
    val preservedConstraints: PreservedConstraints,
    val capabilities: ExportCapabilities,
    val usageSignals: UsageSignalsSection?,
) {
    init {
        require(schemaVersion == ContextExportContract.SCHEMA_VERSION)
        require(exportId.isNotEmpty())
        require(items.size <= ContextExportContract.MAX_EXPORT_ITEMS)
        require(capabilities.intentSchemaVersion == ContextExportContract.INTENT_SCHEMA_VERSION)
        require(capabilities.functions == ContextExportContract.FIXED_CAPABILITIES)
        require(items.map { it.ref }.toSet().size == items.size)
        val itemRefs = items.map { it.ref }.toSet()
        usageSignals?.let { section ->
            require(section.entries.size <= ContextExportContract.MAX_EXPORT_ITEMS)
            require(section.entries.all { it.ref in itemRefs })
        }
        // Issue #337: the advertised category ref namespace is its own
        // allow-list — unique, disjoint from the item ref namespace, and the
        // only namespace item category projection may point into.
        val categoryRefs = categories.map { it.ref }
        require(categoryRefs.toSet().size == categoryRefs.size)
        val categoryRefSet = categoryRefs.toSet()
        require(itemRefs.intersect(categoryRefSet).isEmpty())
        require(items.all { it.categoryRef == null || it.categoryRef in categoryRefSet })
        require(items.all { it.folderCategoryRef == null || it.folderCategoryRef in categoryRefSet })
        if (tier == PrivacyTier.EXTERNAL_REDACTED) {
            require(categories.none { it.kind == CategoryRefKind.USER_DEFINED && it.displayName != null })
        }
    }
}

enum class IntentCapability {
    IMPORTANCE,
    GROUPING,
    PAGE_AFFINITY,
    REGION_AFFINITY,
    PRESERVE,
    GLOBAL_PREFERENCE,
}

/**
 * Durable, expiring export session. Held by the app-private store only; never
 * part of the export document. The ref↔`ItemId` map and the structural
 * `sourceContextDigest` live only here.
 */
data class ExportSession(
    val exportId: String,
    /** Export-scoped ref → internal `ItemId`. Candidate refs map to their planning IDs. */
    val itemRefs: Map<String, ItemId>,
    val tier: PrivacyTier,
    val sourceContextDigest: String,
    /** #203 snapshot identity at export time. Record only, never matched at import. */
    val signalProvenance: SignalProvenance?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
    /**
     * Issue #331 (v2): the export scope's candidate identities (spec 331
     * "scope binding"). Empty for full-organization exports. App-private
     * stable identities; never part of the export document.
     */
    val scopeCandidates: List<CandidateTarget.AppKey> = emptyList(),
    /**
     * Issue #331 (v2): digest over the export-time candidate projection
     * (identity + availability + resolved category), recomputed at binding
     * (spec 331 D-4). Session-local; never part of the export document.
     */
    val scopeCandidateDigest: String = CandidateScopeIdentity.EMPTY_DIGEST,
    /**
     * Issue #337 (v4): the advertised category ref → stable `CategoryIdentity`
     * mapping (spec 337 D-1/D-5). App-private and never part of the export
     * document: it is the only place a category ref resolves to an identity, so
     * the document itself carries no stable identifier. Empty for a session
     * record written before v4 (those refs cannot resolve -> typed fail-closed).
     */
    val categoryRefs: Map<String, CategoryIdentity> = emptyMap(),
    /**
     * Issue #417 (spec 417 "Data and state"): the durable entry origin —
     * whether this request was authored inside a run with an explicit scope
     * selection ([ExportEntryOrigin.RUN_IN]) or outside one
     * ([ExportEntryOrigin.IDLE]). Written exactly once at session creation
     * (save) and immutable afterwards. `null` only on a record written before
     * #417 (absent = unknown); readers observe it through
     * [resolvedEntryOrigin].
     */
    val entryOrigin: ExportEntryOrigin? = null,
) {
    init {
        require(exportId.isNotEmpty())
        require(sourceContextDigest.isNotEmpty())
        require(expiresAtEpochMs > createdAtEpochMs)
        require(scopeCandidates.size == scopeCandidates.map { CandidatePlanningIds.planningId(it) }.toSet().size)
        if (scopeCandidates.isNotEmpty()) {
            require(scopeCandidates.all { CandidatePlanningIds.planningId(it) in itemRefs.values.toSet() })
        }
        require(categoryRefs.keys.none { it in itemRefs.keys })
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs

    /** The refs bound to candidate subjects (spec 331 candidate partition). */
    val candidateRefs: Set<String>
        get() {
            val ids = scopeCandidates.map { CandidatePlanningIds.planningId(it) }.toSet()
            return itemRefs.filterValues { it in ids }.keys
        }

    /**
     * Issue #417 legacy decode rule (spec 417 "Data and state"): the entry
     * origin callers observe. A present [entryOrigin] is respected as-is
     * (written once at session creation, immutable afterwards). An ABSENT
     * origin decodes from the durable scope fields: non-empty
     * [scopeCandidates] = legacy RUN_IN — a pre-#417 record could only carry
     * candidates when authored in a scope-selected run, so the origin is
     * uniquely recoverable and #375's selection-restore rebind semantics are
     * kept; empty scope = IDLE, the fail-safe arm (a legacy IDLE record and a
     * zero-selection RUN_IN record are indistinguishable, and neither may
     * regain direct attach authority).
     */
    val resolvedEntryOrigin: ExportEntryOrigin
        get() = entryOrigin
            ?: if (scopeCandidates.isEmpty()) ExportEntryOrigin.IDLE else ExportEntryOrigin.RUN_IN
}

/** Identity of the #203 snapshot used at export time. */
data class SignalProvenance(
    val schemaVersion: String,
    val contentDigest: String,
) {
    init {
        require(schemaVersion.isNotEmpty())
        require(contentDigest.isNotEmpty())
    }
}
