package app.lawnchair.organizer.application.actions

import app.lawnchair.organizer.application.adapter.RecordingFolderTitleResolver
import app.lawnchair.organizer.application.public.ApplyAction
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ClassificationSignal
import app.lawnchair.organizer.planning.ClassificationSignals
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.DockPolicy
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FallbackCategoryPolicy
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.FolderPolicy
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.NewFolderProfileScope
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.OverflowPolicy
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PlacementCode
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RuleSemantics
import app.lawnchair.organizer.planning.RuleVersion
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.StrategyId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import app.lawnchair.organizer.planning.TaxonomyContract
import app.lawnchair.organizer.planning.TaxonomyVersion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #201 (FN-AC-04, review Major 2): a single category with more movable
 * apps than the folder capacity is split into several planned folders by the
 * real planner, and the materializer must resolve every split half to the
 * exact same user-facing title — no ordinals, no numeric suffixes.
 */
class SplitFolderNamingEndToEndTest {

    @Test
    fun capacityOverflowSplitsOneCategoryIntoFoldersWithIdenticalTitles() {
        val profile = ProfileId("personal")
        val planner = DeterministicOrganizationPlanner()
        val resolver = RecordingFolderTitleResolver()

        // Folder capacity = 2x2 = 4. Nine COMMUNICATION apps overflow into
        // 4 + 4 + 1, and the trailing single falls under minGroupSize=2, so the
        // planner merges it into the last folder via partitionMembers.
        val ids = (1..9).map { ItemId("app.c$it") }
        val capturedItems = ids.mapIndexed { index, id ->
            CapturedItem(
                id = id,
                profile = profile,
                kind = app.lawnchair.organizer.planning.ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.c${id.value}/.Main"), profile),
                placement = CapturedPlacement.Workspace(
                    page = app.lawnchair.organizer.planning.PageRef(PageId("p0")),
                    cell = GridCell(index % 4, index / 4),
                    span = GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }
        val taxonomy = TaxonomyContract(
            version = TaxonomyVersion("tv1"),
            allowedCategories = listOf(
                app.lawnchair.organizer.planning.CategoryId("COMMUNICATION"),
                app.lawnchair.organizer.planning.CategoryId("OTHER"),
            ),
            fallbackCategory = app.lawnchair.organizer.planning.CategoryId("OTHER"),
        )
        val ruleVersion = RuleVersion("v2")
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = app.lawnchair.organizer.planning.RevisionId("snapshot"),
                device = DeviceCapabilities(4, 5, 4, 2, 2, Orientation.PORTRAIT),
                pages = listOf(Page(PageId("p0"), PageOrder(0))),
                items = capturedItems,
            ),
            rules = RuleSemantics(
                version = ruleVersion,
                folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                dockPolicy = DockPolicy.PRESERVE,
                overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),
            taxonomy = taxonomy,
            signals = ClassificationSignals(
                entries = ids.map {
                    ClassificationSignal(
                        item = it,
                        source = SignalSource.S2,
                        candidate = app.lawnchair.organizer.planning.CategoryId("COMMUNICATION"),
                    )
                },
            ),
            targets = TargetSet(
                existing = ids.map { ExistingTargetMembership(it, app.lawnchair.organizer.planning.ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )

        val result = planner.plan(input)
        val planned = result.outcome as Planned
        assertTrue(
            "capacity overflow must produce more than one folder, got ${planned.newFolders.size}",
            planned.newFolders.size >= 2,
        )
        val splitCount = planned.newFolders.size

        // The materializer joins the snapshot to the canonical capture, so the
        // source state must carry exactly the fixture's items (and the
        // snapshot's revision must be the canonical revision of that state).
        val sourceItems = ids.mapIndexed { index, id ->
            app.lawnchair.organizer.application.canonical.CanonicalFixtures.appItem(
                itemId = id.value,
                cell = GridCell(index % 4, index / 4),
                target = TargetKey.AppKey(ComponentKey("com.example.${id.value}/.Main"), profile),
            )
        }
        val base = app.lawnchair.organizer.application.canonical.CanonicalFixtures.state()
        val sourceState = app.lawnchair.organizer.application.public.LayoutState(
            pages = base.pages,
            profiles = base.profiles,
            deviceCapabilities = app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 4, 2, 2, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT),
            items = sourceItems,
        )
        val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState, resolver)
        val plan = (materialized as OrganizationPlanMaterializer.Result.Ready).plan

        assertEquals(
            "resolve must be called exactly once per split folder",
            splitCount,
            resolver.resolved.size,
        )
        val insertTitles = plan.actions.filterIsInstance<ApplyAction.Insert>()
            .map { (it.intended.title as OptionalText.Present).value }
        assertEquals(splitCount, insertTitles.size)
        assertEquals(
            "all split halves must carry the identical resolved title",
            List(splitCount) { insertTitles.first() },
            insertTitles,
        )
        assertTrue(insertTitles.first().isNotBlank())
        for (title in insertTitles) {
            assertFalse(
                "split titles must not embed ordinals or numeric suffixes: $title",
                title.any { it.isDigit() },
            )
        }
        for (folder in plan.newFolders) {
            assertEquals(
                FolderNaming.FromCategory(app.lawnchair.organizer.planning.CategoryId("COMMUNICATION")),
                folder.naming,
            )
        }
    }

    @Test
    fun dispositionIsFolderMemberForAllSplitMembers() {
        // Sanity guard: the split fixture really routes members through the
        // folder-member path (spec 12), not singleton placement.
        val ids = (1..5).map { ItemId("app.c$it") }
        val profile = ProfileId("personal")
        val capturedItems = ids.mapIndexed { index, id ->
            CapturedItem(
                id = id,
                profile = profile,
                kind = app.lawnchair.organizer.planning.ItemKind.APPLICATION,
                target = TargetKey.AppKey(ComponentKey("com.example.s${id.value}/.Main"), profile),
                placement = CapturedPlacement.Workspace(
                    app.lawnchair.organizer.planning.PageRef(PageId("p0")),
                    GridCell(index % 4, index / 4),
                    GridSpan(1, 1),
                ),
                locked = false,
                availability = Availability.AVAILABLE,
            )
        }
        val taxonomy = TaxonomyContract(
            version = TaxonomyVersion("tv1"),
            allowedCategories = listOf(
                app.lawnchair.organizer.planning.CategoryId("COMMUNICATION"),
                app.lawnchair.organizer.planning.CategoryId("OTHER"),
            ),
            fallbackCategory = app.lawnchair.organizer.planning.CategoryId("OTHER"),
        )
        val input = OrganizationInput(
            snapshot = LayoutSnapshot(
                revision = app.lawnchair.organizer.planning.RevisionId("snapshot"),
                device = DeviceCapabilities(4, 5, 4, 2, 2, Orientation.PORTRAIT),
                pages = listOf(Page(PageId("p0"), PageOrder(0))),
                items = capturedItems,
            ),
            rules = RuleSemantics(
                version = RuleVersion("v2"),
                folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
                dockPolicy = DockPolicy.PRESERVE,
                overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
                fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
                organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
            ),
            taxonomy = taxonomy,
            signals = ClassificationSignals(
                entries = ids.map {
                    ClassificationSignal(
                        item = it,
                        source = SignalSource.S2,
                        candidate = app.lawnchair.organizer.planning.CategoryId("COMMUNICATION"),
                    )
                },
            ),
            targets = TargetSet(
                existing = ids.map { ExistingTargetMembership(it, app.lawnchair.organizer.planning.ExistingRole.Movable) },
                additions = emptyList(),
            ),
            runMode = RunMode.FullOrganization,
        )
        val result = DeterministicOrganizationPlanner().plan(input)
        val planned = result.outcome as Planned

        assertTrue(planned.newFolders.size >= 2)
        val folderMembers = planned.placements.count {
            it.disposition == Disposition.Moved(PlacementCode.FOLDER_MEMBER)
        }
        assertEquals(ids.size, folderMembers)
    }
}
