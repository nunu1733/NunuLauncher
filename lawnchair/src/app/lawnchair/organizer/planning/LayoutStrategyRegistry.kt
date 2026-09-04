package app.lawnchair.organizer.planning

/**
 * Internal curated catalog of built-in layout strategies (spec 182 / ADR-0012).
 * One executable [StrategyDefinition] per accepted catalog member; there is no
 * plugin surface and strategies cannot bypass the shared validation, allocator,
 * or result canonicalization — dispatch happens strictly inside `plan`.
 */
internal data class StrategyDefinition(
    val identity: StrategyId,
    val createsFolders: Boolean,
    /**
     * Executes the strategy over the shared materialization. Child 2 registers
     * only the canonical baseline, whose executor is the minimal adapter over
     * the existing placement body (no behavior change). Behavior extraction
     * per strategy is child 3+.
     */
    val place: (
        input: OrganizationInput,
        classification: ClassificationOutput,
        allocationFault: AllocationFault,
    ) -> PlacementOutput,
)

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
            place = PlanningPlacement::place,
        ),
    )

    fun definition(id: StrategyId): StrategyDefinition? = definitions[id]

    /**
     * Planner-side accepted set backing the V-20 defense-in-depth check.
     * Exactly the registered, executable strategies.
     */
    val acceptedIds: Set<StrategyId> = definitions.keys
}
