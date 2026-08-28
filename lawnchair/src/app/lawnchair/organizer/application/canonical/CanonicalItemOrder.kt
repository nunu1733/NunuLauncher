package app.lawnchair.organizer.application.canonical

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.StructureState

/**
 * Issue #164: the single authority for canonical `LayoutState` item order.
 * The DB capture (`RowManifestCodec`) and the write-set finalization
 * (`IntendedStateResolution`) must present the same item order or the A7
 * exact comparison diverges; both order through this authority instead of
 * private sort sites.
 */
internal object CanonicalItemOrder {

    /**
     * Items in canonical `ItemId` UTF-8 byte order, or null when any item
     * reference is not yet persistent — including references nested in
     * placements (folder/app-pair parents) and structures (members), not only
     * the top-level item identity. Callers fail closed on null instead of
     * inventing a fallback order.
     */
    fun sortedResolved(items: List<CanonicalItemState>): List<CanonicalItemState>? {
        if (items.any { !hasResolvedReferences(it) }) return null
        return items.sortedBy { (it.ref as ApplicationItemRef.PersistentItem).itemId }
    }

    private fun hasResolvedReferences(item: CanonicalItemState): Boolean {
        if (item.ref !is ApplicationItemRef.PersistentItem) return false
        when (val placement = item.placement) {
            is PlacementState.FolderChild -> if (placement.parent !is ApplicationItemRef.PersistentItem) return false
            is PlacementState.AppPairChild -> if (placement.parent !is ApplicationItemRef.PersistentItem) return false
            else -> Unit
        }
        return when (val structure = item.structure) {
            is StructureState.FolderMembers -> structure.members.all { it.item is ApplicationItemRef.PersistentItem }
            is StructureState.AppPairMembers -> structure.members.all { it.item is ApplicationItemRef.PersistentItem }
            else -> true
        }
    }
}
