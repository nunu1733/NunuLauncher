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
import app.lawnchair.organizer.planning.ItemId
import app.lawnchair.organizer.planning.PackageName
import app.lawnchair.organizer.planning.TargetKey

/**
 * Issue #205: the single canonical-input adapter shared by exchange export
 * generation and import-time reconstruction (spec 205 "canonical入力sourceの
 * 単一化"). Both sides derive their inputs from the same
 * `OrganizationInputComposer.composeFullOrganization` seam (the full-target
 * composition; #228 selection is a run-flow concept and never participates in
 * the exchange flow), so the structural trio, categories, and signal snapshot
 * can never drift between the two derivations.
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
    fun composeForExport(nowEpochMs: Long): ExchangeInputResult {
        return when (val composition = composer.composeFullOrganization()) {
            is OrganizationInputComposition.NotReady ->
                ExchangeInputResult.NotReady(composition.reason)

            is OrganizationInputComposition.Ready -> {
                val input = composition.input
                ExchangeInputResult.ExportReady(
                    ExportInputs(
                        snapshot = input.snapshot,
                        targets = input.targets,
                        resolvedCategories = resolvedCategoriesOf(input.signals.entries.map { it.item to it.candidate.value }),
                        userLabels = titleSource.read(),
                        signals = input.personalization,
                        usageKeysByItem = usageKeysOf(input),
                        nowEpochMs = nowEpochMs,
                    ),
                )
            }
        }
    }

    /** Derives the current structural inputs for import validation. */
    fun currentStructural(): ExchangeStructuralResult = when (val result = composeForExport(nowEpochMs = 0L)) {
        is ExchangeInputResult.NotReady -> ExchangeStructuralResult.NotReady(result.reason)

        is ExchangeInputResult.ExportReady -> ExchangeStructuralResult.Ready(
            CanonicalStructuralInputs(
                snapshot = result.inputs.snapshot,
                targets = result.inputs.targets,
                resolvedCategories = result.inputs.resolvedCategories,
            ),
        )
    }

    private fun resolvedCategoriesOf(pairs: List<Pair<ItemId, String?>>): Map<ItemId, String?> = LinkedHashMap<ItemId, String?>(pairs.size).apply { pairs.forEach { (k, v) -> put(k, v) } }

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
