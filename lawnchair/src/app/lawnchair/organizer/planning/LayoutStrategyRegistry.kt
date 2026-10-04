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

    /** Per page: `(profile, category with fallback last, canonical target key, ItemId)` (spec 182 CATEGORY_CONTIGUOUS_V1). */
    CATEGORY_CONTIGUOUS_PAGE_LOCAL,
}

internal object LayoutStrategyRegistry {

    /**
     * Compatibility baseline: byte-equivalent to the pre-spec-182
     * `CANONICAL_V1` behavior. The `_V1` suffix is the version; a behavior
     * change is a new identity, never a reinterpretation.
     */
    val CANONICAL_PAGE_COMPACT_V1 = StrategyId("CANONICAL_PAGE_COMPACT_V1")

    /**
     * Spec 182 STABLE_PAGE_TIDY_V1: page-local lift-then-place compaction of
     * eligible `1×1` units; otherwise-movable non-`1×1` units and existing
     * folders are `STRATEGY_PRESERVED`; no new folders, no new pages.
     */
    val STABLE_PAGE_TIDY_V1 = StrategyId("STABLE_PAGE_TIDY_V1")

    /**
     * Spec 182 BOTTOM_FIRST_V1: canonical folder/unit/page policy with the
     * allocator traversal mirrored to fill lower rows first. User-facing copy
     * must say "bottom first" — never "thumb optimized" or "one-handed" (no
     * handedness input exists).
     */
    val BOTTOM_FIRST_V1 = StrategyId("BOTTOM_FIRST_V1")

    /**
     * Spec 182 GLOBAL_COMPACT_V1: cross-page density compaction. Only movable
     * `1×1` singletons compact (global captured visual order via
     * `allocateCapturedThenNew`); otherwise-movable non-`1×1` units and all
     * existing folder units are `STRATEGY_PRESERVED` fixed constraints; folder
     * formation (canonical P-04/P-05 grouping) applies to the eligible `1×1`
     * candidates only and the formed folders are placed after the compacting
     * units. The heterogeneous-span variant was rejected (INV-8).
     */
    val GLOBAL_COMPACT_V1 = StrategyId("GLOBAL_COMPACT_V1")

    /**
     * Spec 182 CATEGORY_CONTIGUOUS_V1: page-local category grouping — same
     * lift-then-place mechanics as STABLE_PAGE_TIDY_V1 with the per-page unit
     * order `(profile, category with fallback last, canonical target key,
     * ItemId)`; categories never pulled across page boundaries, no new
     * folders, existing folders fixed.
     */
    val CATEGORY_CONTIGUOUS_V1 = StrategyId("CATEGORY_CONTIGUOUS_V1")

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
        STABLE_PAGE_TIDY_V1 to StrategyDefinition(
            identity = STABLE_PAGE_TIDY_V1,
            createsFolders = false,
            eligibleUnitFilter = { item ->
                (item.kind == ItemKind.APPLICATION || item.kind == ItemKind.DEEP_SHORTCUT) &&
                    (item.placement as? CapturedPlacement.Workspace)?.span == GridSpan(1, 1)
            },
            unitOrder = UnitOrdering.CAPTURED_VISUAL_PAGE_LOCAL,
            pageScope = PageScope.CAPTURED_PAGE_ONLY,
            cellTraversal = CellTraversal.TOP_LEFT_ROW_MAJOR,
            placeFullRun = FullRunExecution::execute,
        ),
        BOTTOM_FIRST_V1 to StrategyDefinition(
            identity = BOTTOM_FIRST_V1,
            createsFolders = true,
            eligibleUnitFilter = { it.kind == ItemKind.APPLICATION || it.kind == ItemKind.DEEP_SHORTCUT },
            unitOrder = UnitOrdering.CANONICAL_TIE_BREAK,
            pageScope = PageScope.PREFERRED_THEN_NEW,
            cellTraversal = CellTraversal.BOTTOM_UP_ROW_MAJOR,
            placeFullRun = FullRunExecution::execute,
        ),
        GLOBAL_COMPACT_V1 to StrategyDefinition(
            identity = GLOBAL_COMPACT_V1,
            createsFolders = true,
            eligibleUnitFilter = { item ->
                (item.kind == ItemKind.APPLICATION || item.kind == ItemKind.DEEP_SHORTCUT) &&
                    (item.placement as? CapturedPlacement.Workspace)?.span == GridSpan(1, 1)
            },
            unitOrder = UnitOrdering.CAPTURED_VISUAL_GLOBAL,
            pageScope = PageScope.CAPTURED_THEN_NEW,
            cellTraversal = CellTraversal.TOP_LEFT_ROW_MAJOR,
            placeFullRun = FullRunExecution::execute,
        ),
        CATEGORY_CONTIGUOUS_V1 to StrategyDefinition(
            identity = CATEGORY_CONTIGUOUS_V1,
            createsFolders = false,
            eligibleUnitFilter = { item ->
                (item.kind == ItemKind.APPLICATION || item.kind == ItemKind.DEEP_SHORTCUT) &&
                    (item.placement as? CapturedPlacement.Workspace)?.span == GridSpan(1, 1)
            },
            unitOrder = UnitOrdering.CATEGORY_CONTIGUOUS_PAGE_LOCAL,
            pageScope = PageScope.CAPTURED_PAGE_ONLY,
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
