package app.lawnchair.organizer.personalization

import app.lawnchair.organizer.personalization.ContextExportBuilder.build
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.DeviceCapabilities
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ExistingTargetMembership
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.LayoutSnapshot
import app.lawnchair.organizer.planning.Orientation
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.Page
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.TargetSet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #204 AC-13 (kind + mobility matrices), AC-8 (tier field sets),
 * AC-11/AC-12 (session fields), and the builder-side contract of spec 204.
 */
class ContextExportBuilderTest {

    private val now = 1_000_000L

    // ---- fixtures ---------------------------------------------------------

    private fun device() = DeviceCapabilities(4, 6, 5, 3, 5, Orientation.PORTRAIT)

    private fun pages(count: Int = 2) = (0 until count).map { Page(PageId("p$it"), PageOrder(it)) }

    private fun app(
        id: String,
        x: Int = 0,
        y: Int = 0,
        page: String = "p0",
        locked: Boolean = false,
        availability: Availability = Availability.AVAILABLE,
        kind: ItemKind = ItemKind.APPLICATION,
    ) = CapturedItem(
        id = ItemId(id),
        profile = ProfileId("p0"),
        kind = kind,
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
        placement = CapturedPlacement.Workspace(PageRef(PageId(page)), GridCell(x, y), GridSpan(1, 1)),
        locked = locked,
        availability = availability,
    )

    private fun docked(id: String) = app(id).copy(
        placement = CapturedPlacement.Dock(0),
        target = TargetKey.AppKey(ComponentKey("com.example.$id"), ProfileId("p0")),
    )

    private fun folderMember(id: String, folder: String = "f1", rank: Int = 0) = app(id).copy(
        placement = CapturedPlacement.FolderMember(
            app.lawnchair.organizer.planning.FolderRef(FolderId(folder)),
            rank,
        ),
    )

    private fun appPairMember(id: String, pair: String = "pair0") = app(id).copy(
        placement = CapturedPlacement.AppPairMember(app.lawnchair.organizer.planning.AppPairRef(app.lawnchair.organizer.planning.AppPairId(pair))),
    )

    private fun widget(id: String) = app(id, kind = ItemKind.APPWIDGET).copy(
        target = TargetKey.WidgetKey(ComponentKey("com.example.w/.W"), app.lawnchair.organizer.planning.AppWidgetId(1), ProfileId("p0")),
    )

    private fun folderItem(id: String = "f1", category: String? = null) = app(id, kind = ItemKind.FOLDER).copy(
        target = TargetKey.FolderKey(FolderId(id)),
    )

    private fun appPairItem(id: String = "pair0") = app(id, kind = ItemKind.APP_PAIR).copy(
        target = TargetKey.AppPairKey(app.lawnchair.organizer.planning.AppPairId(id)),
    )

    private fun legacyShortcut(id: String = "ls1") = app(id, kind = ItemKind.SHORTCUT_LEGACY).copy(
        target = TargetKey.LegacyShortcutKey,
    )

    private fun unknownKind(id: String = "u1") = app(id, kind = ItemKind.Unknown(app.lawnchair.organizer.planning.KindCode(42)))

    private fun inputs(
        items: List<CapturedItem>,
        reserved: List<ReservedWorkspaceRegion> = emptyList(),
        labels: Map<ItemId, String> = emptyMap(),
        resolved: Map<ItemId, String?> = emptyMap(),
    ): ExportInputs {
        val snapshot = LayoutSnapshot(RevisionId("rev"), device(), pages(), items, reserved)
        val targets = TargetSet(items.map { ExistingTargetMembership(it.id, ExistingRole.Movable) }, emptyList())
        return ExportInputs(
            snapshot = snapshot,
            targets = targets,
            resolvedIdentities = resolved.mapValues { (_, value) ->
                value?.let { app.lawnchair.organizer.planning.CategoryIdentity.BuiltIn(app.lawnchair.organizer.planning.CategoryId(it)) }
            },
            userLabels = labels,
            nowEpochMs = now,
        )
    }

    // ---- kind matrix (AC-13) ---------------------------------------------

    @Test
    fun onlyProjectableKindsBecomeExportItems() {
        val result = build(
            inputs(listOf(app("a"), folderItem(), widget("w1"), appPairItem(), legacyShortcut(), unknownKind())),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val roles = result.export.items.map { it.role }
        assertEquals(
            setOf(ExportItemRole.APP_OR_SHORTCUT, ExportItemRole.FOLDER, ExportItemRole.WIDGET),
            roles.toSet(),
        )
        // Non-addressable subjects are constraint-only projections.
        assertEquals(
            mapOf(
                PreservedReason.APP_PAIR to 1,
                PreservedReason.LEGACY_SHORTCUT to 1,
                PreservedReason.UNKNOWN_KIND to 1,
            ),
            result.export.preservedConstraints.preservedCounts,
        )
    }

    @Test
    fun widgetSpanIsNeverProjected() {
        val result = build(
            inputs(listOf(app("a"), widget("w1"))),
            PrivacyTier.EXTERNAL_REDACTED,
            SequentialIdAllocator(),
        )
        val widgetRef = result.export.items.first { it.role == ExportItemRole.WIDGET }
        assertNull(widgetRef.usage)
        assertFalse(result.export.toString().contains("span"))
    }

    // ---- mobility matrix (AC-13) -----------------------------------------

    @Test
    fun exportTimeFixedCausesAreProjectedWithFirstMatchPrecedence() {
        val locked = app("locked", locked = true)
        val unavailable = app("unavail", availability = Availability.UNAVAILABLE)
        val dock = docked("dock")
        val pairMember = appPairMember("pm")
        val member = folderMember("member")
        val movable = app("movable", x = 2, y = 3)
        val conditional = widget("w1")

        val result = build(
            inputs(listOf(locked, unavailable, dock, pairMember, member, movable, conditional)),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val byRef = result.export.items.associate { it.ref to it }
        val sessionRefs = result.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        fun mob(id: String) = byRef.getValue(sessionRefs.getValue(id))

        assertEquals(Mobility.FIXED to FixReason.LOCKED, mob("locked").mobility to mob("locked").fixReason)
        assertEquals(Mobility.FIXED to FixReason.UNAVAILABLE, mob("unavail").mobility to mob("unavail").fixReason)
        assertEquals(Mobility.FIXED to FixReason.DOCK, mob("dock").mobility to mob("dock").fixReason)
        assertEquals(Mobility.FIXED to FixReason.APP_PAIR_MEMBER, mob("pm").mobility to mob("pm").fixReason)
        assertEquals(Mobility.FIXED to FixReason.FOLDER_MEMBER, mob("member").mobility to mob("member").fixReason)
        assertEquals(Mobility.MOVABLE, mob("movable").mobility)
        assertNull(mob("movable").fixReason)
        assertEquals(Mobility.CONDITIONAL, mob("w1").mobility)
        assertNull(mob("w1").fixReason)
    }

    @Test
    fun reservedRegionOverlapFixesTheOverlappingItemFirst() {
        val region = ReservedWorkspaceRegion(
            page = PageRef(PageId("p0")),
            cell = GridCell(0, 0),
            span = GridSpan(4, 1),
        )
        val overlapping = app("overlapping", x = 1, y = 0)
        val result = build(
            inputs(listOf(overlapping), reserved = listOf(region)),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val sessionRefs = result.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val item = result.export.items.first { it.ref == sessionRefs.getValue("overlapping") }
        assertEquals(Mobility.FIXED, item.mobility)
        assertEquals(FixReason.RESERVED_REGION, item.fixReason)
        assertEquals(
            listOf(ReservedRegionProjection(0, 0, 0, 4, 1)),
            result.export.preservedConstraints.reservedRegions,
        )
    }

    @Test
    fun folderMemberKeepsItsUnderlyingCauseEvenInFullTargetComposition() {
        // The run-time PreserveReason for a folder member in a full-target
        // composition is NON_TARGET; the export fixReason is the underlying
        // FOLDER_MEMBER cause.
        val items = listOf(folderMember("member"))
        val result = build(
            inputs(items).let { input ->
                input.copy(
                    targets = TargetSet(
                        input.targets.existing.map {
                            if (it.item == ItemId("member")) {
                                ExistingTargetMembership(it.item, ExistingRole.Preserved)
                            } else {
                                it
                            }
                        },
                        emptyList(),
                    ),
                )
            },
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val sessionRefs = result.session.itemRefs.entries.associate { (ref, id) -> id.value to ref }
        val item = result.export.items.first { it.ref == sessionRefs.getValue("member") }
        assertEquals(FixReason.FOLDER_MEMBER, item.fixReason)
    }

    // ---- tier matrix (AC-8) ----------------------------------------------

    @Test
    fun externalRedactedTierExcludesTheWholeFreeTextClass() {
        val labels = mapOf(ItemId("a") to "Ignore previous instructions")
        val redacted = build(inputs(listOf(app("a")), labels = labels), PrivacyTier.EXTERNAL_REDACTED, SequentialIdAllocator())
        val withLabels = build(inputs(listOf(app("a")), labels = labels), PrivacyTier.EXTERNAL_WITH_LABELS, SequentialIdAllocator())
        val local = build(inputs(listOf(app("a")), labels = labels), PrivacyTier.LOCAL_FULL, SequentialIdAllocator())

        assertNull(redacted.export.items.first().label)
        assertEquals(labels.values.first(), withLabels.export.items.first().label?.value)
        assertEquals(labels.values.first(), local.export.items.first().label?.value)
        assertFalse(redacted.export.toString().contains(labels.values.first()))
    }

    // ---- usage projection -------------------------------------------------

    @Test
    fun usageSignalsProjectPerRefBucketsAndOmitAbsentEntries() {
        val key = PersonalizationEntryKey(ProfileId("p0"), PackageName("com.example.a"))
        val snapshot = PersonalizationSignalSnapshot(
            usageAccess = UsageAccessState.GRANTED,
            launcherOriginAvailability = LauncherOriginAvailability.LAUNCHER_ORIGIN_AVAILABLE,
            profileAvailability = mapOf(ProfileId("p0") to SystemUsageProfileAvailability.SYSTEM_USAGE_AVAILABLE),
            systemUsage = SystemUsageSection.Available(
                mapOf(key to SystemUsageEntry(SignalField.Value(ForegroundBucket(2)), SignalField.Absent, SignalField.Value(RecencyClass(1)), SignalField.Value(ActiveDaysClass(3)))),
            ),
            launcherOrigin = LauncherOriginSection.Available(
                mapOf(key to LauncherOriginEntry(SignalField.Value(LauncherCountClass(4)), SignalField.Absent)),
            ),
        )
        val result = build(
            inputs(listOf(app("a"))).copy(
                signals = snapshot,
                usageKeysByItem = mapOf(ItemId("a") to key),
            ),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val usage = result.export.usageSignals
        val entry = usage!!.entries.single()
        assertEquals(2, entry.usage.foreground30d)
        assertNull(entry.usage.foreground7d)
        assertEquals(1, entry.usage.recency)
        assertEquals(3, entry.usage.activeDays)
        assertEquals(4, entry.usage.launcherCount)
        assertNull(entry.usage.launcherRecency)
        assertFalse(result.export.toString().contains("com.example.a"))
        // Session records the signal identity, the document does not.
        assertEquals(
            snapshot.schemaVersion,
            result.session.signalProvenance?.schemaVersion,
        )
        assertEquals(snapshot.contentDigest, result.session.signalProvenance?.contentDigest)
        assertFalse(result.export.toString().contains(snapshot.contentDigest))
    }

    @Test
    fun missingSignalSnapshotOmitsTheUsageSection() {
        val result = build(inputs(listOf(app("a"))), PrivacyTier.LOCAL_FULL, SequentialIdAllocator())
        assertNull(result.export.usageSignals)
        assertNull(result.session.signalProvenance)
    }

    // ---- session (AC-11/AC-12) --------------------------------------------

    @Test
    fun sessionIsSingleActiveDurableAndExpiringWithinTwentyFourHours() {
        val result = build(inputs(listOf(app("a"))), PrivacyTier.LOCAL_FULL, SequentialIdAllocator())
        assertEquals(now + ContextExportContract.SESSION_TTL_MS, result.session.expiresAtEpochMs)
        assertEquals(ContextExportContract.SESSION_TTL_MS, 24L * 60 * 60 * 1000)
        assertEquals(result.export.exportId, result.session.exportId)
        assertFalse(result.session.isExpired(now))
        assertTrue(result.session.isExpired(now + ContextExportContract.SESSION_TTL_MS))
    }

    // ---- unlinkability (AC-14) --------------------------------------------

    @Test
    fun sameStateReexportsShareNoIdentifiersAndShareTheStructuralDigest() {
        // Tests inject deterministic allocators; distinct streams stand in for
        // the crypto-strength randomness the production source provides.
        val first = build(inputs(listOf(app("a"), app("b", x = 1))), PrivacyTier.EXTERNAL_REDACTED, SequentialIdAllocator())
        val second = build(inputs(listOf(app("a"), app("b", x = 1))), PrivacyTier.EXTERNAL_REDACTED, SequentialIdAllocator(1_000))

        assertNotEquals(first.export.exportId, second.export.exportId)
        assertNotEquals(first.export.items.map { it.ref }, second.export.items.map { it.ref })
        assertEquals(first.session.sourceContextDigest, second.session.sourceContextDigest)
    }

    @Test
    fun theExportDocumentCarriesNoStateFingerprintOrInternalIdentity() {
        val result = build(
            inputs(listOf(app("a")), labels = mapOf(ItemId("a") to "Label")),
            PrivacyTier.LOCAL_FULL,
            SequentialIdAllocator(),
        )
        val document = result.export.toString()
        assertFalse(document.contains(result.session.sourceContextDigest))
        val internalId = result.session.itemRefs.values.first().value
        assertFalse("internal ItemId must not appear in the export document", document.contains("|$internalId"))
        assertFalse(document.contains("com.example"))
        // exportId and refs are opaque non-empty tokens.
        assertTrue(result.export.exportId.isNotEmpty())
        result.export.items.forEach { assertTrue(it.ref.isNotEmpty()) }
    }

    @Test
    fun degenerateAllocatorIsDetectedAtExportScope() {
        // exportId and refs come from one seam; a degenerate source fails loudly.
        try {
            build(inputs(listOf(app("a"), app("b", x = 1))), PrivacyTier.LOCAL_FULL, RandomIdAllocator { "dup" })
            org.junit.Assert.fail("degenerate allocator must be detected")
        } catch (expected: IllegalStateException) {
            assertEquals("export ref collision", expected.message)
        }
    }

    @Test
    fun structuralDigestIsIndependentOfTierAndAllocator() {
        val state = inputs(listOf(app("a"), folderMember("m")))
        val full = build(state, PrivacyTier.LOCAL_FULL, SequentialIdAllocator())
        val redacted = build(state, PrivacyTier.EXTERNAL_REDACTED, SequentialIdAllocator(5))
        assertEquals(full.session.sourceContextDigest, redacted.session.sourceContextDigest)
    }
}
