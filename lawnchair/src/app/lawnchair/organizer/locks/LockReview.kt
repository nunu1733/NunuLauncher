package app.lawnchair.organizer.locks

import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.locks.LockReviewEntrySortKey.keyOf
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId

/**
 * One `UNKNOWN` row awaiting review, with everything the review UI renders:
 * identity, kind, placement, and profile context.
 */
data class LockReviewEntry(
    val item: ItemId,
    val title: OptionalText,
    val kind: CanonicalItemKind,
    val placement: LockPlacementSummary,
    val profile: ProfileId,
) {
    init {
        require(kind !is CanonicalItemKind.Unknown) { "Non-actionable rows are never reviewable" }
    }
}

/**
 * Deterministic review listing: every `UNKNOWN` row of the capture, ordered
 * by profile, then placement (page order, cell, rank), then item id, so the
 * same capture always renders the same list.
 */
data class LockReviewListing(
    val entries: List<LockReviewEntry>,
) {
    init {
        require(
            entries.zipWithNext().all { (a, b) -> keyOf(a) <= keyOf(b) },
        ) { "LockReviewListing must be deterministically ordered" }
    }

    companion object {
        fun of(state: LayoutState): LockReviewListing = LockReviewListing(
            state.items
                .filter { it.lockState == OrganizerLockState.UNKNOWN && it.kind !is CanonicalItemKind.Unknown }
                .map { item ->
                    LockReviewEntry(
                        item = itemIdOf(item),
                        title = item.title,
                        kind = item.kind,
                        placement = placementSummaryOf(state, item),
                        profile = item.profile,
                    )
                }
                .sortedWith(compareBy(::keyOf)),
        )
    }
}

/** Sort key extracted from the placement summary; deterministic tie-break by item id. */
internal object LockReviewEntrySortKey {
    fun keyOf(entry: LockReviewEntry): String {
        val profile = entry.profile.value
        val placement = when (val p = entry.placement) {
            is LockPlacementSummary.Desktop ->
                "d:${(p.pageOrder ?: Int.MAX_VALUE).toString().padStart(6, '0')}:" +
                    "${p.cell.y.toString().padStart(4, '0')}:${p.cell.x.toString().padStart(4, '0')}"

            is LockPlacementSummary.DockSlot ->
                "k:${p.rank.toString().padStart(4, '0')}"

            is LockPlacementSummary.InFolder ->
                "f:${p.parent.value}:${p.rank.toString().padStart(4, '0')}"

            is LockPlacementSummary.InAppPair -> "p:${p.parent.value}"

            is LockPlacementSummary.Unsupported -> "u:${p.code.value}"
        }
        return "$profile:$placement:${entry.item.value}"
    }
}

/** Lock state display model for one captured row, used by management UI. */
data class LockStateEntry(
    val item: ItemId,
    val title: OptionalText,
    val kind: CanonicalItemKind,
    val stored: OrganizerLockState,
    val effectivelyProtected: Boolean,
    val scope: LockProtectionScope,
    val placement: LockPlacementSummary,
    val profile: ProfileId,
    val profileAvailable: Boolean,
)

/** Every captured row with its lock view, deterministically ordered like [LockReviewListing]. */
fun lockStateListing(state: LayoutState): List<LockStateEntry> = state.items
    .filter { it.kind !is CanonicalItemKind.Unknown }
    .map { item ->
        val view = effectiveLockViewOf(state, item)
        LockStateEntry(
            item = itemIdOf(item),
            title = item.title,
            kind = item.kind,
            stored = item.lockState,
            effectivelyProtected = view.effectivelyProtected,
            scope = view.scope,
            placement = placementSummaryOf(state, item),
            profile = item.profile,
            profileAvailable = state.profiles.firstOrNull { it.id == item.profile }
                ?.availability == app.lawnchair.organizer.application.public.ProfileAvailability.AVAILABLE,
        )
    }
    .sortedWith(
        compareBy(
            { it.profile.value },
            { keyOf(LockReviewEntry(it.item, it.title, it.kind, it.placement, it.profile)) },
        ),
    )

/** All captured rows regardless of kind, for previews by identity. */
fun LayoutState.lockEntryOf(item: ItemId): CanonicalItemState? = items.firstOrNull { itemIdOf(it) == item }
