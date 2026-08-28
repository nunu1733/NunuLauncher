package app.lawnchair.organizer.application.canonical

import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemState

/**
 * Issue #164: the single authority for canonical `LayoutState` item order.
 * The DB capture (`RowManifestCodec`) and the write-set finalization
 * (`IntendedStateResolution`) must present the same item order or the A7
 * exact comparison diverges; both order through this authority instead of
 * private sort sites.
 */
internal object CanonicalItemOrder {

    /**
     * Items in canonical `ItemId` UTF-8 byte order, or null when any item's
     * reference is not yet persistent. Callers fail closed on null instead of
     * inventing a fallback order.
     */
    fun sortedResolved(items: List<CanonicalItemState>): List<CanonicalItemState>? {
        if (items.any { it.ref !is ApplicationItemRef.PersistentItem }) return null
        return items.sortedBy { (it.ref as ApplicationItemRef.PersistentItem).itemId }
    }
}
