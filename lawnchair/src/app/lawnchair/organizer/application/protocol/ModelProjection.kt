package app.lawnchair.organizer.application.protocol

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetRestoreState
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey

/**
 * Issue #152: the model-verifiable projection of a layout state — exactly the
 * fields the in-memory Launcher model faithfully represents, pinned by the
 * accepted spec: item identity, container, placement, item type, folder
 * membership, widget identity and bind state, profile identity, and the
 * per-kind semantic launch identity (including the canonical launch identity
 * of legacy shortcut kinds). Fields the model does not represent (raw icon
 * bytes, persisted `modified`, device capabilities, profile inventory,
 * reserved regions, organizer lock state, raw folder ranks) are verified
 * solely by the unchanged DB leg.
 *
 * Both verification legs project onto this shape: the DB recapture through
 * [LayoutState.projectedToModelVerifiable], the model snapshot through the
 * capture-side codec, so equality compares like with like.
 */
data class ModelSnapshot(
    val items: List<ModelItemProjection>,
    /** Loader generation observed at capture; diagnostic only, never compared. */
    val diagnosticGenerationId: Long,
)

data class ModelItemProjection(
    val ref: ApplicationItemRef,
    val kind: CanonicalItemKind,
    val targetKey: TargetKey,
    val profile: ProfileId,
    val placement: ModelPlacement,
    val structure: ModelStructure,
    val widget: ModelWidgetIdentity?,
    /**
     * Canonical launch identity of legacy shortcut kinds (`ShortcutLegacy`,
     * `Unknown`), derived from the persisted DB intent and the in-memory
     * `WorkspaceItemIntent` by the same canonical serialization. Null for
     * other kinds and for rows with no comparable identity.
     */
    val legacyLaunchIdentity: String?,
)

sealed interface ModelPlacement {
    data class Workspace(val page: PageId, val cell: GridCell, val span: GridSpan) : ModelPlacement
    data class Dock(val slot: Int) : ModelPlacement

    /** Folder membership carries the parent only; the platform normalizes child ranks on load. */
    data class FolderChild(val parent: ItemId) : ModelPlacement
    data class AppPairChild(val parent: ItemId, val stage: SplitStage) : ModelPlacement

    /**
     * A container the organizer does not own. Represented explicitly (not
     * dropped) so a row the model loses cannot compare equal by symmetric
     * omission; the raw container code is model-observable on the platform
     * `ItemInfo`.
     */
    data class UnsupportedContainer(val container: ContainerCode) : ModelPlacement
}

sealed interface ModelStructure {
    data object Plain : ModelStructure

    /** Members in persisted rank order; the platform normalizes folder ranks on load. */
    data class FolderMembers(val members: List<ItemId>) : ModelStructure
    data class AppPairMembers(val members: List<ModelAppPairMember>) : ModelStructure
}

data class ModelAppPairMember(val item: ItemId, val stage: SplitStage)

data class ModelWidgetIdentity(
    val provider: ComponentKey,
    val appWidgetId: AppWidgetId,
    val restoreState: WidgetRestoreState,
)

/**
 * Project the DB-derived canonical state onto the model-verifiable subset.
 * [legacyLaunchIdentityOf] derives the canonical launch identity of legacy
 * shortcut kinds from the persisted intent; production passes the writer
 * port's implementation so both legs use the same canonical serialization.
 * Items whose identity the model cannot represent (planned references) are
 * excluded — they stay covered by the exact DB comparison. Items are ordered
 * by canonical [ItemId] so projection equality never depends on enumeration
 * order; the canonical order itself is verified by the exact DB leg.
 */
fun LayoutState.projectedToModelVerifiable(
    legacyLaunchIdentityOf: (CanonicalItemState) -> String? = { null },
): ModelSnapshot = ModelSnapshot(
    items = items.mapNotNull { it.toModelProjection(legacyLaunchIdentityOf) }.sortedBy { it.itemId },
    diagnosticGenerationId = 0L,
)

private fun CanonicalItemState.toModelProjection(
    legacyLaunchIdentityOf: (CanonicalItemState) -> String?,
): ModelItemProjection? {
    val persistentRef = ref as? ApplicationItemRef.PersistentItem ?: return null
    val modelPlacement = when (val p = placement) {
        is PlacementState.Workspace -> ModelPlacement.Workspace(
            (p.page as? ApplicationPageRef.PersistentPage)?.pageId ?: return null,
            p.cell,
            p.span,
        )

        is PlacementState.Dock -> ModelPlacement.Dock(p.rank)

        is PlacementState.FolderChild -> ModelPlacement.FolderChild(
            (p.parent as? ApplicationItemRef.PersistentItem)?.itemId ?: return null,
        )

        is PlacementState.AppPairChild -> ModelPlacement.AppPairChild(
            (p.parent as? ApplicationItemRef.PersistentItem)?.itemId ?: return null,
            p.stage,
        )

        is PlacementState.UnsupportedContainer -> ModelPlacement.UnsupportedContainer(p.code)
    }
    val modelStructure = when (val s = structure) {
        is StructureState.FolderMembers -> ModelStructure.FolderMembers(
            s.members.map { m -> (m.item as? ApplicationItemRef.PersistentItem)?.itemId ?: return null },
        )

        is StructureState.AppPairMembers -> ModelStructure.AppPairMembers(
            s.members.map { m ->
                ModelAppPairMember(
                    (m.item as? ApplicationItemRef.PersistentItem)?.itemId ?: return null,
                    m.stage,
                )
            },
        )

        StructureState.Plain -> ModelStructure.Plain
    }
    val widgetIdentity = when (val w = widget) {
        is WidgetState.Widget -> ModelWidgetIdentity(w.provider, w.appWidgetId, w.restored)
        WidgetState.NoWidget -> null
    }
    val legacyIdentity = if (kind is CanonicalItemKind.ShortcutLegacy || kind is CanonicalItemKind.Unknown) {
        legacyLaunchIdentityOf(this)
    } else {
        null
    }
    return ModelItemProjection(
        persistentRef,
        kind,
        targetKey,
        profile,
        modelPlacement,
        modelStructure,
        widgetIdentity,
        legacyIdentity,
    )
}

/** Comparison key for the canonical projection order. */
private val ModelItemProjection.itemId: ItemId
    get() = (ref as ApplicationItemRef.PersistentItem).itemId
