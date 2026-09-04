package app.lawnchair.organizer.planning

internal object PlanningResultCanonicalization {

    fun assemble(
        input: OrganizationInput,
        classification: ClassificationOutput,
        placement: PlacementOutput,
    ): PlanningResult {
        val allWarnings = (classification.warnings + placement.preservationWarnings)
            .distinct()
            .sortedWith(warningComparator)

        val categories = classification.decisions.values
            .sortedBy { it.item }

        return PlanningResult(
            revision = input.snapshot.revision,
            ruleVersion = input.rules.version,
            taxonomyVersion = input.taxonomy.version,
            organizationStrategy = input.rules.organizationStrategy,
            outcome = Planned(
                placements = placement.placements,
                newPages = placement.newPages,
                newFolders = placement.newFolders,
                categories = categories,
                warnings = allWarnings,
            ),
        )
    }

    fun invalidResult(
        input: OrganizationInput,
        reasons: List<RejectionReason>,
    ): PlanningResult = PlanningResult(
        revision = input.snapshot.revision,
        ruleVersion = input.rules.version,
        taxonomyVersion = input.taxonomy.version,
        organizationStrategy = input.rules.organizationStrategy,
        outcome = Rejected.Invalid(reasons = reasons, warnings = emptyList()),
    )

    fun impossibleResult(
        input: OrganizationInput,
        unplaced: List<UnplacedItem>,
    ): PlanningResult = PlanningResult(
        revision = input.snapshot.revision,
        ruleVersion = input.rules.version,
        taxonomyVersion = input.taxonomy.version,
        organizationStrategy = input.rules.organizationStrategy,
        outcome = Rejected.Impossible(unplaced = unplaced, warnings = emptyList()),
    )
}

internal val warningComparator: Comparator<Warning> = Comparator { a, b ->
    compareValues(a.code.ordinal, b.code.ordinal).takeIf { it != 0 }
        ?: compareDiagnosticParamLists(a.params, b.params)
}
