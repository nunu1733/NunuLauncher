package app.lawnchair.organizer.locks

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.AppPairMemberState
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.ItemAvailability
import app.lawnchair.organizer.application.public.LayoutState
import app.lawnchair.organizer.application.public.ModifiedAtMillis
import app.lawnchair.organizer.application.public.OptionalBytes
import app.lawnchair.organizer.application.public.OptionalSnapPosition
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.application.public.OrganizerLockState
import app.lawnchair.organizer.application.public.PageState
import app.lawnchair.organizer.application.public.PlacementState
import app.lawnchair.organizer.application.public.ProfileAvailability
import app.lawnchair.organizer.application.public.ProfileState
import app.lawnchair.organizer.application.public.RankedMember
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.SplitStage
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

    /**
     * Issue #211: same-named rows in distinct placements. Every row's
     * placement description is unique ("Home screen 1", "Inside a folder,
     * position 1", "Home screen 2", "Home screen 3", and the
     * effectively-protected composition) while the app title repeats, so the
     * dialog can be checked against the tapped row by description alone.
     */
    private fun sameTitleState(): LayoutState {
        val folder = appItem(
            "201",
            title = "F",
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(emptyList()),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p1")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
        )
        val lockedFolder = appItem(
            "202",
            title = "F2",
            kind = CanonicalItemKind.Folder,
            lockState = OrganizerLockState.LOCKED,
            structure = StructureState.FolderMembers(emptyList()),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p2")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
        )
        val protectedChild = appItem(
            "211",
            title = "Protected",
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("202")), 0),
        )
        val onHome = appItem(
            "101",
            title = "Google",
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
        )
        val inFolder = appItem(
            "102",
            title = "Google",
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("201")), 0),
        )
        return LayoutState(
            pages = listOf(
                PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)),
                PageState(ApplicationPageRef.PersistentPage(PageId("p1")), PageOrder(1)),
                PageState(ApplicationPageRef.PersistentPage(PageId("p2")), PageOrder(2)),
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
            items = listOf(folder, lockedFolder, protectedChild, onHome, inFolder),
        )
    }

    /**
     * PR #264 review P1: rows whose placement descriptions collide — the
     * row description collapses Desktop to the page number and folder
     * children to the rank, so two same-named icons in different cells of
     * one page, and two same-named icons in different folders at the same
     * position, render one identical description. The dialog must resolve
     * them with the disambiguator line.
     */
    private fun collidingTitleState(): LayoutState {        val folderG = appItem(
            "201",
            title = "G",
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(emptyList()),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p1")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
        )
        val folderH = appItem(
            "202",
            title = "H",
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(emptyList()),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p1")),
                cell = GridCell(2, 0),
                span = GridSpan(1, 1),
            ),
        )
        val cellA = appItem(
            "101",
            title = "Google",
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p0")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
        )
        val cellB = appItem(
            "102",
            title = "Google",
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p0")),
                cell = GridCell(3, 1),
                span = GridSpan(1, 1),
            ),
        )
        val inG = appItem(
            "103",
            title = "FChild",
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("201")), 0),
        )
        val inH = appItem(
            "104",
            title = "FChild",
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("202")), 0),
        )
        // Second review P1: parent titles are not unique, so two same-named
        // folders each holding a same-named child at the same position must
        // still resolve through the parent's own placement.
        val sameFolderA = appItem(
            "301",
            title = "Same",
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(emptyList()),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p1")),
                cell = GridCell(0, 2),
                span = GridSpan(1, 1),
            ),
        )
        val sameFolderB = appItem(
            "302",
            title = "Same",
            kind = CanonicalItemKind.Folder,
            structure = StructureState.FolderMembers(emptyList()),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p1")),
                cell = GridCell(2, 2),
                span = GridSpan(1, 1),
            ),
        )
        val inSameA = appItem(
            "303",
            title = "SChild",
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("301")), 0),
        )
        val inSameB = appItem(
            "304",
            title = "SChild",
            placement = PlacementState.FolderChild(ApplicationItemRef.PersistentItem(ItemId("302")), 0),
        )
        // …and the app pair class from the first review: two same-named pairs
        // on the workspace, each holding a same-named member.
        val pairARef = ApplicationItemRef.PersistentItem(ItemId("401"))
        val pairBRef = ApplicationItemRef.PersistentItem(ItemId("402"))
        val pairA = appItem(
            "401",
            title = "Duo",
            kind = CanonicalItemKind.AppPair,
            structure = StructureState.AppPairMembers(
                members = listOf(
                    AppPairMemberState(ApplicationItemRef.PersistentItem(ItemId("403")), SplitStage.TOP_OR_LEFT),
                ),
                snapPosition = OptionalSnapPosition.Absent,
            ),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p2")),
                cell = GridCell(0, 0),
                span = GridSpan(1, 1),
            ),
        )
        val pairB = appItem(
            "402",
            title = "Duo",
            kind = CanonicalItemKind.AppPair,
            structure = StructureState.AppPairMembers(
                members = listOf(
                    AppPairMemberState(ApplicationItemRef.PersistentItem(ItemId("404")), SplitStage.TOP_OR_LEFT),
                ),
                snapPosition = OptionalSnapPosition.Absent,
            ),
            placement = PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(PageId("p2")),
                cell = GridCell(2, 0),
                span = GridSpan(1, 1),
            ),
        )
        val inPairA = appItem(
            "403",
            title = "PChild",
            placement = PlacementState.AppPairChild(parent = pairARef, stage = SplitStage.TOP_OR_LEFT),
        )
        val inPairB = appItem(
            "404",
            title = "PChild",
            placement = PlacementState.AppPairChild(parent = pairBRef, stage = SplitStage.TOP_OR_LEFT),
        )
        return LayoutState(
            pages = listOf(
                PageState(ApplicationPageRef.PersistentPage(PageId("p0")), PageOrder(0)),
                PageState(ApplicationPageRef.PersistentPage(PageId("p1")), PageOrder(1)),
                PageState(ApplicationPageRef.PersistentPage(PageId("p2")), PageOrder(2)),
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
            items = listOf(
                folderG,
                folderH,
                cellA,
                cellB,
                inG,
                inH,
                sameFolderA,
                sameFolderB,
                inSameA,
                inSameB,
                pairA,
                pairB,
                inPairA,
                inPairB,
            ),
        )
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
    fun dialogNamesTheTappedRowAmongSameTitleRows() {
        val capture = MutableCapture(sameTitleState())
        val writer = FakeWriter(capture)
        setContent(LockAuthoringModule(capture, writer))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val homeDescription = context.getString(R.string.organizer_lock_screen_placement_desktop, 1)
        val folderDescription = context.getString(R.string.organizer_lock_screen_placement_folder, 1)
        val protectedDescription = context.getString(
            R.string.organizer_lock_screen_placement_summary_double,
            context.getString(R.string.organizer_lock_screen_placement_folder, 1),
            context.getString(R.string.organizer_lock_screen_effectively_locked),
        )
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(homeDescription).fetchSemanticsNodes().isNotEmpty()
        }
        // Opening the dialog must not write.
        assertEquals(0, writer.writes.size)
        composeRule.onNodeWithText(homeDescription).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_title, "Google"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // The tapped row's description now exists both in the list and in the dialog.
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(homeDescription).fetchSemanticsNodes().size == 2
        }
        // The other same-title row's placement is not named by the dialog.
        assertEquals(1, composeRule.onAllNodesWithText(folderDescription).fetchSemanticsNodes().size)
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        // Tapping the folder-child row swaps the dialog's named target.
        composeRule.onNodeWithText(folderDescription).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(folderDescription).fetchSemanticsNodes().size == 2
        }
        assertEquals(1, composeRule.onAllNodesWithText(homeDescription).fetchSemanticsNodes().size)
        assertEquals(0, writer.writes.size)
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        // The effectively-protected row is named with the same composed
        // description the row renders. At 200% font scale the row can sit
        // below the fold of the lazy list, so scroll it into view first.
        composeRule.onNode(hasScrollAction())
            .performScrollToNode(hasText(protectedDescription))
        composeRule.onNodeWithText(protectedDescription).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(protectedDescription).fetchSemanticsNodes().size == 2
        }
        assertEquals(0, writer.writes.size)
    }

    @Test
    fun dialogTargetRowsRenderAsIndependentTextNodes() {
        val capture = MutableCapture(sameTitleState())
        setContent(LockAuthoringModule(capture, FakeWriter(capture)))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val homeDescription = context.getString(R.string.organizer_lock_screen_placement_desktop, 1)
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(homeDescription).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(homeDescription).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_title, "Google"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        // "Target: Google" and the placement description are separate text
        // nodes: an exact match on the target line only succeeds if the title
        // was not concatenated into one body string, and the description node
        // appears next to the list row (2 nodes total).
        composeRule.onNodeWithText(
            context.getString(R.string.organizer_lock_dialog_target_title, "Google"),
        ).assertIsDisplayed()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(homeDescription).fetchSemanticsNodes().size == 2
        }
    }

    @Test
    fun dialogResolvesCollidingDescriptionsWithDisambiguator() {
        val capture = MutableCapture(collidingTitleState())
        val writer = FakeWriter(capture)
        setContent(LockAuthoringModule(capture, writer))
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val homeDescription = context.getString(R.string.organizer_lock_screen_placement_desktop, 1)
        val targetTitle = context.getString(R.string.organizer_lock_dialog_target_title, "Google")
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(homeDescription).fetchSemanticsNodes().isNotEmpty()
        }
        assertEquals(0, writer.writes.size)
        // Two same-named icons on one page share the "Home screen 1" row
        // description; the disambiguator line resolves them by cell.
        val homeRows = composeRule.onAllNodesWithText(homeDescription)
        homeRows[0].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(targetTitle).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(R.string.organizer_lock_dialog_target_position, 1, 1))
            .assertIsDisplayed()
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeRule.onAllNodesWithText(homeDescription)[1].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_position, 2, 4),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        // Two same-named "FChild" icons in differently-named folders at the
        // same position share the row description; the disambiguator resolves
        // them by the parent folder's title. The pair of rows is brought on
        // screen by scrolling to the first row's unique-ish sibling title.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("FChild"))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("FChild").fetchSemanticsNodes().size == 2
        }
        val home2Description = context.getString(R.string.organizer_lock_screen_placement_desktop, 2)
        composeRule.onAllNodesWithText("FChild")[0].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_folder, "G") +
                    " · " + home2Description + " · " +
                    context.getString(R.string.organizer_lock_dialog_target_position, 1, 1),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeRule.onAllNodesWithText("FChild")[1].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_folder, "H") +
                    " · " + home2Description + " · " +
                    context.getString(R.string.organizer_lock_dialog_target_position, 1, 3),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        // Second review P1: two same-named folders ("Same") each holding a
        // same-named child at the same position produce identical title +
        // description + parent title; the parent's own placement appended to
        // the disambiguator is the distinguishing part.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("SChild"))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("SChild").fetchSemanticsNodes().size == 2
        }
        composeRule.onAllNodesWithText("SChild")[0].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_folder, "Same") +
                    " · " + home2Description + " · " +
                    context.getString(R.string.organizer_lock_dialog_target_position, 3, 1),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        // Cancel can leave the list scrolled; re-scroll before tapping the
        // second same-named child (needed at 200% font scale).
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("SChild"))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("SChild").fetchSemanticsNodes().size == 2
        }
        composeRule.onAllNodesWithText("SChild")[1].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_folder, "Same") +
                    " · " + home2Description + " · " +
                    context.getString(R.string.organizer_lock_dialog_target_position, 3, 3),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        // First review class: two same-named app pairs ("Duo") each holding a
        // same-named member share the "In an app pair" description; the
        // disambiguator resolves them by pair title plus the pair's placement.
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("PChild"))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("PChild").fetchSemanticsNodes().size == 2
        }
        composeRule.onAllNodesWithText("PChild")[0].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_app_pair, "Duo") +
                    " · " + context.getString(R.string.organizer_lock_screen_placement_desktop, 3) + " · " +
                    context.getString(R.string.organizer_lock_dialog_target_position, 1, 1),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        // Re-scroll for the second member row (needed at 200% font scale).
        composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("PChild"))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText("PChild").fetchSemanticsNodes().size == 2
        }
        composeRule.onAllNodesWithText("PChild")[1].performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_app_pair, "Duo") +
                    " · " + context.getString(R.string.organizer_lock_screen_placement_desktop, 3) + " · " +
                    context.getString(R.string.organizer_lock_dialog_target_position, 1, 3),
            ).fetchSemanticsNodes().isNotEmpty()
        }
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

    /**
     * Issue #211 device evidence: captures the same-named rows and the
     * target-naming dialog from the real composable with real resources, for
     * `docs/evidence/issue-211/`. Runs on the local emulator; the captured
     * PNGs land in `Pictures/Issue211-ui-evidence` via MediaStore.
     *
     * PR #264 review: this is evidence capture, not a regression assertion —
     * it writes to shared device storage, so it is skipped unless the runner
     * is explicitly invoked with `-e captureEvidence true` (Gradle:
     * `-Pandroid.testInstrumentationRunnerArguments.captureEvidence=true`).
     */
    @Test
    fun capturesLockDialogTargetEvidence() {
        org.junit.Assume.assumeTrue(
            androidx.test.platform.app.InstrumentationRegistry.getArguments()
                .getString("captureEvidence") == "true",
        )
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val homeDescription = context.getString(R.string.organizer_lock_screen_placement_desktop, 1)
        val folderDescription = context.getString(R.string.organizer_lock_screen_placement_folder, 1)
        val capture = MutableCapture(sameTitleState())
        setContent(LockAuthoringModule(capture, FakeWriter(capture)))
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(homeDescription).fetchSemanticsNodes().isNotEmpty()
        }
        captureLockDialogScreenshot(context, "rows-same-title")
        composeRule.onNodeWithText(homeDescription).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(
                context.getString(R.string.organizer_lock_dialog_target_title, "Google"),
            ).fetchSemanticsNodes().isNotEmpty()
        }
        captureLockDialogScreenshot(context, "dialog-names-tapped-row")
        composeRule.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
        composeRule.onNodeWithText(folderDescription).performClick()
        composeRule.waitUntil(5_000) {
            composeRule.onAllNodesWithText(folderDescription).fetchSemanticsNodes().size == 2
        }
        captureLockDialogScreenshot(context, "dialog-names-folder-row")
    }

    private fun captureLockDialogScreenshot(context: android.content.Context, name: String) {
        composeRule.waitForIdle()
        // PixelCopy of the hosting window (activity window for the list,
        // dialog window once the dialog is open) instead of a full-display
        // uiAutomation screenshot: transient emulator system overlays (ANR
        // dialogs) must not photobomb the evidence.
        val isDialog = SemanticsMatcher.keyIsDefined(SemanticsProperties.IsDialog)
        val hasDialog = composeRule.onAllNodes(isDialog).fetchSemanticsNodes().isNotEmpty()
        val bitmap = (if (hasDialog) {
            composeRule.onNode(isDialog).captureToImage()
        } else {
            composeRule.onRoot().captureToImage()
        }).asAndroidBitmap()
        val values = android.content.ContentValues().apply {
            put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, "$name.png")
            put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Issue211-ui-evidence")
            put(android.provider.MediaStore.Images.Media.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        // MediaStore keeps rows from previous capture runs; delete rows with
        // the same name first so re-running cannot collide on the file path.
        resolver.delete(
            android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            "${android.provider.MediaStore.Images.Media.DISPLAY_NAME} = ?",
            arrayOf("$name.png"),
        )
        val uri = requireNotNull(
            resolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values),
        )
        try {
            check(
                resolver.openOutputStream(uri).use { output ->
                    output != null && bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, output)
                },
            )
            values.clear()
            values.put(android.provider.MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
        } catch (error: Throwable) {
            resolver.delete(uri, null, null)
            throw error
        }
    }
}
