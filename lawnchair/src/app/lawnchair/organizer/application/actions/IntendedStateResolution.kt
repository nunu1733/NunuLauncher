package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.canonical.CanonicalItemOrder
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.TargetKey

/**
 * Pure finalization of one materializer's planned intended state into the
 * persistent-identity write-set input. Issue #164: this seam exists so the
 * production adapter (`LauncherLayoutAdapter`) and the JVM tests exercise
 * identical resolution behavior; the write set's intended state must leave
 * this seam in the same canonical item order `RowManifestCodec.capture`
 * produces, or the A7 exact comparison diverges.
 */
internal object IntendedStateResolution {

    fun resolveAndFinalize(
        state: LayoutState,
        plannedIds: Map<ApplicationItemRef, Long>,
        plannedPages: Map<ApplicationPageRef.PlannedPage, Long>,
    ): LayoutState? = finalizeCanonicalOrder(resolve(state, plannedIds, plannedPages))

    /**
     * Substitutes planned references for their allocated persistent identities,
     * preserving item order. Order preservation at this stage is deliberate:
     * the planned list is not yet canonical and must never be reordered before
     * every reference is resolved (Issue #164 invariant).
     */
    fun resolve(
        state: LayoutState,
        plannedIds: Map<ApplicationItemRef, Long>,
        plannedPages: Map<ApplicationPageRef.PlannedPage, Long>,
    ): LayoutState {
        fun itemRef(ref: ApplicationItemRef): ApplicationItemRef = when (ref) {
            is ApplicationItemRef.PersistentItem -> ref
            else -> ApplicationItemRef.PersistentItem(ItemId(requireNotNull(plannedIds[ref]).toString()))
        }

        fun pageRef(ref: ApplicationPageRef): ApplicationPageRef = when (ref) {
            is ApplicationPageRef.PersistentPage -> ref

            is ApplicationPageRef.PlannedPage -> ApplicationPageRef.PersistentPage(
                PageId(requireNotNull(plannedPages[ref]).toString()),
            )
        }

        fun placement(placement: PlacementState): PlacementState = when (placement) {
            is PlacementState.Workspace -> placement.copy(page = pageRef(placement.page))
            is PlacementState.FolderChild -> placement.copy(parent = itemRef(placement.parent))
            is PlacementState.AppPairChild -> placement.copy(parent = itemRef(placement.parent))
            is PlacementState.Dock, is PlacementState.UnsupportedContainer -> placement
        }

        fun structure(structure: StructureState): StructureState = when (structure) {
            StructureState.Plain -> structure

            is StructureState.FolderMembers -> structure.copy(
                members = structure.members.map { RankedMember(itemRef(it.item), it.rank) },
            )

            is StructureState.AppPairMembers -> structure.copy(
                members = structure.members.map { it.copy(item = itemRef(it.item)) },
            )
        }

        fun targetKey(ref: ApplicationItemRef, target: TargetKey): TargetKey = when {
            ref is ApplicationItemRef.PlannedFolder && target is TargetKey.FolderKey ->
                TargetKey.FolderKey(FolderId(requireNotNull(plannedIds[ref]).toString()))

            else -> target
        }

        return state.copy(
            pages = state.pages.map { it.copy(ref = pageRef(it.ref)) },
            items = state.items.map { item ->
                val resolvedRef = itemRef(item.ref)
                item.copy(
                    ref = resolvedRef,
                    targetKey = targetKey(item.ref, item.targetKey),
                    placement = placement(item.placement),
                    structure = structure(item.structure),
                )
            },
        )
    }

    /**
     * Issue #164: presents the fully resolved items in canonical `ItemId`
     * UTF-8 byte order — the order `RowManifestCodec.capture` produces — or
     * fails closed when any reference is still unresolved. Ordering happens
     * only at this resolved boundary; the planned list is never reordered and
     * no fallback order is invented.
     */
    fun finalizeCanonicalOrder(state: LayoutState): LayoutState? {
        val items = CanonicalItemOrder.sortedResolved(state.items) ?: return null
        return state.copy(items = items)
    }
}
