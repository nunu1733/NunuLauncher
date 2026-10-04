package app.lawnchair.organizer.planning

internal enum class AllocationFault {
    NONE,
    FAIL_ALLOCATION,
}

/**
 * Deterministic cell traversal orders for the shared allocator (spec 182
 * internal seam). `TOP_LEFT_ROW_MAJOR` is the pre-182 canonical behavior;
 * `BOTTOM_UP_ROW_MAJOR` mirrors it from the bottom edge and is exercised by
 * `BOTTOM_FIRST_V1` (child 5) through the public seam.
 */
internal enum class CellTraversal { TOP_LEFT_ROW_MAJOR, BOTTOM_UP_ROW_MAJOR }

/**
 * Deterministic page-scope policies for the shared allocator (spec 182
 * internal seam). `PREFERRED_THEN_NEW` is the pre-182 canonical behavior;
 * `CAPTURED_THEN_NEW` reuses the proven incremental scan and is exercised by
 * `GLOBAL_COMPACT_V1` (child 6) through the public seam.
 */
internal enum class PageScope { PREFERRED_THEN_NEW, CAPTURED_THEN_NEW }

/**
 * The single shared occupancy/bounds allocator (spec 182: never a second
 * implementation). Callers choose the deterministic page scope and cell
 * traversal; everything else — occupancy tracking, bounds, overflow onto new
 * pages — is invariant across strategies.
 */
internal class Allocator(
    val device: DeviceCapabilities,
    val capturedPages: List<Page>,
    val maxCapturedOrder: PageOrder?,
    private val allocationFault: AllocationFault,
    private val cellTraversal: CellTraversal,
) {
    private val occupancy = mutableMapOf<PageTargetRef, MutableList<Rect>>()
    private val newPages = mutableListOf<NewPage>()
    private var nextNewPageOrder: PageOrder = if (maxCapturedOrder != null) maxCapturedOrder + 1 else PageOrder(0)

    fun markOccupied(page: PageTargetRef, cell: GridCell, span: GridSpan) {
        occupancy.getOrPut(page) { mutableListOf() }
            .add(Rect(cell.x.toLong(), cell.y.toLong(), span.width.toLong(), span.height.toLong()))
    }

    fun allocatePreferred(span: GridSpan, preferredPage: PageRef): Pair<PageTargetRef, GridCell>? {
        if (allocationFault == AllocationFault.FAIL_ALLOCATION) return null

        val occupied = occupancy[preferredPage] ?: emptyList()
        val cell = findRowMajorFirstFit(occupied, device.columns, device.rows, span, cellTraversal)
        if (cell != null) return preferredPage to cell
        return allocateOnNewPages(span)
    }

    fun allocateCapturedThenNew(span: GridSpan): Pair<PageTargetRef, GridCell>? {
        if (allocationFault == AllocationFault.FAIL_ALLOCATION) return null

        for (page in capturedPages) {
            val ref: PageTargetRef = PageRef(page.id)
            val occupied = occupancy[ref] ?: emptyList()
            val cell = findRowMajorFirstFit(occupied, device.columns, device.rows, span, cellTraversal)
            if (cell != null) return ref to cell
        }
        return allocateOnNewPages(span)
    }

    private fun allocateOnNewPages(span: GridSpan): Pair<PageTargetRef, GridCell> {
        for (np in newPages) {
            val ref: PageTargetRef = NewPageRef(np.ordinal)
            val occupied = occupancy[ref] ?: emptyList()
            val cell = findRowMajorFirstFit(occupied, device.columns, device.rows, span, cellTraversal)
            if (cell != null) return ref to cell
        }
        val ordinal = NewPageOrdinal(newPages.size)
        val order = nextNewPageOrder
        val cell = findRowMajorFirstFit(emptyList(), device.columns, device.rows, span, cellTraversal)
            ?: error(
                "Validated placement span ${span.width}x${span.height} does not fit " +
                    "an empty ${device.columns}x${device.rows} page",
            )
        newPages += NewPage(ordinal, order)
        nextNewPageOrder = nextNewPageOrder + 1
        val ref: PageTargetRef = NewPageRef(ordinal)
        return ref to cell
    }

    fun buildNewPages(): List<NewPage> = newPages.toList()
}

internal fun findRowMajorFirstFit(
    occupied: List<Rect>,
    columns: Int,
    rows: Int,
    span: GridSpan,
    traversal: CellTraversal,
): GridCell? {
    val w = span.width.toLong()
    val h = span.height.toLong()
    val cols = columns.toLong()
    val rws = rows.toLong()

    if (w > cols || h > rws) return null

    val candidateYs = when (traversal) {
        // A fit can start at the top edge or directly below any occupied
        // rectangle's bottom; scan rows top-down.
        CellTraversal.TOP_LEFT_ROW_MAJOR -> (listOf(0L) + occupied.map { it.bottom }).distinct().sorted()

        // Mirror image: a fit can start at the bottom edge or directly above
        // any occupied rectangle's top; scan rows bottom-up.
        CellTraversal.BOTTOM_UP_ROW_MAJOR ->
            (listOf(rws - h) + occupied.map { it.y - h })
                .filter { it >= 0 }
                .distinct()
                .sortedDescending()
    }

    for (y in candidateYs) {
        if (y + h > rws) break

        val blockingIntervals = occupied
            .filter { it.y < y + h && y < it.bottom }
            .map { it.x to it.right }
            .sortedBy { it.first }

        var cursor = 0L
        for ((start, end) in blockingIntervals) {
            if (start - cursor >= w) {
                return GridCell(cursor.toInt(), y.toInt())
            }
            cursor = maxOf(cursor, end)
        }
        if (cursor + w <= cols) {
            return GridCell(cursor.toInt(), y.toInt())
        }
    }
    return null
}

internal data class Rect(val x: Long, val y: Long, val width: Long, val height: Long) {
    val right: Long get() = x + width
    val bottom: Long get() = y + height
}
