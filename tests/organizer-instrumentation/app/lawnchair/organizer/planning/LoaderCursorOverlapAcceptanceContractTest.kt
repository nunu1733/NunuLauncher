package app.lawnchair.organizer.planning

import android.content.ContentValues
import android.database.MatrixCursor
import android.os.Process
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import app.lawnchair.preferences2.PreferenceManager2
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherAppState
import com.android.launcher3.LauncherSettings.Favorites
import com.android.launcher3.model.LoaderCursor
import com.android.launcher3.model.UserManagerState
import com.android.launcher3.model.data.ItemInfo
import com.android.launcher3.util.PackageManagerHelper
import com.patrykmichalik.opto.core.firstBlocking
import com.patrykmichalik.opto.core.setBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue #185 / ADR-0010 §4 contract (audit F3): for the QSB-row overlap shape,
 * the platform loader's acceptance decision (`LoaderCursor.checkItemPlacement`
 * via `allowWidgetOverlap`) and the organizer's acceptance predicate
 * (`ReservationOverlapAcceptance` + [WorkspaceOverlapToleranceSource] truth)
 * must agree under the same policy value and the same geometry. A future
 * `LoaderCursor` rule change that drifts from this contract fails here, which
 * is the accepted alternative to wiring the loader to the organizer predicate.
 */
@RunWith(AndroidJUnit4::class)
class LoaderCursorOverlapAcceptanceContractTest {

    /** Exposes the protected loader decision at the accepted seam. */
    private class ContractLoaderCursor(
        cursor: MatrixCursor,
        app: LauncherAppState,
        userManagerState: UserManagerState,
        pmHelper: PackageManagerHelper,
    ) : LoaderCursor(cursor, app, userManagerState, pmHelper, null) {
        fun accepts(info: ItemInfo): Boolean = checkItemPlacement(info, true)
    }

    @Test
    fun loaderAcceptanceMatchesOrganizerPredicateForQsbRowOverlap() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val app = LauncherAppState.getInstance(context)
        val prefs = PreferenceManager2.getInstance(context)
        val idp = InvariantDeviceProfile.INSTANCE.get(context)

        val originalSmartspace = prefs.enableSmartspace.firstBlocking()
        val originalTolerance = prefs.allowWidgetOverlap.firstBlocking()
        try {
            // The organizer predicate reads the same QSB condition the loader
            // marks its occupancy with. The fixture cell is derived from the
            // live QSB geometry so a grid/QSB change cannot silently skip the
            // drift check (PR #186 review): a premise failure is an assertion,
            // not an assumption.
            prefs.enableSmartspace.setBlocking(true)
            val qsbColumns = idp.numSearchContainerColumns.coerceAtMost(idp.numColumns)
            assertTrue("QSB reservation must be positive", qsbColumns > 0)
            val reservation = ReservedWorkspaceRegion(
                page = PageRef(PageId("0")),
                cell = GridCell(0, 0),
                span = GridSpan(qsbColumns, 1),
            )
            val overlapCellX = qsbColumns / 2
            val overlapsReservation = ReservationOverlapAcceptance.overlaps(
                PageId("0"),
                GridCell(overlapCellX, 0),
                GridSpan(1, 1),
                listOf(reservation),
            )
            assertTrue("fixture must overlap the QSB reservation", overlapsReservation)

            for (tolerance in listOf(false, true)) {
                prefs.allowWidgetOverlap.setBlocking(tolerance)
                val loaderAccepts = loaderDecision(app, overlapCellX)
                val organizerAccepts = !ReservationOverlapAcceptance.overlaps(
                    PageId("0"),
                    GridCell(overlapCellX, 0),
                    GridSpan(1, 1),
                    listOf(reservation),
                ) || tolerance

                assertEquals(
                    "loader must keep a QSB-row item exactly when the policy tolerates overlap",
                    tolerance,
                    loaderAccepts,
                )
                assertEquals(
                    "organizer predicate must agree with the loader decision",
                    loaderAccepts,
                    organizerAccepts,
                )
            }
        } finally {
            prefs.allowWidgetOverlap.setBlocking(originalTolerance)
            prefs.enableSmartspace.setBlocking(originalSmartspace)
        }
    }

    /** Runs one real `LoaderCursor.checkItemPlacement` for the QSB-row overlap shape. */
    private fun loaderDecision(app: LauncherAppState, overlapCellX: Int): Boolean {
        val cursor = MatrixCursor(
            arrayOf(
                Favorites.ICON, Favorites.TITLE, Favorites._ID, Favorites.CONTAINER, Favorites.ITEM_TYPE,
                Favorites.PROFILE_ID, Favorites.SCREEN, Favorites.CELLX, Favorites.CELLY, Favorites.RESTORED,
                Favorites.INTENT, Favorites.APPWIDGET_ID, Favorites.APPWIDGET_PROVIDER,
                Favorites.SPANX, Favorites.SPANY, Favorites.RANK, Favorites.OPTIONS, Favorites.APPWIDGET_SOURCE,
            ),
        )
        val values = ContentValues().apply {
            put(Favorites._ID, 115L)
            put(Favorites.TITLE, "qsb-row overlap fixture")
            put(Favorites.CONTAINER, Favorites.CONTAINER_DESKTOP)
            put(Favorites.ITEM_TYPE, Favorites.ITEM_TYPE_APPLICATION)
            put(Favorites.PROFILE_ID, 0L)
            put(Favorites.SCREEN, 0)
            put(Favorites.CELLX, overlapCellX)
            put(Favorites.CELLY, 0)
            put(Favorites.SPANX, 1)
            put(Favorites.SPANY, 1)
            put(Favorites.RESTORED, 0)
            put(Favorites.APPWIDGET_ID, -1)
            put(Favorites.RANK, 0)
            put(Favorites.OPTIONS, 0)
            put(Favorites.APPWIDGET_SOURCE, -1)
        }
        cursor.newRow().apply { values.valueSet().forEach { (key, value) -> add(key, value) } }

        val userManagerState = UserManagerState()
        userManagerState.allUsers.put(0, Process.myUserHandle())
        val loaderCursor = ContractLoaderCursor(
            cursor,
            app,
            userManagerState,
            PackageManagerHelper.INSTANCE.get(InstrumentationRegistry.getInstrumentation().targetContext),
        )
        loaderCursor.moveToNext()
        val info = ItemInfo().apply {
            container = Favorites.CONTAINER_DESKTOP
            screenId = 0
            cellX = overlapCellX
            cellY = 0
            spanX = 1
            spanY = 1
            itemType = Favorites.ITEM_TYPE_APPLICATION
        }
        val accepts = loaderCursor.accepts(info)
        loaderCursor.close()
        return accepts
    }
}
