package app.lawnchair.organizer.integration

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.database.Cursor
import android.os.Process
import android.os.SystemClock
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.adapter.RowManifestCodec
import app.lawnchair.organizer.application.adapter.canonicalProfileId
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.application.revision.RevisionCalculator
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.OptionalSnapPosition
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.AppPairId
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.CategoryId
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.DiagnosticParam
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.Rejected
import app.lawnchair.organizer.planning.RejectionCode
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.SignalSource
import app.lawnchair.organizer.planning.SnapPositionToken
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.planning.WorkspaceOverlapToleranceSource
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import app.lawnchair.organizer.rules.CategoryOverrideKey
import app.lawnchair.organizer.rules.CategoryOverrideSnapshot
import app.lawnchair.organizer.rules.CategoryOverrideSnapshotSource
import app.lawnchair.organizer.rules.CategoryOverrideStoreModule
import app.lawnchair.organizer.rules.CategoryOverrideStoredReadResult
import app.lawnchair.organizer.rules.ClassificationPolicy
import app.lawnchair.organizer.rules.OverrideSnapshotReadResult
import app.lawnchair.organizer.rules.PolicyInputIdentity
import app.lawnchair.organizer.rules.PolicySourceKind
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringCoordinator
import app.lawnchair.organizer.ui.CategoryOverrideAuthoringResult
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import com.android.wm.shell.common.split.SplitScreenConstants
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.Assume.assumeTrue
import org.junit.runner.RunWith

/**
 * Issue #83 AC-3/5/6 instrumentation evidence through the actual production
 * composer wiring. DB mutation is limited to the fixture and restored in
 * [tearDown]; composing itself may invoke only captureCurrent on the writer.
 */
@RunWith(AndroidJUnit4::class)
class ProductionOrganizationInputInstrumentationTest {
    private lateinit var context: android.content.Context
    private lateinit var launcher: LauncherAppState
    private var originalRows: List<ContentValues> = emptyList()
    private lateinit var overridePreferences: SharedPreferences
    private lateinit var overrideFiles: List<File>
    private var originalOverrideValues: Map<String, Any?> = emptyMap()
    private var originalOverrideFiles: Map<String, ByteArray> = emptyMap()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        originalRows = snapshotFavorites()
        overridePreferences = context.getSharedPreferences(OVERRIDE_PREFERENCES_NAME, android.content.Context.MODE_PRIVATE)
        val overrideBase = File(context.noBackupFilesDir, "$OVERRIDE_DIRECTORY_NAME/$OVERRIDE_FILE_NAME")
        overrideFiles = listOf(overrideBase, File("${overrideBase.path}.new"), File("${overrideBase.path}.bak"))
        originalOverrideValues = overridePreferences.all.mapValues { it.value }
        originalOverrideFiles = overrideFiles.filter { it.exists() }.associate { it.path to it.readBytes() }
        clearOverrideStorage()
    }

    @After
    fun tearDown() {
        try {
            restoreOverrideStorage()
        } finally {
            restoreFavorites(originalRows)
            launcher.model.forceReload()
        }
    }

    @Test
    fun productionCaptureUsesTheSharedUserCacheProfileIdMapper() {
        val userCache = UserCache.INSTANCE.get(context)
        val expected = checkNotNull(canonicalProfileId(userCache, Process.myUserHandle()))

        val capture = realWriter().captureCurrent(CaptureId("shared-profile-id"))

        assertTrue(capture.layoutState.profiles.any { it.id == expected })
    }

    @Test
    fun productionAuthoringAtomicStoreFeedsFreshComposerWithCanonicalProfileKey() {
        val user = Process.myUserHandle()
        val userCache = UserCache.INSTANCE.get(context)
        val profile = checkNotNull(canonicalProfileId(userCache, user))
        val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))
        val activity = launcherApps.getActivityList(null, user).firstOrNull()
            ?: error("API 35 test emulator must expose a launchable activity")
        val targetKey = CategoryOverrideKey(PackageName(activity.componentName.packageName), profile)
        val coordinator = CategoryOverrideAuthoringCoordinator(context)
        val loaded = coordinator.load() as? CategoryOverrideAuthoringResult.Loaded
            ?: error("production category override inventory must be available")
        val target = loaded.apps.firstOrNull { it.key == targetKey }
            ?: error("production inventory must expose the selected canonical app/profile")
        val itemId = insertLauncherRow(
            lock = OrganizerLockState.UNLOCKED,
            component = activity.componentName,
            profileSerial = userCache.getSerialNumberForUser(user),
        )

        assertTrue(coordinator.save(target, CategoryId("OTHER")) is CategoryOverrideAuthoringResult.Saved)

        val writer = realWriter()
        val capture = writer.captureCurrent(CaptureId("category-override-production-path"))
        val capturedItem = capture.layoutState.items.single { it.ref.itemId() == itemId }
        val capturedTarget = capturedItem.targetKey as TargetKey.AppKey
        assertEquals(targetKey.profile, capturedTarget.profile)
        assertEquals(targetKey.packageName.value, capturedTarget.component.value.substringBefore('/'))

        val result = ProductionOrganizationInputComposer(context, writer).composeFullOrganization()
        assertTrue(result is OrganizationInputComposition.Ready)
        val ready = result as OrganizationInputComposition.Ready
        val signal = ready.input.signals.entries.single { it.item == itemId }
        assertEquals(SignalSource.S1, signal.source)
        assertEquals(CategoryId("OTHER"), signal.candidate)
    }

    @Test
    fun productionComposerReadsOnlyCompleteGenerationsWhileAuthoringWrites() {
        val user = Process.myUserHandle()
        val userCache = UserCache.INSTANCE.get(context)
        val profile = checkNotNull(canonicalProfileId(userCache, user))
        val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))
        val activity = launcherApps.getActivityList(null, user).firstOrNull()
            ?: error("API 35 test emulator must expose a launchable activity")
        val coordinator = CategoryOverrideAuthoringCoordinator(context)
        val loaded = coordinator.load() as? CategoryOverrideAuthoringResult.Loaded
            ?: error("production category override inventory must be available")
        val targetKey = CategoryOverrideKey(PackageName(activity.componentName.packageName), profile)
        val target = loaded.apps.firstOrNull { it.key == targetKey }
            ?: error("production inventory must expose the selected canonical app/profile")
        val itemId = insertLauncherRow(
            lock = OrganizerLockState.UNLOCKED,
            component = activity.componentName,
            profileSerial = userCache.getSerialNumberForUser(user),
        )
        val allowed = setOf(CategoryId("GAME"), CategoryId("OTHER"))
        val executor = Executors.newFixedThreadPool(2)
        try {
            val writes = executor.submit {
                repeat(8) { index ->
                    val category = if (index % 2 == 0) CategoryId("OTHER") else CategoryId("GAME")
                    val result = coordinator.save(target, category)
                    assertTrue(
                        "authoring write $index failed: $result",
                        result is CategoryOverrideAuthoringResult.Saved || result is CategoryOverrideAuthoringResult.NoChange,
                    )
                }
            }
            val reads = executor.submit {
                repeat(8) {
                    val result = ProductionOrganizationInputComposer(context, realWriter()).composeFullOrganization()
                    assertTrue(result is OrganizationInputComposition.Ready)
                    val ready = result as OrganizationInputComposition.Ready
                    ready.input.signals.entries
                        .firstOrNull { it.item == itemId }
                        ?.takeIf { it.source == SignalSource.S1 }
                        ?.let { signal ->
                            assertTrue("composer observed a partial/invalid generation", signal.candidate in allowed)
                        }
                }
            }
            writes.get(30, TimeUnit.SECONDS)
            reads.get(30, TimeUnit.SECONDS)
        } finally {
            executor.shutdownNow()
        }

        assertTrue(CategoryOverrideStoreModule.get(context).readStored() is CategoryOverrideStoredReadResult.Ready)
    }

    @Test
    fun productionComposerMapsCanonicalCaptureAndPreservesPageDeviceProfileAvailabilityAndLock() {
        val expectedId = insertLauncherRow(OrganizerLockState.LOCKED)
        val writer = realWriter()
        val capture = writer.captureCurrent(CaptureId("production-map"))

        val result = ProductionOrganizationInputComposer(context, writer).composeFullOrganization()
        assertTrue(result is OrganizationInputComposition.Ready)
        val ready = result as OrganizationInputComposition.Ready
        val source = capture.layoutState.items.single { it.ref.itemId() == expectedId }
        val mapped = ready.input.snapshot.items.single { it.id == expectedId }

        assertEquals(capture.revision, ready.provenance.revision)
        assertEquals(capture.layoutState.pages.size, ready.input.snapshot.pages.size)
        assertEquals(capture.layoutState.deviceCapabilities.columns, ready.input.snapshot.device.columns)
        assertEquals(capture.layoutState.deviceCapabilities.rows, ready.input.snapshot.device.rows)
        assertEquals(source.profile, mapped.profile)
        assertEquals(Availability.AVAILABLE, mapped.availability)
        assertTrue(mapped.locked)
        assertEquals(ExistingRole.Preserved, ready.input.targets.existing.single { it.item == expectedId }.role)
    }

    /**
     * Issue #136: the exact fresh-install default-layout row shape (Dock rows
     * carrying their slot in SCREEN with RANK at its schema default of 0, one
     * workspace folder with seven members, three second-page apps) must capture,
     * compose, and partition as planner-valid input instead of collapsing into
     * the typed zero-placement rejection.
     */
    @Test
    fun defaultLayoutRowsCaptureSlotsFromScreenAndComposeIntoValidPartition() {
        val serial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle()).toString()
        val source = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            insertDefaultLayoutFixture(source, serial)

            val captured = RowManifestCodec.capture(
                source,
                app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 4, 4, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT),
                listOf(app.lawnchair.organizer.planning.PageId("0"), app.lawnchair.organizer.planning.PageId("1")),
                listOf(ProfileState(ProfileId(serial), ProfileAvailability.AVAILABLE)),
            )

            assertEquals(15, captured.state.items.size)
            val dockRanks = captured.state.items
                .mapNotNull { it.placement as? PlacementState.Dock }
                .sortedBy { it.rank }
            assertEquals(listOf(0, 1, 2, 3), dockRanks.map { it.rank })
            captured.manifest.rows
                .filter { it.containerCode.value == Favorites.CONTAINER_HOTSEAT }
                .forEach { row ->
                    val slot = row.screenId?.value?.toIntOrNull()
                    assertTrue("hotseat SCREEN must keep carrying its slot", slot in 0..3)
                    assertEquals(0, row.rank)
                }

            val snapshot = CapturedSnapshot(
                layoutState = captured.state,
                manifest = captured.manifest,
                revision = RevisionCalculator.revisionOf(captured.state),
                digest = RevisionCalculator.classificationDigestOf(captured.state),
            )
            val composition = DefaultOrganizationInputComposer(
                captureSource = CanonicalCaptureSource { CanonicalCaptureReadResult.Ready(snapshot) },
                bundleSource = object : app.lawnchair.organizer.rules.OrganizerPolicyBundleSource {
                    override fun readActive() = BuiltInOrganizerPolicyBundleSource.readActive()
                },
                overrides = EmptyOverrideSnapshotSource(),
                platformEvidence = EmptyEvidenceSource(),
            ).composeFullOrganization()
            assertTrue("composition must be ready: $composition", composition is OrganizationInputComposition.Ready)
            val ready = composition as OrganizationInputComposition.Ready

            val partition = ready.input.targets.existing
            assertEquals(15, partition.size)
            val items = ready.input.snapshot.items.associateBy { it.id }
            val folder = items.getValue(app.lawnchair.organizer.planning.ItemId("6"))
            assertEquals(app.lawnchair.organizer.planning.ItemKind.FOLDER, folder.kind)
            assertEquals(app.lawnchair.organizer.planning.FolderId("6"), folder.folderId)
            assertEquals(
                (7..13).map { app.lawnchair.organizer.planning.ItemId(it.toString()) }.sorted(),
                folder.members.sorted(),
            )
            (7..13).forEach { memberId ->
                val member = items.getValue(app.lawnchair.organizer.planning.ItemId(memberId.toString()))
                assertNull(member.folderId)
                assertEquals(
                    ExistingRole.Preserved,
                    partition.single { it.item == member.id }.role,
                )
            }
        } finally {
            source.close()
        }
    }

    /**
     * Issue #185 / ADR-0010: the #172 reproduction shape — a 5-column grid whose
     * QSB reservation covers the entire first row, with an item captured inside
     * it (favorites row 115, a deep shortcut at screen 0 cell(2,0)). Capture must
     * succeed losslessly, and composition must follow the platform's overlap
     * policy: Ready with a preserved item when tolerated, the narrower typed
     * `CAPTURE_RESERVED_OVERLAP` reason otherwise.
     */
    @Test
    fun qsbRowItemCapturesLosslesslyAndComposesPerPlatformTolerance() {
        val serial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle()).toString()
        val capabilities = app.lawnchair.organizer.application.public.DeviceCapabilities(5, 5, 5, 4, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT)
        val reservation = ReservedWorkspaceRegion(PageRef(PageId("0")), GridCell(0, 0), GridSpan(5, 1))
        val source = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            source.insertOrThrow(
                Favorites.TABLE_NAME,
                null,
                ContentValues().apply {
                    put(Favorites._ID, 115L)
                    put(Favorites.TITLE, "ambient music shortcut")
                    put(
                        Favorites.INTENT,
                        "#Intent;action=android.intent.action.VIEW;package=com.google.android.as;S.shortcut_id=ambient_music;end",
                    )
                    put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                    put(Favorites.SCREEN, 0)
                    put(Favorites.CELLX, 2)
                    put(Favorites.CELLY, 0)
                    put(Favorites.SPANX, 1)
                    put(Favorites.SPANY, 1)
                    put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_DEEP_SHORTCUT)
                    put(Favorites.APPWIDGET_ID, -1)
                    put(Favorites.MODIFIED, 1_000L)
                    put(Favorites.RESTORED, 0)
                    put(Favorites.PROFILE_ID, serial.toLong())
                    put(Favorites.RANK, 0)
                    put(Favorites.OPTIONS, 0)
                    put(Favorites.APPWIDGET_SOURCE, -1)
                },
            )

            val captured = RowManifestCodec.capture(
                source,
                capabilities,
                listOf(PageId("0")),
                listOf(ProfileState(ProfileId(serial), ProfileAvailability.AVAILABLE)),
                listOf(reservation),
            )
            assertEquals(1, captured.manifest.rows.size)
            val workspace = captured.state.items.single().placement as PlacementState.Workspace
            assertEquals(GridCell(2, 0), workspace.cell)

            val snapshot = CapturedSnapshot(
                layoutState = captured.state,
                manifest = captured.manifest,
                revision = RevisionCalculator.revisionOf(captured.state),
                digest = RevisionCalculator.classificationDigestOf(captured.state),
            )
            val composition = DefaultOrganizationInputComposer(
                captureSource = CanonicalCaptureSource { CanonicalCaptureReadResult.Ready(snapshot) },
                bundleSource = object : app.lawnchair.organizer.rules.OrganizerPolicyBundleSource {
                    override fun readActive() = BuiltInOrganizerPolicyBundleSource.readActive()
                },
                overrides = EmptyOverrideSnapshotSource(),
                platformEvidence = EmptyEvidenceSource(),
                overlapTolerance = WorkspaceOverlapToleranceSource { true },
            ).composeFullOrganization()
            assertTrue("tolerant policy must compose: $composition", composition is OrganizationInputComposition.Ready)
            val role = (composition as OrganizationInputComposition.Ready).input.targets.existing.single().role
            assertEquals(ExistingRole.Preserved, role)

            val rejected = DefaultOrganizationInputComposer(
                captureSource = CanonicalCaptureSource { CanonicalCaptureReadResult.Ready(snapshot) },
                bundleSource = object : app.lawnchair.organizer.rules.OrganizerPolicyBundleSource {
                    override fun readActive() = BuiltInOrganizerPolicyBundleSource.readActive()
                },
                overrides = EmptyOverrideSnapshotSource(),
                platformEvidence = EmptyEvidenceSource(),
                overlapTolerance = WorkspaceOverlapToleranceSource { false },
            ).composeFullOrganization() as OrganizationInputComposition.NotReady
            assertEquals(
                InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.RESERVED_OVERLAP),
                rejected.reason,
            )
            assertEquals(InputCompositionCode.CAPTURE_RESERVED_OVERLAP, rejected.diagnostic.code)
        } finally {
            source.close()
        }
    }

    /**
     * Review follow-up on Issue #136: SCREEN is schema-nullable and a slot-0
     * hotseat row may legitimately carry SCREEN=NULL. Capture must keep the raw
     * NULL in the manifest (reading it as slot 0 only when deriving the Dock
     * placement), and the write/recovery encoder must put NULL back instead of
     * normalizing it to 0 behind the platform's back.
     */
    @Test
    fun hotseatNullScreenRowRoundTripsExactlyThroughCaptureWriteAndRecovery() {
        val serial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle()).toString()
        val capabilities = app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 4, 4, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT)
        val source = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            insertHotseatRow(source, serial, id = 1L, screen = null)

            val captured = RowManifestCodec.capture(
                source,
                capabilities,
                emptyList(),
                listOf(ProfileState(ProfileId(serial), ProfileAvailability.AVAILABLE)),
            )
            val row = captured.manifest.rows.single()
            assertNull("capture must keep the raw nullable SCREEN", row.screenId)
            assertEquals(0, row.rank)
            val dock = captured.state.items.single().placement as PlacementState.Dock
            assertEquals("NULL SCREEN is read as slot 0", 0, dock.rank)

            // The encoder must not invent a value for the nullable column.
            val encoded = RowManifestCodec.values(row)
            assertTrue(encoded.containsKey(Favorites.SCREEN))
            assertNull(encoded.get(Favorites.SCREEN))

            val restored = android.database.sqlite.SQLiteDatabase.create(null)
            try {
                Favorites.addTableToDb(restored, 10L, false)
                restored.insertOrThrow(Favorites.TABLE_NAME, null, encoded)
                restored.query(Favorites.TABLE_NAME, null, null, null, null, null, null).use { cursor ->
                    cursor.moveToFirst()
                    assertTrue(cursor.isNull(cursor.getColumnIndexOrThrow(Favorites.SCREEN)))
                    assertEquals(0, cursor.getInt(cursor.getColumnIndexOrThrow(Favorites.RANK)))
                }
                val recaptured = RowManifestCodec.capture(
                    restored,
                    capabilities,
                    emptyList(),
                    listOf(ProfileState(ProfileId(serial), ProfileAvailability.AVAILABLE)),
                )
                assertEquals(captured.manifest.rows, recaptured.manifest.rows)
                assertEquals(captured.state.items, recaptured.state.items)
            } finally {
                restored.close()
            }
        } finally {
            source.close()
        }
    }

    /**
     * Issue #141: a user-saved app pair persists its split layout by packing
     * `(splitPosition shl 16) + snapPosition` into each member row's RANK. Capture
     * must decode that authoritative value into a present snap token, compose it
     * through the production mapping seam, and reach a planner-accepted full
     * organization instead of the typed zero-placement rejection.
     */
    @Test
    fun appPairMemberRanksCaptureSnapPositionComposeIntoPlannerAcceptedInput() {
        val serial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle()).toString()
        // Fixture ranks come from the live platform constants so drift between the
        // persisted format and the codec's mirrored decoder fails this test first.
        val snapPosition = SplitScreenConstants.SNAP_TO_50_50
        val topRank = (SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT shl 16) + snapPosition
        val bottomRank = (SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT shl 16) + snapPosition
        val source = android.database.sqlite.SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            insertAppPairFixture(source, serial, memberRanks = listOf(topRank, bottomRank))

            val captured = RowManifestCodec.capture(
                source,
                app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 4, 4, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT),
                listOf(app.lawnchair.organizer.planning.PageId("0")),
                listOf(ProfileState(ProfileId(serial), ProfileAvailability.AVAILABLE)),
            )

            val pair = captured.state.items.single { it.kind == CanonicalItemKind.AppPair }
            val structure = pair.structure as StructureState.AppPairMembers
            assertEquals(
                OptionalSnapPosition.Present(SnapPositionToken(snapPosition.toString())),
                structure.snapPosition,
            )
            assertEquals(
                listOf(SplitStage.TOP_OR_LEFT, SplitStage.BOTTOM_OR_RIGHT),
                structure.members.map { it.stage },
            )
            // The manifest keeps the raw encoded ranks lossless.
            assertEquals(
                setOf(topRank, bottomRank),
                captured.manifest.rows
                    .filter { it.containerCode.value == PAIR_ROW_ID.toInt() }
                    .map { it.rank }
                    .toSet(),
            )

            val snapshot = CapturedSnapshot(
                layoutState = captured.state,
                manifest = captured.manifest,
                revision = RevisionCalculator.revisionOf(captured.state),
                digest = RevisionCalculator.classificationDigestOf(captured.state),
            )
            val composition = DefaultOrganizationInputComposer(
                captureSource = CanonicalCaptureSource { CanonicalCaptureReadResult.Ready(snapshot) },
                bundleSource = object : app.lawnchair.organizer.rules.OrganizerPolicyBundleSource {
                    override fun readActive() = BuiltInOrganizerPolicyBundleSource.readActive()
                },
                overrides = EmptyOverrideSnapshotSource(),
                platformEvidence = EmptyEvidenceSource(),
            ).composeFullOrganization()
            assertTrue("composition must be ready: $composition", composition is OrganizationInputComposition.Ready)
            val ready = composition as OrganizationInputComposition.Ready

            val items = ready.input.snapshot.items.associateBy { it.id }
            val pairId = ItemId(PAIR_ROW_ID.toString())
            val plannedPair = items.getValue(pairId)
            assertEquals(ItemKind.APP_PAIR, plannedPair.kind)
            assertEquals(listOf(SnapPositionToken(snapPosition.toString()), SnapPositionToken(snapPosition.toString())), plannedPair.appPair!!.members.map { it.snapPosition })
            assertEquals(setOf(SplitStage.TOP_OR_LEFT, SplitStage.BOTTOM_OR_RIGHT), plannedPair.appPair!!.members.map { it.stage }.toSet())
            (1..2).forEach { memberId ->
                val member = items.getValue(ItemId(memberId.toString()))
                assertNull(member.appPairId)
                val placement = member.placement as CapturedPlacement.AppPairMember
                assertEquals(AppPairId(PAIR_ROW_ID.toString()), placement.pair.appPairId)
            }

            val partition = ready.input.targets.existing
            assertEquals(3, partition.size)
            listOf(pairId, ItemId("1"), ItemId("2")).forEach { id ->
                assertEquals(ExistingRole.Preserved, partition.single { it.item == id }.role)
            }

            val planned = DeterministicOrganizationPlanner().plan(ready.input)
            assertTrue("planner must accept the app-pair layout: ${planned.outcome}", planned.outcome is Planned)
            val placements = (planned.outcome as Planned).placements
            assertEquals(3, placements.size)
            val pairPlacement = placements.single { it.item == pairId }
            assertTrue(pairPlacement.disposition is Disposition.Preserved)
            val workspaceTarget = pairPlacement.target as PlacementTarget.WorkspaceTarget
            assertEquals(GridCell(0, 4), workspaceTarget.cell)
        } finally {
            source.close()
        }
    }

    /**
     * Issue #141: pairs whose member ranks carry no decodable persistent snap
     * position stay typed fail-closed — capture keeps them lossless, the composer
     * projects no invented token, and the planner rejects exactly
     * MALFORMED_APP_PAIR instead of guessing or dropping the pair.
     */
    @Test
    fun undecodableAppPairRanksStayTypedMalformedAtThePlanner() {
        val serial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle()).toString()
        val malformedRankPairs = mapOf(
            "legacy plain ranks" to (0 to 1),
            "out-of-domain snap" to (3 to (65536 + 3)),
            "mismatched snaps" to (
                (SplitScreenConstants.SPLIT_POSITION_TOP_OR_LEFT shl 16) + SplitScreenConstants.SNAP_TO_50_50 to
                    (SplitScreenConstants.SPLIT_POSITION_BOTTOM_OR_RIGHT shl 16) + SplitScreenConstants.SNAP_TO_70_30
                ),
        )
        for ((label, ranks) in malformedRankPairs) {
            val source = android.database.sqlite.SQLiteDatabase.create(null)
            try {
                Favorites.addTableToDb(source, 10L, false)
                insertAppPairFixture(source, serial, memberRanks = listOf(ranks.first, ranks.second))

                val captured = RowManifestCodec.capture(
                    source,
                    app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 4, 4, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT),
                    listOf(app.lawnchair.organizer.planning.PageId("0")),
                    listOf(ProfileState(ProfileId(serial), ProfileAvailability.AVAILABLE)),
                )
                val structure = captured.state.items
                    .single { it.kind == CanonicalItemKind.AppPair }.structure as StructureState.AppPairMembers
                assertEquals("$label: no snap may be invented", OptionalSnapPosition.Absent, structure.snapPosition)

                val snapshot = CapturedSnapshot(
                    layoutState = captured.state,
                    manifest = captured.manifest,
                    revision = RevisionCalculator.revisionOf(captured.state),
                    digest = RevisionCalculator.classificationDigestOf(captured.state),
                )
                val composition = DefaultOrganizationInputComposer(
                    captureSource = CanonicalCaptureSource { CanonicalCaptureReadResult.Ready(snapshot) },
                    bundleSource = object : app.lawnchair.organizer.rules.OrganizerPolicyBundleSource {
                        override fun readActive() = BuiltInOrganizerPolicyBundleSource.readActive()
                    },
                    overrides = EmptyOverrideSnapshotSource(),
                    platformEvidence = EmptyEvidenceSource(),
                ).composeFullOrganization()
                assertTrue("$label: composition stays ready", composition is OrganizationInputComposition.Ready)

                val planned = DeterministicOrganizationPlanner().plan((composition as OrganizationInputComposition.Ready).input)
                val rejected = planned.outcome as? Rejected.Invalid
                assertNotNull("$label: planner must reject", rejected)
                val malformed = rejected!!.reasons.filter { it.code == RejectionCode.MALFORMED_APP_PAIR }
                assertEquals("$label: exactly one typed rejection", 1, malformed.size)
                assertEquals(
                    "$label: rejection names the pair",
                    listOf(DiagnosticParam.ItemParam(ItemId(PAIR_ROW_ID.toString()))),
                    malformed.single().params,
                )
            } finally {
                source.close()
            }
        }
    }

    /**
     * Issue #141 review follow-up: an APP_PAIR row whose child-row count is not
     * two — including zero members — must capture without throwing, project
     * losslessly through the composer, and reach the planner's typed
     * MALFORMED_APP_PAIR rejection instead of crashing canonicalization.
     */
    @Test
    fun appPairRowWithAbnormalMemberCountStaysTypedMalformedAtThePlanner() {
        val serial = UserCache.INSTANCE.get(context)
            .getSerialNumberForUser(Process.myUserHandle()).toString()
        for (memberCount in listOf(0, 1, 3)) {
            val source = android.database.sqlite.SQLiteDatabase.create(null)
            try {
                Favorites.addTableToDb(source, 10L, false)
                insertAppPairFixture(source, serial, memberRanks = (0 until memberCount).map { it })

                // Capture itself must not throw on degenerate cardinality.
                val captured = RowManifestCodec.capture(
                    source,
                    app.lawnchair.organizer.application.public.DeviceCapabilities(4, 5, 4, 4, 4, app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT),
                    listOf(app.lawnchair.organizer.planning.PageId("0")),
                    listOf(ProfileState(ProfileId(serial), ProfileAvailability.AVAILABLE)),
                )
                val structure = captured.state.items
                    .single { it.kind == CanonicalItemKind.AppPair }.structure as StructureState.AppPairMembers
                assertEquals("$memberCount: every child row projects losslessly", memberCount, structure.members.size)
                assertEquals("$memberCount: no snap may be invented", OptionalSnapPosition.Absent, structure.snapPosition)

                val snapshot = CapturedSnapshot(
                    layoutState = captured.state,
                    manifest = captured.manifest,
                    revision = RevisionCalculator.revisionOf(captured.state),
                    digest = RevisionCalculator.classificationDigestOf(captured.state),
                )
                val composition = DefaultOrganizationInputComposer(
                    captureSource = CanonicalCaptureSource { CanonicalCaptureReadResult.Ready(snapshot) },
                    bundleSource = object : app.lawnchair.organizer.rules.OrganizerPolicyBundleSource {
                        override fun readActive() = BuiltInOrganizerPolicyBundleSource.readActive()
                    },
                    overrides = EmptyOverrideSnapshotSource(),
                    platformEvidence = EmptyEvidenceSource(),
                ).composeFullOrganization()
                assertTrue("$memberCount: composition stays ready", composition is OrganizationInputComposition.Ready)

                val planned = DeterministicOrganizationPlanner().plan((composition as OrganizationInputComposition.Ready).input)
                val rejected = planned.outcome as? Rejected.Invalid
                assertNotNull("$memberCount: planner must reject", rejected)
                val malformed = rejected!!.reasons.filter { it.code == RejectionCode.MALFORMED_APP_PAIR }
                assertEquals("$memberCount: exactly one typed rejection", 1, malformed.size)
                assertEquals(
                    "$memberCount: rejection names the pair",
                    listOf(DiagnosticParam.ItemParam(ItemId(PAIR_ROW_ID.toString()))),
                    malformed.single().params,
                )
            } finally {
                source.close()
            }
        }
    }

    /** Inserts one saved app pair: desktop row plus one child row per given rank. */
    private fun insertAppPairFixture(
        db: android.database.sqlite.SQLiteDatabase,
        serial: String,
        memberRanks: List<Int>,
    ) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName("com.example.issue141", "com.example.issue141.MainActivity"))
        fun memberRow(id: Long, rank: Int) {
            db.insertOrThrow(
                Favorites.TABLE_NAME,
                null,
                ContentValues().apply {
                    put(Favorites._ID, id)
                    put(Favorites.TITLE, "Issue141 member $id")
                    put(Favorites.INTENT, intent.toUri(0))
                    put(Favorites.CONTAINER, PAIR_ROW_ID)
                    put(Favorites.SPANX, 1)
                    put(Favorites.SPANY, 1)
                    put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                    put(Favorites.APPWIDGET_ID, -1)
                    put(Favorites.MODIFIED, 1_000L)
                    put(Favorites.RESTORED, 0)
                    put(Favorites.PROFILE_ID, serial.toLong())
                    put(Favorites.RANK, rank)
                    put(Favorites.OPTIONS, 0)
                    put(Favorites.APPWIDGET_SOURCE, -1)
                    put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
                },
            )
        }
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, PAIR_ROW_ID)
                put(Favorites.TITLE, "Issue141 pair")
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 4)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APP_PAIR)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, serial.toLong())
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
            },
        )
        memberRanks.forEachIndexed { index, rank -> memberRow(index + 1L, rank) }
    }

    /** Inserts one dock-row fixture; a null screen leaves the nullable column unset. */
    private fun insertHotseatRow(
        db: android.database.sqlite.SQLiteDatabase,
        serial: String,
        id: Long,
        screen: Int?,
    ) {
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName("com.example.issue136", "com.example.issue136.MainActivity"))
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, "Issue136 null-screen dock")
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_HOTSEAT)
                if (screen != null) put(Favorites.SCREEN, screen)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, serial.toLong())
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
            },
        )
    }

    /** Row ids mirror the observed fresh-install layout: 1-4 Dock, 6 folder, 7-13 members, 14-16 page one. */
    private fun insertDefaultLayoutFixture(db: android.database.sqlite.SQLiteDatabase, serial: String) {
        fun appRow(
            id: Long,
            container: Int,
            screen: Int?,
            cellX: Int?,
            cellY: Int?,
            rank: Int,
            title: String,
        ) {
            val intent = Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_LAUNCHER)
                .setComponent(ComponentName("com.example.issue136", "com.example.issue136.MainActivity"))
            db.insertOrThrow(
                Favorites.TABLE_NAME,
                null,
                ContentValues().apply {
                    put(Favorites._ID, id)
                    put(Favorites.TITLE, title)
                    put(Favorites.INTENT, intent.toUri(0))
                    put(Favorites.CONTAINER, container)
                    if (screen != null) put(Favorites.SCREEN, screen)
                    if (cellX != null) put(Favorites.CELLX, cellX)
                    if (cellY != null) put(Favorites.CELLY, cellY)
                    put(Favorites.SPANX, 1)
                    put(Favorites.SPANY, 1)
                    put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                    put(Favorites.APPWIDGET_ID, -1)
                    put(Favorites.MODIFIED, 1_000L)
                    put(Favorites.RESTORED, 0)
                    put(Favorites.PROFILE_ID, serial.toLong())
                    put(Favorites.RANK, rank)
                    put(Favorites.OPTIONS, 0)
                    put(Favorites.APPWIDGET_SOURCE, -1)
                    put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
                },
            )
        }
        for (slot in 0 until 4) {
            appRow(slot + 1L, Favorites.CONTAINER_HOTSEAT, slot, null, null, 0, "Issue136 dock $slot")
        }
        // The folder itself.
        db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, 6L)
                put(Favorites.TITLE, "Issue136 folder")
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 4)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_FOLDER)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, serial.toLong())
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, OrganizerLockState.UNLOCKED.ordinal)
            },
        )
        for ((index, id) in (7..13).withIndex()) {
            appRow(id.toLong(), 6, null, null, null, index, "Issue136 member $id")
        }
        appRow(14L, Favorites.CONTAINER_DESKTOP, 1, 0, 4, 0, "Issue136 page one a")
        appRow(15L, Favorites.CONTAINER_DESKTOP, 1, 1, 4, 0, "Issue136 page one b")
        appRow(16L, Favorites.CONTAINER_DESKTOP, 1, 3, 4, 0, "Issue136 page one c")
    }

    @Test
    fun productionComposerPreservesQuietPrivateDisabledAndUnavailableProfileWithoutEvidenceFallback() {        val expectedId = insertLauncherRow(OrganizerLockState.UNLOCKED)
        val capture = realWriter().captureCurrent(CaptureId("availability-base"))
        val original = capture.layoutState.items.single { it.ref.itemId() == expectedId }
        val baseProfiles = capture.layoutState.profiles

        val cases = listOf(
            ItemAvailability.QUIET to Availability.QUIET,
            ItemAvailability.LOCKED_PRIVATE_SPACE to Availability.LOCKED_PRIVATE_SPACE,
            ItemAvailability.DISABLED to Availability.DISABLED,
        )
        for ((platformAvailability, expectedAvailability) in cases) {
            val altered = capture.copy(
                layoutState = capture.layoutState.copy(
                    items = listOf(original.copy(itemAvailability = platformAvailability)),
                ),
            )
            val result = ProductionOrganizationInputComposer(context, captureOnlyWriter(altered, mutableListOf())).composeFullOrganization()
            assertTrue(result is OrganizationInputComposition.Ready)
            val ready = result as OrganizationInputComposition.Ready
            val item = ready.input.snapshot.items.single()
            assertEquals(expectedAvailability, item.availability)
            assertEquals(ExistingRole.Preserved, ready.input.targets.existing.single().role)
        }

        val unavailableProfile = baseProfiles.single { it.id == original.profile }.copy(availability = ProfileAvailability.UNAVAILABLE)
        val unavailable = capture.copy(
            layoutState = capture.layoutState.copy(
                profiles = baseProfiles.map { if (it.id == original.profile) unavailableProfile else it },
                items = listOf(original),
            ),
        )
        val result = ProductionOrganizationInputComposer(context, captureOnlyWriter(unavailable, mutableListOf())).composeFullOrganization()
        assertTrue(result is OrganizationInputComposition.Ready)
        val ready = result as OrganizationInputComposition.Ready
        assertEquals(Availability.UNAVAILABLE, ready.input.snapshot.items.single().availability)
        assertEquals(ExistingRole.Preserved, ready.input.targets.existing.single().role)
    }

    @Test
    fun unknownLockFailsClosedBeforeAnyWriterSideEffect() {
        val expectedId = insertLauncherRow(OrganizerLockState.UNLOCKED)
        val capture = realWriter().captureCurrent(CaptureId("unknown-lock-base"))
        val unknown = capture.copy(
            layoutState = capture.layoutState.copy(
                items = capture.layoutState.items.map {
                    if (it.ref.itemId() == expectedId) it.copy(lockState = OrganizerLockState.UNKNOWN) else it
                },
            ),
        )
        val calls = mutableListOf<String>()

        val result = ProductionOrganizationInputComposer(context, captureOnlyWriter(unknown, calls)).composeFullOrganization()
        assertEquals(
            InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.UNKNOWN_LOCK),
            (result as OrganizationInputComposition.NotReady).reason,
        )
        assertEquals(listOf("captureCurrent"), calls)
    }

    @Test
    fun unrepresentableCaptureFailsClosedBeforeAnyWriterSideEffect() {
        val expectedId = insertLauncherRow(OrganizerLockState.UNLOCKED)
        val capture = realWriter().captureCurrent(CaptureId("unrepresentable-base"))
        val unrepresentable = capture.copy(
            layoutState = capture.layoutState.copy(
                items = capture.layoutState.items.map {
                    if (it.ref.itemId() == expectedId) it.copy(placement = PlacementState.UnsupportedContainer(ContainerCode(99))) else it
                },
            ),
        )
        val calls = mutableListOf<String>()

        val result = ProductionOrganizationInputComposer(context, captureOnlyWriter(unrepresentable, calls)).composeFullOrganization()
        assertEquals(
            InputReadinessReason.InvalidCanonicalCapture(CaptureFailureCategory.UNREPRESENTABLE_LAYOUT),
            (result as OrganizationInputComposition.NotReady).reason,
        )
        assertEquals(listOf("captureCurrent"), calls)
    }

    @Test
    fun androidEvidenceUsesRequestedProfileAndDoesNotFallBackForSamePackageInAnotherProfile() {
        val serial = UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()).toString()
        val policy = (BuiltInOrganizerPolicyBundleSource.readActive() as app.lawnchair.organizer.rules.BundleReadResult.Ready)
            .bundle.classification
        val packageName = context.packageName
        val source = AndroidClassificationSignalSnapshotSource(context)
        val personal = source.read(
            listOf(ClassificationEvidenceRequest(ItemId("personal"), app.lawnchair.organizer.planning.PackageName(packageName), ProfileId(serial))),
            policy,
        )
        assertTrue(personal is PlatformEvidenceReadResult.Ready)

        val samePackageAcrossProfiles = source.read(
            listOf(
                ClassificationEvidenceRequest(ItemId("personal"), app.lawnchair.organizer.planning.PackageName(packageName), ProfileId(serial)),
                ClassificationEvidenceRequest(ItemId("other-profile"), app.lawnchair.organizer.planning.PackageName(packageName), ProfileId("not-a-user-serial")),
            ),
            policy,
        )
        assertEquals(PlatformEvidenceReadResult.Unreadable, samePackageAcrossProfiles)
    }

    @Test
    fun productionComposerComposesEvidenceForEveryAvailableProfileWithoutPrivilegedCrossUserAccess() {
        val userCache = UserCache.INSTANCE.get(context)
        val userManager = checkNotNull(context.getSystemService(UserManager::class.java))
        val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))
        val policy = (BuiltInOrganizerPolicyBundleSource.readActive() as app.lawnchair.organizer.rules.BundleReadResult.Ready)
            .bundle.classification
        val handles = awaitProfileConvergence(userCache, userManager)

        // Issue #129: one package resolvable in every accessible profile keeps the
        // fixture valid — each inserted row then requires evidence read from its own
        // profile through the authorized seam.
        val accessible = handles.mapNotNull { handle ->
            if (!userManager.isUserUnlocked(handle) || userManager.isQuietModeEnabled(handle)) return@mapNotNull null
            val serial = userCache.getSerialNumberForUser(handle).toString()
            launchableComponents(launcherApps, handle).takeIf { it.isNotEmpty() }?.let { ProfileFixture(serial, it) }
        }
        assertTrue("at least the current profile must expose launchable activities", accessible.isNotEmpty())
        val sharedPackages = accessible.map { it.components.keys }.reduce { acc, keys -> acc.intersect(keys).toSet() }
            .filterNot { it.startsWith("com.google.") }
        assumeTrue("accessible profiles must share a non-Google package", sharedPackages.isNotEmpty())
        val sharedPackage = sharedPackages.first()
        val systemPackage = accessible.all { fixture ->
            val info = checkNotNull(
                launcherApps.getApplicationInfo(sharedPackage, 0, userCache.getUserForSerialNumber(fixture.serial.toLong())),
            ) { "shared package must resolve in every accessible profile" }
            info.flags and ApplicationInfo.FLAG_SYSTEM != 0
        }

        val insertedIds = accessible.map { fixture ->
            insertLauncherRow(
                lock = OrganizerLockState.UNLOCKED,
                component = checkNotNull(fixture.components[sharedPackage]),
                profileSerial = fixture.serial.toLong(),
            )
        }

        val result = ProductionOrganizationInputComposer(context, realWriter()).composeFullOrganization()
        assertTrue(
            "composer must stay ready for valid multi-profile layouts: $result",
            result is OrganizationInputComposition.Ready,
        )
        val ready = result as OrganizationInputComposition.Ready

        // A row that is not AVAILABLE would be excluded from evidence requests and make
        // this pass vacuous, so require every inserted row to have been requested.
        insertedIds.forEach { itemId ->
            assertEquals(Availability.AVAILABLE, ready.input.snapshot.items.single { it.id == itemId }.availability)
        }
        if (systemPackage) {
            for (itemId in insertedIds) {
                val signal = ready.input.signals.entries.single { it.item == itemId }
                assertEquals(SignalSource.S5, signal.source)
                assertEquals(policy.systemCategory, signal.candidate)
            }
        }
        Log.i(
            TAG,
            "ISSUE129_EVIDENCE profiles=${accessible.joinToString(",") { it.serial }} " +
                "insertedRows=${insertedIds.size} systemPackage=$systemPackage ready=true",
        )
    }

    @Test
    fun androidEvidenceFailsClosedForRealProfileMissingThePackageInsteadOfFallingBackAcrossProfiles() {
        val userCache = UserCache.INSTANCE.get(context)
        val userManager = checkNotNull(context.getSystemService(UserManager::class.java))
        val launcherApps = checkNotNull(context.getSystemService(LauncherApps::class.java))
        val policy = (BuiltInOrganizerPolicyBundleSource.readActive() as app.lawnchair.organizer.rules.BundleReadResult.Ready)
            .bundle.classification
        val handles = awaitProfileConvergence(userCache, userManager)

        val personalPackages = launchableComponents(launcherApps, Process.myUserHandle()).keys
        val other = handles.firstOrNull { handle ->
            handle != Process.myUserHandle() &&
                userManager.isUserUnlocked(handle) &&
                !userManager.isQuietModeEnabled(handle)
        }
        assumeTrue("multi-profile host required for cross-profile fallback regression", other != null)
        val confirmedOther = checkNotNull(other)
        // getActivityList lists launchable activities only; a package can still be
        // installed-but-hidden in the other profile. Use the authorized resolution
        // seam itself as the setup oracle for true absence (it throws when the
        // package is not installed for that user), then require the evidence read
        // to fail closed instead of falling back to the current profile's copy.
        val missingElsewhere = personalPackages.filter { pkg ->
            try {
                launcherApps.getApplicationInfo(pkg, 0, confirmedOther) == null
            } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
                true
            }
        }
        assumeTrue("personal package absent from the other profile required", missingElsewhere.isNotEmpty())

        val result = AndroidClassificationSignalSnapshotSource(context).read(
            listOf(
                ClassificationEvidenceRequest(
                    ItemId("absent-in-other-profile"),
                    PackageName(missingElsewhere.first()),
                    ProfileId(userCache.getSerialNumberForUser(confirmedOther).toString()),
                ),
            ),
            policy,
        )
        assertEquals(PlatformEvidenceReadResult.Unreadable, result)
    }

    private data class ProfileFixture(val serial: String, val components: Map<String, ComponentName>)

    private fun awaitProfileConvergence(userCache: UserCache, userManager: UserManager): List<UserHandle> {
        val handles = (userManager.userProfiles + Process.myUserHandle()).distinct()
        val profileDeadline = SystemClock.elapsedRealtime() + 5_000L
        while (!userCache.userProfiles.containsAll(handles) && SystemClock.elapsedRealtime() < profileDeadline) {
            SystemClock.sleep(100)
        }
        assertTrue("UserCache must converge to the authoritative profile inventory", userCache.userProfiles.containsAll(handles))
        return handles
    }

    private fun launchableComponents(launcherApps: LauncherApps, user: UserHandle): Map<String, ComponentName> =
        launcherApps.getActivityList(null, user).associate { it.componentName.packageName to it.componentName }

    private fun realWriter() = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)

    private fun insertLauncherRow(
        lock: OrganizerLockState,
        component: ComponentName = ComponentName(context.packageName, LawnchairLauncher::class.java.name),
        profileSerial: Long = UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()),
    ): ItemId {
        val id = launcher.model.modelDbController.generateNewItemId()
        val itemId = ItemId(id.toString())
        // Keep the production composer fixture independent of the runtime QSB
        // preference. The first workspace row is always reserved when QSB is
        // enabled, while this deterministic 4x4 region begins below it.
        val fixtureSlot = Math.floorMod(id - 1L, 16L).toInt()
        val cellX = fixtureSlot % 4
        val cellY = fixtureSlot / 4 + 1
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(component)
        launcher.model.modelDbController.db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, "Issue83 instrumentation")
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, cellX)
                put(Favorites.CELLY, cellY)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, profileSerial)
                put(Favorites.RANK, 0)
                put(Favorites.OPTIONS, 0)
                put(Favorites.APPWIDGET_SOURCE, -1)
                put(Favorites.ORGANIZER_LOCK_STATE, lock.ordinal)
            },
        )
        return itemId
    }

    @Suppress("UNCHECKED_CAST")
    private fun captureOnlyWriter(snapshot: CapturedSnapshot, calls: MutableList<String>): LayoutWriterPort = Proxy.newProxyInstance(
        LayoutWriterPort::class.java.classLoader,
        arrayOf(LayoutWriterPort::class.java),
    ) { _, method, _ ->
        when (method.name) {
            "captureCurrent" -> {
                calls += method.name
                snapshot
            }
            "toString" -> "Issue83CaptureOnlyWriter"
            "hashCode" -> System.identityHashCode(calls)
            "equals" -> false
            else -> error("Unexpected writer operation: ${method.name}")
        }
    } as LayoutWriterPort

    private fun app.lawnchair.organizer.application.public.ApplicationItemRef.itemId(): ItemId? =
        (this as? app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem)?.itemId

    private fun snapshotFavorites(): List<ContentValues> {
        val db = launcher.model.modelDbController.db
        val rows = mutableListOf<ContentValues>()
        db.query(Favorites.TABLE_NAME, null, null, null, null, null, Favorites._ID).use { cursor ->
            val columns = cursor.columnNames
            while (cursor.moveToNext()) rows += readRow(cursor, columns)
        }
        return rows
    }

    private fun restoreFavorites(rows: List<ContentValues>) {
        val db = launcher.model.modelDbController.db
        db.beginTransaction()
        try {
            db.delete(Favorites.TABLE_NAME, null, null)
            rows.forEach { db.insertOrThrow(Favorites.TABLE_NAME, null, it) }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun readRow(cursor: Cursor, columns: Array<String>): ContentValues = ContentValues().also { values ->
        for (index in columns.indices) {
            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL -> values.putNull(columns[index])
                Cursor.FIELD_TYPE_INTEGER -> values.put(columns[index], cursor.getLong(index))
                Cursor.FIELD_TYPE_FLOAT -> values.put(columns[index], cursor.getDouble(index))
                Cursor.FIELD_TYPE_STRING -> values.put(columns[index], cursor.getString(index))
                Cursor.FIELD_TYPE_BLOB -> values.put(columns[index], cursor.getBlob(index))
            }
        }
    }

    private fun clearOverrideStorage() {
        overridePreferences.edit().clear().commit()
        overrideFiles.forEach { it.delete() }
    }

    private fun restoreOverrideStorage() {
        overridePreferences.edit().clear().commit()
        val editor = overridePreferences.edit()
        originalOverrideValues.forEach { (key, value) ->
            when (value) {
                is Boolean -> editor.putBoolean(key, value)
                is Float -> editor.putFloat(key, value)
                is Int -> editor.putInt(key, value)
                is Long -> editor.putLong(key, value)
                is String -> editor.putString(key, value)
                is Set<*> -> editor.putStringSet(key, value.filterIsInstance<String>().toMutableSet())
            }
        }
        check(editor.commit()) { "failed to restore category override preferences" }
        overrideFiles.forEach { it.delete() }
        originalOverrideFiles.forEach { (path, bytes) ->
            val file = File(path)
            file.parentFile?.mkdirs()
            file.writeBytes(bytes)
        }
    }

    private class EmptyOverrideSnapshotSource : CategoryOverrideSnapshotSource {
        override fun read(capturedProfiles: Set<ProfileId>): OverrideSnapshotReadResult = OverrideSnapshotReadResult.Ready(
            CategoryOverrideSnapshot(
                schemaVersion = 1,
                generation = 0L,
                assignments = emptyMap(),
                identity = PolicyInputIdentity(
                    PolicySourceKind.CATEGORY_OVERRIDE_SNAPSHOT,
                    "schema-1-generation-0",
                    "a".repeat(64),
                ),
            ),
        )
    }

    private class EmptyEvidenceSource : ClassificationSignalSnapshotSource {
        override fun read(
            requests: List<ClassificationEvidenceRequest>,
            policy: ClassificationPolicy,
        ): PlatformEvidenceReadResult = PlatformEvidenceReadResult.Ready(
            PlatformClassificationEvidence(
                emptyMap(),
                emptyMap(),
                PolicyInputIdentity(
                    PolicySourceKind.PLATFORM_CLASSIFICATION_EVIDENCE,
                    "platform-evidence-v1",
                    "b".repeat(64),
                ),
            ),
        )
    }

    private companion object {
        const val TAG = "ProductionOrgInputTest"
        const val PAIR_ROW_ID = 100L
        const val OVERRIDE_PREFERENCES_NAME = "organizer_category_overrides"
        const val OVERRIDE_DIRECTORY_NAME = "organizer_category_overrides"
        const val OVERRIDE_FILE_NAME = "snapshot-v1"
    }
}
