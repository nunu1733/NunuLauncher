package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.AppPairMember
import app.lawnchair.organizer.planning.AppPairMetadata
import app.lawnchair.organizer.planning.AppPairRef
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CandidateItem
import app.lawnchair.organizer.planning.CandidateKind
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.CategoryDecision
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ClassificationSignal
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.Confidence
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.DiagnosticParam
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.FolderRef
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.NewFolderRef
import app.lawnchair.organizer.planning.NewPage
import app.lawnchair.organizer.planning.NewPageOrdinal
import app.lawnchair.organizer.planning.NewPageRef
import app.lawnchair.organizer.planning.OrderingPolicy
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.OrganizationPlanner
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlannedPlacement
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.Rejected
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.RejectionReason
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.ShortcutId
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.SnapPositionToken
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.UnplacedItem
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.Warning
import app.lawnchair.organizer.planning.WarningCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class ScriptedPlanner(
    scripts: Map<OrganizationInput, List<PlanningResult>>,
) : OrganizationPlanner {

    private val originalScripts = scripts.mapValues { (_, results) ->
        ArrayList(results)
    }
    private val counters = mutableMapOf<OrganizationInput, Int>()
    var callCount = 0
        private set

    override fun plan(input: OrganizationInput): PlanningResult {
        callCount++
        val results = originalScripts[input]
            ?: throw AssertionError("ScriptedPlanner: no script for input $input")
        val idx = counters.getOrDefault(input, 0)
        val safeIdx = if (idx >= results.size) results.size - 1 else idx
        counters[input] = idx + 1
        return results[safeIdx]
    }
}

private val defaultDevice = DeviceCapabilities(
    columns = 4,
    rows = 4,
    hotseatSlots = 4,
    folderMaxColumns = 4,
    folderMaxRows = 4,
    orientation = Orientation.PORTRAIT,
)

private val defaultRules = RuleSemantics(
    version = RuleVersion("v1"),
    folderPolicy = FolderPolicy(minGroupSize = 2, newFolderProfileScope = NewFolderProfileScope.SAME_PROFILE_ONLY),
    dockPolicy = DockPolicy.PRESERVE,
    overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
    fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
    orderingPolicy = OrderingPolicy.CANONICAL_V1,
)

private val defaultTaxonomy = TaxonomyContract(
    version = TaxonomyVersion("tv1"),
    allowedCategories = listOf(CategoryId("OTHER"), CategoryId("GAMES"), CategoryId("TOOLS")),
    fallbackCategory = CategoryId("OTHER"),
)

private val p0 = ProfileId("p0")

private fun minimalInput(
    items: List<CapturedItem> = emptyList(),
    runMode: RunMode = RunMode.FullOrganization,
    additions: List<CandidateItem> = emptyList(),
    existing: List<ExistingTargetMembership> = emptyList(),
    signals: List<ClassificationSignal> = emptyList(),
    pages: List<Page> = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
    device: DeviceCapabilities = defaultDevice,
    taxonomy: TaxonomyContract = defaultTaxonomy,
): OrganizationInput {
    val resolvedExisting = if (existing.isEmpty() && items.isNotEmpty()) {
        items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }
    } else {
        existing
    }
    return OrganizationInput(
        snapshot = LayoutSnapshot(
            revision = RevisionId("test-rev"),
            device = device,
            pages = pages,
            items = items,
        ),
        rules = defaultRules,
        taxonomy = taxonomy,
        signals = ClassificationSignals(entries = signals),
        targets = TargetSet(existing = resolvedExisting, additions = additions),
        runMode = runMode,
    )
}

private fun plannedResult(
    placements: List<PlannedPlacement> = emptyList(),
    newPages: List<NewPage> = emptyList(),
    newFolders: List<app.lawnchair.organizer.planning.NewFolder> = emptyList(),
    categories: List<CategoryDecision> = emptyList(),
    warnings: List<Warning> = emptyList(),
): PlanningResult = PlanningResult(
    revision = RevisionId("test-rev"),
    ruleVersion = RuleVersion("v1"),
    taxonomyVersion = TaxonomyVersion("tv1"),
    outcome = Planned(
        placements = placements,
        newPages = newPages,
        newFolders = newFolders,
        categories = categories,
        warnings = warnings,
    ),
)

private fun invalidResult(
    reasons: List<RejectionReason> = emptyList(),
    warnings: List<Warning> = emptyList(),
): PlanningResult = PlanningResult(
    revision = RevisionId("test-rev"),
    ruleVersion = RuleVersion("v1"),
    taxonomyVersion = TaxonomyVersion("tv1"),
    outcome = Rejected.Invalid(reasons = reasons, warnings = warnings),
)

private fun impossibleResult(
    unplaced: List<UnplacedItem> = emptyList(),
    warnings: List<Warning> = emptyList(),
): PlanningResult = PlanningResult(
    revision = RevisionId("test-rev"),
    ruleVersion = RuleVersion("v1"),
    taxonomyVersion = TaxonomyVersion("tv1"),
    outcome = Rejected.Impossible(unplaced = unplaced, warnings = warnings),
)

private fun workspaceTarget(
    page: PageRef = PageRef(PageId("p0")),
    cell: GridCell = GridCell(0, 0),
    span: GridSpan = GridSpan(1, 1),
): PlacementTarget.WorkspaceTarget = PlacementTarget.WorkspaceTarget(page = page, cell = cell, span = span)

class PlannerContractHarnessTest {

    private val itemA = CapturedItem(
        id = ItemId("app.a"),
        profile = p0,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.a"), p0),
        placement = CapturedPlacement.Workspace(page = PageRef(PageId("p0")), cell = GridCell(0, 0), span = GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    @Test
    fun happyPathNoViolations() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("happy"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.BOUNDS, ContractCheck.NO_OVERLAP, ContractCheck.DETERMINISM),
        )

        val report = harness.verify(fixture)
        assertTrue("Expected success but got violations: ${report.violations}", report.isSuccess)
    }

    @Test
    fun expectationMismatchDetected() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Preserved(PreserveReason.LOCKED),
                    target = workspaceTarget(),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("expect-mismatch"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(
                    requiredPreservations = mapOf(itemA.id to PreserveReason.DOCK),
                ),
            ),
            checks = setOf(ContractCheck.EXPECTATION),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for expectation mismatch", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.EXPECTATION })
    }

    @Test
    fun conservationViolationDetected() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(placements = emptyList())
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("conservation-miss"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.CONSERVATION),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for conservation", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.CONSERVATION })
    }

    @Test
    fun boundsViolationDetected() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(
                        page = PageRef(PageId("p0")),
                        cell = GridCell(10, 10),
                        span = GridSpan(1, 1),
                    ),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("bounds-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.BOUNDS),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for bounds", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.BOUNDS })
    }

    @Test
    fun noOverlapViolationDetected() {
        val input = minimalInput(
            items = listOf(
                itemA,
                CapturedItem(
                    id = ItemId("app.b"),
                    profile = p0,
                    kind = ItemKind.APPLICATION,
                    target = TargetKey.AppKey(ComponentKey("com.example.b"), p0),
                    placement = CapturedPlacement.Workspace(page = PageRef(PageId("p0")), cell = GridCell(0, 0), span = GridSpan(1, 1)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
            ),
        )
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(0, 0)),
                ),
                PlannedPlacement(
                    item = ItemId("app.b"),
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(0, 0)),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("overlap-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.NO_OVERLAP),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for overlap", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.NO_OVERLAP })
    }

    @Test
    fun containerIntegrityViolationDetected() {
        val folderId = FolderId("f0")
        val memberItem = CapturedItem(
            id = ItemId("app.member"),
            profile = p0,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.member"), p0),
            placement = CapturedPlacement.FolderMember(folder = FolderRef(folderId), rank = 0),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val input = minimalInput(
            items = listOf(
                CapturedItem(
                    id = ItemId("app.folder"),
                    profile = p0,
                    kind = ItemKind.FOLDER,
                    target = TargetKey.FolderKey(folderId),
                    placement = CapturedPlacement.Workspace(page = PageRef(PageId("p0")), cell = GridCell(0, 0), span = GridSpan(2, 2)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                    folderId = folderId,
                    members = listOf(memberItem.id),
                ),
                memberItem,
            ),
        )
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = ItemId("app.folder"),
                    disposition = Disposition.Preserved(PreserveReason.STRUCTURAL),
                    target = workspaceTarget(cell = GridCell(0, 0), span = GridSpan(2, 2)),
                ),
                PlannedPlacement(
                    item = memberItem.id,
                    disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    target = PlacementTarget.FolderMember(
                        folder = NewFolderRef(NewFolderOrdinal(0)),
                        rank = 0,
                    ),
                ),
            ),
            newFolders = listOf(
                app.lawnchair.organizer.planning.NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = p0,
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        page = NewPageRef(NewPageOrdinal(0)),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                    members = emptyList(),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("container-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.CONTAINER_INTEGRITY),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for container integrity", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.CONTAINER_INTEGRITY })
        assertTrue(report.violations.any { it.message.contains("Existing-folder member has 0 matching placements") })
    }

    @Test
    fun containerIntegrityAcceptsAppPairMembershipFromMetadata() {
        val pairId = AppPairId("pair-0")
        val first = itemA.copy(
            id = ItemId("app.pair.first"),
            placement = CapturedPlacement.AppPairMember(AppPairRef(pairId)),
        )
        val second = itemA.copy(
            id = ItemId("app.pair.second"),
            target = TargetKey.AppKey(ComponentKey("com.example.second"), p0),
            placement = CapturedPlacement.AppPairMember(AppPairRef(pairId)),
        )
        val pair = CapturedItem(
            id = ItemId("pair.parent"),
            profile = p0,
            kind = ItemKind.APP_PAIR,
            target = TargetKey.AppPairKey(pairId),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            appPairId = pairId,
            appPair = AppPairMetadata(
                listOf(
                    AppPairMember(first.id, SplitStage.TOP_OR_LEFT, SnapPositionToken("snap")),
                    AppPairMember(second.id, SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("snap")),
                ),
            ),
        )
        val input = minimalInput(items = listOf(pair, first, second))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(pair.id, Disposition.Preserved(PreserveReason.APP_PAIR), workspaceTarget()),
                PlannedPlacement(first.id, Disposition.Preserved(PreserveReason.APP_PAIR), PlacementTarget.AppPairMember(AppPairRef(pairId))),
                PlannedPlacement(second.id, Disposition.Preserved(PreserveReason.APP_PAIR), PlacementTarget.AppPairMember(AppPairRef(pairId))),
            ),
        )
        val harness = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result))))
        val fixture = PlannerFixture(
            id = FixtureId("valid-app-pair-container"),
            input = input,
            expectation = FixtureExpectation(ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.CONTAINER_INTEGRITY),
        )

        assertTrue(harness.verify(fixture).isSuccess)
    }

    @Test
    fun lockPreservationViolationDetected() {
        val lockedItem = itemA.copy(locked = true)
        val input = minimalInput(items = listOf(lockedItem))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = lockedItem.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("lock-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.LOCK_PRESERVATION),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for lock preservation", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.LOCK_PRESERVATION })
    }

    @Test
    fun lockPreservationAcceptsExactLockedPlacement() {
        val lockedItem = itemA.copy(locked = true)
        val input = minimalInput(items = listOf(lockedItem))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    lockedItem.id,
                    Disposition.Preserved(PreserveReason.LOCKED),
                    workspaceTarget(),
                ),
            ),
        )
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("lock-positive"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.LOCK_PRESERVATION),
            ),
        )

        assertTrue(report.isSuccess)
    }

    @Test
    fun profileIsolationViolationDetected() {
        val p1 = ProfileId("p1")
        val itemP0 = itemA
        val itemP1 = CapturedItem(
            id = ItemId("app.work"),
            profile = p1,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.work"), p1),
            placement = CapturedPlacement.Workspace(page = PageRef(PageId("p0")), cell = GridCell(1, 0), span = GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val input = minimalInput(items = listOf(itemP0, itemP1))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemP0.id,
                    disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    target = PlacementTarget.FolderMember(
                        folder = NewFolderRef(NewFolderOrdinal(0)),
                        rank = 0,
                    ),
                ),
                PlannedPlacement(
                    item = itemP1.id,
                    disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    target = PlacementTarget.FolderMember(
                        folder = NewFolderRef(NewFolderOrdinal(0)),
                        rank = 1,
                    ),
                ),
            ),
            newFolders = listOf(
                app.lawnchair.organizer.planning.NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = p0,
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        page = NewPageRef(NewPageOrdinal(0)),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                    members = listOf(itemP0.id, itemP1.id),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("profile-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.PROFILE_ISOLATION),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for profile isolation", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.PROFILE_ISOLATION })
    }

    @Test
    fun profileIsolationAcceptsSameProfileFolder() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    itemA.id,
                    Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    PlacementTarget.FolderMember(NewFolderRef(NewFolderOrdinal(0)), rank = 0),
                ),
            ),
            newFolders = listOf(
                app.lawnchair.organizer.planning.NewFolder(
                    NewFolderOrdinal(0),
                    p0,
                    workspaceTarget(),
                    listOf(itemA.id),
                ),
            ),
        )
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("profile-positive"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.PROFILE_ISOLATION),
            ),
        )

        assertTrue(report.isSuccess)
    }

    @Test
    fun profileIsolationDetectsPreExistingMixedProfileMemberMovedOut() {
        val folderId = FolderId("mixed-folder")
        val workProfile = ProfileId("p1")
        val member = CapturedItem(
            id = ItemId("mixed.member"),
            profile = workProfile,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.mixed"), workProfile),
            placement = CapturedPlacement.FolderMember(FolderRef(folderId), rank = 0),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val parent = CapturedItem(
            id = ItemId("mixed.parent"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(folderId),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = folderId,
            members = listOf(member.id),
        )
        val input = minimalInput(items = listOf(parent, member))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(parent.id, Disposition.Preserved(PreserveReason.STRUCTURAL), workspaceTarget()),
                PlannedPlacement(
                    member.id,
                    Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    workspaceTarget(cell = GridCell(1, 0)),
                ),
            ),
        )
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("profile-mixed-move-out"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.PROFILE_ISOLATION),
            ),
        )

        assertTrue(report.violations.any { it.message == "Pre-existing cross-profile folder member target changed" })
    }

    @Test
    fun determinismViolationDetected() {
        val input = minimalInput(items = listOf(itemA))
        val result1 = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(0, 0)),
                ),
            ),
        )
        val result2 = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(1, 0)),
                ),
            ),
        )
        val script = mapOf(input to listOf(result1, result2))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("determinism-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.DETERMINISM),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for determinism", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.DETERMINISM })
    }

    @Test
    fun determinismEqualResultsPass() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(0, 0)),
                ),
            ),
        )
        val script = mapOf(input to listOf(result, result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("determinism-equal"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.DETERMINISM),
        )

        val report = harness.verify(fixture)
        assertTrue("Expected success for equal determinism results", report.isSuccess)
    }

    @Test
    fun inputPermutationDetected() {
        val itemB = CapturedItem(
            id = ItemId("app.b"),
            profile = p0,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.b"), p0),
            placement = CapturedPlacement.Workspace(page = PageRef(PageId("p0")), cell = GridCell(1, 0), span = GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val singleExisting = ExistingTargetMembership(itemA.id, ExistingRole.Movable)
        val singleCategory = TaxonomyContract(
            version = TaxonomyVersion("tv1"),
            allowedCategories = listOf(CategoryId("OTHER")),
            fallbackCategory = CategoryId("OTHER"),
        )
        val input = minimalInput(
            items = listOf(itemA, itemB),
            existing = listOf(singleExisting),
            taxonomy = singleCategory,
        )
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(0, 0)),
                ),
                PlannedPlacement(
                    item = itemB.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(1, 0)),
                ),
            ),
        )

        val permutedResult = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(1, 0)),
                ),
                PlannedPlacement(
                    item = itemB.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(0, 0)),
                ),
            ),
        )

        val rotatedItems = input.snapshot.items.drop(1) + input.snapshot.items.take(1)
        val permutedInput = input.copy(snapshot = input.snapshot.copy(items = rotatedItems))

        val script = mapOf(
            input to listOf(result),
            permutedInput to listOf(permutedResult),
        )
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("permutation-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.INPUT_PERMUTATION),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for permutation sensitivity", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.INPUT_PERMUTATION })
    }

    @Test
    fun invalidExpectationDoesNotRunPlannedChecks() {
        val input = minimalInput()
        val result = invalidResult(
            reasons = listOf(
                RejectionReason(
                    code = RejectionCode.DUPLICATE_ITEM_ID,
                    params = listOf(DiagnosticParam.ItemParam(ItemId("dup"))),
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("invalid-only"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DUPLICATE_ITEM_ID),
                ),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.BOUNDS),
        )

        val report = harness.verify(fixture)
        assertTrue("Expected success for invalid expectation", report.isSuccess)
    }

    @Test
    fun impossibleExpectationDoesNotRunPlannedChecks() {
        val input = minimalInput(
            additions = listOf(
                CandidateItem(
                    id = ItemId("v.big"),
                    profile = p0,
                    kind = CandidateKind.APPLICATION,
                    target = CandidateTarget.AppKey(ComponentKey("com.example"), p0),
                    availability = Availability.AVAILABLE,
                    span = GridSpan(10, 10),
                ),
            ),
            runMode = RunMode.IncrementalPlacement,
        )
        val result = impossibleResult(
            unplaced = listOf(
                UnplacedItem(
                    item = ItemId("v.big"),
                    requiredSpan = GridSpan(10, 10),
                    reason = UnplacedReason.EXCEEDS_GRID_DIMENSIONS,
                ),
            ),
        )
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("impossible-only"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Impossible(
                    requiredReasons = setOf(UnplacedReason.EXCEEDS_GRID_DIMENSIONS),
                ),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.BOUNDS),
        )

        val report = harness.verify(fixture)
        assertTrue("Expected success for impossible expectation", report.isSuccess)
    }

    @Test
    fun harnessOnlyCrossesPlanSeam() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(),
                ),
            ),
        )
        val script = mapOf(input to listOf(result, result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("seam-only"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.DETERMINISM),
        )

        harness.verify(fixture)
        assertEquals(2, planner.callCount)
    }

    @Test
    fun violationOrderingByFixtureIdThenCheck() {
        val inputA = minimalInput(items = listOf(itemA))
        val inputB = minimalInput(
            items = listOf(
                CapturedItem(
                    id = ItemId("app.b"),
                    profile = p0,
                    kind = ItemKind.APPLICATION,
                    target = TargetKey.AppKey(ComponentKey("com.example.b"), p0),
                    placement = CapturedPlacement.Workspace(page = PageRef(PageId("p0")), cell = GridCell(0, 0), span = GridSpan(1, 1)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
            ),
        )

        val resultA = plannedResult(placements = emptyList())
        val resultB = plannedResult(placements = emptyList())

        val script = mapOf(
            inputA to listOf(resultA),
            inputB to listOf(resultB),
        )
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixtureA = PlannerFixture(
            id = FixtureId("z-fixture"),
            input = inputA,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.CONSERVATION),
        )

        val fixtureB = PlannerFixture(
            id = FixtureId("a-fixture"),
            input = inputB,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.CONSERVATION),
        )

        val reportA = harness.verify(fixtureA)
        val reportB = harness.verify(fixtureB)

        assertFalse(reportA.isSuccess)
        assertFalse(reportB.isSuccess)
        assertTrue(reportA.violations[0].fixtureId.value > reportB.violations[0].fixtureId.value)
    }

    @Test
    fun violationOrderingUsesTypedSubjectThenMessage() {
        val itemB = itemA.copy(
            id = ItemId("item.b"),
            target = TargetKey.AppKey(ComponentKey("com.example.b"), p0),
            locked = true,
        )
        val itemAOrdered = itemA.copy(id = ItemId("item.a"), locked = true)
        val input = minimalInput(items = listOf(itemB, itemAOrdered))
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(plannedResult())))).verify(
            PlannerFixture(
                FixtureId("typed-order"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.LOCK_PRESERVATION),
            ),
        )
        assertEquals(4, report.violations.size)
        assertTrue(report.violations.take(2).all { it.message.startsWith("Item item.a:") })
        assertTrue(report.violations.drop(2).all { it.message.startsWith("Item item.b:") })
        assertEquals(report.violations.take(2).map { it.message }.sorted(), report.violations.take(2).map { it.message })
    }

    @Test
    fun reproductionStringFormat() {
        val r = Reproduction(seed = 0x4E554E55L, caseIndex = 0)
        val text = r.toString()
        assertTrue(text.contains("PlannerGeneratedPropertyTest"))
        assertTrue(text.contains("testLawnWithQuickstepGithubDebugUnitTest"))
        assertTrue(text.contains("planner.seed"))
        assertTrue(text.contains("planner.case"))
    }

    @Test
    fun fixtureHasNonNullReproductionWhenGenerated() {
        val fixtures = SyntheticFixtureGenerator.generate(seed = 0x4E554E55L, count = 5)
        for (f in fixtures) {
            assertNotNull(f.reproduction)
        }
    }

    @Test
    fun materializationPreservesOriginalRoles() {
        val folderId = FolderId("f0")
        val memberId = ItemId("app.member")
        val folderItem = CapturedItem(
            id = ItemId("app.folder"),
            profile = p0,
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(folderId),
            placement = CapturedPlacement.Workspace(page = PageRef(PageId("p0")), cell = GridCell(0, 0), span = GridSpan(2, 2)),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = folderId,
            members = listOf(memberId),
        )
        val memberItem = CapturedItem(
            id = memberId,
            profile = p0,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.member"), p0),
            placement = CapturedPlacement.FolderMember(folder = FolderRef(folderId), rank = 0),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val input = minimalInput(items = listOf(folderItem, memberItem))

        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = folderItem.id,
                    disposition = Disposition.Preserved(PreserveReason.STRUCTURAL),
                    target = workspaceTarget(cell = GridCell(0, 0), span = GridSpan(2, 2)),
                ),
                PlannedPlacement(
                    item = memberId,
                    disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    target = PlacementTarget.FolderMember(folder = NewFolderRef(NewFolderOrdinal(0)), rank = 0),
                ),
            ),
            newPages = listOf(NewPage(ordinal = NewPageOrdinal(0), order = PageOrder(1))),
            newFolders = listOf(
                app.lawnchair.organizer.planning.NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = p0,
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        page = NewPageRef(NewPageOrdinal(0)),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                    members = listOf(memberId),
                ),
            ),
        )

        val plannedOutcome = result.outcome as Planned
        val materialized = (PostPlanMaterializer.materialize(input, plannedOutcome) as MaterializationResult.Success).input
        val materializedFolder = materialized.snapshot.items.find { it.id == folderItem.id }
        assertNotNull(materializedFolder)
        assertEquals(folderItem.kind, materializedFolder!!.kind)
        assertEquals(folderItem.target, materializedFolder.target)
        assertEquals(folderItem.locked, materializedFolder.locked)
        assertEquals(folderItem.folderId, materializedFolder.folderId)
        assertEquals(input.targets.existing.first { it.item == folderItem.id }.role, materialized.targets.existing.first { it.item == folderItem.id }.role)
        val synthetic = materialized.snapshot.items.single { it.id.value.startsWith("fixture.materialized.folder-item.") }
        assertEquals(ExistingRole.Preserved, materialized.targets.existing.single { it.item == synthetic.id }.role)
        assertEquals(CapturedPlacement.FolderMember(FolderRef(synthetic.folderId!!), 0), materialized.snapshot.items.single { it.id == memberId }.placement)
        val repeated = (PostPlanMaterializer.materialize(input, plannedOutcome) as MaterializationResult.Success).input
        assertEquals(materialized, repeated)
    }

    @Test
    fun expectationChecksAllEchoFieldsAndGatesPlannedChecks() {
        val input = minimalInput(items = listOf(itemA))
        val wrongEcho = plannedResult(placements = emptyList()).copy(revision = RevisionId("wrong"))
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(wrongEcho)))).verify(
            PlannerFixture(
                FixtureId("echo"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION),
            ),
        )
        assertFalse(report.isSuccess)
        assertTrue(report.violations.all { it.check == ContractCheck.EXPECTATION })
        assertTrue(report.violations.any { it.message == "Revision echo mismatch" })
    }

    @Test
    fun determinismDetectsResultFamilyTransition() {
        val input = minimalInput()
        val report = PlannerContractHarness(
            ScriptedPlanner(mapOf(input to listOf(plannedResult(), invalidResult()))),
        ).verify(
            PlannerFixture(
                FixtureId("determinism-family"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.DETERMINISM),
            ),
        )
        assertTrue(report.violations.any { it.check == ContractCheck.DETERMINISM })
    }

    @Test
    fun permutationDetectsRejectedVariant() {
        val pages = listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1)))
        val input = minimalInput(
            pages = pages,
            taxonomy = defaultTaxonomy.copy(allowedCategories = listOf(defaultTaxonomy.fallbackCategory)),
        )
        val variant = input.copy(snapshot = input.snapshot.copy(pages = pages.drop(1) + pages.take(1)))
        val planner = ScriptedPlanner(mapOf(input to listOf(plannedResult()), variant to listOf(invalidResult())))
        val report = PlannerContractHarness(planner).verify(
            PlannerFixture(
                FixtureId("permutation-family"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.INPUT_PERMUTATION),
            ),
        )
        assertTrue(report.violations.any { it.check == ContractCheck.INPUT_PERMUTATION })
    }

    @Test
    fun permutationAcceptsCompleteValueEqualResult() {
        val pages = listOf(Page(PageId("p0"), PageOrder(0)), Page(PageId("p1"), PageOrder(1)))
        val input = minimalInput(
            pages = pages,
            taxonomy = defaultTaxonomy.copy(allowedCategories = listOf(defaultTaxonomy.fallbackCategory)),
        )
        val variant = input.copy(snapshot = input.snapshot.copy(pages = pages.drop(1) + pages.take(1)))
        val result = plannedResult()
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result), variant to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("permutation-pass"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.INPUT_PERMUTATION),
            ),
        )
        assertTrue(report.isSuccess)
    }

    @Test
    fun permutationSkipsValueEqualNoOpRotation() {
        val signal = ClassificationSignal(itemA.id, SignalSource.S1, defaultTaxonomy.fallbackCategory)
        val input = minimalInput(
            items = listOf(itemA),
            signals = listOf(signal, signal),
            taxonomy = defaultTaxonomy.copy(allowedCategories = listOf(defaultTaxonomy.fallbackCategory)),
        )
        val planner = ScriptedPlanner(mapOf(input to listOf(plannedResult())))

        val report = PlannerContractHarness(planner).verify(
            PlannerFixture(
                FixtureId("permutation-no-op"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.INPUT_PERMUTATION),
            ),
        )

        assertTrue(report.isSuccess)
        assertEquals(1, planner.callCount)
    }

    @Test
    fun conservationDetectsDuplicatePlacement() {
        val input = minimalInput(items = listOf(itemA))
        val placement = PlannedPlacement(itemA.id, Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), workspaceTarget())
        val result = plannedResult(placements = listOf(placement, placement))
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("duplicate-placement"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.CONSERVATION),
            ),
        )
        assertTrue(report.violations.single().message.contains("2 placements"))
    }

    @Test
    fun boundsChecksFolderRankAndNewFolderShape() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    itemA.id,
                    Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    PlacementTarget.FolderMember(NewFolderRef(NewFolderOrdinal(0)), -1),
                ),
            ),
            newFolders = listOf(
                app.lawnchair.organizer.planning.NewFolder(
                    NewFolderOrdinal(0),
                    p0,
                    PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(2, 1)),
                    listOf(itemA.id),
                ),
            ),
        )
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(FixtureId("bounds-extra"), input, FixtureExpectation(ExpectedOutcome.Planned()), setOf(ContractCheck.BOUNDS)),
        )
        assertEquals(2, report.violations.size)
    }

    @Test
    fun noOverlapIncludesNewFoldersAndFolderRanks() {
        val folderOrdinal = NewFolderOrdinal(0)
        val input = minimalInput(items = listOf(itemA, itemA.copy(id = ItemId("app.b"))))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Moved(PlacementCode.FOLDER_MEMBER), PlacementTarget.FolderMember(NewFolderRef(folderOrdinal), 0)),
                PlannedPlacement(ItemId("app.b"), Disposition.Moved(PlacementCode.FOLDER_MEMBER), PlacementTarget.FolderMember(NewFolderRef(folderOrdinal), 0)),
            ),
            newFolders = listOf(
                app.lawnchair.organizer.planning.NewFolder(
                    folderOrdinal,
                    p0,
                    workspaceTarget(cell = GridCell(0, 0)),
                    listOf(itemA.id, ItemId("app.b")),
                ),
                app.lawnchair.organizer.planning.NewFolder(
                    NewFolderOrdinal(1),
                    p0,
                    workspaceTarget(cell = GridCell(0, 0)),
                    emptyList(),
                ),
            ),
        )
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(FixtureId("overlap-extra"), input, FixtureExpectation(ExpectedOutcome.Planned()), setOf(ContractCheck.NO_OVERLAP)),
        )
        assertTrue(report.violations.any { it.message == "Duplicate folder rank" })
        assertTrue(report.violations.any { it.message.startsWith("Workspace overlap") })
    }

    @Test
    fun containerIntegrityChecksPageResolutionAndOrdinals() {
        val input = minimalInput(items = listOf(itemA))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), workspaceTarget(page = PageRef(PageId("missing")))),
            ),
            newPages = listOf(
                NewPage(NewPageOrdinal(0), PageOrder(1)),
                NewPage(NewPageOrdinal(0), PageOrder(2)),
            ),
        )
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("container-extra"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.CONTAINER_INTEGRITY),
            ),
        )
        assertTrue(report.violations.any { it.message == "Duplicate new-page ordinal" })
        assertTrue(report.violations.any { it.message.contains("page reference") })
    }

    @Test
    fun containerIntegrityAcceptsPageAfterFormerIntMaximum() {
        val base = minimalInput(items = listOf(itemA))
        val input = base.copy(
            snapshot = base.snapshot.copy(
                pages = listOf(Page(PageId("p0"), PageOrder(Int.MAX_VALUE))),
            ),
        )
        val result = plannedResult(
            newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder("2147483648"))),
        )

        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("page-order-beyond-int"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.CONTAINER_INTEGRITY),
            ),
        )

        assertTrue(report.violations.toString(), report.isSuccess)
    }

    @Test
    fun containerIntegrityRejectsNewPageNotAfterCapturedMaximum() {
        val base = minimalInput(items = listOf(itemA))
        val input = base.copy(
            snapshot = base.snapshot.copy(
                pages = listOf(Page(PageId("p0"), PageOrder(Int.MAX_VALUE))),
            ),
        )
        val result = plannedResult(
            newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder(Int.MAX_VALUE))),
        )

        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("page-order-not-following"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.CONTAINER_INTEGRITY),
            ),
        )

        assertTrue(
            report.violations.any {
                it.message == "New-page order does not follow captured pages"
            },
        )
    }

    @Test
    fun lockPreservationRequiresExactTarget() {
        val locked = itemA.copy(locked = true)
        val input = minimalInput(items = listOf(locked))
        val result = plannedResult(
            placements = listOf(
                PlannedPlacement(locked.id, Disposition.Preserved(PreserveReason.LOCKED), workspaceTarget(cell = GridCell(1, 0))),
            ),
        )
        val report = PlannerContractHarness(ScriptedPlanner(mapOf(input to listOf(result)))).verify(
            PlannerFixture(
                FixtureId("lock-target"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.LOCK_PRESERVATION),
            ),
        )
        assertTrue(report.violations.any { it.message.contains("locked target changed") })
    }

    @Test
    fun idempotenceDetectsRejectedReplan() {
        val input = minimalInput(items = listOf(itemA))
        val first = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Preserved(PreserveReason.NON_TARGET), workspaceTarget()),
            ),
        )
        val materialized = (PostPlanMaterializer.materialize(input, first.outcome as Planned) as MaterializationResult.Success).input
        val planner = ScriptedPlanner(mapOf(input to listOf(first), materialized to listOf(invalidResult())))
        val report = PlannerContractHarness(planner).verify(
            PlannerFixture(
                FixtureId("idempotence-rejected"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.IDEMPOTENCE),
            ),
        )
        assertTrue(report.violations.any { it.message.contains("rejected") })
    }

    @Test
    fun idempotenceAcceptsEmptyEffectiveChange() {
        val input = minimalInput(items = listOf(itemA))
        val first = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), workspaceTarget()),
            ),
        )
        val materialized = (PostPlanMaterializer.materialize(input, first.outcome as Planned) as MaterializationResult.Success).input
        val replan = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), workspaceTarget()),
            ),
        ).copy(revision = materialized.snapshot.revision)
        val planner = ScriptedPlanner(mapOf(input to listOf(first), materialized to listOf(replan)))
        val report = PlannerContractHarness(planner).verify(
            PlannerFixture(
                FixtureId("idempotence-pass"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.IDEMPOTENCE),
            ),
        )
        assertTrue(report.isSuccess)
    }

    @Test
    fun idempotenceRejectsNonTargetReasonForCanonicalMovableItem() {
        val input = minimalInput(items = listOf(itemA))
        val first = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), workspaceTarget()),
            ),
        )
        val materialized = (PostPlanMaterializer.materialize(input, first.outcome as Planned) as MaterializationResult.Success).input
        val replan = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Preserved(PreserveReason.NON_TARGET), workspaceTarget()),
            ),
        ).copy(revision = materialized.snapshot.revision)
        val planner = ScriptedPlanner(mapOf(input to listOf(first), materialized to listOf(replan)))
        val report = PlannerContractHarness(planner).verify(
            PlannerFixture(
                FixtureId("idempotence-wrong-reason"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.IDEMPOTENCE),
            ),
        )
        assertTrue(report.violations.any { it.message.contains("ALREADY_CANONICAL") })
    }

    @Test
    fun idempotenceRejectsAlreadyCanonicalReasonWhenLockedReasonHasPrecedence() {
        val locked = itemA.copy(locked = true)
        val input = minimalInput(items = listOf(locked))
        val first = plannedResult(
            placements = listOf(
                PlannedPlacement(locked.id, Disposition.Preserved(PreserveReason.LOCKED), workspaceTarget()),
            ),
        )
        val materialized = (PostPlanMaterializer.materialize(input, first.outcome as Planned) as MaterializationResult.Success).input
        val replan = plannedResult(
            placements = listOf(
                PlannedPlacement(locked.id, Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), workspaceTarget()),
            ),
        ).copy(revision = materialized.snapshot.revision)
        val planner = ScriptedPlanner(mapOf(input to listOf(first), materialized to listOf(replan)))
        val report = PlannerContractHarness(planner).verify(
            PlannerFixture(
                FixtureId("idempotence-higher-reason"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.IDEMPOTENCE),
            ),
        )
        assertTrue(report.violations.any { it.message.contains("LOCKED") })
    }

    @Test
    fun idempotenceRejectsMissingAndDuplicatePlacements() {
        val itemB = itemA.copy(
            id = ItemId("app.b"),
            target = TargetKey.AppKey(ComponentKey("com.example.b"), p0),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(1, 0), GridSpan(1, 1)),
        )
        val input = minimalInput(items = listOf(itemA, itemB))
        val first = plannedResult(
            placements = listOf(
                PlannedPlacement(itemA.id, Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), workspaceTarget()),
                PlannedPlacement(itemB.id, Disposition.Moved(PlacementCode.SINGLE_PLACEMENT), workspaceTarget(cell = GridCell(1, 0))),
            ),
        )
        val materialized = (PostPlanMaterializer.materialize(input, first.outcome as Planned) as MaterializationResult.Success).input
        val duplicate = PlannedPlacement(itemA.id, Disposition.Preserved(PreserveReason.ALREADY_CANONICAL), workspaceTarget())
        val replan = plannedResult(placements = listOf(duplicate, duplicate)).copy(revision = materialized.snapshot.revision)
        val planner = ScriptedPlanner(mapOf(input to listOf(first), materialized to listOf(replan)))
        val report = PlannerContractHarness(planner).verify(
            PlannerFixture(
                FixtureId("idempotence-cardinality"),
                input,
                FixtureExpectation(ExpectedOutcome.Planned()),
                setOf(ContractCheck.IDEMPOTENCE),
            ),
        )
        assertEquals(2, report.violations.count { it.message.contains("exactly one placement") })
    }

    @Test
    fun idempotenceViolationDetected() {
        val input = minimalInput(items = listOf(itemA))

        val firstResult = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(0, 0)),
                ),
            ),
        )

        val secondResult = plannedResult(
            placements = listOf(
                PlannedPlacement(
                    item = itemA.id,
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = workspaceTarget(cell = GridCell(1, 0)),
                ),
            ),
        )

        val firstPlanned = firstResult.outcome as Planned
        val materializedInput = (PostPlanMaterializer.materialize(input, firstPlanned) as MaterializationResult.Success).input

        val script = mapOf(
            input to listOf(firstResult, firstResult),
            materializedInput to listOf(secondResult),
        )
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("idempotence-violation"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = setOf(ContractCheck.IDEMPOTENCE),
        )

        val report = harness.verify(fixture)
        assertFalse("Expected violation for idempotence", report.isSuccess)
        assertTrue(report.violations.any { it.check == ContractCheck.IDEMPOTENCE })
    }

    @Test
    fun emptyChecksProducesEmptyReport() {
        val input = minimalInput()
        val result = plannedResult()
        val script = mapOf(input to listOf(result))
        val planner = ScriptedPlanner(script)
        val harness = PlannerContractHarness(planner)

        val fixture = PlannerFixture(
            id = FixtureId("empty-checks"),
            input = input,
            expectation = FixtureExpectation(outcome = ExpectedOutcome.Planned()),
            checks = emptySet(),
        )

        val report = harness.verify(fixture)
        assertTrue(report.isSuccess)
        assertEquals(0, planner.callCount)
    }
}
