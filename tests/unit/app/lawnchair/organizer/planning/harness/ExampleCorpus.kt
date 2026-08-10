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
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ClassificationSignal
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.Confidence
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.DeviceDimension
import app.lawnchair.organizer.planning.DiagnosticParam
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
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OrderingPolicy
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.PreserveReason
import app.lawnchair.organizer.planning.ProfileId
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

internal object ExampleCorpus {

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
        folderPolicy = FolderPolicy(
            minGroupSize = 2,
            newFolderProfileScope = NewFolderProfileScope.SAME_PROFILE_ONLY,
        ),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        orderingPolicy = OrderingPolicy.CANONICAL_V1,
    )

    private val defaultTaxonomy = TaxonomyContract(
        version = TaxonomyVersion("tv1"),
        allowedCategories = listOf(
            CategoryId("OTHER"),
            CategoryId("GAMES"),
            CategoryId("TOOLS"),
        ),
        fallbackCategory = CategoryId("OTHER"),
    )

    private val p0 = ProfileId("p0")
    private val p1 = ProfileId("p1")

    val emptyHome: PlannerFixture by lazy { buildEmptyHome() }
    val appsOnly: PlannerFixture by lazy { buildAppsOnly() }
    val mixedAppShortcutWidget: PlannerFixture by lazy { buildMixedAppShortcutWidget() }
    val folderContainerIntegrity: PlannerFixture by lazy { buildFolderContainerIntegrity() }
    val lockedFragmentedSpace: PlannerFixture by lazy { buildLockedFragmentedSpace() }
    val fullGridNoCapacity: PlannerFixture by lazy { buildFullGridNoCapacity() }
    val multiplePagesAndDock: PlannerFixture by lazy { buildMultiplePagesAndDock() }
    val samePackagePersonalWork: PlannerFixture by lazy { buildSamePackagePersonalWork() }
    val undefinedCategory: PlannerFixture by lazy { buildUndefinedCategory() }
    val deviceProfileVariation: PlannerFixture by lazy { buildDeviceProfileVariation() }
    val deckOutputCompatibility: PlannerFixture by lazy { buildDeckOutputCompatibility() }

    val allExamples: Map<FixtureId, PlannerFixture> by lazy {
        listOf(
            emptyHome,
            appsOnly,
            mixedAppShortcutWidget,
            folderContainerIntegrity,
            lockedFragmentedSpace,
            fullGridNoCapacity,
            multiplePagesAndDock,
            samePackagePersonalWork,
            undefinedCategory,
            deviceProfileVariation,
            deckOutputCompatibility,
        ).associateBy { it.id }
    }

    val validationFixtures: Map<ValidationRuleId, PlannerFixture> by lazy { buildValidationFixtures() }

    val validationCoverage: List<CoverageRow<ValidationRuleId>> by lazy { buildValidationCoverage() }
    val scenarioCoverage: List<CoverageRow<ScenarioId>> by lazy { buildScenarioCoverage() }

    private fun buildEmptyHome(): PlannerFixture {
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-empty"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = emptyList(),
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(existing = emptyList(), additions = emptyList()),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("empty-home"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(
                ContractCheck.EXPECTATION,
                ContractCheck.CONSERVATION,
                ContractCheck.BOUNDS,
                ContractCheck.DETERMINISM,
            ),
        )
    }

    private fun buildAppsOnly(): PlannerFixture {
        val items = listOf(
            CapturedItem(
                id = ItemId("app.calc"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.calc"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.game"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.game"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(1, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.tools"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.tools"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(2, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-apps"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(
                entries = listOf(
                    ClassificationSignal(items[0].id, SignalSource.S3, CategoryId("GAMES")),
                    ClassificationSignal(items[1].id, SignalSource.S3, CategoryId("GAMES")),
                    ClassificationSignal(items[2].id, SignalSource.S1, CategoryId("TOOLS")),
                ),
            ),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("apps-only"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(
                    requiredCategories = setOf(
                        app.lawnchair.organizer.planning.CategoryDecision(items[0].id, CategoryId("GAMES"), SignalSource.S3, Confidence.RULE),
                        app.lawnchair.organizer.planning.CategoryDecision(items[1].id, CategoryId("GAMES"), SignalSource.S3, Confidence.RULE),
                        app.lawnchair.organizer.planning.CategoryDecision(items[2].id, CategoryId("TOOLS"), SignalSource.S1, Confidence.EXPLICIT),
                    ),
                    expectedNewFolderCount = 1,
                ),
            ),
            checks = setOf(
                ContractCheck.EXPECTATION,
                ContractCheck.CONSERVATION,
                ContractCheck.BOUNDS,
            ),
        )
    }

    private fun buildMixedAppShortcutWidget(): PlannerFixture {
        val pairId = AppPairId("mixed.pair")
        val pairFirstId = ItemId("app.pair.first")
        val pairSecondId = ItemId("app.pair.second")
        val items = listOf(
            CapturedItem(
                id = ItemId("app.widget"),
                profile = p0,
                kind = ItemKind.APPWIDGET,
                target = TargetKey.WidgetKey(ComponentKey("com.example.widget"), AppWidgetId(1), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(2, 2),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.shortcut"),
                profile = p0,
                kind = ItemKind.DEEP_SHORTCUT,
                target = TargetKey.ShortcutKey(PackageName("com.example"), ShortcutId("sc1"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(2, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.legacy"),
                profile = p0,
                kind = ItemKind.SHORTCUT_LEGACY,
                target = TargetKey.LegacyShortcutKey,
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 2),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.dock"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.dock"), p0),
                placement = CapturedPlacement.Dock(rank = 0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.locked.dock"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.locked.dock"), p0),
                placement = CapturedPlacement.Dock(rank = 1),
                locked = true,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.locked.unavailable"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.locked.unavailable"), p0),
                placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(3, 2), GridSpan(1, 1)),
                locked = true,
                availability = Availability.UNAVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.pair.parent"),
                profile = p0,
                kind = ItemKind.APP_PAIR,
                target = TargetKey.AppPairKey(pairId),
                placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(2, 2), GridSpan(1, 1)),
                locked = false,
                availability = Availability.AVAILABLE,
                appPairId = pairId,
                appPair = AppPairMetadata(
                    members = listOf(
                        AppPairMember(pairFirstId, SplitStage.TOP_OR_LEFT, SnapPositionToken("mixed.snap")),
                        AppPairMember(pairSecondId, SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("mixed.snap")),
                    ),
                ),
            ),
            CapturedItem(
                id = pairFirstId,
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.pair.first"), p0),
                placement = CapturedPlacement.AppPairMember(AppPairRef(pairId)),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = pairSecondId,
                profile = p0,
                kind = ItemKind.DEEP_SHORTCUT,
                target = TargetKey.ShortcutKey(PackageName("com.example.pair"), ShortcutId("second"), p0),
                placement = CapturedPlacement.AppPairMember(AppPairRef(pairId)),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-mixed"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(
                entries = listOf(
                    ClassificationSignal(ItemId("app.shortcut"), SignalSource.S6, CategoryId("OTHER")),
                ),
            ),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("mixed-app-shortcut-widget"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(
                    requiredPreservations = mapOf(
                        ItemId("app.widget") to PreserveReason.WIDGET,
                        ItemId("app.legacy") to PreserveReason.LEGACY_SHORTCUT,
                        ItemId("app.dock") to PreserveReason.DOCK,
                        ItemId("app.locked.dock") to PreserveReason.LOCKED,
                        ItemId("app.locked.unavailable") to PreserveReason.LOCKED,
                        ItemId("app.pair.parent") to PreserveReason.APP_PAIR,
                        pairFirstId to PreserveReason.APP_PAIR,
                        pairSecondId to PreserveReason.APP_PAIR,
                    ),
                    requiredCategories = setOf(
                        app.lawnchair.organizer.planning.CategoryDecision(
                            ItemId("app.shortcut"),
                            CategoryId("OTHER"),
                            SignalSource.S6,
                            Confidence.FALLBACK,
                        ),
                    ),
                ),
                requiredWarningCodes = setOf(WarningCode.LEGACY_SHORTCUT_REVIEW, WarningCode.FALLBACK_CATEGORY),
            ),
            checks = setOf(
                ContractCheck.EXPECTATION,
                ContractCheck.CONSERVATION,
                ContractCheck.LOCK_PRESERVATION,
                ContractCheck.NO_OVERLAP,
            ),
        )
    }

    private fun buildFolderContainerIntegrity(): PlannerFixture {
        val memberId = ItemId("app.folder.member")
        val folderId = FolderId("f0")
        val items = listOf(
            CapturedItem(
                id = ItemId("app.folder.parent"),
                profile = p0,
                kind = ItemKind.FOLDER,
                target = TargetKey.FolderKey(folderId),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(2, 2),
                ),
                locked = false, availability = Availability.AVAILABLE,
                folderId = folderId,
                members = listOf(memberId),
            ),
            CapturedItem(
                id = memberId,
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.member"), p0),
                placement = CapturedPlacement.FolderMember(folder = FolderRef(folderId), rank = 0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-folder"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("folder-container-integrity"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONTAINER_INTEGRITY),
        )
    }

    private fun buildLockedFragmentedSpace(): PlannerFixture {
        val folderId = FolderId("locked.folder")
        val firstChildId = ItemId("app.locked.folder.child.1")
        val secondChildId = ItemId("app.locked.folder.child.2")
        val items = listOf(
            CapturedItem(
                id = ItemId("app.locked.1"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.locked1"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(3, 4),
                    span = GridSpan(1, 1),
                ),
                locked = true,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.locked.folder"),
                profile = p0,
                kind = ItemKind.FOLDER,
                target = TargetKey.FolderKey(folderId),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
                locked = true,
                availability = Availability.AVAILABLE,
                folderId = folderId,
                members = listOf(firstChildId, secondChildId),
            ),
            CapturedItem(
                id = firstChildId,
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.locked.child.1"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(folderId), rank = 0),
                locked = true,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = secondChildId,
                profile = p0,
                kind = ItemKind.DEEP_SHORTCUT,
                target = TargetKey.ShortcutKey(PackageName("com.example.locked"), ShortcutId("child.2"), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(folderId), rank = 1),
                locked = true,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-locked"),
                device = defaultDevice.copy(rows = 6),
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("locked-fragmented-space"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(
                    requiredPreservations = items.associate { it.id to PreserveReason.LOCKED },
                ),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.LOCK_PRESERVATION, ContractCheck.NO_OVERLAP),
        )
    }

    private fun buildFullGridNoCapacity(): PlannerFixture {
        val items = (0 until 16).map { i ->
            val x = i % 4
            val y = i / 4
            CapturedItem(
                id = ItemId("app.full.$i"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.full.$i"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(x, y),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-full"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("full-grid-no-capacity"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.BOUNDS),
        )
    }

    private fun buildMultiplePagesAndDock(): PlannerFixture {
        val pages = listOf(
            Page(id = PageId("p0"), order = PageOrder(0)),
            Page(id = PageId("p1"), order = PageOrder(1)),
        )
        val items = listOf(
            CapturedItem(
                id = ItemId("app.page1"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.p1"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.page2"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.p2"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p1")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.dock"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.dock"), p0),
                placement = CapturedPlacement.Dock(rank = 0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-multi"),
                device = defaultDevice,
                pages = pages,
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("multiple-pages-and-dock"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.BOUNDS),
        )
    }

    private fun buildSamePackagePersonalWork(): PlannerFixture {
        val items = listOf(
            CapturedItem(
                id = ItemId("app.same.personal"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.same"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("app.same.work"),
                profile = p1,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.same"), p1),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(1, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-profile"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("same-package-personal-work"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(ContractCheck.PROFILE_ISOLATION),
        )
    }

    private fun buildUndefinedCategory(): PlannerFixture {
        val items = listOf(
            CapturedItem(
                id = ItemId("app.unknown"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.unknown"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-undefined"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(
                entries = listOf(
                    ClassificationSignal(items.single().id, SignalSource.S6, CategoryId("OTHER")),
                ),
            ),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("undefined-category"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(
                    requiredCategories = setOf(
                        app.lawnchair.organizer.planning.CategoryDecision(
                            items.single().id,
                            CategoryId("OTHER"),
                            SignalSource.S6,
                            Confidence.FALLBACK,
                        ),
                    ),
                    expectedNewFolderCount = 0,
                ),
                requiredWarningCodes = setOf(WarningCode.FALLBACK_CATEGORY),
            ),
            checks = setOf(ContractCheck.EXPECTATION),
        )
    }

    private fun buildDeviceProfileVariation(): PlannerFixture {
        val device = DeviceCapabilities(
            columns = 6,
            rows = 8,
            hotseatSlots = 6,
            folderMaxColumns = 4,
            folderMaxRows = 4,
            orientation = Orientation.LANDSCAPE,
        )
        val items = listOf(
            CapturedItem(
                id = ItemId("app.landscape"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.landscape"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-device"),
                device = device,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("device-profile-variation"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.BOUNDS),
        )
    }

    private fun buildDeckOutputCompatibility(): PlannerFixture {
        val folderId = FolderId("deck.f0")
        val memberId = ItemId("deck.member")
        val items = listOf(
            CapturedItem(
                id = ItemId("deck.folder"),
                profile = p0,
                kind = ItemKind.FOLDER,
                target = TargetKey.FolderKey(folderId),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(0, 0),
                    span = GridSpan(2, 2),
                ),
                locked = false, availability = Availability.AVAILABLE,
                folderId = folderId,
                members = listOf(memberId),
            ),
            CapturedItem(
                id = memberId,
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.deck.member"), p0),
                placement = CapturedPlacement.FolderMember(folder = FolderRef(folderId), rank = 0),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
            CapturedItem(
                id = ItemId("deck.singleton"),
                profile = p0,
                kind = ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.deck.single"), p0),
                placement = CapturedPlacement.Workspace(
                    page = PageRef(PageId("p0")),
                    cell = GridCell(2, 0),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            ),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = RevisionId("rev-deck"),
                device = defaultDevice,
                pages = listOf(Page(id = PageId("p0"), order = PageOrder(0))),
                items = items,
            ),
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        return PlannerFixture(
            id = FixtureId("deck-output-compatibility"),
            input = input,
            expectation = FixtureExpectation(
                outcome = ExpectedOutcome.Planned(),
            ),
            checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONTAINER_INTEGRITY),
        )
    }

    private fun buildValidationFixtures(): Map<ValidationRuleId, PlannerFixture> {
        val fixtures = mutableMapOf<ValidationRuleId, PlannerFixture>()

        val validItem = CapturedItem(
            id = ItemId("v.valid"),
            profile = p0,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.valid"), p0),
            placement = CapturedPlacement.Workspace(
                page = PageRef(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
            locked = false,
            availability = Availability.AVAILABLE,
        )

        val validPage = Page(id = PageId("p0"), order = PageOrder(0))
        val validSnapshot = LayoutSnapshot(
            revision = RevisionId("rev-v"),
            device = defaultDevice,
            pages = listOf(validPage),
            items = listOf(validItem),
        )

        fun baseInput() = OrganizationInput(
            snapshot = validSnapshot,
            rules = defaultRules,
            taxonomy = defaultTaxonomy,
            signals = ClassificationSignals(entries = emptyList()),
            targets = TargetSet(
                existing = listOf(ExistingTargetMembership(validItem.id, ExistingRole.Movable)),
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )

        fun fixtureFor(id: String, input: OrganizationInput, expectation: FixtureExpectation): PlannerFixture {
            val ownsTargetDefect = id.startsWith("V-15") || id == "V-16" || id == "V-17" || id == "V-18"
            val isolatedInput = if (ownsTargetDefect) {
                input
            } else {
                input.copy(
                    targets = input.targets.copy(
                        existing = input.snapshot.items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) },
                    ),
                )
            }
            return PlannerFixture(
                id = FixtureId("v-fixture.$id"),
                input = isolatedInput,
                expectation = expectation,
                checks = setOf(ContractCheck.EXPECTATION),
            )
        }

        val vItem = CapturedItem(
            id = ItemId("v.item"),
            profile = p0,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey("com.example.v"), p0),
            placement = CapturedPlacement.Workspace(
                page = PageRef(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
            locked = false,
            availability = Availability.AVAILABLE,
        )

        fixtures[ValidationRuleId("V-01")] = fixtureFor(
            "V-01",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(kind = ItemKind.Unknown(KindCode(99)), target = TargetKey.AppKey(ComponentKey("com.example.v"), p0)),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.UNKNOWN_ITEM_KIND),
                    requiredDetails = setOf(
                        RejectionReason(RejectionCode.UNKNOWN_ITEM_KIND, listOf(DiagnosticParam.KindParam(KindCode(99)))),
                    ),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-02")] = fixtureFor(
            "V-02",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.UnsupportedContainer(ContainerCode(7)),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INVALID_CONTAINER),
                    requiredDetails = setOf(
                        RejectionReason(RejectionCode.INVALID_CONTAINER, listOf(DiagnosticParam.ContainerCodeParam(ContainerCode(7)))),
                    ),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-03")] = fixtureFor(
            "V-03",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("nonexistent")),
                                cell = GridCell(0, 0),
                                span = GridSpan(1, 1),
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.UNKNOWN_PAGE),
                    requiredDetails = setOf(
                        RejectionReason(RejectionCode.UNKNOWN_PAGE, listOf(DiagnosticParam.PageParam(PageId("nonexistent")))),
                    ),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-04-neg-x")] = fixtureFor(
            "V-04-neg-x",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(-1, 0),
                                span = GridSpan(1, 1),
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.BOUNDS_VIOLATION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-04-neg-y")] = fixtureFor(
            "V-04-neg-y",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(0, -1),
                                span = GridSpan(1, 1),
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.BOUNDS_VIOLATION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-04-right")] = fixtureFor(
            "V-04-right",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(3, 0),
                                span = GridSpan(2, 1),
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.BOUNDS_VIOLATION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-04-bottom")] = fixtureFor(
            "V-04-bottom",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(0, 3),
                                span = GridSpan(1, 2),
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.BOUNDS_VIOLATION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-04-neg-dock")] = fixtureFor(
            "V-04-neg-dock",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Dock(rank = -1),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.BOUNDS_VIOLATION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-04-dock-eq")] = fixtureFor(
            "V-04-dock-eq",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Dock(rank = 4),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.BOUNDS_VIOLATION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-05")] = fixtureFor(
            "V-05",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            id = ItemId("v.overlap.a"),
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(0, 0),
                                span = GridSpan(2, 2),
                            ),
                        ),
                        CapturedItem(
                            id = ItemId("v.overlap.b"),
                            profile = p0,
                            kind = ItemKind.APPLICATION,
                            target = TargetKey.AppKey(ComponentKey("com.example.ob"), p0),
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(1, 1),
                                span = GridSpan(1, 1),
                            ),
                            locked = false,
                            availability = Availability.AVAILABLE,
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.OVERLAP),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-06-absent-ref")] = fixtureFor(
            "V-06-absent-ref",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.FolderMember(
                                folder = FolderRef(FolderId("nonexistent")),
                                rank = 0,
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DANGLING_REFERENCE),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-06-dup-folder-id")] = fixtureFor(
            "V-06-dup-folder-id",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            id = ItemId("v.folder.a"),
                            kind = ItemKind.FOLDER,
                            target = TargetKey.FolderKey(FolderId("fdup")),
                            folderId = FolderId("fdup"),
                            members = listOf(ItemId("v.child")),
                        ),
                        CapturedItem(
                            id = ItemId("v.folder.b"),
                            profile = p0,
                            kind = ItemKind.FOLDER,
                            target = TargetKey.FolderKey(FolderId("fdup")),
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(1, 0),
                                span = GridSpan(1, 1),
                            ),
                            locked = false, availability = Availability.AVAILABLE,
                            folderId = FolderId("fdup"),
                            members = emptyList(),
                        ),
                        CapturedItem(
                            id = ItemId("v.child"),
                            profile = p0,
                            kind = ItemKind.APPLICATION,
                            target = TargetKey.AppKey(ComponentKey("com.example.child"), p0),
                            placement = CapturedPlacement.FolderMember(folder = FolderRef(FolderId("fdup")), rank = 0),
                            locked = false,
                            availability = Availability.AVAILABLE,
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DANGLING_REFERENCE),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-06-cycle")] = fixtureFor(
            "V-06-cycle",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            id = ItemId("v.folder.a"),
                            kind = ItemKind.FOLDER,
                            target = TargetKey.FolderKey(FolderId("fa")),
                            folderId = FolderId("fa"),
                            members = listOf(ItemId("v.folder.b")),
                        ),
                        CapturedItem(
                            id = ItemId("v.folder.b"),
                            profile = p0,
                            kind = ItemKind.FOLDER,
                            target = TargetKey.FolderKey(FolderId("fb")),
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(1, 0),
                                span = GridSpan(1, 1),
                            ),
                            locked = false, availability = Availability.AVAILABLE,
                            folderId = FolderId("fb"),
                            members = listOf(ItemId("v.folder.a")),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DANGLING_REFERENCE),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-07-wrong-count")] = fixtureFor(
            "V-07-wrong-count",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            kind = ItemKind.APP_PAIR,
                            target = TargetKey.AppPairKey(AppPairId("ap1")),
                            appPairId = AppPairId("ap1"),
                            appPair = AppPairMetadata(
                                members = listOf(
                                    AppPairMember(ItemId("v.ap.a"), SplitStage.TOP_OR_LEFT, SnapPositionToken("s")),
                                ),
                            ),
                        ),
                        CapturedItem(
                            id = ItemId("v.ap.a"),
                            profile = p0,
                            kind = ItemKind.APPLICATION,
                            target = TargetKey.AppKey(ComponentKey("com.example.ap.a"), p0),
                            placement = CapturedPlacement.AppPairMember(pair = AppPairRef(AppPairId("ap1"))),
                            locked = false,
                            availability = Availability.AVAILABLE,
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.MALFORMED_APP_PAIR),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-07-dup-id")] = fixtureFor(
            "V-07-dup-id",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            kind = ItemKind.APP_PAIR,
                            target = TargetKey.AppPairKey(AppPairId("ap2")),
                            appPairId = AppPairId("ap2"),
                            appPair = AppPairMetadata(
                                members = listOf(
                                    AppPairMember(ItemId("v.ap.a"), SplitStage.TOP_OR_LEFT, SnapPositionToken("s")),
                                    AppPairMember(ItemId("v.ap.a"), SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("s")),
                                ),
                            ),
                        ),
                        CapturedItem(
                            id = ItemId("v.ap.a"),
                            profile = p0,
                            kind = ItemKind.APPLICATION,
                            target = TargetKey.AppKey(ComponentKey("com.example.ap.a"), p0),
                            placement = CapturedPlacement.AppPairMember(pair = AppPairRef(AppPairId("ap2"))),
                            locked = false,
                            availability = Availability.AVAILABLE,
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.MALFORMED_APP_PAIR),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-08")] = fixtureFor(
            "V-08",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(id = ItemId("v.dup")),
                        vItem.copy(id = ItemId("v.dup")),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DUPLICATE_ITEM_ID),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-09-dup-page-id")] = fixtureFor(
            "V-09-dup-page-id",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    pages = listOf(
                        Page(id = PageId("pdup"), order = PageOrder(0)),
                        Page(id = PageId("pdup"), order = PageOrder(1)),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DUPLICATE_PAGE),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-09-dup-order")] = fixtureFor(
            "V-09-dup-order",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    pages = listOf(
                        Page(id = PageId("p0"), order = PageOrder(0)),
                        Page(id = PageId("p1"), order = PageOrder(0)),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DUPLICATE_PAGE),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-10-captured-width")] = fixtureFor(
            "V-10-captured-width",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(0, 0),
                                span = GridSpan(0, 1),
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INVALID_DIMENSIONS),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-10-rows")] = fixtureFor(
            "V-10-rows",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    device = defaultDevice.copy(rows = 0),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INVALID_DIMENSIONS),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-11-kind-target")] = fixtureFor(
            "V-11-kind-target",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            kind = ItemKind.APPLICATION,
                            target = TargetKey.WidgetKey(ComponentKey("com.example.w"), AppWidgetId(1), p0),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.KIND_TARGET_MISMATCH),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-11-folder-missing-id")] = fixtureFor(
            "V-11-folder-missing-id",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            kind = ItemKind.FOLDER,
                            target = TargetKey.FolderKey(FolderId("f0")),
                            folderId = null,
                            members = listOf(ItemId("v.child")),
                        ),
                        CapturedItem(
                            id = ItemId("v.child"),
                            profile = p0,
                            kind = ItemKind.APPLICATION,
                            target = TargetKey.AppKey(ComponentKey("com.example.child"), p0),
                            placement = CapturedPlacement.FolderMember(folder = FolderRef(FolderId("f0")), rank = 0),
                            locked = false,
                            availability = Availability.AVAILABLE,
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.KIND_TARGET_MISMATCH),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-12-captured")] = fixtureFor(
            "V-12-captured",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            profile = p0,
                            target = TargetKey.AppKey(ComponentKey("com.example.mismatch"), p1),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.TARGET_PROFILE_MISMATCH),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-12-candidate")] = fixtureFor(
            "V-12-candidate",
            baseInput().copy(
                targets = TargetSet(
                    existing = listOf(ExistingTargetMembership(ItemId("v.candidate.mismatch"), ExistingRole.Movable)),
                    additions = listOf(
                        CandidateItem(
                            id = ItemId("v.candidate.mismatch"),
                            profile = p0,
                            kind = CandidateKind.APPLICATION,
                            target = CandidateTarget.AppKey(ComponentKey("com.example"), p1),
                            availability = Availability.AVAILABLE,
                            span = GridSpan(1, 1),
                        ),
                    ),
                ),
                runMode = RunMode.IncrementalPlacement,
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.TARGET_PROFILE_MISMATCH),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-13")] = fixtureFor(
            "V-13",
            baseInput().copy(
                signals = ClassificationSignals(
                    entries = listOf(
                        ClassificationSignal(
                            item = ItemId("nonexistent.item"),
                            source = SignalSource.S1,
                            candidate = CategoryId("OTHER"),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.UNKNOWN_SIGNAL_ITEM),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-14")] = fixtureFor(
            "V-14",
            baseInput().copy(
                signals = ClassificationSignals(
                    entries = listOf(
                        ClassificationSignal(
                            item = ItemId("v.item"),
                            source = SignalSource.S1,
                            candidate = CategoryId("NONEXISTENT"),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.UNKNOWN_CATEGORY),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-15-dup-existing")] = fixtureFor(
            "V-15-dup-existing",
            baseInput().copy(
                targets = TargetSet(
                    existing = listOf(
                        ExistingTargetMembership(validItem.id, ExistingRole.Movable),
                        ExistingTargetMembership(validItem.id, ExistingRole.Preserved),
                    ),
                    additions = emptyList(),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DUPLICATE_TARGET),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-15-dup-addition")] = fixtureFor(
            "V-15-dup-addition",
            baseInput().copy(
                targets = TargetSet(
                    existing = listOf(ExistingTargetMembership(validItem.id, ExistingRole.Movable)),
                    additions = listOf(
                        CandidateItem(
                            id = ItemId("v.dup.add"),
                            profile = p0,
                            kind = CandidateKind.APPLICATION,
                            target = CandidateTarget.AppKey(ComponentKey("com.example"), p0),
                            availability = Availability.AVAILABLE,
                            span = GridSpan(1, 1),
                        ),
                        CandidateItem(
                            id = ItemId("v.dup.add"),
                            profile = p0,
                            kind = CandidateKind.APPLICATION,
                            target = CandidateTarget.AppKey(ComponentKey("com.example"), p0),
                            availability = Availability.AVAILABLE,
                            span = GridSpan(1, 1),
                        ),
                    ),
                ),
                runMode = RunMode.IncrementalPlacement,
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DUPLICATE_TARGET),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-15-captured-collision")] = fixtureFor(
            "V-15-captured-collision",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(vItem.copy(id = ItemId("v.collision"))),
                ),
                targets = TargetSet(
                    existing = listOf(ExistingTargetMembership(ItemId("v.collision"), ExistingRole.Movable)),
                    additions = listOf(
                        CandidateItem(
                            id = ItemId("v.collision"),
                            profile = p0,
                            kind = CandidateKind.APPLICATION,
                            target = CandidateTarget.AppKey(ComponentKey("com.example"), p0),
                            availability = Availability.AVAILABLE,
                            span = GridSpan(1, 1),
                        ),
                    ),
                ),
                runMode = RunMode.IncrementalPlacement,
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.DUPLICATE_TARGET),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-16")] = fixtureFor(
            "V-16",
            baseInput().copy(
                targets = TargetSet(
                    existing = listOf(
                        ExistingTargetMembership(ItemId("missing.item"), ExistingRole.Movable),
                    ),
                    additions = emptyList(),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.MISSING_TARGET),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-17")] = fixtureFor(
            "V-17",
            baseInput().copy(
                targets = TargetSet(
                    existing = emptyList(),
                    additions = emptyList(),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INCOMPLETE_TARGET_PARTITION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-18")] = fixtureFor(
            "V-18",
            baseInput().copy(
                targets = TargetSet(
                    existing = listOf(ExistingTargetMembership(validItem.id, ExistingRole.Movable)),
                    additions = listOf(
                        CandidateItem(
                            id = ItemId("v.addition"),
                            profile = p0,
                            kind = CandidateKind.APPLICATION,
                            target = CandidateTarget.AppKey(ComponentKey("com.example"), p0),
                            availability = Availability.AVAILABLE,
                            span = GridSpan(1, 1),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.ADDITIONS_UNDER_FULL_ORGANIZATION),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-19")] = fixtureFor(
            "V-19",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(
                            locked = true,
                            placement = CapturedPlacement.Workspace(
                                page = PageRef(PageId("p0")),
                                cell = GridCell(0, 0),
                                span = GridSpan(5, 5),
                            ),
                        ),
                    ),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.LOCKED_OUT_OF_BOUNDS),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-20-bad-version")] = fixtureFor(
            "V-20-bad-version",
            baseInput().copy(
                rules = defaultRules.copy(version = RuleVersion("unknown")),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INVALID_RULES),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-20-min-group")] = fixtureFor(
            "V-20-min-group",
            baseInput().copy(
                rules = defaultRules.copy(
                    folderPolicy = FolderPolicy(minGroupSize = 1, newFolderProfileScope = NewFolderProfileScope.SAME_PROFILE_ONLY),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INVALID_RULES),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-20-dup-category")] = fixtureFor(
            "V-20-dup-category",
            baseInput().copy(
                taxonomy = defaultTaxonomy.copy(
                    allowedCategories = listOf(CategoryId("OTHER"), CategoryId("OTHER")),
                    fallbackCategory = CategoryId("OTHER"),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INVALID_RULES),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-20-bad-fallback")] = fixtureFor(
            "V-20-bad-fallback",
            baseInput().copy(
                taxonomy = defaultTaxonomy.copy(
                    fallbackCategory = CategoryId("NONEXISTENT"),
                ),
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Invalid(
                    requiredCodes = setOf(RejectionCode.INVALID_RULES),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-21")] = fixtureFor(
            "V-21",
            baseInput().copy(
                targets = TargetSet(
                    existing = listOf(ExistingTargetMembership(validItem.id, ExistingRole.Movable)),
                    additions = listOf(
                        CandidateItem(
                            id = ItemId("v.big.candidate"),
                            profile = p0,
                            kind = CandidateKind.APPLICATION,
                            target = CandidateTarget.AppKey(ComponentKey("com.example"), p0),
                            availability = Availability.AVAILABLE,
                            span = GridSpan(10, 10),
                        ),
                    ),
                ),
                runMode = RunMode.IncrementalPlacement,
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Impossible(
                    requiredReasons = setOf(UnplacedReason.EXCEEDS_GRID_DIMENSIONS),
                    requiredItems = setOf(
                        UnplacedItem(
                            ItemId("v.big.candidate"),
                            GridSpan(10, 10),
                            UnplacedReason.EXCEEDS_GRID_DIMENSIONS,
                        ),
                    ),
                ),
            ),
        )

        fixtures[ValidationRuleId("V-22")] = fixtureFor(
            "V-22",
            baseInput().copy(
                targets = TargetSet(
                    existing = listOf(ExistingTargetMembership(validItem.id, ExistingRole.Movable)),
                    additions = listOf(
                        CandidateItem(
                            id = ItemId("v.unavailable.candidate"),
                            profile = p0,
                            kind = CandidateKind.APPLICATION,
                            target = CandidateTarget.AppKey(ComponentKey("com.example"), p0),
                            availability = Availability.DISABLED,
                            span = GridSpan(1, 1),
                        ),
                    ),
                ),
                runMode = RunMode.IncrementalPlacement,
            ),
            FixtureExpectation(
                outcome = ExpectedOutcome.Impossible(
                    requiredReasons = setOf(UnplacedReason.TARGET_UNAVAILABLE),
                    requiredItems = setOf(
                        UnplacedItem(
                            ItemId("v.unavailable.candidate"),
                            GridSpan(1, 1),
                            UnplacedReason.TARGET_UNAVAILABLE,
                        ),
                    ),
                ),
            ),
        )

        fun application(id: String, placement: CapturedPlacement): CapturedItem = vItem.copy(
            id = ItemId(id),
            target = TargetKey.AppKey(ComponentKey("com.example.$id"), p0),
            placement = placement,
        )

        fun folder(id: String, folderId: String, members: List<ItemId>, x: Int): CapturedItem = vItem.copy(
            id = ItemId(id),
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(FolderId(folderId)),
            placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
            folderId = FolderId(folderId),
            members = members,
        )

        fun invalid(id: String, items: List<CapturedItem>, code: RejectionCode = RejectionCode.DANGLING_REFERENCE) {
            fixtures[ValidationRuleId(id)] = fixtureFor(
                id,
                baseInput().copy(snapshot = validSnapshot.copy(items = items)),
                FixtureExpectation(ExpectedOutcome.Invalid(setOf(code))),
            )
        }

        invalid(
            "V-06-absent-app-pair-ref",
            listOf(application("v.ap.absent", CapturedPlacement.AppPairMember(AppPairRef(AppPairId("missing-pair"))))),
        )
        run {
            val pairId = AppPairId("duplicate-pair")
            val a1 = application("v.ap.a1", CapturedPlacement.AppPairMember(AppPairRef(pairId)))
            val a2 = application("v.ap.a2", CapturedPlacement.AppPairMember(AppPairRef(pairId)))
            val b1 = application("v.ap.b1", CapturedPlacement.AppPairMember(AppPairRef(pairId)))
            val b2 = application("v.ap.b2", CapturedPlacement.AppPairMember(AppPairRef(pairId)))
            fun parent(id: String, x: Int, members: List<CapturedItem>) = vItem.copy(
                id = ItemId(id),
                kind = ItemKind.APP_PAIR,
                target = TargetKey.AppPairKey(pairId),
                placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(x, 0), GridSpan(1, 1)),
                appPairId = pairId,
                appPair = AppPairMetadata(
                    listOf(
                        AppPairMember(members[0].id, SplitStage.TOP_OR_LEFT, SnapPositionToken("snap")),
                        AppPairMember(members[1].id, SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("snap")),
                    ),
                ),
            )
            invalid("V-06-dup-app-pair-id", listOf(parent("v.ap.parent-a", 0, listOf(a1, a2)), parent("v.ap.parent-b", 1, listOf(b1, b2)), a1, a2, b1, b2))
        }
        run {
            val child = application("v.folder.child", CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(1, 0), GridSpan(1, 1)))
            invalid("V-06-parent-child-mismatch", listOf(folder("v.folder.parent", "parent", listOf(child.id), 0), child))
        }
        run {
            val child = application("v.folder.child", CapturedPlacement.FolderMember(FolderRef(FolderId("parent")), 0))
            invalid("V-06-child-parent-mismatch", listOf(folder("v.folder.parent", "parent", emptyList(), 0), child))
            invalid("V-06-duplicate-child", listOf(folder("v.folder.parent", "parent", listOf(child.id, child.id), 0), child))
        }
        run {
            val child = vItem.copy(
                id = ItemId("v.folder.widget"),
                kind = ItemKind.APPWIDGET,
                target = TargetKey.WidgetKey(ComponentKey("com.example.widget"), AppWidgetId(42), p0),
                placement = CapturedPlacement.FolderMember(FolderRef(FolderId("parent")), 0),
            )
            invalid("V-06-disallowed-child", listOf(folder("v.folder.parent", "parent", listOf(child.id), 0), child))
        }
        run {
            val child = application("v.folder.child", CapturedPlacement.FolderMember(FolderRef(FolderId("a")), 0))
            invalid(
                "V-06-two-containers",
                listOf(folder("v.folder.a", "a", listOf(child.id), 0), folder("v.folder.b", "b", listOf(child.id), 1), child),
            )
        }

        fun appPairCase(
            id: String,
            metadataMembers: List<AppPairMember>,
            firstKind: ItemKind = ItemKind.APPLICATION,
            secondPlacementPair: AppPairId = AppPairId("pair-case"),
        ) {
            val pairId = AppPairId("pair-case")
            val firstBase = application("v.pair.first", CapturedPlacement.AppPairMember(AppPairRef(pairId)))
            val first = if (firstKind == ItemKind.APPLICATION) {
                firstBase
            } else {
                firstBase.copy(
                    kind = firstKind,
                    target = TargetKey.WidgetKey(ComponentKey("com.example.pair-widget"), AppWidgetId(77), p0),
                )
            }
            val second = application("v.pair.second", CapturedPlacement.AppPairMember(AppPairRef(secondPlacementPair)))
            val parent = vItem.copy(
                id = ItemId("v.pair.parent"),
                kind = ItemKind.APP_PAIR,
                target = TargetKey.AppPairKey(pairId),
                appPairId = pairId,
                appPair = AppPairMetadata(metadataMembers),
            )
            invalid(id, listOf(parent, first, second), RejectionCode.MALFORMED_APP_PAIR)
        }
        val pairFirst = ItemId("v.pair.first")
        val pairSecond = ItemId("v.pair.second")
        appPairCase(
            "V-07-dup-stage",
            listOf(
                AppPairMember(pairFirst, SplitStage.TOP_OR_LEFT, SnapPositionToken("snap")),
                AppPairMember(pairSecond, SplitStage.TOP_OR_LEFT, SnapPositionToken("snap")),
            ),
        )
        appPairCase(
            "V-07-invalid-kind",
            listOf(
                AppPairMember(pairFirst, SplitStage.TOP_OR_LEFT, SnapPositionToken("snap")),
                AppPairMember(pairSecond, SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("snap")),
            ),
            firstKind = ItemKind.APPWIDGET,
        )
        appPairCase(
            "V-07-missing-snap",
            listOf(
                AppPairMember(pairFirst, SplitStage.TOP_OR_LEFT, null),
                AppPairMember(pairSecond, SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("snap")),
            ),
        )
        appPairCase(
            "V-07-unequal-snap",
            listOf(
                AppPairMember(pairFirst, SplitStage.TOP_OR_LEFT, SnapPositionToken("left")),
                AppPairMember(pairSecond, SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("right")),
            ),
        )
        appPairCase(
            "V-07-incoherent-placement",
            listOf(
                AppPairMember(pairFirst, SplitStage.TOP_OR_LEFT, SnapPositionToken("snap")),
                AppPairMember(pairSecond, SplitStage.BOTTOM_OR_RIGHT, SnapPositionToken("snap")),
            ),
            secondPlacementPair = AppPairId("other-pair"),
        )

        fun invalidDimensions(id: String, input: OrganizationInput) {
            fixtures[ValidationRuleId(id)] = fixtureFor(
                id,
                input,
                FixtureExpectation(ExpectedOutcome.Invalid(setOf(RejectionCode.INVALID_DIMENSIONS))),
            )
        }
        invalidDimensions(
            "V-10-captured-height",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(vItem.copy(placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(1, 0)))),
                ),
            ),
        )
        fun badCandidate(id: String, span: GridSpan) {
            invalidDimensions(
                id,
                baseInput().copy(
                    targets = baseInput().targets.copy(
                        additions = listOf(
                            CandidateItem(
                                ItemId("v.dimension.candidate"),
                                p0,
                                CandidateKind.APPLICATION,
                                CandidateTarget.AppKey(ComponentKey("com.example.dimension"), p0),
                                Availability.AVAILABLE,
                                span,
                            ),
                        ),
                    ),
                    runMode = RunMode.IncrementalPlacement,
                ),
            )
        }
        badCandidate("V-10-candidate-width", GridSpan(0, 1))
        badCandidate("V-10-candidate-height", GridSpan(1, 0))
        invalidDimensions("V-10-columns", baseInput().copy(snapshot = validSnapshot.copy(device = defaultDevice.copy(columns = 0))))
        invalidDimensions("V-10-hotseat", baseInput().copy(snapshot = validSnapshot.copy(device = defaultDevice.copy(hotseatSlots = 0))))
        invalidDimensions("V-10-folder-columns", baseInput().copy(snapshot = validSnapshot.copy(device = defaultDevice.copy(folderMaxColumns = 0))))
        invalidDimensions("V-10-folder-rows", baseInput().copy(snapshot = validSnapshot.copy(device = defaultDevice.copy(folderMaxRows = 0))))

        fun kindMismatch(id: String, input: OrganizationInput) {
            fixtures[ValidationRuleId(id)] = fixtureFor(
                id,
                input,
                FixtureExpectation(ExpectedOutcome.Invalid(setOf(RejectionCode.KIND_TARGET_MISMATCH))),
            )
        }
        kindMismatch(
            "V-11-candidate-kind-target",
            baseInput().copy(
                targets = baseInput().targets.copy(
                    additions = listOf(
                        CandidateItem(
                            ItemId("v.kind.candidate"),
                            p0,
                            CandidateKind.APPLICATION,
                            CandidateTarget.ShortcutKey(PackageName("com.example"), ShortcutId("bad"), p0),
                            Availability.AVAILABLE,
                            GridSpan(1, 1),
                        ),
                    ),
                ),
                runMode = RunMode.IncrementalPlacement,
            ),
        )
        kindMismatch("V-11-non-folder-id", baseInput().copy(snapshot = validSnapshot.copy(items = listOf(vItem.copy(folderId = FolderId("unexpected"))))))
        kindMismatch("V-11-non-folder-members", baseInput().copy(snapshot = validSnapshot.copy(items = listOf(vItem.copy(members = listOf(ItemId("unexpected")))))))
        kindMismatch(
            "V-11-app-pair-missing-id",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(
                        vItem.copy(kind = ItemKind.APP_PAIR, target = TargetKey.AppPairKey(AppPairId("pair"))),
                    ),
                ),
            ),
        )
        kindMismatch("V-11-non-app-pair-id", baseInput().copy(snapshot = validSnapshot.copy(items = listOf(vItem.copy(appPairId = AppPairId("unexpected"))))))
        kindMismatch(
            "V-11-app-pair-missing-metadata",
            baseInput().copy(
                snapshot = validSnapshot.copy(
                    items = listOf(vItem.copy(kind = ItemKind.APP_PAIR, target = TargetKey.AppPairKey(AppPairId("pair")), appPairId = AppPairId("pair"))),
                ),
            ),
        )
        kindMismatch(
            "V-11-non-app-pair-metadata",
            baseInput().copy(snapshot = validSnapshot.copy(items = listOf(vItem.copy(appPair = AppPairMetadata(emptyList()))))),
        )

        return fixtures
    }

    private fun buildValidationCoverage(): List<CoverageRow<ValidationRuleId>> {
        return (1..22).map { number ->
            val id = ValidationRuleId("V-${number.toString().padStart(2, '0')}")
            val matching = validationFixtures.filterKeys { it.rule == id.value }.values
            CoverageRow(
                id = id,
                evidence = matching.map {
                    CoverageEvidence.Fixture(
                        fixture = it.id,
                        checks = setOf(ContractCheck.EXPECTATION),
                    )
                },
            )
        }
    }

    private fun buildScenarioCoverage(): List<CoverageRow<ScenarioId>> {
        fun validationEvidence(vararg rules: String): List<CoverageEvidence> = validationFixtures
            .filterKeys { it.rule in rules }
            .values
            .map {
                CoverageEvidence.Fixture(
                    fixture = it.id,
                    checks = setOf(ContractCheck.EXPECTATION),
                )
            }

        return listOf(
            CoverageRow(
                id = ScenarioId("S-01"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = appsOnly.id,
                        checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.BOUNDS),
                    ),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-02"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = mixedAppShortcutWidget.id,
                        checks = setOf(ContractCheck.CONSERVATION),
                    ),
                    CoverageEvidence.Generated(checks = setOf(ContractCheck.CONSERVATION)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-03"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = mixedAppShortcutWidget.id,
                        checks = setOf(ContractCheck.EXPECTATION, ContractCheck.LOCK_PRESERVATION, ContractCheck.NO_OVERLAP),
                    ),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-04"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = lockedFragmentedSpace.id,
                        checks = setOf(ContractCheck.EXPECTATION, ContractCheck.LOCK_PRESERVATION, ContractCheck.NO_OVERLAP),
                    ),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-05"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = samePackagePersonalWork.id,
                        checks = setOf(ContractCheck.PROFILE_ISOLATION),
                    ),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-06"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = appsOnly.id,
                        checks = setOf(ContractCheck.EXPECTATION),
                    ),
                    CoverageEvidence.Fixture(
                        fixture = undefinedCategory.id,
                        checks = setOf(ContractCheck.EXPECTATION),
                    ),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-07"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = appsOnly.id,
                        checks = setOf(ContractCheck.EXPECTATION),
                    ),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-08"),
                evidence = listOf(
                    CoverageEvidence.Generated(checks = setOf(ContractCheck.DETERMINISM, ContractCheck.INPUT_PERMUTATION)),
                    CoverageEvidence.Downstream(issue = 12, clause = "locale/timezone/thread executions"),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-09"),
                evidence = listOf(
                    CoverageEvidence.Generated(checks = setOf(ContractCheck.IDEMPOTENCE)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-10"),
                evidence = listOf(
                    CoverageEvidence.Generated(checks = setOf(ContractCheck.CONSERVATION, ContractCheck.PROFILE_ISOLATION)),
                    CoverageEvidence.Downstream(issue = 12, clause = "noncanonical convergence behavior"),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-11"),
                evidence = listOf(
                    CoverageEvidence.Generated(checks = setOf(ContractCheck.EXPECTATION, ContractCheck.CONSERVATION)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-12"),
                evidence = listOf(
                    CoverageEvidence.Fixture(
                        fixture = mixedAppShortcutWidget.id,
                        checks = setOf(ContractCheck.EXPECTATION),
                    ),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-13"),
                evidence = listOf(
                    CoverageEvidence.Generated(checks = setOf(ContractCheck.EXPECTATION)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-14"),
                evidence = validationEvidence("V-01", "V-02", "V-04", "V-05", "V-08", "V-09", "V-10", "V-11", "V-12"),
            ),
            CoverageRow(
                id = ScenarioId("S-15"),
                evidence = validationEvidence("V-07"),
            ),
            CoverageRow(
                id = ScenarioId("S-16"),
                evidence = listOf(
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-19")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-15-dup-existing")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-15-dup-addition")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-15-captured-collision")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-16")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-17")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-18")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-20-bad-version")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-20-min-group")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-20-dup-category")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-20-bad-fallback")).id, checks = setOf(ContractCheck.EXPECTATION)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-17"),
                evidence = listOf(
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-13")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-14")).id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Generated(checks = setOf(ContractCheck.EXPECTATION, ContractCheck.DETERMINISM)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-18"),
                evidence = listOf(
                    CoverageEvidence.Fixture(fixture = validationFixtures.getValue(ValidationRuleId("V-21")).id, checks = setOf(ContractCheck.EXPECTATION)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-19"),
                evidence = listOf(
                    CoverageEvidence.Fixture(fixture = mixedAppShortcutWidget.id, checks = setOf(ContractCheck.EXPECTATION)),
                    CoverageEvidence.Fixture(fixture = undefinedCategory.id, checks = setOf(ContractCheck.EXPECTATION)),
                ),
            ),
            CoverageRow(
                id = ScenarioId("S-20"),
                evidence = listOf(
                    CoverageEvidence.Fixture(fixture = emptyHome.id, checks = setOf(ContractCheck.EXPECTATION, ContractCheck.DETERMINISM)),
                    CoverageEvidence.Downstream(issue = 12, clause = "purity and locale inspection"),
                ),
            ),
        )
    }
}
