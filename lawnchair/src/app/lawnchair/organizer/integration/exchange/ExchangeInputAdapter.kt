package app.lawnchair.organizer.integration.exchange

import app.lawnchair.organizer.application.protocol.CaptureId
import app.lawnchair.organizer.application.protocol.LayoutWriterPort
import app.lawnchair.organizer.application.public.ApplicationItemRef
import app.lawnchair.organizer.application.public.OptionalText
import app.lawnchair.organizer.integration.InputReadinessReason
import app.lawnchair.organizer.integration.OrganizationInputComposer
import app.lawnchair.organizer.integration.OrganizationInputComposition
import app.lawnchair.organizer.personalization.CanonicalStructuralInputs
import app.lawnchair.organizer.personalization.ExportInputs
import app.lawnchair.organizer.personalization.PersonalizationEntryKey
import app.lawnchair.organizer.planning.CandidatePlanningIds
import app.lawnchair.organizer.planning.CandidateTarget
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.TargetKey

/**
 * Issue #205: the single canonical-input adapter shared by exchange export
 * generation and import-time reconstruction (spec 205 "canonical入力sourceの
 * 単一化"). Both sides derive their inputs from the same
 * `OrganizationInputComposer` seam, so the structural trio, categories, and
 * signal snapshot can never drift between the two derivations.
 *
 * Issue #331 (spec 331 §1): there are TWO entries onto that single seam — the
 * idle entry ([composeForExport] over the full-organization composition, no
 * candidates) and the run-in entry ([composeForExport] with the confirmed
 * selection over the scope-composed composition). Both are the same adapter
 * and the same projection predicates; `composeFullOrganization()` and the
 * #228 selection state never become separate sources of truth.
 *
 * App titles (the user-authored free-text class) are the one composition
 * output the planning input drops; they are read from a canonical capture
 * through [ExchangeItemTitleSource]. Titles never join the structural digest,
 * so a stale title read cannot invalidate an exchange.
 */
class ExchangeInputAdapter(
    private val composer: OrganizationInputComposer,
    private val titleSource: ExchangeItemTitleSource,
) {

    /** Derives the export inputs for one generation attempt. */
    fun composeForExport(nowEpochMs: Long): ExchangeInputResult = composeFrom(nowEpochMs, composer.composeFullOrganization(), emptyMap())

    /**
     * Issue #331: derives the export inputs for one run-in (scope-composed)
     * generation attempt. The scope comes from the SAME canonical composition
     * seam the planner consumes (`composeScopeComposedOrganization`), so the
     * export scope and the run's target scope cannot diverge (spec 331 §1).
     * Candidate labels (user-authored free text) ride the tier control like
     * placed labels.
     */
    fun composeForExport(
        nowEpochMs: Long,
        selection: List<CandidateTarget.AppKey>,
        candidateLabels: Map<CandidateTarget.AppKey, String>,
    ): ExchangeInputResult = composeFrom(nowEpochMs, composer.composeScopeComposedOrganization(selection), candidateLabels)

    private fun composeFrom(
        nowEpochMs: Long,
        composition: OrganizationInputComposition,
        candidateLabels: Map<CandidateTarget.AppKey, String>,
    ): ExchangeInputResult = when (composition) {
        is OrganizationInputComposition.NotReady ->
            ExchangeInputResult.NotReady(composition.reason)

        is OrganizationInputComposition.Ready -> {
            val input = composition.input
            val candidateLabelEntries = input.targets.additions.mapNotNull { addition ->
                val target = addition.target as? CandidateTarget.AppKey ?: return@mapNotNull null
                candidateLabels[target]?.let { label ->
                    CandidatePlanningIds.planningId(target) to label
                }
            }
            ExchangeInputResult.ExportReady(
                ExportInputs(
                    snapshot = input.snapshot,
                    targets = input.targets,
                    // Issue #336: the single identity-bearing input feeds both
                    // layers — the builder redacts user-defined identities to
                    // the absent export category (built-in values keep the
                    // pre-336 bytes) while the session freshness digests
                    // consume the resolved CategoryIdentity itself.
                    resolvedIdentities = resolvedIdentitiesOf(input.signals.entries.map { it.item to it.candidate }),
                    userLabels = titleSource.read() + candidateLabelEntries,
                    signals = input.personalization,
                    usageKeysByItem = usageKeysOf(input),
                    nowEpochMs = nowEpochMs,
                ),
            )
        }
    }

    /** Derives the current structural inputs for import validation. */
    fun currentStructural(): ExchangeStructuralResult = when (val result = composeForExport(nowEpochMs = 0L)) {
        is ExchangeInputResult.NotReady -> ExchangeStructuralResult.NotReady(result.reason)

        is ExchangeInputResult.ExportReady -> ExchangeStructuralResult.Ready(
            CanonicalStructuralInputs(
                snapshot = result.inputs.snapshot,
                targets = result.inputs.targets,
                resolvedIdentities = result.inputs.resolvedIdentities,
            ),
        )
    }

    private fun resolvedIdentitiesOf(pairs: List<Pair<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?>>): Map<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?> = LinkedHashMap<ItemId, app.lawnchair.organizer.planning.CategoryIdentity?>(pairs.size).apply { pairs.forEach { (k, v) -> put(k, v) } }

    private fun usageKeysOf(
        input: app.lawnchair.organizer.planning.OrganizationInput,
    ): Map<ItemId, PersonalizationEntryKey> {
        val keys = LinkedHashMap<ItemId, PersonalizationEntryKey>()
        for (item in input.snapshot.items) {
            val key = when (val target = item.target) {
                is TargetKey.AppKey -> appKeyPackage(target.component)?.let {
                    PersonalizationEntryKey(item.profile, it)
                }

                is TargetKey.ShortcutKey -> PersonalizationEntryKey(item.profile, target.packageName)

                else -> null
            } ?: continue
            keys[item.id] = key
        }
        // Issue #331: selected candidates are installed apps and project usage
        // buckets like placed items; their planning IDs key the projection.
        for (addition in input.targets.additions) {
            val target = addition.target as? CandidateTarget.AppKey ?: continue
            val key = appKeyPackage(target.component)?.let { PersonalizationEntryKey(addition.profile, it) } ?: continue
            keys[CandidatePlanningIds.planningId(target)] = key
        }
        return keys
    }

    private fun appKeyPackage(component: app.lawnchair.organizer.planning.ComponentKey): PackageName? = component.value.substringBefore('/').takeIf { it.isNotBlank() }?.let(::PackageName)
}

/** Readiness of the canonical export inputs for one generation attempt. */
sealed interface ExchangeInputResult {
    data class ExportReady(val inputs: ExportInputs) : ExchangeInputResult

    data class NotReady(val reason: InputReadinessReason) : ExchangeInputResult
}

/** Readiness of the current structural inputs for import validation. */
sealed interface ExchangeStructuralResult {
    data class Ready(val structural: CanonicalStructuralInputs) : ExchangeStructuralResult

    data class NotReady(val reason: InputReadinessReason) : ExchangeStructuralResult
}

/** Source of item titles (user-authored free text) for label-inclusive exports. */
fun interface ExchangeItemTitleSource {
    fun read(): Map<ItemId, String>
}

/**
 * Production title source: one canonical capture, keyed by the persistent
 * item ids. Titles beyond the #204 free-text cap are dropped (not truncated)
 * so the builder's bounded-label invariant holds without inventing content.
 */
class LayoutWriterExchangeItemTitleSource(
    private val writer: LayoutWriterPort,
) : ExchangeItemTitleSource {

    override fun read(): Map<ItemId, String> = try {
        val state = writer.captureCurrent(CaptureId("exchange-titles")).layoutState
        val titles = LinkedHashMap<ItemId, String>()
        for (item in state.items) {
            val id = (item.ref as? ApplicationItemRef.PersistentItem)?.itemId ?: continue
            val title = (item.title as? OptionalText.Present)?.value ?: continue
            if (title.isNotEmpty() && title.length <= app.lawnchair.organizer.personalization.ContextExportContract.MAX_FREE_TEXT_CHARS) {
                titles[id] = title
            }
        }
        titles
    } catch (_: RuntimeException) {
        emptyMap()
    }
}
