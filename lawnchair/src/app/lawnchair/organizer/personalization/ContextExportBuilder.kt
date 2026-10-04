package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ReservationOverlapAcceptance
import app.lawnchair.organizer.planning.TargetSet

/**
 * Issue #204: the pure builder of `PersonalizationContextExportV1`
 * (spec 204 "生成規則"). Side-effect free: no writes of any kind.
 *
 * - `ref`/`exportId` allocation is delegated to the injected
 *   [RandomIdAllocator] (fresh random per generation; never derived from the
 *   canonical inputs; the builder validates export-scoped uniqueness).
 * - The structural `sourceContextDigest` is computed by [SourceContextIdentity]
 *   and returned for the session only — it never enters the export document.
 * - Per-item mobility is projected with the same first-match precedence as the
 *   planner's export-time-projectable fixed causes (verified against
 *   `determinePreservation` / `FullTargetSetMaterializer` on the baseline).
 * - Free-text control is single-point in this builder: labels only appear in
 *   `LOCAL_FULL` / `EXTERNAL_WITH_LABELS`; `EXTERNAL_REDACTED` excludes the
 *   whole user-authored free-text class and never generates surrogates.
 * - The #203 snapshot feeds the optional `usageSignals` projection and the
 *   session signal provenance only — never the structural digest.
 */
object ContextExportBuilder {

    fun build(inputs: ExportInputs, tier: PrivacyTier, allocator: RandomIdAllocator): BuiltExport {
        val snapshot = inputs.snapshot
        val pageOrdinal = snapshot.pages.withIndex().associate { (index, page) -> page.id to index }
        val refsByItem = LinkedHashMap<ItemId, String>()
        val folderSemantics = LinkedHashMap<String, String?>()

        for (item in snapshot.items) {
            if (item.kind is ItemKind.FOLDER) {
                folderSemantics[item.id.value] = inputs.resolvedCategories[item.id]
            }
        }

        var appPairCount = 0
        var legacyShortcutCount = 0
        var unsupportedContainerCount = 0
        var unknownKindCount = 0
        val items = ArrayList<ExportItem>(snapshot.items.size)

        for (item in snapshot.items) {
            val excludedReason = when {
                item.kind is ItemKind.APP_PAIR -> PreservedReason.APP_PAIR
                item.kind is ItemKind.SHORTCUT_LEGACY -> PreservedReason.LEGACY_SHORTCUT
                item.kind is ItemKind.Unknown -> PreservedReason.UNKNOWN_KIND
                item.placement is CapturedPlacement.UnsupportedContainer -> PreservedReason.UNSUPPORTED_CONTAINER
                else -> null
            }
            if (excludedReason != null) {
                when (excludedReason) {
                    PreservedReason.APP_PAIR -> appPairCount++
                    PreservedReason.LEGACY_SHORTCUT -> legacyShortcutCount++
                    PreservedReason.UNSUPPORTED_CONTAINER -> unsupportedContainerCount++
                    PreservedReason.UNKNOWN_KIND -> unknownKindCount++
                }
                continue
            }
            items += item.toExportItem(inputs, snapshot, tier, folderSemantics, pageOrdinal, allocator, refsByItem)
        }
        require(items.size <= ContextExportContract.MAX_EXPORT_ITEMS)

        val preserved = PreservedConstraints(
            reservedRegions = snapshot.reservedWorkspaceRegions.map { region ->
                ReservedRegionProjection(
                    pageOrdinal = pageOrdinal[region.page.pageId] ?: -1,
                    cellX = region.cell.x,
                    cellY = region.cell.y,
                    spanWidth = region.span.width,
                    spanHeight = region.span.height,
                )
            },
            preservedCounts = buildMap {
                if (appPairCount > 0) put(PreservedReason.APP_PAIR, appPairCount)
                if (legacyShortcutCount > 0) put(PreservedReason.LEGACY_SHORTCUT, legacyShortcutCount)
                if (unsupportedContainerCount > 0) {
                    put(PreservedReason.UNSUPPORTED_CONTAINER, unsupportedContainerCount)
                }
                if (unknownKindCount > 0) put(PreservedReason.UNKNOWN_KIND, unknownKindCount)
            },
        )

        val export = PersonalizationContextExportV1(
            exportId = allocator.newId(),
            tier = tier,
            grid = ExportGridContext(
                columns = snapshot.device.columns,
                rows = snapshot.device.rows,
                pageCount = snapshot.pages.size,
            ),
            items = items,
            preservedConstraints = preserved,
            capabilities = ExportCapabilities(
                intentSchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
                functions = ContextExportContract.FIXED_CAPABILITIES,
            ),
            usageSignals = buildUsageSection(inputs, refsByItem),
        )
        val session = ExportSession(
            exportId = export.exportId,
            itemRefs = refsByItem.entries.associate { (id, ref) -> ref to id },
            tier = tier,
            sourceContextDigest = SourceContextIdentity.digest(
                CanonicalStructuralInputs(snapshot, inputs.targets, inputs.resolvedCategories),
            ),
            signalProvenance = inputs.signals?.let {
                SignalProvenance(schemaVersion = it.schemaVersion, contentDigest = it.contentDigest)
            },
            createdAtEpochMs = inputs.nowEpochMs,
            expiresAtEpochMs = inputs.nowEpochMs + ContextExportContract.SESSION_TTL_MS,
        )
        return BuiltExport(export, session)
    }
}

/** Inputs for one export attempt. */
data class ExportInputs(
    val snapshot: LayoutSnapshot,
    val targets: TargetSet,
    /** Resolved classification (override included) keyed by internal `ItemId`. */
    val resolvedCategories: Map<ItemId, String?> = emptyMap(),
    /** User-authored app labels keyed by internal `ItemId` (free-text class). */
    val userLabels: Map<ItemId, String> = emptyMap(),
    /** #203 snapshot if the tier permits a usage projection and it is ready. */
    val signals: PersonalizationSignalSnapshot? = null,
    /** Internal ItemId ↔ #203 entry-key resolution for the usage projection. */
    val usageKeysByItem: Map<ItemId, PersonalizationEntryKey> = emptyMap(),
    /** Wall-clock anchor for the session TTL. */
    val nowEpochMs: Long = 0L,
)

/** Export document plus the session only the store persists. */
data class BuiltExport(
    val export: PersonalizationContextExportV1,
    val session: ExportSession,
)

/**
 * Export-time fixed-cause precedence (first match wins), verified against the
 * planner's `determinePreservation` and `FullTargetSetMaterializer`:
 * reserved overlap → locked → unavailable → dock → widget (conditional) →
 * app-pair member → folder member, else movable. `NON_TARGET` /
 * `STRATEGY_PRESERVED` are run-time-only reasons and are deliberately not
 * projected.
 */
internal fun projectMobility(item: CapturedItem, snapshot: LayoutSnapshot): Pair<Mobility, FixReason?> {
    val reservedOverlap = (item.placement as? CapturedPlacement.Workspace)
        ?.let { ws ->
            ReservationOverlapAcceptance.overlaps(
                ws.page.pageId,
                ws.cell,
                ws.span,
                snapshot.reservedWorkspaceRegions,
            )
        } == true
    return when {
        reservedOverlap -> Mobility.FIXED to FixReason.RESERVED_REGION
        item.locked -> Mobility.FIXED to FixReason.LOCKED
        item.availability != Availability.AVAILABLE -> Mobility.FIXED to FixReason.UNAVAILABLE
        item.placement is CapturedPlacement.Dock -> Mobility.FIXED to FixReason.DOCK
        item.kind is ItemKind.APPWIDGET || item.kind is ItemKind.CUSTOM_APPWIDGET -> Mobility.CONDITIONAL to null
        item.placement is CapturedPlacement.AppPairMember -> Mobility.FIXED to FixReason.APP_PAIR_MEMBER
        item.placement is CapturedPlacement.FolderMember -> Mobility.FIXED to FixReason.FOLDER_MEMBER
        else -> Mobility.MOVABLE to null
    }
}

internal fun exportBand(rows: Int, cellY: Int): ExportRegionKind = when {
    cellY < rows / 3 -> ExportRegionKind.TOP
    cellY < (2 * rows) / 3 -> ExportRegionKind.MIDDLE
    else -> ExportRegionKind.BOTTOM
}

private fun CapturedItem.toExportItem(
    inputs: ExportInputs,
    snapshot: LayoutSnapshot,
    tier: PrivacyTier,
    folderSemantics: Map<String, String?>,
    pageOrdinal: Map<PageId, Int>,
    allocator: RandomIdAllocator,
    refsByItem: MutableMap<ItemId, String>,
): ExportItem {
    val ref = allocator.newId()
    if (ref in refsByItem.values) throw IllegalStateException("export ref collision")
    refsByItem[id] = ref
    val label = if (tier == PrivacyTier.EXTERNAL_REDACTED) {
        null
    } else {
        inputs.userLabels[id]?.let { ExportItemLabel(FreeTextClass.APP_LABEL, it) }
    }
    return toExportItemCore(
        ref = ref,
        snapshot = snapshot,
        resolvedCategories = inputs.resolvedCategories,
        folderSemantics = folderSemantics,
        pageOrdinal = pageOrdinal,
        label = label,
        usage = buildUsage(inputs, id),
    )
}

/**
 * Issue #205: the per-item field derivation shared by the export builder and
 * the validation-view reconstruction ([SessionExportReconstructor]) so both
 * derive role/mobility/affinities/category from one implementation (spec 205
 * reconstruction-parity contract).
 */
internal fun CapturedItem.toExportItemCore(
    ref: String,
    snapshot: LayoutSnapshot,
    resolvedCategories: Map<ItemId, String?>,
    folderSemantics: Map<String, String?>,
    pageOrdinal: Map<PageId, Int>,
    label: ExportItemLabel?,
    usage: UsageProjection?,
): ExportItem {
    val role = when (kind) {
        is ItemKind.FOLDER -> ExportItemRole.FOLDER
        is ItemKind.APPWIDGET, is ItemKind.CUSTOM_APPWIDGET -> ExportItemRole.WIDGET
        else -> ExportItemRole.APP_OR_SHORTCUT
    }
    val (mobility, fixReason) = projectMobility(this, snapshot)
    val pageAffinity = (placement as? CapturedPlacement.Workspace)
        ?.let { pageOrdinal[it.page.pageId] }?.let { ExportPageAffinity(it) }
    val regionAffinity = (placement as? CapturedPlacement.Workspace)
        ?.let { exportBand(snapshot.device.rows, it.cell.y) }
    val groupSemantic = (placement as? CapturedPlacement.FolderMember)
        ?.let { folderSemantics[it.folder.folderId.value] }
    return ExportItem(
        ref = ref,
        role = role,
        category = resolvedCategories[id],
        groupSemantic = groupSemantic,
        label = label,
        pageAffinity = pageAffinity,
        regionAffinity = regionAffinity,
        mobility = mobility,
        fixReason = fixReason,
        usage = usage,
    )
}

internal fun buildUsageSection(
    inputs: ExportInputs,
    refsByItem: Map<ItemId, String>,
): UsageSignalsSection? {
    if (inputs.signals == null) return null
    val entries = refsByItem.mapNotNull { (itemId, ref) ->
        val usage = buildUsage(inputs, itemId) ?: return@mapNotNull null
        UsageSignalEntry(ref = ref, usage = usage)
    }
    if (entries.isEmpty()) return null
    return UsageSignalsSection(entries)
}

/**
 * Per-item #203 bucket projection. The ItemId↔`PersonalizationEntryKey`
 * (profile, package) resolution is an internal builder input — package names
 * never reach the export document.
 */
internal fun buildUsage(inputs: ExportInputs, itemId: ItemId): UsageProjection? {
    val snapshot = inputs.signals ?: return null
    val key = inputs.usageKeysByItem[itemId] ?: return null
    val system = (snapshot.systemUsage as? SystemUsageSection.Available)?.entries?.get(key)
    val launcher = (snapshot.launcherOrigin as? LauncherOriginSection.Available)?.entries?.get(key)
    if (system == null && launcher == null) return null
    return UsageProjection(
        foreground30d = system?.foreground30dBucket.bucketOrdinal(),
        foreground7d = system?.foreground7dBucket.bucketOrdinal(),
        recency = system?.recencyBucket.bucketOrdinal(),
        activeDays = system?.activeDaysBucket.bucketOrdinal(),
        launcherCount = launcher?.countClass.bucketOrdinal(),
        launcherRecency = launcher?.recencyClass.bucketOrdinal(),
    )
}

private fun <T : PersonalizationValue> SignalField<T>?.bucketOrdinal(): Int? = when (this) {
    null -> null
    is SignalField.Value -> value.ordinal
    SignalField.Absent -> null
}
