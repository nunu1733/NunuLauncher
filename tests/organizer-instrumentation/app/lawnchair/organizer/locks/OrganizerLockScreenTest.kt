package app.lawnchair.organizer.locks

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.locks.LockEffectNote
import app.lawnchair.organizer.locks.LockTargetState
import app.lawnchair.organizer.locks.LockWriteOutcome
import app.lawnchair.organizer.locks.LockWritePlan
import app.lawnchair.organizer.locks.LockCapturePort
import app.lawnchair.organizer.locks.LockCapture
import app.lawnchair.organizer.locks.LockStateWriterPort
import app.lawnchair.organizer.locks.LockAuthoringModule
import app.lawnchair.organizer.planning.ComponentKey
import app.lawnchair.organizer.planning.GridCell
import app.lawnchair.organizer.planning.GridSpan
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PageId
import app.lawnchair.organizer.planning.PageOrder
import app.lawnchair.organizer.planning.ProfileId
import app.lawnchair.organizer.planning.RevisionId
import app.lawnchair.organizer.planning.TargetKey
import app.lawnchair.organizer.ui.LockMessages
import app.lawnchair.ui.preferences.destinations.PlacementLockPreferences
import app.lawnchair.ui.theme.LawnchairTheme
import com.android.launcher3.R
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spec §“Accessibility and localization”: the lock management/review screen
 * renders state as text (never color alone), surfaces the parent/child effect
 * explanation before mutation, resolves `UNKNOWN` only through a confirmed
 * dialog, and renders localized failure messaging. Uses a fake module so the
 * UI surface is tested independently of the Launcher DB.
 *
 * Issue #38.
 */
@RunWith(AndroidJUnit4::class)
class OrganizerLockScreenTest {

    @get:Rule
    val composeRule = createComposeRule()

    private class MutableCapture(var state: LayoutState, var revisionValue: String = "r0") : LockCapturePort {
        override fun capture(): LockCapture = LockCapture(state, app.lawnchair.organizer.planning.RevisionId(revisionValue))
    }

    private class FakeWriter(
        private val capture: MutableCapture,
        var outcome: LockWriteOutcome? = null,
    ) : LockStateWriterPort {
        val writes = mutableListOf<LockWritePlan>()

        override fun write(plan: LockWritePlan): LockWriteOutcome {
            writes += plan
            outcome?.let { return it }
            capture.state = capture.state.copy(
                items = capture.state.items.map { item ->
                    val itemId = (
                        item.ref as? app.lawnchair.organizer.application.public.ApplicationItemRef.PersistentItem
                        )?.itemId
                    val write = plan.writes.firstOrNull { it.item == itemId }
                    if (write != null) item.copy(lockState = write.newState.toStored()) else item
                },
            )
            capture.revisionValue = "r${writes.size}"
            return LockWriteOutcome.Committed(app.lawnchair.organizer.planning.RevisionId(capture.revisionValue))
        }
    }

    private fun screenState(
        folderLock: OrganizerLockState = OrganizerLockState.UNLOCKED,
        unknownApp: Boolean = true,
    ): LayoutState {
        val child = appItem(
            "211",
            title = "Folder Child",
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("201")), 0),
        )
        val folder = appItem(
            "201",
            title = "201",
            kind = CanonicalItemKind.Folder,
            lockState = folderLock,
            structure = StructureState.FolderMembers(listOf(RankedMember(child.ref, 0))),
        )
        val plain = appItem("101", title = "Locked App", lockState = OrganizerLockState.LOCKED)
        val unknown = if (unknownApp) {
            listOf(appItem("112", title = "Unknown App", lockState = OrganizerLockState.UNKNOWN))
        } else {
            emptyList()
        }
        val items = listOf(child, folder, plain) + unknown
        return LayoutState(
            pages = listOf(
                PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)),
            ),
            profiles = listOf(ProfileState(ProfileId("personal"), ProfileAvailability.AVAILABLE)),
            deviceCapabilities = app.lawnchair.organizer.application.public.DeviceCapabilities(
                4,
                5,
                4,
                4,
                4,
                app.lawnchair.organizer.application.public.DeviceOrientation.PORTRAIT,
            ),
            items = items,
        )
    }

    private fun appItem(
        id: String,
        title: String,
        kind: CanonicalItemKind = CanonicalItemKind.Application,
        lockState: OrganizerLockState = OrganizerLockState.UNLOCKED,
        placement: PlacementState = PlacementState.Workspace(
            page = ApplicationPageRef.PersistentPage(PageId("p0")),
            cell = GridCell(0, 0),
            span = GridSpan(1, 1),
        ),
        structure: StructureState = StructureState.Plain,
    ): CanonicalItemState = CanonicalItemState(
        ref = ApplicationItemRef.PersistentItem(ItemId(id)),
        kind = kind,
        targetKey = TargetKey.AppKey(ComponentKey("com.example/$id"), ProfileId("personal")),
        profile = ProfileId("personal"),
        profileAvailability = ProfileAvailability.AVAILABLE,
        itemAvailability = ItemAvailability.AVAILABLE,
        placement = placement,
        title = OptionalText.Present(title),
        intent = OptionalText.Absent,
        icon = OptionalBytes.Absent,
        widget = WidgetState.NoWidget,
        modified = ModifiedAtMillis(1_000L),
        lockState = lockState,
        structure = structure,
    )

    private fun setContent(module: LockAuthoringModule) {
        composeRule.setContent {
            LawnchairTheme {
                PlacementLockPreferences(
                    module = module,
                )
            }
        }
    }

    @Test
    fun unknownBannerAndTextStateLabelsAreRendered() {
        val capture = MutableCapture(screenState())
        setContent(LockAuthoringModule(capture, FakeWriter(capture)))
        val banner = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getString(R.string.organizer_lock_screen_unknown_banner, 1)
        composeRule.waitUntil(5_000) { composeRule.onAllNodesWithText(banner).fetchSemanticsNodes().isNotEmpty() }
        composeRule.onNodeWithText(banner).assertIsDisplayed()
        // Non-color-only state evidence: the state is visible text.
        composeRule.onNodeWithText(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.organizer_lock_state_unknown),
        ).assertIsDisplayed()
        composeRule.onNodeWithText(
            ApplicationProvider.getApplicationContext<android.content.Context>()
                .getString(R.string.organizer_lock_state_locked),
        ).assertIsDisplayed()
    }

    @Test
    fun unknownReviewResolvesOnlyThroughConfirmedDialog() {
        val capture = MutableCapture(screenState())
        val writer = FakeWriter(capture)
        setContent(LockAuthoringModule(capture, writer))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Unknown App").fetchSemanticsNodes().isNotEmpty()
        }
        // No write may happen before the user confirms.
        assertEquals(0, writer.writes.size)
        composeRule.onNodeWithText("Unknown App").performClick()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_action_keep_locked),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // Review intro is shown before the choice.
        composeRule.onNodeWithText(context.getString(R.string.organizer_lock_dialog_title_review))
            .assertIsDisplayed()
        assertEquals(0, writer.writes.size)
        composeRule.onNodeWithText(context.getString(R.string.organizer_lock_action_keep_locked)).performClick()
        composeRule.waitUntil(5_000) { writer.writes.size == 1 }
        assertEquals(LockTargetState.LOCKED, writer.writes.single().writes.single().newState)
        // The reviewed row leaves the unknown section.
        val noneBanner = context.getString(R.string.organizer_lock_screen_unknown_banner_none)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(noneBanner).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun folderLockDialogExplainsChildCoverageBeforeMutation() {
        val capture = MutableCapture(screenState(folderLock = OrganizerLockState.UNLOCKED))
        val writer = FakeWriter(capture)
        setContent(LockAuthoringModule(capture, writer))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("201").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("201").performClick()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val effect = context.getString(
            LockMessages.effectNote(LockEffectNote.FOLDER_PARENT_COVERS_CHILDREN),
        )
        // The dialog body is one text block; the effect note appears as a substring.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(effect, substring = true).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText(effect, substring = true)[0].assertIsDisplayed()
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun busyFailureRendersLocalizedMessage() {
        val capture = MutableCapture(screenState())
        val writer = FakeWriter(capture, outcome = LockWriteOutcome.Rejected(LockWriteRejection.WRITER_BUSY))
        setContent(LockAuthoringModule(capture, writer))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("Unknown App").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("Unknown App").performClick()
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_action_keep_locked),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.organizer_lock_action_keep_locked)).performClick()
        val busy = context.getString(R.string.organizer_lock_error_busy)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(busy).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(busy).assertIsDisplayed()
    }
}
