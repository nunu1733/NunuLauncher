package app.lawnchair.organizer.application

import android.content.Intent
import android.database.sqlite.SQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.adapter.RowManifestCodec
import app.lawnchair.organizer.application.adapter.canonicalLegacyLaunchUri
import app.lawnchair.organizer.application.actions.IntendedStateResolution
import app.lawnchair.organizer.application.actions.RecoveryAction
import app.lawnchair.organizer.application.actions.RecoveryWriteSetMaterializer
import app.lawnchair.organizer.application.canonical.PersistentResourceKind
import app.lawnchair.organizer.application.canonical.PersistentRow
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.planning.AppWidgetId
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.ContainerCode
import app.lawnchair.organizer.planning.FolderId
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.KindCode
import app.lawnchair.organizer.planning.NewFolderOrdinal
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageRef
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.ReservedWorkspaceRegion
import app.lawnchair.organizer.planning.TargetKey
import com.android.launcher3.LauncherSettings.Favorites
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
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
            assertEquals(1, captured.manifest.resources.count {
                it.kind == PersistentResourceKind.WORKSPACE_RESERVATION
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

    @Test
    fun reservationOverlapIsRejectedOnlyWhenRegionsShareAPage() {
        val source = SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            val capabilities = DeviceCapabilities(4, 5, 5, 4, 4, DeviceOrientation.PORTRAIT)
            val profiles = listOf(ProfileState(ProfileId("10"), ProfileAvailability.AVAILABLE))
            val firstPage = ReservedWorkspaceRegion(PageRef(PageId("0")), GridCell(0, 0), GridSpan(2, 1))

            assertThrows(IllegalArgumentException::class.java) {
                RowManifestCodec.capture(
                    source,
                    capabilities,
                    listOf(PageId("0"), PageId("1")),
                    profiles,
                    listOf(firstPage, firstPage.copy(cell = GridCell(1, 0))),
                )
            }

            val captured = RowManifestCodec.capture(
                source,
                capabilities,
                listOf(PageId("0"), PageId("1")),
                profiles,
                listOf(firstPage, firstPage.copy(page = PageRef(PageId("1")))),
            )
            assertEquals(2, captured.state.reservedWorkspaceRegions.size)
        } finally {
            source.close()
        }
    }

    @Test
    fun qsbReservationIsCapturedAsNonItemContextInStateAndManifest() {
        val source = SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            val profiles = listOf(ProfileState(ProfileId("10"), ProfileAvailability.AVAILABLE))
            val reservation = ReservedWorkspaceRegion(PageRef(PageId("0")), GridCell(0, 0), GridSpan(4, 1))

            val captured = RowManifestCodec.capture(
                source,
                DeviceCapabilities(4, 5, 5, 4, 4, DeviceOrientation.PORTRAIT),
                listOf(PageId("0")),
                profiles,
                listOf(reservation),
            )

            assertEquals(listOf(reservation), captured.state.reservedWorkspaceRegions)
            val context = captured.manifest.resources.single { it.kind == PersistentResourceKind.WORKSPACE_RESERVATION }
            assertTrue(context.payload.isNotEmpty())
            assertEquals(1, captured.state.pages.size)
            assertTrue(captured.state.items.isEmpty())
        } finally {
            source.close()
        }
    }

    /**
     * Issue #150: canonical LayoutState item order is ItemId byte order — the
     * same order the planner emits (`placements.sortedBy { it.item }`) — while
     * manifest.rows keeps the raw row-enumeration order. Numeric row ids with
     * more than one digit distinguish the two orders ("10" sorts before "2" as
     * bytes, after "2" as a number). A7 verification compares LayoutState
     * exactly, so a capture that leaked row order into the canonical surface
     * failed every real workspace with ten or more rows.
     */
    @Test
    fun captureOrdersCanonicalItemsByItemIdByteOrderNotRowEnumeration() {
        val source = SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            val rows = listOf(
                row(
                    1,
                    Favorites.ITEM_TYPE_APPLICATION,
                    10,
                    Favorites.CONTAINER_DESKTOP,
                    0,
                    0,
                    0,
                    OrganizerLockState.UNLOCKED,
                    intent = "#Intent;component=com.example/.A1;end",
                ),
                row(
                    2,
                    Favorites.ITEM_TYPE_APPLICATION,
                    10,
                    Favorites.CONTAINER_DESKTOP,
                    0,
                    1,
                    0,
                    OrganizerLockState.UNLOCKED,
                    intent = "#Intent;component=com.example/.A2;end",
                ),
                row(
                    10,
                    Favorites.ITEM_TYPE_APPLICATION,
                    10,
                    Favorites.CONTAINER_DESKTOP,
                    0,
                    2,
                    0,
                    OrganizerLockState.UNLOCKED,
                    intent = "#Intent;component=com.example/.A10;end",
                ),
            )
            rows.forEach { source.insertOrThrow(Favorites.TABLE_NAME, null, RowManifestCodec.values(it)) }
            val captured = RowManifestCodec.capture(
                source,
                DeviceCapabilities(4, 5, 5, 4, 4, DeviceOrientation.PORTRAIT),
                listOf(PageId("0")),
                listOf(ProfileState(ProfileId("10"), ProfileAvailability.AVAILABLE)),
            )
            assertEquals(
                "Canonical items must follow ItemId byte order",
                listOf("1", "10", "2"),
                captured.state.items.map {
                    (it.ref as ApplicationItemRef.PersistentItem).itemId.value
                },
            )
            assertEquals(
                "Manifest rows keep the row-enumeration order",
                listOf(1L, 2L, 10L),
                captured.manifest.rows.map { it.rowId },
            )
        } finally {
            source.close()
        }
    }

    /**
     * Issue #164: the write set's intended state, after persistent-reference
     * resolution and canonical finalization, must equal the real
     * `RowManifestCodec.capture` output for the same rows. The fixture creates
     * a new folder whose allocated id (10) byte-sorts mid-list among ids 1..9,
     * which is exactly the divergence that failed A7 on device.
     */
    @Test
    fun newFolderWriteSetIntendedStateMatchesRealCanonicalCapture() {
        val source = SQLiteDatabase.create(null)
        try {
            Favorites.addTableToDb(source, 10L, false)
            val rows = listOf(
                row(
                    1,
                    Favorites.ITEM_TYPE_APPLICATION,
                    10,
                    Favorites.CONTAINER_DESKTOP,
                    0,
                    0,
                    0,
                    OrganizerLockState.UNLOCKED,
                    intent = "#Intent;component=com.example/.A1;end",
                ),
                row(
                    10,
                    Favorites.ITEM_TYPE_FOLDER,
                    10,
                    Favorites.CONTAINER_DESKTOP,
                    0,
                    1,
                    0,
                    OrganizerLockState.UNLOCKED,
                ),
                row(
                    2,
                    Favorites.ITEM_TYPE_APPLICATION,
                    10,
                    10,
                    null,
                    null,
                    null,
                    OrganizerLockState.UNLOCKED,
                    rank = 0,
                    intent = "#Intent;component=com.example/.A2;end",
                ),
                row(
                    3,
                    Favorites.ITEM_TYPE_APPLICATION,
                    10,
                    Favorites.CONTAINER_DESKTOP,
                    0,
                    0,
                    1,
                    OrganizerLockState.UNLOCKED,
                    intent = "#Intent;component=com.example/.A3;end",
                ),
            )
            rows.forEach { source.insertOrThrow(Favorites.TABLE_NAME, null, RowManifestCodec.values(it)) }
            val captured = RowManifestCodec.capture(
                source,
                DeviceCapabilities(4, 5, 5, 4, 4, DeviceOrientation.PORTRAIT),
                listOf(PageId("0")),
                listOf(ProfileState(ProfileId("10"), ProfileAvailability.AVAILABLE)),
            )

            // De-canonicalize into the planner/materializer shape: the new
            // folder becomes a PlannedFolder appended last and its child points
            // at the planned ordinal, exactly what the pre-fix writer produced.
            val capturedFolder = captured.state.items.single { it.kind == CanonicalItemKind.Folder }
            val plannedRef = ApplicationItemRef.PlannedFolder(NewFolderOrdinal(0))
            val plannedItems = captured.state.items
                .filter { it != capturedFolder }
                .map { item ->
                    val placement = item.placement as? PlacementState.FolderChild
                    if (placement != null && placement.parent == capturedFolder.ref) {
                        item.copy(placement = placement.copy(parent = plannedRef))
                    } else {
                        item
                    }
                } + capturedFolder.copy(
                    ref = plannedRef,
                    targetKey = TargetKey.FolderKey(FolderId("planned-folder-0")),
                )
            val resolved = IntendedStateResolution.resolveAndFinalize(
                captured.state.copy(items = plannedItems),
                mapOf(plannedRef to 10L),
                emptyMap(),
            )

            assertEquals(
                "Resolved write-set intended state must equal the real canonical capture",
                captured.state,
                resolved,
            )
        } finally {
            source.close()
        }
    }

    /**
     * Issue #152 (re-review P1, rev 5): the canonical legacy launch identity
     * masks the two loader-managed bits **only for the intent shape the
     * loader actually normalizes** (`ACTION_MAIN` + `CATEGORY_LAUNCHER`,
     * mirroring `WorkspaceItemProcessor`) — nothing more, nothing wider.
     *
     * (a) MAIN+LAUNCHER: a model intent differing from the persisted text
     * only by the loader-added bits canonicalizes to the persisted identity.
     * (b) VIEW: the same loader-bits difference is a real semantic
     * divergence — the loader never adds them here, so the canonical forms
     * stay unequal.
     * (c) MAIN+LAUNCHER with an extra non-loader flag (`NO_HISTORY`): the
     * divergence survives the mask and stays detectable.
     */
    @Test
    fun legacyShortcutLaunchIdentityMasksOnlyLoaderFlags() {
        val mainLauncher = "#Intent;action=android.intent.action.MAIN;category=android.intent.category.LAUNCHER;component=com.example/.Home;end"
        val persistedMain = Intent.parseUri(mainLauncher, 0)
        val loadedMain = Intent(persistedMain).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        assertEquals(
            "(a) loader-added bits alone must canonicalize to the persisted identity",
            canonicalLegacyLaunchUri(persistedMain),
            canonicalLegacyLaunchUri(loadedMain),
        )

        val view = "#Intent;action=android.intent.action.VIEW;component=com.example/.Page;end"
        val persistedView = Intent.parseUri(view, 0)
        val loadedView = Intent(persistedView).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
        }
        assertNotEquals(
            "(b) a loader-bits divergence on a non-normalized intent shape is a real semantic divergence",
            canonicalLegacyLaunchUri(persistedView),
            canonicalLegacyLaunchUri(loadedView),
        )

        val tampered = Intent(persistedMain).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NO_HISTORY or
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED,
            )
        }
        assertNotEquals(
            "(c) a non-loader flag divergence must stay detectable",
            canonicalLegacyLaunchUri(persistedMain),
            canonicalLegacyLaunchUri(tampered),
        )
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
