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

                val placement = PlanningPlacement.place(input, classification, allocationFault)

                PlanningResultCanonicalization.assemble(input, classification, placement)
            }
        }
    }
}
