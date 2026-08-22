package app.lawnchair.organizer.integration

import android.content.ComponentName
import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.os.Process
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.LawnchairLauncher
import app.lawnchair.organizer.application.adapter.LauncherLayoutAdapter
import app.lawnchair.organizer.application.adapter.canonicalProfileId
import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.CapturedSnapshot
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.ExistingRole
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.rules.BuiltInOrganizerPolicyBundleSource
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.pm.UserCache
import java.lang.reflect.Proxy
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
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

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        launcher = LauncherAppState.getInstance(context)
        originalRows = snapshotFavorites()
    }

    @After
    fun tearDown() {
        restoreFavorites(originalRows)
        launcher.model.forceReload()
    }

    @Test
    fun productionCaptureUsesTheSharedUserCacheProfileIdMapper() {
        val userCache = UserCache.INSTANCE.get(context)
        val expected = checkNotNull(canonicalProfileId(userCache, Process.myUserHandle()))

        val capture = realWriter().captureCurrent(CaptureId("shared-profile-id"))

        assertTrue(capture.layoutState.profiles.any { it.id == expected })
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

    @Test
    fun productionComposerPreservesQuietPrivateDisabledAndUnavailableProfileWithoutEvidenceFallback() {
        val expectedId = insertLauncherRow(OrganizerLockState.UNLOCKED)
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

    private fun realWriter() = LauncherLayoutAdapter(context, launcher.model.modelDbController, launcher.model)

    private fun insertLauncherRow(lock: OrganizerLockState): ItemId {
        val id = launcher.model.modelDbController.generateNewItemId()
        val itemId = ItemId(id.toString())
        val intent = Intent(Intent.ACTION_MAIN)
            .addCategory(Intent.CATEGORY_LAUNCHER)
            .setComponent(ComponentName(context.packageName, LawnchairLauncher::class.java.name))
        launcher.model.modelDbController.db.insertOrThrow(
            Favorites.TABLE_NAME,
            null,
            ContentValues().apply {
                put(Favorites._ID, id)
                put(Favorites.TITLE, "Issue83 instrumentation")
                put(Favorites.INTENT, intent.toUri(0))
                put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
                put(Favorites.SCREEN, 0)
                put(Favorites.CELLX, 0)
                put(Favorites.CELLY, 0)
                put(Favorites.SPANX, 1)
                put(Favorites.SPANY, 1)
                put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
                put(Favorites.APPWIDGET_ID, -1)
                put(Favorites.MODIFIED, 1_000L)
                put(Favorites.RESTORED, 0)
                put(Favorites.PROFILE_ID, UserCache.INSTANCE.get(context).getSerialNumberForUser(Process.myUserHandle()))
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
}
