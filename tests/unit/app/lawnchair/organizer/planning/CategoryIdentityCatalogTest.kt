package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #336 planning-domain contract tests: identity ordering/equality, the
 * active catalog membership and built-in consistency invariant, the non-S1
 * user-defined provenance rejection, mixed-catalog folder formation and
 * category-contiguous ordering, rename-invariant canonical plan bytes, and
 * the built-in-only byte-equivalence fixture.
 */
class CategoryIdentityCatalogTest {

    private val builtInOther = CategoryId("OTHER")
    private val builtInGames = CategoryId("GAMES")
    private val builtInTools = CategoryId("TOOLS")

    private val userA = UserCategoryId("0a000000-0000-4000-8000-00000000000a")
    private val userB = UserCategoryId("1b000000-0000-4000-9000-00000000000b")

    private val taxonomy = TaxonomyContract(
        version = TaxonomyVersion("tv1"),
        allowedCategories = listOf(builtInOther, builtInGames, builtInTools),
        fallbackCategory = builtInOther,
    )

    private val device = DeviceCapabilities(4, 4, 4, 4, 4, Orientation.PORTRAIT)

    private val rules = RuleSemantics(
        version = RuleVersion("v2"),
        folderPolicy = FolderPolicy(2, NewFolderProfileScope.SAME_PROFILE_ONLY),
        dockPolicy = DockPolicy.PRESERVE,
        overflowPolicy = OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE,
        fallbackCategoryPolicy = FallbackCategoryPolicy.KEEP_AS_SINGLETON,
        organizationStrategy = StrategyId("CANONICAL_PAGE_COMPACT_V1"),
    )

    private fun catalog(vararg userDefined: UserDefinedCategory) = ActiveCategoryCatalog(taxonomy, userDefined.toList())

    private fun app(id: String, cell: GridCell = GridCell(0, 0), profile: ProfileId = ProfileId("p0")) = CapturedItem(
        id = ItemId(id),
        profile = profile,
        kind = ItemKind.APPLICATION,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), profile),
        placement = CapturedPlacement.Workspace(PageRef(PageId("p0")), cell, GridSpan(1, 1)),
        locked = false,
        availability = Availability.AVAILABLE,
    )

    private fun input(
        items: List<CapturedItem>,
        signals: List<ClassificationSignal> = emptyList(),
        catalog: ActiveCategoryCatalog = catalog(),
        runMode: RunMode = RunMode.FullOrganization,
    ): OrganizationInput = OrganizationInput(
        snapshot = LayoutSnapshot(
            revision = RevisionId("rev"),
            device = device,
            pages = listOf(Page(PageId("p0"), PageOrder(0))),
            items = items,
        ),
        rules = rules,
        taxonomy = taxonomy,
        catalog = catalog,
        signals = ClassificationSignals(signals),
        targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList()),
        runMode = runMode,
    )

    private val planner = DeterministicOrganizationPlanner()

    @Test
    fun userIdRejectsNonCanonicalForms() {
        assertThrows(IllegalArgumentException::class.java) { UserCategoryId("not-a-uuid") }
        assertThrows(IllegalArgumentException::class.java) { UserCategoryId("OTHER") }
        assertThrows(IllegalArgumentException::class.java) { UserCategoryId("0A000000-0000-4000-8000-00000000000A") }
        assertThrows(IllegalArgumentException::class.java) { UserCategoryId("0a000000-0000-4000-c000-00000000000a") }
        assertThrows(IllegalArgumentException::class.java) { UserCategoryId("") }
    }

    @Test
    fun identityOrderingIsBuiltInFirstThenUserIdByteOrderAndRenameInvariant() {
        // equality by ID alone
        assertEquals(CategoryIdentity.UserDefined(userA), CategoryIdentity.UserDefined(userA))
        assertNotEquals(CategoryIdentity.UserDefined(userA), CategoryIdentity.UserDefined(userB))
        // built-in before user-defined regardless of string order
        assertTrue(CategoryIdentity.BuiltIn(CategoryId("ZZZ")) < CategoryIdentity.UserDefined(userA))
        assertTrue(CategoryIdentity.UserDefined(userA) > CategoryIdentity.BuiltIn(builtInOther))
        // within kinds: UTF-8 byte order of the value/ID
        assertTrue(CategoryIdentity.BuiltIn(builtInGames) < CategoryIdentity.BuiltIn(builtInOther))
        assertTrue(CategoryIdentity.BuiltIn(builtInOther) < CategoryIdentity.BuiltIn(builtInTools))
        assertTrue(CategoryIdentity.UserDefined(userA) < CategoryIdentity.UserDefined(userB))
        // total order, no equality between namespaces
        assertNotEquals(CategoryIdentity.BuiltIn(CategoryId(userA.value)), CategoryIdentity.UserDefined(userA))
    }

    @Test
    fun canonicalValueKeepsBuiltInRawValueAndDiscriminatesUserDefined() {
        assertEquals("OTHER", CategoryIdentity.BuiltIn(builtInOther).canonicalValue)
        assertEquals("GAMES", CategoryIdentity.BuiltIn(builtInGames).canonicalValue)
        assertEquals("u:${userA.value}", CategoryIdentity.UserDefined(userA).canonicalValue)
    }

    @Test
    fun catalogMembershipCoversBothNamespacesAndKeepsBuiltInFallback() {
        val mixed = catalog(UserDefinedCategory(userA, "AI tools"))
        assertTrue(mixed.contains(CategoryIdentity.BuiltIn(builtInGames)))
        assertTrue(mixed.contains(CategoryIdentity.UserDefined(userA)))
        assertTrue(!mixed.contains(CategoryIdentity.UserDefined(userB)))
        assertTrue(!mixed.contains(CategoryIdentity.BuiltIn(CategoryId("NOT_THERE"))))
        assertEquals(CategoryIdentity.BuiltIn(builtInOther), mixed.fallback)
        assertEquals("AI tools", mixed.displayNameOf(userA))
        assertEquals(null, mixed.displayNameOf(userB))
    }

    @Test
    fun catalogRejectsDuplicateUserDefinedIds() {
        assertThrows(IllegalArgumentException::class.java) {
            ActiveCategoryCatalog(taxonomy, listOf(UserDefinedCategory(userA, "A"), UserDefinedCategory(userA, "B")))
        }
    }

    @Test
    fun catalogBuiltInDivergenceFromTaxonomyIsInvalidRules() {
        val items = listOf(app("a"))
        val divergentCatalog = ActiveCategoryCatalog(
            taxonomy.copy(allowedCategories = listOf(builtInOther)),
            emptyList(),
        )
        val result = planner.plan(input(items, catalog = divergentCatalog))
        val invalid = result.outcome as Rejected.Invalid
        assertTrue(invalid.reasons.any { it.code == RejectionCode.INVALID_RULES })
    }

    @Test
    fun nonS1UserDefinedCandidateIsRejectedAsTypedProvenanceFailure() {
        val items = listOf(app("a", GridCell(0, 0)), app("b", GridCell(1, 0)))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S1, CategoryIdentity.UserDefined(userA)),
            ClassificationSignal(ItemId("b"), SignalSource.S2, CategoryIdentity.UserDefined(userA)),
        )
        val result = planner.plan(input(items, signals, catalog(UserDefinedCategory(userA, "AI tools"))))
        val invalid = result.outcome as Rejected.Invalid
        assertTrue(invalid.reasons.any { it.code == RejectionCode.INVALID_CATEGORY_PROVENANCE })
        // The user-defined diagnostic param is present exactly once.
        val provenance = invalid.reasons.single { it.code == RejectionCode.INVALID_CATEGORY_PROVENANCE }
        assertEquals(DiagnosticParam.UserCategoryParam(userA), provenance.params.single())
    }

    @Test
    fun unknownUserDefinedCandidateIsUnknownCategoryWithUserParam() {
        val items = listOf(app("a"))
        val signals = listOf(
            ClassificationSignal(ItemId("a"), SignalSource.S1, CategoryIdentity.UserDefined(userB)),
        )
        val result = planner.plan(input(items, signals))
        val invalid = result.outcome as Rejected.Invalid
        val unknown = invalid.reasons.single { it.code == RejectionCode.UNKNOWN_CATEGORY }
        assertEquals(DiagnosticParam.UserCategoryParam(userB), unknown.params.single())
    }

    @Test
    fun userDefinedGroupsFormFoldersWithStableNamingAndCanonicalIdentityOrder() {
        val items = listOf(
            app("a1", GridCell(0, 0)),
            app("a2", GridCell(1, 0)),
            app("b1", GridCell(2, 0)),
            app("b2", GridCell(3, 0)),
            app("t1", GridCell(0, 1)),
            app("t2", GridCell(1, 1)),
        )
        val signals = listOf(
            ClassificationSignal(ItemId("a1"), SignalSource.S1, CategoryIdentity.UserDefined(userA)),
            ClassificationSignal(ItemId("a2"), SignalSource.S1, CategoryIdentity.UserDefined(userA)),
            ClassificationSignal(ItemId("b1"), SignalSource.S1, CategoryIdentity.UserDefined(userB)),
            ClassificationSignal(ItemId("b2"), SignalSource.S1, CategoryIdentity.UserDefined(userB)),
            ClassificationSignal(ItemId("t1"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInTools)),
            ClassificationSignal(ItemId("t2"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInTools)),
        )
        val result = planner.plan(input(items, signals, catalog(UserDefinedCategory(userA, "Commute"), UserDefinedCategory(userB, "AI tools"))))
        val planned = result.outcome as Planned

        // Built-in groups sort before user-defined groups; user-defined groups
        // order by stable ID byte order; fallback never forms a folder.
        val names = planned.newFolders.map { it.naming }
        assertEquals(
            listOf(
                FolderNaming.FromCategory(builtInTools),
                FolderNaming.FromUserCategory(userA),
                FolderNaming.FromUserCategory(userB),
            ),
            names,
        )
        val memberFolders = planned.placements
            .mapNotNull { (it.target as? PlacementTarget.FolderMember)?.folder as? NewFolderRef }
            .groupBy { it.ordinal }
        assertEquals(setOf(NewFolderOrdinal(0), NewFolderOrdinal(1), NewFolderOrdinal(2)), memberFolders.keys)
    }

    @Test
    fun categoryContiguousOrderPlacesUserDefinedAfterBuiltInAndFallbackLast() {
        val items = listOf(
            app("f1", GridCell(0, 0)),
            app("u1", GridCell(1, 0)),
            app("g1", GridCell(2, 0)),
            app("x1", GridCell(3, 0)),
        )
        val signals = listOf(
            ClassificationSignal(ItemId("f1"), SignalSource.S6, CategoryIdentity.BuiltIn(builtInOther)),
            ClassificationSignal(ItemId("u1"), SignalSource.S1, CategoryIdentity.UserDefined(userA)),
            ClassificationSignal(ItemId("g1"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInGames)),
        )
        val catalog = catalog(UserDefinedCategory(userA, "AI tools"))
        val contextInput = input(items, signals, catalog)
        val result = DeterministicOrganizationPlanner().plan(contextInput)
        val planned = result.outcome as Planned

        // Reorder the captured cells and confirm the CATEGORY_CONTIGUOUS
        // comparator orders built-in (non-fallback) first, then user-defined,
        // then fallback last — purely from the identity order.
        val decisions = planned.categories.associateBy { it.item }
        val context = FullRunContext(
            input = contextInput,
            classification = ClassificationOutput(decisions, emptyList()),
            strategy = checkNotNull(LayoutStrategyRegistry.definition(StrategyId("CATEGORY_CONTIGUOUS_V1"))),
            rolesById = contextInput.targets.existing.associate { it.item to it.role },
            itemById = contextInput.snapshot.items.associateBy { it.id },
            movableItems = contextInput.snapshot.items,
            allocator = Allocator(device, listOf(Page(PageId("p0"), PageOrder(0))), null, AllocationFault.NONE, CellTraversal.TOP_LEFT_ROW_MAJOR),
            pageOrderMap = mapOf(PageId("p0") to PageOrder(0)),
            preservationWarnings = emptyList(),
        )
        val ordered = contextInput.snapshot.items.sortedWith(FullRunExecution.categoryContiguousOrder(context))
        assertEquals(listOf(ItemId("g1"), ItemId("u1"), ItemId("f1"), ItemId("x1")), ordered.map { it.id })
    }

    @Test
    fun renameDoesNotChangeCanonicalPlanBytes() {
        val items = listOf(
            app("a1", GridCell(0, 0)),
            app("a2", GridCell(1, 0)),
            app("t1", GridCell(0, 1)),
            app("t2", GridCell(1, 1)),
        )
        val signals = listOf(
            ClassificationSignal(ItemId("a1"), SignalSource.S1, CategoryIdentity.UserDefined(userA)),
            ClassificationSignal(ItemId("a2"), SignalSource.S1, CategoryIdentity.UserDefined(userA)),
            ClassificationSignal(ItemId("t1"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInTools)),
            ClassificationSignal(ItemId("t2"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInTools)),
        )
        val before = planner.plan(input(items, signals, catalog(UserDefinedCategory(userA, "Old name"))))
        val after = planner.plan(input(items, signals, catalog(UserDefinedCategory(userA, "New name"))))

        // The canonical plan carries the stable ID only: the whole result is
        // equal, hence byte-identical in its canonical representation.
        assertEquals(before, after)
        val folders = (after.outcome as Planned).newFolders
        assertTrue(folders.any { it.naming == FolderNaming.FromUserCategory(userA) })
        assertTrue(folders.none { it.naming.toString().contains("New name") || it.naming.toString().contains("Old name") })
    }

    /**
     * Built-in-only runs must reproduce today's plans exactly: the identity
     * surface with an empty user-defined catalog produces the pre-336
     * canonical values (raw built-in IDs, `FromCategory` naming, fallback
     * `OTHER`), asserted here against literal expectations.
     */
    @Test
    fun builtInOnlyCatalogKeepsPre336CanonicalPlanValues() {
        val items = listOf(
            app("g1", GridCell(0, 0)),
            app("g2", GridCell(1, 0)),
            app("x1", GridCell(2, 0)),
        )
        val signals = listOf(
            ClassificationSignal(ItemId("g1"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInGames)),
            ClassificationSignal(ItemId("g2"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInGames)),
        )
        val result = planner.plan(input(items, signals, catalog(UserDefinedCategory(userA, "unused but present"))))
        val planned = result.outcome as Planned

        assertEquals(
            listOf(
                CategoryDecision(ItemId("g1"), CategoryIdentity.BuiltIn(builtInGames), SignalSource.S3, Confidence.RULE),
                CategoryDecision(ItemId("g2"), CategoryIdentity.BuiltIn(builtInGames), SignalSource.S3, Confidence.RULE),
                CategoryDecision(ItemId("x1"), CategoryIdentity.BuiltIn(builtInOther), SignalSource.S6, Confidence.FALLBACK),
            ),
            planned.categories.sortedBy { it.item },
        )
        assertEquals(
            listOf(FolderNaming.FromCategory(builtInGames)),
            planned.newFolders.map { it.naming },
        )
    }

    @Test
    fun deterministicOverMixedCatalogs() {
        val items = listOf(
            app("a1", GridCell(0, 0)),
            app("a2", GridCell(1, 0)),
            app("t1", GridCell(0, 1)),
            app("t2", GridCell(1, 1)),
        )
        val signals = listOf(
            ClassificationSignal(ItemId("a1"), SignalSource.S1, CategoryIdentity.UserDefined(userB)),
            ClassificationSignal(ItemId("a2"), SignalSource.S1, CategoryIdentity.UserDefined(userB)),
            ClassificationSignal(ItemId("t1"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInTools)),
            ClassificationSignal(ItemId("t2"), SignalSource.S3, CategoryIdentity.BuiltIn(builtInTools)),
        )
        val mixed = input(items, signals, catalog(UserDefinedCategory(userB, "AI tools")))
        assertEquals(planner.plan(mixed), planner.plan(mixed))
        // Permuting the catalog entry list (order of the user-defined list)
        // must not change the plan.
        val reordered = mixed.copy(catalog = mixed.catalog.copy(userDefined = mixed.catalog.userDefined.reversed()))
        assertEquals(planner.plan(mixed), planner.plan(reordered))
    }
}
