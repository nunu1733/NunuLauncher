package app.lawnchair.organizer.planning

import app.lawnchair.organizer.planning.harness.DEFAULT_PLANNER_CASE_COUNT
import app.lawnchair.organizer.planning.harness.MaterializationResult
import app.lawnchair.organizer.planning.harness.Oracle
import app.lawnchair.organizer.planning.harness.PostPlanMaterializer
import app.lawnchair.organizer.planning.harness.SyntheticFixtureGenerator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue #451 property suite (spec 451 AC-2, plan Verification): duplicate
 * injection wrapper over the unchanged synthetic corpus.
 *
 * The injection wrapper deliberately does NOT extend `SyntheticFixtureGenerator`
 * or the pinned golden corpus: `SyntheticFixtureGenerator` emits a fixed
 * `template = index % 8` corpus whose per-case digests must stay unchanged
 * (spec N-7 / AC-5), so duplicates are injected here, on top of each generated
 * input, and every injected case is planned through the public
 * `OrganizationPlanner.plan` seam. For every injectable generated case:
 *
 * 1. every planned new folder contains unique launch targets (the #451 core
 *    guarantee — "全新規フォルダ内で起動先が一意"),
 * 2. every duplicate surplus item is preserved exactly once at its captured
 *    target with `DUPLICATE_LAUNCH_TARGET` (conservation of the surplus),
 * 3. the same input plans twice to the same complete result (P-09), and
 * 4. materialize-and-replan moves nothing and creates no pages or folders
 *    (P-10).
 *
 * Cases without an injectable source (no movable top-level workspace
 * application/deep-shortcut item, or no free cell for the clone) are skipped
 * honestly: the property is asserted for every case where a duplicate can be
 * constructed without corrupting the generated fixture.
 */
class DuplicateInjectionPropertyTest {

    private val planner: OrganizationPlanner = DeterministicOrganizationPlanner()

    @Test
    fun injectedDuplicatesNeverJoinANewFolderAndStayIdempotent() {
        val fixtures = SyntheticFixtureGenerator.generate(count = DEFAULT_PLANNER_CASE_COUNT)
        var injectedCases = 0
        val failures = StringBuilder()

        for (fixture in fixtures) {
            // The duplicate property governs planned runs; generated cases
            // whose own contract is a rejection (e.g. unavailable candidates)
            // are out of scope and skipped unchanged.
            if (planner.plan(fixture.input).outcome !is Planned) continue
            val input = injectDuplicate(fixture.input) ?: continue
            injectedCases++
            val label = "case ${fixture.id.value} (seed=${fixture.reproduction?.seed}, " +
                "index=${fixture.reproduction?.caseIndex})"

            val result = planner.plan(input)
            val planned = result.outcome as? Planned
            if (planned == null) {
                failures.append("$label: injected input was rejected: ${result.outcome}\n")
                continue
            }

            // 1. Conservation of every captured item (surplus included).
            Oracle.checkConservation(input, planned).forEach {
                failures.append("$label: ${it.message}\n")
            }

            // 2. Every planned new folder has unique member launch targets.
            val targetById = input.snapshot.items.associate { it.id to it.target }
            planned.newFolders.forEach { folder ->
                val targets = folder.members.map { targetById.getValue(it) }
                if (targets.size != targets.distinct().size) {
                    failures.append("$label: folder ${folder.ordinal.value} has duplicate launch targets $targets\n")
                }
            }

            // 3. Every surplus item: exactly one preservation at its captured
            //    target. The reason is DUPLICATE_LAUNCH_TARGET unless a higher
            //    preservation predicate also applies (spec N-3: locked,
            //    unavailable, NON_TARGET, structural duplicates keep their
            //    stronger reason) — being moved is the only violation.
            val placementsById = planned.placements.groupBy { it.item }
            val surplus = duplicateSurplusIds(input.snapshot.items)
            input.snapshot.items.filter { it.id in surplus }.forEach { item ->
                val rows = placementsById[item.id].orEmpty()
                if (rows.size != 1) {
                    failures.append("$label: surplus ${item.id} has ${rows.size} placements\n")
                    return@forEach
                }
                if (rows.single().disposition !is Disposition.Preserved) {
                    failures.append("$label: surplus ${item.id} was moved: ${rows.single().disposition}\n")
                }
                if (rows.single().target != capturedToOutput(item.placement)) {
                    failures.append("$label: surplus ${item.id} left its captured target\n")
                }
            }

            // 4. N-4: one DUPLICATE_LAUNCH_TARGET warning per surplus item,
            //    none for representatives — regardless of which stronger
            //    preservation predicate (if any) applies to the surplus.
            val warnedIds = planned.warnings
                .filter { it.code == WarningCode.DUPLICATE_LAUNCH_TARGET }
                .flatMap { warning -> warning.params.filterIsInstance<DiagnosticParam.ItemParam>().map { it.item } }
            if (warnedIds.size != warnedIds.distinct().size || warnedIds.toSet() != surplus) {
                failures.append("$label: warned ids $warnedIds must equal the surplus set $surplus\n")
            }

            // 5. P-09 determinism on the injected input.
            if (planner.plan(input) != result) {
                failures.append("$label: same injected input produced unequal results\n")
            }

            // 6. P-10 idempotence through the materializer seam.
            when (val materialized = PostPlanMaterializer.materialize(input, planned)) {
                is MaterializationResult.Failed ->
                    failures.append("$label: materialization failed: ${materialized.findings}\n")

                is MaterializationResult.Success -> {
                    val replanned = planner.plan(materialized.input).outcome as? Planned
                    if (replanned == null) {
                        failures.append("$label: replan was not Planned\n")
                    } else {
                        if (replanned.placements.any { it.disposition is Disposition.Moved }) {
                            failures.append("$label: replan moved an item\n")
                        }
                        if (replanned.newPages.isNotEmpty()) failures.append("$label: replan created pages\n")
                        if (replanned.newFolders.isNotEmpty()) failures.append("$label: replan created folders\n")
                    }
                }
            }
        }

        assertTrue(
            "the corpus must contain injectable cases (found $injectedCases); " +
                "check the injection precondition filters",
            injectedCases > 0,
        )
        assertTrue(
            "duplicate injection property violations:\n$failures",
            failures.isEmpty(),
        )
    }

    /**
     * Clones the first injectable captured item (application or deep shortcut
     * at a top-level workspace placement) into a free 1x1 cell on any captured
     * page, sharing the original's launch target. Returns null when the input
     * offers no injectable source or no free cell.
     */
    private fun injectDuplicate(input: OrganizationInput): OrganizationInput? {
        val source = input.snapshot.items.firstOrNull { item ->
            (item.kind == ItemKind.APPLICATION || item.kind == ItemKind.DEEP_SHORTCUT) &&
                (item.target is TargetKey.AppKey || item.target is TargetKey.ShortcutKey) &&
                item.placement is CapturedPlacement.Workspace
        } ?: return null

        val occupied = mutableListOf<Triple<PageId, GridCell, GridSpan>>()
        input.snapshot.items.forEach { item ->
            (item.placement as? CapturedPlacement.Workspace)?.let {
                occupied += Triple(it.page.pageId, it.cell, it.span)
            }
        }
        input.snapshot.reservedWorkspaceRegions.forEach { region ->
            occupied += Triple(region.page.pageId, region.cell, region.span)
        }

        val freeCell = input.snapshot.pages.asSequence()
            .flatMap { page ->
                (0 until input.snapshot.device.rows).asSequence().flatMap { y ->
                    (0 until input.snapshot.device.columns).asSequence().map { x -> page.id to GridCell(x, y) }
                }
            }
            .firstOrNull { (pageId, cell) ->
                occupied.none { (occupiedPage, occupiedCell, span) ->
                    occupiedPage == pageId &&
                        cell.x < occupiedCell.x + span.width &&
                        occupiedCell.x < cell.x + 1 &&
                        cell.y < occupiedCell.y + span.height &&
                        occupiedCell.y < cell.y + 1
                }
            }
            ?: return null

        val clone = source.copy(
            id = ItemId("fixture.dup.${source.id.value}"),
            placement = CapturedPlacement.Workspace(PageRef(freeCell.first), freeCell.second, GridSpan(1, 1)),
            locked = false,
            availability = Availability.AVAILABLE,
        )
        return input.copy(
            snapshot = input.snapshot.copy(items = input.snapshot.items + clone),
            targets = input.targets.copy(
                existing = input.targets.existing + ExistingTargetMembership(clone.id, ExistingRole.Movable),
            ),
        )
    }
}
