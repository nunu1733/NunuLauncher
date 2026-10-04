package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.MaterializationResult
import app.lawnchair.organizer.planning.harness.PostPlanMaterializer
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #451 (spec 451): duplicate launch-target handling, driven strictly
 * through the public `OrganizationPlanner.plan` seam.
 *
 * Protected contract (spec N-1..N-6, AC-1..AC-4): items sharing a launch
 * target (`TargetKey.AppKey`/`ShortcutKey` value equality) never join the same
 * newly formed folder; every item beyond the representative (first `ItemId` in
 * canonical UTF-8 byte order, where "10" < "2") is preserved at its captured
 * position with `DUPLICATE_LAUNCH_TARGET` and warned exactly once, below every
 * existing preservation predicate and regardless of run mode or strategy
 * folder capability. Determinism (P-09) and materialize-and-replan
 * idempotence (P-10) hold with duplicates present.
 */
class DuplicateLaunchTargetsTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()
    private val p0 = ProfileId("p0")
    private val p1 = ProfileId("p1")

    private fun rules(strategyId: StrategyId = StrategyId("CANONICAL_PAGE_COMPACT_V1")) = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = strategyId,
    )

    private fun taxonomy() = TaxonomyContract(
        TaxonomyVersion("tv1"),
        listOf(CategoryId("OTHER"), CategoryId("GAMES"), CategoryId("TOOLS")),
        CategoryId("OTHER"),
    )

    private fun device(columns: Int = 4, rows: Int = 4) = DeviceCapabilities(
        columns,
        rows,
        4,
        4,
        4,
        Orientation.PORTRAIT,
    )

    private fun app(
        id: String,
        component: String = "com.example.$id",
        x: Int = 0,
        y: Int = 0,
        page: String = "p0",
        profile: ProfileId = p0,
        locked: Boolean = false,
        availability: Availability = Availability.AVAILABLE,
        kind: ItemKind = ItemKind.APPLICATION,
        target: TargetKey = TargetKey.AppKey(ComponentKey(component), profile),
    ) = CapturedItem(
        id = ItemId(id),
        profile = profile,
        kind = kind,
        target = target,
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = availability,
    )

    private fun shortcut(
        id: String,
        packageName: String,
        shortcutId: String,
        x: Int = 0,
        y: Int = 0,
        page: String = "p0",
        profile: ProfileId = p0,
    ) = CapturedItem(
        id = ItemId(id),
        profile = profile,
        kind = ItemKind.DEEP_SHORTCUT,
        target = TargetKey.ShortcutKey(PackageName(packageName), ShortcutId(shortcutId), profile),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun pages(vararg ids: String) = ids.mapIndexed { index, id -> Page(PageId(id), PageOrder(index)) }

    private fun input(
        items: List<CapturedItem>,
        strategyId: StrategyId = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
        runMode: RunMode = RunMode.FullOrganization,
        device: DeviceCapabilities = device(),
        pageIds: List<String> = listOf("p0", "p1"),
    ): OrganizationInput {
        val taxonomy = taxonomy()
        val signals = items.mapNotNull { item ->
            if (item.kind != ItemKind.APPLICATION && item.kind != ItemKind.DEEP_SHORTCUT) return@mapNotNull null
            ClassificationSignal(item.id, SignalSource.S1, CategoryIdentity.BuiltIn(CategoryId("GAMES")))
        }
        return OrganizationInput(
            snapshot = LayoutSnapshot(
                RevisionId("rev"),
                device,
                pages(*pageIds.toTypedArray()),
                items,
                emptyList(),
            ),
            rules = rules(strategyId),
            taxonomy = taxonomy,
            catalog = ActiveCategoryCatalog(taxonomy, emptyList()),
            signals = ClassificationSignals(signals),
            targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
            runMode = runMode,
        )
    }

    private fun plan(input: OrganizationInput): Planned {
        val result = planner.plan(input)
        assertTrue("expected Planned but was ${result.outcome}", result.outcome is Planned)
        return result.outcome as Planned
    }

    private fun placement(planned: Planned, id: String): PlannedPlacement = planned.placements.single { it.item == ItemId(id) }

    private fun preservedReason(planned: Planned, id: String): PreserveReason {
        val disposition = placement(planned, id).disposition
        assertTrue("item $id was not preserved: $disposition", disposition is Disposition.Preserved)
        return (disposition as Disposition.Preserved).reason
    }

    private fun duplicateWarnings(planned: Planned): List<ItemId> = planned.warnings
        .filter { it.code == WarningCode.DUPLICATE_LAUNCH_TARGET }
        .map { warning ->
            val params = warning.params.filterIsInstance<DiagnosticParam.ItemParam>()
            assertEquals("DUPLICATE_LAUNCH_TARGET warning must carry exactly one ItemParam", 1, params.size)
            params.single().item
        }

    /** AC-1: within every planned new folder, member launch targets are unique. */
    private fun assertNewFolderTargetsUnique(input: OrganizationInput, planned: Planned) {
        val targetById = input.snapshot.items.associate { it.id to it.target }
        planned.newFolders.forEach { folder ->
            val targets = folder.members.map { targetById.getValue(it) }
            assertEquals(
                "folder ${folder.ordinal.value} contains duplicate launch targets: ${folder.members}",
                targets.size,
                targets.distinct().size,
            )
        }
    }

    /**
     * Spec scenario "同一カテゴリの重複が同じ新規フォルダに入らない" (AC-1/AC-3):
     * the representative ("10" — UTF-8 byte order puts "10" before "2") joins
     * the new folder; the surplus "2" is preserved at its captured position
     * and warned exactly once.
     */
    @Test
    fun duplicateSurplusIsExcludedFromTheNewFolderAndWarnedOnce() {
        val input = input(
            listOf(
                app("10", component = "com.example.photos", x = 0, y = 0, page = "p0"),
                app("3", component = "com.example.maps", x = 1, y = 0, page = "p0"),
                app("2", component = "com.example.photos", x = 0, y = 0, page = "p1"),
            ),
        )

        val planned = plan(input)

        assertEquals(1, planned.newFolders.size)
        // ItemId canonical order: "10" < "2" < "3", so the folder members are
        // the representative "10" and the non-duplicate "3".
        assertEquals(listOf(ItemId("10"), ItemId("3")), planned.newFolders.single().members)
        assertEquals(Disposition.Moved(PlacementCode.FOLDER_MEMBER), placement(planned, "10").disposition)
        assertEquals(Disposition.Moved(PlacementCode.FOLDER_MEMBER), placement(planned, "3").disposition)

        // Surplus keeps its captured cell, byte for byte.
        assertEquals(Disposition.Preserved(PreserveReason.DUPLICATE_LAUNCH_TARGET), placement(planned, "2").disposition)
        assertEquals(
            PlacementTarget.WorkspaceTarget(PageRef(PageId("p1")), GridCell(0, 0), GridSpan(1, 1)),
            placement(planned, "2").target,
        )

        assertEquals(listOf(ItemId("2")), duplicateWarnings(planned))
        assertNewFolderTargetsUnique(input, planned)
    }

    /** AC-3: one warning per surplus item; the representative is never warned. */
    @Test
    fun everySurplusItemIsWarnedExactlyOnceAndTheRepresentativeIsNot() {
        val input = input(
            listOf(
                app("1", component = "com.example.photos", x = 0, y = 0),
                app("2", component = "com.example.photos", x = 1, y = 0),
                app("3", component = "com.example.photos", x = 2, y = 0),
            ),
        )

        val planned = plan(input)

        // After excluding {"2","3"} the GAMES group is a single member, below
        // minGroupSize: no folder forms.
        assertEquals(0, planned.newFolders.size)
        assertEquals(listOf(ItemId("2"), ItemId("3")), duplicateWarnings(planned).sorted())
        assertTrue(preservedReason(planned, "2") == PreserveReason.DUPLICATE_LAUNCH_TARGET)
        assertTrue(preservedReason(planned, "3") == PreserveReason.DUPLICATE_LAUNCH_TARGET)
        assertTrue(preservedReason(planned, "1") != PreserveReason.DUPLICATE_LAUNCH_TARGET)
    }

    /**
     * AC-3: appending the new code last keeps the canonical order of the
     * existing warning codes (LEGACY_SHORTCUT_REVIEW < UNAVAILABLE_PRESERVED
     * < DUPLICATE_LAUNCH_TARGET, by enum ordinal).
     */
    @Test
    fun newWarningCodeAppendedLastKeepsExistingCanonicalWarningOrder() {
        val input = input(
            listOf(
                CapturedItem(
                    id = ItemId("legacy"),
                    profile = p0,
                    kind = ItemKind.SHORTCUT_LEGACY,
                    target = TargetKey.LegacyShortcutKey,
                    placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 3), GridSpan(1, 1)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
                app("unavail", component = "com.example.unavail", x = 3, y = 0, availability = Availability.UNAVAILABLE),
                app("1", component = "com.example.photos", x = 0, y = 0),
                app("2", component = "com.example.photos", x = 1, y = 0),
            ),
        )

        val planned = plan(input)

        assertEquals(
            listOf(
                WarningCode.LEGACY_SHORTCUT_REVIEW,
                WarningCode.UNAVAILABLE_PRESERVED,
                WarningCode.DUPLICATE_LAUNCH_TARGET,
            ),
            planned.warnings.map { it.code },
        )
    }

    /**
     * Spec scenario "重複の一方が既に保全predicateに掛かる" (AC-4/N-3): the
     * locked duplicate wins LOCKED; the movable duplicate is suppressed as
     * DUPLICATE_LAUNCH_TARGET and neither joins a folder.
     */
    @Test
    fun lockedRepresentativeKeepsLockReasonAndSuppressesTheMovableDuplicate() {
        val input = input(
            listOf(
                app("a", component = "com.example.photos", x = 0, y = 0, locked = true),
                app("b", component = "com.example.photos", x = 0, y = 0, page = "p1"),
            ),
        )

        val planned = plan(input)

        assertEquals(0, planned.newFolders.size)
        assertEquals(PreserveReason.LOCKED, preservedReason(planned, "a"))
        assertEquals(PreserveReason.DUPLICATE_LAUNCH_TARGET, preservedReason(planned, "b"))
        assertEquals(
            PlacementTarget.WorkspaceTarget(PageRef(PageId("p1")), GridCell(0, 0), GridSpan(1, 1)),
            placement(planned, "b").target,
        )
        assertEquals(listOf(ItemId("b")), duplicateWarnings(planned))
        assertTrue(planned.placements.none { it.disposition is Disposition.Moved })
    }

    /** AC-4/N-3: UNAVAILABLE_TARGET also outranks DUPLICATE_LAUNCH_TARGET. */
    @Test
    fun unavailableDuplicateKeepsItsStrongerReason() {
        val input = input(
            listOf(
                app("u", component = "com.example.photos", x = 0, y = 0, availability = Availability.UNAVAILABLE),
                app("v", component = "com.example.photos", x = 1, y = 0),
            ),
        )

        val planned = plan(input)

        assertEquals(PreserveReason.UNAVAILABLE_TARGET, preservedReason(planned, "u"))
        assertEquals(PreserveReason.DUPLICATE_LAUNCH_TARGET, preservedReason(planned, "v"))
        assertEquals(0, planned.newFolders.size)
    }

    /**
     * Spec scenario "重複の一方が既に保全predicateに掛かる" — the structural
     * variant (N-1 contract extension): an existing folder member is the
     * representative, so the top-level duplicate is move-inhibited instead of
     * joining a new folder.
     */
    @Test
    fun structuralMemberRepresentativeSuppressesTheTopLevelDuplicate() {
        val folderId = FolderId("f0")
        val input = input(
            listOf(
                CapturedItem(
                    id = ItemId("f0"),
                    profile = p0,
                    kind = ItemKind.FOLDER,
                    target = TargetKey.FolderKey(folderId),
                    placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(2, 2)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                    folderId = folderId,
                    members = listOf(ItemId("m")),
                ),
                CapturedItem(
                    id = ItemId("m"),
                    profile = p0,
                    kind = ItemKind.APPLICATION,
                    target = TargetKey.AppKey(ComponentKey("com.example.photos"), p0),
                    placement = CapturedPlacement.FolderMember(FolderRef(folderId), rank = 0),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
                app("t", component = "com.example.photos", x = 3, y = 0),
            ),
        )

        val planned = plan(input)

        assertEquals(PreserveReason.STRUCTURAL, preservedReason(planned, "m"))
        assertEquals(PreserveReason.DUPLICATE_LAUNCH_TARGET, preservedReason(planned, "t"))
        assertEquals(0, planned.newFolders.size)
        assertEquals(listOf(ItemId("t")), duplicateWarnings(planned))
    }

    /**
     * Spec scenario "除外でgroupが最小サイズを下回る" (AC-4/P-05): with exactly
     * two same-category members where one is surplus, the remaining single
     * member is below minGroupSize and no folder forms.
     */
    @Test
    fun exclusionBelowMinGroupSizePreventsFolderFormation() {
        val input = input(
            listOf(
                app("1", component = "com.example.photos", x = 0, y = 0),
                app("2", component = "com.example.photos", x = 1, y = 0),
            ),
        )

        val planned = plan(input)

        assertEquals(0, planned.newFolders.size)
        assertEquals(0, planned.newPages.size)
        assertEquals(PreserveReason.DUPLICATE_LAUNCH_TARGET, preservedReason(planned, "2"))
        assertTrue(preservedReason(planned, "1") != PreserveReason.DUPLICATE_LAUNCH_TARGET)
        assertEquals(listOf(ItemId("2")), duplicateWarnings(planned))
    }

    /**
     * Spec scenario "スコープ合成整理とincremental" — incremental run (AC-4):
     * every captured item stays at its captured position; only the surplus's
     * reason vocabulary changes from ALREADY_CANONICAL to
     * DUPLICATE_LAUNCH_TARGET.
     */
    @Test
    fun incrementalRunKeepsAllCapturedPositionsAndRelabelsOnlyTheSurplusReason() {
        val input = input(
            listOf(
                app("1", component = "com.example.photos", x = 0, y = 0),
                app("2", component = "com.example.photos", x = 1, y = 0),
                app("x", component = "com.example.x", x = 2, y = 0),
            ),
            runMode = RunMode.IncrementalPlacement,
        )

        val planned = plan(input)

        assertEquals(0, planned.newFolders.size)
        assertEquals(0, planned.newPages.size)
        input.snapshot.items.forEach { item ->
            val placement = planned.placements.single { it.item == item.id }
            assertEquals(Disposition.Preserved::class, placement.disposition::class)
            assertEquals(
                PlacementTarget.WorkspaceTarget(PageRef(PageId("p0")), item.placement.cell(), GridSpan(1, 1)),
                placement.target,
            )
        }
        assertEquals(PreserveReason.ALREADY_CANONICAL, preservedReason(planned, "1"))
        assertEquals(PreserveReason.ALREADY_CANONICAL, preservedReason(planned, "x"))
        assertEquals(PreserveReason.DUPLICATE_LAUNCH_TARGET, preservedReason(planned, "2"))
        assertEquals(listOf(ItemId("2")), duplicateWarnings(planned))
    }

    private fun CapturedPlacement.cell(): GridCell = (this as CapturedPlacement.Workspace).cell

    /**
     * Spec scenario "対象外のkindとcross-profile" (AC-1): widgets (same
     * provider, distinct ids), legacy shortcuts (shared object key), and a
     * same-component pair across profiles never produce duplicate detection,
     * exclusion, or warnings.
     */
    @Test
    fun outOfScopeKindsAndCrossProfilePairsAreNeverDuplicates() {
        val input = input(
            listOf(
                CapturedItem(
                    id = ItemId("w1"),
                    profile = p0,
                    kind = ItemKind.APPWIDGET,
                    target = TargetKey.WidgetKey(ComponentKey("com.example.widget"), AppWidgetId(1), p0),
                    placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 0), GridSpan(2, 2)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
                CapturedItem(
                    id = ItemId("w2"),
                    profile = p0,
                    kind = ItemKind.APPWIDGET,
                    target = TargetKey.WidgetKey(ComponentKey("com.example.widget"), AppWidgetId(2), p0),
                    placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(2, 2), GridSpan(2, 2)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
                CapturedItem(
                    id = ItemId("l1"),
                    profile = p0,
                    kind = ItemKind.SHORTCUT_LEGACY,
                    target = TargetKey.LegacyShortcutKey,
                    placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(0, 2), GridSpan(1, 1)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
                CapturedItem(
                    id = ItemId("l2"),
                    profile = p0,
                    kind = ItemKind.SHORTCUT_LEGACY,
                    target = TargetKey.LegacyShortcutKey,
                    placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), GridCell(1, 2), GridSpan(1, 1)),
                    locked = false,
                    availability = Availability.AVAILABLE,
                ),
                app("cp1", component = "com.example.crossprofile", x = 2, y = 0),
                app("cp2", component = "com.example.crossprofile", x = 3, y = 0, profile = p1),
            ),
        )

        val planned = plan(input)

        assertTrue(
            "out-of-scope kinds and cross-profile pairs must not warn",
            planned.warnings.none { it.code == WarningCode.DUPLICATE_LAUNCH_TARGET },
        )
        planned.placements.forEach { placement ->
            assertTrue(
                "unexpected DUPLICATE_LAUNCH_TARGET preservation for ${placement.item}",
                placement.disposition !is Disposition.Preserved ||
                    (placement.disposition as Disposition.Preserved).reason != PreserveReason.DUPLICATE_LAUNCH_TARGET,
            )
        }
        assertEquals(0, planned.newFolders.size)
    }

    /**
     * Spec scenario "materialize後の再実行" (AC-2/P-10): after applying the
     * plan, the replan moves nothing, creates no pages or folders, keeps every
     * target, and preserves the surplus with the same DUPLICATE_LAUNCH_TARGET
     * reason. Covered for both the folder-forming shape and the
     * min-group-boundary shape.
     */
    @Test
    fun materializedReplanMovesNothingAndKeepsDuplicateSurplusReason() {
        val folderForming = input(
            listOf(
                app("10", component = "com.example.photos", x = 0, y = 0, page = "p0"),
                app("3", component = "com.example.maps", x = 1, y = 0, page = "p0"),
                app("2", component = "com.example.photos", x = 0, y = 0, page = "p1"),
            ),
        )
        assertIdempotentReplan(folderForming, surplusId = "2")

        val minBoundary = input(
            listOf(
                app("1", component = "com.example.photos", x = 0, y = 0),
                app("2", component = "com.example.photos", x = 1, y = 0),
            ),
        )
        assertIdempotentReplan(minBoundary, surplusId = "2")
    }

    /**
     * Plan Risk ("surplus itemが必ず1個のPlannedPlacementを持つ"): after
     * materialization the replan keeps exactly one placement per item with an
     * unchanged target, creates nothing, and the surplus keeps its reason.
     */
    private fun assertIdempotentReplan(input: OrganizationInput, surplusId: String) {
        val first = plan(input)
        val materialized = PostPlanMaterializer.materialize(input, first)
        val materializedInput = (materialized as? MaterializationResult.Success)?.input
            ?: error("materialization failed: $materialized")

        val replanned = plan(materializedInput)

        assertTrue("replan moved an item", replanned.placements.none { it.disposition is Disposition.Moved })
        assertTrue("replan created pages", replanned.newPages.isEmpty())
        assertTrue("replan created folders", replanned.newFolders.isEmpty())
        assertEquals(
            "replan returned a different item set",
            materializedInput.snapshot.items.map { it.id }.sorted(),
            replanned.placements.map { it.item }.sorted(),
        )
        val capturedTargetById = materializedInput.snapshot.items.associate { it.id to capturedToOutput(it.placement) }
        replanned.placements.forEach { placement ->
            assertEquals(
                "replan changed the target of ${placement.item}",
                capturedTargetById.getValue(placement.item),
                placement.target,
            )
        }
        assertEquals(
            PreserveReason.DUPLICATE_LAUNCH_TARGET,
            preservedReason(replanned, surplusId),
        )
    }

    /**
     * AC-2/P-09: the same input yields the same complete result across
     * invocations, default locales, and threads.
     */
    @Test
    fun duplicatePlanningIsDeterministicAcrossInvocationsLocaleAndThreads() {
        val input = input(
            listOf(
                app("10", component = "com.example.photos", x = 0, y = 0, page = "p0"),
                app("3", component = "com.example.maps", x = 1, y = 0, page = "p0"),
                app("2", component = "com.example.photos", x = 0, y = 0, page = "p1"),
                shortcut("s1", packageName = "com.example.photos", shortcutId = "share", x = 2, y = 0),
                shortcut("s2", packageName = "com.example.photos", shortcutId = "share", x = 3, y = 0),
            ),
        )

        val first = planner.plan(input)
        assertEquals("same input must produce the same result", first, planner.plan(input))

        val originalLocale = Locale.getDefault()
        try {
            Locale.setDefault(Locale.JAPAN)
            val ja = planner.plan(input)
            Locale.setDefault(Locale.ENGLISH)
            val en = planner.plan(input)
            assertEquals("locale must not affect the plan", first, ja)
            assertEquals("locale must not affect the plan", first, en)
        } finally {
            Locale.setDefault(originalLocale)
        }

        val pool = Executors.newFixedThreadPool(2)
        val ready = CountDownLatch(2)
        val futures = (0 until 2).map {
            pool.submit(
                java.util.concurrent.Callable<PlanningResult> {
                    ready.countDown()
                    ready.await(5, TimeUnit.SECONDS)
                    planner.plan(input)
                },
            )
        }
        val results = futures.map { it.get(30, TimeUnit.SECONDS) }
        pool.shutdown()
        assertTrue(pool.awaitTermination(5, TimeUnit.SECONDS))
        results.forEach { assertEquals("thread scheduling must not affect the plan", first, it) }
    }
}
