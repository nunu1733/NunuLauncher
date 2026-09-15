package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.ItemId

/**
 * Issue #204: the versioned AI-personalization context export contract
 * (spec 204). Everything in this file is a pure typed model: no Android types,
 * no I/O. The export document never carries the structural source-context
 * digest, internal `ItemId`s, DB row ids, package names, or raw usage times.
 */
object ContextExportContract {
    const val SCHEMA_VERSION = "personalization-context-v1"

    /** V1 fixed capability set: the export always advertises all six. */
    val FIXED_CAPABILITIES: Set<IntentCapability> = setOf(
        IntentCapability.IMPORTANCE,
        IntentCapability.GROUPING,
        IntentCapability.PAGE_AFFINITY,
        IntentCapability.REGION_AFFINITY,
        IntentCapability.PRESERVE,
        IntentCapability.GLOBAL_PREFERENCE,
    )

    const val INTENT_SCHEMA_VERSION = "personalized-intent-v1"

    // Content limits (spec 204 "content limits (V1)"). Overshoot is OVERSIZE.
    const val MAX_EXPORT_ITEMS = 512
    const val MAX_EXPORT_BYTES = 256 * 1024
    const val MAX_INTENT_ENTRIES = 512
    const val MAX_INTENT_UNRESOLVED = 512
    const val MAX_INTENT_BYTES = 128 * 1024
    const val MAX_FREE_TEXT_CHARS = 200
    const val MAX_GROUP_SEMANTIC_FREE_TEXT_CHARS = 100
    const val MAX_RATIONALE_CHARS = 500

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
 * User-authored free-text class (app label, folder title, and any other
 * user-entered text). Every export field that carries such text must belong to
 * this class so tier control stays single-point.
 */
enum class FreeTextClass {
    APP_LABEL,
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
    /** Resolved project category (override included) or null. */
    val category: String?,
    /**
     * Existing folder semantic as taxonomy projection. Folder titles are
     * user-authored free text and never projected.
     */
    val groupSemantic: String?,
    val label: ExportItemLabel?,
    val pageAffinity: ExportPageAffinity?,
    val regionAffinity: ExportRegionKind?,
    val mobility: Mobility,
    /** Present iff [Mobility.FIXED]. */
    val fixReason: FixReason?,
    val usage: UsageProjection?,
) {
    init {
        require(ref.isNotEmpty())
        require((mobility == Mobility.FIXED) == (fixReason != null))
        if (mobility == Mobility.FIXED) require(fixReason != null)
        if (mobility == Mobility.CONDITIONAL) {
            require(role == ExportItemRole.WIDGET)
        }
    }
}

/** The complete `PersonalizationContextExportV1` envelope. */
data class PersonalizationContextExportV1(
    val schemaVersion: String = ContextExportContract.SCHEMA_VERSION,
    val exportId: String,
    val tier: PrivacyTier,
    val grid: ExportGridContext,
    val items: List<ExportItem>,
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
    /** Export-scoped ref → internal `ItemId`. */
    val itemRefs: Map<String, ItemId>,
    val tier: PrivacyTier,
    val sourceContextDigest: String,
    /** #203 snapshot identity at export time. Record only, never matched at import. */
    val signalProvenance: SignalProvenance?,
    val createdAtEpochMs: Long,
    val expiresAtEpochMs: Long,
) {
    init {
        require(exportId.isNotEmpty())
        require(sourceContextDigest.isNotEmpty())
        require(expiresAtEpochMs > createdAtEpochMs)
    }

    fun isExpired(nowEpochMs: Long): Boolean = nowEpochMs >= expiresAtEpochMs
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
