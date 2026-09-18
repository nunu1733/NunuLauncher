package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.planning.ActiveCategoryCatalog
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.CategoryIdentity
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
 * - Issue #337 (v4) category projection: the envelope advertises the active
 *   category catalog as export-scoped refs ([ExportCategory]) and every
 *   item-level category exposure is one of those refs — built-in and
 *   user-defined alike (`categoryRef` / `folderCategoryRef`). No raw built-in
 *   value, no `UserCategoryId`, and (below the label-inclusive tiers) no
 *   display name appears at item level; the ref → `CategoryIdentity` mapping
 *   lives in the session, while the session-local freshness digests consume
 *   the resolved `CategoryIdentity` itself, so a reassignment or
 *   assigned-category deletion stays detectable.
 */
object ContextExportBuilder {

    fun build(inputs: ExportInputs, tier: PrivacyTier, allocator: RandomIdAllocator): BuiltExport {
        val snapshot = inputs.snapshot
        val pageOrdinal = snapshot.pages.withIndex().associate { (index, page) -> page.id to index }
        val refsByItem = LinkedHashMap<ItemId, String>()
        val folderSemantics = LinkedHashMap<String, CategoryIdentity?>()

        for (item in snapshot.items) {
            if (item.kind is ItemKind.FOLDER) {
                folderSemantics[item.id.value] = inputs.resolvedIdentities[item.id]
            }
        }

        var appPairCount = 0
        var legacyShortcutCount = 0
        var unsupportedContainerCount = 0
        var unknownKindCount = 0
        val items = ArrayList<ExportItem>(snapshot.items.size)
        val categoryRefsByIdentity = inputs.catalog?.let { projectCategoryRefs(it, allocator) } ?: emptyMap()

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
            items += item.toExportItem(
                inputs,
                snapshot,
                tier,
                folderSemantics,
                pageOrdinal,
                allocator,
                refsByItem,
                categoryRefsByIdentity,
            )
        }
        // Issue #331: selected missing-app candidates join the export scope as
        // candidate subjects. The canonical scope is ONE composition output:
        // `targets.additions` (already composed by the same
        // ProductionOrganizationInputComposer seam the planner consumes).
        val candidateRefsByItem = LinkedHashMap<ItemId, String>()
        val candidateTargets = inputs.targets.additions
            .map { addition ->
                val target = addition.target as? CandidateTarget.AppKey
                    ?: error("non-AppKey candidate in export scope: ${addition.id.value}")
                target
            }
            .sortedWith(compareBy({ it.component.value }, { it.profile.value }))
        for (target in candidateTargets) {
            val candidateId = CandidatePlanningIds.planningId(target)
            val ref = allocator.newId()
            if (ref in refsByItem.values || ref in candidateRefsByItem.values || ref in categoryRefsByIdentity.values) {
                throw IllegalStateException("export ref collision")
            }
            candidateRefsByItem[candidateId] = ref
            val label = if (tier == PrivacyTier.EXTERNAL_REDACTED) {
                null
            } else {
                inputs.userLabels[candidateId]?.let { ExportItemLabel(FreeTextClass.APP_LABEL, it) }
            }
            items += ExportItem(
                ref = ref,
                role = ExportItemRole.APP_OR_SHORTCUT,
                // Issue #337: the candidate's resolved category is an
                // advertised ref like any placed item's.
                categoryRef = categoryRefsByIdentity[inputs.resolvedIdentities[candidateId]],
                folderCategoryRef = null,
                label = label,
                pageAffinity = null,
                regionAffinity = null,
                mobility = Mobility.CANDIDATE,
                fixReason = null,
                usage = buildUsage(inputs, candidateId),
                subject = ExportItemSubject.CANDIDATE,
            )
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

        // Allocation order note: the item/candidate refs keep their pre-v4
        // allocator sequence, the export id follows them (also unchanged), and
        // the category refs were drawn before the items (they are resolved
        // while projecting items).
        val exportId = allocator.newId()
        val categories = categoriesOf(inputs.catalog, categoryRefsByIdentity, tier)
        val export = PersonalizationContextExportV1(
            exportId = exportId,
            tier = tier,
            grid = ExportGridContext(
                columns = snapshot.device.columns,
                rows = snapshot.device.rows,
                pageCount = snapshot.pages.size,
            ),
            items = items,
            categories = categories,
            preservedConstraints = preserved,
            capabilities = ExportCapabilities(
                intentSchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
                functions = ContextExportContract.FIXED_CAPABILITIES,
            ),
            usageSignals = buildUsageSection(inputs, refsByItem + candidateRefsByItem),
        )
        val session = ExportSession(
            exportId = export.exportId,
            itemRefs = (refsByItem + candidateRefsByItem).entries.associate { (id, ref) -> ref to id },
            tier = tier,
            // Issue #336: the freshness digest consumes the resolved
            // identities themselves (kind + stable ID), not the redacted
            // export fields.
            sourceContextDigest = SourceContextIdentity.digest(
                CanonicalStructuralInputs(snapshot, inputs.targets, inputs.resolvedIdentities),
            ),
            signalProvenance = inputs.signals?.let {
                SignalProvenance(schemaVersion = it.schemaVersion, contentDigest = it.contentDigest)
            },
            createdAtEpochMs = inputs.nowEpochMs,
            expiresAtEpochMs = inputs.nowEpochMs + ContextExportContract.SESSION_TTL_MS,
            scopeCandidates = candidateTargets,
            scopeCandidateDigest = CandidateScopeIdentity.digest(
                candidateTargets.map { target ->
                    CandidateScopeProjection(
                        target = target,
                        // Composed additions are AVAILABLE by contract (spec
                        // 228 §6 fail-closed verification happens at apply).
                        availability = Availability.AVAILABLE,
                        category = inputs.resolvedIdentities[CandidatePlanningIds.planningId(target)],
                    )
                },
            ),
            // Issue #337: the only ref → identity resolution surface.
            categoryRefs = categoryRefsByIdentity.entries.associate { (identity, ref) -> ref to identity },
        )
        return BuiltExport(export, session)
    }
}

/**
 * Issue #337 (spec 337 D-1): the advertised category catalog projection in
 * canonical identity order (built-in in `CategoryId` byte order first, then
 * user-defined in stable-ID byte order) — the same order the planner's
 * canonical category ordering uses, so the projection is rename-invariant.
 *
 * An identity missing from the projection (no catalog, or an identity the
 * composition does not advertise) projects the absent category at item level;
 * the freshness digest still carries the identity, so the difference stays
 * detectable.
 */
private fun projectCategoryRefs(
    catalog: ActiveCategoryCatalog,
    allocator: RandomIdAllocator,
): Map<CategoryIdentity, String> {
    // Stable identity order over the whole union: a user-defined ID can never
    // compare equal to a built-in id (disjoint namespaces by construction).
    val ordered = (
        catalog.builtIn.allowedCategories.map { CategoryIdentity.BuiltIn(it) } +
            catalog.userDefined.map { CategoryIdentity.UserDefined(it.id) }
        ).sorted()
    val refs = LinkedHashMap<CategoryIdentity, String>(ordered.size)
    for (identity in ordered) {
        val ref = allocator.newId()
        if (ref in refs.values) throw IllegalStateException("export category ref collision")
        refs[identity] = ref
    }
    return refs
}

/**
 * Issue #337 (spec 337 D-2): the `categories` entries of one projection. Only
 * `displayName` belongs to a free-text class; built-in `taxonomyId` is a
 * taxonomy enum spelling and every tier advertises it, an unknown user-defined
 * name (the entry vanished between composition and this read) is omitted, and
 * `EXTERNAL_REDACTED` never carries the class.
 */
private fun categoriesOf(
    catalog: ActiveCategoryCatalog?,
    refsByIdentity: Map<CategoryIdentity, String>,
    tier: PrivacyTier,
): List<ExportCategory> = refsByIdentity.entries
    .sortedBy { it.key }
    .map { (identity, ref) ->
        when (identity) {
            is CategoryIdentity.BuiltIn -> ExportCategory(
                ref = ref,
                kind = CategoryRefKind.BUILT_IN,
                taxonomyId = identity.id.value,
            )

            is CategoryIdentity.UserDefined -> ExportCategory(
                ref = ref,
                kind = CategoryRefKind.USER_DEFINED,
                displayName = if (tier == PrivacyTier.EXTERNAL_REDACTED) {
                    null
                } else {
                    catalog?.displayNameOf(identity.id)?.let { ExportCategoryName(FreeTextClass.USER_CATEGORY_NAME, it) }
                },
            )
        }
    }

/** Inputs for one export attempt. */
data class ExportInputs(
    val snapshot: LayoutSnapshot,
    val targets: TargetSet,
    /**
     * Resolved classification (override included) keyed by internal `ItemId`,
     * as the closed planning identity. This is the single source for BOTH
     * #336 layers: the export presentation fields project the identity as an
     * advertised category ref (spec 337), while the session freshness digests
     * consume the identity itself.
     */
    val resolvedIdentities: Map<ItemId, CategoryIdentity?> = emptyMap(),
    /**
     * Issue #337: the active category catalog of the same composition cut,
     * projected into the envelope `categories` (spec 337 D-1). Null means the
     * caller has no catalog projection (test/harness composition): the export
     * then advertises no category and item category refs are absent.
     */
    val catalog: ActiveCategoryCatalog? = null,
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
    folderSemantics: Map<String, CategoryIdentity?>,
    pageOrdinal: Map<PageId, Int>,
    allocator: RandomIdAllocator,
    refsByItem: MutableMap<ItemId, String>,
    categoryRefsByIdentity: Map<CategoryIdentity, String>,
): ExportItem {
    val ref = allocator.newId()
    if (ref in refsByItem.values || ref in categoryRefsByIdentity.values) throw IllegalStateException("export ref collision")
    refsByItem[id] = ref
    val label = if (tier == PrivacyTier.EXTERNAL_REDACTED) {
        null
    } else {
        inputs.userLabels[id]?.let { ExportItemLabel(FreeTextClass.APP_LABEL, it) }
    }
    return toExportItemCore(
        ref = ref,
        snapshot = snapshot,
        resolvedIdentities = inputs.resolvedIdentities,
        categoryRefsByIdentity = categoryRefsByIdentity,
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
    resolvedIdentities: Map<ItemId, CategoryIdentity?>,
    categoryRefsByIdentity: Map<CategoryIdentity, String>,
    folderSemantics: Map<String, CategoryIdentity?>,
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
    val folderCategoryRef = (placement as? CapturedPlacement.FolderMember)
        ?.let { folderSemantics[it.folder.folderId.value] }
        ?.let { categoryRefsByIdentity[it] }
    return ExportItem(
        ref = ref,
        role = role,
        // Issue #337 export presentation: the resolved identity projects as an
        // advertised category ref — never a raw built-in value, an ID, or a
        // name. The identity itself flows only into the session digest inputs.
        categoryRef = resolvedIdentities[id]?.let { categoryRefsByIdentity[it] },
        folderCategoryRef = folderCategoryRef,
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
