package app.lawnchair.organizer.planning

/**
 * Internal curated catalog of built-in layout strategies (spec 182 / ADR-0012).
 * One executable [StrategyDefinition] per accepted catalog member; there is no
 * plugin surface and strategies cannot bypass the shared validation, allocator,
 * or result canonicalization — dispatch happens strictly inside `plan`.
 *
 * A definition declares its deterministic intent as data ([createsFolders],
 * [eligibleUnitFilter], [unitOrder], [pageScope], [cellTraversal]) and shares
 * the single full-run executor; the executor branches only on these declared
 * fields. [placeFullRun] receives the shared materialization produced by
 * `PlanningPlacement.place` and must not reach back into platform or input
 * state beyond it.
 */
internal data class StrategyDefinition(
    val identity: StrategyId,
    val createsFolders: Boolean,
    /** Which movable captured items this strategy treats as placement units. */
    val eligibleUnitFilter: (CapturedItem) -> Boolean,
    val unitOrder: UnitOrdering,
    val pageScope: PageScope,
    val cellTraversal: CellTraversal,
    val placeFullRun: (FullRunContext) -> PlacementOutput,
)

/** Deterministic unit-ordering families (spec 182 internal seam). */
internal enum class UnitOrdering {
    /** Existing folders → new folders → singletons with canonical tie-breaks, grouped by preferred page. */
    CANONICAL_TIE_BREAK,

    /** Captured visual order `(cell.y, cell.x, ItemId)` within each page (page-local strategies). */
    CAPTURED_VISUAL_PAGE_LOCAL,

    /** Captured visual order across all pages (cross-page strategies). */
    CAPTURED_VISUAL_GLOBAL,
}

internal object LayoutStrategyRegistry {

    /**
     * Compatibility baseline: byte-equivalent to the pre-spec-182
     * `CANONICAL_V1` behavior. The `_V1` suffix is the version; a behavior
     * change is a new identity, never a reinterpretation.
     */
    val CANONICAL_PAGE_COMPACT_V1 = StrategyId("CANONICAL_PAGE_COMPACT_V1")

    private val definitions: Map<StrategyId, StrategyDefinition> = mapOf(
        CANONICAL_PAGE_COMPACT_V1 to StrategyDefinition(
            identity = CANONICAL_PAGE_COMPACT_V1,
            createsFolders = true,
            eligibleUnitFilter = { it.kind == ItemKind.APPLICATION || it.kind == ItemKind.DEEP_SHORTCUT },
            unitOrder = UnitOrdering.CANONICAL_TIE_BREAK,
            pageScope = PageScope.PREFERRED_THEN_NEW,
            cellTraversal = CellTraversal.TOP_LEFT_ROW_MAJOR,
            placeFullRun = FullRunExecution::execute,
        ),
    )

    fun definition(id: StrategyId): StrategyDefinition? = definitions[id]

    /**
     * Planner-side accepted set backing the V-20 defense-in-depth check.
     * Exactly the registered, executable strategies.
     */
    val acceptedIds: Set<StrategyId> = definitions.keys
}
