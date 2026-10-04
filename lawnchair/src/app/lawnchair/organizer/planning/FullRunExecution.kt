package app.lawnchair.organizer.planning

/**
 * Shared materialization handed to a strategy's full-run executor
 * (spec 182 internal seam): the constraint/unit/preservation work that is
 * invariant across strategies, computed once by `PlanningPlacement.place`.
 */

internal data class FullRunContext(
    val input: OrganizationInput,
    val classification: ClassificationOutput,
    val strategy: StrategyDefinition,
    val rolesById: Map<ItemId, ExistingRole>,
    val itemById: Map<ItemId, CapturedItem>,
    /** Captured items with no preservation predicate — the strategy's raw material. */
    val movableItems: List<CapturedItem>,
    val allocator: Allocator,
    val pageOrderMap: Map<PageId, PageOrder>,
    val preservationWarnings: List<Warning>,
    /**
     * Issue #204 (spec 204 / Q1): the semantic projection of the accepted
     * AI-personalization intent. Preference/ordering bias ONLY — it never
     * weakens `determinePreservation`, the allocator constraints, or any
     * strategy-declared semantics. Null for runs without an intent (existing
     * runs are byte-identical).
     */
    val preferences: app.lawnchair.organizer.personalization.PersonalizedIntentProjection? = null,
)

/**
 * Issue #204 (PR review 4, P1-2): canonical grouping identity of the accepted
 * intent's `desiredGroup` relation. The relation graph is closed into
 * connected components (`{self} ∪ desiredGroup`, transitively), so a
 * one-directional declaration coheres the referenced members too, and
 * multiple distinct groups get disjoint, stable ranks.
 */
internal fun intentComponentRanks(
    preferences: app.lawnchair.organizer.personalization.PersonalizedIntentProjection?,
): Map<ItemId, Int> {
    if (preferences == null) return emptyMap()
    val parent = HashMap<ItemId, ItemId>()
    fun find(x: ItemId): ItemId {
        var root = x
        while (parent[root] != root) root = parent.getValue(root)
        var cursor = x
        while (parent[cursor] != cursor) {
            val next = parent.getValue(cursor)
            parent[cursor] = root
            cursor = next
        }
        return root
    }

    fun union(a: ItemId, b: ItemId) {
        parent.putIfAbsent(a, a)
        parent.putIfAbsent(b, b)
        val ra = find(a)
        val rb = find(b)
        if (ra != rb) parent[ra] = rb
    }

    for (preference in preferences.itemPreferences) {
        val members = preference.desiredGroup ?: continue
        for (member in members) union(preference.item, member)
    }
    if (parent.isEmpty()) return emptyMap()
    val componentKey = HashMap<ItemId, String>()
    for (itemId in parent.keys) {
        val members = parent.keys.filter { find(it) == find(itemId) }.map { it.value }.sorted()
        componentKey[itemId] = members.joinToString(",")
    }
    return componentKey.entries
        .groupBy({ it.value }, { it.key })
        .entries
        .sortedBy { it.key }
        .flatMapIndexed { rank, (_, members) -> members.map { it to rank } }
        .toMap()
}

/**
 * Issue #204 (spec 204 / Q1): deterministic preference rank of an accepted
 * intent's `importance` for placement ordering. HIGH < NORMAL < LOW; an item
 * without a preference shares NORMAL's rank, so runs without an intent (or
 * without a preference for the item) keep the canonical ordering.
 */
internal fun unitPreferenceRank(
    itemId: ItemId,
    preferenceByItem: Map<ItemId, app.lawnchair.organizer.personalization.ItemPreference>?,
): Int {
    val importance = preferenceByItem?.get(itemId)?.importance
    return when (importance) {
        null -> 1
        app.lawnchair.organizer.personalization.Importance.HIGH -> 0
        app.lawnchair.organizer.personalization.Importance.NORMAL -> 1
        app.lawnchair.organizer.personalization.Importance.LOW -> 2
    }
}

/**
 * The single shared full-run executor (spec 182 internal seam item 4): it
 * branches only on the registered [StrategyDefinition]'s declared fields
 * (createsFolders / eligibleUnitFilter / unitOrder / pageScope; cell traversal
 * is carried by the shared allocator). Strategy semantics that are not yet
 * represented here belong to their own child issue — registering a definition
 * whose declared fields have no executor branch fails loudly instead of
 * silently running another strategy's layout.
 */
internal object FullRunExecution {

    fun execute(context: FullRunContext): PlacementOutput = when (context.strategy.unitOrder) {
        UnitOrdering.CANONICAL_TIE_BREAK -> executeCanonicalPageCompact(context)

        UnitOrdering.CAPTURED_VISUAL_PAGE_LOCAL ->
            executePageLocalLiftThenPlace(
                context,
                preferenceLayeredOrder(context, capturedVisualOrder()),
            )

        UnitOrdering.CAPTURED_VISUAL_GLOBAL -> executeGlobalCompact(context)

        UnitOrdering.CATEGORY_CONTIGUOUS_PAGE_LOCAL ->
            executePageLocalLiftThenPlace(
                context,
                preferenceLayeredOrder(context, categoryContiguousOrder(context)),
            )
    }

    /**
     * Issue #235: full-run executor for strategies that declare a
     * [WidgetPlacementPolicy]. The widget stream is consumed *before* the
     * strategy's app/folder stream (spec: role ordering is explicit, never
     * allocator iteration order) with these stages:
     *
     * (a) movable widgets are lifted out of `movableItems`;
     * (a') strategy-fixed movable items (`StrategyDefinition.strategyFixes`,
     *   restricted to non-widget items) become occupancy before any widget is
     *   placed — the page-local executors mark them only inside their own
     *   bodies, after this wrapper would already have placed widgets
     *   (spec 235 review M1). Re-marking inside the executor is
     *   occupancy-idempotent (`Allocator.markOccupied` is append-only);
     * (b) each captured page's widgets place first-fit row-major in the
     *   invariant key order `(span height desc, span width desc, target key,
     *   ItemId)` — all position-independent, so replan reproduces the stream
     *   order (spec D-2/D-3) — inside the policy's region (the captured
     *   widget band for [WidgetPlacementPolicy.PageLocalBand], the whole
     *   page for [WidgetPlacementPolicy.PageLocalTopAnchored]);
     * (c) a page whose widgets cannot all be placed degrades: every eligible
     *   widget on it keeps its captured position as occupancy and is reported
     *   `PreserveReason.STRATEGY_PRESERVED` (truthful; never a resize or a
     *   silent drop);
     * (d) the strategy's ordinary executor runs over the remaining movable
     *   items with the widget targets as fixed occupancy.
     *
     * Widget targets always keep the captured span; a moved widget is
     * `Moved{WIDGET_UNIT}`, an unmoved one `Preserved{ALREADY_CANONICAL}`.
     */
    fun executeWithWidgetStream(context: FullRunContext): PlacementOutput {
        val widgets = context.movableItems.filter(::isWidgetItem)
        if (widgets.isEmpty()) return execute(context)
        val remaining = context.movableItems.filterNot(::isWidgetItem)

        val widgetRows = placeWidgetStream(context, widgets, remaining)

        val output = execute(context.copy(movableItems = remaining))
        return output.copy(
            placements = (output.placements + widgetRows).sortedBy { it.item },
        )
    }

    internal fun isWidgetItem(item: CapturedItem): Boolean = item.kind == ItemKind.APPWIDGET || item.kind == ItemKind.CUSTOM_APPWIDGET

    /**
     * Stages (b)/(c) plus the row materialization. Each page's targets are
     * computed against a local obstacle list — the page's fixed occupancy
     * (naturally preserved items plus stage (a') strategy-fixed movable
     * items, both recomputed from the input) and the page's earlier widget
     * targets — and only a fully placeable page commits to the shared
     * allocator, so a degraded page never leaves partial marks behind. A
     * degraded page's widgets keep their captured positions as occupancy and
     * are reported `STRATEGY_PRESERVED`.
     */
    private fun placeWidgetStream(
        context: FullRunContext,
        widgets: List<CapturedItem>,
        remaining: List<CapturedItem>,
    ): List<PlannedPlacement> {
        val device = context.input.snapshot.device
        val reservations = context.input.snapshot.reservedWorkspaceRegions

        val pageObstaclesBuilder = mutableMapOf<PageId, MutableList<Rect>>()
        for (item in context.input.snapshot.items) {
            if (determinePreservation(item, context.rolesById[item.id], reservations, relocateWidgets = true) != null) {
                val ws = item.placement as? CapturedPlacement.Workspace ?: continue
                pageObstaclesBuilder.getOrPut(ws.page.pageId) { mutableListOf() } += rectOf(ws.cell, ws.span)
            }
        }
        for (item in remaining.filter(context.strategy::strategyFixes)) {
            val ws = item.placement as CapturedPlacement.Workspace
            pageObstaclesBuilder.getOrPut(ws.page.pageId) { mutableListOf() } += rectOf(ws.cell, ws.span)
        }
        // Owner review (PR #296 High): the reservation rectangles themselves
        // are planner occupancy authority (spec #185/ADR-0010) — a widget
        // target must never land on a reserved region even when no captured
        // item overlaps it. Without this, the trial scan could place a widget
        // onto e.g. a top QSB strip because `markOccupied` never re-checks.
        for (reservation in reservations) {
            pageObstaclesBuilder.getOrPut(reservation.page.pageId) { mutableListOf() } += rectOf(reservation.cell, reservation.span)
        }
        val pageObstacles: Map<PageId, List<Rect>> = pageObstaclesBuilder

        val ordered = widgets.sortedWith(
            compareByDescending<CapturedItem> { (it.placement as CapturedPlacement.Workspace).span.height }
                .thenByDescending { (it.placement as CapturedPlacement.Workspace).span.width }
                // Owner review (PR #296 Medium): the invariant key compares the
                // widget identity in its typed canonical order — provider
                // (ComponentKey, UTF-8 byte order), appWidgetId numerically
                // (AppWidgetId), profile (ProfileId, UTF-8 byte order) — never
                // the concatenated `targetKeySortValue` string, whose
                // appWidgetId segment compares lexicographically ("10" < "2")
                // and which drops the profile segment entirely. Validation
                // (V-12 family) guarantees widget kinds carry a WidgetKey.
                .thenBy { (it.target as TargetKey.WidgetKey).provider }
                .thenBy { (it.target as TargetKey.WidgetKey).appWidgetId }
                .thenBy { (it.target as TargetKey.WidgetKey).profile }
                .thenBy { it.id },
        )
        // Issue #204 (PR review 5, P1): both registered widget policies are
        // page-local (#235 — "widgets stay on their captured page"), so a
        // widget's `pageAffinity` preference is NOT consumed here; cross-page
        // widget affinity activation belongs to a future policy that declares
        // it. Region affinity stays inside the strategy-owned window below.
        val byPage = ordered.groupBy { (it.placement as CapturedPlacement.Workspace).page.pageId }
        val pagesInOrder = context.input.snapshot.pages
            .sortedWith(compareBy({ it.order }, { it.id.value }))
            .filter { it.id in byPage.keys }

        data class WidgetCell(val page: PageRef, val cell: GridCell)

        val streamCells = mutableMapOf<ItemId, WidgetCell>()
        val degradedPages = mutableSetOf<PageId>()
        for (page in pagesInOrder) {
            val pageWidgets = byPage.getValue(page.id)
            // Trial targets accumulate into a page-local list: a degraded page
            // must leave no trace here, and the map's captured lists stay
            // immutable (review L-2).
            val obstacles = (pageObstacles[page.id] ?: emptyList()).toMutableList()
            val window = when (context.strategy.widgetPolicy) {
                WidgetPlacementPolicy.PageLocalBand -> {
                    val minY = pageWidgets.minOf { item -> (item.placement as CapturedPlacement.Workspace).cell.y }
                    val maxY = pageWidgets.maxOf { item ->
                        val ws = item.placement as CapturedPlacement.Workspace
                        ws.cell.y + ws.span.height - 1
                    }
                    minY..maxY
                }

                WidgetPlacementPolicy.PageLocalTopAnchored -> null

                null -> error("executeWithWidgetStream registered without a widget policy")
            }
            val preferenceByItem = context.preferences?.itemPreferences?.associateBy { it.item }
            val pageCells = mutableListOf<WidgetCell>()
            for (widget in pageWidgets) {
                val ws = widget.placement as CapturedPlacement.Workspace
                // Issue #204 (PR review 5, P1): regionAffinity is a soft hint
                // strictly inside the strategy-owned window. `PageLocalBand`
                // intersects the requested band with the captured band;
                // `PageLocalTopAnchored` declares its own traversal and is
                // never re-anchored by the intent.
                val bandWindow = if (window != null) {
                    preferenceByItem?.get(widget.id)?.regionAffinity?.let { affinity ->
                        val rows = device.rows
                        val band = when (affinity) {
                            app.lawnchair.organizer.personalization.ExportRegionKind.TOP -> 0 until rows / 3
                            app.lawnchair.organizer.personalization.ExportRegionKind.MIDDLE -> (rows / 3) until ((2 * rows) / 3)
                            app.lawnchair.organizer.personalization.ExportRegionKind.BOTTOM -> ((2 * rows) / 3) until rows
                        }
                        val overlap = maxOf(band.first, window.first)..minOf(band.last, window.last)
                        if (overlap.isEmpty()) null else overlap
                    }
                } else {
                    null
                }
                val cell = bandWindow?.let { band ->
                    findRowMajorFirstFit(
                        obstacles,
                        device.columns,
                        device.rows,
                        ws.span,
                        CellTraversal.TOP_LEFT_ROW_MAJOR,
                        rowWindow = band,
                    )
                } ?: findRowMajorFirstFit(
                    obstacles,
                    device.columns,
                    device.rows,
                    ws.span,
                    CellTraversal.TOP_LEFT_ROW_MAJOR,
                    rowWindow = window,
                )
                if (cell == null) {
                    degradedPages += page.id
                    break
                }
                obstacles += rectOf(cell, ws.span)
                pageCells += WidgetCell(PageRef(page.id), cell)
            }
            if (page.id in degradedPages) continue
            pageCells.forEachIndexed { index, widgetCell ->
                val widget = pageWidgets[index]
                streamCells[widget.id] = widgetCell
                val ws = widget.placement as CapturedPlacement.Workspace
                context.allocator.markOccupied(widgetCell.page, widgetCell.cell, ws.span)
            }
        }

        return widgets.map { widget ->
            val ws = widget.placement as CapturedPlacement.Workspace
            val capturedTarget = PlacementTarget.WorkspaceTarget(PageRef(ws.page.pageId), ws.cell, ws.span)
            val degraded = ws.page.pageId in degradedPages
            val streamCell = streamCells[widget.id]
            check(degraded != (streamCell != null)) { "widget ${widget.id} must be either degraded or placed" }
            if (degraded) {
                context.allocator.markOccupied(PageRef(ws.page.pageId), ws.cell, ws.span)
                PlannedPlacement(widget.id, Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED), capturedTarget)
            } else {
                val target = PlacementTarget.WorkspaceTarget(streamCell!!.page, streamCell.cell, ws.span)
                val disposition = if (target == capturedTarget) {
                    Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)
                } else {
                    Disposition.Moved(PlacementCode.WIDGET_UNIT)
                }
                PlannedPlacement(widget.id, disposition, target)
            }
        }
    }

    private fun rectOf(cell: GridCell, span: GridSpan) = Rect(
        cell.x.toLong(),
        cell.y.toLong(),
        span.width.toLong(),
        span.height.toLong(),
    )

    /** CAPTURED_VISUAL_PAGE_LOCAL: captured visual order `(cell.y, cell.x, ItemId)`. */
    private fun capturedVisualOrder(): Comparator<CapturedItem> = compareBy(
        { (it.placement as CapturedPlacement.Workspace).cell.y },
        { (it.placement as CapturedPlacement.Workspace).cell.x },
        { it.id },
    )

    /**
     * CATEGORY_CONTIGUOUS_PAGE_LOCAL: `(profile, category with fallback last,
     * canonical target key, ItemId)`. The target-key sort value is this
     * executor's documented canonical encoding (variant ordinal, then the
     * opaque handle values in byte order); it is locale-independent and total
     * over the eligible app/deep-shortcut units.
     */
    internal fun categoryContiguousOrder(context: FullRunContext): Comparator<CapturedItem> {
        val fallback = context.input.taxonomy.fallbackCategory
        return compareBy(
            { it.profile },
            { if (context.classification.decisions[it.id]?.category ?: fallback == fallback) 1 else 0 },
            { context.classification.decisions[it.id]?.category ?: fallback },
            { targetKeySortValue(it.target) },
            { it.id },
        )
    }

    private fun targetKeySortValue(key: TargetKey): String = when (key) {
        is TargetKey.AppKey -> "0:${key.component.value}"
        is TargetKey.ShortcutKey -> "1:${key.packageName.value}:${key.shortcutId.value}"
        is TargetKey.LegacyShortcutKey -> "2"
        is TargetKey.WidgetKey -> "3:${key.provider.value}:${key.appWidgetId.value}"
        is TargetKey.FolderKey -> "4:${key.folderId.value}"
        is TargetKey.AppPairKey -> "5:${key.appPairId.value}"
    }

    /** Shared tail: naturally preserved items keep their captured placement with their precedence reason. */
    private fun appendPreservedPlacements(
        context: FullRunContext,
        placements: MutableList<PlannedPlacement>,
    ) {
        // Issue #235: under a widget-capable strategy an eligible widget has
        // no preservation reason — its row comes from the widget stream, so
        // this tail must not re-report it.
        val relocateWidgets = context.strategy.widgetPolicy != null
        for (item in context.input.snapshot.items) {
            val reason = determinePreservation(
                item,
                context.rolesById[item.id],
                context.input.snapshot.reservedWorkspaceRegions,
                relocateWidgets = relocateWidgets,
            )
            if (reason != null) {
                placements += PlannedPlacement(
                    item = item.id,
                    disposition = Disposition.Preserved(reason),
                    target = capturedToOutput(item.placement),
                )
            }
        }
    }

    /**
     * Issue #204 (spec 204 / Q1): preference layering for the non-canonical
     * executors — GROUPING (connected-component rank) and IMPORTANCE order
     * the units before the executor's own captured-visual/category order.
     * Every key is a constant without a matching preference, so
     * intent-less runs keep each executor's canonical ordering.
     */
    private fun preferenceLayeredOrder(
        context: FullRunContext,
        base: Comparator<CapturedItem>,
    ): Comparator<CapturedItem> {
        val componentRanks = intentComponentRanks(context.preferences)
        val preferenceByItem = context.preferences?.itemPreferences?.associateBy { it.item }
        return compareBy(
            { item: CapturedItem -> componentRanks[item.id] ?: Int.MAX_VALUE },
            { item: CapturedItem ->
                val importance = preferenceByItem?.get(item.id)?.importance
                when (importance) {
                    app.lawnchair.organizer.personalization.Importance.HIGH -> 0
                    null -> 1
                    app.lawnchair.organizer.personalization.Importance.NORMAL -> 1
                    app.lawnchair.organizer.personalization.Importance.LOW -> 2
                }
            },
        ).thenComparing(base)
    }

    /**
     * Issue #204 (spec 204 / Q1): page-local soft hints — a preserved
     * item's captured cell, else its region-affinity band. Page-local
     * strategies never create pages, so both hints stay on the captured
     * page and degrade to the strategy's own page-local first fit.
     */
    private fun preferenceAllocateOnPage(
        context: FullRunContext,
        item: CapturedItem,
        allocator: Allocator,
        span: GridSpan,
        page: PageRef,
    ): Pair<PageTargetRef, GridCell>? {
        val preference = context.preferences?.itemPreferences?.firstOrNull { it.item == item.id }
            ?: return null
        if (preference.preserve == true) {
            val captured = (item.placement as? CapturedPlacement.Workspace)?.cell
            if (captured != null) {
                allocator.allocateOnPageOnlyWithCellHint(span, page, captured)?.let { return it }
            }
        }
        val affinity = preference.regionAffinity ?: return null
        val rows = context.input.snapshot.device.rows
        val band = when (affinity) {
            app.lawnchair.organizer.personalization.ExportRegionKind.TOP -> 0 until rows / 3
            app.lawnchair.organizer.personalization.ExportRegionKind.MIDDLE -> (rows / 3) until ((2 * rows) / 3)
            app.lawnchair.organizer.personalization.ExportRegionKind.BOTTOM -> ((2 * rows) / 3) until rows
        }
        return allocator.allocateOnPageOnlyInBand(span, page, band)
    }

    private fun executePageLocalLiftThenPlace(
        context: FullRunContext,
        unitOrder: Comparator<CapturedItem>,
    ): PlacementOutput {
        val strategy = context.strategy
        val allocator = context.allocator

        // Spec 182 STABLE_PAGE_TIDY_V1: lift-then-place page-local compaction.
        // Otherwise-movable items the strategy intentionally keeps fixed —
        // existing folders and non-1x1 apps/deep shortcuts — are occupancy
        // constraints reported with the truthful STRATEGY_PRESERVED reason.
        val strategyFixed = context.movableItems.filter { !strategy.eligibleUnitFilter(it) }
        val eligible = context.movableItems.filter { strategy.eligibleUnitFilter(it) }

        val placements = mutableListOf<PlannedPlacement>()
        for (item in strategyFixed) {
            val ws = item.placement as CapturedPlacement.Workspace
            allocator.markOccupied(PageRef(ws.page.pageId), ws.cell, ws.span)
            placements += PlannedPlacement(
                item = item.id,
                disposition = Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
                target = capturedToOutput(item.placement),
            )
        }

        // Per page: eligible 1x1 units in captured visual order (cell.y, cell.x,
        // ItemId) take the earliest free row-major 1x1 cell on their captured
        // page. The captured layout is valid and non-overlapping, so after
        // lifting the eligible units their count never exceeds the page's free
        // cells: every unit is placeable, and a placement failure is a planner
        // invariant violation that fails loudly (never a new page, never a
        // partial plan).
        val byPage = eligible.groupBy { item ->
            (item.placement as CapturedPlacement.Workspace).page
        }
        for ((page, units) in byPage) {
            val ordered = units.sortedWith(unitOrder)
            for (item in ordered) {
                val ws = item.placement as CapturedPlacement.Workspace
                val capturedTarget = PlacementTarget.WorkspaceTarget(PageRef(ws.page.pageId), ws.cell, ws.span)
                // Issue #204 (spec 204 / Q1): soft preference hints (preserve
                // captured cell, region band) degrade to the page-local first fit.
                val allocated = preferenceAllocateOnPage(context, item, allocator, ws.span, PageRef(ws.page.pageId))
                    ?: allocator.allocateOnPageOnly(ws.span, PageRef(ws.page.pageId))
                val (pageRef, cell) = allocated ?: error(
                    "${strategy.identity.value} could not place eligible unit ${item.id} on its captured page " +
                        "(lift-then-place placeability invariant violated)",
                )
                allocator.markOccupied(pageRef, cell, ws.span)

                val newTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, ws.span)
                val disposition = if (newTarget != capturedTarget) {
                    Disposition.Moved(PlacementCode.SINGLE_PLACEMENT)
                } else {
                    Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)
                }
                placements += PlannedPlacement(item.id, disposition, newTarget)
            }
        }

        appendPreservedPlacements(context, placements)

        return PlacementOutput(
            placements = placements.sortedBy { it.item },
            newPages = allocator.buildNewPages(),
            newFolders = emptyList(),
            preservationWarnings = context.preservationWarnings,
        )
    }

    /**
     * GLOBAL_COMPACT family (spec 182 V1 / spec 237 V2): cross-page density
     * compaction in global captured visual order `(PageOrder, PageId, cell.y,
     * cell.x, ItemId)`, each unit taking the earliest free cell scanning all
     * captured pages then already-created new pages
     * (`PageScope.CAPTURED_THEN_NEW`). Formed folders (canonical P-04/P-05
     * grouping) are placed after the compacting units by `(preferred page
     * key, NewFolderOrdinal)`.
     *
     * The two versions differ only in their [StrategyDefinition]'s declared
     * eligibility filter: V1 compacts movable `1×1` singletons and pins every
     * existing folder (`STRATEGY_PRESERVED`); V2 (spec 237) lets existing `1×1`
     * top-level folder units join the stream — reported as
     * `PlacementCode.FOLDER_UNIT` when they move — while non-`1×1` units stay
     * fixed. Folder formation candidates exclude existing folders under both
     * versions. Replan idempotence for V2 is re-proven over the
     * formation-inclusive state transition in spec 237: the fixed set is
     * invariant, the materialized captured visual order restores the
     * consumption order, and formation is replan-stable (residual singleton
     * candidate groups cannot form a new folder), so a replan reclaims every
     * unit's own cell with an empty diff.
     */
    private fun executeGlobalCompact(context: FullRunContext): PlacementOutput {
        val input = context.input
        val strategy = context.strategy
        val taxonomy = input.taxonomy
        val device = input.snapshot.device
        val allocator = context.allocator

        val strategyFixed = context.movableItems.filter { !strategy.eligibleUnitFilter(it) }
        val eligible = context.movableItems.filter { strategy.eligibleUnitFilter(it) }

        val placements = mutableListOf<PlannedPlacement>()
        for (item in strategyFixed) {
            val ws = item.placement as CapturedPlacement.Workspace
            allocator.markOccupied(PageRef(ws.page.pageId), ws.cell, ws.span)
            placements += PlannedPlacement(
                item = item.id,
                disposition = Disposition.Preserved(PreserveReason.STRATEGY_PRESERVED),
                target = capturedToOutput(item.placement),
            )
        }

        val capacity = device.folderMaxColumns.toLong() * device.folderMaxRows.toLong()
        val minGroupSize = input.rules.folderPolicy.minGroupSize
        val folderGroups = if (strategy.createsFolders) {
            formFolderGroups(
                candidates = eligible
                    .filter { it.kind != ItemKind.FOLDER }
                    .map { item ->
                        FolderCandidate(
                            item.id,
                            item.profile,
                            context.classification.decisions[item.id]?.category ?: taxonomy.fallbackCategory,
                        )
                    },
                fallbackCategory = taxonomy.fallbackCategory,
                capacity = capacity,
                minGroupSize = minGroupSize,
            )
        } else {
            emptyList()
        }
        val folderMemberIds = folderGroups.flatMapTo(mutableSetOf()) { it.members }

        // Issue #204 (spec 204 / Q1): preference layering (GROUPING rank,
        // IMPORTANCE) over the global captured visual order.
        val componentRanks = intentComponentRanks(context.preferences)
        val preferenceByItem = context.preferences?.itemPreferences?.associateBy { it.item }
        val workspaceUnits = eligible
            .filter { it.id !in folderMemberIds }
            .sortedWith(
                compareBy(
                    { item: CapturedItem -> componentRanks[item.id] ?: Int.MAX_VALUE },
                    { item: CapturedItem ->
                        when (preferenceByItem?.get(item.id)?.importance) {
                            app.lawnchair.organizer.personalization.Importance.HIGH -> 0
                            app.lawnchair.organizer.personalization.Importance.LOW -> 2
                            else -> 1
                        }
                    },
                    { pageOrderOf(context, (it.placement as CapturedPlacement.Workspace).page) },
                    { (it.placement as CapturedPlacement.Workspace).page.pageId },
                    { (it.placement as CapturedPlacement.Workspace).cell.y },
                    { (it.placement as CapturedPlacement.Workspace).cell.x },
                    { it.id },
                ),
            )
        for (item in workspaceUnits) {
            val ws = item.placement as CapturedPlacement.Workspace
            val capturedTarget = PlacementTarget.WorkspaceTarget(PageRef(ws.page.pageId), ws.cell, ws.span)
            // Issue #204: soft preference hints (preserve captured cell,
            // region band) degrade to the global captured-then-new scan.
            val allocated = preferenceAllocateOnPage(context, item, allocator, ws.span, PageRef(ws.page.pageId))
                ?: allocator.allocateCapturedThenNew(ws.span)
                ?: error("Validated item ${item.id} could not be allocated")
            val (pageRef, cell) = allocated
            allocator.markOccupied(pageRef, cell, ws.span)
            val newTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, ws.span)
            val disposition = if (newTarget != capturedTarget) {
                Disposition.Moved(
                    if (item.kind == ItemKind.FOLDER) PlacementCode.FOLDER_UNIT else PlacementCode.SINGLE_PLACEMENT,
                )
            } else {
                Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)
            }
            placements += PlannedPlacement(item.id, disposition, newTarget)
        }

        val outputNewFolders = mutableListOf<NewFolder>()
        for (group in folderGroups.sortedWith(
            compareBy<FolderGroup>(
                { pageOrderOf(context, preferredPageOf(context, it.members)) },
                { it.ordinal.value },
            ),
        )) {
            val allocated = allocator.allocateCapturedThenNew(GridSpan(1, 1))
                ?: error("Validated folder ${group.ordinal} could not be allocated")
            val (pageRef, cell) = allocated
            allocator.markOccupied(pageRef, cell, GridSpan(1, 1))
            outputNewFolders += NewFolder(
                ordinal = group.ordinal,
                profile = group.profile,
                naming = FolderNaming.FromCategory(group.category),
                workspacePlacement = PlacementTarget.WorkspaceTarget(pageRef, cell, GridSpan(1, 1)),
                members = group.members,
            )
            for ((rank, memberId) in group.members.withIndex()) {
                placements += PlannedPlacement(
                    item = memberId,
                    disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    target = PlacementTarget.FolderMember(NewFolderRef(group.ordinal), rank),
                )
            }
        }

        appendPreservedPlacements(context, placements)

        return PlacementOutput(
            placements = placements.sortedBy { it.item },
            newPages = allocator.buildNewPages(),
            newFolders = outputNewFolders.sortedBy { it.ordinal },
            preservationWarnings = context.preservationWarnings,
        )
    }

    /**
     * Issue #204 (spec 204 / Q1): accepted-intent `pageAffinity` bias. A
     * movable unit whose intent declares a page affinity prefers that captured
     * page for allocation. The preference is NOT authoritative: the strategy's
     * page scope and the allocator's capacity semantics decide placement, and
     * an unsatisfiable preference simply falls through to the strategy's own
     * overflow behavior. Items without a preference keep their captured page.
     */
    private fun biasedPreferredPage(
        context: FullRunContext,
        itemId: ItemId,
        capturedPage: PageRef,
    ): PageRef {
        val preference = context.preferences?.itemPreferences?.firstOrNull { it.item == itemId }
        val ordinal = preference?.pageAffinity ?: return capturedPage
        if (ordinal < 0 || ordinal >= context.input.snapshot.pages.size) return capturedPage
        return context.input.snapshot.pages[ordinal].let { PageRef(it.id) }
    }

    private fun pageOrderOf(context: FullRunContext, page: PageRef): PageOrder = context.pageOrderMap.getValue(page.pageId)

    private fun preferredPageOf(context: FullRunContext, members: List<ItemId>): PageRef = members
        .map { id -> (context.itemById.getValue(id).placement as CapturedPlacement.Workspace).page }
        .minWith(pageRefComparator(context.pageOrderMap))

    private fun executeCanonicalPageCompact(context: FullRunContext): PlacementOutput {
        val input = context.input
        val strategy = context.strategy
        val itemById = context.itemById
        val device = input.snapshot.device
        val taxonomy = input.taxonomy
        val movableItems = context.movableItems
        val allocator = context.allocator

        val existingFolderUnits = movableItems.filter { it.kind == ItemKind.FOLDER }
        val movableApps = movableItems.filter { strategy.eligibleUnitFilter(it) }

        val capacity = device.folderMaxColumns.toLong() * device.folderMaxRows.toLong()
        val minGroupSize = input.rules.folderPolicy.minGroupSize
        // Issue #204 (spec 204 / Q1): the movement-minimization ordering bias
        // of the accepted intent — global `minimizeMovement`, or a per-item
        // `preserve` on any movable unit, switches the singleton ordering to
        // the captured visual order (see executeCanonicalPageCompact).
        val preferenceByItem = context.preferences?.itemPreferences?.associateBy { it.item }
        val movementMinimizing = context.preferences != null && (
            context.preferences.globalMinimizeMovement ||
                preferenceByItem.orEmpty().values.any { it.preserve == true }
            )
        fun effectiveCategory(itemId: ItemId): CategoryId {
            val intentCategory = preferenceByItem?.get(itemId)?.groupSemantic?.category
            if (intentCategory != null && context.input.taxonomy.allowedCategories.any { it.value == intentCategory }) {
                return CategoryId(intentCategory)
            }
            return context.classification.decisions[itemId]?.category ?: taxonomy.fallbackCategory
        }

        data class FormedFolder(
            val ordinal: NewFolderOrdinal,
            val profile: ProfileId,
            val naming: FolderNaming,
            val members: List<ItemId>,
            val preferredPage: PageRef,
        )

        val folderGroups = if (strategy.createsFolders) {
            formFolderGroups(
                candidates = movableApps.map { item ->
                    FolderCandidate(
                        item.id,
                        item.profile,
                        effectiveCategory(item.id),
                    )
                },
                fallbackCategory = taxonomy.fallbackCategory,
                capacity = capacity,
                minGroupSize = minGroupSize,
            )
        } else {
            emptyList()
        }
        val newFolders = folderGroups.map { group ->
            val preferredPage = group.members
                .map { id -> (itemById.getValue(id).placement as CapturedPlacement.Workspace).page }
                .minWith(pageRefComparator(context.pageOrderMap))
            FormedFolder(group.ordinal, group.profile, FolderNaming.FromCategory(group.category), group.members, preferredPage)
        }
        val folderMemberIds = folderGroups.flatMapTo(mutableSetOf()) { it.members }

        val singletonItems = movableApps.filter { it.id !in folderMemberIds }

        data class FullUnit(
            val itemId: ItemId,
            val span: GridSpan,
            val preferredPage: PageRef,
            val isFolder: Boolean,
            val sortProfile: ProfileId,
            val sortCategory: CategoryId,
            val isNewFolder: Boolean,
            val newFolderOrdinal: NewFolderOrdinal?,
            /** Captured visual index for the movement-minimization bias (null for synthetic units). */
            val capturedVisual: Int? = null,
            /** Issue #204: preserve hint — this unit's captured cell (soft). */
            val cellHint: GridCell? = null,
            /** Issue #204: region-affinity hint — preferred row band (soft). */
            val bandHint: IntRange? = null,
        )

        /**
         * Issue #204 (PR review 4, P1-1): per-item `preserve` — the unit's
         * own captured cell as the allocation hint. The allocator uses it
         * exactly when free and degrades to the canonical first fit
         * otherwise, so a preserve can never worsen the item's displacement.
         */
        fun preferenceCellHint(context: FullRunContext, item: CapturedItem): GridCell? {
            val preference = context.preferences?.itemPreferences?.firstOrNull { it.item == item.id }
            if (preference?.preserve != true) return null
            return (item.placement as? CapturedPlacement.Workspace)?.cell
        }

        /** Issue #204 (PR review 4, P2): region-affinity row band (soft). */
        fun preferenceBandHint(context: FullRunContext, item: CapturedItem): IntRange? {
            val preference = context.preferences?.itemPreferences?.firstOrNull { it.item == item.id }
            val affinity = preference?.regionAffinity ?: return null
            val rows = context.input.snapshot.device.rows
            return when (affinity) {
                app.lawnchair.organizer.personalization.ExportRegionKind.TOP -> 0 until rows / 3
                app.lawnchair.organizer.personalization.ExportRegionKind.MIDDLE -> (rows / 3) until ((2 * rows) / 3)
                app.lawnchair.organizer.personalization.ExportRegionKind.BOTTOM -> ((2 * rows) / 3) until rows
            }
        }

        fun capturedVisualOf(item: CapturedItem): Int? {
            val ws = item.placement as? CapturedPlacement.Workspace ?: return null
            val pageIndex = context.input.snapshot.pages.indexOfFirst { it.id == ws.page.pageId }
            if (pageIndex < 0) return null
            return pageIndex * (device.rows * device.columns) + ws.cell.y * device.columns + ws.cell.x
        }

        val units = mutableListOf<FullUnit>()

        fun FullUnit.preferenceRank(
            preferenceByItem: Map<ItemId, app.lawnchair.organizer.personalization.ItemPreference>?,
        ): Int = unitPreferenceRank(itemId, preferenceByItem)

        fun FullUnit.regionRank(
            preferenceByItem: Map<ItemId, app.lawnchair.organizer.personalization.ItemPreference>?,
        ): Int = when (preferenceByItem?.get(itemId)?.regionAffinity) {
            app.lawnchair.organizer.personalization.ExportRegionKind.TOP -> 0
            null -> 1
            app.lawnchair.organizer.personalization.ExportRegionKind.MIDDLE -> 1
            app.lawnchair.organizer.personalization.ExportRegionKind.BOTTOM -> 2
        }

        fun FullUnit.groupRank(componentRanks: Map<ItemId, Int>): Int = componentRanks[itemId] ?: Int.MAX_VALUE
        for (folder in existingFolderUnits) {
            val ws = folder.placement as CapturedPlacement.Workspace
            units += FullUnit(
                itemId = folder.id,
                span = ws.span,
                preferredPage = biasedPreferredPage(context, folder.id, ws.page),
                isFolder = true,
                sortProfile = folder.profile,
                sortCategory = effectiveCategory(folder.id),
                capturedVisual = capturedVisualOf(folder),
                cellHint = preferenceCellHint(context, folder),
                bandHint = preferenceBandHint(context, folder),
                isNewFolder = false,
                newFolderOrdinal = null,
            )
        }
        for (nf in newFolders) {
            units += FullUnit(
                itemId = nf.members.first(),
                span = GridSpan(1, 1),
                preferredPage = nf.preferredPage,
                isFolder = true,
                sortProfile = nf.profile,
                sortCategory = taxonomy.fallbackCategory,
                isNewFolder = true,
                newFolderOrdinal = nf.ordinal,
            )
        }
        for (item in singletonItems) {
            val ws = item.placement as CapturedPlacement.Workspace
            units += FullUnit(
                itemId = item.id,
                span = ws.span,
                preferredPage = biasedPreferredPage(context, item.id, ws.page),
                isFolder = false,
                sortProfile = item.profile,
                sortCategory = effectiveCategory(item.id),
                capturedVisual = capturedVisualOf(item),
                cellHint = preferenceCellHint(context, item),
                bandHint = preferenceBandHint(context, item),
                isNewFolder = false,
                newFolderOrdinal = null,
            )
        }

        val pageGroups = units.groupBy { it.preferredPage }
        val sortedPages = pageGroups.keys.sortedWith(pageRefComparator(context.pageOrderMap))

        val placements = mutableListOf<PlannedPlacement>()
        val outputNewFolders = mutableListOf<NewFolder>()

        for (page in sortedPages) {
            val pageUnits = pageGroups.getValue(page)

            val ordered = when (strategy.unitOrder) {
                // The outer dispatch routes CATEGORY_CONTIGUOUS_PAGE_LOCAL to
                // the page-local executor; only the canonical ordering reaches
                // this flow.
                UnitOrdering.CANONICAL_TIE_BREAK -> {
                    val existingFolders = pageUnits.filter { it.isFolder && !it.isNewFolder }
                        .sortedBy { it.itemId }
                    val newFolderUnits = pageUnits.filter { it.isNewFolder }
                        .sortedBy { it.newFolderOrdinal }
                    // Issue #204 (spec 204 / Q1): accepted-intent preference
                    // bias. PRESERVE items order first so their captured-cell
                    // hint stays free (each then allocates exactly at its
                    // captured cell, never drifting farther). GLOBAL_PREFERENCE
                    // (`minimizeMovement`) switches the whole singleton
                    // ordering to the captured visual order so allocations
                    // reproduce the existing layout as closely as the strategy
                    // allows. GROUPING (connected components of the desired
                    // group relation, one-directional declarations included)
                    // and REGION_AFFINITY (top band first) order the units the
                    // movement bias does not pin. Every key is a constant
                    // without a matching preference and the whole chain is
                    // inert without an accepted intent, so intent-less runs
                    // keep the canonical ordering byte-identical.
                    val preferenceByItem = context.preferences?.itemPreferences?.associateBy { it.item }
                    val preserveItems = preferenceByItem.orEmpty().values
                        .filter { it.preserve == true }.map { it.item }.toSet()
                    val movementMinimizing = context.preferences?.globalMinimizeMovement == true
                    val componentRanks = intentComponentRanks(context.preferences)
                    val singletons = pageUnits.filter { !it.isFolder }
                        .sortedWith(
                            compareBy<FullUnit>(
                                { if (it.itemId in preserveItems) 0 else 1 },
                                { if (movementMinimizing && it.capturedVisual != null) 0 else 1 },
                                { if (movementMinimizing) it.capturedVisual ?: 0 else 0 },
                                { it.groupRank(componentRanks) },
                                { it.preferenceRank(preferenceByItem) },
                                { it.regionRank(preferenceByItem) },
                            ).thenBy { it.sortProfile }
                                .thenBy { it.sortCategory }
                                .thenBy { it.itemId },
                        )
                    existingFolders + newFolderUnits + singletons
                }

                else -> throw IllegalStateException(
                    "UnitOrdering ${strategy.unitOrder} has no canonical-flow executor branch",
                )
            }

            for (unit in ordered) {
                // Issue #204 (spec 204 / Q1): soft preference hints — a
                // preserve cell hint is used exactly when free (never worse
                // than the canonical first fit), and a region band is tried
                // before the strategy's own fallback.
                val hinted = when {
                    unit.cellHint != null -> allocator.allocateWithCellHint(unit.span, unit.preferredPage, unit.cellHint)
                    unit.bandHint != null -> allocator.allocatePreferredInBand(unit.span, unit.preferredPage, unit.bandHint)
                    else -> null
                }
                val allocated = hinted ?: when (strategy.pageScope) {
                    PageScope.PREFERRED_THEN_NEW -> allocator.allocatePreferred(unit.span, unit.preferredPage)

                    PageScope.CAPTURED_THEN_NEW -> allocator.allocateCapturedThenNew(unit.span)

                    // Declared by page-local strategies; the canonical flow
                    // never selects it. Page-local strategies run through
                    // executeStablePageTidy, whose allocation never creates a
                    // page.
                    PageScope.CAPTURED_PAGE_ONLY -> throw IllegalStateException(
                        "PageScope ${strategy.pageScope} has no canonical-flow executor branch",
                    )
                }
                val (pageRef, cell) = allocated ?: error(
                    "Validated item ${unit.itemId} could not be allocated",
                )

                allocator.markOccupied(pageRef, cell, unit.span)

                if (unit.isNewFolder) {
                    val nf = newFolders.single { it.ordinal == unit.newFolderOrdinal }
                    val wsTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                    outputNewFolders += NewFolder(
                        ordinal = nf.ordinal,
                        profile = nf.profile,
                        naming = nf.naming,
                        workspacePlacement = wsTarget,
                        members = nf.members,
                    )
                    for ((rank, memberId) in nf.members.withIndex()) {
                        placements += PlannedPlacement(
                            item = memberId,
                            disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                            target = PlacementTarget.FolderMember(
                                NewFolderRef(nf.ordinal),
                                rank,
                            ),
                        )
                    }
                } else {
                    val item = itemById[unit.itemId]!!
                    val capturedWs = item.placement as CapturedPlacement.Workspace
                    val capturedTarget = PlacementTarget.WorkspaceTarget(
                        PageRef(capturedWs.page.pageId),
                        capturedWs.cell,
                        capturedWs.span,
                    )
                    val newTarget = PlacementTarget.WorkspaceTarget(pageRef, cell, unit.span)
                    val isChanged = newTarget != capturedTarget
                    val disposition = if (isChanged) {
                        Disposition.Moved(
                            if (unit.isFolder) PlacementCode.FOLDER_UNIT else PlacementCode.SINGLE_PLACEMENT,
                        )
                    } else {
                        Disposition.Preserved(PreserveReason.ALREADY_CANONICAL)
                    }
                    placements += PlannedPlacement(item.id, disposition, newTarget)
                }
            }
        }

        appendPreservedPlacements(context, placements)

        val sortedPlacements = placements.sortedBy { it.item }
        val sortedNewFolders = outputNewFolders.sortedBy { it.ordinal }

        return PlacementOutput(
            placements = sortedPlacements,
            newPages = allocator.buildNewPages(),
            newFolders = sortedNewFolders,
            preservationWarnings = context.preservationWarnings,
        )
    }
}
