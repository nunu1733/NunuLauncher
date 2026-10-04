package app.lawnchair.organizer.planning

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ContractShapeTest {

    @Test
    fun textHandlesUseExactValueEquality() {
        assertEquals(ItemId("app.browser"), ItemId("app.browser"))
        assertNotEquals(ItemId("a"), ItemId("b"))
        assertEquals(ProfileId("work"), ProfileId("work"))
        assertNotEquals(ProfileId("work"), ProfileId("personal"))
        assertEquals(CategoryId("TOOLS"), CategoryId("TOOLS"))
    }

    @Test
    fun textHandlesOrderByUnsignedLexicographicUtf8Bytes() {
        assertTrue(ItemId("a").compareTo(ItemId("b")) < 0)
        assertTrue(ItemId("b").compareTo(ItemId("a")) > 0)
        assertEquals(0, ItemId("x").compareTo(ItemId("x")))
    }

    @Test
    fun textHandleShorterPrefixSortsFirst() {
        assertTrue(ItemId("ab").compareTo(ItemId("abc")) < 0)
        assertTrue(ItemId("abc").compareTo(ItemId("ab")) > 0)
    }

    @Test
    fun integerCodeHandlesUseSignedNumericOrder() {
        assertTrue(KindCode(-1).compareTo(KindCode(0)) < 0)
        assertTrue(KindCode(0).compareTo(KindCode(-1)) > 0)
        assertTrue(ContainerCode(5).compareTo(ContainerCode(3)) > 0)
        assertTrue(AppWidgetId(2).compareTo(AppWidgetId(10)) < 0)
    }

    @Test
    fun nonNegativeHandlesRejectNegativeConstruction() {
        assertThrows(IllegalArgumentException::class.java) { PageOrder(-1) }
        assertThrows(IllegalArgumentException::class.java) { NewPageOrdinal(-1) }
        assertThrows(IllegalArgumentException::class.java) { NewFolderOrdinal(-1) }
    }

    @Test
    fun pageOrderIsUnboundedCanonicalNumericValue() {
        val formerIntMaximum = PageOrder(Int.MAX_VALUE)
        val next = PageOrder("2147483648")

        assertTrue(formerIntMaximum < next)
        assertTrue(PageOrder("9") < PageOrder("10"))
        assertTrue(PageOrder("99999999999999999999") < PageOrder("100000000000000000000"))
        assertEquals(next, formerIntMaximum + 1)
        assertEquals(PageOrder("2147483650"), formerIntMaximum + 3)
        assertEquals(PageOrder("1000"), PageOrder("999") + 1)
        assertEquals(PageOrder(7), PageOrder("7"))
    }

    @Test
    fun pageOrderRejectsNonCanonicalText() {
        listOf("", "-1", "+1", "01", " 1", "1 ").forEach { invalid ->
            assertThrows(IllegalArgumentException::class.java) { PageOrder(invalid) }
        }
    }

    @Test
    fun emptyTextHandleConstructionRejected() {
        assertThrows(IllegalArgumentException::class.java) { ItemId("") }
        assertThrows(IllegalArgumentException::class.java) { CategoryId("") }
        assertThrows(IllegalArgumentException::class.java) { RevisionId("") }
    }

    @Test
    fun capturedAndPlanLocalIdentitiesAreDistinctTypes() {
        val pageRef: PageTargetRef = PageRef(PageId("p0"))
        val newPageRef: PageTargetRef = NewPageRef(NewPageOrdinal(0))
        assertNotEquals(pageRef, newPageRef)
        assertTrue(pageRef is PageRef)
        assertTrue(newPageRef is NewPageRef)

        val folderRef: FolderTargetRef = FolderRef(FolderId("f0"))
        val newFolderRef: FolderTargetRef = NewFolderRef(NewFolderOrdinal(0))
        assertNotEquals(folderRef, newFolderRef)
        assertTrue(folderRef is FolderRef)
        assertTrue(newFolderRef is NewFolderRef)
    }

    @Test
    fun gridTypesExposeNamedFields() {
        val cell = GridCell(x = 1, y = 2)
        assertEquals(1, cell.x)
        assertEquals(2, cell.y)

        val span = GridSpan(width = 3, height = 4)
        assertEquals(3, span.width)
        assertEquals(4, span.height)
    }

    @Test
    fun unknownItemKindSentinelIsConstructible() {
        val unknown = ItemKind.Unknown(KindCode(99))
        assertEquals(KindCode(99), unknown.code)
        assertTrue(unknown is ItemKind)
    }

    @Test
    fun unsupportedContainerSentinelIsConstructible() {
        val unsupported = CapturedPlacement.UnsupportedContainer(ContainerCode(7))
        assertEquals(ContainerCode(7), unsupported.code)
        assertTrue(unsupported is CapturedPlacement)
    }

    @Test
    fun legacyShortcutKeyIsUnitVariant() {
        assertEquals(TargetKey.LegacyShortcutKey, TargetKey.LegacyShortcutKey)
        assertTrue(TargetKey.LegacyShortcutKey is TargetKey)
    }

    @Test
    fun everyCapturedPlacementVariantIsConstructible() {
        val workspace = CapturedPlacement.Workspace(
            page = PageRef(PageId("p0")),
            cell = GridCell(0, 0),
            span = GridSpan(1, 1),
        )
        assertTrue(workspace is CapturedPlacement)
        assertEquals(PageRef(PageId("p0")), workspace.page)

        val dock = CapturedPlacement.Dock(rank = 0)
        assertTrue(dock is CapturedPlacement)
        assertEquals(0, dock.rank)

        val folderMember = CapturedPlacement.FolderMember(
            folder = FolderRef(FolderId("f0")),
            rank = 0,
        )
        assertTrue(folderMember is CapturedPlacement)
        assertEquals(FolderRef(FolderId("f0")), folderMember.folder)

        val appPairMember = CapturedPlacement.AppPairMember(
            pair = AppPairRef(AppPairId("ap1")),
        )
        assertTrue(appPairMember is CapturedPlacement)
        assertEquals(AppPairRef(AppPairId("ap1")), appPairMember.pair)

        val unsupported = CapturedPlacement.UnsupportedContainer(ContainerCode(42))
        assertTrue(unsupported is CapturedPlacement)
        assertEquals(ContainerCode(42), unsupported.code)
    }

    @Test
    fun everyTargetKeyVariantIsConstructible() {
        val appKey = TargetKey.AppKey(
            component = ComponentKey("com.example.app"),
            profile = ProfileId("p0"),
        )
        assertTrue(appKey is TargetKey)

        val shortcutKey = TargetKey.ShortcutKey(
            packageName = PackageName("com.example.app"),
            shortcutId = ShortcutId("shortcut_1"),
            profile = ProfileId("p0"),
        )
        assertTrue(shortcutKey is TargetKey)

        val legacy = TargetKey.LegacyShortcutKey
        assertTrue(legacy is TargetKey)

        val widgetKey = TargetKey.WidgetKey(
            provider = ComponentKey("com.example.widget"),
            appWidgetId = AppWidgetId(100),
            profile = ProfileId("p0"),
        )
        assertTrue(widgetKey is TargetKey)

        val folderKey = TargetKey.FolderKey(folderId = FolderId("f0"))
        assertTrue(folderKey is TargetKey)

        val appPairKey = TargetKey.AppPairKey(appPairId = AppPairId("ap1"))
        assertTrue(appPairKey is TargetKey)
    }

    @Test
    fun capturedApplicationIsConstructible() {
        val item = CapturedItem(
            id = ItemId("app.calculator"),
            profile = ProfileId("p0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(
                component = ComponentKey("com.example.calc"),
                profile = ProfileId("p0"),
            ),
            placement = CapturedPlacement.Workspace(
                page = PageRef(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        assertEquals(ItemKind.APPLICATION, item.kind)
        assertEquals(ItemId("app.calculator"), item.id)
    }

    @Test
    fun capturedFolderWithMembershipIsConstructible() {
        val childId = ItemId("child.app")
        val folder = CapturedItem(
            id = ItemId("folder.1"),
            profile = ProfileId("p0"),
            kind = ItemKind.FOLDER,
            target = TargetKey.FolderKey(folderId = FolderId("f0")),
            placement = CapturedPlacement.Workspace(
                page = PageRef(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(2, 2),
            ),
            locked = false,
            availability = Availability.AVAILABLE,
            folderId = FolderId("f0"),
            members = listOf(childId),
        )
        assertEquals(ItemKind.FOLDER, folder.kind)
        assertEquals(FolderId("f0"), folder.folderId)
        assertEquals(listOf(childId), folder.members)
    }

    @Test
    fun capturedAppPairWithTwoMembersIsConstructible() {
        val member1 = AppPairMember(
            item = ItemId("app.left"),
            stage = SplitStage.TOP_OR_LEFT,
            snapPosition = SnapPositionToken("snap-a"),
        )
        val member2 = AppPairMember(
            item = ItemId("app.right"),
            stage = SplitStage.BOTTOM_OR_RIGHT,
            snapPosition = SnapPositionToken("snap-a"),
        )
        val metadata = AppPairMetadata(members = listOf(member1, member2))
        assertEquals(2, metadata.members.size)
        assertEquals(SplitStage.TOP_OR_LEFT, metadata.members[0].stage)
        assertEquals(SplitStage.BOTTOM_OR_RIGHT, metadata.members[1].stage)
        assertEquals(SnapPositionToken("snap-a"), metadata.members[0].snapPosition)
        assertNotNull(metadata.members[1].snapPosition)

        val pair = CapturedItem(
            id = ItemId("app.pair"),
            profile = ProfileId("p0"),
            kind = ItemKind.APP_PAIR,
            target = TargetKey.AppPairKey(appPairId = AppPairId("ap1")),
            placement = CapturedPlacement.Workspace(
                page = PageRef(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(2, 1),
            ),
            locked = false,
            availability = Availability.AVAILABLE,
            appPairId = AppPairId("ap1"),
            appPair = metadata,
        )
        assertEquals(ItemKind.APP_PAIR, pair.kind)
        assertEquals(AppPairId("ap1"), pair.appPairId)
    }

    @Test
    fun bothCandidateKindsAndTargetsAreConstructible() {
        val appCandidate = CandidateItem(
            id = ItemId("candidate.app"),
            profile = ProfileId("p0"),
            kind = CandidateKind.APPLICATION,
            target = CandidateTarget.AppKey(
                component = ComponentKey("com.example.new"),
                profile = ProfileId("p0"),
            ),
            availability = Availability.AVAILABLE,
            span = GridSpan(1, 1),
        )
        assertEquals(CandidateKind.APPLICATION, appCandidate.kind)
        assertTrue(appCandidate.target is CandidateTarget.AppKey)

        val shortcutCandidate = CandidateItem(
            id = ItemId("candidate.shortcut"),
            profile = ProfileId("p0"),
            kind = CandidateKind.DEEP_SHORTCUT,
            target = CandidateTarget.ShortcutKey(
                packageName = PackageName("com.example.new"),
                shortcutId = ShortcutId("sc1"),
                profile = ProfileId("p0"),
            ),
            availability = Availability.AVAILABLE,
            span = GridSpan(1, 1),
        )
        assertEquals(CandidateKind.DEEP_SHORTCUT, shortcutCandidate.kind)
        assertTrue(shortcutCandidate.target is CandidateTarget.ShortcutKey)
    }

    @Test
    fun candidateKindEntriesAreExactlyApplicationAndDeepShortcut() {
        assertEquals(2, CandidateKind.entries.size)
        assertTrue(CandidateKind.entries.contains(CandidateKind.APPLICATION))
        assertTrue(CandidateKind.entries.contains(CandidateKind.DEEP_SHORTCUT))
    }

    @Test
    fun kindTargetMismatchShapesAreConstructible() {
        val captured = CapturedItem(
            id = ItemId("captured.kind.mismatch"),
            profile = ProfileId("p0"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.WidgetKey(
                provider = ComponentKey("com.example.widget"),
                appWidgetId = AppWidgetId(1),
                profile = ProfileId("p0"),
            ),
            placement = CapturedPlacement.Workspace(
                page = PageRef(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val candidate = CandidateItem(
            id = ItemId("candidate.kind.mismatch"),
            profile = ProfileId("p0"),
            kind = CandidateKind.APPLICATION,
            target = CandidateTarget.ShortcutKey(
                packageName = PackageName("com.example"),
                shortcutId = ShortcutId("shortcut"),
                profile = ProfileId("p0"),
            ),
            availability = Availability.AVAILABLE,
            span = GridSpan(1, 1),
        )
        val itemParam = DiagnosticParam.ItemParam(ItemId("mismatch"))
        val reason = RejectionReason(
            code = RejectionCode.KIND_TARGET_MISMATCH,
            params = listOf(itemParam),
        )
        assertEquals(ItemKind.APPLICATION, captured.kind)
        assertTrue(captured.target is TargetKey.WidgetKey)
        assertEquals(CandidateKind.APPLICATION, candidate.kind)
        assertTrue(candidate.target is CandidateTarget.ShortcutKey)
        assertEquals(RejectionCode.KIND_TARGET_MISMATCH, reason.code)
        assertEquals(itemParam, reason.params[0])
    }

    @Test
    fun profileMismatchShapesAreConstructible() {
        val captured = CapturedItem(
            id = ItemId("captured.profile.mismatch"),
            profile = ProfileId("personal"),
            kind = ItemKind.APPLICATION,
            target = TargetKey.AppKey(
                component = ComponentKey("com.example.app"),
                profile = ProfileId("work"),
            ),
            placement = CapturedPlacement.Workspace(
                page = PageRef(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        val candidate = CandidateItem(
            id = ItemId("candidate.profile.mismatch"),
            profile = ProfileId("personal"),
            kind = CandidateKind.APPLICATION,
            target = CandidateTarget.AppKey(
                component = ComponentKey("com.example.app"),
                profile = ProfileId("work"),
            ),
            availability = Availability.AVAILABLE,
            span = GridSpan(1, 1),
        )
        val itemParam = DiagnosticParam.ItemParam(ItemId("profile.mismatch"))
        val reason = RejectionReason(
            code = RejectionCode.TARGET_PROFILE_MISMATCH,
            params = listOf(itemParam),
        )
        assertNotEquals(captured.profile, (captured.target as TargetKey.AppKey).profile)
        assertNotEquals(candidate.profile, (candidate.target as CandidateTarget.AppKey).profile)
        assertEquals(RejectionCode.TARGET_PROFILE_MISMATCH, reason.code)
    }

    @Test
    fun categoryDecisionShapesAreConstructible() {
        val explicit = CategoryDecision(
            item = ItemId("app.calculator"),
            category = CategoryId("TOOLS"),
            decidedSignal = SignalSource.S1,
            confidence = Confidence.EXPLICIT,
        )
        val fallback = CategoryDecision(
            item = ItemId("app.unknown"),
            category = CategoryId("OTHER"),
            decidedSignal = SignalSource.S6,
            confidence = Confidence.FALLBACK,
        )
        val rule = CategoryDecision(
            item = ItemId("app.game"),
            category = CategoryId("GAMES"),
            decidedSignal = SignalSource.S3,
            confidence = Confidence.RULE,
        )
        assertEquals(Confidence.EXPLICIT, explicit.confidence)
        assertEquals(Confidence.FALLBACK, fallback.confidence)
        assertEquals(Confidence.RULE, rule.confidence)
    }

    @Test
    fun invalidAndImpossibleAreDistinctRejectedVariants() {
        val invalid = Rejected.Invalid(
            reasons = listOf(
                RejectionReason(
                    RejectionCode.DUPLICATE_ITEM_ID,
                    listOf(DiagnosticParam.ItemParam(ItemId("dup"))),
                ),
            ),
            warnings = emptyList(),
        )
        val impossible = Rejected.Impossible(
            unplaced = listOf(
                UnplacedItem(
                    ItemId("big"),
                    GridSpan(10, 10),
                    UnplacedReason.EXCEEDS_GRID_DIMENSIONS,
                ),
            ),
            warnings = emptyList(),
        )
        assertNotEquals(invalid, impossible)
        assertTrue(invalid is Rejected)
        assertTrue(impossible is Rejected)
        assertTrue(invalid is PlanningOutcome)
        assertTrue(impossible is PlanningOutcome)
    }

    @Test
    fun plannedOutcomeIsConstructibleWithAllFields() {
        val planned = Planned(
            placements = listOf(
                PlannedPlacement(
                    item = ItemId("app.a"),
                    disposition = Disposition.Moved(PlacementCode.SINGLE_PLACEMENT),
                    target = PlacementTarget.WorkspaceTarget(
                        page = PageRef(PageId("p0")),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                ),
            ),
            newPages = listOf(NewPage(NewPageOrdinal(0), PageOrder(1))),
            newFolders = emptyList(),
            categories = emptyList(),
            warnings = emptyList(),
        )
        assertTrue(planned is PlanningOutcome)
        assertEquals(1, planned.placements.size)
    }

    @Test
    fun plannedWithFolderTargetAndWarningsIsConstructible() {
        val folderTarget = PlacementTarget.FolderMember(
            folder = NewFolderRef(NewFolderOrdinal(0)),
            rank = 0,
        )
        val planned = Planned(
            placements = listOf(
                PlannedPlacement(
                    item = ItemId("app.in.folder"),
                    disposition = Disposition.Moved(PlacementCode.FOLDER_MEMBER),
                    target = folderTarget,
                ),
            ),
            newPages = emptyList(),
            newFolders = listOf(
                NewFolder(
                    ordinal = NewFolderOrdinal(0),
                    profile = ProfileId("p0"),
                    naming = FolderNaming.FromCategory(CategoryId("COMMUNICATION")),
                    workspacePlacement = PlacementTarget.WorkspaceTarget(
                        page = NewPageRef(NewPageOrdinal(0)),
                        cell = GridCell(0, 0),
                        span = GridSpan(1, 1),
                    ),
                    members = listOf(ItemId("app.in.folder")),
                ),
            ),
            categories = listOf(
                CategoryDecision(
                    item = ItemId("app.in.folder"),
                    category = CategoryId("GAMES"),
                    decidedSignal = SignalSource.S6,
                    confidence = Confidence.FALLBACK,
                ),
            ),
            warnings = listOf(
                Warning(
                    code = WarningCode.FALLBACK_CATEGORY,
                    params = listOf(DiagnosticParam.ItemParam(ItemId("app.in.folder"))),
                ),
            ),
        )
        assertTrue(planned is PlanningOutcome)
        assertEquals(1, planned.newFolders.size)
        assertEquals(1, planned.warnings.size)
        assertEquals(NewFolderOrdinal(0), planned.newFolders[0].ordinal)
        assertEquals(ProfileId("p0"), planned.newFolders[0].profile)
    }

    @Test
    fun invalidWithAllTypedDiagnosticVariantsIsConstructible() {
        val reason = RejectionReason(
            code = RejectionCode.UNKNOWN_ITEM_KIND,
            params = listOf(DiagnosticParam.KindParam(KindCode(99))),
        )
        val invalid = Rejected.Invalid(
            reasons = listOf(reason),
            warnings = listOf(
                Warning(
                    code = WarningCode.LEGACY_SHORTCUT_REVIEW,
                    params = emptyList(),
                ),
            ),
        )
        assertEquals(RejectionCode.UNKNOWN_ITEM_KIND, invalid.reasons[0].code)
        assertEquals(1, invalid.warnings.size)
    }

    @Test
    fun impossibleWithUnplacedItemIsConstructible() {
        val impossible = Rejected.Impossible(
            unplaced = listOf(
                UnplacedItem(
                    item = ItemId("unavailable.app"),
                    requiredSpan = GridSpan(2, 2),
                    reason = UnplacedReason.TARGET_UNAVAILABLE,
                ),
            ),
            warnings = emptyList(),
        )
        assertEquals(1, impossible.unplaced.size)
        assertEquals(UnplacedReason.TARGET_UNAVAILABLE, impossible.unplaced[0].reason)
    }

    @Test
    fun diagnosticParamsAreDistinctTypedVariants() {
        val itemParam = DiagnosticParam.ItemParam(ItemId("x"))
        val kindParam = DiagnosticParam.KindParam(KindCode(5))
        val spanParam = DiagnosticParam.SpanParam(GridSpan(2, 2))
        val rankParam = DiagnosticParam.RankParam(3)
        val dimParam = DiagnosticParam.DimensionParam(DeviceDimension.COLUMNS, 4)
        val pageParam = DiagnosticParam.PageParam(PageId("p0"))
        val catParam = DiagnosticParam.CategoryParam(CategoryId("TOOLS"))
        val ccParam = DiagnosticParam.ContainerCodeParam(ContainerCode(2))

        assertNotEquals(itemParam, kindParam)
        assertNotEquals(spanParam, rankParam)
        assertNotEquals(dimParam, pageParam)
        assertNotEquals(catParam, ccParam)
    }

    @Test
    fun completeRuleSemanticsIsConstructible() {
        val rules = RuleSemantics(
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
        assertEquals(RuleVersion("v1"), rules.version)
        assertEquals(FolderPolicy(minGroupSize = 2, newFolderProfileScope = NewFolderProfileScope.SAME_PROFILE_ONLY), rules.folderPolicy)
    }

    @Test
    fun allClosedV1PolicyEnumsAreConstructible() {
        assertEquals(NewFolderProfileScope.SAME_PROFILE_ONLY, NewFolderProfileScope.valueOf("SAME_PROFILE_ONLY"))
        assertEquals(1, NewFolderProfileScope.entries.size)

        assertEquals(DockPolicy.PRESERVE, DockPolicy.valueOf("PRESERVE"))
        assertEquals(1, DockPolicy.entries.size)

        assertEquals(OverflowPolicy.ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE, OverflowPolicy.valueOf("ADD_PAGES_FOR_ITEMS_THAT_FIT_EMPTY_PAGE"))
        assertEquals(1, OverflowPolicy.entries.size)

        assertEquals(FallbackCategoryPolicy.KEEP_AS_SINGLETON, FallbackCategoryPolicy.valueOf("KEEP_AS_SINGLETON"))
        assertEquals(1, FallbackCategoryPolicy.entries.size)

        assertEquals(OrderingPolicy.CANONICAL_V1, OrderingPolicy.valueOf("CANONICAL_V1"))
        assertEquals(1, OrderingPolicy.entries.size)
    }

    @Test
    fun completeTaxonomyContractIsConstructible() {
        val taxonomy = TaxonomyContract(
            version = TaxonomyVersion("tv1"),
            allowedCategories = listOf(CategoryId("GAMES"), CategoryId("OTHER"), CategoryId("TOOLS")),
            fallbackCategory = CategoryId("OTHER"),
        )
        assertEquals(TaxonomyVersion("tv1"), taxonomy.version)
        assertEquals(3, taxonomy.allowedCategories.size)
        assertEquals(CategoryId("OTHER"), taxonomy.fallbackCategory)
    }

    @Test
    fun classificationSignalsWithUnknownSignalAndCategoryAreConstructible() {
        val signals = ClassificationSignals(
            entries = listOf(
                ClassificationSignal(
                    item = ItemId("unknown.item"),
                    source = SignalSource.S1,
                    candidate = CategoryId("NONEXISTENT"),
                ),
            ),
        )
        assertEquals(1, signals.entries.size)
        assertEquals(ItemId("unknown.item"), signals.entries[0].item)
        assertEquals(CategoryId("NONEXISTENT"), signals.entries[0].candidate)
    }

    @Test
    fun targetSetShapesWithDuplicateAndMissingAreConstructible() {
        val targetSet = TargetSet(
            existing = listOf(
                ExistingTargetMembership(item = ItemId("dup"), role = ExistingRole.Movable),
                ExistingTargetMembership(item = ItemId("dup"), role = ExistingRole.Movable),
                ExistingTargetMembership(item = ItemId("missing.ref"), role = ExistingRole.Preserved),
            ),
            additions = listOf(
                CandidateItem(
                    id = ItemId("candidate.1"),
                    profile = ProfileId("p0"),
                    kind = CandidateKind.APPLICATION,
                    target = CandidateTarget.AppKey(
                        component = ComponentKey("com.example"),
                        profile = ProfileId("p0"),
                    ),
                    availability = Availability.AVAILABLE,
                    span = GridSpan(1, 1),
                ),
            ),
        )
        assertEquals(3, targetSet.existing.size)
        assertEquals(1, targetSet.additions.size)
    }

    @Test
    fun malformedAppPairMetadataIsConstructible() {
        val singleMember = AppPairMetadata(
            members = listOf(
                AppPairMember(
                    item = ItemId("only.one"),
                    stage = SplitStage.TOP_OR_LEFT,
                    snapPosition = SnapPositionToken("snap"),
                ),
            ),
        )
        assertEquals(1, singleMember.members.size)

        val duplicateStages = AppPairMetadata(
            members = listOf(
                AppPairMember(item = ItemId("a"), stage = SplitStage.TOP_OR_LEFT, snapPosition = SnapPositionToken("s1")),
                AppPairMember(item = ItemId("b"), stage = SplitStage.TOP_OR_LEFT, snapPosition = SnapPositionToken("s2")),
            ),
        )
        assertEquals(2, duplicateStages.members.size)

        val unequalSnapPositions = AppPairMetadata(
            members = listOf(
                AppPairMember(item = ItemId("a"), stage = SplitStage.TOP_OR_LEFT, snapPosition = SnapPositionToken("s1")),
                AppPairMember(item = ItemId("b"), stage = SplitStage.BOTTOM_OR_RIGHT, snapPosition = SnapPositionToken("s2")),
            ),
        )
        assertNotEquals(
            unequalSnapPositions.members[0].snapPosition,
            unequalSnapPositions.members[1].snapPosition,
        )

        val nullSnap = AppPairMetadata(
            members = listOf(
                AppPairMember(item = ItemId("a"), stage = SplitStage.TOP_OR_LEFT, snapPosition = null),
                AppPairMember(item = ItemId("b"), stage = SplitStage.BOTTOM_OR_RIGHT, snapPosition = null),
            ),
        )
        assertEquals(2, nullSnap.members.size)
    }

    @Test
    fun newPageAndNewFolderAreConstructible() {
        val newPage = NewPage(ordinal = NewPageOrdinal(0), order = PageOrder(5))
        assertEquals(NewPageOrdinal(0), newPage.ordinal)
        assertEquals(PageOrder(5), newPage.order)

        val newFolder = NewFolder(
            ordinal = NewFolderOrdinal(1),
            profile = ProfileId("p0"),
            naming = FolderNaming.FromCategory(CategoryId("COMMUNICATION")),
            workspacePlacement = PlacementTarget.WorkspaceTarget(
                page = NewPageRef(NewPageOrdinal(0)),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
            members = listOf(ItemId("member.1")),
        )
        assertEquals(NewFolderOrdinal(1), newFolder.ordinal)
        assertEquals(1, newFolder.members.size)
    }

    @Test
    fun placementTargetVariantsAreConstructible() {
        val workspace = PlacementTarget.WorkspaceTarget(
            page = PageRef(PageId("p0")),
            cell = GridCell(1, 2),
            span = GridSpan(2, 2),
        )
        assertTrue(workspace is PlacementTarget)

        val dock = PlacementTarget.Dock(rank = 3)
        assertTrue(dock is PlacementTarget)
        assertEquals(3, dock.rank)

        val folderMember = PlacementTarget.FolderMember(
            folder = FolderRef(FolderId("f0")),
            rank = 1,
        )
        assertTrue(folderMember is PlacementTarget)

        val appPairMember = PlacementTarget.AppPairMember(
            pair = AppPairRef(AppPairId("ap1")),
        )
        assertTrue(appPairMember is PlacementTarget)
    }

    @Test
    fun everySignalSourceVariantIsConstructible() {
        assertEquals(6, SignalSource.entries.size)
        assertTrue(
            SignalSource.entries.containsAll(
                listOf(SignalSource.S1, SignalSource.S2, SignalSource.S3, SignalSource.S4, SignalSource.S5, SignalSource.S6),
            ),
        )
    }

    @Test
    fun everyPreserveReasonVariantIsConstructible() {
        assertEquals(10, PreserveReason.entries.size)
        assertTrue(
            PreserveReason.entries.containsAll(
                listOf(
                    PreserveReason.LOCKED,
                    PreserveReason.RESERVED_REGION,
                    PreserveReason.UNAVAILABLE_TARGET,
                    PreserveReason.DOCK,
                    PreserveReason.WIDGET,
                    PreserveReason.APP_PAIR,
                    PreserveReason.LEGACY_SHORTCUT,
                    PreserveReason.NON_TARGET,
                    PreserveReason.STRUCTURAL,
                    PreserveReason.ALREADY_CANONICAL,
                ),
            ),
        )
    }

    @Test
    fun everyPlacementCodeVariantIsConstructible() {
        assertEquals(3, PlacementCode.entries.size)
        assertTrue(
            PlacementCode.entries.containsAll(
                listOf(PlacementCode.SINGLE_PLACEMENT, PlacementCode.FOLDER_MEMBER, PlacementCode.FOLDER_UNIT),
            ),
        )
    }

    @Test
    fun everyRejectionCodeVariantIsConstructible() {
        val codes = RejectionCode.entries
        assertTrue(codes.contains(RejectionCode.UNKNOWN_ITEM_KIND))
        assertTrue(codes.contains(RejectionCode.INVALID_CONTAINER))
        assertTrue(codes.contains(RejectionCode.UNKNOWN_PAGE))
        assertTrue(codes.contains(RejectionCode.BOUNDS_VIOLATION))
        assertTrue(codes.contains(RejectionCode.OVERLAP))
        assertTrue(codes.contains(RejectionCode.DANGLING_REFERENCE))
        assertTrue(codes.contains(RejectionCode.MALFORMED_APP_PAIR))
        assertTrue(codes.contains(RejectionCode.LOCKED_OUT_OF_BOUNDS))
        assertTrue(codes.contains(RejectionCode.DUPLICATE_TARGET))
        assertTrue(codes.contains(RejectionCode.MISSING_TARGET))
        assertTrue(codes.contains(RejectionCode.INCOMPLETE_TARGET_PARTITION))
        assertTrue(codes.contains(RejectionCode.ADDITIONS_UNDER_FULL_ORGANIZATION))
        assertTrue(codes.contains(RejectionCode.INVALID_RULES))
        assertTrue(codes.contains(RejectionCode.DUPLICATE_ITEM_ID))
        assertTrue(codes.contains(RejectionCode.DUPLICATE_PAGE))
        assertTrue(codes.contains(RejectionCode.INVALID_DIMENSIONS))
        assertTrue(codes.contains(RejectionCode.KIND_TARGET_MISMATCH))
        assertTrue(codes.contains(RejectionCode.TARGET_PROFILE_MISMATCH))
        assertTrue(codes.contains(RejectionCode.UNKNOWN_SIGNAL_ITEM))
        assertTrue(codes.contains(RejectionCode.UNKNOWN_CATEGORY))
    }
}
