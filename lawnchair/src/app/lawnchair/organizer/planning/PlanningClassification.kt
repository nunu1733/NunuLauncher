package app.lawnchair.organizer.planning

internal data class ClassificationOutput(
    val decisions: Map<ItemId, CategoryDecision>,
    val warnings: List<Warning>,
)

internal object PlanningClassification {

    fun classify(
        classifiableIds: Set<ItemId>,
        signals: ClassificationSignals,
        taxonomy: TaxonomyContract,
    ): ClassificationOutput {
        val decisions = mutableMapOf<ItemId, CategoryDecision>()
        val warnings = mutableListOf<Warning>()

        val signalsByItem = signals.entries.groupBy { it.item }

        for (itemId in classifiableIds) {
            val itemSignals = signalsByItem[itemId].orEmpty()
            val decision = resolveDecision(itemId, itemSignals, taxonomy)
            decisions[itemId] = decision
            if (decision.decidedSignal == SignalSource.S6) {
                warnings += Warning(WarningCode.FALLBACK_CATEGORY, listOf(DiagnosticParam.ItemParam(itemId)))
            }
        }

        return ClassificationOutput(decisions, warnings)
    }

    private fun resolveDecision(
        itemId: ItemId,
        itemSignals: List<ClassificationSignal>,
        taxonomy: TaxonomyContract,
    ): CategoryDecision {
        val bySource = itemSignals
            .filter { it.candidate in taxonomy.allowedCategories }
            .groupBy { it.source }

        for (source in SignalSource.entries) {
            val entries = bySource[source] ?: continue
            val collapsed = entries.distinct()
            if (collapsed.isEmpty()) continue
            val bestCategory = collapsed.minOf { it.candidate }
            return CategoryDecision(
                item = itemId,
                category = bestCategory,
                decidedSignal = source,
                confidence = confidenceFor(source),
            )
        }

        return CategoryDecision(
            item = itemId,
            category = taxonomy.fallbackCategory,
            decidedSignal = SignalSource.S6,
            confidence = Confidence.FALLBACK,
        )
    }

    private fun confidenceFor(source: SignalSource): Confidence = when (source) {
        SignalSource.S1, SignalSource.S2 -> Confidence.EXPLICIT
        SignalSource.S3, SignalSource.S4 -> Confidence.RULE
        SignalSource.S5, SignalSource.S6 -> Confidence.FALLBACK
    }
}
