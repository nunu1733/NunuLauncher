package app.lawnchair.organizer.application.preview

import app.lawnchair.organizer.application.actions.OrganizationPlanMaterializer
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.ApplicationPageRef
import app.lawnchair.organizer.application.public.CanonicalItemKind
import app.lawnchair.organizer.application.public.CanonicalItemState
import app.lawnchair.organizer.application.public.DeviceCapabilities
import app.lawnchair.organizer.application.public.DeviceOrientation
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
import app.lawnchair.organizer.application.public.StructureState
import app.lawnchair.organizer.application.public.WidgetState
import app.lawnchair.organizer.planning.Availability
import app.lawnchair.organizer.planning.CapturedItem
import app.lawnchair.organizer.planning.CapturedPlacement
import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.ItemKind
import app.lawnchair.organizer.planning.OrganizationInput
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.RunMode
import app.lawnchair.organizer.planning.SplitStage
import app.lawnchair.organizer.planning.WarningCode
import app.lawnchair.organizer.planning.harness.ExampleCorpus
import app.lawnchair.organizer.planning.harness.PlannerFixture
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #194 PP-AC-12 corpus contract between the planner's semantic plan and
 * the projector's `PreviewCounts`.
 *
 * The two count derivations diverge exactly when the plan contains a
 * disposition the executable rows cannot mirror: a `Preserved` placement whose
 * target departs from the captured placement (materializes as `Update`, hence
 * no `PreservedChange` row), a `Moved` placement whose target reproduces the
 * captured placement (materializes as `Preserve`), or a preserved folder whose
 * membership changes (materializes as a placement-unchanged `Update` that
 * projects no row). The corpus checks assert none of these occur anywhere in
 * the v1 planner fixtures, and the end-to-end section proves header equality
 * on every full-organization fixture that reaches confirmation preview.
 *
 * A failure here must be surfaced to the planner contract — it is never
 * normalized away in the projection.
 */
class PlanPreviewCountsCorpusContractTest {

    private val planner = DeterministicOrganizationPlanner()

    private fun allFixtures(): List<Pair<String, PlannerFixture>> = ExampleCorpus.allExamples.values.map { "example:${it.id.value}" to it } +
        ExampleCorpus.validationFixtures.values.map { "validation:${it.id.value}" to it } +
        SyntheticFixtureGenerator.generate().map { "generated:${it.id.value}" to it }

    @Test
    fun preservedPlacementsAlwaysReproduceTheirCapturedPlacement() {
        val failures = StringBuilder()
        var checked = 0
        allFixtures().forEach { (label, fixture) ->
            val planned = planner.plan(fixture.input).outcome as? Planned ?: return@forEach
            val itemById = fixture.input.snapshot.items.associateBy { it.id }
            planned.placements.forEach { placement ->
                checked++
                val captured = itemById[placement.item]?.placement ?: return@forEach
                val reproduces = targetReproducesCaptured(captured, placement.target)
                when (val disposition = placement.disposition) {
                    is Disposition.Preserved -> if (!reproduces) {
                        failures.append("[$label] ${placement.item.value}: Preserved(${disposition.reason}) target departs from the captured placement\n")
                    }

                    is Disposition.Moved -> if (reproduces) {
                        failures.append("[$label] ${placement.item.value}: Moved(${disposition.rationale}) target reproduces the captured placement\n")
                    }
                }
            }
        }
        assertTrue("corpus checked no placements — wiring is broken", checked > 0)
        assertTrue("disposition/target divergences:\n$failures", failures.isEmpty())
    }

    @Test
    fun preservedFoldersKeepTheirCapturedMembership() {
        val failures = StringBuilder()
        var checked = 0
        allFixtures().forEach { (label, fixture) ->
            val planned = planner.plan(fixture.input).outcome as? Planned ?: return@forEach
            val itemById = fixture.input.snapshot.items.associateBy { it.id }
            val dispositionByItem = planned.placements.associate { it.item to it.disposition }
            val targetByItem = planned.placements.associate { it.item to it.target }
            planned.placements.forEach { placement ->
                val folder = itemById[placement.item] ?: return@forEach
                if (folder.kind != ItemKind.FOLDER) return@forEach
                if (placement.disposition !is Disposition.Preserved) return@forEach
                checked++
                folder.members.forEach { memberId ->
                    val member = itemById[memberId] ?: return@forEach
                    val memberDisposition = dispositionByItem[memberId]
                    if (memberDisposition !is Disposition.Preserved) {
                        failures.append("[$label] folder ${placement.item.value}: member ${memberId.value} is ${memberDisposition ?: "missing"} while the folder is preserved\n")
                        return@forEach
                    }
                    val target = targetByItem[memberId]
                    val captured = member.placement
                    if (target == null || !targetReproducesCaptured(captured, target)) {
                        failures.append("[$label] folder ${placement.item.value}: preserved member ${memberId.value} departs from its captured placement\n")
                    }
                }
            }
        }
        assertTrue("corpus checked no preserved folders — wiring is broken", checked > 0)
        assertTrue("preserved-folder membership divergences:\n$failures", failures.isEmpty())
    }

    @Test
    fun previewableFullOrganizationFixturesSummariesMatchProjectedCounts() {
        var plannedFixtures = 0
        var previewedFixtures = 0
        val failures = StringBuilder()
        allFixtures().forEach { (label, fixture) ->
            val input = fixture.input
            // Confirmation preview is a full-organization surface; incremental
            // placement runs never reach inspectPlan.
            if (input.runMode != RunMode.FullOrganization) return@forEach
            val result = planner.plan(input)
            val planned = result.outcome as? Planned ?: return@forEach
            plannedFixtures++
            val summary = summaryCountsOf(planned)
            if (summary.movedCount == 0 && summary.newFolderCount == 0 && summary.newPageCount == 0) {
                // Empty diff never reaches confirmation preview (NoChanges gate).
                return@forEach
            }
            previewedFixtures++
            try {
                val sourceState = canonicalStateOf(input)
                val materialized = OrganizationPlanMaterializer.materialize(input, result, sourceState)
                val plan = (materialized as? OrganizationPlanMaterializer.Result.Ready)?.plan
                    ?: error("materializer rejected a planner-produced plan")
                val projection = PlanPreviewProjector.project(plan, planned)
                val details = (projection as? PlanPreviewProjector.Result.Ready)?.details
                    ?: error("projection rejected a materializer-valid plan")
                assertEquals("[$label] movedCount", summary.movedCount, details.counts.movedCount)
                assertEquals("[$label] preservedCount", summary.preservedCount, details.counts.preservedCount)
                assertEquals("[$label] newFolderCount", summary.newFolderCount, details.counts.newFolderCount)
                assertEquals("[$label] newPageCount", summary.newPageCount, details.counts.newPageCount)
                assertEquals("[$label] warningCounts", summary.warningCounts, details.counts.warningCounts)
            } catch (failure: AssertionError) {
                failures.append("[$label] ${failure.message}\n")
            } catch (failure: IllegalStateException) {
                failures.append("[$label] ${failure.message}\n")
            }
        }
        assertTrue(
            "no full-organization planner fixture reached the preview path — corpus wiring is broken",
            previewedFixtures > 0,
        )
        assertTrue(
            "planned=$plannedFixtures previewed=$previewedFixtures mismatches:\n$failures",
            failures.isEmpty(),
        )
    }

    private data class CountSummary(
        val movedCount: Int,
        val preservedCount: Int,
        val newFolderCount: Int,
        val newPageCount: Int,
        val warningCounts: Map<WarningCode, Int>,
    )

    /** The five count categories exactly as `ManualOrganizationRun.Summary` derives them. */
    private fun summaryCountsOf(planned: Planned) = CountSummary(
        movedCount = planned.placements.count { it.disposition is Disposition.Moved },
        preservedCount = planned.placements.count { it.disposition is Disposition.Preserved },
        newFolderCount = planned.newFolders.size,
        newPageCount = planned.newPages.size,
        warningCounts = planned.warnings.groupingBy { it.code }.eachCount(),
    )

    private fun targetReproducesCaptured(captured: CapturedPlacement, target: PlacementTarget): Boolean = when (captured) {
        is CapturedPlacement.Workspace ->
            target is PlacementTarget.WorkspaceTarget &&
                target.page == captured.page &&
                target.cell == captured.cell &&
                target.span == captured.span

        is CapturedPlacement.Dock -> target is PlacementTarget.Dock && target.rank == captured.rank

        is CapturedPlacement.FolderMember ->
            target is PlacementTarget.FolderMember &&
                target.folder == captured.folder &&
                target.rank == captured.rank

        is CapturedPlacement.AppPairMember -> target is PlacementTarget.AppPairMember && target.pair == captured.pair

        is CapturedPlacement.UnsupportedContainer -> false
    }

    /**
     * Forward mapping of the planning snapshot into the canonical apply state.
     * Only placement identity matters beyond reachability: the materializer
     * compares `expected`/`intended` copies that differ in placement alone, so
     * every non-placement field passes through unchanged.
     */
    private fun canonicalStateOf(input: OrganizationInput): LayoutState {
        val snapshot = input.snapshot
        val stageByMemberId = snapshot.items
            .flatMap { item -> item.appPair?.members.orEmpty().map { it.item to it.stage } }
            .toMap()
        val items = snapshot.items.map { item -> canonicalItemOf(item, stageByMemberId) }
        return LayoutState(
            pages = snapshot.pages.map { PageState(ApplicationPageRef.PersistentPage(it.id), it.order) },
            profiles = snapshot.items.map { it.profile }.distinct().map { ProfileState(it, ProfileAvailability.AVAILABLE) },
            deviceCapabilities = DeviceCapabilities(
                columns = snapshot.device.columns,
                rows = snapshot.device.rows,
                hotseatSlots = snapshot.device.hotseatSlots,
                folderMaxColumns = snapshot.device.folderMaxColumns,
                folderMaxRows = snapshot.device.folderMaxRows,
                orientation = when (snapshot.device.orientation) {
                    app.lawnchair.organizer.planning.Orientation.PORTRAIT,
                    app.lawnchair.organizer.planning.Orientation.TWO_PANEL_PORTRAIT,
                    -> DeviceOrientation.PORTRAIT

                    app.lawnchair.organizer.planning.Orientation.LANDSCAPE,
                    app.lawnchair.organizer.planning.Orientation.TWO_PANEL_LANDSCAPE,
                    -> DeviceOrientation.LANDSCAPE
                },
            ),
            items = items,
            reservedWorkspaceRegions = snapshot.reservedWorkspaceRegions,
        )
    }

    private fun canonicalItemOf(item: CapturedItem, stageByMemberId: Map<ItemId, SplitStage>): CanonicalItemState {
        val kind = when (item.kind) {
            ItemKind.APPLICATION -> CanonicalItemKind.Application
            ItemKind.DEEP_SHORTCUT -> CanonicalItemKind.DeepShortcut
            ItemKind.SHORTCUT_LEGACY -> CanonicalItemKind.ShortcutLegacy
            ItemKind.FOLDER -> CanonicalItemKind.Folder
            ItemKind.APPWIDGET -> CanonicalItemKind.AppWidget
            ItemKind.CUSTOM_APPWIDGET -> CanonicalItemKind.CustomAppWidget
            ItemKind.APP_PAIR -> CanonicalItemKind.AppPair
            is ItemKind.Unknown -> error("planned corpus fixture must not carry unknown kinds")
        }
        val placement = when (val value = item.placement) {
            is CapturedPlacement.Workspace -> PlacementState.Workspace(
                page = ApplicationPageRef.PersistentPage(value.page.pageId),
                cell = value.cell,
                span = value.span,
            )

            is CapturedPlacement.Dock -> PlacementState.Dock(value.rank)

            is CapturedPlacement.FolderMember -> PlacementState.FolderChild(
                parent = ApplicationItemRef.PersistentItem(ItemId(value.folder.folderId.value)),
                rank = value.rank,
            )

            is CapturedPlacement.AppPairMember -> PlacementState.AppPairChild(
                parent = ApplicationItemRef.PersistentItem(ItemId(value.pair.appPairId.value)),
                stage = stageByMemberId[item.id] ?: SplitStage.TOP_OR_LEFT,
            )

            is CapturedPlacement.UnsupportedContainer -> error("planned corpus fixture must not carry unsupported containers")
        }
        val structure = when {
            item.kind == ItemKind.FOLDER -> StructureState.FolderMembers(
                item.members.mapIndexed { rank, member ->
                    app.lawnchair.organizer.application.public.RankedMember(ApplicationItemRef.PersistentItem(member), rank)
                },
            )

            item.kind == ItemKind.APP_PAIR -> StructureState.AppPairMembers(
                members = item.appPair?.members.orEmpty().map { member ->
                    app.lawnchair.organizer.application.public.AppPairMemberState(
                        item = ApplicationItemRef.PersistentItem(member.item),
                        stage = member.stage,
                    )
                },
                snapPosition = item.appPair?.members?.firstOrNull()?.snapPosition
                    ?.let { OptionalSnapPosition.Present(it) }
                    ?: OptionalSnapPosition.Absent,
            )

            else -> StructureState.Plain
        }
        return CanonicalItemState(
            ref = ApplicationItemRef.PersistentItem(item.id),
            kind = kind,
            targetKey = item.target,
            profile = item.profile,
            profileAvailability = ProfileAvailability.AVAILABLE,
            itemAvailability = when (item.availability) {
                Availability.AVAILABLE -> ItemAvailability.AVAILABLE
                Availability.DISABLED -> ItemAvailability.DISABLED
                Availability.QUIET -> ItemAvailability.QUIET
                Availability.LOCKED_PRIVATE_SPACE -> ItemAvailability.LOCKED_PRIVATE_SPACE
                Availability.UNAVAILABLE -> ItemAvailability.UNAVAILABLE
            },
            placement = placement,
            title = OptionalText.Present("T${item.id.value}"),
            intent = OptionalText.Absent,
            icon = OptionalBytes.Absent,
            widget = WidgetState.NoWidget,
            modified = ModifiedAtMillis(0),
            lockState = if (item.locked) OrganizerLockState.LOCKED else OrganizerLockState.UNLOCKED,
            structure = structure,
        )
    }
}
