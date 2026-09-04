package app.lawnchair.organizer.planning

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class DeterministicOrganizationPlannerTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val p0 = ProfileId("p0")
    private val p1 = ProfileId("p1")

    private fun defaultDevice(
        columns: Int = 4,
        rows: Int = 4,
        hotseatSlots: Int = 4,
        folderMaxColumns: Int = 4,
        folderMaxRows: Int = 4,
        orientation: Orientation = Orientation.PORTRAIT,
    ) = DeviceCapabilities(columns, rows, hotseatSlots, folderMaxColumns, folderMaxRows, orientation)

    private fun defaultRules(minGroupSize: Int = 2) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(minGroupSize, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
    )

    private fun defaultTaxonomy(
        allowed: List<CategoryId> = listOf(CategoryId("OTHER"), CategoryId("GAMES"), CategoryId("TOOLS")),
        fallback: CategoryId = CategoryId("OTHER"),
    ) = TaxonomyContract(TaxonomyVersion("tv1"), allowed, fallback)

    private fun app(
        id: String,
        profile: ProfileId = p0,
        x: Int = 0,
        y: Int = 0,
        spanW: Int = 1,
        spanH: Int = 1,
        page: String = "p0",
        locked: Boolean = false,
        available: Availability = Availability.AVAILABLE,
    ) = CapturedItem(
        id = ItemId(id),
        profile = profile,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), profile),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = locked,
        availability = available,
    )

    private fun dockApp(id: String, rank: Int, profile: ProfileId = p0, locked: Boolean = false) = CapturedItem(
        id = ItemId(id),
        profile = profile,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), profile),
        placement = CapturedPlacement.Dock(rank),
        locked = locked,
        availability = Availability.AVAILABLE,
    )

    private fun widget(id: String, x: Int, y: Int, spanW: Int, spanH: Int, page: String = "p0") = CapturedItem(
        id = ItemId(id),
        profile = p0,
        kind = ItemKind.APPWIDGET,
        target = TargetKey.WidgetKey(ComponentKey("com.example.$id"), AppWidgetId(1), p0),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(spanW, spanH)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun apps(n: Int, prefix: String = "app", startX: Int = 0, startY: Int = 0, columns: Int = 4): List<CapturedItem> = (0 until n).map { app("$prefix$it", x = startX + it % columns, y = startY + it / columns) }

    @Test
    fun catalogExternalStrategyIsRejectedInvalidThroughTheSeam() {
        // Spec 182 failure layering: a direct planner-seam caller with a
        // catalog-external StrategyId receives a typed V-20 Rejected.Invalid —
        // never a fallback strategy and never a thrown exception.
        val input = fullInput(
            items = listOf(app("a")),
            rules = defaultRules().copy(organizationStrategy = StrategyId("REMOVED_STRATEGY_V1")),
        )

        val result = planner.plan(input)

        val outcome = result.outcome as Rejected.Invalid
        assertTrue(outcome.reasons.any { it.code == RejectionCode.INVALID_RULES })
        assertEquals(StrategyId("REMOVED_STRATEGY_V1"), result.organizationStrategy)
    }

    private fun fullInput(
        items: List<CapturedItem>,
        device: DeviceCapabilities = defaultDevice(),
        rules: RuleSemantics = defaultRules(),
        taxonomy: TaxonomyContract = defaultTaxonomy(),
        signals: List<ClassificationSignal> = emptyList(),
        pages: List<Page> = listOf(Page(PageId("p0"), PageOrder(0))),
        reservations: List<ReservedWorkspaceRegion> = emptyList(),
    ): OrganizationInput {
        val existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }
        return OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev"), device, pages, items, reservations),
            rules = rules,
            taxonomy = taxonomy,
            signals = ClassificationSignals(signals),
            targets = TargetSet(existing, emptyList()),
            runMode = RunMode.FullOrganization,
        )
    }

    private fun incrementalInput(
        captured: List<CapturedItem>,
        additions: List<CandidateItem>,
        device: DeviceCapabilities = defaultDevice(),
        rules: RuleSemantics = defaultRules(),
        taxonomy: TaxonomyContract = defaultTaxonomy(),
        signals: List<ClassificationSignal> = emptyList(),
        pages: List<Page> = listOf(Page(PageId("p0"), PageOrder(0))),
        existingRoles: List<ExistingTargetMembership>? = null,
    ): OrganizationInput {
        val existing = existingRoles ?: captured.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }
        return OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev"), device, pages, captured),
            rules = rules,
            taxonomy = taxonomy,
            signals = ClassificationSignals(signals),
            targets = TargetSet(existing, additions),
            runMode = RunMode.IncrementalPlacement,
        )
    }

    private fun candidate(
        id: String,
        profile: ProfileId = p0,
        kind: CandidateKind = CandidateKind.APPLICATION,
        available: Availability = Availability.AVAILABLE,
        spanW: Int = 1,
        spanH: Int = 1,
    ) = CandidateItem(
        id = ItemId(id),
        profile = profile,
        kind = kind,
        target = if (kind == CandidateKind.APPLICATION) {
            CandidateTarget.AppKey(ComponentKey("com.example.$id"), profile)
        } else {
            CandidateTarget.ShortcutKey(PackageName("com.example.$id"), ShortcutId("sc"), profile)
        },
        availability = available,
        span = GridSpan(spanW, spanH),
    )

    @Test
    fun emptyHomeProducesEmptyPlannedResult() {
        val input = fullInput(items = emptyList())
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(0, planned.placements.size)
        assertEquals(0, planned.newPages.size)
        assertEquals(0, planned.newFolders.size)
        assertEquals(0, planned.categories.size)
        assertEquals(0, planned.warnings.size)
    }

    @Test
    fun qsbReservationPreventsFullOrganizationFromTargetingFirstScreenCells() {
        val reservation = ReservedWorkspaceRegion(
            PageRef(PageId("p0")),
            GridCell(0, 0),
            GridSpan(4, 1),
        )
        val input = fullInput(
            items = listOf(app("folder", x = 0, y = 1)),
            device = defaultDevice(columns = 4, rows = 5),
            reservations = listOf(reservation),
        )

        val planned = planner.plan(input).outcome as Planned
        val target = planned.placements.single().target as PlacementTarget.WorkspaceTarget

        assertEquals(reservation.page, target.page)
        assertTrue(target.cell.y >= reservation.cell.y + reservation.span.height)
        assertEquals(GridCell(0, 1), target.cell)
    }

    @Test
    fun reservationOverlapIsPreservedInPlaceInsteadOfRejected() {
        // Issue #185 / ADR-0010: a captured item overlapping the QSB reservation
        // is representable — it is preserved exactly where it was captured and
        // the reservation cells stay untargetable.
        val reserved = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(4, 1))
        val overlap = fullInput(items = listOf(app("overlap", x = 2, y = 0)), reservations = listOf(reserved))

        val planned = planner.plan(overlap).outcome as Planned
        val placement = planned.placements.single()

        assertEquals(Disposition.Preserved(PreserveReason.RESERVED_REGION), placement.disposition)
        val target = placement.target as PlacementTarget.WorkspaceTarget
        assertEquals(PageRef(PageId("p0")), target.page)
        assertEquals(GridCell(2, 0), target.cell)
    }

    @Test
    fun reservationAndReservationOverlapIsStillRejected() {
        val first = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(2, 1))
        val second = ReservedWorkspaceRegion(PageRef(PageId("p0")), GridCell(1, 0), GridSpan(2, 1))
        val input = fullInput(items = emptyList(), reservations = listOf(first, second))
        assertTrue(
            (planner.plan(input).outcome as Rejected.Invalid).reasons.contains(
                RejectionReason(RejectionCode.OVERLAP, emptyList()),
            ),
        )
    }

    @Test
    fun unknownPageReservationIsRejected() {
        val unknown = fullInput(
            items = emptyList(),
            reservations = listOf(ReservedWorkspaceRegion(PageRef(PageId("unknown")), GridCell(0, 0), GridSpan(1, 1))),
        )
        assertTrue(
            (planner.plan(unknown).outcome as Rejected.Invalid).reasons.contains(
                RejectionReason(RejectionCode.UNKNOWN_PAGE, listOf(DiagnosticParam.PageParam(PageId("unknown")))),
            ),
        )
    }

    @Test
    fun categoryPrioritySourceWinsOverLower() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S3, CategoryId("TOOLS")),
            ClassificationSignal(ItemId("a"), SignalSource.S1, CategoryId("GAMES")),
        )
        val input = fullInput(items, signals = signals)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        val decision = planned.categories.single { it.item == ItemId("a") }
        assertEquals(SignalSource.S1, decision.decidedSignal)
        assertEquals(CategoryId("GAMES"), decision.category)
        assertEquals(Confidence.EXPLICIT, decision.confidence)
    }

    @Test
    fun sameSourceSmallestCategoryWins() {
        val items = listOf(app("a"))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S3, CategoryId("TOOLS")),
            ClassificationSignal(ItemId("a"), SignalSource.S3, CategoryId("GAMES")),
        )
        val input = fullInput(items, signals = signals)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        val decision = planned.categories.single { it.item == ItemId("a") }
        assertEquals(CategoryId("GAMES"), decision.category)
        assertEquals(SignalSource.S3, decision.decidedSignal)
        assertEquals(Confidence.RULE, decision.confidence)
    }

    @Test
    fun noSignalProducesFallbackAndWarning() {
        val items = listOf(app("a"))
        val input = fullInput(items)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        val decision = planned.categories.single()
        assertEquals(CategoryId("OTHER"), decision.category)
        assertEquals(SignalSource.S6, decision.decidedSignal)
        assertEquals(Confidence.FALLBACK, decision.confidence)
        assertTrue(planned.warnings.any { it.code == WarningCode.FALLBACK_CATEGORY })
    }

    @Test
    fun s5FallbackConfidenceDoesNotEmitS6FallbackWarning() {
        val input = fullInput(
            items = listOf(app("a")),
            signals = listOf(
                ClassificationSignal(ItemId("a"), SignalSource.S5, CategoryId("TOOLS")),
            ),
        )

        val planned = planner.plan(input).outcome as Planned

        assertEquals(
            CategoryDecision(ItemId("a"), CategoryId("TOOLS"), SignalSource.S5, Confidence.FALLBACK),
            planned.categories.single(),
        )
        assertFalse(planned.warnings.any { it.code == WarningCode.FALLBACK_CATEGORY })
    }

    @Test
    fun alreadyCanonicalForUnchangedMovableItem() {
        val items = listOf(app("a", x = 0, y = 0))
        val input = fullInput(items)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        val placement = planned.placements.single()
        assertEquals(Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), placement.disposition)
    }

    @Test
    fun movedWhenReallocatedToDifferentCell() {
        val items = listOf(
            app("a", x = 2, y = 0),
            app("b", x = 0, y = 0),
        )
        val input = fullInput(items)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        val placementA = planned.placements.single { it.item == ItemId("a") }
        val placementB = planned.placements.single { it.item == ItemId("b") }
        assertEquals(Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), placementB.disposition)
    }

    @Test
    fun twoAppsSameCategoryFormFolder() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S3, CategoryId("GAMES")),
            ClassificationSignal(ItemId("b"), SignalSource.S3, CategoryId("GAMES")),
        )
        val input = fullInput(items, signals = signals)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(1, planned.newFolders.size)
        val folder = planned.newFolders.single()
        assertEquals(NewFolderOrdinal(0), folder.ordinal)
        assertEquals(listOf(ItemId("a"), ItemId("b")), folder.members)
        val placementA = planned.placements.single { it.item == ItemId("a") }
        val placementB = planned.placements.single { it.item == ItemId("b") }
        assertEquals(Disposition.Moved(PlacementCode.FOLDER_MEMBER), placementA.disposition)
        assertEquals(Disposition.Moved(PlacementCode.FOLDER_MEMBER), placementB.disposition)
    }

    @Test
    fun fallbackCategoryStaysSingleton() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val input = fullInput(items)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(0, planned.newFolders.size)
    }

    @Test
    fun folderCapacity65536x65536DoesNotOverflow() {
        val device = defaultDevice(folderMaxColumns = 65536, folderMaxRows = 65536)
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0), app("c", x = 2, y = 0))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S3, CategoryId("GAMES")),
            ClassificationSignal(ItemId("b"), SignalSource.S3, CategoryId("GAMES")),
            ClassificationSignal(ItemId("c"), SignalSource.S3, CategoryId("GAMES")),
        )
        val input = fullInput(items, device = device, signals = signals)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(1, planned.newFolders.size)
        assertEquals(3, planned.newFolders.single().members.size)
    }

    @Test
    fun folderPartitionBoundaryMatrix() {
        fun plannedFor(
            count: Int,
            minGroupSize: Int,
            folderColumns: Int = 4,
            folderRows: Int = 4,
        ): Planned {
            val items = (0 until count).map { index ->
                app("item.$index", x = index % 4, y = index / 4)
            }
            val signals = items.map {
                ClassificationSignal(it.id, SignalSource.S3, CategoryId("GAMES"))
            }
            return planner.plan(
                fullInput(
                    items = items,
                    device = defaultDevice(
                        rows = 4,
                        folderMaxColumns = folderColumns,
                        folderMaxRows = folderRows,
                    ),
                    rules = defaultRules(minGroupSize),
                    signals = signals,
                ),
            ).outcome as Planned
        }

        assertEquals(emptyList<NewFolder>(), plannedFor(count = 2, minGroupSize = 3).newFolders)
        assertEquals(listOf(3), plannedFor(count = 3, minGroupSize = 3).newFolders.map { it.members.size })
        assertEquals(listOf(4), plannedFor(count = 4, minGroupSize = 3).newFolders.map { it.members.size })
        assertEquals(
            listOf(4, 4),
            plannedFor(count = 8, minGroupSize = 3, folderColumns = 2, folderRows = 2)
                .newFolders.map { it.members.size },
        )
        assertEquals(
            listOf(3, 3),
            plannedFor(count = 6, minGroupSize = 3, folderColumns = 2, folderRows = 2)
                .newFolders.map { it.members.size },
        )
        val nonRedistributable = plannedFor(
            count = 5,
            minGroupSize = 3,
            folderColumns = 3,
            folderRows = 1,
        )
        assertEquals(listOf(3), nonRedistributable.newFolders.map { it.members.size })
        assertEquals(
            2,
            nonRedistributable.placements.count {
                when (val disposition = it.disposition) {
                    is Disposition.Moved -> disposition.rationale == PlacementCode.SINGLE_PLACEMENT
                    is Disposition.Preserved -> true
                }
            },
        )
        nonRedistributable.newFolders.single().members.forEachIndexed { rank, itemId ->
            assertEquals(
                PlacementTarget.FolderMember(NewFolderRef(NewFolderOrdinal(0)), rank),
                nonRedistributable.placements.single { it.item == itemId }.target,
            )
        }
    }

    @Test
    fun fullGridReordersInRowMajorByItemId() {
        val items = (0 until 16).map { i ->
            app("$i", x = i % 4, y = i / 4)
        }
        val input = fullInput(items)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(0, planned.newPages.size)
        val wsPlacements = planned.placements.mapNotNull {
            val ws = (it.target as? PlacementTarget.WorkspaceTarget) ?: return@mapNotNull null
            it.item.value to ws.cell
        }
        val expected = listOf(
            "0" to GridCell(0, 0), "1" to GridCell(1, 0),
            "10" to GridCell(2, 0), "11" to GridCell(3, 0),
            "12" to GridCell(0, 1), "13" to GridCell(1, 1),
            "14" to GridCell(2, 1), "15" to GridCell(3, 1),
            "2" to GridCell(0, 2), "3" to GridCell(1, 2),
            "4" to GridCell(2, 2), "5" to GridCell(3, 2),
            "6" to GridCell(0, 3), "7" to GridCell(1, 3),
            "8" to GridCell(2, 3), "9" to GridCell(3, 3),
        )
        assertEquals(expected, wsPlacements)
    }

    @Test
    fun pageOverflowCreatesNewPage() {
        val device = defaultDevice(columns = 4, rows = 4)
        val lockedItems = (0 until 16).map { i ->
            app("locked$i", x = i % 4, y = i / 4, locked = true)
        }
        val addition = candidate("new.app")
        val input = incrementalInput(
            captured = lockedItems,
            additions = listOf(addition),
            device = device,
        )
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(1, planned.newPages.size)
        val newPage = planned.newPages.single()
        assertEquals(NewPageOrdinal(0), newPage.ordinal)
        assertEquals(PageOrder(1), newPage.order)
    }

    @Test
    fun fullAllocationFailureCannotBecomePartialPlannedResult() {
        val failingPlanner: OrganizationPlanner = DeterministicOrganizationPlanner(
            allocationFault = AllocationFault.FAIL_ALLOCATION,
        )

        assertThrows(IllegalStateException::class.java) {
            failingPlanner.plan(fullInput(listOf(app("movable"))))
        }
    }

    @Test
    fun incrementalAllocationFailureCannotBecomePartialPlannedResult() {
        val failingPlanner: OrganizationPlanner = DeterministicOrganizationPlanner(
            allocationFault = AllocationFault.FAIL_ALLOCATION,
        )

        assertThrows(IllegalStateException::class.java) {
            failingPlanner.plan(
                incrementalInput(
                    captured = listOf(app("existing", locked = true)),
                    additions = listOf(candidate("new.app")),
                ),
            )
        }
    }

    @Test
    fun pageOverflowWithNoCapturedPagesStartsAtOrderZero() {
        val add = candidate("new.app")
        val input = incrementalInput(
            captured = emptyList(),
            additions = listOf(add),
            pages = emptyList(),
        )
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(1, planned.newPages.size)
        assertEquals(PageOrder(0), planned.newPages.single().order)
    }

    @Test
    fun incrementalModePreservesAllCapturedTargets() {
        val captured = listOf(app("existing", x = 0, y = 0))
        val addition = candidate("new.app")
        val input = incrementalInput(captured = captured, additions = listOf(addition))
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        val existingPlacement = planned.placements.single { it.item == ItemId("existing") }
        assertEquals(Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), existingPlacement.disposition)
        val newPlacement = planned.placements.single { it.item == ItemId("new.app") }
        assertEquals(Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), newPlacement.disposition)
    }

    @Test
    fun oversizedCandidateProducesImpossible() {
        val captured = listOf(app("existing", x = 0, y = 0))
        val addition = candidate("big", spanW = 10, spanH = 10)
        val input = incrementalInput(captured = captured, additions = listOf(addition))
        val result = planner.plan(input)
        val impossible = result.outcome as Rejected.Impossible
        assertEquals(1, impossible.unplaced.size)
        assertEquals(UnplacedReason.EXCEEDS_GRID_DIMENSIONS, impossible.unplaced.single().reason)
    }

    @Test
    fun unavailableCandidateProducesImpossible() {
        val captured = listOf(app("existing", x = 0, y = 0))
        val addition = candidate("unavail", available = Availability.DISABLED)
        val input = incrementalInput(captured = captured, additions = listOf(addition))
        val result = planner.plan(input)
        val impossible = result.outcome as Rejected.Impossible
        assertEquals(UnplacedReason.TARGET_UNAVAILABLE, impossible.unplaced.single().reason)
    }

    @Test
    fun validationUsesCanonicalDiagnosticParams() {
        val outOfBounds = fullInput(listOf(app("bounds", x = 3, spanW = 2)))
        assertEquals(
            RejectionReason(RejectionCode.BOUNDS_VIOLATION, listOf(DiagnosticParam.SpanParam(GridSpan(2, 1)))),
            (planner.plan(outOfBounds).outcome as Rejected.Invalid).reasons.single(),
        )

        val invalidSpan = fullInput(listOf(app("span", spanW = 0)))
        assertEquals(
            RejectionReason(RejectionCode.INVALID_DIMENSIONS, listOf(DiagnosticParam.SpanParam(GridSpan(0, 1)))),
            (planner.plan(invalidSpan).outcome as Rejected.Invalid).reasons.single(),
        )

        val lockedOutOfBounds = fullInput(listOf(app("locked", x = 3, spanW = 2, locked = true)))
        assertEquals(
            RejectionReason(RejectionCode.LOCKED_OUT_OF_BOUNDS, listOf(DiagnosticParam.SpanParam(GridSpan(2, 1)))),
            (planner.plan(lockedOutOfBounds).outcome as Rejected.Invalid).reasons.single(),
        )

        val duplicateOrder = fullInput(
            items = emptyList(),
            pages = listOf(Page(PageId("a"), PageOrder(0)), Page(PageId("b"), PageOrder(0))),
        )
        assertEquals(
            listOf(
                RejectionReason(RejectionCode.DUPLICATE_PAGE, listOf(DiagnosticParam.PageParam(PageId("a")))),
                RejectionReason(RejectionCode.DUPLICATE_PAGE, listOf(DiagnosticParam.PageParam(PageId("b")))),
            ),
            (planner.plan(duplicateOrder).outcome as Rejected.Invalid).reasons,
        )
    }

    @Test
    fun overlapAndDanglingReferenceUseTheirAcceptedParamShapes() {
        val overlap = fullInput(listOf(app("a", x = 0), app("b", x = 0)))
        assertEquals(
            listOf(RejectionReason(RejectionCode.OVERLAP, emptyList())),
            (planner.plan(overlap).outcome as Rejected.Invalid).reasons,
        )

        val dangling = fullInput(
            listOf(
                app("child").copy(
                    placement = CapturedPlacement.FolderMember(FolderRef(FolderId("missing")), 0),
                ),
            ),
        )
        assertEquals(
            listOf(
                RejectionReason(
                    RejectionCode.DANGLING_REFERENCE,
                    listOf(DiagnosticParam.ItemParam(ItemId("child"))),
                ),
            ),
            (planner.plan(dangling).outcome as Rejected.Invalid).reasons,
        )
    }

    @Test
    fun partialTargetPartitionIsRejected() {
        val items = listOf(app("included", x = 0), app("omitted", x = 1))
        val input = fullInput(items).copy(
            targets = TargetSet(
                existing = listOf(ExistingTargetMembership(ItemId("included"), ExistingRole.Movable)),
                additions = emptyList(),
            ),
        )

        assertTrue(
            (planner.plan(input).outcome as Rejected.Invalid).reasons.any {
                it == RejectionReason(RejectionCode.INCOMPLETE_TARGET_PARTITION, emptyList())
            },
        )
    }

    @Test
    fun coordinateOverflowIsRejectedWithoutThrowing() {
        val unlocked = fullInput(listOf(app("overflow", x = Int.MAX_VALUE, spanW = 2)))
        assertTrue(
            (planner.plan(unlocked).outcome as Rejected.Invalid).reasons.any {
                it.code == RejectionCode.BOUNDS_VIOLATION
            },
        )

        val locked = fullInput(listOf(app("locked-overflow", x = Int.MAX_VALUE, spanW = 2, locked = true)))
        assertTrue(
            (planner.plan(locked).outcome as Rejected.Invalid).reasons.any {
                it.code == RejectionCode.LOCKED_OUT_OF_BOUNDS
            },
        )
    }

    @Test
    fun invalidAndDuplicateContainerRanksAreRejected() {
        val duplicateDock = fullInput(listOf(dockApp("a", 0), dockApp("b", 0)))
        assertTrue(
            (planner.plan(duplicateDock).outcome as Rejected.Invalid).reasons.contains(
                RejectionReason(RejectionCode.OVERLAP, emptyList()),
            ),
        )

        val folderId = FolderId("folder")
        val parent = CapturedItem(
            id = ItemId("folder"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(folderId),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = folderId,
            members = listOf(ItemId("a"), ItemId("b")),
        )
        val children = listOf("a", "b").map { id ->
            app(id).copy(placement = CapturedPlacement.FolderMember(FolderRef(folderId), -1))
        }
        val reasons = (planner.plan(fullInput(listOf(parent) + children)).outcome as Rejected.Invalid).reasons
        assertTrue(
            reasons.contains(
                RejectionReason(RejectionCode.BOUNDS_VIOLATION, listOf(DiagnosticParam.RankParam(-1))),
            ),
        )
        assertTrue(reasons.contains(RejectionReason(RejectionCode.OVERLAP, emptyList())))
    }

    @Test
    fun appPairValidationIsTotalAndBidirectional() {
        val pairId = AppPairId("pair")
        val missingIdentity = CapturedItem(
            id = ItemId("missing-id"),
            profile = p0,
            kind = ItemKind.APP_PAIR,
            target = TargetKey.AppPairKey(pairId),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            appPair = AppPairMetadata(emptyList()),
        )
        val missingIdentityReasons =
            (planner.plan(fullInput(listOf(missingIdentity))).outcome as Rejected.Invalid).reasons
        assertTrue(missingIdentityReasons.any { it.code == RejectionCode.KIND_TARGET_MISMATCH })
        assertTrue(missingIdentityReasons.any { it.code == RejectionCode.MALFORMED_APP_PAIR })

        fun pairChild(id: String) = app(id).copy(
            placement = CapturedPlacement.AppPairMember(AppPairRef(pairId)),
        )
        val parent = CapturedItem(
            id = ItemId("parent"),
            profile = p0,
            kind = ItemKind.APP_PAIR,
            target = TargetKey.AppPairKey(pairId),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            appPairId = pairId,
            appPair = AppPairMetadata(
                listOf(
                    AppPairMember(ItemId("a"), SplitStage.TOP_OR_LEFT, SnapPositionToken("snap")),
                    AppPairMember(ItemId("b"), SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("snap")),
                ),
            ),
        )
        val reverseMismatch = fullInput(listOf(parent, pairChild("a"), pairChild("b"), pairChild("c")))
        val mismatchReasons = (planner.plan(reverseMismatch).outcome as Rejected.Invalid).reasons
        assertFalse(mismatchReasons.any { it.code == RejectionCode.DANGLING_REFERENCE })
        assertTrue(
            mismatchReasons.contains(
                RejectionReason(
                    RejectionCode.MALFORMED_APP_PAIR,
                    listOf(DiagnosticParam.ItemParam(ItemId("parent"))),
                ),
            ),
        )
    }

    @Test
    fun emptyFolderIsAValidIntactWorkspaceUnit() {
        val folderId = FolderId("empty")
        val folder = CapturedItem(
            id = ItemId("empty-folder"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(folderId),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = folderId,
            members = emptyList(),
        )

        val planned = planner.plan(fullInput(listOf(folder))).outcome as Planned

        assertEquals(ItemId("empty-folder"), planned.placements.single().item)
        assertEquals(0, planned.newFolders.size)
    }

    @Test
    fun multiDefectRejectionOrderedByCodeOrdinal() {
        val items = listOf(
            CapturedItem(
                id = ItemId("dup"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.dup"), p0),
                placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("dup"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.dup2"), p0),
                placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(1, 0), GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev"), defaultDevice(), listOf(Page(PageId("p0"), PageOrder(0))), items),
            rules = defaultRules(),
            taxonomy = defaultTaxonomy(),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(
                listOf(
                    ExistingTargetMembership(ItemId("dup"), ExistingRole.Movable),
                    ExistingTargetMembership(ItemId("missing"), ExistingRole.Movable),
                ),
                emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        val result = planner.plan(input)
        val invalid = result.outcome as Rejected.Invalid
        val codes = invalid.reasons.map { it.code }
        assertTrue(codes.contains(RejectionCode.DUPLICATE_ITEM_ID))
        assertTrue(codes.contains(RejectionCode.MISSING_TARGET))
        val missingIndex = codes.indexOf(RejectionCode.MISSING_TARGET)
        val dupIndex = codes.indexOf(RejectionCode.DUPLICATE_ITEM_ID)
        assertTrue("MISSING_TARGET should precede DUPLICATE_ITEM_ID", missingIndex < dupIndex)
    }

    @Test
    fun sameInputProducesEqualResults() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S3, CategoryId("GAMES")),
            ClassificationSignal(ItemId("b"), SignalSource.S3, CategoryId("GAMES")),
        )
        val input = fullInput(items, signals = signals)
        val result1 = planner.plan(input)
        val result2 = planner.plan(input)
        assertEquals(result1, result2)
    }

    @Test
    fun localeAndTimezoneIndependent() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val input = fullInput(items)
        val defaultLocale = Locale.getDefault()
        val defaultTz = TimeZone.getDefault()
        val result1 = planner.plan(input)
        try {
            Locale.setDefault(Locale.JAPAN)
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Tokyo"))
            val result2 = planner.plan(input)
            assertEquals(result1, result2)
        } finally {
            Locale.setDefault(defaultLocale)
            TimeZone.setDefault(defaultTz)
        }
    }

    @Test
    fun threadIndependent() {
        val items = listOf(app("a", x = 0, y = 0), app("b", x = 1, y = 0))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S3, CategoryId("GAMES")),
            ClassificationSignal(ItemId("b"), SignalSource.S3, CategoryId("GAMES")),
        )
        val input = fullInput(items, signals = signals)
        val mainResult = planner.plan(input)
        var threadResult: PlanningResult? = null
        val thread = Thread { threadResult = planner.plan(input) }
        thread.start()
        thread.join()
        assertEquals(mainResult, threadResult)
    }

    @Test
    fun l13PortraitFourByFivePlacement() {
        val device = defaultDevice(columns = 4, rows = 5)
        val widgetItems = listOf(
            widget("w1", 0, 0, 2, 2),
            widget("w2", 0, 2, 4, 1),
        )
        val lockItem = app("locked", x = 3, y = 4, locked = true)
        val dockItems = listOf(dockApp("d0", 0), dockApp("d1", 1))
        val appIds = (0 until 10).map { "app$it" }
        val appItems = appIds.mapIndexed { index, id ->
            val cell = listOf(
                GridCell(2, 0), GridCell(3, 0), GridCell(2, 1), GridCell(3, 1),
                GridCell(0, 3), GridCell(1, 3), GridCell(2, 3), GridCell(3, 3),
                GridCell(0, 4), GridCell(1, 4),
            )[index]
            app(id, x = cell.x, y = cell.y)
        }
        val items = widgetItems + lockItem + dockItems + appItems
        val input = fullInput(items, device = device)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(0, planned.newPages.size)
        val expectedCells = appIds.sorted().map { id ->
            val ws = planned.placements.single { it.item == ItemId(id) }.target as PlacementTarget.WorkspaceTarget
            ws.cell
        }
        val expectedOrder = listOf(
            GridCell(2, 0), GridCell(3, 0), GridCell(2, 1), GridCell(3, 1),
            GridCell(0, 3), GridCell(1, 3), GridCell(2, 3), GridCell(3, 3),
            GridCell(0, 4), GridCell(1, 4),
        )
        assertEquals(expectedOrder, expectedCells)
    }

    @Test
    fun l14LandscapeFourByThreePlacement() {
        val device = defaultDevice(columns = 4, rows = 3, orientation = Orientation.LANDSCAPE)
        val captured = listOf(
            widget("w1", 0, 0, 2, 2),
            app("locked", x = 3, y = 2, locked = true),
            dockApp("d0", 0),
            dockApp("d1", 1),
        )
        val additions = (0 until 10).map { candidate("app$it") }

        val planned = planner.plan(
            incrementalInput(captured = captured, additions = additions, device = device),
        ).outcome as Planned

        val sortedIds = additions.map { it.id }.sorted()
        val actualTargets = sortedIds.map { id ->
            planned.placements.single { it.item == id }.target as PlacementTarget.WorkspaceTarget
        }
        assertEquals(
            listOf(
                GridCell(2, 0),
                GridCell(3, 0),
                GridCell(2, 1),
                GridCell(3, 1),
                GridCell(0, 2),
                GridCell(1, 2),
                GridCell(2, 2),
            ),
            actualTargets.take(7).map { it.cell },
        )
        assertTrue(actualTargets.take(7).all { it.page == PageRef(PageId("p0")) })
        assertEquals(1, planned.newPages.size)
        assertEquals(NewPageOrdinal(0), planned.newPages.single().ordinal)
        assertEquals(
            listOf(GridCell(0, 0), GridCell(1, 0), GridCell(2, 0)),
            actualTargets.drop(7).map { it.cell },
        )
        assertTrue(actualTargets.drop(7).all { it.page == NewPageRef(NewPageOrdinal(0)) })
    }

    @Test
    fun l15TabletSixByFivePlacement() {
        val device = defaultDevice(columns = 6, rows = 5, hotseatSlots = 6)
        val dockItems = (0..3).map { dockApp("d$it", it) }
        val appItems = (0 until 20).map { app("app$it", x = it % 6, y = it / 6) }
        val items = dockItems + appItems
        val input = fullInput(items, device = device)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(0, planned.newPages.size)
        val appIds = (0 until 20).map { ItemId("app$it") }.sorted()
        val actualCells = appIds.map { id ->
            val ws = planned.placements.single { it.item == id }.target as PlacementTarget.WorkspaceTarget
            ws.cell
        }
        val expectedCells = (0 until 20).map { GridCell(it % 6, it / 6) }
        assertEquals(expectedCells, actualCells)
    }

    @Test
    fun l16FolderPlacement() {
        val device = defaultDevice(columns = 4, rows = 5)
        val gamesSignals = (0..2).map {
            ClassificationSignal(ItemId("g$it"), SignalSource.S3, CategoryId("GAMES"))
        }
        val toolsSignals = (0..4).map {
            ClassificationSignal(ItemId("t$it"), SignalSource.S3, CategoryId("TOOLS"))
        }
        val gamesApps = listOf(app("g0", x = 0, y = 0), app("g1", x = 1, y = 0), app("g2", x = 2, y = 0))
        val toolsApps = listOf(
            app("t0", x = 0, y = 1),
            app("t1", x = 1, y = 1),
            app("t2", x = 2, y = 1),
            app("t3", x = 3, y = 1),
            app("t4", x = 0, y = 2),
        )
        val fallbackApps = listOf(
            app("f0", x = 1, y = 2),
            app("f1", x = 2, y = 2),
            app("f2", x = 3, y = 2),
            app("f3", x = 0, y = 3),
            app("f4", x = 1, y = 3),
            app("f5", x = 2, y = 3),
            app("f6", x = 3, y = 3),
        )
        val items = gamesApps + toolsApps + fallbackApps
        val input = fullInput(items, device = device, signals = gamesSignals + toolsSignals)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(2, planned.newFolders.size)
        val folder0 = planned.newFolders[0]
        val folder1 = planned.newFolders[1]
        assertEquals(3, folder0.members.size)
        assertEquals(5, folder1.members.size)
        assertEquals(GridCell(0, 0), folder0.workspacePlacement.cell)
        assertEquals(GridCell(1, 0), folder1.workspacePlacement.cell)
        val fallbackIds = (0..6).map { ItemId("f$it") }.sorted()
        val expectedSingletonCells = listOf(
            GridCell(2, 0),
            GridCell(3, 0),
            GridCell(0, 1),
            GridCell(1, 1),
            GridCell(2, 1),
            GridCell(3, 1),
            GridCell(0, 2),
        )
        val actualSingletonCells = fallbackIds.map { id ->
            val ws = planned.placements.single { it.item == id }.target as PlacementTarget.WorkspaceTarget
            ws.cell
        }
        assertEquals(expectedSingletonCells, actualSingletonCells)
    }

    @Test
    fun l17WidgetAndLockPlacement() {
        val device = defaultDevice(columns = 5, rows = 5, hotseatSlots = 5)
        val widgetItem = widget("w1", 0, 0, 3, 2)
        val lockItem = app("locked", x = 4, y = 4, locked = true)
        val dockItems = listOf(dockApp("d0", 0), dockApp("d1", 1), dockApp("d2", 2))
        val appIds = (0 until 8).map { "app$it" }
        val appCells = listOf(
            GridCell(3, 0),
            GridCell(4, 0),
            GridCell(3, 1),
            GridCell(4, 1),
            GridCell(0, 2),
            GridCell(1, 2),
            GridCell(2, 2),
            GridCell(3, 2),
        )
        val appItems = appCells.mapIndexed { index, cell ->
            app("app$index", x = cell.x, y = cell.y)
        }
        val items = listOf(widgetItem, lockItem) + dockItems + appItems
        val input = fullInput(items, device = device)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(0, planned.newPages.size)
        val sortedIds = appIds.sorted()
        val expectedCells = listOf(
            GridCell(3, 0),
            GridCell(4, 0),
            GridCell(3, 1),
            GridCell(4, 1),
            GridCell(0, 2),
            GridCell(1, 2),
            GridCell(2, 2),
            GridCell(3, 2),
        )
        val actualCells = sortedIds.map { id ->
            val ws = planned.placements.single { it.item == ItemId(id) }.target as PlacementTarget.WorkspaceTarget
            ws.cell
        }
        assertEquals(expectedCells, actualCells)
    }

    @Test
    fun sparseAllocationMatchesExhaustiveSearch() {
        val device = defaultDevice(columns = 5, rows = 5)
        val widgetItem = widget("w1", 1, 0, 2, 3)
        val lockItem = app("locked", x = 0, y = 4, locked = true)
        val freeCells = listOf(
            GridCell(0, 0), GridCell(3, 0), GridCell(4, 0),
            GridCell(0, 1), GridCell(3, 1), GridCell(4, 1),
            GridCell(0, 2), GridCell(3, 2), GridCell(4, 2),
            GridCell(0, 3), GridCell(3, 3), GridCell(4, 3),
            GridCell(1, 4), GridCell(2, 4), GridCell(3, 4), GridCell(4, 4),
        )
        val appItems = freeCells.take(10).mapIndexed { index, cell ->
            app("app$index", x = cell.x, y = cell.y)
        }
        val items = listOf(widgetItem, lockItem) + appItems
        val input = fullInput(items, device = device)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        val sortedAppIds = appItems.map { it.id }.sorted()
        val occupied = mutableSetOf<GridCell>()
        for (x in 1..2) for (y in 0..2) occupied += GridCell(x, y)
        occupied += GridCell(0, 4)
        val allocated = mutableSetOf<GridCell>()
        for (id in sortedAppIds) {
            val ws = planned.placements.single { it.item == id }.target as PlacementTarget.WorkspaceTarget
            assertFalse("Duplicate cell ${ws.cell}", ws.cell in allocated)
            assertFalse("Overlaps preserved ${ws.cell}", ws.cell in occupied)
            allocated += ws.cell
            assertTrue("Out of bounds ${ws.cell}", ws.cell.x in 0 until 5 && ws.cell.y in 0 until 5)
        }
        assertEquals(10, allocated.size)
        val exhaustiveExpected = (0 until 5).flatMap { y -> (0 until 5).map { x -> GridCell(x, y) } }
            .filter { it !in occupied }
            .take(10)
            .toSet()
        assertEquals(exhaustiveExpected, allocated)
    }

    @Test
    fun sparseAllocationMatchesRowMajorForMultiCellSpans() {
        val items = listOf(
            widget("block", 1, 0, 1, 2),
            app("a", x = 0, y = 2, spanW = 2),
            app("b", x = 2, y = 2, spanW = 2),
        )

        val planned = planner.plan(fullInput(items)).outcome as Planned

        assertEquals(
            GridCell(2, 0),
            (planned.placements.single { it.item == ItemId("a") }.target as PlacementTarget.WorkspaceTarget).cell,
        )
        assertEquals(
            GridCell(2, 1),
            (planned.placements.single { it.item == ItemId("b") }.target as PlacementTarget.WorkspaceTarget).cell,
        )
    }

    @Test
    fun invalidSuppressesImpossible() {
        val captured = listOf(
            CapturedItem(
                id = ItemId("existing"),
                profile = p0,
                kind = ItemKind.Unknown(KindCode(99)),
                target = TargetKey.AppKey(ComponentKey("com.example"), p0),
                placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val addition = candidate("big", spanW = 10, spanH = 10)
        val input = incrementalInput(captured = captured, additions = listOf(addition))
        val result = planner.plan(input)
        assertTrue(result.outcome is Rejected.Invalid)
        assertFalse(result.outcome is Rejected.Impossible)
    }

    @Test
    fun additionsUnderFullOrganizationRejected() {
        val items = listOf(app("existing", x = 0, y = 0))
        val addition = candidate("new.app")
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(RevisionId("rev"), defaultDevice(), listOf(Page(PageId("p0"), PageOrder(0))), items),
            rules = defaultRules(),
            taxonomy = defaultTaxonomy(),
            signals = ClassificationSignals(emptyList()),
            targets = TargetSet(
                items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                listOf(addition),
            ),
            runMode = RunMode.FullOrganization,
        )
        val result = planner.plan(input)
        val invalid = result.outcome as Rejected.Invalid
        assertTrue(invalid.reasons.any { it.code == RejectionCode.ADDITIONS_UNDER_FULL_ORGANIZATION })
    }

    @Test
    fun profileIsolationPreventsCrossProfileFolder() {
        val items = listOf(
            app("personal", profile = p0, x = 0, y = 0),
            app("work", profile = p1, x = 1, y = 0),
        )
        val signals = listOf(
            ClassificationSignal(ItemId("personal"), SignalSource.S3, CategoryId("GAMES")),
            ClassificationSignal(ItemId("work"), SignalSource.S3, CategoryId("GAMES")),
        )
        val input = fullInput(items, signals = signals)
        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertEquals(0, planned.newFolders.size)
    }
}
