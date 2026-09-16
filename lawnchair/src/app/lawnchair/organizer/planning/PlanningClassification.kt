package app.lawnchair.organizer.planning

internal data class ClassificationOutput(
    val decisions: Map<ItemId, CategoryDecision>,
    val warnings: List<Warning>,
)

internal object PlanningClassification {

    fun classify(
        classifiableIds: Set<ItemId>,
        signals: ClassificationSignals,
        catalog: ActiveCategoryCatalog,
    ): ClassificationOutput {
        val decisions = mutableMapOf<ItemId, CategoryDecision>()
        val warnings = mutableListOf<Warning>()

        val signalsByItem = signals.entries.groupBy { it.item }

        for (itemId in classifiableIds) {
            val itemSignals = signalsByItem[itemId].orEmpty()
            val decision = resolveDecision(itemId, itemSignals, catalog)
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
        catalog: ActiveCategoryCatalog,
    ): CategoryDecision {
        // Issue #336: membership is catalog-based, so an S1 override onto a
        // user-defined category resolves exactly like a built-in one while the
        // S1 > S2–S6 precedence and the built-in fallback stay unchanged.
        val bySource = itemSignals
            .filter { it.candidate in catalog.allowedIdentities }
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
            category = catalog.fallback,
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
