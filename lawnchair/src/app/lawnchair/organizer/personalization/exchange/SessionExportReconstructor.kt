package app.lawnchair.organizer.personalization.exchange

import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ContextExportContract
import app.lawnchair.organizer.personalization.ExportCapabilities
import app.lawnchair.organizer.personalization.ExportGridContext
import app.lawnchair.organizer.personalization.ExportItem
import app.lawnchair.organizer.personalization.ExportItemRole
import app.lawnchair.organizer.personalization.ExportItemSubject
import app.lawnchair.organizer.personalization.ExportSession
import app.lawnchair.organizer.personalization.Mobility
import app.lawnchair.organizer.personalization.PersonalizationContextExportV1
import app.lawnchair.organizer.personalization.PreservedConstraints
import app.lawnchair.organizer.personalization.PreservedReason
import app.lawnchair.organizer.personalization.ReservedRegionProjection
import app.lawnchair.organizer.personalization.toExportItemCore
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.CategoryIdentity
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind

/**
 * Issue #205: rebuilds the validation-time export view from the durable
 * session plus the current canonical structural inputs (spec 205
 * "SessionExportReconstructor"). The #204 store persists only the ref map and
 * the structural digest — the export document itself is never persisted —
 * while `IntentValidator.validate` (and the planner adapter) need the export
 * view. Because the structural digest is compared before this view is used
 * (see [ExchangeImportPipeline]), the current state equals the export-time
 * state whenever reconstruction result is consumed, so the view is faithful.
 *
 * The ref universe is exactly the session's: items captured after the export
 * are not part of the view (the digest mismatch classifies them), and any
 * session ref that no longer resolves to a current addressable item is a
 * divergence ([ReconstructionResult.Diverged] → `CONTEXT_STALE`), preserving
 * the #204 failure classification.
 */
object SessionExportReconstructor {

    fun rebuild(
        session: ExportSession,
        current: CanonicalStructuralInputs,
    ): ReconstructionResult {
        val snapshot = current.snapshot
        val pageOrdinal = snapshot.pages.withIndex().associate { (index, page) -> page.id to index }
        val refByItem = session.itemRefs.entries.associate { (ref, id) -> id to ref }
        val candidateIds = session.scopeCandidates
            .map { CandidatePlanningIds.planningId(it) }
            .toSet()
        // Issue #336: folder semantics reconstruct from the resolved identity
        // and redact at the field site (toExportItemCore), so the validation
        // view never carries a raw user-defined ID or display name.
        val folderSemantics = LinkedHashMap<String, CategoryIdentity?>()
        for (item in snapshot.items) {
            if (item.kind is ItemKind.FOLDER) {
                folderSemantics[item.id.value] = current.resolvedIdentities[item.id]
            }
        }

        var appPairCount = 0
        var legacyShortcutCount = 0
        var unsupportedContainerCount = 0
        var unknownKindCount = 0
        val items = ArrayList<ExportItem>(session.itemRefs.size)
        // Issue #337 (spec 337 D-5): the advertised category refs of the
        // validation view are exactly the session's ref → identity mapping
        // intersected with the CURRENT composition's catalog. An identity the
        // catalog no longer contains (deleted between export and import) is not
        // advertised, so an intent referencing it fails closed with
        // `UNKNOWN_CATEGORY_REF` instead of resolving a stale category.
        val catalog = current.catalog
        val advertisedRefs = LinkedHashMap<CategoryIdentity, String>()
        for ((ref, identity) in session.categoryRefs) {
            if (catalog != null && identity in catalog) advertisedRefs[identity] = ref
        }
        val categoryRefsByIdentity = advertisedRefs.toSortedMap()

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
                // A session ref that now maps onto a non-addressable item no
                // longer reconstructs: the structural state diverged.
                if (item.id in refByItem) return ReconstructionResult.Diverged
                continue
            }
            val ref = refByItem[item.id] ?: continue // captured after the export
            items += item.toValidationItem(ref, snapshot, current, folderSemantics, categoryRefsByIdentity, pageOrdinal)
        }
        // Issue #331: candidate subjects reconstruct from the session scope
        // alone — they have no captured item and the validator needs only
        // ref/role/mobility/subject (category and label are non-authority).
        // Resolvability of the candidate identities is the scope binding
        // gate's check (spec 331 §3), not a reconstruction concern.
        val candidateRefByPlanningId = session.itemRefs.entries
            .filter { it.value in candidateIds }
            .associate { (ref, id) -> id to ref }
        for (candidateId in candidateIds.sortedBy { it.value }) {
            val ref = candidateRefByPlanningId[candidateId] ?: continue
            items += ExportItem(
                ref = ref,
                role = ExportItemRole.APP_OR_SHORTCUT,
                categoryRef = current.resolvedIdentities[candidateId]?.let { categoryRefsByIdentity[it] },
                folderCategoryRef = null,
                label = null,
                pageAffinity = null,
                regionAffinity = null,
                mobility = Mobility.CANDIDATE,
                fixReason = null,
                usage = null,
                subject = ExportItemSubject.CANDIDATE,
            )
        }
        if (items.map { it.ref }.toSet() != session.itemRefs.keys) {
            return ReconstructionResult.Diverged
        }

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
        return ReconstructionResult.Rebuilt(
            PersonalizationContextExportV1(
                exportId = session.exportId,
                tier = session.tier,
                grid = ExportGridContext(
                    columns = snapshot.device.columns,
                    rows = snapshot.device.rows,
                    pageCount = snapshot.pages.size,
                ),
                items = items,
                categories = categoryEntriesOf(categoryRefsByIdentity, catalog, session.tier),
                preservedConstraints = preserved,
                capabilities = ExportCapabilities(
                    intentSchemaVersion = ContextExportContract.INTENT_SCHEMA_VERSION,
                    functions = ContextExportContract.FIXED_CAPABILITIES,
                ),
                // Labels and usage live only in the original export document;
                // they are not authority fields for validation or planning, so
                // the reconstructed view omits them.
                usageSignals = null,
            ),
        )
    }

    private fun CapturedItem.toValidationItem(
        ref: String,
        snapshot: app.lawnchair.organizer.planning.LayoutSnapshot,
        current: CanonicalStructuralInputs,
        folderSemantics: Map<String, CategoryIdentity?>,
        categoryRefsByIdentity: Map<CategoryIdentity, String>,
        pageOrdinal: Map<app.lawnchair.organizer.planning.PageId, Int>,
    ): ExportItem = toExportItemCore(
        ref = ref,
        snapshot = snapshot,
        resolvedIdentities = current.resolvedIdentities,
        categoryRefsByIdentity = categoryRefsByIdentity,
        folderSemantics = folderSemantics,
        pageOrdinal = pageOrdinal,
        label = null,
        usage = null,
    )
}

/**
 * Issue #337 (spec 337 D-3): the validation view's category entries. Names are
 * presentation, not authority — a renamed category reconstructs with its
 * current display name and validation is unaffected; the entry set is the
 * advertised ref set the validator resolves against.
 */
private fun categoryEntriesOf(
    categoryRefsByIdentity: Map<CategoryIdentity, String>,
    catalog: app.lawnchair.organizer.planning.ActiveCategoryCatalog?,
    tier: app.lawnchair.organizer.personalization.PrivacyTier,
): List<app.lawnchair.organizer.personalization.ExportCategory> = categoryRefsByIdentity.map { (identity, ref) ->
    when (identity) {
        is CategoryIdentity.BuiltIn -> app.lawnchair.organizer.personalization.ExportCategory(
            ref = ref,
            kind = app.lawnchair.organizer.personalization.CategoryRefKind.BUILT_IN,
            taxonomyId = identity.id.value,
        )

        is CategoryIdentity.UserDefined -> app.lawnchair.organizer.personalization.ExportCategory(
            ref = ref,
            kind = app.lawnchair.organizer.personalization.CategoryRefKind.USER_DEFINED,
            displayName = if (tier == app.lawnchair.organizer.personalization.PrivacyTier.EXTERNAL_REDACTED) {
                null
            } else {
                catalog?.displayNameOf(identity.id)
            },
        )
    }
}

sealed interface ReconstructionResult {
    data class Rebuilt(val export: PersonalizationContextExportV1) : ReconstructionResult

    /** A session ref no longer resolves; the structural state diverged. */
    data object Diverged : ReconstructionResult
}
