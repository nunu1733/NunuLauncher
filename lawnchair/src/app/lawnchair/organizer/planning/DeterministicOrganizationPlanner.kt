package app.lawnchair.organizer.planning

internal class DeterministicOrganizationPlanner(
    private val allocationFault: AllocationFault = AllocationFault.NONE,
) : OrganizationPlanner {

    override fun plan(input: OrganizationInput): PlanningResult {
        val validationResult = PlanningValidation.validate(input)
        return when (validationResult) {
            is ValidationResult.Invalid -> PlanningResultCanonicalization.invalidResult(
                input,
                validationResult.reasons,
            )

            is ValidationResult.Impossible -> PlanningResultCanonicalization.impossibleResult(
                input,
                validationResult.unplaced,
            )

            ValidationResult.Valid -> {
                val classifiableIds = (
                    input.snapshot.items
                        .filter { it.kind == ItemKind.APPLICATION || it.kind == ItemKind.DEEP_SHORTCUT }
                        .map { it.id } + input.targets.additions.map { it.id }
                    ).toSet()

                val classification = PlanningClassification.classify(
                    classifiableIds,
                    input.signals,
                    input.taxonomy,
                )

                // Spec 182: strategy dispatch goes strictly through the
                // internal registry; the selected definition executes the
                // placement. V-20 already rejected catalog-external ids.
                val definition = checkNotNull(LayoutStrategyRegistry.definition(input.rules.organizationStrategy))
                val placement = definition.place(input, classification, allocationFault)

                PlanningResultCanonicalization.assemble(input, classification, placement)
            }
        }
    }
}
