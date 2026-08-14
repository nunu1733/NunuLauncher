package app.lawnchair.organizer.application

import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.adapter.RowManifestCodec
import app.lawnchair.organizer.application.actions.RecoveryAction
import app.lawnchair.organizer.application.actions.RecoveryWriteSetMaterializer
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.ProfileId
import com.android.launcher3.LauncherSettings.Favorites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Exact schema-33 row round-trip through the production RowManifestCodec. */
@RunWith(AndroidJUnit4::class)
class RealAdapterRowMatrixInstrumentationTest {
    @Test
    fun folderWidgetProfileAndLockRoundTripExactly() {
        val source = SQLiteDatabase.create(null)
        val restored = SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            Favorites.addTableToDb(restored, 10L, false)
            val rows = listOf(
                row(1, Favorites.ITEM_TYPE_FOLDER, 10, Favorites.CONTAINER_DESKTOP, 0, 0, 0, OrganizerLockState.LOCKED),
                row(
                    2,
                    Favorites.ITEM_TYPE_APPLICATION,
                    10,
                    1,
                    null,
                    null,
                    null,
                    OrganizerLockState.UNLOCKED,
                    rank = 0,
                    intent = "#Intent;component=com.example/.Main;end",
                ),
                row(
                    3,
                    Favorites.ITEM_TYPE_APPWIDGET,
                    20,
                    Favorites.CONTAINER_DESKTOP,
                    0,
                    2,
                    0,
                    OrganizerLockState.LOCKED,
                    widgetId = 42,
                    provider = "com.example/.Widget",
                ),
            )
            rows.forEach { source.insertOrThrow(Favorites.TABLE_NAME, null, RowManifestCodec.values(it)) }
            val profiles = listOf(
                ProfileState(ProfileId("10"), ProfileAvailability.AVAILABLE),
                ProfileState(ProfileId("20"), ProfileAvailability.UNAVAILABLE),
            )
            val capabilities = DeviceCapabilities(4, 5, 5, 4, 4, DeviceOrientation.PORTRAIT)
            val captured = RowManifestCodec.capture(
                source,
                capabilities,
                listOf(PageId("0"), PageId("99")),
                profiles,
            )
            assertEquals(listOf(PageId("0")), captured.state.pages.map {
                (it.ref as app.lawnchair.organizer.application.public.ApplicationPageRef.PersistentPage).pageId
            })
            assertEquals(2, captured.manifest.resources.count {
                it.kind == PersistentResourceKind.PROFILE_INVENTORY
            })
            assertEquals(1, captured.manifest.resources.count {
                it.kind == PersistentResourceKind.DEVICE_PROFILE
            })
            captured.manifest.rows.forEach {
                restored.insertOrThrow(Favorites.TABLE_NAME, null, RowManifestCodec.values(it))
            }
            val recovered = RowManifestCodec.capture(restored, capabilities, listOf(PageId("0")), profiles)
            assertEquals(captured.manifest, recovered.manifest)
            assertEquals(captured.state, recovered.state)
            assertTrue(
                RecoveryWriteSetMaterializer.materialize(captured.manifest, recovered.manifest)
                    .actions.filterIsInstance<RecoveryAction.PreserveResource>()
                    .size == captured.manifest.resources.size,
            )
            assertEquals(
                OrganizerLockState.LOCKED,
                recovered.state.items.single {
                    it.ref == ApplicationItemRef.PersistentItem(ItemId("1"))
                }.lockState,
            )
        } finally {
            source.close()
            restored.close()
        }
    }

    private fun row(
        id: Long,
        type: Int,
        profile: Long,
        container: Int,
        screen: Long?,
        cellX: Int?,
        cellY: Int?,
        lock: OrganizerLockState,
        rank: Int = 0,
        intent: String? = null,
        widgetId: Int? = null,
        provider: String? = null,
    ) = PersistentRow(
        id,
        ItemId(id.toString()),
        ProfileId(profile.toString()),
        ContainerCode(container),
        screen?.let { PageId(it.toString()) },
        cellX,
        cellY,
        1,
        1,
        rank,
        KindCode(type),
        widgetId?.let(::AppWidgetId),
        provider?.let(::ComponentKey),
        byteArrayOf(id.toByte()),
        "title-$id",
        intent,
        0,
        7,
        -1,
        id,
        lock,
        if (cellX != null && cellY != null) GridCell(cellX, cellY) else null,
        GridSpan(1, 1),
    )
}
