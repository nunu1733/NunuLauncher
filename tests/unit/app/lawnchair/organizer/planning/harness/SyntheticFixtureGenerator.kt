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
import app.lawnchair.organizer.planning.DeviceCapabilities
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
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderProfileScope
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
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.ShortcutId
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.SnapPositionToken
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import app.lawnchair.organizer.planning.UnplacedItem
import app.lawnchair.organizer.planning.UnplacedReason
import app.lawnchair.organizer.planning.WarningCode
import java.util.Random

internal object SyntheticFixtureGenerator {
    const val DEFAULT_SEED: Long = 0x4E554E55L

    fun generate(seed: Long = DEFAULT_SEED, count: Int = DEFAULT_PLANNER_CASE_COUNT): List<PlannerFixture> {
        require(count > 0)
        val random = Random(seed)
        return List(count) { index -> generateOne(seed, index, count, Random(random.nextLong())) }
    }

    fun selectCase(fixtures: List<PlannerFixture>, seed: Long, caseIndex: Int): PlannerFixture {
        require(caseIndex in fixtures.indices)
        return fixtures[caseIndex].also {
            val reproduction = requireNotNull(it.reproduction)
            require(reproduction.seed == seed)
            require(reproduction.caseIndex == caseIndex)
            require(reproduction.corpusCount == fixtures.size)
        }
    }

    private fun generateOne(seed: Long, index: Int, count: Int, random: Random): PlannerFixture {
        val columns = random.nextInt(3, 9)
        val rows = random.nextInt(3, 9)
        val orientation = Orientation.entries[index % Orientation.entries.size]
        val template = index % 8
        val pageCount = if (template == 0) 0 else 1 + random.nextInt(3)
        val pages = List(pageCount) { Page(PageId("fixture.page.$index.$it"), PageOrder(it)) }
        val personal = ProfileId("profile.personal.$index")
        val work = ProfileId("profile.work.$index")
        val items = mutableListOf<CapturedItem>()

        fun workspace(page: Int = 0, x: Int = 0, y: Int = 0, span: GridSpan = GridSpan(1, 1)) = CapturedPlacement.Workspace(PageRef(pages[page].id), GridCell(x, y), span)

        fun app(id: String, profile: ProfileId, placement: CapturedPlacement, component: String = "component.shared.$index") = CapturedItem(
            id = ItemId(id),
            profile = profile,
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(ComponentKey(component), profile),
            placement = placement,
            locked = false,
            availability = Availability.AVAILABLE,
        )

        when (template) {
            0 -> Unit

            1 -> {
                items += app("fixture.item.$index.app", personal, workspace())
                items += CapturedItem(
                    ItemId("fixture.item.$index.shortcut"),
                    personal,
                    ItemKind.DEEP_SHORTCUT,
                    TargetKey.ShortcutKey(PackageName("component.pkg.$index"), ShortcutId("fixture.shortcut.$index"), personal),
                    workspace(x = 1),
                    false,
                    Availability.AVAILABLE,
                )
            }

            2 -> {
                items += CapturedItem(
                    ItemId("fixture.item.$index.legacy"),
                    personal,
                    ItemKind.SHORTCUT_LEGACY,
                    TargetKey.LegacyShortcutKey,
                    workspace(),
                    false,
                    Availability.AVAILABLE,
                )
                items += CapturedItem(
                    ItemId("fixture.item.$index.widget"),
                    personal,
                    ItemKind.APPWIDGET,
                    TargetKey.WidgetKey(ComponentKey("component.widget.$index"), AppWidgetId(index * 2), personal),
                    workspace(x = 1),
                    false,
                    Availability.AVAILABLE,
                )
                items += CapturedItem(
                    ItemId("fixture.item.$index.custom-widget"),
                    personal,
                    ItemKind.CUSTOM_APPWIDGET,
                    TargetKey.WidgetKey(ComponentKey("component.custom-widget.$index"), AppWidgetId(index * 2 + 1), personal),
                    workspace(x = 2),
                    false,
                    Availability.AVAILABLE,
                )
            }

            3 -> {
                val folderId = FolderId("fixture.folder.$index")
                val first = app("fixture.item.$index.folder-member-a", personal, CapturedPlacement.FolderMember(FolderRef(folderId), 0))
                val second = app("fixture.item.$index.folder-member-b", personal, CapturedPlacement.FolderMember(FolderRef(folderId), 1), "component.other.$index")
                items += CapturedItem(
                    ItemId("fixture.item.$index.folder"), personal, ItemKind.FOLDER,
                    TargetKey.FolderKey(folderId), workspace(), false, Availability.AVAILABLE,
                    folderId = folderId, members = listOf(first.id, second.id),
                )
                items += first
                items += second
            }

            4 -> {
                val pairId = AppPairId("fixture.pair.$index")
                val first = app("fixture.item.$index.pair-member-a", personal, CapturedPlacement.AppPairMember(AppPairRef(pairId)))
                val second = app("fixture.item.$index.pair-member-b", personal, CapturedPlacement.AppPairMember(AppPairRef(pairId)), "component.other.$index")
                val snap = SnapPositionToken("fixture.snap.$index")
                items += CapturedItem(
                    ItemId("fixture.item.$index.pair"), personal, ItemKind.APP_PAIR,
                    TargetKey.AppPairKey(pairId), workspace(), false, Availability.AVAILABLE,
                    appPairId = pairId,
                    appPair = AppPairMetadata(
                        listOf(
                            AppPairMember(first.id, SplitStage.TOP_OR_LEFT, snap),
                            AppPairMember(second.id, SplitStage.BOTTOM_OR_RIGHT, snap),
                        ),
                    ),
                )
                items += first
                items += second
            }

            5 -> {
                items += app("fixture.item.$index.personal", personal, workspace(), "component.same-package.$index")
                items += app("fixture.item.$index.work", work, workspace(x = 1), "component.same-package.$index")
            }

            6 -> {
                items += app("fixture.item.$index.locked", personal, workspace()).copy(locked = true)
                items += app("fixture.item.$index.unavailable", work, workspace(x = 1)).copy(availability = Availability.UNAVAILABLE)
            }

            7 -> {
                items += app("fixture.item.$index.workspace", personal, workspace(page = pageCount - 1))
                items += app("fixture.item.$index.dock", personal, CapturedPlacement.Dock(0), "component.dock.$index")
            }
        }

        val runMode = if (template == 0 || index % 2 != 0) RunMode.IncrementalPlacement else RunMode.FullOrganization
        val unavailableCandidate = runMode == RunMode.IncrementalPlacement && template == 5
        val additions = if (runMode == RunMode.IncrementalPlacement) {
            val profile = if (template == 5) work else personal
            val kind = if (index % 4 == 1) CandidateKind.DEEP_SHORTCUT else CandidateKind.APPLICATION
            val additionCount = if (template == 7) 2 else 1
            List(additionCount) { additionIndex ->
                CandidateItem(
                    id = ItemId("fixture.addition.$index.$additionIndex"),
                    profile = profile,
                    kind = kind,
                    target = when (kind) {
                        CandidateKind.APPLICATION -> CandidateTarget.AppKey(ComponentKey("component.addition.$index.$additionIndex"), profile)

                        CandidateKind.DEEP_SHORTCUT -> CandidateTarget.ShortcutKey(
                            PackageName("component.addition.pkg.$index.$additionIndex"),
                            ShortcutId("fixture.addition.shortcut.$index.$additionIndex"),
                            profile,
                        )
                    },
                    availability = if (unavailableCandidate) Availability.UNAVAILABLE else Availability.AVAILABLE,
                    span = GridSpan(1, 1),
                )
            }
        } else {
            emptyList()
        }
        val categories = listOf(CategoryId("category.default.$index"), CategoryId("category.games.$index"))
        val signalItems = items.filter { it.kind == ItemKind.APPLICATION || it.kind == ItemKind.DEEP_SHORTCUT }.take(2)
        val signals = signalItems.flatMapIndexed { signalIndex, item ->
            if (template == 1 && signalIndex == 0) {
                listOf(
                    ClassificationSignal(item.id, SignalSource.S1, categories[1]),
                    ClassificationSignal(item.id, SignalSource.S1, categories[0]),
                    ClassificationSignal(item.id, SignalSource.S1, categories[1]),
                )
            } else {
                listOf(ClassificationSignal(item.id, SignalSource.S1, categories[0]))
            }
        }
        val existingTargets = items.map { item ->
            val role = if (template == 7 && item.id.value.endsWith(".workspace")) ExistingRole.Movable else ExistingRole.Preserved
            ExistingTargetMembership(item.id, role)
        }
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                RevisionId("fixture.revision.$index"),
                DeviceCapabilities(columns, rows, 3 + random.nextInt(6), 2 + random.nextInt(columns - 1), 2 + random.nextInt(rows - 1), orientation),
                pages,
                items,
            ),
            rules = RuleSemantics(
                RuleVersion("v2"),
                FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                DockPolicy.PRESERVE,
                OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),
            taxonomy = TaxonomyContract(TaxonomyVersion("fixture.taxonomy.$index"), categories, categories.first()),
            signals = ClassificationSignals(signals),
            targets = TargetSet(existingTargets, additions),
            runMode = runMode,
        )
        val expectation = when {
            unavailableCandidate -> {
                val candidate = additions.single()
                FixtureExpectation(
                    ExpectedOutcome.Impossible(
                        setOf(UnplacedReason.TARGET_UNAVAILABLE),
                        setOf(UnplacedItem(candidate.id, candidate.span, UnplacedReason.TARGET_UNAVAILABLE)),
                    ),
                )
            }

            template == 0 -> FixtureExpectation(ExpectedOutcome.Planned(expectedNewPageCount = 1))

            template == 6 -> FixtureExpectation(
                ExpectedOutcome.Planned(
                    requiredPreservations = mapOf(
                        ItemId("fixture.item.$index.unavailable") to PreserveReason.UNAVAILABLE_TARGET,
                    ),
                ),
                requiredWarningCodes = setOf(WarningCode.UNAVAILABLE_PRESERVED),
            )

            template == 1 -> FixtureExpectation(
                ExpectedOutcome.Planned(
                    requiredCategories = setOf(
                        app.lawnchair.organizer.planning.CategoryDecision(
                            signalItems.first().id,
                            categories[0],
                            SignalSource.S1,
                            Confidence.EXPLICIT,
                        ),
                    ),
                ),
            )

            else -> FixtureExpectation(ExpectedOutcome.Planned())
        }
        val structural = setOf(
            ContractCheck.EXPECTATION, ContractCheck.CONSERVATION, ContractCheck.BOUNDS,
            ContractCheck.NO_OVERLAP, ContractCheck.CONTAINER_INTEGRITY,
            ContractCheck.LOCK_PRESERVATION, ContractCheck.PROFILE_ISOLATION,
            ContractCheck.DETERMINISM, ContractCheck.INPUT_PERMUTATION,
        )
        return PlannerFixture(
            FixtureId("fixture.generated.$index"),
            input,
            expectation,
            if (runMode == RunMode.FullOrganization) structural + ContractCheck.IDEMPOTENCE else structural,
            Reproduction(seed, index, count),
        )
    }
}
