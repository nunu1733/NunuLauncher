package app.lawnchair.organizer.planning

/**
 * Issue #185 / ADR-0010: the single acceptance predicate for desktop items
 * whose captured placement overlaps an authoritative workspace reservation.
 *
 * The platform model loader (`LoaderCursor.checkItemPlacement`) marks the QSB
 * row as occupied and keeps an overlapping item only when the platform overlap
 * policy allows it. The organizer must never treat such an item as movable and
 * must never write an intended state the current policy would not accept, so
 * the composer gate, the A5 precondition, and the recovery re-evaluation all
 * call this one predicate instead of re-deriving the rule.
 *
 * ADR-0010 records why `LoaderCursor` does not reference this predicate
 * directly (loader hot path + compile configuration risk) and instead pins the
 * equivalence with a mandatory contract test.
 */
object ReservationOverlapAcceptance {

    /** True when the placement's cells intersect the reservation rectangle on the same page. */
    fun overlaps(
        page: PageId,
        cell: GridCell,
        span: GridSpan,
        reservations: List<ReservedWorkspaceRegion>,
    ): Boolean = reservations.any { reservation ->
        reservation.page.pageId == page && rectanglesOverlap(reservation.cell, reservation.span, cell, span)
    }

    fun rectanglesOverlap(
        aCell: GridCell,
        aSpan: GridSpan,
        bCell: GridCell,
        bSpan: GridSpan,
    ): Boolean = aCell.x.toLong() < bCell.x.toLong() + bSpan.width.toLong() &&
        bCell.x.toLong() < aCell.x.toLong() + aSpan.width.toLong() &&
        aCell.y.toLong() < bCell.y.toLong() + bSpan.height.toLong() &&
        bCell.y.toLong() < aCell.y.toLong() + aSpan.height.toLong()
}

/**
 * The "current overlap policy truth" the acceptance predicate is evaluated
 * against: true when the platform model loader keeps an item that overlaps an
 * authoritative reservation (`LoaderCursor` → allowWidgetOverlap). Production
 * reads the preference at evaluation time; the value is never persisted into
 * revisions, digests, or recovery records.
 */
fun interface WorkspaceOverlapToleranceSource {
    fun isOverlapTolerated(): Boolean
}
