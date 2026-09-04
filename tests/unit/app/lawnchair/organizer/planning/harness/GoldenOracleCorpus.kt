package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.PlanningResult
import java.security.MessageDigest

/**
 * Spec 182 byte-equivalence oracle (child 2/child 3).
 *
 * The golden file is pinned from the accepted pre-#182 baseline commit
 * (`5fdab48082`, recorded in spec 182). On that baseline, run
 * `GoldenOracleCorpusTest.generate` (system property `golden.write=baseline`)
 * to emit `planner-golden-corpus/sha256.txt`; the committed digest then gates
 * both the selection child (registry/adapter dispatch) and the extraction
 * child. The strategy echo is excluded from the digest — it is metadata, not
 * layout observation.
 */
internal object GoldenOracleCorpus {

    /**
     * Canonical plan payload: placements, new pages, new folders, categories,
     * warnings. The `organizationStrategy`, `ruleVersion`, `taxonomyVersion`,
     * and `revision` echoes are deliberately excluded — they are metadata, not
     * layout observation, and the rule version legitimately changes v1 → v2.
     */
    fun canonicalPayload(result: PlanningResult): String = buildString {
        when (val outcome = result.outcome) {
            is app.lawnchair.organizer.planning.Planned -> {
                append("outcome=planned\n")
                for (placement in outcome.placements) {
                    append("placement=").append(placement.item.value)
                        .append('|').append(dispositionToken(placement.disposition))
                        .append('|').append(targetToken(placement.target))
                        .append('\n')
                }
                for (page in outcome.newPages) {
                    append("newPage=").append(page.ordinal.value).append('|').append(page.order.value).append('\n')
                }
                for (folder in outcome.newFolders) {
                    append("newFolder=").append(folder.ordinal.value)
                        .append('|').append(folder.profile.value)
                        .append('|').append(folder.naming.javaClass.simpleName)
                        .append('|').append(folder.workspacePlacement.cell.x)
                        .append(',').append(folder.workspacePlacement.cell.y)
                        .append(',').append(folder.workspacePlacement.span.width)
                        .append('x').append(folder.workspacePlacement.span.height)
                        .append('|').append(folder.members.joinToString(",") { it.value })
                        .append('\n')
                }
                for (category in outcome.categories) {
                    append("category=").append(category.item.value)
                        .append('|').append(category.category.value)
                        .append('|').append(category.decidedSignal.name)
                        .append('|').append(category.confidence.name)
                        .append('\n')
                }
                for (warning in outcome.warnings) {
                    append("warning=").append(warning.code.name)
                        .append('|').append(warning.params.joinToString(",") { it.javaClass.simpleName })
                        .append('\n')
                }
            }

            is app.lawnchair.organizer.planning.Rejected.Invalid -> {
                append("outcome=invalid\n")
                for (reason in outcome.reasons) {
                    append("reason=").append(reason.code.name).append('\n')
                }
            }

            is app.lawnchair.organizer.planning.Rejected.Impossible -> {
                append("outcome=impossible\n")
                for (unplaced in outcome.unplaced) {
                    append("unplaced=").append(unplaced.item.value)
                        .append('|').append(unplaced.reason.name)
                        .append('|').append(unplaced.requiredSpan.width)
                        .append('x').append(unplaced.requiredSpan.height)
                        .append('\n')
                }
            }
        }
    }

    fun digestOf(results: List<PlanningResult>): String {
        val canonical = results.joinToString("\u0000") { canonicalPayload(it) }
        return MessageDigest.getInstance("SHA-256")
            .digest(canonical.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    /** Every accepted fixture + generated case, planned through the public seam. */
    fun planAll(): List<Pair<String, PlanningResult>> {
        val planner = DeterministicOrganizationPlanner()
        val planned = mutableListOf<Pair<String, PlanningResult>>()
        for (fixture in ExampleCorpus.allExamples.values) {
            planned += "fixture:${fixture.id.value}" to planner.plan(fixture.input)
        }
        for (fixture in ExampleCorpus.validationFixtures.values) {
            planned += "fixture:${fixture.id.value}" to planner.plan(fixture.input)
        }
        for (fixture in SyntheticFixtureGenerator.generate(seed = SyntheticFixtureGenerator.DEFAULT_SEED, count = DEFAULT_PLANNER_CASE_COUNT)) {
            planned += "generated:${fixture.id.value}" to planner.plan(fixture.input)
        }
        return planned.sortedBy { it.first }
    }

    const val GOLDEN_RESOURCE_PATH = "/planner-golden-corpus/sha256.txt"

    private fun dispositionToken(disposition: app.lawnchair.organizer.planning.Disposition): String = when (disposition) {
        is app.lawnchair.organizer.planning.Disposition.Moved -> "moved:${disposition.rationale.name}"
        is app.lawnchair.organizer.planning.Disposition.Preserved -> "preserved:${disposition.reason.name}"
    }

    private fun targetToken(target: app.lawnchair.organizer.planning.PlacementTarget): String = when (target) {
        is app.lawnchair.organizer.planning.PlacementTarget.WorkspaceTarget ->
            "ws:${target.page.javaClass.simpleName}:${pageRefValue(target.page)}:${target.cell.x},${target.cell.y},${target.span.width}x${target.span.height}"

        is app.lawnchair.organizer.planning.PlacementTarget.Dock -> "dock:${target.rank}"

        is app.lawnchair.organizer.planning.PlacementTarget.FolderMember ->
            "fm:${target.folder.javaClass.simpleName}:${folderRefValue(target.folder)}:${target.rank}"

        is app.lawnchair.organizer.planning.PlacementTarget.AppPairMember -> "ap:${target.pair.appPairId.value}"
    }

    private fun pageRefValue(page: app.lawnchair.organizer.planning.PageTargetRef): String = when (page) {
        is app.lawnchair.organizer.planning.PageRef -> page.pageId.value
        is app.lawnchair.organizer.planning.NewPageRef -> "np${page.ordinal.value}"
    }

    private fun folderRefValue(folder: app.lawnchair.organizer.planning.FolderTargetRef): String = when (folder) {
        is app.lawnchair.organizer.planning.FolderRef -> folder.folderId.value
        is app.lawnchair.organizer.planning.NewFolderRef -> "nf${folder.ordinal.value}"
    }
}
