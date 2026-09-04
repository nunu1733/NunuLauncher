package app.lawnchair.organizer.planning.harness

import app.lawnchair.organizer.planning.DeterministicOrganizationPlanner
import app.lawnchair.organizer.planning.DiagnosticParam
import app.lawnchair.organizer.planning.Disposition
import app.lawnchair.organizer.planning.FolderNaming
import app.lawnchair.organizer.planning.PlacementTarget
import app.lawnchair.organizer.planning.Planned
import app.lawnchair.organizer.planning.PlanningResult
import app.lawnchair.organizer.planning.Rejected
import java.security.MessageDigest

/**
 * Spec 182 byte-equivalence oracle (child 2/child 3).
 *
 * The golden file is pinned from the accepted pre-#182 baseline commit
 * (`5fdab48082`, recorded in spec 182). On that baseline, run
 * `GoldenOracleCorpusTest` with `-Dgolden.write=true` to emit
 * `planner-golden-corpus/sha256.txt`; the committed digest then gates both the
 * selection child (registry/adapter dispatch) and the extraction child.
 *
 * The canonical payload losslessly serializes the observable `PlanningOutcome`:
 * placements, new pages, new folders (naming category and target page
 * included), categories, warnings with full param values, rejection reasons
 * with full params, unplaced items, and rejected warnings. Only the four
 * metadata echoes — `revision`, `ruleVersion`, `taxonomyVersion`,
 * `organizationStrategy` — are excluded, per spec 182: they are identity
 * metadata, not layout observation, and the rule version legitimately changes
 * v1 → v2 across the boundary.
 */
internal object GoldenOracleCorpus {

    fun canonicalPayload(result: PlanningResult): String = buildString {
        when (val outcome = result.outcome) {
            is Planned -> {
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
                        .append('|').append(folderNamingToken(folder.naming))
                        .append('|').append(folder.workspacePlacement.page.javaClass.simpleName)
                        .append(':').append(pageRefValue(folder.workspacePlacement.page))
                        .append(':').append(folder.workspacePlacement.cell.x)
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
                    append("warning=").append(warningToken(warning)).append('\n')
                }
            }

            is Rejected.Invalid -> {
                append("outcome=invalid\n")
                for (reason in outcome.reasons) {
                    append("reason=").append(reason.code.name)
                        .append('|').append(reason.params.joinToString(",") { paramToken(it) })
                        .append('\n')
                }
                for (warning in outcome.warnings) {
                    append("invalidWarning=").append(warningToken(warning)).append('\n')
                }
            }

            is Rejected.Impossible -> {
                append("outcome=impossible\n")
                for (unplaced in outcome.unplaced) {
                    append("unplaced=").append(unplaced.item.value)
                        .append('|').append(unplaced.reason.name)
                        .append('|').append(unplaced.requiredSpan.width)
                        .append('x').append(unplaced.requiredSpan.height)
                        .append('\n')
                }
                for (warning in outcome.warnings) {
                    append("impossibleWarning=").append(warningToken(warning)).append('\n')
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

    /** Per-source digests alongside the aggregate, for pinpointing regressions. */
    fun digestsBySource(): List<Pair<String, String>> = planAll().map { (source, result) -> source to digestOf(listOf(result)) }

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
        return planned.apply { sortBy { it.first } }
    }

    const val GOLDEN_RESOURCE_PATH = "/planner-golden-corpus/sha256.txt"

    private fun dispositionToken(disposition: Disposition): String = when (disposition) {
        is Disposition.Moved -> "moved:${disposition.rationale.name}"
        is Disposition.Preserved -> "preserved:${disposition.reason.name}"
    }

    private fun targetToken(target: PlacementTarget): String = when (target) {
        is PlacementTarget.WorkspaceTarget ->
            "ws:${target.page.javaClass.simpleName}:${pageRefValue(target.page)}:${target.cell.x},${target.cell.y},${target.span.width}x${target.span.height}"

        is PlacementTarget.Dock -> "dock:${target.rank}"

        is PlacementTarget.FolderMember ->
            "fm:${target.folder.javaClass.simpleName}:${folderRefValue(target.folder)}:${target.rank}"

        is PlacementTarget.AppPairMember -> "ap:${target.pair.appPairId.value}"
    }

    private fun folderNamingToken(naming: FolderNaming): String = when (naming) {
        is FolderNaming.FromCategory -> "fromCategory:${naming.category.value}"
    }

    private fun warningToken(warning: app.lawnchair.organizer.planning.Warning): String = "${warning.code.name}|${warning.params.joinToString(",") { paramToken(it) }}"

    private fun paramToken(param: DiagnosticParam): String = when (param) {
        is DiagnosticParam.ItemParam -> "item:${param.item.value}"
        is DiagnosticParam.KindParam -> "kind:${param.code.value}"
        is DiagnosticParam.ContainerCodeParam -> "container:${param.code.value}"
        is DiagnosticParam.SpanParam -> "span:${param.span.width}x${param.span.height}"
        is DiagnosticParam.RankParam -> "rank:${param.rank}"
        is DiagnosticParam.DimensionParam -> "dim:${param.dimension.name}:${param.value}"
        is DiagnosticParam.PageParam -> "page:${param.page.value}"
        is DiagnosticParam.CategoryParam -> "category:${param.category.value}"
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
