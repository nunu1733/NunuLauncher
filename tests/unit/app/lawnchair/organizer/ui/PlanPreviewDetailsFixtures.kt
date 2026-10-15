package app.lawnchair.organizer.ui

import app.lawnchair.organizer.application.public.PlanPreviewDetails
import app.lawnchair.organizer.application.public.PlanPreviewDiagrams
import app.lawnchair.organizer.application.public.PreviewChange
import app.lawnchair.organizer.application.public.PreviewCounts
import app.lawnchair.organizer.application.public.PreviewDiagram
import app.lawnchair.organizer.application.public.PreviewDiagramPage
import app.lawnchair.organizer.application.public.PreviewDiagramPageRef
import app.lawnchair.organizer.application.public.PreviewExcludableItem
import app.lawnchair.organizer.planning.PageId

/**
 * Issue #508 test fixture: `PlanPreviewDetails` requires the before/after
 * diagrams (a details value is only ever built complete, production and test
 * alike). These helpers build a minimal empty diagram pair for the row-builder
 * and coordinator tests that do not exercise the diagram projection itself;
 * the diagram projection has its own dedicated tests.
 */
internal fun testDiagrams(columns: Int = 4, rows: Int = 5): PlanPreviewDiagrams {
    fun empty(): PreviewDiagram = PreviewDiagram(
        columns = columns,
        rows = rows,
        pages = listOf(PreviewDiagramPage(PreviewDiagramPageRef.Persistent(PageId("1")), emptyList())),
        dockItems = emptyList(),
        reservedRegions = emptyList(),
    )
    return PlanPreviewDiagrams(before = empty(), after = empty())
}

internal fun planPreviewDetails(
    changes: List<PreviewChange>,
    counts: PreviewCounts,
    columns: Int = 4,
    rows: Int = 5,
    excludableItems: List<PreviewExcludableItem> = emptyList(),
): PlanPreviewDetails = PlanPreviewDetails(
    changes = changes,
    counts = counts,
    diagrams = testDiagrams(columns, rows),
    excludableItems = excludableItems,
)
